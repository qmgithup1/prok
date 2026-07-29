package com.systemsgo.hex.mosh.protocol.framebuffer

/**
 * FULL-FRAMEBUFFER-PARITY (Phase 2 of 4 — see mosh/NOTES.md's migration path).
 *
 * A faithful Kotlin port of upstream mosh's:
 *   `src/terminal/terminaldispatcher.h`, `terminaldispatcher.cc` (the
 *   `Dispatcher` class: parameter/intermediate-char accumulation, and the
 *   dispatch-table lookup that turns a completed CSI/ESC/control action
 *   into a call against a [Framebuffer]), and
 *   `src/terminal/terminalfunctions.cc` (every individual VT100/xterm
 *   function body: CUP/ED/EL/SGR/DECSTBM/insert-delete line, etc).
 *
 * This file depends only on [VtParser.kt] (for the [VtAction] types it
 * consumes) and [TerminalFramebuffer.kt] (the existing Phase-1 data model —
 * not redefined here, per that file's own instruction). It has zero
 * dependency on [TerminalEmulator.kt]; the top-level `Emulator::print` /
 * `Emulator::execute` / `Emulator::CSI_dispatch` / `Emulator::Esc_dispatch`
 * orchestration from upstream's `terminal.cc` lives there instead, since
 * those are the *emulator's* entry points into this dispatcher, not part of
 * the dispatcher itself.
 *
 * DOCUMENTED SIMPLIFICATION — DEC private mode dispatch table:
 * Upstream's `get_DEC_mode(param, fb)` returns a `bool*` pointing directly
 * at the relevant `DrawState` member (or `NULL` for unknown params), and
 * both `CSI_DECSM`/`CSI_DECRM` call it identically, then
 * `set_if_available(mode, true/false)`. Kotlin has no pointer-to-member, so
 * [applyDecPrivateMode] takes the target `value` directly instead of
 * returning a pointer — but preserves the exact upstream ORDER OF SIDE
 * EFFECTS, including the two easy-to-miss quirks: mode `3` (80/132 column)
 * clears the whole screen and homes the cursor on EITHER set OR reset
 * (upstream's `get_DEC_mode` does this unconditionally before returning
 * `NULL`, so the mode is never actually stored); and mode `6` (origin mode)
 * homes the cursor on EITHER set OR reset, in addition to actually flipping
 * `originMode` (unlike mode 3, upstream's `get_DEC_mode` returns a real
 * pointer here, so `set_if_available` does still assign it afterward).
 */

private enum class FunctionType { ESCAPE, CSI, CONTROL }

/** Port of `Terminal::Function` — a registered VT100/xterm function plus whether it clears the wrap-pending flag. */
private class DispatchEntry(val clearsWrapState: Boolean = true, val fn: (Framebuffer, TerminalDispatcher) -> Unit)

/**
 * Direct port of `Terminal::Dispatcher`. Accumulates CSI/DCS parameters and
 * intermediate/dispatch characters as actions stream in, then looks up and
 * invokes the matching [DispatchEntry] once a terminating action arrives.
 */
class TerminalDispatcher {

    companion object {
        /** Prevents evil escape sequences from causing long loops (matches upstream's own comment). */
        const val PARAM_MAX = 65535
        private const val MAXIMUM_CLIPBOARD_SIZE = 16 * 1024
    }

    private val params = StringBuilder()
    private var parsedParams: MutableList<Int> = mutableListOf()
    private var parsed = false

    private val dispatchChars = StringBuilder()
    private val oscString = mutableListOf<Int>()

    /** This is the reply string sent back to the host (upstream's `Dispatcher::terminal_to_host`). */
    val terminalToHost = StringBuilder()

    fun newParamChar(act: VtAction.Param) {
        val ch = act.ch
        if (params.length < 100) {
            // enough for 16 five-char params plus 15 semicolons (matches upstream's own comment)
            params.append(ch.toChar())
        }
        parsed = false
    }

    fun collect(act: VtAction.Collect) {
        collectChar(act.ch)
    }

