package com.systemsgo.hex.serialconsole.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * SERIAL-CONSOLE FEATURE (Parts 2-3/N): direct USB-OTG serial-adapter support
 * for [com.systemsgo.hex.serialconsole.protocol.SerialConsoleClient]'s
 * `LOCAL_DEVICE` transport. Talks straight to the chipset over Android's USB
 * Host API (`android.hardware.usb`) — the same approach (and, for the
 * permission/bulk-transfer plumbing, the same overall shape) as
 * [com.systemsgo.hex.smartcard.UsbCcidReader] uses for CCID smart-card
 * readers, since Android has no kernel-level `/dev/ttyUSBx` a normal app can
 * just `open()` (that path is what the *RDP-redirect* feature's own
 * `LOCAL_DEVICE` mode uses instead, via FreeRDP's native code — see
 * [com.systemsgo.hex.data.model.SerialRedirectMode]'s doc — but that only
 * works when the OS/kernel already exposes and grants a real tty node,
 * which isn't the case for an app-level USB-host client like this one).
 *
 * ## Chipset coverage
 * Implements two drivers, chosen to cover the largest share of real
 * hardware for the least protocol-guesswork risk:
 * - [CdcAcmSerialPort] — the USB-IF standard "Communications Device Class /
 *   Abstract Control Model" (CDC-ACM). A real, fully-specified USB class
 *   (not a reverse-engineered vendor protocol), implemented natively by
 *   most Arduino-style boards (Uno R4, Leonardo, Due, Zero, ...), ESP32's
 *   native-USB variants, and a good share of "USB to TTL" adapters sold
 *   today.
 * - [Cp210xSerialPort] — Silicon Labs CP210x (CP2102/CP2102N/CP2104/CP2109/
 *   ...), one of the two or three most common chipsets on cheap USB-serial
 *   cables and on-board USB-UART bridges (e.g. most ESP32 dev boards).
 *   Register layout/values here match the publicly documented behavior
 *   used by the Linux kernel's own `cp210x` driver and Silicon Labs' AN571.
 *
 * ## Part 3/N — FTDI and WCH CH340/CH341
 * Adds [FtdiSerialPort] and [Ch340SerialPort]. Both are reverse-engineered
 * vendor protocols rather than a USB-IF standard class, but — unlike the
 * "deliberately not implemented" note this doc used to carry — both are
 * now implemented against register-level behavior that's independently
 * corroborated across multiple long-maintained, public GPL driver
 * implementations (the Linux kernel's own `ftdi_sio.c`/`ch341.c`, in
 * upstream `torvalds/linux`) and FTDI's own published app note
 * (AN232B-05, for the baud-divisor encoding). None of that GPL driver
 * source is copied here — only the *protocol facts* it documents (vendor
 * request numbers, register addresses, bit layouts, the divisor formula)
 * are reused, reimplemented independently in this file's own style, since
 * copying GPL-licensed source into this app would carry that license.
 * - [FtdiSerialPort] — FT232R/FT230X/FT231X/FT234X only (single-port,
 *   3 MHz-base-clock parts). Deliberately excludes the H-series
 *   (FT232H/2232H/4232H), which uses a different 120 MHz-derived clock
 *   tree and multi-channel indexing this pass hasn't verified.
 * - [Ch340SerialPort] — WCH CH340/CH341 in UART mode. Implements the
 *   prescaler/divisor baud formula and both this chip's older
 *   (pre-v0x30) and newer single-register line-control formats.
 *
 * ## Part 4/N — Prolific PL2303
 * Adds [Pl2303SerialPort]. This chipset was withheld from Parts 2-3 because
 * its vendor-request set and baud-divisor scheme actually *differ* across
 * sub-generations (H/HX/HXD use one direct baud-rate table plus a divisor
 * fallback; HXN uses a different vendor-request number entirely and
 * rejects the old one outright; TA/TB add their own alternate divisor
 * table) — using any single one of those against the wrong sub-generation
 * is exactly the "garbled line settings instead of a clean error" failure
 * mode this file exists to avoid. What unblocks it here is sub-generation
 * detection *before* any line-format request is sent — [Pl2303SerialPort]
 * reads the raw USB device descriptor (`bDeviceClass`/`bMaxPacketSize0`/
 * `bcdUSB`/`bcdDevice` — fields Android's [UsbDevice] doesn't expose
 * itself, hence the manual `GET_DESCRIPTOR`) and, where that alone is
 * ambiguous, probes the legacy `PL2303_READ_TYPE_HX_STATUS` vendor-read
 * the newer HXN silicon rejects — mirroring `pl2303_detect_type`/
 * `pl2303_supports_hx_status` in the Linux kernel's own `pl2303.c`/
 * `pl2303.h`. As with Part 3/N, only the protocol facts those files
 * document (vendor request numbers, the sub-generation ID table, both
 * divisor formulas) are reused here — reimplemented independently in this
 * file's own style — not the GPL source itself. A sub-generation this
 * table doesn't recognize is treated as unsupported rather than guessed.
 *
 * A device matching none of the above surfaces as
 * `error_serial_console_unsupported_usb_chipset` rather than silently
 * failing — see `UsbSerialProbe.createPort`.
 *
 * ## Part 6/N — interrupt-endpoint line status
 * Adds [LineStatus] and [UsbSerialDriverPort.pollLineStatus]. Each driver's
 * `probe()` now also looks for an interrupt-IN endpoint alongside its bulk
 * in/out pair and, where one exists, [BaseUsbSerialPort.endpointInterrupt]
 * stores it — but "an endpoint exists" and "this file has verified what its
 * packets mean" are two different things per chipset:
 * - [Pl2303SerialPort] — fully documented: `pl2303.c`'s UART_STATE byte,
 *   including the HXN sub-generation's different offset into the packet
 *   (see file doc's Part 4/N note on why sub-generation already matters
 *   everywhere else in this class).
 * - [CdcAcmSerialPort] — the USB-IF class spec's own SERIAL_STATE
 *   notification, sent on the *control* interface's interrupt-IN endpoint.
 *   Uniform across compliant devices, but the bitmap itself has no CTS bit
 *   at all (a real spec gap, not a parsing omission here — see that class's
 *   doc).
 * - [FtdiSerialPort] — as file doc's Part 3/N note already says, this
 *   chipset has no separate interrupt endpoint; its modem/line-status bytes
 *   ride along in every bulk-IN packet's existing 2-byte header instead, so
 *   [FtdiSerialPort.pollLineStatus] just returns whatever [FtdiSerialPort.read]
 *   most recently parsed from that header rather than generating its own
 *   USB traffic.
 * - [Cp210xSerialPort] — common CP210x configurations don't reliably expose
 *   a genuine notification interrupt endpoint the way CDC-ACM/PL2303 do, so
 *   rather than depend on one, this driver uses the documented
 *   `CP210X_GET_MDMSTS` vendor control-read instead (AN571 / `cp210x.c`) —
 *   same modem-line information, different transport. That request has no
 *   framing/parity/overrun-error bits, so those three always read `false`
 *   for this chipset.
 * - [Ch340SerialPort] — genuine WCH silicon does expose an interrupt-IN
 *   endpoint carrying an inverted-logic modem-status byte (`ch341.c`'s
 *   `ch341_update_line_status`), but the cheap clones this file's VID/PID
 *   table also matches aren't all guaranteed to implement it faithfully —
 *   [probe] only *looks* for the endpoint, so a clone that omits or
 *   misimplements it simply yields `pollLineStatus() == null` via the
 *   ordinary "no endpoint found" / "packet too short" path rather than a
 *   special case.
 *
 * A `null` [pollLineStatus] result is deliberately overloaded (no endpoint
 * this device/chipset combination has, a plain per-call timeout, or a
 * malformed/too-short packet) — see that method's own doc for why callers
 * (`SerialConsoleClient`'s background poll loop) should treat all three the
 * same way: "nothing new to show", never an error.
 */
interface UsbSerialDriverPort {
    /** Human-readable chipset name, surfaced in logs/errors only. */
    val displayName: String

    /**
     * Claims whatever interface(s) this port needs from the already-opened
     * [UsbDeviceConnection] it was constructed with. Returns `false` (rather
     * than throwing) on any failure — caller treats that as "device present
     * but not usable" (e.g. interface already claimed by another process).
     */
    fun open(): Boolean

    /**
     * Programs baud rate and line format. [stopBitsCode]/[parityCode] use
     * the USB CDC-ACM `SET_LINE_CODING` convention (0/1/2 = 1/1.5/2 stop
     * bits; 0..4 = None/Odd/Even/Mark/Space) regardless of chipset — each
     * driver translates into its own wire format internally. Best-effort:
     * a chipset that rejects a combination (e.g. an unsupported baud
     * divisor) logs a warning rather than throwing, matching how
     * [com.systemsgo.hex.smartcard.UsbCcidReader] treats hardware
     * quirks as non-fatal where it can.
     */
    fun setLineCoding(baudRate: Int, dataBits: Int, stopBitsCode: Int, parityCode: Int)

    /**
     * Blocking bulk-IN read with a bounded per-call [timeoutMs] (so a
     * caller polling in a `while (stillConnected)` loop — see
     * `SerialConsoleClient`'s `UsbSerialInputStream` — can still notice a
     * disconnect promptly instead of blocking forever). Returns the byte
     * count read, `0` on a plain timeout (no data, connection still fine —
     * caller should just call again), or `-1` on a real transport error.
     */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    /** Blocking bulk-OUT write of `buffer[offset, offset+length)`. Returns bytes written, or `-1` on error. */
    fun write(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /**
     * SERIAL-CONSOLE (Part 5/N): enables ([rtsCts]=true) or disables RTS/CTS
     * *hardware* flow control at the chipset level — i.e. the chip itself
     * pauses/resumes the UART based on the CTS input pin, rather than this
     * app watching CTS and pausing writes in software (which USB's own
     * buffering makes too laggy to prevent overrun at high baud rates
     * anyway). Best-effort like [setLineCoding]: a chipset/driver that has
     * no such mechanism (or none this file has verified) just logs and
     * leaves flow control off rather than throwing — callers should not
     * treat this as fatal to the connection, matching how every other
     * best-effort call in this interface already behaves.
     */
    fun setFlowControl(rtsCts: Boolean)

    /**
     * SERIAL-CONSOLE (Part 6/N): best-effort read of the chipset's current
     * modem-control-line state (CTS/DSR/DCD/RI) and, where the chipset
     * exposes them, its UART break/framing/parity/overrun error flags.
     * [timeoutMs] bounds a single poll attempt the same way it does for
     * [read] — meant to be called from a background poll loop (see
     * `SerialConsoleClient`), not the data-transfer hot path.
     *
     * Returns `null` for any of: this chipset/device has no working status
     * source at all (see file doc's Part 6/N section per-chipset), the poll
     * simply timed out with nothing new to report, or the response was too
     * short/malformed to parse. Callers should treat all three identically
     * — "no update this round" — never as a fatal error; this call must
     * never throw.
     */
    fun pollLineStatus(timeoutMs: Int): LineStatus?

    /** Releases the claimed interface(s). Safe to call more than once. */
    fun close()
}

/**
 * SERIAL-CONSOLE (Part 6/N): a snapshot of the chipset-reported modem
 * control lines and UART error flags, as returned by
 * [UsbSerialDriverPort.pollLineStatus]. Every field defaults to `false`
 * rather than being nullable/optional per-field, since a chipset that
 * doesn't report a given line/flag at all (see that method's doc for which
 * driver reports what) has no meaningful "unknown" state to distinguish
 * from "not asserted" here — callers that care about that distinction
 * should consult the per-chipset doc instead of this data class alone.
 */
data class LineStatus(
    /** Clear To Send — asserted by the far end/DCE when it's ready to receive. Not reported by CDC-ACM's SERIAL_STATE notification at all (see [CdcAcmSerialPort] doc) — always `false` there. */
    val cts: Boolean = false,
    /** Data Set Ready — asserted by the far end/DCE once it's powered up and ready. */
    val dsr: Boolean = false,
    /** Data Carrier Detect — asserted while a "carrier" (link/session) is present on the line. */
    val dcd: Boolean = false,
    /** Ring Indicator — asserted momentarily on an incoming call signal; rarely meaningful outside modem hardware. */
    val ring: Boolean = false,
    /** A break condition (line held low longer than one character time) was seen on the UART's RX line. */
    val breakError: Boolean = false,
    /** A framing error (missing/misplaced stop bit) was seen on the UART's RX line. */
    val frameError: Boolean = false,
    /** A parity error was seen on the UART's RX line, per the parity mode [UsbSerialDriverPort.setLineCoding] last configured. */
    val parityError: Boolean = false,
    /** The UART's receive buffer overran — incoming bytes were dropped before this driver's own [UsbSerialDriverPort.read] could collect them. */
    val overrunError: Boolean = false,
)

/** Shared bulk-transfer plumbing every driver here needs, so each concrete class only has to supply its own control-transfer/line-coding logic. */
private abstract class BaseUsbSerialPort(
    protected val connection: UsbDeviceConnection,
) : UsbSerialDriverPort {
    protected abstract val endpointIn: UsbEndpoint
    protected abstract val endpointOut: UsbEndpoint

    /**
     * SERIAL-CONSOLE (Part 6/N): the chipset's interrupt-IN status endpoint,
     * if `probe()` found one — `null` for a device/chipset that has none at
     * all. Populated by each subclass's own constructor/companion `probe()`
     * alongside [endpointIn]/[endpointOut]; there is no separate per-endpoint
     * claim call in Android's USB Host API (`claimInterface` already covers
     * every endpoint on that interface, this one included), so "claiming" it
     * in practice just means finding and storing it here for
     * [readInterruptPacket]/[pollLineStatus] to use.
     */
    protected open val endpointInterrupt: UsbEndpoint? = null

    override fun read(buffer: ByteArray, timeoutMs: Int): Int =
        connection.bulkTransfer(endpointIn, buffer, buffer.size, timeoutMs)

    override fun write(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        // UsbDeviceConnection.bulkTransfer(endpoint, buffer, offset, length, timeout)
        // needs API 26 (this app's minSdk), which is why this overload (not
        // the offset-less one) is used directly rather than copying a slice.
        return connection.bulkTransfer(endpointOut, buffer, offset, length, timeoutMs)
    }

    /**
     * Raw read off [endpointInterrupt], for subclasses that report line
     * status via a genuine interrupt-IN notification (CDC-ACM/PL2303/
     * CH340 — see file doc's Part 6/N section; CP210x/FTDI use a different
     * source entirely and don't call this). Same return convention as
     * [read] (byte count, `0` on plain timeout, `-1` on transport error),
     * plus [NO_INTERRUPT_ENDPOINT] when [endpointInterrupt] is `null` —
     * `bulkTransfer` works against any non-control endpoint in Android's
     * USB Host API despite the name, so no separate "interrupt transfer"
     * call exists to use instead.
     */
    protected fun readInterruptPacket(buffer: ByteArray, timeoutMs: Int): Int {
        val ep = endpointInterrupt ?: return NO_INTERRUPT_ENDPOINT
        return connection.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
    }

    /** Default for any subclass that has no line-status source at all — see [UsbSerialDriverPort.pollLineStatus]'s doc for why this is a normal, non-error outcome. */
    override fun pollLineStatus(timeoutMs: Int): LineStatus? = null

    private companion object {
        const val NO_INTERRUPT_ENDPOINT = -2
    }
}

/** USB-IF standard Communications Device Class / Abstract Control Model — see class doc. */
private class CdcAcmSerialPort(
    connection: UsbDeviceConnection,
    private val controlInterface: UsbInterface,
    private val dataInterface: UsbInterface,
    override val endpointIn: UsbEndpoint,
    override val endpointOut: UsbEndpoint,
    override val endpointInterrupt: UsbEndpoint?,
) : BaseUsbSerialPort(connection) {
    override val displayName = "USB CDC-ACM"

    /** Reused scratch buffer for [pollLineStatus] — a SERIAL_STATE notification is an 8-byte header plus this class's fixed 2-byte UART_STATE payload. */
    private val notificationBuffer = ByteArray(NOTIFICATION_HEADER_SIZE + 2)

    override fun open(): Boolean {
        // Some ACM devices expose control+data as the *same* interface
        // (single-interface composite descriptor) — guard against
        // double-claiming it in that case.
        if (!connection.claimInterface(dataInterface, true)) {
            Log.e(TAG, "claimInterface failed for CDC data interface")
            return false
        }
        if (controlInterface.id != dataInterface.id) {
            if (!connection.claimInterface(controlInterface, true)) {
                Log.w(TAG, "claimInterface failed for CDC control interface — continuing with data-only (line control may not apply)")
            }
        }
        if (endpointInterrupt == null) {
            Log.d(TAG, "No CDC-ACM notification (interrupt-IN) endpoint found — pollLineStatus will always return null")
        }
        // SET_CONTROL_LINE_STATE: assert DTR+RTS (bit0=DTR, bit1=RTS) so
        // devices that gate their UART on DTR (many Arduino-style boards
        // reset on DTR toggle, but simply never emit output at all if DTR
        // is never asserted in the first place) actually start talking.
        sendControlRequest(REQ_SET_CONTROL_LINE_STATE, 0x03, ByteArray(0))
        return true
    }

    /**
     * Parses a USB-IF CDC-ACM `SERIAL_STATE` notification off the control
     * interface's interrupt-IN endpoint: an 8-byte notification header
     * (`bmRequestType=0xA1, bNotification=0x20, wValue=0, wIndex, wLength=2`)
     * followed by the 2-byte little-endian `UART_STATE` bitmap this method
     * reads. Per the CDC-PSTN spec that bitmap defines `bRxCarrier`(DCD)=
     * bit0, `bTxCarrier`(DSR)=bit1, `bBreak`=bit2, `bRingSignal`=bit3,
     * `bFraming`=bit4, `bParity`=bit5, `bOverRun`=bit6 — and, notably, *no*
     * CTS bit at all (see class/file doc: a real spec gap, not something
     * missed here), so [LineStatus.cts] is always `false` from this driver.
     */
    override fun pollLineStatus(timeoutMs: Int): LineStatus? {
        val n = readInterruptPacket(notificationBuffer, timeoutMs)
        if (n < NOTIFICATION_HEADER_SIZE + 2) return null // no endpoint, timeout, transport error, or short/malformed packet
        if ((notificationBuffer[1].toInt() and 0xFF) != NOTIFICATION_SERIAL_STATE) return null // some other notification type this driver doesn't track
        val bitmap = (notificationBuffer[NOTIFICATION_HEADER_SIZE].toInt() and 0xFF) or
            ((notificationBuffer[NOTIFICATION_HEADER_SIZE + 1].toInt() and 0xFF) shl 8)
        return LineStatus(
            cts = false, // SERIAL_STATE has no CTS bit — see this method's doc
            dsr = (bitmap and UART_STATE_TX_CARRIER) != 0,
            dcd = (bitmap and UART_STATE_RX_CARRIER) != 0,
            ring = (bitmap and UART_STATE_RING_SIGNAL) != 0,
            breakError = (bitmap and UART_STATE_BREAK) != 0,
            frameError = (bitmap and UART_STATE_FRAMING) != 0,
            parityError = (bitmap and UART_STATE_PARITY) != 0,
            overrunError = (bitmap and UART_STATE_OVERRUN) != 0,
        )
    }

    override fun setLineCoding(baudRate: Int, dataBits: Int, stopBitsCode: Int, parityCode: Int) {
        val coding = ByteArray(7)
        coding[0] = (baudRate and 0xFF).toByte()
        coding[1] = ((baudRate ushr 8) and 0xFF).toByte()
        coding[2] = ((baudRate ushr 16) and 0xFF).toByte()
        coding[3] = ((baudRate ushr 24) and 0xFF).toByte()
        coding[4] = stopBitsCode.toByte()   // 0=1, 1=1.5, 2=2 — CDC-ACM's own encoding
        coding[5] = parityCode.toByte()     // 0=None..4=Space — CDC-ACM's own encoding
        coding[6] = dataBits.toByte()
        sendControlRequest(REQ_SET_LINE_CODING, 0, coding)
    }

    private fun sendControlRequest(request: Int, value: Int, data: ByteArray) {
        val index = controlInterface.id
        val sent = connection.controlTransfer(
            REQTYPE_CLASS_INTERFACE_OUT, request, value, index, data, data.size, CONTROL_TIMEOUT_MS,
        )
        if (sent < 0) Log.w(TAG, "CDC-ACM control request 0x${"%02x".format(request)} failed")
    }

    /**
     * The base USB-IF CDC-ACM class spec has no vendor/class request for
     * *hardware* RTS/CTS handshaking — unlike PL2303/FTDI/CP210x/CH340
     * (all reverse-engineered or vendor-extended protocols with their own
     * flow-control register), CDC-ACM only standardizes line coding and
     * DTR/RTS *state* ([SET_CONTROL_LINE_STATE], already sent in [open]).
     * Some individual boards implement automatic hardware handshaking
     * transparently in firmware whenever RTS/CTS are wired at all, with no
     * host-visible toggle — but that's a device-specific behavior this
     * generic driver can't detect or control, so this is a documented
     * no-op rather than a guess.
     */
    override fun setFlowControl(rtsCts: Boolean) {
        if (rtsCts) {
            Log.w(TAG, "USB CDC-ACM has no standard request for RTS/CTS hardware flow control — leaving it to the device's own firmware default")
        }
    }

    override fun close() {
        try { connection.releaseInterface(dataInterface) } catch (e: Exception) { android.util.Log.d("UsbSerialDrivers", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        if (controlInterface.id != dataInterface.id) {
            try { connection.releaseInterface(controlInterface) } catch (e: Exception) { android.util.Log.d("UsbSerialDrivers", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }

    companion object {
        private const val TAG = "CdcAcmSerialPort"
        private const val REQTYPE_CLASS_INTERFACE_OUT = 0x21 // host-to-device | class | interface
        private const val REQ_SET_LINE_CODING = 0x20
        private const val REQ_SET_CONTROL_LINE_STATE = 0x22
        private const val CONTROL_TIMEOUT_MS = 5000

        private const val CDC_COMM_INTERFACE_CLASS = 0x02
        private const val CDC_DATA_INTERFACE_CLASS = 0x0A

        // SERIAL_STATE notification (Part 6/N) — USB CDC-PSTN subclass spec §6.5.4.
        private const val NOTIFICATION_HEADER_SIZE = 8
        private const val NOTIFICATION_SERIAL_STATE = 0x20
        private const val UART_STATE_RX_CARRIER = 0x01 // bRxCarrier / DCD
        private const val UART_STATE_TX_CARRIER = 0x02 // bTxCarrier / DSR
        private const val UART_STATE_BREAK = 0x04
        private const val UART_STATE_RING_SIGNAL = 0x08
        private const val UART_STATE_FRAMING = 0x10
        private const val UART_STATE_PARITY = 0x20
        private const val UART_STATE_OVERRUN = 0x40

        /**
         * Looks for a CDC-Data interface (class 0x0A) with bulk in/out
         * endpoints, plus whichever interface carries the CDC-Communications
         * class (0x02) for control requests — falling back to the data
         * interface itself if no separate control interface exists (some
         * single-interface composite devices do this). Returns null if
         * [device] has no usable CDC-Data interface at all.
         */
        fun probe(usbManager: UsbManager, device: UsbDevice, connection: UsbDeviceConnection): CdcAcmSerialPort? {
            var dataIface: UsbInterface? = null
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            var controlIface: UsbInterface? = null
            var epInterrupt: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == CDC_COMM_INTERFACE_CLASS && controlIface == null) {
                    controlIface = iface
                    // SERIAL_STATE notifications ride the *control* interface's
                    // own interrupt-IN endpoint — see pollLineStatus's doc.
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN) {
                            epInterrupt = ep
                            break
                        }
                    }
                }
                if (iface.interfaceClass == CDC_DATA_INTERFACE_CLASS && dataIface == null) {
                    var inEp: UsbEndpoint? = null
                    var outEp: UsbEndpoint? = null
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                        if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                    }
                    if (inEp != null && outEp != null) {
                        dataIface = iface; epIn = inEp; epOut = outEp
                    }
                }
            }
            if (dataIface == null || epIn == null || epOut == null) return null
            // Single-interface composite devices carry control+data (and so
            // any interrupt-IN endpoint) on the same interface as the bulk
            // pair — check that case too if the dedicated-control-interface
            // scan above found none.
            if (epInterrupt == null && controlIface == null) {
                for (e in 0 until dataIface.endpointCount) {
                    val ep = dataIface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN) {
                        epInterrupt = ep
                        break
                    }
                }
            }
            return CdcAcmSerialPort(connection, controlIface ?: dataIface, dataIface, epIn, epOut, epInterrupt)
        }
    }
}

/**
 * Silicon Labs CP210x family. Register requests/values match the publicly
 * documented behavior of the Linux kernel's `cp210x` driver and Silicon
 * Labs AN571 — see class doc for scope. Uses the "GENERATION 2" direct
 * (non-divisor) baud-rate request (`CP210X_SET_BAUDRATE`), which every
 * commonly-available CP210x part (CP2102N, CP2104, CP2105, CP2108, and
 * modern CP2102 revisions) accepts.
 */
private class Cp210xSerialPort(
    connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    override val endpointIn: UsbEndpoint,
    override val endpointOut: UsbEndpoint,
    override val endpointInterrupt: UsbEndpoint?,
) : BaseUsbSerialPort(connection) {
    override val displayName = "Silicon Labs CP210x"

    override fun open(): Boolean {
        if (!connection.claimInterface(iface, true)) {
            Log.e(TAG, "claimInterface failed for CP210x interface")
            return false
        }
        // SERIAL-CONSOLE (Part 6/N): unlike CDC-ACM/PL2303, common CP210x
        // configurations don't reliably carry a genuine notification
        // interrupt-IN endpoint — [endpointInterrupt] is stored (if probe()
        // found one) purely for diagnostics/future use; pollLineStatus below
        // uses the documented CP210X_GET_MDMSTS control-read instead, which
        // needs no endpoint at all. See file doc's Part 6/N section.
        if (endpointInterrupt == null) {
            Log.d(TAG, "No CP210x interrupt-IN endpoint found — pollLineStatus uses CP210X_GET_MDMSTS instead, unaffected")
        }
        vendorRequest(CP210X_IFC_ENABLE, 1)
        return true
    }

    override fun setLineCoding(baudRate: Int, dataBits: Int, stopBitsCode: Int, parityCode: Int) {
        // CP210X_SET_BAUDRATE: 4-byte little-endian rate sent in the data phase (not wValue).
        val baudBytes = byteArrayOf(
            (baudRate and 0xFF).toByte(),
            ((baudRate ushr 8) and 0xFF).toByte(),
            ((baudRate ushr 16) and 0xFF).toByte(),
            ((baudRate ushr 24) and 0xFF).toByte(),
        )
        val sent = connection.controlTransfer(
            REQTYPE_VENDOR_OUT, CP210X_SET_BAUDRATE, 0, iface.id, baudBytes, baudBytes.size, CONTROL_TIMEOUT_MS,
        )
        if (sent < 0) Log.w(TAG, "CP210x SET_BAUDRATE failed")

        // CP210X_SET_LINE_CTL: bits [11:8]=data bits, [7:4]=parity, [3:0]=stop
        // bits — parity/stop-bits' 0..N ordinal here happens to already match
        // this chipset's own nibble values in the same order (None/Odd/Even/
        // Mark/Space and 1/1.5/2 stop bits), so no separate translation table
        // is needed beyond the bit-shift.
        val lineCtl = (dataBits shl 8) or (parityCode shl 4) or stopBitsCode
        vendorRequest(CP210X_SET_LINE_CTL, lineCtl)

        // CP210X_SET_MHS: best-effort DTR+RTS assert (value|mask for each) —
        // mirrors CdcAcmSerialPort.open()'s SET_CONTROL_LINE_STATE call;
        // several CP210x boards (e.g. ESP32 dev boards) also use DTR/RTS
        // toggles for auto-reset/bootloader-select, so leaving both simply
        // unasserted can leave such a board held in reset.
        vendorRequest(CP210X_SET_MHS, 0x0303)
    }

    private fun vendorRequest(request: Int, value: Int) {
        val sent = connection.controlTransfer(
            REQTYPE_VENDOR_OUT, request, value, iface.id, null, 0, CONTROL_TIMEOUT_MS,
        )
        if (sent < 0) Log.w(TAG, "CP210x vendor request 0x${"%02x".format(request)} failed")
    }

    /**
     * `CP210X_GET_FLOW`/`CP210X_SET_FLOW` read/write a 16-byte
     * `cp210x_flow_ctl` struct (`ulControlHandshake`/`ulFlowReplace`/
     * `ulXonLimit`/`ulXoffLimit`, each little-endian u32) — read-modify-
     * write like the kernel's own `cp210x_set_termios` so [ulXonLimit]/
     * [ulXoffLimit] (this driver has no XON/XOFF support to set them
     * itself) survive untouched. Toggles exactly the two bits/fields the
     * kernel driver does for `CRTSCTS`: `ulControlHandshake`'s
     * CTS-handshake bit, and `ulFlowReplace`'s RTS sub-field between
     * "flow-controlled" and a plain fixed-active RTS — while always
     * re-asserting DTR-active, matching [open]'s own `CP210X_SET_MHS` call
     * so toggling flow control can't accidentally drop DTR.
     */
    override fun setFlowControl(rtsCts: Boolean) {
        val flow = ByteArray(16)
        val got = connection.controlTransfer(
            REQTYPE_VENDOR_IN, CP210X_GET_FLOW, 0, iface.id, flow, flow.size, CONTROL_TIMEOUT_MS,
        )
        if (got != flow.size) {
            Log.w(TAG, "CP210x GET_FLOW failed — leaving flow control at its current/power-on state")
            return
        }
        var controlHandshake = readLe32(flow, 0)
        var flowReplace = readLe32(flow, 4)

        controlHandshake = (controlHandshake and CP210X_SERIAL_DTR_MASK.inv()) or CP210X_SERIAL_DTR_ACTIVE
        flowReplace = flowReplace and CP210X_SERIAL_RTS_MASK.inv()
        if (rtsCts) {
            controlHandshake = controlHandshake or CP210X_SERIAL_CTS_HANDSHAKE
            flowReplace = flowReplace or CP210X_SERIAL_RTS_FLOW_CTL
        } else {
            controlHandshake = controlHandshake and CP210X_SERIAL_CTS_HANDSHAKE.inv()
            flowReplace = flowReplace or CP210X_SERIAL_RTS_ACTIVE
        }

        writeLe32(flow, 0, controlHandshake)
        writeLe32(flow, 4, flowReplace)
        val sent = connection.controlTransfer(
            REQTYPE_VENDOR_OUT, CP210X_SET_FLOW, 0, iface.id, flow, flow.size, CONTROL_TIMEOUT_MS,
        )
        if (sent != flow.size) Log.w(TAG, "CP210x SET_FLOW failed")
    }

    private fun readLe32(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeLe32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    /**
     * `CP210X_GET_MDMSTS`: a single-byte vendor control-read of the
     * chipset's live modem-control-line state — AN571 / the kernel
     * `cp210x` driver's own use of this same request for `TIOCMGET`.
     * Bits 4-7 are the modem-status lines this method reports; bits 0-1
     * (this driver doesn't read them back) mirror the host's own last
     * DTR/RTS write instead. No framing/parity/overrun bits exist in this
     * byte at all — see class/file doc — so those three fields are always
     * `false` in the returned [LineStatus].
     */
    override fun pollLineStatus(timeoutMs: Int): LineStatus? {
        val buf = ByteArray(1)
        val n = connection.controlTransfer(
            REQTYPE_VENDOR_IN, CP210X_GET_MDMSTS, 0, iface.id, buf, buf.size, timeoutMs,
        )
        if (n < 1) return null
        val status = buf[0].toInt() and 0xFF
        return LineStatus(
            cts = (status and CP210X_MDMSTS_CTS) != 0,
            dsr = (status and CP210X_MDMSTS_DSR) != 0,
            dcd = (status and CP210X_MDMSTS_DCD) != 0,
            ring = (status and CP210X_MDMSTS_RING) != 0,
        )
    }

    override fun close() {
        vendorRequest(CP210X_IFC_ENABLE, 0)
        try { connection.releaseInterface(iface) } catch (e: Exception) { android.util.Log.d("UsbSerialDrivers", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
    }

    companion object {
        private const val TAG = "Cp210xSerialPort"
        private const val REQTYPE_VENDOR_OUT = 0x41 // host-to-device | vendor | interface
        private const val REQTYPE_VENDOR_IN = 0xC1  // device-to-host | vendor | interface
        private const val CP210X_IFC_ENABLE = 0x00
        private const val CP210X_SET_MHS = 0x07
        private const val CP210X_SET_LINE_CTL = 0x03
        private const val CP210X_SET_BAUDRATE = 0x1E
        private const val CP210X_SET_FLOW = 0x13
        private const val CP210X_GET_FLOW = 0x14
        private const val CP210X_GET_MDMSTS = 0x08
        private const val CONTROL_TIMEOUT_MS = 5000

        // CP210X_GET_MDMSTS response byte (AN571 / kernel cp210x.c) — bits 4-7.
        private const val CP210X_MDMSTS_CTS = 0x10
        private const val CP210X_MDMSTS_DSR = 0x20
        private const val CP210X_MDMSTS_RING = 0x40
        private const val CP210X_MDMSTS_DCD = 0x80

        // cp210x_flow_ctl::ulControlHandshake bits (AN571 / kernel cp210x.c)
        private const val CP210X_SERIAL_DTR_MASK = 0x3       // GENMASK(1,0)
        private const val CP210X_SERIAL_DTR_ACTIVE = 0x1
        private const val CP210X_SERIAL_CTS_HANDSHAKE = 0x8  // BIT(3)
        // cp210x_flow_ctl::ulFlowReplace bits
        private const val CP210X_SERIAL_RTS_MASK = 0xC0       // GENMASK(7,6)
        private const val CP210X_SERIAL_RTS_ACTIVE = 0x1 shl 6
        private const val CP210X_SERIAL_RTS_FLOW_CTL = 0x2 shl 6

        const val VENDOR_ID_SILABS = 0x10C4
        /** Common CP210x product IDs — CP2102/CP2102N/CP2103/CP2104/CP2109 all ship as 0xEA60; 0xEA70 covers CP2105/CP2108's second/composite interface. */
        val PRODUCT_IDS = setOf(0xEA60, 0xEA70)

        fun probe(usbManager: UsbManager, device: UsbDevice, connection: UsbDeviceConnection): Cp210xSerialPort? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                var epInterrupt: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    when {
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN -> epIn = ep
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK -> epOut = ep
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN -> epInterrupt = ep
                    }
                }
                if (epIn != null && epOut != null) return Cp210xSerialPort(connection, iface, epIn, epOut, epInterrupt)
            }
            return null
        }
    }
}

/**
 * FTDI FT232R/FT230X/FT231X/FT234X family — see file doc for scope (H-series
 * excluded). Vendor request numbers, the SET_DATA bit layout, and the
 * baud-divisor formula match FTDI's own AN232B-05 and the long-stable
 * behavior of every independent open-source FTDI driver (Linux's
 * `ftdi_sio.c`, libftdi, pyftdi).
 *
 * Framing quirk unique to this chipset (unlike CDC-ACM/CP210x above): every
 * bulk-IN USB packet — not just every logical read — starts with a 2-byte
 * modem/line-status header ahead of the actual data. A read that doesn't
 * strip it per-packet corrupts the stream with two garbage bytes per
 * max-packet-size chunk, so [read] deliberately caps each bulk transfer at
 * [endpointIn]'s own max packet size rather than the caller's full buffer,
 * to guarantee one status header per transfer.
 */
private class FtdiSerialPort(
    connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    override val endpointIn: UsbEndpoint,
    override val endpointOut: UsbEndpoint,
) : BaseUsbSerialPort(connection) {
    override val displayName = "FTDI FT232R/FT230X-class"

    // FTDI addresses the UART on a single-port device as channel/port 1,
    // not 0 — a longstanding quirk of the SIO command set carried over
    // from multi-port FT2232/4232 chips where 1 = channel A.
    private val portIndex = 1

    private var rawReadBuffer = ByteArray(endpointIn.maxPacketSize.coerceAtLeast(3))

    /** SERIAL-CONSOLE (Part 6/N): most recently parsed 2-byte status header from [read] — see [pollLineStatus]'s doc for why this chipset has no separate interrupt endpoint to poll instead. */
    @Volatile private var lastLineStatus: LineStatus? = null

    override fun open(): Boolean {
        if (!connection.claimInterface(iface, true)) {
            Log.e(TAG, "claimInterface failed for FTDI interface")
            return false
        }
        sendControlRequest(FTDI_SIO_RESET_REQUEST, FTDI_SIO_RESET_SIO)
        sendControlRequest(FTDI_SIO_SET_FLOW_CTRL_REQUEST, 0) // no hardware/software flow control
        // Assert DTR+RTS (bit0=DTR state, bit1=RTS state, bit8/bit9=their
        // respective "apply this bit" enable masks) — same rationale as
        // CdcAcmSerialPort.open(): boards that reset or gate output on DTR
        // otherwise never start talking.
        sendControlRequest(FTDI_SIO_SET_MODEM_CTRL_REQUEST, 0x0303)
        return true
    }

    override fun setLineCoding(baudRate: Int, dataBits: Int, stopBitsCode: Int, parityCode: Int) {
        val (baudValue, baudIndex) = encodeBaudDivisor(baudRate)
        val sentBaud = connection.controlTransfer(
            REQTYPE_VENDOR_OUT, FTDI_SIO_SET_BAUDRATE_REQUEST, baudValue, baudIndex, null, 0, CONTROL_TIMEOUT_MS,
        )
        if (sentBaud < 0) Log.w(TAG, "FTDI SET_BAUDRATE failed")

        // FTDI_SIO_SET_DATA: bits 0-7 data bits, bits 8-10 parity
        // (0..4 = None/Odd/Even/Mark/Space — already this chipset's own
        // encoding, no translation needed), bits 11-12 stop bits (0/1/2 =
        // 1/1.5/2 stop bits — likewise already FTDI's own encoding).
        val dataValue = (dataBits and 0xFF) or ((parityCode and 0x7) shl 8) or ((stopBitsCode and 0x3) shl 11)
        sendControlRequest(FTDI_SIO_SET_DATA_REQUEST, dataValue)
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val chunkSize = rawReadBuffer.size
        val n = connection.bulkTransfer(endpointIn, rawReadBuffer, chunkSize, timeoutMs)
        if (n <= 0) return n // 0 = plain timeout, -1 = real transport error — pass through as-is
        if (n >= FTDI_STATUS_HEADER_SIZE) {
            // Every packet carries this header regardless of whether it also
            // carries payload bytes — see pollLineStatus's doc.
            lastLineStatus = parseStatusHeader(rawReadBuffer[0], rawReadBuffer[1])
        }
        if (n <= FTDI_STATUS_HEADER_SIZE) return 0 // status-only packet, no payload this round
        val payloadLen = (n - FTDI_STATUS_HEADER_SIZE).coerceAtMost(buffer.size)
        System.arraycopy(rawReadBuffer, FTDI_STATUS_HEADER_SIZE, buffer, 0, payloadLen)
        return payloadLen
    }

    /**
     * SERIAL-CONSOLE (Part 6/N): `ftdi_sio.h`'s modem-status (byte 0) and
     * line-status (byte 1) bit layout for this same 2-byte header [read]
     * already strips on every call.
     */
    private fun parseStatusHeader(modemByte: Byte, lineByte: Byte): LineStatus {
        val modem = modemByte.toInt() and 0xFF
        val line = lineByte.toInt() and 0xFF
        return LineStatus(
            cts = (modem and FTDI_RS0_CTS) != 0,
            dsr = (modem and FTDI_RS0_DSR) != 0,
            dcd = (modem and FTDI_RS0_RLSD) != 0,
            ring = (modem and FTDI_RS0_RI) != 0,
            breakError = (line and FTDI_RS_BI) != 0,
            frameError = (line and FTDI_RS_FE) != 0,
            parityError = (line and FTDI_RS_PE) != 0,
            overrunError = (line and FTDI_RS_OE) != 0,
        )
    }

    /**
     * This chipset has no separate interrupt-IN endpoint at all (file doc's
     * Part 3/N note) — status instead rides along in every bulk-IN packet's
     * own 2-byte header, which [read] already parses on every call. This
     * just hands back whatever it last saw rather than generating its own
     * USB traffic, so [timeoutMs] is unused; returns `null` only if no
     * bulk-IN transfer carrying a full header has completed yet since
     * [open] (e.g. nothing has been read from the device at all).
     */
    override fun pollLineStatus(timeoutMs: Int): LineStatus? = lastLineStatus

    private fun sendControlRequest(request: Int, value: Int) {
        val sent = connection.controlTransfer(
            REQTYPE_VENDOR_OUT, request, value, portIndex, null, 0, CONTROL_TIMEOUT_MS,
        )
        if (sent < 0) Log.w(TAG, "FTDI control request 0x${"%02x".format(request)} failed")
    }

    /**
     * `FTDI_SIO_SET_FLOW_CTRL_REQUEST` (already sent once, disabled, in
     * [open]) packs the flow-control *mode* into the upper byte of
     * `wIndex` alongside the channel/port selector in the lower byte —
     * [FTDI_SIO_RTS_CTS_HS] is that mode's documented value (AN232B-05 /
     * `ftdi_sio.h`'s `FTDI_SIO_RTS_CTS_HS`). `wValue` is unused for this
     * mode (it only carries XON/XOFF characters in XON/XOFF mode, which
     * this driver doesn't implement).
     */
    override fun setFlowControl(rtsCts: Boolean) {
        val index = if (rtsCts) portIndex or FTDI_SIO_RTS_CTS_HS else portIndex
        val sent = connection.controlTransfer(
            REQTYPE_VENDOR_OUT, FTDI_SIO_SET_FLOW_CTRL_REQUEST, 0, index, null, 0, CONTROL_TIMEOUT_MS,
        )
        if (sent < 0) Log.w(TAG, "FTDI SET_FLOW_CTRL failed")
    }

    /**
     * FTDI's own AN232B-05 divisor scheme: an integer divisor against a
     * 3 MHz reference clock plus a 3-bit eighths-of-a-step fractional
     * refinement, encoded as `value` = 14 integer bits | 2 fraction bits
     * (bits 15:14), with the fractional value itself remapped through
     * [FRAC_CODE] and its 3rd bit folded into `index` bit 0 (bits 8-15 of
     * index select the channel on multi-port chips — always 0 here, single
     * port). A divisor of exactly 0 is the documented special case for the
     * chip's max rate (3 MBaud).
     */
    private fun encodeBaudDivisor(baudRate: Int): Pair<Int, Int> {
        if (baudRate <= 0 || baudRate >= FTDI_BASE_CLOCK) return 0 to portIndex
        val eighths = ((FTDI_BASE_CLOCK.toLong() * 8 + baudRate / 2) / baudRate).toInt()
        val intPart = eighths shr 3
        val frac = FRAC_CODE[eighths and 0x7]
        val value = (intPart and 0x3FFF) or ((frac and 0x3) shl 14)
        val index = portIndex or (((frac shr 2) and 0x1) shl 0)
        return value to index
    }

    override fun close() {
        try { connection.releaseInterface(iface) } catch (e: Exception) { android.util.Log.d("UsbSerialDrivers", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
    }

    companion object {
        private const val TAG = "FtdiSerialPort"
        private const val REQTYPE_VENDOR_OUT = 0x40 // host-to-device | vendor | device
        private const val CONTROL_TIMEOUT_MS = 5000
        private const val FTDI_STATUS_HEADER_SIZE = 2
        private const val FTDI_BASE_CLOCK = 3_000_000

        private const val FTDI_SIO_RESET_REQUEST = 0x00
        private const val FTDI_SIO_RESET_SIO = 0
        private const val FTDI_SIO_SET_MODEM_CTRL_REQUEST = 0x01
        private const val FTDI_SIO_SET_FLOW_CTRL_REQUEST = 0x02
        private const val FTDI_SIO_SET_BAUDRATE_REQUEST = 0x03
        private const val FTDI_SIO_SET_DATA_REQUEST = 0x04
        private const val FTDI_SIO_RTS_CTS_HS = 0x1 shl 8

        private val FRAC_CODE = intArrayOf(0, 3, 2, 4, 1, 5, 6, 7)

        // Status-header bytes (Part 6/N) — ftdi_sio.h's FTDI_RS0_*/FTDI_RS_* layout.
        private const val FTDI_RS0_CTS = 0x10
        private const val FTDI_RS0_DSR = 0x20
        private const val FTDI_RS0_RI = 0x40
        private const val FTDI_RS0_RLSD = 0x80 // DCD
        private const val FTDI_RS_OE = 0x02
        private const val FTDI_RS_PE = 0x04
        private const val FTDI_RS_FE = 0x08
        private const val FTDI_RS_BI = 0x10

        const val VENDOR_ID_FTDI = 0x0403
        /** FT232AM/BM/R (0x6001) and the FT230X/FT231X/FT234X family (0x6015) — the single-port, 3 MHz-clock parts this driver targets. H-series PIDs (0x6010/0x6011/0x6014/...) are intentionally excluded — see file doc. */
        val PRODUCT_IDS = setOf(0x6001, 0x6015)

        fun probe(device: UsbDevice, connection: UsbDeviceConnection): FtdiSerialPort? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                }
                if (epIn != null && epOut != null) return FtdiSerialPort(connection, iface, epIn, epOut)
            }
            return null
        }
    }
}

/**
 * WCH CH340/CH341 in UART mode — see file doc for scope/sourcing. Register
 * addresses, vendor request numbers, and the prescaler/divisor baud formula
 * match the long-stable behavior of the Linux kernel's own `ch341.c`
 * (independently corroborated by the NetBSD/FreeBSD `uchcom` drivers, per
 * that same kernel source's own attribution comment).
 */
private class Ch340SerialPort(
    connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    override val endpointIn: UsbEndpoint,
    override val endpointOut: UsbEndpoint,
    override val endpointInterrupt: UsbEndpoint?,
) : BaseUsbSerialPort(connection) {
    override val displayName = "WCH CH340/CH341"

    /** Reused scratch buffer for [pollLineStatus] — genuine WCH firmware's interrupt packets are 8 bytes, but only byte index 2 is read here (see that method's doc), so any packet of at least [CH341_INT_STATUS_MIN_LEN] is usable. */
    private val interruptBuffer = ByteArray(8)

    /**
     * Chips report their own silicon revision via [CH341_REQ_READ_VERSION];
     * versions before 0x30 need line-control written through the older,
     * split register pair instead of the newer combined one. Populated in
     * [open], defaults to "new-style" (0x30) if the read itself fails,
     * since that's the behavior of every CH340/CH341 sold in the last
     * decade-plus.
     */
    private var chipVersion: Int = 0x30

    override fun open(): Boolean {
        if (!connection.claimInterface(iface, true)) {
            Log.e(TAG, "claimInterface failed for CH340/CH341 interface")
            return false
        }
        readVersion()
        // CH341_REQ_SERIAL_INIT: undocumented by WCH but required by every
        // known driver before any register writes will take effect.
        vendorOut(CH341_REQ_SERIAL_INIT, 0, 0)
        setLineCoding(DEFAULT_BOOT_BAUD, 8, 0, 0)
        setModemControl(dtr = true, rts = true)
        if (endpointInterrupt == null) {
            // Not every CH340-VID/PID clone this file's table matches
            // implements genuine WCH silicon's interrupt endpoint faithfully
            // — see file doc's Part 6/N section. pollLineStatus already
            // handles this the same way as a plain timeout.
            Log.d(TAG, "No CH340/CH341 interrupt-IN endpoint found — pollLineStatus will always return null")
        }
        return true
    }

    override fun setLineCoding(baudRate: Int, dataBits: Int, stopBitsCode: Int, parityCode: Int) {
        val divisorByte = encodeBaudDivisor(baudRate)
        if (divisorByte == null) {
            Log.w(TAG, "CH340/CH341: $baudRate baud is outside the supported range, keeping previous rate")
        } else {
            val r = vendorOut(
                CH341_REQ_WRITE_REG,
                (CH341_REG_DIVISOR.toInt() shl 8) or CH341_REG_PRESCALER.toInt(),
                divisorByte,
            )
            if (r < 0) Log.w(TAG, "CH340/CH341 baud-rate register write failed")
        }

        var lcr = CH341_LCR_ENABLE_RX or CH341_LCR_ENABLE_TX
        lcr = lcr or when (dataBits) {
            5 -> CH341_LCR_CS5
            6 -> CH341_LCR_CS6
            7 -> CH341_LCR_CS7
            else -> CH341_LCR_CS8
        }
        // parityCode: 0=None,1=Odd,2=Even,3=Mark,4=Space (this file's CDC-ACM convention).
        if (parityCode != 0) {
            lcr = lcr or CH341_LCR_ENABLE_PAR
            if (parityCode == 2 || parityCode == 4) lcr = lcr or CH341_LCR_PAR_EVEN // Even, Space
            if (parityCode >= 3) lcr = lcr or CH341_LCR_MARK_SPACE // Mark, Space
        }
        if (stopBitsCode == 2) {
            lcr = lcr or CH341_LCR_STOP_BITS_2
        } else if (stopBitsCode == 1) {
            Log.w(TAG, "CH340/CH341 has no 1.5 stop-bit mode; using 2 stop bits instead")
            lcr = lcr or CH341_LCR_STOP_BITS_2
        }

        if (chipVersion < 0x30) return // pre-0x30 silicon: caller only gets the baud rate applied, matching this chip generation's real limits
        val r = vendorOut(CH341_REQ_WRITE_REG, (CH341_REG_LCR2.toInt() shl 8) or CH341_REG_LCR.toInt(), lcr)
        if (r < 0) Log.w(TAG, "CH340/CH341 line-control register write failed")
    }

    /**
     * `CH341_REG_FLOW_CTL` (0x27), written the same "same value in both
     * bytes" way [setModemControl]/[setLineCoding]'s register writes
     * already use here — matches the upstream kernel's `ch341.c`
     * `ch341_set_flow_control` (merged after this file's CH340/CH341
     * baud/line-control logic was originally written against an older
     * kernel snapshot that didn't have it yet): `0x0101` selects RTS/CTS
     * hardware handshaking, `0x0000` disables it.
     */
    override fun setFlowControl(rtsCts: Boolean) {
        val flowCtl = if (rtsCts) 0x01 else 0x00
        val packed = (flowCtl shl 8) or flowCtl
        val r = vendorOut(CH341_REQ_WRITE_REG, (CH341_REG_FLOW_CTL.toInt() shl 8) or CH341_REG_FLOW_CTL.toInt(), packed)
        if (r < 0) Log.w(TAG, "CH340/CH341 flow-control register write failed")
    }

    private fun setModemControl(dtr: Boolean, rts: Boolean) {
        var control = 0
        if (dtr) control = control or CH341_BIT_DTR
        if (rts) control = control or CH341_BIT_RTS
        // Modem-control lines are active-low on the wire for this request.
        val r = connection.controlTransfer(
            REQTYPE_VENDOR_OUT, CH341_REQ_MODEM_CTRL, control.inv() and 0xFF, 0, null, 0, CONTROL_TIMEOUT_MS,
        )
        if (r < 0) Log.w(TAG, "CH340/CH341 modem-control request failed")
    }

    private fun readVersion() {
        val buf = ByteArray(2)
        val r = connection.controlTransfer(
            REQTYPE_VENDOR_IN, CH341_REQ_READ_VERSION, 0, 0, buf, buf.size, CONTROL_TIMEOUT_MS,
        )
        if (r >= 1) chipVersion = buf[0].toInt() and 0xFF
    }

    private fun vendorOut(request: Int, value: Int, index: Int): Int =
        connection.controlTransfer(REQTYPE_VENDOR_OUT, request, value, index, null, 0, CONTROL_TIMEOUT_MS)

    /**
     * `baud = 48MHz / (2^(12 - 3*ps - fact) * div)`, choosing the highest
     * base clock (`fact=1`) that keeps `div` under 512, falling back to
     * `fact=0` when `div` would fall outside the chip's `[9, 255]` (or
     * `[2, 255]` at `fact=0`) window — mirrors `ch341_get_divisor`'s
     * search over prescaler stages `ps` without copying its C source.
     * Returns the packed `(0x100 - div) << 8 | fact << 2 | ps` byte the
     * device expects, or null if [baudRate] is outside the ~46..3 MBaud
     * range this formula supports for `ps` in `0..3`.
     */
    private fun encodeBaudDivisor(baudRate: Int): Int? {
        if (baudRate <= 0) return null
        for (ps in 3 downTo 0) {
            val clkDivFact1 = 1 shl (12 - 3 * ps - 1)
            val minRateAtPs = CH341_CLOCK_RATE / (clkDivFact1 * 512)
            if (baudRate <= minRateAtPs) continue

            var fact = 1
            var clkDiv = clkDivFact1
            var div = CH341_CLOCK_RATE / (clkDiv * baudRate)
            if (div < 9 || div > 255) {
                div /= 2
                clkDiv *= 2
                fact = 0
            }
            if (div < 2) continue
            // Round to the nearer achievable divisor rather than always truncating.
            val rateAtDiv = CH341_CLOCK_RATE / (clkDiv * div)
            val rateAtDivPlus1 = CH341_CLOCK_RATE / (clkDiv * (div + 1))
            if (rateAtDiv - baudRate >= baudRate - rateAtDivPlus1) div++
            if (fact == 1 && div % 2 == 0) {
                div /= 2
                fact = 0
            }
            return ((0x100 - div) shl 8) or (fact shl 2) or ps
        }
        return null
    }

    /**
     * Genuine WCH silicon reports modem-status on its interrupt-IN endpoint
     * as an inverted-logic byte at packet offset 2 — `ch341.c`'s
     * `ch341_update_line_status` (`priv->line_status = ~data[2] & mask`),
     * hence the bitwise-NOT below. No framing/parity/overrun bits exist in
     * this notification at all — see class/file doc — so those three
     * fields are always `false` in the returned [LineStatus].
     */
    override fun pollLineStatus(timeoutMs: Int): LineStatus? {
        val n = readInterruptPacket(interruptBuffer, timeoutMs)
        if (n < CH341_INT_STATUS_MIN_LEN) return null // no endpoint, timeout, transport error, or short packet
        val status = (interruptBuffer[2].toInt() and 0xFF).inv() and 0xFF
        return LineStatus(
            cts = (status and CH341_STATUS_CTS) != 0,
            dsr = (status and CH341_STATUS_DSR) != 0,
            dcd = (status and CH341_STATUS_DCD) != 0,
            ring = (status and CH341_STATUS_RI) != 0,
        )
    }

    override fun close() {
        try { connection.releaseInterface(iface) } catch (e: Exception) { android.util.Log.d("UsbSerialDrivers", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
    }

    companion object {
        private const val TAG = "Ch340SerialPort"
        private const val REQTYPE_VENDOR_OUT = 0x40 // host-to-device | vendor | device
        private const val REQTYPE_VENDOR_IN = 0xC0 // device-to-host | vendor | device
        private const val CONTROL_TIMEOUT_MS = 5000
        private const val DEFAULT_BOOT_BAUD = 9600
        private const val CH341_CLOCK_RATE = 48_000_000

        private const val CH341_REQ_READ_VERSION = 0x5F
        private const val CH341_REQ_WRITE_REG = 0x9A
        private const val CH341_REQ_SERIAL_INIT = 0xA1
        private const val CH341_REQ_MODEM_CTRL = 0xA4

        private const val CH341_REG_PRESCALER: Byte = 0x12
        private const val CH341_REG_DIVISOR: Byte = 0x13
        private const val CH341_REG_LCR: Byte = 0x18
        private const val CH341_REG_LCR2: Byte = 0x25
        private const val CH341_REG_FLOW_CTL: Byte = 0x27

        private const val CH341_LCR_ENABLE_RX = 0x80
        private const val CH341_LCR_ENABLE_TX = 0x40
        private const val CH341_LCR_MARK_SPACE = 0x20
        private const val CH341_LCR_PAR_EVEN = 0x10
        private const val CH341_LCR_ENABLE_PAR = 0x08
        private const val CH341_LCR_STOP_BITS_2 = 0x04
        private const val CH341_LCR_CS8 = 0x03
        private const val CH341_LCR_CS7 = 0x02
        private const val CH341_LCR_CS6 = 0x01
        private const val CH341_LCR_CS5 = 0x00

        private const val CH341_BIT_DTR = 1 shl 5
        private const val CH341_BIT_RTS = 1 shl 6

        // Interrupt-IN modem-status notification (Part 6/N) — ch341.c's
        // ch341_update_line_status; byte offset 2, inverted-logic bits.
        private const val CH341_INT_STATUS_MIN_LEN = 4
        private const val CH341_STATUS_CTS = 0x01
        private const val CH341_STATUS_DSR = 0x02
        private const val CH341_STATUS_RI = 0x04
        private const val CH341_STATUS_DCD = 0x08

        /** (VID, PID) pairs covering genuine WCH parts and the common CH340 clones seen in the wild. */
        val VENDOR_PRODUCT_IDS = setOf(
            0x1A86 to 0x7523, // CH340
            0x1A86 to 0x5523, // CH341 (UART mode)
            0x1A86 to 0x7522,
            0x4348 to 0x5523, // common clone
        )

        fun probe(device: UsbDevice, connection: UsbDeviceConnection): Ch340SerialPort? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                var epInterrupt: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    when {
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN -> epIn = ep
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK -> epOut = ep
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN -> epInterrupt = ep
                    }
                }
                if (epIn != null && epOut != null) return Ch340SerialPort(connection, iface, epIn, epOut, epInterrupt)
            }
            return null
        }
    }
}

