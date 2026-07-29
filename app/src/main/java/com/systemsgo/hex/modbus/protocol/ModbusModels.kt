package com.systemsgo.hex.modbus.protocol

/**
 * MODBUS TCP FEATURE (Part 1/2 — protocol engine).
 *
 * This file, [ModbusTcpClient] and [com.systemsgo.hex.modbus.ModbusProfileMapper]
 * are the wire-protocol counterpart to snmp/protocol's SnmpModels+SnmpClient: a
 * native Modbus TCP (Modbus Application Protocol, "MBAP") master implementation,
 * no third-party Modbus library used. Part 2 wires this into the app proper —
 * [com.systemsgo.hex.data.model.ProtocolType], [com.systemsgo.hex.data.model.RdpProfile]
 * columns, the Add-Connection config screen, a session/monitor screen for
 * reading and writing registers, and [com.systemsgo.hex.data.model.ProtocolCatalog]'s
 * existing "modbus_tcp" stub entry (currently `protocolType = null`, i.e.
 * NOT_YET_SUPPORTED) — none of that exists yet; see the Part 2 handoff prompt.
 *
 * Reference: Modbus Application Protocol V1.1b3 (function/exception codes,
 * PDU layout) and the Modbus Messaging on TCP/IP Implementation Guide V1.0b
 * (the 7-byte MBAP header framing).
 */

/** The four Modbus data tables (spec §4.3), each independently addressed 0-based on the wire. */
enum class ModbusRegisterType(val label: String, val bitSized: Boolean) {
    COIL("Coil", bitSized = true),
    DISCRETE_INPUT("Discrete Input", bitSized = true),
    HOLDING_REGISTER("Holding Register", bitSized = false),
    INPUT_REGISTER("Input Register", bitSized = false),
}

/** Function codes this client speaks (spec §6) — the standard master-side read/write set. */
internal object ModbusFunctionCode {
    const val READ_COILS = 0x01
    const val READ_DISCRETE_INPUTS = 0x02
    const val READ_HOLDING_REGISTERS = 0x03
    const val READ_INPUT_REGISTERS = 0x04
    const val WRITE_SINGLE_COIL = 0x05
    const val WRITE_SINGLE_REGISTER = 0x06
    const val WRITE_MULTIPLE_COILS = 0x0F
    const val WRITE_MULTIPLE_REGISTERS = 0x10

    /** Any response whose function code has this bit set is an exception response (spec §7). */
    const val EXCEPTION_BIT = 0x80
}

/** Exception codes a slave/server can return in the exception-response PDU (spec §7, Appendix A). */
enum class ModbusExceptionCode(val code: Int, val label: String) {
    ILLEGAL_FUNCTION(0x01, "Illegal function"),
    ILLEGAL_DATA_ADDRESS(0x02, "Illegal data address"),
    ILLEGAL_DATA_VALUE(0x03, "Illegal data value"),
    SLAVE_DEVICE_FAILURE(0x04, "Slave device failure"),
    ACKNOWLEDGE(0x05, "Acknowledge"),
    SLAVE_DEVICE_BUSY(0x06, "Slave device busy"),
    MEMORY_PARITY_ERROR(0x08, "Memory parity error"),
    GATEWAY_PATH_UNAVAILABLE(0x0A, "Gateway path unavailable"),
    GATEWAY_TARGET_DEVICE_FAILED_TO_RESPOND(0x0B, "Gateway target device failed to respond"),
    ;

    companion object {
        fun fromCode(code: Int): ModbusExceptionCode? = entries.find { it.code == code }
    }
}

/** Base type for everything [ModbusTcpClient] can throw — lets callers `catch (e: ModbusException)` broadly or match specific causes. */
sealed class ModbusException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The slave/server understood the request and explicitly rejected it (spec §7). */
    class Protocol(val exceptionCode: ModbusExceptionCode?, val rawCode: Int, val functionCode: Int) :
        ModbusException(
            "Modbus exception on function 0x${functionCode.toString(16)}: " +
                (exceptionCode?.label ?: "unknown code 0x${rawCode.toString(16)}"),
        )

    /** No response within [ModbusTcpClient]'s configured timeout. */
    class Timeout(functionCode: Int) : ModbusException("Timed out waiting for response to function 0x${functionCode.toString(16)}")

    /** TCP connect/read/write failure — see [cause] for the underlying [java.io.IOException]. */
    class Connection(message: String, cause: Throwable? = null) : ModbusException(message, cause)

    /** Response bytes didn't parse as a well-formed MBAP+PDU frame, or didn't match the request (wrong transaction id, wrong unit id, wrong function code, malformed byte count, …). */
    class Framing(message: String) : ModbusException(message)

    /** An argument the client itself rejects before ever touching the wire (address/quantity/value out of the protocol's legal range). */
    class InvalidArgument(message: String) : ModbusException(message)
}

/** Result of a coil/discrete-input read: one bit per requested address, in ascending address order. */
data class ModbusBits(val startAddress: Int, val values: List<Boolean>)

/** Result of a holding/input-register read: one 16-bit unsigned word (0..65535) per requested address, in ascending address order. */
data class ModbusRegisters(val startAddress: Int, val values: List<Int>)

/**
 * A single addressable point a UI would show in a register/tag list — the
 * unit this app's "favorite OIDs"-equivalent (a saved point list per
 * connection, Part 2) would be built from.
 */
data class ModbusPoint(
    val registerType: ModbusRegisterType,
    val address: Int,
    val label: String = "",
    /** For registers only: how to interpret consecutive 16-bit words: signed/unsigned 16-bit, or a 32-bit value spanning 2 registers. */
    val dataFormat: ModbusDataFormat = ModbusDataFormat.UINT16,
)

/** How to interpret one or more raw 16-bit register words as a numeric value. Word order for the 32-bit formats is big-endian-first (CDAB is the common "byte-swapped" alternative some PLCs use — not handled here; Part 2 can add it as a per-point toggle if a device needs it.) */
enum class ModbusDataFormat(val wordCount: Int) {
    UINT16(1), INT16(1),
    UINT32(2), INT32(2),
    FLOAT32(2),
}

/** Decodes 1 or 2 raw register words (as returned by [ModbusRegisters.values]) per [ModbusDataFormat]. */
fun ModbusDataFormat.decode(words: List<Int>): Number {
    require(words.size == wordCount) { "$this needs $wordCount register word(s), got ${words.size}" }
    return when (this) {
        ModbusDataFormat.UINT16 -> words[0] and 0xFFFF
        ModbusDataFormat.INT16 -> words[0].toShort()
        ModbusDataFormat.UINT32 -> ((words[0].toLong() and 0xFFFF) shl 16) or (words[1].toLong() and 0xFFFF)
        ModbusDataFormat.INT32 -> (((words[0] and 0xFFFF) shl 16) or (words[1] and 0xFFFF))
        ModbusDataFormat.FLOAT32 -> {
            val bits = ((words[0] and 0xFFFF) shl 16) or (words[1] and 0xFFFF)
            Float.fromBits(bits)
        }
    }
}
