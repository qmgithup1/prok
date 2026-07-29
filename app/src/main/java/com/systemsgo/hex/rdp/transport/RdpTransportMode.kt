package com.systemsgo.hex.rdp.transport

import java.net.URI

/**
 * Selects how the RDP byte stream reaches the server.
 *
 * [TCP] is the existing, default behavior: [com.systemsgo.hex.rdp.native.AFreeRdpBridge.connect]
 * opens a native TCP socket to `host:port` exactly as it always has — nothing
 * about that path changes when this feature is added.
 *
 * [WS] / [WSS] tunnel the same byte stream through a WebSocket (RFC 6455)
 * instead, via [RdpWebSocketTransport]. FreeRDP's native code never learns
 * the difference: [RdpWebSocketTransport] listens on a loopback TCP socket,
 * hands *that* host/port to [com.systemsgo.hex.rdp.native.AFreeRdpBridge.connect],
 * and relays raw bytes between the native socket and the WebSocket. This is
 * the same "local loopback bridge" shape already used for serial-over-network
 * (see [com.systemsgo.hex.rdp.serial.SerialNetworkBridge]) — no native/JNI
 * change is required to add this transport.
 */
enum class RdpTransportMode {
    TCP,
    WS,
    WSS;

    val isWebSocket: Boolean get() = this == WS || this == WSS

    companion object {
        /**
         * Same String-backed-enum pattern as ProtocolType.fromName/
         * SshAuthType — used by [com.systemsgo.hex.data.model.RdpProfile.transportMode]'s
         * Room column (a plain String, same as gatewayAuthMode) and by
         * RemoteSessionFactory when building [com.systemsgo.hex.data.model.RdpCredentials].
         * Falls back to [TCP] for an unrecognized/blank value so a
         * corrupt or pre-feature row always resolves to the existing
         * TCP behavior (requirement #13).
         */
        fun fromName(name: String): RdpTransportMode =
            entries.firstOrNull { it.name == name } ?: TCP

        /**
         * Requirement #3: automatic transport selection.
         *
         * - An explicit `ws://` or `wss://` WebSocket URL always wins.
         * - Otherwise, if a WebSocket URL is present but schemeless, its
         *   `secure` flag (or the surrounding profile's "use TLS" toggle)
         *   decides WS vs WSS.
         * - Otherwise falls back to [TCP], so every profile that has never
         *   touched WebSocket settings keeps behaving exactly as before
         *   (requirement #13).
         */
        fun autoDetect(webSocketUrl: String?, preferSecure: Boolean = true): RdpTransportMode {
            val raw = webSocketUrl?.trim()
            if (raw.isNullOrEmpty()) return TCP

            val withScheme = if (raw.contains("://")) raw else {
                (if (preferSecure) "wss://" else "ws://") + raw
            }
            val scheme = runCatching { URI(withScheme).scheme?.lowercase() }.getOrNull()
            return when (scheme) {
                "wss", "https" -> WSS
                "ws", "http" -> WS
                else -> if (preferSecure) WSS else WS
            }
        }
    }
}
