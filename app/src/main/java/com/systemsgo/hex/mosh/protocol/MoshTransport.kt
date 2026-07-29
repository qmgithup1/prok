package com.systemsgo.hex.mosh.protocol

import com.systemsgo.hex.mosh.native.MoshCrypto
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * MOSH-PROTOCOL FEATURE: the SSP (State Synchronization Protocol) transport
 * itself — sequence numbering, acknowledgement, retransmission timing
 * (Jacobson/Karels RTT estimation, same algorithm TCP uses, per RFC 6298),
 * and the marshal -> zlib-compress -> fragment -> encrypt pipeline (and its
 * exact inverse on receive).
 *
 * This is a direct Kotlin port of the `Transport` type in the independent,
 * MIT-licensed `unixshells/mosh-go` reference implementation's
 * `transport.go` (see [MoshWire]'s file doc comment in
 * MoshWireProtocol.kt for how that source was found and cross-checked) —
 * itself documented there as tested against the real C `mosh-server`/
 * `mosh-client`. This app only ever plays the *client* role, so
 * [MoshTransport] hardcodes that direction rather than taking an
 * `isServer` flag mosh-go needs for its dual client+server support.
 *
 * Outer datagram layout (cleartext prefix + AEAD-encrypted body):
 *   [direction_seq : 8 bytes, big-endian]  -- bit 63 = direction, bits 0-62 = sequence
 *   [OCB(key, nonce=4 zero bytes + above 8 bytes) of:
 *       [timestamp : 2 bytes][timestamp_reply : 2 bytes][fragment bytes]
 *   ]
 *
 * The encrypted body's first 4 bytes (timestamp + timestamp_reply) exist
 * purely for RTT estimation (mirroring TCP timestamp options) — mosh's own
 * paper documents these fields directly and this matches mosh-go's
 * `encryptFragment`/`Recv` exactly.
 */
class MoshTransport(private val crypto: MoshCrypto) {

    companion object {
        private const val DIR_TO_SERVER = 0L
        private const val DIR_TO_CLIENT = 1L shl 63
        private const val SEQ_MASK = DIR_TO_CLIENT.inv()
        private const val MIN_DATAGRAM = 8 + 16 // direction+seq prefix + OCB tag, no payload

        private const val INITIAL_RTO_MS = 1000L
        private const val MIN_RTO_MS = 250L
        private const val MAX_RTO_MS = 10_000L
    }

    // Outgoing state.
    private var sentNum = 0L
    private var ackedByRemote = 0L
    private var pendingDiff: ByteArray? = null
    private var diffSent = false
    private var diffOldNum = 0L
    private var hasPendingBase = false
    private var pendingDataAck = false
    private var seqOut = 0L

    // Incoming state.
    private val receivedNums = mutableListOf(0L)
    private var ackNum = 0L
    private var sentAckNum = 0L
    private var throwawayNum = 0L
    private var lastRecvOldNum = 0L
    private var lastRecvNewNum = 0L
    private var seqInMax = 0L
    private var seqInMaxSet = false

    // Timestamps / RTT (RFC 6298 Jacobson/Karels).
    private var lastSendMs = System.currentTimeMillis()
    private var lastRecvMs = System.currentTimeMillis()
    private var lastRemoteTs: Int = 0
    private var srttMs = 0.0
    private var rttVarMs = 0.0
    private var rtoMs = INITIAL_RTO_MS
    private var rttInit = false

    private val assembler = MoshFragmentAssembler()

    @Synchronized fun ackedByRemote(): Long = ackedByRemote
    @Synchronized fun sentNum(): Long = sentNum
    @Synchronized fun lastRecvOldNum(): Long = lastRecvOldNum
    @Synchronized fun lastRecvNewNum(): Long = lastRecvNewNum
    @Synchronized fun throwawayNum(): Long = throwawayNum
    @Synchronized fun lastRecvMs(): Long = lastRecvMs
    @Synchronized fun rtoMs(): Long = rtoMs.toLong()
    /** The Jacobson/Karels smoothed RTT estimate, or 0 before the first echoed timestamp arrives. */
    @Synchronized fun smoothedRttMs(): Long = if (rttInit) srttMs.toLong() else 0L

