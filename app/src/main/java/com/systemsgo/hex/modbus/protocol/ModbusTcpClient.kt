package com.systemsgo.hex.modbus.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Modbus TCP master (client) — the Modbus counterpart to [com.systemsgo.hex.snmp.protocol.SnmpClient].
 * A native MBAP-framing + PDU implementation ([ModbusFunctionCode]/[ModbusExceptionCode]
 * in ModbusModels.kt); no third-party Modbus library used.
 *
 * Usage:
 * ```
 * val client = ModbusTcpClient(host = "192.168.1.50")
 * val regs = client.readHoldingRegisters(unitId = 1, startAddress = 0, quantity = 10)
 * client.writeSingleRegister(unitId = 1, address = 0, value = 42)
 * client.close()
 * ```
 *
 * All I/O methods are suspend functions dispatched on [Dispatchers.IO] — call
 * from a ViewModel/coroutine scope, never directly from the UI thread.
 *
 * One TCP connection is opened lazily on first request and reused; Modbus TCP
 * is a strict request/response protocol over a byte stream (no built-in
 * multiplexing benefit the way SNMP's per-datagram request-id gives you over
 * UDP), so requests are serialized with [requestLock] — the transaction
 * identifier is still populated and checked on every reply (spec recommends
 * it for detecting a stale/mismatched response, e.g. after a timeout-and-retry),
 * it just isn't used to pipeline concurrent requests.
 *
 * @param unitId default Modbus unit/slave identifier (spec §4.1) used when a
 *   call site doesn't pass its own — most Modbus TCP devices ignore this
 *   entirely and expect 0x01 or 0xFF (per-request override still supported
 *   for TCP/serial gateways that do route on it).
 */
