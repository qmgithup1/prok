package com.systemsgo.hex.modbus.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket

/**
 * Plain JVM tests for [ModbusTcpClient] — no network beyond loopback, no
 * Android framework dependency, matching Asn1Test's style. A minimal fake
 * Modbus TCP slave runs on a loopback [ServerSocket] in a background thread
 * for each test so the MBAP framing and PDU encode/decode can be checked
 * against known-good bytes rather than mocked.
 */
class ModbusTcpClientTest {

    /** Reads one MBAP+PDU request off [input] and returns (transactionId, unitId, functionCode, data-after-function-code). */
    private fun readRequest(input: DataInputStream): Quad {
        val header = ByteArray(6)
        input.readFully(header)
        val txId = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val length = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
        val rest = ByteArray(length)
        input.readFully(rest)
        val unitId = rest[0].toInt() and 0xFF
        val functionCode = rest[1].toInt() and 0xFF
        val data = rest.copyOfRange(2, rest.size)
        return Quad(txId, unitId, functionCode, data)
    }

    private data class Quad(val txId: Int, val unitId: Int, val functionCode: Int, val data: ByteArray)

    private fun writeResponse(output: DataOutputStream, txId: Int, unitId: Int, pdu: ByteArray) {
        val length = 1 + pdu.size
        output.write(byteArrayOf((txId shr 8).toByte(), txId.toByte(), 0, 0, (length shr 8).toByte(), length.toByte(), unitId.toByte()))
        output.write(pdu)
        output.flush()
    }

    /** Starts a loopback server that handles exactly one request with [respond], and returns the bound port. */
    private fun startFakeSlave(respond: (DataInputStream, DataOutputStream, Quad) -> Unit): Pair<ServerSocket, Int> {
        val server = ServerSocket(0)
        val thread = Thread {
            server.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                val req = readRequest(input)
                respond(input, output, req)
            }
        }
        thread.isDaemon = true
        thread.start()
        return server to server.localPort
    }

    @Test
    fun `read holding registers decodes a well-formed response`() = runBlocking {
        val (server, port) = startFakeSlave { _, output, req ->
            assertEquals(ModbusFunctionCode.READ_HOLDING_REGISTERS, req.functionCode)
            // 2 registers: 0x0064 (100) and 0x00C8 (200)
            writeResponse(output, req.txId, req.unitId, byteArrayOf(0x03, 0x04, 0x00, 0x64, 0x00, 0xC8.toByte()))
        }
        server.use {
            ModbusTcpClient("127.0.0.1", port).use { client ->
                val result = client.readHoldingRegisters(startAddress = 0, quantity = 2)
                assertEquals(listOf(100, 200), result.values)
            }
        }
    }

    @Test
    fun `read coils unpacks bits LSB-first per byte`() = runBlocking {
        val (server, port) = startFakeSlave { _, output, req ->
            assertEquals(ModbusFunctionCode.READ_COILS, req.functionCode)
            // 10 coils packed LSB-first into 2 bytes: 0xCD = 1100 1101 -> coils 0,2,3,6,7 set; 0x01 -> coil 8 set
            writeResponse(output, req.txId, req.unitId, byteArrayOf(0x01, 0x02, 0xCD.toByte(), 0x01))
        }
        server.use {
            ModbusTcpClient("127.0.0.1", port).use { client ->
                val result = client.readCoils(startAddress = 0, quantity = 10)
                val expected = listOf(true, false, true, true, false, false, true, true, true, false)
                assertEquals(expected, result.values)
            }
        }
    }

    @Test
    fun `write single register echoes address and value`() = runBlocking {
        val (server, port) = startFakeSlave { _, output, req ->
            assertEquals(ModbusFunctionCode.WRITE_SINGLE_REGISTER, req.functionCode)
            assertEquals(0x0001, ((req.data[0].toInt() and 0xFF) shl 8) or (req.data[1].toInt() and 0xFF))
            // Echo request payload verbatim, per spec.
            writeResponse(output, req.txId, req.unitId, byteArrayOf(0x06) + req.data)
        }
        server.use {
            ModbusTcpClient("127.0.0.1", port).use { client ->
                client.writeSingleRegister(address = 1, value = 999)
            }
        }
    }

    @Test
    fun `exception response surfaces the exception code`() = runBlocking {
        val (server, port) = startFakeSlave { _, output, req ->
            writeResponse(output, req.txId, req.unitId, byteArrayOf((req.functionCode or 0x80).toByte(), 0x02))
        }
        server.use {
            ModbusTcpClient("127.0.0.1", port).use { client ->
                try {
                    client.readHoldingRegisters(startAddress = 70000 - 65537, quantity = 1) // any valid address; slave will reject regardless
                    fail("expected ModbusException.Protocol")
                } catch (e: ModbusException.Protocol) {
                    assertEquals(ModbusExceptionCode.ILLEGAL_DATA_ADDRESS, e.exceptionCode)
                }
            }
        }
    }

    @Test
    fun `rejects out-of-range address before touching the wire`() = runBlocking {
        val client = ModbusTcpClient("127.0.0.1", 1) // port irrelevant — should fail before connecting
        try {
            client.readHoldingRegisters(startAddress = 65535, quantity = 10) // overflows past 0xFFFF
            fail("expected ModbusException.InvalidArgument")
        } catch (e: ModbusException.InvalidArgument) {
            assertTrue(e.message!!.contains("overflows"))
        }
    }

    @Test
    fun `data format decode handles signed and float32`() {
        assertEquals((-1).toShort(), ModbusDataFormat.INT16.decode(listOf(0xFFFF)))
        assertEquals(1.5f, ModbusDataFormat.FLOAT32.decode(listOf(0x3FC0, 0x0000)))
        assertFalse(ModbusDataFormat.UINT16.wordCount == 2)
    }
}
