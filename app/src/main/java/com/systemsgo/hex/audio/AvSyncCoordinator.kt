package com.systemsgo.hex.audio

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AV-SYNC FEATURE.
 *
 * Before this class existed there was no code anywhere in this project relating
 * video (framebuffer/RDPGFX bitmap updates, delivered via
 * [com.systemsgo.hex.rdp.native.AFreeRdpBridge.frames]) to audio (rdpsnd PCM,
 * delivered via [com.systemsgo.hex.rdp.native.AFreeRdpBridge.audioFrames]) in
 * time at all. RDP has no shared presentation-timestamp concept the way a
 * media container does — the two channels are independent dynamic virtual
 * channels, each subject to its own network jitter and its own
 * decode/buffer path on the Android side. In practice this means it is
 * entirely possible for something playing back inside the remote session
 * (a video, a call) to have its audio keep flowing smoothly out of
 * [RemoteAudioManager]'s [android.media.AudioTrack] for a noticeable
 * stretch while the on-screen picture has stalled (a delayed bitmap
 * update, a GFX decode hiccup, a brief network stall) — the sound audibly
 * runs ahead of a frozen frame.
 *
 * This is the shared clock the two independently-clocked paths are checked
 * against:
 *  - [RdpRemoteAdapter][com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter] calls
 *    [onVideoFrame] every time a bitmap update arrives, feeding a rolling
 *    estimate of how fast the picture is *currently* updating.
 *  - [RemoteAudioManager] calls [shouldHoldAudio] right before writing each
 *    PCM buffer, and pauses output rather than let it run ahead when the
 *    picture has gone quiet for much longer than its own established
 *    cadence would predict.
 *
 * Deliberately NOT triggered by "no video update recently" on its own: an
 * idle desktop with no on-screen motion (the common case — someone reading
 * a remote document, or playing background audio with nothing changing on
 * screen) can legitimately go seconds between bitmap updates, and pausing
 * audio in that case would be a regression, not a fix. [onVideoFrame] only
 * folds a gap into the rolling cadence when it looks like part of an
 * *active* update stream (see [STALL_GAP_CEILING_MS]); [shouldHoldAudio]
 * only fires once a cadence has actually been established, so a stall is
 * judged relative to how fast the picture was just updating, not against a
 * fixed constant.
 *
 * All correction happens on the audio side only — video/input responsiveness
 * (mouse, keyboard, screen redraws) is never delayed on account of audio.
 * The correction itself is intentionally coarse (pause/resume the whole
 * output rather than resampling or per-sample timestamp alignment): the
 * goal is to bound how far audio can drift ahead of a stalled picture, not
 * to reproduce sample-accurate lip sync against a protocol that carries
 * none of the timestamps that would require.
 */
class AvSyncCoordinator {

    @Volatile
    private var lastVideoFrameAtMs: Long = SystemClock.elapsedRealtime()

    /** EWMA of the interval between recent "active" video updates; MAX_VALUE = no cadence established yet. */
    @Volatile
    private var emaIntervalMs: Double = Double.MAX_VALUE

    private val _driftMs = MutableStateFlow(0L)
    /** How long it's been since the last video update, for UI/diagnostics. */
    val driftMs: StateFlow<Long> = _driftMs.asStateFlow()

    private val _holding = MutableStateFlow(false)
    /** True while audio is deliberately paused waiting for a stalled picture to catch up. */
    val holding: StateFlow<Boolean> = _holding.asStateFlow()

    /** Call whenever a new bitmap/frame update arrives from the native bridge. */
    fun onVideoFrame(timestampMs: Long = SystemClock.elapsedRealtime()) {
        val gap = timestampMs - lastVideoFrameAtMs
        lastVideoFrameAtMs = timestampMs
        // Only fold gaps that look like part of an ongoing active-update cadence
        // into the rolling average — a huge gap is itself a stall (possibly the
        // one shouldHoldAudio just caught) and would poison the cadence estimate
        // right before it's needed to judge the *next* stall.
        if (gap in 1 until STALL_GAP_CEILING_MS) {
            emaIntervalMs = if (emaIntervalMs == Double.MAX_VALUE) {
                gap.toDouble()
            } else {
                EMA_ALPHA * gap + (1 - EMA_ALPHA) * emaIntervalMs
            }
        }
    }

    /**
     * Called by [RemoteAudioManager] right before each PCM buffer would be written
     * to the AudioTrack. Returns true if audio should hold (skip this buffer, the
     * picture is judged stalled relative to its own recent update cadence).
     */
    fun shouldHoldAudio(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val cadence = emaIntervalMs
        val gap = nowMs - lastVideoFrameAtMs
        _driftMs.value = gap
        if (cadence == Double.MAX_VALUE) {
            // No active-update cadence ever observed (idle desktop, or nothing
            // played back yet) — never hold audio on this basis.
            _holding.value = false
            return false
        }
        val stallThreshold = (cadence * STALL_MULTIPLIER).coerceAtLeast(MIN_HOLD_THRESHOLD_MS.toDouble())
        val hold = gap > stallThreshold
        _holding.value = hold
        return hold
    }

    companion object {
        private const val EMA_ALPHA = 0.2
        /** Gaps at or above this are treated as a stall themselves, not a cadence sample. */
        private const val STALL_GAP_CEILING_MS = 2_000L
        /** How many multiples of the established cadence counts as "stalled". */
        private const val STALL_MULTIPLIER = 6.0
        /** Floor so a very fast cadence (e.g. 16ms) doesn't make the threshold unreasonably twitchy. */
        private const val MIN_HOLD_THRESHOLD_MS = 150L
    }
}
