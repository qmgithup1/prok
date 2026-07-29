package com.systemsgo.hex.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PAC-SUPPORT FEATURE: covers PacFileParser's FindProxyForURL execution
 * (Rhino) and result parsing. Plain JVM tests, no network and no Android
 * framework dependency — matching QrConnectionParserTest's style.
 */
class PacFileParserTest {

    private val parser = PacFileParser()

    @Test
    fun `simple DIRECT result`() = runBlocking {
        val script = """
            function FindProxyForURL(url, host) {
                return "DIRECT";
            }
        """.trimIndent()

        val result = parser.findProxyForUrl(script, "https://example.com/", "example.com")

        assertTrue(result is PacEvaluationResult.Success)
        assertEquals(
            listOf(PacProxyDirective.Direct),
            (result as PacEvaluationResult.Success).directives
        )
    }

    @Test
    fun `single PROXY result`() = runBlocking {
        val script = """
            function FindProxyForURL(url, host) {
                return "PROXY proxy.corp.local:8080";
            }
        """.trimIndent()

        val result = parser.findProxyForUrl(script, "https://intranet.corp.local/", "intranet.corp.local")

        val directives = (result as PacEvaluationResult.Success).directives
        assertEquals(listOf(PacProxyDirective.Proxy("proxy.corp.local", 8080)), directives)
    }

    @Test
    fun `SOCKS with DIRECT fallback parses both entries in order`() = runBlocking {
        val script = """
            function FindProxyForURL(url, host) {
                return "SOCKS socks.corp.local:1080; DIRECT";
            }
        """.trimIndent()

        val result = parser.findProxyForUrl(script, "https://example.com/", "example.com")

        val directives = (result as PacEvaluationResult.Success).directives
        assertEquals(
            listOf(
                PacProxyDirective.Socks("socks.corp.local", 1080),
                PacProxyDirective.Direct
            ),
            directives
        )
    }

    @Test
    fun `realistic script using isPlainHostName and dnsDomainIs helpers`() = runBlocking {
        // A typical corporate PAC: go direct for the local domain and plain
        // hostnames, otherwise use the corporate proxy.
        val script = """
            function FindProxyForURL(url, host) {
                if (isPlainHostName(host) || dnsDomainIs(host, ".corp.local")) {
                    return "DIRECT";
                }
                return "PROXY proxy.corp.local:3128";
            }
        """.trimIndent()

        val internal = parser.findProxyForUrl(script, "http://fileserver/", "fileserver")
        assertEquals(
            listOf(PacProxyDirective.Direct),
            (internal as PacEvaluationResult.Success).directives
        )

        val intranet = parser.findProxyForUrl(script, "http://wiki.corp.local/", "wiki.corp.local")
        assertEquals(
            listOf(PacProxyDirective.Direct),
            (intranet as PacEvaluationResult.Success).directives
        )

        val external = parser.findProxyForUrl(script, "https://example.com/", "example.com")
        assertEquals(
            listOf(PacProxyDirective.Proxy("proxy.corp.local", 3128)),
            (external as PacEvaluationResult.Success).directives
        )
    }

    @Test
    fun `shExpMatch wildcard helper`() = runBlocking {
        val script = """
            function FindProxyForURL(url, host) {
                if (shExpMatch(host, "*.example.com")) {
                    return "PROXY p.example.com:80";
                }
                return "DIRECT";
            }
        """.trimIndent()

        val matched = parser.findProxyForUrl(script, "https://foo.example.com/", "foo.example.com")
        assertEquals(
            listOf(PacProxyDirective.Proxy("p.example.com", 80)),
            (matched as PacEvaluationResult.Success).directives
        )

        val unmatched = parser.findProxyForUrl(script, "https://foo.other.com/", "foo.other.com")
        assertEquals(
            listOf(PacProxyDirective.Direct),
            (unmatched as PacEvaluationResult.Success).directives
        )
    }

    @Test
    fun `missing FindProxyForURL function is reported as an error`() = runBlocking {
        val script = "var notAFunction = 42;"

        val result = parser.findProxyForUrl(script, "https://example.com/", "example.com")

        assertTrue(result is PacEvaluationResult.Error)
    }

    @Test
    fun `invalid JavaScript syntax is reported as an error, not a crash`() = runBlocking {
        val script = "function FindProxyForURL(url, host) { return DIRECT" // missing quotes + brace

        val result = parser.findProxyForUrl(script, "https://example.com/", "example.com")

        assertTrue(result is PacEvaluationResult.Error)
    }

    @Test
    fun `unrecognized directive keyword is preserved rather than dropped`() {
        val directives = parser.parseProxyDirectives("HTTP proxy.example.com:8080")

        assertEquals(1, directives.size)
        assertTrue(directives[0] is PacProxyDirective.Unrecognized)
    }

    @Test
    fun `directive with malformed port falls back to Unrecognized`() {
        val directives = parser.parseProxyDirectives("PROXY proxy.example.com:notaport")

        assertEquals(listOf(PacProxyDirective.Unrecognized("PROXY proxy.example.com:notaport")), directives)
    }
}