/** Per-sub-generation constants [Pl2303SerialPort] needs — see its class doc and [UsbSerialProbe]'s file-level Part 4/N note. */
private enum class Pl2303Type(
    val label: String,
    val maxBaudRate: Int,
    val noDivisors: Boolean,
    val altDivisors: Boolean,
) {
    H("H", 1_228_800, noDivisors = false, altDivisors = false),
    HX("HX", 6_000_000, noDivisors = false, altDivisors = false),
    TA("TA", 6_000_000, noDivisors = false, altDivisors = true),
    TB("TB", 12_000_000, noDivisors = false, altDivisors = true),
    HXD("HXD", 12_000_000, noDivisors = false, altDivisors = false),
    HXN("HXN/G", 12_000_000, noDivisors = true, altDivisors = false),
}

/**
 * Prolific PL2303 — see file doc (Part 4/N) for why sub-generation
 * detection had to come first. Vendor request numbers, the sub-generation
 * ID table, and both baud-divisor formulas match the publicly documented
 * behavior of the Linux kernel's own `pl2303.c`/`pl2303.h` — reimplemented
 * independently in this file's own style, not copied, per the same
 * GPL-avoidance note as [FtdiSerialPort]/[Ch340SerialPort] above.
 *
 * `SET_LINE_REQUEST`/`SET_CONTROL_REQUEST` turn out to reuse the exact
 * same request-type/request-number/bit-layout as CDC-ACM's
 * `SET_LINE_CODING`/`SET_CONTROL_LINE_STATE` (0x21/0x20 and 0x21/0x22
 * respectively, DTR=bit0/RTS=bit1) — confirmed against the real kernel
 * source rather than assumed — so only the baud-rate encoding itself
 * needs chipset-specific logic; the request plumbing below mirrors
 * [CdcAcmSerialPort]'s.
 */
