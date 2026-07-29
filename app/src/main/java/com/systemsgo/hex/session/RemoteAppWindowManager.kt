package com.systemsgo.hex.session

import android.graphics.Rect
import android.util.Log
import com.systemsgo.hex.data.model.RailWindow
import com.systemsgo.hex.data.model.RemoteAppDisplayMode
import com.systemsgo.hex.rdp.native.AFreeRdpBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

private const val TAG = "RemoteAppWindowManager"

/**
 * REMOTEAPP-WINDOWS FEATURE: per-session tracker of every open RAIL
 * (RemoteApp, MS-RDPERP) window, and the user's current single-vs-multi
 * window display choice for that session.
 *
 * One instance per connected RDP session — owned by
 * [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter] (mirrors how
 * [com.systemsgo.hex.remote.clipboard.ClipboardSyncManager] is one-per-session,
 * *not* a Hilt @Singleton like [SessionTabManager], which tracks tabs across
 * every session at once). RdpSessionActivity reads [windows] / [displayMode]
 * to decide what to draw:
 *  - [RemoteAppDisplayMode.SINGLE_WINDOW]: crop+draw only [activeWindow]
 *    full-screen, no local title bar, no desktop background.
 *  - [RemoteAppDisplayMode.MULTI_WINDOW]: draw a horizontal switcher of every
 *    entry in [windows] (see Components.kt's RemoteAppDisplayModePicker for
 *    the *mode* toggle itself — the switcher that lists individual open
 *    windows once MULTI_WINDOW is picked is a separate, still-to-build
 *    piece of RdpSessionActivity UI).
 *
 * NATIVE DEPENDENCY: [windows] is populated once systemsgo_jni.c parses Window
 * State Order PDUs on the "rail" static channel and calls
 * [AFreeRdpBridge.onNativeRailWindowState] / [AFreeRdpBridge.onNativeRailWindowDelete]
 * — that channel loads automatically whenever RemoteApp mode is requested
 * and the server supports MS-RDPERP (see systemsgo_pre_connect() in
 * systemsgo_jni.c); a server without RAIL support simply never sends window
 * orders and [windows] stays empty. Icons ([RailWindow.icon]) and local
 * drag/resize ([moveWindow]) depend on the same channel plus, for icons,
 * systemsgo_rail_window_icon()/systemsgo_rail_window_cached_icon() specifically.
 */
