package com.systemsgo.hex.nfs

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

// ─────────────────────────────────────────────────────────────────────────────
// NFS-FEATURE: minimal hand-rolled XDR (External Data Representation, RFC 4506)
// codec. Same philosophy as RfbConnectable.kt's hand-rolled RFB/VeNCrypt
// framing — no external library, just the primitives ONC RPC / MOUNT / NFSv3
// actually need:
//   - unsigned/signed 32-bit int, unsigned 64-bit int (hyper)
//   - fixed-length and variable-length opaque (byte arrays), 4-byte aligned
//   - strings (length-prefixed opaque, ASCII/UTF-8)
//   - variable-length arrays via count-prefix + per-element encode/decode
//   - optional ("union bool") values
// Deliberately NOT implemented (unused by MOUNT/NFSv3 as consumed here):
// XDR floating point, enums-as-a-distinct-type (we just use Int), and
// discriminated unions beyond the simple optional-value case.
// ─────────────────────────────────────────────────────────────────────────────

/** Grows a byte buffer while writing an XDR-encoded RPC call/argument list. */
class XdrEncoder(initialCapacity: Int = 256) {
    private val buf = ByteArrayOutputStream(initialCapacity)

    fun toByteArray(): ByteArray = buf.toByteArray()
    val size: Int get() = buf.size()

    fun putUInt(v: Long) {
        buf.write(((v ushr 24) and 0xFF).toInt())
        buf.write(((v ushr 16) and 0xFF).toInt())
        buf.write(((v ushr 8) and 0xFF).toInt())
        buf.write((v and 0xFF).toInt())
    }

    /** Convenience for callers passing a non-negative Kotlin Int. */
    fun putInt(v: Int) = putUInt(v.toLong() and 0xFFFFFFFFL)

    fun putUHyper(v: Long) {
        putUInt((v ushr 32) and 0xFFFFFFFFL)
        putUInt(v and 0xFFFFFFFFL)
    }

    fun putBool(v: Boolean) = putUInt(if (v) 1L else 0L)

    /** Fixed-length opaque: raw bytes padded to a 4-byte boundary, no length prefix. */
    fun putFixedOpaque(data: ByteArray) {
        buf.write(data)
        padTo4(data.size)
    }

    /** Variable-length opaque: uint length prefix + bytes + padding. */
    fun putVarOpaque(data: ByteArray) {
        putUInt(data.size.toLong())
        putFixedOpaque(data)
    }

    fun putString(s: String) = putVarOpaque(s.toByteArray(Charsets.UTF_8))

    private fun padTo4(len: Int) {
        val pad = (4 - (len % 4)) % 4
        repeat(pad) { buf.write(0) }
    }
}

/** Thrown when a peer sends fewer bytes than the XDR stream requires. */
class XdrEofException(message: String) : IOException(message)

/**
 * Reads an XDR stream out of an in-memory byte array (a full RPC reply body,
 * already de-framed from its record-marking header by [OncRpcClient]).
 */
class XdrDecoder(private val data: ByteArray) {
    private var pos = 0

    val remaining: Int get() = data.size - pos

    private fun need(n: Int) {
        if (pos + n > data.size) throw XdrEofException("XDR underrun: need $n bytes, have ${data.size - pos}")
    }

    fun getUInt(): Long {
        need(4)
        val v = ((data[pos].toLong() and 0xFF) shl 24) or
                ((data[pos + 1].toLong() and 0xFF) shl 16) or
                ((data[pos + 2].toLong() and 0xFF) shl 8) or
                (data[pos + 3].toLong() and 0xFF)
        pos += 4
        return v
    }

    fun getInt(): Int = getUInt().toInt()

    fun getUHyper(): Long {
        val hi = getUInt()
        val lo = getUInt()
        return (hi shl 32) or lo
    }

    fun getBool(): Boolean = getUInt() != 0L

    fun getFixedOpaque(len: Int): ByteArray {
        need(len)
        val out = data.copyOfRange(pos, pos + len)
        pos += len
        skipPad(len)
        return out
    }

    fun getVarOpaque(maxLen: Int = 16 * 1024 * 1024): ByteArray {
        val len = getUInt()
        if (len < 0 || len > maxLen) throw IOException("XDR opaque length out of range: $len")
        return getFixedOpaque(len.toInt())
    }

    fun getString(maxLen: Int = 65536): String = String(getVarOpaque(maxLen), Charsets.UTF_8)

    private fun skipPad(len: Int) {
        val pad = (4 - (len % 4)) % 4
        need(pad)
        pos += pad
    }

    /** Skips [n] raw, unpadded bytes (used for opaque bodies we don't need to parse). */
    fun skip(n: Int) {
        need(n)
        pos += n
    }
}

/**
 * Reads exactly [len] bytes from [input], blocking until satisfied.
 * java.io.InputStream.read() is permitted to return short reads even over a
 * blocking TCP socket, so a plain single read() is not sufficient here.
 */
internal fun readFully(input: InputStream, len: Int): ByteArray {
    val out = ByteArray(len)
    var off = 0
    while (off < len) {
        val n = input.read(out, off, len - off)
        if (n < 0) throw EOFException("Connection closed after $off/$len bytes")
        off += n
    }
    return out
}
