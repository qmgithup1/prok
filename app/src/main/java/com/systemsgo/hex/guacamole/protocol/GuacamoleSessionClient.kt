package com.systemsgo.hex.guacamole.protocol

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.systemsgo.hex.audio.RemoteAudioManager
import com.systemsgo.hex.remote.RemoteFrameUpdate
import com.systemsgo.hex.remote.RemoteMouseButton
import com.systemsgo.hex.remote.RemoteSessionClient
import com.systemsgo.hex.remote.RemoteSessionState
import com.systemsgo.hex.remote.TerminalOutput
import com.systemsgo.hex.remote.clipboard.ClipboardCapableSession
import com.systemsgo.hex.remote.clipboard.ClipboardFormat
import com.systemsgo.hex.remote.clipboard.ClipboardPayload
import com.systemsgo.hex.remote.clipboard.ClipboardSyncManager
import com.systemsgo.hex.vnc.protocol.VncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GUACAMOLE-PROTOCOL FEATURE (Part 2/N).
 *
 * The [RemoteSessionClient] implementation for Guacamole — same role
 * [com.systemsgo.hex.vnc.protocol.VncClient] plays for VNC and
 * [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter] plays for RDP. Wires
 * together [GuacamoleTunnelClient] (transport), [GuacamoleDisplayRenderer]
 * (drawing-instruction interpretation), and this class's own input
 * translation (mouse/keyboard → `mouse`/`key` instructions).
 *
 * Deliberately protocol-agnostic about what's *behind* guacd (RDP, VNC,
 * SSH, Telnet, Kubernetes, or anything else the server exposes) — per
 * reg.txt's "must not hardcode protocols" requirement, this class never
 * branches on [GuacamoleTunnelConfig] beyond what the tunnel needs to open.
 * Whatever guacd is actually driving on the other end, this client only
 * ever sees the same drawing/input instruction stream.
 *
 * Because Guacamole always renders server-side to a bitmap — even for a
 * terminal protocol like SSH/Telnet through guacd — this always reports
 * through [frameUpdates], never [terminalOutput] (permanently empty),
 * unlike this app's own direct SSH/Telnet clients.
 */
