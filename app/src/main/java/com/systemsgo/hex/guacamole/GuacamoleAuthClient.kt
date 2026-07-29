package com.systemsgo.hex.guacamole

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.systemsgo.hex.security.openEncryptedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * GUACAMOLE-PROTOCOL FEATURE (Part 1/N).
 *
 * How a connection reaches this app once it's been resolved down to a base
 * URL — see [GuacamoleAuthClient]'s class doc for how the URL is built.
 */
data class GuacamoleServerConfig(
    val baseUrl: String, // e.g. "https://guac.example.com/guacamole" — WITHOUT a trailing slash, WITHOUT "/api"
    val acceptSelfSignedCertificate: Boolean = false,
)

/** Result of a successful `POST /api/tokens` call. Field names match the Guacamole REST API's JSON response verbatim. */
data class GuacamoleAuthResult(
    @SerializedName("authToken") val authToken: String,
    @SerializedName("username") val username: String,
    @SerializedName("dataSource") val dataSource: String,
    @SerializedName("availableDataSources") val availableDataSources: List<String> = emptyList(),
)

/** One entry from `GET /api/session/data/{dataSource}/connections`. */
data class GuacamoleConnection(
    @SerializedName("identifier") val identifier: String,
    @SerializedName("name") val name: String,
    @SerializedName("parentIdentifier") val parentIdentifier: String? = null,
    @SerializedName("protocol") val protocol: String? = null, // e.g. "rdp", "vnc", "ssh", "telnet", "kubernetes"
    @SerializedName("activeConnections") val activeConnections: Int = 0,
    @SerializedName("attributes") val attributes: Map<String, String?> = emptyMap(),
)

/** One entry from `GET /api/session/data/{dataSource}/connectionGroups` — see reg.txt's "Connection groups" requirement. */
data class GuacamoleConnectionGroup(
    @SerializedName("identifier") val identifier: String,
    @SerializedName("name") val name: String,
    @SerializedName("parentIdentifier") val parentIdentifier: String? = null,
    @SerializedName("type") val type: String = "ORGANIZATIONAL", // or "BALANCING"
)

sealed class GuacamoleApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 403 on `/api/tokens` — bad username/password/OTP. Maps directly to reg.txt's "Authentication failed" error state. */
    class AuthenticationFailed(message: String) : GuacamoleApiException(message)
    /** Any non-2xx other than the auth-specific 403, or a response that didn't parse — "Permission denied" / "Protocol unavailable" etc. are disambiguated by [httpCode]. */
    class RequestFailed(val httpCode: Int, message: String) : GuacamoleApiException(message)
    /** DNS/connect/TLS failure reaching [GuacamoleServerConfig.baseUrl] at all — "Server unreachable". */
    class Unreachable(message: String, cause: Throwable) : GuacamoleApiException(message, cause)
}

/**
 * Talks to the Guacamole web application's REST API — NOT guacd directly,
 * and NOT the WebSocket tunnel (see [com.systemsgo.hex.guacamole.protocol.GuacamoleTunnelClient]
 * for that). This is the same REST API the Guacamole web UI itself is built
 * on (https://guacamole.apache.org/doc/gug/api-documentation.html),
 * responsible for reg.txt's CONNECTION MANAGEMENT section: authenticating
 * and discovering which connections/groups a user is allowed to launch,
 * before any tunnel is opened.
 *
 * [GuacamoleServerConfig.baseUrl] must already include Guacamole's context
 * path if the deployment uses a non-default one (reg.txt's "Custom context
 * paths" requirement) — e.g. `https://host/guacamole` or
 * `https://host/my-custom-path`, exactly as a browser would reach the
 * Guacamole login page. Reverse-proxy deployments (also called out in
 * reg.txt) work transparently here since this is plain HTTPS with no
 * assumptions about what's in front of it.
 */
