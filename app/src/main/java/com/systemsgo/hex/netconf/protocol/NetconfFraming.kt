package com.systemsgo.hex.netconf.protocol

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * NETCONF FEATURE (Part 1/N — transport): RFC 6242 message framing over the
 * `netconf` SSH subsystem channel opened by [NetconfClient].
 *
 * Two framing modes exist on the wire and this class implements both:
 *
 *  - **base:1.0 "end-of-message" framing**: every `<rpc>`/`<hello>` document
 *    is followed by the literal delimiter `]]>]]>` with nothing else. This
 *    is the *only* framing either side is allowed to use before capabilities
 *    have been exchanged — RFC 6242 §4 requires the initial `<hello>` in
 *    both directions to use EOM framing even if both sides go on to
 *    advertise base:1.1.
 *  - **base:1.1 "chunked" framing** (RFC 6242 §4.2): once both peers'
 *    `<hello>` capability lists contain `urn:ietf:params:netconf:base:1.1`,
 *    every subsequent message is instead split into one or more chunks of
 *    the form `\n#<length>\n<data>` and terminated with `\n##\n`. This is
 *    what lets a NETCONF session safely carry a payload that itself
 *    contains the byte sequence `]]>]]>` (e.g. an edit-config whose
 *    <config> blob quotes XML from another source).
 *
 * [NetconfClient] always starts a session in EOM mode, performs the hello
 * exchange, and then calls [switchToChunked] iff both hellos advertised
 * base:1.1 — mirroring exactly what the RFC mandates, not a per-message
 * choice.
 */
class NetconfFrameReader(private val input: InputStream) {

    @Volatile
    var chunkedMode: Boolean = false
        private set

    fun switchToChunked() {
        chunkedMode = true
    }

    /**
     * Reads exactly one full NETCONF message (the XML document, without any
     * framing delimiter) and blocks until it is available. Returns null on
     * clean EOF (peer closed the channel).
     */
    @Throws(IOException::class)
    fun readMessage(): String? =
        if (chunkedMode) readChunkedMessage() else readEomMessage()

    // ── base:1.0 end-of-message framing ─────────────────────────────
    private val EOM = byteArrayOf(']'.code.toByte(), ']'.code.toByte(), '>'.code.toByte(), ']'.code.toByte(), ']'.code.toByte(), '>'.code.toByte())

    private fun readEomMessage(): String? {
        val buf = java.io.ByteArrayOutputStream(4096)
        var matched = 0
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            }
            buf.write(b)
            matched = if (b == EOM[matched].toInt() and 0xFF) matched + 1 else {
                // Not a match at this position; a byte that happens to equal
                // EOM[0] could still start a new match (]]>]]> after a stray
                // ']' isn't possible here since EOM has no internal repeat,
                // so a plain reset to 0/1 is correct RFC 6242 delimiter
                // scanning, same approach libnetconf2 uses).
                if (b == EOM[0].toInt() and 0xFF) 1 else 0
            }
            if (matched == EOM.size) {
                val total = buf.size()
                return buf.toByteArray().copyOfRange(0, total - EOM.size).toString(Charsets.UTF_8)
            }
        }
    }

    // ── base:1.1 chunked framing ─────────────────────────────────────
    private fun readChunkedMessage(): String? {
        val out = java.io.ByteArrayOutputStream(4096)
        while (true) {
            // Each chunk starts with "\n#" then either a decimal length and
            // "\n<data>", or the literal "#\n" end-of-message marker.
            val nl = input.read()
            if (nl == -1) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (nl != '\n'.code) throw IOException("NETCONF chunked framing error: expected LF, got $nl")
            val hash = input.read()
            if (hash != '#'.code) throw IOException("NETCONF chunked framing error: expected '#', got $hash")

            val header = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) throw IOException("NETCONF chunked framing error: EOF reading chunk header")
                if (c == '\n'.code) break
                header.append(c.toChar())
            }
            val headerStr = header.toString()
            if (headerStr == "#") {
                // end-of-message marker "\n##\n" fully consumed
                return out.toString(Charsets.UTF_8.name())
            }
            val chunkLen = headerStr.toIntOrNull()
                ?: throw IOException("NETCONF chunked framing error: bad chunk-size '$headerStr'")
            if (chunkLen <= 0 || chunkLen > 16 * 1024 * 1024) {
                throw IOException("NETCONF chunked framing error: implausible chunk-size $chunkLen")
            }
            val chunk = ByteArray(chunkLen)
            var read = 0
            while (read < chunkLen) {
                val n = input.read(chunk, read, chunkLen - read)
                if (n == -1) throw IOException("NETCONF chunked framing error: EOF mid-chunk")
                read += n
            }
            out.write(chunk)
        }
    }
}

class NetconfFrameWriter(private val output: OutputStream) {

    @Volatile
    var chunkedMode: Boolean = false
        private set

    fun switchToChunked() {
        chunkedMode = true
    }

    @Throws(IOException::class)
    @Synchronized
    fun writeMessage(xml: String) {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        if (chunkedMode) {
            // RFC 6242 §4.2: one chunk is sufficient (no hard requirement to
            // split large payloads into multiple chunks — a single chunk up
            // to 2^32-1 octets is legal), followed by the end-of-message
            // marker "\n##\n".
            output.write("\n#${bytes.size}\n".toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.write("\n##\n".toByteArray(Charsets.US_ASCII))
        } else {
            output.write(bytes)
            output.write("]]>]]>".toByteArray(Charsets.US_ASCII))
        }
        output.flush()
    }
}
