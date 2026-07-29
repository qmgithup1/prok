package com.systemsgo.hex.modbus

import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.modbus.protocol.ModbusDataFormat
import com.systemsgo.hex.modbus.protocol.ModbusPoint
import com.systemsgo.hex.modbus.protocol.ModbusRegisterType
import com.systemsgo.hex.modbus.protocol.ModbusTcpClient

/**
 * MODBUS TCP FEATURE (Part 2/2).
 *
 * This is the seam Part 1's [ModbusConnectionConfig] doc comment described:
 * host/port/unitId/timeouts/retries now live as real `modbus*` columns on
 * [RdpProfile] (see the "MODBUS-TCP FEATURE (Part 2/2)" block there), and
 * [toModbusConnectionConfig]/[toModbusClient] reconstruct a connection from
 * a saved profile — the same shape as
 * [com.systemsgo.hex.snmp.SnmpProfileMapper.toSnmpCredentials]. Every other
 * file in the `modbus` package (the protocol engine itself) is unchanged
 * from Part 1.
 */
data class ModbusConnectionConfig(
    val host: String,
    val port: Int = ModbusTcpClient.MODBUS_TCP_DEFAULT_PORT,
    /** Modbus unit/slave identifier (spec §4.1). Most Modbus TCP-native devices ignore this; TCP/serial gateways route on it. */
    val unitId: Int = 1,
    val connectTimeoutMillis: Int = 5000,
    val responseTimeoutMillis: Int = 3000,
    val retries: Int = 1,
)

fun ModbusConnectionConfig.toModbusClient(): ModbusTcpClient = ModbusTcpClient(
    host = host,
    port = port,
    unitId = unitId,
    connectTimeoutMillis = connectTimeoutMillis,
    responseTimeoutMillis = responseTimeoutMillis,
    retries = retries,
)

/** Builds a [ModbusConnectionConfig] from an [RdpProfile]'s `host`/`port`/`modbus*` columns. */
fun RdpProfile.toModbusConnectionConfig(): ModbusConnectionConfig = ModbusConnectionConfig(
    host = host,
    port = if (port > 0) port else ProtocolType.MODBUS_TCP.defaultPort,
    unitId = modbusUnitId,
    connectTimeoutMillis = modbusConnectTimeoutMs,
    responseTimeoutMillis = modbusResponseTimeoutMs,
    retries = modbusRetries,
)

/** Shortcut for `toModbusConnectionConfig().toModbusClient()`. */
fun RdpProfile.toModbusClient(): ModbusTcpClient = toModbusConnectionConfig().toModbusClient()

/**
 * Parses [RdpProfile.modbusPoints]' delimited storage format (`;`-separated
 * points, each `registerType:address:label:dataFormat`) into a list of
 * [ModbusPoint]s, silently skipping any malformed entries — the same
 * defensive tradeoff [com.systemsgo.hex.snmp.snmpFavoriteOidList] makes for
 * its own free-text-adjacent column. [ModbusPoint.dataFormat] is only
 * meaningful for register points; it's stored as UINT16 for coil/discrete
 * points and ignored when read back for them.
 */
fun RdpProfile.modbusPointList(): List<ModbusPoint> =
    modbusPoints.split(";").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { entry ->
        val parts = entry.split(":")
        if (parts.size < 3) return@mapNotNull null
        runCatching {
            ModbusPoint(
                registerType = ModbusRegisterType.valueOf(parts[0]),
                address = parts[1].toInt(),
                label = parts[2],
                dataFormat = parts.getOrNull(3)?.let { ModbusDataFormat.valueOf(it) } ?: ModbusDataFormat.UINT16,
            )
        }.getOrNull()
    }

fun List<ModbusPoint>.toModbusPointsColumn(): String =
    joinToString(";") { "${it.registerType.name}:${it.address}:${it.label}:${it.dataFormat.name}" }

/** Coils and holding registers are writable (spec §6.5/§6.6/§6.11/§6.12); discrete inputs and input registers are read-only by definition (spec §4.3). */
val ModbusRegisterType.isReadOnly: Boolean
    get() = this == ModbusRegisterType.DISCRETE_INPUT || this == ModbusRegisterType.INPUT_REGISTER
