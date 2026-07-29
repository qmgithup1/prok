package com.systemsgo.hex.smartcard

/**
 * SMARTCARD-REDIRECT FEATURE / NFC-READER FEATURE: the minimal contract
 * [PcscUsbBridge] needs from *any* physical smart-card transport to back
 * the WinSCard calls `pcsc_shim.c` forwards to it — see that class's doc
 * comment for the full picture of how a `SCardConnect`/`SCardTransmit`/...
 * call from FreeRDP's MS-RDPESC channel ends up here.
 *
 * Two implementations exist:
 *  - [UsbCcidReader]: a contact reader talking CCID over `android.hardware.usb`
 *    (USB-IF "Smart Card CCID" 1.1) — the common case for a PIV/CAC card in
 *    a USB reader.
 *  - [NfcCcidReader]: a contactless ISO/IEC 14443-4 card read directly over
 *    Android's built-in NFC radio via `android.nfc.tech.IsoDep` — no reader
 *    hardware needed at all, just tapping a contactless PIV/CAC/eID card to
 *    the back of the phone. See that class's doc comment for how it
 *    synthesizes a PC/SC-style ATR, since a contactless tag never presents
 *    a real ISO/IEC 7816-3 ATR the way a contact card does.
 *
 * [PcscUsbBridge] holds at most one active reader of either kind at a time
 * (mirrors the single-reader/single-slot scope both implementations already
 * document individually) and calls through this interface so its own
 * `native*` methods — the ones `pcsc_shim.c` actually calls via JNI — never
 * need to know or care which transport is behind the active card.
 */
interface PcscCardReader {
    /** Whether this reader is currently open and able to service calls. */
    val isOpen: Boolean

    /**
     * Powers the card on (if not already) and returns its ATR (real, for a
     * contact card; synthesized, for a contactless one — see
     * [NfcCcidReader]), or `null` if no card responded.
     */
    fun powerOn(): ByteArray?

    /** Powers the card off. Safe to call even if no card was ever powered on. */
    fun powerOff()

    /** The ATR from the last successful [powerOn], or `null` if none yet. */
    fun lastAtr(): ByteArray?

    /** Best-effort "is a card physically present right now" check. */
    fun isCardPresent(): Boolean

    /** ISO/IEC 7816-3 protocol negotiated by the last [powerOn]: 0 = T=0, 1 = T=1. */
    fun activeProtocol(): Int

    /**
     * Blocks up to [timeoutMs] for a real presence change (insert/remove),
     * backing `SCardGetStatusChange`'s actual blocking contract. Returns
     * `false` if this transport has no way to detect a change (e.g. no
     * CCID interrupt pipe) or none occurred within the timeout.
     */
    fun waitForStatusChange(timeoutMs: Long): Boolean

    /** Exchanges one APDU with the card. Returns `null` on any failure. */
    fun transmit(apdu: ByteArray): ByteArray?

    /**
     * PCSC-GETATTRIB FEATURE: backs `SCardGetAttrib`, called by
     * `pcsc_shim.c` for the small set of attributes MS-RDPESC/winscard
     * callers actually query outside of a real transmit — reader vendor
     * name, ATR, negotiated protocol, and similar read-only reader state.
     * [attribId] is a `SCARD_ATTR_*` constant (PC/SC spec part 3, §3.2 —
     * same numbering `pcsc-lite`'s `winscard.h` uses). Returns `null` for
     * any attribute this reader doesn't have real data for — [PcscUsbBridge]
     * turns that into `SCARD_E_UNSUPPORTED_FEATURE`/`SCARD_F_UNKNOWN_ERROR`,
     * never a guessed value.
     *
     * Default implementation covers the two attributes derivable from
     * *any* [PcscCardReader] regardless of transport (ATR string, current
     * protocol) — see [PcscAttributes]. Override to add transport-specific
     * ones (e.g. [UsbCcidReader]'s real USB vendor/serial).
     */
    fun getAttrib(attribId: Int): ByteArray? = PcscAttributes.commonAttrib(attribId, lastAtr(), activeProtocol())

    /**
     * `SCardSetAttrib` — still always returns `false`, but not for lack of
     * looking: PC/SC's `SCardSetAttrib` has exactly **one** documented,
     * non-vendor-specific `dwAttrId` in Microsoft's own reference —
     * `SCARD_ATTR_SUPRESS_T1_IFS_REQUEST` (see
     * [PcscAttributes.SCARD_ATTR_SUPRESS_T1_IFS_REQUEST] for the
     * numeric value, cross-checked against `pcsc-lite`/FreeRDP/ReactOS
     * headers, and exactly why it still can't be honored here: this app
     * has no T=1 IFSD-negotiation code to suppress — that happens inside
     * CCID reader firmware or Android's `IsoDep`, below anything this app
     * controls). Every *other* writable PC/SC attribute (e.g.
     * `SCARD_ATTR_DEVICE_FRIENDLY_NAME`, vendor IFD config knobs) remains
     * reader/vendor-specific with no public spec for which bytes a given
     * IFD expects — the same "no documented source to derive it from"
     * reasoning behind declining to guess VNC AppleRA2's undocumented wire
     * bytes elsewhere in this app.
     *
     * Silently returning `true` here would tell the RDP server a reader
     * setting took effect when nothing happened; returning `false`
     * (mapped to `SCARD_E_UNSUPPORTED_FEATURE` by [PcscUsbBridge]) is the
     * honest result for both the one documented case (real spec, no local
     * capability to act on it) and every undocumented one (no spec at all).
     */
    fun setAttrib(attribId: Int, data: ByteArray): Boolean = false

    /**
     * `SCardControl` — the escape/vendor-IOCTL passthrough (CCID's optional
     * `PC_to_RDR_Escape`/`RDR_to_PC_Escape` pair on the USB side). Not
     * implemented for the same reason as [setAttrib]: the command/response
     * byte layout for any given reader's escape functions (secure PIN
     * entry, reader firmware queries, vendor config) isn't part of the
     * public CCID or PC/SC specs — it's documented only in each vendor's
     * own IFD driver, which this app has no access to. Faking a response
     * here risks a PIN-entry or config flow silently misbehaving on real
     * hardware, which is worse than a clear "unsupported" from the RDP
     * server. Always returns `null` ([PcscUsbBridge] maps that to
     * `SCARD_E_UNSUPPORTED_FEATURE`).
     */
    fun control(controlCode: Int, data: ByteArray): ByteArray? = null

    /** Releases any underlying hardware handle. Idempotent. */
    fun close()
}
