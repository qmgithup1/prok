package com.systemsgo.hex.proxmox.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.systemsgo.hex.data.model.ProxmoxAuthMode
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * PROXMOX-API FEATURE (Part 1/N): a thin REST client for the Proxmox VE
 * management API (`/api2/json/...`). Deliberately hand-rolled with plain
 * OkHttp + Gson (both already project dependencies — see app/build.gradle)
 * rather than Retrofit, same "one client class per protocol, no shared
 * generic REST framework" shape as
 * [com.systemsgo.hex.snmp.protocol.SnmpClient] and
 * [com.systemsgo.hex.redfish.protocol.RedfishClient].
 *
 * ## Authentication
 * Proxmox supports two independent auth mechanisms (see
 * https://pve.proxmox.com/pve-docs/api-viewer/ and the "API Tokens" section
 * of the admin guide):
 *  - **API token** ([ProxmoxAuthMode.TOKEN]): stateless — every request
 *    just carries `Authorization: PVEAPIToken=user@realm!tokenid=secret`.
 *    No [login] round-trip needed, no CSRF token needed (tokens are exempt
 *    from Proxmox's CSRF check by design, since they're not cookie-based).
 *  - **Realm login** ([ProxmoxAuthMode.PASSWORD]): classic ticket auth — a
 *    `POST /access/ticket` with username+password returns a ticket (used as
 *    the `PVEAuthCookie` cookie value) and a `CSRFPreventionToken` that
 *    must accompany every subsequent state-changing (POST/PUT/DELETE)
 *    request as a header. [login] performs this exchange and this class
 *    holds the resulting ticket/token in memory for the client's lifetime.
 *
 * ## TLS
 * Proxmox's built-in `pveproxy` almost always serves a self-signed
 * certificate out of the box (a real one has to be deliberately configured
 * by the admin — e.g. via ACME). [ProxmoxConnectionConfig.acceptSelfSignedCertificate]
 * defaults to `true` for that reason (unlike most of this app's other
 * protocols, where the self-signed toggle defaults to `false`) — see
 * [RdpProfile.proxmoxAcceptSelfSignedCertificate]'s doc comment.
 */
class ProxmoxApiClient(private val config: ProxmoxConnectionConfig) {

    private val baseUrl = "https://${config.host}:${config.port}/api2/json"

