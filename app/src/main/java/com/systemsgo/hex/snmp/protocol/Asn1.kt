package com.systemsgo.hex.snmp.protocol

import java.io.ByteArrayOutputStream

/**
 * ASN.1 BER (Basic Encoding Rules, X.690) codec for SNMP — the wire format
 * every SNMP message (v1/v2c/v3, GET/SET/trap alike) is built from. No
 * external ASN.1/SNMP library is used anywhere in [com.systemsgo.hex.snmp];
 * this file, [SnmpUsm] and [SnmpClient] are the whole stack, matching the
 * rest of the app's protocol clients (IPMI, AMT, Redfish), which are all
 * native implementations rather than third-party libraries.
 *
 * Covers definite-length, primitive and constructed encodings only — SNMP
 * never uses indefinite-length BER, so that form isn't implemented.
 */
object Ber {
    // Universal tags (X.690 §8)
    const val INTEGER = 0x02
    const val BIT_STRING = 0x03
    const val OCTET_STRING = 0x04
    const val NULL = 0x05
    const val OBJECT_IDENTIFIER = 0x06
    const val SEQUENCE = 0x30

    // SNMP application-wide types (RFC 1155 §3.2.3, RFC 2578 §7.1)
    const val IP_ADDRESS = 0x40
    const val COUNTER32 = 0x41
    const val GAUGE32 = 0x42 // == Unsigned32
    const val TIME_TICKS = 0x43
    const val OPAQUE = 0x44
    const val COUNTER64 = 0x46 // RFC 2578 §7.1.10 (SNMPv2/v3 only)

    // varbind exception values (RFC 3416 §2.2 — GetNext/GetBulk/response only)
    const val NO_SUCH_OBJECT = 0x80
    const val NO_SUCH_INSTANCE = 0x81
    const val END_OF_MIB_VIEW = 0x82

    // PDU types (context-specific, constructed — RFC 3416 §3, RFC 1157 §4.1)
    const val GET_REQUEST = 0xA0
    const val GET_NEXT_REQUEST = 0xA1
    const val GET_RESPONSE = 0xA2 // "Response-PDU" in v2c/v3 terminology
    const val SET_REQUEST = 0xA3
    const val TRAP_V1 = 0xA4 // Trap-PDU, v1 only — distinct format, see [SnmpTrapListener]
    const val GET_BULK_REQUEST = 0xA5 // v2c/v3 only
    const val INFORM_REQUEST = 0xA6 // v2c/v3 only
    const val SNMPV2_TRAP = 0xA7 // v2c/v3 only
    const val REPORT = 0xA8 // v3 only — used for usmStats*/engine-discovery reports
}

/**
 * An SNMP OBJECT IDENTIFIER, e.g. `1.3.6.1.2.1.1.1.0` (sysDescr.0). Arcs are
 * stored as-is (no compression); [Oid.arcs] must have at least 2 elements
 * per X.690 §8.19 (the first BER byte folds arcs 0 and 1 together as
 * `40*arc0 + arc1`), which is always true for real SNMP OIDs (they're all
 * under 1.3 / iso.org).
 */
data class Oid(val arcs: IntArray) : Comparable<Oid> {
    constructor(dotted: String) : this(
        dotted.trim().trimStart('.').split(".").filter { it.isNotEmpty() }.map {
            it.toIntOrNull() ?: throw SnmpException("Invalid OID component '$it' in \"$dotted\"")
        }.toIntArray()
    )

    init {
        require(arcs.isNotEmpty()) { "OID must have at least 1 arc" }
        require(arcs.all { it >= 0 }) { "OID arcs must be non-negative: $this" }
    }

    override fun toString(): String = arcs.joinToString(".")

    /** True if this OID is [prefix] itself or lies anywhere under it in the tree. */
    fun startsWith(prefix: Oid): Boolean {
        if (arcs.size < prefix.arcs.size) return false
        for (i in prefix.arcs.indices) if (arcs[i] != prefix.arcs[i]) return false
        return true
    }

    /** The arcs beyond [prefix] — e.g. the table-index suffix a MIB-browser row groups by. Empty if this doesn't start with [prefix]. */
    fun suffixAfter(prefix: Oid): IntArray =
        if (startsWith(prefix)) arcs.copyOfRange(prefix.arcs.size, arcs.size) else IntArray(0)

