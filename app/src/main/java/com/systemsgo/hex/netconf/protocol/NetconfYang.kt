package com.systemsgo.hex.netconf.protocol

/**
 * YANG-BROWSER FEATURE (Part 1/N — discovery + schema fetch + parsing):
 * everything needed to power a YANG module browser on top of an already-
 * connected [NetconfClient]/[NetconfOperations] pair, without pulling in a
 * third-party YANG compiler (none is available offline in this build
 * environment — see the class docs below for what a from-scratch, RFC-scope
 * parser here does and does not cover).
 */

/** One YANG module the server has told us about — either via its `<hello>` capabilities or `ietf-yang-library`. */
data class NetconfYangModule(
    val name: String,
    val namespace: String,
    val revision: String? = null,
    val prefix: String? = null,
    val features: List<String> = emptyList(),
    val deviations: List<String> = emptyList(),
    /** Whether get-schema (RFC 6022) is expected to work for this module, i.e. the server advertised ietf-netconf-monitoring or this module came from ietf-yang-library. */
    val schemaFetchable: Boolean = false,
)

/**
 * Parses the `module=NAME&revision=DATE&features=...&deviations=...` query
 * string NETCONF servers append to every non-base capability URI (RFC 7950
 * §5.6.4 / the older RFC 6020 equivalent both servers in the wild still use)
 * — this is the zero-round-trip module list every server already gives us
 * for free in its `<hello>`, before any `ietf-yang-library` query is needed.
 */
object NetconfCapabilityParser {
    fun parseModules(capabilities: List<NetconfCapability>): List<NetconfYangModule> =
        capabilities.mapNotNull { cap -> parseOne(cap.uri) }

    private fun parseOne(uri: String): NetconfYangModule? {
        // Base/notification/framing capabilities (no '?module=' query) aren't
        // YANG modules at all — e.g. urn:ietf:params:netconf:base:1.1.
        val qIdx = uri.indexOf('?')
        if (qIdx == -1) return null
        val namespace = uri.substring(0, qIdx)
        val query = uri.substring(qIdx + 1)
        val params = query.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq == -1) null else part.substring(0, eq) to urlDecode(part.substring(eq + 1))
        }.toMap()
        val moduleName = params["module"] ?: return null
        return NetconfYangModule(
            name = moduleName,
            namespace = namespace,
            revision = params["revision"],
            features = params["features"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            deviations = params["deviations"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }

    private fun urlDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) { s }
}

/** In-memory cache of fetched YANG source text, keyed by "name@revision" (or just "name" when the server didn't advertise one). */
class NetconfSchemaCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun get(module: NetconfYangModule): String? = cache[keyOf(module)]
    fun put(module: NetconfYangModule, source: String) { cache[keyOf(module)] = source }
    fun clear() = cache.clear()
    private fun keyOf(module: NetconfYangModule) = "${module.name}@${module.revision ?: ""}"
}

/**
 * YANG-BROWSER FEATURE (Part 2/N — schema retrieval + module search):
 * discovers the module list and fetches raw YANG source via RFC 6022
 * `<get-schema>`, with an `ietf-yang-library` (RFC 8525 / RFC 7895) fallback
 * for servers that under-report modules in their `<hello>` (some do, for
 * modules with no deviations/features to advertise).
 */
