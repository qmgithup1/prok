package com.systemsgo.hex.mosh.protocol.framebuffer

/**
 * FULL-FRAMEBUFFER-PARITY (Phase 1 of 4 — see mosh/NOTES.md's migration path):
 *
 * A faithful, line-for-line-where-possible Kotlin port of upstream mosh's
 * `src/terminal/terminalframebuffer.{h,cc}` — the persistent 2-D grid of
 * [Cell]s with cursor/attribute state ([DrawState]) that upstream's real
 * `PredictionEngine` predicts against and validates cell-by-cell. This file
 * is the data model only: nothing in the app wires it up yet.
 *
 * SCOPE OF THIS PORT — read before touching anything below:
 * - Semantics (cursor movement, scrolling region, insert/delete line/cell,
 *   resize, tab stops, save/restore cursor, SGR renditions) are ported
 *   directly from the C++ source, including its exact bounds-clamping and
 *   edge-case order of operations (e.g. [DrawState.resize]'s "reinitialize
 *   tabs using the OLD width" quirk, or [Framebuffer.insertLine]/[deleteLine]
 *   computing their second erase/insert offset against the ALREADY-shrunk
 *   list — both preserved exactly because getting either wrong silently
 *   corrupts scrolling-region behavior in a way that's easy to miss in review).
 * - Upstream's copy-on-write `shared_ptr<Row>` sharing between multiple
 *   `Framebuffer` snapshots is a network-diffing optimization (cheap
 *   equality/diff between server-side generations of the framebuffer) that
 *   has no analogue here — this app only ever needs ONE live client-side
 *   Framebuffer to predict/render against, never a snapshot lineage to diff.
 *   Dropped intentionally; every [Row]/[Cell] here is a plain mutable object.
 * - `Cell::append(wchar_t)`'s manual `wcrtomb`/mbstate_t UTF-8 encoding dance
 *   is dropped: Kotlin's `Char`/`String` are UTF-16 natively, so cell
 *   contents are stored as plain `String`/`StringBuilder` — no multibyte
 *   encoding step is needed on this side of the terminal.
 * - `Cell::compare()` (a debug-only stderr-diffing helper used by upstream's
 *   test harness) and `Cell::debug_contents()` are omitted — dead weight
 *   with no runtime behavior to preserve.
 *
 * NOT YET DONE (see mosh/NOTES.md for the rest of the phased plan):
 *   Phase 2 — port `parser*`/`terminaldispatcher.*` (the VT100/ANSI state
 *     machine that turns a byte stream into Framebuffer mutations).
 *   Phase 3 — replace TerminalScreen.kt's `parseAnsiChunk` rendering path
 *     with a renderer that reads from this Framebuffer instead of an
 *     appended string. Cross-cutting: changes rendering for every protocol
 *     screen (SSH/IPMI-SOL/serial/telnet/rlogin), not just Mosh.
 *   Phase 4 — port upstream's `PredictionEngine` (terminaloverlay.h/.cc) 1:1
 *     against this Framebuffer, replacing MoshPredictionEngine's current
 *     string-prefix-matching approach with real cell-by-cell validation
 *     (arrow keys, mid-line inserts, overwrite-in-place).
 */

/** 32-bit packed color: either an indexed/ANSI value or a true-color RGB triple flagged by [TRUE_COLOR_MASK]. */
typealias ColorType = Int

/**
 * Direct port of `Terminal::Renditions` — SGR (Select Graphic Rendition) state:
 * foreground/background color plus the bold/italic/underline/etc attribute bits.
 */
class Renditions(backgroundColor: ColorType = 0) {

    enum class Attribute { BOLD, FAINT, ITALIC, UNDERLINED, BLINK, INVERSE, INVISIBLE }

    companion object {
        private const val TRUE_COLOR_MASK: Int = 0x1000000

        fun makeTrueColor(r: Int, g: Int, b: Int): Int = TRUE_COLOR_MASK or (r shl 16) or (g shl 8) or b
        fun isTrueColor(color: Int): Boolean = (color and TRUE_COLOR_MASK) != 0
    }

    var foregroundColor: ColorType = 0
    var backgroundColor: ColorType = backgroundColor
        private set
    private var attributes: Int = 0

    fun getBackgroundRendition(): ColorType = backgroundColor

    /** Deep-value copy (Kotlin equivalent of C++'s struct-assignment `DrawState.renditions = other`). */
    fun copy(): Renditions {
        val r = Renditions(backgroundColor)
        r.foregroundColor = foregroundColor
        r.attributes = attributes
        return r
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Renditions) return false
        return attributes == other.attributes &&
            foregroundColor == other.foregroundColor &&
            backgroundColor == other.backgroundColor
    }

    override fun hashCode(): Int = (attributes * 31 + foregroundColor) * 31 + backgroundColor

    fun setAttribute(attr: Attribute, value: Boolean) {
        val bit = 1 shl attr.ordinal
        attributes = if (value) attributes or bit else attributes and bit.inv()
    }

    fun getAttribute(attr: Attribute): Boolean = (attributes and (1 shl attr.ordinal)) != 0
    fun clearAttributes() { attributes = 0 }

    fun setForegroundColor(num: Int) {
        when {
            num in 0..255 -> foregroundColor = 30 + num
            isTrueColor(num) -> foregroundColor = num
        }
    }

    fun setBackgroundColor(num: Int) {
        when {
            num in 0..255 -> backgroundColor = 40 + num
            isTrueColor(num) -> backgroundColor = num
        }
    }

    /** This routine cannot be used to set a color beyond the 16-color set (matches upstream's own comment). */
    fun setRendition(num: Int) {
        when {
            num == 0 -> { clearAttributes(); foregroundColor = 0; backgroundColor = 0; return }
            num == 39 -> { foregroundColor = 0; return }
            num == 49 -> { backgroundColor = 0; return }
            num in 30..37 -> { foregroundColor = num; return }
            num in 40..47 -> { backgroundColor = num; return }
            num in 90..97 -> { foregroundColor = num - 90 + 38; return }
            num in 100..107 -> { backgroundColor = num - 100 + 48; return }
        }
        val value = num < 9
        when (num) {
            1, 22 -> setAttribute(Attribute.BOLD, value)
            3, 23 -> setAttribute(Attribute.ITALIC, value)
            4, 24 -> setAttribute(Attribute.UNDERLINED, value)
            5, 25 -> setAttribute(Attribute.BLINK, value)
            7, 27 -> setAttribute(Attribute.INVERSE, value)
            8, 28 -> setAttribute(Attribute.INVISIBLE, value)
            else -> { /* ignore unknown rendition, matches upstream */ }
        }
    }

    /** Builds the escape sequence that would reproduce this rendition state (upstream's `Renditions::sgr()`). */
    fun sgr(): String {
        val ret = StringBuilder("\u001b[0")
        if (getAttribute(Attribute.BOLD)) ret.append(";1")
        if (getAttribute(Attribute.ITALIC)) ret.append(";3")
        if (getAttribute(Attribute.UNDERLINED)) ret.append(";4")
        if (getAttribute(Attribute.BLINK)) ret.append(";5")
        if (getAttribute(Attribute.INVERSE)) ret.append(";7")
        if (getAttribute(Attribute.INVISIBLE)) ret.append(";8")

        if (foregroundColor != 0) {
            ret.append(colorSgrFragment(foregroundColor, isForeground = true))
        }
        if (backgroundColor != 0) {
            ret.append(colorSgrFragment(backgroundColor, isForeground = false))
        }
        ret.append("m")
        return ret.toString()
    }

    private fun colorSgrFragment(color: Int, isForeground: Boolean): String {
        val base38or48 = if (isForeground) 38 else 48
        val ansiOffset = if (isForeground) 30 else 40
        return when {
            isTrueColor(color) -> ";$base38or48;2;${(color shr 16) and 0xff};${(color shr 8) and 0xff};${color and 0xff}"
            color > ansiOffset + 7 -> ";$base38or48;5;${color - ansiOffset}" // 256-color set
            else -> ";$color" // plain ANSI color
        }
    }
}

/** Direct port of `Terminal::Hyperlink` (OSC 8) — immutable, empty() when there's no URL. */
class Hyperlink private constructor(val params: String, val url: String) {
    companion object {
        val EMPTY = Hyperlink("", "")
        fun of(params: String, url: String): Hyperlink = if (url.isEmpty()) EMPTY else Hyperlink(params, url)
    }

    fun isEmpty(): Boolean = url.isEmpty()

    fun osc8(): String = buildString {
        append("\u001b]8;")
        if (!isEmpty()) append(params)
        append(";")
        if (!isEmpty()) append(url)
        append("\u001b\\")
    }

    override fun equals(other: Any?): Boolean =
        other is Hyperlink && url == other.url && params == other.params

    override fun hashCode(): Int = url.hashCode() * 31 + params.hashCode()
}

/**
 * Direct port of `Terminal::Cell`. `contents` is a plain `StringBuilder`
 * instead of a raw multibyte byte buffer — see the file-level doc for why
 * the mbstate_t/`wcrtomb` dance from upstream is unnecessary in Kotlin.
 */
class Cell(backgroundColor: ColorType) {
    private val contents = StringBuilder()
    var renditions: Renditions = Renditions(backgroundColor)
    var hyperlink: Hyperlink = Hyperlink.EMPTY
    var wide: Boolean = false
    var fallback: Boolean = false /* true if the first character is a combining character */
    var wrap: Boolean = false

    fun reset(backgroundColor: ColorType) {
        contents.setLength(0)
        renditions = Renditions(backgroundColor)
        hyperlink = Hyperlink.EMPTY
        wide = false
        fallback = false
        wrap = false
    }

    fun isEmpty(): Boolean = contents.isEmpty()
    /** 32 seems like a reasonable limit on combining characters (matches upstream's own comment). */
    fun isFull(): Boolean = contents.length >= 32
    fun clear() { contents.setLength(0) }

    fun isBlank(): Boolean {
        val s = contents.toString()
        return s.isEmpty() || s == " " || s == "\u00A0"
    }

    fun contentsMatch(other: Cell): Boolean =
        (isBlank() && other.isBlank()) || contents.toString() == other.contents.toString()

    fun append(c: Char) { contents.append(c) }
    fun append(s: CharSequence) { contents.append(s) }

    /** Renders this cell's grapheme (combining-character no-break-space prefix included) into [output]. */
    fun printGrapheme(output: StringBuilder) {
        if (contents.isEmpty()) {
            output.append(' ')
            return
        }
        if (fallback) output.append('\u00A0')
        output.append(contents)
    }

    fun getWidth(): Int = if (wide) 2 else 1

    /** Value-copy used when a Row needs a snapshot of an existing cell (e.g. blank-row fill). */
    fun copy(): Cell {
        val c = Cell(renditions.backgroundColor)
        c.contents.append(contents)
        c.renditions = renditions.copy()
        c.hyperlink = hyperlink
        c.wide = wide
        c.fallback = fallback
        c.wrap = wrap
        return c
    }
}

/** Direct port of `Terminal::Row`. */
class Row(width: Int, backgroundColor: ColorType) {
    val cells: MutableList<Cell> = MutableList(width) { Cell(backgroundColor) }

    /** Generation counter — lets callers cheaply rule out two rows being identical (upstream uses it for scrolling). */
    var gen: Long = nextGen()
        private set

    private companion object {
        private var genCounter = 0L
        fun nextGen(): Long = genCounter++
    }

    fun insertCell(col: Int, backgroundColor: ColorType) {
        cells.add(col, Cell(backgroundColor))
        cells.removeAt(cells.size - 1)
    }

    fun deleteCell(col: Int, backgroundColor: ColorType) {
        cells.add(Cell(backgroundColor))
        cells.removeAt(col)
    }

    fun reset(backgroundColor: ColorType) {
        gen = nextGen()
        for (c in cells) c.reset(backgroundColor)
    }

    fun getWrap(): Boolean = cells.last().wrap
    fun setWrap(w: Boolean) { cells[cells.size - 1].wrap = w }
}

/** Direct port of `Terminal::SavedCursor` — the DECSC/DECRC save-cursor state. */
class SavedCursor {
    var cursorCol: Int = 0
    var cursorRow: Int = 0
    var renditions: Renditions = Renditions(0)
    var autoWrapMode: Boolean = true
    var originMode: Boolean = false
}

/**
 * Direct port of `Terminal::DrawState` — cursor position, tab stops, scrolling
 * region, and the "current SGR state new cells get created with" bookkeeping.
 */
class DrawState(width: Int, height: Int) {

    enum class MouseReportingMode(val code: Int) {
        NONE(0), X10(9), VT220(1000), VT220_HILIGHT(1001), BTN_EVENT(1002), ANY_EVENT(1003)
    }

