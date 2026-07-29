package com.systemsgo.hex.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.systemsgo.hex.util.HardwareKeyMap

/**
 * EXTERNAL-DISPLAY / DEX FEATURE
 *
 * Forwards physical-keyboard key events (Bluetooth keyboard, USB keyboard, a
 * DeX/Desktop-Mode dock's attached keyboard, etc.) reaching this node to
 * [onScanCode] as PC Set-1 scan codes via [HardwareKeyMap] — the same scan
 * codes the on-screen [com.systemsgo.hex.ui.screens.ExtraKeysBar] already sends,
 * so this is purely an additional input source into the exact same
 * `sendKeyEvent(scanCode, down, extended)` plumbing.
 *
 * This never touches the soft/IME keyboard path: Compose only routes key
 * events from a physical device through onKeyEvent/onPreviewKeyEvent, never
 * IME composition — text typed via the on-screen keyboard is completely
 * unaffected by adding this modifier.
 *
 * The node needs to be focusable and focused to actually receive key events
 * (standard Compose behavior), so this also requests focus once when first
 * composed — matching how a desktop app grabs keyboard focus for its main
 * viewport on window show. Tapping/clicking into the canvas (existing pointer
 * input) will also (re)grab focus, so a rejected initial request here is
 * harmless.
 */
fun Modifier.hardwareKeyboardInput(
    enabled: Boolean = true,
    onScanCode: (scanCode: Int, down: Boolean, extended: Boolean) -> Unit
): Modifier = composed {
    if (!enabled) return@composed this

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    this
        .focusRequester(focusRequester)
        .focusable()
        .onKeyEvent { event ->
            if (HardwareKeyMap.isReservedForSystem(event.nativeKeyEvent.keyCode)) {
                return@onKeyEvent false
            }
            val mapped = HardwareKeyMap.toScanCode(event.key.nativeKeyCode) ?: return@onKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> { onScanCode(mapped.code, true, mapped.extended); true }
                KeyEventType.KeyUp   -> { onScanCode(mapped.code, false, mapped.extended); true }
                else -> false
            }
        }
}
