package com.systemsgo.hex.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * REMOTE-AUDIO FEATURE.
 *
 * Owns the Android-side half of remote audio for one RDP session:
 *  - Playback: consumes decoded PCM frames from the native rdpsnd channel
 *    ([com.systemsgo.hex.rdp.native.AFreeRdpBridge.audioFrames]) and plays
 *    them through [AudioTrack].
 *  - Capture: does NOT open its own AudioRecord — see the AUDIN-CAPTURE
 *    FIX note below. It only mirrors mic-redirection state for the UI.
 *  - Audio focus: requests transient focus while the session's audio is
 *    actually flowing, and releases it promptly.
 *  - Device routing: reports which output (Bluetooth / wired headset /
 *    speaker / earpiece) is currently active, per the requirement to
 *    "Support Bluetooth devices, wired headsets, speakers, and earpiece."
 *    Actual route *selection* is left to the Android system's normal audio
 *    routing (a USE_VOICE_COMMUNICATION stream routes automatically to a
 *    connected Bluetooth SCO/A2DP or wired device when present) — this class
 *    surfaces what's currently active for the UI rather than overriding it.
 *  - Adaptive quality: downgrades [AudioQualityProfile] when the session's
 *    reported network latency crosses a threshold, mirroring the existing
 *    video-side adaptive behavior. Still meaningful for playback (caps the
 *    sample rate this class downsamples incoming frames to — see
 *    [applyQualityProfile]); has no effect on capture since this class no
 *    longer captures.
 *  - AV sync: consults [AvSyncCoordinator] before each buffer write and
 *    pauses playback if the on-screen picture has stalled relative to its
 *    own recent update cadence, so audio doesn't keep running ahead of a
 *    frozen frame — see that class's doc comment for the full rationale.
 *
 * One instance per RDP session — created and torn down alongside the
 * session's [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter], same lifetime
 * as the native bridge it reads from.
 *
 * AUDIN-CAPTURE FIX (was: "IMPORTANT SCOPE NOTE" describing a pending PCM
 * handoff — that note is now stale/wrong and has been replaced by this one).
 * See systemsgo_jni.c's REAL-PCM FIX comment and CMakeLists.txt's
 * SYSTEMSGO_AUDIO_BACKEND_AVAILABLE option for the full picture: this project's
 * FreeRDP prebuilt is built with -DWITH_OPENSLES=ON, so FreeRDP's own
 * Android audin backend (channels/audin/client/opensles/) captures the
 * microphone itself, directly inside libfreerdp-client3.so, the moment the
 * server opens the "audin" channel — entirely independent of this class.
 * `nativeSendAudioCapture`/`AFreeRdpBridge.sendAudioCapture` are a
 * deliberate permanent no-op on the native side for exactly this reason
 * (see systemsgo_jni.c's doc comment on that function).
 *
 * Earlier revisions of this class still opened a *second*,
 * `MediaRecorder.AudioSource.VOICE_COMMUNICATION` AudioRecord here and fed
 * its PCM into that now-inert `sendAudioCapture` call. Two problems with
 * that, one certain and one device-dependent:
 *  1. (Certain) It accomplished nothing — `sendAudioCapture` /
 *     `nativeSendAudioCapture` is a deliberate no-op, so this was an
 *     active AudioRecord session running, draining battery and holding
 *     the mic, purely to throw its output away.
 *  2. (Device-dependent, not a blanket guarantee) It was a second capture
 *     of the *same privacy-sensitive source* competing with FreeRDP's own
 *     OpenSL ES audin capture, running in the same process at the same
 *     time. Android 10+'s concurrent-capture framework generally resolves
 *     same-app/cross-app contention by silencing the lower-priority
 *     stream rather than failing the second AudioRecord's open() outright
 *     (see AOSP's "Sharing audio input" docs), so this wasn't guaranteed
 *     to break FreeRDP's capture outright — but real-world reports of
 *     FreeRDP's own opensles AudioRecord failing to open at all exist
 *     (e.g. FreeRDP/FreeRDP#6412, "AudioFlinger could not create record
 *     track, status: -22"), and adding a second, redundant, same-process
 *     claimant on that same resource for zero benefit was pure downside
 *     with no corresponding upside — worth removing regardless of exactly
 *     how a given device's audio HAL arbitrates the contention.
 * That capture path is removed; this class now only reflects mic-redirection
 * *state* (channel connected + `captureRequested`) for
 * [RemoteAudioState.microphoneActive], the same "surface state, don't own
 * the hardware twice" pattern already used for playback's `audioFrames`
 * (which nothing currently emits into, for the mirror-image reason: FreeRDP
 * plays rdpsnd PCM internally too — see systemsgo_jni.c's REAL-PCM FIX comment.
 * That path is left in place rather than removed since it is genuinely
 * inert, not resource-competing, unlike the capture path above).
 */
class RemoteAudioManager(
    private val appContext: Context,
    /** PCM frames decoded by native from the "rdpsnd" channel. */
    private val audioFrames: SharedFlow<AudioFrame>,
    /** Channel connect/disconnect events for rdpsnd/audin. */
    private val channelState: SharedFlow<AudioChannelState>,
    /** True if this profile requested playback (enableSound). */
    private val playbackRequested: Boolean,
    /** True if this profile requested mic redirection (enableMicRedirect). */
    private val captureRequested: Boolean,
    /** Whether the native build has a working audio backend at all. */
    private val backendAvailable: Boolean,
    /** Current round-trip latency for this session, used for adaptive quality. */
    private val latencyMsProvider: () -> Long,
    /** AV-SYNC FEATURE: shared clock fed video-frame arrivals by RdpRemoteAdapter — see its doc. */
    private val avSync: AvSyncCoordinator,
) {
    data class AudioFrame(val pcm: ByteArray, val sampleRate: Int, val channels: Int, val bitsPerSample: Int)
    data class AudioChannelState(val playbackConnected: Boolean, val captureConnected: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val audioManager: AudioManager
        get() = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(RemoteAudioState())
    val state: StateFlow<RemoteAudioState> = _state.asStateFlow()

    private var audioTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    /** Call once the session is CONNECTED — starts collecting playback frames and (if enabled) mic capture. */
    fun start() {
        val availability = when {
            !backendAvailable -> RemoteAudioAvailability.UNSUPPORTED_BUILD
            !playbackRequested && !captureRequested -> RemoteAudioAvailability.DISABLED_BY_PROFILE
            else -> RemoteAudioAvailability.CHANNEL_NOT_CONNECTED
        }
        _state.value = _state.value.copy(
            availability = availability,
            unavailableReason = unavailableReasonFor(availability),
        )
        if (availability != RemoteAudioAvailability.CHANNEL_NOT_CONNECTED) return

        scope.launch {
            channelState.collect { evt ->
                val available = if (evt.playbackConnected || evt.captureConnected) {
                    RemoteAudioAvailability.AVAILABLE
                } else {
                    RemoteAudioAvailability.CHANNEL_NOT_CONNECTED
                }
                _state.value = _state.value.copy(
                    availability = available,
                    unavailableReason = unavailableReasonFor(available),
                )
                if (evt.captureConnected && captureRequested) {
                    startMicCapture()
                } else {
                    stopMicCapture()
                }
                if (!evt.playbackConnected) {
                    releasePlayback()
                }
                updateRoute()
            }
        }

        scope.launch {
            audioFrames.collect { frame ->
                playFrame(frame)
                adaptQualityIfNeeded()
            }
        }
    }

    private fun unavailableReasonFor(availability: RemoteAudioAvailability): String? = when (availability) {
        RemoteAudioAvailability.UNSUPPORTED_BUILD ->
            "This build has no audio backend compiled in — remote audio is unavailable. " +
                "See app/src/main/cpp/SETUP.md."
        RemoteAudioAvailability.DISABLED_BY_PROFILE ->
            "Remote audio and microphone redirection are both turned off for this connection."
        RemoteAudioAvailability.CHANNEL_NOT_CONNECTED ->
            "Waiting for the server to accept the audio channel — it may not support remote audio."
        RemoteAudioAvailability.AVAILABLE -> null
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    private fun ensurePlaybackFocus() {
        if (hasFocus) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            hasFocus = audioManager.requestAudioFocus(
                null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun releaseFocus() {
        if (!hasFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        hasFocus = false
    }

    private fun playFrame(frame: AudioFrame) {
        if (!playbackRequested) return
        // AV-SYNC FEATURE: hold output rather than write this buffer if the picture
        // has stalled relative to its own recent update cadence — see
        // AvSyncCoordinator's doc. Only pauses a track that actually exists; if
        // nothing has played yet there's nothing to pause.
        if (avSync.shouldHoldAudio()) {
            audioTrack?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause() }
            return
        }
        // QUALITY-EFFECTIVE FIX: this used to call ensureAudioTrack(frame.sampleRate, ...)
        // directly, i.e. always play at whatever rate the server sent — the adaptive
        // qualityProfile only ever updated _state and fed the *mic-capture* rate
        // (see startMicCapture), so a LOW/MEDIUM downgrade under bad network conditions
        // had zero effect on what was actually played back. Applying the profile here
        // makes the downgrade real: it caps played-back audio at the profile's ceiling,
        // trading fidelity for less data/buffering exactly as the class doc promises.
        val adapted = applyQualityProfile(frame)
        val track = ensureAudioTrack(adapted.sampleRate, adapted.channels, adapted.bitsPerSample) ?: return
        ensurePlaybackFocus()
        try {
            track.write(adapted.pcm, 0, adapted.pcm.size)
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            _state.value = _state.value.copy(playbackActive = true)
        } catch (e: Throwable) {
            Log.w(TAG, "AudioTrack write failed", e)
        }
    }

    /**
     * Caps a playback frame at the current [AudioQualityProfile]'s sample rate. Only
     * downsamples (never upsamples — a LOW-profile stream from the server, if the
     * server itself sends fewer samples, is left untouched) and only handles PCM16,
     * which is the only encoding this class ever hands to [AudioTrack] (see
     * ensureAudioTrack: bitsPerSample < 16 uses ENCODING_PCM_8BIT and isn't covered by
     * the adaptive path — 8-bit streams are already minimal).
     */
    private fun applyQualityProfile(frame: AudioFrame): AudioFrame {
        val ceiling = _state.value.qualityProfile.sampleRateHz
        if (frame.sampleRate <= ceiling || frame.bitsPerSample != 16) return frame
        val downsampled = downsamplePcm16(frame.pcm, frame.sampleRate, ceiling, frame.channels)
        return frame.copy(pcm = downsampled, sampleRate = ceiling)
    }

    /**
     * Nearest-sample PCM16 decimation from [fromRate] down to [toRate]. Not a proper
     * band-limited resampler — this is a deliberate, cheap trade for an interactive
     * remote-desktop audio path where reduced fidelity is the explicit goal at low
     * quality tiers, not a defect to hide.
     */
    private fun downsamplePcm16(pcm: ByteArray, fromRate: Int, toRate: Int, channels: Int): ByteArray {
        if (fromRate <= toRate || channels <= 0) return pcm
        val bytesPerFrame = 2 * channels
        val frameCount = pcm.size / bytesPerFrame
        if (frameCount <= 1) return pcm
        val outFrameCount = ((frameCount.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = ByteArray(outFrameCount * bytesPerFrame)
        for (i in 0 until outFrameCount) {
            val srcIndex = ((i.toLong() * fromRate) / toRate).toInt().coerceIn(0, frameCount - 1)
            System.arraycopy(pcm, srcIndex * bytesPerFrame, out, i * bytesPerFrame, bytesPerFrame)
        }
        return out
    }

    private fun ensureAudioTrack(sampleRate: Int, channels: Int, bitsPerSample: Int): AudioTrack? {
        val existing = audioTrack
        if (existing != null && existing.sampleRate == sampleRate) return existing
        existing?.release()

        val channelConfig = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val encoding = if (bitsPerSample >= 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBuf <= 0) return null

        // Latency minimization: AudioTrack.PERFORMANCE_MODE_LOW_LATENCY (API 26+)
        // trades a slightly higher underrun risk for materially lower output
        // latency, appropriate for an interactive remote-desktop session
        // rather than media playback.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        return track
    }

    private fun releasePlayback() {
        audioTrack?.let {
            try { it.stop() } catch (e: Throwable) { android.util.Log.d("RemoteAudioManager", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
            it.release()
        }
        audioTrack = null
        releaseFocus()
        _state.value = _state.value.copy(playbackActive = false)
    }

    // ── Microphone capture ───────────────────────────────────────────────────
    //
    // AUDIN-CAPTURE FIX: this class deliberately does NOT open its own
    // AudioRecord — see the class doc's "AUDIN-CAPTURE FIX" note. Certain
    // reason: it fed a now-documented no-op (nativeSendAudioCapture), so it
    // was pure overhead. Possible additional reason: a redundant same-source
    // capture racing FreeRDP's own OpenSL ES audin capture in the same
    // process. These two functions only keep [RemoteAudioState.microphoneActive]
    // accurate for the UI.

    private fun startMicCapture() {
        _state.value = _state.value.copy(microphoneActive = true)
    }

    private fun stopMicCapture() {
        _state.value = _state.value.copy(microphoneActive = false)
    }

    // ── Device routing (Bluetooth / wired / speaker / earpiece) ─────────────

    /**
     * Support requirement: "Bluetooth devices, wired headsets, speakers, and
     * earpiece." Reports the currently-active output — Android's own audio
     * policy already picks the right physical route for a
     * USE_VOICE_COMMUNICATION stream (preferring a connected Bluetooth
     * SCO/A2DP or wired device over the built-in speaker/earpiece
     * automatically), so this only needs to reflect that choice for the UI.
     */
    private fun updateRoute() {
        val route = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val active = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                } ?: devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                active?.let {
                    AudioRoute(
                        type = when (it.type) {
                            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRouteType.BLUETOOTH
                            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioRouteType.WIRED_HEADSET
                            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRouteType.SPEAKER
                            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRouteType.EARPIECE
                            else -> AudioRouteType.UNKNOWN
                        },
                        deviceName = it.productName?.toString() ?: "Audio device",
                    )
                }
            } else {
                null
            }
        } catch (e: Throwable) {
            null
        }
        _state.value = _state.value.copy(currentRoute = route)
    }

    // ── Adaptive quality ─────────────────────────────────────────────────────

    /**
     * Adaptive audio quality: mirrors the existing video-side network
     * adaptation. A round trip above 300ms downgrades to LOW (favors
     * reduced buffering over fidelity); under 100ms allows HIGH.
     */
    private fun adaptQualityIfNeeded() {
        val latency = latencyMsProvider()
        val target = when {
            latency > 300 -> AudioQualityProfile.LOW
            latency > 100 -> AudioQualityProfile.MEDIUM
            else -> AudioQualityProfile.HIGH
        }
        if (target != _state.value.qualityProfile) {
            _state.value = _state.value.copy(qualityProfile = target)
        }
    }

    /** Tears down playback/capture and releases audio focus. Call from the session's onDisconnect/onCleared. */
    fun stop() {
        stopMicCapture()
        releasePlayback()
        // BUG FIX (SCOPE-LEAK): `scope` backs two indefinite `collect{}` loops
        // started in start() (channelState and audioFrames) — since those
        // SharedFlows never complete on their own, the collectors ran forever
        // even after stop() was called, unless the whole scope was
        // cancelled. It never was, unlike every sibling client in this app
        // (VncClient, SshClient, GuacamoleSessionClient, etc. all cancel
        // their own scope on teardown) — leaking both coroutines and, via
        // their captured `this`, this entire RemoteAudioManager instance for
        // the rest of the process lifetime. One start()/stop() pair per
        // instance (see call sites in RdpRemoteAdapter/GuacamoleSessionClient,
        // which construct a fresh RemoteAudioManager per session rather than
        // reusing one across reconnects), so cancelling here is safe.
        scope.cancel()
    }

    companion object {
        private const val TAG = "RemoteAudioManager"
    }
}
