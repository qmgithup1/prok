package com.systemsgo.hex.redfish.protocol

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.systemsgo.hex.security.TofuTrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Redfish (DMTF DSP0266) REST client for BMC out-of-band management —
 * covers Dell iDRAC, HPE iLO, Lenovo XCC, Supermicro, and any other
 * Redfish-conformant BMC. This is the modern, vendor-neutral counterpart to
 * [com.systemsgo.hex.ipmi.protocol.IpmiClient]; prefer this client whenever
 * the target BMC advertises `/redfish/v1/` — it exposes far more (virtual
 * media, firmware update, structured event log, sensor thresholds) than raw
 * IPMI can.
 *
 * Same self-signed-certificate opt-in shape as
 * [com.systemsgo.hex.webfeed.RdWebFeedClient] / `RdpProfile.acceptSelfSignedCertificate`
 * — BMC web UIs are overwhelmingly self-signed out of the box.
 *
 * Auth: tries Redfish Session auth first (POST SessionService/Sessions,
 * gets back an X-Auth-Token) since that's what the spec recommends and lets
 * us log the session out cleanly; falls back to HTTP Basic if
 * SessionService isn't present (some minimal/embedded implementations skip
 * it and only support Basic).
 */
class RedfishClient(
    private val baseUrl: String, // e.g. "https://10.0.0.5" — no trailing slash, no /redfish/v1
    private val username: String,
    private val password: String,
    private val acceptSelfSignedCertificate: Boolean = true,
    /**
     * SECURITY FIX (TLS-TOFU-PARITY): application [Context], used only when
     * [acceptSelfSignedCertificate] is on, to back a [TofuTrustManager]
     * instead of the old blind trust-all manager — see that class's doc
     * comment. Optional (default null) purely so existing call sites/tests
     * that construct this client without a Context still compile; passing
     * one is what actually gets pinning + MITM detection instead of the
     * legacy trust-all fallback below.
     */
    private val appContext: Context? = null,
) {
    private val root = baseUrl.trimEnd('/')
    private var sessionToken: String? = null
    private var sessionLocation: String? = null

    private val httpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS) // firmware/media uploads can be slow
        if (acceptSelfSignedCertificate) {
            // SECURITY FIX (TLS-TOFU-PARITY): this used to be a blind
            // trust-all X509TrustManager — once the user opted in to
            // "accept self-signed certificate" for this BMC profile, *every*
            // future connection trusted *any* certificate, with no
            // fingerprint pinning and no detection of a later-substituted
            // (MITM) certificate, unlike Telnet/RDP/NETCONF/Guacamole
            // elsewhere in this app. Now uses the same silent TOFU pinning
            // those protocols use — see TofuTrustManager's doc comment.
            val identity = java.net.URI(root).let { "${it.host}:${if (it.port > 0) it.port else 443}" }
            // SECURITY FIX (TLS-TOFU-NO-FALLBACK): a missing appContext used to
            // fall back to a trust-all X509TrustManager (every certificate
            // accepted, no pinning, no MITM detection) — exactly the
            // vulnerability TofuTrustManager exists to close. Fail closed
            // instead of silently downgrading to an insecure connection.
            val trustManager: X509TrustManager = appContext?.let { TofuTrustManager(it, identity) }
                ?: throw IllegalStateException(
                    "acceptSelfSignedCertificate is on for '$identity' but no appContext was " +
                        "supplied to RedfishClient — TOFU certificate pinning requires a Context " +
                        "to store the pinned fingerprint. Refusing to connect with a trust-all " +
                        "fallback. Pass appContext to RedfishClient's constructor.",
                )
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            // Safe to skip the hostname/CN check here: the trust manager
            // above already pins the exact certificate fingerprint, which is
            // a strictly stronger identity guarantee than a name match.
            builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
        builder.build()
    }

    // ── session lifecycle ────────────────────────────────────────────

    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply {
                addProperty("UserName", username)
                addProperty("Password", password)
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$root/redfish/v1/SessionService/Sessions").post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code in 200..299) {
                    sessionToken = resp.header("X-Auth-Token")
                    sessionLocation = resp.header("Location")
                }
                // if session auth isn't supported (404/405/501), we silently
                // fall back to Basic auth on every subsequent request below.
            }
        } catch (e: Exception) {
            throw RedfishException("Could not reach $root — check host/port and network reachability", cause = e)
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        val loc = sessionLocation
        if (loc != null) {
            try {
                request("DELETE", loc)
            } catch (_: Exception) {
                // best-effort; the BMC will expire the session on its own
            }
        }
        sessionToken = null
        sessionLocation = null
    }

    // ── low-level request helper ────────────────────────────────────

    private fun request(method: String, path: String, jsonBody: String? = null): JsonObject? {
        val url = if (path.startsWith("http")) path else "$root$path"
        val builder = Request.Builder().url(url)
        val token = sessionToken
        if (token != null) {
            builder.header("X-Auth-Token", token)
        } else {
            builder.header("Authorization", Credentials.basic(username, password))
        }
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post((jsonBody ?: "{}").toRequestBody("application/json".toMediaType()))
            "PATCH" -> builder.patch((jsonBody ?: "{}").toRequestBody("application/json".toMediaType()))
            else -> throw IllegalArgumentException("Unsupported method $method")
        }
        httpClient.newCall(builder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string()
            if (resp.code !in 200..299) {
                val msg = bodyStr?.let { runCatching { extractErrorMessage(it) }.getOrNull() }
                throw RedfishException(msg ?: "HTTP ${resp.code} from $url", httpStatus = resp.code)
            }
            if (bodyStr.isNullOrBlank()) return null
            return JsonParser.parseString(bodyStr).asJsonObject
        }
    }

    private fun extractErrorMessage(body: String): String? =
        JsonParser.parseString(body).asJsonObject
            .getAsJsonObject("error")
            ?.get("message")?.asString

    private fun get(path: String): JsonObject = request("GET", path)
        ?: throw RedfishException("Empty response from $path")

    // ── Systems ─────────────────────────────────────────────────────

    suspend fun getSystems(): List<RedfishSystemSummary> = withContext(Dispatchers.IO) {
        val collection = get("/redfish/v1/Systems")
        collection.arrOrEmpty("Members").map { member ->
            val odataId = member.asJsonObject.get("@odata.id").asString
            val sys = get(odataId)
            parseSystem(sys, odataId)
        }
    }

    suspend fun getSystem(odataId: String): RedfishSystemSummary = withContext(Dispatchers.IO) {
        parseSystem(get(odataId), odataId)
    }

    private fun parseSystem(sys: JsonObject, odataId: String): RedfishSystemSummary {
        val status = sys.getAsJsonObject("Status")
        val procSummary = sys.getAsJsonObject("ProcessorSummary")
        val memSummary = sys.getAsJsonObject("MemorySummary")
        return RedfishSystemSummary(
            id = sys.str("Id") ?: odataId.substringAfterLast('/'),
            name = sys.str("Name") ?: "System",
            powerState = sys.str("PowerState") ?: "Unknown",
            health = status?.str("Health"),
            model = sys.str("Model"),
            manufacturer = sys.str("Manufacturer"),
            serialNumber = sys.str("SerialNumber"),
            biosVersion = sys.str("BiosVersion"),
            processorSummary = procSummary?.let { "${it.str("Count") ?: "?"} × ${it.str("Model") ?: "CPU"}" },
            memorySummaryGiB = memSummary?.get("TotalSystemMemoryGiB")?.takeIf { !it.isJsonNull }?.asDouble,
            odataId = odataId,
        )
    }

    /** ComputerSystem.Reset — power on/off/restart/NMI. */
    suspend fun resetSystem(systemOdataId: String, resetType: RedfishResetType) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply { addProperty("ResetType", resetType.wireValue) }.toString()
        request("POST", "$systemOdataId/Actions/ComputerSystem.Reset", body)
        Unit
    }

    // ── Chassis ─────────────────────────────────────────────────────

    suspend fun getChassis(): List<RedfishChassisSummary> = withContext(Dispatchers.IO) {
        val collection = get("/redfish/v1/Chassis")
        collection.arrOrEmpty("Members").map { member ->
            val odataId = member.asJsonObject.get("@odata.id").asString
            val c = get(odataId)
            RedfishChassisSummary(
                id = c.str("Id") ?: odataId.substringAfterLast('/'),
                name = c.str("Name") ?: "Chassis",
                health = c.getAsJsonObject("Status")?.str("Health"),
                odataId = odataId,
            )
        }
    }

    /** Reads Thermal + Power sub-resources for a chassis (older schema) or Sensors collection (2022.x+ schema), whichever the BMC has. */
    suspend fun getSensors(chassisOdataId: String): List<RedfishSensorReading> = withContext(Dispatchers.IO) {
        val out = mutableListOf<RedfishSensorReading>()
        runCatching { get("$chassisOdataId/Thermal") }.getOrNull()?.let { thermal ->
            thermal.getAsJsonArray("Temperatures")?.forEach { t ->
                val o = t.asJsonObject
                out += RedfishSensorReading(
                    name = o.str("Name") ?: "Temperature",
                    reading = o.get("ReadingCelsius")?.takeIf { !it.isJsonNull }?.asDouble,
                    units = "°C",
                    health = o.getAsJsonObject("Status")?.str("Health"),
                    upperCritical = o.get("UpperThresholdCritical")?.takeIf { !it.isJsonNull }?.asDouble,
                    lowerCritical = o.get("LowerThresholdCritical")?.takeIf { !it.isJsonNull }?.asDouble,
                )
            }
            thermal.getAsJsonArray("Fans")?.forEach { f ->
                val o = f.asJsonObject
                out += RedfishSensorReading(
                    name = o.str("Name") ?: "Fan",
                    reading = (o.get("Reading") ?: o.get("ReadingRPM"))?.takeIf { !it.isJsonNull }?.asDouble,
                    units = o.str("ReadingUnits") ?: "RPM",
                    health = o.getAsJsonObject("Status")?.str("Health"),
                )
            }
        }
        runCatching { get("$chassisOdataId/Power") }.getOrNull()?.let { power ->
            power.getAsJsonArray("PowerControl")?.forEach { p ->
                val o = p.asJsonObject
                out += RedfishSensorReading(
                    name = o.str("Name") ?: "Power",
                    reading = o.get("PowerConsumedWatts")?.takeIf { !it.isJsonNull }?.asDouble,
                    units = "W",
                    health = o.getAsJsonObject("Status")?.str("Health"),
                )
            }
            power.getAsJsonArray("Voltages")?.forEach { v ->
                val o = v.asJsonObject
                out += RedfishSensorReading(
                    name = o.str("Name") ?: "Voltage",
                    reading = o.get("ReadingVolts")?.takeIf { !it.isJsonNull }?.asDouble,
                    units = "V",
                    health = o.getAsJsonObject("Status")?.str("Health"),
                )
            }
        }
        out
    }

    // ── Managers (BMC firmware) ────────────────────────────────────

    suspend fun getManagers(): List<RedfishManagerSummary> = withContext(Dispatchers.IO) {
        val collection = get("/redfish/v1/Managers")
        collection.arrOrEmpty("Members").map { member ->
            val odataId = member.asJsonObject.get("@odata.id").asString
            val m = get(odataId)
            RedfishManagerSummary(
                id = m.str("Id") ?: odataId.substringAfterLast('/'),
                name = m.str("Name") ?: "Manager",
                firmwareVersion = m.str("FirmwareVersion"),
                odataId = odataId,
            )
        }
    }

    // ── Event / Log service ─────────────────────────────────────────

    suspend fun getEventLog(systemOrManagerOdataId: String, maxEntries: Int = 50): List<RedfishLogEntry> = withContext(Dispatchers.IO) {
        val logServices = runCatching { get("$systemOrManagerOdataId/LogServices") }.getOrNull() ?: return@withContext emptyList()
        val out = mutableListOf<RedfishLogEntry>()
        for (member in logServices.arrOrEmpty("Members")) {
            if (out.size >= maxEntries) break
            val logOdataId = member.asJsonObject.get("@odata.id").asString
            val entries = runCatching { get("$logOdataId/Entries") }.getOrNull() ?: continue
            for (e in entries.arrOrEmpty("Members")) {
                if (out.size >= maxEntries) break
                val eo = e.asJsonObject
                out += RedfishLogEntry(
                    id = eo.str("Id") ?: "",
                    created = eo.str("Created"),
                    severity = eo.str("Severity"),
                    message = eo.str("Message"),
                    entryType = eo.str("EntryType"),
                )
            }
        }
        out.sortedByDescending { it.created ?: "" }
    }

    // ── Virtual Media ────────────────────────────────────────────────

    suspend fun getVirtualMedia(managerOdataId: String): List<RedfishVirtualMedia> = withContext(Dispatchers.IO) {
        val collection = runCatching { get("$managerOdataId/VirtualMedia") }.getOrNull() ?: return@withContext emptyList()
        collection.arrOrEmpty("Members").map { member ->
            val odataId = member.asJsonObject.get("@odata.id").asString
            val vm = get(odataId)
            RedfishVirtualMedia(
                id = vm.str("Id") ?: odataId.substringAfterLast('/'),
                name = vm.str("Name") ?: "Virtual Media",
                mediaTypes = vm.getAsJsonArray("MediaTypes")?.map { it.asString } ?: emptyList(),
                inserted = vm.get("Inserted")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                image = vm.str("Image"),
                odataId = odataId,
            )
        }
    }

    /** Mounts a remote ISO/IMG by URL (e.g. `http://myshare/os.iso`) — the BMC fetches it itself, nothing is uploaded through this app. */
    suspend fun insertVirtualMedia(virtualMediaOdataId: String, imageUrl: String, asWriteProtected: Boolean = true) = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("Image", imageUrl)
            addProperty("Inserted", true)
            addProperty("WriteProtected", asWriteProtected)
        }.toString()
        request("POST", "$virtualMediaOdataId/Actions/VirtualMedia.InsertMedia", body)
        Unit
    }

    suspend fun ejectVirtualMedia(virtualMediaOdataId: String) = withContext(Dispatchers.IO) {
        request("POST", "$virtualMediaOdataId/Actions/VirtualMedia.EjectMedia", "{}")
        Unit
    }

    // ── Firmware update (UpdateService.SimpleUpdate) ─────────────────

    /**
     * Kicks off a firmware update from an image already reachable by URL
     * (HTTP/HTTPS/TFTP — whatever the BMC's TransferProtocol supports).
     * Returns the @odata.id of the Task the BMC created to track progress,
     * poll it with [getTaskStatus].
     */
    suspend fun simpleUpdateFirmware(imageUrl: String, transferProtocol: String = "HTTP", targetOdataId: String? = null): String =
        withContext(Dispatchers.IO) {
            val body = JsonObject().apply {
                addProperty("ImageURI", imageUrl)
                addProperty("TransferProtocol", transferProtocol)
                targetOdataId?.let {
                    val arr = JsonArray().apply { add(it) }
                    add("Targets", arr)
                }
            }.toString()
            val resp = request("POST", "/redfish/v1/UpdateService/Actions/UpdateService.SimpleUpdate", body)
            resp?.get("@odata.id")?.asString
                ?: throw RedfishException("BMC did not return a Task to track update progress")
        }

    suspend fun getTaskStatus(taskOdataId: String): RedfishTaskStatus = withContext(Dispatchers.IO) {
        val t = get(taskOdataId)
        RedfishTaskStatus(
            id = t.str("Id") ?: taskOdataId.substringAfterLast('/'),
            state = t.str("TaskState") ?: "Unknown",
            percentComplete = t.get("PercentComplete")?.takeIf { !it.isJsonNull }?.asInt,
            messages = t.getAsJsonArray("Messages")?.mapNotNull { it.asJsonObject.str("Message") } ?: emptyList(),
        )
    }

    // ── discovery ────────────────────────────────────────────────────

    /** Quick reachability + version check — call before saving a new profile. */
    suspend fun probeServiceRoot(): String = withContext(Dispatchers.IO) {
        val root = get("/redfish/v1/")
        root.str("RedfishVersion") ?: "unknown"
    }

    private fun JsonObject.str(key: String): String? = this.get(key)?.takeIf { !it.isJsonNull }?.asString

    /** Null/absent-safe accessor — Gson's JsonObject.getAsJsonArray returns null (not throws) when the member is missing. */
    private fun JsonObject.arrOrEmpty(key: String): JsonArray = this.getAsJsonArray(key) ?: JsonArray()
}
