package com.systemsgo.hex.ui.screens

/**
 * TOOLBOX FEATURE (Stage 6a) — "وضع الماوس/تاتش باد": how a single finger's
 * touch position is translated into remote mouse movement in [RdpCanvas].
 *
 * - [TOUCHPAD]: the pre-existing behaviour (BUG-M4/touchpadSensitivity era).
 *   The finger acts like a laptop touchpad — only the *delta* between
 *   consecutive touch points moves the remote cursor, scaled by
 *   `touchpadSensitivity`. The remote cursor can therefore sit anywhere on
 *   the remote screen regardless of where the finger currently is.
 *
 * - [DIRECT]: "ماوس مباشر" (absolute/direct mode) — the finger's on-screen
 *   position is mapped straight through to the corresponding remote pixel
 *   (through the same letterbox + pinch-zoom + [ScreenFlipMode] transform
 *   used to *draw* the frame — see `RdpCanvas.localPointToRemote`), exactly
 *   like touching a physical touchscreen. A tap always lands where the
 *   finger is, with no relative "travel" needed first.
 *
 * A local cursor overlay is required reading for [DIRECT] (a bare tap with
 * no visible cursor is disorienting on a touchscreen) and is intentionally
 * suppressed for [TOUCHPAD] (where the cursor's on-screen position is
 * unrelated to the finger's position, so drawing it there would mislead
 * rather than help) — see the `mouseInputMode == DIRECT` gate on
 * `showCursor` in [RdpCanvas].
 *
 * - [MULTITOUCH]: MULTITOUCH FEATURE — real, multi-contact touch passthrough
 *   over MS-RDPEI (see systemsgo_jni.c's nativeSendTouchFrame /
 *   AFreeRdpBridge.sendTouchFrame), for RDP sessions only. Unlike TOUCHPAD/
 *   DIRECT, which always collapse every gesture to one synthesized mouse
 *   pointer, every finger's own raw contact (id/position/down-move-up) is
 *   forwarded to the server as its own RDPEI contact in the same frame —
 *   e.g. two fingers pinch-zooming inside a remote drawing app reaches that
 *   app as two real touch points, not as this client's local pinch-to-zoom
 *   gesture. detectTransformGestures (pinch/pan-to-navigate the local
 *   viewport) is suppressed while this mode is active for exactly that
 *   reason — see the `mouseInputMode == MULTITOUCH` gate in RdpCanvas. Falls
 *   back to no-op per finger if the server never opened the "rdpei" channel
 *   (no MS-RDPEI support) — same graceful-degradation contract nativeResize
 *   already has for servers without RDPEDISP.
 */
enum class MouseInputMode {
    TOUCHPAD,
    DIRECT,
    MULTITOUCH;

    companion object {
        fun fromSetting(raw: String): MouseInputMode = when (raw) {
            "direct"     -> DIRECT
            "multitouch" -> MULTITOUCH
            else         -> TOUCHPAD
        }
    }

    fun toSetting(): String = when (this) {
        TOUCHPAD    -> "touchpad"
        DIRECT      -> "direct"
        MULTITOUCH  -> "multitouch"
    }
}

/**
 * MULTITOUCH FEATURE: one finger's state within a single raw touch frame,
 * as reported by [RdpCanvas]'s `onMultiTouchFrame` callback — already
 * mapped into remote-screen pixel space (via `localPointToRemote`, the same
 * transform [MouseInputMode.DIRECT] uses), so the caller (RdpSessionScreen)
 * only has to forward it to whichever protocol bridge is active. Kept
 * protocol-agnostic on purpose (no FreeRDP/RDPEI types here) — the RDP call
 * site maps [phase] to [com.systemsgo.hex.rdp.native.AFreeRdpBridge.TouchAction]
 * before calling `sendTouchFrame`; a VNC call site would do the equivalent
 * for whatever multi-touch extension (if any) that protocol bridge ends up
 * supporting.
 */
data class RemoteTouchContact(val id: Int, val x: Int, val y: Int, val phase: TouchPhase)

enum class TouchPhase { DOWN, MOVE, UP }
