package com.systemsgo.hex.util

import com.systemsgo.hex.data.model.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WEB-PORTAL FEATURE: covers QrConnectionParser.parse()'s new "https"/"http"/
 * "web" scheme and JSON "url" alias handling. Plain JVM tests — no Android
 * framework dependency, matching how the rest of this file is written.
 */
class QrConnectionParserTest {

    @Test
    fun `https URI resolves to WEB protocol and preserves the path in webUrl`() {
        val profile = QrConnectionParser.parse("https://192.168.1.10/RDWeb/Pages")
        assertEquals(ProtocolType.WEB, profile.protocolType)
        assertEquals("192.168.1.10", profile.host)
        assertEquals(443, profile.port)
        assertEquals("https://192.168.1.10/RDWeb/Pages", profile.webUrl)
    }

    @Test
    fun `web scheme is an alias for https`() {
        val profile = QrConnectionParser.parse("web://10.0.0.5:8443/ui")
        assertEquals(ProtocolType.WEB, profile.protocolType)
        assertEquals("10.0.0.5", profile.host)
        assertEquals(8443, profile.port)
        assertEquals("https://10.0.0.5:8443/ui", profile.webUrl)
    }

    @Test
    fun `userinfo is stripped from the reconstructed webUrl but still captured as credentials`() {
        val profile = QrConnectionParser.parse("https://admin:secret@10.0.0.9/idrac")
        assertEquals("admin", profile.username)
        assertEquals("secret", profile.password)
        assertEquals("https://10.0.0.9/idrac", profile.webUrl)
    }

    @Test
    fun `JSON url field alone implies WEB protocol and derives host`() {
        val profile = QrConnectionParser.parse("""{"url":"https://esxi.local:443/ui"}""")
        assertEquals(ProtocolType.WEB, profile.protocolType)
        assertEquals("esxi.local", profile.host)
        assertEquals(443, profile.port)
        assertEquals("https://esxi.local:443/ui", profile.webUrl)
    }

    @Test
    fun `non-web profiles leave webUrl blank`() {
        val profile = QrConnectionParser.parse("rdp://admin@192.168.1.10:3389")
        assertEquals(ProtocolType.RDP, profile.protocolType)
        assertEquals("", profile.webUrl)
    }
}
