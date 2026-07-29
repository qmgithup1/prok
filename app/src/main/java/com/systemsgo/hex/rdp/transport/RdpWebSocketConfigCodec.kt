package com.systemsgo.hex.rdp.transport

/**
 * Serialises [RdpWebSocketConfig] to/from a single delimited string for Room
 * storage — same lightweight approach already used elsewhere in this
 * codebase for structured per-profile config (see
 * `com.systemsgo.hex.data.model.SshPortForwardCodec`/`SshJumpHopCodec`):
 * "\u001D" (Group Separator) separates the top-level scalar fields,
 * "\u001E" (Record Separator) separates entries within a list/map field,
 * and "\u001F" (Unit Separator) separates a map entry's key from its value.
 * None of those three control characters can appear in a URL, header name/
 * value (CR/LF are already rejected — see [RdpWebSocketTransport.validateHeaders]),
 * PEM block, or base64 pin, so no escaping is needed.
 *
 * [RdpProfile.transportMode]/[RdpProfile.webSocketConfig] follow the exact
 * same "plain String column, decoded at the point of use" pattern already
 * used for `gatewayAuthMode` (an enum name) and `sshPortForwards` (this
 * codec's sibling) — see those fields' doc comments. An empty string
 * decodes to [RdpWebSocketConfig]'s all-default instance, so every existing
 * row (which has never had this column populated) round-trips to "TCP,
 * blank WS config" with zero behavior change (requirement #13).
 */
object RdpWebSocketConfigCodec {
    private const val FIELD_SEP = "\u001D"
    private const val ITEM_SEP = "\u001E"
    private const val KV_SEP = "\u001F"

    // Field count/order below MUST stay in sync between encode() and decode().
    private const val FIELD_COUNT = 17

    fun encode(config: RdpWebSocketConfig): String {
        val fields = listOf(
            config.url,
            config.host,
            config.port.toString(),
            config.path,
            encodeMap(config.headers),
            config.authorizationHeader,
            config.bearerToken,
            config.origin,
            config.subprotocol,
            config.cookie,
            config.tls.validateCertificate.toString(),
            config.tls.allowSelfSigned.toString(),
            config.tls.pinnedCertificateSha256.joinToString(ITEM_SEP),
            config.tls.customCaCertificatesPem.joinToString(ITEM_SEP),
            config.autoReconnect.toString(),
            config.maxReconnectAttempts.toString(),
            listOf(
                config.reconnectBackoff.initialDelayMs.toString(),
                config.reconnectBackoff.maxDelayMs.toString(),
                config.reconnectBackoff.multiplier.toString(),
                config.connectTimeoutMs.toString(),
                config.handshakeTimeoutMs.toString(),
            ).joinToString(ITEM_SEP),
        )
        check(fields.size == FIELD_COUNT) { "RdpWebSocketConfigCodec field count drifted" }
        return fields.joinToString(FIELD_SEP)
    }

    fun decode(value: String): RdpWebSocketConfig {
        if (value.isEmpty()) return RdpWebSocketConfig()
        val f = value.split(FIELD_SEP)
        if (f.size != FIELD_COUNT) return RdpWebSocketConfig()
        val timers = f[16].split(ITEM_SEP)
        return runCatching {
            RdpWebSocketConfig(
                url = f[0],
                host = f[1],
                port = f[2].toIntOrNull() ?: 443,
                path = f[3],
                headers = decodeMap(f[4]),
                authorizationHeader = f[5],
                bearerToken = f[6],
                origin = f[7],
                subprotocol = f[8],
                cookie = f[9],
                tls = RdpWebSocketConfig.TlsOptions(
                    validateCertificate = f[10].toBooleanStrictOrNull() ?: true,
                    allowSelfSigned = f[11].toBooleanStrictOrNull() ?: false,
                    pinnedCertificateSha256 = if (f[12].isEmpty()) emptyList() else f[12].split(ITEM_SEP),
                    customCaCertificatesPem = if (f[13].isEmpty()) emptyList() else f[13].split(ITEM_SEP),
                ),
                autoReconnect = f[14].toBooleanStrictOrNull() ?: true,
                maxReconnectAttempts = f[15].toIntOrNull() ?: 5,
                reconnectBackoff = RdpWebSocketConfig.ReconnectBackoff(
                    initialDelayMs = timers.getOrNull(0)?.toLongOrNull() ?: 500,
                    maxDelayMs = timers.getOrNull(1)?.toLongOrNull() ?: 15_000,
                    multiplier = timers.getOrNull(2)?.toDoubleOrNull() ?: 2.0,
                ),
                connectTimeoutMs = timers.getOrNull(3)?.toLongOrNull() ?: 10_000,
                handshakeTimeoutMs = timers.getOrNull(4)?.toLongOrNull() ?: 10_000,
            )
        }.getOrElse { RdpWebSocketConfig() }
    }

    private fun encodeMap(map: Map<String, String>): String =
        map.entries.joinToString(ITEM_SEP) { (k, v) -> "$k$KV_SEP$v" }

    private fun decodeMap(value: String): Map<String, String> {
        if (value.isEmpty()) return emptyMap()
        return value.split(ITEM_SEP).mapNotNull { entry ->
            val idx = entry.indexOf(KV_SEP)
            if (idx < 0) return@mapNotNull null
            entry.substring(0, idx) to entry.substring(idx + 1)
        }.toMap()
    }
}
