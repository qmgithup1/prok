package com.systemsgo.hex.rdp.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * CODEC-NEGOTIATION FEATURE (hardware-decode signal): probes this specific
 * Android device's [MediaCodecList] for a hardware-accelerated H.264/AVC and
 * AV1 video decoder.
 *
 * This is intentionally unrelated to
 * [com.systemsgo.hex.rdp.native.AFreeRdpBridge.isH264BackendAvailable] /
 * [com.systemsgo.hex.rdp.native.AFreeRdpBridge.isAv1BackendAvailable], which
 * answer a completely different question — "did *this app's FreeRDP build*
 * compile in a decoder for this codec at all?" (a compile-time fact about
 * the .so, true or false for every device running this build). This object
 * answers "does *this device's chipset* happen to have silicon that can
 * decode this codec efficiently?" — a runtime fact that varies phone to
 * phone, and stays true or false regardless of what FreeRDP was built with.
 *
 * IMPORTANT — this is a signal, not a gate: nothing here disables or forces
 * a [com.systemsgo.hex.data.model.CodecPreference] choice. A device with no
 * hardware AV1 decoder can still pick "Prefer AV1" (FreeRDP would then have
 * to fall back to a software AV1 decode path, or the server simply won't
 * speak AV1 at all — see AFreeRdpBridge.isAv1BackendAvailable's caveat about
 * server-side support besides). The Advanced Settings screen
 * (see com.systemsgo.hex.ui.components.CodecPreferenceSection) only uses
 * this to add a small "hardware accelerated on this device" hint next to
 * the H.264/AV1 options, so a user picking between them has the information
 * to make the lower-CPU/lower-battery choice for their own phone — the same
 * kind of hint a video-streaming app's quality picker shows, not a
 * requirement.
 *
 * Results are cached for the process lifetime: [MediaCodecList] enumeration
 * queries the platform's codec registry, which does not change while the
 * app is running (no codec is installed/removed without a reboot), so
 * there's no reason to repeat the (comparatively expensive, sub-100ms but
 * non-trivial) query on every Advanced Settings screen open.
 */
object HardwareDecoderCapabilities {
    private const val TAG = "HwDecoderCapabilities"

    private const val MIME_H264 = "video/avc"
    private const val MIME_AV1 = "video/av01"

    /**
     * True if this device exposes at least one hardware-accelerated decoder
     * for [MIME_H264]. See the class doc for what "hardware-accelerated"
     * means pre/post Android 10 and why this can never throw.
     */
    val isH264HardwareDecoderAvailable: Boolean by lazy {
        hasHardwareDecoderFor(MIME_H264)
    }

    /**
     * True if this device exposes at least one hardware-accelerated decoder
     * for [MIME_AV1]. AV1 hardware decode blocks are far newer/rarer than
     * H.264 ones — most devices before ~2021-2022 flagships have none at
     * all, so this is expected to be false on a large share of devices even
     * when [isH264HardwareDecoderAvailable] is true. That asymmetry is
     * exactly the information [com.systemsgo.hex.ui.components.CodecPreferenceSection]
     * surfaces to the user.
     */
    val isAv1HardwareDecoderAvailable: Boolean by lazy {
        hasHardwareDecoderFor(MIME_AV1)
    }

    /**
     * Walks [MediaCodecList] once and caches every hardware-accelerated
     * decoder's supported MIME types, rather than re-querying the whole
     * codec list separately for H.264 and AV1 — [MediaCodecList]'s
     * `codecInfos` getter itself does the expensive enumeration work, so
     * this makes [isH264HardwareDecoderAvailable]/[isAv1HardwareDecoderAvailable]
     * each a cheap set lookup after the first access to either.
     *
     * Wrapped in try/catch: enumerating codecs is a platform/OEM-driver call
     * this app has no control over, and a probe like this should never be
     * able to crash the connection-settings screen it backs — same
     * fail-safe shape as every AFreeRdpBridge.isXxxBackendAvailable probe
     * (native probes catch Throwable; this is the Android-side equivalent).
     */
    private val hardwareDecodableMimeTypes: Set<String> by lazy {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val mimeTypes = mutableSetOf<String>()
            for (info in codecList.codecInfos) {
                if (!info.isDecoder || !info.isHardwareAcceleratedCompat()) continue
                for (type in info.supportedTypes) {
                    mimeTypes.add(type)
                }
            }
            mimeTypes
        } catch (e: Throwable) {
            Log.w(TAG, "Unexpected error enumerating MediaCodecList for hardware decoder capabilities", e)
            emptySet()
        }
    }

    private fun hasHardwareDecoderFor(mimeType: String): Boolean =
        mimeType in hardwareDecodableMimeTypes

    /**
     * [MediaCodecInfo.isHardwareAccelerated] only exists from API 29
     * (Android 10) onward. Below that, the platform gives no direct API to
     * ask "is this codec hardware or software?", so this falls back to the
     * same name-prefix heuristic Android's own CTS/CDD test suite and most
     * real-world players (e.g. ExoPlayer's MediaCodecUtil) have long used:
     * Google's bundled *software* reference decoders are always named
     * "OMX.google.*" (or, on the newer Codec2 stack, "c2.android.*"/
     * "c2.google.*") — every other vendor-registered codec name
     * (e.g. "OMX.qcom.*", "OMX.MTK.*", "c2.qti.*") is a real hardware
     * block. This can't be 100% precise on every OEM skin, but it's the
     * same heuristic the wider Android ecosystem already relies on, and
     * being occasionally wrong here only affects a UI hint — never gates a
     * feature (see the class doc's "signal, not a gate" note).
     */
    private fun MediaCodecInfo.isHardwareAcceleratedCompat(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return isHardwareAccelerated
        }
        val lowerName = name.lowercase()
        val isKnownSoftware = lowerName.startsWith("omx.google.") ||
            lowerName.startsWith("c2.android.") ||
            lowerName.startsWith("c2.google.")
        return !isKnownSoftware
    }
}