private class Pl2303SerialPort(
    connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    override val endpointIn: UsbEndpoint,
    override val endpointOut: UsbEndpoint,
    override val endpointInterrupt: UsbEndpoint?,
    private val chipType: Pl2303Type,
) : BaseUsbSerialPort(connection) {
    override val displayName = "Prolific PL2303 (${chipType.label})"

    /** Reused scratch buffer for [pollLineStatus] — real PL2303 interrupt packets run up to [UART_STATE_INDEX]+1 bytes; sized with headroom rather than exactly, since a too-small buffer would silently truncate a legitimate packet. */
    private val interruptBuffer = ByteArray(16)

    override fun open(): Boolean {
        if (!connection.claimInterface(iface, true)) {
            Log.e(TAG, "claimInterface failed for PL2303 interface")
            return false
        }
        if (endpointInterrupt == null) {
            Log.d(TAG, "No PL2303 interrupt-IN endpoint found — pollLineStatus will always return null")
        }
        // HXN silicon rejects the legacy vendor-request number this dance
        // uses outright — and, per pl2303_startup, doesn't need it anyway.
        if (chipType != Pl2303Type.HXN) runLegacyInitSequence()
        setControlLines(dtr = true, rts = true)
        return true
    }

    override fun setLineCoding(baudRate: Int, dataBits: Int, stopBitsCode: Int, parityCode: Int) {
        val buf = ByteArray(7)
        encodeBaudRate(buf, baudRate)
        buf[4] = stopBitsCode.toByte() // 0=1, 1=1.5, 2=2 stop bits — CDC-ACM's own encoding, reused as-is
        buf[5] = parityCode.toByte()   // 0=None..4=Space — CDC-ACM's own encoding, reused as-is
        buf[6] = dataBits.toByte()
        val sent = connection.controlTransfer(
            REQTYPE_SET_LINE, SET_LINE_REQUEST, 0, 0, buf, buf.size, CONTROL_TIMEOUT_MS,
        )
        if (sent < 0) Log.w(TAG, "PL2303 SET_LINE_REQUEST failed")
    }

    private fun setControlLines(dtr: Boolean, rts: Boolean) {
        var value = 0
        if (dtr) value = value or CONTROL_DTR
        if (rts) value = value or CONTROL_RTS
        val r = connection.controlTransfer(
            REQTYPE_SET_CONTROL, SET_CONTROL_REQUEST, value, 0, null, 0, CONTROL_TIMEOUT_MS,
        )
        if (r < 0) Log.w(TAG, "PL2303 SET_CONTROL_REQUEST failed")
    }

    /**
     * The undocumented-but-required vendor read/write sequence every H/HX/
     * TA/TB/HXD-generation PL2303 needs once at startup before line
     * settings reliably take effect. Return values are intentionally
     * unchecked, matching `pl2303_startup` itself — several of these reads
     * exist only to step the chip's internal state machine into place for
     * the writes that follow, not to retrieve data this caller needs.
     */
    private fun runLegacyInitSequence() {
        val buf = ByteArray(1)
        vendorRead(0x8484, buf)
        vendorWrite(0x0404, 0)
        vendorRead(0x8484, buf)
        vendorRead(0x8383, buf)
        vendorRead(0x8484, buf)
        vendorWrite(0x0404, 1)
        vendorRead(0x8484, buf)
        vendorRead(0x8383, buf)
        vendorWrite(0, 1)
        vendorWrite(1, 0)
        vendorWrite(2, if (chipType == Pl2303Type.H) 0x24 else 0x44)
    }

    private fun vendorRead(value: Int, buf: ByteArray): Int =
        connection.controlTransfer(REQTYPE_VENDOR_IN, VENDOR_READ_REQUEST, value, 0, buf, buf.size, CONTROL_TIMEOUT_MS)

    private fun vendorWrite(value: Int, index: Int): Int =
        connection.controlTransfer(REQTYPE_VENDOR_OUT, VENDOR_WRITE_REQUEST, value, index, null, 0, CONTROL_TIMEOUT_MS)

    /**
     * Read-modify-write of one vendor register — mirrors `pl2303_update_reg`:
     * read [reg] (through the same "OR 0x80" read-address convention every
     * H/HX/HXD/TA/TB read in [runLegacyInitSequence] already uses — HXN
     * reads the bare register number instead, another difference this
     * table already had to account for in [detectType]), clear [mask]'s
     * bits, OR in [value]'s bits within that mask, write it back.
     */
    private fun updateReg(reg: Int, mask: Int, value: Int) {
        val buf = ByteArray(1)
        val readAddress = if (chipType == Pl2303Type.HXN) reg else (reg or 0x80)
        if (vendorRead(readAddress, buf) < 1) {
            Log.w(TAG, "PL2303 flow-control register 0x${reg.toString(16)} read failed")
            return
        }
        val updated = (buf[0].toInt() and 0xFF and mask.inv()) or (value and mask)
        if (vendorWrite(reg, updated) < 0) {
            Log.w(TAG, "PL2303 flow-control register 0x${reg.toString(16)} write failed")
        }
    }

    /**
     * HXN uses its own register/bitfield ([PL2303_HXN_FLOWCTRL_REG]);
     * every other sub-generation shares one register (0) whose "hardware
     * handshake" value differs only for legacy H silicon (0x40) vs.
     * everything else (0x60) — both facts straight out of
     * `pl2303_set_termios`'s `C_CRTSCTS` branch.
     */
    override fun setFlowControl(rtsCts: Boolean) {
        if (chipType == Pl2303Type.HXN) {
            updateReg(
                PL2303_HXN_FLOWCTRL_REG, PL2303_HXN_FLOWCTRL_MASK,
                if (rtsCts) PL2303_HXN_FLOWCTRL_RTS_CTS else PL2303_HXN_FLOWCTRL_NONE,
            )
        } else {
            val value = if (!rtsCts) 0x00 else if (chipType == Pl2303Type.H) 0x40 else 0x60
            updateReg(0, PL2303_FLOWCTRL_MASK, value)
        }
    }

    /**
     * Direct 4-byte encoding for any baud rate on [chipType]'s known-good
     * table (or, for HXN, *any* rate at all — the one generation whose
     * firmware accepts an arbitrary direct value with no divisor table),
     * falling back to one of two divisor formulas — H/HX/HXD's, or TA/TB's
     * alternate one — for anything else. Mirrors `pl2303_encode_baud_rate`'s
     * dispatch logic.
     */
    private fun encodeBaudRate(buf: ByteArray, requestedBaud: Int) {
        val baud = requestedBaud.coerceIn(1, chipType.maxBaudRate)
        val nearestDirect = if (chipType.noDivisors) baud else nearestSupportedBaud(baud)
        when {
            baud == nearestDirect -> writeLe32(buf, baud)
            chipType.altDivisors -> encodeDivisorAlt(buf, baud)
            else -> encodeDivisor(buf, baud)
        }
    }

    private fun writeLe32(buf: ByteArray, value: Int) {
        buf[0] = (value and 0xFF).toByte()
        buf[1] = ((value ushr 8) and 0xFF).toByte()
        buf[2] = ((value ushr 16) and 0xFF).toByte()
        buf[3] = ((value ushr 24) and 0xFF).toByte()
    }

    /** `baud = 12MHz*32 / (mantissa * 4^exponent)` — H/HX/HXD's divisor format. */
    private fun encodeDivisor(buf: ByteArray, baud: Int) {
        val baseline = BAUD_BASELINE
        var mantissa = (baseline / baud).toInt()
        if (mantissa == 0) mantissa = 1 // avoid divide-by-zero on baud > baseline
        var exponent = 0
        while (mantissa >= 512) {
            if (exponent < 7) {
                mantissa = mantissa shr 2
                exponent++
            } else {
                mantissa = 511 // exponent maxed — trim mantissa and stop, matching upstream
                break
            }
        }
        buf[3] = 0x80.toByte()
        buf[2] = 0
        buf[1] = ((exponent shl 1) or (mantissa shr 8)).toByte()
        buf[0] = (mantissa and 0xFF).toByte()
    }

    /** `baud = 12MHz*32 / (mantissa * 2^exponent)` — TA/TB's alternate divisor format. */
    private fun encodeDivisorAlt(buf: ByteArray, baud: Int) {
        val baseline = BAUD_BASELINE
        var mantissa = (baseline / baud).toInt()
        if (mantissa == 0) mantissa = 1
        var exponent = 0
        while (mantissa >= 2048) {
            if (exponent < 15) {
                mantissa = mantissa shr 1
                exponent++
            } else {
                mantissa = 2047
                break
            }
        }
        buf[3] = 0x80.toByte()
        buf[2] = (exponent and 0x01).toByte()
        buf[1] = (((exponent and 0x01.inv()) shl 4) or (mantissa shr 8)).toByte()
        buf[0] = (mantissa and 0xFF).toByte()
    }

    private fun nearestSupportedBaud(baud: Int): Int {
        var i = 0
        while (i < BAUD_SUP.size && BAUD_SUP[i] <= baud) i++
        return when {
            i == BAUD_SUP.size -> BAUD_SUP[i - 1]
            i > 0 && (BAUD_SUP[i] - baud) > (baud - BAUD_SUP[i - 1]) -> BAUD_SUP[i - 1]
            else -> BAUD_SUP[i]
        }
    }

    /**
     * Parses `pl2303.c`'s `UART_STATE` byte off the interrupt-IN endpoint —
     * fully documented (see file doc's Part 6/N section) including the one
     * real per-sub-generation difference: HXN puts this byte at a different
     * packet offset ([UART_STATE_INDEX_HXN]) than every other sub-generation
     * ([UART_STATE_INDEX]), matching `pl2303_update_line_status`'s own
     * `type == TYPE_HXN` branch — the same [chipType] distinction
     * [detectType]/[runLegacyInitSequence]/[updateReg] already need
     * elsewhere in this class.
     */
    override fun pollLineStatus(timeoutMs: Int): LineStatus? {
        val n = readInterruptPacket(interruptBuffer, timeoutMs)
        val statusIndex = if (chipType == Pl2303Type.HXN) UART_STATE_INDEX_HXN else UART_STATE_INDEX
        if (n < statusIndex + 1) return null // no endpoint, timeout, transport error, or packet too short for this sub-generation's offset
        val status = interruptBuffer[statusIndex].toInt() and 0xFF
        return LineStatus(
            cts = (status and UART_CTS) != 0,
            dsr = (status and UART_DSR) != 0,
            dcd = (status and UART_DCD) != 0,
            ring = (status and UART_RING) != 0,
            breakError = (status and UART_BREAK_ERROR) != 0,
            frameError = (status and UART_FRAME_ERROR) != 0,
            parityError = (status and UART_PARITY_ERROR) != 0,
            overrunError = (status and UART_OVERRUN_ERROR) != 0,
        )
    }

    override fun close() {
        try { connection.releaseInterface(iface) } catch (e: Exception) { android.util.Log.d("UsbSerialDrivers", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
    }

    companion object {
        private const val TAG = "Pl2303SerialPort"
        private const val CONTROL_TIMEOUT_MS = 5000
        private const val BAUD_BASELINE = 12_000_000L * 32

        private const val REQTYPE_SET_LINE = 0x21    // host-to-device | class | interface
        private const val SET_LINE_REQUEST = 0x20
        private const val REQTYPE_SET_CONTROL = 0x21 // host-to-device | class | interface
        private const val SET_CONTROL_REQUEST = 0x22
        private const val CONTROL_DTR = 0x01
        private const val CONTROL_RTS = 0x02

        private const val REQTYPE_VENDOR_OUT = 0x40 // host-to-device | vendor | device
        private const val REQTYPE_VENDOR_IN = 0xC0  // device-to-host | vendor | device
        private const val VENDOR_READ_REQUEST = 0x01
        private const val VENDOR_WRITE_REQUEST = 0x01
        private const val PL2303_READ_TYPE_HX_STATUS = 0x8080

        // Flow-control register/bitfields — pl2303.c's PL2303_FLOWCTRL_MASK
        // and PL2303_HXN_FLOWCTRL_* (HXN uses a different register entirely,
        // consistent with detectType/runLegacyInitSequence needing separate
        // HXN handling everywhere else in this class too).
        private const val PL2303_FLOWCTRL_MASK = 0xF0
        private const val PL2303_HXN_FLOWCTRL_REG = 0x0A
        private const val PL2303_HXN_FLOWCTRL_MASK = 0x1C
        private const val PL2303_HXN_FLOWCTRL_RTS_CTS = 0x18
        private const val PL2303_HXN_FLOWCTRL_NONE = 0x1C

        // UART_STATE interrupt notification (Part 6/N) — pl2303.c's own
        // UART_STATE_INDEX(_HXN) offsets and bit layout.
        private const val UART_STATE_INDEX = 8
        private const val UART_STATE_INDEX_HXN = 6
        private const val UART_DCD = 0x01
        private const val UART_DSR = 0x02
        private const val UART_BREAK_ERROR = 0x04
        private const val UART_RING = 0x08
        private const val UART_FRAME_ERROR = 0x10
        private const val UART_PARITY_ERROR = 0x20
        private const val UART_OVERRUN_ERROR = 0x40
        private const val UART_CTS = 0x80

        private const val REQTYPE_STD_DEVICE_IN = 0x80 // device-to-host | standard | device
        private const val GET_DESCRIPTOR = 0x06
        private const val DEVICE_DESCRIPTOR_LENGTH = 18

        private val BAUD_SUP = intArrayOf(
            75, 150, 300, 600, 1200, 1800, 2400, 3600, 4800, 7200, 9600,
            14400, 19200, 28800, 38400, 57600, 115200, 230400, 460800,
            614400, 921600, 1_228_800, 2_457_600, 3_000_000, 6_000_000,
        )

        const val VENDOR_ID_PROLIFIC = 0x067B
        /**
         * Prolific's own PIDs (0x2303 covers the original PL2303/PL2303HX;
         * 0x2304 and 0x23a3-0x23f3 the TB/HXN "G-series" refresh) plus a
         * handful of common OEM-badged variants built on the same silicon
         * (ATEN UC-232A/RSAQ2-3-style adapters, Alcatel/AlDiga-marked
         * cables, HCR331). Not exhaustive — see `pl2303.h` for the dozens
         * of other OEM PIDs that exist — but [detectType] still gates
         * every match here on the real sub-generation before ever sending
         * a line-format request, so an unlisted PID is simply left
         * unsupported rather than misconfigured.
         */
        val PRODUCT_IDS = setOf(
            0x2303, 0x2304, 0x23a3, 0x23b3, 0x23c3, 0x23d3, 0x23e3, 0x23f3,
            0x04bb, 0x1234, 0xaaa0, 0xaaa2, 0xaaa8, 0x0611, 0x0612, 0x0609, 0x331a,
            // Verified against the upstream kernel's id_table (pl2303.c/.h): these two
            // same-VID PIDs were missing from the original list, so genuine Prolific
            // hardware using them (a Motorola-badged cable and Prolific's own "ZTEK"
            // adapter) was silently falling through to "unsupported chipset" instead
            // of ever reaching Pl2303SerialPort.probe.
            0x0307, 0xe1f1,
        )

        /**
         * Reads the sub-generation identity Android's [UsbDevice] doesn't
         * expose (`bDeviceClass`/`bMaxPacketSize0`/`bcdUSB`/`bcdDevice`)
         * via a plain standard `GET_DESCRIPTOR` control request — every
         * USB device answers this regardless of vendor, so no PL2303-
         * specific support is needed to read it — then applies the same
         * decision tree `pl2303_detect_type` uses. Returns null for a
         * descriptor this table doesn't recognize, or if the descriptor
         * read itself fails — surfaced by [probe] as "not supported"
         * rather than guessing a sub-generation (see file doc: this is
         * exactly the failure mode PL2303 support was withheld over until
         * this table existed).
         */
        private fun detectType(connection: UsbDeviceConnection): Pl2303Type? {
            val desc = ByteArray(DEVICE_DESCRIPTOR_LENGTH)
            val n = connection.controlTransfer(
                REQTYPE_STD_DEVICE_IN, GET_DESCRIPTOR, 0x0100, 0, desc, desc.size, CONTROL_TIMEOUT_MS,
            )
            if (n < DEVICE_DESCRIPTOR_LENGTH) return null

            val deviceClass = desc[4].toInt() and 0xFF
            val maxPacketSize0 = desc[7].toInt() and 0xFF
            val bcdUsb = (desc[2].toInt() and 0xFF) or ((desc[3].toInt() and 0xFF) shl 8)
            val bcdDevice = (desc[12].toInt() and 0xFF) or ((desc[13].toInt() and 0xFF) shl 8)

            // Legacy PL2303H, variants 0/1 (difference unknown upstream too).
            if (deviceClass == 0x02) return Pl2303Type.H
            if (maxPacketSize0 != 0x40) return Pl2303Type.H

            return when (bcdUsb) {
                0x0101, 0x0110 -> when (bcdDevice) {
                    0x0300 -> Pl2303Type.HX
                    0x0400 -> Pl2303Type.HXD
                    else -> Pl2303Type.HX
                }
                0x0200 -> when (bcdDevice) {
                    0x0100, 0x0105 -> Pl2303Type.HXN
                    0x0300 -> if (supportsHxStatus(connection)) Pl2303Type.TA else Pl2303Type.HXN
                    0x0500 -> if (supportsHxStatus(connection)) Pl2303Type.TB else Pl2303Type.HXN
                    0x0305, 0x0400, 0x0405, 0x0505, 0x0600, 0x0605,
                    0x0700, 0x0705, 0x0905, 0x1005 -> Pl2303Type.HXN
                    else -> null
                }
                else -> null
            }
        }

        /**
         * TA/TB are the only sub-generations that report `bcdDevice`
         * 0x300/0x500 *and* still answer the legacy
         * `PL2303_READ_TYPE_HX_STATUS` vendor-read — the newer HXN silicon
         * sharing those same `bcdDevice` values rejects it (the control
         * transfer fails), which is exactly how `pl2303_supports_hx_status`
         * tells them apart upstream.
         */
        private fun supportsHxStatus(connection: UsbDeviceConnection): Boolean {
            val buf = ByteArray(1)
            val r = connection.controlTransfer(
                REQTYPE_VENDOR_IN, VENDOR_READ_REQUEST, PL2303_READ_TYPE_HX_STATUS, 0, buf, buf.size, CONTROL_TIMEOUT_MS,
            )
            return r >= 1
        }

        fun probe(device: UsbDevice, connection: UsbDeviceConnection): Pl2303SerialPort? {
            val type = detectType(connection)
            if (type == null) {
                Log.w(TAG, "PL2303 sub-generation not recognized from its descriptor — treating as unsupported rather than guessing")
                return null
            }
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                var epInterrupt: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    when {
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN -> epIn = ep
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK -> epOut = ep
                        ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN -> epInterrupt = ep
                    }
                }
                if (epIn != null && epOut != null) return Pl2303SerialPort(connection, iface, epIn, epOut, epInterrupt, type)
            }
            return null
        }
    }
}

