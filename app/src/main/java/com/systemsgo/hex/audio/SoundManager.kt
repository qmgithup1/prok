package com.systemsgo.hex.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import com.systemsgo.hex.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Space-themed sound manager using SoundPool for low-latency sfx.
 * Sounds are galaxy/warp themed (generated WAV files in res/raw/).
 */
@Singleton
class SoundManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class Sound {
        TAP,        // holographic interface tap
        TOGGLE,     // sci-fi switch
        SWIPE,      // whoosh navigation
        SUCCESS,    // tri-tone mission accomplished
        ERROR,      // dissonant failure buzz
        CONNECT,    // cinematic 2.4s galaxy warp connection
    }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val soundMap    = mutableMapOf<Sound, Int>()
    // FIX #6: track each sound ID individually so play() can guard against
    // sounds that haven't finished loading yet (e.g. rapid tap right after launch).
    // CONNECT-SOUND-RACE FIX: switched to a thread-safe set — it's now read/written
    // from both the SoundPool load-complete callback thread and the retry
    // coroutine below, whereas before it was only ever touched from the
    // callback thread.
    private val loadedIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private var enabled     = true
    @Volatile private var released = false  // BUG-10 FIX: guard against play() after release()

    // CONNECT-SOUND-RACE FIX: backs the short grace-period retry in play() below.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // FIX #6: add the completed sound's sampleId to loadedIds instead of
        // setting a single boolean on the first completion (which previously
        // allowed play() on the other 5 sounds that were still loading).
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedIds.add(sampleId)
        }
        soundMap[Sound.TAP]     = pool.load(context, R.raw.sfx_tap,     1)
        soundMap[Sound.TOGGLE]  = pool.load(context, R.raw.sfx_toggle,  1)
        soundMap[Sound.SWIPE]   = pool.load(context, R.raw.sfx_swipe,   1)
        soundMap[Sound.SUCCESS] = pool.load(context, R.raw.sfx_success, 1)
        soundMap[Sound.ERROR]   = pool.load(context, R.raw.sfx_error,   1)
        soundMap[Sound.CONNECT] = pool.load(context, R.raw.sfx_connect, 1)
    }

    fun play(sound: Sound, volume: Float = 1f, force: Boolean = false) {
        // UX FIX: `force` lets a caller play a confirmation sound even while
        // `enabled` is currently false — needed for the "Sound effects" toggle
        // itself, so tapping it to turn sound ON actually produces an audible
        // click right away instead of staying silent because the flag hadn't
        // flipped yet at the moment of the tap.
        if (released) return
        if (!force && !enabled) return
        val id = soundMap[sound] ?: return
        val vol = volume.coerceIn(0f, 1f)

        if (id in loadedIds) {
            pool.play(id, vol, vol, 1, 0, 1f)
            return
        }

        // CONNECT-SOUND-RACE FIX: previously this just returned here, silently
        // dropping the sound forever if it hadn't finished decoding yet. That
        // was mostly harmless for the short clicks (tap/toggle/swipe are only
        // 10-20KB and finish loading almost immediately), but sfx_connect.wav
        // is a 2.4s/~210KB "cinematic warp" clip — noticeably slower to decode
        // — so a fast connection (LAN, quick reconnect, or the very first
        // connect right after a cold app launch) could reach
        // SessionUiState.Connected and call play(CONNECT, ...) before it had
        // finished loading, permanently losing that one chime for the session
        // while every other sound worked fine. Give it a short grace period to
        // finish loading instead of dropping it immediately.
        scope.launch {
            var waitedMs = 0L
            while (waitedMs < LOAD_WAIT_TIMEOUT_MS && id !in loadedIds) {
                delay(LOAD_POLL_INTERVAL_MS)
                waitedMs += LOAD_POLL_INTERVAL_MS
            }
            if (released) return@launch
            if (id in loadedIds && (force || enabled)) {
                pool.play(id, vol, vol, 1, 0, 1f)
            }
        }
    }

    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
    }

    fun release() {
        released = true  // BUG-10 FIX: set before pool.release() to prevent race
        scope.cancel()
        pool.release()
    }

    companion object {
        // CONNECT-SOUND-RACE FIX: generous enough for sfx_connect.wav's decode
        // time on a slow/first-run device without ever noticeably delaying the
        // sound relative to the connection itself.
        private const val LOAD_WAIT_TIMEOUT_MS = 3000L
        private const val LOAD_POLL_INTERVAL_MS = 30L
    }
}
