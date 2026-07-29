package com.systemsgo.hex.restconf.protocol

import android.content.Context
import android.security.KeyChain
import com.google.gson.JsonParser
import com.systemsgo.hex.data.model.RestconfAuthType
import com.systemsgo.hex.data.model.RestconfDataFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * RESTCONF FEATURE (Part 1/4): native RFC 8040 client — first-class
 * protocol alongside RDP/SSH/VNC/Telnet/Redfish, not an embedded browser
 * (see [com.systemsgo.hex.data.model.ProtocolType.RESTCONF]). Mirrors
 * [com.systemsgo.hex.redfish.protocol.RedfishClient]'s shape (both are
 * REST-over-HTTPS network-management protocols) but generalizes far past
 * it: RESTCONF has no fixed schema the way Redfish's DMTF-defined resource
 * tree does, so this client is a *generic* RESTCONF/YANG engine — the
 * request builder/response viewer (Part 2) and YANG browser (Part 3) sit on
 * top of it rather than this client knowing about any particular device's
 * data model.
 *
 * One instance = one session against one server. "Multiple concurrent
 * RESTCONF sessions" (requirement) falls out of that directly — the
 * ViewModel layer (Part 2) holds one [RestconfClient] per open tab/profile,
 * exactly like [com.systemsgo.hex.ssh.protocol] already does per SSH tab.
 *
 * Auth: every [RestconfAuthType] is wired here. BASIC/BEARER_TOKEN/JWT/
 * API_KEY/CUSTOM_HEADER attach a header via [RestconfAuth.headerInterceptor].
 * DIGEST is a real [okhttp3.Authenticator] (needs the server's 401
 * challenge first — RFC 7616 forbids preemptive digest). OAUTH2 fetches +
 * caches + refreshes a client-credentials token. CLIENT_CERTIFICATE/
 * MUTUAL_TLS load a client keystore into the TLS layer itself (see
 * [buildHttpClient]) rather than an HTTP header.
 */