    /** Shared by [collect] and [dispatch]'s "add final char to dispatch key" step (upstream reuses `collect()` for both). */
    private fun collectChar(ch: Int) {
        if (dispatchChars.length < 8 && ch <= 255) { // never should need more than 2; ignore non-8-bit
            dispatchChars.append(ch.toChar())
        }
    }

    fun clear(@Suppress("UNUSED_PARAMETER") act: VtAction.Clear) {
        params.setLength(0)
        dispatchChars.setLength(0)
        parsed = false
    }

    private fun parseParams() {
        if (parsed) return

        parsedParams = mutableListOf()
        val segments = params.toString().split(';')
        for (segment in segments) {
            var value = segment.toIntOrNull()
            if (value == null || value > PARAM_MAX) value = -1
            parsedParams.add(value)
        }
        parsed = true
    }

    fun getParam(n: Int, defaultVal: Int): Int {
        if (!parsed) parseParams()
        var ret = if (n < parsedParams.size) parsedParams[n] else defaultVal
        if (ret < 1) ret = defaultVal
        return ret
    }

    fun paramCount(): Int {
        if (!parsed) parseParams()
        return parsedParams.size
    }

    fun getDispatchChars(): String = dispatchChars.toString()

    fun dispatch(type: PublicFunctionType, act: VtAction, fb: Framebuffer) {
        val internalType = when (type) {
            PublicFunctionType.ESCAPE -> FunctionType.ESCAPE
            PublicFunctionType.CSI -> FunctionType.CSI
            PublicFunctionType.CONTROL -> FunctionType.CONTROL
        }

        // add final char to dispatch key
        if (internalType == FunctionType.ESCAPE || internalType == FunctionType.CSI) {
            collectChar(act.ch)
        }

        val map = when (internalType) {
            FunctionType.ESCAPE -> VtFunctionTable.escape
            FunctionType.CSI -> VtFunctionTable.csi
            FunctionType.CONTROL -> VtFunctionTable.control
        }

        val key = if (internalType == FunctionType.CONTROL) {
            act.ch.toChar().toString()
        } else {
            dispatchChars.toString()
        }

        val entry = map[key]
        if (entry == null) {
            fb.ds.nextPrintWillWrap = false
            return
        }
        if (entry.clearsWrapState) {
            fb.ds.nextPrintWillWrap = false
        }
        entry.fn(fb, this)
    }

    fun oscPut(act: VtAction.OscPut) {
        if (oscString.size < MAXIMUM_CLIPBOARD_SIZE) {
            oscString.add(act.ch)
        }
    }

    fun oscStart(@Suppress("UNUSED_PARAMETER") act: VtAction.OscStart) {
        oscString.clear()
    }

    /** Port of `Dispatcher::OSC_dispatch` — xterm title/icon-name/OSC-52-clipboard/OSC-8-hyperlink handling. */
    fun oscDispatch(@Suppress("UNUSED_PARAMETER") act: VtAction.OscEnd, fb: Framebuffer) {
        if (oscString.size >= 5 && oscString[0] == '5'.code && oscString[1] == '2'.code &&
            oscString[2] == ';'.code && oscString[3] == 'c'.code && oscString[4] == ';'.code
        ) {
            fb.clipboard = codepointsToString(oscString.subList(5, oscString.size))
            return
        }

        if (oscString.isEmpty()) return

        var cmdNum = -1L
        var offset = 0
        if (oscString[0] == ';'.code) {
            // OSC of the form "\033];<title>\007"
            cmdNum = 0
            offset = 1
        } else if (oscString.size >= 2 && oscString[1] == ';'.code) {
            // OSC of the form "\033]X;<title>\007" where X is 0 (icon+title), 1 (icon), or 2 (title)
            cmdNum = (oscString[0] - '0'.code).toLong()
            offset = 2
        }

        if (cmdNum == 8L) {
            val osc8 = parseOsc8(oscString) ?: return
            applyOsc8(osc8, fb)
            return
        }

        val setIcon = cmdNum == 0L || cmdNum == 1L
        val setTitle = cmdNum == 0L || cmdNum == 2L
        if (setIcon || setTitle) {
            fb.setTitleInitialized()
            val titleLength = minOf(oscString.size, 256)
            val newTitle = codepointsToString(oscString.subList(offset, titleLength))
            if (setIcon) fb.iconName = newTitle
            if (setTitle) fb.windowTitle = newTitle
        }
    }

