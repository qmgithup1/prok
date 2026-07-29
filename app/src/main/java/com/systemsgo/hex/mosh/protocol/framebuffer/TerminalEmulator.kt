package com.systemsgo.hex.mosh.protocol.framebuffer

/**
 * FULL-FRAMEBUFFER-PARITY (Phase 2 of 4 — see mosh/NOTES.md's migration path).
 *
 * A faithful Kotlin port of upstream mosh's `src/terminal/terminal.h` /
 * `terminal.cc` — the `Terminal::Emulator` class ("Complete" in upstream's
 * own migration-path comment). This is the ONLY file in this phase that
 * combines [VtParser] + [TerminalDispatcher] + [Framebuffer] into a single
 * public entry point, matching upstream's own layering
 * (`Emulator` owns a `Framebuffer fb` and a `Dispatcher dispatch`, and
 * `Parser::Action::act_on_terminal` calls back into `Emulator`'s private
 * `print`/`execute`/`CSI_dispatch`/`Esc_dispatch` methods).
 *
 * Zero integration with any UI code or existing app file: this is a new,
 * self-contained class. Nothing outside this package imports it yet.
 *
 * SCOPE OF THIS PORT:
 * - `Emulator::print` is ported with its exact character-width branching
 *   (normal/wide/combining/unprintable), its auto-wrap and insert-mode
 *   interactions, and the wide-character "erase overlapped cell" quirk —
 *   all copied from upstream's `terminal.cc` line-for-line where possible.
 * - `Emulator::execute` / `CSI_dispatch` / `resize` are trivial one-line
 *   forwards to [TerminalDispatcher.dispatch], exactly as upstream.
 * - `Emulator::Esc_dispatch`'s 7-bit-ESC-encoding-of-C1-control-characters
 *   special case (`dispatch_chars.size() == 0 && 0x40 <= ch <= 0x5F` means
 *   this was originally a C1 control byte sent as `ESC` + `ch-0x40`, so
 *   redispatch it as CONTROL with `ch + 0x40`) is preserved exactly.
 *
 * DOCUMENTED SIMPLIFICATION — character width (`wcwidth`):
 * Upstream calls libc's locale-aware `wcwidth()` for anything that isn't
 * plain ISO-8859-1 (`Cell::isprint_iso8859_1`). There is no libc `wcwidth`
 * on the JVM/Android, so [charWidth] below reimplements the same four
 * outcomes upstream depends on (-1 unprintable, 0 combining/zero-width, 1
 * normal, 2 East-Asian-wide) using hand-written Unicode range tables
 * instead of glibc's per-locale Unicode tables. This does not affect any of
 * the acceptance-criteria sequences (CUP/ED/EL/SGR/DECSTBM/insert-delete
 * line/scroll all go through [TerminalDispatcher] and never call this
 * function) — it only affects rendering of individual wide/combining glyphs,
 * and is called out here precisely because it's the one place this port
 * cannot be byte-for-byte identical to glibc across every locale/Unicode
 * version.
 */

/**
 * Side effects the server byte stream can produce that are NOT visible in
 * [Framebuffer]'s own state — matches this file's brief: "SideEffect sealed
 * class تغطي على الأقل: Bell، TitleChanged، ClipboardChanged". [HostReply]
 * is also included: it mirrors upstream's `Emulator::read_octets_to_host`
 * (device-attribute/status-report replies queued by [TerminalDispatcher]
 * that must be written back to the server), which is exactly this kind of
 * "separate from screen state" side channel.
 */
sealed class TerminalSideEffect {
    /** Terminal bell (BEL / upstream's `ring_bell`) rang one or more times since the last call. */
    data class Bell(val count: Int) : TerminalSideEffect()

    /** The OSC window-title was (re)set. */
    data class TitleChanged(val title: String) : TerminalSideEffect()

    /** The OSC icon-name was (re)set. */
    data class IconNameChanged(val iconName: String) : TerminalSideEffect()

    /** An OSC 52 clipboard-copy sequence set the clipboard contents. */
    data class ClipboardChanged(val clipboard: String) : TerminalSideEffect()

