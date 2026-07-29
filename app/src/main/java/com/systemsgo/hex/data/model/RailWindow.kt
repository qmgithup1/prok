package com.systemsgo.hex.data.model

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * REMOTEAPP-WINDOWS FEATURE: one open window inside a RemoteApp (RAIL,
 * MS-RDPERP) session, as reported by the server's Window State Order PDUs on
 * the "rail" virtual channel.
 *
 * Populated from [com.systemsgo.hex.rdp.native.AFreeRdpBridge]'s
 * `railWindowUpdates`/`railWindowRemovals` flows (native → Kotlin, via
 * `onNativeRailWindowState`/`onNativeRailWindowDelete`) and held by
 * [com.systemsgo.hex.session.RemoteAppWindowManager]. This is intentionally a
 * plain data holder with no behavior — RemoteAppWindowManager owns the
 * add/update/remove/z-order logic, RdpSessionActivity owns how a given
 * [RailWindow] is actually drawn (full-screen crop in
 * [RemoteAppDisplayMode.SINGLE_WINDOW], one tile among several in
 * [RemoteAppDisplayMode.MULTI_WINDOW]).
 *
 * NATIVE SCOPE NOTE: [rect] and [title] both come from the RAIL window-order
 * PDU, not from re-parsing the primary frame buffer — see systemsgo_jni.c's
 * REMOTEAPP FIX comment for why the frame buffer alone (what
 * `AFreeRdpBridge.frames` already delivers) is not enough to tell several
 * open app windows apart: it is one flat bitmap of the whole composited RAIL
 * desktop, with no per-window boundaries of its own. [rect] is exactly the
 * crop of that shared bitmap this window currently occupies.
 */
data class RailWindow(
    /** Server-assigned window ID (MS-RDPERP `WindowId`) — stable for the
     *  lifetime of this window, used to target activate/close/move requests
     *  back at the server (see RemoteAppWindowManager.activate/close). */
    val windowId: Int,

    /** Window title as sent by the server (RAIL_UNICODE_STRING `TitleInfo`).
     *  Shown as the label under each icon in the multi-window switcher and,
     *  in single-window mode, nowhere (no local title bar is drawn — see
     *  RemoteAppDisplayMode.SINGLE_WINDOW's doc comment). */
    val title: String,

    /** This window's current position and size within the shared RAIL
     *  desktop surface that `AFreeRdpBridge.frames` delivers — i.e. the
     *  region of that bitmap that is this window. Updated on every Window
     *  State Order PDU that changes position/size (server-driven moves,
     *  e.g. the remote app maximizing itself), and optimistically on a
     *  local drag/resize via
     *  [com.systemsgo.hex.session.RemoteAppWindowManager.moveWindow], which
     *  also sends the corresponding client → server WindowMove request. */
    val rect: Rect,

    /** Server-assigned stacking order — lower is further back. Used by the
     *  multi-window switcher to lay out icons in a stable, predictable
     *  order (most-recently-activated / highest z-order first) rather than
     *  by arbitrary window-creation order. */
    val zOrder: Int,

    /** True once this window has actually received at least one Window
     *  State Order PDU with the "visible" bit set. A window can exist
     *  (created) but be minimized/hidden by the remote app itself — such
     *  windows are kept in RemoteAppWindowManager.windows (so e.g. a
     *  minimized window still shows a dimmed icon) but excluded from
     *  SINGLE_WINDOW mode's "active surface" selection. */
    val isVisible: Boolean = true,

    /** Small icon bitmap for this window (MS-RDPERP Window Icon / Cached
     *  Icon orders), used as the switcher tile's icon. Null until the first
     *  icon order for this window arrives — the switcher falls back to a
     *  generic "window" glyph until then. Decoded natively (systemsgo_jni.c's
     *  systemsgo_rail_window_icon/systemsgo_rail_window_cached_icon) and merged in
     *  by [com.systemsgo.hex.session.RemoteAppWindowManager]; not every
     *  server sends an icon for every window, so this can stay null for the
     *  lifetime of a window even once connected. */
    val icon: Bitmap? = null,
)
