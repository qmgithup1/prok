package com.systemsgo.hex.webfeed

import android.content.Context
import android.util.Base64
import android.util.Xml
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.security.TofuTrustManager
import com.systemsgo.hex.util.RdpFileParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RD-WEB-FEED FEATURE: talks to an RD Web Access "RemoteApp and Desktop
 * Connections" feed — the same endpoint the official Microsoft Remote
 * Desktop clients (Windows/Mac/iOS/Android) use to auto-discover published
 * RemoteApps and full desktops, normally at
 * `https://<server>/RDWeb/Feed/webfeed.aspx`.
 *
 * Auth: this endpoint (unlike the browser-facing `/RDWeb/Pages` forms login)
 * is specifically designed for programmatic clients and is authenticated via
 * plain HTTP Basic auth over TLS — this is why RD Web Access documentation
 * instructs admins to enable Basic Authentication on the IIS "Feed" virtual
 * directory for non-Windows/rich clients to work at all. If the server only
 * has Windows/NTLM or forms auth enabled on that endpoint (no Basic), the
 * request comes back as a 401 or an HTML login page and [fetchFeed] reports
 * [WebFeedFetchResult.AuthRequired] — there's no in-app way around that
 * short of the admin enabling Basic auth on the Feed vdir (this app does not
 * implement NTLM or interactive forms/ADFS login for this feature).
 *
 * Schema reference: MS-TSWP (ResourceCollection XML), confirmed against
 * Microsoft's published schema + samples — Resource@Alias/@Title/@Type,
 * HostingTerminalServers/HostingTerminalServer/ResourceFile@URL.
 */
