package com.systemsgo.hex.smartcard

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.util.Log
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * NFC-READER FEATURE: a contactless smart-card reader implementation of
 * [PcscCardReader], talking directly to an ISO/IEC 14443-4 card over
 * Android's built-in NFC radio via [IsoDep] — no external reader hardware
 * needed, unlike [UsbCcidReader]. Covers the common contactless-capable
 * PIV/CAC/eID card case (most modern US federal PIV and many national eID
 * cards ship as *dual-interface*: the same chip answers both over contacts
 * and over ISO/IEC 14443-4 RF), which real Windows Remote Desktop clients
 * (mstsc included) also support via any PC/SC-registered contactless
 * reader — this class exists to make this app's own in-process PC/SC
 * replacement ([PcscUsbBridge]) able to reach that same card population
 * without requiring a physical USB-CCID reader at all.
 *
 * ## Discovery model
 * Unlike [UsbCcidReader] (persistent USB device, discovered once and kept
 * open for the session), an NFC tag only exists for as long as it's held
 * against the device's antenna — there is no "plug in once, stays
 * available" state. [Companion.enableReaderMode] wraps
 * [NfcAdapter.enableReaderMode], which Android only delivers callbacks for
 * while the hosting Activity is in the foreground; [PcscUsbBridge] enables
 * this in `RdpSessionActivity.onResume` and disables it in `onPause` (see
 * that Activity's SMARTCARD-REDIRECT / NFC-READER comments), mirroring how
 * every other Android NFC reader-mode integration is expected to behave.
 *
 * ## Synthesizing an ATR
 * A contactless ISO/IEC 14443-4 tag never presents a real ISO/IEC 7816-3
 * ATR the way a contact card in a CCID reader does — [IsoDep] only exposes
 * the PICC's *historical bytes* (Type A, via [IsoDep.getHistoricalBytes])
 * or *higher-layer response* (Type B, via [IsoDep.getHiLayerResponse]).
 * PC/SC Part 3 Supplement (the vendor-neutral "PC/SC in contactless"
 * addendum every contactless-aware PC/SC driver on desktop/Windows also
 * follows) defines a standard way to wrap those into a synthetic ATR so
 * upper layers that only understand "an ATR" — like FreeRDP's MS-RDPESC
 * channel here — keep working unmodified: a fixed T=1/T=CL-shaped
 * preamble, historical/hi-layer bytes as the "T1" payload, and a trailing
 * XOR checksum (TCK) exactly as ISO/IEC 7816-3 §8.2.5 requires for any ATR
 * whose T0 high nibble indicates more than just T=0. [synthesizeAtr] builds
 * exactly that.
 *
 * NOT a general NFC/ISO-14443 implementation: single tag, T=CL APDU
 * passthrough only via [IsoDep.transceive] — no support for storage-only
 * (MIFARE Classic/Ultralight) cards, which have no APDU interface at all
 * and are out of scope for smart-card logon regardless.
 */
class NfcCcidReader private constructor(
    private val isoDep: IsoDep,
) : PcscCardReader {

    private val ioLock = ReentrantLock()
    private val statusChangeLock = ReentrantLock()
    private val statusChangeCondition: Condition = statusChangeLock.newCondition()

    @Volatile private var atr: ByteArray? = null
    @Volatile private var connected = false
    @Volatile private var lost = false

    companion object {
        private const val TAG = "NfcCcidReader"

        /**
         * Enables NFC reader mode on [activity] for ISO/IEC 14443-4 tags
         * (both Type A and Type B — `FLAG_READER_NFC_A or FLAG_READER_NFC_B`),
         * calling [onReader] with a freshly connected [NfcCcidReader] each
         * time a compatible tag is tapped, or `null` if the tag that was
         * tapped isn't ISO-DEP-capable (e.g. a storage-only tag) or failed
         * to connect. Safe to call even on a device with no NFC adapter —
         * silently does nothing in that case, matching [UsbCcidReader]'s own
         * "reader class this device doesn't have" no-op shape.
         *
         * `FLAG_READER_SKIP_NDEF_CHECK` is set so Android doesn't waste time
         * probing for an NDEF payload on every tap — this app only ever
         * wants the raw ISO-DEP/APDU channel, never NDEF records.
         */
        fun enableReaderMode(activity: Activity, onReader: (NfcCcidReader?) -> Unit) {
            val adapter = NfcAdapter.getDefaultAdapter(activity) ?: run {
                Log.i(TAG, "No NFC adapter on this device — contactless smart-card reading unavailable")
                return
            }
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            adapter.enableReaderMode(activity, { tag: Tag -> onReader(tryConnect(tag)) }, flags, null)
        }

        /** Disables reader mode previously enabled via [enableReaderMode]. Idempotent. */
        fun disableReaderMode(activity: Activity) {
            val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
            try {
                adapter.disableReaderMode(activity)
            } catch (e: IllegalStateException) {
                // Activity already finishing/not resumed — same defensive
                // shape as UsbCcidReader.close() swallowing a redundant
                // release, nothing left to disable in that state anyway.
                Log.w(TAG, "disableReaderMode: activity not in a valid state", e)
            }
        }

        private fun tryConnect(tag: Tag): NfcCcidReader? {
            val isoDep = IsoDep.get(tag) ?: run {
                Log.w(TAG, "Tapped tag has no ISO-DEP (14443-4) technology — not a smart card this bridge can read")
                return null
            }
            return try {
                isoDep.connect()
                // A generous timeout: unlike a USB CCID reader (whose bulk
                // transfers already have their own BULK_TIMEOUT_MS), a slow
                // PIV applet doing an RSA/ECC signature over NFC can
                // legitimately take longer than IsoDep's ~300ms hardware
                // default before the OS itself would time it out.
                isoDep.timeout = 20_000
                NfcCcidReader(isoDep)
            } catch (e: Exception) {
                Log.w(TAG, "IsoDep.connect() failed", e)
                null
            }
        }

        /**
         * Builds a PC/SC Part 3 Supplement-style synthetic ATR from a
         * contactless tag's historical bytes (Type A) or hi-layer response
         * (Type B), so the rest of the WinSCard surface ([PcscUsbBridge],
         * `pcsc_shim.c`, FreeRDP's MS-RDPESC channel) can keep treating this
         * exactly like a real contact-card ATR.
         *
         * Layout (ISO/IEC 7816-3 §8 shape, all fixed per the PC/SC
         * contactless supplement):
         *   TS  = 0x3B (direct convention)
         *   T0  = 0x8_ where the low nibble is the historical-byte count K
         *         (capped at 15 — the low nibble's max) and the high nibble
         *         bit for TD1-present is set so the T=1/T=CL indication
         *         below is actually read.
         *   TD1 = 0x80 (no TA/TB/TC1, TD1 present) — placeholder kept
         *         simple since this bridge only ever declares T=1 for
         *         contactless (see [activeProtocol]).
         *   TD2 = 0x01 (protocol T=1, no further interface bytes)
         *   T1..TK = the historical/hi-layer bytes themselves, truncated to
         *         K bytes.
         *   TCK = XOR checksum of T0..TK, per §8.2.5 (mandatory whenever
         *         the ATR conveys more than T=0's "no TD1" minimal form,
         *         which this synthetic one always does).
         */
        private fun synthesizeAtr(historicalOrHiLayer: ByteArray?): ByteArray {
            val hist = (historicalOrHiLayer ?: ByteArray(0)).let {
                if (it.size > 15) it.copyOf(15) else it
            }
            val k = hist.size
            val t0 = (0x80 or k)
            val head = byteArrayOf(0x3B, t0.toByte(), 0x80.toByte(), 0x01)
            val body = head + hist
            var tck = 0
            // TCK covers T0 through the last historical byte (everything
            // after TS), per ISO/IEC 7816-3 §8.2.5.
            for (i in 1 until body.size) tck = tck xor body[i].toInt()
            return body + tck.toByte()
        }
    }

    override val isOpen: Boolean get() = isoDep.isConnected && !lost

    override fun powerOn(): ByteArray? = ioLock.withLock {
        if (lost) return@withLock null
        if (!isoDep.isConnected) {
            try {
                isoDep.connect()
            } catch (e: Exception) {
                Log.w(TAG, "powerOn: reconnect failed", e)
                markLost()
                return@withLock null
            }
        }
        val hist = isoDep.historicalBytes ?: isoDep.hiLayerResponse
        val synthesized = synthesizeAtr(hist)
        atr = synthesized
        connected = true
        Log.i(TAG, "Contactless card connected — synthesized ATR=" +
            synthesized.joinToString(" ") { "%02x".format(it) })
        synthesized
    }

    override fun powerOff() = ioLock.withLock {
        try { isoDep.close() } catch (e: Exception) { android.util.Log.d("NfcCcidReader", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        atr = null
        connected = false
        Unit
    }

    override fun lastAtr(): ByteArray? = atr

    /**
     * Always T=1 — the T=CL (Contactless) transmission protocol
     * ISO/IEC 14443-4 mandates is layered on top of, and reported upstream
     * as, T=1; [IsoDep.transceive] already handles the CL framing
     * transparently so this bridge never needs to distinguish the two.
     */
    override fun activeProtocol(): Int = UsbCcidReader.PROTOCOL_T1

    override fun isCardPresent(): Boolean = connected && isoDep.isConnected && !lost

    /**
     * Unlike [UsbCcidReader] (a real hardware interrupt endpoint), an NFC
     * tag has no out-of-band "it was removed" signal — the only way to find
     * out is to try talking to it and see [TagLostException] come back (see
     * [transmit]/[markLost]). This polls [isCardPresent] at a short interval
     * until [timeoutMs] elapses or a loss is observed, which is the same
     * "no hardware event, sleep out the window" fallback
     * [UsbCcidReader.waitForStatusChange] already uses for CCID readers
     * with no interrupt pipe.
     */
    override fun waitForStatusChange(timeoutMs: Long): Boolean {
        val before = isCardPresent()
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000
        return statusChangeLock.withLock {
            while (isCardPresent() == before && System.nanoTime() < deadline) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) break
                statusChangeCondition.awaitNanos((remainingMs.coerceAtMost(250)) * 1_000_000)
            }
            isCardPresent() != before
        }
    }

    override fun transmit(apdu: ByteArray): ByteArray? = ioLock.withLock {
        if (lost) return@withLock null
        try {
            isoDep.transceive(apdu)
        } catch (e: TagLostException) {
            Log.w(TAG, "transmit: tag lost mid-transaction", e)
            markLost()
            null
        } catch (e: Exception) {
            Log.w(TAG, "transmit: transceive failed", e)
            null
        }
    }

    override fun close() {
        try { isoDep.close() } catch (e: Exception) { android.util.Log.d("NfcCcidReader", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        connected = false
    }

    private fun markLost() {
        lost = true
        connected = false
        statusChangeLock.withLock { statusChangeCondition.signalAll() }
    }
}
