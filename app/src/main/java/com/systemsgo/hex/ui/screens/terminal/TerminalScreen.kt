package com.systemsgo.hex.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.launch
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.res.stringResource
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.TerminalSnippet
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import com.systemsgo.hex.ssh.protocol.SshKeyMap
import com.systemsgo.hex.ui.components.ButtonVariant
import com.systemsgo.hex.ui.components.SpaceButton
import com.systemsgo.hex.ui.theme.*

// ── UX-12: ANSI escape code parser ───────────────────────────────────────────

/** Default terminal green for uncoloured output. */
private val AnsiDefaultGreen = Color(0xFF33FF66)

/**
 * Tracks the active SGR (Select Graphic Rendition) state between chunks so
 * that incremental parsing can resume at the correct colour and weight without
 * re-scanning the entire accumulated buffer.
 */
internal data class AnsiParseState(
    val color: Color = AnsiDefaultGreen,
    val bold: Boolean = false,
)

/**
 * Maps SGR colour codes to Compose [Color].
 * Covers the standard 8 foreground colours (30–37) and their bright
 * variants (90–97). Background codes and 256-colour/true-colour
 * extensions are stripped silently.
 */
private fun ansiCodeToColor(code: Int): Color? = when (code) {
    30    -> Color(0xFF555555)
    31    -> Color(0xFFFF5555)
    32    -> Color(0xFF55FF55)
    33    -> Color(0xFFFFFF55)
    34    -> Color(0xFF5555FF)
    35    -> Color(0xFFFF55FF)
    36    -> Color(0xFF55FFFF)
    37    -> Color(0xFFCCCCCC)
    90    -> Color(0xFF777777)
    91    -> Color(0xFFFF7777)
    92    -> Color(0xFF77FF77)
    93    -> Color(0xFFFFFF77)
    94    -> Color(0xFF7777FF)
    95    -> Color(0xFFFF77FF)
    96    -> Color(0xFF77FFFF)
    97    -> Color(0xFFFFFFFF)
    else  -> null
}

/**
 * FIX #5 (Performance): Parses only the [chunk] string (the NEW bytes that
 * arrived since the last call), starting from [state]. Returns the rendered
 * [AnnotatedString] for the chunk plus the updated [AnsiParseState] to pass
 * into the next call.
 *
 * Callers compose the incremental result onto the previously cached
 * [AnnotatedString] via [buildAnnotatedString] { append(existing); append(newChunk) },
 * so the O(n) regex scan is limited to the incoming bytes (~10–200 chars)
 * rather than the full ~200 KB accumulated buffer.
 */
internal fun parseAnsiChunk(chunk: String, state: AnsiParseState): Pair<AnnotatedString, AnsiParseState> {
    val escapeRegex = Regex("\u001B\\[([0-9;]*)([A-Za-z])")
    var currentColor = state.color
    var bold = state.bold
    var pos = 0

    val annotated = buildAnnotatedString {
        for (match in escapeRegex.findAll(chunk)) {
            if (match.range.first > pos) {
                withStyle(SpanStyle(
                    color = currentColor,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
                )) {
                    append(chunk.substring(pos, match.range.first))
                }
            }
            pos = match.range.last + 1

            if (match.groupValues[2] == "m") {
                val params = match.groupValues[1]
                if (params.isEmpty() || params == "0") {
                    currentColor = AnsiDefaultGreen
                    bold = false
                } else {
                    params.split(";").forEach { part ->
                        val code = part.toIntOrNull() ?: return@forEach
                        when (code) {
                            0           -> { currentColor = AnsiDefaultGreen; bold = false }
                            1           -> bold = true
                            22          -> bold = false
                            in 30..37   -> currentColor = ansiCodeToColor(code) ?: currentColor
                            in 90..97   -> currentColor = ansiCodeToColor(code) ?: currentColor
                            39          -> currentColor = AnsiDefaultGreen
                        }
                    }
                }
            }
        }
        if (pos < chunk.length) {
            withStyle(SpanStyle(
                color = currentColor,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
            )) {
                append(chunk.substring(pos))
            }
        }
    }
    return annotated to AnsiParseState(currentColor, bold)
}

