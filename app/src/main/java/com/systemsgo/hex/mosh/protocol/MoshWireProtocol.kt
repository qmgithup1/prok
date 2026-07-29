package com.systemsgo.hex.mosh.protocol

import java.io.ByteArrayOutputStream

/**
 * MOSH-PROTOCOL FEATURE: hand-rolled protobuf encode/decode for the three
 * message schemas mosh's SSP (State Synchronization Protocol) uses on the
 * wire: `TransportBuffers.Instruction` (the outer envelope, carried in
 * every UDP datagram after decryption), `HostBuffers.HostMessage` (server
 * -> client: terminal-diff text + resize/echo-ack), and
 * `ClientBuffers.UserMessage` (client -> server: keystrokes + resize).
 *
 * There is deliberately no dependency on Google's protobuf-lite runtime
 * here — mosh's own `.proto` schemas are tiny (a handful of scalar/bytes
 * fields, no nested messages beyond one level, no repeated scalars, no
 * `oneof`), so a ~150-line hand-rolled varint/length-delimited codec
 * covers 100% of what's actually on the wire, the same tradeoff this
 * app already made for [com.systemsgo.hex.snmp.protocol.Asn1] (a hand-rolled
 * BER codec instead of a third-party ASN.1 library).
 *
 * FIELD NUMBERS — this is the part that was genuinely blocking (see
 * mosh/NOTES.md's "Research update" section from the previous pass): the
 * Winstein/Balakrishnan Mosh paper documents field *names and purposes*
 * but not wire tag numbers, and this sandbox couldn't fetch mosh's actual
 * `.proto` sources (GitHub's robots.txt blocks the `/tree/`/blob raw-file
 * routes web search/fetch use). This pass found and verified an
 * independent, MIT-licensed, wire-compatible reference implementation —
 * `github.com/unixshells/mosh-go` (a from-scratch Go port, explicitly
 * documented as tested against the real C `mosh-server`/`mosh-client`) —
 * whose `pb.go` states the field numbers below with the comment "Field
 * numbers match upstream mobile-shell/mosh exactly". The numbers matched
 * this app's own prior general knowledge of the mosh wire format, which is
 * the cross-check this pass could actually perform without a live mosh
 * source checkout:
 *
 * TransportBuffers.Instruction:
 *   1: protocol_version (uint32, optional — 0 = field omitted)
 *   2: old_num           (uint64)
 *   3: new_num           (uint64)
 *   4: ack_num           (uint64)
 *   5: throwaway_num     (uint64)
 *   6: diff              (bytes)
 *   7: chaff             (bytes, optional — padding; this app never sends it)
 *
 * HostBuffers.Instruction (repeated field 1 inside a HostMessage):
 *   2: HostBytes    { 4: hoststring }         -- terminal-diff ANSI text
 *   3: ResizeMessage{ 5: width, 6: height }   -- server telling client its size
 *   7: EchoAck      { 8: echo_ack_num }       -- ack of the client's last keystroke echo
 *
 * ClientBuffers.Instruction (repeated field 1 inside a UserMessage):
 *   2: Keystroke    { 4: keys }               -- raw bytes typed
 *   3: ResizeMessage{ 5: width, 6: height }   -- client telling server its size
 *
 * mosh-go additionally defines non-standard "latch" extension fields
 * (TransportInstruction field 8, Host/UserInstruction field 9) for its own
 * session-multiplexing feature. Those are NOT part of upstream mosh and
 * are deliberately NOT implemented here — sending them to a real
 * mosh-server is harmless (protobuf skips unknown fields), but there is no
 * reason to invent wire content this app doesn't use, so this codec only
 * ever emits fields 1-7 / the three real HostBuffers/ClientBuffers
 * sub-messages above.
 *
 * NOT YET CROSS-CHECKED BYTE-FOR-BYTE against a live mosh-server capture
 * (no network access in the environment that wrote this) — see
 * mosh/NOTES.md for what independent confirmation exists and what a real
 * end-to-end test would still add.
 */
internal object MoshWire {
    const val WIRE_VARINT = 0
    const val WIRE_BYTES = 2

    fun appendTag(out: ByteArrayOutputStream, field: Int, wireType: Int) {
        appendVarint(out, ((field.toLong() shl 3) or wireType.toLong()))
    }

    fun appendVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    fun appendTagVarint(out: ByteArrayOutputStream, field: Int, value: Long) {
        appendTag(out, field, WIRE_VARINT)
        appendVarint(out, value)
    }

    fun appendTagBytes(out: ByteArrayOutputStream, field: Int, data: ByteArray) {
        appendTag(out, field, WIRE_BYTES)
        appendVarint(out, data.size.toLong())
        out.write(data)
    }

