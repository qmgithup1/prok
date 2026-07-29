package com.systemsgo.hex.serialconsole.protocol

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.SerialParity
import com.systemsgo.hex.data.model.SerialRedirectMode
import com.systemsgo.hex.data.model.SerialStopBits
import com.systemsgo.hex.remote.*
import com.systemsgo.hex.rdp.serial.SerialNetworkBridge
import com.systemsgo.hex.serialconsole.usb.LineStatus
import com.systemsgo.hex.serialconsole.usb.UsbSerialDriverPort
import com.systemsgo.hex.serialconsole.usb.UsbSerialProbe
import com.systemsgo.hex.ssh.protocol.SshKeyMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Connection details for a standalone Serial Console session
 * ([com.systemsgo.hex.data.model.ProtocolType.SERIAL_CONSOLE]).
 *
 * NOT to be confused with [com.systemsgo.hex.data.model.SerialRedirectMode]'s
 * other consumer, [SerialNetworkBridge] — that one forwards a serial device
 * *into* an RDP session (MS-RDPESP); this one *is* the session, a direct
 * interactive terminal onto a serial console, exactly like
 * [com.systemsgo.hex.telnet.protocol.TelnetClient] or
 * [com.systemsgo.hex.rlogin.protocol.RloginClient] are.
 */
data class SerialConsoleCredentials(
    val host: String,
    val port: Int = 2217,
    val transport: SerialRedirectMode = SerialRedirectMode.RFC_2217,
    val baudRate: Int = 9600,
    val dataBits: Int = 8,
    val parity: SerialParity = SerialParity.NONE,
    val stopBits: SerialStopBits = SerialStopBits.ONE,
    /** Only meaningful when [transport] is LOCAL_DEVICE — see [SerialConsoleClient.connect]'s guard. */
    val localDevicePath: String = "",
    /**
     * Hardware RTS/CTS flow control, only meaningful when [transport] is
     * LOCAL_DEVICE — applied via [UsbSerialDriverPort.setFlowControl] in
     * [connectLocalDevice] after line coding is set. No RFC_2217
     * equivalent: the COM-PORT-OPTION subnegotiation has no flow-control
     * command, so this is silently ignored for that transport.
     */
    val hardwareFlowControl: Boolean = false,
)

/**
 * Terminal client for a standalone Serial Console session, exposed through
 * the same [RemoteSessionClient] surface [com.systemsgo.hex.telnet.protocol.TelnetClient]
 * uses, so the existing [com.systemsgo.hex.ui.screens.terminal.TerminalScreen]
 * UI and RdpSessionActivity plumbing drive it exactly the way they already
 * drive Telnet/SSH/Rlogin (see
 * [com.systemsgo.hex.data.model.ProtocolType.isTerminal]).
 *
 * ## Transport modes ([SerialConsoleCredentials.transport])
 * - **RAW_TCP**: byte-for-byte pipe to a network serial-device server (e.g.
 *   ser2net in raw mode, a KVM/PDU's serial-over-LAN port) — no line-control
 *   signalling, [SerialConsoleCredentials.baudRate]/etc. are not sent
 *   anywhere (the server and the physical device on its end are assumed
 *   already configured to match).
 * - **RFC_2217**: telnet COM-PORT-OPTION — same wire protocol
 *   [SerialNetworkBridge] speaks for the RDP-redirect feature, and this
 *   class reuses its IAC-escaping helpers ([SerialNetworkBridge.encodeIac]/
 *   `decodeIac`) rather than re-implementing them, since the escaping rule
 *   is identical. Unlike SerialNetworkBridge (which is *driven* by whatever
 *   baud/parity/etc. FreeRDP's serial channel sets on its PTY, since a real
 *   remote OS serial driver dictates those), this client is the only thing
 *   asking for line parameters at all, so it sends [SerialConsoleCredentials]'s
 *   baudRate/dataBits/parity/stopBits once at connect time via the same
 *   SET-BAUDRATE/SET-DATASIZE/SET-PARITY/SET-STOPSIZE subnegotiation
 *   commands, and simply logs (doesn't otherwise act on) any
 *   NOTIFY-MODEMSTATE/NOTIFY-LINESTATE the server sends back.
 * - **LOCAL_DEVICE** (direct USB-OTG serial adapter): implemented via
 *   Android's USB Host API against the drivers in
 *   [com.systemsgo.hex.serialconsole.usb.UsbSerialProbe] — CDC-ACM (the
 *   USB-IF standard class), Silicon Labs CP210x, FTDI, WCH CH340/CH341,
 *   and Prolific PL2303 today; see that file's class doc for exactly
 *   which chipsets/sub-generations that does and doesn't cover. No
 *   native/JNI code is involved — unlike the RDP-redirect feature's own
 *   LOCAL_DEVICE mode (which leans on FreeRDP's native code `open()`-ing a
 *   real tty path directly, since a genuine remote-OS driver is on the
 *   other end), a standalone terminal session talks to the USB device
 *   itself, so this is pure `UsbManager`/`UsbDeviceConnection` plumbing —
 *   see [connectLocalDevice]. [SerialConsoleCredentials.localDevicePath],
 *   if set, must match the target device's [UsbDevice.getDeviceName] (the
 *   `/dev/bus/usb/BBB/DDD`-style path `UsbManager.deviceList` reports it
 *   under — not a kernel serial-device path like `/dev/ttyUSB0`, which
 *   this app has no access to); left blank, [connectLocalDevice]
 *   auto-selects the sole matching candidate if exactly one supported
 *   adapter is attached, and fails with a clear error if there's none or
 *   more than one.
 */
