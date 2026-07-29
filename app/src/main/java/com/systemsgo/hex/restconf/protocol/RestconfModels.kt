package com.systemsgo.hex.restconf.protocol

import com.systemsgo.hex.data.model.RestconfAuthType
import com.systemsgo.hex.data.model.RestconfDataFormat

/**
 * RESTCONF FEATURE (Part 1/4): everything [RestconfClient] needs to open a
 * session that isn't already on [com.systemsgo.hex.data.model.RdpProfile]
 * verbatim (host/port/username/password/acceptSelfSignedCertificate are
 * reused exactly like every other protocol). Built once by the caller
 * (ViewModel) from a profile row + any PAC/proxy resolution already done by
 * [com.systemsgo.hex.proxy.PacProxyResolver] — mirrors AFreeRdpBridge's/
 * SshClient's `outboundProxy` parameter shape rather than have the client
 * reach into proxy resolution itself.
 *
 * `baseUrl` is the RESTCONF root, e.g. `https://10.0.0.5:8443` — the client
 * appends the standard `/.well-known/host-meta`-discovered (or, absent
 * that, conventional `/restconf`) root path itself; see
 * [RestconfClient.discoverRootResource].
 */
data class RestconfConnectionConfig(
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val authType: RestconfAuthType = RestconfAuthType.BASIC,
    val dataFormat: RestconfDataFormat = RestconfDataFormat.JSON,
    val acceptSelfSignedCertificate: Boolean = false,
    val bearerToken: String = "",
    val jwtToken: String = "",
    val apiKeyHeaderName: String = "X-API-Key",
    val apiKeyValue: String = "",
    val customHeaders: Map<String, String> = emptyMap(),
    val clientCertAlias: String = "",
    val mutualTlsEnabled: Boolean = false,
    val oauth2TokenUrl: String = "",
    val oauth2ClientId: String = "",
    val oauth2ClientSecret: String = "",
    val oauth2Scope: String = "",
    val certificatePinsSha256: List<String> = emptyList(), // "sha256/AAAA..." SPKI pins, OkHttp CertificatePinner format
    val http2Enabled: Boolean = true,
    val compressionEnabled: Boolean = true,
    val connectTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 30,
    val writeTimeoutSeconds: Long = 30,
    val keepAliveSeconds: Long = 60,
    val proxy: java.net.Proxy? = null,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
)

enum class RestconfMethod { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS }

/** Raw wire response, format-agnostic — [RestconfClient] hands this back for every call so the UI (Part 2) can render tree/raw/pretty from one shape. */
data class RestconfResponse(
    val statusCode: Int,
    val statusMessage: String,
    val headers: Map<String, List<String>>,
    val body: String?,
    val contentType: String?,
    val requestUrl: String,
    val requestMethod: String,
    val elapsedMillis: Long,
    val sizeBytes: Long,
    val tlsVersion: String? = null,
    val protocol: String? = null, // "h2", "http/1.1", ...
) {
    val isSuccess: Boolean get() = statusCode in 200..299
    /** Best-effort sniff for the Response Viewer/YANG browser when a server omits/mislabels Content-Type. */
    val detectedFormat: RestconfDataFormat
        get() {
            val ct = contentType?.lowercase().orEmpty()
            return when {
                ct.contains("xml") -> RestconfDataFormat.XML
                ct.contains("json") -> RestconfDataFormat.JSON
                body?.trimStart()?.startsWith("<") == true -> RestconfDataFormat.XML
                else -> RestconfDataFormat.JSON
            }
        }
}

data class RestconfException(
    val message0: String,
    val httpStatus: Int? = null,
    val restconfErrorTag: String? = null,   // e.g. "invalid-value", "data-missing" (RFC 8040 §7)
    val restconfErrorMessage: String? = null,
    override val cause: Throwable? = null,
) : Exception(message0, cause)

// ── Capability / YANG library discovery (RFC 8040 §9, RFC 7895/8525) ──────

data class RestconfServerCapabilities(
    val restconfRoot: String,                 // e.g. "/restconf"
    val capabilities: List<String>,            // raw ietf-restconf-monitoring:capability entries
    val supportsYangLibrary1_1: Boolean,
    val supportsPatch: Boolean,
    val supportsDefaultsParam: Boolean,
    val supportsFilterParam: Boolean,
    val supportsDepthParam: Boolean,
)

data class YangModule(
    val name: String,
    val revision: String?,
    val namespace: String,
    val featureSet: List<String> = emptyList(),
    val deviations: List<String> = emptyList(),
    val isSubmodule: Boolean = false,
    val schemaUrl: String? = null, // GET-able via ietf-yang-library / RFC 8525 to fetch the actual .yang text
)

/** One node in the YANG browser's datastore tree (Part 3 UI consumes this; the walk itself lives here since it's protocol-level, not UI). */
data class RestconfResourceNode(
    val name: String,
    val path: String,            // full RESTCONF resource path from datastore root
    val isContainer: Boolean,
    val isList: Boolean,
    val hasChildren: Boolean,
)

// ── RPC / YANG action execution (RFC 8040 §3.6) ────────────────────────────

data class RestconfOperation(
    val name: String,
    val moduleName: String,
    val inputSchema: String? = null, // best-effort, from OPTIONS/description if server exposes it
)

// ── Session-level monitoring (mirrors the shape SshClient/other clients already expose to their session screens) ─

data class RestconfSessionStats(
    val requestCount: Long = 0,
    val errorCount: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val lastLatencyMillis: Long = 0,
    val averageLatencyMillis: Long = 0,
    val lastTlsVersion: String? = null,
    val lastProtocol: String? = null,
    val connectedSinceEpochMillis: Long? = null,
    val reconnectCount: Int = 0,
)

enum class RestconfConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }
