package com.systemsgo.hex.mosh.protocol

import com.systemsgo.hex.mosh.protocol.framebuffer.Cell
import com.systemsgo.hex.mosh.protocol.framebuffer.Framebuffer
import com.systemsgo.hex.mosh.protocol.framebuffer.TerminalEmulator
import com.systemsgo.hex.mosh.protocol.framebuffer.VtAction
import com.systemsgo.hex.mosh.protocol.framebuffer.VtParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FULL-FRAMEBUFFER-PARITY, step 4 of 4 (see mosh/NOTES.md's migration path —
 * steps 1-3, the ported [com.systemsgo.hex.mosh.protocol.framebuffer.Framebuffer]/
 * parser/dispatcher and TerminalScreen's framebuffer-based renderer, are done
 * and stable; this file is the step described there as "port PredictionEngine
 * itself 1:1 ... since it needs step 1-2's Framebuffer to predict/validate
 * against").
 *
 * This supersedes the previous line-local, string-prefix-matching engine with
 * a faithful port of upstream mosh's real `PredictionEngine`
 * (src/frontend/terminaloverlay.h/.cc): predictions are now individual
 * [Cell] objects at (row, col) positions in a real [Framebuffer] this class
 * owns and mirrors from confirmed server output, validated cell-by-cell —
 * exactly like `ConditionalOverlayCell::get_validity` — instead of matching
 * a flat pending-text string against a prefix of the next server chunk.
 * This is what unlocks predicting arrow-key cursor moves and mid-line edits
 * (Backspace/insert in the middle of an already-synced line), not just
 * "append after the last confirmed character".
 *
 * ARCHITECTURE — why this class owns its OWN [TerminalEmulator]:
 * Upstream mosh has exactly one client-side `Framebuffer`: `STMClient`'s
 * `local_framebuffer`, which is fed confirmed server bytes AND is the same
 * object the overlay manager paints predictions onto before it's drawn. This
 * app's rendering framebuffer (the one `TerminalScreen`'s Phase-3 renderer
 * reads from) lives one layer up, in `RdpSessionViewModel` -- outside this
 * class, outside this package. Rather than reach into that (which would mean
 * this protocol-layer class depending on a ui-layer/viewmodel type, and would
 * require the two to be resized/fed in perfect lockstep to stay bit-for-bit
 * identical), [confirmedTerminal] is a second, independent [TerminalEmulator]
 * instance that mirrors the SAME confirmed byte stream ([onServerText] is not
 * a new byte source -- it's the exact call MoshSessionClient already made,
 * this is just a second consumer of it) and is kept the same size via
 * [onResize] (see the doc comment there and in MoshSessionClient.kt for the
 * one small, necessary call-site addition this requires). Fed identically,
 * the two framebuffers are always identical in content, so validating
 * predictions against this private copy is equivalent to validating them
 * against the "real" one, without this class needing to import anything from
 * the ui/viewmodel layer.
 *
 * WHAT'S PORTED 1:1 FROM `terminaloverlay.h`/`.cc`:
 * - [ConditionalOverlayCellPort]/[ConditionalCursorMovePort]/[PredictionRow]
 *   mirror `ConditionalOverlayCell`/`ConditionalCursorMove`/`ConditionalOverlayRow`
 *   exactly, including `reset()` vs `reset_with_orig()`'s different treatment
 *   of `unknown`/`original_contents`, and `get_validity`'s exact Pending /
 *   Correct / CorrectNoCredit / IncorrectOrExpired / Inactive rules (the
 *   `original_contents` de-dup that denies "credit" for a predicted
 *   character that only "matches" because it happens to equal what was
 *   already there -- see [ConditionalOverlayCellPort.getValidity]).
 * - [handlePrint]/[handleUserAction] is a line-for-line port of
 *   `PredictionEngine::new_user_byte`'s per-Action-type switch (the
 *   `typeid(...)` chain in the .cc file): Backspace's "shift remaining cells
 *   left, borrowing the actual/predicted contents of the cell to the right"
 *   logic, ordinary printable-character insertion's "shift cells right,
 *   borrowing from the cell to the left" logic (this is exactly what makes
 *   mid-line edits and cursor-relative typing safe to predict), the
 *   "heuristic: match renditions of character to the left", right/left
 *   arrow (`CSI C` / `CSI D`) cursor-only predictions, and `become_tentative()`
 *   for every CR/other-Execute/Esc-dispatch/other-CSI case upstream also
 *   can't safely predict -- ARROW KEYS ARE THE ONLY CSI SEQUENCES THIS
 *   PREDICTS, exactly like upstream; every other CSI/escape sequence the
 *   user could type falls back to tentative, matching upstream exactly (see
 *   the mandatory constraint this task was given not to invent predictive
 *   behavior mosh itself doesn't have).
 * - [cullOverlays]/[killEpoch]/[becomeTentative]/[resetState]/[initCursor]/
 *   [newlineCarriageReturn]/[getOrMakeRow]/[active] port `cull`/`kill_epoch`/
 *   `become_tentative`/`reset`/`init_cursor`/`newline_carriage_return`/
 *   `get_or_make_row`/`active` respectively, including `cull`'s "match rest
 *   of row to the actual renditions" step on a Correct validation and the
 *   scroll-avoidance comment upstream itself left in
 *   `newline_carriage_return` ("Don't try to predict scroll until we have
 *   versioned cell predictions" -- still true here, so a newline on the last
 *   row still only blanks that row's own predictions, exactly like upstream,
 *   rather than inventing a scroll prediction upstream itself doesn't do).
 * - The SRTT/flagging trigger constants and hysteresis in [onRttSampleMs]
 *   are UNCHANGED from the previous version of this file (already ported
 *   correctly and verified against upstream -- this task's brief explicitly
 *   said not to re-touch this part). The only edit there is swapping the
 *   old flat `pending.isEmpty()` emptiness check for the new [active] (no
 *   cell/cursor prediction currently outstanding) -- a direct, mechanical
 *   adaptation to the new data model, not a behavior change.
 *
 * DOCUMENTED SIMPLIFICATIONS (deliberate, and why each is safe):
 *
 * 1. Upstream's `local_frame_sent`/`local_frame_acked`/`local_frame_late_acked`
 *    are actual SSP transport diff-sequence numbers, updated by `STMClient`
 *    from the live network layer every time a state diff is sent/acked.
 *    Wiring THAT would mean reaching into MoshTransport/MoshWireProtocol --
 *    explicitly off-limits for this task ("لا تلمس MoshWireProtocol.kt").
 *    Instead, [localFrameSent] is a monotonic counter ticked once per
 *    [onRttSampleMs] call (already invoked every ~50ms tick by
 *    MoshSessionClient's tickLoop, tick or no typing -- the same cadence
 *    upstream's continuous diff-sending provides) and [localFrameAcked] is
 *    set to the CURRENT [localFrameSent] value whenever [onServerText]
 *    delivers a fresh confirmed chunk (i.e. "at least one round-trip's worth
 *    of time has passed since this was predicted, AND the server just
 *    spoke" -- the same "don't validate before the server had a chance to
 *    touch this cell" guarantee upstream's real ack-number gate provides,
 *    just measured in ticks-since-prediction instead of diff-sequence
 *    numbers since the latter isn't available to this class without the
 *    off-limits transport change). This can never grant false credit for a
 *    WRONG prediction -- validity is still decided by comparing actual
 *    [Cell] contents ([ConditionalOverlayCellPort.getValidity]); it only
 *    affects how soon a correct prediction is confirmed as "Correct" versus
 *    still "Pending".
 * 2. Upstream's own keystroke re-parsing (`Parser::UTF8Parser parser`
 *    member of `PredictionEngine`, fed raw bytes by `new_user_byte`) is
 *    reused here as-is: [outgoingParser] is a plain [VtParser] instance (the
 *    exact same parser class this app already uses for SERVER bytes, since
 *    it's upstream's identical DEC-ANSI state machine either direction) fed
 *    the outgoing keystroke bytes one at a time, including the same
 *    SS3-to-CSI ('O' -> '[') application-cursor-mode translation upstream
 *    applies before parsing (see [feedOutgoingByte]) -- this is what lets an
 *    arrow key sent as either `ESC [ C`/`ESC O C` be recognized as "right
 *    arrow" for prediction purposes regardless of which mode `SshKeyMap`
 *    encoded it in.
 * 3. Non-ASCII outgoing keystrokes (wide/combining characters an IME might
 *    produce) are conservatively treated as "unknown print" -> tentative,
 *    same as any other byte this class can't safely predict -- upstream
 *    calls libc's locale-aware `wcwidth()` here too and predicts only
 *    `wcwidth()==1` characters; duplicating a full Unicode width table for
 *    the OUTGOING side (a human typing at a keyboard, essentially always
 *    ASCII) wasn't judged worth the size given [TerminalEmulator.kt] already
 *    has one for the SERVER (rendering) side where it actually matters.
 * 4. [Overlay] keeps its previous flat shape ([pendingText]/[visible]/
 *    [underlined]) so MoshSessionClient.kt's existing field and
 *    RdpSessionActivity.kt/TerminalScreen.kt's existing consumption of it
 *    (`predictionOverlay?.pendingText` etc -- verified by inspection, zero
 *    other call sites read anything else off this class) keep compiling
 *    completely unchanged. [pendingText] is populated only for the exact
 *    case the OLD engine already covered (a contiguous run of predicted
 *    cells starting exactly at the confirmed cursor's row/column, i.e.
 *    ordinary forward typing with no arrow-key/mid-line edit in play) via
 *    [simpleLinearSuffix] -- never a wrong guess at rendering a cursor move
 *    or mid-line edit as if it were a trailing suffix. The full, honest
 *    per-cell picture (needed to render arrow-key/mid-line predictions
 *    correctly) is additionally exposed via [Overlay.cells]/[Overlay.cursor]
 *    for whenever TerminalScreen.kt is updated with a real per-cell overlay
 *    renderer -- deliberately NOT done in this pass, for the same reason
 *    mosh/NOTES.md gave for not doing the (much larger) Phase-3 UI wiring
 *    blind in one pass: TerminalScreen.kt is a large, already-reviewed
 *    Compose file, and wiring a cell-accurate renderer is a separate,
 *    focused change in its own right, not a required acceptance criterion
 *    for this pass (which is about the ENGINE's predictions being correct,
 *    not about a new visual layer to show them).
 */
class MoshPredictionEngine(initialCols: Int = 100, initialRows: Int = 32) {

    enum class DisplayPreference { ALWAYS, NEVER, ADAPTIVE }

    /** One predicted character at a specific screen cell, for a future cell-accurate renderer. */
    data class PredictedCell(val row: Int, val col: Int, val text: String, val underlined: Boolean)

    /** A predicted cursor position (e.g. after a right/left-arrow keystroke), same purpose as [PredictedCell]. */
    data class PredictedCursor(val row: Int, val col: Int)

    /**
     * What TerminalScreen should render as an overlay after the confirmed stream.
     * [pendingText]/[visible]/[underlined] are the pre-existing fields every current
     * caller reads (see the class doc's simplification #4). [cells]/[cursor] are the
     * new, full per-cell picture -- unused by any call site today, present for the
     * eventual cell-accurate renderer.
     */
    data class Overlay(
        val pendingText: String,
        val visible: Boolean,
        val underlined: Boolean,
        val cells: List<PredictedCell> = emptyList(),
        val cursor: PredictedCursor? = null,
    )

    /** Port of `Overlay::Validity`. */
    private enum class Validity { PENDING, CORRECT, CORRECT_NO_CREDIT, INCORRECT_OR_EXPIRED, INACTIVE }

    companion object {
        // Ported verbatim from terminaloverlay.h's PredictionEngine constants -- untouched by this pass.
        private const val SRTT_TRIGGER_LOW = 20L   // <= ms cures SRTT trigger (stop showing predictions)
        private const val SRTT_TRIGGER_HIGH = 30L  // >  ms starts SRTT trigger (start showing predictions)
        private const val FLAG_TRIGGER_LOW = 50L   // <= ms cures flagging (stop underlining)
        private const val FLAG_TRIGGER_HIGH = 80L  // >  ms starts flagging (start underlining)
    }

    private val lock = Any()

    // --- Port of ConditionalOverlay / ConditionalOverlayCell -----------------------------------

    /** Port of `ConditionalOverlayCell` (a single predicted cell within a [PredictionRow]). */
    private class ConditionalOverlayCellPort(val col: Int, initialTentativeUntilEpoch: Long) {
        var expirationFrame: Long = Long.MAX_VALUE
        var active: Boolean = false
        var tentativeUntilEpoch: Long = initialTentativeUntilEpoch
        var predictionTime: Long = Long.MAX_VALUE

        /** Upstream's `replacement` -- the predicted contents/renditions for this cell. */
        var replacement: Cell = Cell(0)

        /** Upstream's `unknown` -- true when we know a cell WILL change but not what to. */
        var unknown: Boolean = false

        /** Upstream's `original_contents` -- denies "credit" for a prediction that merely
         *  happens to match what was already there before we predicted anything. */
        val originalContents: MutableList<Cell> = mutableListOf()

        fun tentative(confirmedEpoch: Long): Boolean = tentativeUntilEpoch > confirmedEpoch

        fun expire(sExp: Long, now: Long) {
            expirationFrame = sExp
            predictionTime = now
        }

        private fun baseReset() {
            expirationFrame = Long.MAX_VALUE
            tentativeUntilEpoch = Long.MAX_VALUE
            active = false
        }

        fun reset() {
            unknown = false
            originalContents.clear()
            baseReset()
        }

        /** Port of `ConditionalOverlayCell::reset_with_orig`. */
        fun resetWithOrig() {
            if (!active || unknown) {
                reset()
                return
            }
            originalContents.add(replacement.copy())
            baseReset()
        }

        /** Port of `ConditionalOverlayCell::get_validity`. [row] comes from the owning [PredictionRow]. */
        fun getValidity(fb: Framebuffer, row: Int, lateAck: Long): Validity {
            if (!active) return Validity.INACTIVE
            if (row >= fb.ds.height || col >= fb.ds.width) return Validity.INCORRECT_OR_EXPIRED

            val current = fb.getCell(row, col)

            if (lateAck < expirationFrame) return Validity.PENDING
            if (unknown) return Validity.CORRECT_NO_CREDIT
            if (replacement.isBlank()) return Validity.CORRECT_NO_CREDIT // too easy for this to trigger falsely

            if (current.contentsMatch(replacement)) {
                val deniedCredit = originalContents.any { it.contentsMatch(replacement) }
                return if (!deniedCredit) Validity.CORRECT else Validity.CORRECT_NO_CREDIT
            }
            return Validity.INCORRECT_OR_EXPIRED
        }
    }

    /** Port of `ConditionalOverlayRow`. */
    private class PredictionRow(val rowNum: Int) {
        val cells: MutableList<ConditionalOverlayCellPort> = mutableListOf()
    }

    /** Port of `ConditionalCursorMove`. */
    private class ConditionalCursorMovePort(var row: Int, var col: Int, initialTentativeUntilEpoch: Long) {
        var expirationFrame: Long = Long.MAX_VALUE
        var active: Boolean = false
        var tentativeUntilEpoch: Long = initialTentativeUntilEpoch

        fun tentative(confirmedEpoch: Long): Boolean = tentativeUntilEpoch > confirmedEpoch
        fun expire(sExp: Long) { expirationFrame = sExp }

        /** Port of `ConditionalCursorMove::get_validity`. */
        fun getValidity(fb: Framebuffer, lateAck: Long): Validity {
            if (!active) return Validity.INACTIVE
            if (row >= fb.ds.height || col >= fb.ds.width) return Validity.INCORRECT_OR_EXPIRED
            if (lateAck >= expirationFrame) {
                return if (fb.ds.cursorRow == row && fb.ds.cursorCol == col) Validity.CORRECT else Validity.INCORRECT_OR_EXPIRED
            }
            return Validity.PENDING
        }
    }

    // --- Engine state ---------------------------------------------------------------------------

    /**
     * Our own mirror of confirmed server state -- see the class doc's "ARCHITECTURE" section for
     * why this is a second, independent [TerminalEmulator] rather than the render-path one.
     */
    private var confirmedTerminal = TerminalEmulator(initialCols, initialRows)
    private var lastWidth = initialCols
    private var lastHeight = initialRows

    /** Upstream's `Parser::UTF8Parser parser` member -- reused here to interpret the user's OWN
     *  outgoing keystroke bytes (see the class doc's simplification #2). */
    private val outgoingParser = VtParser()
    private var lastByte: Int = 0

    private val overlays: MutableList<PredictionRow> = mutableListOf()
    private val cursors: MutableList<ConditionalCursorMovePort> = mutableListOf()

    private var predictionEpoch: Long = 1
    private var confirmedEpoch: Long = 0

    // See class doc simplification #1 for why these stand in for upstream's real SSP frame numbers.
    private var localFrameSent: Long = 0
    private var localFrameAcked: Long = 0

    private var displayPreference = DisplayPreference.ADAPTIVE
    private var srttTrigger = false
    private var flagging = false

    private val _overlay = MutableStateFlow(Overlay("", visible = false, underlined = false))
    val overlay: StateFlow<Overlay> = _overlay.asStateFlow()

    fun setDisplayPreference(pref: DisplayPreference) {
        synchronized(lock) {
            displayPreference = pref
            publish()
        }
    }

    /**
     * Feed the current smoothed RTT (ms). Mirrors STMClient::process_network_input's
     * `set_send_interval` call driving PredictionEngine::cull()'s hysteresis (untouched
     * from the previous version of this file), and ALSO ticks [localFrameSent] once per
     * call -- see the class doc's simplification #1 for why this is the stand-in for
     * upstream's real per-diff SSP frame counter.
     */
    fun onRttSampleMs(rttMs: Long) {
        synchronized(lock) {
            localFrameSent++
            if (rttMs > SRTT_TRIGGER_HIGH) {
                srttTrigger = true
            } else if (srttTrigger && rttMs <= SRTT_TRIGGER_LOW && !active()) {
                // upstream only clears the trigger "when no predictions being shown"
                srttTrigger = false
            }
            flagging = if (rttMs > FLAG_TRIGGER_HIGH) true else if (rttMs <= FLAG_TRIGGER_LOW) false else flagging
            publish()
        }
    }

    /**
     * Notify the engine the terminal was resized. NEW call site required in
     * MoshSessionClient.kt's `queueResize` (documented there) -- without it this
     * class's private [confirmedTerminal] would silently diverge in size from the
     * real session, corrupting every subsequent cell-position validation. Mirrors
     * upstream's `cull()` detecting `last_height`/`last_width` changing and calling
     * `reset()`, except driven directly by the real resize event instead of being
     * inferred from watching the framebuffer's own size drift across calls.
     */
    fun onResize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        synchronized(lock) {
            if (cols == lastWidth && rows == lastHeight) return@synchronized
            lastWidth = cols
            lastHeight = rows
            confirmedTerminal.resize(cols, rows)
            resetState()
            publish()
        }
    }

    /** Mirrors PredictionEngine::new_user_byte for outgoing keystroke text. */
    fun onOutgoingText(text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            if (displayPreference == DisplayPreference.NEVER) return@synchronized
            val fb = confirmedTerminal.framebuffer
            var i = 0
            while (i < text.length) {
                val codepoint = text.codePointAt(i)
                i += Character.charCount(codepoint)
                feedOutgoingCodepoint(codepoint, fb)
            }
            publish()
        }
    }

    /** Mirrors PredictionEngine::new_user_byte for a single outgoing raw byte
     *  (e.g. Backspace 0x7f, or a control byte like Ctrl+C). */
    fun onOutgoingControlByte(byte: Int) {
        synchronized(lock) {
            if (displayPreference == DisplayPreference.NEVER) return@synchronized
            feedOutgoingByte(byte and 0xFF, confirmedTerminal.framebuffer)
            publish()
        }
    }

    /** Bulk/paste input: mosh's STMClient::process_user_input resets predictions
     *  outright for reads over 100 bytes ("Don't predict for bulk data"). */
    fun onOutgoingBulk() = reset()

    /**
     * Reconcile against confirmed server output. Feeds [text] into our own mirrored
     * [confirmedTerminal] (see the class doc's ARCHITECTURE section) and then runs
     * [cullOverlays] -- the real cell-by-cell equivalent of
     * `PredictionEngine::cull()`'s per-cell Correct/IncorrectOrExpired validity
     * check, replacing the previous version's flat string-prefix matching.
     */
    fun onServerText(text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            confirmedTerminal.processServerBytes(text.toByteArray(Charsets.UTF_8))
            // See class doc simplification #1: a fresh confirmed chunk just arrived,
            // so everything sent up to the current tick is now "acked" as far as this
            // class's simplified frame counter is concerned.
            localFrameAcked = localFrameSent
            cullOverlays(confirmedTerminal.framebuffer)
            publish()
        }
    }

    fun reset() {
        synchronized(lock) {
            resetState()
            publish()
        }
    }

    // --- Internal port of PredictionEngine's private methods -----------------------------------

    /** Port of `PredictionEngine::reset`. */
    private fun resetState() {
        cursors.clear()
        overlays.clear()
        becomeTentative()
    }

    /** Port of `PredictionEngine::active`. */
    private fun active(): Boolean {
        if (cursors.isNotEmpty()) return true
        return overlays.any { row -> row.cells.any { it.active } }
    }

    /** Port of `PredictionEngine::become_tentative`. */
    private fun becomeTentative() {
        predictionEpoch++
    }

    private fun cursorLast(): ConditionalCursorMovePort = cursors[cursors.size - 1]

    /** Port of `PredictionEngine::init_cursor`. */
    private fun initCursor(fb: Framebuffer) {
        if (cursors.isEmpty()) {
            cursors.add(ConditionalCursorMovePort(fb.ds.cursorRow, fb.ds.cursorCol, predictionEpoch).also {
                it.expire(localFrameSent + 1)
            })
            cursorLast().active = true
        } else if (cursorLast().tentativeUntilEpoch != predictionEpoch) {
            val c = cursorLast()
            cursors.add(ConditionalCursorMovePort(c.row, c.col, predictionEpoch).also { it.expire(localFrameSent + 1) })
            cursorLast().active = true
        }
    }

    /** Port of `PredictionEngine::get_or_make_row`. */
    private fun getOrMakeRow(rowNum: Int, numCols: Int): PredictionRow {
        for (row in overlays) if (row.rowNum == rowNum) return row
        val row = PredictionRow(rowNum)
        for (i in 0 until numCols) row.cells.add(ConditionalOverlayCellPort(i, predictionEpoch))
        overlays.add(row)
        return row
    }

    /** Port of `PredictionEngine::kill_epoch`. */
    private fun killEpoch(epoch: Long, fb: Framebuffer) {
        cursors.removeAll { it.tentative(epoch - 1) }

        cursors.add(ConditionalCursorMovePort(fb.ds.cursorRow, fb.ds.cursorCol, predictionEpoch).also {
            it.expire(localFrameSent + 1)
        })
        cursorLast().active = true

        for (row in overlays) {
            for (cell in row.cells) {
                if (cell.tentative(epoch - 1)) cell.reset()
            }
        }

        becomeTentative()
    }

    /** Port of `PredictionEngine::newline_carriage_return`. */
    private fun newlineCarriageReturn(fb: Framebuffer) {
        val now = System.currentTimeMillis()
        initCursor(fb)
        cursorLast().col = 0
        if (cursorLast().row == fb.ds.height - 1) {
            // "Don't try to predict scroll until we have versioned cell predictions" --
            // upstream's own comment; only blank this row's predictions, never scroll.
            val row = getOrMakeRow(cursorLast().row, fb.ds.width)
            for (cell in row.cells) {
                cell.active = true
                cell.tentativeUntilEpoch = predictionEpoch
                cell.expire(localFrameSent + 1, now)
                cell.replacement.clear()
            }
        } else {
            cursorLast().row = cursorLast().row + 1
        }
    }

    /** Port of `PredictionEngine::cull`'s cell/cursor-validity pass (the SRTT/flagging
     *  hysteresis half of upstream's `cull()` stays in [onRttSampleMs], untouched). */
    private fun cullOverlays(fb: Framebuffer) {
        val rowIterator = overlays.iterator()
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()
            if (row.rowNum < 0 || row.rowNum >= fb.ds.height) {
                rowIterator.remove()
                continue
            }

            for (index in row.cells.indices) {
                val cell = row.cells[index]
                when (cell.getValidity(fb, row.rowNum, localFrameAcked)) {
                    Validity.INCORRECT_OR_EXPIRED -> {
                        if (cell.tentative(confirmedEpoch)) {
                            killEpoch(cell.tentativeUntilEpoch, fb)
                        } else {
                            resetState()
                            return
                        }
                    }
                    Validity.CORRECT -> {
                        if (cell.tentativeUntilEpoch > confirmedEpoch) confirmedEpoch = cell.tentativeUntilEpoch
                        // "match rest of row to the actual renditions"
                        val actualRenditions = fb.getCell(row.rowNum, cell.col).renditions.copy()
                        for (k in index until row.cells.size) {
                            row.cells[k].replacement.renditions = actualRenditions.copy()
                        }
                        cell.reset()
                    }
                    Validity.CORRECT_NO_CREDIT -> cell.reset()
                    Validity.PENDING, Validity.INACTIVE -> { /* nothing to do -- no glitch-trigger bookkeeping in this port */ }
                }
            }
        }

        if (cursors.isNotEmpty() && cursorLast().getValidity(fb, localFrameAcked) == Validity.INCORRECT_OR_EXPIRED) {
            resetState()
            return
        }
        cursors.removeAll { it.getValidity(fb, localFrameAcked) != Validity.PENDING }
    }

    // --- Port of PredictionEngine::new_user_byte's per-Action-type switch -----------------------

    /**
     * Feed one outgoing Unicode code point (from [onOutgoingText]'s string). UTF-8 re-encodes it
     * to raw bytes since [VtParser] (like upstream's own parser) only takes bytes -- see the class
     * doc's simplification #2.
     */
    private fun feedOutgoingCodepoint(codepoint: Int, fb: Framebuffer) {
        val bytes = String(Character.toChars(codepoint)).toByteArray(Charsets.UTF_8)
        for (b in bytes) feedOutgoingByte(b.toInt() and 0xFF, fb)
    }

    /** Port of the per-byte body of `PredictionEngine::new_user_byte` (the `cull(fb)` call at the
     *  top, the SS3->CSI translation, and dispatch of the resulting action(s)). */
    private fun feedOutgoingByte(byteIn: Int, fb: Framebuffer) {
        cullOverlays(fb)

        var byte = byteIn
        // translate application-mode cursor control function to ANSI cursor control sequence
        if (lastByte == 0x1b && byte == 'O'.code) byte = '['.code
        lastByte = byte

        val actions = ArrayList<VtAction>()
        outgoingParser.input(byte, actions)
        val now = System.currentTimeMillis()
        for (action in actions) handleUserAction(action, fb, now)
    }

    private fun handleUserAction(action: VtAction, fb: Framebuffer, now: Long) {
        when (action) {
            is VtAction.Print -> handlePrint(action, fb, now)
            is VtAction.Execute -> {
                if (action.charPresent && action.ch == 0x0d) { // CR
                    becomeTentative()
                    newlineCarriageReturn(fb)
                } else {
                    becomeTentative()
                }
            }
            is VtAction.EscDispatch -> becomeTentative()
            is VtAction.CsiDispatch -> {
                if (action.charPresent && action.ch == 'C'.code) { // right arrow
                    initCursor(fb)
                    val c = cursorLast()
                    if (c.col < fb.ds.width - 1) {
                        c.col++
                        c.expire(localFrameSent + 1)
                    }
                } else if (action.charPresent && action.ch == 'D'.code) { // left arrow
                    initCursor(fb)
                    val c = cursorLast()
                    if (c.col > 0) {
                        c.col--
                        c.expire(localFrameSent + 1)
                    }
                } else {
                    // every other CSI sequence: upstream can't safely predict it either.
                    becomeTentative()
                }
            }
            // Clear/Collect/Param/Hook/Put/Unhook/OscStart/OscPut/OscEnd: upstream's
            // new_user_byte only switches on Print/Execute/Esc_Dispatch/CSI_Dispatch by
            // RTTI; every other Action subtype falls through with no effect.
            else -> { /* intentionally no-op */ }
        }
    }

    /** Port of the `Parser::Print` branch of `PredictionEngine::new_user_byte`. */
    private fun handlePrint(action: VtAction.Print, fb: Framebuffer, now: Long) {
        initCursor(fb)
        val ch = action.ch
        val cur = cursorLast()

        if (ch == 0x7f) { // Backspace
            val row = getOrMakeRow(cur.row, fb.ds.width)
            if (cur.col > 0) {
                cur.col--
                cur.expire(localFrameSent + 1)
                var i = cur.col
                while (i < fb.ds.width) {
                    val cell = row.cells[i]
                    cell.resetWithOrig()
                    cell.active = true
                    cell.tentativeUntilEpoch = predictionEpoch
                    cell.expire(localFrameSent + 1, now)
                    cell.originalContents.add(fb.getCell(cur.row, i).copy())

                    if (i + 2 < fb.ds.width) {
                        val nextCell = row.cells[i + 1]
                        val nextCellActual = fb.getCell(cur.row, i + 1)
                        if (nextCell.active) {
                            if (nextCell.unknown) {
                                cell.unknown = true
                            } else {
                                cell.unknown = false
                                cell.replacement = nextCell.replacement.copy()
                            }
                        } else {
                            cell.unknown = false
                            cell.replacement = nextCellActual.copy()
                        }
                    } else {
                        cell.unknown = true
                    }
                    i++
                }
            }
            return
        }

        if (ch < 0x20 || ch > 0x7e) {
            // "unknown print" -- control byte, or (see class doc simplification #3)
            // any non-ASCII outgoing keystroke.
            becomeTentative()
            return
        }

        val row = getOrMakeRow(cur.row, fb.ds.width)

        if (cur.col + 1 >= fb.ds.width) {
            // prediction in the last column is tricky (e.g. wrap behavior varies by
            // app) -- become tentative, but STILL predict the character below, exactly
            // like upstream does (no early return here).
            becomeTentative()
        }

        val rightmostColumn = fb.ds.width - 1
        var i = rightmostColumn
        while (i > cur.col) {
            val cell = row.cells[i]
            cell.resetWithOrig()
            cell.active = true
            cell.tentativeUntilEpoch = predictionEpoch
            cell.expire(localFrameSent + 1, now)
            cell.originalContents.add(fb.getCell(cur.row, i).copy())

            val prevCell = row.cells[i - 1]
            val prevCellActual = fb.getCell(cur.row, i - 1)
            if (i == fb.ds.width - 1) {
                cell.unknown = true
            } else if (prevCell.active) {
                if (prevCell.unknown) {
                    cell.unknown = true
                } else {
                    cell.unknown = false
                    cell.replacement = prevCell.replacement.copy()
                }
            } else {
                cell.unknown = false
                cell.replacement = prevCellActual.copy()
            }
            i--
        }

        val cell = row.cells[cur.col]
        cell.resetWithOrig()
        cell.active = true
        cell.tentativeUntilEpoch = predictionEpoch
        cell.expire(localFrameSent + 1, now)
        cell.replacement.renditions = fb.ds.renditions.copy()

        // heuristic: match renditions of character to the left
        if (cur.col > 0) {
            val prevCell = row.cells[cur.col - 1]
            val prevCellActual = fb.getCell(cur.row, cur.col - 1)
            cell.replacement.renditions = if (prevCell.active && !prevCell.unknown) {
                prevCell.replacement.renditions.copy()
            } else {
                prevCellActual.renditions.copy()
            }
        }

        cell.replacement.clear()
        cell.replacement.append(codePointToString(ch))
        cell.originalContents.add(fb.getCell(cur.row, cur.col).copy())

        cur.expire(localFrameSent + 1)

        if (cur.col < fb.ds.width - 1) {
            cur.col++
        } else {
            becomeTentative()
            newlineCarriageReturn(fb)
        }
    }

    // --- Overlay computation (port of PredictionEngine::apply / OverlayManager::apply) ----------

    private fun publish() {
        _overlay.value = computeOverlaySnapshot()
    }

    /** Port of `PredictionEngine::apply` -- instead of mutating a shared render
     *  [Framebuffer] in place, builds a read-only snapshot of what WOULD be drawn. */
    private fun computeOverlaySnapshot(): Overlay {
        val show = displayPreference != DisplayPreference.NEVER &&
            (displayPreference == DisplayPreference.ALWAYS || srttTrigger)
        if (!show) return Overlay(pendingText = "", visible = false, underlined = flagging)

        val fb = confirmedTerminal.framebuffer
        val predictedCells = mutableListOf<PredictedCell>()

        for (row in overlays) {
            if (row.rowNum < 0 || row.rowNum >= fb.ds.height) continue
            for (cell in row.cells) {
                if (!cell.active || cell.col >= fb.ds.width) continue
                if (cell.tentative(confirmedEpoch)) continue // upstream: apply() returns early for tentative cells

                val confirmedCell = fb.getCell(row.rowNum, cell.col)
                var underline = flagging
                if (cell.replacement.isBlank() && confirmedCell.isBlank()) underline = false

                if (cell.unknown) {
                    if (underline && cell.col != fb.ds.width - 1) {
                        val sb = StringBuilder()
                        confirmedCell.printGrapheme(sb)
                        predictedCells.add(PredictedCell(row.rowNum, cell.col, sb.toString(), underlined = true))
                    }
                    continue
                }

                val sb = StringBuilder()
                cell.replacement.printGrapheme(sb)
                predictedCells.add(PredictedCell(row.rowNum, cell.col, sb.toString(), underlined = underline))
            }
        }

        var predictedCursor: PredictedCursor? = null
        if (cursors.isNotEmpty()) {
            val c = cursorLast()
            if (c.active && !c.tentative(confirmedEpoch) && c.row < fb.ds.height && c.col < fb.ds.width) {
                predictedCursor = PredictedCursor(c.row, c.col)
            }
        }

        val pendingText = simpleLinearSuffix(predictedCells, fb)
        return Overlay(pendingText, visible = true, underlined = flagging, cells = predictedCells, cursor = predictedCursor)
    }

    /**
     * Best-effort convenience for [Overlay.pendingText] (see the class doc's
     * simplification #4): non-empty ONLY for the exact case the previous, simpler
     * engine already handled correctly -- a contiguous run of predicted cells on the
     * SAME row as the confirmed cursor, starting exactly at the confirmed cursor's
     * column, none of them underline-only "unknown" cells. Any arrow-key or mid-line
     * prediction (which cannot be expressed as a single trailing suffix) intentionally
     * yields "" here rather than rendering something misleading; [Overlay.cells] /
     * [Overlay.cursor] carry the honest, full picture for those cases.
     */
    private fun simpleLinearSuffix(cells: List<PredictedCell>, fb: Framebuffer): String {
        if (cells.isEmpty()) return ""
        val row = fb.ds.cursorRow
        if (cells.any { it.row != row || it.underlined }) return ""

        val sorted = cells.sortedBy { it.col }
        val startCol = fb.ds.cursorCol
        if (sorted.first().col != startCol) return ""
        for (idx in 1 until sorted.size) {
            if (sorted[idx].col != sorted[idx - 1].col + 1) return ""
        }
        return sorted.joinToString("") { it.text }
    }
}

private fun codePointToString(ch: Int): String = String(Character.toChars(ch))
