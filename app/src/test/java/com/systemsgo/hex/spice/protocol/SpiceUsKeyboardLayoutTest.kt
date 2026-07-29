package com.systemsgo.hex.spice.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SPICE-PASTE-AS-KEYSTROKES FEATURE: plain-JVM tests for
 * [SpiceUsKeyboardLayout], the char→(scancode, needsShift) table
 * [SpiceSessionClient.sendText] uses.
 */
class SpiceUsKeyboardLayoutTest {

    @Test
    fun `lowercase letter needs no shift`() {
        val (code, shift) = SpiceUsKeyboardLayout.scancodeFor('a')!!
        assertEquals(0x1E, code)
        assertEquals(false, shift)
    }

    @Test
    fun `uppercase letter reuses the same scancode with shift`() {
        val (lowerCode, lowerShift) = SpiceUsKeyboardLayout.scancodeFor('a')!!
        val (upperCode, upperShift) = SpiceUsKeyboardLayout.scancodeFor('A')!!
        assertEquals(lowerCode, upperCode)
        assertEquals(false, lowerShift)
        assertEquals(true, upperShift)
    }

    @Test
    fun `digit and its shifted symbol share a scancode`() {
        val (digitCode, digitShift) = SpiceUsKeyboardLayout.scancodeFor('1')!!
        val (bangCode, bangShift) = SpiceUsKeyboardLayout.scancodeFor('!')!!
        assertEquals(0x02, digitCode)
        assertEquals(false, digitShift)
        assertEquals(digitCode, bangCode)
        assertEquals(true, bangShift)
    }

    @Test
    fun `space tab and newline map to their dedicated scancodes`() {
        assertEquals(0x39, SpiceUsKeyboardLayout.scancodeFor(' ')!!.first)
        assertEquals(0x0F, SpiceUsKeyboardLayout.scancodeFor('\t')!!.first)
        assertEquals(0x1C, SpiceUsKeyboardLayout.scancodeFor('\n')!!.first)
    }

    @Test
    fun `character with no US keyboard key is unmapped, not guessed`() {
        // Arabic letter — genuinely has no scancode on a physical US
        // keyboard; must come back null, not some nearest-guess mapping.
        assertNull(SpiceUsKeyboardLayout.scancodeFor('ك'))
        assertNull(SpiceUsKeyboardLayout.scancodeFor('€'))
    }
}