/**
 * Entry point [com.systemsgo.hex.serialconsole.protocol.SerialConsoleClient]
 * uses to go from an attached [UsbDevice] to a working [UsbSerialDriverPort]
 * (or find out none of this file's drivers apply).
 */
object UsbSerialProbe {
    /**
     * True if [device] looks like something one of this file's drivers can
     * open — used both to filter "candidate" devices for auto-detection and
     * to decide, before ever requesting USB permission from the user, that
     * a device is worth asking about at all. Deliberately permission-free
     * (only reads [UsbDevice]'s already-public descriptor fields), unlike
     * [createPort] which needs an opened connection.
     */
    fun isSupported(device: UsbDevice): Boolean {
        if (device.vendorId == Cp210xSerialPort.VENDOR_ID_SILABS && device.productId in Cp210xSerialPort.PRODUCT_IDS) {
            return true
        }
        if (device.vendorId == FtdiSerialPort.VENDOR_ID_FTDI && device.productId in FtdiSerialPort.PRODUCT_IDS) {
            return true
        }
        if ((device.vendorId to device.productId) in Ch340SerialPort.VENDOR_PRODUCT_IDS) {
            return true
        }
        if (device.vendorId == Pl2303SerialPort.VENDOR_ID_PROLIFIC && device.productId in Pl2303SerialPort.PRODUCT_IDS) {
            return true
        }
        return (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == 0x0A }
    }

