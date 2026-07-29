package com.systemsgo.hex.util

import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import org.json.JSONObject

/**
 * Parses connection details out of a scanned QR code's raw text payload into
 * an [RdpProfile], for the "Scan QR Code" option in the new-connection
 * chooser (see AddOptionsDialog in Components.kt / HomeScreen.kt).
 *
 * Two payload shapes are accepted, mirroring how [RdpFileParser] tolerates
 * more than one real-world variant of the format it reads:
 *
 * 1. A connection URI:
 *      protocol://[username[:password]@]host[:port]
 *    e.g. "rdp://admin:secret@192.168.1.10:3389",
 *         "ssh://pi@raspberrypi.local", "vnc://192.168.1.20:5901",
 *         "https://192.168.1.10/RDWeb/Pages" (WEB-PORTAL FEATURE — a portal
 *         URL scanned as-is, path and all; "web://" is also accepted as an
 *         explicit alias for "https://")
 *    `protocol` must be one of "rdp", "vnc", "ssh", "telnet", "rlogin", "web"/"http"/
 *    "https" (case-insensitive); it defaults to RDP if omitted entirely (see
 *    the bare host:port fallback below, which is handled the same way as a
 *    URI with no scheme).
 *
 * 2. A flat JSON object with case-insensitive keys:
 *      {"protocol":"RDP","host":"192.168.1.10","port":3389,
 *       "username":"admin","password":"secret"}
 *    Recognised key aliases: protocol/type, host/hostname/address/server,
 *    port, username/user, password/pass, and (WEB-PORTAL FEATURE) weburl/
 *    web_url/url/portalurl/portal_url — supplying one of these implies WEB
 *    even without an explicit "protocol" key, and stands in for "host" too
 *    if that key is missing (the host is extracted from the URL).
 *
 * Only host is mandatory; port falls back to the resolved protocol's default
 * and username/password default to blank (classic VNC servers need neither).
 */
object QrConnectionParser {

    /** Thrown when [parse] cannot find a usable host in the scanned text. */
    class InvalidQrContentException(message: String) : Exception(message)

    fun parse(rawText: String, fallbackName: String = "Scanned"): RdpProfile {
        val text = rawText.trim()
        if (text.isEmpty()) {
            throw InvalidQrContentException("QR code did not contain any text")
        }

        val fields = if (looksLikeJson(text)) parseJson(text) else parseUri(text)

        // WEB-PORTAL FEATURE: a JSON payload may give only a "url" field with
        // no separate "host" — derive host/port from the URL the same way
        // Components.kt's profile form already does when saving a WEB
        // profile (Uri.parse(webUrl).host / .port), rather than requiring
        // both.
        val parsedWebUri = fields.webUrl?.let { hostPortFromUrl(it) }
        val host = fields.host.trim().ifBlank { parsedWebUri?.first ?: "" }
        if (host.isBlank()) {
            throw InvalidQrContentException(
                "QR code did not contain a host/address. Expected something like " +
                    "\"rdp://user@192.168.1.10:3389\" or a JSON object with a \"host\" field."
            )
        }

        val protocolType = fields.protocol ?: ProtocolType.RDP
        val port = fields.port?.coerceIn(1, 65535)
            ?: parsedWebUri?.second?.takeIf { it > 0 }
            ?: protocolType.defaultPort
        val username = fields.username?.trim() ?: ""
        val password = fields.password ?: ""

        val derivedName = when {
            username.isNotBlank() -> "$username@$host"
            else -> host
        }.let { if (it.length > 50) host else it }

        return RdpProfile(
            name = derivedName.ifBlank { fallbackName },
            protocolType = protocolType,
            host = host,
            port = port,
            username = username,
            password = password,
            // WEB-PORTAL FEATURE: WebPortalActivity/SessionLauncher read
            // webUrl (not host/port) to actually open the session — see
            // Components.kt's save handler, which builds host/port *from*
            // webUrl the same way [parsedWebUri] does above. Falls back to
            // reconstructing a plain "https://host[:port]" so a scanned
            // "rdp://..."-shaped-but-WEB-protocol payload (or a bare
            // host:port QR the person picked WEB for) still opens somewhere
            // sensible.
            webUrl = if (protocolType == ProtocolType.WEB) {
                fields.webUrl ?: buildString {
                    append("https://")
                    append(host)
                    if (port != ProtocolType.WEB.defaultPort) append(':').append(port)
                }
            } else ""
        )
    }

    /** Loosely-typed intermediate result shared by both payload parsers. */
    private data class ParsedFields(
        val protocol: ProtocolType?,
        val host: String,
        val port: Int?,
        val username: String?,
        val password: String?,
        // WEB-PORTAL FEATURE: only populated when the QR payload resolves to
        // ProtocolType.WEB — the full portal URL (scheme + host + path +
        // query), since [host]/[port] alone (parsed for the other four
        // protocols) would drop the path a portal login page needs, e.g.
        // ".../RDWeb/Pages" or ESXi's "/ui".
        val webUrl: String? = null
    )