class RemoteAppWindowManager(
    private val bridge: AFreeRdpBridge,
    initialDisplayMode: RemoteAppDisplayMode,
    scope: CoroutineScope,
) {
    private val _windows = MutableStateFlow<List<RailWindow>>(emptyList())
    /** Every window currently known for this session, most recently
     *  activated / highest z-order first. Includes minimized/hidden windows
     *  (see [RailWindow.isVisible]) — filter those out at the UI layer if a
     *  given surface (e.g. the single-window renderer) should ignore them. */
    val windows: StateFlow<List<RailWindow>> = _windows.asStateFlow()

    private val _displayMode = MutableStateFlow(initialDisplayMode)
    /** Current single-vs-multi window presentation choice. Starts from the
     *  profile's saved [com.systemsgo.hex.data.model.RdpProfile.remoteAppDisplayMode]
     *  but can be changed for the rest of this session via [setDisplayMode]
     *  without reconnecting — this is a pure local rendering choice, the
     *  server is never told which mode is active (see
     *  [RemoteAppDisplayMode]'s doc comment). */
    val displayMode: StateFlow<RemoteAppDisplayMode> = _displayMode.asStateFlow()

    private val _activeWindowId = MutableStateFlow<Int?>(null)
    /** windowId of the window SINGLE_WINDOW mode should render full-screen,
     *  and the one MULTI_WINDOW's switcher should show as highlighted/
     *  selected. Defaults to whichever window most recently reported the
     *  highest z-order (see [handleWindowState]); [activateWindow] lets the
     *  user override that explicitly from the switcher. */
    val activeWindowId: StateFlow<Int?> = _activeWindowId.asStateFlow()

    /** Convenience accessor for the window SINGLE_WINDOW mode should
     *  currently draw, or null if no window has been reported yet (nothing
     *  to show but a loading/blank state). */
    val activeWindow: RailWindow?
        get() = _activeWindowId.value?.let { id -> _windows.value.firstOrNull { it.windowId == id } }

    private val listenerJobs: List<Job> = listOf(
        bridge.railWindowUpdates.onEach(::handleWindowState).launchIn(scope),
        bridge.railWindowRemovals.onEach(::handleWindowDelete).launchIn(scope),
        bridge.railWindowIcons.onEach(::handleWindowIcon).launchIn(scope),
    )

    /** Switches this session between single- and multi-window presentation.
     *  Does not touch [windows] or [activeWindowId] — a window that was
     *  active before the switch stays active after it. */
    fun setDisplayMode(mode: RemoteAppDisplayMode) {
        _displayMode.value = mode
    }

    /** Explicitly selects which window is "active" — called when the user
     *  taps a tile in the multi-window switcher, or switches back to
     *  SINGLE_WINDOW mode and needs to pick which of several open windows
     *  to show full-screen. No-op if [windowId] isn't currently tracked. */
    fun activateWindow(windowId: Int) {
        if (_windows.value.none { it.windowId == windowId }) {
            Log.w(TAG, "activateWindow($windowId) — unknown window id, ignoring")
            return
        }
        _activeWindowId.value = windowId
    }

    /** REMOTEAPP-WINDOWS FEATURE: local drag/resize, client -> server half.
     *  Called once a local drag/resize gesture on [windowId]'s tile/surface
     *  ends (see [com.systemsgo.hex.rdp.native.AFreeRdpBridge.sendRailWindowMove]
     *  for the wire-level details) with the window's new on-screen [rect].
     *  Updates the local [windows] entry immediately (optimistic — matches
     *  what a user expects to see the instant they let go) rather than
     *  waiting for the server's own Window State Order echo, which
     *  [handleWindowState] will still merge in normally when it arrives. */
    fun moveWindow(windowId: Int, rect: Rect) {
        if (_windows.value.none { it.windowId == windowId }) {
            Log.w(TAG, "moveWindow($windowId) — unknown window id, ignoring")
            return
        }
        bridge.sendRailWindowMove(windowId, rect.left, rect.top, rect.right, rect.bottom)
        _windows.update { current ->
            current.map { if (it.windowId == windowId) it.copy(rect = rect) else it }
        }
    }

    private fun handleWindowIcon(update: AFreeRdpBridge.NativeRailWindowIcon) {
        _windows.update { current ->
            current.map { if (it.windowId == update.windowId) it.copy(icon = update.icon) else it }
        }
    }

    private fun handleWindowState(update: AFreeRdpBridge.NativeRailWindow) {
        val incoming = RailWindow(
            windowId = update.windowId,
            title = update.title,
            rect = Rect(update.x, update.y, update.x + update.width, update.y + update.height),
            zOrder = update.zOrder,
            isVisible = update.isVisible,
        )
        _windows.update { current ->
            val withoutThis = current.filterNot { it.windowId == incoming.windowId }
            (withoutThis + incoming).sortedByDescending { it.zOrder }
        }
        // First window ever reported (or the highest z-order window if this
        // update just took the top spot) becomes active automatically, so
        // SINGLE_WINDOW mode has something to render without the user
        // needing to interact with a switcher for the common one-window
        // case. Later windows never steal activation from an existing
        // explicit choice — only activateWindow() does that once at least
        // one window has been active.
        if (_activeWindowId.value == null) {
            _activeWindowId.value = incoming.windowId
        }
    }

    private fun handleWindowDelete(windowId: Int) {
        _windows.update { current -> current.filterNot { it.windowId == windowId } }
        if (_activeWindowId.value == windowId) {
            _activeWindowId.value = _windows.value.maxByOrNull { it.zOrder }?.windowId
        }
    }
}
