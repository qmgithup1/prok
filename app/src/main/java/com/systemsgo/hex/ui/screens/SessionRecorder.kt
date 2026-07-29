package com.systemsgo.hex.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Choreographer
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * TOOLBOX FEATURE (Stage 1 — "تصوير الجلسة" / session capture): encodes the
 * *remote frame only* (never the Toolbox, Quick Bar, or any other overlay)
 * into an MP4 file.
 *
 * Deliberately reuses the exact same clean bitmap source
 * `RdpSessionViewModel.frameBitmap` already feeds to `takeScreenshot()` —
 * this is NOT a screen/View capture of the Android UI, so there is no risk
 * of Toolbox chrome, FPS counters, or other overlays leaking into the
 * output, matching the plan's Stage 1 acceptance criteria.
 *
 * TOOLBOX FEATURE (Stage 15 — "تصوير حقيقي بجودة ما يراه المستخدم"): the
 * old fixed 10fps capture cadence was a battery-saving guess, not a match
 * for anything the user actually sees — the on-screen Canvas this app
 * already draws the same [frameBitmap] into is itself driven by the
 * device's real display refresh rate via [Choreographer]/VSYNC, so a
 * recording capped at 10fps always looked choppier than the live session
 * did. Capture now (a) defaults its target fps to the *device's actual
 * measured display refresh rate* (see [detectDisplayRefreshRateFps], capped
 * to a sane 24-60 range) instead of a hardcoded 10, and (b) is driven by a
 * [Choreographer.FrameCallback] — the same per-VSYNC callback mechanism
 * Android's own UI toolkit uses to draw each screen frame — instead of a
 * coroutine `delay()` loop, so each capture tick lines up with an actual
 * display frame rather than an approximate timer that drifts under
 * scheduling load. The net effect: what ends up in the MP4 is, frame for
 * frame, what was really on the user's screen at that moment — not a
 * throttled-down approximation of it.
 *
 * Implementation: a `MediaCodec` H.264 encoder configured with a
 * `Surface` input (`COLOR_FormatSurface`) avoids ever touching raw YUV
 * buffers ourselves — each captured frame is simply drawn onto that
 * `Surface`'s canvas via [Surface.lockCanvas]/[Surface.unlockCanvasAndPost],
 * scaled to the target quality's resolution. Encoded output is drained into
 * an [MediaMuxer] writing a standard MP4 container.
 */
class SessionRecorder(private val appContext: Context) {

    enum class Quality(val label: String, val targetHeight: Int) {
        P144("144p", 144),
        P240("240p", 240),
        P360("360p", 360),
        P480("480p", 480),
        P720("720p", 720),
        P1080("1080p", 1080),
    }

    // Session-content video, same "app-private, never shared storage" policy
    // as screenshots/[RdpSessionViewModel.takeScreenshot] — filesDir requires
    // no WRITE_EXTERNAL_STORAGE permission and isn't visible to other apps.
    private val recordingsDir: File
        get() = File(appContext.filesDir, "recordings").apply { mkdirs() }

    // Recordings are much larger than PNG screenshots, so the retention
    // policy caps by both count and total size — mirrors
    // RdpSessionViewModel.pruneScreenshots (MAX_SCREENSHOTS / MAX_SCREENSHOT_DIR_BYTES)
    // but with numbers appropriate for video: fewer files, bigger budget.
    private val maxRecordings = 6
    private val maxRecordingsDirBytes = 500L * 1024 * 1024 // 500 MB

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private var drainJob: Job? = null
    private var captureJob: Job? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private var recording = false
    private var outputFile: File? = null

    /** True while a recording is currently in progress. */
    fun isRecording(): Boolean = recording

