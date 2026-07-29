package com.systemsgo.hex.util

import android.view.KeyEvent

/**
 * EXTERNAL-DISPLAY / DEX FEATURE
 *
 * Maps Android's [KeyEvent.keyCode] (from a physical/Bluetooth/USB keyboard —
 * exactly what a Samsung DeX or Android Desktop Mode session is typically
 * paired with) to the PC "Set 1" scan codes that
 * [com.systemsgo.hex.remote.RemoteSessionClient.sendKeyEvent] already expects —
 * the same scan codes [com.systemsgo.hex.ui.screens.ExtraKeysBar] sends for its
 * on-screen buttons, so both input paths drive the exact same RDP/VNC/SSH
 * key-event plumbing with no protocol-layer changes required.
 *
 * Only a hardware keyboard should ever reach this map: the soft/IME keyboard
 * continues to go through the existing text-input path unchanged.
 */
object HardwareKeyMap {

    /** Result of mapping one Android keyCode. */
    data class ScanCode(val code: Int, val extended: Boolean)

    // NOTE: extended=true marks the keys that, on a real PS/2 keyboard, are
    // sent as a two-byte E0-prefixed sequence (right-side modifiers, arrow
    // cluster, Home/End/PageUp/PageDown/Insert/Delete, the numeric-keypad-less
    // arithmetic keys, etc.) — see the identical `extended` usage already in
    // ExtraKeysBar / SshKeyMap for the same scan codes.
    private val map: Map<Int, ScanCode> = buildMap {
        // ── Letters (US QWERTY scan codes) ──────────────────────────────
        put(KeyEvent.KEYCODE_A, ScanCode(0x1E, false)); put(KeyEvent.KEYCODE_B, ScanCode(0x30, false))
        put(KeyEvent.KEYCODE_C, ScanCode(0x2E, false)); put(KeyEvent.KEYCODE_D, ScanCode(0x20, false))
        put(KeyEvent.KEYCODE_E, ScanCode(0x12, false)); put(KeyEvent.KEYCODE_F, ScanCode(0x21, false))
        put(KeyEvent.KEYCODE_G, ScanCode(0x22, false)); put(KeyEvent.KEYCODE_H, ScanCode(0x23, false))
        put(KeyEvent.KEYCODE_I, ScanCode(0x17, false)); put(KeyEvent.KEYCODE_J, ScanCode(0x24, false))
        put(KeyEvent.KEYCODE_K, ScanCode(0x25, false)); put(KeyEvent.KEYCODE_L, ScanCode(0x26, false))
        put(KeyEvent.KEYCODE_M, ScanCode(0x32, false)); put(KeyEvent.KEYCODE_N, ScanCode(0x31, false))
        put(KeyEvent.KEYCODE_O, ScanCode(0x18, false)); put(KeyEvent.KEYCODE_P, ScanCode(0x19, false))
        put(KeyEvent.KEYCODE_Q, ScanCode(0x10, false)); put(KeyEvent.KEYCODE_R, ScanCode(0x13, false))
        put(KeyEvent.KEYCODE_S, ScanCode(0x1F, false)); put(KeyEvent.KEYCODE_T, ScanCode(0x14, false))
        put(KeyEvent.KEYCODE_U, ScanCode(0x16, false)); put(KeyEvent.KEYCODE_V, ScanCode(0x2F, false))
        put(KeyEvent.KEYCODE_W, ScanCode(0x11, false)); put(KeyEvent.KEYCODE_X, ScanCode(0x2D, false))
        put(KeyEvent.KEYCODE_Y, ScanCode(0x15, false)); put(KeyEvent.KEYCODE_Z, ScanCode(0x2C, false))

        // ── Digits (top row) ─────────────────────────────────────────────
        put(KeyEvent.KEYCODE_1, ScanCode(0x02, false)); put(KeyEvent.KEYCODE_2, ScanCode(0x03, false))
        put(KeyEvent.KEYCODE_3, ScanCode(0x04, false)); put(KeyEvent.KEYCODE_4, ScanCode(0x05, false))
        put(KeyEvent.KEYCODE_5, ScanCode(0x06, false)); put(KeyEvent.KEYCODE_6, ScanCode(0x07, false))
        put(KeyEvent.KEYCODE_7, ScanCode(0x08, false)); put(KeyEvent.KEYCODE_8, ScanCode(0x09, false))
        put(KeyEvent.KEYCODE_9, ScanCode(0x0A, false)); put(KeyEvent.KEYCODE_0, ScanCode(0x0B, false))

        // ── Numeric keypad ───────────────────────────────────────────────
        put(KeyEvent.KEYCODE_NUMPAD_0, ScanCode(0x52, false)); put(KeyEvent.KEYCODE_NUMPAD_1, ScanCode(0x4F, false))
        put(KeyEvent.KEYCODE_NUMPAD_2, ScanCode(0x50, false)); put(KeyEvent.KEYCODE_NUMPAD_3, ScanCode(0x51, false))
        put(KeyEvent.KEYCODE_NUMPAD_4, ScanCode(0x4B, false)); put(KeyEvent.KEYCODE_NUMPAD_5, ScanCode(0x4C, false))
        put(KeyEvent.KEYCODE_NUMPAD_6, ScanCode(0x4D, false)); put(KeyEvent.KEYCODE_NUMPAD_7, ScanCode(0x47, false))
        put(KeyEvent.KEYCODE_NUMPAD_8, ScanCode(0x48, false)); put(KeyEvent.KEYCODE_NUMPAD_9, ScanCode(0x49, false))
        put(KeyEvent.KEYCODE_NUMPAD_DOT, ScanCode(0x53, false)); put(KeyEvent.KEYCODE_NUMPAD_ENTER, ScanCode(0x1C, true))
        put(KeyEvent.KEYCODE_NUMPAD_ADD, ScanCode(0x4E, false)); put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, ScanCode(0x4A, false))
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, ScanCode(0x37, false)); put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, ScanCode(0x35, true))

        // ── Function row ─────────────────────────────────────────────────
        put(KeyEvent.KEYCODE_F1, ScanCode(0x3B, false)); put(KeyEvent.KEYCODE_F2, ScanCode(0x3C, false))
        put(KeyEvent.KEYCODE_F3, ScanCode(0x3D, false)); put(KeyEvent.KEYCODE_F4, ScanCode(0x3E, false))
        put(KeyEvent.KEYCODE_F5, ScanCode(0x3F, false)); put(KeyEvent.KEYCODE_F6, ScanCode(0x40, false))
        put(KeyEvent.KEYCODE_F7, ScanCode(0x41, false)); put(KeyEvent.KEYCODE_F8, ScanCode(0x42, false))
        put(KeyEvent.KEYCODE_F9, ScanCode(0x43, false)); put(KeyEvent.KEYCODE_F10, ScanCode(0x44, false))
        put(KeyEvent.KEYCODE_F11, ScanCode(0x57, false)); put(KeyEvent.KEYCODE_F12, ScanCode(0x58, false))

        // ── Navigation / editing cluster ──────────────────────────────────
        put(KeyEvent.KEYCODE_DPAD_UP,    ScanCode(0x48, true)); put(KeyEvent.KEYCODE_DPAD_DOWN,  ScanCode(0x50, true))
        put(KeyEvent.KEYCODE_DPAD_LEFT,  ScanCode(0x4B, true)); put(KeyEvent.KEYCODE_DPAD_RIGHT, ScanCode(0x4D, true))
        put(KeyEvent.KEYCODE_MOVE_HOME,  ScanCode(0x47, true)); put(KeyEvent.KEYCODE_MOVE_END,   ScanCode(0x4F, true))
        put(KeyEvent.KEYCODE_PAGE_UP,    ScanCode(0x49, true)); put(KeyEvent.KEYCODE_PAGE_DOWN,  ScanCode(0x51, true))
        put(KeyEvent.KEYCODE_INSERT,     ScanCode(0x52, true)); put(KeyEvent.KEYCODE_FORWARD_DEL, ScanCode(0x53, true))

        // ── Whitespace / control keys ──────────────────────────────────────
        put(KeyEvent.KEYCODE_SPACE, ScanCode(0x39, false)); put(KeyEvent.KEYCODE_TAB, ScanCode(0x0F, false))
        put(KeyEvent.KEYCODE_ENTER, ScanCode(0x1C, false)); put(KeyEvent.KEYCODE_DEL, ScanCode(0x0E, false)) // Backspace
        put(KeyEvent.KEYCODE_ESCAPE, ScanCode(0x01, false))

        // ── Modifiers ───────────────────────────────────────────────────────
        put(KeyEvent.KEYCODE_SHIFT_LEFT,  ScanCode(0x2A, false)); put(KeyEvent.KEYCODE_SHIFT_RIGHT, ScanCode(0x36, false))
        put(KeyEvent.KEYCODE_CTRL_LEFT,   ScanCode(0x1D, false)); put(KeyEvent.KEYCODE_CTRL_RIGHT,  ScanCode(0x1D, true))
        put(KeyEvent.KEYCODE_ALT_LEFT,    ScanCode(0x38, false)); put(KeyEvent.KEYCODE_ALT_RIGHT,   ScanCode(0x38, true))
        put(KeyEvent.KEYCODE_META_LEFT,   ScanCode(0x5B, true));  put(KeyEvent.KEYCODE_META_RIGHT,  ScanCode(0x5C, true))
        put(KeyEvent.KEYCODE_CAPS_LOCK,   ScanCode(0x3A, false))

        // ── Punctuation (US QWERTY) ──────────────────────────────────────
        put(KeyEvent.KEYCODE_MINUS, ScanCode(0x0C, false)); put(KeyEvent.KEYCODE_EQUALS, ScanCode(0x0D, false))
        put(KeyEvent.KEYCODE_LEFT_BRACKET, ScanCode(0x1A, false)); put(KeyEvent.KEYCODE_RIGHT_BRACKET, ScanCode(0x1B, false))
        put(KeyEvent.KEYCODE_BACKSLASH, ScanCode(0x2B, false)); put(KeyEvent.KEYCODE_SEMICOLON, ScanCode(0x27, false))
        put(KeyEvent.KEYCODE_APOSTROPHE, ScanCode(0x28, false)); put(KeyEvent.KEYCODE_GRAVE, ScanCode(0x29, false))
        put(KeyEvent.KEYCODE_COMMA, ScanCode(0x33, false)); put(KeyEvent.KEYCODE_PERIOD, ScanCode(0x34, false))
        put(KeyEvent.KEYCODE_SLASH, ScanCode(0x35, false))
    }

    /**
     * Returns the PC scan code + extended flag for [keyCode], or null if this
     * key isn't mapped (e.g. a media/volume key, or an unusual layout key) —
     * callers should simply not forward the event in that case.
     */
    fun toScanCode(keyCode: Int): ScanCode? = map[keyCode]

    /**
     * True for keys that should never be intercepted for the remote session
     * even while the canvas has focus — letting the platform/back-stack
     * handle them normally (matches the existing BackHandler-based disconnect
     * confirmation in [com.systemsgo.hex.ui.screens.RdpSessionScreen]).
     */
    fun isReservedForSystem(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_BACK
}
