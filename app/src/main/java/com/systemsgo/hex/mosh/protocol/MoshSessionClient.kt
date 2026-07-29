package com.systemsgo.hex.mosh.protocol

import android.content.Context
import android.util.Log
import com.systemsgo.hex.data.model.MoshProfile
import com.systemsgo.hex.mosh.native.MoshBridge
import com.systemsgo.hex.remote.RemoteMouseButton
import com.systemsgo.hex.remote.RemoteSessionClient
import com.systemsgo.hex.remote.RemoteSessionState
import com.systemsgo.hex.remote.TerminalOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * MOSH-PROTOCOL FEATURE: the session client itself. This is the piece that
 * finally makes Mosh support real end-to-end: it runs
 * [MoshSessionManager] to SSH-bootstrap `mosh-server` and get a
 * [MoshConnectInfo], opens a plain UDP [DatagramSocket] to that host/port,
 * and drives [MoshTransport] (SSP sequencing/ack/retransmission) +
 * [MoshBridge] (native AES-128-OCB) + [MoshWire]'s protobuf codec to carry
 * keystrokes out and terminal-diff text back, exactly the way SshClient
 * carries a PTY over an SSH channel — see that class for the pattern this
 * one follows (same [RemoteSessionClient] surface, same
 * StateFlow/SharedFlow field shapes, same `ioScope` lifecycle).
 *
 * Terminal rendering is NOT reimplemented here. Mosh's server-side
 * `Terminal::Display::new_frame()` diff (confirmed against the independent
 * `unixshells/mosh-go` reference implementation's `framebuffer.go` — see
 * [MoshWire]'s doc comment in MoshWireProtocol.kt) already produces plain
 * ANSI/VT100 escape sequences as the `HostBytes.hoststring` payload — the
 * exact same kind of byte stream [com.systemsgo.hex.ssh.protocol.SshClient]
 * emits from a PTY. So this class just decodes that field and emits it
 * through [terminalOutput] unmodified; the existing ANSI parser in
 * [com.systemsgo.hex.ui.screens.terminal.TerminalScreen] renders it exactly
 * as it already does for SSH/IPMI-SOL, with no new rendering code needed.
 *
 * Predictive local echo (mosh's headline "type ahead, see it underlined
 * immediately" UX feature) IS implemented, via [predictionEngine] /
 * [predictionOverlay] — see [MoshPredictionEngine]'s class doc for exactly
 * what's ported 1:1 from upstream's `PredictionEngine` (the SRTT/flagging
 * timing model, verbatim thresholds) versus deliberately scoped down
 * (line-local prediction instead of a full cell-framebuffer port, since
 * this app has no cursor-addressable terminal grid to predict against yet
 * — see mosh/NOTES.md for the phased plan to close that gap).
 *
 * NOT implemented in this pass (see mosh/NOTES.md for the up-to-date
 * list): live end-to-end verification against a real `mosh-server` (no
 * network access in the environment this was written in — see
 * mosh/NOTES.md for exactly what WAS verified).
 */
class MoshSessionClient(
    private val profile: MoshProfile,
    password: CharArray,
    private val appContext: Context,
    private val termCols: Int = 100,
    private val termRows: Int = 32,
    // MOSH-RDPPROFILE-MERGE FEATURE: PEM key material for PRIVATE_KEY auth,
    // passed straight through to MoshSessionManager — see that class's
    // constructor doc comment. Empty for PASSWORD auth (the vast majority
    // of today's call sites); RemoteSessionFactory is the one real caller
    // that supplies these from RdpProfile.sshPrivateKey/sshPrivateKeyPassphrase.
    privateKeyPem: CharArray = CharArray(0),
    privateKeyPassphrase: CharArray = CharArray(0),
) : RemoteSessionClient {

    companion object {
        private const val TAG = "MoshSessionClient"
        private const val TICK_INTERVAL_MS = 50L
        private const val RECV_BUFFER_SIZE = 2048 // comfortably above MOSH_MAX_FRAGMENT_PAYLOAD + framing overhead
        private const val SOCKET_READ_TIMEOUT_MS = 200 // lets the receive loop notice `connected == false` promptly
    }

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<com.systemsgo.hex.remote.RemoteFrameUpdate>(extraBufferCapacity = 1)
    override val frameUpdates: SharedFlow<com.systemsgo.hex.remote.RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 64)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    // MOSH-PREDICT-FEATURE: cell-by-cell predictive echo — see MoshPredictionEngine's
    // class doc for full scope/limits. TerminalScreen currently still renders
    // `predictionOverlay.pendingText` as an underlined suffix after the confirmed
    // stream (unchanged call site); it is never mixed into `terminalOutput` itself
    // so a mismatch never corrupts confirmed history. `termCols`/`termRows` are
    // passed through so the engine's own internal confirmed-state mirror starts at
    // the right size — see queueResize() below for the matching resize call.
    val predictionEngine = MoshPredictionEngine(termCols, termRows)
    val predictionOverlay: StateFlow<MoshPredictionEngine.Overlay> = predictionEngine.overlay

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    override var latencyMs: Long = 0L
        private set

    private val passwordRef = password
    private val sshManager = MoshSessionManager(profile, password, appContext, privateKeyPem, privateKeyPassphrase)
    private val crypto = MoshBridge()
    private lateinit var transport: MoshTransport
    private var socket: DatagramSocket? = null
    private var remoteAddress: InetAddress? = null
    private var remoteUdpPort: Int = 0

    @Volatile private var connected = false
    private var ioJob: SupervisorJob? = null
    private var ioScope: CoroutineScope? = null

    // Accumulated, not-yet-fully-acked keystroke bytes — see the class doc
    // comment on why this must be cumulative rather than "whatever was
    // typed since the last tick": SSP retransmits the SAME diff bytes
    // until acked, so replacing them with only the newest chunk would
    // silently drop any keystrokes typed between the last ack and now.
    private val pendingKeys = ByteArrayOutputStream()
    private val pendingKeysLock = Any()
    private var currentCols = termCols
    private var currentRows = termRows

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        _sessionState.value = RemoteSessionState.CONNECTING
        try {
            sshManager.connect()
            val connectInfo = sshManager.startSession()
            sshManager.disconnect() // the SSH exec channel's only job was to bootstrap mosh-server; SSP doesn't need it held open

            if (!crypto.init() || !crypto.setKey(connectInfo.sessionKey)) {
                Log.e(TAG, "Failed to initialize native Mosh crypto or decode session key")
                _error.emit("Mosh: failed to set up encryption for the SSP session")
                _sessionState.value = RemoteSessionState.ERROR
                return@withContext false
            }
            transport = MoshTransport(crypto)

            remoteAddress = InetAddress.getByName(connectInfo.remoteHost)
            remoteUdpPort = connectInfo.udpPort
            val sock = DatagramSocket()
            sock.soTimeout = SOCKET_READ_TIMEOUT_MS
            socket = sock

            connected = true
            _sessionState.value = RemoteSessionState.CONNECTED

            val job = SupervisorJob()
            ioJob = job
            val scope = CoroutineScope(Dispatchers.IO + job)
            ioScope = scope
            scope.launch { receiveLoop() }
            scope.launch { tickLoop() }

            // Announce our terminal size right away, same as mosh's own client does on connect.
            queueResize(currentCols, currentRows)

            true
        } catch (e: MoshProtocolException) {
            Log.e(TAG, "Mosh SSH bootstrap failed: ${e.javaClass.simpleName}")
            _error.emit(e.message ?: "Mosh: SSH bootstrap failed")
            _sessionState.value = RemoteSessionState.ERROR
            false
        } catch (e: Exception) {
            Log.e(TAG, "Mosh connect failed: ${e.javaClass.simpleName}")
            _error.emit("Mosh: connection failed (${e.javaClass.simpleName})")
            _sessionState.value = RemoteSessionState.ERROR
            false
        } finally {
            passwordRef.fill('\u0000')
        }
    }

    private suspend fun tickLoop() {
        val scope = ioScope ?: return
        while (scope.isActive && connected) {
            try {
                val datagrams = transport.tick()
                val addr = remoteAddress
                val sock = socket
                if (addr != null && sock != null) {
                    for (datagram in datagrams) {
                        sock.send(DatagramPacket(datagram, datagram.size, addr, remoteUdpPort))
                    }
                }
                val rtt = transport.smoothedRttMs()
                if (rtt > 0) {
                    latencyMs = rtt
                    // MOSH-PREDICT-FEATURE: drives predictionEngine's SRTT/flagging
                    // hysteresis exactly like STMClient::process_network_input's
                    // set_send_interval() call feeds PredictionEngine::cull().
                    predictionEngine.onRttSampleMs(rtt)
                }
            } catch (e: Exception) {
                if (connected) Log.w(TAG, "Mosh tick/send error: ${e.javaClass.simpleName}")
            }
            kotlinx.coroutines.delay(TICK_INTERVAL_MS)
        }
    }

    private suspend fun receiveLoop() {
        val sock = socket ?: return
        val buf = ByteArray(RECV_BUFFER_SIZE)
        val packet = DatagramPacket(buf, buf.size)
        while (ioScope?.isActive == true && connected) {
            try {
                sock.receive(packet)
                val wire = packet.data.copyOfRange(0, packet.length)
                val hostMessageBytes = transport.recv(wire) ?: continue
                onAckProgressed()
                val instructions = try {
                    MoshHostMessage.unmarshal(hostMessageBytes)
                } catch (e: MoshProtocolException) {
                    continue // malformed HostMessage from an authenticated-but-unexpected payload — drop, don't crash the session
                }
                for (instr in instructions) {
                    val text = instr.hoststring
                    if (text != null && text.isNotEmpty()) {
                        val decoded = String(text, Charsets.UTF_8)
                        predictionEngine.onServerText(decoded)
                        _terminalOutput.emit(TerminalOutput(decoded))
                    }
                    // instr.width/height (server-reported size) is intentionally not
                    // acted on — this app's client is authoritative for its own
                    // terminal size rather than accepting a server resize.
                    // instr.echoAckNum is still not consumed: real mosh uses it for the
                    // cell-framebuffer epoch bookkeeping in PredictionEngine::cull, via
                    // real SSP transport frame numbers. MoshPredictionEngine now does
                    // real cell-framebuffer prediction/validation (see its class doc),
                    // but deliberately doesn't wire this ack — MoshWireProtocol.kt/
                    // MoshTransport's transport-frame internals are out of scope for
                    // that class, so it keeps its own simplified, self-contained frame
                    // counter instead (see the class doc's "simplification #1"). This
                    // field would only become useful if that simplification is later
                    // replaced with the real transport-frame-number wiring.
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Expected every SOCKET_READ_TIMEOUT_MS with no traffic — just lets
                // this loop re-check `connected` promptly; not an error.
            } catch (e: Exception) {
                if (connected) Log.w(TAG, "Mosh receive error: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Clears the pending-keystroke accumulator once the transport confirms the server has caught up. */
    private fun onAckProgressed() {
        if (transport.ackedByRemote() >= transport.sentNum()) {
            synchronized(pendingKeysLock) { pendingKeys.reset() }
        }
    }

    private fun queuePendingUserMessage() {
        val keysSnapshot = synchronized(pendingKeysLock) { pendingKeys.toByteArray() }
        val instructions = mutableListOf<MoshUserInstruction>()
        if (keysSnapshot.isNotEmpty()) {
            instructions.add(MoshUserInstruction(keys = keysSnapshot))
        }
        instructions.add(MoshUserInstruction(width = currentCols, height = currentRows))
        transport.setPending(MoshUserMessage.marshal(instructions))
        transport.forceNextSend()
    }

    private fun queueResize(cols: Int, rows: Int) {
        currentCols = cols
        currentRows = rows
        // MOSH-PREDICT-FEATURE: keep the prediction engine's own internal
        // confirmed-state mirror framebuffer the same size as the real session —
        // see MoshPredictionEngine.onResize's doc comment for why this is required
        // (without it, cell-position validation would silently corrupt after any
        // resize).
        predictionEngine.onResize(cols, rows)
        if (::transport.isInitialized) queuePendingUserMessage()
    }

    // --- RemoteSessionClient: terminal input/output -------------------

    override fun sendText(text: String) {
        if (!connected) return
        // MOSH-PREDICT-FEATURE: bulk paste (mirrors upstream's >100-byte "don't
        // predict for bulk data" rule in STMClient::process_user_input) resets
        // instead of predicting; anything smaller is fed char-by-char.
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > 100) predictionEngine.onOutgoingBulk() else predictionEngine.onOutgoingText(text)
        synchronized(pendingKeysLock) { pendingKeys.write(bytes) }
        queuePendingUserMessage()
    }

    /** Sends a single raw control byte, e.g. Ctrl+C = 0x03 — same surface as SshClient.sendControlByte. */
    fun sendControlByte(byte: Int) {
        if (!connected) return
        predictionEngine.onOutgoingControlByte(byte)
        synchronized(pendingKeysLock) { pendingKeys.write(byte) }
        queuePendingUserMessage()
    }

    // KBD-INT FIX: the SSH-bootstrap phase (sshManager) can hit a real
    // keyboard-interactive challenge (TOTP/PAM) exactly like a plain SSH
    // profile can — see MoshSessionManager's InteractiveUserInfo. Exposed
    // here with the identical surface SshClient uses so RdpSessionActivity's
    // existing authPrompt/submitAuthPromptResponse/cancelAuthPrompt dispatch
    // just needs a MoshSessionClient branch added, not new UI plumbing.
    val authPrompt: kotlinx.coroutines.flow.StateFlow<com.systemsgo.hex.ssh.protocol.SshInteractivePrompt?> get() = sshManager.authPrompt
    fun submitAuthPromptResponse(responses: List<String>) = sshManager.submitAuthPromptResponse(responses)
    fun cancelAuthPrompt() = sshManager.cancelAuthPrompt()

    fun resizeTerminal(cols: Int, rows: Int) = queueResize(cols, rows)

    override fun sendCtrlAltDel() { /* not meaningful over a terminal */ }
    override fun sendMouseMove(x: Int, y: Int) { /* terminal sessions don't use pointer input */ }
    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) { }
    override fun sendMouseScroll(x: Int, y: Int, delta: Int) { }
    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        if (!down) return
        val seq = com.systemsgo.hex.ssh.protocol.SshKeyMap.scanCodeToAnsiSequence(scanCode, extended) ?: return
        sendText(seq)
    }

    override fun disconnect() {
        connected = false
        predictionEngine.reset()
        ioJob?.cancel()
        try { socket?.close() } catch (e: Exception) { android.util.Log.d("MoshSessionClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        socket = null
        crypto.release()
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }
}
