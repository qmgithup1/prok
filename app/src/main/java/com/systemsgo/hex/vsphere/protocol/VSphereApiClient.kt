package com.systemsgo.hex.vsphere.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.systemsgo.hex.data.model.VSphereApiMode
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * VMWARE-VSPHERE FEATURE (Part 2/N): a thin REST client for the vSphere
 * Automation API (vCenter 7+'s `/api/...` mount) — same "hand-rolled OkHttp
 * + Gson, one client class per protocol, no shared generic REST framework"
 * shape as [com.systemsgo.hex.proxmox.protocol.ProxmoxApiClient] and
 * [com.systemsgo.hex.redfish.protocol.RedfishClient].
 *
 * Only [VSphereApiMode.REST] is implemented here. [VSphereApiMode.SOAP]
 * (needed against a standalone ESXi host with no vCenter in front of it, or
 * a pre-7.0 vCenter whose REST coverage is much thinner — see
 * [VSphereConnectionConfig.apiMode]'s doc comment) would mean a full vim25
 * SOAP/WSDL client: a different wire format entirely (XML envelopes, not
 * JSON), not a variant of this class. That's out of scope for this pass —
 * every public method below throws [VSphereException] immediately when
 * [VSphereConnectionConfig.apiMode] is SOAP, rather than silently
 * misbehaving against a REST-only implementation.
 *
 * ## Authentication
 * `POST /api/session` with HTTP Basic auth carried on the request itself
 * (not a form body — the vSphere Automation API takes credentials as a
 * standard `Authorization: Basic ...` header on this one bootstrap call)
 * returns a session token as a bare JSON string in the response body (the
 * quoted string itself, e.g. `"09827812-...-1234"`). Every subsequent
 * request carries it back as a `vmware-api-session-id` header. See
 * https://developer.vmware.com/apis/vsphere-automation/latest/cis/api/session/post/.
 *
 * ## TLS
 * vCenter/ESXi ship with a self-signed certificate out of the box unless
 * the admin deliberately replaced it — same reasoning
 * `ProxmoxApiClient.buildHttpClient()` documents for Proxmox's own toggle;
 * see [com.systemsgo.hex.data.model.RdpProfile.vsphereAcceptSelfSignedCertificate]'s
 * doc comment.
 *
 * ## A note on accuracy
 * The endpoint paths and JSON field names below (`power_state`,
 * `memory_size_MiB`, `guest_OS`, the `console/tickets` request/response
 * shape, etc.) are transcribed from VMware's public vSphere Automation API
 * reference rather than exercised against a live vCenter/ESXi host from
 * this environment (no network egress here — see this feature's
 * conversation history). They should hold for a current vCenter 7+/8, but
 * if a field comes back under a slightly different key on a specific
 * version, [optString]/[optInt]/[optLong] below fail soft (empty/zero
 * default) rather than throwing, so a naming mismatch shows up as a blank
 * field in the UI rather than a crash — worth spot-checking against a real
 * host before this ships.
 */
class VSphereApiClient(private val config: VSphereConnectionConfig) {

    private val apiMode = runCatching { VSphereApiMode.valueOf(config.apiMode) }.getOrDefault(VSphereApiMode.REST)
    private val baseUrl = "https://${config.host}:${config.port}/api"

    /** Set by [login]; every other method requires it. */
    @Volatile private var sessionId: String? = null

    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        if (config.acceptSelfSignedCertificate) {
            // Trust-all, same "the user explicitly opted into this on this
            // one profile" reasoning every other protocol in this app uses
            // for its own self-signed toggle — see ProxmoxApiClient's
            // identical block for the fuller version of this note.
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

    private fun requireRest() {
        if (apiMode != VSphereApiMode.REST) {
            throw VSphereException(
                "SOAP mode (vim25) isn't implemented yet in this app — only REST " +
                    "(vSphere 6.7+/vCenter 7's /api) is supported so far. Switch this " +
                    "profile's API mode to REST, or connect through a vCenter 7+ placed " +
                    "in front of this host if one is available."
            )
        }
    }

    /** Performs the session-token exchange and caches the result. Call once before any other method. */
    suspend fun login() = withContext(Dispatchers.IO) {
        requireRest()
        require(config.username.isNotBlank()) { "vSphere login requires a username." }
        val request = Request.Builder()
            .url("$baseUrl/session")
            .post("".toRequestBody(null))
            .addHeader("Authorization", Credentials.basic(config.username, config.password))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val bodyText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw VSphereException("vSphere login failed: HTTP ${response.code} — ${bodyText.take(200)}")
            }
            // Bare JSON string response (quotes included in the body) — asString unwraps them.
            sessionId = runCatching { JsonParser.parseString(bodyText).asString }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: throw VSphereException("vSphere login response wasn't a session-id string: ${bodyText.take(200)}")
        }
    }

    /** Best-effort server-side session teardown (`DELETE /api/session`) — failures are swallowed since the client is being torn down either way. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val id = sessionId ?: return@withContext
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/session")
                .delete()
                .addHeader("vmware-api-session-id", id)
                .build()
            httpClient.newCall(request).execute().close()
        }
        sessionId = null
    }

    private fun authedRequestBuilder(url: String): Request.Builder {
        val id = sessionId ?: throw VSphereException("Not logged in — call login() before making requests.")
        return Request.Builder().url(url).addHeader("vmware-api-session-id", id)
    }

    private suspend fun get(path: String): JsonElement = withContext(Dispatchers.IO) {
        requireRest()
        val request = authedRequestBuilder("$baseUrl$path").get().build()
        httpClient.newCall(request).execute().use { response -> parseOrThrow(path, response) }
    }

    /** [jsonBody] defaults to an empty object — most of this API's action-style POSTs (power ops, console tickets with a fixed type) don't need a real payload beyond that. */
    private suspend fun post(path: String, jsonBody: String = "{}"): JsonElement = withContext(Dispatchers.IO) {
        requireRest()
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val request = authedRequestBuilder("$baseUrl$path").post(body).build()
        httpClient.newCall(request).execute().use { response -> parseOrThrow(path, response) }
    }

    private fun parseOrThrow(path: String, response: Response): JsonElement {
        val bodyText = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw VSphereException("vSphere $path failed: HTTP ${response.code} — ${bodyText.take(300)}")
        }
        // Several action-style endpoints (power ops in particular) return an
        // empty 204/200 body on success — that's not an error, just nothing
        // for the caller to parse.
        if (bodyText.isBlank()) return JsonNull.INSTANCE
        return runCatching { JsonParser.parseString(bodyText) }.getOrNull()
            ?: throw VSphereException("vSphere $path returned a non-JSON response: ${bodyText.take(200)}")
    }

    // ── Inventory ─────────────────────────────────────────────────────────

    /** `GET /api/vcenter/host`, scoped to [VSphereConnectionConfig.datacenter] when set. */
    suspend fun listHosts(): List<VSphereHost> {
        val path = "/vcenter/host" + datacenterQuery(first = true)
        val array = get(path).asJsonArray
        return array.map { it.asJsonObject.toVSphereHost() }.sortedBy { it.name }
    }

    /**
     * `GET /api/vcenter/vm`, scoped to [VSphereConnectionConfig.datacenter]
     * when set. The list endpoint only returns moref/name/power_state/
     * cpu_count/memory_size_MiB — guest-OS/Tools-reported fields need the
     * separate per-VM [vmDetail] call, mirroring the vSphere Automation
     * API's own list-vs-detail split.
     */
    suspend fun listVms(): List<VSphereVm> {
        val path = "/vcenter/vm" + datacenterQuery(first = true)
        val array = get(path).asJsonArray
        return array.map { it.asJsonObject.toVSphereVmSummary() }.sortedBy { it.name }
    }

    /** `GET /api/vcenter/vm/{vm}` — see [listVms]'s doc for why this is a separate call. */
    suspend fun vmDetail(moref: String): VSphereVm {
        val obj = get("/vcenter/vm/${moref.urlEncode()}").asJsonObject
        return obj.toVSphereVmDetail(moref)
    }

    private fun datacenterQuery(first: Boolean): String =
        if (config.datacenter.isBlank()) "" else "${if (first) "?" else "&"}filter.datacenters=${config.datacenter.urlEncode()}"

    // ── Power actions ────────────────────────────────────────────────────

    /**
     * START/STOP/SUSPEND are hypervisor-level power ops (`/power?action=`) —
     * they work even without VMware Tools running in the guest, same as
     * pulling/plugging a physical machine's power. SHUTDOWN/RESET are
     * graceful in-guest requests routed through VMware Tools
     * (`/guest/power?action=`) and fail if Tools isn't running — mirrors
     * [com.systemsgo.hex.proxmox.protocol.ProxmoxApiClient.powerAction]'s
     * own start/shutdown/stop/reboot distinction for Proxmox.
     */
    suspend fun powerAction(vm: VSphereVm, action: VSpherePowerAction) {
        val moref = vm.moref.urlEncode()
        val path = when (action) {
            VSpherePowerAction.START -> "/vcenter/vm/$moref/power?action=start"
            VSpherePowerAction.STOP -> "/vcenter/vm/$moref/power?action=stop"
            VSpherePowerAction.SUSPEND -> "/vcenter/vm/$moref/power?action=suspend"
            VSpherePowerAction.SHUTDOWN -> "/vcenter/vm/$moref/guest/power?action=shutdown"
            VSpherePowerAction.RESET -> "/vcenter/vm/$moref/guest/power?action=reboot"
        }
        post(path)
    }

    // ── Console ──────────────────────────────────────────────────────────

    /**
     * `POST /api/vcenter/vm/{vm}/console/tickets` (vSphere 7+ REST — see
     * [VSphereConsoleTicket]'s doc comment for the SOAP
     * `AcquireWebMksTicket` equivalent this client doesn't implement, see
     * this class's own doc comment). The response's `ticket` field is
     * already the full `wss://` URL to connect to, not a bare token — see
     * [VSphereConsoleTicket.webSocketUrl]'s doc.
     *
     * Returns the ticket only; feeding it into an actual WebMKS-over-VNC
     * bridge (mirroring
     * [com.systemsgo.hex.proxmox.ProxmoxVncBridge]'s role for Proxmox's own
     * VNC-proxy ticket) and the management-screen UI around it are Part
     * 3/N, not built yet.
     */
    suspend fun acquireConsoleTicket(vm: VSphereVm): VSphereConsoleTicket {
        val data = post(
            "/vcenter/vm/${vm.moref.urlEncode()}/console/tickets",
            jsonBody = """{"type":"WEBMKS"}""",
        ).asJsonObject
        val ticket = data.optString("ticket").takeIf { it.isNotBlank() }
            ?: throw VSphereException("console/tickets response had no ticket.")
        return VSphereConsoleTicket(ticket = ticket, webSocketUrl = ticket)
    }

    /** Releases pooled connections/threads — call when the management screen is done with this client (e.g. onCleared()). Does not perform the [logout] round-trip itself; call that first if a clean server-side session teardown matters. */
    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    // ── JSON mapping ─────────────────────────────────────────────────────

    private fun JsonObject.toVSphereHost(): VSphereHost = VSphereHost(
        moref = optString("host"),
        name = optString("name"),
        connectionState = optString("connection_state", "UNKNOWN"),
        powerState = optString("power_state", "UNKNOWN"),
    )

    private fun JsonObject.toVSphereVmSummary(): VSphereVm = VSphereVm(
        moref = optString("vm"),
        name = optString("name"),
        powerState = optString("power_state", "UNKNOWN"),
        cpuCount = optInt("cpu_count"),
        memoryMb = optLong("memory_size_MiB"),
    )

    private fun JsonObject.toVSphereVmDetail(moref: String): VSphereVm = VSphereVm(
        moref = moref,
        name = optString("name"),
        powerState = optString("power_state", "UNKNOWN"),
        // `guest_OS` here is the VM's *configured* guest-OS identifier
        // (e.g. "UBUNTU_64"), not a live VMware-Tools-reported name — the
        // live equivalent needs a further GET on
        // /vcenter/vm/{vm}/guest/identity, which requires Tools to be
        // running and isn't fetched here. Close enough for a display label;
        // revisit if a truly live Tools-reported name is needed later.
        guestFullName = optString("guest_OS"),
        cpuCount = optInt("cpu_count"),
        memoryMb = optLong("memory_size_MiB"),
    )

    private fun JsonObject.optString(key: String, default: String = ""): String =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull() ?: default
    private fun JsonObject.optInt(key: String, default: Int = 0): Int =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asInt }.getOrNull() ?: default
    private fun JsonObject.optLong(key: String, default: Long = 0L): Long =
        runCatching { get(key)?.takeIf { !it.isJsonNull }?.asLong }.getOrNull() ?: default

    private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
