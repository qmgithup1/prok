package com.systemsgo.hex.shadow

/**
 * SHADOW-SERVER FEATURE: maps PC "Set 1" keyboard scancodes — the same
 * scheme [com.systemsgo.hex.util.HardwareKeyMap] maps INTO for the client
 * direction — back OUT to a printable character, for
 * [RemoteInputAccessibilityService]'s text-injection path. US-QWERTY
 * layout only, unshifted/shifted variants only (no AltGr/dead-key/IME
 * support) — deliberately a subset, not a full keyboard layout engine; see
 * that service's class doc for the same caveat.
 */
object ScancodeKeyMap {
    const val SHIFT_LEFT = 0x2A
    const val SHIFT_RIGHT = 0x36
    const val BACKSPACE = 0x0E
    const val ENTER = 0x1C
    const val TAB = 0x0F
    const val SPACE = 0x39

    private val unshifted: Map<Int, Char> = buildMap {
        // Letters
        val letters = "qwertyuiop".zip(intArrayOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19))
        letters.forEach { (c, code) -> put(code, c) }
        val homeRow = "asdfghjkl".zip(intArrayOf(0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26))
        homeRow.forEach { (c, code) -> put(code, c) }
        val bottomRow = "zxcvbnm".zip(intArrayOf(0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32))
        bottomRow.forEach { (c, code) -> put(code, c) }

        // Digits (top row)
        put(0x02, '1'); put(0x03, '2'); put(0x04, '3'); put(0x05, '4'); put(0x06, '5')
        put(0x07, '6'); put(0x08, '7'); put(0x09, '8'); put(0x0A, '9'); put(0x0B, '0')

        // Punctuation
        put(0x0C, '-'); put(0x0D, '='); put(0x1A, '['); put(0x1B, ']')
        put(0x27, ';'); put(0x28, '\''); put(0x29, '`'); put(0x2B, '\\')
        put(0x33, ','); put(0x34, '.'); put(0x35, '/')
        put(SPACE, ' ')
    }

    private val shifted: Map<Int, Char> = buildMap {
        // Shifted letters are just uppercase of the unshifted letter.
        unshifted.forEach { (code, ch) ->
            if (ch.isLetter()) put(code, ch.uppercaseChar())
        }
        put(0x02, '!'); put(0x03, '@'); put(0x04, '#'); put(0x05, '$'); put(0x06, '%')
        put(0x07, '^'); put(0x08, '&'); put(0x09, '*'); put(0x0A, '('); put(0x0B, ')')
        put(0x0C, '_'); put(0x0D, '+'); put(0x1A, '{'); put(0x1B, '}')
        put(0x27, ':'); put(0x28, '"'); put(0x29, '~'); put(0x2B, '|')
        put(0x33, '<'); put(0x34, '>'); put(0x35, '?')
        put(SPACE, ' ')
    }

    /** Returns the printable character for [scancode] given [shiftDown],
     * or null if this scancode isn't a printable key this map covers
     * (function keys, arrows, modifiers, Enter/Backspace/Tab — those are
     * handled separately by [RemoteInputAccessibilityService]). */
    fun toChar(scancode: Int, shiftDown: Boolean): Char? =
        if (shiftDown) shifted[scancode] ?: unshifted[scancode] else unshifted[scancode]
}
