package com.systemsgo.hex.rdp.protocol

/**
 * Translates the raw error-name strings that `systemsgo_jni.c` relays through
 * `AFreeRdpBridge.onNativeError()` into human-readable, xrdp-aware messages.
 *
 * Two independent FreeRDP error surfaces are both routed through that one
 * callback (see systemsgo_jni.c for where each is emitted), and are told apart
 * here purely by their name prefix — no native-side format/JNI-signature
 * change was needed to add this:
 *
 *  - "ERRCONNECT_*" (from `freerdp_get_last_error_name`) — the connection
 *    never came up at all: DNS/TCP/TLS/NLA/auth failed during
 *    `freerdp_connect()`. Emitted from `nativeConnect`'s failure path.
 *
 *  - "ERRINFO_*" (from `freerdp_get_error_info_name`) — the connection came
 *    up fine and the *server* tore it down afterwards via a Set Error Info
 *    PDU. Emitted from `systemsgo_post_disconnect`. This is the bucket that
 *    matters most for xrdp: when xrdp's `sesman` can't reach or start the
 *    Xorg/Xvnc backend after a successful login, this is how it shows up —
 *    there is no dedicated "sesman unavailable" wire code (xrdp speaks
 *    plain MS-RDPBCGR here, it doesn't extend the Error Info PDU), so that
 *    specific case is inferred from ERRINFO_SERVER_DENIED_CONNECTION /
 *    ERRCONNECT_CONNECT_TRANSPORT_FAILED context and worded accordingly
 *    rather than asserted as fact.
 *
 * Unrecognized names fall back to the raw string unchanged (today's
 * behaviour), so this can never make error reporting *worse* — it only ever
 * adds a friendlier message on top of names this table knows about.
 */
object RdpErrorMessages {

    /**
     * @param rawName the exact string passed to `AFreeRdpBridge.onNativeError`
     * @return a human-readable message; falls back to [rawName] itself when
     *   it isn't one of the known FreeRDP error-name symbols.
     */
    fun humanize(rawName: String): String {
        return CONNECT_ERRORS[rawName]
            ?: ERROR_INFO[rawName]
            ?: rawName
    }

