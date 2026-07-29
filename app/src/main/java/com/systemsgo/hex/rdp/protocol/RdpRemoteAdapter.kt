package com.systemsgo.hex.rdp.protocol

import android.content.Context
import com.systemsgo.hex.audio.RemoteAudioManager
import com.systemsgo.hex.print.RemotePrintManager
import com.systemsgo.hex.data.model.RdpCredentials
import com.systemsgo.hex.data.model.RdpPerformance
import com.systemsgo.hex.data.model.CodecPreference
import com.systemsgo.hex.session.RemoteAppWindowManager
import com.systemsgo.hex.display.MonitorSelection
import com.systemsgo.hex.display.RemoteMonitor
import com.systemsgo.hex.remote.*
import com.systemsgo.hex.remote.clipboard.ClipboardCapableSession
import com.systemsgo.hex.remote.clipboard.ClipboardFormat
import com.systemsgo.hex.remote.clipboard.ClipboardPayload
import com.systemsgo.hex.remote.clipboard.ClipboardSyncManager
import com.systemsgo.hex.rdp.native.AFreeRdpBridge
import com.systemsgo.hex.rdp.channels.RdpChannelPluginRegistry
import com.systemsgo.hex.rdp.transport.RdpTransportException
import com.systemsgo.hex.rdp.transport.RdpWebSocketTransport
import com.systemsgo.hex.security.openEncryptedPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Adapts RDP connectivity to the common [RemoteSessionClient] surface.
 *
 * Relies exclusively on the native **aFreeRDP** backend ([AFreeRdpBridge])
 * built via the NDK/CMake pipeline (see app/src/main/cpp/CMakeLists.txt and
 * the CI workflow). The pure-Kotlin hand-written RDP parser has been removed;
 * FreeRDP is the only supported backend.
 *
 * If the native `.so` has not been built yet (e.g. first-time local checkout
 * without running CI), [connect] will return false and emit an error message
 * directing the developer to build the native library.
 */