    operator fun plus(child: Int): Oid = Oid(arcs + child)
    operator fun plus(child: IntArray): Oid = Oid(arcs + child)
    operator fun plus(child: Oid): Oid = Oid(arcs + child.arcs)

    override fun compareTo(other: Oid): Int {
        val n = minOf(arcs.size, other.arcs.size)
        for (i in 0 until n) {
            val c = arcs[i].compareTo(other.arcs[i])
            if (c != 0) return c
        }
        return arcs.size.compareTo(other.arcs.size)
    }

    override fun equals(other: Any?) = other is Oid && arcs.contentEquals(other.arcs)
    override fun hashCode() = arcs.contentHashCode()

    companion object {
        /** `0.0` — the conventional placeholder used for "no OID" in a few v3 fields (context-engine defaults, etc). */
        val ZERO_ZERO = Oid(intArrayOf(0, 0))
    }
}

/** Thrown for any malformed packet, unsupported encoding, or protocol-level failure (timeouts, auth failures, etc — see subclasses in SnmpModels.kt). */
open class SnmpException(message: String, cause: Throwable? = null) : Exception(message, cause)

// ─────────────────────────── low-level BER primitives ───────────────────────────

/** Minimal two's-complement encoding of a signed integer (X.690 §8.3) — used for INTEGER (request-id, error-status/index, and any plain signed SMI INTEGER). */
fun berEncodeSignedInt(value: Long): ByteArray {
    val full = ByteArray(8)
    var v = value
    for (i in 7 downTo 0) { full[i] = (v and 0xFF).toByte(); v = v shr 8 }
    var start = 0
    while (start < 7) {
        val b = full[start].toInt()
        val next = full[start + 1].toInt()
        if (b == 0x00 && (next and 0x80) == 0) start++
        else if (b == -1 && (next and 0x80) != 0) start++
        else break
    }
    return full.copyOfRange(start, 8)
}

/** Decodes a BER-minimal two's-complement signed integer back to a [Long]; sign-extends from whatever width was encoded. */
fun berDecodeSignedInt(bytes: ByteArray): Long {
    if (bytes.isEmpty()) return 0
    var v = 0L
    for (b in bytes) v = (v shl 8) or (b.toLong() and 0xFF)
    if (bytes.size < 8) {
        val shift = 64 - bytes.size * 8
        v = (v shl shift) shr shift // sign-extend
    }
    return v
}

/**
 * Big-endian unsigned encoding, padded with a leading 0x00 if the top bit
 * would otherwise be set (X.690 unsigned-integer convention) — used for
 * Counter32/Gauge32/Unsigned32/TimeTicks (4 bytes) and Counter64 (8 bytes).
 * [valueBits] is the value's raw bit pattern; for Counter64 values >=
 * 2^63 that means passing a "negative" Kotlin Long is correct — only the
 * bits matter, not the signed interpretation (see [Long.toUnsignedDecimalString]
 * for display of such values).
 */
fun berEncodeUnsigned(valueBits: Long, byteLen: Int): ByteArray {
    val full = ByteArray(byteLen)
    var v = valueBits
    for (i in byteLen - 1 downTo 0) { full[i] = (v and 0xFF).toByte(); v = v shr 8 }
    var start = 0
    while (start < byteLen - 1 && full[start] == 0.toByte()) start++
    val trimmed = full.copyOfRange(start, byteLen)
    return if ((trimmed[0].toInt() and 0x80) != 0) byteArrayOf(0) + trimmed else trimmed
}

/** Decodes a big-endian (possibly zero-padded) unsigned integer into the bit pattern of a [Long]. Use [Long.toUnsignedDecimalString] to display Counter64-range values correctly. */
fun berDecodeUnsigned(bytes: ByteArray): Long {
    var v = 0L
    for (b in bytes) v = (v shl 8) or (b.toLong() and 0xFF)
    return v
}

/** Renders this [Long]'s bit pattern as an unsigned decimal string — needed for Counter64 values >= 2^63, which Kotlin would otherwise print as negative. */
fun Long.toUnsignedDecimalString(): String = java.lang.Long.toUnsignedString(this)