class SerialConsoleClient(
    private val credentials: SerialConsoleCredentials,
    private val appContext: Context,
) : RemoteSessionClient {

    companion object {
        private const val TAG = "SerialConsoleClient"
        private const val CONNECT_TIMEOUT_MS = 15_000

        // LOCAL_DEVICE (USB) transport — see connectLocalDevice()/UsbSerialInputStream.
        /** Per-call timeout for each UsbSerialInputStream poll — short enough that disconnect() is noticed promptly, long enough not to busy-spin. */
        private const val USB_POLL_TIMEOUT_MS = 300
        private const val USB_WRITE_TIMEOUT_MS = 5000

        // SERIAL-CONSOLE (Part 6/N): background line-status poll loop — see pollLineStatusLoop().
        private const val LINE_STATUS_POLL_INTERVAL_MS = 500L
        private const val LINE_STATUS_POLL_TIMEOUT_MS = 200

        // Telnet/RFC 2217 protocol byte constants — same values as
        // SerialNetworkBridge/TelnetClient's own private copies (RFC 854/2217
        // are fixed wire protocols, not something to parameterize).
        private const val IAC = 255
        private const val WILL = 251
        private const val WONT = 252
        private const val DO = 253
        private const val DONT = 254
        private const val SB = 250
        private const val SE = 240
        private const val COM_PORT_OPTION = 44
        private const val OPT_ECHO = 1
        private const val OPT_SUPPRESS_GA = 3
        private const val CMD_SET_BAUDRATE = 1
        private const val CMD_SET_DATASIZE = 2
        private const val CMD_SET_PARITY = 3
        private const val CMD_SET_STOPSIZE = 4
        private fun intToBytes(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
        )
    }

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 1)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 64)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    /**
     * SERIAL-CONSOLE (Part 6/N): most recent [LineStatus] reported by
     * [pollLineStatusLoop] — LOCAL_DEVICE sessions only (RAW_TCP/RFC_2217
     * have no USB device to poll, so this simply stays `null` for them).
     * Meant for the terminal UI's status bar (e.g. a CTS/DSR indicator);
     * see [UsbSerialDriverPort.pollLineStatus]'s own doc for what `null`
     * from any individual poll means and why it's not surfaced as an error.
     */
    private val _lineStatus = MutableStateFlow<LineStatus?>(null)
    val lineStatus: StateFlow<LineStatus?> = _lineStatus.asStateFlow()

    override var latencyMs: Long = 0L
        private set

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var connected = false

    // ── LOCAL_DEVICE (USB) transport state — see connectLocalDevice() ──────
    private var usbConnection: UsbDeviceConnection? = null
    private var usbPort: UsbSerialDriverPort? = null

    private var ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        if (credentials.transport == SerialRedirectMode.LOCAL_DEVICE) {
            return@withContext connectLocalDevice()
        }

        try {
            _sessionState.emit(RemoteSessionState.CONNECTING)

            val connectStart = System.currentTimeMillis()
            val s = Socket()
            s.connect(InetSocketAddress(credentials.host, credentials.port), CONNECT_TIMEOUT_MS)
            s.soTimeout = 0
            s.tcpNoDelay = true
            latencyMs = System.currentTimeMillis() - connectStart

            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()
            connected = true

            if (credentials.transport == SerialRedirectMode.RFC_2217) {
                negotiateRfc2217AndApplyLineParams()
            }

            _sessionState.emit(RemoteSessionState.CONNECTED)
            ioScope.launch { readLoop() }
            true
        } catch (e: Exception) {
            // SEC-LOG: log only the exception class — same reasoning as
            // TelnetClient.connect()'s catch block; raw socket exception
            // messages can embed hostnames.
            Log.e(TAG, "Serial Console connect failed: ${e.javaClass.simpleName}")
            val userMessage = when {
                e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("timed out", ignoreCase = true) == true ->
                    appContext.getString(R.string.error_serial_console_timeout)
                e.message?.contains("refused", ignoreCase = true) == true ->
                    appContext.getString(R.string.error_serial_console_refused)
                else ->
                    appContext.getString(R.string.error_serial_console_connect_failed)
            }
            _error.emit(userMessage)
            _sessionState.emit(RemoteSessionState.ERROR)
            ioScope.cancel()
            cleanup()
            false
        }
    }

    /**
     * `LOCAL_DEVICE` path of [connect] — see class doc's LOCAL_DEVICE
     * section for the overall picture. Finds the target [UsbDevice] (by
     * [SerialConsoleCredentials.localDevicePath] if set, otherwise
     * auto-selecting when there's exactly one supported candidate
     * attached), requests USB permission if needed, opens it, picks a
     * driver via [UsbSerialProbe.createPort], and — on success — wires
     * that driver into [input]/[output] via [UsbSerialInputStream]/
     * [UsbSerialOutputStream] so [readLoop]/[sendText]/[disconnect] all
     * work completely unchanged, exactly as they already do for the
     * socket-based transports.
     */
    private suspend fun connectLocalDevice(): Boolean {
        _sessionState.emit(RemoteSessionState.CONNECTING)

        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            Log.w(TAG, "USB_SERVICE unavailable on this device")
            _error.emit(appContext.getString(R.string.error_serial_console_no_usb_host))
            _sessionState.emit(RemoteSessionState.ERROR)
            return false
        }

        val candidates = UsbSerialProbe.findCandidates(usbManager)
        val device: UsbDevice? = if (credentials.localDevicePath.isNotBlank()) {
            usbManager.deviceList.values.find { it.deviceName == credentials.localDevicePath }
        } else {
            candidates.singleOrNull()
        }

        if (device == null) {
            val message = when {
                credentials.localDevicePath.isNotBlank() ->
                    appContext.getString(R.string.error_serial_console_usb_device_not_found)
                candidates.isEmpty() ->
                    appContext.getString(R.string.error_serial_console_no_usb_device)
                else ->
                    appContext.getString(R.string.error_serial_console_ambiguous_usb_device)
            }
            Log.w(TAG, "connectLocalDevice: no unambiguous target device (path='${credentials.localDevicePath}', candidates=${candidates.size})")
            _error.emit(message)
            _sessionState.emit(RemoteSessionState.ERROR)
            return false
        }

        if (!usbManager.hasPermission(device)) {
            val granted = requestUsbPermission(usbManager, device)
            if (!granted) {
                _error.emit(appContext.getString(R.string.error_serial_console_usb_permission_denied))
                _sessionState.emit(RemoteSessionState.ERROR)
                return false
            }
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "openDevice failed for ${device.deviceName}")
            _error.emit(appContext.getString(R.string.error_serial_console_usb_open_failed))
            _sessionState.emit(RemoteSessionState.ERROR)
            return false
        }

        val port = UsbSerialProbe.createPort(usbManager, device, connection)
        if (port == null || !port.open()) {
            Log.e(TAG, "No supported USB-serial driver / interface claim failed for ${device.deviceName}")
            try { connection.close() } catch (e: Exception) { android.util.Log.d("SerialConsoleClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
            _error.emit(appContext.getString(R.string.error_serial_console_unsupported_usb_chipset))
            _sessionState.emit(RemoteSessionState.ERROR)
            return false
        }

        try {
            port.setLineCoding(credentials.baudRate, credentials.dataBits, credentials.stopBits.toCdcCode(), credentials.parity.toCdcCode())
        } catch (e: Exception) {
            // Best-effort, matching UsbSerialDriverPort.setLineCoding's own
            // doc — a chipset quirk here shouldn't abort an otherwise-usable
            // connection.
            Log.w(TAG, "setLineCoding failed: ${e.javaClass.simpleName}")
        }
        try {
            port.setFlowControl(credentials.hardwareFlowControl)
        } catch (e: Exception) {
            // Same best-effort reasoning as setLineCoding above — see
            // UsbSerialDriverPort.setFlowControl's doc (e.g. CDC-ACM's
            // no-op, or a transfer failure on a flaky hub) for why this
            // shouldn't abort an otherwise-usable connection either.
            Log.w(TAG, "setFlowControl failed: ${e.javaClass.simpleName}")
        }

        usbConnection = connection
        usbPort = port
        input = UsbSerialInputStream(port)
        output = UsbSerialOutputStream(port)
        connected = true

        _sessionState.emit(RemoteSessionState.CONNECTED)
        ioScope.launch { readLoop() }
        ioScope.launch { pollLineStatusLoop(port) }
        return true
    }

    /**
     * SERIAL-CONSOLE (Part 6/N): calls [UsbSerialDriverPort.pollLineStatus]
     * on a fixed cadence for the lifetime of a LOCAL_DEVICE session,
     * publishing whatever it returns through [lineStatus] — cancelled
     * automatically along with every other `ioScope` child when
     * [disconnect] calls `ioScope.cancel()`, same as [readLoop]'s own
     * coroutine. A single failed poll (chipset hiccup, transient USB
     * error) just logs and the loop continues; it's [readLoop] noticing a
     * real transport failure — not this loop — that ends the session.
     */
    private suspend fun pollLineStatusLoop(port: UsbSerialDriverPort) {
        while (connected) {
            try {
                val status = port.pollLineStatus(LINE_STATUS_POLL_TIMEOUT_MS)
                if (status != null) _lineStatus.emit(status)
            } catch (e: Exception) {
                Log.w(TAG, "Line-status poll failed: ${e.javaClass.simpleName}")
            }
            delay(LINE_STATUS_POLL_INTERVAL_MS)
        }
    }

    /**
     * Suspends until the user answers Android's USB-permission prompt (or
     * resolves immediately if [device] was already granted). Mirrors
     * [com.systemsgo.hex.smartcard.UsbCcidReader.discoverAndRequestPermission]'s
     * broadcast-receiver mechanics, adapted to a single-device suspend call
     * instead of a fire-and-forget callback since [connectLocalDevice] needs
     * the answer before it can proceed.
     */
    private suspend fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val action = "com.systemsgo.hex.serialconsole.USB_PERMISSION"
            val permissionIntent = PendingIntent.getBroadcast(
                appContext, 0, Intent(action).setPackage(appContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
            )
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    ctx.applicationContext.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation {
                try { appContext.unregisterReceiver(receiver) } catch (e: Exception) { android.util.Log.d("SerialConsoleClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
            }
            usbManager.requestPermission(device, permissionIntent)
        }

    /**
     * Adapts a [UsbSerialDriverPort] to [InputStream] so [readLoop] (shared
     * with every other transport) can read it unchanged. Internally polls
     * [UsbSerialDriverPort.read] with a bounded per-call timeout and loops
     * while [connected] stays true, rather than a single unbounded call —
     * that's what lets [disconnect] interrupt a blocked read promptly
     * (closing [UsbDeviceConnection] mid-transfer isn't guaranteed to
     * unblock a pending `bulkTransfer` the way closing a [Socket] reliably
     * unblocks a pending socket read).
     */
    private inner class UsbSerialInputStream(private val port: UsbSerialDriverPort) : InputStream() {
        override fun read(): Int = throw UnsupportedOperationException("UsbSerialInputStream only supports the bulk read(ByteArray) form readLoop() uses")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val tmp = if (off == 0) b else ByteArray(len)
            while (connected) {
                val n = port.read(tmp, USB_POLL_TIMEOUT_MS)
                if (n > 0) {
                    if (tmp !== b) System.arraycopy(tmp, 0, b, off, n)
                    return n
                }
                if (n < 0) return -1 // real transport error — treat like a closed socket (EOF)
                // n == 0: plain poll timeout, no data yet — loop again while still connected.
            }
            return -1
        }
    }

    /** [UsbSerialDriverPort] counterpart to [UsbSerialInputStream] for [sendText]'s writes. */
    private inner class UsbSerialOutputStream(private val port: UsbSerialDriverPort) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            var written = 0
            while (written < len) {
                val n = port.write(b, off + written, len - written, USB_WRITE_TIMEOUT_MS)
                if (n <= 0) throw java.io.IOException("USB serial write failed or timed out")
                written += n
            }
        }
        override fun flush() { /* bulkTransfer writes are already synchronous */ }
    }

    /**
     * Client-offers COM-PORT-OPTION (RFC 2217 handshake start, mirroring
     * [SerialNetworkBridge.connectRemote]'s own `negotiateRfc2217`), then —
     * since this client (unlike SerialNetworkBridge) is the one actually
     * choosing the line parameters, not just relaying whatever a remote tty
     * driver decided — immediately sends SET-BAUDRATE/SET-DATASIZE/
     * SET-PARITY/SET-STOPSIZE for [SerialConsoleCredentials.baudRate]/
     * dataBits/parity/stopBits. A server that doesn't understand
     * COM-PORT-OPTION at all simply won't reply to any of this (RFC 2217 has
     * no negotiation failure signal beyond that), so this never blocks
     * [connect] on a reply — the data channel itself still works as a plain
     * byte pipe either way, exactly like [SerialNetworkBridge].
     */
    private fun negotiateRfc2217AndApplyLineParams() {
        val out = output ?: return
        try {
            out.write(byteArrayOf(IAC.toByte(), WILL.toByte(), COM_PORT_OPTION.toByte()))
            out.flush()
            sendComPortCommand(CMD_SET_BAUDRATE, intToBytes(credentials.baudRate))
            sendComPortCommand(CMD_SET_DATASIZE, byteArrayOf(credentials.dataBits.toByte()))
            sendComPortCommand(CMD_SET_PARITY, byteArrayOf(credentials.parity.rfc2217Code.toByte()))
            sendComPortCommand(CMD_SET_STOPSIZE, byteArrayOf(credentials.stopBits.rfc2217Code.toByte()))
        } catch (e: Exception) {
            Log.w(TAG, "RFC 2217 negotiation/line-parameter setup failed: ${e.javaClass.simpleName}")
        }
    }

    private fun sendComPortCommand(command: Int, payload: ByteArray) {
        val out = output ?: return
        val body = byteArrayOf(COM_PORT_OPTION.toByte(), command.toByte()) + payload
        val escaped = SerialNetworkBridge.encodeIac(body, 0, body.size)
        val frame = byteArrayOf(IAC.toByte(), SB.toByte()) + escaped + byteArrayOf(IAC.toByte(), SE.toByte())
        try {
            synchronized(out) { out.write(frame); out.flush() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send RFC 2217 command $command", e)
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(4096)
        val decoder = Charsets.UTF_8.newDecoder()
        val textOut = java.io.ByteArrayOutputStream(4096)

        var state = ParseState.DATA
        val sbPayload = java.io.ByteArrayOutputStream(32)

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
                            if (credentials.transport == SerialRedirectMode.RFC_2217 && b == IAC) {
                                ParseState.IAC
                            } else {
                                textOut.write(b); ParseState.DATA
                            }
                        }
                        ParseState.IAC -> when (b) {
                            IAC -> { textOut.write(IAC); ParseState.DATA } // escaped literal 0xFF
                            WILL, WONT, DO, DONT -> ParseState.pendingFor(b)
                            SB -> { sbPayload.reset(); ParseState.SUBNEG }
                            else -> ParseState.DATA
                        }
                        ParseState.WILL_OPT, ParseState.WONT_OPT, ParseState.DO_OPT, ParseState.DONT_OPT -> {
                            respondToNegotiation(state, b); ParseState.DATA
                        }
                        ParseState.SUBNEG -> if (b == IAC) ParseState.SUBNEG_IAC else { sbPayload.write(b); ParseState.SUBNEG }
                        ParseState.SUBNEG_IAC -> if (b == SE) {
                            handleSubnegotiation(sbPayload.toByteArray())
                            ParseState.DATA
                        } else {
                            sbPayload.write(IAC); ParseState.SUBNEG // escaped IAC inside subnegotiation payload
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
                Log.e(TAG, "Serial Console read loop error: ${e.javaClass.simpleName}")
                _error.tryEmit(appContext.getString(R.string.error_connection_lost))
                _sessionState.tryEmit(RemoteSessionState.ERROR)
            }
        } finally {
            connected = false
            _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
        }
    }

    private enum class ParseState {
        DATA, IAC, WILL_OPT, WONT_OPT, DO_OPT, DONT_OPT, SUBNEG, SUBNEG_IAC;
        companion object {
            fun pendingFor(command: Int): ParseState = when (command) {
                WILL -> WILL_OPT
                WONT -> WONT_OPT
                DO -> DO_OPT
                DONT -> DONT_OPT
                else -> DATA
            }
        }
    }

    /** Declines every telnet option except SUPPRESS-GO-AHEAD/ECHO — same minimal-but-correct stance as TelnetClient's own respondToNegotiation. */
    private fun respondToNegotiation(pending: ParseState, option: Int) {
        val reply: Pair<Int, Int>? = when (pending) {
            ParseState.DO_OPT -> when (option) {
                OPT_SUPPRESS_GA -> WILL to option
                else -> WONT to option
            }
            ParseState.WILL_OPT -> when (option) {
                OPT_ECHO, OPT_SUPPRESS_GA -> DO to option
                else -> DONT to option
            }
            else -> null // acknowledging a DONT/WONT needs no reply
        }
        if (reply != null) {
            val (verb, opt) = reply
            try {
                output?.write(byteArrayOf(IAC.toByte(), verb.toByte(), opt.toByte()))
                output?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write telnet negotiation reply", e)
            }
        }
    }

    /** Logs the server's NOTIFY-MODEMSTATE/NOTIFY-LINESTATE replies (and this client's own SET-* echoes) — nothing in this session currently surfaces them further; see class doc. */
    private fun handleSubnegotiation(payload: ByteArray) {
        val decoded = SerialNetworkBridge.decodeIac(payload)
        if (decoded.isEmpty() || (decoded[0].toInt() and 0xFF) != COM_PORT_OPTION) return
        if (decoded.size < 2) return
        Log.d(TAG, "RFC 2217 COM-PORT-OPTION reply code=${decoded[1].toInt() and 0xFF}")
    }

    // ── Input — terminal sessions take raw text, not framebuffer events ────

    override fun sendText(text: String) {
        ioScope.launch {
            try {
                val raw = text.toByteArray(Charsets.UTF_8)
                val toWrite = if (credentials.transport == SerialRedirectMode.RFC_2217) {
                    SerialNetworkBridge.encodeIac(raw, 0, raw.size)
                } else {
                    raw
                }
                output?.write(toWrite)
                output?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send terminal input", e)
            }
        }
    }

    override fun sendCtrlAltDel() { /* not meaningful over a serial console */ }
    override fun sendMouseMove(x: Int, y: Int) { /* terminal sessions don't use pointer input */ }
    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) { }
    override fun sendMouseScroll(x: Int, y: Int, delta: Int) { }
    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        // Reuse the same PC-scan-code -> ANSI/VT100 escape sequence mapping
        // every other terminal protocol in this app uses, since they all
        // share the same TerminalScreen/ExtraKeysBar UI.
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
        try { socket?.close() } catch (e: Exception) { android.util.Log.d("SerialConsoleClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        try { usbPort?.close() } catch (e: Exception) { android.util.Log.d("SerialConsoleClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        try { usbConnection?.close() } catch (e: Exception) { android.util.Log.d("SerialConsoleClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        socket = null; input = null; output = null
        usbPort = null; usbConnection = null
        _lineStatus.value = null
    }
}

/** [SerialParity] → USB CDC-ACM `SET_LINE_CODING`'s bParityType (0=None..4=Space) — see [UsbSerialDriverPort.setLineCoding]'s doc for why every driver in that package shares this convention. */
private fun SerialParity.toCdcCode(): Int = when (this) {
    SerialParity.NONE -> 0
    SerialParity.ODD -> 1
    SerialParity.EVEN -> 2
    SerialParity.MARK -> 3
    SerialParity.SPACE -> 4
}

/** [SerialStopBits] → USB CDC-ACM `SET_LINE_CODING`'s bCharFormat (0=1, 1=1.5, 2=2 stop bits). Deliberately NOT [SerialStopBits.rfc2217Code] — RFC 2217's SET-STOPSIZE codes use a different numbering (TWO=2, ONE_POINT_FIVE=3). */
private fun SerialStopBits.toCdcCode(): Int = when (this) {
    SerialStopBits.ONE -> 0
    SerialStopBits.ONE_POINT_FIVE -> 1
    SerialStopBits.TWO -> 2
}