    private fun looksLikeJson(text: String): Boolean =
        text.startsWith("{") && text.endsWith("}")

    // WEB-PORTAL FEATURE: minimal "scheme://host[:port]/path" splitter, kept
    // as plain Kotlin (no android.net.Uri) so this whole file stays a plain
    // JVM unit — same reasoning as parseUri's own manual host:port splitting
    // just below. Only used for the JSON-payload path where a "url" field
    // was given without a separate "host" field.
    private fun hostPortFromUrl(url: String): Pair<String, Int?>? {
        val schemeSepIdx = url.indexOf("://")
        if (schemeSepIdx < 0) return null
        val hostPortPart = url.substring(schemeSepIdx + 3)
            .substringBefore('/').substringBefore('?')
        if (hostPortPart.isBlank()) return null
        return when {
            hostPortPart.startsWith('[') -> {
                val closingBracket = hostPortPart.indexOf(']')
                if (closingBracket >= 0) {
                    val host = hostPortPart.substring(0, closingBracket + 1)
                    val port = hostPortPart.getOrNull(closingBracket + 1)
                        ?.takeIf { it == ':' }
                        ?.let { hostPortPart.substring(closingBracket + 2).toIntOrNull() }
                    host to port
                } else hostPortPart to null
            }
            hostPortPart.contains(':') -> {
                val lastColon = hostPortPart.lastIndexOf(':')
                hostPortPart.substring(0, lastColon) to hostPortPart.substring(lastColon + 1).toIntOrNull()
            }
            else -> hostPortPart to null
        }
    }

    private fun parseJson(text: String): ParsedFields {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw InvalidQrContentException("QR code JSON is malformed: ${e.message}")
        }
        // Build a case-insensitive lookup since QR-generating tools/users may
        // capitalize keys inconsistently (e.g. "Host" vs "host").
        val lower = HashMap<String, Any?>()
        for (key in obj.keys()) lower[key.lowercase()] = obj.opt(key)

        fun str(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { lower[it]?.toString()?.takeIf { s -> s.isNotBlank() } }

        val protocolStr = str("protocol", "type", "proto")
        val hostStr = (str("host", "hostname", "address", "server") ?: "").normalizeDigits()
        val portVal = (lower["port"] as? Number)?.toInt()
            ?: str("port")?.normalizeDigits()?.toIntOrNull()
        val username = str("username", "user")
        val password = str("password", "pass")
        // WEB-PORTAL FEATURE: accept a direct portal URL under a few common
        // key aliases, same case-insensitive lookup as every other field here.
        val webUrlStr = str("weburl", "web_url", "url", "portalurl", "portal_url")

        return ParsedFields(
            protocol = protocolStr?.let { protocolFromString(it) }
                ?: webUrlStr?.let { ProtocolType.WEB },
            host = hostStr,
            port = portVal,
            username = username,
            password = password,
            webUrl = webUrlStr
        )
    }