/**
 * Legacy full-parse overload — kept for callers that need a one-shot parse
 * (e.g. tests). The [TerminalScreen] composable uses [rememberIncrementalAnsiText]
 * which calls [parseAnsiChunk] incrementally instead.
 */
internal fun parseAnsiColors(raw: String): AnnotatedString =
    parseAnsiChunk(raw, AnsiParseState()).first

/**
 * FIX #5 (Performance): Composable helper that maintains incremental ANSI
 * parse state across recompositions. On each new [terminalText] value it
 * parses ONLY the newly appended suffix (a few bytes) rather than re-running
 * the full regex over the entire 5 000-line buffer.
 *
 * Falls back to a full re-parse only when lines are trimmed (buffer wrap) or
 * when the text is reset between sessions.
 *
 * SOL-FEATURE: made `internal` (was `private`) so [com.systemsgo.hex.ui.screens.IpmiConsoleTab]/
 * `AmtConsoleTab` (BmcManagementScreen.kt) can render SOL console output through
 * the same ANSI/VT100 renderer instead of duplicating it — a BIOS serial console
 * is exactly the kind of ANSI-escape-heavy stream (colour, cursor moves) this
 * was written for in the first place.
 */
@Composable
internal fun rememberIncrementalAnsiText(terminalText: String): AnnotatedString {
    // Mutable refs survive recomposition but are invisible to the snapshot system,
    // which is exactly what we want — we manage invalidation ourselves via
    // `remember(terminalText)` below.
    val prevText      = remember { mutableStateOf("") }
    val prevAnnotated = remember { mutableStateOf(AnnotatedString("")) }
    val prevState     = remember { mutableStateOf(AnsiParseState()) }

    return remember(terminalText) {
        when {
            terminalText.isEmpty() -> {
                // Session reset — clear everything.
                prevText.value      = ""
                prevAnnotated.value = AnnotatedString("")
                prevState.value     = AnsiParseState()
                AnnotatedString("")
            }
            terminalText.length > prevText.value.length &&
            terminalText.startsWith(prevText.value) -> {
                // Fast path: only the suffix is new — parse just that.
                val suffix = terminalText.substring(prevText.value.length)
                val (newAnnotated, newState) = parseAnsiChunk(suffix, prevState.value)
                val combined = buildAnnotatedString {
                    append(prevAnnotated.value)
                    append(newAnnotated)
                }
                prevText.value      = terminalText
                prevAnnotated.value = combined
                prevState.value     = newState
                combined
            }
            else -> {
                // Slow path: buffer was trimmed or text changed in a non-append
                // way (reconnect). Full re-parse is correct and necessary here.
                val (annotated, state) = parseAnsiChunk(terminalText, AnsiParseState())
                prevText.value      = terminalText
                prevAnnotated.value = annotated
                prevState.value     = state
                annotated
            }
        }
    }
}

/**
 * Interactive SSH terminal — the SSH equivalent of [com.systemsgo.hex.ui.screens.RdpCanvas],
 * but text-based rather than framebuffer-based. Renders the running output
 * stream and drives input via a hidden text field (so typed keystrokes are
 * sent as raw bytes immediately) plus a row of common terminal control keys
 * (Ctrl+C, Tab, arrows, Esc) that have no plain-text representation.
 */
