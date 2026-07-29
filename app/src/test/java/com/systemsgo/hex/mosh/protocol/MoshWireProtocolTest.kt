package com.systemsgo.hex.mosh.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MOSH-PROTOCOL FEATURE: plain-JVM round-trip tests for [MoshWire]'s
 * hand-rolled protobuf codec. These don't (and can't, in this sandbox)
 * confirm byte-for-byte compatibility with a real mosh-server capture —
 * see mosh/NOTES.md — but they do pin down that marshal(unmarshal(x)) == x
 * for every field this app actually sends/receives, and that the field
 * numbers documented in MoshWireProtocol.kt's file doc comment are what's
 * actually on the wire.
 */
class MoshWireProtocolTest {

    @Test
    fun transportInstruction_roundTrips_withDiff() {
        val original = MoshTransportInstruction(
            protocolVersion = 2,
            oldNum = 5,
            newNum = 6,
            ackNum = 4,
            throwawayNum = 0,
            diff = byteArrayOf(1, 2, 3, 4, 5),
        )
        val decoded = MoshTransportInstruction.unmarshal(original.marshal())
        assertEquals(original.protocolVersion, decoded.protocolVersion)
        assertEquals(original.oldNum, decoded.oldNum)
        assertEquals(original.newNum, decoded.newNum)
        assertEquals(original.ackNum, decoded.ackNum)
        assertEquals(original.throwawayNum, decoded.throwawayNum)
        assertArrayEquals(original.diff, decoded.diff)
    }

    @Test
    fun transportInstruction_roundTrips_withEmptyDiff() {
        val original = MoshTransportInstruction(oldNum = 0, newNum = 1, ackNum = 0, throwawayNum = 0)
        val decoded = MoshTransportInstruction.unmarshal(original.marshal())
        assertEquals(0, decoded.diff.size)
        assertEquals(1L, decoded.newNum)
    }

    @Test
    fun transportInstruction_largeSequenceNumbers_surviveVarintEncoding() {
        val original = MoshTransportInstruction(
            oldNum = Long.MAX_VALUE / 2,
            newNum = Long.MAX_VALUE / 2 + 1,
            ackNum = Long.MAX_VALUE / 3,
            throwawayNum = 12345678901L,
        )
        val decoded = MoshTransportInstruction.unmarshal(original.marshal())
        assertEquals(original.oldNum, decoded.oldNum)
        assertEquals(original.newNum, decoded.newNum)
        assertEquals(original.ackNum, decoded.ackNum)
        assertEquals(original.throwawayNum, decoded.throwawayNum)
    }

    @Test
    fun hostInstruction_hoststring_roundTrips() {
        val text = "\u001B[2J\u001B[HHello, Mosh!".toByteArray(Charsets.UTF_8)
        val original = MoshHostInstruction(hoststring = text)
        val decoded = MoshHostInstruction.unmarshal(original.marshal())
        assertArrayEquals(text, decoded.hoststring)
        assertNull(decoded.width)
        assertNull(decoded.echoAckNum)
    }

    @Test
    fun hostInstruction_resize_roundTrips() {
        val original = MoshHostInstruction(width = 120, height = 40)
        val decoded = MoshHostInstruction.unmarshal(original.marshal())
        assertEquals(120, decoded.width)
        assertEquals(40, decoded.height)
        assertNull(decoded.hoststring)
    }

    @Test
    fun hostInstruction_echoAck_roundTrips() {
        val original = MoshHostInstruction(echoAckNum = 42L)
        val decoded = MoshHostInstruction.unmarshal(original.marshal())
        assertEquals(42L, decoded.echoAckNum)
    }

    @Test
    fun hostMessage_multipleInstructions_roundTrip() {
        val instructions = listOf(
            MoshHostInstruction(hoststring = "line one\r\n".toByteArray()),
            MoshHostInstruction(width = 80, height = 24),
            MoshHostInstruction(hoststring = "line two\r\n".toByteArray()),
        )
        val decoded = MoshHostMessage.unmarshal(MoshHostMessage.marshal(instructions))
        assertEquals(3, decoded.size)
        assertArrayEquals("line one\r\n".toByteArray(), decoded[0].hoststring)
        assertEquals(80, decoded[1].width)
        assertArrayEquals("line two\r\n".toByteArray(), decoded[2].hoststring)
    }

    @Test
    fun userInstruction_keystroke_roundTrips() {
        val keys = "ls -la\n".toByteArray(Charsets.UTF_8)
        val original = MoshUserInstruction(keys = keys)
        val decoded = MoshUserInstruction.unmarshal(original.marshal())
        assertArrayEquals(keys, decoded.keys)
    }

    @Test
    fun userInstruction_keysAndResize_together_roundTrip() {
        val original = MoshUserInstruction(keys = byteArrayOf(0x03), width = 100, height = 32)
        val decoded = MoshUserInstruction.unmarshal(original.marshal())
        assertArrayEquals(byteArrayOf(0x03), decoded.keys)
        assertEquals(100, decoded.width)
        assertEquals(32, decoded.height)
    }

    @Test
    fun userMessage_emptyList_roundTrips() {
        val decoded = MoshUserMessage.unmarshal(MoshUserMessage.marshal(emptyList()))
        assertEquals(0, decoded.size)
    }

    @Test
    fun unknownFields_areSkippedWithoutError() {
        // A field number this codec doesn't know about (e.g. mosh-go's own
        // non-standard "latch" extension, field 8) must be skipped cleanly
        // rather than throwing — this is required for forward-compatibility
        // with any real mosh-server that might add fields this app doesn't
        // recognize yet.
        val out = java.io.ByteArrayOutputStream()
        MoshWire.appendTagVarint(out, 2, 5L) // old_num
        MoshWire.appendTagVarint(out, 3, 6L) // new_num
        MoshWire.appendTagVarint(out, 4, 4L) // ack_num
        MoshWire.appendTagVarint(out, 5, 0L) // throwaway_num
        MoshWire.appendTagBytes(out, 8, byteArrayOf(9, 9, 9)) // unknown field — must be skipped
        val decoded = MoshTransportInstruction.unmarshal(out.toByteArray())
        assertEquals(5L, decoded.oldNum)
        assertEquals(6L, decoded.newNum)
    }
}
