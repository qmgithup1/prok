package com.systemsgo.hex.restconf.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.google.gson.GsonBuilder
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * RESTCONF FEATURE (Part 2/4): everything the Request Builder's body editor
 * and the Response Viewer's Pretty/Tree tabs need from raw JSON/XML text —
 * formatting, validation-with-line-numbers, and a format-agnostic
 * [RestconfTreeNode] the tree view renders either format from identically.
 * Kept dependency-free of Compose/Android UI classes (uses only Gson +
 * the platform's built-in XmlPull parser) so it's usable from a unit test
 * without an Android context.
 */
object RestconfFormatting {

    private val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    data class ValidationResult(val isValid: Boolean, val errorMessage: String? = null, val errorLine: Int? = null)

    // ── JSON ──────────────────────────────────────────────────────────

    fun prettyPrintJson(raw: String): String = runCatching {
        prettyGson.toJson(JsonParser.parseString(raw))
    }.getOrElse { raw }

    fun minifyJson(raw: String): String = runCatching {
        GsonBuilder().disableHtmlEscaping().create().toJson(JsonParser.parseString(raw))
    }.getOrElse { raw }

    fun validateJson(raw: String): ValidationResult {
        if (raw.isBlank()) return ValidationResult(true)
        return try {
            JsonParser.parseString(raw)
            ValidationResult(true)
        } catch (e: JsonSyntaxException) {
            // Gson's message often embeds "line N column M"; surface line for the
            // editor's error gutter if present, otherwise just the message.
            val line = Regex("""line (\d+)""").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ValidationResult(false, e.message ?: "Invalid JSON", line)
        } catch (e: Exception) {
            ValidationResult(false, e.message ?: "Invalid JSON")
        }
    }

    fun buildJsonTree(raw: String, rootName: String = "root"): RestconfTreeNode? = runCatching {
        jsonElementToNode(rootName, JsonParser.parseString(raw))
    }.getOrNull()

    private fun jsonElementToNode(name: String, el: JsonElement): RestconfTreeNode = when {
        el.isJsonObject -> RestconfTreeNode(
            name = name, valuePreview = "{${el.asJsonObject.size()}}", kind = RestconfTreeNodeKind.OBJECT,
            children = el.asJsonObject.entrySet().map { (k, v) -> jsonElementToNode(k, v) },
        )
        el.isJsonArray -> RestconfTreeNode(
            name = name, valuePreview = "[${el.asJsonArray.size()}]", kind = RestconfTreeNodeKind.ARRAY,
            children = el.asJsonArray.mapIndexed { i, v -> jsonElementToNode("[$i]", v) },
        )
        el.isJsonNull -> RestconfTreeNode(name = name, valuePreview = "null", kind = RestconfTreeNodeKind.NULL)
        el.isJsonPrimitive -> {
            val p = el.asJsonPrimitive
            val kind = when {
                p.isBoolean -> RestconfTreeNodeKind.BOOLEAN
                p.isNumber -> RestconfTreeNodeKind.NUMBER
                else -> RestconfTreeNodeKind.STRING
            }
            RestconfTreeNode(name = name, valuePreview = p.asString, kind = kind)
        }
        else -> RestconfTreeNode(name = name, valuePreview = "", kind = RestconfTreeNodeKind.NULL)
    }

    // ── XML ───────────────────────────────────────────────────────────

    fun prettyPrintXml(raw: String, indent: Int = 2): String = runCatching {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(raw))
        val sb = StringBuilder()
        var depth = 0
        var event = parser.eventType
        var lastWasStartTag = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(" ".repeat(depth * indent)).append('<').append(parser.name)
                    for (i in 0 until parser.attributeCount) {
                        sb.append(' ').append(parser.getAttributeName(i)).append("=\"").append(parser.getAttributeValue(i)).append('"')
                    }
                    sb.append('>')
                    depth++
                    lastWasStartTag = true
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    if (!lastWasStartTag) sb.append('\n').append(" ".repeat(depth * indent))
                    sb.append("</").append(parser.name).append('>')
                    lastWasStartTag = false
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) { sb.append(text); lastWasStartTag = false }
                }
            }
            event = parser.next()
        }
        sb.toString()
    }.getOrElse { raw }

    fun validateXml(raw: String): ValidationResult {
        if (raw.isBlank()) return ValidationResult(true)
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(raw))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) event = parser.next()
            ValidationResult(true)
        } catch (e: Exception) {
            val line = Regex("""line (\d+)""", RegexOption.IGNORE_CASE).find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ValidationResult(false, e.message ?: "Invalid XML", line)
        }
    }

    fun buildXmlTree(raw: String): RestconfTreeNode? = runCatching {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(raw))
        var event = parser.eventType
        // Skip to first START_TAG.
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = parser.next()
        if (event == XmlPullParser.END_DOCUMENT) return@runCatching null
        parseXmlElement(parser)
    }.getOrNull()

    /** Recursive-descent build of one element (and its subtree) from the parser's current START_TAG; leaves the parser positioned just past the matching END_TAG. */
    private fun parseXmlElement(parser: XmlPullParser): RestconfTreeNode {
        val name = parser.name
        val attrs = (0 until parser.attributeCount).map { i -> parser.getAttributeName(i) to parser.getAttributeValue(i) }
        val children = mutableListOf<RestconfTreeNode>()
        var text = ""
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == name)) {
            when (event) {
                XmlPullParser.START_TAG -> children.add(parseXmlElement(parser))
                XmlPullParser.TEXT -> parser.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text = it }
            }
            event = parser.next()
        }
        val attrPreview = attrs.joinToString(" ") { (k, v) -> "$k=\"$v\"" }
        return RestconfTreeNode(
            name = name,
            valuePreview = if (children.isEmpty()) text else attrPreview,
            kind = if (children.isEmpty()) RestconfTreeNodeKind.STRING else RestconfTreeNodeKind.OBJECT,
            children = children,
            attributes = attrs,
        )
    }

    // ── format-agnostic entry points the Response Viewer calls ─────────

    fun prettyPrint(raw: String, format: RestconfDataFormat): String =
        if (format == RestconfDataFormat.XML) prettyPrintXml(raw) else prettyPrintJson(raw)

    fun validate(raw: String, format: RestconfDataFormat): ValidationResult =
        if (format == RestconfDataFormat.XML) validateXml(raw) else validateJson(raw)

    fun buildTree(raw: String, format: RestconfDataFormat): RestconfTreeNode? =
        if (format == RestconfDataFormat.XML) buildXmlTree(raw) else buildJsonTree(raw)
}

