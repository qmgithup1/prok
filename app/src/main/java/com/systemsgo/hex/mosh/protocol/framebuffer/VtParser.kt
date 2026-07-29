package com.systemsgo.hex.mosh.protocol.framebuffer

/**
 * FULL-FRAMEBUFFER-PARITY (Phase 2 of 4 — see mosh/NOTES.md's migration path).
 *
 * A faithful Kotlin port of upstream mosh's VT100/ANSI/UTF-8 parser:
 *   `src/terminal/parser.h`, `parser.cc`, `parserstate.h`, `parserstate.cc`,
 *   `parserstatefamily.h`, `parsertransition.h`, `parseraction.h`, `parseraction.cc`.
 *
 * This file is ISOLATED: it has no dependency on [TerminalFramebuffer.kt]'s
 * types and no dependency on any UI code. It only turns a raw server byte
 * stream into a flat list of [VtAction]s using upstream's exact Paul
 * Williams DEC ANSI state-machine transition table
 * (http://www.vt100.net/emu/dec_ansi_parser, referenced by upstream's own
 * header comment). [TerminalDispatcher.kt] is the piece that interprets
 * these actions against a Framebuffer.
 *
 * SCOPE OF THIS PORT:
 * - Every state (`Ground`, `Escape`, `Escape_Intermediate`, `CSI_Entry`,
 *   `CSI_Param`, `CSI_Intermediate`, `CSI_Ignore`, `DCS_Entry`, `DCS_Param`,
 *   `DCS_Intermediate`, `DCS_Passthrough`, `DCS_Ignore`, `OSC_String`,
 *   `SOS_PM_APC_String`) and the "anywhere rule" (C1 control codes that
 *   force a transition regardless of current state) are ported with
 *   upstream's exact byte-range literals — none of the `0x18`/`0x1B`/`0x9C`/
 *   etc. constants below are approximated or rounded.
 * - `Parser::Action::ch`/`char_present` is only ever set on the ACTION
 *   RETURNED DIRECTLY BY `state->input(ch)` — never on the `enter()`/`exit()`
 *   actions (`Clear`, `Hook`, `Unhook`, `OSC_Start`, `OSC_End`), exactly
 *   mirroring upstream's `Parser::Parser::input` (see `parser.cc`): those
 *   two calls are appended to the actions vector without ever touching
 *   `ch`/`char_present`.
 * - `Ignore` actions are never materialized at all (upstream still
 *   allocates one and then drops it via `Action::ignore()` /
 *   `append_or_delete`; Kotlin just returns `null` from the transition
 *   table for the same cases and never adds anything to the output list —
 *   behaviorally identical, one fewer allocation).
 *
 * DOCUMENTED SIMPLIFICATION — UTF-8 decoding (`Parser::UTF8Parser`):
 * Upstream's `UTF8Parser::input` drives glibc's `mbrtowc`/`mbstate_t` byte
 * by byte and, on `EILSEQ`, retries with the tail of the buffer to comply
 * with "Unicode 6.0, section 3.9, Best Practices for using U+FFFD" (see its
 * own comment). Kotlin/the JVM has no `mbstate_t` equivalent, so [Utf8Decoder]
 * below reimplements the same *visible* contract — a maximal invalid
 * subpart is replaced by exactly one U+FFFD and decoding resynchronizes on
 * the next byte, overlong encodings are rejected, and encoded surrogate
 * code points (which upstream also special-cases, "OS X unfortunately
 * allows these sequences without EILSEQ") are replaced by U+FFFD — using
 * the well-known WHATWG Encoding Standard UTF-8 decoder algorithm (a public
 * technical specification, not upstream's literal source) instead of
 * reproducing glibc's specific state machine. The `ch > 0x10FFFF` upstream
 * check is unreachable here by construction, since a 4-byte UTF-8 leading
 * byte (`0xF0`-`0xF4`) can never decode past `0x10FFFF` in this algorithm.
 */

/** One parsed action from the VT state machine, matching `Parser::Action`'s subclasses. */
sealed class VtAction {
    /** The triggering Unicode code point (upstream's `wchar_t ch`). Unset (-1) for enter/exit actions. */
    var ch: Int = -1
    var charPresent: Boolean = false

