package com.systemsgo.hex.rtsp.protocol

import android.util.Base64

/**
 * One `m=` media section of a DESCRIBE response's SDP body, narrowed down to
 * exactly what [RtspClient] needs to SETUP and decode an H.264 video track.
 * Audio (`m=audio`) sections are parsed by [SdpParser] like any other but
 * [RtspClient] currently ignores them — see that class's doc comment.
 */
data class SdpVideoTrack(
    /** RTP payload type from the `m=video <port> RTP/AVP <payloadType>` line. */
    val payloadType: Int,
    /** The `a=control:` attribute for this media — a relative or absolute URI to SETUP against. */
    val controlUri: String,
    /** RTP clock rate from `a=rtpmap` (H.264 is always 90000, but read it rather than assume). */
    val clockRateHz: Int,
    /** Base64-decoded SPS NAL unit(s) from `a=fmtp`'s `sprop-parameter-sets`, first field. */
    val spropSps: ByteArray?,
    /** Base64-decoded PPS NAL unit from `a=fmtp`'s `sprop-parameter-sets`, second field. */
    val spropPps: ByteArray?,
)

/** Minimal SDP (RFC 4566) parser: just enough to drive an RTSP/H.264 SETUP+PLAY. */
object SdpParser {

    /** Parses the whole SDP body and returns the first H.264 video track found, or null. */
    fun parseFirstH264VideoTrack(sdp: String): SdpVideoTrack? {
        val lines = sdp.lines().map { it.trim() }

        var currentPayloadType: Int? = null
        var sawVideoSection = false
        var clockRate = 90000
        var controlUri = ""
        var spropSps: ByteArray? = null
        var spropPps: ByteArray? = null

        // First pass: find the m=video line and remember its payload type,
        // then only look at a=lines that belong to that same section (i.e.
        // everything up to the next m= line).
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("m=video")) {
                sawVideoSection = true
                val parts = line.removePrefix("m=video").trim().split(Regex("\\s+"))
                // parts = [<port>, RTP/AVP, <fmt> ...] — take the first payload type listed.
                currentPayloadType = parts.getOrNull(2)?.toIntOrNull()
            } else if (sawVideoSection && line.startsWith("m=")) {
                // Entered the next media section — video section is done.
                break
            } else if (sawVideoSection) {
                when {
                    line.startsWith("a=control:") -> {
                        controlUri = line.removePrefix("a=control:").trim()
                    }
                    line.startsWith("a=rtpmap:") -> {
                        // a=rtpmap:96 H264/90000
                        val rest = line.removePrefix("a=rtpmap:").trim()
                        val spaceIdx = rest.indexOf(' ')
                        if (spaceIdx >= 0) {
                            val encoding = rest.substring(spaceIdx + 1)
                            val slashIdx = encoding.indexOf('/')
                            if (slashIdx >= 0) {
                                encoding.substring(slashIdx + 1).toIntOrNull()?.let { clockRate = it }
                            }
                        }
                    }
                    line.startsWith("a=fmtp:") -> {
                        val fmtpBody = line.substringAfter(' ', missingDelimiterValue = "")
                        val spropField = fmtpBody.split(';')
                            .map { it.trim() }
                            .firstOrNull { it.startsWith("sprop-parameter-sets=") }
                        if (spropField != null) {
                            val value = spropField.removePrefix("sprop-parameter-sets=")
                            val sets = value.split(',')
                            if (sets.isNotEmpty()) {
                                spropSps = runCatching {
                                    Base64.decode(sets[0], Base64.DEFAULT)
                                }.getOrNull()
                            }
                            if (sets.size > 1) {
                                spropPps = runCatching {
                                    Base64.decode(sets[1], Base64.DEFAULT)
                                }.getOrNull()
                            }
                        }
                    }
                }
            }
            index++
        }

        val payloadType = currentPayloadType ?: return null
        if (!sawVideoSection) return null
        return SdpVideoTrack(
            payloadType = payloadType,
            controlUri = controlUri,
            clockRateHz = clockRate,
            spropSps = spropSps,
            spropPps = spropPps,
        )
    }

    /**
     * Resolves an `a=control:` value against the DESCRIBE request's own URL,
     * per RFC 2326 §C.1.1: an absolute `rtsp://...` control URI is used
     * as-is; anything else (including "*", meaning "the session URL itself")
     * is appended as a path segment onto [baseUrl].
     */
    fun resolveControlUri(baseUrl: String, control: String): String {
        if (control.isEmpty() || control == "*") return baseUrl
        if (control.startsWith("rtsp://", ignoreCase = true) || control.startsWith("rtsps://", ignoreCase = true)) {
            return control
        }
        return if (baseUrl.endsWith("/")) baseUrl + control else "$baseUrl/$control"
    }
}
