package com.systemsgo.hex.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.systemsgo.hex.data.model.RdpPerformance

/**
 * BUG-AUTO-QUALITY FIX: single source of truth for turning a live Android
 * network-signal reading into a concrete [RdpPerformance] level.
 *
 * Before this fix, [RdpPerformance.AUTO] ("Auto-detect and adapt") was only
 * ever treated as a synonym for the *best possible* quality:
 * `codecQualityFor(AUTO) == 100` unconditionally, with no relationship to the
 * device's actual current network conditions. A user on a weak connection who
 * picked "Auto" expecting the app to scale quality down instead got the
 * heaviest possible codec settings — the opposite of what the name promises.
 *
 * This object is the one place that decides what "Auto" *actually* resolves
 * to, based on live [ConnectivityManager] signal-strength data:
 *  - [MainViewModel] uses it for the live Network Quality badge shown in the
 *    UI.
 *  - [RdpSessionViewModel] uses it to resolve `AUTO` to a concrete level at
 *    the moment each session actually connects (and on every reconnect), so
 *    "Auto" tracks the network the device is on *right now* rather than
 *    whatever it was when the profile was first created.
 *
 * [recommendedPerformanceLevel] never returns [RdpPerformance.AUTO] itself —
 * that would let a caller which forgot to resolve AUTO silently loop back to
 * the "always maximum quality" bug this class exists to fix. UNKNOWN
 * conditions (e.g. right at app/session startup before the OS has reported
 * capabilities) conservatively fall back to [RdpPerformance.MEDIUM] rather
 * than assuming the network is fast, since under-shooting quality merely
 * wastes a little headroom while over-shooting it causes visible lag/latency.
 */
object NetworkQualityDetector {

    enum class Bucket { UNKNOWN, POOR, FAIR, GOOD, EXCELLENT }

    /** Live-reads the currently active network's capabilities and buckets them. */
    fun currentBucket(context: Context): Bucket {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return Bucket.UNKNOWN
        val caps = try {
            cm.getNetworkCapabilities(cm.activeNetwork)
        } catch (_: Exception) {
            null
        } ?: return Bucket.UNKNOWN
        return bucketFromCapabilities(caps)
    }

    // UX-10 (see MainViewModel history): WiFi/cellular quality is based on actual
    // reported downstream bandwidth, not just transport type, so a weak WiFi
    // signal isn't mistaken for a strong one.
    fun bucketFromCapabilities(caps: NetworkCapabilities): Bucket {
        val bw = caps.linkDownstreamBandwidthKbps
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                // Wired Ethernet: treat as EXCELLENT regardless of reported BW
                // (Android often under-reports BW for Ethernet adapters).
                Bucket.EXCELLENT

            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> when {
                bw > 25_000 -> Bucket.EXCELLENT   // > 25 Mbps — strong WiFi
                bw >  5_000 -> Bucket.GOOD        // > 5 Mbps  — decent WiFi
                bw >  1_000 -> Bucket.FAIR        // > 1 Mbps  — weak WiFi
                else        -> Bucket.POOR        // ≤ 1 Mbps  — very weak signal
            }

            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> when {
                bw > 5_000 -> Bucket.GOOD
                bw > 1_000 -> Bucket.FAIR
                else       -> Bucket.POOR
            }

            else -> Bucket.UNKNOWN
        }
    }

    /**
     * Maps a [Bucket] to a concrete (never AUTO) [RdpPerformance] level.
     */
    fun recommendedPerformanceLevel(bucket: Bucket): Int = when (bucket) {
        Bucket.POOR      -> RdpPerformance.LOW_BANDWIDTH
        Bucket.FAIR      -> RdpPerformance.MEDIUM
        Bucket.GOOD      -> RdpPerformance.WIFI
        Bucket.EXCELLENT -> RdpPerformance.LAN
        // BUG-AUTO-QUALITY FIX: previously an unknown reading resolved back to
        // AUTO itself (a circular non-resolution — see MainViewModel history).
        // MEDIUM is a safe, concrete, network-agnostic default.
        Bucket.UNKNOWN   -> RdpPerformance.MEDIUM
    }

    /**
     * Convenience: live-detects current conditions and returns a concrete
     * [RdpPerformance] level in one call.
     */
    fun detectRecommendedPerformanceLevel(context: Context): Int =
        recommendedPerformanceLevel(currentBucket(context))

    /**
     * Resolves [level] to a concrete, connectable [RdpPerformance] level:
     * returns [level] unchanged unless it is [RdpPerformance.AUTO], in which
     * case it is replaced by a live network-based recommendation. Callers
     * should use this immediately before deriving codec quality / performance
     * flags (see `RdpPerformance.codecQualityFor` / `flagsFor`) so "Auto"
     * always reflects the network the device is on right now.
     *
     * DATA-SAVER FEATURE: when [dataSaverEnabled] is true, this always
     * returns [RdpPerformance.LOW_BANDWIDTH] — overriding [level] entirely,
     * including a fixed (non-AUTO) level the user explicitly chose. Data
     * Saver is a "minimize data no matter how fast the network looks"
     * switch (metered/roaming connections), which is a different axis from
     * AUTO's "adapt to how fast the network currently is" — see
     * AppSettings.dataSaverEnabled's doc comment.
     */
    fun resolve(context: Context, level: Int, dataSaverEnabled: Boolean = false): Int = when {
        dataSaverEnabled          -> RdpPerformance.LOW_BANDWIDTH
        level == RdpPerformance.AUTO -> detectRecommendedPerformanceLevel(context)
        else                      -> level
    }

    // AUTO-COLOR-DEPTH FEATURE: same "resolve at connect time" pattern as
    // [resolve] above, but for the Settings → Connection → Color Depth
    // control. A value of [com.systemsgo.hex.data.model.RdpPerformance.COLOR_DEPTH_AUTO]
    // (0) is never sent to the native bridge as-is — it's replaced right
    // before each connect/reconnect with a concrete depth chosen from the
    // live network bucket, so a user on a weak connection automatically gets
    // a lighter 16-bit stream (less data per frame) while a strong
    // connection gets the full 32-bit depth, without the user having to
    // guess and hand-tune it themselves every time their network changes.
    //
    // DATA-SAVER FEATURE: when [dataSaverEnabled] is true, this always
    // returns 16 — overriding [colorDepth] entirely, including a fixed
    // (non-auto) depth the user explicitly chose — same override semantics
    // as [resolve]'s dataSaverEnabled parameter above.
    fun resolveColorDepth(context: Context, colorDepth: Int, dataSaverEnabled: Boolean = false): Int {
        if (dataSaverEnabled) return 16
        return if (colorDepth == RdpPerformance.COLOR_DEPTH_AUTO) {
            when (currentBucket(context)) {
                Bucket.POOR, Bucket.UNKNOWN -> 16
                Bucket.FAIR                 -> 16
                Bucket.GOOD                 -> 24
                Bucket.EXCELLENT            -> 32
            }
        } else colorDepth
    }
}
