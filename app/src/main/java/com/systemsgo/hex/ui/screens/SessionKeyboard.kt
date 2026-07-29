package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.ui.theme.CometTail
import com.systemsgo.hex.ui.theme.DeepSpace
import com.systemsgo.hex.ui.theme.HorizonGray
import com.systemsgo.hex.ui.theme.NebulaSurface
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.StarDust

/**
 * TOOLBOX FEATURE (Stage 2) — "لوحة المفاتيح الحقيقية" (real virtual
 * keyboard): unlike Android's system IME (which frequently fails to route
 * correctly into a remote-desktop surface, and has no concept of the
 * remote's own layout), every key here goes straight through
 * `RdpSessionViewModel.sendTerminalText()` → `RemoteSessionClient.sendText()`
 * → one RDP Unicode-keyboard PDU per character (see
 * `RdpRemoteAdapter.sendText` / `systemsgo_jni.c`'s `nativeSendUnicode`). That
 * makes Arabic (or any script) type correctly into the remote session
 * regardless of the remote machine's configured keyboard layout — something
 * scancode-based input (the physical/hardware keyboard path, and the old
 * ExtraKeysBar's modifier keys) fundamentally cannot do, since a scancode
 * only has meaning once mapped through *some* layout.
 *
 * Backspace/Enter still go through the scancode path
 * (`onSpecialKey`/`RdpSessionViewModel.sendKeyEvent`) rather than Unicode,
 * since they're control actions, not characters — matching how ExtraKeysBar
 * already sends its modifier/function keys.
 */
enum class VirtualKeyboardLayout { ENGLISH, ARABIC }

// TOOLBOX FEATURE (Stage 12 — "100% real keyboard"): LETTERS is the normal
// alphabet layout; SYMBOLS swaps the letter rows for the full ASCII
// punctuation/symbol set a real hardware keyboard has (previously only 6
// symbols were reachable at all — see symbolsRows below). Toggled by the
// "#+=" / "ABC" key in the bottom control row, exactly like a real
// phone/PC on-screen keyboard's symbols layer.
enum class KeyboardMode { LETTERS, SYMBOLS }

private val englishLetterRows = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m"),
)

// Standard Arabic (101) keyboard letter mapping. Arabic has no upper/lower
// case, so — unlike English — there is no shift-driven second layer here.
private val arabicLetterRows = listOf(
    listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "د"),
    listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"),
    listOf("ئ", "ء", "ؤ", "ر", "ى", "ة", "و", "ز", "ظ"),
)

private val digitRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
private val punctuationRow = listOf(".", ",", "?", "!", "-", "@")

// TOOLBOX FEATURE (Stage 12): the full ASCII symbol set, laid out like a
// real keyboard's/Gboard's "#+=" layer — everything the old 6-symbol
// punctuationRow was missing: ; ' " / \ | ~ ` # $ % ^ & * ( ) _ + = { } [ ] < >
private val symbolsRows = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("@", "#", "$", "%", "^", "&", "*", "(", ")", "-", "_", "="),
    listOf("[", "]", "{", "}", "\\", "|", ";", ":", "'", "\""),
    listOf("<", ">", ",", ".", "?", "/", "~", "`", "+"),
)

// TOOLBOX FEATURE (Stage 13 — Arabic punctuation): a real Arabic (101)
// keyboard doesn't send the ASCII ",", ";", "?" glyphs for those keys — it
// sends the Arabic-specific comma/semicolon/question-mark forms (، ؛ ؟),
// which read correctly right-to-left inside Arabic text. Digits are left as
// Western 0-9 on purpose: that's also what a real physical Arabic PC
// keyboard's number row actually sends in the overwhelming majority of
// layouts/OSes (Eastern Arabic-Indic numeral *display* is a locale/app
// setting, not something the keyboard itself sends), so changing them here
// would risk typing the wrong characters into forms/fields that expect
// plain 0-9.
private val arabicPunctuationOverrides = mapOf(
    "," to "،",
    ";" to "؛",
    "?" to "؟",
)

private fun localizeSymbols(rows: List<List<String>>, isEnglish: Boolean): List<List<String>> =
    if (isEnglish) rows else rows.map { row -> row.map { arabicPunctuationOverrides[it] ?: it } }

// TOOLBOX FEATURE (Stage 14 — Eastern Arabic-Indic digits): a real Arabic
// keyboard's number row still *sends* Western 0-9 (digit substitution —
// Windows/apps optionally *displaying* ٠-٩ instead — is an OS/locale
// setting, not something the keyboard itself transmits, and this app sends
// raw Unicode characters straight through, bypassing any such OS layer
// entirely). So Western digits stay the primary tap on every digit key —
// necessary for forms/passwords/IPs that expect plain 0-9 — exactly like a
// real hardware Arabic keyboard. Long-press each digit for its actual
// ٠١٢٣٤٥٦٧٨٩ character instead, mirroring how mobile Arabic keyboards
// (Gboard/iOS) expose the alternate digit under a long-press.
private val easternArabicDigits = mapOf(
    "0" to "٠", "1" to "١", "2" to "٢", "3" to "٣", "4" to "٤",
    "5" to "٥", "6" to "٦", "7" to "٧", "8" to "٨", "9" to "٩",
)

// TOOLBOX FEATURE (Stage 12): sticky remote modifiers, merged directly into
// the real keyboard (previously only reachable via the separate
// ExtraKeysBar). "id" matches RdpSessionViewModel.toggleStickyModifier's key
// and RdpSessionActivity's activeModifiers set, so a modifier toggled here
// shows as held whether it was tapped here or on ExtraKeysBar, and vice versa.
private data class ModifierKey(val id: String, val scanCode: Int, val extended: Boolean = false)
private val modifierKeys = listOf(
    ModifierKey("Ctrl", 0x1D),
    ModifierKey("Alt", 0x38),
    ModifierKey("Shift", 0x2A),
    ModifierKey("Win", 0x5B, true),
)

// TOOLBOX FEATURE (Stage 12): the remaining keys a real keyboard has that
// weren't reachable on the keyboard itself before — Esc, Tab, F1-F12,
// CapsLock, navigation cluster, and arrows. Momentary (single tap = a
// complete down+up), combining with any sticky modifierKeys currently held,
// via the same onScancodeTap → RdpSessionViewModel.sendComboKeyTap path
// digits/letters already use in modifier-combo mode.
private data class FunctionKey(val label: String, val scanCode: Int, val extended: Boolean = false)
private val functionKeys = listOf(
    FunctionKey("Esc", 0x01), FunctionKey("Tab", 0x0F), FunctionKey("Caps", 0x3A),
    FunctionKey("F1", 0x3B), FunctionKey("F2", 0x3C), FunctionKey("F3", 0x3D),
    FunctionKey("F4", 0x3E), FunctionKey("F5", 0x3F), FunctionKey("F6", 0x40),
    FunctionKey("F7", 0x41), FunctionKey("F8", 0x42), FunctionKey("F9", 0x43),
    FunctionKey("F10", 0x44), FunctionKey("F11", 0x57), FunctionKey("F12", 0x58),
    FunctionKey("PrtSc", 0x37, true), FunctionKey("Ins", 0x52, true), FunctionKey("Del", 0x53, true),
    FunctionKey("Home", 0x47, true), FunctionKey("End", 0x4F, true),
    FunctionKey("PgUp", 0x49, true), FunctionKey("PgDn", 0x51, true),
    FunctionKey("↑", 0x48, true), FunctionKey("←", 0x4B, true),
    FunctionKey("↓", 0x50, true), FunctionKey("→", 0x4D, true),
)

// TOOLBOX FEATURE (Stage 3): standard PC/AT Set-1 scancodes for each row, by
// *physical position* — not by character. Windows keyboard shortcuts
// (Ctrl+C, Ctrl+Alt+T...) are bound to physical key position, not to the
// character a given layout produces there, so the same position table
// applies whether the visible label is English "c" or Arabic "ة" sitting on
// that same key. Used only while a sticky modifier (see ExtraKeysBar /
// RdpSessionViewModel.toggleStickyModifier) is active — see onKeyTap below —
// so ordinary typing is completely unaffected and still goes through
// Unicode (sendTerminalText), exactly as Stage 2 shipped it.
private val digitRowScancodes    = listOf(0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B)
private val letterRowScancodes = listOf(
    listOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B), // Q..P, [, ]
    listOf(0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28),       // A..L, ;, '
    listOf(0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35),             // Z..M, ,, ., /
)

