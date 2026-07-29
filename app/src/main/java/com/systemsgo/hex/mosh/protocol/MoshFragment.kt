package com.systemsgo.hex.mosh.protocol

/**
 * MOSH-PROTOCOL FEATURE: datagram fragmentation for a single (zlib-
 * compressed) [MoshTransportInstruction] payload.
 *
 * Wire format (mosh's `src/network/network.cc`, confirmed against the
 * independent `unixshells/mosh-go` reference implementation's
 * `fragment.go` — see [MoshWire]'s file doc comment for how that source
 * was found/verified):
 *
 *   [instruction_id : 8 bytes, big-endian]   -- the TransportInstruction's new_num
 *   [fragment_num(15 bits) | final_flag(1 bit) : 2 bytes, big-endian]
 *   [payload : remaining bytes]
 *
 * `fragment_num` occupies the upper 15 bits of that 16-bit field; the
 * lowest bit is the "this is the last fragment of this instruction" flag.
 * Each fragment carries at most [MAX_FRAGMENT_PAYLOAD] bytes (1300, same
 * as upstream mosh — comfortably under a typical 1500-byte Ethernet MTU
 * once UDP/IP headers and this app's own 8-byte direction+sequence prefix
 * and 16-byte OCB tag are accounted for).
 */
internal const val MOSH_FRAGMENT_HEADER_SIZE = 10 // 8 (id) + 2 (fragment_num|final)
internal const val MOSH_FRAGMENT_FINAL_BIT = 0x8000
internal const val MOSH_MAX_FRAGMENT_PAYLOAD = 1300
private const val MOSH_MAX_REASSEMBLED_SIZE = 1 shl 20 // 1 MiB — guards against a malicious/corrupt peer

data class MoshFragment(
    val id: Long,
    val fragmentNum: Int, // 0-based
    val isFinal: Boolean,
    val payload: ByteArray,
) {
    fun marshal(): ByteArray {
        val out = ByteArray(MOSH_FRAGMENT_HEADER_SIZE + payload.size)
        writeUInt64BE(out, 0, id)
        var numAndFinal = fragmentNum and 0x7FFF
        if (isFinal) numAndFinal = numAndFinal or MOSH_FRAGMENT_FINAL_BIT
        out[8] = ((numAndFinal ushr 8) and 0xFF).toByte()
        out[9] = (numAndFinal and 0xFF).toByte()
        System.arraycopy(payload, 0, out, MOSH_FRAGMENT_HEADER_SIZE, payload.size)
        return out
    }

    companion object {
        fun unmarshal(data: ByteArray): MoshFragment {
            if (data.size < MOSH_FRAGMENT_HEADER_SIZE) {
                throw MoshProtocolException("mosh: fragment shorter than header ($MOSH_FRAGMENT_HEADER_SIZE bytes)")
            }
            val id = readUInt64BE(data, 0)
            val numAndFinal = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
            val isFinal = (numAndFinal and MOSH_FRAGMENT_FINAL_BIT) != 0
            val fragmentNum = numAndFinal and 0x7FFF
            val payload = data.copyOfRange(MOSH_FRAGMENT_HEADER_SIZE, data.size)
            return MoshFragment(id, fragmentNum, isFinal, payload)
        }
    }
}

/** Splits [data] (already zlib-compressed) into wire-ready fragments for instruction [id]. */
internal fun moshFragmentize(id: Long, data: ByteArray): List<MoshFragment> {
    if (data.isEmpty()) return listOf(MoshFragment(id, 0, true, ByteArray(0)))
    val count = (data.size + MOSH_MAX_FRAGMENT_PAYLOAD - 1) / MOSH_MAX_FRAGMENT_PAYLOAD
    return (0 until count).map { i ->
        val start = i * MOSH_MAX_FRAGMENT_PAYLOAD
        val end = minOf(start + MOSH_MAX_FRAGMENT_PAYLOAD, data.size)
        MoshFragment(id, i, i == count - 1, data.copyOfRange(start, end))
    }
}

/**
 * Reassembles fragments belonging to one instruction ID at a time.
 * Not thread-safe — callers (see [MoshTransport]) must serialize access,
 * same as every other piece of per-connection mutable state there.
 */
internal class MoshFragmentAssembler {
    private var currentId: Long = -1
    private var fragments: MutableList<ByteArray?>? = null
    private var totalNum: Int = -1
    private var totalSize: Int = 0

    /** Feeds one fragment; returns the reassembled message once complete, else null. */
    fun add(fragment: MoshFragment): ByteArray? {
        if (currentId >= 0 && fragment.id < currentId) return null // stale, from an older instruction

        if (fragment.id != currentId) {
            // New instruction id — reset all reassembly state.
            currentId = fragment.id
            fragments = mutableListOf()
            totalNum = -1
            totalSize = 0
        }

        totalSize += fragment.payload.size
        if (totalSize > MOSH_MAX_REASSEMBLED_SIZE) {
            fragments = mutableListOf()
            totalNum = -1
            totalSize = 0
            return null
        }

        val frags = fragments ?: mutableListOf<ByteArray?>().also { fragments = it }
        while (frags.size <= fragment.fragmentNum) frags.add(null)
        frags[fragment.fragmentNum] = fragment.payload
        if (fragment.isFinal) totalNum = fragment.fragmentNum + 1

        val expected = totalNum
        if (expected < 0 || frags.size < expected) return null
        for (i in 0 until expected) if (frags[i] == null) return null

        var total = 0
        for (i in 0 until expected) total += frags[i]!!.size
        val message = ByteArray(total)
        var offset = 0
        for (i in 0 until expected) {
            val part = frags[i]!!
            System.arraycopy(part, 0, message, offset, part.size)
            offset += part.size
        }

        fragments = mutableListOf()
        totalNum = -1
        totalSize = 0
        return message
    }
}

internal fun writeUInt64BE(out: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) {
        out[offset + i] = ((value ushr (56 - 8 * i)) and 0xFF).toByte()
    }
}

internal fun readUInt64BE(data: ByteArray, offset: Int): Long {
    var result = 0L
    for (i in 0 until 8) {
        result = (result shl 8) or (data[offset + i].toLong() and 0xFF)
    }
    return result
}