    /** Sets the diff payload (a marshaled [MoshUserMessage], for this app's client role) to send. */
    @Synchronized fun setPending(diff: ByteArray) {
        if (diff.isNotEmpty()) diffSent = false
        pendingDiff = diff
    }

    /** Forces the next [tick] to send even with no new diff pending — used for a keepalive. */
    @Synchronized fun forceNextSend() {
        lastSendMs = 0L
    }

    /**
     * Produces outgoing wire datagrams if it's time to send (a new diff, a
     * pending ack, an RTO-driven retransmission, or a forced keepalive).
     * Returns an empty list if nothing to send.
     */
    @Synchronized fun tick(): List<ByteArray> {
        val now = System.currentTimeMillis()
        val haveDiff = (pendingDiff?.isNotEmpty() == true)
        val haveNewDiff = haveDiff && !diffSent
        val needAck = ackNum > sentAckNum
        val expired = (now - lastSendMs) >= rtoMs.toLong()
        val urgentAck = pendingDataAck

        if (!(haveNewDiff || needAck || expired || urgentAck)) return emptyList()

        if (haveNewDiff) {
            sentNum++
            diffSent = true
            if (!hasPendingBase) {
                diffOldNum = ackedByRemote
                hasPendingBase = true
            }
        }
        pendingDataAck = false

        val oldNum = if (haveDiff) diffOldNum else ackedByRemote
        val ti = MoshTransportInstruction(
            protocolVersion = 2,
            oldNum = oldNum,
            newNum = sentNum,
            ackNum = ackNum,
            throwawayNum = 0L, // this app is always the client; the client never tells the server to throw away states
            diff = pendingDiff ?: ByteArray(0),
        )
        sentAckNum = ackNum

        val compressed = zlibCompress(ti.marshal())
        val fragments = moshFragmentize(sentNum, compressed)
        val datagrams = fragments.map { encryptFragment(it, now) }

        lastSendMs = now
        return datagrams
    }

    /**
     * Processes one incoming wire datagram. Returns the reassembled diff
     * payload (a marshaled [MoshHostMessage], for this app's client role)
     * once a complete message decodes and authenticates, or null
     * otherwise (partial fragment, decrypt/auth failure, replay, or a
     * diff whose base state this side doesn't have yet — all of which
     * must be silently dropped, per SSP's threat/reliability model, not
     * surfaced as errors).
     */
    @Synchronized fun recv(wire: ByteArray): ByteArray? {
        if (wire.size < MIN_DATAGRAM) return null

        val dirSeq = readUInt64BE(wire, 0)
        if ((dirSeq and DIR_TO_CLIENT) != DIR_TO_CLIENT) return null // this app is always the client, so an incoming datagram must carry the server->client direction bit
        val seq = dirSeq and SEQ_MASK
        if (seqInMaxSet && seq <= seqInMax) return null // replay

        val nonce = ByteArray(12)
        System.arraycopy(wire, 0, nonce, 4, 8)
        val plaintext = crypto.decrypt(nonce, wire.copyOfRange(8, wire.size)) ?: return null
        if (plaintext.size < 4) return null

        val remoteTs = ((plaintext[0].toInt() and 0xFF) shl 8) or (plaintext[1].toInt() and 0xFF)
        val tsReply = ((plaintext[2].toInt() and 0xFF) shl 8) or (plaintext[3].toInt() and 0xFF)
        val payload = plaintext.copyOfRange(4, plaintext.size)

        seqInMax = seq
        seqInMaxSet = true
        lastRecvMs = System.currentTimeMillis()
        lastRemoteTs = remoteTs
        if (tsReply != 0) updateRtt(tsReply)

        if (payload.size < MOSH_FRAGMENT_HEADER_SIZE) return null // heartbeat with no fragment — fine, nothing to reassemble

        val fragment = try {
            MoshFragment.unmarshal(payload)
        } catch (e: MoshProtocolException) {
            return null
        }
        val message = assembler.add(fragment) ?: return null

        val decompressed = zlibDecompress(message) ?: return null
        val ti = try {
            MoshTransportInstruction.unmarshal(decompressed)
        } catch (e: MoshProtocolException) {
            return null
        }

        // Ack from remote: how much of what we've sent has it actually received.
        if (ti.ackNum > ackedByRemote) {
            ackedByRemote = ti.ackNum
            if (ackedByRemote >= sentNum && pendingDiff != null) {
                pendingDiff = null
                diffSent = false
                hasPendingBase = false
            }
        }

        // Dedup: already have this new_num.
        if (receivedNums.contains(ti.newNum)) return null

        // Need old_num to apply this diff against.
        if (!receivedNums.contains(ti.oldNum)) return null

        if (ti.throwawayNum > throwawayNum) {
            throwawayNum = ti.throwawayNum
            receivedNums.retainAll { it >= throwawayNum }
        }

        lastRecvOldNum = ti.oldNum
        lastRecvNewNum = ti.newNum
        receivedNums.add(ti.newNum)
        if (receivedNums.size > 128) receivedNums.removeAt(0)
        ackNum = ti.newNum

        if (ti.diff.isNotEmpty()) pendingDataAck = true
        return ti.diff
    }

