package com.systemsgo.hex.telnet.protocol

import android.content.Context
import android.util.Log
import com.systemsgo.hex.R
import com.systemsgo.hex.security.openEncryptedPrefs
import com.systemsgo.hex.remote.*
import com.systemsgo.hex.ssh.protocol.SshKeyMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Connection details for a Telnet session.
 *
 * Unlike SSH, the Telnet *protocol* itself has no built-in authentication —
 * login (if any) happens as plain text exchanged over the terminal once
 * connected (a "login:"/"Password:" prompt from the remote host), so there
 * is no username/password field here for the protocol layer to consume.
 * [useTls] is the one real security knob this client has: it wraps the
 * socket in a TLS handshake before Telnet option negotiation starts (the
 * "telnets" convention), which is the only way to get real server-identity
 * assurance and confidentiality out of a Telnet connection at all — plain
 * Telnet sends everything, credentials included, in clear text on the wire.
 */
data class TelnetCredentials(
    val host: String,
    val port: Int = 23,
    val useTls: Boolean = false,
)

/**
 * Telnet client speaking just enough of RFC 854 (Telnet) / RFC 855 (option
 * negotiation) to drive an interactive remote terminal, exposed through the
 * same [RemoteSessionClient] surface [com.systemsgo.hex.ssh.protocol.SshClient]
 * uses — [terminalOutput]/[sendText] rather than a framebuffer, so the
 * existing [com.systemsgo.hex.ui.screens.terminal.TerminalScreen] UI and
 * RdpSessionActivity plumbing drive a Telnet session exactly the way they
 * already drive an SSH one (see [com.systemsgo.hex.data.model.ProtocolType.isTerminal]).
 *
 * Two responsibilities that don't exist in a "just open a socket and pipe
 * bytes" client:
 *  - IAC (0xFF) option negotiation: every DO/WILL/DONT/WONT the server sends
 *    is answered (declining everything except the couple of options that
 *    are safe/expected — see [respondToNegotiation]), and IAC subnegotiation
 *    blocks are consumed and discarded, so none of that control-byte traffic
 *    leaks into the terminal as garbage characters.
 *  - Optional TLS ("telnets"): when [TelnetCredentials.useTls] is set, the
 *    socket is upgraded to TLS *before* any Telnet bytes are exchanged, with
 *    server-certificate trust handled the same first-use-pinning way
 *    RDP/VNC already do — see [verifyServerCertificate].
 */