    /** Bytes the emulator wants written back to the server (DA/DSR replies etc; upstream's `terminal_to_host`). */
    data class HostReply(val bytes: String) : TerminalSideEffect()
}

/**
 * Direct port of `Terminal::Emulator`. Owns a [Framebuffer], a [VtParser],
 * and a [TerminalDispatcher], and exposes upstream's `act(string)`-equivalent
 * as [processServerBytes].
 */
class TerminalEmulator(width: Int, height: Int) {

    /** Upstream's `Emulator::fb` (`get_fb()`). This is the single client-side Framebuffer for this session. */
    val framebuffer: Framebuffer = Framebuffer(width, height)

    private val dispatcher = TerminalDispatcher()
    private val parser = VtParser()

    // Baselines used to detect what changed since the last processServerBytes() call,
    // since none of these are reported through the action stream directly.
    private var lastBellCount = 0
    private var lastWindowTitle = ""
    private var lastIconName = ""
    private var lastClipboard = ""

    /**
     * Feed a chunk of raw bytes received from the server. Mutates
     * [framebuffer] in place and returns any [TerminalSideEffect]s produced
     * along the way, in the order they occurred (matches upstream's
     * `act_on_terminal` being invoked once per action, in stream order).
     */
    fun processServerBytes(bytes: ByteArray): List<TerminalSideEffect> {
        val actions = parser.inputBytes(bytes)
        for (action in actions) {
            actOnTerminal(action)
        }
        return collectSideEffects()
    }

    /** Upstream's `Emulator::resize` (also reachable via the `Parser::Resize` action upstream, which this port omits — see class doc). */
    fun resize(newWidth: Int, newHeight: Int) {
        framebuffer.resize(newWidth, newHeight)
    }

    /** Port of `Parser::Action::act_on_terminal`'s dispatch to the correct `Emulator` method, by action subtype. */
    private fun actOnTerminal(action: VtAction) {
        when (action) {
            is VtAction.Print -> printChar(action)
            is VtAction.Execute -> execute(action)
            is VtAction.Clear -> dispatcher.clear(action)
            is VtAction.Collect -> dispatcher.collect(action)
            is VtAction.Param -> dispatcher.newParamChar(action)
            is VtAction.CsiDispatch -> csiDispatch(action)
            is VtAction.EscDispatch -> escDispatch(action)
            is VtAction.OscStart -> dispatcher.oscStart(action)
            is VtAction.OscPut -> dispatcher.oscPut(action)
            is VtAction.OscEnd -> {
                dispatcher.oscDispatch(action, framebuffer)
            }
            // Hook/Put/Unhook: no-op on the terminal, matching upstream (those
            // Action subclasses never override `act_on_terminal`, so the base
            // no-op default applies -- DCS passthrough content has nowhere to go).
            is VtAction.Hook, is VtAction.Put, is VtAction.Unhook -> { /* intentionally no-op */ }
        }
    }

    /** Port of `Emulator::execute`. */
    private fun execute(act: VtAction.Execute) {
        dispatcher.dispatch(PublicFunctionType.CONTROL, act, framebuffer)
    }

    /** Port of `Emulator::CSI_dispatch`. */
    private fun csiDispatch(act: VtAction.CsiDispatch) {
        dispatcher.dispatch(PublicFunctionType.CSI, act, framebuffer)
    }

    /** Port of `Emulator::Esc_dispatch`, including the 7-bit C1 control re-encoding special case. */
    private fun escDispatch(act: VtAction.EscDispatch) {
        if (dispatcher.getDispatchChars().isEmpty() && act.ch in 0x40..0x5F) {
            // handle 7-bit ESC-encoding of C1 control characters
            val reencoded = VtAction.EscDispatch()
            reencoded.charPresent = act.charPresent
            reencoded.ch = act.ch + 0x40
            dispatcher.dispatch(PublicFunctionType.CONTROL, reencoded, framebuffer)
        } else {
            dispatcher.dispatch(PublicFunctionType.ESCAPE, act, framebuffer)
        }
    }