@Composable
fun SessionVirtualKeyboard(
    layout: VirtualKeyboardLayout,
    onLayoutChange: (VirtualKeyboardLayout) -> Unit,
    onChar: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onHide: () -> Unit,
    // Stage 3: when non-empty, a sticky modifier (Ctrl/Alt/Shift/Win) is
    // currently held on the remote side — tapping a letter/digit sends its
    // *scancode* (so it actually combines into a shortcut) instead of a
    // Unicode character.
    activeModifiers: Set<String> = emptySet(),
    // Stage 12: extended is now passed through (arrows/Del/Home/... are all
    // extended 0xE0-prefixed scancodes) — call sites should route this to
    // RdpSessionViewModel.sendComboKeyTap(scanCode, extended).
    onScancodeTap: (Int, Boolean) -> Unit = { _, _ -> },
    // Stage 12: toggles Ctrl/Alt/Shift/Win directly on this keyboard — route
    // to RdpSessionViewModel.toggleStickyModifier, the same sticky-hold
    // mechanism ExtraKeysBar's modifier row already uses, so state stays
    // shared between the two.
    onToggleModifier: (id: String, scanCode: Int, extended: Boolean) -> Unit = { _, _, _ -> },
) {
    var shiftOn by remember(layout) { mutableStateOf(false) }
    var mode by remember(layout) { mutableStateOf(KeyboardMode.LETTERS) }
    val isEnglish = layout == VirtualKeyboardLayout.ENGLISH
    val letterRows = if (isEnglish) englishLetterRows else arabicLetterRows
    val modifierComboMode = activeModifiers.isNotEmpty()

    // TOOLBOX FEATURE (Stage 2/11 RTL fix): a keyboard's rows represent fixed
    // *physical* key positions (like a real hardware keyboard), not
    // direction-dependent reading content. Compose's Row mirrors child order
    // automatically under an RTL LocalLayoutDirection (the whole app runs RTL
    // under an Arabic locale), which would silently reverse every row here —
    // "q w e r t y u i o p" rendering as "p o i u y t r e w q", the digit row
    // rendering 0-9 backwards, etc. Forcing Ltr for this composable's subtree
    // keeps every row's left-to-right key order stable regardless of the
    // surrounding UI's direction, exactly like a real keyboard never
    // reverses its own key layout just because the app around it is RTL.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Surface(
        color = DeepSpace.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, HorizonGray),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (modifierComboMode) {
                Text(
                    activeModifiers.joinToString(" + ") + " + …",
                    style = MaterialTheme.typography.labelSmall,
                    color = PulsarCyan,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }

            // TOOLBOX FEATURE (Stage 12 — "100% real keyboard"): Esc / Tab /
            // Ctrl / Alt / Shift / Win / F1-F12 / Caps / navigation cluster /
            // arrows, all on the keyboard itself — no more switching to the
            // separate ExtraKeysBar to reach them.
            FunctionKeyRow(
                activeModifiers = activeModifiers,
                onToggleModifier = onToggleModifier,
                onTap = onScancodeTap,
            )

            if (mode == KeyboardMode.LETTERS) {
                // TOOLBOX FEATURE (Stage 13): "،"/"؛"/"؟" replace ","/";"/"?"
                // when Arabic is active — see arabicPunctuationOverrides.
                val localizedPunctuationRow = if (isEnglish) punctuationRow
                    else punctuationRow.map { arabicPunctuationOverrides[it] ?: it }

                // TOOLBOX FEATURE (Stage 14): digit row now supports
                // long-press → ٠-٩ (see DigitRow/easternArabicDigits above).
                DigitRow(
                    digits = digitRow,
                    modifierComboMode = modifierComboMode,
                    scancodes = digitRowScancodes,
                    onScancodeTap = onScancodeTap,
                    onChar = onChar,
                )
                if (!isEnglish) {
                    Text(
                        stringResource(R.string.vk_arabic_digits_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = CometTail,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                KeyRow(localizedPunctuationRow.map { it to it }) { display -> onChar(display) }

                letterRows.forEachIndexed { rowIndex, row ->
                    val scancodes = letterRowScancodes.getOrNull(rowIndex)
                    KeyRow(row.mapIndexed { colIndex, ch ->
                        (if (isEnglish && shiftOn) ch.uppercase() else ch) to colIndex
                    }) { colIndex ->
                        val sc = if (modifierComboMode) scancodes?.getOrNull(colIndex) else null
                        if (sc != null) {
                            onScancodeTap(sc, false)
                        } else {
                            val ch = row[colIndex]
                            onChar(if (isEnglish && shiftOn) ch.uppercase() else ch)
                        }
                    }
                }
            } else {
                // TOOLBOX FEATURE (Stage 12/14): full symbol layer — always a
                // plain Unicode character (a real keyboard's symbol row isn't
                // remapped by Ctrl/Alt combos the way letter/digit shortcuts
                // are), so these always go through onChar regardless of
                // modifierComboMode. The digit row is still its own DigitRow
                // (long-press → ٠-٩ works here too, same physical position).
                DigitRow(
                    digits = digitRow,
                    modifierComboMode = modifierComboMode,
                    scancodes = digitRowScancodes,
                    onScancodeTap = onScancodeTap,
                    onChar = onChar,
                )
                localizeSymbols(symbolsRows.drop(1), isEnglish).forEach { row ->
                    KeyRow(row.map { it to it }) { display -> onChar(display) }
                }
            }

            // Bottom control row: layout toggle, symbols/letters toggle,
            // shift (English letters only), space, backspace, enter, hide.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ControlKey(
                    modifier = Modifier.weight(1.2f),
                    icon = Icons.Default.Language,
                    contentDescription = stringResource(R.string.vk_switch_language),
                    onClick = { onLayoutChange(if (isEnglish) VirtualKeyboardLayout.ARABIC else VirtualKeyboardLayout.ENGLISH) },
                )
                ControlKey(
                    modifier = Modifier.weight(1.2f),
                    label = if (mode == KeyboardMode.LETTERS) "#+=" else "ABC",
                    contentDescription = stringResource(
                        if (mode == KeyboardMode.LETTERS) R.string.vk_symbols else R.string.vk_letters
                    ),
                    highlighted = mode == KeyboardMode.SYMBOLS,
                    onClick = { mode = if (mode == KeyboardMode.LETTERS) KeyboardMode.SYMBOLS else KeyboardMode.LETTERS },
                )
                if (isEnglish && mode == KeyboardMode.LETTERS) {
                    ControlKey(
                        modifier = Modifier.weight(1.2f),
                        label = if (shiftOn) "⇧" else "⇧",
                        contentDescription = stringResource(R.string.vk_shift),
                        highlighted = shiftOn,
                        onClick = { shiftOn = !shiftOn },
                    )
                }
                ControlKey(
                    modifier = Modifier.weight(4f),
                    label = "",
                    contentDescription = stringResource(R.string.vk_space),
                    onClick = { onChar(" ") },
                )
                ControlKey(
                    modifier = Modifier.weight(1.2f),
                    icon = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = stringResource(R.string.vk_backspace),
                    onClick = onBackspace,
                )
                ControlKey(
                    modifier = Modifier.weight(1.2f),
                    icon = Icons.Default.KeyboardReturn,
                    contentDescription = stringResource(R.string.vk_enter),
                    onClick = onEnter,
                )
                ControlKey(
                    modifier = Modifier.weight(1.2f),
                    icon = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.vk_hide),
                    onClick = onHide,
                )
            }
        }
    }
    }
}

