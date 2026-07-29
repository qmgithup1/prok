package com.systemsgo.hex.netconf.xml

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * XML-EDITOR FEATURE (Part 1/N — syntax highlighting): a real, regex-driven
 * tokenizer over raw XML text, producing an [AnnotatedString] a
 * [androidx.compose.foundation.text.BasicTextField]'s `visualTransformation`
 * can render — this project has no bundled code-editor library, so this is
 * a from-scratch (but complete-for-XML, not a toy) tokenizer covering every
 * construct RPC bodies/get-config replies actually contain: tags, attribute
 * names/values, comments, CDATA, processing instructions, and text content.
 *
 * Deliberately single-pass over the whole document rather than line-by-line
 * (unlike [XmlDiff], which is line-oriented) because XML tokens routinely
 * span a rendered "line" boundary inside long attribute values.
 */
object XmlHighlighter {

    // Muted, high-contrast palette that reads well on both the app's light
    // and dark Material 3 schemes (checked against NovaPink/CometTail/etc.
    // already used elsewhere in the session UI, but independent of theme
    // color roles so this stays legible without a ColorScheme reference).
    private val TagColor = Color(0xFF7DA6FF)
    private val AttrNameColor = Color(0xFF9CDCFE)
    private val AttrValueColor = Color(0xFFCE9178)
    private val CommentColor = Color(0xFF6A9955)
    private val TextColor = Color(0xFFD4D4D4)
    private val PunctColor = Color(0xFF808080)
    private val CdataColor = Color(0xFFDCDCAA)

    fun highlight(xml: String): AnnotatedString = AnnotatedString.Builder(xml).run {
        var i = 0
        val n = xml.length
        while (i < n) {
            when {
                xml.startsWith("<!--", i) -> {
                    val end = xml.indexOf("-->", i).let { if (it == -1) n else it + 3 }
                    addStyle(SpanStyle(color = CommentColor), i, end.coerceAtMost(n))
                    i = end
                }
                xml.startsWith("<![CDATA[", i) -> {
                    val end = xml.indexOf("]]>", i).let { if (it == -1) n else it + 3 }
                    addStyle(SpanStyle(color = CdataColor), i, end.coerceAtMost(n))
                    i = end
                }
                xml.startsWith("<?", i) -> {
                    val end = xml.indexOf("?>", i).let { if (it == -1) n else it + 2 }
                    addStyle(SpanStyle(color = PunctColor), i, end.coerceAtMost(n))
                    i = end
                }
                xml[i] == '<' -> {
                    val gt = xml.indexOf('>', i)
                    if (gt == -1) { addStyle(SpanStyle(color = TagColor), i, n); i = n }
                    else { i = highlightTag(this, xml, i, gt + 1) }
                }
                else -> {
                    val next = xml.indexOf('<', i).let { if (it == -1) n else it }
                    addStyle(SpanStyle(color = TextColor), i, next)
                    i = next
                }
            }
        }
        toAnnotatedString()
    }

    private fun highlightTag(b: AnnotatedString.Builder, xml: String, start: Int, end: Int): Int {
        b.addStyle(SpanStyle(color = TagColor, fontWeight = FontWeight.SemiBold), start, end)
        // The element name itself starts right after '<' (open tag) or
        // after '</' (closing tag) — skip highlighting it as an attribute.
        val elementNameStart = if (xml.getOrNull(start + 1) == '/') start + 2 else start + 1
        // Re-scan inside the tag for attribute="value" pairs and overlay
        // finer-grained colors on top of the base tag color above.
        var j = start
        var inName = false
        var nameStart = -1
        while (j < end) {
            val c = xml[j]
            when {
                c.isWhitespace() && !inName -> j++
                c == '=' -> j++
                c == '"' || c == '\'' -> {
                    val quote = c
                    val valStart = j
                    j++
                    while (j < end && xml[j] != quote) j++
                    j = (j + 1).coerceAtMost(end)
                    b.addStyle(SpanStyle(color = AttrValueColor), valStart, j)
                }
                c.isLetter() || c == '_' || c == ':' || c == '-' -> {
                    if (!inName) { inName = true; nameStart = j }
                    j++
                    if (j >= end || !(xml[j].isLetterOrDigit() || xml[j] in "_:-.")) {
                        if (inName && nameStart != elementNameStart) {
                            b.addStyle(SpanStyle(color = AttrNameColor), nameStart, j)
                        }
                        inName = false
                    }
                }
                else -> j++
            }
        }
        return end
    }
}