    /** `Parser::Print` — printable character, dispatched to `Emulator::print`. */
    class Print : VtAction()

    /** `Parser::Execute` — C0/C1 control character, dispatched to `Dispatcher::dispatch(CONTROL, ...)`. */
    class Execute : VtAction()

    /** `Parser::Clear` — clear parameter/intermediate-char buffers (state entry action). */
    class Clear : VtAction()

    /** `Parser::Collect` — accumulate an intermediate byte (e.g. `?` in `CSI ? 25 h`). */
    class Collect : VtAction()

    /** `Parser::Param` — accumulate a parameter digit or `;` separator. */
    class Param : VtAction()

    /** `Parser::Esc_Dispatch` — a complete escape sequence (non-CSI) is ready to dispatch. */
    class EscDispatch : VtAction()

    /** `Parser::CSI_Dispatch` — a complete CSI sequence is ready to dispatch. */
    class CsiDispatch : VtAction()

    /** `Parser::Hook` — entering DCS passthrough. No-op on the terminal (upstream doesn't override `act_on_terminal`). */
    class Hook : VtAction()

    /** `Parser::Put` — a DCS passthrough byte. No-op on the terminal, same reason as [Hook]. */
    class Put : VtAction()

    /** `Parser::Unhook` — leaving DCS passthrough. No-op on the terminal, same reason as [Hook]. */
    class Unhook : VtAction()

    /** `Parser::OSC_Start` — entering an OSC string (state-entry action). */
    class OscStart : VtAction()

    /** `Parser::OSC_Put` — accumulate one OSC string code point. */
    class OscPut : VtAction()

    /** `Parser::OSC_End` — OSC string terminated; dispatched to `Dispatcher::OSC_dispatch`. */
    class OscEnd : VtAction()
}

/** The 14 states of upstream's `Parser::StateFamily`. */
private enum class VtState {
    GROUND,
    ESCAPE,
    ESCAPE_INTERMEDIATE,
    CSI_ENTRY,
    CSI_PARAM,
    CSI_INTERMEDIATE,
    CSI_IGNORE,
    DCS_ENTRY,
    DCS_PARAM,
    DCS_INTERMEDIATE,
    DCS_PASSTHROUGH,
    DCS_IGNORE,
    OSC_STRING,
    SOS_PM_APC_STRING,
}

/**
 * One state-machine transition: an optional [action] to emit (null == upstream's
 * filtered-out `Ignore`) and an optional [nextState] (null == stay in the same state,
 * matching upstream's `Transition(next_state = NULL)`).
 */
private class VtTransition(val action: VtAction? = null, val nextState: VtState? = null)

/**
 * Direct port of `Parser::State::anywhere_rule` — C1 control codes and a few
 * others force an immediate transition regardless of the current state.
 */
private fun anywhereRule(ch: Int): VtTransition? = when {
    ch == 0x18 || ch == 0x1A ||
        (ch in 0x80..0x8F) || (ch in 0x91..0x97) || ch == 0x99 || ch == 0x9A ->
        VtTransition(VtAction.Execute(), VtState.GROUND)
    ch == 0x9C -> VtTransition(nextState = VtState.GROUND)
    ch == 0x1B -> VtTransition(nextState = VtState.ESCAPE)
    ch == 0x98 || ch == 0x9E || ch == 0x9F -> VtTransition(nextState = VtState.SOS_PM_APC_STRING)
    ch == 0x90 -> VtTransition(nextState = VtState.DCS_ENTRY)
    ch == 0x9D -> VtTransition(nextState = VtState.OSC_STRING)
    ch == 0x9B -> VtTransition(nextState = VtState.CSI_ENTRY)
    else -> null
}

/** `C0_prime` from parserstate.cc: C0 control codes minus the ones handled elsewhere (ESC, CAN, SUB...). */
private fun isC0Prime(ch: Int): Boolean = (ch <= 0x17) || (ch == 0x19) || (ch in 0x1C..0x1F)

/** `GLGR` from parserstate.cc: printable GL (7-bit) or GR (8-bit) area. */
private fun isGlGr(ch: Int): Boolean = (ch in 0x20..0x7F) || (ch in 0xA0..0xFF)