@Composable
fun TerminalScreen(
    profileName: String,
    terminalText: String,
    latency: Long,
    onSendText: (String) -> Unit,
    onSendControlByte: (Int) -> Unit,
    onDisconnect: () -> Unit,
    // FEATURE-TERM-FONT: current terminal text size in sp, plus the callback to
    // persist a new value. Defaulted so existing call sites (tests, previews)
    // that don't care about the feature keep compiling unchanged.
    fontSize: Int = 14,
    onFontSizeChange: (Int) -> Unit = {},
    // TERM-RESIZE FIX (عربي: إصلاح حساب أبعاد الطرفية): يبلغ المستدعي بعدد
    // الأعمدة/الصفوف (cols/rows) الفعلية التي تتسع بمساحة عرض الطرفية
    // المقاسة فعليًا — يُستدعى من [LaunchedEffect] أدناه كلما تغيّر حجم
    // صندوق العرض (تدوير الشاشة، split-screen، فتح/غلق لوحة المفاتيح) أو
    // تغيّر [fontSize]. المستدعي (RdpSessionActivity's ViewModel) يمرر هذا
    // إلى resizeTerminal() على العميل النشط (SshClient.setPtySize /
    // MoshSessionClient's SSP resize message) — قبل هذا الإصلاح كانت
    // resizeTerminal() موجودة على مستوى العميل لكن غير مستدعاة إطلاقًا من
    // الواجهة، فالجلسة تبقى عالقة على حجم PTY افتراضي ثابت (100×32) بغض
    // النظر عن حجم الشاشة أو حجم الخط الفعليين.
    //
    // TERM-RESIZE FIX (EN): reports the actual cols/rows that fit the
    // *measured* terminal viewport back to the caller. Fired from the
    // [LaunchedEffect] below whenever the output Box's measured size
    // changes (rotation, split-screen resize, soft-keyboard show/hide —
    // all of these change the Box's Compose-measured size the same way,
    // so a single onSizeChanged hook covers every case without needing
    // separate Activity-level plumbing per trigger) or whenever [fontSize]
    // changes (same pixel area, different character cell size). Defaulted
    // so existing call sites/tests/previews keep compiling unchanged.
    onTerminalSizeChanged: (cols: Int, rows: Int) -> Unit = { _, _ -> },
    // FEATURE-TERM-SNIPPETS: saved commands the user can re-run with a tap,
    // plus callbacks to add/remove them. Persisted app-wide by the caller
    // (see RdpSessionViewModel / AppSettingsRepository) — this composable only
    // renders the list and reports user intent.
    snippets: List<TerminalSnippet> = emptyList(),
    onAddSnippet: (label: String, command: String) -> Unit = { _, _ -> },
    onDeleteSnippet: (id: String) -> Unit = {},
    // MOSH-PREDICT-FEATURE: three neutral primitives instead of depending on
    // MoshPredictionEngine.Overlay directly — TerminalScreen is a shared
    // composable for every protocol (SSH, IPMI-SOL, serial, telnet, rlogin,
    // mosh), so it must not import anything from the mosh.protocol package.
    // The caller (RdpSessionViewModel) is the only place that knows whether
    // the active client is Mosh; for every other protocol these stay at
    // their defaults ("", false, false), which renders identically to
    // before this feature existed. See mosh/NOTES.md for the full picture.
    predictedText: String = "",
    predictedVisible: Boolean = false,
    predictedUnderlined: Boolean = false,
) {
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboard.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    // TERM-RESIZE FIX: measured pixel size of the terminal output Box (after
    // its own padding is subtracted — see onSizeChanged placement below) and
    // the tools needed to convert that into a cols/rows character grid for
    // [onTerminalSizeChanged]. textMeasurer measures a real run of Monospace
    // glyphs at the current [fontSize] rather than assuming a fixed cell
    // size, so this stays correct across every font size the A-/A+ steppers
    // allow (10sp–24sp) and across device density/font-scale settings.
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var outputBoxSizePx by remember { mutableStateOf(IntSize.Zero) }
    // FEATURE-TERM-FONT: min/max mirror AppSettings.MIN/MAX_TERMINAL_FONT_SIZE —
    // duplicated as plain Ints here so this UI module doesn't need to depend on
    // the data/repository layer just for two constants.
    val minFontSize = 10
    val maxFontSize = 24
    // FEATURE-TERM-SNIPPETS: dialog state for adding a new saved command.
    var showAddSnippetDialog by remember { mutableStateOf(false) }
    // UX FIX: snippet pending delete confirmation — was previously deleted
    // instantly on a single tap of a 14dp icon with no way back. Holding the
    // snippet here (rather than just its id) lets the confirmation dialog
    // show its label without a second lookup.
    var deletingSnippet by remember { mutableStateOf<TerminalSnippet?>(null) }
    // FIX #TERM-BS: Use a sentinel space so the field is never truly empty.
    // An empty field means the IME never fires onValueChange for backspace.
    // With a sentinel, typing 'a' → " a" (length 2 > 1) → send "a".
    // Pressing backspace → "" (length 0 < 1) → send \u007F (DEL).
    // The sentinel is invisible in the terminal because we only forward
    // characters AFTER position 0, and the decoration box shows "$ " anyway.
    var inputBuffer by remember { mutableStateOf(" ") }  // single space sentinel

    // FIX #4 (UX): Replace animateScrollTo with instant scrollTo so rapid output
    // doesn't cause the screen to "float" up and down as each animation is
    // cancelled by the next chunk. Only auto-scroll when the user is already
    // near the bottom — if they've scrolled up to review earlier output we
    // stay put so we don't interrupt their reading.
    LaunchedEffect(terminalText) {
        val distanceFromBottom = scrollState.maxValue - scrollState.value
        // 300 px tolerance: accounts for partial visible line at the bottom edge.
        if (distanceFromBottom < 300 || scrollState.maxValue == 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    // FIX #TERM-SCROLL: Track whether user has scrolled away from the bottom
    // so we can show a scroll-to-bottom FAB.
    val isAtBottom = remember(scrollState.value, scrollState.maxValue) {
        scrollState.maxValue == 0 || (scrollState.maxValue - scrollState.value) < 300
    }

    // TERM-RESIZE FIX: recompute cols/rows and notify the caller whenever
    // the measured output area or the font size changes. Both are captured
    // as LaunchedEffect keys so this fires for every trigger the task calls
    // out: initial layout right after connecting (outputBoxSizePx goes from
    // Zero to its first real value), device rotation / multi-window / split-
    // screen resize and soft-keyboard show/hide (all of these change
    // outputBoxSizePx — see the Box's onSizeChanged below), and the user
    // tapping the A-/A+ font-size steppers (fontSize changes at the same
    // pixel area, changing the character cell size).
    //
    // TERM-RESIZE FIX (عربي): يعيد الحساب ويبلغ المستدعي كلما تغيّرت مساحة
    // العرض المقاسة أو حجم الخط — نفس الحالات المطلوبة: أول اتصال (أول
    // قياس حقيقي للصندوق)، تدوير الشاشة/split-screen/فتح-غلق لوحة المفاتيح
    // (كلها تغيّر outputBoxSizePx)، وتغيير fontSize من المستخدم.
    LaunchedEffect(outputBoxSizePx, fontSize) {
        val boxSize = outputBoxSizePx
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect

        // Measure a real run of monospace glyphs at the current fontSize
        // rather than assuming a fixed advance width — TextMeasurer already
        // accounts for the actual Monospace font's metrics on this device.
        // A longer run (64 chars) divided down avoids per-glyph rounding
        // error from measuring a single character.
        val sampleLength = 64
        val measured = textMeasurer.measure(
            text = "M".repeat(sampleLength),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp)
        )
        val charWidthPx = measured.size.width.toFloat() / sampleLength
        // lineHeight must match exactly what the output Text() below renders
        // with (fontSize + 4).sp — otherwise rows would be computed against
        // a line height the terminal isn't actually using.
        val lineHeightPx = with(density) { (fontSize + 4).sp.toPx() }
        if (charWidthPx <= 0f || lineHeightPx <= 0f) return@LaunchedEffect

        val cols = (boxSize.width / charWidthPx).toInt().coerceAtLeast(1)
        val rows = (boxSize.height / lineHeightPx).toInt().coerceAtLeast(1)
        onTerminalSizeChanged(cols, rows)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .background(DeepSpace.copy(alpha = 0.95f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(profileName, color = StarDust, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 15.sp)
                    Text(stringResource(R.string.terminal_ssh_latency, latency), color = PulsarCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                    // FEATURE-TERM-FONT: A-/A+ steppers, disabled at the clamp so it's
                    // obvious there's nowhere further to go rather than silently no-op'ing.
                    // Sized down from the default 48dp touch target so the top bar still
                    // fits comfortably alongside Paste/Disconnect on narrow phone screens.
                    IconButton(
                        onClick = { onFontSizeChange((fontSize - 1).coerceAtLeast(minFontSize)) },
                        enabled = fontSize > minFontSize,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.TextDecrease,
                            contentDescription = stringResource(R.string.cd_decrease_font_size),
                            tint = if (fontSize > minFontSize) CometTail else CometTail.copy(alpha = 0.35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { onFontSizeChange((fontSize + 1).coerceAtMost(maxFontSize)) },
                        enabled = fontSize < maxFontSize,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.TextIncrease,
                            contentDescription = stringResource(R.string.cd_increase_font_size),
                            tint = if (fontSize < maxFontSize) CometTail else CometTail.copy(alpha = 0.35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = {
                        coroutineScope.launch {
                            val clip = clipboard.getClipEntry()
                            val text = clip?.clipData?.getItemAt(0)?.text?.toString()
                            text?.let { onSendText(it) }
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.cd_paste), tint = CometTail)
                    }
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_disconnect), tint = NovaPink)
                    }
                }
            }

            // Terminal output — monospace, green-on-black classic terminal look.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
                    .verticalScroll(scrollState)
                    .padding(10.dp)
                    // TERM-RESIZE FIX: measured AFTER padding(10.dp) above so
                    // outputBoxSizePx is the actual text-drawable area, not
                    // the outer Box's full bounds — matches exactly what the
                    // Text() below is laid out into.
                    .onSizeChanged { outputBoxSizePx = it }
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        keyboardController?.show()
                    }
            ) {
                // FIX #5 (Performance): rememberIncrementalAnsiText only re-parses the
                // newly arrived suffix on each update rather than running Regex.findAll()
                // over the entire ~200 KB buffer. Full re-parse only on session reset.
                val connectingText = stringResource(R.string.terminal_connecting)
                val ansiText: AnnotatedString = if (terminalText.isEmpty()) {
                    remember(connectingText) {
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = AnsiDefaultGreen)) { append(connectingText) }
                        }
                    }
                } else {
                    rememberIncrementalAnsiText(terminalText)
                }
                // MOSH-PREDICT-FEATURE: append the predicted (unconfirmed) suffix
                // *after* ansiText is fully computed above, rather than feeding it
                // into rememberIncrementalAnsiText itself. That function's whole
                // point is incremental parsing keyed off terminalText only — it
                // remembers a running (prevText, prevAnnotated, prevState) triple
                // across recompositions and assumes terminalText only ever grows
                // by a stable confirmed suffix (or resets to ""). A predicted
                // suffix changes on every keystroke and can shrink/disappear on
                // reconciliation, which would corrupt that incremental cache and
                // force spurious full re-parses. Keeping it a separate, un-remembered
                // append here is cheap (one AnnotatedString build over already-
                // parsed spans + a short plain string) and never touches
                // terminalText/ansiText's own caching. Not wrapped in remember()
                // (contrast rememberIncrementalAnsiText's own remember above) —
                // this recomputes every recomposition, which is correct since it
                // must reflect the very latest of ansiText/predictedText/
                // predictedVisible/predictedUnderlined and the work is light.
                val finalText: AnnotatedString = if (predictedVisible && predictedText.isNotEmpty()) {
                    buildAnnotatedString {
                        append(ansiText)
                        withStyle(
                            SpanStyle(
                                textDecoration = if (predictedUnderlined)
                                    androidx.compose.ui.text.style.TextDecoration.Underline
                                else null
                            )
                        ) { append(predictedText) }
                    }
                } else {
                    ansiText
                }
                // FIX-SELECT: Wrap in SelectionContainer so the user can long-press
                // to select text and copy it — previously the plain Text composable
                // had no selection support at all.
                // FEATURE-TERM-FONT: fontSize now comes from the caller (persisted
                // in AppSettings) instead of the previous hardcoded 13.sp. lineHeight
                // scales with it (same +4sp ratio as the original 13/17 pairing) so
                // line spacing stays proportional at every size instead of getting
                // cramped at large sizes or overly sparse at small ones.
                SelectionContainer {
                    Text(
                        text = finalText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize + 4).sp
                    )
                }
            }

            // FEATURE-TERM-SNIPPETS: saved-commands row. Unlike the control-key row
            // below, this one is NOT forced to LTR — chip labels are free-form user
            // text (possibly Arabic), so the row should follow the app's normal
            // layout direction like any other list of user content.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NebulaSurface)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    items(snippets, key = { it.id }) { snippet ->
                        SnippetChip(
                            snippet = snippet,
                            onRun = { onSendText(snippet.command + "\n") },
                            onDelete = { deletingSnippet = snippet }
                        )
                    }
                }
                IconButton(onClick = { showAddSnippetDialog = true }) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.cd_add_snippet),
                        tint = PulsarCyan
                    )
                }
            }

            if (showAddSnippetDialog) {
                AddSnippetDialog(
                    onDismiss = { showAddSnippetDialog = false },
                    onSave = { label, command ->
                        onAddSnippet(label, command)
                        showAddSnippetDialog = false
                    }
                )
            }

            // UX FIX: confirm before permanently removing a saved snippet
            // instead of deleting on the first tap.
            deletingSnippet?.let { snippet ->
                DeleteSnippetDialog(
                    snippet = snippet,
                    onConfirm = { onDeleteSnippet(snippet.id); deletingSnippet = null },
                    onDismiss = { deletingSnippet = null }
                )
            }

            // Control-key row — keys with no plain-text representation.
            // BUGFIX-UI: this row used to inherit the app's layout direction,
            // so in Arabic (RTL) the whole row — including the arrow keys —
            // got reordered right-to-left. The glyphs "←"/"→" themselves don't
            // mirror (they're not bidi-mirrored characters), so the row ended
            // up visually reading "→" before "←", which is disorienting since
            // these represent fixed terminal control-key positions, not
            // natural-language content. Forcing LTR here keeps the physical
            // key order (and the arrows' meaning) consistent regardless of
            // the app's language, the same way a physical keyboard layout
            // wouldn't rearrange itself for RTL.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NebulaSurface)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                // FIX 5: terminal key labels use stringResource so they are translatable.
                // Arrow / symbol keys (↑ ↓ ← → | / ~) are universal symbols — kept as-is.
                item { TermKeyChip(stringResource(R.string.term_key_esc))  { onSendText("\u001B") } }
                item { TermKeyChip(stringResource(R.string.term_key_tab))  { onSendText("\t") } }
                item { TermKeyChip("Ctrl+C") { onSendControlByte(SshKeyMap.CTRL_C) } }
                item { TermKeyChip("Ctrl+D") { onSendControlByte(SshKeyMap.CTRL_D) } }
                item { TermKeyChip("Ctrl+Z") { onSendControlByte(SshKeyMap.CTRL_Z) } }
                item { TermKeyChip("Ctrl+L") { onSendControlByte(SshKeyMap.CTRL_L) } }
                item { TermKeyChip("↑") { onSendText("\u001B[A") } }
                item { TermKeyChip("↓") { onSendText("\u001B[B") } }
                item { TermKeyChip("←") { onSendText("\u001B[D") } }
                item { TermKeyChip("→") { onSendText("\u001B[C") } }
                item { TermKeyChip(stringResource(R.string.term_key_home)) { onSendText("\u001B[H") } }
                item { TermKeyChip(stringResource(R.string.term_key_end))  { onSendText("\u001B[F") } }
                item { TermKeyChip("|") { onSendText("|") } }
                item { TermKeyChip("/") { onSendText("/") } }
                item { TermKeyChip("~") { onSendText("~") } }
            }
            }

            // Hidden input field: captures the system keyboard, sends each
            // character/line straight to the SSH channel, and is cleared
            // immediately after every keystroke so it never accumulates.
            BasicTextField(
                value = inputBuffer,
                onValueChange = { newValue ->
                    // Sentinel is always at index 0 (" ").
                    // Typing a char: newValue = " x" → length 2 > 1 → send "x"
                    // Backspace:     newValue = ""   → length 0 < 1 → send DEL
                    when {
                        newValue.length > inputBuffer.length ->
                            onSendText(newValue.substring(inputBuffer.length))
                        newValue.length < inputBuffer.length ->
                            onSendText("\u007F")  // DEL / backspace
                    }
                    inputBuffer = " "  // always restore sentinel
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .background(NebulaSurface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                textStyle = TextStyle(color = StarDust, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PulsarCyan),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = { onSendText("\n") }
                ),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$ ", color = PulsarCyan, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        Box(Modifier.weight(1f)) { inner() }
                    }
                }
            )
        } // end Column

        // FIX #TERM-SCROLL: Scroll-to-bottom FAB — visible only when the user
        // has scrolled up from the bottom of the terminal output.
        AnimatedVisibility(
            visible = !isAtBottom,
            enter   = fadeIn() + scaleIn(),
            exit    = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                },
                containerColor = NebulaSurface,
                contentColor   = PulsarCyan,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.cd_scroll_to_bottom),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    } // end outer Box

    // Bring up the keyboard automatically when the terminal first connects.
    LaunchedEffect(Unit) { keyboardController?.show() }
}

