package com.systemsgo.hex.proxmox

import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * PROXMOX-API FEATURE (Part 2/N): the loopback bridge [ProxmoxApiClient.vncWebSocketUrl]'s
 * doc comment promises — lets this app's existing plain-TCP [com.systemsgo.hex.vnc.protocol.VncClient]
 * consume Proxmox's `wss://` noVNC console without teaching that client a
 * second transport.
 *
 * Same "ServerSocket + relay" shape as
 * [com.systemsgo.hex.rdp.transport.RdpWebSocketTransport] (that class's doc
 * comment explains the "why a loopback bridge, not a native change"
 * reasoning in full — it applies here identically): this class opens a
 * [ServerSocket] on 127.0.0.1 on an ephemeral port, hands that port back via
 * [Listener.onBridgeReady], then once [com.systemsgo.hex.vnc.protocol.VncClient]
 * connects to it (exactly like it would to a real VNC server), relays raw
 * bytes bidirectionally between that socket and a real RFC 6455 WebSocket
 * (via OkHttp) carrying Proxmox's binary-framed RFB stream.
 *
 * One instance per console session — not reused across guests/reconnects.
 * Caller owns the lifecycle: call [stop] when the VNC session ends (screen
 * onCleared/onDestroy), same as it would tear down any other transport.
 */
class ProxmoxVncBridge(
    private val wsUrl: String,
    private val headers: Map<String, String>,
    private val acceptSelfSignedCertificate: Boolean,
) {
    interface Listener {
        /** The loopback socket is listening — point [com.systemsgo.hex.vnc.protocol.VncClient] at 127.0.0.1:[localPort]. */
        fun onBridgeReady(localPort: Int)
        /** The bridge failed before or during the relay (bad ticket, TLS failure, network drop, ...). */
        fun onBridgeFailed(error: Throwable)
        /** The relay ended normally (either side closed). */
        fun onBridgeClosed()
    }

    private val tag = "ProxmoxVncBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var webSocket: WebSocket? = null
    private val writeLock = Any()

    fun start(listener: Listener) {
        scope.launch {
            try {
                val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                serverSocket = server
                listener.onBridgeReady(server.localPort)

                // Blocks until VncClient dials in — same "accept once, this
                // is a single-session relay, not a listening daemon" shape
                // as RdpWebSocketTransport.
                val socket = server.accept()
                clientSocket = socket
                connectWebSocket(socket, listener)
            } catch (e: Exception) {
                if (!closed.get()) {
                    Log.w(tag, "Bridge setup failed", e)
                    listener.onBridgeFailed(e)
                }
            }
        }
    }

    private fun connectWebSocket(socket: Socket, listener: Listener) {
        val httpClient = buildHttpClient()
        val requestBuilder = Request.Builder().url(wsUrl)
        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }

        webSocket = httpClient.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Pump: local TCP socket -> WebSocket (binary frames).
                    scope.launch {
                        try {
                            val input = socket.getInputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (!closed.get()) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                webSocket.send(buffer.copyOf(n).toByteString())
                            }
                        } catch (e: Exception) {
                            Log.d(tag, "socket->ws pump ended: ${e.message}")
                        } finally {
                            stop()
                            listener.onBridgeClosed()
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Pump: WebSocket -> local TCP socket.
                    try {
                        synchronized(writeLock) { socket.getOutputStream().write(bytes.toByteArray()) }
                    } catch (e: Exception) {
                        Log.d(tag, "ws->socket write failed: ${e.message}")
                        webSocket.close(1000, null)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!closed.get()) {
                        Log.w(tag, "WebSocket failed", t)
                        listener.onBridgeFailed(t)
                    }
                    stop()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    stop()
                    listener.onBridgeClosed()
                }
            },
        )
    }

    /** Same trust-all-when-opted-in shape as [com.systemsgo.hex.proxmox.protocol.ProxmoxApiClient]'s own HTTP client — Proxmox's `pveproxy` almost always serves a self-signed cert out of the box. */
    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // WebSocket relays are long-lived by nature — no read timeout,
            // matching RdpWebSocketTransport's own websocket client config.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)

        if (acceptSelfSignedCertificate) {
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

    fun stop() {
        if (closed.getAndSet(true)) return
        runCatching { webSocket?.close(1000, null) }
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        scope.cancel()
    }
}
