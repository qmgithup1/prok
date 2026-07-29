package com.systemsgo.hex.display

/**
 * MULTI-MONITOR FEATURE.
 *
 * Describes one monitor made available to a remote session, mirroring the
 * model classic desktop RDP clients (mstsc's "Use all my monitors") use:
 * *this client* declares a set of monitors — normally the Android device's
 * own display plus any external/DeX displays currently attached — and the
 * server (if it advertises MS-RDPBCGR/RDPEDISP multi-monitor support) spans
 * or maps its desktop across them. A server that doesn't support multi-mon
 * simply ignores everything beyond the first entry, which is why every
 * consumer of this type must treat monitors.size <= 1 as "unsupported" and
 * hide the whole feature (see [com.systemsgo.hex.remote.RemoteSessionClient.monitors]).
 *
 * [id] is a stable 0-based index matching the order sent to
 * FreeRDP_MonitorDefArray (see systemsgo_jni.c's systemsgo_pre_connect) — NOT a
 * server-assigned identifier, since the server never hands one back before
 * a monitor layout is negotiated.
 */
data class RemoteMonitor(
    val id: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val isPrimary: Boolean,
    /** 0, 90, 180, or 270 — matches MS-RDPEDISP's MONITOR_LAYOUT.Orientation. */
    val orientationDegrees: Int = 0,
    /** DPI scale factor as a percentage (100 = 1.0x), matches MONITOR_LAYOUT.DesktopScaleFactor. */
    val dpiScaleFactor: Int = 100,
) {
    val aspectRatio: Float get() = if (height != 0) width.toFloat() / height.toFloat() else 1f
}

/**
 * What the user currently wants displayed: either one specific monitor, or
 * every declared monitor spanned together ("All Monitors" mode from the
 * requirements). Stored per-connection (see
 * [com.systemsgo.hex.data.model.RdpProfile.preferredMonitorId]) so a saved
 * connection reopens showing whatever the user last picked.
 */
sealed class MonitorSelection {
    data class Single(val monitorId: Int) : MonitorSelection()
    object All : MonitorSelection()

    companion object {
        /** Sentinel stored in [com.systemsgo.hex.data.model.RdpProfile.preferredMonitorId]. */
        const val ALL_MONITORS_ID = -1

        fun fromStoredId(id: Int): MonitorSelection =
            if (id == ALL_MONITORS_ID) All else Single(id)
    }

    fun toStoredId(): Int = when (this) {
        is Single -> monitorId
        All -> ALL_MONITORS_ID
    }
}