    private fun codepointsToString(codepoints: List<Int>): String {
        val sb = StringBuilder(codepoints.size)
        for (cp in codepoints) sb.appendCodePoint(cp)
        return sb.toString()
    }

    /** Port of `Parse_OSC_8`: every code point must be in the valid printable-ASCII range 32-126. */
    private fun parseOsc8(osc8Vector: List<Int>): String? {
        val sb = StringBuilder(osc8Vector.size)
        for (wideChar in osc8Vector) {
            if (wideChar < 32 || wideChar > 126) return null
            sb.append(wideChar.toChar())
        }
        return sb.toString()
    }

    /** Port of `OSC_8`: OSC string of the form "8;params;url". */
    private fun applyOsc8(oscString8: String, fb: Framebuffer) {
        if (oscString8.isEmpty() || oscString8[0] != '8') return
        if (oscString8.length <= 2 || oscString8[1] != ';') return

        val secondSemicolon = oscString8.indexOf(';', 2)
        if (secondSemicolon == -1) return

        val url = oscString8.substring(secondSemicolon + 1)
        val params = oscString8.substring(2, secondSemicolon)
        fb.ds.hyperlink = Hyperlink.of(params, url)
    }
}

/** Public mirror of the private `FunctionType` so callers outside this file (TerminalEmulator) can request a dispatch. */
enum class PublicFunctionType { ESCAPE, CSI, CONTROL }

// ---------------------------------------------------------------------------------------------
// Terminal functions -- routines activated by CSI, escape, or a control char.
// Direct port of every function body in terminalfunctions.cc, keyed exactly
// as upstream registers them (its `Function func_XXX(TYPE, "key", fn[, clears_wrap])` statics).
// ---------------------------------------------------------------------------------------------

private fun clearLine(fb: Framebuffer, row: Int, start: Int, end: Int) {
    for (col in start..end) {
        fb.getMutableCell(row, col).reset(fb.ds.getBackgroundRendition())
    }
}

/* erase in line */
private fun csiEl(fb: Framebuffer, dispatch: TerminalDispatcher) {
    when (dispatch.getParam(0, 0)) {
        0 -> clearLine(fb, -1, fb.ds.cursorCol, fb.ds.width - 1) // active position to end of line, inclusive
        1 -> clearLine(fb, -1, 0, fb.ds.cursorCol) // start of screen to active position, inclusive
        2 -> fb.getMutableRow(-1).reset(fb.ds.getBackgroundRendition()) // all of line
    }
}

/* erase in display */
private fun csiEd(fb: Framebuffer, dispatch: TerminalDispatcher) {
    when (dispatch.getParam(0, 0)) {
        0 -> { // active position to end of screen, inclusive
            clearLine(fb, -1, fb.ds.cursorCol, fb.ds.width - 1)
            for (y in (fb.ds.cursorRow + 1) until fb.ds.height) {
                fb.getMutableRow(y).reset(fb.ds.getBackgroundRendition())
            }
        }
        1 -> { // start of screen to active position, inclusive
            for (y in 0 until fb.ds.cursorRow) {
                fb.getMutableRow(y).reset(fb.ds.getBackgroundRendition())
            }
            clearLine(fb, -1, 0, fb.ds.cursorCol)
        }
        2 -> { // entire screen
            for (y in 0 until fb.ds.height) {
                fb.getMutableRow(y).reset(fb.ds.getBackgroundRendition())
            }
        }
    }
}

/* cursor movement -- relative and absolute */
private fun csiCursorMove(fb: Framebuffer, dispatch: TerminalDispatcher) {
    val num = dispatch.getParam(0, 1)
    when (dispatch.getDispatchChars().getOrNull(0)) {
        'A' -> fb.ds.moveRow(-num, relative = true)
        'B' -> fb.ds.moveRow(num, relative = true)
        'C' -> fb.ds.moveCol(num, relative = true)
        'D' -> fb.ds.moveCol(-num, relative = true)
        'H', 'f' -> {
            fb.ds.moveRow(dispatch.getParam(0, 1) - 1)
            fb.ds.moveCol(dispatch.getParam(1, 1) - 1)
        }
    }
}

