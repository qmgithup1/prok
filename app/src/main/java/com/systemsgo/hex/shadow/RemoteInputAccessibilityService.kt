package com.systemsgo.hex.shadow

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.systemsgo.hex.rdp.native.AFreeRdpServerBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * SHADOW-SERVER FEATURE: the input-injection half of "Shadow Server" (see
 * `systemsgo_server_jni.c`'s and `AFreeRdpServerBridge.kt`'s SHADOW-SERVER
 * FEATURE comments — [ShadowScreenCaptureService] is the video half).
 * Subscribes to [AFreeRdpServerBridge.peerMouseEvents]/[peerKeyEvents] on
 * whichever bridge instance [ShadowScreenCaptureService] currently has
 * running, and turns them into:
 *   - Mouse move/down/up → a real touch gesture via [dispatchGesture]
 *     (continued-stroke API, so a drag/press-hold survives across multiple
 *     MouseEvent callbacks rather than being one instantaneous tap).
 *   - Keyboard scancodes → edits to whatever [AccessibilityNodeInfo] is
 *     currently focused for text input, via ACTION_SET_TEXT — see
 *     [ScancodeKeyMap] below for exactly which keys are supported.
 *
 * This is an OPT-IN system Accessibility Service — the user must
 * explicitly enable "Remote Input (SystemsGo)" in Android Settings ▸
 * Accessibility (see [accessibility_service_config] and
 * `res/xml/accessibility_service_config.xml`) before any of this runs.
 * With the service disabled, Shadow Server still works as view-only:
 * [ShadowScreenCaptureService] does not depend on this class at all.
 *
 * NOT AN EXHAUSTIVE KEYBOARD: only common US-QWERTY printable characters,
 * Enter, Backspace, Space and Tab are handled (see [ScancodeKeyMap]) —
 * IME/non-Latin input, dead keys, and most non-printable keys (function
 * keys, media keys, etc.) are not — matches the same "not yet split into
 * its own path" caveat `systemsgo_server_input_unicode` already documents on
 * the native side.
 */
class RemoteInputAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteInputA11y"

        /** MS-RDPBCGR / FreeRDP pointer.h PTR_FLAGS_* bits, as delivered
         * verbatim through AFreeRdpServerBridge.PeerMouseEvent.flags. */
        private const val PTR_FLAGS_MOVE = 0x0800
        private const val PTR_FLAGS_DOWN = 0x8000
        private const val PTR_FLAGS_BUTTON1 = 0x1000 // left button

        private const val GESTURE_STROKE_TICK_MS = 40L // how long each continued-stroke segment claims to last
    }

    private var scope: CoroutineScope? = null
    private var jobs: Job? = null

    /** Tracks an in-progress left-button press so mouse-move events between
     * down and up become one continued gesture stroke instead of separate
     * unrelated taps. Null when the button is up. */
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var shiftDown = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "RemoteInputAccessibilityService connected")
        val bridge = ShadowScreenCaptureService.activeBridge
        if (bridge == null) {
            // Nothing to subscribe to yet — ShadowScreenCaptureService will
            // set ShadowScreenCaptureService.activeBridge before it starts
            // pushing frames; if this service is enabled AFTER Shadow
            // Server is already running, the user needs to restart sharing
            // once so this service can pick up the (new) bridge instance.
            // Kept simple deliberately: no cross-process/static-flow
            // re-subscription plumbing for what is expected to be a rare
            // ordering in practice (enabling accessibility once, up front).
            Log.w(TAG, "No active Shadow Server bridge yet — input injection idle until sharing (re)starts")
        }
        wireBridge(bridge)
    }

    private fun wireBridge(bridge: AFreeRdpServerBridge?) {
        jobs?.cancel()
        if (bridge == null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        jobs = s.launch {
            launch {
                bridge.peerMouseEvents.collect { event ->
                    try {
                        handleMouseEvent(event.flags, event.x, event.y)
                    } catch (e: Exception) {
                        Log.w(TAG, "handleMouseEvent failed", e)
                    }
                }
            }
            launch {
                bridge.peerKeyEvents.collect { event ->
                    try {
                        handleKeyEvent(event.scancode, event.isDown)
                    } catch (e: Exception) {
                        Log.w(TAG, "handleKeyEvent failed", e)
                    }
                }
            }
        }
    }

    // ── Mouse → touch gesture ───────────────────────────────────────────

    private fun handleMouseEvent(flags: Int, x: Int, y: Int) {
        val isMove = (flags and PTR_FLAGS_MOVE) != 0
        val isDown = (flags and PTR_FLAGS_DOWN) != 0
        val isButton1 = (flags and PTR_FLAGS_BUTTON1) != 0

        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

        when {
            isButton1 && isDown && activeStroke == null -> {
                // Press start: dispatch a stroke that says "more to come".
                val stroke = GestureDescription.StrokeDescription(
                    path, 0, GESTURE_STROKE_TICK_MS, true,
                )
                activeStroke = stroke
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            }
            isMove && activeStroke != null -> {
                // Continue the existing press as a drag to the new point.
                val continued = activeStroke!!.continueStroke(
                    path, 0, GESTURE_STROKE_TICK_MS, true,
                )
                activeStroke = continued
                dispatchGesture(GestureDescription.Builder().addStroke(continued).build(), null, null)
            }
            isButton1 && !isDown && activeStroke != null -> {
                // Release: finish the stroke (willContinue = false).
                val finished = activeStroke!!.continueStroke(
                    path, 0, GESTURE_STROKE_TICK_MS, false,
                )
                dispatchGesture(GestureDescription.Builder().addStroke(finished).build(), null, null)
                activeStroke = null
            }
            isMove && activeStroke == null -> {
                // Plain hover-move with no button held: nothing to inject —
                // Android's touch model has no "hover pointer" equivalent
                // for a generic app the way a mouse cursor does. Ignored,
                // matching the milestone-1 comment that the pointer image
                // itself is not reproduced either.
            }
        }
    }

    // ── Keyboard → focused-field text edits ─────────────────────────────

    private fun handleKeyEvent(scancode: Int, isDown: Boolean) {
        if (scancode == ScancodeKeyMap.SHIFT_LEFT || scancode == ScancodeKeyMap.SHIFT_RIGHT) {
            shiftDown = isDown
            return
        }
        if (!isDown) return // only act on key-down; key-up only matters for modifiers above

        when (scancode) {
            ScancodeKeyMap.BACKSPACE -> editFocusedText { text, selStart, selEnd ->
                if (selStart <= 0) return@editFocusedText null
                val newSelStart = selStart - 1
                text.removeRange(newSelStart, selStart) to newSelStart
            }
            ScancodeKeyMap.ENTER -> {
                // Best-effort only: ask the field to perform its IME action
                // (e.g. "Done"/"Go") rather than literally inserting "\n",
                // since most single-line RDP-shared fields expect submit.
                val node = focusedEditableNode()
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            else -> {
                val ch = ScancodeKeyMap.toChar(scancode, shiftDown) ?: return
                editFocusedText { text, selStart, _ ->
                    val newText = StringBuilder(text).insert(selStart, ch).toString()
                    newText to (selStart + 1)
                }
            }
        }
    }

    private fun focusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focused != null && focused.isEditable) focused else null
    }

    /** [transform] receives (currentText, selectionStart, selectionEnd) and
     * returns (newText, newCursorPosition), or null to do nothing. */
    private inline fun editFocusedText(transform: (String, Int, Int) -> Pair<String, Int>?) {
        val node = focusedEditableNode() ?: return
        val current = node.text?.toString() ?: ""
        val selStart = node.textSelectionStart.coerceIn(0, current.length)
        val selEnd = node.textSelectionEnd.coerceIn(0, current.length)
        val result = transform(current, selStart, selEnd) ?: return
        val (newText, newCursor) = result

        val setTextArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)

        val setSelectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, setSelectionArgs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No behavior needed on generic accessibility events — this service
        // only exists for its gesture-dispatch/text-edit capability, not to
        // observe the tree proactively. android:canRetrieveWindowContent
        // (accessibility_service_config.xml) is still required for
        // rootInActiveWindow/findFocus above to return real nodes.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        jobs?.cancel()
        scope?.cancel()
        super.onDestroy()
    }
}