    /**
     * Port of `Emulator::print`. [act] is guaranteed `charPresent` by the
     * parser (upstream asserts this). Named `printChar` rather than `print`
     * to avoid shadowing `kotlin.io.print` at this call site.
     */
    private fun printChar(act: VtAction.Print) {
        val ch = act.ch
        val fb = framebuffer

        // Check for printing ISO 8859-1 first -- a cheap way to detect some common narrow characters.
        val chwidth = if (ch == 0) -1 else if (isPrintIso88591(ch)) 1 else charWidth(ch)

        var thisCell: Cell? = fb.getMutableCell()

        when (chwidth) {
            1, 2 -> { // normal or wide character
                if (fb.ds.autoWrapMode && fb.ds.nextPrintWillWrap) {
                    fb.getMutableRow(-1).setWrap(true)
                    fb.ds.moveCol(0)
                    fb.moveRowsAutoscroll(1)
                    thisCell = null
                } else if (fb.ds.autoWrapMode && chwidth == 2 && fb.ds.cursorCol == fb.ds.width - 1) {
                    // wrap 2-cell chars if no room, even without will-wrap flag
                    thisCell?.reset(fb.ds.getBackgroundRendition())
                    fb.getMutableRow(-1).setWrap(false)
                    // There doesn't seem to be a consistent way to get the downstream
                    // terminal emulator to set the wrap-around copy-and-paste flag on a
                    // row that ends with an empty cell because a wide char was wrapped
                    // to the next line.
                    fb.ds.moveCol(0)
                    fb.moveRowsAutoscroll(1)
                    thisCell = null
                }

                if (fb.ds.insertMode) {
                    repeat(chwidth) { fb.insertCell(fb.ds.cursorRow, fb.ds.cursorCol) }
                    thisCell = null
                }

                val cell = thisCell ?: fb.getMutableCell()

                cell.reset(fb.ds.getBackgroundRendition())
                cell.append(codePointToChars(ch))
                cell.wide = (chwidth == 2)
                fb.applyRenditionsToCell(cell)
                fb.applyHyperlinkToCell(cell)

                if (chwidth == 2 && fb.ds.cursorCol + 1 < fb.ds.width) { // erase overlapped cell
                    fb.getMutableCell(fb.ds.cursorRow, fb.ds.cursorCol + 1).reset(fb.ds.getBackgroundRendition())
                }

                fb.ds.moveCol(chwidth, relative = true, implicit = true)
            }
            0 -> { // combining character
                val combiningCell = fb.getCombiningCell() // can be null if we were resized
                if (combiningCell != null) {
                    if (combiningCell.isEmpty()) {
                        // cell starts with combining character -- but isn't necessarily the
                        // target for a new base character [e.g. start of line], if the
                        // combining character has been cleared with a sequence like ED ("J") or EL ("K")
                        combiningCell.fallback = true
                        fb.ds.moveCol(1, relative = true, implicit = true)
                    }
                    if (!combiningCell.isFull()) {
                        combiningCell.append(codePointToChars(ch))
                    }
                }
            }
            -1 -> { /* unprintable character: no-op */ }
        }
    }

    private fun collectSideEffects(): List<TerminalSideEffect> {
        val effects = mutableListOf<TerminalSideEffect>()

        val bellDelta = framebuffer.bellCount - lastBellCount
        if (bellDelta > 0) {
            effects.add(TerminalSideEffect.Bell(bellDelta))
            lastBellCount = framebuffer.bellCount
        }

        if (framebuffer.windowTitle != lastWindowTitle) {
            effects.add(TerminalSideEffect.TitleChanged(framebuffer.windowTitle))
            lastWindowTitle = framebuffer.windowTitle
        }

        if (framebuffer.iconName != lastIconName) {
            effects.add(TerminalSideEffect.IconNameChanged(framebuffer.iconName))
            lastIconName = framebuffer.iconName
        }

        if (framebuffer.clipboard != lastClipboard) {
            effects.add(TerminalSideEffect.ClipboardChanged(framebuffer.clipboard))
            lastClipboard = framebuffer.clipboard
        }

        if (dispatcher.terminalToHost.isNotEmpty()) {
            effects.add(TerminalSideEffect.HostReply(dispatcher.terminalToHost.toString()))
            dispatcher.terminalToHost.setLength(0)
        }

        return effects
    }
}