    enum class MouseEncodingMode(val code: Int) {
        DEFAULT(0), UTF8(1005), SGR(1006), URXVT(1015)
    }

    var width: Int = width
        private set
    var height: Int = height
        private set

    var cursorCol: Int = 0
        private set
    var cursorRow: Int = 0
        private set
    var combiningCharCol: Int = 0
        private set
    var combiningCharRow: Int = 0
        private set

    private var defaultTabs = true
    private var tabs = BooleanArray(width)

    var scrollingRegionTopRow: Int = 0
        private set
    var scrollingRegionBottomRow: Int = height - 1
        private set

    var renditions: Renditions = Renditions(0)
    var hyperlink: Hyperlink = Hyperlink.EMPTY
    private var save = SavedCursor()

    var nextPrintWillWrap: Boolean = false
    var originMode: Boolean = false
    var autoWrapMode: Boolean = true
    var insertMode: Boolean = false
    var cursorVisible: Boolean = true
    var reverseVideo: Boolean = false
    var bracketedPaste: Boolean = false
    var mouseReportingMode: MouseReportingMode = MouseReportingMode.NONE
    var mouseFocusEvent: Boolean = false
    var mouseAlternateScroll: Boolean = false
    var mouseEncodingMode: MouseEncodingMode = MouseEncodingMode.DEFAULT
    var applicationModeCursorKeys: Boolean = false

    init {
        reinitializeTabs(0)
    }

    private fun reinitializeTabs(start: Int) {
        for (i in start until tabs.size) tabs[i] = (i % 8) == 0
    }

    private fun newGrapheme() {
        combiningCharCol = cursorCol
        combiningCharRow = cursorRow
    }

    private fun snapCursorToBorder() {
        if (cursorRow < limitTop()) cursorRow = limitTop()
        if (cursorRow > limitBottom()) cursorRow = limitBottom()
        if (cursorCol < 0) cursorCol = 0
        if (cursorCol >= width) cursorCol = width - 1
    }

    fun moveRow(n: Int, relative: Boolean = false) {
        cursorRow = if (relative) cursorRow + n else n + limitTop()
        snapCursorToBorder()
        newGrapheme()
        nextPrintWillWrap = false
    }

    fun moveCol(n: Int, relative: Boolean = false, implicit: Boolean = false) {
        if (implicit) newGrapheme()
        cursorCol = if (relative) cursorCol + n else n
        if (implicit) nextPrintWillWrap = (cursorCol >= width)
        snapCursorToBorder()
        if (!implicit) {
            newGrapheme()
            nextPrintWillWrap = false
        }
    }

    fun setTab() { tabs[cursorCol] = true }
    fun clearTab(col: Int) { tabs[col] = false }
    fun clearDefaultTabs() { defaultTabs = false }

    fun getNextTab(count: Int): Int {
        var c = count
        if (c >= 0) {
            for (i in (cursorCol + 1) until width) {
                if (tabs[i]) { c--; if (c == 0) return i }
            }
            return -1
        }
        for (i in (cursorCol - 1) downTo 1) {
            if (tabs[i]) { c++; if (c == 0) return i }
        }
        return 0
    }

    fun setScrollingRegion(top: Int, bottom: Int) {
        if (height < 1) return
        scrollingRegionTopRow = top
        scrollingRegionBottomRow = bottom
        if (scrollingRegionTopRow < 0) scrollingRegionTopRow = 0
        if (scrollingRegionBottomRow >= height) scrollingRegionBottomRow = height - 1
        // real rule requires TWO-line scrolling region (matches upstream's own comment)
        if (scrollingRegionBottomRow < scrollingRegionTopRow) scrollingRegionBottomRow = scrollingRegionTopRow
        if (originMode) {
            snapCursorToBorder()
            newGrapheme()
        }
    }

    fun limitTop(): Int = if (originMode) scrollingRegionTopRow else 0
    fun limitBottom(): Int = if (originMode) scrollingRegionBottomRow else height - 1

    fun getBackgroundRendition(): ColorType = renditions.getBackgroundRendition()

    fun saveCursor() {
        save.cursorCol = cursorCol
        save.cursorRow = cursorRow
        save.renditions = renditions.copy()
        save.autoWrapMode = autoWrapMode
        save.originMode = originMode
    }