/* device attributes */
private fun csiDa(@Suppress("UNUSED_PARAMETER") fb: Framebuffer, dispatch: TerminalDispatcher) {
    dispatch.terminalToHost.append("\u001b[?62c") // plain vt220
}

/* secondary device attributes */
private fun csiSda(@Suppress("UNUSED_PARAMETER") fb: Framebuffer, dispatch: TerminalDispatcher) {
    dispatch.terminalToHost.append("\u001b[>1;10;0c") // plain vt220
}

/* screen alignment diagnostic */
private fun escDecaln(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    for (y in 0 until fb.ds.height) {
        for (x in 0 until fb.ds.width) {
            val cell = fb.getMutableCell(y, x)
            cell.reset(fb.ds.getBackgroundRendition())
            cell.append('E')
        }
    }
}

/* line feed (and same procedure for index, vertical tab, form feed) */
private fun ctrlLf(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    fb.moveRowsAutoscroll(1)
}

/* carriage return */
private fun ctrlCr(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    fb.ds.moveCol(0)
}

/* backspace */
private fun ctrlBs(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    fb.ds.moveCol(-1, relative = true)
}

/* reverse index -- like a backwards line feed */
private fun ctrlRi(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    fb.moveRowsAutoscroll(-1)
}

/* newline */
private fun ctrlNel(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    fb.ds.moveCol(0)
    fb.moveRowsAutoscroll(1)
}

/* horizontal tab */
private fun htN(fb: Framebuffer, count: Int) {
    var col = fb.ds.getNextTab(count)
    if (col == -1) col = fb.ds.width - 1 // no tabs, go to end of line

    // A horizontal tab is the only operation that preserves but does not set
    // the wrap state. It also starts a new grapheme.
    val wrapStateSave = fb.ds.nextPrintWillWrap
    fb.ds.moveCol(col, relative = false)
    fb.ds.nextPrintWillWrap = wrapStateSave
}

private fun ctrlHt(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    htN(fb, 1)
}

private fun csiCxT(fb: Framebuffer, dispatch: TerminalDispatcher) {
    var param = dispatch.getParam(0, 1)
    if (dispatch.getDispatchChars().getOrNull(0) == 'Z') param = -param
    if (param == 0) return
    htN(fb, param)
}

/* horizontal tab set */
private fun ctrlHts(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    fb.ds.setTab()
}

/* tabulation clear */
private fun csiTbc(fb: Framebuffer, dispatch: TerminalDispatcher) {
    when (dispatch.getParam(0, 0)) {
        0 -> fb.ds.clearTab(fb.ds.cursorCol) // clear this tab stop
        3 -> { // clear all tab stops
            fb.ds.clearDefaultTabs()
            for (x in 0 until fb.ds.width) fb.ds.clearTab(x)
        }
    }
}

/**
 * Port of upstream's `get_DEC_mode` + `set_if_available` pair — see the
 * file-level doc for why this takes [value] directly. Applied identically
 * from both DECSM (value=true) and DECRM (value=false), matching upstream
 * calling the same lookup from both.
 */
private fun applyDecPrivateMode(param: Int, fb: Framebuffer, value: Boolean) {
    when (param) {
        1 -> fb.ds.applicationModeCursorKeys = value // cursor key mode
        3 -> { // 80/132. Ignore but clear screen (mode itself is never stored, matches upstream).
            fb.ds.moveRow(0)
            fb.ds.moveCol(0)
            for (y in 0 until fb.ds.height) fb.getMutableRow(y).reset(fb.ds.getBackgroundRendition())
        }
        5 -> fb.ds.reverseVideo = value
        6 -> { // origin -- homes the cursor on EITHER set or reset, matching upstream
            fb.ds.moveRow(0)
            fb.ds.moveCol(0)
            fb.ds.originMode = value
        }
        7 -> fb.ds.autoWrapMode = value
        25 -> fb.ds.cursorVisible = value
        1004 -> fb.ds.mouseFocusEvent = value // xterm mouse focus event
        1007 -> fb.ds.mouseAlternateScroll = value // xterm mouse alternate scroll
        2004 -> fb.ds.bracketedPaste = value
        // unknown param: no-op, matches upstream's `default: break;`
    }
}