/** Port of `Cell::isprint_iso8859_1`. */
private fun isPrintIso88591(ch: Int): Boolean = (ch in 0xA0..0xFF) || (ch in 0x20..0x7E)

private fun codePointToChars(ch: Int): String = StringBuilder().appendCodePoint(ch).toString()

// ---------------------------------------------------------------------------------------------
// DOCUMENTED SIMPLIFICATION: character-width tables standing in for libc's locale-aware wcwidth().
// See the file-level doc for what this preserves (the four outcomes upstream's `print()` branches
// on) and what it doesn't (byte-for-byte parity with glibc's per-Unicode-version tables).
// ---------------------------------------------------------------------------------------------

/** Zero-width combining marks (Unicode general categories Mn/Me) -- common ranges, not the full table. */
private val combiningRanges = listOf(
    0x0300..0x036F, // Combining Diacritical Marks
    0x0483..0x0489, // Cyrillic combining marks
    0x0591..0x05BD, 0x05BF..0x05BF, 0x05C1..0x05C2, 0x05C4..0x05C5, 0x05C7..0x05C7, // Hebrew points
    0x0610..0x061A, 0x064B..0x065F, 0x0670..0x0670, // Arabic diacritics
    0x06D6..0x06DC, 0x06DF..0x06E4, 0x06E7..0x06E8, 0x06EA..0x06ED,
    0x0711..0x0711, 0x0730..0x074A, // Syriac
    0x07A6..0x07B0, // Thaana
    0x0900..0x0903, 0x093A..0x093C, 0x0941..0x0948, 0x094D..0x094D, // Devanagari (subset)
    0x0E31..0x0E31, 0x0E34..0x0E3A, 0x0E47..0x0E4E, // Thai
    0x1AB0..0x1AFF, 0x1DC0..0x1DFF, // Combining Diacritical Marks Supplement/Extended
    0x200B..0x200F, // zero-width space/joiners/marks
    0x20D0..0x20FF, // Combining Diacritical Marks for Symbols
    0xFE00..0xFE0F, // Variation Selectors
    0xFE20..0xFE2F, // Combining Half Marks
)

/** East-Asian "Wide"/"Fullwidth" ranges (common subset of Unicode's East_Asian_Width property). */
private val wideRanges = listOf(
    0x1100..0x115F, // Hangul Jamo
    0x2E80..0x303E, // CJK Radicals, Kangxi, CJK Symbols and Punctuation
    0x3041..0x33FF, // Hiragana..CJK Compatibility
    0x3400..0x4DBF, // CJK Unified Ideographs Extension A
    0x4E00..0x9FFF, // CJK Unified Ideographs
    0xA000..0xA4CF, // Yi
    0xAC00..0xD7A3, // Hangul Syllables
    0xF900..0xFAFF, // CJK Compatibility Ideographs
    0xFF00..0xFF60, 0xFFE0..0xFFE6, // Fullwidth Forms
    0x1F300..0x1F64F, 0x1F900..0x1F9FF, // common emoji blocks
    0x20000..0x3FFFD, // CJK Unified Ideographs Extension B and beyond
)

/**
 * Stand-in for libc's `wcwidth()`: -1 unprintable/control, 0 combining,
 * 2 East-Asian-wide, 1 everything else. Only called for code points that
 * already failed [isPrintIso88591] (i.e. `ch < 0x20`, `ch in 0x7F..0x9F`, or
 * `ch > 0xFF`), matching upstream's own call site.
 */
private fun charWidth(ch: Int): Int {
    if (ch < 0x20 || ch in 0x7F..0x9F) return -1 // C0/C1 control range
    if (combiningRanges.any { ch in it }) return 0
    if (wideRanges.any { ch in it }) return 2
    return 1
}