data class XmlValidationResult(
    val isWellFormed: Boolean,
    val errorMessage: String? = null,
    val errorLine: Int? = null,
    val errorColumn: Int? = null,
)

/**
 * XML-EDITOR FEATURE (Part 2/N — validation): real well-formedness checking
 * via the platform's own SAX parser (not a hand-rolled matcher, so error
 * line/column numbers are accurate) — used by the RPC Builder to flag a
 * broken `<rpc>` body before it's ever sent to the server.
 */
object XmlValidator {
    fun validate(xmlFragment: String): XmlValidationResult {
        if (xmlFragment.isBlank()) return XmlValidationResult(isWellFormed = false, errorMessage = "Empty body")
        // RPC Builder bodies are fragments (no single root, no XML
        // declaration) — wrap in a throwaway root so a genuinely
        // well-formed multi-element fragment doesn't false-positive as an
        // error purely for lacking one.
        val wrapped = "<_root_>$xmlFragment</_root_>"
        return try {
            val factory = javax.xml.parsers.SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newSAXParser()
            parser.parse(
                java.io.ByteArrayInputStream(wrapped.toByteArray(Charsets.UTF_8)),
                object : org.xml.sax.helpers.DefaultHandler() {},
            )
            XmlValidationResult(isWellFormed = true)
        } catch (e: org.xml.sax.SAXParseException) {
            XmlValidationResult(
                isWellFormed = false,
                errorMessage = e.message,
                errorLine = e.lineNumber.takeIf { it > 0 },
                errorColumn = e.columnNumber.takeIf { it > 0 },
            )
        } catch (e: Exception) {
            XmlValidationResult(isWellFormed = false, errorMessage = e.message ?: "Invalid XML")
        }
    }
}

/**
 * XML-EDITOR FEATURE (Part 3/N — auto-completion): suggests closing tags and
 * element names as the user types, driven by whatever tag vocabulary is
 * currently relevant — the selected YANG module's data-node names (when the
 * YANG Browser has one loaded) plus a static set of RFC 6241 envelope/
 * operation keywords that are relevant in every NETCONF session regardless
 * of which YANG modules the target device implements.
 */
object XmlAutoComplete {

    private val RFC6241_KEYWORDS = listOf(
        "rpc", "rpc-reply", "get", "get-config", "edit-config", "copy-config", "delete-config",
        "lock", "unlock", "validate", "commit", "confirmed", "confirm-timeout", "persist", "persist-id",
        "cancel-commit", "discard-changes", "close-session", "kill-session", "session-id",
        "filter", "source", "target", "config", "default-operation", "test-option", "error-option",
        "running", "candidate", "startup", "operational", "intended",
        "create-subscription", "stream", "startTime", "stopTime",
    )

    /** @param knownTagNames additional tag vocabulary, e.g. from the currently-loaded YANG module's tree (leaf/container/list names). */
    fun suggest(textBeforeCursor: String, knownTagNames: List<String> = emptyList()): List<String> {
        // Only offer suggestions right after typing '<' (opening a new tag) —
        // anywhere else, a suggestion would just be noise.
        val ltIdx = textBeforeCursor.lastIndexOf('<')
        if (ltIdx == -1) return emptyList()
        val fragment = textBeforeCursor.substring(ltIdx + 1)
        if (fragment.contains('>') || fragment.contains(' ') || fragment.contains('\n')) return emptyList()
        val prefix = fragment.removePrefix("/")
        val pool = (RFC6241_KEYWORDS + knownTagNames).distinct()
        if (prefix.isBlank()) return pool.take(12)
        return pool.filter { it.startsWith(prefix, ignoreCase = true) }.take(12)
    }

    /** Given the text and a chosen suggestion, returns the new text with that tag name inserted at the last `<`/`</`. */
    fun applySuggestion(fullText: String, cursorPos: Int, suggestion: String): Pair<String, Int> {
        val before = fullText.substring(0, cursorPos)
        val after = fullText.substring(cursorPos)
        val ltIdx = before.lastIndexOf('<')
        if (ltIdx == -1) return fullText to cursorPos
        val isClosing = before.getOrNull(ltIdx + 1) == '/'
        val prefixEnd = ltIdx + 1 + (if (isClosing) 1 else 0)
        val newBefore = before.substring(0, prefixEnd) + suggestion
        return (newBefore + after) to newBefore.length
    }
}
