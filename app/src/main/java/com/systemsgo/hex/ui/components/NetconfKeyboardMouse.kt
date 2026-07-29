package com.systemsgo.hex.ui.components

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * NETCONF-KEYBOARD-MOUSE FEATURE: hardware-keyboard shortcuts and
 * external-mouse right-click context menus for the (native Compose, not a
 * remote framebuffer) NETCONF session UI.
 *
 * This is a deliberately different mechanism from
 * [com.systemsgo.hex.ui.components.hardwareKeyboardInput] — that function
 * forwards raw PC Set-1 scan codes into a *remote* RDP/VNC session's input
 * stream, because on those protocols there is no local text field for
 * Android's normal keyboard/IME handling to attach to. The NETCONF session
 * screen is the opposite: every field is a real Compose `TextField`, so a
 * hardware keyboard's *typing* already works with zero extra code (Compose
 * routes physical-keyboard character input through the same IME pipeline
 * as the soft keyboard). What a hardware keyboard is missing here is
 * desktop-style **shortcut keys** for actions that would otherwise need a
 * touch tap — that's what [netconfKeyboardShortcuts] adds — and a mouse is
 * missing a **right-click context menu**, standard on desktop but not
 * something a touch-first list automatically gets — that's
 * [MouseContextMenuArea].
 */
data class NetconfKeyboardShortcuts(
    val onSwitchTab: (Int) -> Unit = {},
    val onReconnect: () -> Unit = {},
    val onSend: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onFind: () -> Unit = {},
    val onEscape: () -> Unit = {},
)

/**
 * Attaches desktop-style Ctrl-chord shortcuts to the session screen's root
 * node (Samsung DeX / Chromebook / any Bluetooth-or-USB keyboard):
 *
 * - **Ctrl+1..7** — jump directly to that tab (Status/Datastore/RPC Tools/
 *   YANG/Diff/Wire Log/Notifications), the keyboard equivalent of tapping
 *   the [androidx.compose.material3.TabRow].
 * - **Ctrl+R** — reconnect, mirroring the toolbar's reconnect icon.
 * - **Ctrl+Enter** — send the current RPC Builder body (only meaningful
 *   while that tab is active; harmless no-op otherwise since the callback
 *   is wired per-tab by the caller).
 * - **Ctrl+S** — save (the current RPC into the Saved RPC Library).
 * - **Ctrl+F** — focus the active tab's search field (YANG module search).
 * - **Escape** — context-dependent "go back" (YANG module detail → module
 *   list, or dismiss the error banner) — the caller decides what that means
 *   for whichever tab is currently showing.
 *
 * Uses `onPreviewKeyEvent` (not `onKeyEvent`) so a shortcut fires even when
 * a child `TextField` currently has focus — a Ctrl+Enter typed while the
 * RPC Builder text field is focused must still send the RPC rather than
 * only inserting a newline.
 */
fun Modifier.netconfKeyboardShortcuts(shortcuts: NetconfKeyboardShortcuts): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val ctrl = event.isCtrlPressed
    when {
        ctrl && event.key in DIGIT_KEYS -> {
            val tabIndex = DIGIT_KEYS.indexOf(event.key)
            shortcuts.onSwitchTab(tabIndex)
            true
        }
        ctrl && event.key == Key.R -> { shortcuts.onReconnect(); true }
        ctrl && event.key == Key.Enter -> { shortcuts.onSend(); true }
        ctrl && event.key == Key.NumPadEnter -> { shortcuts.onSend(); true }
        ctrl && event.key == Key.S -> { shortcuts.onSave(); true }
        ctrl && event.key == Key.F -> { shortcuts.onFind(); true }
        event.key == Key.Escape -> { shortcuts.onEscape(); true }
        else -> false
    }
}

private val DIGIT_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven)

/**
 * A right-click (physical mouse) context menu for a row/card in a list —
 * touch users keep using long-press if the row already wires one up
 * elsewhere; this only adds the desktop-mouse path on top, following the
 * same `PointerType.Mouse` + `event.buttons.isSecondaryPressed` detection
 * [com.systemsgo.hex.ui.screens.RdpSessionActivity]'s canvas already uses
 * for its own right-click handling, generalized here into a reusable
 * wrapper for ordinary Compose list rows instead of a remote-desktop canvas.
 *
 * Implemented on top of Compose Foundation's built-in [ContextMenuArea],
 * which already renders the right platform-appropriate context-menu popup
 * on right-click / long-press and requires no manual pointer-event
 * plumbing — using the framework primitive here rather than hand-rolling a
 * DropdownMenu keeps the mouse (Foundation-level) and touch (implicit
 * long-press-to-open, same API) behavior consistent for free.
 */
@Composable
fun MouseContextMenuArea(
    items: List<Pair<String, () -> Unit>>,
    content: @Composable () -> Unit,
) {
    ContextMenuArea(
        items = { items.map { (label, action) -> ContextMenuItem(label, action) } },
        content = content,
    )
}