    fun restoreCursor() {
        cursorCol = save.cursorCol
        cursorRow = save.cursorRow
        renditions = save.renditions.copy()
        autoWrapMode = save.autoWrapMode
        originMode = save.originMode
        snapCursorToBorder() // we could have resized in between
        newGrapheme()
    }

    fun clearSavedCursor() { save = SavedCursor() }

    /**
     * NOTE: reinitializeTabs below is intentionally called with the OLD
     * `width` (read before it's reassigned) — this exactly mirrors upstream's
     * own `DrawState::resize`, which calls `reinitialize_tabs(width)` before
     * updating the `width` member. Getting this backwards would silently
     * shift every tab stop by one resize.
     */
    fun resize(newWidth: Int, newHeight: Int) {
        if (width != newWidth || height != newHeight) {
            // reset entire scrolling region on any resize (xterm/rxvt-unicode
            // do this; gnome-terminal only resets if it has to shrink)
            scrollingRegionTopRow = 0
            scrollingRegionBottomRow = newHeight - 1
        }

        val oldWidth = width
        tabs = BooleanArray(newWidth) { i -> if (i < tabs.size) tabs[i] else false }
        if (defaultTabs) reinitializeTabs(oldWidth)

        width = newWidth
        height = newHeight

        snapCursorToBorder()
        // saved cursor will be snapped to border on restore

        if (combiningCharCol >= width || combiningCharRow >= height) {
            combiningCharCol = -1
            combiningCharRow = -1
        }
    }
}

/**
 * Direct port of `Terminal::Framebuffer` — the persistent 2-D grid this
 * app's rendering path will eventually read from once Phase 3 (see the
 * file-level doc) replaces `parseAnsiChunk`.
 */
class Framebuffer(width: Int, height: Int) {
    init {
        require(width > 0) { "width must be > 0" }
        require(height > 0) { "height must be > 0" }
    }

    var ds: DrawState = DrawState(width, height)
        private set

    var rows: MutableList<Row> = MutableList(height) { Row(width, 0) }
        private set

    var iconName: String = ""
    var windowTitle: String = ""
    var clipboard: String = ""
    var bellCount: Int = 0
        private set
    var titleInitialized: Boolean = false
        private set

    private fun newRow(): Row = Row(ds.width, ds.getBackgroundRendition())

    fun getRows(): List<Row> = rows

    fun getRow(row: Int = -1): Row {
        val r = if (row == -1) ds.cursorRow else row
        return rows[r]
    }

    fun getCell(row: Int = -1, col: Int = -1): Cell {
        val r = if (row == -1) ds.cursorRow else row
        val c = if (col == -1) ds.cursorCol else col
        return rows[r].cells[c]
    }

    /**
     * Upstream distinguishes get_row/get_mutable_row for copy-on-write
     * sharing between Framebuffer snapshots (see file-level doc for why
     * that's dropped here) — with plain mutable objects, "mutable" and
     * "immutable" access are the same call. Kept as separate names anyway to
     * keep call sites obviously matched to their upstream counterpart.
     */
    fun getMutableRow(row: Int = -1): Row = getRow(row)
    fun getMutableCell(row: Int = -1, col: Int = -1): Cell = getCell(row, col)

    fun getCombiningCell(): Cell? {
        // can happen if a resize came in between
        if (ds.combiningCharCol < 0 || ds.combiningCharRow < 0 ||
            ds.combiningCharCol >= ds.width || ds.combiningCharRow >= ds.height
        ) return null
        return getMutableCell(ds.combiningCharRow, ds.combiningCharCol)
    }

    fun applyRenditionsToCell(cell: Cell? = null) {
        val c = cell ?: getMutableCell()
        c.renditions = ds.renditions.copy()
    }

    fun applyHyperlinkToCell(cell: Cell? = null) {
        val c = cell ?: getMutableCell()
        c.hyperlink = ds.hyperlink
    }

    fun scroll(n: Int) {
        if (n >= 0) deleteLine(ds.scrollingRegionTopRow, n) else insertLine(ds.scrollingRegionTopRow, -n)
    }

