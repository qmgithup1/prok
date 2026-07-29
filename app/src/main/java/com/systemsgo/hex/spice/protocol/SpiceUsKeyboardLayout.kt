package com.systemsgo.hex.spice.protocol

/**
 * SPICE-PASTE-AS-KEYSTROKES FEATURE: US-QWERTY char → (scancode, needsShift)
 * table for [SpiceSessionClient.sendText].
 *
 * Why this exists at all — SPICE has no Unicode-keysym client message the
 * way RFB does (see [com.systemsgo.hex.vnc.protocol.VncClient.sendText]'s
 * `0x01000000 | codepoint` approach): `spice_inputs_channel`'s wire protocol
 * only carries raw PC/AT Set-1 scancodes (the same ones
 * [SpiceSessionClient.sendKeyEvent] already forwards 1:1 from Android key
 * events). Pasting text therefore means *synthesizing* the scancode
 * sequence a real US keyboard would send for each character — there is no
 * extra SPICE channel involved; this reuses the same Inputs channel
 * [SpiceSessionClient] already talks to via `SpiceBridge.sendKey`.
 *
 * Deliberate scope limit, matching this codebase's existing precedent for
 * the identical trade-off ([com.systemsgo.hex.shadow.ScancodeKeyMap]'s doc
 * comment): US-QWERTY only, no AltGr/dead-key/IME/other-layout support. A
 * character not on a physical US keyboard (Arabic, CJK, most accented
 * Latin, emoji, ...) has no scancode sequence to synthesize *by definition*
 * — that's not a gap in this table, it's the actual hardware limitation any
 * "paste as keystrokes" feature has on any protocol (VNC/RDP's Unicode-
 * keysym paths are the exception, not the norm, and only work because the
 * *server*, not the wire format, does the Unicode-to-input translation).
 * [SpiceSessionClient.sendText] skips and logs unmappable characters rather
 * than guessing or silently corrupting the pasted text.
 */
internal object SpiceUsKeyboardLayout {
    // PC/AT Set-1 scancodes — same numbering space SpiceSessionClient's
    // SC_CONTROL_L/SC_ALT_L/SC_DELETE already use, and numerically identical
    // to com.systemsgo.hex.shadow.ScancodeKeyMap's table (that class maps
    // the same physical layout in the opposite direction — scancode→char,
    // for the Shadow-server text-injection path — so the values are the
    // hardware's, not this app's; kept as an independent table here rather
    // than a cross-module dependency since "shadow" and "spice" are
    // unrelated features that happen to need the same keyboard geometry).
    const val SC_SHIFT_L = 0x2A

    private val unshifted: Map<Char, Int> = buildMap {
        "qwertyuiop".zip(intArrayOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19))
            .forEach { (c, code) -> put(c, code) }
        "asdfghjkl".zip(intArrayOf(0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26))
            .forEach { (c, code) -> put(c, code) }
        "zxcvbnm".zip(intArrayOf(0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32))
            .forEach { (c, code) -> put(c, code) }

        put('1', 0x02); put('2', 0x03); put('3', 0x04); put('4', 0x05); put('5', 0x06)
        put('6', 0x07); put('7', 0x08); put('8', 0x09); put('9', 0x0A); put('0', 0x0B)

        put('-', 0x0C); put('=', 0x0D); put('[', 0x1A); put(']', 0x1B)
        put(';', 0x27); put('\'', 0x28); put('`', 0x29); put('\\', 0x2B)
        put(',', 0x33); put('.', 0x34); put('/', 0x35)
        put(' ', 0x39)
        put('\t', 0x0F)
        put('\n', 0x1C)
        put('\r', 0x1C)
    }

    private val shifted: Map<Char, Int> = buildMap {
        unshifted.forEach { (ch, code) -> if (ch.isLetter()) put(ch.uppercaseChar(), code) }
        put('!', 0x02); put('@', 0x03); put('#', 0x04); put('$', 0x05); put('%', 0x06)
        put('^', 0x07); put('&', 0x08); put('*', 0x09); put('(', 0x0A); put(')', 0x0B)
        put('_', 0x0C); put('+', 0x0D); put('{', 0x1A); put('}', 0x1B)
        put(':', 0x27); put('"', 0x28); put('~', 0x29); put('|', 0x2B)
        put('<', 0x33); put('>', 0x34); put('?', 0x35)
    }

    /** Returns (scancode, needsShift) for [char], or null if it has no
     *  US-QWERTY scancode — see this object's class doc for why that's an
     *  expected outcome for non-Latin/accented/symbol characters, not a bug. */
    fun scancodeFor(char: Char): Pair<Int, Boolean>? {
        unshifted[char]?.let { return it to false }
        shifted[char]?.let { return it to true }
        return null
    }
}