// TOOLBOX FEATURE (Stage 12 — "100% real keyboard"): the horizontally
// scrollable row of Ctrl/Alt/Shift/Win (sticky) plus Esc/Tab/F-keys/Caps/
// navigation-cluster/arrows (momentary), merged into the keyboard itself.
// A LazyRow rather than a fixed Row since ~28 keys can't fit one phone-width
// screen at a legible size — exactly how ExtraKeysBar already handles the
// same problem for the same keys.
@Composable
private fun FunctionKeyRow(
    activeModifiers: Set<String>,
    onToggleModifier: (id: String, scanCode: Int, extended: Boolean) -> Unit,
    onTap: (Int, Boolean) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(modifierKeys.size) { i ->
            val key = modifierKeys[i]
            val active = activeModifiers.contains(key.id)
            Surface(
                color = if (active) PulsarCyan.copy(alpha = 0.2f) else NebulaSurface,
                border = BorderStroke(1.dp, if (active) PulsarCyan else HorizonGray),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .sizeIn(minWidth = 44.dp, minHeight = 40.dp)
                    .clickable { onToggleModifier(key.id, key.scanCode, key.extended) },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        key.id,
                        color = if (active) PulsarCyan else StarDust,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
        items(functionKeys.size) { i ->
            val key = functionKeys[i]
            Surface(
                color = NebulaSurface,
                border = BorderStroke(1.dp, HorizonGray),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .sizeIn(minWidth = 40.dp, minHeight = 40.dp)
                    .clickable { onTap(key.scanCode, key.extended) },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        key.label,
                        color = StarDust,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

// TOOLBOX FEATURE (Stage 14): the digit row, split out from KeyRow so each
// key can support both a normal tap (Western 0-9 / scancode-combo, exactly
// as before) and a long-press (Eastern Arabic-Indic ٠-٩ — see
// easternArabicDigits above). Long-press always sends a plain character,
// never a scancode: typing an actual "٥" character isn't a keyboard
// shortcut, so it bypasses modifierComboMode entirely.
@Composable
private fun DigitRow(
    digits: List<String>,
    modifierComboMode: Boolean,
    scancodes: List<Int>,
    onScancodeTap: (Int, Boolean) -> Unit,
    onChar: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        digits.forEachIndexed { i, d ->
            Surface(
                color = NebulaSurface,
                border = BorderStroke(1.dp, HorizonGray),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .pointerInput(d) {
                        detectTapGestures(
                            onTap = {
                                if (modifierComboMode && i in scancodes.indices) {
                                    onScancodeTap(scancodes[i], false)
                                } else {
                                    onChar(d)
                                }
                            },
                            onLongPress = { easternArabicDigits[d]?.let(onChar) },
                        )
                    },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(d, color = StarDust, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun <T> KeyRow(keys: List<Pair<String, T>>, onKey: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEach { (display, sendValue) ->
            Surface(
                color = NebulaSurface,
                border = BorderStroke(1.dp, HorizonGray),
                shape = RoundedCornerShape(6.dp),
                // STAGE 11 AUDIT FIX: was heightIn(min = 40.dp). A full
                // 48.dp minWidth per key isn't feasible here — up to 12 keys
                // share one row (see letterRowScancodes' Q..P row), and
                // 12 × 48.dp would overflow every phone width this app
                // targets, the same real-world constraint any dense soft
                // keyboard (including Android's own Gboard) accepts. Height
                // is raised to 44.dp to match the accessibility floor this
                // app already settled on for ExtraKeysBar's own densely
                // packed row (see its "BUGFIX-UI" comment) — each key's
                // visible Text is its own accessible label via this
                // Surface's clickable-merged semantics, so no separate
                // contentDescription is needed.
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clickable { onKey(sendValue) },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(display, color = StarDust, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ControlKey(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    label: String? = null,
    // STAGE 11 AUDIT FIX: new parameter — every icon-only call site below
    // now passes a real description (see the vk_* strings) instead of the
    // previous hardcoded contentDescription = null with no Text fallback.
    // Applied via a semantics modifier on the *container* (below), not on
    // the Icon itself: the space key has neither an icon nor a non-empty
    // label (nothing renders inside it at all), so an Icon-only
    // contentDescription would never attach for it. A shared/labelled key
    // (shift) still gets its visible Text picked up automatically via this
    // Surface's clickable-merged semantics — passing contentDescription
    // there too is harmless (semantics { contentDescription = ... } simply
    // gives TalkBack the same string either way) and keeps every call site
    // below consistent and explicit rather than relying on some keys having
    // it forced and others left to infer it.
    contentDescription: String? = null,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        color = if (highlighted) PulsarCyan.copy(alpha = 0.2f) else NebulaSurface,
        border = BorderStroke(1.dp, if (highlighted) PulsarCyan else HorizonGray),
        shape = RoundedCornerShape(6.dp),
        // STAGE 11 AUDIT FIX: was heightIn(min = 40.dp) with no width floor —
        // under the 48.dp accessibility minimum the plan asks every new
        // button to meet. These six keys share the row via weight(), so
        // (unlike the full letter/digit rows, which must fit far more keys
        // side by side) there's room to raise both dimensions without
        // breaking the layout.
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(
                if (contentDescription != null)
                    Modifier.semantics { this.contentDescription = contentDescription }
                else Modifier
            )
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                // contentDescription intentionally null here — it's already
                // carried by the outer Surface's semantics modifier above.
                // Setting it here too would make TalkBack announce it twice
                // (same double-announce pitfall documented on
                // SessionToolbox.kt's ToolboxToolButton).
                icon != null -> Icon(icon, contentDescription = null, tint = if (highlighted) PulsarCyan else CometTail, modifier = Modifier.size(18.dp))
                !label.isNullOrEmpty() -> Text(label, color = if (highlighted) PulsarCyan else StarDust, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
