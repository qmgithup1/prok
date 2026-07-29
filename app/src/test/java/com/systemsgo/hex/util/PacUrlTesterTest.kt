package com.systemsgo.hex.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PAC-SUPPORT FEATURE: covers PacUrlTester's branching over
 * [PacUrlTestOutcome] — the logic behind the PAC URL "Test" button in
 * Components.kt's PacUrlTestBlock, extracted specifically so these cases
 * (invalid URL / 404 / timeout / script error, plus the rest) don't require
 * a Compose UI test to exercise. Plain JVM tests, no network and no
 * Android framework dependency — matching PacFileParserTest's style.
 *
 * The HTTP-fetch-outcome cases (404 / other HTTP error / timeout / other
 * network error) fake [PacUrlTester]'s fetchPacScript seam instead of
 * hitting a real server. The evaluation cases (success / script error /
 * no usable directive) delegate to a real [PacFileParser] for
 * findProxyForUrl, same as PacFileParserTest — only the fetch step is
 * faked there, so Rhino itself is never mocked.
 */
class PacUrlTesterTest {

    private val realParser = PacFileParser()

    private fun testerWithFakeFetch(fetch: suspend (String) -> PacFetchResult): PacUrlTester =
        PacUrlTester(fetchPacScript = fetch, findProxyForUrl = realParser::findProxyForUrl)

    // ── Guards before any fetch ──────────────────────────────────────

    @Test
    fun `missing target host is reported without touching the fetcher`() = runBlocking {
        val tester = PacUrlTester(
            fetchPacScript = { fail("fetchPacScript should not be called when Host is empty"); error("unreachable") },
            findProxyForUrl = { _, _, _ -> fail("findProxyForUrl should not be called when Host is empty"); error("unreachable") },
        )

        val outcome = tester.test(pacUrl = "https://pac.corp.local/proxy.pac", targetHost = "  ", targetPort = "3389")

        assertEquals(PacUrlTestOutcome.MissingHost, outcome)
    }

    @Test
    fun `invalid URL is reported without touching the fetcher`() = runBlocking {
        val tester = PacUrlTester(
            fetchPacScript = { fail("fetchPacScript should not be called for an invalid URL"); error("unreachable") },
            findProxyForUrl = { _, _, _ -> fail("findProxyForUrl should not be called for an invalid URL"); error("unreachable") },
        )

        val outcome = tester.test(pacUrl = "not a url", targetHost = "intranet.corp.local", targetPort = "3389")

        assertEquals(PacUrlTestOutcome.InvalidUrl, outcome)
    }

    @Test
    fun `URL missing an http or https scheme is invalid`() = runBlocking {
        val tester = testerWithFakeFetch { fail("should not fetch"); error("unreachable") }

        val outcome = tester.test(pacUrl = "ftp://pac.corp.local/proxy.pac", targetHost = "intranet.corp.local", targetPort = "3389")

        assertEquals(PacUrlTestOutcome.InvalidUrl, outcome)
    }

    // ── Fetch-outcome branches ───────────────────────────────────────

    @Test
    fun `HTTP 404 is reported as NotFound`() = runBlocking {
        val tester = testerWithFakeFetch { PacFetchResult.HttpError(404) }

        val outcome = tester.test(pacUrl = "https://pac.corp.local/proxy.pac", targetHost = "intranet.corp.local", targetPort = "3389")

        assertEquals(PacUrlTestOutcome.NotFound, outcome)
    }

    @Test
    fun `other HTTP errors are reported with their status code`() = runBlocking {
        val tester = testerWithFakeFetch { PacFetchResult.HttpError(503) }

        val outcome = tester.test(pacUrl = "https://pac.corp.local/proxy.pac", targetHost = "intranet.corp.local", targetPort = "3389")

        assertEquals(PacUrlTestOutcome.HttpError(503), outcome)
    }

    @Test
    fun `a network error mentioning timeout is reported as Timeout`() = runBlocking {
        val tester = testerWithFakeFetch { PacFetchResult.NetworkError("Read timed out") }

        val outcome = tester.test(pacUrl = "https://pac.corp.local/proxy.pac", targetHost = "intranet.corp.local", targetPort = "3389")

        assertEquals(PacUrlTestOutcome.Timeout, outcome)
    }

    @Test
    fun `a network error not mentioning timeout keeps its message`() = runBlocking {
        val tester = testerWithFakeFetch { PacFetchResult.NetworkError("Unable to resolve host") }

        val outcome = tester.test(pacUrl = "https://pac.corp.local/proxy.pac", targetHost = "intranet.corp.local", targetPort = "3389")

        assertEquals(PacUrlTestOutcome.NetworkError("Unable to resolve host"), outcome)
    }