/**
 * Direct port of each `State::input_state_rule` override. [ch] here is
 * ALREADY translated (high Unicode codepoints folded to 0x41), matching
 * `State::input`'s `this->input_state_rule(ch >= 0xA0 ? 0x41 : ch)` call —
 * see [CodepointParser.input] for where that translation happens.
 */
private fun inputStateRule(state: VtState, ch: Int): VtTransition = when (state) {
    VtState.GROUND -> when {
        isC0Prime(ch) -> VtTransition(VtAction.Execute())
        isGlGr(ch) -> VtTransition(VtAction.Print())
        else -> VtTransition()
    }

    VtState.ESCAPE -> when {
        isC0Prime(ch) -> VtTransition(VtAction.Execute())
        ch in 0x20..0x2F -> VtTransition(VtAction.Collect(), VtState.ESCAPE_INTERMEDIATE)
        (ch in 0x30..0x4F) || (ch in 0x51..0x57) || ch == 0x59 || ch == 0x5A || ch == 0x5C || (ch in 0x60..0x7E) ->
            VtTransition(VtAction.EscDispatch(), VtState.GROUND)
        ch == 0x5B -> VtTransition(nextState = VtState.CSI_ENTRY)
        ch == 0x5D -> VtTransition(nextState = VtState.OSC_STRING)
        ch == 0x50 -> VtTransition(nextState = VtState.DCS_ENTRY)
        ch == 0x58 || ch == 0x5E || ch == 0x5F -> VtTransition(nextState = VtState.SOS_PM_APC_STRING)
        else -> VtTransition()
    }

    VtState.ESCAPE_INTERMEDIATE -> when {
        isC0Prime(ch) -> VtTransition(VtAction.Execute())
        ch in 0x20..0x2F -> VtTransition(VtAction.Collect())
        ch in 0x30..0x7E -> VtTransition(VtAction.EscDispatch(), VtState.GROUND)
        else -> VtTransition()
    }

    VtState.CSI_ENTRY -> when {
        isC0Prime(ch) -> VtTransition(VtAction.Execute())
        ch in 0x40..0x7E -> VtTransition(VtAction.CsiDispatch(), VtState.GROUND)
        (ch in 0x30..0x39) || ch == 0x3B -> VtTransition(VtAction.Param(), VtState.CSI_PARAM)
        ch in 0x3C..0x3F -> VtTransition(VtAction.Collect(), VtState.CSI_PARAM)
        ch == 0x3A -> VtTransition(nextState = VtState.CSI_IGNORE)
        ch in 0x20..0x2F -> VtTransition(VtAction.Collect(), VtState.CSI_INTERMEDIATE)
        else -> VtTransition()
    }

    VtState.CSI_PARAM -> when {
        isC0Prime(ch) -> VtTransition(VtAction.Execute())
        (ch in 0x30..0x39) || ch == 0x3B -> VtTransition(VtAction.Param())
        ch == 0x3A || (ch in 0x3C..0x3F) -> VtTransition(nextState = VtState.CSI_IGNORE)
        ch in 0x20..0x2F -> VtTransition(VtAction.Collect(), VtState.CSI_INTERMEDIATE)
        ch in 0x40..0x7E -> VtTransition(VtAction.CsiDispatch(), VtState.GROUND)
        else -> VtTransition()
    }

    VtState.CSI_INTERMEDIATE -> when {
        isC0Prime(ch) -> VtTransition(VtAction.Execute())
        ch in 0x20..0x2F -> VtTransition(VtAction.Collect())
        ch in 0x40..0x7E -> VtTransition(VtAction.CsiDispatch(), VtState.GROUND)
        ch in 0x30..0x3F -> VtTransition(nextState = VtState.CSI_IGNORE)
        else -> VtTransition()
    }

    VtState.CSI_IGNORE -> when {
        isC0Prime(ch) -> VtTransition(VtAction.Execute())
        ch in 0x40..0x7E -> VtTransition(nextState = VtState.GROUND)
        else -> VtTransition()
    }

    VtState.DCS_ENTRY -> when {
        ch in 0x20..0x2F -> VtTransition(VtAction.Collect(), VtState.DCS_INTERMEDIATE)
        ch == 0x3A -> VtTransition(nextState = VtState.DCS_IGNORE)
        (ch in 0x30..0x39) || ch == 0x3B -> VtTransition(VtAction.Param(), VtState.DCS_PARAM)
        ch in 0x3C..0x3F -> VtTransition(VtAction.Collect(), VtState.DCS_PARAM)
        ch in 0x40..0x7E -> VtTransition(nextState = VtState.DCS_PASSTHROUGH)
        else -> VtTransition()
    }

    VtState.DCS_PARAM -> when {
        (ch in 0x30..0x39) || ch == 0x3B -> VtTransition(VtAction.Param())
        ch == 0x3A || (ch in 0x3C..0x3F) -> VtTransition(nextState = VtState.DCS_IGNORE)
        ch in 0x20..0x2F -> VtTransition(VtAction.Collect(), VtState.DCS_INTERMEDIATE)
        ch in 0x40..0x7E -> VtTransition(nextState = VtState.DCS_PASSTHROUGH)
        else -> VtTransition()
    }

    VtState.DCS_INTERMEDIATE -> when {
        ch in 0x20..0x2F -> VtTransition(VtAction.Collect())
        ch in 0x40..0x7E -> VtTransition(nextState = VtState.DCS_PASSTHROUGH)
        ch in 0x30..0x3F -> VtTransition(nextState = VtState.DCS_IGNORE)
        else -> VtTransition()
    }

    VtState.DCS_PASSTHROUGH -> when {
        isC0Prime(ch) || (ch in 0x20..0x7E) -> VtTransition(VtAction.Put())
        ch == 0x9C -> VtTransition(nextState = VtState.GROUND)
        else -> VtTransition()
    }

    VtState.DCS_IGNORE -> when {
        ch == 0x9C -> VtTransition(nextState = VtState.GROUND)
        else -> VtTransition()
    }

    VtState.OSC_STRING -> when {
        ch in 0x20..0x7F -> VtTransition(VtAction.OscPut())
        ch == 0x9C || ch == 0x07 -> VtTransition(nextState = VtState.GROUND) // 0x07 is xterm's non-ANSI variant
        else -> VtTransition()
    }

    VtState.SOS_PM_APC_STRING -> when {
        ch == 0x9C -> VtTransition(nextState = VtState.GROUND)
        else -> VtTransition()
    }
}

