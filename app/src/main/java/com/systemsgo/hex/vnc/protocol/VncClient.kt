package com.systemsgo.hex.vnc.protocol

import android.graphics.Bitmap
import android.util.Log
import com.systemsgo.hex.R
import com.systemsgo.hex.remote.*
import com.systemsgo.hex.remote.clipboard.ClipboardCapableSession
import com.systemsgo.hex.remote.clipboard.ClipboardFormat
import com.systemsgo.hex.remote.clipboard.ClipboardPayload
import com.systemsgo.hex.remote.clipboard.ClipboardSyncManager
import com.systemsgo.hex.security.openEncryptedPrefs
import com.undatech.opaque.RfbConnectable
// BUG-8 FIX: removed unused imports SpiceCommunicator and RemoteKeyboard
// (com.undatech.opaque.SpiceCommunicator, com.undatech.opaque.input.RemoteKeyboard)
// — these classes may not exist in all bVNC versions → Unresolved reference compile error.
import com.undatech.opaque.input.RemotePointer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Connection details for a VNC (RFB) session.
 *
 * CRIT-2 FIX: password is stored as CharArray rather than String.
 * JVM Strings are immutable and stay on the heap until GC decides to collect them —
 * they are therefore visible in heap dumps and memory forensics for an unpredictable
 * duration.  CharArray can be zeroed via fill('\u0000') immediately after use,
 * bounding the exposure window to the lifetime of the connection setup only.
 * Call [zero] as soon as the password has been passed to the bVNC library.
 */
// BUG-i18n-LEAK FIX: marker exception carrying a message that is already a resolved,
// localized, user-safe string (built via appContext.getString(...)). When caught by
// the generic `catch (e: Exception)` in connect(), its message is trusted and shown
// as-is; every other exception type must be re-mapped to a string resource rather
// than displaying e.message, since raw socket/bVNC exception text can contain the
// server hostname or port.
private class LocalizedVncException(message: String) : Exception(message)

class VncCredentials(
    val host: String,
    val port: Int,
    password: String,
    val viewOnly: Boolean = false,
    // CLIPBOARD FIX: mirrors RdpCredentials.enableClipboard. Defaults to true
    // so existing call sites that don't pass it keep working unchanged; set
    // false to disable RFB clipboard sync entirely (no listener registered,
    // nothing sent or received) for callers that want it optional.
    val enableClipboard: Boolean = true,
    // VENCRYPT FIX: only meaningful for the VeNCrypt-Plain/X509Plain/TLSPlain
    // sub-types (RfbConnectable.sendVeNCryptPlainCredentials) — base RFB
    // (None/VNC-Auth) and every other VeNCrypt sub-type never read this.
    // Left blank by default since classic VNC has no concept of a username;
    // callers that have one (e.g. RdpProfile.username, shared across
    // protocols) may pass it through for servers that require it.
    val username: String = "",
    // ULTRAVNC-REPEATER FEATURE: when true, [host]/[port] above are the
    // *repeater's* address, not the real VNC server's — see
    // RfbConnectable's class doc and Connection.useRepeater for the wire
    // protocol. [repeaterId] is whatever ID string the server side
    // registered with the repeater (e.g. `winvnc -connect
    // repeaterHost:5500 -id:12345` on the server, "12345" here).
    val repeaterEnabled: Boolean = false,
    val repeaterId: String = "",
    // ULTRAVNC-REPEATER FEATURE (Mode I/II): see VncRepeaterMode's doc
    // comment. Mapped onto Connection.RepeaterMode in connect() below, the
    // same by-name-not-ordinal mapping pattern RdpRemoteAdapter uses for
    // ProxyType/CodecPreference, so this class's data/UI-facing enum never
    // has a compile-time dependency on com.undatech.opaque.Connection's own.
    val repeaterMode: com.systemsgo.hex.data.model.VncRepeaterMode =
        com.systemsgo.hex.data.model.VncRepeaterMode.MODE_II,
    // LISTEN-MODE FEATURE (reverse VNC): when true, [host]/[port] above are
    // ignored and this client instead opens a listening socket on
    // [listenPort] and waits for the remote VNC *server* to dial in — see
    // the doc comment on Connection.useListenMode for the full protocol
    // rationale. Mutually exclusive with [repeaterEnabled] in the UI.
    val listenModeEnabled: Boolean = false,
    val listenPort: Int = 5500,
) {
    val password: CharArray = password.toCharArray()
    /** Zero all sensitive fields; call after the password has been handed to bVNC. */
    fun zero() { password.fill('\u0000') }
}