class RdWebFeedClient @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    private val connectTimeoutMs = 15_000
    private val readTimeoutMs = 20_000

    /**
     * Turns whatever the user typed (a bare host, "rdweb.contoso.com", a
     * partial URL, or the full feed URL) into the canonical
     * `https://<host>/RDWeb/Feed/webfeed.aspx` form.
     */
    fun normalizeFeedUrl(input: String): String = Companion.normalizeFeedUrl(input)

    companion object {
        /**
         * Same normalization as the instance method above, exposed
         * statically so pure string-formatting call sites (e.g. the "add
         * feed" dialog, before any [RdWebFeedClient] instance/Context is
         * needed) don't have to construct a whole client — see
         * [RdWebFeedScreen]'s `AddWebFeedDialog` `onSave` callback.
         */
        fun normalizeFeedUrl(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return s
            if (!s.contains("://")) s = "https://$s"
            val lower = s.lowercase()
            return when {
                lower.contains("/feed/webfeed.aspx") -> s
                lower.endsWith(".aspx") -> s // some other explicit endpoint the user knows about — trust it
                else -> s.trimEnd('/') + "/RDWeb/Feed/webfeed.aspx"
            }
        }
    }

    suspend fun fetchFeed(
        feedUrl: String,
        username: String,
        password: String,
        domain: String,
        acceptSelfSignedCertificate: Boolean,
    ): WebFeedFetchResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(normalizeFeedUrl(feedUrl))
            val connection = openConnection(url, username, password, domain, acceptSelfSignedCertificate)
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/xml, text/xml, */*")

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                connection.disconnect()
                return@withContext WebFeedFetchResult.AuthRequired
            }
            if (code !in 200..299) {
                val message = "HTTP $code ${connection.responseMessage.orEmpty()}"
                connection.disconnect()
                return@withContext WebFeedFetchResult.Error(message)
            }

            val contentType = connection.contentType ?: ""
            val bytes = connection.inputStream.use { it.readBytes() }
            connection.disconnect()

            val text = String(bytes, Charsets.UTF_8)
            val looksLikeXml = text.trimStart().startsWith("<?xml") ||
                text.trimStart().startsWith("<ResourceCollection") ||
                contentType.contains("xml", ignoreCase = true)
            if (!looksLikeXml) {
                // Almost always means we got redirected to an HTML forms-login
                // page instead of the raw feed XML — see class doc comment.
                return@withContext WebFeedFetchResult.AuthRequired
            }

            parseResourceCollection(bytes.inputStream(), url)
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            WebFeedFetchResult.Error("TLS/certificate error: ${e.message}")
        } catch (e: java.net.UnknownHostException) {
            WebFeedFetchResult.Error("Unknown host: ${e.message}")
        } catch (e: java.net.SocketTimeoutException) {
            WebFeedFetchResult.Error("Connection timed out")
        } catch (e: Exception) {
            WebFeedFetchResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Downloads the resource's own server-side .rdp file and parses it into
     * an [RdpProfile] draft via the existing [RdpFileParser] — the feed only
     * gives enough to list resources; the actual connection settings
     * (server, gateway, RemoteApp program, ...) live in this per-resource
     * .rdp file, exactly like a manually-imported .rdp file.
     */
    suspend fun fetchResourceProfile(
        resource: WebFeedResource,
        username: String,
        password: String,
        domain: String,
        acceptSelfSignedCertificate: Boolean,
    ): RdpProfile? = withContext(Dispatchers.IO) {
        try {
            val url = URL(resource.resourceFileUrl)
            val connection = openConnection(url, username, password, domain, acceptSelfSignedCertificate)
            connection.setRequestProperty("Accept", "*/*")
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                return@withContext null
            }
            val profile = connection.inputStream.use { stream ->
                RdpFileParser.parse(stream, fallbackName = resource.title)
            }
            connection.disconnect()
            profile.copy(
                name = resource.title,
                // The feed's own login is a reasonable default for the resource
                // itself (same SSO domain in most deployments); left editable —
                // the password is intentionally never carried over.
                username = profile.username.ifBlank { username },
                domain = profile.domain.ifBlank { domain },
                webFeedAlias = resource.alias,
            )
        } catch (e: Exception) {
            null
        }
    }

    // ── HTTP plumbing ────────────────────────────────────────────────────────

    private fun openConnection(
        url: URL,
        username: String,
        password: String,
        domain: String,
        acceptSelfSignedCertificate: Boolean,
    ): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = "GET"

        if (connection is HttpsURLConnection && acceptSelfSignedCertificate) {
            // SECURITY FIX (TLS-TOFU-PARITY): this used to install a single
            // process-wide trust-all SSLContext (shared across every host,
            // never checking which host a certificate was even for) — once
            // the user opted in to "accept self-signed certificate" for this
            // feed, *every* future connection trusted *any* certificate, with
            // no fingerprint pinning and no detection of a later-substituted
            // (MITM) certificate, unlike Telnet/RDP/NETCONF/Guacamole
            // elsewhere in this app. Now uses the same silent TOFU pinning
            // those protocols use, pinned per-host via TofuTrustManager
            // (still scoped to this single connection only — never installed
            // as the process-wide default trust manager).
            val identity = "${url.host}:${if (url.port > 0) url.port else 443}"
            val trustManager = TofuTrustManager(appContext, identity)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }
            connection.sslSocketFactory = sslContext.socketFactory
            // Safe to skip the hostname/CN check here: the trust manager
            // above already pins the exact certificate fingerprint, which is
            // a strictly stronger identity guarantee than a name match.
            connection.hostnameVerifier = HostnameVerifier { _: String, _: SSLSession -> true }
        }

        if (username.isNotBlank()) {
            val basicUser = if (domain.isNotBlank()) "$domain\\$username" else username
            val token = Base64.encodeToString("$basicUser:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $token")
        }
        return connection
    }

    // ── XML parsing (MS-TSWP ResourceCollection) ────────────────────────────

    private fun parseResourceCollection(stream: InputStream, feedUrl: URL): WebFeedFetchResult {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, "UTF-8")

        val resources = mutableListOf<WebFeedResource>()
        var publisherName = ""

        var eventType = parser.eventType
        var inResource = false
        var alias = ""
        var title = ""
        var type = ""
        var iconUrl: String? = null
        var resourceFileUrl: String? = null
        var terminalServerRef = ""

        fun resolve(relative: String?): String? {
            if (relative.isNullOrBlank()) return null
            return try { URL(feedUrl, relative).toString() } catch (e: Exception) { null }
        }

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (localName(parser.name)) {
                        "Publisher" -> publisherName = parser.getAttributeValue(null, "Name") ?: publisherName
                        "Resource" -> {
                            inResource = true
                            alias = parser.getAttributeValue(null, "Alias") ?: ""
                            title = parser.getAttributeValue(null, "Title") ?: alias
                            type = parser.getAttributeValue(null, "Type") ?: "RemoteApp"
                            iconUrl = null
                            resourceFileUrl = null
                            terminalServerRef = ""
                        }
                        "Icon32" -> if (inResource && iconUrl == null) {
                            iconUrl = resolve(parser.getAttributeValue(null, "FileURL"))
                        }
                        "ResourceFile" -> if (inResource) {
                            resourceFileUrl = resolve(parser.getAttributeValue(null, "URL"))
                        }
                        "TerminalServerRef" -> if (inResource) {
                            terminalServerRef = parser.getAttributeValue(null, "Ref") ?: terminalServerRef
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (localName(parser.name) == "Resource" && inResource) {
                        val fileUrl = resourceFileUrl
                        if (alias.isNotBlank() && fileUrl != null) {
                            resources += WebFeedResource(
                                alias = alias,
                                title = title,
                                type = if (type.equals("Desktop", ignoreCase = true))
                                    WebFeedResourceType.DESKTOP else WebFeedResourceType.REMOTE_APP,
                                iconUrl = iconUrl,
                                resourceFileUrl = fileUrl,
                                terminalServerName = terminalServerRef,
                            )
                        }
                        inResource = false
                    }
                }
            }
            eventType = parser.next()
        }

        return WebFeedFetchResult.Success(resources, publisherName)
    }

    private fun localName(qualifiedName: String): String =
        qualifiedName.substringAfterLast(':')
}