    /** Every currently-attached device [isSupported] recognizes. */
    fun findCandidates(usbManager: UsbManager): List<UsbDevice> =
        usbManager.deviceList.values.filter { isSupported(it) }

    /**
     * Builds the right driver for [device] against an already-opened
     * [connection] (caller must already hold USB permission — see
     * `SerialConsoleClient.connectLocalDevice`). Tries CP210x's specific
     * VID/PID match first since that's unambiguous; falls back to the
     * generic CDC-ACM interface-class probe. Returns null if [device]
     * matches neither — caller surfaces that as an unsupported-chipset
     * error rather than guessing.
     */
    fun createPort(usbManager: UsbManager, device: UsbDevice, connection: UsbDeviceConnection): UsbSerialDriverPort? {
        if (device.vendorId == Cp210xSerialPort.VENDOR_ID_SILABS && device.productId in Cp210xSerialPort.PRODUCT_IDS) {
            Cp210xSerialPort.probe(usbManager, device, connection)?.let { return it }
        }
        if (device.vendorId == FtdiSerialPort.VENDOR_ID_FTDI && device.productId in FtdiSerialPort.PRODUCT_IDS) {
            FtdiSerialPort.probe(device, connection)?.let { return it }
        }
        if ((device.vendorId to device.productId) in Ch340SerialPort.VENDOR_PRODUCT_IDS) {
            Ch340SerialPort.probe(device, connection)?.let { return it }
        }
        if (device.vendorId == Pl2303SerialPort.VENDOR_ID_PROLIFIC && device.productId in Pl2303SerialPort.PRODUCT_IDS) {
            Pl2303SerialPort.probe(device, connection)?.let { return it }
        }
        return CdcAcmSerialPort.probe(usbManager, device, connection)
    }
}