class GuacamoleAuthClient(
    private val config: GuacamoleServerConfig,
    private val gson: Gson = Gson(),
    // GUACAMOLE-PROTOCOL FEATURE (Part 6/N): required (and only used) when
    // config.acceptSelfSignedCertificate is on — in that case TLS trust for
    // this REST client uses silent TOFU (pin on first contact, hard-reject
    // on a later fingerprint change) instead of a blind trust-all. NOT the
    // same interactive prompt GuacamoleCertificateVerifier gives the tunnel
    // connection — see that class's doc comment for exactly why this leg
    // can't use the interactive flow (it runs before any
    // RemoteSessionClient/UI surface exists to show the prompt on) and why
    // the two legs use separate pinned-fingerprint stores.
    //
    // SECURITY (GUAC-REST-TOFU-SCOPE): when acceptSelfSignedCertificate is
    // OFF (the default), this parameter is not consulted at all — the
    // client uses OkHttp's normal platform CA trust store + hostname
    // verification, exactly like any other HTTPS client. A non-null
    // appContext alone is NOT enough to switch this REST leg to TOFU; see
    // applyTrustPolicy().
    private val appContext: android.content.Context? = null,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
        .let { applyTrustPolicy(it) }

    /**
     * `POST /api/tokens` with username/password. For an OAuth/OpenID token
     * obtained out-of-band, see [loginWithExternalToken] instead.
     */
    suspend fun login(username: String, password: String): GuacamoleAuthResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .build()
        val request = Request.Builder()
            .url("${config.baseUrl}/api/tokens")
            .post(body)
            .build()
        executeAndParse(request, GuacamoleAuthResult::class.java, isAuthCall = true)
    }

    /**
     * OAuth/OpenID → `POST /api/tokens` for a caller that already holds an
     * ID token from the identity provider (reg.txt's "OAuth/OpenID session
     * token if supported").
     *
     * IMPORTANT CAVEAT: this sends the token under an `id_token` form field
     * (and `state` when the IdP round-trip provided one), matching the
     * guacamole-auth-openid extension's documented request shape at the
     * time of writing — but that extension's exact parameter name(s) can
     * differ across Guacamole versions and configurations, and this has
     * NOT been verified against a live OpenID-configured Guacamole server.
     * Treat this as a starting point to confirm/adjust against your actual
     * deployment, not a guaranteed-working integration.
     *
     * Also NOTE what this class does NOT do: obtain the [idToken] in the
     * first place. That requires an interactive browser (Custom Tabs/
     * WebView) redirect to the identity provider, which is provider- and
     * security-config-specific (PKCE, redirect URI allowlisting, etc.) and
     * isn't implemented here — see [GuacamoleRepository.loginWithExternalToken]'s
     * doc comment for why that's a deliberate stopping point rather than an
     * oversight.
     */
    suspend fun loginWithExternalToken(idToken: String, state: String? = null): GuacamoleAuthResult = withContext(Dispatchers.IO) {
        val bodyBuilder = FormBody.Builder().add("id_token", idToken)
        state?.let { bodyBuilder.add("state", it) }
        val request = Request.Builder()
            .url("${config.baseUrl}/api/tokens")
            .post(bodyBuilder.build())
            .build()
        executeAndParse(request, GuacamoleAuthResult::class.java, isAuthCall = true)
    }

    /** `DELETE /api/tokens/{token}` — reg.txt's "Logout". Best-effort: failures here shouldn't block the app from discarding its local session state. */
    suspend fun logout(authToken: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${config.baseUrl}/api/tokens/$authToken")
            .delete()
            .build()
        try {
            client.newCall(request).execute().close()
        } catch (_: IOException) {
            // Best-effort — see doc comment above.
        }
    }

    /** `GET /api/session/data/{dataSource}/connections` — reg.txt's "Available connections". */
    suspend fun listConnections(dataSource: String, authToken: String): List<GuacamoleConnection> =
        withContext(Dispatchers.IO) {
            val request = authedGet("/api/session/data/$dataSource/connections", authToken)
            val type = object : com.google.gson.reflect.TypeToken<Map<String, GuacamoleConnection>>() {}.type
            val map: Map<String, GuacamoleConnection> = executeAndParse(request, type)
            map.values.toList()
        }

    /** `GET /api/session/data/{dataSource}/connectionGroups` — reg.txt's "Connection groups". */
    suspend fun listConnectionGroups(dataSource: String, authToken: String): List<GuacamoleConnectionGroup> =
        withContext(Dispatchers.IO) {
            val request = authedGet("/api/session/data/$dataSource/connectionGroups", authToken)
            val type = object : com.google.gson.reflect.TypeToken<Map<String, GuacamoleConnectionGroup>>() {}.type
            val map: Map<String, GuacamoleConnectionGroup> = executeAndParse(request, type)
            map.values.toList()
        }

    private fun authedGet(path: String, authToken: String): Request =
        Request.Builder()
            .url("${config.baseUrl}$path?token=$authToken")
            .get()
            .build()

    private fun <T> executeAndParse(request: Request, javaType: java.lang.reflect.Type, isAuthCall: Boolean = false): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw GuacamoleApiException.Unreachable("Could not reach ${request.url.host}: ${e.message}", e)
        }
        response.use {
            val bodyString = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                if (isAuthCall && (it.code == 403 || it.code == 401)) {
                    throw GuacamoleApiException.AuthenticationFailed(extractGuacMessage(bodyString) ?: "Invalid credentials")
                }
                throw GuacamoleApiException.RequestFailed(it.code, extractGuacMessage(bodyString) ?: "HTTP ${it.code}")
            }
            return try {
                gson.fromJson(bodyString, javaType)
            } catch (e: Exception) {
                throw GuacamoleApiException.RequestFailed(it.code, "Malformed response from server: ${e.message}")
            }
        }
    }

    /** Guacamole's REST API error bodies look like `{"message": "...", "translatableMessage": {...}, "type": "..."}`. */
    private fun extractGuacMessage(body: String): String? = try {
        gson.fromJson(body, Map::class.java)?.get("message") as? String
    } catch (_: Exception) {
        null
    }

    private fun applyTrustPolicy(base: OkHttpClient): OkHttpClient {
        // SECURITY FIX (GUAC-REST-TOFU-SCOPE): this used to build the silent
        // TOFU trust manager below for *every* connection with a non-null
        // appContext — which GuacamoleRepository always supplies in
        // production — regardless of config.acceptSelfSignedCertificate.
        // That meant this REST leg (the one that POSTs the user's actual
        // username/password to /api/tokens) never validated the server's
        // certificate against the platform CA trust store at all, even for
        // profiles where the person never opted into "accept self-signed
        // certificate": a MITM attacker present only for the very first
        // connection to a given host:port could hand back any certificate,
        // have it silently accepted and pinned with zero prompt or warning,
        // and receive the login credentials directly. Every comparable
        // client in this app (RdWebFeedClient, RdpWebSocketTransport, ...)
        // only substitutes TOFU/lenient trust in place of the system trust
        // manager when acceptSelfSignedCertificate is actually on — this
        // now matches that same gating.
        if (config.acceptSelfSignedCertificate) {
            val context = appContext
                ?: throw IllegalStateException(
                    "acceptSelfSignedCertificate is on for '${config.baseUrl}' but no appContext was " +
                        "supplied to GuacamoleAuthClient — TOFU certificate pinning requires a Context to " +
                        "store the pinned fingerprint. Refusing to connect with a trust-all fallback. " +
                        "Pass appContext to GuacamoleAuthClient's constructor.",
                )
            val url = config.baseUrl.toHttpUrl()
            val trustManager = silentTofuTrustManager(context, url.host, url.port)
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), java.security.SecureRandom())
            }
            return base.newBuilder().sslSocketFactory(sslContext.socketFactory, trustManager).build()
        }
        // Normal case: acceptSelfSignedCertificate is off, so this leg must
        // get the same protection any ordinary HTTPS client gets — full
        // chain-of-trust validation against the platform's CA store and
        // hostname verification, both left at OkHttp's defaults by simply
        // not touching sslSocketFactory/hostnameVerifier here.
        return base
    }

    /** Silent TOFU — see this class's `appContext` constructor param doc for why there's no user prompt on this leg. */
    private fun silentTofuTrustManager(context: android.content.Context, host: String, port: Int): javax.net.ssl.X509TrustManager {
        val prefs = context.openEncryptedPrefs(PREFS_TOFU_GUACAMOLE_REST)
        val key = "$host:$port"
        return object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull()
                    ?: throw java.security.cert.CertificateException("No server certificate presented")
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                    .joinToString(":") { "%02X".format(it) }
                val stored = prefs.getString(key, null)
                if (stored == null) {
                    prefs.edit().putString(key, fingerprint).commit() // First contact — pin silently, see class doc.
                } else if (stored != fingerprint) {
                    throw java.security.cert.CertificateException(
                        "Guacamole server identity changed for $key — possible MITM. " +
                        "Clear this profile's trusted certificate and reconnect if the certificate was legitimately renewed."
                    )
                }
            }
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
    }

    private companion object {
        const val PREFS_TOFU_GUACAMOLE_REST = "guacamole_tofu_rest_certs"
    }
}