    // ── RDP-OVER-WEBSOCKET FEATURE — requirement #10 ────────────────────────
    // A third, independent error surface alongside CONNECT_ERRORS/ERROR_INFO
    // above: failures in RdpWebSocketTransport's loopback-bridge/WebSocket
    // layer, which never reach systemsgo_jni.c at all when they happen (the
    // native side simply never got a chance to connect to the loopback
    // socket, or lost the connection to it once the tunnel dropped) — see
    // com.systemsgo.hex.rdp.transport.RdpTransportException's doc comment for
    // the full failure taxonomy this switches over.
    /**
     * @param e the classified transport failure (see [RdpTransportException])
     * @return a human-readable message describing the WebSocket transport
     *   failure — never falls back to a raw exception message, since every
     *   [RdpTransportException] subtype already carries enough structured
     *   detail (URL, HTTP status, proxy host, ...) to build one directly.
     */
    fun forWebSocketTransport(e: com.systemsgo.hex.rdp.transport.RdpTransportException): String {
        return when (e) {
            is com.systemsgo.hex.rdp.transport.RdpTransportException.ConnectionTimeout ->
                "Connection to ${e.url} timed out."
            is com.systemsgo.hex.rdp.transport.RdpTransportException.TlsFailure -> when (e.reason) {
                com.systemsgo.hex.rdp.transport.RdpTransportException.TlsFailureReason.UNTRUSTED_CERTIFICATE ->
                    "The server's TLS certificate isn't trusted. Enable \"Allow self-signed certificates\" " +
                        "in this profile's Transport settings if you trust this server, or add its CA " +
                        "certificate under Custom CA certificates."
                com.systemsgo.hex.rdp.transport.RdpTransportException.TlsFailureReason.HOSTNAME_MISMATCH ->
                    "The certificate doesn't match ${e.url}."
                com.systemsgo.hex.rdp.transport.RdpTransportException.TlsFailureReason.PIN_MISMATCH ->
                    "The server's certificate doesn't match the pinned certificate for this profile."
                com.systemsgo.hex.rdp.transport.RdpTransportException.TlsFailureReason.HANDSHAKE_ERROR ->
                    "TLS handshake failed."
            }
            is com.systemsgo.hex.rdp.transport.RdpTransportException.HandshakeFailure -> when (e.reason) {
                com.systemsgo.hex.rdp.transport.RdpTransportException.HandshakeFailureReason.UNAUTHORIZED ->
                    "Authentication required (HTTP 401). Check the Authorization header, Bearer token, or Cookie in this profile's Transport settings."
                com.systemsgo.hex.rdp.transport.RdpTransportException.HandshakeFailureReason.FORBIDDEN ->
                    "Access denied (HTTP 403)."
                com.systemsgo.hex.rdp.transport.RdpTransportException.HandshakeFailureReason.NOT_FOUND ->
                    "WebSocket endpoint not found (HTTP 404) — check the Path in this profile's Transport settings."
                com.systemsgo.hex.rdp.transport.RdpTransportException.HandshakeFailureReason.SERVER_ERROR ->
                    "Gateway server error (HTTP ${e.httpStatusCode ?: 500})."
                com.systemsgo.hex.rdp.transport.RdpTransportException.HandshakeFailureReason.INVALID_HEADERS ->
                    "The gateway rejected one or more headers."
                com.systemsgo.hex.rdp.transport.RdpTransportException.HandshakeFailureReason.SUBPROTOCOL_REJECTED ->
                    "The gateway rejected the WebSocket subprotocol."
                com.systemsgo.hex.rdp.transport.RdpTransportException.HandshakeFailureReason.UNEXPECTED_STATUS ->
                    "WebSocket handshake failed" +
                        (e.httpStatusCode?.let { " (HTTP $it)" } ?: "") + "."
            }
            is com.systemsgo.hex.rdp.transport.RdpTransportException.ProxyAuthenticationFailure ->
                "Proxy authentication failed at ${e.proxyHost}."
            is com.systemsgo.hex.rdp.transport.RdpTransportException.InvalidHeader ->
                "Invalid header \"${e.headerName}\": ${e.reason}"
            is com.systemsgo.hex.rdp.transport.RdpTransportException.LocalBridgeFailure ->
                "Internal transport error setting up the WebSocket bridge."
            is com.systemsgo.hex.rdp.transport.RdpTransportException.ReconnectExhausted ->
                "Lost connection to the gateway (gave up after ${e.attempts} attempt(s))."
        }
    }

    // ── ERRCONNECT_* — connection never established ────────────────────────
    // Source: FreeRDP include/freerdp/error.h (values stable since FreeRDP 2.x;
    // this project pins FreeRDP 3.27.1 — see .github/workflows/main.yml).
    private val CONNECT_ERRORS: Map<String, String> = mapOf(
        "ERRCONNECT_DNS_ERROR" to
            "Could not resolve the server address. Check the hostname/IP and your network's DNS.",
        "ERRCONNECT_DNS_NAME_NOT_FOUND" to
            "The server hostname could not be found. Check for typos or try the server's IP address directly.",
        "ERRCONNECT_CONNECT_FAILED" to
            "Could not reach the server. On Linux, confirm the xrdp service is running " +
                "(`systemctl status xrdp`) and listening on port 3389, and that nothing " +
                "(a firewall, security group, etc.) is blocking the connection.",
        "ERRCONNECT_CONNECT_TRANSPORT_FAILED" to
            "The connection dropped while negotiating with the server. If this happens " +
                "right after entering credentials, xrdp's session manager (sesman) or the " +
                "Xorg/Xvnc backend it starts may have failed — check /var/log/xrdp/xrdp-sesman.log " +
                "and ~/.xorgxrdp.*.log (or ~/.xsession-errors) on the server.",
        "ERRCONNECT_TLS_CONNECT_FAILED" to
            "TLS handshake with the server failed. This can mean an untrusted/self-signed " +
                "certificate, an expired certificate, or a TLS version/cipher mismatch — " +
                "xrdp's default certificate is self-signed unless one was configured in xrdp.ini.",
        "ERRCONNECT_AUTHENTICATION_FAILED" to
            "Authentication failed. Check the username and password, and confirm the " +
                "authentication mode (NLA / TLS / Standard RDP Security) matches what xrdp expects.",
        "ERRCONNECT_INSUFFICIENT_PRIVILEGES" to
            "The server rejected this account due to insufficient privileges.",
        "ERRCONNECT_CONNECT_CANCELLED" to
            "The connection was cancelled.",
        "ERRCONNECT_SECURITY_NEGO_CONNECT_FAILED" to
            "Security protocol negotiation failed. The server and client couldn't agree on " +
                "NLA / TLS / Standard RDP Security — check the security layer configured in " +
                "xrdp.ini (security_layer=) against this connection's authentication setting.",
        "ERRCONNECT_MCS_CONNECT_INITIAL_ERROR" to
            "The server rejected the initial connection request (MCS Connect-Initial).",
        "ERRCONNECT_PASSWORD_EXPIRED" to
            "The account's password has expired on the server.",
        "ERRCONNECT_PRE_CONNECT_FAILED" to
            "The connection attempt failed before reaching the server. Check the address and port.",
        "ERRCONNECT_POST_CONNECT_FAILED" to
            "The connection was accepted but setup failed afterwards.",
    )

