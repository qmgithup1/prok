package com.systemsgo.hex.audio

/**
 * REMOTE-AUDIO FEATURE.
 *
 * Overall availability of remote audio for the current session, combining
 * every gate that has to pass before sound can actually be heard/captured:
 *  - the native FreeRDP build must have a working audio backend
 *    ([com.systemsgo.hex.rdp.native.AFreeRdpBridge.isAudioBackendAvailable]);
 *  - the profile must have opted in (enableSound / enableMicRedirect);
 *  - the server must have agreed to open the rdpsnd/audin channel.
 * The UI (session toolbar audio indicator, Settings audio section) reads
 * this single value instead of re-deriving it from three separate booleans
 * at every call site.
 */
enum class RemoteAudioAvailability {
    /** Backend not compiled in this build — see AFreeRdpBridge.isAudioBackendAvailable. */
    UNSUPPORTED_BUILD,
    /** Profile has sound/mic redirect turned off. */
    DISABLED_BY_PROFILE,
    /** Enabled and backend present, but the server hasn't opened the channel (yet, or ever). */
    CHANNEL_NOT_CONNECTED,
    /** Channel connected — audio can flow. */
    AVAILABLE,
}

enum class AudioDirection { PLAYBACK, MICROPHONE }

/** Mirrors the requirement list: "Bluetooth devices, wired headsets, speakers, and earpiece." */
enum class AudioRouteType { BLUETOOTH, WIRED_HEADSET, SPEAKER, EARPIECE, UNKNOWN }

data class AudioRoute(
    val type: AudioRouteType,
    val deviceName: String,
)

/**
 * Adaptive audio quality profile — mirrors the existing network-quality
 * concept already used for video (see
 * [com.systemsgo.hex.data.model.RdpPerformance] /
 * [com.systemsgo.hex.util.NetworkQualityDetector]) so a weak connection trades
 * audio fidelity for reduced buffering/latency the same way it already
 * trades video fidelity for responsiveness.
 */
enum class AudioQualityProfile(val sampleRateHz: Int, val bitDepth: Int) {
    LOW(16_000, 16),
    MEDIUM(24_000, 16),
    HIGH(44_100, 16),
}

data class RemoteAudioState(
    val availability: RemoteAudioAvailability = RemoteAudioAvailability.UNSUPPORTED_BUILD,
    val playbackActive: Boolean = false,
    val microphoneActive: Boolean = false,
    val currentRoute: AudioRoute? = null,
    val qualityProfile: AudioQualityProfile = AudioQualityProfile.MEDIUM,
    /** User-facing explanation for why audio isn't available, or null when [availability] is AVAILABLE. */
    val unavailableReason: String? = null,
)