    // ── Evaluation branches (real Rhino via realParser) ──────────────

    @Test
    fun `a broken PAC script is reported as ScriptError`() = runBlocking {
        val script = "function FindProxyForURL(url, host) { return DIRECT" // missing quotes + brace
        val tester = testerWithFakeFetch { PacFetchResult.Success(script) }

        val outcome = tester.test(pacUrl = "https://pac.corp.local/proxy.pac", targetHost = "intranet.corp.local", targetPort = "3389")

        assertTrue(outcome is PacUrlTestOutcome.ScriptError)
    }

    @Test
    fun `a script returning only unrecognized entries is NoUsableDirective`() = runBlocking {
        val script = """
            function FindProxyForURL(url, host) {
                return "BOGUS proxy.example.com:8080";
            }
        """.trimIndent()
        val tester = testerWithFakeFetch { PacFetchResult.Success(script) }

        val outcome = tester.test(pacUrl = "https://pac.corp.local/proxy.pac", targetHost = "intranet.corp.local", targetPort = "3389")

        assertEquals(PacUrlTestOutcome.NoUsableDirective, outcome)
    }

    @Test
    fun `DIRECT resolves to Success with the Direct resolution`() = runBlocking {
        val script = """
            function FindProxyForURL(url, host) {
                return "DIRECT";
            }
        """.trimIndent()
        val tester = testerWithFakeFetch { PacFetchResult.Success(script) }

        val outcome = tester.test(pacUrl = "https://pac.corp.local/proxy.pac", targetHost = "intranet.corp.local", targetPort = "3389")

        assertEquals(
            PacUrlTestOutcome.Success(PacUrlTestOutcome.Resolution.Direct, listOf(PacProxyDirective.Direct)),
            outcome,
        )
    }

    @Test
    fun `PROXY resolves to Success with the Proxy resolution and full directive list`() = runBlocking {
        val script = """
            function FindProxyForURL(url, host) {
                return "PROXY proxy.corp.local:8080; DIRECT";
            }
        """.trimIndent()
        val tester = testerWithFakeFetch { PacFetchResult.Success(script) }

        val outcome = tester.test(pacUrl = "https://pac.corp.local/proxy.pac", targetHost = "intranet.corp.local", targetPort = "3389")

        assertEquals(
            PacUrlTestOutcome.Success(
                PacUrlTestOutcome.Resolution.Proxy("proxy.corp.local", 8080),
                listOf(PacProxyDirective.Proxy("proxy.corp.local", 8080), PacProxyDirective.Direct),
            ),
            outcome,
        )
    }

    @Test
    fun `SOCKS resolves to Success with the Socks resolution`() = runBlocking {
        val script = """
            function FindProxyForURL(url, host) {
                return "SOCKS socks.corp.local:1080";
            }
        """.trimIndent()
        val tester = testerWithFakeFetch { PacFetchResult.Success(script) }

        val outcome = tester.test(pacUrl = "https://pac.corp.local/proxy.pac", targetHost = "intranet.corp.local", targetPort = "3389")

        assertEquals(
            PacUrlTestOutcome.Success(
                PacUrlTestOutcome.Resolution.Socks("socks.corp.local", 1080),
                listOf(PacProxyDirective.Socks("socks.corp.local", 1080)),
            ),
            outcome,
        )
    }

    @Test
    fun `a blank target port falls back to the RDP default port in the evaluated URL`() = runBlocking {
        // Not directly observable from the outcome, but a script that
        // inspects `url` proves the fallback took effect instead of
        // throwing/behaving oddly on a blank port.
        val script = """
            function FindProxyForURL(url, host) {
                return url.indexOf(":3389/") != -1 ? "DIRECT" : "PROXY unexpected:1";
            }
        """.trimIndent()
        val tester = testerWithFakeFetch { PacFetchResult.Success(script) }

        val outcome = tester.test(pacUrl = "https://pac.corp.local/proxy.pac", targetHost = "intranet.corp.local", targetPort = "")

        assertEquals(
            PacUrlTestOutcome.Success(PacUrlTestOutcome.Resolution.Direct, listOf(PacProxyDirective.Direct)),
            outcome,
        )
    }

    // ── Real PacFileParser secondary constructor wiring ──────────────

    @Test
    fun `the PacFileParser constructor wires fetch and evaluate through to the real parser`() = runBlocking {
        // No network call happens here — the URL itself is invalid, so
        // this only exercises that the secondary constructor's method
        // references are wired correctly (it would throw/NPE otherwise).
        val tester = PacUrlTester(realParser)

        val outcome = tester.test(pacUrl = "not a url", targetHost = "intranet.corp.local", targetPort = "3389")

        assertEquals(PacUrlTestOutcome.InvalidUrl, outcome)
    }
}
