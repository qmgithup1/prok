package com.systemsgo.hex.mosh.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * MOSH-PROTOCOL FEATURE: plain-JVM tests for [MoshFragment]/[moshFragmentize]/
 * [MoshFragmentAssembler] — the datagram-fragmentation layer described in
 * MoshFragment.kt's file doc comment.
 */
class MoshFragmentTest {

    @Test
    fun singleFragment_roundTrips() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val frag = MoshFragment(id = 42L, fragmentNum = 0, isFinal = true, payload = payload)
        val decoded = MoshFragment.unmarshal(frag.marshal())
        assertEquals(42L, decoded.id)
        assertEquals(0, decoded.fragmentNum)
        assertTrue(decoded.isFinal)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun emptyPayload_producesOneFinalFragment() {
        val frags = moshFragmentize(id = 1L, data = ByteArray(0))
        assertEquals(1, frags.size)
        assertTrue(frags[0].isFinal)
        assertEquals(0, frags[0].payload.size)
    }

    @Test
    fun payloadUnderOneFragment_producesSingleFinalFragment() {
        val data = Random.nextBytes(500)
        val frags = moshFragmentize(id = 7L, data = data)
        assertEquals(1, frags.size)
        assertTrue(frags[0].isFinal)
        assertArrayEquals(data, frags[0].payload)
    }

    @Test
    fun payloadOverOneFragment_splitsCorrectly() {
        val data = Random.nextBytes(MOSH_MAX_FRAGMENT_PAYLOAD * 2 + 37)
        val frags = moshFragmentize(id = 9L, data = data)
        assertEquals(3, frags.size)
        assertEquals(0, frags[0].fragmentNum)
        assertEquals(false, frags[0].isFinal)
        assertEquals(1, frags[1].fragmentNum)
        assertEquals(false, frags[1].isFinal)
        assertEquals(2, frags[2].fragmentNum)
        assertTrue(frags[2].isFinal)
        assertEquals(MOSH_MAX_FRAGMENT_PAYLOAD, frags[0].payload.size)
        assertEquals(MOSH_MAX_FRAGMENT_PAYLOAD, frags[1].payload.size)
        assertEquals(37, frags[2].payload.size)
    }

    @Test
    fun assembler_reassemblesInOrder() {
        val data = Random.nextBytes(MOSH_MAX_FRAGMENT_PAYLOAD * 3 + 100)
        val frags = moshFragmentize(id = 1L, data = data)
        val assembler = MoshFragmentAssembler()
        var result: ByteArray? = null
        for (f in frags) {
            val r = assembler.add(f)
            if (r != null) result = r
        }
        assertArrayEquals(data, result)
    }

    @Test
    fun assembler_reassemblesOutOfOrder() {
        val data = Random.nextBytes(MOSH_MAX_FRAGMENT_PAYLOAD * 3 + 100)
        val frags = moshFragmentize(id = 1L, data = data).shuffled(Random(42))
        val assembler = MoshFragmentAssembler()
        var result: ByteArray? = null
        for (f in frags) {
            val r = assembler.add(f)
            if (r != null) result = r
        }
        assertArrayEquals(data, result)
    }

    @Test
    fun assembler_dropsStaleFragmentsFromOlderInstruction() {
        val assembler = MoshFragmentAssembler()
        val newData = "second".toByteArray()
        val newFrags = moshFragmentize(id = 5L, data = newData)
        for (f in newFrags) assembler.add(f)

        // A fragment from an older instruction id arriving late (network reorder).
        val staleResult = assembler.add(MoshFragment(id = 4L, fragmentNum = 0, isFinal = true, payload = byteArrayOf(1)))
        assertNull(staleResult)
    }

    @Test
    fun assembler_newerInstructionResetsPartialState() {
        val assembler = MoshFragmentAssembler()
        // Start instruction 1 with a non-final fragment (incomplete).
        assembler.add(MoshFragment(id = 1L, fragmentNum = 0, isFinal = false, payload = byteArrayOf(1, 2, 3)))
        // Instruction 2 arrives complete before instruction 1 ever finished.
        val result = assembler.add(MoshFragment(id = 2L, fragmentNum = 0, isFinal = true, payload = byteArrayOf(9, 9)))
        assertArrayEquals(byteArrayOf(9, 9), result)
    }

    @Test
    fun fragmentHeader_encodesFragmentNumAndFinalBitSeparately() {
        // fragment_num up to 0x7FFF must not collide with the final bit (0x8000).
        val frag = MoshFragment(id = 1L, fragmentNum = 0x7FFF, isFinal = false, payload = byteArrayOf())
        val wire = frag.marshal()
        val decoded = MoshFragment.unmarshal(wire)
        assertEquals(0x7FFF, decoded.fragmentNum)
        assertEquals(false, decoded.isFinal)
    }
}