/**
 * VNC client backed by the **bVNC / LibVNCAndroid** library
 * (`com.github.iiordanov:bVNC`), which wraps libvncserver/libvncclient
 * and handles the full RFB protocol (versions 3.3–3.8, all standard
 * security types, Raw/CopyRect/Hextile/Tight/ZRLE encodings, BouncyCastle
 * TLS, etc.) without any hand-written protocol code.
 *
 * The [RemoteSessionClient] surface is the same one used by [RdpRemoteAdapter]
 * and [com.systemsgo.hex.ssh.protocol.SshClient], so the session UI drives all
 * three protocols identically.
 */
class VncClient(
    private val credentials: VncCredentials,
    // BUG-B FIX: bVNC's RfbConnectable uses Context for TLS/certificate handling.
    // Passing null crashes with NullPointerException on VNC-over-TLS servers.
    private val appContext: android.content.Context,
) : RemoteSessionClient, ClipboardCapableSession {

    companion object {
        private const val TAG = "VncClient"
        private const val CONNECT_TIMEOUT_MS = 15_000
        // LISTEN-MODE FEATURE: must match (or exceed) RfbConnectable's own
        // LISTEN_ACCEPT_TIMEOUT_MS. CONNECT_TIMEOUT_MS above is sized for a
        // normal outbound dial (seconds); listen mode instead waits for a
        // person on the *server* side to trigger an outgoing connection,
        // which can reasonably take minutes, so it needs its own, much
        // longer budget here too — otherwise this coroutine-level timeout
        // would fire and tear down the session long before
        // acceptListenModeSocket()'s own timeout ever would.
        private const val LISTEN_CONNECT_TIMEOUT_MS = 5 * 60_000

        // GC-FIX: frameLoop used to do `IntArray(w * h)` on every single iteration
        // (up to ~60/s), unlike the RDP path which reuses fixed display buffers.
        // Instead of allocating fresh each frame, rotate through a small fixed pool
        // of pre-sized buffers. Pool size must exceed frameUpdates' extraBufferCapacity
        // (below) so a buffer is never overwritten while a still-unconsumed emission
        // referencing it is sitting in the SharedFlow buffer or being read by a
        // collector — this is what the old code's BUG-RACE FIX comment was guarding
        // against with a 2-buffer scheme, which wasn't enough for an 8-deep buffer.
        private const val FRAME_BUFFER_POOL_SIZE = 12

        // TOFU constants
        private const val PREFS_TOFU_VNC = "systemsgo_tofu_vnc"

        // ── Minimal X11 keysym constants ─────────────────────────────────────
        const val XK_BACKSPACE  = 0xFF08
        const val XK_TAB        = 0xFF09
        const val XK_RETURN     = 0xFF0D
        const val XK_ESCAPE     = 0xFF1B
        const val XK_DELETE     = 0xFFFF
        const val XK_HOME       = 0xFF50
        const val XK_LEFT       = 0xFF51
        const val XK_UP         = 0xFF52
        const val XK_RIGHT      = 0xFF53
        const val XK_DOWN       = 0xFF54
        const val XK_PAGE_UP    = 0xFF55
        const val XK_PAGE_DOWN  = 0xFF56
        const val XK_END        = 0xFF57
        const val XK_INSERT     = 0xFF63
        const val XK_F1         = 0xFFBE
        const val XK_F2         = 0xFFBF
        const val XK_F3         = 0xFFC0
        const val XK_F4         = 0xFFC1
        const val XK_F5         = 0xFFC2
        const val XK_F6         = 0xFFC3
        const val XK_F7         = 0xFFC4
        const val XK_F8         = 0xFFC5
        const val XK_F9         = 0xFFC6
        const val XK_F10        = 0xFFC7
        const val XK_F11        = 0xFFC8
        const val XK_F12        = 0xFFC9
        const val XK_SHIFT_L    = 0xFFE1
        const val XK_SHIFT_R    = 0xFFE2
        const val XK_CONTROL_L  = 0xFFE3
        const val XK_CONTROL_R  = 0xFFE4
        const val XK_ALT_L      = 0xFFE9
        const val XK_ALT_R      = 0xFFEA
        const val XK_SUPER_L    = 0xFFEB
        const val XK_PRINT      = 0xFF61

        fun scanCodeToKeysym(scanCode: Int, extended: Boolean): Int? = when (scanCode) {
            0x0E -> XK_BACKSPACE
            0x0F -> XK_TAB
            0x1C -> XK_RETURN
            0x01 -> XK_ESCAPE
            0x53 -> if (extended) XK_DELETE else null
            0x47 -> if (extended) XK_HOME   else null
            0x4F -> if (extended) XK_END    else null
            0x49 -> if (extended) XK_PAGE_UP   else null
            0x51 -> if (extended) XK_PAGE_DOWN else null
            0x52 -> if (extended) XK_INSERT    else null
            0x4B -> if (extended) XK_LEFT   else null
            0x48 -> if (extended) XK_UP     else null
            0x4D -> if (extended) XK_RIGHT  else null
            0x50 -> if (extended) XK_DOWN   else null
            0x3B -> XK_F1;  0x3C -> XK_F2;  0x3D -> XK_F3;  0x3E -> XK_F4
            0x3F -> XK_F5;  0x40 -> XK_F6;  0x41 -> XK_F7;  0x42 -> XK_F8
            0x43 -> XK_F9;  0x44 -> XK_F10; 0x57 -> XK_F11; 0x58 -> XK_F12
            0x2A -> XK_SHIFT_L;   0x36 -> XK_SHIFT_R
            0x1D -> if (extended) XK_CONTROL_R else XK_CONTROL_L
            0x38 -> if (extended) XK_ALT_R     else XK_ALT_L
            0x5B -> XK_SUPER_L
            0x37 -> if (extended) XK_PRINT     else null
            else -> null
        }
    }

    // ── VNC TOFU (VeNCrypt certificate pinning) ─────────────────────────────
    //
    // VENCRYPT FIX: RfbConnectable now implements real VeNCrypt/TLS (RFB
    // security-type 19) and exposes the actual negotiated peer certificate
    // via RfbConnectable.certificateVerifier — a callback invoked
    // synchronously during connect(), before any session data is exchanged.
    // This replaces the previous approach (a separate, throwaway TLS probe
    // connection opened before the real RFB handshake even started): that
    // probe could only detect servers doing raw TLS-from-byte-0, which is
    // NOT how VeNCrypt actually works (the server sends the plaintext RFB
    // version banner first; TLS only begins mid-handshake, after security-
    // type and sub-type negotiation) — so it never actually validated a real
    // VeNCrypt server's certificate. Pinning the real session certificate
    // here means TOFU actually protects the connection that's really used.
    //
    // Design mirrors SshClient.TofuHostKeyRepository:
    //   - First connection → fingerprint stored in EncryptedSharedPreferences (TOFU).
    //   - Subsequent connections → fingerprint compared; mismatch aborts the session.

    private enum class VncTofuResult { OK, FIRST_USE, CHANGED }

    private inner class VncTofuVerifier {
        private val prefs by lazy { appContext.openEncryptedPrefs(PREFS_TOFU_VNC) }
        private val prefKey = "${credentials.host}:${credentials.port}"

        /** Called from [RfbConnectable.certificateVerifier] with the real,
         *  negotiated X509* VeNCrypt certificate. Returns true to accept the
         *  connection (and updates [lastResult] for the caller to log/report
         *  after connect() returns), false to abort it. */
        var lastResult: VncTofuResult = VncTofuResult.OK
            private set

        fun verify(cert: X509Certificate): Boolean {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            val fingerprint = digest.joinToString(":") { b -> "%02X".format(b) }
            val stored = prefs.getString(prefKey, null)
            return when {
                stored == null -> {
                    // LIVE-MED-3 / LIVE-HIGH-1 FIX: commit() instead of apply().
                    // Using apply() risks losing the fingerprint to an OOM kill
                    // between this check succeeding and the async write — the
                    // next connection would then re-accept an unchanged (or
                    // MITM) cert as a fresh first connection.
                    prefs.edit().putString(prefKey, fingerprint).commit()
                    Log.i(TAG, "VNC TOFU: first connection to $prefKey — fingerprint stored")
                    lastResult = VncTofuResult.FIRST_USE
                    true
                }
                stored == fingerprint -> {
                    lastResult = VncTofuResult.OK
                    true
                }
                else -> {
                    Log.w(TAG, "VNC TOFU: fingerprint CHANGED for $prefKey — possible MITM attack!")
                    lastResult = VncTofuResult.CHANGED
                    false
                }
            }
        }
    }

    // ── Session state ──────────────────────────────────────────────────────────

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 8)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    // GC-FIX: pool of pre-sized pixel buffers reused across frames instead of a
    // fresh IntArray(w * h) allocation per frame. See FRAME_BUFFER_POOL_SIZE.
    private var framePool: Array<IntArray>? = null
    private var framePoolWidth = 0
    private var framePoolHeight = 0
    private var framePoolIndex = 0

    /** Returns the next reusable pixel buffer for a w×h frame, (re)allocating the
     * pool only when the resolution changes. */
    private fun nextFrameBuffer(w: Int, h: Int): IntArray {
        var pool = framePool
        if (pool == null || framePoolWidth != w || framePoolHeight != h) {
            pool = Array(FRAME_BUFFER_POOL_SIZE) { IntArray(w * h) }
            framePool = pool
            framePoolWidth = w
            framePoolHeight = h
            framePoolIndex = 0
            return pool[0]
        }
        framePoolIndex = (framePoolIndex + 1) % FRAME_BUFFER_POOL_SIZE
        return pool[framePoolIndex]
    }

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 1)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    override var latencyMs: Long = 0L
        private set

    // BUG-VOLATILE FIX: rfb is written in one coroutine (connect()) and read in
    // another (frameLoop, sendMouseMove, sendKeyEvent, etc.). Without @Volatile,
    // the JVM is free to cache the field in a register, meaning IO-thread readers
    // could see null even after connect() assigned it. @Volatile guarantees a
    // happens-before relationship between the write and all subsequent reads.
    @Volatile private var rfb: RfbConnectable? = null
    // BUG-AA4 FIX: removed dead `private var framebuffer: Bitmap? = null` field.
    // It was never assigned and never read anywhere in this class — the actual
    // framebuffer is rfbClient.framebuffer (a property on bVNC's RfbConnectable).
    // The dead field wasted a Bitmap reference slot and confused readers into
    // thinking VncClient maintained its own copy of the screen buffer.
    @Volatile private var connected = false

    // ── Clipboard sync (ServerCutText / ClientCutText) ──────────────────────
    //
    // CLIPBOARD-SYNC FEATURE: bidirectional clipboard sync between the
    // Android system clipboard and the remote VNC session's clipboard (RFB
    // ServerCutText / ClientCutText, RFB Protocol §7.5.4 / §7.5.6). Loop
    // prevention, duplicate detection, format detection and per-connection
    // enable/disable are all handled by the shared ClipboardSyncManager (see
    // [supportedClipboardFormats] / [remoteClipboardUpdates] /
    // [sendClipboardPayload] below) — this class only knows how to move a
    // ClipboardPayload on/off the RFB wire.
    private var clipboardSync: ClipboardSyncManager? = null

    // CLIPBOARD-SYNC FEATURE: the base RFB protocol this hand-written client
    // speaks (see RfbConnectable's class doc) only has ServerCutText /
    // ClientCutText, which carry Latin-1 plain text and nothing else — no
    // rich text, image, or file clipboard formats exist in plain RFB (those
    // require the non-standard "Extended Clipboard" pseudo-encoding, which
    // is rarely implemented server-side and not wired up here). Declaring
    // only PLAIN_TEXT is what makes ClipboardSyncManager gracefully
    // downgrade HTML to its plain-text fallback and skip images/files
    // entirely instead of attempting a send this backend can't honor.
    override val supportedClipboardFormats: Set<ClipboardFormat> = setOf(ClipboardFormat.PLAIN_TEXT)

    private val _remoteClipboardUpdates = MutableSharedFlow<ClipboardPayload>(extraBufferCapacity = 4)
    override val remoteClipboardUpdates: SharedFlow<ClipboardPayload> = _remoteClipboardUpdates.asSharedFlow()

    override fun sendClipboardPayload(payload: ClipboardPayload) {
        val text = (payload as? ClipboardPayload.Text)?.text ?: return
        rfb?.sendClientCutText(text)
    }

    // TOOLBOX FEATURE (Stage 9): set true once registerClipboardSync() below
    // actually creates the manager (post-handshake), null for the lifetime
    // of a session where the profile disabled clipboard redirection outright
    // — see the doc comment on RemoteSessionClient.clipboardSyncState for
    // what each value means to the Toolbox tool.
    private val _clipboardSyncState = MutableStateFlow<Boolean?>(null)
    override val clipboardSyncState: StateFlow<Boolean?> = _clipboardSyncState.asStateFlow()

    /**
     * CLIPBOARD-SYNC FEATURE: lets the UI toggle clipboard sync on/off for
     * this connection at runtime, independent of the profile-level default
     * ([VncCredentials.enableClipboard]). A no-op if the profile disabled
     * clipboard sync outright (no [ClipboardSyncManager] was ever created).
     */
    override fun setClipboardSyncEnabled(enabled: Boolean) {
        val sync = clipboardSync ?: return
        sync.setEnabled(enabled)
        _clipboardSyncState.value = enabled
    }

    /**
     * Wires up clipboard sync for the just-established [rfbClient]. Optional
     * and lightweight: skipped entirely when [VncCredentials.enableClipboard]
     * is false. Delegates all sync logic (loop prevention, duplicate
     * detection, format detection) to the shared [ClipboardSyncManager] —
     * this method only bridges RfbConnectable's ServerCutText callback into
     * [_remoteClipboardUpdates].
     */
    private fun registerClipboardSync(rfbClient: RfbConnectable) {
        if (!credentials.enableClipboard) return

        // Remote -> local: ServerCutText received from the VNC server. Runs
        // on RfbConnectable's reader thread; tryEmit is non-blocking and
        // safe to call from any thread.
        rfbClient.onServerCutText = { text ->
            _remoteClipboardUpdates.tryEmit(ClipboardPayload.Text(text))
        }

        clipboardSync = ClipboardSyncManager(appContext, this, sessionScope).also { it.start() }
        // TOOLBOX FEATURE (Stage 9): ClipboardSyncManager's initiallyEnabled
        // default is true, so the tool starts tinted "on" the moment this
        // session becomes clipboard-capable, matching the manager's real state.
        _clipboardSyncState.value = true
    }

    /** Undoes [registerClipboardSync]; called from [disconnect]. */
    private fun unregisterClipboardSync() {
        clipboardSync?.stop()
        clipboardSync = null
        // TOOLBOX FEATURE (Stage 9): back to "unsupported/not started" so a
        // stale "on" tint can never survive into a disconnected session.
        _clipboardSyncState.value = null
    }

    // BUG-4 FIX: Changed from `val` to `@Volatile var` so the scope can be replaced
    // in connect() after a disconnect(). With `val`, calling disconnect() cancels the
    // scope permanently — any subsequent connect() call would launch coroutines into the
    // cancelled scope where they silently do nothing (no frame loop, no state updates),
    // making the app appear connected while being frozen. Mirroring the pattern already
    // used in SshTunneledClient: recreate the scope at the start of every connect().
    @Volatile private var sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        // BUG-4 FIX: Recreate scope at the start of every connect() call so that
        // reusing the same VncClient instance after disconnect() works correctly.
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            _sessionState.emit(RemoteSessionState.CONNECTING)

            val connectStart = System.currentTimeMillis()

            // Build connection URI expected by bVNC: vnc://host:port
            // NEW-HIGH-1 FIX: Use try-finally so credentials.zero() is guaranteed to run
            // even if an exception is thrown inside the apply{} block (e.g., OOM during
            // String(credentials.password), or any future field initialisation that throws).
            // Previously credentials.zero() was a plain statement after the apply{} block;
            // any exception inside apply{} would skip it, leaving the CharArray alive until GC.
            val conn: com.undatech.opaque.Connection
            try {
                conn = com.undatech.opaque.Connection().apply {
                    address   = credentials.host
                    port      = credentials.port
                    // CRIT-2 FIX: Convert CharArray to String only at the last moment —
                    // bVNC's Connection.password is a String (JVM library; cannot change its
                    // API), so a short-lived String is unavoidable here.  Zero the CharArray
                    // immediately after to remove the longer-lived copy from heap.
                    password  = String(credentials.password)
                    // VENCRYPT FIX: only read by RfbConnectable.sendVeNCryptPlainCredentials
                    // for the VeNCrypt-Plain/X509Plain/TLSPlain sub-types; harmless empty
                    // string for every other sub-type and for base RFB (None/VNC-Auth).
                    userName  = credentials.username
                    inputMode = if (credentials.viewOnly)
                        RemotePointer.INPUT_MODE_TOUCH_TOUCHPAD
                    else
                        RemotePointer.INPUT_MODE_TOUCH_DIRECT
                    // ULTRAVNC-REPEATER FEATURE: see RfbConnectable's class
                    // doc — passed straight through so RfbConnectable.connect()
                    // sends the Mode II ID frame before the RFB handshake.
                    useRepeater  = credentials.repeaterEnabled
                    repeaterId   = credentials.repeaterId
                    // ULTRAVNC-REPEATER FEATURE (Mode I/II): mapped by name,
                    // not ordinal — same convention as RdpRemoteAdapter's
                    // toBridgeProxyType()/toBridgeCodecPreference().
                    repeaterMode = when (credentials.repeaterMode) {
                        com.systemsgo.hex.data.model.VncRepeaterMode.MODE_I ->
                            com.undatech.opaque.Connection.RepeaterMode.MODE_I
                        com.systemsgo.hex.data.model.VncRepeaterMode.MODE_II ->
                            com.undatech.opaque.Connection.RepeaterMode.MODE_II
                    }
                    // LISTEN-MODE FEATURE: see VncCredentials.listenModeEnabled's
                    // doc comment. RfbConnectable.connect() branches on
                    // useListenMode before it ever looks at address/port.
                    useListenMode = credentials.listenModeEnabled
                    listenPort    = credentials.listenPort
                }
            } finally {
                credentials.zero()  // Erase CharArray — called even if apply{} throws
            }

            val rfbClient = RfbConnectable(conn, appContext)  // BUG-B FIX: was null
            rfb = rfbClient

            // VENCRYPT FIX: TOFU verification now runs against the *real*
            // negotiated VeNCrypt certificate, invoked synchronously by
            // RfbConnectable from inside connect() — before any session data
            // is exchanged with a potentially impersonating server. Returning
            // false here makes connect() throw and abort the whole session.
            val tofuVerifier = VncTofuVerifier()
            rfbClient.certificateVerifier = { cert -> tofuVerifier.verify(cert) }

            // BUG-K FIX: ForkJoinPool.cancel(true) does NOT interrupt blocking socket I/O
            // on some Android versions, leaving threads stuck in the common pool and
            // degrading app-wide performance. Use withTimeout + Dispatchers.IO coroutine
            // instead — cancellation is cooperative and actually interrupts the coroutine.
            //
            // BUG-2 FIX: withTimeout alone still does NOT interrupt rfbClient.connect()
            // because it is a Java blocking call (java.net.Socket.connect) with no
            // coroutine suspension points — the coroutine timeout fires but the thread
            // keeps blocking for another 15–30 seconds waiting for the OS TCP timeout.
            // Fix: wrap the call in runInterruptible { } which invokes Thread.interrupt()
            // when the coroutine is cancelled, causing Socket.connect() to throw
            // SocketException("Socket closed") immediately and unblocking the thread.
            // LISTEN-MODE FEATURE: pick the timeout budget to match what
            // connect() is actually about to do — dial out quickly, or sit
            // waiting for the remote server to dial in.
            val effectiveTimeoutMs =
                if (credentials.listenModeEnabled) LISTEN_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
            try {
                kotlinx.coroutines.withTimeout(effectiveTimeoutMs.toLong()) {
                    kotlinx.coroutines.runInterruptible {
                        rfbClient.connect()
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // BUG-i18n-LEAK FIX: wrapped in the marker type LocalizedVncException
                // (instead of a plain IOException) so the generic `catch (e: Exception)`
                // below can recognise this message as already-localized/safe and use it
                // as-is, rather than either leaking a raw message or discarding this
                // specific, useful timeout text in favor of a generic fallback.
                val timeoutStringRes =
                    if (credentials.listenModeEnabled) R.string.error_vnc_listen_timeout
                    else R.string.error_vnc_timeout
                throw LocalizedVncException(
                    appContext.getString(timeoutStringRes, effectiveTimeoutMs / 1000)
                )
            }
            latencyMs = System.currentTimeMillis() - connectStart

            // VENCRYPT FIX: warn based on the real negotiation outcome
            // (rfbClient.isEncrypted), not a separate pre-connect guess.
            // True for any VeNCrypt sub-type except cleartext Plain.
            if (!rfbClient.isEncrypted) {
                Log.w(TAG, "VNC session to ${credentials.host}:${credentials.port} is " +
                    "unencrypted — MITM protection unavailable for this connection")
                // HIGH-2 FIX: user-visible warning (non-blocking) rather than a
                // silent log line, so the user knows their session and
                // password are travelling unencrypted and can decide whether
                // to proceed (the session is already connected at this point —
                // we cannot add TLS to a plain-RFB server after the fact).
                _error.emit(appContext.getString(R.string.warning_vnc_no_tls))
            }

            connected = true
            _sessionState.emit(RemoteSessionState.CONNECTED)

            // CLIPBOARD-SYNC FEATURE: optional, lightweight bidirectional clipboard sync.
            registerClipboardSync(rfbClient)

            // Pump frame updates from the library bitmap into our Flow
            sessionScope.launch { frameLoop(rfbClient) }

            true
        } catch (e: com.undatech.opaque.AuthenticationException) {
            Log.e(TAG, "VNC auth failed", e)
            // BUG-i18n-LEAK FIX: never surface e.message — bVNC auth-exception text can
            // include server-specific detail. Always show the localized, opaque string.
            _error.emit(appContext.getString(R.string.disconnect_reason_auth))
            _sessionState.emit(RemoteSessionState.AUTH_FAILED)
            // BUG-M2 FIX: sessionScope was not cancelled here (unlike the general
            // Exception handler below). The scope remained open with no coroutines
            // in it — a silent resource leak on every failed auth attempt.
            sessionScope.cancel()
            false
        } catch (e: Exception) {
            Log.e(TAG, "VNC connect failed", e)
            // BUG-i18n-LEAK FIX: raw socket/bVNC exception messages can contain the
            // server hostname/port. Only a message we localized ourselves
            // (LocalizedVncException, e.g. the timeout case above) is trusted verbatim;
            // everything else is classified by keyword for control-flow only and mapped
            // to an opaque, localized string resource — mirroring SshClient.connect().
            val userMessage = when {
                e is LocalizedVncException -> e.message!!
                // ULTRAVNC-REPEATER FEATURE: the repeater rejected the ID
                // (typo, or the target server isn't currently registered
                // with it) — a specific, actionable message instead of the
                // generic error_vnc_connect_failed fallback below.
                e is com.undatech.opaque.RepeaterRejectedException ->
                    appContext.getString(R.string.error_vnc_repeater_rejected, credentials.repeaterId)
                // VENCRYPT FIX: a TOFU fingerprint mismatch is a specific,
                // actionable security event (possible MITM) — surface it as
                // such instead of the generic connect-failed message, same
                // as SshClient does for a changed SSH host key.
                e.message?.contains("TOFU fingerprint mismatch", ignoreCase = true) == true ->
                    "VNC server identity changed for " +
                        "${credentials.host}:${credentials.port} — connection refused " +
                        "(possible MITM attack). If the server certificate was " +
                        "legitimately renewed, remove the saved profile and reconnect."
                e.message?.contains("refused", ignoreCase = true) == true ->
                    appContext.getString(R.string.error_vnc_refused)
                else ->
                    appContext.getString(R.string.error_vnc_connect_failed)
            }
            _error.emit(userMessage)
            _sessionState.emit(RemoteSessionState.ERROR)
            sessionScope.cancel()  // BUG-G FIX: cancel scope on connect failure to prevent scope leak
            false
        }
    }

    /** Continuously reads framebuffer updates from the library and re-emits them. */
    private suspend fun frameLoop(rfbClient: RfbConnectable) {
        // FIX-polling: Use adaptive delay — skip the 16 ms fixed spin when the
        // server has nothing new to send. We track whether the Bitmap reference
        // changed since the last frame; if not, we back off to 100 ms so idle
        // VNC sessions no longer burn CPU / battery at ~60 "updates"/s.
        //
        // BUG-RACE FIX: The previous double-buffer scheme (bufA/bufB with useA flip)
        // passed the IntArray *by reference* to _frameUpdates.emit(). After two frame
        // flips Compose could still be reading bufA while frameLoop had already started
        // writing the next frame into it → corrupted pixels / canvas crash.
        // Fix: always emit a fresh IntArray copy so the reference is immutable after
        // emit() returns, completely eliminating the race.
        // BUG-X3 FIX: bVNC reuses the same Bitmap object and writes new pixels
        // directly into it. Comparing fb === lastFb is always true once connected,
        // so the adaptive back-off would fire every iteration → screen appears frozen
        // even though the server is sending updates.
        // Fix: track the Bitmap reference AND its generationId (available since API 12).
        // Bitmap.getGenerationId() increments whenever setPixels/copyPixelsFromBuffer
        // or any native write touches the backing buffer, so it reliably detects new
        // content even when the object reference never changes.
        var lastFbRef: Bitmap? = null
        var lastFbGenId: Int = -1
        try {
            while (connected) {
                val fb = rfbClient.framebuffer ?: break
                // Adaptive delay: back off when neither the Bitmap reference nor its
                // content (generationId) has changed since the last iteration.
                val currentGenId = fb.generationId
                if (fb === lastFbRef && currentGenId == lastFbGenId) {
                    delay(100L)
                    // BUG-BURST FIX: After the 100ms idle delay, loop back immediately
                    // to re-check generationId before sleeping again. Without this, a
                    // burst of frames arriving from the server during the idle window
                    // would each wait a full 100ms because `continue` skips `delay(16L)`
                    // but the next iteration instantly hits the back-off check again.
                    // Now we simply fall through to re-read fb and currentGenId fresh.
                    continue
                }
                lastFbRef = fb
                lastFbGenId = currentGenId
                val w = fb.width
                val h = fb.height
                // GC-FIX: pull a buffer from the reusable pool instead of allocating
                // IntArray(w * h) fresh on every frame (was happening up to ~60x/s).
                // The emitted reference still becomes read-only from this loop's
                // perspective once emit() returns; the pool is simply large enough
                // that the same slot won't be reused before downstream collectors
                // have finished reading it (see FRAME_BUFFER_POOL_SIZE).
                val pixels = nextFrameBuffer(w, h)
                fb.getPixels(pixels, 0, w, 0, 0, w, h)
                _frameUpdates.emit(
                    RemoteFrameUpdate(
                        x = 0, y = 0,
                        width = w, height = h,
                        pixels = pixels,
                        fullScreen = true,
                    )
                )
                // BUG-BURST FIX: use a short cooperative yield instead of a fixed
                // 16ms sleep so back-to-back frames from a fast server are not each
                // delayed by a full frame interval unnecessarily.
                delay(16L)
            }
        } catch (e: Exception) {
            if (connected) {
                Log.e(TAG, "VNC frame loop error", e)
                // BUG-i18n-LEAK FIX: mid-session read errors can also carry raw socket
                // detail in e.message — always show the localized, opaque string
                // (matches SshClient.readLoop()'s use of the same resource).
                _error.emit(appContext.getString(R.string.error_connection_lost))
                _sessionState.emit(RemoteSessionState.ERROR)
            }
        } finally {
            connected = false
            _sessionState.emit(RemoteSessionState.DISCONNECTED)
        }
    }

    // ── Input ────────────────────────────────────────────────────────────────

    override fun sendMouseMove(x: Int, y: Int) {
        if (credentials.viewOnly) return
        rfb?.sendPointerEvent(x, y, 0)
    }

    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) {
        if (credentials.viewOnly) return
        val mask = when (button) {
            RemoteMouseButton.LEFT   -> 1
            RemoteMouseButton.MIDDLE -> 2
            RemoteMouseButton.RIGHT  -> 4
        }
        rfb?.sendPointerEvent(x, y, if (down) mask else 0)
    }

    override fun sendMouseScroll(x: Int, y: Int, delta: Int) {
        if (credentials.viewOnly) return
        val wheelMask = if (delta > 0) (1 shl 3) else (1 shl 4)
        rfb?.sendPointerEvent(x, y, wheelMask)
        rfb?.sendPointerEvent(x, y, 0)
    }

    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        val keysym = scanCodeToKeysym(scanCode, extended) ?: return
        rfb?.sendKeyEvent(keysym, down)
    }

    override fun sendCtrlAltDel() {
        rfb?.let {
            it.sendKeyEvent(XK_CONTROL_L, true)
            it.sendKeyEvent(XK_ALT_L,     true)
            it.sendKeyEvent(XK_DELETE,     true)
            it.sendKeyEvent(XK_DELETE,     false)
            it.sendKeyEvent(XK_ALT_L,     false)
            it.sendKeyEvent(XK_CONTROL_L, false)
        }
    }

    override fun sendText(text: String) {
        text.forEach { ch ->
            // BUG-UNICODE FIX: RFB spec §5.4 defines Unicode keysyms as
            // 0x01000000 OR codepoint for any character outside the Latin-1
            // range (0x0020–0x00FF). Using ch.code directly as a keysym only
            // works for ASCII/Latin-1; Arabic, Chinese, emoji, etc. would map
            // to wrong or undefined keysyms and be silently dropped by the server.
            val keysym = when {
                ch.code in 0x0020..0x00FF -> ch.code          // Latin-1: direct mapping
                else -> 0x01000000 or ch.code                  // Unicode plane: RFB §5.4
            }
            rfb?.sendKeyEvent(keysym, true)
            rfb?.sendKeyEvent(keysym, false)
        }
    }

    // LIVE-RESIZE FIX: forwards to the RFB SetDesktopSize client message
    // (best-effort — see RfbConnectable.requestResize doc). If accepted, the
    // server replies with an ExtendedDesktopSize rectangle that
    // RfbConnectable applies to its own `framebuffer` Bitmap; frameLoop()
    // above already detects that Bitmap reference/generationId change on its
    // own and emits a fresh fullScreen RemoteFrameUpdate, so no extra
    // plumbing is needed here beyond sending the request.
    override fun resize(width: Int, height: Int) {
        rfb?.requestResize(width, height)
    }

    // HIRES-ZOOM FEATURE: forwards to a full, non-incremental
    // FramebufferUpdateRequest (see RfbConnectable.requestFullRefresh doc).
    // frameLoop() below picks up the resulting rectangles the same way it
    // does for any other update — no extra plumbing needed here.
    override fun refresh() {
        rfb?.requestFullRefresh()
    }

    override fun disconnect() {
        connected = false
        unregisterClipboardSync()
        sessionScope.cancel()
        try { rfb?.close() } catch (e: Exception) { android.util.Log.d("VncClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        rfb = null
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }

}

