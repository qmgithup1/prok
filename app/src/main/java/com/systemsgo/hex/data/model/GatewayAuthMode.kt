package com.systemsgo.hex.data.model

/**
 * ENTRA-ID-AUTH FEATURE — Part 2/2 (native/gateway wiring).
 *
 * How the RD Gateway leg of a connection authenticates, independent of how
 * the *desktop* leg (NLA/Kerberos/username+password against the RDP host
 * itself) authenticates. A profile can freely mix either GatewayAuthMode
 * with `RdpCredentials.useNla` — Entra ID only ever gates the Gateway hop,
 * never replaces desktop credentials, matching how Azure AD Application
 * Proxy + RD Gateway deployments are actually configured (pre-auth at the
 * proxy, then normal Windows auth to the session host behind it).
 *
 * - [PASSWORD]: today's behavior. `gatewayUsername`/`gatewayPassword`/
 *   `gatewayDomain` are sent to the Gateway (RDG-in-band credentials, the
 *   same as mstsc's "Use these RD Gateway credentials" option).
 * - [ENTRA_ID]: the Gateway sits behind Azure AD Application Proxy. Instead
 *   of a username/password, an MSAL-acquired bearer token for the App
 *   Proxy's Application ID URI is sent as an `Authorization: Bearer <token>`
 *   header on the RDG HTTPS transport (MS-RDPBCGR's RDG-in-band-HTTP
 *   handshake), which App Proxy's pre-authentication validates before
 *   forwarding to the on-prem connector / RD Gateway. See
 *   `AFreeRdpBridge.GatewayAuthMode` for the mirrored native-facing enum and
 *   the systemsgo_jni.c handoff notes on the FreeRDP settings this maps onto.
 *
 * `toBridgeGatewayAuthMode()` lives next to `RdpRemoteAdapter`'s
 * `ProxyType.toBridgeProxyType()`, following the exact same "public/private
 * enum kept 1:1 in step by hand" pattern already used for that mapping.
 */
enum class GatewayAuthMode {
    PASSWORD,
    ENTRA_ID;

    companion object {
        fun fromName(name: String): GatewayAuthMode =
            entries.firstOrNull { it.name == name } ?: PASSWORD
    }
}
