package com.systemsgo.hex.snmp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests for [Asn1]'s BER primitives — no network, no Android
 * framework dependency, matching PacFileParserTest's style. The full-packet
 * test below is checked byte-for-byte against a known-good SNMPv1
 * GetRequest capture (community "public", sysDescr.0), which is the
 * standard way to sanity-check a from-scratch BER/SNMP encoder.
 */
class Asn1Test {

    @Test
    fun `signed integer round trip including sign edge cases`() {
        for (v in listOf(0L, 1L, -1L, 127L, 128L, -128L, -129L, 255L, 256L, Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong())) {
            assertEquals(v, berDecodeSignedInt(berEncodeSignedInt(v)))
        }
    }

    @Test
    fun `minimal two's-complement encoding lengths`() {
        assertEquals(1, berEncodeSignedInt(-1).size)
        assertEquals(1, berEncodeSignedInt(127).size)
        assertEquals(2, berEncodeSignedInt(128).size) // needs a leading 0x00 so it doesn't read as negative
        assertEquals(1, berEncodeSignedInt(-128).size)
        assertEquals(2, berEncodeSignedInt(-129).size)
    }

    @Test
    fun `unsigned 32-bit encoding pads when top bit set`() {
        val enc = berEncodeUnsigned(0xFFFFFFFFL, 4)
        assertEquals(5, enc.size) // leading 0x00 pad required
        assertEquals(0xFFFFFFFFL, berDecodeUnsigned(enc))
    }

    @Test
    fun `counter64 round trip for values above Long MAX_VALUE`() {
        val bits = -1L // all 64 bits set == 2^64 - 1 unsigned
        val enc = berEncodeUnsigned(bits, 8)
        assertEquals("18446744073709551615", berDecodeUnsigned(enc).toUnsignedDecimalString())
    }

    @Test
    fun `oid round trip`() {
        val oid = Oid("1.3.6.1.2.1.1.1.0")
        assertEquals(oid, berDecodeOid(berEncodeOid(oid)))
        assertEquals("2b 06 01 02 01 01 01 00", berEncodeOid(oid).joinToString(" ") { "%02x".format(it) })
    }

    @Test
    fun `oid with a large arc encodes multi-byte base-128`() {
        val oid = Oid("1.3.6.1.4.1.9999")
        assertEquals(oid, berDecodeOid(berEncodeOid(oid)))
    }

    @Test
    fun `startsWith and suffixAfter`() {
        val ifDescr = Oid("1.3.6.1.2.1.2.2.1.2")
        val row = Oid("1.3.6.1.2.1.2.2.1.2.3")
        assertTrue(row.startsWith(ifDescr))
        assertEquals(listOf(3), row.suffixAfter(ifDescr).toList())
    }

    @Test
    fun `known-good SNMPv1 GetRequest packet for sysDescr0`() {
        val requestId = 0x3f979d4b
        val writer = BerWriter()
        writer.writeConstructed(Ber.SEQUENCE) {
            writeInteger(0) // v1
            writeOctetString("public")
            writeConstructed(Ber.GET_REQUEST) {
                writeInteger(requestId.toLong())
                writeInteger(0)
                writeInteger(0)
                writeConstructed(Ber.SEQUENCE) {
                    writeConstructed(Ber.SEQUENCE) {
                        writeOid(Oid("1.3.6.1.2.1.1.1.0"))
                        writeNull()
                    }
                }
            }
        }
        val expected = "30 29 02 01 00 04 06 70 75 62 6c 69 63 a0 1c 02 04 3f 97 9d 4b " +
            "02 01 00 02 01 00 30 0e 30 0c 06 08 2b 06 01 02 01 01 01 00 05 00"
        val actual = writer.toByteArray().joinToString(" ") { "%02x".format(it) }
        assertEquals(expected, actual)
    }

    @Test
    fun `decodes a Response-PDU carrying a mix of value types`() {
        // Response-PDU: request-id=7, noError, varbind sysDescr.0 = "test", ifInOctets.1 = Counter32(42)
        val writer = BerWriter()
        writer.writeConstructed(Ber.SEQUENCE) {
            writeInteger(1) // v2c
            writeOctetString("public")
            writeConstructed(Ber.GET_RESPONSE) {
                writeInteger(7)
                writeInteger(0)
                writeInteger(0)
                writeConstructed(Ber.SEQUENCE) {
                    writeConstructed(Ber.SEQUENCE) { writeOid(Oid("1.3.6.1.2.1.1.1.0")); writeOctetString("test") }
                    writeConstructed(Ber.SEQUENCE) { writeOid(Oid("1.3.6.1.2.1.2.2.1.10.1")); writeTlv(Ber.COUNTER32, berEncodeUnsigned(42, 4)) }
                }
            }
        }
        val root = BerReader(writer.toByteArray()).readTlv()
        val r = root.reader()
        r.readTlv(); r.readTlv() // version, community
        val pdu = r.readTlv()
        assertEquals(Ber.GET_RESPONSE, pdu.tag)
        val pr = pdu.reader()
        assertEquals(7L, berDecodeSignedInt(pr.readTlv().content))
        pr.readTlv(); pr.readTlv() // error-status, error-index
        val listReader = pr.readTlv().reader()
        val vb1 = listReader.readTlv().reader()
        assertEquals(Oid("1.3.6.1.2.1.1.1.0"), berDecodeOid(vb1.readTlv().content))
        assertEquals("test", (SnmpValue.fromBer(vb1.readTlv()) as SnmpValue.OctetStringVal).asText())
        val vb2 = listReader.readTlv().reader()
        vb2.readTlv()
        assertEquals(42L, (SnmpValue.fromBer(vb2.readTlv()) as SnmpValue.Counter32Val).value)
    }
}