    // ── ERRINFO_* — server tore down an established session ───────────────
    private val ERROR_INFO: Map<String, String> = mapOf(
        "ERRINFO_SERVER_DENIED_CONNECTION" to
            "The server denied the connection after login. On xrdp this usually means " +
                "sesman could not start (or reach) the Xorg/Xvnc session backend — check " +
                "/var/log/xrdp/xrdp-sesman.log on the server.",
        "ERRINFO_SERVER_INSUFFICIENT_PRIVILEGES" to
            "The session was denied: insufficient privileges for this account on the server.",
        "ERRINFO_SERVER_FRESH_CREDENTIALS_REQUIRED" to
            "The server rejected the saved credentials and requires them to be entered again.",
        "ERRINFO_LOGOFF_BY_USER" to
            "Session ended: logged off.",
        "ERRINFO_RPC_INITIATED_DISCONNECT" to
            "The session was disconnected by the server administrator.",
        "ERRINFO_RPC_INITIATED_LOGOFF" to
            "The session was logged off by the server administrator.",
        "ERRINFO_RPC_INITIATED_DISCONNECT_BY_USER" to
            "The session was disconnected by an administrative tool on the server.",
        "ERRINFO_IDLE_TIMEOUT" to
            "Session ended: idle timeout on the server.",
        "ERRINFO_LOGON_TIMEOUT" to
            "Session ended: the server's logon timer elapsed. On xrdp this can mean " +
                "sesman took too long to start the Xorg/Xvnc backend.",
        "ERRINFO_DISCONNECTED_BY_OTHER_CONNECTION" to
            "Disconnected: another connection took over this session.",
        "ERRINFO_OUT_OF_MEMORY" to
            "The server ran out of memory resources.",
        "ERRINFO_SERVER_DWM_CRASH" to
            "The remote session's desktop compositor crashed on the server.",
        "ERRINFO_SERVER_WINLOGON_CRASH" to
            "The remote session's logon process crashed on the server.",
        "ERRINFO_SERVER_CSRSS_CRASH" to
            "A core session process crashed on the server.",
        "ERRINFO_CLOSE_STACK_ON_DRIVER_NOT_READY" to
            "The server's display driver was not ready; the session was closed.",
        "ERRINFO_CLOSE_STACK_ON_DRIVER_FAILURE" to
            "The server's display driver failed; the session was closed.",
        "ERRINFO_CLOSE_STACK_ON_DRIVER_IFACE_FAILURE" to
            "The server's display driver interface failed; the session was closed.",
        "ERRINFO_LICENSE_NO_LICENSE_SERVER" to
            "No RDP license server could be found (Windows-only; not applicable to xrdp).",
        "ERRINFO_LICENSE_NO_LICENSE" to
            "No RDP license is available on the server (Windows-only; not applicable to xrdp).",
    )
}