class TelnetClient(
    private val credentials: TelnetCredentials,
    private val appContext: Context,
) : RemoteSessionClient {

    companion object {
        private const val TAG = "TelnetClient"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val PREFS_TOFU_TELNET = "systemsgo_tofu_telnet_tls"

        // Telnet command bytes (RFC 854).
        private const val IAC: Int  = 0xFF
        private const val DONT: Int = 254
        private const val DO: Int   = 253
        private const val WONT: Int = 252
        private const val WILL: Int = 251
        private const val SB: Int   = 250
        private const val SE: Int   = 240
        private const val GA: Int   = 249

        // Telnet option bytes we actually care about (RFC 856/857/1091 etc.);
        // everything else is uniformly declined.
        private const val OPT_ECHO: Int          = 1
        private const val OPT_SUPPRESS_GA: Int    = 3
        private const val OPT_TERMINAL_TYPE: Int  = 24
        private const val OPT_NAWS: Int           = 31  // negotiate about window size
    }

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 1)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 64)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    // TELNET-TLS FEATURE: same shape as RdpRemoteAdapter/VncClient's untrusted-
    // certificate dialog — non-null exactly while a decision is pending.
    private val _certificateChallenge = MutableStateFlow<CertificateChallenge?>(null)
    override val certificateChallenge: StateFlow<CertificateChallenge?> = _certificateChallenge.asStateFlow()

    override var latencyMs: Long = 0L
        private set

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var connected = false

    // NAWS-FEATURE (عربي: دعم إرسال حجم النافذة الفعلي — RFC 1073): كانت
    // OPT_NAWS معرّفة بس بتُرفض دايمًا (تقع بفرع else -> WONT في
    // respondToNegotiation). الحين لو السيرفر طلب DO NAWS نوافق ونرسل
    // الحجم الحقيقي، ونحدّثه كل ما تغيّر عبر resizeTerminal() — بنفس واجهة
    // SshClient.resizeTerminal()/MoshSessionClient.resizeTerminal() تمامًا.
    //
    // NAWS-FEATURE (EN — RFC 1073): defaults mirror SshClient/
    // MoshSessionClient's fallback (100×32) for consistency across
    // protocols before RdpSessionActivity ever calls resizeTerminal() with
    // a real, Compose-measured value.
    private var currentCols: Int = 100
    private var currentRows: Int = 32
    // True once the server has DO'd NAWS and this client has WILL'd it back
    // (see respondToNegotiation) — until then sendNawsSubnegotiation() is a
    // no-op, since sending unsolicited NAWS data before the option is even
    // agreed isn't meaningful Telnet traffic and some servers may choke on it.
    @Volatile private var nawsNegotiated = false

    private var ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // TOFU pinning store for the TLS certificate fingerprint, keyed by
    // host:port — same persistence pattern as RdpRemoteAdapter's PREFS_TOFU_RDP.
    private val certPrefs by lazy { appContext.openEncryptedPrefs(PREFS_TOFU_TELNET) }

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            _sessionState.emit(RemoteSessionState.CONNECTING)

            val connectStart = System.currentTimeMillis()
            val rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(credentials.host, credentials.port), CONNECT_TIMEOUT_MS)
            rawSocket.soTimeout = 0
            rawSocket.tcpNoDelay = true

            val activeSocket: Socket = if (credentials.useTls) {
                upgradeToTls(rawSocket) ?: run {
                    // verifyServerCertificate/upgradeToTls already emitted an
                    // error and this connection was rejected by the user or
                    // failed the handshake — clean up and bail out.
                    try { rawSocket.close() } catch (e: Exception) { android.util.Log.d("TelnetClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
                    return@withContext false
                }
            } else {
                rawSocket
            }

            latencyMs = System.currentTimeMillis() - connectStart

            socket = activeSocket
            input = activeSocket.getInputStream()
            output = activeSocket.getOutputStream()
            connected = true
            _sessionState.emit(RemoteSessionState.CONNECTED)
            ioScope.launch { readLoop() }

            true
        } catch (e: Exception) {
            // SEC-LOG: log only the exception class — same reasoning as
            // SshClient.connect()'s catch block; raw socket/TLS exception
            // messages can embed hostnames and negotiated algorithm details.
            Log.e(TAG, "Telnet connect failed: ${e.javaClass.simpleName}")
            val userMessage = when {
                e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("timed out", ignoreCase = true) == true ->
                    appContext.getString(R.string.error_telnet_timeout)
                e.message?.contains("refused", ignoreCase = true) == true ->
                    appContext.getString(R.string.error_telnet_refused)
                else ->
                    appContext.getString(R.string.error_telnet_connect_failed)
            }
            _error.emit(userMessage)
            _sessionState.emit(RemoteSessionState.ERROR)
            ioScope.cancel()
            cleanup()
            false
        }
    }

    /**
     * Wraps [rawSocket] in a TLS session ("telnets"). Returns the upgraded
     * [SSLSocket], or `null` if the handshake failed or the user rejected an
     * untrusted certificate — in both cases an [_error] has already been
     * emitted by the caller/[verifyServerCertificate].
     */
    private fun upgradeToTls(rawSocket: Socket): Socket? {
        // Capture the leaf certificate's details ourselves rather than trust
        // it outright — mirrors RdpRemoteAdapter.verifyServerCertificate's
        // "ask the user on first use, pin the fingerprint" flow instead of
        // either (a) blindly trusting any certificate (no security benefit
        // over plaintext Telnet) or (b) using the platform default trust
        // store (most self-hosted Telnet-over-TLS servers use a self-signed
        // certificate, which would just fail outright).
        var challengeResult = false
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull()
                    ?: throw java.security.cert.CertificateException("No server certificate presented")
                val fingerprint = sha256Fingerprint(leaf)
                val commonName = leaf.subjectX500Principal?.name.orEmpty()
                val issuer = leaf.issuerX500Principal?.name.orEmpty()
                challengeResult = verifyServerCertificate(
                    credentials.host, credentials.port, commonName, issuer, fingerprint
                )
                if (!challengeResult) {
                    throw java.security.cert.CertificateException("Certificate not trusted")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())
        val factory = sslContext.socketFactory
        val sslSocket = factory.createSocket(rawSocket, credentials.host, credentials.port, true) as SSLSocket
        sslSocket.useClientMode = true
        return try {
            sslSocket.startHandshake()
            sslSocket
        } catch (e: Exception) {
            Log.w(TAG, "Telnet TLS handshake failed: ${e.javaClass.simpleName}")
            if (challengeResult) {
                // Handshake failed for a reason unrelated to the trust
                // decision (protocol mismatch, reset, etc.) — still a
                // connect failure, just not an untrusted-certificate one.
                _error.tryEmit(appContext.getString(R.string.error_telnet_tls_failed))
            }
            try { sslSocket.close() } catch (e: Exception) { android.util.Log.d("TelnetClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
            null
        }
    }

    private fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Same TOFU decision flow as RdpRemoteAdapter.verifyServerCertificate:
     * first connection to a host pins the fingerprint (after the user
     * confirms via [CertificateChallenge]); later connections compare
     * silently, and a changed fingerprint is always a hard reject (possible
     * MITM) rather than a re-prompt.
     *
     * Called synchronously from the TLS handshake thread (inside
     * [X509TrustManager.checkServerTrusted]) — must not suspend, but may
     * block on [CertificateChallenge.awaitDecision].
     */
    private fun verifyServerCertificate(
        host: String, port: Int, commonName: String, issuer: String, fingerprint: String,
    ): Boolean {
        val key = "$host:$port"
        val stored = certPrefs.getString(key, null)

        if (stored != null) {
            if (stored == fingerprint) return true
            _error.tryEmit(
                "Telnet server identity changed for $host:$port — connection refused " +
                    "(possible MITM attack). If the certificate was legitimately renewed, " +
                    "clear this profile's trusted certificate and reconnect."
            )
            return false
        }

        val challenge = CertificateChallenge(host, port, commonName, issuer, fingerprint)
        _certificateChallenge.value = challenge
        val decision = challenge.awaitDecision()
        _certificateChallenge.value = null

        return when (decision) {
            CertificateChallenge.Decision.REJECT -> {
                _error.tryEmit(
                    "Connection to $host:$port cancelled — the certificate " +
                        "(CN=$commonName, issuer=$issuer) was not trusted."
                )
                false
            }
            CertificateChallenge.Decision.ACCEPT_ONCE -> true
            CertificateChallenge.Decision.ACCEPT_ALWAYS -> {
                certPrefs.edit().putString(key, fingerprint).commit()
                true
            }
        }
    }

    // ── Read loop: Telnet IAC negotiation + UTF-8 terminal text ─────────────

    private suspend fun readLoop() {
        val buffer = ByteArray(8192)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        // Bytes actually destined for the terminal (IAC sequences stripped),
        // accumulated per read() before being handed to the UTF-8 decoder —
        // same reasoning as SshClient.readLoop for why a *stateful* decoder
        // is used (a multi-byte UTF-8 sequence, or a Telnet IAC sequence,
        // can straddle two consecutive socket reads).
        val textOut = java.io.ByteArrayOutputStream(buffer.size)

        // Telnet option-negotiation parser state, preserved across read()
        // calls for the same straddling reason.
        var state = ParseState.DATA

        try {
            val stream = input ?: return
            while (connected) {
                val n = stream.read(buffer)
                if (n < 0) break
                textOut.reset()
                for (i in 0 until n) {
                    val b = buffer[i].toInt() and 0xFF
                    state = when (state) {
                        ParseState.DATA -> {
                            if (b == IAC) ParseState.IAC else run { textOut.write(b); ParseState.DATA }
                        }
                        ParseState.IAC -> when (b) {
                            IAC -> { textOut.write(IAC); ParseState.DATA }  // escaped literal 0xFF
                            WILL, WONT, DO, DONT -> ParseState.pendingFor(b)
                            SB -> ParseState.SUBNEG
                            GA -> ParseState.DATA
                            else -> ParseState.DATA // other single-byte commands (NOP, AYT, etc.) — ignore
                        }
                        ParseState.WILL_OPT -> { respondToNegotiation(WILL, b); ParseState.DATA }
                        ParseState.WONT_OPT -> { respondToNegotiation(WONT, b); ParseState.DATA }
                        ParseState.DO_OPT   -> { respondToNegotiation(DO, b);   ParseState.DATA }
                        ParseState.DONT_OPT -> { respondToNegotiation(DONT, b); ParseState.DATA }
                        ParseState.SUBNEG -> if (b == IAC) ParseState.SUBNEG_IAC else ParseState.SUBNEG
                        ParseState.SUBNEG_IAC -> if (b == SE) ParseState.DATA else ParseState.SUBNEG
                    }
                }

                if (textOut.size() > 0) {
                    val bytes = textOut.toByteArray()
                    val inBuf = java.nio.ByteBuffer.wrap(bytes)
                    val outBuf = java.nio.CharBuffer.allocate(bytes.size * 2 + 8)
                    decoder.decode(inBuf, outBuf, false)
                    outBuf.flip()
                    if (outBuf.hasRemaining()) {
                        _terminalOutput.emit(TerminalOutput(outBuf.toString()))
                    }
                }
            }
            // Flush any buffered partial UTF-8 sequence, same as SshClient.
            val outBuf = java.nio.CharBuffer.allocate(8)
            decoder.decode(java.nio.ByteBuffer.allocate(0), outBuf, true)
            decoder.flush(outBuf)
            outBuf.flip()
            if (outBuf.hasRemaining()) {
                _terminalOutput.emit(TerminalOutput(outBuf.toString()))
            }
        } catch (e: Exception) {
            if (connected) {
                Log.e(TAG, "Telnet read loop error: ${e.javaClass.simpleName}")
                _error.emit(appContext.getString(R.string.error_connection_lost))
                _sessionState.emit(RemoteSessionState.ERROR)
            }
        } finally {
            connected = false
            _sessionState.emit(RemoteSessionState.DISCONNECTED)
        }
    }

    private enum class ParseState {
        DATA, IAC, WILL_OPT, WONT_OPT, DO_OPT, DONT_OPT, SUBNEG, SUBNEG_IAC;
        companion object {
            fun pendingFor(command: Int): ParseState = when (command) {
                WILL -> WILL_OPT
                WONT -> WONT_OPT
                DO   -> DO_OPT
                DONT -> DONT_OPT
                else -> DATA
            }
        }
    }

    /**
     * Answers one DO/WILL/DONT/WONT [option] request from the server.
     *
     * This client deliberately supports the bare minimum needed for a plain
     * interactive terminal and declines everything else — declining an
     * option is always protocol-safe (RFC 855), it just means that feature
     * (server-side line editing, terminal-type negotiation, etc.) isn't
     * offered. SUPPRESS-GO-AHEAD (character-at-a-time mode, which every
     * real interactive Telnet session wants), remote ECHO (letting the
     * server, not this client, echo typed characters — the normal setup
     * for a login prompt), and NAWS (window-size reporting — see
     * [sendNawsSubnegotiation]/[resizeTerminal]) are the three agreed to.
     */
    private fun respondToNegotiation(command: Int, option: Int) {
        val reply: Pair<Int, Int>? = when (command) {
            DO -> when (option) {
                OPT_SUPPRESS_GA -> WILL to option
                // NAWS-FEATURE: agree to report window size (RFC 1073) — the
                // Telnet analogue of SshClient.resizeTerminal()'s
                // ch.setPtySize()/MoshSessionClient.resizeTerminal()'s
                // queueResize(). This used to fall into the `else -> WONT`
                // branch below, so a server-side program that queries
                // COLUMNS/LINES (many TUIs, `less`, shells doing
                // line-wrapping) always saw whatever size the server
                // defaulted to, never this device's actual terminal size.
                OPT_NAWS -> WILL to option
                else -> WONT to option
            }
            WILL -> when (option) {
                OPT_ECHO, OPT_SUPPRESS_GA -> DO to option
                else -> DONT to option
            }
            DONT -> null // acknowledging a DONT needs no reply
            WONT -> null // acknowledging a WONT needs no reply
            else -> null
        }
        if (reply != null) {
            val (verb, opt) = reply
            writeRaw(byteArrayOf(IAC.toByte(), verb.toByte(), opt.toByte()))
            // NAWS-FEATURE: RFC 1073 requires the client to send an initial
            // window-size subnegotiation right after agreeing WILL NAWS —
            // not just on the next real resize — otherwise the server has
            // no size at all until the user happens to rotate the screen
            // or change the font size. currentCols/currentRows already
            // hold either the constructor default or a real value if
            // resizeTerminal() was called before negotiation finished
            // (possible on a slow/high-latency link).
            if (command == DO && option == OPT_NAWS && verb == WILL) {
                nawsNegotiated = true
                sendNawsSubnegotiation()
            }
        }
    }

    /**
     * NAWS-FEATURE: sends `IAC SB NAWS <cols-hi><cols-lo><rows-hi><rows-lo>
     * IAC SE` (RFC 1073) — the Telnet wire message reporting this client's
     * terminal size. No-op until the server has DO'd NAWS and this client
     * has WILL'd it back (see [respondToNegotiation]/[nawsNegotiated]).
     */
    private fun sendNawsSubnegotiation() {
        if (!nawsNegotiated) return
        val cols = currentCols.coerceIn(0, 0xFFFF)
        val rows = currentRows.coerceIn(0, 0xFFFF)
        val sizeBytes = byteArrayOf(
            (cols shr 8).toByte(), (cols and 0xFF).toByte(),
            (rows shr 8).toByte(), (rows and 0xFF).toByte()
        )
        val out = java.io.ByteArrayOutputStream(9)
        out.write(IAC); out.write(SB); out.write(OPT_NAWS)
        for (b in sizeBytes) {
            val v = b.toInt() and 0xFF
            out.write(v)
            // RFC 854: a literal 0xFF inside subnegotiation data must be
            // escaped as IAC IAC so it isn't misread as the start of a new
            // Telnet command — a real (if rare) case here, e.g. a wide
            // split-screen pane at a small font size can legitimately hit
            // exactly 255 columns.
            if (v == IAC) out.write(IAC)
        }
        out.write(IAC); out.write(SE)
        writeRaw(out.toByteArray())
    }

    /**
     * TERM-RESIZE FIX (Telnet/NAWS): reports a new terminal size to the
     * server. Same surface as [com.systemsgo.hex.ssh.protocol.SshClient.resizeTerminal]/
     * [com.systemsgo.hex.mosh.protocol.MoshSessionClient.resizeTerminal] on
     * purpose, so RdpSessionViewModel.resizeTerminal()'s
     * `when (remoteClient)` dispatch can add an `is TelnetClient` branch
     * the exact same way it already has one for SshClient/MoshSessionClient.
     *
     * Always safe to call even before the server has negotiated NAWS — the
     * new size is simply remembered here and sent the moment
     * [respondToNegotiation] sees `DO NAWS` (covers a slow/high-latency
     * link where the UI computes its first resize before the server has
     * gotten around to negotiating options).
     */
    fun resizeTerminal(cols: Int, rows: Int) {
        currentCols = cols
        currentRows = rows
        if (nawsNegotiated) sendNawsSubnegotiation()
    }

    private fun writeRaw(bytes: ByteArray) {
        try {
            output?.write(bytes)
            output?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write Telnet negotiation reply", e)
        }
    }

    // ── Input — terminal sessions take raw text, not framebuffer events ────

    override fun sendText(text: String) {
        ioScope.launch {
            try {
                // Escape any literal 0xFF byte in typed/pasted text as IAC IAC
                // (RFC 854) so it can't be misread as the start of a command.
                val raw = text.toByteArray(Charsets.UTF_8)
                val escaped = if (raw.none { (it.toInt() and 0xFF) == IAC }) {
                    raw
                } else {
                    val out = java.io.ByteArrayOutputStream(raw.size + 4)
                    for (b in raw) {
                        out.write(b.toInt())
                        if ((b.toInt() and 0xFF) == IAC) out.write(IAC)
                    }
                    out.toByteArray()
                }
                output?.write(escaped)
                output?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send terminal input", e)
            }
        }
    }

    /** Sends a single raw control byte, e.g. Ctrl+C = 0x03. */
    fun sendControlByte(byte: Int) {
        ioScope.launch {
            try {
                output?.write(byte)
                output?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send control byte", e)
            }
        }
    }

    override fun sendCtrlAltDel() { /* not meaningful over a terminal */ }
    override fun sendMouseMove(x: Int, y: Int) { /* terminal sessions don't use pointer input */ }
    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) { }
    override fun sendMouseScroll(x: Int, y: Int, delta: Int) { }
    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        // Reuse the exact same PC-scan-code → ANSI/VT100 escape sequence
        // mapping SshClient uses for its terminal, since both drive the same
        // TerminalScreen/ExtraKeysBar UI.
        if (!down) return
        val seq = SshKeyMap.scanCodeToAnsiSequence(scanCode, extended) ?: return
        sendText(seq)
    }

    override fun disconnect() {
        connected = false
        ioScope.cancel()
        _certificateChallenge.value = null
        cleanup()
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }

    private fun cleanup() {
        try { socket?.close() } catch (e: Exception) { android.util.Log.d("TelnetClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        socket = null; input = null; output = null
    }
}