    /** Set by [login] in PASSWORD mode; unused (stays null) in TOKEN mode. */
    @Volatile private var authTicket: String? = null
    @Volatile private var csrfToken: String? = null

    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        if (config.acceptSelfSignedCertificate) {
            // Trust-all, same "the user explicitly opted into this on this
            // one profile" reasoning every other protocol in this app uses
            // for its own self-signed toggle — not a silent, app-wide
            // relaxation. No pinning/TOFU here (unlike RestconfClient's
            // TOFU-over-TLS approach) since Proxmox admins rotate the
            // self-signed cert on every reinstall far more often than a
            // typical NETCONF/RESTCONF target would.
            val trustAllManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustAllManager), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    /** No-op in TOKEN mode (nothing to exchange). In PASSWORD mode, performs the ticket exchange and caches the result for subsequent calls. */
    suspend fun login() = withContext(Dispatchers.IO) {
        if (config.authMode == ProxmoxAuthMode.TOKEN) return@withContext
        require(config.username.isNotBlank() && config.password.isNotBlank()) {
            "Proxmox PASSWORD auth requires both username (user@realm) and password."
        }
        val form = FormBody.Builder()
            .add("username", config.username)
            .add("password", config.password)
            .build()
        val request = Request.Builder().url("$baseUrl/access/ticket").post(form).build()
        httpClient.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ProxmoxException("Proxmox login failed: HTTP ${response.code} — ${bodyText.take(200)}")
            }
            val data = runCatching { JsonParser.parseString(bodyText).asJsonObject.getAsJsonObject("data") }
                .getOrNull() ?: throw ProxmoxException("Proxmox login response had no 'data' object.")
            authTicket = data.get("ticket")?.asString
                ?: throw ProxmoxException("Proxmox login response had no ticket.")
            csrfToken = data.get("CSRFPreventionToken")?.asString
        }
    }

    private fun applyAuth(builder: Request.Builder, isWrite: Boolean) {
        when (config.authMode) {
            ProxmoxAuthMode.TOKEN ->
                builder.addHeader("Authorization", "PVEAPIToken=${config.tokenId}=${config.tokenSecret}")
            ProxmoxAuthMode.PASSWORD -> {
                val ticket = authTicket
                    ?: throw ProxmoxException("Not logged in — call login() before making requests in PASSWORD auth mode.")
                builder.addHeader("Cookie", "PVEAuthCookie=$ticket")
                if (isWrite) {
                    csrfToken?.let { builder.addHeader("CSRFPreventionToken", it) }
                }
            }
        }
    }

    private suspend fun get(path: String): com.google.gson.JsonElement = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$baseUrl$path").get()
        applyAuth(builder, isWrite = false)
        httpClient.newCall(builder.build()).execute().use { response ->
            parseDataOrThrow(path, response)
        }
    }

    private suspend fun post(path: String, form: Map<String, String> = emptyMap()): com.google.gson.JsonElement =
        withContext(Dispatchers.IO) {
            val formBuilder = FormBody.Builder()
            form.forEach { (key, value) -> formBuilder.add(key, value) }
            val builder = Request.Builder().url("$baseUrl$path").post(formBuilder.build())
            applyAuth(builder, isWrite = true)
            httpClient.newCall(builder.build()).execute().use { response ->
                parseDataOrThrow(path, response)
            }
        }

    private fun parseDataOrThrow(path: String, response: okhttp3.Response): com.google.gson.JsonElement {
        val bodyText = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw ProxmoxException("Proxmox $path failed: HTTP ${response.code} — ${bodyText.take(300)}")
        }
        val root = runCatching { JsonParser.parseString(bodyText).asJsonObject }.getOrNull()
            ?: throw ProxmoxException("Proxmox $path returned a non-JSON response: ${bodyText.take(200)}")
        return root.get("data") ?: throw ProxmoxException("Proxmox $path response had no 'data' field.")
    }

    // ── Nodes / guests ───────────────────────────────────────────────────

    suspend fun listNodes(): List<ProxmoxNode> {
        val array = get("/nodes").asJsonArray
        return array.map { it.asJsonObject.toProxmoxNode() }.sortedBy { it.node }
    }

    /** Combines `qemu` and `lxc` for one node into a single unified list, VMs first (matches how the Proxmox web UI orders them within a node). */
    suspend fun listGuests(node: String): List<ProxmoxGuest> {
        val vms = runCatching { get("/nodes/$node/qemu").asJsonArray }.getOrNull() ?: JsonArray()
        val containers = runCatching { get("/nodes/$node/lxc").asJsonArray }.getOrNull() ?: JsonArray()
        val vmGuests = vms.map { it.asJsonObject.toProxmoxGuest(node, ProxmoxGuestType.QEMU) }
        val ctGuests = containers.map { it.asJsonObject.toProxmoxGuest(node, ProxmoxGuestType.LXC) }
        return (vmGuests + ctGuests).sortedBy { it.vmid }
    }

    // ── Power actions ────────────────────────────────────────────────────

    suspend fun powerAction(guest: ProxmoxGuest, action: ProxmoxPowerAction) {
        require(guest.type == ProxmoxGuestType.QEMU || action != ProxmoxPowerAction.SUSPEND) {
            "Suspend is only supported for QEMU VMs, not LXC containers."
        }
        require(guest.type == ProxmoxGuestType.QEMU || action != ProxmoxPowerAction.RESUME) {
            "Resume is only supported for QEMU VMs, not LXC containers."
        }
        val kind = if (guest.type == ProxmoxGuestType.QEMU) "qemu" else "lxc"
        post("/nodes/${guest.node}/$kind/${guest.vmid}/status/${action.apiPath}")
    }

    // ── Console (VNC — QEMU only; see ProxmoxManagementActivity for the LXC/SPICE follow-up) ──

    /** Fetches a one-time VNC ticket for [guest] (must be a running QEMU VM). Feed the result into [vncWebSocketUrl]. */
    suspend fun vncProxy(guest: ProxmoxGuest): ProxmoxVncTicket {
        require(guest.type == ProxmoxGuestType.QEMU) { "VNC console proxy is only available for QEMU VMs." }
        val data = post(
            "/nodes/${guest.node}/qemu/${guest.vmid}/vncproxy",
            form = mapOf("websocket" to "1"),
        ).asJsonObject
        val ticket = data.get("ticket")?.asString ?: throw ProxmoxException("vncproxy response had no ticket.")
        val port = data.get("port")?.asString?.toIntOrNull()
            ?: throw ProxmoxException("vncproxy response had no numeric port.")
        return ProxmoxVncTicket(ticket = ticket, port = port)
    }

    /**
     * Builds the `wss://` URL Proxmox's noVNC frontend itself connects to —
     * carries raw RFB bytes framed as binary WebSocket messages, no
     * additional protocol on top. See
     * [com.systemsgo.hex.proxmox.ProxmoxVncBridge] for the loopback bridge
     * that lets this app's existing (plain-TCP) VNC engine consume it.
     *
     * NOTE (documented, not yet resolved — Proxmox forum reports differ by
     * version): the WebSocket *handshake* itself has been reported to
     * additionally require a valid `PVEAuthCookie`, separate from the
     * `vncticket` query param, on some Proxmox versions. [ProxmoxVncBridge]
     * sends both the ticket query param *and* this client's normal auth
     * headers (Authorization for TOKEN mode, Cookie for PASSWORD mode) on
     * the handshake request to cover both cases; if a specific Proxmox
     * version still rejects a TOKEN-mode console handshake, that's the
     * first thing to check.
     */
    fun vncWebSocketUrl(guest: ProxmoxGuest, ticket: ProxmoxVncTicket): String {
        require(guest.type == ProxmoxGuestType.QEMU) { "VNC console is only available for QEMU VMs." }
        val encodedTicket = URLEncoder.encode(ticket.ticket, "UTF-8")
        return "wss://${config.host}:${config.port}/api2/json/nodes/${guest.node}/qemu/${guest.vmid}" +
            "/vncwebsocket?port=${ticket.port}&vncticket=$encodedTicket"
    }

    /** Headers [ProxmoxVncBridge] should replay on the WebSocket handshake — see [vncWebSocketUrl]'s note. */
    fun consoleAuthHeaders(): Map<String, String> = buildMap {
        when (config.authMode) {
            ProxmoxAuthMode.TOKEN -> put("Authorization", "PVEAPIToken=${config.tokenId}=${config.tokenSecret}")
            ProxmoxAuthMode.PASSWORD -> authTicket?.let { put("Cookie", "PVEAuthCookie=$it") }
        }
    }

    /** Releases pooled connections/threads — call when the management screen is done with this client (e.g. onCleared()). */
    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    private fun JsonObject.toProxmoxNode(): ProxmoxNode = ProxmoxNode(
        node = optString("node"),
        status = optString("status", "unknown"),
        cpuFraction = optDouble("cpu"),
        maxCpu = optInt("maxcpu"),
        memUsedBytes = optLong("mem"),
        memMaxBytes = optLong("maxmem"),
        uptimeSeconds = optLong("uptime"),
    )

    private fun JsonObject.toProxmoxGuest(node: String, type: ProxmoxGuestType): ProxmoxGuest = ProxmoxGuest(
        vmid = optInt("vmid"),
        name = optString("name").ifBlank { "$type-${optInt("vmid")}" },
        node = node,
        type = type,
        status = optString("status", "unknown"),
        cpuFraction = optDouble("cpu"),
        maxCpu = optInt("cpus").takeIf { it > 0 } ?: optInt("maxcpu"),
        memUsedBytes = optLong("mem"),
        memMaxBytes = optLong("maxmem"),
        uptimeSeconds = optLong("uptime"),
        template = optInt("template") == 1,
    )

    // Small Gson helpers — Proxmox's API mixes numeric and string
    // representations of the same logical field across endpoints/versions
    // (e.g. "port" comes back as a JSON string from vncproxy but nodes'
    // numeric fields come back as actual JSON numbers), so these stay
    // defensive rather than assuming one JSON type per field.
    private fun JsonObject.optString(key: String, default: String = ""): String =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull() ?: default
    private fun JsonObject.optInt(key: String, default: Int = 0): Int =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asInt }.getOrNull() ?: default
    private fun JsonObject.optLong(key: String, default: Long = 0L): Long =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asLong }.getOrNull() ?: default
    private fun JsonObject.optDouble(key: String, default: Double = 0.0): Double =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asDouble }.getOrNull() ?: default
}
