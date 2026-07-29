package com.systemsgo.hex.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EXTERNAL-DISPLAY FEATURE
 *
 * Lightweight, injectable wrapper around [DisplayManager] that:
 *  1. Tracks every currently-connected display *other than* the device's own
 *     built-in screen (Samsung DeX monitor, an HDMI/USB-C dock, a wireless
 *     display, a foldable's second physical panel exposed as its own
 *     [Display], etc.) as a simple, Compose-friendly [ExternalDisplayInfo] list.
 *  2. Exposes [isDesktopModeActive], a cross-vendor way to tell whether the
 *     device is *currently* running in a desktop-style windowing mode
 *     (Samsung DeX or the AOSP/Android "Desktop Mode" introduced for large
 *     screens) — delegates to the existing [com.systemsgo.hex.util.DeviceFormFactor]
 *     check rather than re-deriving its own heuristic.
 *
 * This class only *detects* displays/mode — it has no knowledge of any
 * particular session or Activity, so it is safe to keep as a single
 * `@Singleton` for the whole app (mirrors [com.systemsgo.hex.session.SessionTabManager]).
 * Callers (currently only [com.systemsgo.hex.ui.screens.RdpSessionActivity]) are
 * responsible for calling [startListening]/[stopListening] from their own
 * onStart()/onStop() so the DisplayManager callback isn't kept alive longer
 * than something is actually observing it.
 */
data class ExternalDisplayInfo(
    val displayId: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int
) {
    /** Aspect ratio, used to size the [RdpPresentation] content sensibly. */
    val aspectRatio: Float
        get() = if (heightPx > 0) widthPx.toFloat() / heightPx.toFloat() else 16f / 9f
}

@Singleton
class ExternalDisplayManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val displayManager: DisplayManager
        get() = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val _externalDisplays = MutableStateFlow<List<ExternalDisplayInfo>>(emptyList())
    /** Every display other than [Display.DEFAULT_DISPLAY], refreshed live. */
    val externalDisplays: StateFlow<List<ExternalDisplayInfo>> = _externalDisplays.asStateFlow()

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int)   = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    // Reference-counted rather than a plain boolean: multiple RdpSessionActivity
    // tab instances (Feature-05) can each be started/stopped independently, and
    // this single shared listener must stay registered as long as *any* of them
    // is in the foreground, not just the most recent caller.
    private var listenerRefCount = 0

    /** Call from the hosting Activity's onStart(). Safe to call from multiple tabs. */
    @Synchronized
    fun startListening() {
        if (listenerRefCount == 0) {
            displayManager.registerDisplayListener(listener, null)
            refresh()
        }
        listenerRefCount++
    }

    /** Call from the hosting Activity's onStop(). Safe to call from multiple tabs. */
    @Synchronized
    fun stopListening() {
        if (listenerRefCount == 0) return
        listenerRefCount--
        if (listenerRefCount == 0) {
            displayManager.unregisterDisplayListener(listener)
        }
    }

    private fun refresh() {
        // DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED isn't used on purpose: a
        // disabled/mirroring-only display can't actually host a Presentation
        // usefully, so we only surface displays the system reports as usable.
        _externalDisplays.value = displayManager.displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .map { display ->
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                ExternalDisplayInfo(
                    displayId  = display.displayId,
                    name       = display.name ?: "Display ${display.displayId}",
                    widthPx    = display.mode?.physicalWidth  ?: metrics.widthPixels,
                    heightPx   = display.mode?.physicalHeight ?: metrics.heightPixels,
                    densityDpi = metrics.densityDpi
                )
            }
    }

    /**
     * True when the device is currently presenting itself in a desktop-style
     * windowing mode — Samsung DeX (docked or wireless) or Android's own
     * large-screen "Desktop Mode" — as opposed to normal handheld/tablet UI.
     *
     * MERGE NOTE: delegates to [com.systemsgo.hex.util.DeviceFormFactor.isDesktopMode],
     * which already existed in this codebase (added for the Split-Screen
     * feature) and checks the correct, newer `UI_MODE_TYPE_DESKTOP` constant
     * (API 29+, the actual DeX/Desktop-Mode windowing signal) via both
     * `Configuration.uiMode` and `UiModeManager.currentModeType`. The
     * original version of this method checked `UI_MODE_TYPE_DESK` instead,
     * which is a different, older constant (car/desk dock accessories, not
     * desktop windowing) and would have under- or mis-detected DeX/Desktop
     * Mode. Reusing the existing utility also keeps exactly one source of
     * truth for this check across the app.
     */
    fun isDesktopModeActive(): Boolean =
        com.systemsgo.hex.util.DeviceFormFactor.isDesktopMode(context)

    /**
     * Best-effort, purely informational flag: true if the device *supports*
     * Samsung DeX at all (whether or not it's active right now). Used only to
     * word the "move to display" affordance a little more specifically on
     * Samsung hardware; every code path also works correctly on devices where
     * this returns false, since it never gates functionality — only wording.
     */
    fun isDeXCapableDevice(): Boolean =
        context.packageManager.hasSystemFeature("com.samsung.feature.desktopmode")

    /** Look up one previously-seen display by id, e.g. to resolve a saved choice. */
    fun findDisplay(displayId: Int): Display? =
        displayManager.getDisplay(displayId)
}
