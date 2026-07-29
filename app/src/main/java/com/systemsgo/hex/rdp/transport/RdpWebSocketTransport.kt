package com.systemsgo.hex.rdp.transport

import android.content.Context
import android.util.Log
import com.systemsgo.hex.security.TofuTrustManager
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

/**
 * Bridges FreeRDP's native TCP client to a WebSocket transport (requirement
 * #2), without touching a single line of `systemsgo_jni.c`.
 *
 * ## Why a loopback bridge, not a native change (requirement #12)
 * FreeRDP's native transport (`transport.c`/`tcp.c` inside FreeRDP itself,
 * wrapped by `systemsgo_jni.c`'s `nativeConnect`) only ever knows how to open
 * a plain TCP socket to a host/port. Rather than teaching that C code a
 * second transport (a large, risky change to a third-party library this
 * app doesn't own the upstream of), this class:
 *
 * 1. Opens a [ServerSocket] on `127.0.0.1` on an ephemeral port and starts
 *    listening.
 * 2. The caller passes `"127.0.0.1"` + that port to
 *    [com.systemsgo.hex.rdp.native.AFreeRdpBridge.connect] instead of the
 *    real host/port — FreeRDP connects to that exactly as it would to a
 *    real RDP server.
 * 3. This class accepts that connection and relays raw bytes
 *    bidirectionally between it and a real RFC 6455 WebSocket (via OkHttp)
 *    to [RdpWebSocketConfig.resolvedUrl].
 *
 * This is the exact same shape as the existing
 * [com.systemsgo.hex.rdp.serial.SerialNetworkBridge] /
 * `AFreeRdpBridge.resolveEffectiveSerialPath` pattern already in this
 * codebase for serial-over-network — see that class's doc comment.
 *
 * ## Threading
 * All callbacks ([Listener]) fire on a background dispatcher, never the
 * caller's thread and never OkHttp's own WebSocket callback thread
 * directly — callers should hop back to their own scope/dispatcher as
 * needed, same convention as [com.systemsgo.hex.rdp.native.AFreeRdpBridge]'s
 * flows.
 */
