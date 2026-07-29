package com.systemsgo.hex.rlogin.protocol

import android.content.Context
import android.util.Log
import com.systemsgo.hex.R
import com.systemsgo.hex.remote.*
import com.systemsgo.hex.ssh.protocol.SshKeyMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Connection details for an Rlogin (RFC 1282) session.
 *
 * Unlike Telnet, Rlogin's handshake itself carries three pieces of
 * information before any terminal data flows: the *client* username (who
 * this device claims to be), the *server* username (who to log in as), and
 * a terminal type/speed string. A correctly configured server — a matching
 * entry in the remote user's `~/.rhosts` or the system-wide
 * `/etc/hosts.equiv` — can use the first two to skip the interactive login
 * prompt entirely; otherwise the server just falls back to prompting for a
 * password over the terminal exactly like Telnet/SSH-password would.
 */
data class RloginCredentials(
    val host: String,
    val port: Int = 513,
    /** "Who this device is" — the first (client) username field of the handshake. */
    val clientUsername: String,
    /** Who to log in as on the remote host — the second (server) username field. */
    val remoteUsername: String,
    /** Terminal type/speed, e.g. "xterm/38400" — the handshake's third field. */
    val terminalType: String = "xterm/38400",
)

/**
 * Rlogin client speaking just enough of RFC 1282 to drive an interactive
 * remote terminal, exposed through the same [RemoteSessionClient] surface
 * [com.systemsgo.hex.telnet.protocol.TelnetClient] uses — [terminalOutput]/
 * [sendText] rather than a framebuffer, so the existing
 * [com.systemsgo.hex.ui.screens.terminal.TerminalScreen] UI and
 * RdpSessionActivity plumbing drive an Rlogin session exactly the way they
 * already drive a Telnet/SSH one (see
 * [com.systemsgo.hex.data.model.ProtocolType.isTerminal]).
 *
 * Two responsibilities that don't exist in a "just open a socket and pipe
 * bytes" client:
 *  - The one-time startup handshake (four NUL-terminated fields, see
 *    [performHandshake]) and its single-byte acknowledgement — or, if the
 *    server refuses the connection, an arbitrary rejection message sent
 *    back *without* that leading zero byte (handled the same way
 *    [com.systemsgo.hex.ssh.protocol.SshClient] surfaces an auth failure:
 *    reported through [error], not thrown as a raw exception).
 *  - Rlogin's in-band "window size"/flow-control control sequences
 *    (`0xFF 0xFF` followed by a command byte and, for a window-size report,
 *    8 more bytes) are recognized and discarded so they never leak into the
 *    terminal as garbage characters — the same reasoning as
 *    [com.systemsgo.hex.telnet.protocol.TelnetClient]'s IAC stripping, just
 *    for Rlogin's much smaller control vocabulary.
 */