class RdpRemoteAdapter(
    private val credentials: RdpCredentials,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val performanceMode: Int = RdpPerformance.AUTO,    // FIX #8: was @Suppress("UNUSED_PARAMETER")
    private val colorDepth: Int = 32,                          // FIX #3: wired through from profile.colorDepth
    private val compressionQuality: Int = 75,                  // FIX #4: wired through from AppSettings
    // UDP-TRANSPORT FEATURE: wired through from AppSettings.udpTransportEnabled
    // (global, same as colorDepth/compressionQuality/performanceMode above —
    // see that field's doc comment for why this is a global dial rather than
    // a per-profile one). Forwarded to AFreeRdpBridge.connect()'s
    // enableUdpTransport, which is the one that actually does anything.
    private val enableUdpTransport: Boolean = false,
    // TLS-TOFU FIX: needed for the RDP certificate TOFU store (mirrors
    // SshClient/VncClient, which already take appContext for the same reason).
    private val appContext: Context,
) : RemoteSessionClient, ClipboardCapableSession {

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 8)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 1)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    override var latencyMs: Long = 0L
        private set

    private var nativeBridge: AFreeRdpBridge? = null
    // RDP-OVER-WEBSOCKET FEATURE: only non-null while credentials.transportMode
    // is WS/WSS — see the doc comment right before its use in connect() below.
    // Torn down in disconnect(), mirroring how nativeBridge itself is.
    private var wsTransport: RdpWebSocketTransport? = null
    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * GENERIC-VCHANNEL FEATURE (Plugin System): register [com.systemsgo.hex.
     * rdp.channels.RdpChannelPlugin]s here (any time — typically before
     * calling [connect]) to be notified when their channel connects/
     * disconnects. See [RdpChannelPluginRegistry]'s class doc; wired to the
     * live [AFreeRdpBridge] automatically inside [connect].
     */
    val channelPluginRegistry = RdpChannelPluginRegistry()

    // MULTI-MONITOR FEATURE: see RemoteSessionClient.monitors/selectMonitor
    // doc. Populated from AFreeRdpBridge.monitorLayout (native
    // onNativeMonitorLayout) once connected; a single-entry list here is
    // exactly what makes the toolbar's monitor selector hide itself, so no
    // extra "is multi-monitor supported" flag is needed on top of this.
    private val _monitors = MutableStateFlow<List<RemoteMonitor>>(emptyList())
    override val monitors: StateFlow<List<RemoteMonitor>> = _monitors.asStateFlow()
    private var appliedSavedMonitorPreference = false

    // LIVE-CHANNEL-STATUS FEATURE: see RemoteSessionClient.channelStatus doc.
    // Folds bridge.printerChannelState/audioChannelState (two independent hot
    // SharedFlows) into the one StateFlow snapshot the UI reads — mirrors how
    // _monitors above folds bridge.monitorLayout. Collected unconditionally
    // (not gated on credentials.enablePrinterRedirect/enableSound/
    // enableMicRedirect) since a flow the native side never emits on for a
    // disabled feature is already a no-op; gating here would just duplicate
    // that check for no benefit.
    private val _channelStatus = MutableStateFlow(RemoteChannelStatus())
    override val channelStatus: StateFlow<RemoteChannelStatus> = _channelStatus.asStateFlow()

    // MULTITOUCH FEATURE: see RemoteSessionClient.multiTouchSupported doc.
    // Folded from bridge.multiTouchChannelState the same way _channelStatus's
    // individual fields are folded from printer/audio/smartcard/webcam
    // channelState above — one current snapshot instead of a fire-and-forget
    // event stream.
    private val _multiTouchSupported = MutableStateFlow(false)
    override val multiTouchSupported: StateFlow<Boolean> = _multiTouchSupported.asStateFlow()

    // CODEC-NEGOTIATION FEATURE (part 3): see RemoteSessionClient.negotiatedCodec
    // doc. Folds bridge.negotiatedCodec (a replay=1 hot SharedFlow, null until
    // the first RDPGFX surface command arrives) into a StateFlow the same way
    // _channelStatus above folds the channel-state flows — one current
    // snapshot for the diagnostics UI instead of a fire-and-forget event
    // stream it would otherwise have to remember itself.
    private val _negotiatedCodec = MutableStateFlow<String?>(null)
    override val negotiatedCodec: StateFlow<String?> = _negotiatedCodec.asStateFlow()

    // XRDP-CAPABILITY-DETECTION FEATURE: mirrors _negotiatedCodec immediately
    // above — see RemoteSessionClient.negotiatedSecurityProtocol doc.
    private val _negotiatedSecurityProtocol = MutableStateFlow<String?>(null)
    override val negotiatedSecurityProtocol: StateFlow<String?> = _negotiatedSecurityProtocol.asStateFlow()

    // REMOTE-AUDIO FEATURE: created once bridge.init() has run (needs
    // bridge.audioFrames/audioChannelState to already exist), started once
    // the session reaches CONNECTED, stopped in disconnect(). See
    // RemoteAudioManager's doc (AUDIN-CAPTURE FIX note) for why it no longer
    // captures the microphone itself — FreeRDP's own OpenSL ES audin backend
    // does that directly, and this class only mirrors channel/state for the UI.
    private var audioManager: RemoteAudioManager? = null

    // AV-SYNC FEATURE: one shared clock per session — fed every incoming
    // bitmap update in bridge.frames.collect below, consulted by
    // audioManager before every PCM write. See AvSyncCoordinator's doc.
    private val avSync = com.systemsgo.hex.audio.AvSyncCoordinator()

    // PRINTER-REDIRECT FEATURE: created once bridge.init() has run (needs
    // bridge.printJobData/printerChannelState to already exist), started
    // once the session reaches CONNECTED, stopped in disconnect() — mirrors
    // audioManager's lifetime exactly. See RemotePrintManager's doc for what
    // "start" does given the current native-build gap
    // (AFreeRdpBridge.isPrinterBackendAvailable).
    private var printManager: RemotePrintManager? = null

    // REMOTEAPP-WINDOWS FEATURE: created once bridge.init() has run (needs
    // bridge.railWindowUpdates/railWindowRemovals to already exist) —
    // mirrors audioManager/printManager's lifetime exactly, but is exposed
    // publicly (read-only) since RdpSessionActivity needs to collect
    // [RemoteAppWindowManager.windows]/[RemoteAppWindowManager.displayMode]
    // directly to decide what to draw, unlike audioManager/printManager
    // which are purely internal to this adapter. Null until [connect] has
    // run once; stays valid (not recreated/cleared) across [disconnect] so
    // a reconnecting session's UI doesn't flash back to "no manager yet".
    var railWindowManager: RemoteAppWindowManager? = null
        private set

    // CLIPBOARD-SYNC FEATURE: bidirectional clipboard sync between the
    // Android system clipboard and the remote session's clipboard
    // (MS-RDPECLIP "cliprdr"). Loop prevention, duplicate detection, format
    // detection and per-connection enable/disable are all handled by the
    // shared ClipboardSyncManager (see [supportedClipboardFormats] /
    // [remoteClipboardUpdates] / [sendClipboardPayload] below for how this
    // class plugs into it) — this class only knows how to move a
    // ClipboardPayload on/off the cliprdr wire.
    private var clipboardSync: ClipboardSyncManager? = null

    // CLIPBOARD-SYNC FEATURE: the native aFreeRDP build behind AFreeRdpBridge
    // only implements CF_UNICODETEXT on the cliprdr channel today (see
    // AFreeRdpBridge.sendClipboardText / onNativeClipboardText and
    // systemsgo_jni.c's cliprdr callbacks) — no CF_HTML or CF_DIB support yet.
    // Declaring only PLAIN_TEXT here is what makes ClipboardSyncManager
    // gracefully downgrade HTML to its plain-text fallback and skip
    // images/files entirely instead of attempting a send this backend can't
    // honor. Extending this set is a native-layer change (mirroring the
    // existing text callbacks in systemsgo_jni.c for CF_HTML / CF_DIB), not a
    // Kotlin-side one.
    override val supportedClipboardFormats: Set<ClipboardFormat> = setOf(ClipboardFormat.PLAIN_TEXT)

    private val _remoteClipboardUpdates = MutableSharedFlow<ClipboardPayload>(extraBufferCapacity = 4)
    override val remoteClipboardUpdates: SharedFlow<ClipboardPayload> = _remoteClipboardUpdates.asSharedFlow()

    override fun sendClipboardPayload(payload: ClipboardPayload) {
        val text = (payload as? ClipboardPayload.Text)?.text ?: return
        nativeBridge?.sendClipboardText(text)
    }

    // TOOLBOX FEATURE (Stage 9): true the moment clipboardSync is created
    // below, null for the lifetime of a session where the profile disabled
    // clipboard redirection outright — see the doc comment on
    // RemoteSessionClient.clipboardSyncState for what each value means to
    // the Toolbox tool.
    private val _clipboardSyncState = MutableStateFlow<Boolean?>(null)
    override val clipboardSyncState: StateFlow<Boolean?> = _clipboardSyncState.asStateFlow()

    /**
     * CLIPBOARD-SYNC FEATURE: lets the UI toggle clipboard sync on/off for
     * this connection at runtime, independent of the profile-level default
     * ([RdpCredentials.enableClipboard]). A no-op if the profile disabled
     * clipboard sync outright (no [ClipboardSyncManager] was ever created).
     */
    override fun setClipboardSyncEnabled(enabled: Boolean) {
        val sync = clipboardSync ?: return
        sync.setEnabled(enabled)
        _clipboardSyncState.value = enabled
    }

    override suspend fun connect(): Boolean {
        if (!AFreeRdpBridge.isAvailable) {
            _error.emit(
                "Native FreeRDP library (libsystemsgo_jni.so) not found. " +
                "Build it by running the CI pipeline or following app/src/main/cpp/SETUP.md."
            )
            _sessionState.emit(RemoteSessionState.ERROR)
            return false
        }

        val bridge = AFreeRdpBridge().also { nativeBridge = it }
        bridge.init()
        // USB-REDIRECT FEATURE (Part 3/3): armed *before* bridge.connect()
        // so the post-connect-success firing (see AFreeRdpBridge.connect())
        // is never missed. UsbRedirectionManager.onSessionHandleChanged is
        // itself a no-op no matter what thread this fires on (see its doc
        // comment) — announceSessionHandle's synchronized block is what
        // guarantees this can't race a concurrent device attach/detach.
        bridge.onUsbSessionHandleChanged = { handle ->
            com.systemsgo.hex.usb.UsbRedirectionManager.instanceOrNull?.onSessionHandleChanged(handle)
        }
        // TLS-TOFU FIX: must be set before bridge.connect() below — the TLS
        // handshake (and therefore a possible call into this verifier) can
        // happen synchronously inside that call.
        bridge.certificateVerifier = { host, port, commonName, issuer, fingerprint ->
            verifyServerCertificate(host, port, commonName, issuer, fingerprint)
        }

        // GENERIC-VCHANNEL FEATURE: wire whatever RdpChannelPlugins the
        // caller already [register]ed on channelPluginRegistry (before
        // connect() was called — see RdpChannelPluginRegistry's class doc)
        // to this fresh bridge instance, so they start receiving
        // onChannelConnected/onChannelDisconnected for this session.
        channelPluginRegistry.wire(bridge, adapterScope)

        // REMOTEAPP-WINDOWS FEATURE: created unconditionally (not just when
        // credentials.remoteAppEnabled is true) so RdpSessionActivity can
        // always collect railWindowManager!!.windows/displayMode without a
        // null-check dance depending on protocol/profile — for a non-
        // RemoteApp session bridge.railWindowUpdates simply never emits, so
        // `windows` just stays empty forever, which the UI already needs to
        // handle anyway (native "rail" channel not wired yet — see
        // RemoteAppWindowManager's class doc).
        railWindowManager = RemoteAppWindowManager(
            bridge = bridge,
            initialDisplayMode = credentials.remoteAppDisplayMode,
            scope = adapterScope,
        )

        adapterScope.launch {
            bridge.frames.collect { f ->
                // AV-SYNC FEATURE: every bitmap update counts as a "video frame" for
                // cadence-tracking purposes, same as RemoteAudioManager consulting this
                // coordinator before each PCM write — see AvSyncCoordinator's doc.
                avSync.onVideoFrame()
                _frameUpdates.emit(
                    RemoteFrameUpdate(f.x, f.y, f.width, f.height, f.pixels, f.fullScreen)
                )
            }
        }
        adapterScope.launch {
            bridge.errors.collect { msg -> _error.emit(msg) }
        }
        // FIX #2: Subscribe to stateChanges from the native bridge so that
        // autoReconnect on DISCONNECTED actually fires for RDP sessions.
        // Native state codes: 0 = disconnected, 1 = connecting, 2 = connected,
        // 3 = auth_failed (CRIT-NEW-1 FIX: added to stop reconnect on wrong password).
        adapterScope.launch {
            bridge.stateChanges.collect { nativeState ->
                when (nativeState) {
                    0 -> _sessionState.emit(RemoteSessionState.DISCONNECTED)
                    1 -> _sessionState.emit(RemoteSessionState.CONNECTING)
                    2 -> {
                        _sessionState.emit(RemoteSessionState.CONNECTED)
                        // REMOTE-AUDIO FEATURE: start playback/capture collection
                        // only once the session is actually up — mirrors why the
                        // clipboard sync manager above is also only meaningful
                        // post-connect (nothing to sync/play before then).
                        audioManager?.start()
                        // PRINTER-REDIRECT FEATURE: mirrors audioManager?.start()
                        // immediately above — nothing to spool/print before the
                        // session is actually up.
                        printManager?.start()
                    }
                    // CRIT-NEW-1 FIX: native code emits state=3 when FreeRDP returns a
                    // credential-specific error (ERRCONNECT_LOGON_FAILURE and similar).
                    // Without this mapping, auth failures arrive as ERROR, triggering
                    // the auto-reconnect loop and retrying 3 times with the same wrong
                    // password — potentially locking the user's account on the server.
                    3 -> _sessionState.emit(RemoteSessionState.AUTH_FAILED)
                }
            }
        }

        // MULTI-MONITOR FEATURE: mirrors the frames/errors/stateChanges
        // collectors above — turns native's parallel-array callback into the
        // RemoteMonitor list the toolbar/session UI consumes, and applies
        // this profile's saved preference (RdpCredentials.preferredMonitorId)
        // the first time a real (>1 monitor) layout arrives, so a saved
        // connection reopens on whichever monitor the user picked last time.
        adapterScope.launch {
            bridge.monitorLayout.collect { list ->
                val mapped = list.map {
                    RemoteMonitor(
                        id = it.id, x = it.x, y = it.y, width = it.width, height = it.height,
                        isPrimary = it.isPrimary, orientationDegrees = it.orientationDegrees,
                        dpiScaleFactor = it.dpiScaleFactor,
                    )
                }
                _monitors.value = mapped
                if (!appliedSavedMonitorPreference && mapped.size > 1) {
                    appliedSavedMonitorPreference = true
                    val saved = MonitorSelection.fromStoredId(credentials.preferredMonitorId)
                    if (saved != MonitorSelection.All) {
                        selectMonitor(saved)
                    }
                }
            }
        }

        // CODEC-NEGOTIATION FEATURE (part 3): mirrors the monitorLayout
        // collector immediately above — turns the native replay(1) hot flow
        // into the StateFlow the diagnostics UI reads. bridge.negotiatedCodec
        // only starts emitting once the first RDPGFX_SURFACE_COMMAND arrives
        // (see AFreeRdpBridge.negotiatedCodec's doc comment), so
        // _negotiatedCodec correctly stays null both before that and for the
        // whole lifetime of a session that never leaves the classic
        // (non-GFX) path.
        adapterScope.launch {
            bridge.negotiatedCodec.collect { name ->
                _negotiatedCodec.value = name
            }
        }

        // XRDP-CAPABILITY-DETECTION FEATURE: mirrors the negotiatedCodec
        // collector immediately above. bridge.negotiatedSecurityProtocol
        // emits exactly once per successful connection (from
        // systemsgo_post_connect — security negotiation happens once, before
        // PostConnect, so this can't change again mid-session the way the
        // codec can).
        adapterScope.launch {
            bridge.negotiatedSecurityProtocol.collect { name ->
                _negotiatedSecurityProtocol.value = name
            }
        }

        // LIVE-CHANNEL-STATUS FEATURE: mirrors the monitorLayout collector
        // immediately above — two more subscribers attached to bridge hot
        // flows from the moment the connection begins, each updating just
        // its own field of _channelStatus so the printer and audio channels
        // (which connect/disconnect independently of each other) never clobber
        // one another's last-known state.
        adapterScope.launch {
            bridge.printerChannelState.collect { connected ->
                _channelStatus.value = _channelStatus.value.copy(printerConnected = connected)
            }
        }
        adapterScope.launch {
            bridge.audioChannelState.collect { evt ->
                _channelStatus.value = _channelStatus.value.copy(
                    audioPlaybackConnected = evt.playbackConnected,
                    audioCaptureConnected = evt.captureConnected,
                )
            }
        }
        adapterScope.launch {
            bridge.smartcardChannelState.collect { connected ->
                _channelStatus.value = _channelStatus.value.copy(smartcardConnected = connected)
            }
        }
        adapterScope.launch {
            bridge.webcamChannelState.collect { connected ->
                _channelStatus.value = _channelStatus.value.copy(webcamConnected = connected)
            }
        }
        adapterScope.launch {
            bridge.multiTouchChannelState.collect { connected ->
                _multiTouchSupported.value = connected
            }
        }

        // REMOTE-AUDIO FEATURE: see RemoteAudioManager's doc. Created here
        // (rather than lazily) so audioChannelState/audioFrames — both hot
        // SharedFlows on the bridge — have a subscriber attached from the
        // moment the connection begins, matching the pattern already used
        // for bridge.frames/bridge.errors above.
        audioManager = RemoteAudioManager(
            appContext = appContext,
            audioFrames = bridge.audioFrames.let { flow ->
                // Adapts AFreeRdpBridge.NativeAudioFrame to RemoteAudioManager.AudioFrame
                // so the two modules don't need to depend on each other's types directly.
                kotlinx.coroutines.flow.MutableSharedFlow<RemoteAudioManager.AudioFrame>(extraBufferCapacity = 16).also { out ->
                    adapterScope.launch {
                        flow.collect { f -> out.tryEmit(RemoteAudioManager.AudioFrame(f.pcm, f.sampleRate, f.channels, f.bitsPerSample)) }
                    }
                }
            },
            channelState = bridge.audioChannelState.let { flow ->
                kotlinx.coroutines.flow.MutableSharedFlow<RemoteAudioManager.AudioChannelState>(extraBufferCapacity = 4, replay = 1).also { out ->
                    adapterScope.launch {
                        flow.collect { e -> out.tryEmit(RemoteAudioManager.AudioChannelState(e.playbackConnected, e.captureConnected)) }
                    }
                }
            },
            playbackRequested = credentials.enableSound,
            captureRequested = credentials.enableMicRedirect,
            backendAvailable = AFreeRdpBridge.isAudioBackendAvailable,
            latencyMsProvider = { latencyMs },
            avSync = avSync,
        )

        // PRINTER-REDIRECT FEATURE: mirrors the audioManager wiring
        // immediately above — attaches a subscriber to
        // bridge.printJobData/printerChannelState (both hot SharedFlows)
        // from the moment the connection begins.
        printManager = RemotePrintManager(
            appContext = appContext,
            printJobData = bridge.printJobData,
            channelState = bridge.printerChannelState,
            redirectRequested = credentials.enablePrinterRedirect,
            backendAvailable = AFreeRdpBridge.isPrinterBackendAvailable,
        )

        // CLIPBOARD-SYNC FEATURE: only wire up sync when the profile actually
        // enabled it — keeps disabled behavior identical to before (no
        // ClipboardManager listener registered, nothing sent or received).
        if (credentials.enableClipboard) {
            adapterScope.launch {
                bridge.clipboardTextFromRemote.collect { text ->
                    _remoteClipboardUpdates.emit(ClipboardPayload.Text(text))
                }
            }
            clipboardSync = ClipboardSyncManager(appContext, this, adapterScope).also { it.start() }
            // TOOLBOX FEATURE (Stage 9): ClipboardSyncManager's initiallyEnabled
            // default is true, so the tool starts tinted "on" the moment this
            // session becomes clipboard-capable, matching the manager's real state.
            _clipboardSyncState.value = true
        }

        _sessionState.value = RemoteSessionState.CONNECTING

        // RDP-OVER-WEBSOCKET FEATURE: requirement #12 in practice — no
        // duplication of the RDP pipeline below. When credentials.transportMode
        // is WS/WSS, stand up RdpWebSocketTransport's loopback bridge first and
        // hand AFreeRdpBridge a plain 127.0.0.1:<ephemeral> to connect to
        // instead of the real host/port; FreeRDP still does its own TLS/NLA/
        // credential negotiation exactly as it does for a direct TCP profile,
        // just against that loopback socket. See RdpWebSocketTransport's class
        // doc comment for why this needs no systemsgo_jni.c/native change at all.
        // For a TCP profile (the existing behavior — requirement #13) this
        // block is skipped entirely and effectiveHost/effectivePort are just
        // credentials.host/credentials.port, byte-for-byte as before.
        val effectiveTarget: Pair<String, Int> = if (credentials.transportMode.isWebSocket) {
            val transport = RdpWebSocketTransport(credentials.webSocketConfig, credentials.transportMode, appContext = appContext)
            wsTransport = transport

            val ready = CompletableDeferred<Pair<String, Int>>()
            transport.start(object : RdpWebSocketTransport.Listener {
                override fun onBridgeReady(localHost: String, localPort: Int) {
                    ready.complete(localHost to localPort)
                }

                override fun onTransportConnected() {
                    // Informational only today — the loopback bridge is
                    // already relaying bytes by the time this fires, and
                    // sessionState is driven by AFreeRdpBridge's own
                    // stateChanges (collected below) once FreeRDP connects
                    // to that bridge.
                }

                override fun onTransportFailed(error: RdpTransportException) {
                    // Only surface this directly when it happens *after* the
                    // initial connect already succeeded (mid-session drop that
                    // exhausted RdpWebSocketConfig.autoReconnect) — the initial-
                    // connect failure path is instead handled by the
                    // `ready.await()` timeout/exception below, via the same
                    // ok=false cleanup every other connect() failure uses, so
                    // it isn't reported twice.
                    if (ready.isCompleted) {
                        adapterScope.launch {
                            _error.emit(RdpErrorMessages.forWebSocketTransport(error))
                            _sessionState.emit(RemoteSessionState.ERROR)
                        }
                    } else {
                        ready.completeExceptionally(error)
                    }
                }

                override fun onTransportClosed() {
                    adapterScope.launch { disconnect() }
                }
            })

            try {
                withTimeout(credentials.webSocketConfig.connectTimeoutMs + 2_000) { ready.await() }
            } catch (e: Exception) {
                _error.emit(RdpErrorMessages.forWebSocketTransport(
                    e as? RdpTransportException
                        ?: RdpTransportException.ConnectionTimeout(
                            credentials.webSocketConfig.resolvedUrl(credentials.transportMode)
                        )
                ))
                _sessionState.value = RemoteSessionState.ERROR
                // Same cleanup shape as the "if (!ok)" branch further below —
                // this is, in effect, a connect() failure that happened before
                // bridge.connect() was even reached.
                adapterScope.cancel()
                wsTransport?.close()
                wsTransport = null
                nativeBridge?.free()
                nativeBridge = null
                unregisterClipboardListener()
                audioManager?.stop()
                audioManager = null
                printManager?.stop()
                printManager = null
                return false
            }
        } else {
            credentials.host to credentials.port
        }
        val (effectiveHost, effectivePort) = effectiveTarget

        // LATENCY-EFFECTIVE FIX: mirrors VncClient/SshClient's connectStart pattern —
        // this class declared `latencyMs` (read by RemoteAudioManager's
        // latencyMsProvider for adaptive audio quality, and by RdpSessionInfo for the
        // UI) but, unlike those two sibling protocols, never actually assigned it
        // anywhere. It stayed permanently 0, so every latency-driven consumer saw
        // "0ms" regardless of real conditions — audio quality adaptation always
        // resolved to HIGH and the UI latency readout was always wrong.
        val connectStart = System.currentTimeMillis()
        // JNI-OOM-CLEANUP FIX: nativeConnect() allocates non-trivial native
        // memory (the FreeRDP context, its frame/codec buffers, etc.) before
        // this call can return. If the JVM is under enough memory pressure to
        // throw OutOfMemoryError here, it previously propagated straight out
        // of connect() uncaught — skipping the `if (!ok)` cleanup below
        // entirely, so nativeBridge.free() (→ nativeFree(handle), which
        // releases the native FreeRDP context) was never called. That's a
        // native-heap leak at exactly the moment memory is already critically
        // short. Catching it here and falling through to the existing
        // cleanup path (same one the ordinary "server refused" failure uses)
        // ensures the native handle is always freed, OOM or not.
        val ok = try {
            withContext(Dispatchers.IO) {
                bridge.connect(
                    host             = effectiveHost,
                    port             = effectivePort,
                    username         = credentials.username,
                    password         = credentials.password,
                    domain           = credentials.domain,
                    width            = displayWidth,
                    height           = displayHeight,
                    useNla           = credentials.useNla,
                    gatewayEnabled   = credentials.gatewayEnabled,
                    gatewayHost      = credentials.gatewayHost,
                    gatewayPort      = credentials.gatewayPort,
                    gatewayUsername  = credentials.gatewayUsername,
                    gatewayPassword  = credentials.gatewayPassword,
                    gatewayDomain    = credentials.gatewayDomain,
                    // ENTRA-ID-AUTH FEATURE: see AFreeRdpBridge.connect()'s
                    // gatewayAuthMode/gatewayBearerToken doc comment for
                    // current Part 1/Part 2 status.
                    gatewayAuthMode     = credentials.gatewayAuthMode.toBridgeGatewayAuthMode(),
                    gatewayBearerToken  = credentials.gatewayBearerToken,
                    proxyEnabled     = credentials.proxyEnabled,
                    proxyType        = credentials.proxyType.toBridgeProxyType(),
                    proxyHost        = credentials.proxyHost,
                    proxyPort        = credentials.proxyPort,
                    proxyUsername    = credentials.proxyUsername,
                    proxyPassword    = credentials.proxyPassword,
                    remoteAppEnabled    = credentials.remoteAppEnabled,
                    remoteAppProgram    = credentials.remoteAppProgram,
                    remoteAppWorkingDir = credentials.remoteAppWorkingDir,
                    remoteAppCmdLine    = credentials.remoteAppCmdLine,
                    colorDepth       = colorDepth,          // FIX #3
                    compressionQuality = compressionQuality, // FIX #4
                    performanceMode  = performanceMode,     // FIX #8
                    ignoreCert       = credentials.acceptSelfSignedCertificate,  // BUG-3 FIX: was always using default (false)
                    enableUdpTransport = enableUdpTransport,  // UDP-TRANSPORT FEATURE
                    // MIC-REDIRECT FIX: forward audio playback/capture flags to the
                    // native layer — see the doc comment on RdpCredentials.enableSound.
                    enableSound      = credentials.enableSound,
                    enableMicRedirect = credentials.enableMicRedirect,
                    // CLIPBOARD FIX: forward the clipboard-redirection flag to the
                    // native layer — see the doc comment on
                    // RdpCredentials.enableClipboard.
                    enableClipboard  = credentials.enableClipboard,
                    // DRIVE-REDIRECT FIX: forward the drive-redirection flag to the
                    // native layer — see the doc comment on
                    // RdpCredentials.enableDriveRedirect. The path is this app's
                    // own external files directory (no extra storage permission
                    // needed, unlike arbitrary shared-storage access) — the
                    // remote session sees it as a single "android" drive letter.
                    // Falls back to internal files dir if external storage is
                    // unavailable (e.g. removed SD card), matching the same
                    // fail-safe pattern used elsewhere in this file.
                    enableDriveRedirect = credentials.enableDriveRedirect,
                    drivePath        = (appContext.getExternalFilesDir(null) ?: appContext.filesDir).absolutePath,
                    // PRINTER-REDIRECT FIX: forward the printer-redirection flag to
                    // the native layer — see the doc comment on
                    // RdpCredentials.enablePrinterRedirect.
                    enablePrinterRedirect = credentials.enablePrinterRedirect,
                    // WEBCAM-REDIRECT FIX: forward the webcam-redirection flag to
                    // the native layer — see the doc comment on
                    // RdpCredentials.enableWebcamRedirect.
                    enableWebcamRedirect = credentials.enableWebcamRedirect,
                    // SMARTCARD-REDIRECT FIX: forward the smartcard-redirection flag
                    // to the native layer — see the doc comment on
                    // RdpCredentials.enableSmartcardRedirect.
                    enableSmartcardRedirect = credentials.enableSmartcardRedirect,
                    // PARALLEL-REDIRECT FIX: forward the parallel-port-redirection
                    // flag and device path to the native layer — see the doc
                    // comment on RdpCredentials.enableParallelRedirect. Unlike
                    // drivePath above, there is no app-owned fallback path here:
                    // this is whatever local device node the user entered for
                    // this profile (e.g. a USB-OTG adapter's /dev entry).
                    enableParallelRedirect = credentials.enableParallelRedirect,
                    parallelPath     = credentials.parallelPortPath,
                    // SERIAL-REDIRECT FIX: forward the serial-port-redirection
                    // flag and device path to the native layer — see the doc
                    // comment on RdpCredentials.enableSerialRedirect.
                    enableSerialRedirect = credentials.enableSerialRedirect,
                    serialPath       = credentials.serialPortPath,
                    // SERIAL-OVER-NETWORK FIX: forward the redirect mode and
                    // network endpoint alongside the local device path above.
                    // AFreeRdpBridge.connect() resolves the *effective*
                    // serial path from these (via SerialNetworkBridge when
                    // mode != LOCAL_DEVICE) before it ever reaches
                    // systemsgo_jni.c — see AFreeRdpBridge.resolveSerialPath()
                    // and com.systemsgo.hex.rdp.serial.SerialNetworkBridge's
                    // class doc for what's implemented vs. still pending.
                    serialRedirectMode = credentials.serialRedirectMode,
                    serialNetworkHost = credentials.serialNetworkHost,
                    serialNetworkPort = credentials.serialNetworkPort,
                    // CODEC-NEGOTIATION FEATURE: translate the pure-Kotlin
                    // data.model.CodecPreference this adapter was given into
                    // AFreeRdpBridge.CodecPreference — see toBridgeCodecPreference()
                    // below for why this mapping is by-name rather than by-ordinal
                    // (both enums declare the same four values in the same order
                    // today, but a by-name lookup fails safe to AUTO instead of
                    // silently picking the wrong entry if that ever drifts).
                    codecPreference = credentials.codecPreference.toBridgeCodecPreference(),
                )
            }
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("RdpRemoteAdapter",
                "OutOfMemoryError during native RDP connect — freeing native handle", e)
            _error.emit("Not enough memory to start this session. Close other apps and try again.")
            false
        }
        // BUG-D FIX: bridge.stateChanges already emits CONNECTED (native state 2).
        // Emitting it again causes duplicate state transitions → double log entries.
        // Only handle the ERROR case that stateChanges does not emit on bridge.connect() failure.
        if (ok) {
            // LATENCY-EFFECTIVE FIX: same measurement point as VncClient/SshClient —
            // taken right after the blocking handshake succeeds, before CONNECTED is
            // observed by any latency-consuming code above.
            latencyMs = System.currentTimeMillis() - connectStart
        }
        if (!ok) {
            _sessionState.value = RemoteSessionState.ERROR
            // BUG-Y2 FIX: the 3 coroutines launched above (frames / errors / stateChanges
            // collectors) remain active when bridge.connect() returns false, because adapterScope
            // is only cancelled in disconnect(). If connect() fails, disconnect() is never called
            // by the caller → the 3 coroutines collect indefinitely from a failed bridge →
            // coroutine leak + silent resource waste. Cancel here to match the successful-path
            // cleanup already done in disconnect().
            adapterScope.cancel()
            nativeBridge?.free()
            nativeBridge = null
            unregisterClipboardListener()
            audioManager?.stop()
            audioManager = null
            printManager?.stop()
            printManager = null
        }
        return ok
    }

    // CLIPBOARD-SYNC FEATURE: mirrors the pattern already used for the
    // TLS-TOFU/other per-connection state above — undoes what the
    // enableClipboard branch in connect() set up, on both the
    // failed-connect and normal disconnect paths.
    private fun unregisterClipboardListener() {
        clipboardSync?.stop()
        clipboardSync = null
        // TOOLBOX FEATURE (Stage 9): back to "unsupported/not started" so a
        // stale "on" tint can never survive into a disconnected/failed state.
        _clipboardSyncState.value = null
    }

    // ── TLS-TOFU FIX: RDP certificate trust-on-first-use ────────────────────
    //
    // Mirrors SshClient.TofuHostKeyRepository and VncClient.VncTofuVerifier:
    // the first certificate seen for a given host:port is trusted (only when
    // the profile has opted in via acceptSelfSignedCertificate — see below)
    // and its fingerprint pinned in an encrypted preference store; any later
    // connection presenting a *different* fingerprint for the same host:port
    // is refused and flagged as a possible MITM instead of silently
    // re-accepted, which is what the previous FreeRDP_IgnoreCertificate-only
    // implementation did every single time.
    //
    // Unlike VncClient (which has to open a separate probe socket because
    // bVNC hides the server certificate from its public API), RDP gets the
    // real, already-handshaked certificate's fields directly from FreeRDP's
    // VerifyCertificateEx callback (see systemsgo_jni.c) — no extra probe needed.
    private val certPrefs by lazy { appContext.openEncryptedPrefs(PREFS_TOFU_RDP) }

    // UNTRUSTED-CERT DIALOG FEATURE: replaces the old per-profile "Accept
    // self-signed certificate" toggle. That toggle decided trust ahead of
    // time and silently pinned whatever certificate showed up on first
    // connect — the user never actually saw what they were trusting. Now,
    // an unrecognized certificate blocks the (background) connect thread and
    // surfaces a [CertificateChallenge] here; the UI shows a dialog with the
    // real certificate details and the user decides, the same way a browser
    // handles an untrusted HTTPS certificate.
    private val _certificateChallenge = MutableStateFlow<CertificateChallenge?>(null)
    override val certificateChallenge: StateFlow<CertificateChallenge?> = _certificateChallenge.asStateFlow()

    /**
     * Called synchronously (via [AFreeRdpBridge.certificateVerifier]) from the
     * native TLS layer while [connect] is still running, on a background
     * (Dispatchers.IO) thread — never the main thread. Must not suspend, but
     * *may* block synchronously: when the certificate is unrecognized this
     * blocks on [CertificateChallenge.awaitDecision] until the UI (running on
     * the main thread, unaffected by this block) resolves it.
     */
    private fun verifyServerCertificate(
        host: String, port: Int, commonName: String, issuer: String, fingerprint: String,
    ): Boolean {
        val key = "$host:$port"
        val stored = certPrefs.getString(key, null)

        if (stored != null) {
            if (stored == fingerprint) return true
            // Pinned fingerprint changed since last time — always a hard
            // reject, never re-prompted automatically: this is the MITM-
            // suspicious case, and the existing "clear trusted certificate"
            // flow (ServerTrustSection) is the deliberate, explicit way to
            // move past it, not a dialog tap.
            val warning = "RDP server identity changed for $host:$port — connection " +
                "refused (possible MITM attack). If the certificate was legitimately " +
                "renewed, remove this profile's trusted certificate (or the profile " +
                "itself) and reconnect."
            _error.tryEmit(warning)
            return false
        }

        // No pinned fingerprint yet: ask the user, blocking this background
        // thread until they respond.
        val challenge = CertificateChallenge(host, port, commonName, issuer, fingerprint)
        _certificateChallenge.value = challenge
        val decision = challenge.awaitDecision()
        _certificateChallenge.value = null

        return when (decision) {
            CertificateChallenge.Decision.REJECT -> {
                _error.tryEmit(
                    "TLS_UNTRUSTED_CERTIFICATE: connection to $host:$port cancelled — " +
                        "the certificate (CN=$commonName, issuer=$issuer) was not trusted."
                )
                false
            }
            CertificateChallenge.Decision.ACCEPT_ONCE -> true
            CertificateChallenge.Decision.ACCEPT_ALWAYS -> {
                // LIVE-MED-3 / LIVE-HIGH-1 style FIX (mirrors VncClient): commit()
                // synchronously rather than apply(), so the pinned fingerprint
                // cannot be lost to a process death between "accepted" and "saved".
                certPrefs.edit().putString(key, fingerprint).commit()
                true
            }
        }
    }

    override fun sendMouseMove(x: Int, y: Int) {
        nativeBridge?.sendMouse(x, y, MOUSE_FLAG_MOVE)
    }

    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) {
        nativeBridge?.sendMouse(x, y, mouseClickFlags(button, down))
    }

    override fun sendMouseScroll(x: Int, y: Int, delta: Int) {
        nativeBridge?.sendMouse(
            x, y,
            if (delta > 0) MOUSE_FLAG_WHEEL_UP else MOUSE_FLAG_WHEEL_DOWN
        )
    }

    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        nativeBridge?.sendKey(scanCode, down, extended)
    }

    override fun sendCtrlAltDel() {
        nativeBridge?.let {
            sendKeyEvent(0x1D, true)
            sendKeyEvent(0x38, true)
            sendKeyEvent(0x53, true, extended = true)
            sendKeyEvent(0x53, false, extended = true)
            sendKeyEvent(0x38, false)
            sendKeyEvent(0x1D, false)
        }
    }

    override fun sendText(text: String) {
        // TOOLBOX FEATURE (Stage 2): previously a no-op ("RDP has no terminal
        // text channel"), which was true for a *text-stream* channel — but
        // RDP's keyboard input PDU does support a Unicode variant
        // (nativeSendUnicode/KBD_FLAGS_UNICODE) that delivers one UTF-16 code
        // unit per event and lets the remote side's own layout/IME resolve
        // it to a character, regardless of script. This is what makes typing
        // Arabic (or any other non-ANSI-layout text) from our own on-screen
        // keyboard actually reach the remote session correctly.
        // Kotlin's Char is already a UTF-16 code unit, so iterating the
        // string naturally splits any surrogate pairs into the two events
        // the protocol expects, with no extra work needed here.
        val bridge = nativeBridge ?: return
        for (ch in text) {
            bridge.sendUnicode(ch.code)
        }
    }

    // LIVE-RESIZE FIX: forwards to the native Display Control ("disp") channel.
    // Best-effort — see AFreeRdpBridge.resize doc. The server's resulting
    // frame at the new size arrives through the normal bridge.frames flow
    // (systemsgo_on_frame → onNativeFrame → frames → _frameUpdates) with
    // fullScreen=true, so no extra plumbing is needed here.
    override fun resize(width: Int, height: Int) {
        nativeBridge?.resize(width, height)
    }

    // MULTITOUCH FEATURE: forwards to the native "rdpei" (MS-RDPEI) channel.
    // Best-effort — see AFreeRdpBridge.sendTouchFrame doc; silently ignored
    // if the rdpei channel never connected (server doesn't support
    // MS-RDPEI, or the channel hasn't finished opening yet).
    override fun sendTouchFrame(contacts: List<com.systemsgo.hex.ui.screens.RemoteTouchContact>) {
        val bridge = nativeBridge ?: return
        bridge.sendTouchFrame(contacts.map { c ->
            val action = when (c.phase) {
                com.systemsgo.hex.ui.screens.TouchPhase.DOWN -> AFreeRdpBridge.TouchAction.DOWN
                com.systemsgo.hex.ui.screens.TouchPhase.MOVE -> AFreeRdpBridge.TouchAction.UPDATE
                com.systemsgo.hex.ui.screens.TouchPhase.UP   -> AFreeRdpBridge.TouchAction.UP
            }
            AFreeRdpBridge.TouchContact(c.id, c.x, c.y, action)
        })
    }

    // MULTI-MONITOR FEATURE: see RemoteSessionClient.selectMonitor doc and
    // AFreeRdpBridge.selectMonitor for the best-effort semantics (silently
    // ignored if the disp channel never connected or this session never
    // declared more than one monitor).
    override fun selectMonitor(selection: MonitorSelection) {
        when (selection) {
            is MonitorSelection.Single -> nativeBridge?.selectMonitor(selection.monitorId, showAll = false)
            MonitorSelection.All -> nativeBridge?.selectMonitor(monitorId = -1, showAll = true)
        }
    }

    // CODEC-NEGOTIATION FEATURE: see the doc comment at the connect() call
    // site above for why this is a by-name (not by-ordinal) mapping between
    // data.model.CodecPreference (the pure-Kotlin, Room-persisted enum this
    // adapter/RdpCredentials deal in) and AFreeRdpBridge.CodecPreference
    // (the native-bridge enum whose ordinal systemsgo_jni.c actually reads —
    // see AFreeRdpBridge.connect()'s `codecPreference` param doc). Falls
    // back to AUTO for any value with no matching bridge entry, the same
    // fail-safe default AFreeRdpBridge.connect() itself uses.
    private fun CodecPreference.toBridgeCodecPreference(): AFreeRdpBridge.CodecPreference =
        AFreeRdpBridge.CodecPreference.entries.firstOrNull { it.name == name }
            ?: AFreeRdpBridge.CodecPreference.AUTO

    // OUTBOUND-PROXY FEATURE: same data.model-enum -> AFreeRdpBridge-enum
    // mapping shape as toBridgeCodecPreference() immediately above — see
    // com.systemsgo.hex.data.model.ProxyType's doc comment for why this
    // indirection exists (keeps the model layer free of a native-JNI-layer
    // import). Falls back to SOCKS (not NONE) on an unrecognized name,
    // matching data.model.ProxyType.fromName()'s own fallback, so a
    // corrupted/future-version DB value never silently disables proxying
    // that a profile explicitly turned on — worst case it picks the wrong
    // proxy protocol rather than none at all.
    // ENTRA-ID-AUTH FEATURE: mirrors toBridgeProxyType() immediately below —
    // same "public data.model enum -> private bridge-facing enum" mapping
    // pattern, kept as an explicit `when` (not `.ordinal`/`.name` reuse) so a
    // future reordering of either enum fails to compile instead of silently
    // mismatching.
    private fun com.systemsgo.hex.data.model.GatewayAuthMode.toBridgeGatewayAuthMode(): AFreeRdpBridge.GatewayAuthMode =
        when (this) {
            com.systemsgo.hex.data.model.GatewayAuthMode.PASSWORD  -> AFreeRdpBridge.GatewayAuthMode.PASSWORD
            com.systemsgo.hex.data.model.GatewayAuthMode.ENTRA_ID  -> AFreeRdpBridge.GatewayAuthMode.ENTRA_ID
        }

    private fun com.systemsgo.hex.data.model.ProxyType.toBridgeProxyType(): AFreeRdpBridge.ProxyType =
        AFreeRdpBridge.ProxyType.entries.firstOrNull { it.name == name }
            ?: AFreeRdpBridge.ProxyType.SOCKS

    override fun disconnect() {
        audioManager?.stop()
        audioManager = null
        printManager?.stop()
        printManager = null
        nativeBridge?.let { it.disconnect(); it.free() }
        nativeBridge = null
        // RDP-OVER-WEBSOCKET FEATURE: tears down the WebSocket, its loopback
        // listener socket, and cancels any pending reconnect — safe to call
        // even when this session never used the WS transport (wsTransport
        // stays null the whole time for a TCP profile).
        wsTransport?.close()
        wsTransport = null
        adapterScope.cancel()
        unregisterClipboardListener()
        // FIX #2: Emit DISCONNECTED so the autoReconnect logic in
        // RdpSessionActivity can react when the user (or the OS) closes
        // the session — previously this state was never emitted for RDP.
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }

    private companion object {
        const val PREFS_TOFU_RDP = "systemsgo_tofu_rdp"

        const val MOUSE_FLAG_MOVE      = 0x0800
        const val MOUSE_FLAG_BUTTON1   = 0x1000 // left
        const val MOUSE_FLAG_BUTTON2   = 0x2000 // right
        const val MOUSE_FLAG_BUTTON3   = 0x4000 // middle
        const val MOUSE_FLAG_DOWN      = 0x8000
        // CRIT-NEW-2 FIX: MS-RDPBCGR §2.2.8.1.1.3.1.1.3 — the WheelRotationMask
        // occupies bits 0–7 of the flags word and encodes the rotation magnitude.
        // Standard Windows wheel delta is 120 per click.  The previous values
        // (0x0200 and 0x0300) left the rotation field at 0, so most RDP servers
        // treated every wheel event as "no movement" — scroll up did nothing and
        // scroll down behaviour was undefined.
        const val MOUSE_FLAG_WHEEL_UP   = 0x0200 or 120   // PTRFLAGS_WHEEL          | 120 = 0x0278
        const val MOUSE_FLAG_WHEEL_DOWN = 0x0300 or 120   // PTRFLAGS_WHEEL_NEGATIVE | 120 = 0x0378

        fun mouseClickFlags(button: RemoteMouseButton, down: Boolean): Int {
            val base = when (button) {
                RemoteMouseButton.LEFT   -> MOUSE_FLAG_BUTTON1
                RemoteMouseButton.RIGHT  -> MOUSE_FLAG_BUTTON2
                RemoteMouseButton.MIDDLE -> MOUSE_FLAG_BUTTON3
            }
            return if (down) base or MOUSE_FLAG_DOWN else base
        }
    }
}