/** Port of the `enter()` overrides — only these five states have one; every other state's default is `Ignore` (dropped). */
private fun enterAction(state: VtState): VtAction? = when (state) {
    VtState.ESCAPE, VtState.CSI_ENTRY, VtState.DCS_ENTRY -> VtAction.Clear()
    VtState.DCS_PASSTHROUGH -> VtAction.Hook()
    VtState.OSC_STRING -> VtAction.OscStart()
    else -> null
}

/** Port of the `exit()` overrides — only these two states have one; every other state's default is `Ignore` (dropped). */
private fun exitAction(state: VtState): VtAction? = when (state) {
    VtState.DCS_PASSTHROUGH -> VtAction.Unhook()
    VtState.OSC_STRING -> VtAction.OscEnd()
    else -> null
}

/**
 * Direct port of `Parser::Parser` — holds the current state and turns one
 * Unicode code point into zero or more [VtAction]s.
 */
private class CodepointParser {
    private var state: VtState = VtState.GROUND

    /** Port of `Parser::State::input` + `Parser::Parser::input` combined. */
    fun input(ch: Int, out: MutableList<VtAction>) {
        // Check for immediate (anywhere-rule) transitions first.
        var transition = anywhereRule(ch)
        if (transition == null) {
            // Normal X.364 state machine. High Unicode codepoints (>= 0xA0) are
            // folded to 0x41 ('A') for the purpose of matching state-rule ranges only
            // -- the real `ch` is still what gets stamped onto the resulting action below.
            transition = inputStateRule(state, if (ch >= 0xA0) 0x41 else ch)
        }

        transition.action?.let {
            it.charPresent = true
            it.ch = ch
        }

        val nextState = transition.nextState
        if (nextState != null) {
            exitAction(state)?.let(out::add)
        }
        transition.action?.let(out::add)
        if (nextState != null) {
            enterAction(nextState)?.let(out::add)
            state = nextState
        }
    }

