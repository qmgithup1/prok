package com.systemsgo.hex.smartcard

/**
 * PCSC-GETATTRIB FEATURE: `SCARD_ATTR_*` constants (PC/SC spec part 3,
 * §3.2 — numerically identical to `pcsc-lite`'s public `winscard.h`, which
 * is where these values are actually standardized/publicly documented,
 * unlike the vendor-escape commands [PcscCardReader.control] declines to
 * guess) plus the attributes derivable from any [PcscCardReader]
 * regardless of transport.
 */
internal object PcscAttributes {
    // SCARD_ATTR_VALUE(Class, Tag) = (Class << 16) | Tag — same encoding
    // winscard.h uses. Only the classes/tags this object actually answers
    // are listed; anything else is deliberately left for [commonAttrib] to
    // return null on.
    private const val SCARD_CLASS_VENDOR_INFO = 1
    private const val SCARD_CLASS_ICC_STATE = 9
    // PCSC-SETATTRIB FEATURE: SCARD_CLASS_SYSTEM(0x7FFF) is winscard.h's own
    // class number for this one — same encoding, just a class value large
    // enough it's worth calling out explicitly rather than left implicit.
    private const val SCARD_CLASS_SYSTEM = 0x7FFF

    const val SCARD_ATTR_VENDOR_NAME = (SCARD_CLASS_VENDOR_INFO shl 16) or 0x0100
    const val SCARD_ATTR_VENDOR_IFD_SERIAL_NO = (SCARD_CLASS_VENDOR_INFO shl 16) or 0x0103
    const val SCARD_ATTR_ATR_STRING = (SCARD_CLASS_ICC_STATE shl 16) or 0x0303
    const val SCARD_ATTR_PROTOCOL_TYPES = (SCARD_CLASS_ICC_STATE shl 16) or 0x0201

    /**
     * PCSC-SETATTRIB FEATURE: the *only* `SCARD_ATTR_*` that Microsoft's own
     * `winscard.h` reference (learn.microsoft.com/.../nf-winscard-scardsetattrib)
     * documents as a defined, non-vendor-specific `SCardSetAttrib` value —
     * confirmed identically (`SCARD_ATTR_VALUE(SCARD_CLASS_SYSTEM, 0x0007)`
     * = `0x7FFF0007`) across `pcsc-lite`'s public `reader.h`, FreeRDP's
     * `winpr/smartcard.h`, and ReactOS's `winsmcrd.h` — four independent
     * sources agreeing on both the numeric value and its one-line meaning:
     * suppress the reader from sending a T=1 protocol IFSD (Information
     * Field Size Device) negotiation request to the card, for cards that
     * don't handle an IFSD request correctly.
     *
     * Finding this doesn't change [PcscCardReader.setAttrib]'s behavior,
     * though — see that function's doc comment for why: this app doesn't
     * do its own T=1 block framing (no S-block/IFSD code anywhere in
     * [UsbCcidReader]/[NfcCcidReader]), so there is no IFSD request in this
     * codebase to suppress in the first place. Real CCID reader hardware
     * handles T=1 IFSD negotiation in its own firmware before the host
     * ever sees it; [NfcCcidReader]'s contactless path hands that off to
     * Android's `IsoDep.transceive()` the same way. Neither exposes a
     * "don't send IFSD" knob to the host, so honoring this attribute would
     * require a capability neither transport has, not just a missing spec.
     */
    const val SCARD_ATTR_SUPRESS_T1_IFS_REQUEST = (SCARD_CLASS_SYSTEM shl 16) or 0x0007

    /**
     * Attributes answerable purely from [atr]/[activeProtocol] — true for
     * every [PcscCardReader] implementation, contact or contactless alike.
     * [UsbCcidReader] additionally overrides [PcscCardReader.getAttrib] to
     * add the real USB vendor/serial ones this function can't provide.
     */
    fun commonAttrib(attribId: Int, atr: ByteArray?, activeProtocol: Int): ByteArray? = when (attribId) {
        SCARD_ATTR_ATR_STRING -> atr
        SCARD_ATTR_PROTOCOL_TYPES -> {
            // winscard.h's SCARD_PROTOCOL_T0/T1 are bitmask values (1, 2),
            // not the raw 0/1 PcscCardReader.activeProtocol() returns —
            // a raw pass-through here would tell the caller "T=0 or
            // nothing negotiated" for every T=1 card.
            val bit = if (activeProtocol == UsbCcidReader.PROTOCOL_T1) 2 else 1
            byteArrayOf(bit.toByte(), 0, 0, 0)
        }
        else -> null
    }
}
