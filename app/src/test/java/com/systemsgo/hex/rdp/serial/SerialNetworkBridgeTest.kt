package com.systemsgo.hex.rdp.serial

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * SERIAL-OVER-NETWORK FEATURE: pure-Kotlin tests for SerialNetworkBridge's
 * telnet IAC-escaping (RFC 854/2217), independent of any live socket — see
 * SerialNetworkBridge's class doc, "What this class does today" section.
 */
class SerialNetworkBridgeTest {

    private val IAC = 0xFF.toByte()

    @Test
    fun `encodeIac leaves data with no 0xFF bytes untouched`() {
        val data = byteArrayOf(1, 2, 3, 0, 127)
        assertArrayEquals(data, SerialNetworkBridge.encodeIac(data, 0, data.size))
    }

    @Test
    fun `encodeIac doubles a literal 0xFF byte`() {
        val data = byteArrayOf(1, IAC, 2)
        val expected = byteArrayOf(1, IAC, IAC, 2)
        assertArrayEquals(expected, SerialNetworkBridge.encodeIac(data, 0, data.size))
    }

    @Test
    fun `encodeIac handles consecutive 0xFF bytes`() {
        val data = byteArrayOf(IAC, IAC)
        val expected = byteArrayOf(IAC, IAC, IAC, IAC)
        assertArrayEquals(expected, SerialNetworkBridge.encodeIac(data, 0, data.size))
    }

    @Test
    fun `encodeIac honors offset and length`() {
        val data = byteArrayOf(9, 9, 1, IAC, 2, 9, 9)
        val expected = byteArrayOf(1, IAC, IAC, 2)
        assertArrayEquals(expected, SerialNetworkBridge.encodeIac(data, 2, 3))
    }

    @Test
    fun `decodeIac collapses a doubled 0xFF back to one`() {
        val wire = byteArrayOf(1, IAC, IAC, 2)
        val expected = byteArrayOf(1, IAC, 2)
        assertArrayEquals(expected, SerialNetworkBridge.decodeIac(wire))
    }

    @Test
    fun `encodeIac then decodeIac round-trips arbitrary data`() {
        val data = byteArrayOf(0, 1, IAC, IAC, 5, IAC, 127.toByte(), IAC)
        val roundTripped = SerialNetworkBridge.decodeIac(SerialNetworkBridge.encodeIac(data, 0, data.size))
        assertArrayEquals(data, roundTripped)
    }

    @Test
    fun `encodeIac of empty data is empty`() {
        assertArrayEquals(ByteArray(0), SerialNetworkBridge.encodeIac(ByteArray(0), 0, 0))
    }
}