class RdpWebSocketTransport(
    private val config: RdpWebSocketConfig,
    private val mode: RdpTransportMode,
    /**
     * SECURITY FIX (TLS-TOFU-PARITY): application [Context], used only when
     * [RdpWebSocketConfig.TlsOptions.allowSelfSigned] (or
     * `validateCertificate = false`) is set and no explicit
     * [RdpWebSocketConfig.TlsOptions.pinnedCertificateSha256] is configured,
     * to back a [TofuTrustManager] instead of the old blind trust-all
     * manager — see that class's doc comment and [buildSslSocketFactory].
     * Optional (default null) purely so existing call sites/tests that
     * construct this transport without a Context still compile; passing
     * one is what actually gets pinning + MITM detection instead of the
     * legacy trust-all fallback.
     */
    private val appContext: Context? = null,
) {
    interface Listener {
        /** The loopback bridge is up and FreeRDP's native socket may now
         *  connect to [localHost]:[localPort]. */
        fun onBridgeReady(localHost: String, localPort: Int)
        /** The WebSocket connected and the tunnel is live. */
        fun onTransportConnected()
        /** Fired once, whether the failure happened on the first attempt
         *  or after [RdpWebSocketConfig.autoReconnect] gave up. */
        fun onTransportFailed(error: RdpTransportException)
        /** The tunnel closed normally (server or client initiated), no
         *  reconnect will be attempted. */
        fun onTransportClosed()
    }

    private val tag = "RdpWebSocketTransport"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)

    private var serverSocket: ServerSocket? = null
    private var localPeer: Socket? = null
    private var webSocket: WebSocket? = null
    private var listener: Listener? = null
    private var httpClient: OkHttpClient? = null

    /** Starts the loopback bridge and, once a native peer connects to it,
     *  the WebSocket tunnel. Idempotent-ish: call [close] before reusing. */
    fun start(listener: Listener) {
        this.listener = listener
        scope.launch {
            try {
                validateConfig()
                val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                serverSocket = server
                listener.onBridgeReady("127.0.0.1", server.localPort)
                // Blocks until FreeRDP's native code connects to the port
                // we just handed the caller — mirrors SerialNetworkBridge's
                // accept-before-native-open pattern so there's no race.
                val peer = server.accept()
                localPeer = peer
                connectWebSocket(peer)
            } catch (e: IOException) {
                fail(RdpTransportException.LocalBridgeFailure(e))
            }
        }
    }

    private fun validateConfig() {
        val headerNames = buildMap {
            putAll(config.headers)
            if (config.authorizationHeader.isNotBlank()) put("Authorization", config.authorizationHeader)
            if (config.cookie.isNotBlank()) put("Cookie", config.cookie)
            if (config.origin.isNotBlank()) put("Origin", config.origin)
        }
        for ((name, value) in headerNames) {
            if (name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) {
                throw RdpTransportException.InvalidHeader(name, "contains CR/LF")
            }
            if (name.isBlank()) {
                throw RdpTransportException.InvalidHeader(name, "empty header name")
            }
        }
    }

    private fun connectWebSocket(peer: Socket) {
        val url = config.resolvedUrl(mode)
        val client = buildHttpClient(url)
        httpClient = client

        val requestBuilder = Request.Builder().url(url)
        // Requirement #4: custom headers, Authorization, Origin, Cookie,
        // Bearer token, Subprotocol.
        config.headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val authHeader = when {
            config.authorizationHeader.isNotBlank() -> config.authorizationHeader
            config.bearerToken.isNotBlank() -> "Bearer ${config.bearerToken}"
            else -> null
        }
        authHeader?.let { requestBuilder.addHeader("Authorization", it) }
        if (config.origin.isNotBlank()) requestBuilder.addHeader("Origin", config.origin)
        if (config.cookie.isNotBlank()) requestBuilder.addHeader("Cookie", config.cookie)
        if (config.subprotocol.isNotBlank()) requestBuilder.addHeader("Sec-WebSocket-Protocol", config.subprotocol)

        val request = requestBuilder.build()

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt.set(0)
                listener?.onTransportConnected()
                // WS -> local socket (inbound to FreeRDP)
                pumpLocalPeerOutbound(peer, webSocket)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    peer.getOutputStream().write(bytes.toByteArray())
                    peer.getOutputStream().flush()
                } catch (e: IOException) {
                    Log.w(tag, "Failed writing inbound WS frame to local peer", e)
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Requirement #7 gateways all tunnel raw RDP bytes as
                // *binary* frames; a text frame here means either a
                // misconfigured gateway or an out-of-band control message
                // this client doesn't understand. Never silently drop
                // bytes into the RDP stream — that would corrupt it.
                Log.w(tag, "Ignoring unexpected text WS frame (${text.length} chars)")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closePeerQuietly(peer)
                if (!closed.get()) listener?.onTransportClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code
                val err = RdpTransportException.fromHandshakeFailure(url, httpCode, t)
                maybeReconnect(peer, err)
            }
        }).also { this.webSocket = it }
    }

    /** Local socket (FreeRDP's outbound RDP bytes) -> WebSocket binary frames. */
    private fun pumpLocalPeerOutbound(peer: Socket, ws: WebSocket) {
        scope.launch {
            val buf = ByteArray(16 * 1024)
            try {
                val input = peer.getInputStream()
                while (!closed.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    ws.send(buf.copyOf(n).toByteString())
                }
            } catch (e: IOException) {
                if (!closed.get()) Log.d(tag, "Local peer stream ended: ${e.message}")
            } finally {
                ws.close(1000, "local peer closed")
            }
        }
    }

    private fun maybeReconnect(peer: Socket, error: RdpTransportException) {
        if (closed.get()) return
        if (!config.autoReconnect) {
            fail(error)
            return
        }
        val attempt = reconnectAttempt.incrementAndGet()
        if (attempt > config.maxReconnectAttempts) {
            fail(RdpTransportException.ReconnectExhausted(attempt - 1, error))
            return
        }
        val backoff = config.reconnectBackoff
        val delayMs = (backoff.initialDelayMs * Math.pow(backoff.multiplier, (attempt - 1).toDouble()))
            .toLong().coerceAtMost(backoff.maxDelayMs)
        Log.i(tag, "WebSocket transport failed ($error), reconnect attempt $attempt/${config.maxReconnectAttempts} in ${delayMs}ms")
        scope.launch {
            delay(delayMs)
            if (!closed.get() && peer.isConnected && !peer.isClosed) {
                connectWebSocket(peer)
            }
        }
    }

    private fun fail(error: RdpTransportException) {
        Log.e(tag, "WebSocket transport failed permanently", error)
        listener?.onTransportFailed(error)
        close()
    }

    /** Tears down the WebSocket, the local peer socket, and the loopback
     *  listener. Safe to call multiple times. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        webSocket?.close(1000, "client closing")
        closePeerQuietly(localPeer)
        runCatching { serverSocket?.close() }
        httpClient?.dispatcher?.executorService?.shutdown()
        scope.cancel()
    }

    private fun closePeerQuietly(peer: Socket?) {
        runCatching { peer?.close() }
    }

    // ---- TLS (requirement #6) ----------------------------------------

    private fun buildHttpClient(url: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no read timeout, it's a long-lived stream
            .pingInterval(20, TimeUnit.SECONDS)

        if (config.subprotocol.isNotBlank()) {
            // OkHttp negotiates Sec-WebSocket-Protocol via the request
            // header we already add in connectWebSocket(); nothing extra
            // needed here, this branch exists for readers looking for it.
        }

        if (url.startsWith("wss://")) {
            val (sslSocketFactory, trustManager) = buildSslSocketFactory(url)
            builder.sslSocketFactory(sslSocketFactory, trustManager)
            if (config.tls.allowSelfSigned) {
                builder.hostnameVerifier { _, _ -> true }
            }
            if (config.tls.pinnedCertificateSha256.isNotEmpty()) {
                val host = runCatching { java.net.URI(url).host }.getOrNull()
                if (host != null) {
                    val pinnerBuilder = CertificatePinner.Builder()
                    config.tls.pinnedCertificateSha256.forEach { pin ->
                        val normalized = if (pin.startsWith("sha256/")) pin else "sha256/$pin"
                        pinnerBuilder.add(host, normalized)
                    }
                    builder.certificatePinner(pinnerBuilder.build())
                }
            }
        }

        return builder.build()
    }

    private fun buildSslSocketFactory(url: String): Pair<SSLSocketFactory, X509TrustManager> {
        val trustManager: X509TrustManager = when {
            (!config.tls.validateCertificate || config.tls.allowSelfSigned) &&
                config.tls.pinnedCertificateSha256.isEmpty() ->
                lenientOrTofuTrustManager(url)
            !config.tls.validateCertificate || config.tls.allowSelfSigned ->
                // Explicit SPKI pins are already configured (enforced
                // separately via OkHttp's CertificatePinner in
                // buildHttpClient) — real protection comes from those, so
                // this stays a plain trust-all rather than layering a
                // second, redundant TOFU identity check on top.
                lenientTrustManager(config.tls.customCaCertificatesPem)
            config.tls.customCaCertificatesPem.isNotEmpty() ->
                customCaTrustManager(config.tls.customCaCertificatesPem)
            else -> systemTrustManager()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        return sslContext.socketFactory to trustManager
    }

    private fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * SECURITY FIX (TLS-TOFU-PARITY): the actual trust manager used for
     * `allowSelfSigned`/`validateCertificate = false` when no explicit pin
     * is configured — see [AmtClient]'s `httpClient` doc comment for the
     * full reasoning this mirrors. Before this fix, this path always
     * returned [lenientTrustManager]'s blind trust-all — meaning *any*
     * certificate was accepted on *every* connection, with no fingerprint
     * pinning and no detection of a later-substituted (MITM) certificate.
     * Now the first certificate seen for this gateway's host:port is
     * pinned automatically via [TofuTrustManager], and every later
     * connection must present that exact same certificate.
     */
    private fun lenientOrTofuTrustManager(url: String): X509TrustManager {
        val identity = runCatching { java.net.URI(url).let { "${it.host}:${if (it.port > 0) it.port else 443}" } }
            .getOrNull()
        // SECURITY FIX (TLS-TOFU-NO-FALLBACK): a missing appContext (or an
        // unparseable URL) used to fall back to a trust-all X509TrustManager
        // (every certificate accepted, no pinning, no MITM detection) —
        // exactly the vulnerability TofuTrustManager exists to close. Fail
        // closed instead of silently downgrading to an insecure connection.
        return identity?.let { appContext?.let { ctx -> TofuTrustManager(ctx, identity) } }
            ?: throw IllegalStateException(
                "allowSelfSigned/validateCertificate=false is set for '$url' but TOFU pinning " +
                    "could not be set up (no appContext was supplied, or the URL's host could not " +
                    "be parsed) — refusing to connect with a trust-all fallback. Pass appContext " +
                    "to RdpWebSocketTransport's constructor.",
            )
    }

    /** Trusts the system store plus any PEM CAs supplied in [pems]. Used
     *  for [RdpWebSocketConfig.TlsOptions.customCaCertificatesPem] when
     *  the user has NOT also opted into [RdpWebSocketConfig.TlsOptions.allowSelfSigned] —
     *  i.e. "trust my private CA, but still validate everything else
     *  normally". */
    private fun customCaTrustManager(pems: List<String>): X509TrustManager {
        val cf = CertificateFactory.getInstance("X.509")
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
        }
        // Seed with the system store's trust anchors first.
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTmf.init(null as KeyStore?)
        pems.forEachIndexed { i, pem ->
            val cert = cf.generateCertificate(pem.byteInputStream()) as X509Certificate
            keyStore.setCertificateEntry("custom-ca-$i", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        val customTm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        return CompositeTrustManager(listOf(systemTm, customTm))
    }

    /** Requirement #6 "self-signed certificates": trusts everything, but
     *  the connection is still TLS-encrypted (this only disables chain
     *  validation, not encryption). Only reachable when the user
     *  explicitly set [RdpWebSocketConfig.TlsOptions.allowSelfSigned] or
     *  [RdpWebSocketConfig.TlsOptions.validateCertificate] = false — never
     *  the default. As of the TLS-TOFU-PARITY fix, this is no longer the
     *  primary handler for that case (see [lenientOrTofuTrustManager]) —
     *  it now only fires either as that function's no-appContext fallback,
     *  or when [RdpWebSocketConfig.TlsOptions.pinnedCertificateSha256] is
     *  already configured, in which case OkHttp's `CertificatePinner`
     *  (added separately in [buildHttpClient]) is the real protection and
     *  a redundant TOFU identity check here would add nothing. */
    private fun lenientTrustManager(customCaPems: List<String>): X509TrustManager =
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

    private class CompositeTrustManager(private val delegates: List<X509TrustManager>) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            val errors = mutableListOf<Exception>()
            for (d in delegates) {
                try { d.checkClientTrusted(chain, authType); return } catch (e: Exception) { errors += e }
            }
            throw errors.lastOrNull() ?: java.security.cert.CertificateException("untrusted")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val errors = mutableListOf<Exception>()
            for (d in delegates) {
                try { d.checkServerTrusted(chain, authType); return } catch (e: Exception) { errors += e }
            }
            throw errors.lastOrNull() ?: java.security.cert.CertificateException("untrusted")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegates.flatMap { it.acceptedIssuers.asList() }.toTypedArray()
    }

    companion object {
        /** Utility for the settings UI: computes a pin string
         *  ("sha256/base64...") from a PEM certificate, so users pinning a
         *  cert from a file don't have to run openssl by hand. */
        fun sha256PinFromPem(pem: String): String {
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(pem.byteInputStream()) as X509Certificate
            val spki = cert.publicKey.encoded
            val digest = MessageDigest.getInstance("SHA-256").digest(spki)
            return "sha256/" + digest.toByteString().base64()
        }
    }
}