fun berEncodeOid(oid: Oid): ByteArray {
    val out = ByteArrayOutputStream()
    fun writeBase128(value: Long) {
        val groups = ArrayList<Int>()
        groups.add((value and 0x7F).toInt())
        var v = value ushr 7
        while (v > 0) { groups.add(0, ((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        groups.forEach { out.write(it) }
    }
    val arcs = oid.arcs
    val secondArc = if (arcs.size > 1) arcs[1] else 0
    writeBase128((arcs[0].toLong() * 40) + secondArc)
    for (i in 2 until arcs.size) writeBase128(arcs[i].toLong() and 0xFFFFFFFFL)
    return out.toByteArray()
}

fun berDecodeOid(bytes: ByteArray): Oid {
    if (bytes.isEmpty()) return Oid.ZERO_ZERO
    val arcs = ArrayList<Int>()
    var value = 0L
    for (b in bytes) {
        val u = b.toInt() and 0xFF
        value = (value shl 7) or (u and 0x7F).toLong()
        if (u and 0x80 == 0) {
            arcs.add(value.toInt())
            value = 0
        }
    }
    val first = arcs.removeAt(0)
    val (a0, a1) = if (first < 40) 0 to first else if (first < 80) 1 to (first - 40) else 2 to (first - 80)
    val result = ArrayList<Int>()
    result.add(a0); result.add(a1)
    result.addAll(arcs)
    return Oid(result.toIntArray())
}

/** Builds one BER TLV (tag-length-value); [content] must already be the fully-encoded value bytes. */
class BerWriter {
    private val buf = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = buf.toByteArray()

    fun writeTlv(tag: Int, content: ByteArray) {
        buf.write(tag)
        writeLength(content.size)
        buf.write(content)
    }

    /** Writes a constructed TLV (SEQUENCE, a PDU, etc) whose content is itself built by nested writes inside [block]. */
    inline fun writeConstructed(tag: Int, block: BerWriter.() -> Unit) {
        val inner = BerWriter()
        inner.block()
        writeTlv(tag, inner.toByteArray())
    }

    fun writeInteger(value: Long) = writeTlv(Ber.INTEGER, berEncodeSignedInt(value))
    fun writeOctetString(bytes: ByteArray) = writeTlv(Ber.OCTET_STRING, bytes)
    fun writeOctetString(s: String) = writeOctetString(s.toByteArray(Charsets.UTF_8))
    fun writeNull() = writeTlv(Ber.NULL, ByteArray(0))
    fun writeOid(oid: Oid) = writeTlv(Ber.OBJECT_IDENTIFIER, berEncodeOid(oid))

    private fun writeLength(len: Int) {
        if (len < 0x80) {
            buf.write(len)
        } else {
            val bytes = ArrayList<Int>()
            var l = len
            while (l > 0) { bytes.add(0, l and 0xFF); l = l ushr 8 }
            buf.write(0x80 or bytes.size)
            bytes.forEach { buf.write(it) }
        }
    }
}

/** One decoded TLV node — [tag] plus its raw content bytes; constructed types (SEQUENCE, PDUs) are decoded further by [BerReader.readTlv] calls against a nested reader over [content]. */
data class BerNode(val tag: Int, val content: ByteArray) {
    fun reader(): BerReader = BerReader(content)
    val isConstructed: Boolean get() = (tag and 0x20) != 0
}

/** Sequentially reads TLVs out of a byte range — SNMP messages parse as a flat sequence of nested [BerReader]s, one per constructed level. */
class BerReader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
    val hasRemaining: Boolean get() = pos < end

    fun readTlv(): BerNode {
        if (pos >= end) throw SnmpException("Unexpected end of BER data at offset $pos")
        val tag = data[pos].toInt() and 0xFF
        pos++
        val len = readLength()
        if (pos + len > end) throw SnmpException("BER length $len at offset $pos exceeds available data")
        val content = data.copyOfRange(pos, pos + len)
        pos += len
        return BerNode(tag, content)
    }

    private fun readLength(): Int {
        if (pos >= end) throw SnmpException("Unexpected end of BER data reading length")
        val first = data[pos].toInt() and 0xFF
        pos++
        if (first < 0x80) return first
        if (first == 0x80) throw SnmpException("Indefinite-length BER is not valid in SNMP")
        val numBytes = first and 0x7F
        if (numBytes > 4) throw SnmpException("BER length field too large ($numBytes bytes)")
        var len = 0
        repeat(numBytes) { len = (len shl 8) or (data[pos].toInt() and 0xFF); pos++ }
        return len
    }
}
