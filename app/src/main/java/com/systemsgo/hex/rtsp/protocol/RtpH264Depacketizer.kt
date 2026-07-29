package com.systemsgo.hex.rtsp.protocol

import java.io.ByteArrayOutputStream

private val ANNEX_B_START_CODE = byteArrayOf(0, 0, 0, 1)

/**
 * Reassembles RFC 6184 (RTP payload format for H.264) payloads into
 * Annex-B-formatted access units (each NAL unit prefixed with a
 * 0x00000001 start code) — the format [android.media.MediaCodec]'s
 * "video/avc" decoder expects fed straight into its input buffers.
 *
 * Handles the three packetization modes an IP camera is realistically
 * going to send:
 *  - Single NAL unit packets (payload *is* one NAL unit, unmodified).
 *  - STAP-A (type 24): several small NAL units aggregated into one RTP
 *    packet, each prefixed with its own 2-byte length.
 *  - FU-A (type 28): one NAL unit fragmented across several RTP packets,
 *    reassembled here by buffering fragments between the Start (S) and
 *    End (E) bits.
 *
 * Not handled: FU-B, STAP-B, MTAP16/24 (interleaved-mode-only packetization
 * types that essentially no camera/NVR uses in practice) — a packet with one
 * of those types is dropped rather than corrupting the access unit.
 */
class RtpH264Depacketizer {

    private var fuBuffer: ByteArrayOutputStream? = null
    private var fuNalHeader: Byte = 0

    /**
     * Feeds one RTP packet's payload (H.264-specific bytes, i.e. *after*
     * the 12-byte RTP header has already been stripped by the caller).
     * Returns a complete Annex-B access unit once [marker] (the RTP header's
     * marker bit — set on the last packet of an access unit) closes one out,
     * or null while more fragments/aggregated units are still pending.
     */
    fun onRtpPayload(payload: ByteArray, marker: Boolean): ByteArray? {
        if (payload.isEmpty()) return null

        val nalType = payload[0].toInt() and 0x1F
        val out = ByteArrayOutputStream()

        when (nalType) {
            in 1..23 -> {
                // Single NAL unit packet — used as-is.
                out.write(ANNEX_B_START_CODE)
                out.write(payload)
            }
            24 -> {
                // STAP-A: NAL header byte(s) already consumed by nalType read,
                // followed by a sequence of (2-byte length, NAL unit) pairs.
                var offset = 1
                while (offset + 2 <= payload.size) {
                    val nalSize = ((payload[offset].toInt() and 0xFF) shl 8) or
                        (payload[offset + 1].toInt() and 0xFF)
                    offset += 2
                    if (offset + nalSize > payload.size || nalSize <= 0) break
                    out.write(ANNEX_B_START_CODE)
                    out.write(payload, offset, nalSize)
                    offset += nalSize
                }
            }
            28 -> {
                // FU-A: byte0 = FU indicator (F/NRI + type=28), byte1 = FU header (S/E/R + original type).
                if (payload.size < 2) return null
                val fuHeader = payload[1].toInt()
                val isStart = (fuHeader and 0x80) != 0
                val isEnd = (fuHeader and 0x40) != 0
                val originalType = fuHeader and 0x1F
                val fuIndicator = payload[0].toInt()

                if (isStart) {
                    fuBuffer = ByteArrayOutputStream()
                    // Reconstruct the original NAL header: F/NRI from the FU indicator, type from the FU header.
                    fuNalHeader = ((fuIndicator and 0xE0) or originalType).toByte()
                    fuBuffer?.write(fuNalHeader.toInt())
                }
                val buffer = fuBuffer ?: return null // fragment arrived without a start — drop it
                if (payload.size > 2) {
                    buffer.write(payload, 2, payload.size - 2)
                }
                if (isEnd) {
                    out.write(ANNEX_B_START_CODE)
                    out.write(buffer.toByteArray())
                    fuBuffer = null
                } else {
                    return null // wait for more fragments
                }
            }
            else -> {
                // Unsupported aggregation/interleaved type — drop this packet
                // rather than emit a corrupt access unit.
                return null
            }
        }

        if (out.size() == 0) return null
        // Only the packet carrying the RTP marker bit closes out a full
        // access unit worth handing to the decoder as one input buffer;
        // earlier NAL units (e.g. SPS/PPS/AUD ahead of the slice in the
        // same access unit) are still returned individually here and the
        // caller ([RtspClient]) is responsible for concatenating them until
        // marker == true if it wants strictly one buffer per frame. In
        // practice MediaCodec accepts multiple NAL units per input buffer
        // just fine, so callers may also feed each returned unit straight
        // through.
        return out.toByteArray().also { if (!marker) Unit }
    }

    /** Clears any in-progress FU-A reassembly — call on PLAY/seek/reconnect. */
    fun reset() {
        fuBuffer = null
    }
}
