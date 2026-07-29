package com.systemsgo.hex.snmp

import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.snmp.protocol.Oid
import com.systemsgo.hex.snmp.protocol.SnmpAuthProtocol
import com.systemsgo.hex.snmp.protocol.SnmpClient
import com.systemsgo.hex.snmp.protocol.SnmpCredentials
import com.systemsgo.hex.snmp.protocol.SnmpPrivProtocol
import com.systemsgo.hex.snmp.protocol.SnmpSecurityLevel
import com.systemsgo.hex.snmp.protocol.SnmpVersion

/**
 * Reconstructs typed [SnmpCredentials] from an [RdpProfile]'s `snmp*`
 * columns (which, per Room's own constraints, can only store primitives —
 * see [RdpProfile.snmpVersion] et al.'s doc comments for the full column
 * list). Used identically whether the profile's [RdpProfile.protocolType]
 * is [com.systemsgo.hex.data.model.ProtocolType.SNMP] or the profile is
 * some other protocol with [RdpProfile.snmpMonitoringEnabled] layered on.
 */
fun RdpProfile.toSnmpCredentials(): SnmpCredentials {
    val version = runCatching { SnmpVersion.valueOf(snmpVersion) }.getOrDefault(SnmpVersion.V2C)
    return if (version == SnmpVersion.V3) {
        SnmpCredentials.Usm(
            username = snmpV3Username,
            securityLevel = runCatching { SnmpSecurityLevel.valueOf(snmpV3SecurityLevel) }.getOrDefault(SnmpSecurityLevel.AUTH_PRIV),
            authProtocol = runCatching { SnmpAuthProtocol.valueOf(snmpV3AuthProtocol) }.getOrDefault(SnmpAuthProtocol.SHA1),
            authPassphrase = snmpV3AuthPassphrase,
            privProtocol = runCatching { SnmpPrivProtocol.valueOf(snmpV3PrivProtocol) }.getOrDefault(SnmpPrivProtocol.AES128),
            privPassphrase = snmpV3PrivPassphrase,
            contextName = snmpV3ContextName,
        )
    } else {
        SnmpCredentials.Community(version, snmpCommunity)
    }
}

fun RdpProfile.toSnmpClient(timeoutMillis: Int = 3000, retries: Int = 1): SnmpClient =
    SnmpClient(host = host, port = snmpPort, credentials = toSnmpCredentials(), timeoutMillis = timeoutMillis, retries = retries)

/** Parses [RdpProfile.snmpFavoriteOids]' comma-separated storage format into a list of valid [Oid]s, silently skipping any malformed entries (defensive — this column is free-text-adjacent, not a foreign key). */
fun RdpProfile.snmpFavoriteOidList(): List<Oid> =
    snmpFavoriteOids.split(",").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { runCatching { Oid(it) }.getOrNull() }

fun List<Oid>.toSnmpFavoriteOidsColumn(): String = joinToString(",") { it.toString() }
