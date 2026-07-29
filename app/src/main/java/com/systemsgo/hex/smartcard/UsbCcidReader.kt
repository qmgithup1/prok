package com.systemsgo.hex.smartcard

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SMARTCARD-REDIRECT FEATURE: a from-scratch USB-CCID driver talking directly
 * to a smart-card reader over Android's USB Host API
 * (`android.hardware.usb`), implementing just enough of the CCID class spec
 * (USB-IF "Smart Card CCID" 1.1) to power a card on, read its ATR, and
 * exchange APDUs.
 *
 * This exists because Android has no system PC/SC resource manager (no
 * `pcscd`) for a real cross-compiled PCSC-lite to talk to — see
 * `PcscUsbBridge`'s doc comment and `app/src/main/cpp/SETUP.md`'s
 * SMARTCARD-REDIRECT FEATURE section for the full picture of how this fits
 * together with the native `libpcsclite.so` shim
 * (`app/src/main/cpp/pcsc_shim/pcsc_shim.c`) FreeRDP's smartcard channel
 * actually calls into. This class is the *only* piece that speaks to real
 * hardware; everything above it (the shim, FreeRDP's MS-RDPESC channel) is
 * just APDU plumbing.
 *
 * NOT a general CCID implementation: single reader, single slot, T=0/T=1
 * APDU passthrough only (`PC_to_RDR_XfrBlock`) — no support for CCID's
 * optional secure-PIN-entry, escape, or multi-slot commands. That covers the
 * overwhelmingly common case (a PIV/CAC-style reader with one card slot used
 * for smart-card logon), which is what this feature targets.
 *
 * CAVEAT this driver does NOT resolve by itself: even a fully working driver
 * only reaches a reader that's (a) actually CCID-class, (b) granted USB
 * permission by the user, and (c) has a card inserted with an application
 * FreeRDP's remote session's smart-card logon expects. None of that is
 * guaranteed on an arbitrary device/reader combination — treat this as
 * best-effort hardware support, not a certified PC/SC stack.
 */
class UsbCcidReader private constructor(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val ccidInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
    private val endpointInterrupt: UsbEndpoint?,
) : PcscCardReader {
    private var connection: UsbDeviceConnection? = null
    private val ioLock = ReentrantLock()
    private val sequence = AtomicInteger(0)
    @Volatile private var atr: ByteArray? = null

    /** Protocol negotiated by the last successful [powerOn], per ISO/IEC 7816-3
     * TD-chain parsing (see [parseAtrProtocol]) — `0` for T=0, `1` for T=1.
     * Defaults to T=0, the ISO-mandated default when no TD1 is present. */
    @Volatile private var activeProtocol: Int = PROTOCOL_T0

    // ── Interrupt-IN (RDR_to_PC_NotifySlotChange) card-presence tracking ───
    // Only used if [endpointInterrupt] is non-null (CCID's interrupt pipe is
    // optional for readers whose ICC is never removable — see class doc).
    // When present, a dedicated thread blocks on the interrupt endpoint and
    // updates [interruptCardPresent] the instant the reader reports a slot
    // change, and signals [statusChangeLock]'s condition so callers can do a
    // real blocking wait (SCardGetStatusChange's actual contract) instead of
    // an immediate poll-and-return.
    private val statusChangeLock = ReentrantLock()
    private val statusChangeCondition: Condition = statusChangeLock.newCondition()
    @Volatile private var interruptCardPresent: Boolean? = null // null = no interrupt data yet
    @Volatile private var interruptThread: Thread? = null
    @Volatile private var interruptThreadRunning = false

    companion object {
        private const val TAG = "UsbCcidReader"

        /** USB-IF assigned interface class for CCID (Smart Card) devices. */
        private const val CCID_INTERFACE_CLASS = 0x0B

        // ── CCID bulk-OUT message types (PC_to_RDR_*) ──────────────────────
        private const val PC_TO_RDR_ICC_POWER_ON: Byte = 0x62
        private const val PC_TO_RDR_ICC_POWER_OFF: Byte = 0x63
        private const val PC_TO_RDR_GET_SLOT_STATUS: Byte = 0x65
        private const val PC_TO_RDR_XFR_BLOCK: Byte = 0x6F

        // ── CCID bulk-IN message types (RDR_to_PC_*) ────────────────────────
        private const val RDR_TO_PC_DATA_BLOCK: Byte = 0x80.toByte()
        private const val RDR_TO_PC_SLOT_STATUS: Byte = 0x81.toByte()

        // ── CCID interrupt-IN message type ──────────────────────────────────
        private const val RDR_TO_PC_NOTIFY_SLOT_CHANGE: Byte = 0x50

        /** ISO/IEC 7816-3 protocol numbers, as encoded in a TDi byte's low nibble. */
        const val PROTOCOL_T0 = 0
        const val PROTOCOL_T1 = 1

        private const val CCID_HEADER_LEN = 10
        /** Per the USB-IF CCID 1.1 spec, dwLength (and thus header+payload) is
         * bounded; used as a sanity cap so a corrupt/malicious header can't
         * make us grow a buffer without limit. */
        private const val CCID_MAX_MESSAGE_LEN = 65_544
        private const val BULK_TIMEOUT_MS = 5000
        /** Timeout for each individual interrupt-IN poll; short so [close] can
         * stop the listener thread promptly rather than blocking on it. */
        private const val INTERRUPT_POLL_TIMEOUT_MS = 1000
        private const val ACTION_USB_PERMISSION = "com.systemsgo.hex.smartcard.USB_PERMISSION"

        /**
         * Parses an ATR's TD-chain per ISO/IEC 7816-3 §8.2.3 to determine which
         * transmission protocol the card actually negotiated, instead of
         * assuming T=1. Algorithm: T0's high nibble (Y1) says which of
         * TA1/TB1/TC1/TD1 follow; every subsequent TDi's high nibble (Yi+1)
         * says the same for the next group, and TDi's *low* nibble is that
         * group's protocol number T. The protocol actually used is the T value
         * from the *last* TDi in the chain — if there's no TDi at all (the
         * majority case per real-world ATR surveys), ISO 7816-3 mandates the
         * default of T=0.
         *
         * Returns [PROTOCOL_T0] or [PROTOCOL_T1] — CCID/PC/SC readers in
         * practice only ever negotiate one of these two for APDU exchange
         * (T=RAW/T=14/etc. aren't applicable to PIV/CAC-class cards this
         * feature targets), so any other T value found is treated as T0
         * (matches how CCID's own PC_to_RDR_IccPowerOn only offers T0/T1/"any"
         * as bPowerSelect choices in the first place).
         */
        fun parseAtrProtocol(atr: ByteArray?): Int {
            if (atr == null || atr.size < 2) return PROTOCOL_T0
            // atr[0] = TS (convention byte, not needed for protocol detection)
            var i = 1
            val t0 = atr[i].toInt() and 0xFF
            i++
            var y = (t0 shr 4) and 0x0F // bitmap: bit0=TAi, bit1=TBi, bit2=TCi, bit3=TDi present
            var protocol = PROTOCOL_T0
            var sawTd = false
            while (true) {
                if (y and 0x1 != 0) { if (i >= atr.size) break; i++ } // TAi present, skip
                if (y and 0x2 != 0) { if (i >= atr.size) break; i++ } // TBi present, skip
                if (y and 0x4 != 0) { if (i >= atr.size) break; i++ } // TCi present, skip
                if (y and 0x8 != 0) {
                    if (i >= atr.size) break
                    val tdi = atr[i].toInt() and 0xFF
                    i++
                    val t = tdi and 0x0F
                    protocol = if (t == 1) PROTOCOL_T1 else PROTOCOL_T0
                    sawTd = true
                    y = (tdi shr 4) and 0x0F
                } else {
                    break // no further TDi — chain ends, `protocol` holds the last-seen T
                }
            }
            return if (sawTd) protocol else PROTOCOL_T0
        }

        /**
         * Scans [device]'s interfaces for a CCID one (class 0x0B) with a
         * bulk-IN and bulk-OUT endpoint, opens it, and returns a ready
         * [UsbCcidReader] — or null if [device] isn't a usable CCID reader.
         * Caller must already hold USB permission for [device] (see
         * [requestPermissionAndOpen]) or [UsbManager.openDevice] will fail.
         */
        fun tryOpen(usbManager: UsbManager, device: UsbDevice): UsbCcidReader? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass != CCID_INTERFACE_CLASS) continue

                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                var epInterrupt: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                        else if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                    } else if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        ep.direction == UsbConstants.USB_DIR_IN
                    ) {
                        // Optional per CCID spec — only readers that support ICC
                        // insertion/removal are required to expose it. When
                        // present it's what lets us do a real blocking
                        // SCardGetStatusChange instead of an instant poll.
                        epInterrupt = ep
                    }
                }
                if (epIn == null || epOut == null) continue

                val reader = UsbCcidReader(usbManager, device, iface, epIn, epOut, epInterrupt)
                if (reader.open()) return reader
                return null
            }
            Log.w(TAG, "Device ${device.deviceName} has no CCID (class 0x0B) interface with bulk in/out endpoints")
            return null
        }

        /**
         * Finds every currently-attached CCID reader, requesting USB
         * permission (async, via [PendingIntent]/[BroadcastReceiver]) for
         * ones the user hasn't already granted it to. [onReady] is invoked
         * on the calling thread's looper once for each reader as its
         * permission resolves (granted → non-null [UsbCcidReader]; denied →
         * null, purely informational).
         *
         * Called from [PcscUsbBridge] (in turn called from
         * `RdpSessionActivity`) whenever a profile has smart-card redirect
         * enabled — not unconditionally on every launch, so this never
         * prompts users who don't use the feature.
         */
        fun discoverAndRequestPermission(
            context: Context,
            onReady: (UsbCcidReader?) -> Unit,
        ) {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                Log.w(TAG, "USB_SERVICE unavailable on this device — no smart-card reader support")
                onReady(null)
                return
            }

            val candidates = usbManager.deviceList.values.filter { device ->
                (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == CCID_INTERFACE_CLASS }
            }
            if (candidates.isEmpty()) {
                onReady(null)
                return
            }

            for (device in candidates) {
                if (usbManager.hasPermission(device)) {
                    onReady(tryOpen(usbManager, device))
                    continue
                }

                val permissionIntent = PendingIntent.getBroadcast(
                    context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                )
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        ctx.applicationContext.unregisterReceiver(this)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (!granted) {
                            Log.i(TAG, "USB permission denied for smart-card reader ${device.deviceName}")
                            onReady(null)
                            return
                        }
                        onReady(tryOpen(usbManager, device))
                    }
                }
                val filter = IntentFilter(ACTION_USB_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.applicationContext.registerReceiver(receiver, filter)
                }
                usbManager.requestPermission(device, permissionIntent)
            }
        }
    }

    private fun open(): Boolean {
        val conn = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "openDevice failed for ${device.deviceName} — USB permission missing or reader unplugged mid-open")
            return false
        }
        if (!conn.claimInterface(ccidInterface, true)) {
            Log.e(TAG, "claimInterface failed for CCID interface on ${device.deviceName}")
            conn.close()
            return false
        }
        connection = conn
        startInterruptListenerIfAvailable()
        return true
    }

    override fun close() {
        stopInterruptListener()
        ioLock.withLock {
            connection?.let {
                it.releaseInterface(ccidInterface)
                it.close()
            }
            connection = null
        }
    }

    /**
     * Spins up the interrupt-IN listener thread if this reader exposed one
     * (see [endpointInterrupt]'s doc). No-op (leaves [interruptCardPresent]
     * `null` forever) for readers without an interrupt pipe — [isCardPresent]
     * and [waitForStatusChange] both already fall back to the
     * [powerOn]/[atr]-based check in that case.
     */
    private fun startInterruptListenerIfAvailable() {
        val ep = endpointInterrupt ?: return
        interruptThreadRunning = true
        val t = Thread({
            // bmSlotICCState is (2 bits * slot count) rounded up to a byte —
            // for this driver's single-slot scope that's always exactly one
            // byte, per the CCID 1.1 spec's interrupt-message layout.
            val buf = ByteArray(2)
            while (interruptThreadRunning) {
                val conn = connection ?: break
                val received = conn.bulkTransfer(ep, buf, buf.size, INTERRUPT_POLL_TIMEOUT_MS)
                if (!interruptThreadRunning) break
                if (received < 2) continue // timeout (no event) or short read — just poll again
                if (buf[0] != RDR_TO_PC_NOTIFY_SLOT_CHANGE) continue
                val slotState = buf[1].toInt() and 0xFF
                // Slot 0 (this driver's only slot): bit0 = current presence,
                // bit1 = changed-since-last-message (see CCID interrupt
                // message spec) — we only need the presence bit here.
                val present = (slotState and 0x01) != 0
                statusChangeLock.withLock {
                    interruptCardPresent = present
                    statusChangeCondition.signalAll()
                }
            }
        }, "UsbCcidReader-Interrupt").apply { isDaemon = true }
        interruptThread = t
        t.start()
    }

    private fun stopInterruptListener() {
        interruptThreadRunning = false
        statusChangeLock.withLock { statusChangeCondition.signalAll() }
        interruptThread?.let { t ->
            if (t !== Thread.currentThread()) {
                try { t.join(INTERRUPT_POLL_TIMEOUT_MS.toLong() * 2) } catch (e: InterruptedException) { android.util.Log.d("UsbCcidReader", "interrupted, restoring interrupt status"); Thread.currentThread().interrupt() }
            }
        }
        interruptThread = null
    }

    /** True once [open] succeeded and [close] hasn't been called since. */
    override val isOpen: Boolean get() = connection != null

    /**
     * Powers the card on and returns its ATR (Answer To Reset) — the
     * identifying byte string PC/SC's `SCardConnect` reports to callers.
     * Returns null on any transport or protocol error (no card present,
     * reader unplugged, timeout, ...) — callers should treat that as
     * "no card in reader" rather than propagating a hard error, matching
     * how PC/SC itself reports an empty slot.
     */
    override fun powerOn(): ByteArray? = ioLock.withLock {
        val resp = exchangeControl(PC_TO_RDR_ICC_POWER_ON, byteArrayOf(0, 0, 0)) ?: return@withLock null
        if (resp.type != RDR_TO_PC_DATA_BLOCK) {
            Log.w(TAG, "IccPowerOn: unexpected response type 0x${"%02x".format(resp.type)}")
            return@withLock null
        }
        if (resp.status != 0) {
            Log.w(TAG, "IccPowerOn: reader reported error status=${resp.status} error=${resp.error} (no card / power-on failed)")
            return@withLock null
        }
        atr = resp.data
        activeProtocol = parseAtrProtocol(resp.data)
        Log.i(TAG, "IccPowerOn: ATR=${resp.data.joinToString(" ") { "%02x".format(it) }} " +
            "protocol=T${activeProtocol}")
        resp.data
    }

    override fun powerOff() = ioLock.withLock {
        exchangeControl(PC_TO_RDR_ICC_POWER_OFF, byteArrayOf(0, 0, 0))
        atr = null
        Unit
    }

    /** Cached ATR from the last successful [powerOn], or null if none/powered off. */
    override fun lastAtr(): ByteArray? = atr

    /**
     * Protocol negotiated by the last successful [powerOn], parsed from the
     * card's real ATR per ISO/IEC 7816-3 (see [parseAtrProtocol]) rather than
     * assumed — [PROTOCOL_T0] or [PROTOCOL_T1].
     */
    override fun activeProtocol(): Int = activeProtocol

    /**
     * True if a card is currently present. Prefers the interrupt endpoint's
     * live state ([interruptCardPresent]) when this reader exposes one —
     * that reflects the *actual current* hardware state, including removals
     * that happen without an intervening [powerOn] call. Falls back to
     * "did the last [powerOn] succeed" for readers with no interrupt pipe.
     */
    override fun isCardPresent(): Boolean = interruptCardPresent ?: (atr != null)

    /**
     * Blocks the calling thread until the card-presence state actually
     * changes or [timeoutMs] elapses — the real contract `SCardGetStatusChange`
     * is supposed to have. Backed by the interrupt endpoint's condition
     * variable when available; if this reader has no interrupt pipe (CCID's
     * interrupt pipe is optional — see class doc), there's no hardware event
     * to wait on, so this returns `false` immediately rather than blocking on
     * nothing (the shim's caller then falls back to its own poll loop).
     *
     * Returns `true` if a change was observed before the timeout, `false` on
     * timeout or if this reader has no interrupt pipe.
     */
    override fun waitForStatusChange(timeoutMs: Long): Boolean {
        if (endpointInterrupt == null) {
            // No interrupt pipe on this reader (CCID makes it optional — see
            // class doc). There's no hardware event to wait on, but we still
            // have to honor the "block for up to timeoutMs" half of
            // SCardGetStatusChange's contract — returning instantly here
            // would make the native shim's chunked-wait loop busy-spin
            // instead of actually blocking. Sleep out the window instead.
            try { Thread.sleep(timeoutMs.coerceAtLeast(0)) } catch (e: InterruptedException) { android.util.Log.d("UsbCcidReader", "interrupted, restoring interrupt status"); Thread.currentThread().interrupt() }
            return false
        }
        val before = interruptCardPresent
        return statusChangeLock.withLock {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (interruptCardPresent == before && interruptThreadRunning) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return@withLock false
                statusChangeCondition.awaitNanos(remaining)
            }
            interruptThreadRunning && interruptCardPresent != before
        }
    }

    /**
     * Sends [apdu] to the card via `PC_to_RDR_XfrBlock` and returns the
     * card's response APDU (including its trailing SW1/SW2 status bytes,
     * same as PC/SC's `SCardTransmit` would return) — or null on any
     * transport/protocol error.
     */
    override fun transmit(apdu: ByteArray): ByteArray? = ioLock.withLock {
        val resp = exchangeData(PC_TO_RDR_XFR_BLOCK, byteArrayOf(0, 0, 0), apdu) ?: return@withLock null
        if (resp.type != RDR_TO_PC_DATA_BLOCK || resp.status != 0) {
            Log.w(TAG, "XfrBlock: type=0x${"%02x".format(resp.type)} status=${resp.status} error=${resp.error}")
            return@withLock null
        }
        resp.data
    }

    /**
     * PCSC-GETATTRIB FEATURE: adds the two attributes only a real USB
     * reader can answer (vendor name / IFD serial number) from
     * [UsbDevice]'s own descriptor fields — genuine device data, not a
     * guess — and falls back to [PcscAttributes.commonAttrib] (ATR/
     * protocol) for everything else, same as [NfcCcidReader] gets for
     * free from the interface's default.
     */
    override fun getAttrib(attribId: Int): ByteArray? = when (attribId) {
        PcscAttributes.SCARD_ATTR_VENDOR_NAME ->
            (device.manufacturerName ?: device.productName)?.toByteArray(Charsets.US_ASCII)
        PcscAttributes.SCARD_ATTR_VENDOR_IFD_SERIAL_NO ->
            // Requires a still-held USB permission grant to read on modern
            // Android (same permission this reader already needed to open
            // the device at all) — null here just means "unavailable",
            // same as any other unanswerable attribute.
            device.serialNumber?.toByteArray(Charsets.US_ASCII)
        else -> PcscAttributes.commonAttrib(attribId, lastAtr(), activeProtocol())
    }

    // ── CCID bulk transport ─────────────────────────────────────────────────

    private class CcidResponse(val type: Byte, val status: Int, val error: Int, val data: ByteArray)

    private fun exchangeControl(type: Byte, specific: ByteArray): CcidResponse? =
        exchangeData(type, specific, ByteArray(0))

    /** Builds a CCID bulk-OUT message, sends it, and reads back one bulk-IN response. */
    private fun exchangeData(type: Byte, specific: ByteArray, data: ByteArray): CcidResponse? {
        val conn = connection ?: return null
        require(specific.size == 3) { "CCID message-specific field must be exactly 3 bytes" }

        val seq = (sequence.getAndIncrement() and 0xFF).toByte()
        val header = ByteArray(CCID_HEADER_LEN + data.size)
        header[0] = type
        header[1] = (data.size and 0xFF).toByte()
        header[2] = ((data.size shr 8) and 0xFF).toByte()
        header[3] = ((data.size shr 16) and 0xFF).toByte()
        header[4] = ((data.size shr 24) and 0xFF).toByte()
        header[5] = 0 // bSlot — single-slot reader
        header[6] = seq
        specific.copyInto(header, 7)
        data.copyInto(header, CCID_HEADER_LEN)

        val sent = conn.bulkTransfer(endpointOut, header, header.size, BULK_TIMEOUT_MS)
        if (sent != header.size) {
            Log.e(TAG, "CCID bulk-OUT failed (sent=$sent, expected=${header.size})")
            return null
        }

        // Read the response. A single Android bulkTransfer() call already
        // drains everything the device sends back-to-back up to a short
        // packet (the USB-defined end-of-transfer signal), so one call with
        // a large-enough buffer normally captures header + full payload
        // together. We size that first read generously (one max-size CCID
        // message per the spec's abData bound, §5.1 note: dwLength's field
        // itself is bounded to 65,544 bytes total incl. header) but then —
        // unlike before — actually *check* whether more data remains rather
        // than silently truncating: if the reader ever splits a large
        // extended-APDU response (bigger than fits in this bufferful) across
        // more than one bulk transaction, we keep issuing further
        // bulkTransfer reads and append them until dwLength bytes of payload
        // have actually been collected, instead of assuming one call was
        // always enough.
        var inBuf = ByteArray(endpointIn.maxPacketSize.coerceAtLeast(CCID_HEADER_LEN) * 8)
        var received = conn.bulkTransfer(endpointIn, inBuf, inBuf.size, BULK_TIMEOUT_MS)
        if (received < CCID_HEADER_LEN) {
            Log.e(TAG, "CCID bulk-IN failed or short (received=$received)")
            return null
        }

        val respType = inBuf[0]
        val respLen = (inBuf[1].toInt() and 0xFF) or
            ((inBuf[2].toInt() and 0xFF) shl 8) or
            ((inBuf[3].toInt() and 0xFF) shl 16) or
            ((inBuf[4].toInt() and 0xFF) shl 24)
        val respSeq = inBuf[6]
        if (respSeq != seq) {
            Log.w(TAG, "CCID response sequence mismatch (expected=$seq, got=$respSeq) — stale response, treating as error")
            return null
        }
        val status = (inBuf[7].toInt() and 0xC0) shr 6   // bStatus bits 7:6 = command status
        val error = inBuf[8].toInt() and 0xFF             // bError, meaningful only when status indicates failure

        val totalNeeded = CCID_HEADER_LEN + respLen
        if (respLen < 0 || totalNeeded > CCID_MAX_MESSAGE_LEN) {
            Log.e(TAG, "CCID response declares an implausible dwLength=$respLen — rejecting as malformed")
            return null
        }
        while (received < totalNeeded) {
            // Grow the buffer if needed and keep reading; the device is
            // still sending this message's remaining bytes.
            if (totalNeeded > inBuf.size) inBuf = inBuf.copyOf(totalNeeded)
            val more = conn.bulkTransfer(
                endpointIn, inBuf, received, totalNeeded - received, BULK_TIMEOUT_MS
            )
            if (more <= 0) {
                Log.e(TAG, "CCID response claims $respLen data bytes but transport stalled after $received/$totalNeeded")
                return null
            }
            received += more
        }
        val respData = inBuf.copyOfRange(CCID_HEADER_LEN, totalNeeded)
        return CcidResponse(respType, status, error, respData)
    }
}
