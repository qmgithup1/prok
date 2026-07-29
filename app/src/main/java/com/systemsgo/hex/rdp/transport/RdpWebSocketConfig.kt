package com.systemsgo.hex.rdp.transport

/**
 * Everything a profile can configure about the WebSocket transport
 * (requirement #4). Every field has a benign default so adding this class
 * — and a nullable/default-valued instance of it to [com.systemsgo.hex.data.model.RdpProfile]
 * — cannot change behavior for existing TCP profiles (requirement #13):
 * a profile only uses this transport if [RdpTransportMode.autoDetect]
 * resolves to [RdpTransportMode.WS]/[RdpTransportMode.WSS] in the first
 * place, which requires an explicit, non-blank [url] or [host].
 *
 * ## Field -> requirement mapping
 * - [url], [host], [port], [path]                → req #4 connection target
 * - [headers], [authorizationHeader], [origin],
 *   [subprotocol], [cookie], [bearerToken]        → req #4 headers/auth
 * - [tls]                                          → req #6 TLS options
 * - [autoReconnect], [maxReconnectAttempts],
 *   [reconnectBackoff]                             → req #9 auto-reconnect
 * - [connectTimeoutMs], [handshakeTimeoutMs]       → req #10 timeout detection
 *
 * ## Guacamole / RD Gateway / reverse-proxy compatibility (requirement #7)
 * This class intentionally has no gateway-specific fields: Guacamole,
 * Windows RD Gateway's RDG-over-WebSocket mode, Nginx/HAProxy/Traefik/Caddy
 * reverse proxies, and Azure Virtual Desktop's gateway are all, from this
 * client's point of view, just "a WebSocket endpoint that speaks raw RDP
 * bytes framed as binary WS messages once the HTTP upgrade succeeds" —
 * the differences between them live entirely in [url]/[path]/[headers]/
 * [subprotocol], which the user (or a profile-import step) fills in per
 * gateway. If a specific gateway needs a text-framed or otherwise
 * non-raw-binary envelope, that belongs in a small
 * [RdpWebSocketTransport.FrameCodec] adapter — none of the gateways in
 * requirement #7 need one; they all tunnel the raw RDP octet stream as
 * binary WebSocket frames.
 */
data class RdpWebSocketConfig(
    /** Full WebSocket URL, e.g. "wss://gateway.example.com/rdp". Takes
     *  precedence over [host]/[port]/[path] when non-blank. */
    val url: String = "",
    val host: String = "",
    val port: Int = 443,
    val path: String = "/",

    /** Arbitrary extra headers (name -> value), sent on the HTTP upgrade
     *  request. Does not include Authorization/Cookie/Origin — those have
     *  their own fields below so the UI can offer first-class controls
     *  for the ones every gateway in requirement #7 actually needs. */
    val headers: Map<String, String> = emptyMap(),
    /** Sent verbatim as the `Authorization` header if non-blank. Mutually
     *  exclusive in practice with [bearerToken] (whichever is non-blank
     *  wins; if both are set, [authorizationHeader] wins since it lets the
     *  caller specify a non-Bearer scheme too, e.g. Basic/Negotiate). */
    val authorizationHeader: String = "",
    /** Convenience for the common case: sent as `Authorization: Bearer <token>`
     *  when [authorizationHeader] is blank. This is the "OAuth token
     *  forwarding" of requirement #5 at the transport level — RD Gateway's
     *  own OAuth Entra ID token forwarding is a separate, RDP-layer
     *  concern already handled by [com.systemsgo.hex.rdp.native.AFreeRdpBridge.connect]'s
     *  `gatewayAuthMode`/`gatewayBearerToken` params. */
    val bearerToken: String = "",
    val origin: String = "",
    /** Sent as `Sec-WebSocket-Protocol`. Guacamole's `guacamole` tunnel and
     *  some RDP-over-WS gateways expect a specific value here
     *  (e.g. "rdp.systemsgo" or a gateway-defined token) — left blank by
     *  default since RFC 6455 does not require one. */
    val subprotocol: String = "",
    /** Sent as the `Cookie` header — needed by gateways that pair the
     *  WebSocket upgrade with a session cookie from a prior HTTP auth step
     *  (common with reverse-proxy-fronted deployments). */
    val cookie: String = "",

    val tls: TlsOptions = TlsOptions(),

    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    /** Base delay for exponential backoff between reconnect attempts. */
    val reconnectBackoff: ReconnectBackoff = ReconnectBackoff(),

    val connectTimeoutMs: Long = 10_000,
    val handshakeTimeoutMs: Long = 10_000,
) {
    /** Requirement #6: TLS certificate handling. */
    data class TlsOptions(
        /** When false, the platform default trust manager (system CA
         *  store) is used unmodified — this is the safe default and what
         *  every profile gets unless the user explicitly opts into one of
         *  the options below. */
        val validateCertificate: Boolean = true,
        /** Accept self-signed / otherwise-untrusted certificates. Only
         *  meaningful when [validateCertificate] is true; when enabled the
         *  connection is still TLS-encrypted, just without chain-of-trust
         *  validation — same trade-off as [com.systemsgo.hex.rdp.native.AFreeRdpBridge.connect]'s
         *  existing `ignoreCert` flag for the classic TCP+TLS path, and
         *  surfaced identically (a scary, explicit toggle, never a silent
         *  default — see that flag's "BUG-4 FIX" comment on why silently
         *  ignoring certs is a MITM vulnerability). */
        val allowSelfSigned: Boolean = false,
        /** Base64 or PEM-encoded SHA-256 SPKI pins. When non-empty, the
         *  connection is rejected unless the server's leaf certificate
         *  matches one of these — independent of [allowSelfSigned], so
         *  pinning a self-signed cert's own key is supported. */
        val pinnedCertificateSha256: List<String> = emptyList(),
        /** PEM-encoded custom CA certificate(s) to trust in addition to
         *  (not instead of) the system store. */
        val customCaCertificatesPem: List<String> = emptyList(),
    )

    data class ReconnectBackoff(
        val initialDelayMs: Long = 500,
        val maxDelayMs: Long = 15_000,
        val multiplier: Double = 2.0,
    )

    /** Resolves [url] / [host]+[port]+[path] into one URL string, and
     *  normalizes the scheme against [mode] so a profile that only ever
     *  filled in host/port/path still produces a valid ws(s):// URL. */
    fun resolvedUrl(mode: RdpTransportMode): String {
        if (url.isNotBlank()) {
            return if (url.contains("://")) url else {
                val scheme = if (mode == RdpTransportMode.WSS) "wss" else "ws"
                "$scheme://$url"
            }
        }
        val scheme = if (mode == RdpTransportMode.WSS) "wss" else "ws"
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$scheme://$host:$port$cleanPath"
    }
}