    fun resetInput() {
        state = VtState.GROUND
    }
}

/**
 * Direct (behavior-preserving, not byte-identical-algorithm) port of
 * `Parser::UTF8Parser` — see the file-level doc for why glibc's `mbrtowc`
 * dance is replaced by the WHATWG UTF-8 decoder algorithm here. Emits one
 * Unicode code point per call to [onCodepoint] once a full (possibly
 * error-substituted) sequence is available.
 */
private class Utf8Decoder {
    private var codePoint: Int = 0
    private var bytesSeen: Int = 0
    private var bytesNeeded: Int = 0
    private var lowerBoundary: Int = 0x80
    private var upperBoundary: Int = 0xBF

    fun reset() {
        codePoint = 0
        bytesSeen = 0
        bytesNeeded = 0
        lowerBoundary = 0x80
        upperBoundary = 0xBF
    }

    /** Feed one raw byte (0-255). May synchronously emit 0, 1, or 2 code points (on resync after an error). */
    fun decode(byte: Int, onCodepoint: (Int) -> Unit) {
        val b = byte and 0xFF

        if (bytesNeeded == 0) {
            when {
                b <= 0x7F -> onCodepoint(b)
                b in 0xC2..0xDF -> {
                    bytesNeeded = 1
                    codePoint = b and 0x1F
                }
                b in 0xE0..0xEF -> {
                    if (b == 0xE0) lowerBoundary = 0xA0
                    if (b == 0xED) upperBoundary = 0x9F // excludes UTF-16 surrogate range D800-DFFF
                    bytesNeeded = 2
                    codePoint = b and 0x0F
                }
                b in 0xF0..0xF4 -> {
                    if (b == 0xF0) lowerBoundary = 0x90
                    if (b == 0xF4) upperBoundary = 0x8F // caps codepoint at 0x10FFFF
                    bytesNeeded = 3
                    codePoint = b and 0x07
                }
                else -> onCodepoint(0xFFFD) // stray continuation byte or invalid leading byte (0x80-0xC1, 0xF5-0xFF)
            }
            return
        }

        // Expecting a continuation byte.
        if (b < lowerBoundary || b > upperBoundary) {
            // Malformed: emit exactly one replacement character for the maximal
            // invalid subpart seen so far, then resynchronize by reprocessing
            // this byte as a fresh sequence start (it may itself be valid).
            reset()
            onCodepoint(0xFFFD)
            decode(byte, onCodepoint)
            return
        }

        lowerBoundary = 0x80
        upperBoundary = 0xBF
        codePoint = (codePoint shl 6) or (b and 0x3F)
        bytesSeen++
        if (bytesSeen != bytesNeeded) return

        val result = codePoint
        reset()
        onCodepoint(result)
    }
}

/**
 * Public entry point: merges `Parser::UTF8Parser` + `Parser::Parser` into a
 * single byte-at-a-time state machine, matching the file's mandate ("تأخذ
 * بايت واحد في كل مرة وتُرجع action"). Feed raw bytes from the server one at
 * a time (or in bulk via [inputBytes]); actions accumulate in the list you pass in.
 */
class VtParser {
    private val codepointParser = CodepointParser()
    private val utf8Decoder = Utf8Decoder()

    /** Feed a single raw byte (0-255) from the server. Appends zero or more actions to [out]. */
    fun input(byte: Int, out: MutableList<VtAction>) {
        utf8Decoder.decode(byte) { codepoint -> codepointParser.input(codepoint, out) }
    }

    /** Convenience: feed a whole chunk of server bytes, returning the accumulated actions. */
    fun inputBytes(bytes: ByteArray): List<VtAction> {
        val out = ArrayList<VtAction>()
        for (b in bytes) input(b.toInt() and 0xFF, out)
        return out
    }

    fun resetInput() {
        codepointParser.resetInput()
        utf8Decoder.reset()
    }
}
