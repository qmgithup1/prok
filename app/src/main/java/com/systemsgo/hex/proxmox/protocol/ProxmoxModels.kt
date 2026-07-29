package com.systemsgo.hex.proxmox.protocol

import com.systemsgo.hex.data.model.ProxmoxAuthMode

/**
 * PROXMOX-API FEATURE (Part 1/N): everything [ProxmoxApiClient] needs to
 * reach one Proxmox VE host — a plain data holder built once per session
 * from an [com.systemsgo.hex.data.model.RdpProfile] via
 * [com.systemsgo.hex.proxmox.ProxmoxProfileMapper.toProxmoxConfig], same
 * "reconstruct typed config from Room's primitives-only columns" shape as
 * [com.systemsgo.hex.snmp.SnmpProfileMapper]'s SnmpCredentials.
 */
data class ProxmoxConnectionConfig(
    val host: String,
    val port: Int = 8006,
    val authMode: ProxmoxAuthMode = ProxmoxAuthMode.TOKEN,
    /** `user@realm!tokenid`, e.g. `automation@pve!mobile-app`. Only read when [authMode] is TOKEN. */
    val tokenId: String = "",
    /** The token's UUID secret. Only read when [authMode] is TOKEN. */
    val tokenSecret: String = "",
    /** `user@realm`, e.g. `root@pam`. Only read when [authMode] is PASSWORD. */
    val username: String = "",
    val password: String = "",
    /** Proxmox's management API is very commonly behind a self-signed cert unless the admin fronted it with a real one. */
    val acceptSelfSignedCertificate: Boolean = true,
)

/** One row of `GET /nodes` — a single Proxmox cluster member (or the whole host, for a single-node setup). */
data class ProxmoxNode(
    val node: String,
    /** `online` / `offline` / `unknown`. */
    val status: String,
    val cpuFraction: Double,
    val maxCpu: Int,
    val memUsedBytes: Long,
    val memMaxBytes: Long,
    val uptimeSeconds: Long,
)

enum class ProxmoxGuestType { QEMU, LXC }

/** One row of `GET /nodes/{node}/qemu` or `GET /nodes/{node}/lxc` — a VM or container. */
data class ProxmoxGuest(
    val vmid: Int,
    val name: String,
    val node: String,
    val type: ProxmoxGuestType,
    /** `running` / `stopped` / `paused`, etc. */
    val status: String,
    val cpuFraction: Double,
    val maxCpu: Int,
    val memUsedBytes: Long,
    val memMaxBytes: Long,
    val uptimeSeconds: Long,
    val template: Boolean = false,
) {
    val isRunning: Boolean get() = status == "running"
}

/**
 * `POST /nodes/{node}/{qemu|lxc}/{vmid}/status/{action}`. [apiPath] is the
 * literal last path segment Proxmox expects. RESUME/SUSPEND are QEMU-only —
 * see [ProxmoxApiClient.powerAction]'s guard.
 */
enum class ProxmoxPowerAction(val apiPath: String) {
    START("start"),
    /** ACPI-style graceful power-off — the guest OS decides when it's actually done. */
    SHUTDOWN("shutdown"),
    /** Hard power-off, same as pulling the plug — only when SHUTDOWN won't cooperate. */
    STOP("stop"),
    REBOOT("reboot"),
    /** QEMU only — pauses execution, keeping RAM allocated. */
    SUSPEND("suspend"),
    /** QEMU only — the SUSPEND counterpart. */
    RESUME("resume"),
}

/** `POST /nodes/{node}/qemu/{vmid}/vncproxy` (with `websocket=1`) response — a one-time ticket for [ProxmoxApiClient.vncWebSocketUrl]. */
data class ProxmoxVncTicket(
    val ticket: String,
    val port: Int,
)

class ProxmoxException(message: String, cause: Throwable? = null) : Exception(message, cause)
