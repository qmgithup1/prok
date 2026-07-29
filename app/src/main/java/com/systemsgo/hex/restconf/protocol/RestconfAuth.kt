package com.systemsgo.hex.restconf.protocol

import com.systemsgo.hex.data.model.RestconfAuthType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * RESTCONF FEATURE (Part 1/4): every auth mechanism the requirements list
 * maps to exactly one of these, composed onto the client's [OkHttpClient]
 * in [RestconfClient] — kept out of RestconfClient.kt itself so that file
 * stays about the RESTCONF wire protocol, not HTTP auth plumbing.
 *
 * BASIC / API_KEY / BEARER_TOKEN / JWT / CUSTOM_HEADER are all "attach a
 * header up front" and share one [Interceptor]. DIGEST needs a real
 * challenge/response round trip so it's an [Authenticator] (OkHttp only
 * gives you the 401 + WWW-Authenticate header inside that interface).
 * OAUTH2 needs a token fetch + cache + refresh-on-401, so it gets its own
 * class with internal state. CLIENT_CERTIFICATE / MUTUAL_TLS are TLS-layer,
 * not HTTP-layer — see [RestconfClient.buildHttpClient]'s sslSocketFactory
 * wiring, not this file.
 */
internal object RestconfAuth {

    /** Static, up-front header auth: Basic/Bearer/JWT/API-Key/Custom — everything that doesn't need a challenge round trip. */
    fun headerInterceptor(config: RestconfConnectionConfig): Interceptor = Interceptor { chain ->
        val builder = chain.request().newBuilder()
        when (config.authType) {
            RestconfAuthType.BASIC -> builder.header("Authorization", Credentials.basic(config.username, config.password))
            RestconfAuthType.BEARER_TOKEN -> builder.header("Authorization", "Bearer ${config.bearerToken}")
            RestconfAuthType.JWT -> builder.header("Authorization", "Bearer ${config.jwtToken}")
            RestconfAuthType.API_KEY -> builder.header(config.apiKeyHeaderName, config.apiKeyValue)
            RestconfAuthType.CUSTOM_HEADER -> config.customHeaders.forEach { (k, v) -> builder.header(k, v) }
            // DIGEST is handled by an Authenticator (needs the 401 challenge first).
            // OAUTH2 is handled by OAuth2Interceptor (needs a token-endpoint round trip).
            // CLIENT_CERTIFICATE / MUTUAL_TLS / NONE need no request header at all.
            RestconfAuthType.DIGEST, RestconfAuthType.OAUTH2,
            RestconfAuthType.CLIENT_CERTIFICATE, RestconfAuthType.MUTUAL_TLS, RestconfAuthType.NONE -> Unit
        }
        // Custom headers apply on top of any other auth type too (e.g. an API gateway
        // that wants both Basic auth AND a tenant header) — union, not either/or.
        if (config.authType != RestconfAuthType.CUSTOM_HEADER) {
            config.customHeaders.forEach { (k, v) -> builder.header(k, v) }
        }
        chain.proceed(builder.build())
    }

    /**
     * RFC 7616/2617 HTTP Digest. Stateless per attempt: parses the server's
     * `WWW-Authenticate: Digest ...` challenge from the 401 and computes a
     * fresh response — no preemptive digest (RFC forbids it; you need the
     * server's nonce first). `qop=auth` and legacy no-qop servers are both
     * supported; `qop=auth-int` is not (would require hashing the request
     * body into HA2, rare in practice for RESTCONF devices and adds a body-
     * buffering requirement this client doesn't otherwise need).
     */
    fun digestAuthenticator(username: String, password: String): Authenticator = object : Authenticator {
        private val nc = AtomicInteger(0)

        override fun authenticate(route: Route?, response: Response): Request? {
            // Don't loop forever against a server that keeps rejecting us.
            if (responseCount(response) >= 3) return null

            val challenge = response.header("WWW-Authenticate") ?: return null
            if (!challenge.startsWith("Digest", ignoreCase = true)) return null
            val params = parseChallenge(challenge)
            val realm = params["realm"] ?: return null
            val nonce = params["nonce"] ?: return null
            val qop = params["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
            val opaque = params["opaque"]
            val algorithm = (params["algorithm"] ?: "MD5").uppercase().removeSuffix("-SESS")

            val req = response.request
            val method = req.method
            val uri = req.url.encodedPath + if (req.url.encodedQuery != null) "?${req.url.encodedQuery}" else ""
            val cnonce = randomHex(16)
            val ncValue = String.format("%08x", nc.incrementAndGet())

            fun h(s: String): String = digestHex(algorithm, s)

            val ha1 = h("$username:$realm:$password")
            val ha2 = h("$method:$uri")
            val responseHash = if (qop != null) {
                h("$ha1:$nonce:$ncValue:$cnonce:$qop:$ha2")
            } else {
                h("$ha1:$nonce:$ha2")
            }

            val headerValue = buildString {
                append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$responseHash\"")
                if (algorithm != "MD5") append(", algorithm=$algorithm")
                opaque?.let { append(", opaque=\"$it\"") }
                if (qop != null) append(", qop=$qop, nc=$ncValue, cnonce=\"$cnonce\"")
            }
            return req.newBuilder().header("Authorization", headerValue).build()
        }

        private fun responseCount(response: Response): Int {
            var count = 1
            var prior = response.priorResponse
            while (prior != null) { count++; prior = prior.priorResponse }
            return count
        }
    }

