package com.systemsgo.hex.snmp.mib

import com.systemsgo.hex.snmp.protocol.Oid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MibParserTest {

    @Test
    fun `resolves a simple OBJECT IDENTIFIER chain`() {
        val text = """
            ACME-MIB DEFINITIONS ::= BEGIN
            acme OBJECT IDENTIFIER ::= { enterprises 9999 }
            acmeProducts OBJECT IDENTIFIER ::= { acme 1 }
            acmeWidget OBJECT IDENTIFIER ::= { acmeProducts 1 }
            END
        """.trimIndent()
        val result = MibParser.parse(text)
        assertEquals(Oid("1.3.6.1.4.1.9999"), result.resolved["acme"])
        assertEquals(Oid("1.3.6.1.4.1.9999.1"), result.resolved["acmeProducts"])
        assertEquals(Oid("1.3.6.1.4.1.9999.1.1"), result.resolved["acmeWidget"])
        assertTrue(result.unresolvedNames.isEmpty())
    }

    @Test
    fun `resolves OBJECT-TYPE clauses with SYNTAX bodies in between`() {
        val text = """
            widgetStatus OBJECT-TYPE
                SYNTAX INTEGER { up(1), down(2) }
                MAX-ACCESS read-only
                STATUS current
                DESCRIPTION "Whether -- this looks like a comment but isn't -- the widget is up"
                ::= { acmeWidget 1 }

            acmeWidget OBJECT IDENTIFIER ::= { enterprises 9999 }
        """.trimIndent()
        val result = MibParser.parse(text)
        // order-independent: widgetStatus references acmeWidget, which is defined *after* it in the file
        assertEquals(Oid("1.3.6.1.4.1.9999.1"), result.resolved["widgetStatus"])
    }

    @Test
    fun `strips end-of-line comments before matching`() {
        val text = """
            -- this whole line is a comment
            foo OBJECT IDENTIFIER ::= { enterprises 1 } -- trailing comment
        """.trimIndent()
        val result = MibParser.parse(text)
        assertEquals(Oid("1.3.6.1.4.1.1"), result.resolved["foo"])
    }

    @Test
    fun `unresolvable parent is reported, not silently dropped`() {
        val text = "orphan OBJECT IDENTIFIER ::= { someUndefinedParent 1 }"
        val result = MibParser.parse(text)
        assertTrue(result.unresolvedNames.contains("orphan"))
    }

    @Test
    fun `loadInto registers names for MibDictionary describe()`() {
        val text = "acmeSensor OBJECT IDENTIFIER ::= { enterprises 12345 }"
        MibParser.loadInto(text)
        assertEquals("acmeSensor", MibDictionary.describe(Oid("1.3.6.1.4.1.12345")))
        assertEquals("acmeSensor.7", MibDictionary.describe(Oid("1.3.6.1.4.1.12345.7")))
    }
}