private fun mouseReportingModeFor(code: Int): DrawState.MouseReportingMode =
    DrawState.MouseReportingMode.values().first { it.code == code }

private fun mouseEncodingModeFor(code: Int): DrawState.MouseEncodingMode =
    DrawState.MouseEncodingMode.values().first { it.code == code }

/* set private mode */
private fun csiDecsm(fb: Framebuffer, dispatch: TerminalDispatcher) {
    for (i in 0 until dispatch.paramCount()) {
        val param = dispatch.getParam(i, 0)
        when {
            param == 9 || param in 1000..1003 -> fb.ds.mouseReportingMode = mouseReportingModeFor(param)
            param == 1005 || param == 1006 || param == 1015 -> fb.ds.mouseEncodingMode = mouseEncodingModeFor(param)
            else -> applyDecPrivateMode(param, fb, true)
        }
    }
}

/* clear private mode */
private fun csiDecrm(fb: Framebuffer, dispatch: TerminalDispatcher) {
    for (i in 0 until dispatch.paramCount()) {
        val param = dispatch.getParam(i, 0)
        when {
            param == 9 || param in 1000..1003 -> fb.ds.mouseReportingMode = DrawState.MouseReportingMode.NONE
            param == 1005 || param == 1006 || param == 1015 -> fb.ds.mouseEncodingMode = DrawState.MouseEncodingMode.DEFAULT
            else -> applyDecPrivateMode(param, fb, false)
        }
    }
}

/* set mode / clear mode (ANSI, non-DEC-private) -- only mode 4 (insert/replace) is implemented, matches upstream */
private fun csiSm(fb: Framebuffer, dispatch: TerminalDispatcher) {
    for (i in 0 until dispatch.paramCount()) {
        if (dispatch.getParam(i, 0) == 4) fb.ds.insertMode = true
    }
}

private fun csiRm(fb: Framebuffer, dispatch: TerminalDispatcher) {
    for (i in 0 until dispatch.paramCount()) {
        if (dispatch.getParam(i, 0) == 4) fb.ds.insertMode = false
    }
}

/* set top and bottom margins */
private fun csiDecstbm(fb: Framebuffer, dispatch: TerminalDispatcher) {
    val top = dispatch.getParam(0, 1)
    val bottom = dispatch.getParam(1, fb.ds.height)

    if (bottom <= top || top > fb.ds.height || (top == 0 && bottom == 1)) {
        return // invalid, xterm ignores
    }

    fb.ds.setScrollingRegion(top - 1, bottom - 1)
    fb.ds.moveRow(0)
    fb.ds.moveCol(0)
}

/* terminal bell */
private fun ctrlBel(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    fb.ringBell()
}

/* select graphics rendition -- e.g., bold, blinking, etc. */
private fun csiSgr(fb: Framebuffer, dispatch: TerminalDispatcher) {
    var i = 0
    while (i < dispatch.paramCount()) {
        val rendition = dispatch.getParam(i, 0)

        // Special-case [34]8;5;Ps, since Ps=0 there does NOT mean "reset to
        // default" even though it does everywhere else (matches upstream's own comment).
        if ((rendition == 38 || rendition == 48) && (dispatch.paramCount() - i >= 3) &&
            dispatch.getParam(i + 1, -1) == 5
        ) {
            if (rendition == 38) {
                fb.ds.renditions.setForegroundColor(dispatch.getParam(i + 2, 0))
            } else {
                fb.ds.renditions.setBackgroundColor(dispatch.getParam(i + 2, 0))
            }
            i += 2
            i++
            continue
        }

        // True color support: ESC[ ... [34]8;2;<r>;<g>;<b> ... m
        if ((rendition == 38 || rendition == 48) && (dispatch.paramCount() - i >= 5) &&
            dispatch.getParam(i + 1, -1) == 2
        ) {
            val red = dispatch.getParam(i + 2, 0)
            val green = dispatch.getParam(i + 3, 0)
            val blue = dispatch.getParam(i + 4, 0)
            val color = Renditions.makeTrueColor(red, green, blue)

            if (rendition == 38) {
                fb.ds.renditions.setForegroundColor(color)
            } else {
                fb.ds.renditions.setBackgroundColor(color)
            }
            i += 4
            i++
            continue
        }

        fb.ds.renditions.setRendition(rendition)
        i++
    }
}

