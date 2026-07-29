package com.systemsgo.hex.proxmox

import com.systemsgo.hex.data.model.ProxmoxAuthMode
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.proxmox.protocol.ProxmoxApiClient
import com.systemsgo.hex.proxmox.protocol.ProxmoxConnectionConfig

/**
 * Reconstructs a typed [ProxmoxConnectionConfig] from an [RdpProfile]'s
 * `proxmox*` columns (Room's own constraint of primitives-only storage —
 * see [RdpProfile.proxmoxAuthMode] et al.'s doc comments) — same shape as
 * [com.systemsgo.hex.snmp.SnmpProfileMapper.toSnmpCredentials].
 */
fun RdpProfile.toProxmoxConfig(): ProxmoxConnectionConfig = ProxmoxConnectionConfig(
    host = host,
    port = port,
    authMode = runCatching { ProxmoxAuthMode.valueOf(proxmoxAuthMode) }.getOrDefault(ProxmoxAuthMode.TOKEN),
    tokenId = proxmoxTokenId,
    tokenSecret = proxmoxTokenSecret,
    username = username,
    password = password,
    acceptSelfSignedCertificate = proxmoxAcceptSelfSignedCertificate,
)

fun RdpProfile.toProxmoxClient(): ProxmoxApiClient = ProxmoxApiClient(toProxmoxConfig())