class GuacamoleSessionClient(
    private val tunnelConfig: GuacamoleTunnelConfig,
    private val appContext: Context,
    // GUACAMOLE-PROTOCOL FEATURE (Part 3/N): unlike RDP/VNC, there's no
    // per-profile "enable clipboard" toggle for Guacamole yet (see
    // Components.kt's GUACAMOLE ProtocolOptionsSection branch) — defaults
    // to on, same as VncCredentials.enableClipboard's own default, until a
    // toggle exists to override it.
    private val enableClipboard: Boolean = true,
    // SESSION-RECORDING FEATURE: local recording of the server→client
    // instruction stream — see GuacamoleSessionRecorder's class doc for
    // exactly what this does and doesn't cover (a LOCAL recording, not
    // retrieval of a guacd-side one). Null = no recording.
    private val recordingFile: java.io.File? = null,
) : RemoteSessionClient, ClipboardCapableSession {

    private val recorder: GuacamoleSessionRecorder? = recordingFile?.let { GuacamoleSessionRecorder(it) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pumpJob: Job? = null

    // GUACAMOLE-PROTOCOL FEATURE (Part 6/N): see GuacamoleCertificateVerifier's
    // class doc — RdpSessionActivity already collects `certificateChallenge`
    // generically for any RemoteSessionClient, so this is the only wiring
    // needed for the existing untrusted-cert dialog to work for Guacamole too.
    //
    // SECURITY FIX (GUAC-TUNNEL-TOFU-SCOPE): this used to be built and passed
    // to GuacamoleTunnelClient unconditionally, regardless of
    // tunnelConfig.acceptSelfSignedCertificate. Since GuacamoleTunnelClient
    // only falls back to the platform's real CA trust store when its
    // certificateVerifier is null, every Guacamole tunnel connection —
    // including ones to a server with a perfectly valid CA-signed
    // certificate, on a profile that never opted into "accept self-signed
    // certificate" — was routed through the interactive TOFU flow instead:
    // pin-on-first-contact with no reference fingerprint to compare against,
    // rather than the actual chain-of-trust + hostname verification a normal
    // HTTPS client gets for free. Now only built when the profile actually
    // asked for that fallback, matching every other client in this app
    // (RestconfClient, RedfishClient, RdWebFeedClient, ...).
    private val certificateVerifier: GuacamoleCertificateVerifier? =
        if (tunnelConfig.acceptSelfSignedCertificate) GuacamoleCertificateVerifier(appContext) else null
    override val certificateChallenge: StateFlow<com.systemsgo.hex.remote.CertificateChallenge?> =
        certificateVerifier?.certificateChallenge
            ?: MutableStateFlow<com.systemsgo.hex.remote.CertificateChallenge?>(null).asStateFlow()

    private val tunnel = GuacamoleTunnelClient(tunnelConfig, certificateVerifier = certificateVerifier)
    // FILESYSTEM-BROWSING FEATURE: see GuacamoleDisplayRenderer's constructor doc — this is the one
    // instruction the renderer originates itself (the `ack` a `body` stream needs), routed through
    // the same tunnel every other outgoing instruction in this class uses.
    private val renderer = GuacamoleDisplayRenderer(tunnelConfig.width, tunnelConfig.height, outgoing = { tunnel.send(it) })

    // AUDIO-PLAYBACK FEATURE (Part 5/N): reuses the same RemoteAudioManager
    // RDP's rdpsnd path already drives — see that class's doc comment for
    // the full feature set (focus handling, route reporting, adaptive
    // quality, AV-sync). backendAvailable is always true here (unlike RDP,
    // decoding raw PCM into AudioTrack needs no native/compiled backend),
    // and there's no mic-capture equivalent in Guacamole's base protocol,
    // so captureRequested is always false.
    private val avSync = com.systemsgo.hex.audio.AvSyncCoordinator()
    private val audioManager = RemoteAudioManager(
        appContext = appContext,
        audioFrames = kotlinx.coroutines.flow.MutableSharedFlow<RemoteAudioManager.AudioFrame>(extraBufferCapacity = 64).also { flow ->
            scope.launch {
                renderer.audioFrames.collect { frame ->
                    flow.tryEmit(RemoteAudioManager.AudioFrame(frame.pcm, frame.sampleRate, frame.channels, bitsPerSample = 16))
                }
            }
        },
        channelState = kotlinx.coroutines.flow.MutableSharedFlow<RemoteAudioManager.AudioChannelState>(extraBufferCapacity = 4).also { flow ->
            scope.launch {
                renderer.audioChannelConnected.collect { connected ->
                    flow.tryEmit(RemoteAudioManager.AudioChannelState(playbackConnected = connected, captureConnected = false))
                }
            }
        },
        playbackRequested = tunnelConfig.audioMimetypes.isNotEmpty(),
        captureRequested = false,
        backendAvailable = true,
        latencyMsProvider = { latencyMs },
        avSync = avSync,
    )

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 64)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates

    // Guacamole is never a raw-text terminal protocol from this app's point of view — see class doc.
    override val terminalOutput: SharedFlow<TerminalOutput> = MutableSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val error: SharedFlow<String> = _error

    override var latencyMs: Long = 0
        private set

    // ── Clipboard sync (Part 3/N) ───────────────────────────────────────────
    // Guacamole's `clipboard` instruction opens a stream carrying arbitrary
    // mimetype data (blob/end, same generic stream machinery `img` uses —
    // see GuacamoleDisplayRenderer's class doc) in either direction. Only
    // PLAIN_TEXT is wired: guacd's own `clipboard` handling is itself just a
    // text/plain (occasionally text/html) passthrough to whatever's behind
    // it, so this matches VncClient's "only what the wire format actually
    // carries" reasoning rather than inventing richer support the protocol
    // doesn't have a clean path for.
    override val supportedClipboardFormats: Set<ClipboardFormat> = setOf(ClipboardFormat.PLAIN_TEXT)

    private val _remoteClipboardUpdates = MutableSharedFlow<ClipboardPayload>(extraBufferCapacity = 4)
    override val remoteClipboardUpdates: SharedFlow<ClipboardPayload> = _remoteClipboardUpdates.asSharedFlow()

    override fun sendClipboardPayload(payload: ClipboardPayload) {
        val text = (payload as? ClipboardPayload.Text)?.text ?: return
        val streamIndex = nextClientStreamIndex++
        tunnel.send(GuacamoleInstruction.of("clipboard", streamIndex.toString(), "text/plain"))
        // Guacamole blobs are base64-encoded chunks of the stream's payload — see
        // GuacamoleDisplayRenderer.opBlob's decode side for the wire format this mirrors.
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // Chunk conservatively — very large clipboard text in one `blob` risks exceeding
        // guacd/browser-proxy frame-size limits some deployments impose.
        encoded.chunked(4000).forEach { chunk ->
            tunnel.send(GuacamoleInstruction.of("blob", streamIndex.toString(), chunk))
        }
        tunnel.send(GuacamoleInstruction.of("end", streamIndex.toString()))
    }

    private var nextClientStreamIndex = 1 // 0 reserved informally for the very first stream a session might open; any distinct non-negative counter works equally well. Shared by clipboard uploads and file uploads below — see sendClipboardPayload/sendFile.
    private var clipboardSync: ClipboardSyncManager? = null

    // ── File transfer (Part 6/N) ────────────────────────────────────────────
    // See GuacamoleDisplayRenderer's class doc SCOPE note for exactly what
    // this does and doesn't cover (simple bounded file streams, not the
    // separate "filesystem" object/browsing protocol).
    private val _filesSaved = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Emits the display name of each server-initiated file download once it's been saved to the device's Downloads collection — not part of [RemoteSessionClient]; the hosting UI observes this directly (same "extra public member beyond the interface" shape [com.systemsgo.hex.audio.RemoteAudioManager] already uses for RDP's own session-specific state) to show a confirmation/snackbar. */
    val filesSaved: SharedFlow<String> = _filesSaved

    /** reg.txt's FILE TRANSFER → "Upload". Opens a client-initiated `file` stream and chunks [bytes] over it — same wire mechanics as [sendClipboardPayload], just a different opcode/mimetype. */
    fun sendFile(filename: String, mimetype: String, bytes: ByteArray) {
        val streamIndex = nextClientStreamIndex++
        tunnel.send(GuacamoleInstruction.of("file", streamIndex.toString(), mimetype, filename))
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        encoded.chunked(4000).forEach { chunk ->
            tunnel.send(GuacamoleInstruction.of("blob", streamIndex.toString(), chunk))
        }
        tunnel.send(GuacamoleInstruction.of("end", streamIndex.toString()))
    }

    /** reg.txt's FILE TRANSFER → "Download". Saves a completed server→client file stream to the device's public Downloads collection via [MediaStore] (no per-file user prompt — matches how a browser's default download behavior works, and avoids needing a SAF picker round-trip for every incoming file). */
    private fun saveReceivedFile(file: GuacamoleDisplayRenderer.GuacamoleReceivedFile) {
        try {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.filename)
                put(MediaStore.Downloads.MIME_TYPE, file.mimetype)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            }
            val uri = resolver.insert(collection, values) ?: run {
                _error.tryEmit("Could not save received file '${file.filename}'")
                return
            }
            resolver.openOutputStream(uri)?.use { it.write(file.bytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            _filesSaved.tryEmit(file.filename)
        } catch (e: Exception) {
            _error.tryEmit("Could not save received file '${file.filename}': ${e.message}")
        }
    }

    // ── Filesystem browsing (Part 7/N) ──────────────────────────────────────
    // See GuacamoleDisplayRenderer's class doc SCOPE note for the "filesystem"
    // object protocol this wraps — the interactive remote-drive browsing that
    // was the one item the FILE TRANSFER section left unbuilt (sendFile/
    // filesReceived above only cover the separate one-shot `file` stream).

    /** Filesystem "drives" (e.g. an RDP session's redirected local drives) guacd has offered this session — empty until/unless the backend actually exposes one. */
    val filesystems: StateFlow<List<GuacamoleDisplayRenderer.GuacamoleFilesystem>> = renderer.filesystems

    /**
     * Lists the immediate entries of [path] (default `"/"`, the drive root)
     * within [objectIndex]'s filesystem — [objectIndex] comes from
     * [filesystems]. Returns a `{name: mimetype}` map; an entry whose
     * mimetype equals `"application/vnd.glyptodon.guacamole.stream-index+json"`
     * is itself a subdirectory, browsable by calling this again with that
     * entry's name as the new [path] (see GuacamoleDisplayRenderer's SCOPE
     * note on why entry names are already full paths, not bare filenames).
     * Returns null on timeout or if the object/path doesn't exist.
     */
    suspend fun listDirectory(objectIndex: Int, path: String = "/"): Map<String, String>? {
        val deferred = renderer.registerPendingGet(objectIndex, path)
        tunnel.send(GuacamoleInstruction.of("get", objectIndex.toString(), path))
        val result = withTimeoutOrNull(15_000) { deferred.await() } ?: return null
        return (result as? GuacamoleDisplayRenderer.GuacamoleObjectStreamResult.Directory)?.entries
    }

    /** Downloads the full content of the file entry at [path] (as returned by [listDirectory]) within [objectIndex]'s filesystem, buffered entirely in memory. Returns null on timeout, or if [path] turned out to be a directory rather than a file. */
    suspend fun downloadObjectFile(objectIndex: Int, path: String): ByteArray? {
        val deferred = renderer.registerPendingGet(objectIndex, path)
        tunnel.send(GuacamoleInstruction.of("get", objectIndex.toString(), path))
        val result = withTimeoutOrNull(60_000) { deferred.await() } ?: return null
        return (result as? GuacamoleDisplayRenderer.GuacamoleObjectStreamResult.FileContent)?.bytes
    }

    /** Uploads [bytes] to [path] within [objectIndex]'s filesystem (the object-protocol `put`, e.g. saving into a browsed remote-drive directory) — distinct from [sendFile]'s ad-hoc single-stream upload, which isn't associated with any filesystem object. Returns true once the server acks success (status 0); false on timeout or a non-success ack. */
    suspend fun uploadObjectFile(objectIndex: Int, path: String, mimetype: String, bytes: ByteArray): Boolean {
        val streamIndex = nextClientStreamIndex++
        val deferred = renderer.registerPendingAck(streamIndex)
        tunnel.send(GuacamoleInstruction.of("put", objectIndex.toString(), streamIndex.toString(), mimetype, path))
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        encoded.chunked(4000).forEach { chunk -> tunnel.send(GuacamoleInstruction.of("blob", streamIndex.toString(), chunk)) }
        tunnel.send(GuacamoleInstruction.of("end", streamIndex.toString()))
        val ack = withTimeoutOrNull(30_000) { deferred.await() } ?: return false
        return ack.status == 0
    }

    private val _clipboardSyncState = MutableStateFlow<Boolean?>(null)
    override val clipboardSyncState: StateFlow<Boolean?> = _clipboardSyncState.asStateFlow()

    override fun setClipboardSyncEnabled(enabled: Boolean) {
        val sync = clipboardSync ?: return
        sync.setEnabled(enabled)
        _clipboardSyncState.value = enabled
    }

    /** Guacamole's `mouse` instruction is state-based (full button mask + position every time), not press/release deltas — see [sendMouseClick]'s doc. */
    private var buttonMask = 0
    private var lastMouseX = 0
    private var lastMouseY = 0

    private var syncSentAtMs: Long = 0

    override suspend fun connect(): Boolean {
        tunnel.connect()
        pumpJob = scope.launch {
            tunnel.instructions.collect { instruction ->
                recorder?.record(instruction)
                if (instruction.opcode == "sync") {
                    val now = System.currentTimeMillis()
                    if (syncSentAtMs != 0L) latencyMs = (now - syncSentAtMs).coerceAtLeast(0)
                    syncSentAtMs = now
                    renderer.drainDirtyFrame()?.let {
                        avSync.onVideoFrame()
                        _frameUpdates.tryEmit(it)
                    }
                } else {
                    renderer.apply(instruction)?.let { _error.tryEmit(it) }
                }
            }
        }
        scope.launch {
            tunnel.state.collect { state ->
                _sessionState.value = when (state) {
                    GuacamoleTunnelState.DISCONNECTED -> RemoteSessionState.DISCONNECTED
                    GuacamoleTunnelState.CONNECTING -> RemoteSessionState.CONNECTING
                    GuacamoleTunnelState.CONNECTED -> RemoteSessionState.CONNECTED
                    GuacamoleTunnelState.ERROR -> RemoteSessionState.ERROR
                }
            }
        }
        scope.launch {
            tunnel.lastError.collect { msg -> msg?.let { _error.tryEmit(it) } }
        }
        // CLIPBOARD-SYNC FEATURE (Part 3/N): server→client half — bridges
        // GuacamoleDisplayRenderer's decoded `clipboard` stream text into
        // this class's own ClipboardCapableSession surface, exactly like
        // VncClient.registerClipboardSync bridges RfbConnectable's
        // onServerCutText callback.
        scope.launch {
            renderer.clipboardReceived.collect { text -> _remoteClipboardUpdates.tryEmit(ClipboardPayload.Text(text)) }
        }
        // FILE-TRANSFER FEATURE (Part 6/N): server-initiated downloads — see saveReceivedFile's doc comment.
        scope.launch {
            renderer.filesReceived.collect { file -> saveReceivedFile(file) }
        }

        val resolved = withTimeoutOrNull(20_000) {
            tunnel.state.first { it != GuacamoleTunnelState.CONNECTING }
        }
        val connected = resolved == GuacamoleTunnelState.CONNECTED
        if (connected) {
            audioManager.start()
        }
        if (connected && enableClipboard) {
            clipboardSync = ClipboardSyncManager(appContext, this, scope).also { it.start() }
            _clipboardSyncState.value = true
        }
        return connected
    }

    override fun sendMouseMove(x: Int, y: Int) {
        lastMouseX = x; lastMouseY = y
        tunnel.send(GuacamoleInstruction.of("mouse", x.toString(), y.toString(), buttonMask.toString()))
    }

    /**
     * Guacamole has no separate press/release opcode — the client sends its
     * *entire* current button state (as a bitmask) plus position on every
     * change, and the server diffs it against what it last saw. See
     * https://guacamole.apache.org/doc/gug/guacamole-protocol.html#mouse-instruction.
     */
    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) {
        lastMouseX = x; lastMouseY = y
        val bit = when (button) {
            RemoteMouseButton.LEFT -> MASK_LEFT
            RemoteMouseButton.MIDDLE -> MASK_MIDDLE
            RemoteMouseButton.RIGHT -> MASK_RIGHT
        }
        buttonMask = if (down) buttonMask or bit else buttonMask and bit.inv()
        tunnel.send(GuacamoleInstruction.of("mouse", x.toString(), y.toString(), buttonMask.toString()))
    }

    /** Scroll has no dedicated instruction either — it's modeled as a momentary press+release of the "scroll up"/"scroll down" mouse-button bits. */
    override fun sendMouseScroll(x: Int, y: Int, delta: Int) {
        val bit = if (delta < 0) MASK_SCROLL_DOWN else MASK_SCROLL_UP
        tunnel.send(GuacamoleInstruction.of("mouse", x.toString(), y.toString(), (buttonMask or bit).toString()))
        tunnel.send(GuacamoleInstruction.of("mouse", x.toString(), y.toString(), buttonMask.toString()))
    }

    /**
     * [scanCode]/[extended] use the exact same PC-scancode convention every
     * other client in this app (RDP/VNC) already takes as input — so rather
     * than inventing a second scancode→keysym table, this reuses
     * [VncClient.scanCodeToKeysym] (VNC's RFB protocol also speaks X11
     * keysyms, same as Guacamole's `key` instruction), keeping exactly one
     * source of truth for that mapping. Its coverage gaps (documented on
     * that function) — some less-common extended/multimedia keys — are
     * this function's gaps too; closing them is a shared VNC+Guacamole
     * improvement, not something worth duplicating here.
     */
    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        val keysym = VncClient.scanCodeToKeysym(scanCode, extended) ?: return
        tunnel.send(GuacamoleInstruction.of("key", keysym.toString(), if (down) "1" else "0"))
    }

    override fun sendCtrlAltDel() {
        tunnel.send(GuacamoleInstruction.of("key", VncClient.XK_CONTROL_L.toString(), "1"))
        tunnel.send(GuacamoleInstruction.of("key", VncClient.XK_ALT_L.toString(), "1"))
        tunnel.send(GuacamoleInstruction.of("key", VncClient.XK_DELETE.toString(), "1"))
        tunnel.send(GuacamoleInstruction.of("key", VncClient.XK_DELETE.toString(), "0"))
        tunnel.send(GuacamoleInstruction.of("key", VncClient.XK_ALT_L.toString(), "0"))
        tunnel.send(GuacamoleInstruction.of("key", VncClient.XK_CONTROL_L.toString(), "0"))
    }

    /** No raw-text concept in Guacamole's protocol at the level this client operates — reg.txt's clipboard-paste UX belongs to Part 3/N's clipboard `pipe`/`clipboard` stream wiring instead. */
    override fun sendText(text: String) {
        // Intentional no-op — see doc comment above.
    }

    override fun resize(width: Int, height: Int) {
        tunnel.send(GuacamoleInstruction.of("size", width.toString(), height.toString()))
    }

    override fun disconnect() {
        pumpJob?.cancel()
        audioManager.stop()
        clipboardSync?.stop()
        clipboardSync = null
        _clipboardSyncState.value = null
        tunnel.disconnect()
        renderer.dispose()
        recorder?.close()
        // BUG FIX (SCOPE-LEAK): `scope` backs the audio-frame/audio-channel
        // forwarding coroutines launched in the audioManager initializer
        // above, plus every scope.launch{} call throughout this class — none
        // of that was ever cancelled here, unlike every sibling client in
        // this app (VncClient.disconnect(), SshClient, SpiceSessionClient,
        // RdpRemoteAdapter.disconnect(), NetconfClient, etc., all call
        // `<scope>.cancel()` on disconnect). The audio-forwarding collectors
        // in particular kept running indefinitely after disconnect() — they
        // only go idle (never terminate) once `renderer.dispose()` above
        // stops emitting, silently leaking the coroutines and, through their
        // captured references, this entire GuacamoleSessionClient instance
        // (including its renderer/audioManager) for the rest of the process
        // lifetime. The previous `scope.launch { /* let in-flight collectors
        // wind down */ }` line was a no-op that didn't actually cancel or
        // wind anything down; cancelling the scope is what actually does it.
        scope.cancel()
    }

    private companion object {
        const val MASK_LEFT = 1
        const val MASK_MIDDLE = 2
        const val MASK_RIGHT = 4
        const val MASK_SCROLL_UP = 8
        const val MASK_SCROLL_DOWN = 16
    }
}