    /** A tiny cursor over a byte array, used by every Unmarshal below. */
    class Reader(private val data: ByteArray, private val end: Int = data.size, start: Int = 0) {
        var pos: Int = start

        fun hasMore(): Boolean = pos < end

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < end) {
                val b = data[pos].toInt() and 0xFF
                pos++
                result = result or ((b.toLong() and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 64) throw MoshProtocolException("mosh/pb: varint too long")
            }
            throw MoshProtocolException("mosh/pb: truncated varint")
        }

        fun readTag(): Pair<Int, Int> {
            val v = readVarint()
            return Pair((v ushr 3).toInt(), (v and 7).toInt())
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > end) throw MoshProtocolException("mosh/pb: truncated length-delimited field")
            val out = data.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        /** Returns a bounded sub-reader for a nested (length-delimited) message. */
        fun readSubMessage(): Reader {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > end) throw MoshProtocolException("mosh/pb: truncated sub-message")
            val sub = Reader(data, pos + len, pos)
            pos += len
            return sub
        }

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_BYTES -> readBytes()
                5 -> { pos += 4 }
                1 -> { pos += 8 }
                else -> throw MoshProtocolException("mosh/pb: unknown wire type $wireType")
            }
        }
    }
}

/** The outer transport envelope — see field-number table in the file doc comment above. */
data class MoshTransportInstruction(
    val protocolVersion: Int = 0,
    val oldNum: Long = 0,
    val newNum: Long = 0,
    val ackNum: Long = 0,
    val throwawayNum: Long = 0,
    val diff: ByteArray = ByteArray(0),
) {
    fun marshal(): ByteArray {
        val out = ByteArrayOutputStream()
        if (protocolVersion != 0) MoshWire.appendTagVarint(out, 1, protocolVersion.toLong())
        MoshWire.appendTagVarint(out, 2, oldNum)
        MoshWire.appendTagVarint(out, 3, newNum)
        MoshWire.appendTagVarint(out, 4, ackNum)
        MoshWire.appendTagVarint(out, 5, throwawayNum)
        if (diff.isNotEmpty()) MoshWire.appendTagBytes(out, 6, diff)
        return out.toByteArray()
    }

    companion object {
        fun unmarshal(data: ByteArray): MoshTransportInstruction {
            val r = MoshWire.Reader(data)
            var protocolVersion = 0
            var oldNum = 0L
            var newNum = 0L
            var ackNum = 0L
            var throwawayNum = 0L
            var diff = ByteArray(0)
            while (r.hasMore()) {
                val (field, wireType) = r.readTag()
                when (field) {
                    1 -> protocolVersion = r.readVarint().toInt()
                    2 -> oldNum = r.readVarint()
                    3 -> newNum = r.readVarint()
                    4 -> ackNum = r.readVarint()
                    5 -> throwawayNum = r.readVarint()
                    6 -> diff = r.readBytes()
                    7 -> r.readBytes() // chaff — accepted from a real server, never emitted
                    else -> r.skip(wireType)
                }
            }
            return MoshTransportInstruction(protocolVersion, oldNum, newNum, ackNum, throwawayNum, diff)
        }
    }
}

/** One instruction inside a HostMessage — server-to-client. */
data class MoshHostInstruction(
    val hoststring: ByteArray? = null,
    val width: Int? = null,
    val height: Int? = null,
    val echoAckNum: Long? = null,
) {
    fun marshal(): ByteArray {
        val out = ByteArrayOutputStream()
        if (hoststring != null && hoststring.isNotEmpty()) {
            val sub = ByteArrayOutputStream()
            MoshWire.appendTagBytes(sub, 4, hoststring)
            MoshWire.appendTagBytes(out, 2, sub.toByteArray())
        }
        if (width != null || height != null) {
            val sub = ByteArrayOutputStream()
            MoshWire.appendTagVarint(sub, 5, (width ?: 0).toLong())
            MoshWire.appendTagVarint(sub, 6, (height ?: 0).toLong())
            MoshWire.appendTagBytes(out, 3, sub.toByteArray())
        }
        if (echoAckNum != null) {
            val sub = ByteArrayOutputStream()
            MoshWire.appendTagVarint(sub, 8, echoAckNum)
            MoshWire.appendTagBytes(out, 7, sub.toByteArray())
        }
        return out.toByteArray()
    }

    companion object {
        fun unmarshal(data: ByteArray): MoshHostInstruction {
            val r = MoshWire.Reader(data)
            var hoststring: ByteArray? = null
            var width: Int? = null
            var height: Int? = null
            var echoAckNum: Long? = null
            while (r.hasMore()) {
                val (field, wireType) = r.readTag()
                when (field) {
                    2 -> { // HostBytes
                        val sub = r.readSubMessage()
                        while (sub.hasMore()) {
                            val (f2, w2) = sub.readTag()
                            if (f2 == 4) hoststring = sub.readBytes() else sub.skip(w2)
                        }
                    }
                    3 -> { // ResizeMessage
                        val sub = r.readSubMessage()
                        while (sub.hasMore()) {
                            val (f2, w2) = sub.readTag()
                            when (f2) {
                                5 -> width = sub.readVarint().toInt()
                                6 -> height = sub.readVarint().toInt()
                                else -> sub.skip(w2)
                            }
                        }
                    }
                    7 -> { // EchoAck
                        val sub = r.readSubMessage()
                        while (sub.hasMore()) {
                            val (f2, w2) = sub.readTag()
                            if (f2 == 8) echoAckNum = sub.readVarint() else sub.skip(w2)
                        }
                    }
                    else -> r.skip(wireType)
                }
            }
            return MoshHostInstruction(hoststring, width, height, echoAckNum)
        }
    }
}