class NetconfYangService(
    private val client: NetconfClient,
    private val ops: NetconfOperations,
    private val cache: NetconfSchemaCache = NetconfSchemaCache(),
) {
    /** True once the server's hello showed ietf-netconf-monitoring, meaning get-schema is legal to try. */
    private fun serverSupportsGetSchema(hello: NetconfHelloInfo): Boolean =
        hello.capabilities.any { it.uri.contains("ietf-netconf-monitoring") }

    /** Combines the free hello-derived module list with an ietf-yang-library query when available, de-duplicated by name. */
    suspend fun discoverModules(): List<NetconfYangModule> {
        val hello = client.hello.value ?: return emptyList()
        val fromHello = NetconfCapabilityParser.parseModules(hello.capabilities)
        val getSchemaOk = serverSupportsGetSchema(hello)
        val fromLibrary = runCatching { discoverViaYangLibrary() }.getOrDefault(emptyList())

        val byName = LinkedHashMap<String, NetconfYangModule>()
        (fromHello + fromLibrary).forEach { m ->
            val marked = m.copy(schemaFetchable = getSchemaOk || m.schemaFetchable)
            val existing = byName[m.name]
            byName[m.name] = if (existing == null) marked else existing.copy(
                revision = existing.revision ?: marked.revision,
                features = (existing.features + marked.features).distinct(),
                schemaFetchable = existing.schemaFetchable || marked.schemaFetchable,
            )
        }
        return byName.values.sortedBy { it.name }
    }

    /** RFC 8525/7895 `ietf-yang-library` operational-state query — catches modules a terse `<hello>` omitted. */
    private suspend fun discoverViaYangLibrary(): List<NetconfYangModule> {
        val filter = NetconfFilter.Subtree(
            "<yang-library xmlns=\"urn:ietf:params:xml:ns:yang:ietf-yang-library\"/>" +
                "<modules-state xmlns=\"urn:ietf:params:xml:ns:yang:ietf-yang-library\"/>"
        )
        val xml = ops.get(filter)
        return parseYangLibraryXml(xml)
    }

    private fun parseYangLibraryXml(xml: String): List<NetconfYangModule> {
        return try {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
            val moduleNodes = doc.getElementsByTagNameNS("*", "module")
            (0 until moduleNodes.length).mapNotNull { i ->
                val el = moduleNodes.item(i) as? org.w3c.dom.Element ?: return@mapNotNull null
                fun child(tag: String) = el.getElementsByTagNameNS("*", tag).let { if (it.length > 0) it.item(0).textContent.trim() else null }
                val name = child("name") ?: return@mapNotNull null
                NetconfYangModule(
                    name = name,
                    namespace = child("namespace") ?: "",
                    revision = child("revision")?.ifBlank { null },
                    schemaFetchable = true,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** RFC 6022 `<get-schema>` — fetches raw YANG (or YIN) source for one module, using [cache] to avoid re-fetching. */
    suspend fun fetchSchema(module: NetconfYangModule, format: String = "yang"): String {
        cache.get(module)?.let { return it }
        val revisionElem = module.revision?.let { "<revision>$it</revision>" } ?: ""
        val body = "<get-schema xmlns=\"urn:ietf:params:xml:ns:yang:ietf-netconf-monitoring\">" +
            "<identifier>${module.name}</identifier>$revisionElem<format>$format</format></get-schema>"
        val reply = client.sendRawRpc(body)
        val source = extractSchemaData(reply)
        cache.put(module, source)
        return source
    }

    /** `<get-schema>`'s reply wraps the raw text in `<data>...</data>` — unescape and return just the schema source. */
    private fun extractSchemaData(replyXml: String): String {
        val m = Regex("<data[^>]*>(.*?)</data>", RegexOption.DOT_MATCHES_ALL).find(replyXml)
            ?: return replyXml
        return m.groupValues[1]
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
            .trim()
    }
}

// ── Real (brace-structure) YANG statement parser ────────────────────────

/** One parsed YANG statement: `keyword [argument] { children } | ;`. Generic over every YANG 1.0/1.1 keyword — RFC 7950's full grammar, not just data-node keywords. */
data class YangStatement(
    val keyword: String,
    val argument: String?,
    val children: List<YangStatement>,
)

/**
 * YANG-BROWSER FEATURE (Part 3/N — parser): a real, from-scratch tokenizer/
 * parser for YANG's brace-delimited statement grammar (RFC 7950 §6.3) —
 * every YANG module, regardless of which data-modeling constructs it uses,
 * is just `keyword argument { statement* } | keyword argument ;` recursively,
 * so this doesn't need a codegen'd ANTLR grammar to be correct; it only
 * needs to get quoting/escaping/comments right, which is what most of the
 * tokenizer below is doing.
 *
 * This intentionally does NOT resolve `import`/`include`/`uses`/`typedef`
 * references across modules (that needs the full dependency closure fetched
 * via repeated [NetconfYangService.fetchSchema] calls, which the tree-view
 * UI triggers lazily instead) — it only parses the one module's own source
 * into a statement tree, which is exactly what a schema tree/namespace
 * explorer needs.
 */
object YangParser {

    fun parse(source: String): List<YangStatement> {
        val tokens = tokenize(source)
        val (statements, _) = parseStatements(tokens, 0)
        return statements
    }

    private sealed class Token {
        data class Word(val text: String) : Token()
        data class Str(val text: String) : Token()
        object LBrace : Token()
        object RBrace : Token()
        object Semi : Token()
    }

    private fun tokenize(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    while (i < n && source[i] != '\n') i++
                }
                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(source[i] == '*' && source[i + 1] == '/')) i++
                    i += 2
                }
                c == '{' -> { tokens.add(Token.LBrace); i++ }
                c == '}' -> { tokens.add(Token.RBrace); i++ }
                c == ';' -> { tokens.add(Token.Semi); i++ }
                c == '"' || c == '\'' -> {
                    val quote = c
                    val sb = StringBuilder()
                    i++
                    while (i < n && source[i] != quote) {
                        if (quote == '"' && source[i] == '\\' && i + 1 < n) {
                            // RFC 7950 §6.1.3: only \n \t \" \\ are recognized escapes in double-quoted strings.
                            when (source[i + 1]) {
                                'n' -> { sb.append('\n'); i += 2 }
                                't' -> { sb.append('\t'); i += 2 }
                                '"' -> { sb.append('"'); i += 2 }
                                '\\' -> { sb.append('\\'); i += 2 }
                                else -> { sb.append(source[i]); i++ }
                            }
                        } else {
                            sb.append(source[i]); i++
                        }
                    }
                    i++ // closing quote
                    // YANG allows implicit concatenation of an adjacent quoted
                    // string with '+' between them — fold it into one token.
                    var text = sb.toString()
                    var j = i
                    while (j < n) {
                        while (j < n && source[j].isWhitespace()) j++
                        if (j < n && source[j] == '+') {
                            j++
                            while (j < n && source[j].isWhitespace()) j++
                            if (j < n && (source[j] == '"' || source[j] == '\'')) {
                                val q2 = source[j]; j++
                                val sb2 = StringBuilder()
                                while (j < n && source[j] != q2) { sb2.append(source[j]); j++ }
                                j++
                                text += sb2.toString()
                                i = j
                                continue
                            }
                        }
                        break
                    }
                    tokens.add(Token.Str(text))
                }
                else -> {
                    val start = i
                    while (i < n && !source[i].isWhitespace() && source[i] !in "{};\"'") i++
                    if (i == start) i++ else tokens.add(Token.Word(source.substring(start, i)))
                }
            }
        }
        return tokens
    }

    private fun parseStatements(tokens: List<Token>, start: Int): Pair<List<YangStatement>, Int> {
        val statements = mutableListOf<YangStatement>()
        var i = start
        while (i < tokens.size) {
            when (val t = tokens[i]) {
                is Token.RBrace -> return statements to i // caller consumes the RBrace
                is Token.Word -> {
                    val keyword = t.text
                    i++
                    var argument: String? = null
                    if (i < tokens.size && (tokens[i] is Token.Word || tokens[i] is Token.Str)) {
                        argument = when (val a = tokens[i]) {
                            is Token.Word -> a.text
                            is Token.Str -> a.text
                            else -> null
                        }
                        i++
                    }
                    when {
                        i < tokens.size && tokens[i] is Token.LBrace -> {
                            val (children, next) = parseStatements(tokens, i + 1)
                            i = if (next < tokens.size && tokens[next] is Token.RBrace) next + 1 else next
                            statements.add(YangStatement(keyword, argument, children))
                        }
                        i < tokens.size && tokens[i] is Token.Semi -> {
                            i++
                            statements.add(YangStatement(keyword, argument, emptyList()))
                        }
                        else -> {
                            // Malformed/truncated source — stop parsing rather than loop forever.
                            statements.add(YangStatement(keyword, argument, emptyList()))
                        }
                    }
                }
                else -> i++ // stray token — skip defensively
            }
        }
        return statements to i
    }
}