    /**
     * Starts recording, pulling frames from [frameProvider] and encoding
     * them into a new MP4 in [recordingsDir], scaled so the output height
     * matches [quality] while preserving the remote screen's aspect ratio.
     *
     * @param frameRateFps target capture/encode rate. Defaults to the
     *        device's own measured display refresh rate (see
     *        [detectDisplayRefreshRateFps]) — i.e. "whatever fps the user's
     *        screen actually runs at" — rather than an arbitrary fixed
     *        number, so the recording matches what was really seen live.
     * @param remoteWidth / [remoteHeight] the *source* remote framebuffer
     *        size (`RdpSessionViewModel.screenWidth`/`screenHeight`), used
     *        purely to compute the correctly-proportioned output resolution.
     * @return the output [File] on success, or null if the encoder could not
     *        be configured (caller shows a toast/failure state, same as a
     *        failed screenshot).
     */
    fun start(
        scope: CoroutineScope,
        remoteWidth: Int,
        remoteHeight: Int,
        quality: Quality,
        frameRateFps: Int = detectDisplayRefreshRateFps(),
        frameProvider: () -> Bitmap?,
    ): File? {
        if (recording) return outputFile
        if (remoteWidth <= 0 || remoteHeight <= 0) return null

        // MediaCodec requires even dimensions for YUV420-derived formats.
        val targetHeight = (quality.targetHeight / 2) * 2
        val targetWidth = (((targetHeight.toFloat() * remoteWidth / remoteHeight).toInt()) / 2) * 2
        if (targetWidth <= 0 || targetHeight <= 0) return null

        val bitRate = estimateBitRate(targetWidth, targetHeight, frameRateFps)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRateFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (_: Exception) {
            return null
        }
        val surface = try {
            encoder.createInputSurface()
        } catch (_: Exception) {
            encoder.release()
            return null
        }

        pruneRecordings()
        val file = File(recordingsDir, "SystemsGo_${System.currentTimeMillis()}.mp4")
        val newMuxer = try {
            MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (_: Exception) {
            encoder.release()
            surface.release()
            return null
        }

        encoder.start()

        codec = encoder
        inputSurface = surface
        muxer = newMuxer
        muxerStarted = false
        videoTrackIndex = -1
        outputFile = file
        recording = true

        // Continuously drains encoder output into the muxer. Runs on its own
        // job so it keeps up with the encoder independent of the capture
        // cadence below.
        drainJob = scope.launch(Dispatchers.Default) {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isActive && recording) {
                drainEncoder(bufferInfo, endOfStream = false)
                kotlinx.coroutines.delay(10)
            }
        }

        // TOOLBOX FEATURE (Stage 15): pulls the latest clean remote-frame
        // bitmap and draws it onto the encoder's input Surface on every
        // real display VSYNC (via Choreographer) instead of an approximate
        // coroutine `delay()` cadence — this is the exact same callback
        // mechanism the on-screen Canvas rendering this same bitmap already
        // relies on, so the recording's frame timing matches what the user
        // actually saw live, at the device's actual refresh rate, rather
        // than a fixed low-rate poll. This is the only place a Bitmap ever
        // touches the pipeline, and it is always [frameProvider]'s bitmap
        // (RdpSessionViewModel.frameBitmap), never a View/window capture, so
        // Toolbox/overlay UI can never leak in.
        val dstRect = Rect(0, 0, targetWidth, targetHeight)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!recording) return
                val frame = frameProvider()
                if (frame != null && !frame.isRecycled) {
                    drawFrameToSurface(surface, frame, dstRect, paint)
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = callback
        // Choreographer callbacks must be registered from a thread with a
        // Looper — post the initial registration to Main; doFrame's own
        // re-post below then keeps the chain alive on the same thread for
        // as long as `recording` stays true.
        captureJob = scope.launch(Dispatchers.Main) {
            Choreographer.getInstance().postFrameCallback(callback)
        }

        return file
    }

    private fun drawFrameToSurface(surface: Surface, frame: Bitmap, dstRect: Rect, paint: Paint) {
        var canvas: Canvas? = null
        try {
            canvas = surface.lockCanvas(null)
            // Thread-safety note: [frame] is the same live display-buffer
            // bitmap RdpSessionViewModel.applyFrameUpdate() writes into.
            // Matching takeScreenshot()'s BUG #3 FIX, synchronize on it while
            // reading so a concurrent frame update can't tear the draw.
            synchronized(frame) {
                if (!frame.isRecycled) {
                    canvas.drawBitmap(frame, null, dstRect, paint)
                }
            }
        } catch (_: Exception) {
            // Surface may already be released if stop() raced with a
            // capture tick — safe to just skip this frame.
        } finally {
            try { canvas?.let { surface.unlockCanvasAndPost(it) } } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
        }
    }