/* save and restore cursor */
private fun escDecsc(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    fb.ds.saveCursor()
}

private fun escDecrc(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    fb.ds.restoreCursor()
}

/* device status report -- e.g., cursor position (used by resize) */
private fun csiDsr(fb: Framebuffer, dispatch: TerminalDispatcher) {
    when (dispatch.getParam(0, 0)) {
        5 -> dispatch.terminalToHost.append("\u001b[0n") // device status report requested
        6 -> dispatch.terminalToHost.append("\u001b[${fb.ds.cursorRow + 1};${fb.ds.cursorCol + 1}R") // active position
    }
}

/* insert line */
private fun csiIl(fb: Framebuffer, dispatch: TerminalDispatcher) {
    val lines = dispatch.getParam(0, 1)
    fb.insertLine(fb.ds.cursorRow, lines)
    // vt220 manual and Ecma-48 say to move to first column, but xterm and gnome-terminal don't
    fb.ds.moveCol(0)
}

/* delete line */
private fun csiDl(fb: Framebuffer, dispatch: TerminalDispatcher) {
    val lines = dispatch.getParam(0, 1)
    fb.deleteLine(fb.ds.cursorRow, lines)
    // same story -- xterm and gnome-terminal don't move to first column
    fb.ds.moveCol(0)
}

/* insert characters */
private fun csiIch(fb: Framebuffer, dispatch: TerminalDispatcher) {
    val cells = dispatch.getParam(0, 1)
    repeat(cells) { fb.insertCell(fb.ds.cursorRow, fb.ds.cursorCol) }
}

/* delete character */
private fun csiDch(fb: Framebuffer, dispatch: TerminalDispatcher) {
    val cells = dispatch.getParam(0, 1)
    repeat(cells) { fb.deleteCell(fb.ds.cursorRow, fb.ds.cursorCol) }
}

/* line position absolute */
private fun csiVpa(fb: Framebuffer, dispatch: TerminalDispatcher) {
    val row = dispatch.getParam(0, 1)
    fb.ds.moveRow(row - 1)
}

/* character position absolute (CHA / HPA) */
private fun csiHpa(fb: Framebuffer, dispatch: TerminalDispatcher) {
    val col = dispatch.getParam(0, 1)
    fb.ds.moveCol(col - 1)
}

/* erase character */
private fun csiEch(fb: Framebuffer, dispatch: TerminalDispatcher) {
    val num = dispatch.getParam(0, 1)
    var limit = fb.ds.cursorCol + num - 1
    if (limit >= fb.ds.width) limit = fb.ds.width - 1
    clearLine(fb, -1, fb.ds.cursorCol, limit)
}

/* reset to initial state */
private fun escRis(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    fb.reset()
}

/* soft reset */
private fun csiDecstr(fb: Framebuffer, @Suppress("UNUSED_PARAMETER") dispatch: TerminalDispatcher) {
    fb.softReset()
}

/* scroll down or terminfo indn */
private fun csiSd(fb: Framebuffer, dispatch: TerminalDispatcher) {
    fb.scroll(dispatch.getParam(0, 1))
}

/* scroll up or terminfo rin */
private fun csiSu(fb: Framebuffer, dispatch: TerminalDispatcher) {
    fb.scroll(-dispatch.getParam(0, 1))
}

/**
 * Direct port of the global dispatch registry (`get_global_dispatch_registry()`
 * and every `static Function func_XXX(...)` in terminalfunctions.cc). Built
 * eagerly (Kotlin `object` init is thread-safe and lazy-on-first-use,
 * sidestepping upstream's own "construct on first use to avoid static
 * initialization order crash" comment for free).
 */
