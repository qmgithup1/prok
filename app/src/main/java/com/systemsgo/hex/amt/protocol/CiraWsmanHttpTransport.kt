package com.systemsgo.hex.amt.protocol

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * AMT-VPRO FEATURE phase 6 (CIRA), WS-Man-over-CIRA follow-up (the item
 * AMT_VPRO_ROADMAP.md's "Not yet started" section flagged as deliberately
 * out of scope for the pass that built [CiraRelayTransport]): a minimal
 * HTTP/1.1 client that speaks WS-Man's request/response shape directly over
 * an [AmtRedirectionTransport]'s plain byte pipe (a CIRA relay channel to
 * the WS-Man port, 16992, in practice), instead of a real [java.net.Socket]
 * OkHttp can dial itself.
 *
 * ## Why not just hand OkHttp the transport's streams?
 * OkHttp's connection layer is built around actually opening a
 * [java.net.Socket] (or [javax.net.ssl.SSLSocket]) itself — there's no
 * public seam to hand it an already-open pair of streams from something
 * that isn't a real socket (a WebSocket-backed byte pipe here). Rather than
 * fight that, this class implements the small, fixed subset of HTTP/1.1
 * [AmtClient] actually needs — a POST with a Digest `Authorization` header,
 * a status line, headers, and a `Content-Length`- or `Transfer-Encoding:
 * chunked`-delimited body — the same "hand-roll the small fixed subset
 * instead of pulling in a general library" reasoning [AmtClient]'s own top
 * doc comment already gives for hand-rolled SOAP over a generic WS-Man/CIM
 * client.
 *
 * ## Connection reuse
 * A CIRA relay channel is comparatively expensive to open (a WebSocket
 * handshake plus the relay's own `channel-open`/`channel-open-ack` round
 * trip against the device's APF connection — see [CiraRelayTransport]'s doc
 * comment), so unlike a fresh request-scoped socket this keeps one
 * [AmtRedirectionTransport] open across every WS-Man call an [AmtClient]
 * instance makes, matching how a real browser or `wsmancli` would reuse one
 * TCP connection with HTTP keep-alive against AMT's embedded web server.
 * [openTransport] is a *factory*, not a pre-opened transport, because that
 * connection isn't guaranteed to survive AMT's own behaviour: its embedded
 * server commonly sends `Connection: close` after a 401 Digest challenge
 * (confirmed against the same open-source AMT stacks AmtClient's own doc
 * comments already cite — MeshCentral's `amt.js` reopens its own HTTP
 * connection around exactly this case), and a relay hop can drop for
 * reasons that have nothing to do with AMT at all. [exchange] transparently
 * reopens via [openTransport] whenever the current transport is absent,
 * was closed because the last response said `Connection: close`, or an
 * [IOException] surfaces mid-exchange — retried at most once per call, so a
 * genuinely unreachable relay/device still surfaces as a real failure
 * rather than looping forever.
 */
