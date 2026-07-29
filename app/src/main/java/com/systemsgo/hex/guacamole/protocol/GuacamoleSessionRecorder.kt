package com.systemsgo.hex.guacamole.protocol

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * GUACAMOLE-PROTOCOL FEATURE.
 *
 * A real caveat worth stating up front: Guacamole's own server-side session
 * recording (guacd's `recording-path` connection parameter) writes the raw
 * instruction stream straight to a `.guac` file on the guacd host — and
 * the standard Guacamole REST API does not expose a way for a client app
 * to *retrieve* those files; that's an admin/filesystem-level concern in
 * stock Guacamole, not something exposed to the account the app logs in
 * as. So "session recording playback" here means something different and
 * more limited than replaying a recording guacd made: this class lets the
 * app record the instruction stream it itself observes during a live
 * session, and play that back later — a LOCAL recording, not a retrieved
 * server-side one. The file format is the real thing, though: [GuacamoleSessionRecorder]
 * writes plain concatenated encoded instructions, byte-for-byte what a
 * `.guac` file is, so a local recording could be handed to Guacamole's own
 * `guacenc`/`guaclog` tools too if that's ever useful.
 */
class GuacamoleSessionRecorder(file: File) {
    private val out = FileOutputStream(file, false)

    /** Appends one instruction's wire encoding to the recording file — call for every server→client instruction, in order, exactly as received. */
    fun record(instruction: GuacamoleInstruction) {
        try {
            out.write(instruction.encode().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            // Best-effort — a recording write failure shouldn't interrupt the live session it's recording.
        }
    }

    fun close() {
        try { out.close() } catch (e: Exception) { android.util.Log.d("GuacamoleSessionRecorder", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
    }
}

enum class GuacamoleRecordingPlaybackState { STOPPED, PLAYING, PAUSED, FINISHED }

/**
 * Reads a recording written by [GuacamoleSessionRecorder] (or any real
 * `.guac` file) and replays it through a fresh, unconnected
 * [GuacamoleDisplayRenderer] at the pace the original `sync` timestamps
 * indicate — a local "video player" for a past session. There's no
 * random-access seeking (only play from the start, pause/resume, stop) —
 * real seeking would need periodically keyframing the layer bitmaps while
 * recording, which isn't implemented; every playback re-parses and
 * re-applies the file from the beginning.
 */
class GuacamoleRecordingPlayer(private val file: File) {
    private val _frameUpdates = MutableSharedFlow<com.systemsgo.hex.remote.RemoteFrameUpdate>(extraBufferCapacity = 8)
    val frameUpdates: SharedFlow<com.systemsgo.hex.remote.RemoteFrameUpdate> = _frameUpdates

    private val _state = MutableStateFlow(GuacamoleRecordingPlaybackState.STOPPED)
    val state: StateFlow<GuacamoleRecordingPlaybackState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    @Volatile private var stopRequested = false
    @Volatile private var paused = false

    /**
     * Parses and replays the whole file from the beginning, pacing frames
     * by the delta between consecutive `sync` timestamps (clamped to avoid
     * a corrupt/huge gap stalling playback for real). Suspends until
     * playback finishes, is stopped, or the coroutine is cancelled — run it
     * in a dedicated coroutine, not on a UI-blocking call path.
     */
    suspend fun play(initialWidth: Int, initialHeight: Int) {
        stopRequested = false
        paused = false
        _state.value = GuacamoleRecordingPlaybackState.PLAYING
        _positionMs.value = 0

        val renderer = GuacamoleDisplayRenderer(initialWidth, initialHeight)
        val decoder = GuacamoleInstructionDecoder()
        var lastSyncTimestamp: Long? = null

        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(8192)
            while (!stopRequested) {
                while (paused && !stopRequested) delay(100)
                if (stopRequested) break
                val read = raf.read(buffer)
                if (read <= 0) break // End of file.
                val chunk = String(buffer, 0, read, Charsets.UTF_8)
                val instructions = try {
                    decoder.feed(chunk)
                } catch (_: GuacamoleProtocolException) {
                    break // Corrupt recording — stop rather than emit garbage frames.
                }
                for (instruction in instructions) {
                    if (instruction.opcode == "sync") {
                        val ts = instruction.args.firstOrNull()?.toLongOrNull()
                        if (ts != null) {
                            lastSyncTimestamp?.let { prev ->
                                val deltaMs = (ts - prev).coerceIn(0, 2000) // Clamp — see class doc.
                                if (deltaMs > 0) delay(deltaMs)
                                _positionMs.value += deltaMs
                            }
                            lastSyncTimestamp = ts
                        }
                        renderer.drainDirtyFrame()?.let { _frameUpdates.tryEmit(it) }
                    } else {
                        renderer.apply(instruction)
                    }
                }
            }
        }
        renderer.dispose()
        _state.value = if (stopRequested) GuacamoleRecordingPlaybackState.STOPPED else GuacamoleRecordingPlaybackState.FINISHED
    }

    fun pause() { paused = true; _state.value = GuacamoleRecordingPlaybackState.PAUSED }
    fun resume() { paused = false; _state.value = GuacamoleRecordingPlaybackState.PLAYING }
    fun stop() { stopRequested = true }
}
