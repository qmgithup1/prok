package com.systemsgo.hex.restconf.protocol

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * RESTCONF FEATURE (Part 4/4): turns any [com.systemsgo.hex.data.model.RestconfSavedRequest]
 * into a reusable *template* the moment its path/body/headers/queryParams
 * contain one or more `{{name}}` placeholders — deliberately not a separate
 * "is this a template" flag on the entity, so every existing saved request
 * automatically gets this for free the instant someone edits it to add a
 * placeholder, and a plain saved request with no `{{...}}` in it behaves
 * exactly as before (load = fill the editor immediately, same as pre-Part-4).
 *
 * A handful of *dynamic* placeholders ([BUILTIN_RESOLVERS]) resolve
 * automatically without prompting the user — same idea as Postman's
 * `{{$timestamp}}`/`{{$guid}}` dynamic variables, just without the `$`
 * prefix since RESTCONF paths/bodies don't otherwise use one. Everything
 * else becomes a field in [com.systemsgo.hex.restconf.ui.RestconfTemplateFillDialog].
 *
 * RESTCONF FEATURE (Part 5): a third resolution tier sits between the two —
 * the active [com.systemsgo.hex.data.model.RestconfEnvironment]'s variables
 * (an `environment` map every caller here now threads through, resolved by
 * [com.systemsgo.hex.data.repository.RestconfExplorerRepository.resolveActiveVariables]).
 * Precedence is user-typed fill value > active environment variable >
 * builtin > left untouched — an environment variable named `today` would
 * shadow the builtin, same as Postman lets an environment variable shadow a
 * dynamic variable of the same name. This is deliberately *not* a reason to
 * treat environment-backed placeholders as "not a template" in [isTemplate]-
 * style checks: a saved request can rely entirely on environment variables
 * and never prompt the user at all, which is the whole point of having
 * environments — see [extractPlaceholders]'s `environment` parameter, which
 * is what actually removes the prompt.
 */
object RestconfTemplateEngine {

    private val placeholderRegex = Regex("""\{\{\s*([A-Za-z0-9_.]+)\s*\}\}""")

    private val BUILTIN_RESOLVERS: Map<String, () -> String> = mapOf(
        "timestamp" to { System.currentTimeMillis().toString() },
        "uuid" to { UUID.randomUUID().toString() },
        "today" to { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) },
    )

    /** All distinct placeholder names across the given texts, in first-seen order, excluding the builtins and anything already supplied by [environment] (neither ever needs a user-supplied value). */
    fun extractPlaceholders(vararg texts: String, environment: Map<String, String> = emptyMap()): List<String> {
        val found = linkedSetOf<String>()
        texts.forEach { text -> placeholderRegex.findAll(text).forEach { found.add(it.groupValues[1]) } }
        return found.filterNot { it in BUILTIN_RESOLVERS || it in environment }.toList()
    }

    fun hasPlaceholders(vararg texts: String): Boolean =
        texts.any { placeholderRegex.containsMatchIn(it) }

    /** True if [texts] has at least one placeholder that isn't already resolvable from [environment] or a builtin — i.e. one that would actually need [com.systemsgo.hex.restconf.ui.RestconfTemplateFillDialog] to prompt for something. A saved request whose only placeholders are covered by the active environment is *not* a template from the caller's point of view: it should load straight in, auto-substituted, exactly like a plain saved request. */
    fun needsFillDialog(vararg texts: String, environment: Map<String, String> = emptyMap()): Boolean =
        texts.any { text -> placeholderRegex.findAll(text).any { it.groupValues[1] !in BUILTIN_RESOLVERS && it.groupValues[1] !in environment } }

    /** Replaces every `{{name}}` in [text] with `values[name]`, falling back to [environment] then a builtin resolver, then leaving the placeholder untouched if none has a value (rather than silently dropping it — an unresolved placeholder left visible is easier to notice and fix than a request that silently went out with a blank in it). */
    fun substitute(text: String, values: Map<String, String>, environment: Map<String, String> = emptyMap()): String =
        placeholderRegex.replace(text) { match ->
            val name = match.groupValues[1]
            values[name] ?: environment[name] ?: BUILTIN_RESOLVERS[name]?.invoke() ?: match.value
        }
}