class RestconfClient(
    private val config: RestconfConnectionConfig,
    /**
     * Optional fallback: a PKCS12 keystore holding the client cert + key, and
     * its password, for CLIENT_CERTIFICATE/MUTUAL_TLS setups that import a
     * raw .p12 rather than using a cert already installed on the device.
     * Superseded by [androidContext] + [config]'s `clientCertAlias` below
     * whenever both are present (Part 4: real Android Keystore/KeyChain
     * picker), so most callers never need this at all.
     */
    private val clientKeyStore: KeyStore? = null,
    private val clientKeyStorePassword: CharArray? = null,
    /**
     * RESTCONF FEATURE (Part 4/4): application [Context], needed only for
     * CLIENT_CERTIFICATE/MUTUAL_TLS when [RestconfConnectionConfig.clientCertAlias]
     * points at a cert the user picked from the system's real Android
     * Keystore/KeyChain (via [android.security.KeyChain.choosePrivateKeyAlias] —
     * see [com.systemsgo.hex.ui.screens.RestconfExplorerActivity]). We never
     * hold the raw private key material ourselves: [KeyChain.getPrivateKey]
     * returns a key handle the platform brokers (hardware-backed on devices
     * that support it), exactly like every other Android app that does
     * client-cert TLS against a device-installed identity.
     */
    private val androidContext: Context? = null,
) {
    private val root = config.baseUrl.trimEnd('/')
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthMonitorJob: Job? = null

    private val _connectionState = MutableStateFlow(RestconfConnectionState.DISCONNECTED)
    val connectionState: StateFlow<RestconfConnectionState> = _connectionState.asStateFlow()

    private val _stats = MutableStateFlow(RestconfSessionStats())
    val stats: StateFlow<RestconfSessionStats> = _stats.asStateFlow()

    private val requestCount = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val latencySum = AtomicLong(0)
    private var reconnectCount = 0
    private var discoveredRestconfRoot: String? = null // e.g. "/restconf" — cached after discoverRootResource()

    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    // ── HTTP client construction ────────────────────────────────────────

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(config.readTimeoutSeconds + config.writeTimeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // HTTP_2 first (OkHttp negotiates via ALPN over TLS automatically and
            // falls back to HTTP_1_1 itself if the server doesn't advertise h2 —
            // this list is a preference order, not a hard requirement), then 1.1
            // for plain-HTTP RESTCONF servers where ALPN doesn't apply at all.
            .protocols(if (config.http2Enabled) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1) else listOf(Protocol.HTTP_1_1))

        if (config.keepAliveSeconds > 0) {
            builder.connectionPool(okhttp3.ConnectionPool(5, config.keepAliveSeconds, TimeUnit.SECONDS))
        }

        // Compression: OkHttp always transparently sends "Accept-Encoding: gzip" and
        // inflates gzip responses unless the caller sets Accept-Encoding itself, which
        // this client never does — so response compression is on by default already.
        // What this flag controls is *request*-body compression for POST/PUT/PATCH,
        // which OkHttp has no automatic support for (a server has to opt in via
        // Content-Encoding), so we gzip the outgoing body ourselves when enabled.
        if (config.compressionEnabled) {
            builder.addInterceptor(GzipRequestInterceptor())
        }

        wireAuth(builder)
        wireTls(builder)
        wireProxy(builder)

        builder.addInterceptor { chain ->
            requestCount.incrementAndGet()
            val started = System.nanoTime()
            val resp = try {
                chain.proceed(chain.request())
            } catch (e: Exception) {
                errorCount.incrementAndGet()
                throw e
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            latencySum.addAndGet(elapsedMs)
            if (!resp.isSuccessful) errorCount.incrementAndGet()
            bytesReceived.addAndGet(resp.body?.contentLength()?.coerceAtLeast(0) ?: 0)
            publishStats(elapsedMs, resp)
            resp
        }

        return builder.build()
    }

    private fun wireAuth(builder: OkHttpClient.Builder) {
        when (config.authType) {
            RestconfAuthType.DIGEST -> builder.authenticator(RestconfAuth.digestAuthenticator(config.username, config.password))
            RestconfAuthType.OAUTH2 -> {
                val tokenClient = OkHttpClient.Builder()
                    .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
                    .build()
                builder.addInterceptor(RestconfAuth.OAuth2Interceptor(config, tokenClient))
            }
            else -> Unit // header-based types handled by the interceptor below, which always runs
        }
        builder.addInterceptor(RestconfAuth.headerInterceptor(config))
    }

    private fun wireTls(builder: OkHttpClient.Builder) {
        val needsClientCert = config.authType == RestconfAuthType.CLIENT_CERTIFICATE ||
            config.authType == RestconfAuthType.MUTUAL_TLS || config.mutualTlsEnabled

        // SECURITY FIX (TLS-TOFU-PARITY): this used to be a blind trust-all
        // X509TrustManager (empty checkServerTrusted) — once the user opted
        // in to "accept self-signed certificate" for this profile, *every*
        // future connection trusted *any* certificate, with no fingerprint
        // pinning and no detection of a later-substituted (MITM) certificate,
        // unlike Telnet/RDP/NETCONF/Guacamole in this same app. Now uses the
        // same silent TOFU pinning those protocols use — see
        // com.systemsgo.hex.security.TofuTrustManager's doc comment.
        val trustManagers: Array<javax.net.ssl.TrustManager> = if (config.acceptSelfSignedCertificate) {
            val identity = java.net.URI(root).let { "${it.host}:${if (it.port > 0) it.port else 443}" }
            // SECURITY FIX (TLS-TOFU-NO-FALLBACK): previously, a missing
            // androidContext silently fell back to a trust-all X509TrustManager
            // (empty checkServerTrusted) — every certificate accepted, no
            // pinning, no MITM detection, with only a log line as a trace.
            // That fallback is exactly the vulnerability TofuTrustManager was
            // introduced to close, so it must never be reachable: fail closed
            // (refuse to build the client) instead of connecting insecurely.
            val context = androidContext
                ?: throw IllegalStateException(
                    "acceptSelfSignedCertificate is on for '$identity' but no androidContext was " +
                        "supplied to RestconfClient — TOFU certificate pinning requires a Context " +
                        "to store the pinned fingerprint. Refusing to connect with a trust-all " +
                        "fallback. Pass androidContext to RestconfClient's constructor.",
                )
            arrayOf(com.systemsgo.hex.security.TofuTrustManager(context, identity))
        } else {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?) // platform default trust store (system + user-added CAs)
            tmf.trustManagers
        }

        val keyManagers: Array<KeyManager>? = when {
            needsClientCert && androidContext != null && config.clientCertAlias.isNotBlank() ->
                buildKeyChainKeyManagers(androidContext, config.clientCertAlias)
            needsClientCert && clientKeyStore != null && clientKeyStorePassword != null -> {
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                kmf.init(clientKeyStore, clientKeyStorePassword)
                kmf.keyManagers
            }
            else -> null
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers, trustManagers, SecureRandom())
        }
        val x509Tm = trustManagers.filterIsInstance<X509TrustManager>().first()
        builder.sslSocketFactory(sslContext.socketFactory, x509Tm)

        if (config.acceptSelfSignedCertificate) {
            // Safe to skip the hostname/CN check here: checkServerTrusted above
            // (TofuTrustManager) already pins the exact certificate fingerprint,
            // which is a strictly stronger identity guarantee than a name match.
            builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
        }

        if (config.certificatePinsSha256.isNotEmpty()) {
            val host = java.net.URI(root).host
            val pinnerBuilder = CertificatePinner.Builder()
            config.certificatePinsSha256.forEach { pin -> pinnerBuilder.add(host, pin) }
            builder.certificatePinner(pinnerBuilder.build())
        }
    }

    /**
     * Resolves [alias] against the system KeyChain once (blocking — callers
     * only reach this from [httpClient]'s lazy init, which itself only ever
     * runs on [Dispatchers.IO] because every public suspend fun on this
     * class dispatches there; [KeyChain.getPrivateKey]/[getCertificateChain]
     * explicitly forbid the main thread) and wraps the result in a minimal
     * [X509ExtendedKeyManager] that always answers with that one identity —
     * this client is one session against one server, so there's never a
     * "choose among multiple aliases mid-handshake" case to support.
     * Returns null (falling through to "no client cert") if the alias was
     * revoked/removed from the KeyChain since the user picked it.
     */
    private fun buildKeyChainKeyManagers(context: Context, alias: String): Array<KeyManager>? {
        val chain: Array<X509Certificate>? = try { KeyChain.getCertificateChain(context, alias) } catch (_: Exception) { null }
        val privateKey: PrivateKey? = try { KeyChain.getPrivateKey(context, alias) } catch (_: Exception) { null }
        if (chain == null || privateKey == null) return null
        return arrayOf(object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = alias
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = alias
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
            override fun getPrivateKey(alias: String?): PrivateKey = privateKey
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        })
    }

    private fun wireProxy(builder: OkHttpClient.Builder) {
        config.proxy?.let { proxy ->
            builder.proxy(proxy)
            if (config.proxyUsername.isNotBlank()) {
                builder.proxyAuthenticator { _, response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", okhttp3.Credentials.basic(config.proxyUsername, config.proxyPassword))
                        .build()
                }
            }
        }
    }

    private fun publishStats(latestLatencyMs: Long, resp: Response) {
        val count = requestCount.get().coerceAtLeast(1)
        _stats.value = RestconfSessionStats(
            requestCount = requestCount.get(),
            errorCount = errorCount.get(),
            bytesSent = bytesSent.get(),
            bytesReceived = bytesReceived.get(),
            lastLatencyMillis = latestLatencyMs,
            averageLatencyMillis = latencySum.get() / count,
            lastTlsVersion = resp.handshake?.tlsVersion?.javaName,
            lastProtocol = resp.protocol.toString(),
            connectedSinceEpochMillis = _stats.value.connectedSinceEpochMillis,
            reconnectCount = reconnectCount,
        )
    }

    // ── session lifecycle / health monitoring ───────────────────────────

    /**
     * Verifies reachability + auth by probing the RESTCONF root, then starts
     * a background health-monitor loop that re-probes every
     * [healthCheckIntervalSeconds] and drives [connectionState] /
     * transparently reconnects (fresh OkHttp call — the client itself is
     * stateless enough between calls that "reconnect" mostly means "the
     * next probe succeeds again", except for the connectedSinceEpochMillis/
     * reconnectCount bookkeeping this loop does).
     */
    suspend fun connect(healthCheckIntervalSeconds: Long = 30) = withContext(Dispatchers.IO) {
        _connectionState.value = RestconfConnectionState.CONNECTING
        try {
            discoverRootResource()
            _connectionState.value = RestconfConnectionState.CONNECTED
            _stats.value = _stats.value.copy(connectedSinceEpochMillis = System.currentTimeMillis())
        } catch (e: Exception) {
            _connectionState.value = RestconfConnectionState.ERROR
            throw RestconfException("Could not reach $root — check host/port, TLS settings, and credentials", cause = e)
        }
        if (healthCheckIntervalSeconds > 0) startHealthMonitor(healthCheckIntervalSeconds)
    }

    private fun startHealthMonitor(intervalSeconds: Long) {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch {
            while (isActive) {
                delay(TimeUnit.SECONDS.toMillis(intervalSeconds))
                val healthy = runCatching { probeServiceRoot() }.isSuccess
                if (!healthy && _connectionState.value == RestconfConnectionState.CONNECTED) {
                    _connectionState.value = RestconfConnectionState.RECONNECTING
                    val recovered = attemptReconnect()
                    _connectionState.value = if (recovered) RestconfConnectionState.CONNECTED else RestconfConnectionState.ERROR
                } else if (healthy && _connectionState.value != RestconfConnectionState.CONNECTED) {
                    _connectionState.value = RestconfConnectionState.CONNECTED
                }
            }
        }
    }

    /** Bounded exponential backoff (1s, 2s, 4s, 8s, capped 16s), 5 attempts, matching the retry shape already used by [com.systemsgo.hex.ssh.protocol]'s reconnect logic. */
    private suspend fun attemptReconnect(): Boolean {
        var delayMs = 1000L
        repeat(5) { attempt ->
            reconnectCount++
            if (runCatching { probeServiceRoot() }.isSuccess) return true
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(16_000L)
        }
        return false
    }

    fun disconnect() {
        healthMonitorJob?.cancel()
        _connectionState.value = RestconfConnectionState.DISCONNECTED
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    // ── discovery (RFC 8040 §3.1: /.well-known/host-meta) ───────────────

    /** Resolves the actual RESTCONF root path — RFC 8040 says look it up via host-meta, but "/restconf" is what ~every real implementation uses, so that's the fast-path default and host-meta is only consulted if it 404s. */
    suspend fun discoverRootResource(): String = withContext(Dispatchers.IO) {
        discoveredRestconfRoot?.let { return@withContext it }
        val conventional = "/restconf"
        val probe = runCatching { rawRequest("GET", conventional, accept = acceptHeaderFor(config.dataFormat)) }
        if (probe.isSuccess && probe.getOrNull()?.isSuccess == true) {
            discoveredRestconfRoot = conventional
            return@withContext conventional
        }
        val hostMeta = runCatching { rawRequest("GET", "/.well-known/host-meta") }.getOrNull()
        val link = hostMeta?.body?.let { Regex("""href=["']([^"']+)["']""").find(it)?.groupValues?.get(1) }
        val resolved = link ?: conventional
        discoveredRestconfRoot = resolved
        resolved
    }

    suspend fun probeServiceRoot(): Boolean = withContext(Dispatchers.IO) {
        val root0 = discoveredRestconfRoot ?: discoverRootResource()
        rawRequest("GET", "$root0/data", accept = acceptHeaderFor(config.dataFormat)).isSuccess
    }

    suspend fun getServerCapabilities(): RestconfServerCapabilities = withContext(Dispatchers.IO) {
        val restRoot = discoveredRestconfRoot ?: discoverRootResource()
        val resp = execute(RestconfMethod.GET, "$restRoot/data/ietf-restconf-monitoring:restconf-state/capabilities")
        val caps = extractCapabilityList(resp)
        RestconfServerCapabilities(
            restconfRoot = restRoot,
            capabilities = caps,
            supportsYangLibrary1_1 = caps.any { it.contains("yang-library:1.1") },
            supportsPatch = caps.any { it.contains(":yang-patch") },
            supportsDefaultsParam = caps.any { it.contains("with-defaults") },
            supportsFilterParam = caps.any { it.contains(":filter") },
            supportsDepthParam = caps.any { it.contains(":depth") },
        )
    }

    private fun extractCapabilityList(resp: RestconfResponse): List<String> {
        val body = resp.body ?: return emptyList()
        return runCatching {
            if (resp.detectedFormat == RestconfDataFormat.JSON) {
                val json = JsonParser.parseString(body).asJsonObject
                val arr = json.getAsJsonObject("ietf-restconf-monitoring:capabilities")
                    ?.getAsJsonArray("capability") ?: json.getAsJsonArray("capability")
                arr?.map { it.asString } ?: emptyList()
            } else {
                Regex("<capability>([^<]+)</capability>").findAll(body).map { it.groupValues[1] }.toList()
            }
        }.getOrElse { emptyList() }
    }

    /** RFC 8525 (ietf-yang-library) module list — falls back to the older RFC 7895 path for pre-1.1 servers. */
    suspend fun getYangModules(): List<YangModule> = withContext(Dispatchers.IO) {
        val restRoot = discoveredRestconfRoot ?: discoverRootResource()
        val modern = runCatching {
            execute(RestconfMethod.GET, "$restRoot/data/ietf-yang-library:yang-library/module-set")
        }.getOrNull()
        val legacy = if (modern == null || !modern.isSuccess) runCatching {
            execute(RestconfMethod.GET, "$restRoot/data/ietf-yang-library:modules-state/module")
        }.getOrNull() else null

        val resp = modern?.takeIf { it.isSuccess } ?: legacy ?: return@withContext emptyList()
        parseYangModules(resp)
    }

    private fun parseYangModules(resp: RestconfResponse): List<YangModule> {
        val body = resp.body ?: return emptyList()
        return runCatching {
            val json = JsonParser.parseString(body).asJsonObject
            val moduleSet = json.entrySet().firstOrNull()?.value
            val arr = when {
                moduleSet?.isJsonObject == true -> moduleSet.asJsonObject.getAsJsonArray("module")
                moduleSet?.isJsonArray == true -> moduleSet.asJsonArray
                else -> null
            } ?: return@runCatching emptyList()
            arr.map { el ->
                val o = el.asJsonObject
                YangModule(
                    name = o.get("name")?.asString ?: "",
                    revision = o.get("revision")?.takeIf { !it.isJsonNull }?.asString,
                    namespace = o.get("namespace")?.asString ?: "",
                    isSubmodule = false,
                )
            }
        }.getOrElse { emptyList() }
    }

    // ── generic CRUD (RFC 8040 §4: GET/POST/PUT/PATCH/DELETE on /data, plus /operations RPCs) ──

    /**
     * Full-control entry point the Request Builder (Part 2) drives directly
     * — arbitrary method/path/body/headers/query, not just the convenience
     * wrappers below. `path` is relative to the discovered RESTCONF root
     * unless it already starts with "/restconf" or "http".
     */
    suspend fun execute(
        method: RestconfMethod,
        path: String,
        body: String? = null,
        queryParams: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        dataFormat: RestconfDataFormat = config.dataFormat,
    ): RestconfResponse = withContext(Dispatchers.IO) {
        val restRoot = discoveredRestconfRoot ?: discoverRootResource()
        val fullPath = when {
            path.startsWith("http") -> path
            path.startsWith(restRoot) -> path
            path.startsWith("/") -> "$restRoot$path"
            else -> "$restRoot/$path"
        }
        val urlWithQuery = appendQuery(fullPath, queryParams)
        rawRequest(
            method = method.name,
            path = urlWithQuery,
            body = body,
            contentType = contentTypeFor(dataFormat, method),
            accept = acceptHeaderFor(dataFormat),
            extraHeaders = extraHeaders,
        )
    }

    /** Data-resource GET with RFC 8040 §4.8/4.9 query params (content, depth, fields, filter, with-defaults) plus pagination via limit/offset-style `fields`/`filter` a server may support — RESTCONF has no built-in pagination primitive, so this just passes whatever query params the caller/server-side convention wants through untouched. */
    suspend fun getData(path: String, queryParams: Map<String, String> = emptyMap()): RestconfResponse =
        execute(RestconfMethod.GET, dataPath(path), queryParams = queryParams)

    suspend fun postData(path: String, body: String): RestconfResponse =
        execute(RestconfMethod.POST, dataPath(path), body = body)

    suspend fun putData(path: String, body: String): RestconfResponse =
        execute(RestconfMethod.PUT, dataPath(path), body = body)

    /** RFC 8040 §4.6.1 plain PATCH (merge-patch style; not RFC 8072 YANG Patch — that's a POST to the same path with a yang-patch body, which callers can do via [execute] directly with `dataFormat`'s +yang-patch content type in `extraHeaders`). */
    suspend fun patchData(path: String, body: String): RestconfResponse =
        execute(RestconfMethod.PATCH, dataPath(path), body = body)

    suspend fun deleteData(path: String): RestconfResponse =
        execute(RestconfMethod.DELETE, dataPath(path))

    private fun dataPath(path: String) = if (path.startsWith("/data")) path else "/data/${path.trimStart('/')}"

    /** RFC 8040 §3.6: RPC operations and YANG actions both live under /operations (actions are addressed by their parent data node's path, same mechanism). */
    suspend fun invokeOperation(operationPath: String, inputBody: String? = null): RestconfResponse =
        execute(RestconfMethod.POST, "/operations/${operationPath.trimStart('/')}", body = inputBody ?: "{}")

    suspend fun listOperations(): List<RestconfOperation> = withContext(Dispatchers.IO) {
        val resp = runCatching { execute(RestconfMethod.GET, "/operations") }.getOrNull() ?: return@withContext emptyList()
        if (!resp.isSuccess) return@withContext emptyList()
        runCatching {
            val body = resp.body ?: return@runCatching emptyList()
            val json = JsonParser.parseString(body).asJsonObject
            val ops = json.getAsJsonObject("ietf-restconf:operations") ?: json
            ops.entrySet().map { (key, _) ->
                val parts = key.split(":", limit = 2)
                RestconfOperation(name = parts.lastOrNull() ?: key, moduleName = parts.firstOrNull() ?: "")
            }
        }.getOrElse { emptyList() }
    }

    // ── low-level HTTP ────────────────────────────────────────────────

    private fun rawRequest(
        method: String,
        path: String,
        body: String? = null,
        contentType: String? = null,
        accept: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): RestconfResponse {
        val url = if (path.startsWith("http")) path else "$root$path"
        val builder = Request.Builder().url(url)
        accept?.let { builder.header("Accept", it) }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        val requestBody: RequestBody? = body?.toRequestBody((contentType ?: "application/json").toMediaType())
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> builder.delete()
            "OPTIONS" -> builder.method("OPTIONS", null)
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody("application/json".toMediaType()))
            "PUT" -> builder.put(requestBody ?: "{}".toRequestBody("application/json".toMediaType()))
            "PATCH" -> builder.patch(requestBody ?: "{}".toRequestBody("application/json".toMediaType()))
            else -> throw IllegalArgumentException("Unsupported RESTCONF method $method")
        }
        bytesSent.addAndGet(body?.toByteArray()?.size?.toLong() ?: 0)

        val started = System.nanoTime()
        httpClient.newCall(builder.build()).execute().use { resp ->
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val bodyStr = resp.body?.string()
            return RestconfResponse(
                statusCode = resp.code,
                statusMessage = resp.message,
                headers = resp.headers.toMultimap(),
                body = bodyStr,
                contentType = resp.header("Content-Type"),
                requestUrl = url,
                requestMethod = method,
                elapsedMillis = elapsedMs,
                sizeBytes = bodyStr?.toByteArray()?.size?.toLong() ?: 0,
                tlsVersion = resp.handshake?.tlsVersion?.javaName,
                protocol = resp.protocol.toString(),
            )
        }
    }

    private fun appendQuery(path: String, params: Map<String, String>): String {
        if (params.isEmpty()) return path
        val absolute = if (path.startsWith("http")) path else "$root$path"
        val parsed = absolute.toHttpUrlOrNull() ?: return path
        val built = parsed.newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build().toString()
        // Callers pass `path` relative to `root` and expect the same back (rawRequest
        // re-prefixes with `root` itself when the result doesn't start with "http").
        return if (path.startsWith("http")) built else built.removePrefix(root)
    }

    private fun contentTypeFor(format: RestconfDataFormat, method: RestconfMethod): String {
        val suffix = if (format == RestconfDataFormat.XML) "xml" else "json"
        return when (method) {
            RestconfMethod.POST, RestconfMethod.PUT -> "application/yang-data+$suffix"
            RestconfMethod.PATCH -> "application/yang-data+$suffix" // caller overrides via extraHeaders for RFC 8072 yang-patch+$suffix
            else -> "application/yang-data+$suffix"
        }
    }

    private fun acceptHeaderFor(format: RestconfDataFormat): String {
        val suffix = if (format == RestconfDataFormat.XML) "xml" else "json"
        return "application/yang-data+$suffix, application/$suffix"
    }
}

/** Gzips request bodies for POST/PUT/PATCH when the caller opts in — response decompression is already automatic in OkHttp, this is the request-side half of the "Compression" requirement. */
private class GzipRequestInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
        val original = chain.request()
        val body = original.body
        if (body == null || original.header("Content-Encoding") != null) return chain.proceed(original)
        val compressed = original.newBuilder()
            .header("Content-Encoding", "gzip")
            .method(original.method, gzip(body))
            .build()
        return chain.proceed(compressed)
    }

    private fun gzip(body: RequestBody): RequestBody = object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = -1L // unknown once gzipped
        override fun writeTo(sink: okio.BufferedSink) {
            val gzipSink = okio.GzipSink(sink).buffer()
            body.writeTo(gzipSink)
            gzipSink.close()
        }
    }
}
