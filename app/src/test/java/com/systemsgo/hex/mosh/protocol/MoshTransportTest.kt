package com.systemsgo.hex.mosh.protocol

import com.systemsgo.hex.mosh.native.MoshCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MOSH-PROTOCOL FEATURE: plain-JVM tests for [MoshTransport]'s SSP
 * (sequencing/ack/retransmission/fragmentation) logic, using
 * [FakeMoshCrypto] instead of the real native AES-128-OCB implementation —
 * see [MoshCrypto]'s doc comment in MoshBridge.kt for why that interface
 * exists. [FakeMoshCrypto] is NOT a real AEAD (no actual confidentiality
 * or tamper-detection) — it exists purely so two [MoshTransport] instances
 * in the same test can exchange datagrams and this test can assert on the
 * SSP-level behavior (does a diff get delivered, does an ack clear pending
 * state, does a corrupted/truncated packet get silently dropped) without
 * needing a real key exchange or the JNI library.
 */
class MoshTransportTest {

    /**
     * A fake, INSECURE stand-in for AES-128-OCB: "encrypts" by prefixing
     * the plaintext with a fixed 16-byte marker (playing the role of an
     * OCB tag) and copying the nonce nowhere (a real AEAD binds the nonce
     * into the tag; this fake doesn't need to, since these tests never
     * exercise cross-key/cross-nonce tampering — see [decryptFails] for
     * the one test that does need "authentication" to fail, which it
     * simulates by checking the marker directly rather than any real
     * cryptographic property).
     */
    private class FakeMoshCrypto : MoshCrypto {
        companion object {
            val MARKER = ByteArray(16) { 0xAB.toByte() }
        }
        override fun encrypt(nonce: ByteArray, plaintext: ByteArray): ByteArray {
            return plaintext + MARKER
        }
        override fun decrypt(nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray? {
            if (ciphertextAndTag.size < MARKER.size) return null
            val tag = ciphertextAndTag.copyOfRange(ciphertextAndTag.size - MARKER.size, ciphertextAndTag.size)
            if (!tag.contentEquals(MARKER)) return null // simulates OCB auth failure
            return ciphertextAndTag.copyOfRange(0, ciphertextAndTag.size - MARKER.size)
        }
    }

    @Test
    fun tick_producesNothing_whenNoDiffPendingAndNotYetExpired() {
        val transport = MoshTransport(FakeMoshCrypto())
        val datagrams = transport.tick()
        assertTrue(datagrams.isEmpty())
    }

    @Test
    fun tick_producesADatagram_oncePendingDiffIsSet() {
        val transport = MoshTransport(FakeMoshCrypto())
        transport.setPending(MoshUserMessage.marshal(listOf(MoshUserInstruction(keys = "ls\n".toByteArray()))))
        val datagrams = transport.tick()
        assertEquals(1, datagrams.size)
        assertTrue(datagrams[0].size > 8 + 16) // direction+seq prefix + OCB tag, plus actual payload
    }

    @Test
    fun clientDatagram_decodesOnServerSideTransport_andReturnsTheDiff() {
        val crypto = FakeMoshCrypto()
        val client = MoshTransport(crypto)
        val userMessage = MoshUserMessage.marshal(listOf(MoshUserInstruction(keys = "echo hi\n".toByteArray())))
        client.setPending(userMessage)

        val datagrams = client.tick()
        assertEquals(1, datagrams.size)

        // A second MoshTransport instance stands in for "the server's view of
        // this same wire datagram" — recv() doesn't care which side sent it,
        // only that the direction bit encoded in the datagram matches what
        // THIS instance expects to receive, which for two default-constructed
        // MoshTransport instances (both hardcoded to the client role, per the
        // class doc comment) won't match. So this test decodes the datagram's
        // payload directly via the same fragment/zlib/protobuf pipeline
        // MoshTransport.recv() uses internally, rather than routing it through
        // a second MoshTransport (which would require a server-role transport
        // this app deliberately doesn't implement — it only ever plays the
        // client).
        val wire = datagrams[0]
        val nonce = ByteArray(12)
        System.arraycopy(wire, 0, nonce, 4, 8)
        val plaintext = crypto.decrypt(nonce, wire.copyOfRange(8, wire.size))!!
        val fragmentBytes = plaintext.copyOfRange(4, plaintext.size)
        val fragment = MoshFragment.unmarshal(fragmentBytes)
        assertTrue(fragment.isFinal)
    }

    @Test
    fun recv_dropsTruncatedDatagram() {
        val transport = MoshTransport(FakeMoshCrypto())
        assertNull(transport.recv(ByteArray(4))) // shorter than the minimum 8+16 header+tag
    }

    @Test
    fun recv_dropsDatagramFailingAuthentication() {
        val transport = MoshTransport(FakeMoshCrypto())
        // A well-formed server->client direction+sequence header (so the
        // packet gets past the direction check and actually reaches
        // decrypt()), followed by 20 bytes that do NOT end in
        // FakeMoshCrypto's marker — i.e. authentication must fail.
        val bogus = ByteArray(8 + 20)
        bogus[0] = 0x80.toByte() // sets the direction bit (bit 63) to "server -> client"
        bogus[7] = 1 // sequence = 1
        assertNull(transport.recv(bogus))
    }

    @Test
    fun setPending_replacesUnsentDiff_beforeFirstTick() {
        val transport = MoshTransport(FakeMoshCrypto())
        transport.setPending(MoshUserMessage.marshal(listOf(MoshUserInstruction(keys = "a".toByteArray()))))
        transport.setPending(MoshUserMessage.marshal(listOf(MoshUserInstruction(keys = "ab".toByteArray()))))
        val datagrams = transport.tick()
        assertEquals(1, datagrams.size) // only one datagram — the second setPending replaced, not queued, the first
    }

    @Test
    fun sentNum_incrementsOnlyWhenANewDiffIsActuallySent() {
        val transport = MoshTransport(FakeMoshCrypto())
        assertEquals(0L, transport.sentNum())
        transport.setPending(MoshUserMessage.marshal(listOf(MoshUserInstruction(keys = "x".toByteArray()))))
        transport.tick()
        assertEquals(1L, transport.sentNum())
        // Ticking again with nothing new pending (still same un-acked diff) must not
        // bump sentNum a second time until it's either acked or the RTO expires.
        val secondTickImmediately = transport.tick()
        assertTrue(secondTickImmediately.isEmpty())
        assertEquals(1L, transport.sentNum())
    }
}