private object VtFunctionTable {
    val escape: Map<String, DispatchEntry>
    val csi: Map<String, DispatchEntry>
    val control: Map<String, DispatchEntry>

    init {
        val escMap = mutableMapOf<String, DispatchEntry>()
        val csiMap = mutableMapOf<String, DispatchEntry>()
        val ctrlMap = mutableMapOf<String, DispatchEntry>()

        fun reg(target: MutableMap<String, DispatchEntry>, key: String, clearsWrap: Boolean = true, fn: (Framebuffer, TerminalDispatcher) -> Unit) {
            target[key] = DispatchEntry(clearsWrap, fn)
        }

        reg(csiMap, "K", fn = ::csiEl)
        reg(csiMap, "J", fn = ::csiEd)
        reg(csiMap, "A", fn = ::csiCursorMove)
        reg(csiMap, "B", fn = ::csiCursorMove)
        reg(csiMap, "C", fn = ::csiCursorMove)
        reg(csiMap, "D", fn = ::csiCursorMove)
        reg(csiMap, "H", fn = ::csiCursorMove)
        reg(csiMap, "f", fn = ::csiCursorMove)
        reg(csiMap, "c", fn = ::csiDa)
        reg(csiMap, ">c", fn = ::csiSda)
        reg(escMap, "#8", fn = ::escDecaln)

        reg(ctrlMap, "\u000a", fn = ::ctrlLf) // LF
        reg(ctrlMap, "\u0084", fn = ::ctrlLf) // IND
        reg(ctrlMap, "\u000b", fn = ::ctrlLf) // VT
        reg(ctrlMap, "\u000c", fn = ::ctrlLf) // FF
        reg(ctrlMap, "\u000d", fn = ::ctrlCr) // CR
        reg(ctrlMap, "\u0008", fn = ::ctrlBs) // BS
        reg(ctrlMap, "\u008D", fn = ::ctrlRi) // RI
        reg(ctrlMap, "\u0085", fn = ::ctrlNel) // NEL

        reg(ctrlMap, "\u0009", clearsWrap = false, fn = ::ctrlHt) // HT
        reg(csiMap, "I", clearsWrap = false, fn = ::csiCxT) // CHT
        reg(csiMap, "Z", clearsWrap = false, fn = ::csiCxT) // CBT
        reg(ctrlMap, "\u0088", fn = ::ctrlHts) // HTS
        reg(csiMap, "g", clearsWrap = false, fn = ::csiTbc) // TBC preserves wrap state

        reg(csiMap, "?h", clearsWrap = false, fn = ::csiDecsm)
        reg(csiMap, "?l", clearsWrap = false, fn = ::csiDecrm)
        reg(csiMap, "h", fn = ::csiSm)
        reg(csiMap, "l", fn = ::csiRm)

        reg(csiMap, "r", fn = ::csiDecstbm)
        reg(ctrlMap, "\u0007", fn = ::ctrlBel) // BEL
        reg(csiMap, "m", clearsWrap = false, fn = ::csiSgr) // changing renditions doesn't clear wrap flag

        reg(escMap, "7", fn = ::escDecsc)
        reg(escMap, "8", fn = ::escDecrc)

        reg(csiMap, "n", fn = ::csiDsr)
        reg(csiMap, "L", fn = ::csiIl)
        reg(csiMap, "M", fn = ::csiDl)
        reg(csiMap, "@", fn = ::csiIch)
        reg(csiMap, "P", fn = ::csiDch)
        reg(csiMap, "d", fn = ::csiVpa)
        reg(csiMap, "G", fn = ::csiHpa) // ECMA-48 name: CHA
        reg(csiMap, "\u0060", fn = ::csiHpa) // ECMA-48 name: HPA
        reg(csiMap, "X", fn = ::csiEch)

        reg(escMap, "c", fn = ::escRis) // RIS
        reg(csiMap, "!p", fn = ::csiDecstr) // DECSTR

        reg(csiMap, "S", fn = ::csiSd)
        reg(csiMap, "T", fn = ::csiSu)

        escape = escMap
        csi = csiMap
        control = ctrlMap
    }
}
