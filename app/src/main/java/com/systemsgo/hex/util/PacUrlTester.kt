package com.systemsgo.hex.util

import com.systemsgo.hex.data.model.ProtocolType
import java.net.URI

/**
 * PAC-SUPPORT FEATURE (Part 3/n follow-up — testability): the validation +
 * fetch + evaluate pipeline behind the PAC URL "Test" button
 * (PacUrlTestBlock in Components.kt), extracted out of that @Composable
 * into a plain Kotlin class with no Compose/Android dependency.
 *
 * Why this exists: the same logic used to live entirely inside the
 * Composable's onClick lambda. That worked, but it meant the only way to
 * exercise "what does the Test button report for an invalid URL / a 404 /
 * a timeout / a broken script" was a full Compose UI test — a heavy tool
 * for what is really just branching over a couple of sealed results. This
 * class returns a [PacUrlTestOutcome] instead of a localized string
 * (Components.kt maps each case to a stringResource), which is what keeps
 * it a plain JVM type — see PacUrlTesterTest.kt, which covers every branch
 * directly, matching PacFileParserTest.kt's style.
 *
 * [fetchPacScript] and [findProxyForUrl] are taken as plain suspend
 * function references rather than a [PacFileParser] instance directly, so
 * tests can fake the network-touching fetch step (HTTP error / timeout /
 * network error are otherwise only reproducible against a real server)
 * while still exercising the real Rhino-backed evaluation for the
 * success/script-error branches — see the secondary constructor below for
 * the normal (non-test) wiring against a real [PacFileParser].
 */
class PacUrlTester(
    private val fetchPacScript: suspend (String) -> PacFetchResult,
    private val findProxyForUrl: suspend (String, String, String) -> PacEvaluationResult,
) {
    constructor(pacFileParser: PacFileParser) : this(
        fetchPacScript = pacFileParser::fetchPacScript,
        findProxyForUrl = pacFileParser::findProxyForUrl,
    )

    /**
     * Runs the same checks the old inline onClick did, in the same order:
     * missing destination host, then URL shape, then fetch, then evaluate.
     */
    suspend fun test(pacUrl: String, targetHost: String, targetPort: String): PacUrlTestOutcome {
        val trimmedPacUrl = pacUrl.trim()
        val trimmedTargetHost = targetHost.trim()

        // Guard: no destination host to resolve a proxy FOR — ask the user
        // to fill in Host (Quick Connect, above) rather than testing
        // against a meaningless target.
        if (trimmedTargetHost.isEmpty()) {
            return PacUrlTestOutcome.MissingHost
        }

        // UI-only URL shape check BEFORE any network call, so a typo'd URL
        // is reported immediately instead of a confusing low-level fetch
        // error.
        val parsedUri = try { URI(trimmedPacUrl) } catch (e: Exception) { null }
        val validScheme = parsedUri?.scheme?.lowercase() in setOf("http", "https")
        if (parsedUri == null || !validScheme || parsedUri.host.isNullOrBlank()) {
            return PacUrlTestOutcome.InvalidUrl
        }

        val targetPortInt = targetPort.trim().toIntOrNull() ?: ProtocolType.RDP.defaultPort
        // FindProxyForURL's `url` arg is conventionally a full
        // scheme://host:port/ URL (see PacProxyResolver.resolve's own
        // construction of this same shape) — placeholder scheme, not a
        // claim this is actually HTTPS traffic.
        val targetUrl = "https://$trimmedTargetHost:$targetPortInt/"

        return when (val fetch = fetchPacScript(trimmedPacUrl)) {
            is PacFetchResult.HttpError -> {
                if (fetch.code == 404) {
                    PacUrlTestOutcome.NotFound
                } else {
                    PacUrlTestOutcome.HttpError(fetch.code)
                }
            }

            is PacFetchResult.NetworkError -> {
                // fetchPacScript collapses every IOException into this one
                // case, but a timeout's message (or, if null, its
                // exception class's simple name — see fetchPacScript's own
                // fallback) reliably contains "timeout" either way, so
                // this is enough to give a distinct, clearer outcome
                // without changing PacFileParser's return shape.
                if (fetch.message.contains("timeout", ignoreCase = true)) {
                    PacUrlTestOutcome.Timeout
                } else {
                    PacUrlTestOutcome.NetworkError(fetch.message)
                }
            }

            is PacFetchResult.Success -> {
                when (val evaluation = findProxyForUrl(fetch.script, targetUrl, trimmedTargetHost)) {
                    is PacEvaluationResult.Error -> PacUrlTestOutcome.ScriptError(evaluation.message)
                    is PacEvaluationResult.Success -> {
                        val directives = evaluation.directives
                        // Same "first usable entry" rule as
                        // PacProxyResolver.resolve() — the standard says
                        // try each in order until one connects, and this
                        // preview should show the one that would actually
                        // be picked for a real connect attempt.
                        when (val firstUsable = directives.firstOrNull { it !is PacProxyDirective.Unrecognized }) {
                            is PacProxyDirective.Direct ->
                                PacUrlTestOutcome.Success(PacUrlTestOutcome.Resolution.Direct, directives)
                            is PacProxyDirective.Proxy ->
                                PacUrlTestOutcome.Success(
                                    PacUrlTestOutcome.Resolution.Proxy(firstUsable.host, firstUsable.port),
                                    directives,
                                )
                            is PacProxyDirective.Socks ->
                                PacUrlTestOutcome.Success(
                                    PacUrlTestOutcome.Resolution.Socks(firstUsable.host, firstUsable.port),
                                    directives,
                                )
                            else -> PacUrlTestOutcome.NoUsableDirective
                        }
                    }
                }
            }
        }
    }
}

/**
 * Outcome of a [PacUrlTester.test] run. Deliberately not a UI string —
 * Components.kt's PacUrlTestBlock maps each case to a localized
 * stringResource, which is what keeps this whole file free of any
 * Android/Compose dependency.
 */
sealed class PacUrlTestOutcome {
    /** No destination Host was set to resolve a proxy for. */
    object MissingHost : PacUrlTestOutcome()

    /** [pacUrl] isn't a well-formed http(s) URL. */
    object InvalidUrl : PacUrlTestOutcome()

    /** The PAC file returned HTTP 404. */
    object NotFound : PacUrlTestOutcome()

    /** The PAC file fetch returned a non-2xx, non-404 HTTP status. */
    data class HttpError(val code: Int) : PacUrlTestOutcome()

    /** The PAC file fetch failed with what looks like a timeout. */
    object Timeout : PacUrlTestOutcome()

    /** The PAC file fetch failed with some other network error. */
    data class NetworkError(val message: String) : PacUrlTestOutcome()

    /** The PAC script fetched successfully but failed to run. */
    data class ScriptError(val message: String) : PacUrlTestOutcome()

    /** The PAC script ran but returned no usable PROXY/SOCKS/DIRECT entry. */
    object NoUsableDirective : PacUrlTestOutcome()

    /** The PAC script resolved to a usable proxy (or DIRECT). */
    data class Success(
        val resolution: Resolution,
        val directives: List<PacProxyDirective>,
    ) : PacUrlTestOutcome() {
        sealed class Resolution {
            object Direct : Resolution()
            data class Proxy(val host: String, val port: Int) : Resolution()
            data class Socks(val host: String, val port: Int) : Resolution()
        }
    }
}