@Composable
private fun TermKeyChip(label: String, onClick: () -> Unit) {
    Surface(
        color = StarfieldSurface,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            color = StarDust,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/**
 * FEATURE-TERM-SNIPPETS: a saved-command chip. Tapping the label re-runs the
 * command immediately (sends it + a trailing newline, same as pressing Enter
 * after typing it manually); the trailing "×" removes it from the saved list.
 * Two separate clickable targets (rather than one chip + a long-press menu)
 * keep the interaction obvious on a small touch target with no hidden gesture
 * to discover.
 */
@Composable
private fun SnippetChip(snippet: TerminalSnippet, onRun: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color = StarfieldSurface,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                snippet.label,
                color = PulsarCyan,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onRun)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp)
            )
            // UX FIX: was a bare 14dp Icon with .clickable directly on it —
            // a touch target far under Android's 48dp minimum, on a
            // destructive one-tap action with no confirmation. Wrapped in
            // an IconButton for a 32dp touch target (kept compact to fit
            // the chip row) while the icon itself stays visually small.
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_delete_snippet, snippet.label),
                    tint = StarDust.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/**
 * UX FIX: confirmation dialog shown before a saved snippet is permanently
 * removed — replaces the previous instant single-tap delete.
 */
@Composable
private fun DeleteSnippetDialog(
    snippet:   TerminalSnippet,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = NebulaSurface,
        shape            = RoundedCornerShape(20.dp),
        icon  = { Icon(Icons.Outlined.Warning, null, tint = SolarFlare, modifier = Modifier.size(32.dp)) },
        title = { Text(stringResource(R.string.delete_snippet_title), color = StarDust, fontWeight = FontWeight.Bold) },
        text  = { Text(stringResource(R.string.delete_snippet_message, snippet.label), color = CometTail) },
        confirmButton = {
            SpaceButton(stringResource(R.string.delete), onConfirm, ButtonVariant.DANGER, Modifier.fillMaxWidth())
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CometTail) }
        }
    )
}