/** One instruction inside a UserMessage — client-to-server. */
data class MoshUserInstruction(
    val keys: ByteArray? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    fun marshal(): ByteArray {
        val out = ByteArrayOutputStream()
        if (keys != null && keys.isNotEmpty()) {
            val sub = ByteArrayOutputStream()
            MoshWire.appendTagBytes(sub, 4, keys)
            MoshWire.appendTagBytes(out, 2, sub.toByteArray())
        }
        if (width != null || height != null) {
            val sub = ByteArrayOutputStream()
            MoshWire.appendTagVarint(sub, 5, (width ?: 0).toLong())
            MoshWire.appendTagVarint(sub, 6, (height ?: 0).toLong())
            MoshWire.appendTagBytes(out, 3, sub.toByteArray())
        }
        return out.toByteArray()
    }

    companion object {
        fun unmarshal(data: ByteArray): MoshUserInstruction {
            val r = MoshWire.Reader(data)
            var keys: ByteArray? = null
            var width: Int? = null
            var height: Int? = null
            while (r.hasMore()) {
                val (field, wireType) = r.readTag()
                when (field) {
                    2 -> {
                        val sub = r.readSubMessage()
                        while (sub.hasMore()) {
                            val (f2, w2) = sub.readTag()
                            if (f2 == 4) keys = sub.readBytes() else sub.skip(w2)
                        }
                    }
                    3 -> {
                        val sub = r.readSubMessage()
                        while (sub.hasMore()) {
                            val (f2, w2) = sub.readTag()
                            when (f2) {
                                5 -> width = sub.readVarint().toInt()
                                6 -> height = sub.readVarint().toInt()
                                else -> sub.skip(w2)
                            }
                        }
                    }
                    else -> r.skip(wireType)
                }
            }
            return MoshUserInstruction(keys, width, height)
        }
    }
}

/** HostBuffers.HostMessage — a repeated field-1 list of [MoshHostInstruction]. */
object MoshHostMessage {
    fun marshal(instructions: List<MoshHostInstruction>): ByteArray {
        val out = ByteArrayOutputStream()
        for (instr in instructions) MoshWire.appendTagBytes(out, 1, instr.marshal())
        return out.toByteArray()
    }

    fun unmarshal(data: ByteArray): List<MoshHostInstruction> {
        val r = MoshWire.Reader(data)
        val result = mutableListOf<MoshHostInstruction>()
        while (r.hasMore()) {
            val (field, wireType) = r.readTag()
            if (field == 1) {
                result.add(MoshHostInstruction.unmarshal(r.readBytes()))
            } else {
                r.skip(wireType)
            }
        }
        return result
    }
}

/** ClientBuffers.UserMessage — a repeated field-1 list of [MoshUserInstruction]. */
object MoshUserMessage {
    fun marshal(instructions: List<MoshUserInstruction>): ByteArray {
        val out = ByteArrayOutputStream()
        for (instr in instructions) MoshWire.appendTagBytes(out, 1, instr.marshal())
        return out.toByteArray()
    }

    fun unmarshal(data: ByteArray): List<MoshUserInstruction> {
        val r = MoshWire.Reader(data)
        val result = mutableListOf<MoshUserInstruction>()
        while (r.hasMore()) {
            val (field, wireType) = r.readTag()
            if (field == 1) {
                result.add(MoshUserInstruction.unmarshal(r.readBytes()))
            } else {
                r.skip(wireType)
            }
        }
        return result
    }
}