class ModbusTcpClient(
    private val host: String,
    private val port: Int = MODBUS_TCP_DEFAULT_PORT,
    private val unitId: Int = 1,
    private val connectTimeoutMillis: Int = 5000,
    private val responseTimeoutMillis: Int = 3000,
    private val retries: Int = 1,
) : AutoCloseable {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val transactionIdSeq = AtomicInteger((0..0x7FFF).random())
    private val requestLock = Mutex()

    private fun ensureConnected(): Pair<DataInputStream, DataOutputStream> {
        var s = socket
        if (s == null || s.isClosed || !s.isConnected) {
            s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            } catch (e: Exception) {
                runCatching { s.close() }
                throw ModbusException.Connection("Could not connect to $host:$port", e)
            }
            s.soTimeout = responseTimeoutMillis
            s.tcpNoDelay = true
            socket = s
            input = DataInputStream(s.getInputStream())
            output = DataOutputStream(s.getOutputStream())
        }
        return input!! to output!!
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    // ── Public reads ─────────────────────────────────────────────────────

    suspend fun readCoils(startAddress: Int, quantity: Int, unitId: Int = this.unitId): ModbusBits =
        readBits(ModbusFunctionCode.READ_COILS, startAddress, quantity, maxQuantity = 2000, unitId)

    suspend fun readDiscreteInputs(startAddress: Int, quantity: Int, unitId: Int = this.unitId): ModbusBits =
        readBits(ModbusFunctionCode.READ_DISCRETE_INPUTS, startAddress, quantity, maxQuantity = 2000, unitId)

    suspend fun readHoldingRegisters(startAddress: Int, quantity: Int, unitId: Int = this.unitId): ModbusRegisters =
        readWords(ModbusFunctionCode.READ_HOLDING_REGISTERS, startAddress, quantity, maxQuantity = 125, unitId)

    suspend fun readInputRegisters(startAddress: Int, quantity: Int, unitId: Int = this.unitId): ModbusRegisters =
        readWords(ModbusFunctionCode.READ_INPUT_REGISTERS, startAddress, quantity, maxQuantity = 125, unitId)

    // ── Public writes ────────────────────────────────────────────────────

    suspend fun writeSingleCoil(address: Int, value: Boolean, unitId: Int = this.unitId) {
        requireAddress(address)
        val payload = beU16(address) + beU16(if (value) 0xFF00 else 0x0000)
        val respPdu = execute(ModbusFunctionCode.WRITE_SINGLE_COIL, payload, unitId)
        // Echo response: address (2) + value (2) — verify it matches what we sent, per spec §6.5.
        if (respPdu.size < 5 || u16(respPdu, 1) != address) {
            throw ModbusException.Framing("Write single coil: response did not echo the request address")
        }
    }

    suspend fun writeSingleRegister(address: Int, value: Int, unitId: Int = this.unitId) {
        requireAddress(address)
        requireRegisterValue(value)
        val payload = beU16(address) + beU16(value)
        val respPdu = execute(ModbusFunctionCode.WRITE_SINGLE_REGISTER, payload, unitId)
        if (respPdu.size < 5 || u16(respPdu, 1) != address) {
            throw ModbusException.Framing("Write single register: response did not echo the request address")
        }
    }

    suspend fun writeMultipleCoils(startAddress: Int, values: List<Boolean>, unitId: Int = this.unitId) {
        require(values.isNotEmpty() && values.size <= 1968) { "writeMultipleCoils: quantity must be 1..1968, got ${values.size}" }
        requireAddressRange(startAddress, values.size)
        val byteCount = (values.size + 7) / 8
        val packed = ByteArray(byteCount)
        values.forEachIndexed { i, bit -> if (bit) packed[i / 8] = (packed[i / 8].toInt() or (1 shl (i % 8))).toByte() }
        val payload = beU16(startAddress) + beU16(values.size) + byteArrayOf(byteCount.toByte()) + packed
        val respPdu = execute(ModbusFunctionCode.WRITE_MULTIPLE_COILS, payload, unitId)
        if (respPdu.size < 5 || u16(respPdu, 1) != startAddress || u16(respPdu, 3) != values.size) {
            throw ModbusException.Framing("Write multiple coils: response did not echo the request address/quantity")
        }
    }

    suspend fun writeMultipleRegisters(startAddress: Int, values: List<Int>, unitId: Int = this.unitId) {
        require(values.isNotEmpty() && values.size <= 123) { "writeMultipleRegisters: quantity must be 1..123, got ${values.size}" }
        requireAddressRange(startAddress, values.size)
        values.forEach(::requireRegisterValue)
        val byteCount = values.size * 2
        val payload = beU16(startAddress) + beU16(values.size) + byteArrayOf(byteCount.toByte()) +
            values.flatMap { beU16(it).toList() }.toByteArray()
        val respPdu = execute(ModbusFunctionCode.WRITE_MULTIPLE_REGISTERS, payload, unitId)
        if (respPdu.size < 5 || u16(respPdu, 1) != startAddress || u16(respPdu, 3) != values.size) {
            throw ModbusException.Framing("Write multiple registers: response did not echo the request address/quantity")
        }
    }

    // ── Shared read helpers ──────────────────────────────────────────────

    private suspend fun readBits(functionCode: Int, startAddress: Int, quantity: Int, maxQuantity: Int, unitId: Int): ModbusBits {
        require(quantity in 1..maxQuantity) { "quantity must be 1..$maxQuantity, got $quantity" }
        requireAddressRange(startAddress, quantity)
        val payload = beU16(startAddress) + beU16(quantity)
        val respPdu = execute(functionCode, payload, unitId)
        val byteCount = respPdu.getOrNull(1)?.let { it.toInt() and 0xFF } ?: throw ModbusException.Framing("Truncated read-bits response")
        if (respPdu.size < 2 + byteCount) throw ModbusException.Framing("Read-bits response shorter than its own byte count")
        val bits = ArrayList<Boolean>(quantity)
        for (i in 0 until quantity) {
            val byte = respPdu[2 + i / 8].toInt() and 0xFF
            bits.add((byte shr (i % 8)) and 0x01 == 1)
        }
        return ModbusBits(startAddress, bits)
    }

    private suspend fun readWords(functionCode: Int, startAddress: Int, quantity: Int, maxQuantity: Int, unitId: Int): ModbusRegisters {
        require(quantity in 1..maxQuantity) { "quantity must be 1..$maxQuantity, got $quantity" }
        requireAddressRange(startAddress, quantity)
        val payload = beU16(startAddress) + beU16(quantity)
        val respPdu = execute(functionCode, payload, unitId)
        val byteCount = respPdu.getOrNull(1)?.let { it.toInt() and 0xFF } ?: throw ModbusException.Framing("Truncated read-registers response")
        if (byteCount != quantity * 2 || respPdu.size < 2 + byteCount) {
            throw ModbusException.Framing("Read-registers response byte count didn't match requested quantity")
        }
        val values = (0 until quantity).map { i -> u16(respPdu, 2 + i * 2) }
        return ModbusRegisters(startAddress, values)
    }

    // ── Transport: one MBAP request/response round trip, with retry ─────

    /** Sends [functionCode] + [payload] as a PDU, wrapped in an MBAP frame, and returns the *response PDU bytes* (function code byte included at index 0). Throws [ModbusException] on any exception response, timeout, or malformed frame. */
    private suspend fun execute(functionCode: Int, payload: ByteArray, unitId: Int): ByteArray = requestLock.withLock {
        withContext(Dispatchers.IO) {
            var lastError: ModbusException? = null
            repeat(1 + retries) { attempt ->
                try {
                    return@withContext executeOnce(functionCode, payload, unitId)
                } catch (e: ModbusException) {
                    lastError = e
                    // A protocol-level exception response is authoritative — retrying won't change it. Only retry transport failures.
                    if (e is ModbusException.Protocol) throw e
                    if (attempt == retries) throw e
                    close() // drop the (possibly desynced) connection before reconnecting for the retry
                }
            }
            throw lastError ?: ModbusException.Timeout(functionCode)
        }
    }

    private fun executeOnce(functionCode: Int, payload: ByteArray, unitId: Int): ByteArray {
        val (input, output) = try {
            ensureConnected()
        } catch (e: ModbusException) {
            throw e
        }
        val transactionId = transactionIdSeq.updateAndGet { (it + 1) and 0xFFFF }
        val pdu = byteArrayOf(functionCode.toByte()) + payload
        val length = 1 + pdu.size // unit id + PDU
        val frame = beU16(transactionId) + beU16(0) /* protocol id */ + beU16(length) + byteArrayOf(unitId.toByte()) + pdu
        try {
            output.write(frame)
            output.flush()
        } catch (e: Exception) {
            throw ModbusException.Connection("Write failed to $host:$port", e)
        }

        val header = ByteArray(6)
        try {
            input.readFully(header)
        } catch (e: SocketTimeoutException) {
            throw ModbusException.Timeout(functionCode)
        } catch (e: EOFException) {
            throw ModbusException.Connection("Connection closed by $host:$port while waiting for a response", e)
        } catch (e: Exception) {
            throw ModbusException.Connection("Read failed from $host:$port", e)
        }
        val respTransactionId = u16(header, 0)
        val respProtocolId = u16(header, 2)
        val respLength = u16(header, 4)
        if (respProtocolId != 0) throw ModbusException.Framing("Unexpected protocol id $respProtocolId (expected 0 — this doesn't look like Modbus TCP)")
        if (respLength < 2 || respLength > 253) throw ModbusException.Framing("Implausible MBAP length field: $respLength")

        val rest = ByteArray(respLength)
        try {
            input.readFully(rest)
        } catch (e: SocketTimeoutException) {
            throw ModbusException.Timeout(functionCode)
        } catch (e: Exception) {
            throw ModbusException.Connection("Read failed from $host:$port", e)
        }
        val respUnitId = rest[0].toInt() and 0xFF
        val respPdu = rest.copyOfRange(1, rest.size)

        if (respTransactionId != transactionId) {
            throw ModbusException.Framing("Transaction id mismatch: sent $transactionId, got $respTransactionId")
        }
        if (respUnitId != unitId) {
            throw ModbusException.Framing("Unit id mismatch: sent $unitId, got $respUnitId")
        }
        val respFunctionCode = respPdu.getOrNull(0)?.let { it.toInt() and 0xFF }
            ?: throw ModbusException.Framing("Empty response PDU")

        if (respFunctionCode == (functionCode or ModbusFunctionCode.EXCEPTION_BIT)) {
            val rawCode = respPdu.getOrNull(1)?.let { it.toInt() and 0xFF } ?: -1
            throw ModbusException.Protocol(ModbusExceptionCode.fromCode(rawCode), rawCode, functionCode)
        }
        if (respFunctionCode != functionCode) {
            throw ModbusException.Framing("Unexpected function code 0x${respFunctionCode.toString(16)} in response to 0x${functionCode.toString(16)}")
        }
        return respPdu
    }

    // ── Byte helpers ─────────────────────────────────────────────────────

    private fun requireAddress(address: Int) {
        if (address !in 0..0xFFFF) throw ModbusException.InvalidArgument("address must be 0..65535, got $address")
    }

    private fun requireAddressRange(startAddress: Int, quantity: Int) {
        requireAddress(startAddress)
        if (startAddress + quantity - 1 > 0xFFFF) {
            throw ModbusException.InvalidArgument("address range $startAddress..${startAddress + quantity - 1} overflows the 16-bit address space")
        }
    }

    private fun requireRegisterValue(value: Int) {
        if (value !in 0..0xFFFF) throw ModbusException.InvalidArgument("register value must be 0..65535, got $value")
    }

    private fun beU16(value: Int): ByteArray = byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
    private fun u16(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    companion object {
        const val MODBUS_TCP_DEFAULT_PORT = 502
    }
}