    private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        val enc = codec ?: return
        val mux = muxer ?: return
        if (endOfStream) {
            try { enc.signalEndOfInputStream() } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
        }
        while (true) {
            val outIndex = try {
                enc.dequeueOutputBuffer(bufferInfo, 0)
            } catch (_: Exception) {
                return
            }
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) return
                    videoTrackIndex = mux.addTrack(enc.outputFormat)
                    mux.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val encodedData = enc.getOutputBuffer(outIndex)
                    if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        try {
                            mux.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
                    }
                    enc.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    /**
     * Stops recording and finalizes the MP4. Suspends briefly on
     * [Dispatchers.Default] to flush the remaining encoded frames before
     * releasing the encoder/muxer, so the output file is always a valid,
     * playable MP4 rather than one missing its final GOP/moov atom.
     */
    suspend fun stop(): File? {
        if (!recording) return null
        recording = false
        // Choreographer.getInstance() is thread-local — the callback was
        // registered on Main (see start()), so it must be removed there too,
        // or this would silently create/touch a *different* Choreographer
        // instance and leave the real callback still firing.
        frameCallback?.let { cb ->
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                Choreographer.getInstance().removeFrameCallback(cb)
            }
        }
        frameCallback = null
        captureJob?.cancel()
        drainJob?.cancel()
        captureJob = null
        drainJob = null

        return kotlinx.coroutines.withContext(Dispatchers.Default) {
            val bufferInfo = MediaCodec.BufferInfo()
            try {
                drainEncoder(bufferInfo, endOfStream = true)
            } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
            val file = outputFile
            try { codec?.stop() } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
            try { codec?.release() } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
            try { inputSurface?.release() } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
            try { if (muxerStarted) muxer?.stop() } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
            try { muxer?.release() } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
            codec = null
            inputSurface = null
            muxer = null
            muxerStarted = false
            outputFile = null
            file
        }
    }

    /**
     * Emergency stop (e.g. ViewModel.onCleared) — best-effort, doesn't
     * return the file. removeFrameCallback here is only a same-thread
     * best-effort (Choreographer is thread-local, and this may run off
     * Main) — harmless either way, since doFrame() itself checks
     * `recording` before ever re-posting, so the callback chain always
     * self-terminates within one frame regardless.
     */
    fun cancel() {
        recording = false
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
        captureJob?.cancel()
        drainJob?.cancel()
        try { codec?.stop() } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
        try { codec?.release() } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
        try { inputSurface?.release() } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
        try { muxer?.release() } catch (e: Exception) { android.util.Log.d("SessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
        codec = null; inputSurface = null; muxer = null; muxerStarted = false; outputFile = null
    }

    private fun estimateBitRate(width: Int, height: Int, fps: Int): Int {
        // Rough "bits per pixel per frame" heuristic common for H.264 at
        // moderate motion (remote-desktop content is mostly static/text,
        // which compresses far better than video, so this errs low).
        // TOOLBOX FEATURE (Stage 15): fps is now the *actual* configured
        // capture rate (device refresh rate) rather than a hardcoded "10"
        // baseline — a 60fps capture needs proportionally more bitrate than
        // a 10fps one to look equally sharp, since H.264 spends its bit
        // budget across however many frames/sec it's actually given.
        val bitsPerPixel = 0.08
        return (width * height * bitsPerPixel * fps).toInt().coerceAtLeast(250_000)
    }

    /**
     * TOOLBOX FEATURE (Stage 15): the device's own measured display refresh
     * rate — "what the user really sees" is bounded by however fast their
     * screen physically redraws, so this is used as the default recording
     * fps instead of an arbitrary fixed number. Clamped to a sane 24-60
     * range: below 24 would look worse than the live session ever did,
     * and above 60 buys no visible improvement for remote-desktop content
     * while meaningfully raising encode cost/file size on the low-end
     * devices this app already targets.
     */
    private fun detectDisplayRefreshRateFps(): Int {
        val rate = try {
            @Suppress("DEPRECATION")
            val display = (appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            display?.refreshRate?.toInt() ?: 30
        } catch (_: Exception) {
            30
        }
        return rate.coerceIn(24, 60)
    }

    private fun pruneRecordings() {
        val dir = recordingsDir
        val files = (dir.listFiles() ?: return)
            .filter { it.isFile && it.name.endsWith(".mp4") }
            .sortedBy { it.lastModified() }
        var toDelete = files.size - maxRecordings + 1
        if (toDelete > 0) files.take(toDelete).forEach { it.delete() }

        val remaining = (dir.listFiles() ?: return)
            .filter { it.isFile && it.name.endsWith(".mp4") }
            .sortedBy { it.lastModified() }
        var totalBytes = remaining.sumOf { it.length() }
        for (f in remaining) {
            if (totalBytes <= maxRecordingsDirBytes) break
            totalBytes -= f.length()
            f.delete()
        }
    }
}