    // --- internals ---------------------------------------------------

    private fun encryptFragment(fragment: MoshFragment, nowMs: Long): ByteArray {
        seqOut++
        val dirSeq = DIR_TO_SERVER or (seqOut and SEQ_MASK) // this app is always the client
        val dirSeqBytes = ByteArray(8)
        writeUInt64BE(dirSeqBytes, 0, dirSeq)

        val nonce = ByteArray(12)
        System.arraycopy(dirSeqBytes, 0, nonce, 4, 8)

        val fragWire = fragment.marshal()
        val ts = (nowMs and 0xFFFF).toInt()
        val plaintext = ByteArray(4 + fragWire.size)
        plaintext[0] = ((ts ushr 8) and 0xFF).toByte()
        plaintext[1] = (ts and 0xFF).toByte()
        plaintext[2] = ((lastRemoteTs ushr 8) and 0xFF).toByte()
        plaintext[3] = (lastRemoteTs and 0xFF).toByte()
        System.arraycopy(fragWire, 0, plaintext, 4, fragWire.size)

        val ciphertextAndTag = crypto.encrypt(nonce, plaintext)
            ?: throw MoshProtocolException("mosh: native OCB encrypt failed — see MoshBridge/systemsgo_mosh_crypto logs")

        val wire = ByteArray(8 + ciphertextAndTag.size)
        System.arraycopy(dirSeqBytes, 0, wire, 0, 8)
        System.arraycopy(ciphertextAndTag, 0, wire, 8, ciphertextAndTag.size)
        return wire
    }

    private fun updateRtt(tsReply: Int) {
        val now16 = (System.currentTimeMillis() and 0xFFFF).toInt()
        var rttMs = now16 - tsReply
        if (rttMs < 0) rttMs += 65536
        if (rttMs > 30_000) return // implausible — ignore rather than corrupt the estimate

        if (!rttInit) {
            srttMs = rttMs.toDouble()
            rttVarMs = rttMs / 2.0
            rttInit = true
        } else {
            val delta = Math.abs(srttMs - rttMs)
            rttVarMs = (3 * rttVarMs + delta) / 4
            srttMs = (7 * srttMs + rttMs) / 8
        }
        rtoMs = (srttMs + 4 * rttVarMs).toLong().coerceIn(MIN_RTO_MS, MAX_RTO_MS)
    }

    private fun zlibCompress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, /* nowrap = */ false)
        deflater.setInput(data)
        deflater.finish()
        val buf = ByteArray(maxOf(64, data.size + 64))
        val out = java.io.ByteArrayOutputStream(buf.size)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    /** Returns null (rather than throwing) on malformed zlib input — a corrupt/hostile packet, not a bug. */
    private fun zlibDecompress(data: ByteArray): ByteArray? {
        return try {
            val inflater = Inflater(/* nowrap = */ false)
            inflater.setInput(data)
            val buf = ByteArray(8192)
            val out = java.io.ByteArrayOutputStream(maxOf(8192, data.size * 3))
            var total = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput()) break
                out.write(buf, 0, n)
                total += n
                if (total > (1 shl 20)) return null // 1 MiB decompression-bomb guard
            }
            inflater.end()
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}
