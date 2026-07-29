package com.systemsgo.hex.smartcard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PCSC-GETATTRIB FEATURE: plain-JVM tests for [PcscAttributes.commonAttrib],
 * the transport-agnostic half of [PcscCardReader.getAttrib].
 */
class PcscAttributesTest {

    @Test
    fun `ATR string attribute returns the reader's actual ATR`() {
        val atr = byteArrayOf(0x3B, 0x00)
        val result = PcscAttributes.commonAttrib(PcscAttributes.SCARD_ATTR_ATR_STRING, atr, UsbCcidReader.PROTOCOL_T0)
        assertArrayEquals(atr, result)
    }

    @Test
    fun `protocol types attribute maps T0 and T1 to their real bitmask values`() {
        val t0 = PcscAttributes.commonAttrib(PcscAttributes.SCARD_ATTR_PROTOCOL_TYPES, null, UsbCcidReader.PROTOCOL_T0)
        val t1 = PcscAttributes.commonAttrib(PcscAttributes.SCARD_ATTR_PROTOCOL_TYPES, null, UsbCcidReader.PROTOCOL_T1)
        // winscard.h: SCARD_PROTOCOL_T0 = 1, SCARD_PROTOCOL_T1 = 2 — not the
        // raw 0/1 PcscCardReader.activeProtocol() uses internally.
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), t0)
        assertArrayEquals(byteArrayOf(2, 0, 0, 0), t1)
    }

    @Test
    fun `unknown or vendor-specific attribute is null, never guessed`() {
        assertNull(PcscAttributes.commonAttrib(PcscAttributes.SCARD_ATTR_VENDOR_NAME, byteArrayOf(0x3B), UsbCcidReader.PROTOCOL_T0))
        assertNull(PcscAttributes.commonAttrib(0xDEADBEEF.toInt(), byteArrayOf(0x3B), UsbCcidReader.PROTOCOL_T0))
    }
}