enum class RestconfTreeNodeKind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

/** One node in the Response Viewer's / YANG Browser's tree — format-agnostic, built by [RestconfFormatting.buildTree] from either JSON or XML. */
data class RestconfTreeNode(
    val name: String,
    val valuePreview: String,
    val kind: RestconfTreeNodeKind,
    val children: List<RestconfTreeNode> = emptyList(),
    val attributes: List<Pair<String, String>> = emptyList(), // XML only
) {
    val isLeaf: Boolean get() = children.isEmpty()
}

/**
 * RESTCONF FEATURE (Part 3/4): converts between a RESTCONF resource path
 * (RFC 8040 §3.5.1, e.g. `/ietf-interfaces:interfaces/interface=eth0`) and
 * an approximate YANG instance-identifier-style XPath (§3.5.3 gives the
 * formal ABNF; the abbreviated encoding here trades a strict grammar for
 * something people can read/write by hand — this is a request-builder
 * convenience, not a validating parser).
 *
 * List-key values in the resource path are comma-separated and positional
 * (`list=key1,key2`); since this helper works from the path text alone with
 * no YANG schema loaded, it can't know the real key *names* — it labels
 * them `key1`, `key2`, ... in the XPath form, which is enough to read the
 * structure even though it isn't valid XPath a server would accept
 * literally. That limitation goes away once Part 3's module/schema
 * discovery is extended to actually parse `.yang` key definitions, which is
 * out of scope here (would need a real YANG parser, not just RESTCONF's
 * module *list*).
 */
object RestconfXPathHelper {
    fun resourcePathToXPath(resourcePath: String): String {
        val segments = resourcePath.trim('/').split("/").filter { it.isNotEmpty() }
        return "/" + segments.joinToString("/") { segment ->
            val eq = segment.indexOf('=')
            if (eq < 0) segment else {
                val listName = segment.substring(0, eq)
                val keys = segment.substring(eq + 1).split(",").map { java.net.URLDecoder.decode(it, "UTF-8") }
                val predicates = keys.mapIndexed { i, v -> "[key${i + 1}='$v']" }.joinToString("")
                "$listName$predicates"
            }
        }
    }

    fun xPathToResourcePath(xpath: String): String {
        val segments = xpath.trim('/').split("/").filter { it.isNotEmpty() }
        return "/" + segments.joinToString("/") { segment ->
            val predicateStart = segment.indexOf('[')
            if (predicateStart < 0) segment else {
                val listName = segment.substring(0, predicateStart)
                val keys = Regex("""\[key\d+='([^']*)'\]""").findAll(segment).map {
                    java.net.URLEncoder.encode(it.groupValues[1], "UTF-8")
                }.toList()
                if (keys.isEmpty()) listName else "$listName=${keys.joinToString(",")}"
            }
        }
    }
}
