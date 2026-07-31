package com.systemsgo.hex.vsphere.protocol

/**
 * Everything a future `VSphereApiClient` (Part 2/N — not built yet) needs to
 * reach and authenticate against one vCenter or standalone ESXi host. Built
 * from an [com.systemsgo.hex.data.model.RdpProfile] via
 * [com.systemsgo.hex.vsphere.toVSphereConfig] — same pattern as Proxmox's
 * `toProxmoxConfig()`.
 *
 * [apiMode] picks which wire protocol the client speaks:
 *  - REST: vSphere 6.7+'s `/api` (vCenter 7+) or `/rest/vcenter` (6.7/7.0
 *    legacy REST) endpoints — JSON, session-token auth
 *    (`POST /api/session` returns a bearer token in a JSON string body).
 *    Preferred when available; simpler request/response shapes than SOAP.
 *  - SOAP: the vim25 API (`/sdk`) — XML/WSDL, needed for (a) ESXi hosts
 *    with no vCenter in front of them, where REST coverage is much
 *    thinner than on vCenter itself, and (b) operations REST still
 *    doesn't expose as of this writing, most notably
 *    `AcquireWebMksTicket` for console access — see [VSphereConsoleTicket].
 */
data class VSphereConnectionConfig(
    val host: String,
    val port: Int = 443,
    val apiMode: String,
    val username: String,
    val password: String,
    /** Scopes inventory listing to one datacenter when the account can see more than one. Blank = all. */
    val datacenter: String = "",
    val acceptSelfSignedCertificate: Boolean = true,
)

/** One ESXi host, whether standalone or a vCenter cluster member (`GET /api/vcenter/host` or vim25 `HostSystem`). */
data class VSphereHost(
    val moref: String,
    val name: String,
    /** `CONNECTED`, `DISCONNECTED`, `NOT_RESPONDING` per the vSphere API's connection-state enum. */
    val connectionState: String,
    /** `POWERED_ON`, `POWERED_OFF`, `STANDBY` — the host's own power state, distinct from any guest VM's. */
    val powerState: String,
)

/** How the guest OS itself is reporting, distinct from [powerState] — mirrors vim25's `GuestInfo.GuestState`. */
enum class VSphereGuestState { RUNNING, SHUTTING_DOWN, RESETTING, STANDBY, NOT_RUNNING, UNKNOWN }

/** One VM (`GET /api/vcenter/vm` or vim25 `VirtualMachine`). */
data class VSphereVm(
    val moref: String,
    val name: String,
    /** `POWERED_ON`, `POWERED_OFF`, `SUSPENDED` per the vSphere API's VM power-state enum. */
    val powerState: String,
    val guestState: VSphereGuestState = VSphereGuestState.UNKNOWN,
    /** Free-text guest OS identifier as reported by VMware Tools, e.g. "Ubuntu Linux (64-bit)". Empty if Tools isn't running. */
    val guestFullName: String = "",
    val cpuCount: Int = 0,
    /** MiB, matching the vSphere API's own unit for VM memory sizing. */
    val memoryMb: Long = 0,
    /** moref of the [VSphereHost] currently running this VM. */
    val hostMoref: String = "",
)

/** `POST /api/vcenter/vm/{vm}/power/{action}` — the `{action}` path segment, lowercased, is [wireValue]. SUSPEND has no vim25/REST equivalent for a graceful in-guest action (that's `guest.shutdown`, not a power op) — see the SUSPEND doc below. */
enum class VSpherePowerAction(val wireValue: String) {
    START("start"),
    /** Hard stop — equivalent to pulling the power. Prefer [SHUTDOWN] when VMware Tools is running. */
    STOP("stop"),
    /** Graceful in-guest shutdown via VMware Tools (`guest.shutdown`, not a `power` endpoint) — requires Tools to be running. */
    SHUTDOWN("shutdown"),
    /** Graceful in-guest restart via VMware Tools (`guest.reboot`) — requires Tools to be running. */
    RESET("reset"),
    SUSPEND("suspend"),
}

/**
 * Result of `POST /api/vcenter/vm/{vm}/console/tickets` (REST, vSphere 7+)
 * or vim25 `AcquireWebMksTicket` (SOAP — the only option against a
 * standalone ESXi host or an older vCenter). Either way the result is a
 * short-lived WebMKS ticket: a WebSocket URL speaking a VNC-derived framing
 * (RFB messages wrapped for WebSocket transport) that a browser's
 * `vmw-webmks` widget — or, here, a native bridge into the app's existing
 * VNC engine, the same pattern [com.systemsgo.hex.proxmox.ProxmoxVncBridge]
 * uses for Proxmox's own VNC-proxy ticket — connects to directly.
 */
data class VSphereConsoleTicket(
    val ticket: String,
    /** Full `wss://` URL already including the ticket — connect directly, no separate host/port assembly needed. */
    val webSocketUrl: String,
)

/** Thrown by the future `VSphereApiClient` for any non-2xx REST response, SOAP Fault, or malformed payload — wraps the vSphere API's own error body when present, same role as [com.systemsgo.hex.proxmox.protocol.ProxmoxException]. */
class VSphereException(message: String, cause: Throwable? = null) : Exception(message, cause)