/** Simplified node the tree-view UI actually renders — a filtered/typed projection of [YangStatement]. */
data class YangTreeNode(
    val kind: String,       // "module", "container", "list", "leaf", "leaf-list", "choice", "case", "grouping", "rpc", "notification", "anyxml", "anydata", "uses", "augment"
    val name: String,
    val dataType: String? = null,   // for leaf/leaf-list: the "type" statement's argument
    val description: String? = null,
    val isConfig: Boolean = true,   // "config false" marks operational-only nodes
    val children: List<YangTreeNode> = emptyList(),
)

/** The statement keywords that actually define schema-tree nodes worth showing — every other YANG keyword (must/when/status/reference/organization/contact/typedef/...) becomes metadata on its parent instead of its own tree row. */
private val DATA_NODE_KEYWORDS = setOf(
    "container", "list", "leaf", "leaf-list", "choice", "case",
    "rpc", "notification", "anyxml", "anydata", "uses", "augment", "grouping",
)

object YangTreeBuilder {

    fun build(source: String): YangTreeNode {
        val top = YangParser.parse(source)
        val moduleStmt = top.firstOrNull { it.keyword == "module" || it.keyword == "submodule" }
        return if (moduleStmt != null) {
            toNode(moduleStmt, kindOverride = "module")
        } else {
            YangTreeNode(kind = "module", name = "(unparsed)", children = top.mapNotNull { toNodeOrNull(it) })
        }
    }

    private fun toNodeOrNull(stmt: YangStatement): YangTreeNode? =
        if (stmt.keyword in DATA_NODE_KEYWORDS) toNode(stmt) else null

    private fun toNode(stmt: YangStatement, kindOverride: String? = null): YangTreeNode {
        val typeArg = stmt.children.firstOrNull { it.keyword == "type" }?.argument
        val descArg = stmt.children.firstOrNull { it.keyword == "description" }?.argument
        val configArg = stmt.children.firstOrNull { it.keyword == "config" }?.argument
        val childNodes = stmt.children.mapNotNull { toNodeOrNull(it) }
        return YangTreeNode(
            kind = kindOverride ?: stmt.keyword,
            name = stmt.argument ?: "",
            dataType = typeArg,
            description = descArg,
            isConfig = configArg != "false",
            children = childNodes,
        )
    }
}
