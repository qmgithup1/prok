package com.systemsgo.hex.vsphere

import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.model.VSphereApiMode
import com.systemsgo.hex.vsphere.protocol.VSphereApiClient
import com.systemsgo.hex.vsphere.protocol.VSphereConnectionConfig

/**
 * Reconstructs a typed [VSphereConnectionConfig] from an [RdpProfile]'s
 * `vsphere*` columns (Room can only store primitives — see
 * [RdpProfile.vsphereApiMode] et al.'s doc comments for the full column
 * list). Same pattern as Proxmox's `toProxmoxConfig()` in
 * `com.systemsgo.hex.proxmox.ProxmoxCredentials`.
 */
fun RdpProfile.toVSphereConfig(): VSphereConnectionConfig {
    val apiMode = runCatching { VSphereApiMode.valueOf(vsphereApiMode) }.getOrDefault(VSphereApiMode.REST)
    return VSphereConnectionConfig(
        host = host,
        port = port,
        apiMode = apiMode.name,
        username = username,
        password = password,
        datacenter = vsphereDatacenter,
        acceptSelfSignedCertificate = vsphereAcceptSelfSignedCertificate,
    )
}

/**
 * VMWARE-VSPHERE FEATURE (Part 3/N): the built client, ready for
 * [VSphereApiClient.login] — same convenience shape as Proxmox's
 * `toProxmoxClient()` in `com.systemsgo.hex.proxmox.ProxmoxProfileMapper`.
 */
fun RdpProfile.toVSphereClient(): VSphereApiClient = VSphereApiClient(toVSphereConfig())