internal class CiraWsmanHttpTransport(
    /** Sent as the `Host` header — purely informational for AMT's embedded
     *  server (confirmed it doesn't validate this against anything, unlike
     *  a virtual-hosting web server), kept only for HTTP/1.1 compliance. */
    private val host: String,
    private val openTransport: () -> AmtRedirectionTransport,
) {
    private var transport: AmtRedirectionTransport? = null

    /** Sends one WS-Man POST to [path] (always `/wsman` in practice) with
     *  [extraHeaders] (typically just `Authorization` on the Digest retry)
     *  and [bodyBytes] as the SOAP envelope, and returns the parsed
     *  response. Reopens the underlying transport at most once on failure
     *  — see this class's doc comment. */
    @Synchronized
    fun exchange(
        path: String,
        extraHeaders: Map<String, String>,
        bodyBytes: ByteArray,
        readTimeoutMs: Int,
    ): RawHttpResponse {
        var retried = false
        while (true) {
            val t = transport ?: openTransport().also { transport = it }
            t.soTimeout = readTimeoutMs
            try {
                writeRequest(t, path, extraHeaders, bodyBytes)
                val response = readResponse(t)
                // AMT's embedded server telling us this connection is done
                // (most reliably seen after a 401 challenge) — close now so
                // the *next* call reopens fresh rather than writing into a
                // socket the far end has already torn down.
                if (response.headers["connection"]?.contains("close", ignoreCase = true) == true) {
                    closeQuietly()
                }
                return response
            } catch (e: IOException) {
                closeQuietly()
                if (retried) {
                    throw IOException(
                        "CIRA WS-Man: lost the relay channel to the device's WS-Man port " +
                            "and a reconnect attempt also failed: ${e.message}",
                        e,
                    )
                }
                retried = true
            }
        }
    }

    fun close() = closeQuietly()

    private fun closeQuietly() {
        transport?.let { runCatching { it.close() } }
        transport = null
    }

    private fun writeRequest(
        t: AmtRedirectionTransport,
        path: String,
        extraHeaders: Map<String, String>,
        bodyBytes: ByteArray,
    ) {
        val headerText = buildString {
            append("POST $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Content-Type: application/soap+xml;charset=UTF-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            for ((name, value) in extraHeaders) append("$name: $value\r\n")
            // AMT's embedded server supports keep-alive (it's what every
            // real console — MeshCommander, MeshCentral, wsmancli —
            // relies on to avoid a fresh TCP+Digest round trip per WS-Man
            // call); explicit here since it's cheap connection reuse we
            // otherwise pay a full relay-channel-open for, per this class's
            // doc comment.
            append("Connection: keep-alive\r\n")
            append("\r\n")
        }
        val out = t.outputStream
        out.write(headerText.toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        out.flush()
    }

    private fun readResponse(t: AmtRedirectionTransport): RawHttpResponse {
        val input = t.inputStream
        val statusLine = readLine(input)
            ?: throw IOException("CIRA WS-Man: the relay channel closed before a status line arrived.")
        // "HTTP/1.1 200 OK" — status text is optional/ignored, only the code matters here.
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("CIRA WS-Man: couldn't parse an HTTP status line: '$statusLine'")

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
                ?: throw IOException("CIRA WS-Man: the relay channel closed while reading response headers.")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }

        val bodyBytes = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            readChunkedBody(input)
        } else {
            readExact(input, headers["content-length"]?.toIntOrNull() ?: 0)
        }
        return RawHttpResponse(statusCode, headers, bodyBytes.toString(Charsets.UTF_8))
    }

    /** Reads one CRLF-terminated line directly off [input], one byte at a
     *  time — deliberately not a [java.io.BufferedReader]/[java.io.Reader],
     *  since either would risk buffering past the header block's terminal
     *  blank line and swallowing the first bytes of the body, which
     *  [readExact]/[readChunkedBody] need to read themselves afterward from
     *  the exact same [input]. Returns null only on EOF with nothing yet
     *  read (a genuinely closed connection between calls); EOF after a
     *  partial line is treated as a malformed response, matching how a
     *  truncated body is already handled in [readExact]. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) {
                val s = buf.toString("ISO-8859-1")
                return if (s.endsWith("\r")) s.substring(0, s.length - 1) else s
            }
            buf.write(b)
        }
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(out, offset, length - offset)
            if (n < 0) {
                throw IOException(
                    "CIRA WS-Man: the relay channel closed after $offset of $length expected body bytes.",
                )
            }
            offset += n
        }
        return out
    }

    /** Minimal RFC 7230 §4.1 chunked-transfer decoder. AMT's own embedded
     *  server always sends `Content-Length` for the WS-Man responses this
     *  app reads (confirmed against every AMT SDK example and open-source
     *  stack this file's sibling classes already cite — none show chunked
     *  WS-Man responses), so this path is defensive rather than a route
     *  known to be exercised by real hardware; kept anyway since it's a
     *  small amount of code and an untested assumption about a fixed
     *  firmware wire format is exactly the kind of gap this codebase's own
     *  conventions (see AMT_VPRO_ROADMAP.md throughout) prefer to close
     *  rather than silently rely on. */
    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input)
                ?: throw IOException("CIRA WS-Man: the relay channel closed while reading a chunk size.")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("CIRA WS-Man: malformed chunk size line: '$sizeLine'")
            if (size == 0) {
                // Optional trailer headers, then the final blank line — drain both, per RFC 7230 §4.1.2.
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            out.write(readExact(input, size))
            readLine(input) // the CRLF that follows every chunk's data
        }
        return out.toByteArray()
    }
}

/** [statusCode] plus lower-cased header names (HTTP headers are
 *  case-insensitive per RFC 7230 §3.2) and the decoded body text. */
internal data class RawHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String,
)