    fun moveRowsAutoscroll(rowDelta: Int) {
        // don't scroll if outside the scrolling region
        if (ds.cursorRow < ds.scrollingRegionTopRow || ds.cursorRow > ds.scrollingRegionBottomRow) {
            ds.moveRow(rowDelta, relative = true)
            return
        }
        if (ds.cursorRow + rowDelta > ds.scrollingRegionBottomRow) {
            val n = ds.cursorRow + rowDelta - ds.scrollingRegionBottomRow
            scroll(n)
            ds.moveRow(-n, relative = true)
        } else if (ds.cursorRow + rowDelta < ds.scrollingRegionTopRow) {
            val n = ds.cursorRow + rowDelta - ds.scrollingRegionTopRow
            scroll(n)
            ds.moveRow(-n, relative = true)
        }
        ds.moveRow(rowDelta, relative = true)
    }

    /**
     * Inserts [count] blank rows before [beforeRow], scrolling the bottom of
     * the scrolling region off. See the file-level doc for why the second
     * (delete-old-rows) offset below is deliberately computed against the
     * list's state at that point in the method, exactly mirroring upstream's
     * iterator arithmetic.
     */
    fun insertLine(beforeRow: Int, count: Int) {
        if (beforeRow < ds.scrollingRegionTopRow || beforeRow > ds.scrollingRegionBottomRow + 1) return
        var scrollCount = ds.scrollingRegionBottomRow + 1 - beforeRow
        if (count < scrollCount) scrollCount = count
        if (scrollCount == 0) return

        // delete old rows (from the bottom of the scrolling region)
        val deleteStart = ds.scrollingRegionBottomRow + 1 - scrollCount
        repeat(scrollCount) { rows.removeAt(deleteStart) }
        // insert new blank rows at beforeRow
        repeat(scrollCount) { rows.add(beforeRow, newRow()) }
    }

    fun deleteLine(row: Int, count: Int) {
        if (row < ds.scrollingRegionTopRow || row > ds.scrollingRegionBottomRow) return
        var scrollCount = ds.scrollingRegionBottomRow + 1 - row
        if (count < scrollCount) scrollCount = count
        if (scrollCount == 0) return

        repeat(scrollCount) { rows.removeAt(row) }
        // insert a block of dummy rows at the bottom of the (now-shrunk) scrolling region
        val insertAt = ds.scrollingRegionBottomRow + 1 - scrollCount
        repeat(scrollCount) { rows.add(insertAt, newRow()) }
    }

    fun insertCell(row: Int, col: Int) {
        getMutableRow(row).insertCell(col, ds.getBackgroundRendition())
    }

    fun deleteCell(row: Int, col: Int) {
        getMutableRow(row).deleteCell(col, ds.getBackgroundRendition())
    }

    fun reset() {
        val w = ds.width
        val h = ds.height
        ds = DrawState(w, h)
        rows = MutableList(h) { newRow() }
        windowTitle = ""
        clipboard = ""
        // do not reset bellCount (matches upstream's own comment)
    }

    fun softReset() {
        ds.insertMode = false
        ds.originMode = false
        ds.cursorVisible = true // per xterm and gnome-terminal
        ds.applicationModeCursorKeys = false
        ds.setScrollingRegion(0, ds.height - 1)
        ds.renditions.setRendition(0)
        ds.hyperlink = Hyperlink.EMPTY
        ds.clearSavedCursor()
    }

    fun setTitleInitialized() { titleInitialized = true }
    fun ringBell() { bellCount++ }

    fun prefixWindowTitle(s: String) {
        if (iconName == windowTitle) {
            // preserve equivalence
            iconName = s + iconName
        }
        windowTitle = s + windowTitle
    }

    fun resize(newWidth: Int, newHeight: Int) {
        require(newWidth > 0) { "width must be > 0" }
        require(newHeight > 0) { "height must be > 0" }

        val oldHeight = ds.height
        val oldWidth = ds.width
        ds.resize(newWidth, newHeight)

        if (oldHeight != newHeight) {
            if (newHeight > rows.size) {
                repeat(newHeight - rows.size) { rows.add(newRow()) }
            } else {
                while (rows.size > newHeight) rows.removeAt(rows.size - 1)
            }
        }
        if (oldWidth == newWidth) return

        for (row in rows) {
            row.setWrap(false)
            val cells = row.cells
            when {
                cells.size < newWidth -> repeat(newWidth - cells.size) { cells.add(Cell(ds.getBackgroundRendition())) }
                cells.size > newWidth -> while (cells.size > newWidth) cells.removeAt(cells.size - 1)
            }
        }
    }
}
