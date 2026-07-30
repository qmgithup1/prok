package com.systemsgo.hex.restconf.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.systemsgo.hex.data.model.RestconfDataFormat
import com.systemsgo.hex.ui.theme.NovaPink
import com.systemsgo.hex.ui.theme.PlasmaGreen
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.QuantumBlue
import com.systemsgo.hex.ui.theme.SolarFlare
import com.systemsgo.hex.ui.theme.VoidPurple

/**
 * RESTCONF FEATURE (Part 2/4): lightweight regex-token highlighter — not a
 * full lexer/AST, but RESTCONF payloads are small config/state fragments
 * (not megabyte documents), so a single-pass regex walk is both simple and
 * fast enough to run on every keystroke in [RestconfBodyEditor] without a
 * debounce. JSON and XML each get their own token table; unrecognized
 * regions fall through with no styling rather than crashing on malformed
 * input mid-edit (the editor is highlighting text the user is actively
 * typing, so "currently invalid" is the normal, expected state).
 */
object RestconfSyntaxHighlighter {

    @Composable
    fun highlight(text: String, format: RestconfDataFormat): AnnotatedString =
        if (format == RestconfDataFormat.XML) highlightXml(text, tagColor = QuantumBlue, attrNameColor = SolarFlare, valueColor = PlasmaGreen, punctColor = VoidPurple)
        else highlightJson(text, keyColor = QuantumBlue, stringColor = PlasmaGreen, numberColor = SolarFlare, keywordColor = PulsarCyan, punctColor = VoidPurple)

    private val jsonKeyRegex = Regex(""""([^"\\]|\\.)*"\s*(?=:)""")
    private val jsonStringRegex = Regex("\"([^\"\\\\]|\\\\.)*\"")
    private val jsonNumberRegex = Regex("""-?\b\d+\.?\d*([eE][+-]?\d+)?\b""")
    private val jsonKeywordRegex = Regex("""\b(true|false|null)\b""")
    private val jsonPunctRegex = Regex("""[{}\[\],:]""")

    private fun highlightJson(text: String, keyColor: Color, stringColor: Color, numberColor: Color, keywordColor: Color, punctColor: Color): AnnotatedString =
        buildAnnotatedString {
            append(text)
            // Order matters: keys first (string-followed-by-colon), then remaining
            // strings, then numbers/keywords/punctuation — each pass only
            // (re)colors ranges the earlier passes haven't already claimed, so we
            // track claimed ranges instead of re-scanning already-styled text.
            val claimed = BooleanArray(text.length)
            fun applyIfFree(range: IntRange, style: SpanStyle) {
                if (range.isEmpty()) return
                if ((range.first..range.last).any { it < claimed.size && claimed[it] }) return
                addStyle(style, range.first, range.last + 1)
                for (i in range.first..range.last) if (i < claimed.size) claimed[i] = true
            }
            jsonKeyRegex.findAll(text).forEach { applyIfFree(it.range, SpanStyle(color = keyColor)) }
            jsonStringRegex.findAll(text).forEach { applyIfFree(it.range, SpanStyle(color = stringColor)) }
            jsonNumberRegex.findAll(text).forEach { applyIfFree(it.range, SpanStyle(color = numberColor)) }
            jsonKeywordRegex.findAll(text).forEach { applyIfFree(it.range, SpanStyle(color = keywordColor)) }
            jsonPunctRegex.findAll(text).forEach { applyIfFree(it.range, SpanStyle(color = punctColor)) }
        }

    private val xmlTagRegex = Regex("""</?[a-zA-Z_][\w:.-]*""")
    private val xmlAttrNameRegex = Regex("""\b[a-zA-Z_][\w:.-]*(?=\s*=)""")
    private val xmlAttrValueRegex = Regex(""""[^"]*"|'[^']*'""")
    private val xmlPunctRegex = Regex("""[<>/=]""")

    private fun highlightXml(text: String, tagColor: Color, attrNameColor: Color, valueColor: Color, punctColor: Color): AnnotatedString =
        buildAnnotatedString {
            append(text)
            val claimed = BooleanArray(text.length)
            fun applyIfFree(range: IntRange, style: SpanStyle) {
                if (range.isEmpty()) return
                if ((range.first..range.last).any { it < claimed.size && claimed[it] }) return
                addStyle(style, range.first, range.last + 1)
                for (i in range.first..range.last) if (i < claimed.size) claimed[i] = true
            }
            xmlTagRegex.findAll(text).forEach { applyIfFree(it.range, SpanStyle(color = tagColor)) }
            xmlAttrValueRegex.findAll(text).forEach { applyIfFree(it.range, SpanStyle(color = valueColor)) }
            xmlAttrNameRegex.findAll(text).forEach { applyIfFree(it.range, SpanStyle(color = attrNameColor)) }
            xmlPunctRegex.findAll(text).forEach { applyIfFree(it.range, SpanStyle(color = punctColor)) }
        }
}
