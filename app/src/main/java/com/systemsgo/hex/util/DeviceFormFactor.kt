package com.systemsgo.hex.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * SPLIT-SCREEN / DEX FEATURE: centralised "is this a desktop-class surface"
 * check.
 *
 * The task requirement is explicit: Split Screen (and, eventually, external
 * display features) must be available on tablets, foldables, Samsung DeX,
 * Android Desktop Mode, or "sufficiently large displays" — and must be
 * automatically hidden on small phone screens. There is no single Android
 * API that answers "am I on a desktop-class surface"; this object combines
 * the handful of signals Android actually exposes into one answer so every
 * call site (Split Screen entry point, future Multi-Monitor / DeX work)
 * stays consistent instead of re-deriving its own heuristic.
 *
 * Signals used, in order of reliability:
 *  1. `Configuration.smallestScreenWidthDp >= TABLET_SW_DP` (600dp) — the
 *     same breakpoint Android's own `sw600dp` resource qualifier and Material
 *     window-size-class use to mean "tablet or larger". This also covers an
 *     unfolded foldable, since the OS reports the *unfolded* smallest width.
 *  2. `Configuration.uiMode` masked with `UI_MODE_TYPE_MASK` equal to
 *     `UI_MODE_TYPE_DESK` — the documented, public signal for
 *     "the device is being used in a desktop-style windowing environment".
 *     Samsung DeX and Android Desktop Mode (Android 14/15 freeform desktop)
 *     both report this once the session is actually in desktop mode; relying
 *     on the public Configuration flag (rather than reflection into
 *     `SemDesktopModeState`/similar OEM-private APIs) keeps this correct
 *     across OEMs without brittle reflection.
 *  3. `isInMultiWindowMode` / freeform window mode — a large freeform window
 *     on a desktop surface (DeX app window, Chromebook window) is still a
 *     "desktop-class" context worth allowing split screen inside, even if
 *     the window itself is momentarily narrower than 600dp.
 *
 * None of these alone is perfectly reliable across every OEM skin, which is
 * why they're combined with OR: any one of them being true is treated as
 * "desktop-class". A plain phone in portrait, un-docked, satisfies none of
 * them and correctly gets `false`.
 */
object DeviceFormFactor {

    private const val TABLET_SW_DP = 600

    /** True when the current window is at least tablet-sized (sw600dp breakpoint). */
    fun isLargeScreen(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= TABLET_SW_DP

    /**
     * True when Android reports the device/session as running in a desktop
     * windowing environment — covers Samsung DeX and Android Desktop Mode.
     * `UI_MODE_TYPE_DESK` is the public constant for a desktop-style dock/windowing mode;
     * report it, which is fine since DeX/Desktop Mode themselves require
     * newer Android versions in practice.
     */
    fun isDesktopMode(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val uiMode = context.resources.configuration.uiMode
        val type = uiMode and Configuration.UI_MODE_TYPE_MASK
        if (type == Configuration.UI_MODE_TYPE_DESK) return true

        // Some Samsung firmware still reports UI_MODE_TYPE_NORMAL while in a
        // DeX session but exposes it through UiModeManager's current mode type
        // instead — check that too as a second, still-public, source.
        return try {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_DESK
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True when this Activity/window is currently a large freeform window
     * (a resizable desktop window on DeX/Desktop Mode/Chromebook), as
     * opposed to full-screen on a phone. Falls back to `false` pre-API 24
     * where multi-window doesn't exist.
     */
    fun isFreeformWindow(context: Context): Boolean {
        val activity = context.findActivity() ?: return false
        return try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The single source of truth used to gate Split Screen (and future
     * desktop-class features): true on tablets, unfolded foldables, Samsung
     * DeX, Android Desktop Mode, or any sufficiently large / freeform
     * window; false on an ordinary phone screen.
     */
    fun supportsDesktopFeatures(context: Context): Boolean =
        isLargeScreen(context) || isDesktopMode(context) || isFreeformWindow(context)

    private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