    private fun parseUri(text: String): ParsedFields {
        // "protocol://[user[:pass]@]host[:port][/anything]"
        val schemeSepIdx = text.indexOf("://")
        val protocol: ProtocolType?
        val schemeText: String?
        val afterScheme: String
        if (schemeSepIdx >= 0) {
            schemeText = text.substring(0, schemeSepIdx).trim().lowercase()
            protocol = protocolFromString(schemeText)
            afterScheme = text.substring(schemeSepIdx + 3)
        } else {
            // No scheme at all — accept a bare "host[:port]" or
            // "user@host[:port]" and default to RDP, same fallback
            // RdpFileParser uses when a file omits an explicit protocol.
            protocol = null
            schemeText = null
            afterScheme = text
        }

        // Strip any trailing path/query the URI might carry.
        val hostPortPart = afterScheme.substringBefore('/').substringBefore('?')

        val atIdx = hostPortPart.lastIndexOf('@')
        val userInfo = if (atIdx >= 0) hostPortPart.substring(0, atIdx) else null
        // I18N-FIX: normalize Arabic-Indic/Extended Arabic-Indic digits in the
        // host:port segment only — userInfo (username/password) is split off
        // above and must never have its digits altered, since it's a literal
        // credential, not a number.
        val hostPort = (if (atIdx >= 0) hostPortPart.substring(atIdx + 1) else hostPortPart).normalizeDigits()

        var username: String? = null
        var password: String? = null
        if (userInfo != null) {
            val colonIdx = userInfo.indexOf(':')
            if (colonIdx >= 0) {
                username = userInfo.substring(0, colonIdx)
                password = userInfo.substring(colonIdx + 1)
            } else {
                username = userInfo
            }
        }

        val host: String
        val port: Int?
        when {
            hostPort.startsWith('[') -> {
                // IPv6 literal in bracket notation, e.g. "[::1]:3390"
                val closingBracket = hostPort.indexOf(']')
                if (closingBracket >= 0) {
                    host = hostPort.substring(0, closingBracket + 1)
                    port = hostPort.getOrNull(closingBracket + 1)
                        ?.takeIf { it == ':' }
                        ?.let { hostPort.substring(closingBracket + 2).toIntOrNull() }
                } else {
                    host = hostPort
                    port = null
                }
            }
            hostPort.contains(':') -> {
                val lastColon = hostPort.lastIndexOf(':')
                host = hostPort.substring(0, lastColon)
                port = hostPort.substring(lastColon + 1).toIntOrNull()
            }
            else -> {
                host = hostPort
                port = null
            }
        }

        // WEB-PORTAL FEATURE: rebuild the full portal URL — scheme + host[:port]
        // + whatever path/query followed the host:port segment (e.g.
        // "/RDWeb/Pages", ESXi's "/ui") — deliberately dropping any userInfo
        // ("user:pass@") so a credential never ends up embedded in the URL
        // string shown in WebPortalActivity's address bar; username/password
        // are still carried separately via [username]/[password] above and
        // fill the same profile fields WebPortalActivity's HTTP-auth dialog
        // (webAutoFillHttpAuth) already reads.
        val webUrl = if (protocol == ProtocolType.WEB) {
            val effectiveScheme = if (schemeText == "web") "https" else (schemeText ?: "https")
            val pathAndQuery = afterScheme.substring(hostPortPart.length)
            "$effectiveScheme://$hostPort$pathAndQuery"
        } else null

        return ParsedFields(
            protocol = protocol,
            host = host,
            port = port,
            username = username?.let { decodeUriComponent(it) },
            password = password?.let { decodeUriComponent(it) },
            webUrl = webUrl
        )
    }

    private fun protocolFromString(value: String): ProtocolType? = when (value.trim().lowercase()) {
        "rdp" -> ProtocolType.RDP
        "vnc" -> ProtocolType.VNC
        "ssh" -> ProtocolType.SSH
        "telnet" -> ProtocolType.TELNET
        "rlogin" -> ProtocolType.RLOGIN
        "spice" -> ProtocolType.SPICE
        "rtsp", "rtsps" -> ProtocolType.RTSP
        // WEB-PORTAL FEATURE: a QR code encoding a portal URL directly
        // ("https://192.168.1.1/RDWeb/Pages" — the common case, since that's
        // what most RD Web/Guacamole/iDRAC/ESXi "scan to connect" QR
        // generators would actually emit) or an explicit "web://" scheme.
        "https", "http", "web" -> ProtocolType.WEB
        else -> null
    }

    private fun decodeUriComponent(value: String): String = try {
        java.net.URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }
}

/**
 * QR-SHARE FEATURE: the reverse of [QrConnectionParser.parse] — turns an
 * [RdpProfile] back into the same "protocol://[user[:pass]@]host[:port]"
 * payload text that payload shape 1 in [QrConnectionParser]'s class doc
 * already understands, so a code generated by this app on one device is
 * importable via the existing "Scan QR Code" flow (QrScannerActivity ->
 * QrConnectionParser.parse) on another, with no separate decoder needed.
 *
 * A bare host is returned for [ProtocolType.WEB] profiles (their webUrl
 * already carries "https://" and any path), so a scanned WEB profile
 * round-trips through parseUri's "web://"-alias handling correctly.
 */
object QrConnectionEncoder {

    fun encode(profile: RdpProfile, includeCredentials: Boolean = true): String {
        if (profile.protocolType == ProtocolType.WEB && profile.webUrl.isNotBlank()) {
            return profile.webUrl
        }

        val scheme = profile.protocolType.name.lowercase()
        val userInfo = buildString {
            if (includeCredentials && profile.username.isNotBlank()) {
                append(encodeUriComponent(profile.username))
                if (profile.password.isNotBlank()) {
                    append(':').append(encodeUriComponent(profile.password))
                }
                append('@')
            }
        }
        val hostPart = if (profile.host.contains(':') && !profile.host.startsWith('['))
            "[${profile.host}]" // bracket a bare IPv6 literal, mirroring parseUri's own handling
        else profile.host

        val portPart = if (profile.port != profile.protocolType.defaultPort) ":${profile.port}" else ""

        return "$scheme://$userInfo$hostPart$portPart"
    }

    private fun encodeUriComponent(value: String): String = try {
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    } catch (_: Exception) {
        value
    }
}
