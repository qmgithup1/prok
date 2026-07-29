package com.systemsgo.hex.rdp.serial

import android.net.LocalSocket
import android.util.Log
import com.systemsgo.hex.data.model.SerialRedirectMode
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client-side implementation of the two SERIAL-OVER-NETWORK modes
 * ([SerialRedirectMode.RAW_TCP] / [SerialRedirectMode.RFC_2217]) — see
 * [com.systemsgo.hex.data.model.RdpProfile.serialRedirectMode]'s doc
 * comment for the feature this backs.
 *
 * ## Responsibilities
 * - Opens and owns the TCP connection to the network device server (e.g.
 *   a ser2net instance, a KVM/PDU's serial-over-LAN port).
 * - RAW_TCP: the connection is a byte-for-byte pipe, nothing else.
 * - RFC_2217: implements the client side of RFC 2217 (telnet
 *   COM-PORT-OPTION) — option negotiation (WILL/DO COM-PORT-OPTION),
 *   encoding outgoing SET-BAUDRATE/SET-DATASIZE/SET-PARITY/SET-STOPSIZE/
 *   SET-CONTROL/PURGE-DATA subnegotiation commands, decoding incoming
 *   SET-BAUDRATE/.../NOTIFY-MODEMSTATE/NOTIFY-LINESTATE replies, and
 *   telnet IAC escaping/unescaping of the data stream (a literal 0xFF byte
 *   in the data path is doubled per telnet's own SB rules — see
 *   [encodeIac]/[decodeIac]). This half is pure, dependency-free protocol
 *   logic and is unit-testable independent of any Android/native plumbing
 *   (see SerialNetworkBridgeTest).
 * - Once [attachLocalPeer] is called with the [LocalSocket] end of the
 *   native PTY bridge (see `systemsgo_serial_bridge.c`'s top-of-file doc and
 *   [com.systemsgo.hex.rdp.native.AFreeRdpBridge.resolveEffectiveSerialPath]),
 *   relays the decoded data payload bidirectionally between that local
 *   peer and the remote TCP/RFC-2217 connection. [setDtr]/[setRts]/
 *   [setBaudRate] are called *by native code* (via JNI, see
 *   `systemsgo_serial_bridge.c`'s `relay_thread_main`) whenever it detects the
 *   PTY slave side (opened by FreeRDP) changed those — so this class never
 *   has to know about ptys, ioctls, or JNI itself; it only ever sees plain
 *   method calls and a byte stream, exactly like [SerialLineListener] on
 *   the inbound (server -> client) side.
 *
 * ## Native counterpart (see `systemsgo_serial_bridge.c`)
 * FreeRDP's serial channel (MS-RDPESP) is written against a real local tty:
 * it `open()`s a device path and issues termios/TIOCM* ioctls directly on
 * that fd from native code. `systemsgo_serial_bridge.c` gives it a real PTY
 * slave path (via `openpty()`) so those calls succeed unmodified, and
 * relays the PTY *master* side to the [LocalSocket] this class attaches —
 * see that file's doc comment for the full data flow and the explicitly
 * flagged caveats around the DTR/RTS/CTS/DSR null-modem line mapping,
 * which needs on-device verification against a real RFC 2217 server.
 *
 * @param mode Must be [SerialRedirectMode.RAW_TCP] or
 *   [SerialRedirectMode.RFC_2217] — never called with LOCAL_DEVICE, see
 *   AFreeRdpBridge.resolveEffectiveSerialPath's guard.
 */
class SerialNetworkBridge(
    private val mode: SerialRedirectMode,
    private val host: String,
    private val port: Int,
    private val listener: SerialLineListener = SerialLineListener.NONE,
) {
    /** Line/modem-status callbacks an RFC 2217 session can receive from the server. Ignored entirely in RAW_TCP mode. */
    interface SerialLineListener {
        /** Bitmask per RFC 2217 §3: bits 4-7 are CTS/DSR/RI/CD state, bits 0-3 are the corresponding delta-since-last-notify bits. */
        fun onModemState(mask: Int) {}
        /** Bitmask per RFC 2217 §3: bit0 data-ready(TIMEOUT), bit1 overrun, bit2 parity-error, bit3 framing-error, bit4 break, bit5/6 THRE/TEMT, bit7 conveyed-error. */
        fun onLineState(mask: Int) {}
        companion object { val NONE = object : SerialLineListener {} }
    }

    private val closed = AtomicBoolean(false)
    private var socket: Socket? = null
    private var relayThread: Thread? = null
    private var peerRelayThread: Thread? = null
    @Volatile private var localPeer: LocalSocket? = null

    /**
     * Connects to [host]:[port] and, for RFC_2217, performs the initial
     * telnet COM-PORT-OPTION negotiation. Must be called (and return
     * `true`) before [attachLocalPeer]. Returns `false` on any connect or
     * negotiation I/O error, in which case the caller should treat the
     * whole serial redirect as unavailable for this session rather than
     * registering FreeRDP's "serial" rdpdr device against a dead bridge.
     */
    fun connectRemote(): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            socket = s
            if (mode == SerialRedirectMode.RFC_2217) {
                negotiateRfc2217(s.outputStream)
            }
            relayThread = Thread({ relayLoop(s.inputStream) }, "SerialNetworkBridge-remote-$host:$port").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "Failed to connect serial-over-network endpoint $host:$port ($mode)", e)
            close()
            false
        }
    }

    /**
     * Attaches the Kotlin end of the native PTY bridge's local peer socket
     * (see `systemsgo_serial_bridge.c` — it `connect()`s to the abstract-
     * namespace [android.net.LocalServerSocket] this was accepted from) and
     * starts relaying peer -> remote in a dedicated thread. The remote ->
     * peer direction is already running (started by [connectRemote]) and
     * simply starts writing to this peer as soon as it's set.
     */
    fun attachLocalPeer(peer: LocalSocket) {
        localPeer = peer
        peerRelayThread = Thread({
            try {
                val input = peer.inputStream
                val buf = ByteArray(4096)
                while (!closed.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    write(buf, 0, n)
                }
            } catch (e: IOException) {
                if (!closed.get()) Log.w(TAG, "Local-peer -> remote relay ended", e)
            } finally {
                close()
            }
        }, "SerialNetworkBridge-localpeer-$host:$port").apply {
            isDaemon = true
            start()
        }
    }

    /** Writes raw serial payload bytes to the remote endpoint, IAC-escaping them first in RFC_2217 mode. */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val out = socket?.outputStream ?: return
        try {
            synchronized(out) {
                if (mode == SerialRedirectMode.RFC_2217) {
                    out.write(encodeIac(data, offset, length))
                } else {
                    out.write(data, offset, length)
                }
                out.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Write to serial-over-network endpoint failed", e)
        }
    }

    /** Writes decoded payload bytes to the attached native local peer (called from [relayLoop]/[relayRawTcp] for the remote -> PTY direction). No-op if [attachLocalPeer] hasn't been called yet — those bytes are simply dropped, matching "nothing is listening on the serial port yet". */
    private fun writeToLocalPeer(data: ByteArray, length: Int) {
        val peer = localPeer ?: return
        try {
            peer.outputStream.write(data, 0, length)
        } catch (e: IOException) {
            if (!closed.get()) Log.w(TAG, "Remote -> local-peer relay write failed", e)
        }
    }

    // ── RFC 2217 outgoing control requests ─────────────────────────────
    // NOTE: setDtr/setRts/setBaudRate below are called *by native code*
    // (systemsgo_serial_bridge.c, via JNI) whenever it detects FreeRDP changed
    // them on the PTY slave — see this class's doc comment. Their method
    // signatures (Z)V / (I)V are looked up by exact name+signature from
    // native code, so do not rename or re-sign them without updating
    // systemsgo_serial_bridge.c's GetMethodID calls to match.

    fun setBaudRate(baud: Int) = sendComPortCommand(CMD_SET_BAUDRATE, intToBytes(baud))
    fun setDataBits(bits: Int) = sendComPortCommand(CMD_SET_DATASIZE, byteArrayOf(bits.toByte()))
    fun setParity(parity: Int) = sendComPortCommand(CMD_SET_PARITY, byteArrayOf(parity.toByte()))
    fun setStopBits(stopBits: Int) = sendComPortCommand(CMD_SET_STOPSIZE, byteArrayOf(stopBits.toByte()))
    fun setDtr(asserted: Boolean) = sendComPortCommand(CMD_SET_CONTROL, byteArrayOf((if (asserted) 8 else 9).toByte()))
    fun setRts(asserted: Boolean) = sendComPortCommand(CMD_SET_CONTROL, byteArrayOf((if (asserted) 11 else 12).toByte()))
    fun purge(what: PurgeTarget) = sendComPortCommand(CMD_PURGE_DATA, byteArrayOf(what.wireValue.toByte()))

    enum class PurgeTarget(val wireValue: Int) { RX(1), TX(2), BOTH(3) }

    private fun sendComPortCommand(command: Int, payload: ByteArray) {
        if (mode != SerialRedirectMode.RFC_2217) return
        val out = socket?.outputStream ?: return
        val sb = ByteArrayOutputStreamCompat()
        sb.write(IAC); sb.write(SB); sb.write(COM_PORT_OPTION_CLIENT)
        sb.write(command)
        sb.writeAll(escapeIacInPlace(payload))
        sb.write(IAC); sb.write(SE)
        try {
            synchronized(out) { out.write(sb.toByteArray()); out.flush() }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send RFC 2217 command $command", e)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        relayThread?.interrupt()
        peerRelayThread?.interrupt()
        try { socket?.close() } catch (e: IOException) { android.util.Log.d("SerialNetworkBridge", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
        try { localPeer?.close() } catch (e: IOException) { android.util.Log.d("SerialNetworkBridge", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
        socket = null
        localPeer = null
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun negotiateRfc2217(out: OutputStream) {
        // Client offers COM-PORT-OPTION; a compliant RFC 2217 server
        // answers IAC DO COM-PORT-OPTION on the inbound side (handled in
        // relayLoop's telnet state machine below).
        out.write(byteArrayOf(IAC.toByte(), WILL.toByte(), COM_PORT_OPTION_CLIENT.toByte()))
        out.flush()
    }

    /** Telnet-stream state machine: strips/handles IAC sequences, feeds COM-PORT-OPTION replies to [listener], and forwards plain data bytes to [writeToLocalPeer]. RAW_TCP mode skips all of this and forwards every byte verbatim. */
    private fun relayLoop(input: InputStream) {
        if (mode == SerialRedirectMode.RAW_TCP) {
            relayRawTcp(input)
            return
        }
        val sbBuffer = ByteArrayOutputStreamCompat()
        val dataBuffer = ByteArrayOutputStreamCompat()
        var inSubnegotiation = false
        try {
            while (!closed.get()) {
                val b = input.read()
                if (b < 0) break
                when {
                    b == IAC && !inSubnegotiation -> {
                        flushDataBuffer(dataBuffer)
                        val cmd = input.read()
                        if (cmd < 0) break
                        when (cmd) {
                            SB -> inSubnegotiation = true
                            WILL, WONT, DO, DONT -> input.read() // consume the option byte; we only ever offer COM-PORT-OPTION ourselves
                            IAC -> dataBuffer.write(IAC) // escaped literal 0xFF in the data stream
                            else -> {} // NOP/other telnet commands: ignore
                        }
                    }
                    inSubnegotiation && b == IAC -> {
                        val next = input.read()
                        if (next == SE) {
                            inSubnegotiation = false
                            handleSubnegotiation(sbBuffer.toByteArray())
                            sbBuffer.reset()
                        } else {
                            sbBuffer.write(IAC) // escaped IAC inside subnegotiation payload
                        }
                    }
                    inSubnegotiation -> sbBuffer.write(b)
                    else -> dataBuffer.write(b)
                }
            }
            flushDataBuffer(dataBuffer)
        } catch (e: IOException) {
            if (!closed.get()) Log.w(TAG, "Serial-over-network relay loop ended", e)
        } finally {
            close()
        }
    }

    private fun flushDataBuffer(buf: ByteArrayOutputStreamCompat) {
        if (buf.size() == 0) return
        writeToLocalPeer(buf.toByteArray(), buf.size())
        buf.reset()
    }

    private fun relayRawTcp(input: InputStream) {
        val buf = ByteArray(4096)
        try {
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) break
                writeToLocalPeer(buf, n)
            }
        } catch (e: IOException) {
            if (!closed.get()) Log.w(TAG, "Raw-TCP serial relay loop ended", e)
        } finally {
            close()
        }
    }

    private fun handleSubnegotiation(payload: ByteArray) {
        if (payload.isEmpty() || payload[0].toInt() and 0xFF != COM_PORT_OPTION_SERVER) return
        if (payload.size < 2) return
        when (payload[1].toInt() and 0xFF) {
            REPLY_NOTIFY_MODEMSTATE -> if (payload.size >= 3) listener.onModemState(payload[2].toInt() and 0xFF)
            REPLY_NOTIFY_LINESTATE -> if (payload.size >= 3) listener.onLineState(payload[2].toInt() and 0xFF)
            // SET-BAUDRATE/SET-DATASIZE/SET-PARITY/SET-STOPSIZE/SET-CONTROL
            // echo-back replies (server confirming what it applied) are
            // intentionally not surfaced yet — no caller reads them today;
            // add a matching SerialLineListener callback here if a future
            // caller needs to reconcile them.
        }
    }

    companion object {
        private const val TAG = "SerialNetworkBridge"
        private const val CONNECT_TIMEOUT_MS = 8_000

        // Telnet protocol bytes (RFC 854).
        private const val IAC = 255
        private const val WILL = 251
        private const val WONT = 252
        private const val DO = 253
        private const val DONT = 254
        private const val SB = 250
        private const val SE = 240

        // RFC 2217 COM-PORT-OPTION: 44 (0x2C). Both client-originated
        // commands and server-originated replies are wrapped in the same
        // option number on the wire (RFC 2217 §2) — kept as two named
        // constants here only for readability at each call site.
        private const val COM_PORT_OPTION_CLIENT = 44
        private const val COM_PORT_OPTION_SERVER = 44

        // Client -> server command codes (RFC 2217 §3).
        private const val CMD_SET_BAUDRATE = 1
        private const val CMD_SET_DATASIZE = 2
        private const val CMD_SET_PARITY = 3
        private const val CMD_SET_STOPSIZE = 4
        private const val CMD_SET_CONTROL = 5
        private const val CMD_PURGE_DATA = 12

        // Server -> client reply command codes (RFC 2217 §3: request code + 100).
        private const val REPLY_NOTIFY_MODEMSTATE = 107
        private const val REPLY_NOTIFY_LINESTATE = 106

        private fun intToBytes(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
        )

        /** Doubles any literal 0xFF byte per telnet's SB-payload IAC-escaping rule. */
        internal fun escapeIacInPlace(data: ByteArray): ByteArray = encodeIac(data, 0, data.size)

        internal fun encodeIac(data: ByteArray, offset: Int, length: Int): ByteArray {
            val out = ByteArrayOutputStreamCompat()
            for (i in offset until offset + length) {
                val v = data[i].toInt() and 0xFF
                out.write(v)
                if (v == IAC) out.write(IAC)
            }
            return out.toByteArray()
        }

        /** Inverse of [encodeIac] — collapses doubled 0xFF bytes back to one. Exposed for unit testing the wire format independent of the live relay loop. */
        internal fun decodeIac(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStreamCompat()
            var i = 0
            while (i < data.size) {
                val v = data[i].toInt() and 0xFF
                out.write(v)
                if (v == IAC && i + 1 < data.size && (data[i + 1].toInt() and 0xFF) == IAC) i++
                i++
            }
            return out.toByteArray()
        }
    }
}

/** Minimal allocation-light growable byte buffer — avoids pulling in java.io.ByteArrayOutputStream's synchronized methods on a per-byte hot path. */
private class ByteArrayOutputStreamCompat(initialCapacity: Int = 32) {
    private var buf = ByteArray(initialCapacity)
    private var count = 0
    fun write(b: Int) {
        if (count == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[count++] = b.toByte()
    }
    fun writeAll(bytes: ByteArray) { for (b in bytes) write(b.toInt() and 0xFF) }
    fun reset() { count = 0 }
    fun size(): Int = count
    fun toByteArray(): ByteArray = buf.copyOf(count)
}