    private fun parseChallenge(header: String): Map<String, String> {
        val body = header.removePrefix("Digest").trim()
        val out = mutableMapOf<String, String>()
        // key=value or key="value", comma-separated — tolerant of commas inside quoted values (qop lists).
        val regex = Regex("""(\w+)=(?:"([^"]*)"|([^,]*))""")
        for (m in regex.findAll(body)) {
            val key = m.groupValues[1]
            val value = if (m.groupValues[2].isNotEmpty()) m.groupValues[2] else m.groupValues[3].trim()
            out[key] = value
        }
        return out
    }

    private fun digestHex(algorithm: String, input: String): String {
        val algo = when (algorithm) {
            "SHA-256", "SHA256" -> "SHA-256"
            else -> "MD5"
        }
        val digest = MessageDigest.getInstance(algo).digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /**
     * OAuth2 client-credentials flow: exchanges [RestconfConnectionConfig]'s
     * oauth2ClientId/Secret for a bearer token at oauth2TokenUrl, caches it,
     * and transparently refetches on expiry or a single 401. Resource-owner
     * / auth-code flows need a browser redirect this headless client can't
     * do, so client-credentials is what's supported here — the flow every
     * machine-to-machine RESTCONF integration actually uses in practice.
     */
    class OAuth2Interceptor(
        private val config: RestconfConnectionConfig,
        private val tokenClient: OkHttpClient,
    ) : Interceptor {
        private val mutex = Mutex()
        @Volatile private var cachedToken: String? = null
        @Volatile private var expiresAtEpochMillis: Long = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            val token = runBlocking { getValidToken() }
            val req = chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            val resp = chain.proceed(req)
            if (resp.code == 401) {
                // token may have just expired server-side; force one refresh and retry once
                resp.close()
                val fresh = runBlocking { getValidToken(forceRefresh = true) }
                val retryReq = chain.request().newBuilder().header("Authorization", "Bearer $fresh").build()
                return chain.proceed(retryReq)
            }
            return resp
        }

        private suspend fun getValidToken(forceRefresh: Boolean = false): String = mutex.withLock {
            val now = System.currentTimeMillis()
            val cached = cachedToken
            if (!forceRefresh && cached != null && now < expiresAtEpochMillis) return cached
            val formBuilder = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", config.oauth2ClientId)
                .add("client_secret", config.oauth2ClientSecret)
            if (config.oauth2Scope.isNotBlank()) formBuilder.add("scope", config.oauth2Scope)
            val req = Request.Builder().url(config.oauth2TokenUrl).post(formBuilder.build()).build()
            tokenClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw RestconfException("OAuth2 token request failed: HTTP ${resp.code}", httpStatus = resp.code)
                }
                val bodyStr = resp.body?.string().orEmpty()
                val json = com.google.gson.JsonParser.parseString(bodyStr).asJsonObject
                val accessToken = json.get("access_token")?.asString
                    ?: throw RestconfException("OAuth2 token response missing access_token")
                val expiresIn = json.get("expires_in")?.takeIf { !it.isJsonNull }?.asLong ?: 3600L
                cachedToken = accessToken
                expiresAtEpochMillis = now + (expiresIn * 1000) - 5_000 // refresh 5s early
                return accessToken
            }
        }
    }
}
