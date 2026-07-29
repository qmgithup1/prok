package com.systemsgo.hex.rdp.transport

/**
 * Requirement #10: distinct, reportable failure categories for the
 * WebSocket transport. [RdpWebSocketTransport] never lets these leak as a
 * raw [java.io.IOException]/[javax.net.ssl.SSLException] — it classifies
 * every failure into one of these before handing it to the caller's
 * `onFailure` callback, so the UI (and [RdpErrorMessages] — see that
 * file's existing pattern for TCP-path errors) can show a specific,
 * actionable message instead of a generic "connection failed".
 */
sealed class RdpTransportException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The TCP/TLS connection to the WebSocket endpoint never completed
     *  within [RdpWebSocketConfig.connectTimeoutMs]. */
    class ConnectionTimeout(val url: String) :
        RdpTransportException("Timed out connecting to $url")

    /** TLS handshake failed: untrusted chain, hostname mismatch, pinning
     *  mismatch, or a protocol/cipher negotiation failure. [reason]
     *  distinguishes these for the UI/logs. */
    class TlsFailure(val url: String, val reason: TlsFailureReason, cause: Throwable? = null) :
        RdpTransportException("TLS failure connecting to $url: $reason", cause)

    enum class TlsFailureReason { UNTRUSTED_CERTIFICATE, HOSTNAME_MISMATCH, PIN_MISMATCH, HANDSHAKE_ERROR }

    /** The HTTP Upgrade request completed but did not switch protocols —
     *  i.e. the server (or a reverse proxy in front of it) answered with a
     *  normal HTTP response instead of `101 Switching Protocols`.
     *  [httpStatusCode] covers requirement #10's 401/403/404/500 cases;
     *  anything else falls into [HandshakeFailureReason.UNEXPECTED_STATUS]. */
    class HandshakeFailure(
        val url: String,
        val httpStatusCode: Int?,
        val reason: HandshakeFailureReason,
        cause: Throwable? = null,
    ) : RdpTransportException(
        "WebSocket handshake to $url failed" +
            (httpStatusCode?.let { " (HTTP $it)" } ?: "") + ": $reason",
        cause,
    )

    enum class HandshakeFailureReason {
        UNAUTHORIZED,          // HTTP 401
        FORBIDDEN,              // HTTP 403
        NOT_FOUND,               // HTTP 404
        SERVER_ERROR,            // HTTP 5xx
        INVALID_HEADERS,         // malformed/rejected request headers
        SUBPROTOCOL_REJECTED,    // server didn't accept Sec-WebSocket-Protocol
        UNEXPECTED_STATUS,
    }

    /** A configured HTTP/SOCKS proxy sits between the client and the
     *  WebSocket endpoint and rejected the CONNECT/request with a 407 or
     *  equivalent proxy-auth challenge. */
    class ProxyAuthenticationFailure(val proxyHost: String, cause: Throwable? = null) :
        RdpTransportException("Proxy authentication failed at $proxyHost", cause)

    /** A header value (in [RdpWebSocketConfig.headers], the Authorization/
     *  Cookie/Origin fields, or [RdpWebSocketConfig.subprotocol]) was
     *  rejected before the request was even sent — e.g. contains illegal
     *  characters (CR/LF injection guard) — see [RdpWebSocketTransport.validateHeaders]. */
    class InvalidHeader(val headerName: String, val reason: String) :
        RdpTransportException("Invalid header \"$headerName\": $reason")

    /** The local loopback bridge (the socket FreeRDP's native code
     *  connects to) failed to bind/accept — an environment problem, not a
     *  remote one. */
    class LocalBridgeFailure(cause: Throwable) :
        RdpTransportException("Failed to establish local transport bridge", cause)

    /** The WebSocket connected successfully but then dropped mid-session
     *  and [RdpWebSocketConfig.autoReconnect] exhausted [RdpWebSocketConfig.maxReconnectAttempts]. */
    class ReconnectExhausted(val attempts: Int, cause: Throwable? = null) :
        RdpTransportException("Gave up reconnecting after $attempts attempt(s)", cause)

    companion object {
        /** Maps an OkHttp WebSocket `onFailure(response, throwable)` pair
         *  into the taxonomy above. */
        fun fromHandshakeFailure(url: String, httpCode: Int?, throwable: Throwable?): RdpTransportException {
            return when (httpCode) {
                401 -> HandshakeFailure(url, 401, HandshakeFailureReason.UNAUTHORIZED, throwable)
                403 -> HandshakeFailure(url, 403, HandshakeFailureReason.FORBIDDEN, throwable)
                404 -> HandshakeFailure(url, 404, HandshakeFailureReason.NOT_FOUND, throwable)
                407 -> ProxyAuthenticationFailure(url, throwable)
                in 500..599 -> HandshakeFailure(url, httpCode, HandshakeFailureReason.SERVER_ERROR, throwable)
                null -> when (throwable) {
                    is java.net.SocketTimeoutException -> ConnectionTimeout(url)
                    is javax.net.ssl.SSLPeerUnverifiedException ->
                        TlsFailure(url, TlsFailureReason.HOSTNAME_MISMATCH, throwable)
                    is javax.net.ssl.SSLHandshakeException ->
                        TlsFailure(url, TlsFailureReason.UNTRUSTED_CERTIFICATE, throwable)
                    is javax.net.ssl.SSLException ->
                        TlsFailure(url, TlsFailureReason.HANDSHAKE_ERROR, throwable)
                    else -> HandshakeFailure(url, null, HandshakeFailureReason.UNEXPECTED_STATUS, throwable)
                }
                else -> HandshakeFailure(url, httpCode, HandshakeFailureReason.UNEXPECTED_STATUS, throwable)
            }
        }
    }
}