/**
 * FEATURE-TERM-SNIPPETS: dialog to save the current/a new command under a
 * short label. Save stays disabled until both fields are non-blank so an
 * empty snippet (which [AppSettingsRepository.addTerminalSnippet] would
 * silently drop anyway) can't be submitted in the first place.
 */
@Composable
private fun AddSnippetDialog(onDismiss: () -> Unit, onSave: (label: String, command: String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    val accent = PulsarCyan

    // UI-FIX (design feedback): this was still the stock, unthemed AlertDialog —
    // the one dialog on the terminal screen that hadn't picked up the dark
    // surface / accent-border / SpaceButton treatment used everywhere else.
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = NebulaSurface,
        shape            = RoundedCornerShape(20.dp),
        modifier = Modifier.border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(accent.copy(alpha = 0.30f), Color.Transparent, accent.copy(alpha = 0.12f))
            ),
            shape = RoundedCornerShape(20.dp)
        ),
        icon  = { Icon(Icons.Default.Add, null, tint = accent, modifier = Modifier.size(32.dp)) },
        title = {
            Text(
                stringResource(R.string.snippet_dialog_title),
                color      = StarDust,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.snippet_label_hint), color = CometTail) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = accent,
                        unfocusedBorderColor = HorizonGray.copy(alpha = 0.4f),
                        focusedTextColor     = StarDust,
                        unfocusedTextColor   = StarDust,
                        cursorColor          = accent,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.snippet_command_hint), color = CometTail) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = StarDust),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = accent,
                        unfocusedBorderColor = HorizonGray.copy(alpha = 0.4f),
                        focusedTextColor     = StarDust,
                        unfocusedTextColor   = StarDust,
                        cursorColor          = accent,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            SpaceButton(
                text     = stringResource(R.string.save),
                onClick  = { onSave(label, command) },
                enabled  = label.isNotBlank() && command.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CometTail) }
        }
    )
}