class RloginClient(
    private val credentials: RloginCredentials,
    private val appContext: Context,
) : RemoteSessionClient {

    companion object {
        private const val TAG = "RloginClient"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val HANDSHAKE_ACK_TIMEOUT_MS = 10_000

        // RFC 1282 in-band control marker: two consecutive 0xFF bytes,
        // followed by a one-byte command. 's' (window-size report) also
        // carries 8 further bytes (four big-endian 16-bit fields: rows,
        // cols, x-pixels, y-pixels) that must be swallowed too.
        private const val CTRL_MARKER: Int = 0xFF
        private const val CTRL_WINDOW_SIZE: Int = 's'.code
        private const val CTRL_WINDOW_SIZE_PAYLOAD_LEN = 8
    }

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 1)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 64)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    // Rlogin has no TLS convention and no server-identity mechanism at all
    // (same trust model as plain, non-TLS Telnet) — so, unlike TelnetClient,
    // there is no certificate-challenge flow here. Always null.
    override val certificateChallenge: StateFlow<CertificateChallenge?> =
        MutableStateFlow<CertificateChallenge?>(null).asStateFlow()

    override var latencyMs: Long = 0L
        private set

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var connected = false

    private var ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            _sessionState.emit(RemoteSessionState.CONNECTING)

            val connectStart = System.currentTimeMillis()
            val rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(credentials.host, credentials.port), CONNECT_TIMEOUT_MS)
            rawSocket.tcpNoDelay = true

            val socketInput = rawSocket.getInputStream()
            val socketOutput = rawSocket.getOutputStream()

            if (!performHandshake(rawSocket, socketInput, socketOutput)) {
                // performHandshake already emitted a specific _error.
                try { rawSocket.close() } catch (e: Exception) { android.util.Log.d("RloginClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
                _sessionState.emit(RemoteSessionState.ERROR)
                return@withContext false
            }

            rawSocket.soTimeout = 0
            latencyMs = System.currentTimeMillis() - connectStart

            socket = rawSocket
            input = socketInput
            output = socketOutput
            connected = true
            _sessionState.emit(RemoteSessionState.CONNECTED)
            ioScope.launch { readLoop() }

            true
        } catch (e: Exception) {
            // SEC-LOG: log only the exception class, same reasoning as
            // TelnetClient.connect() — raw socket exception messages can
            // embed hostnames.
            Log.e(TAG, "Rlogin connect failed: ${e.javaClass.simpleName}")
            val userMessage = when {
                e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("timed out", ignoreCase = true) == true ->
                    appContext.getString(R.string.error_rlogin_timeout)
                e.message?.contains("refused", ignoreCase = true) == true ->
                    appContext.getString(R.string.error_rlogin_refused)
                else ->
                    appContext.getString(R.string.error_rlogin_connect_failed)
            }
            _error.emit(userMessage)
            _sessionState.emit(RemoteSessionState.ERROR)
            ioScope.cancel()
            cleanup()
            false
        }
    }

    /**
     * Sends the RFC 1282 startup handshake — four NUL-terminated fields:
     * an empty "client terminal speed" placeholder byte (a single leading
     * 0x00, per spec), the client username, the server username, and the
     * terminal type/speed string — then waits for the server's one-byte
     * acknowledgement (a single 0x00).
     *
     * If the server instead sends anything else, this is a rejection: RFC
     * 1282 lets the server return an arbitrary human-readable reason
     * (e.g. "Permission denied.") with no leading zero, then close the
     * connection. That text (if any arrives within
     * [HANDSHAKE_ACK_TIMEOUT_MS]) is surfaced via [error] verbatim, since
     * unlike a generic network failure this message is meant to be read by
     * the person configuring the connection (mismatched .rhosts entry,
     * unknown user, etc.).
     */
    private fun performHandshake(socket: Socket, input: InputStream, output: OutputStream): Boolean {
        val handshake = java.io.ByteArrayOutputStream()
        handshake.write(0)
        handshake.write(credentials.clientUsername.toByteArray(Charsets.US_ASCII))
        handshake.write(0)
        handshake.write(credentials.remoteUsername.toByteArray(Charsets.US_ASCII))
        handshake.write(0)
        handshake.write(credentials.terminalType.toByteArray(Charsets.US_ASCII))
        handshake.write(0)
        output.write(handshake.toByteArray())
        output.flush()

        val previousTimeout = socket.soTimeout
        socket.soTimeout = HANDSHAKE_ACK_TIMEOUT_MS
        try {
            val first = input.read()
            if (first == 0) return true
            if (first < 0) {
                _error.tryEmit(appContext.getString(R.string.error_rlogin_connect_failed))
                return false
            }

            // Rejection path: collect whatever text the server sends back
            // (bounded — a rejection message is short) and surface it.
            val rejection = java.io.ByteArrayOutputStream()
            rejection.write(first)
            val buf = ByteArray(256)
            try {
                while (rejection.size() < 4096) {
                    val n = input.read(buf)
                    if (n < 0) break
                    rejection.write(buf, 0, n)
                }
            } catch (_: Exception) {
                // Timed out / socket closed after the server finished
                // sending its message — expected, not an error in itself.
            }
            val message = rejection.toByteArray().toString(Charsets.UTF_8).trim()
            _error.tryEmit(
                if (message.isNotEmpty()) {
                    appContext.getString(R.string.error_rlogin_denied_with_reason, message)
                } else {
                    appContext.getString(R.string.error_rlogin_refused)
                }
            )
            return false
        } finally {
            try { socket.soTimeout = previousTimeout } catch (e: Exception) { android.util.Log.d("RloginClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    // ── Read loop: rlogin control-sequence stripping + UTF-8 terminal text ──

    private suspend fun readLoop() {
        val buffer = ByteArray(8192)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        val textOut = java.io.ByteArrayOutputStream(buffer.size)

        // Control-sequence parser state, preserved across read() calls since
        // a marker/command/payload can straddle two consecutive socket reads.
        var state = ParseState.DATA
        var payloadRemaining = 0

        try {
            val stream = input ?: return
            while (connected) {
                val n = stream.read(buffer)
                if (n < 0) break
                textOut.reset()
                for (i in 0 until n) {
                    val b = buffer[i].toInt() and 0xFF
                    when (state) {
                        ParseState.DATA ->
                            if (b == CTRL_MARKER) state = ParseState.MARKER else textOut.write(b)
                        ParseState.MARKER ->
                            state = if (b == CTRL_MARKER) ParseState.COMMAND else ParseState.DATA.also {
                                // A lone 0xFF not followed by a second 0xFF is
                                // just a literal byte in the data stream.
                                textOut.write(CTRL_MARKER); textOut.write(b)
                            }
                        ParseState.COMMAND -> {
                            if (b == CTRL_WINDOW_SIZE) {
                                payloadRemaining = CTRL_WINDOW_SIZE_PAYLOAD_LEN
                                state = ParseState.PAYLOAD
                            } else {
                                // Unknown/other control command — RFC 1282
                                // defines no other command bytes today, so
                                // there is no further payload to skip.
                                state = ParseState.DATA
                            }
                        }
                        ParseState.PAYLOAD -> {
                            payloadRemaining--
                            if (payloadRemaining <= 0) state = ParseState.DATA
                        }
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
            val outBuf = java.nio.CharBuffer.allocate(8)
            decoder.decode(java.nio.ByteBuffer.allocate(0), outBuf, true)
            decoder.flush(outBuf)
            outBuf.flip()
            if (outBuf.hasRemaining()) {
                _terminalOutput.emit(TerminalOutput(outBuf.toString()))
            }
        } catch (e: Exception) {
            if (connected) {
                Log.e(TAG, "Rlogin read loop error: ${e.javaClass.simpleName}")
                _error.emit(appContext.getString(R.string.error_connection_lost))
                _sessionState.emit(RemoteSessionState.ERROR)
            }
        } finally {
            connected = false
            _sessionState.emit(RemoteSessionState.DISCONNECTED)
        }
    }

    private enum class ParseState { DATA, MARKER, COMMAND, PAYLOAD }

    // ── Input — terminal sessions take raw text, not framebuffer events ────

    override fun sendText(text: String) {
        ioScope.launch {
            try {
                output?.write(text.toByteArray(Charsets.UTF_8))
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
        // mapping SshClient/TelnetClient use for their terminals, since all
        // three drive the same TerminalScreen/ExtraKeysBar UI.
        if (!down) return
        val seq = SshKeyMap.scanCodeToAnsiSequence(scanCode, extended) ?: return
        sendText(seq)
    }

    override fun disconnect() {
        connected = false
        ioScope.cancel()
        cleanup()
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }

    private fun cleanup() {
        try { socket?.close() } catch (e: Exception) { android.util.Log.d("RloginClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        socket = null; input = null; output = null
    }
}
