package com.systemsgo.hex.vsphere

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
 * VMWARE-VSPHERE FEATURE (Part 3/N): the loopback bridge
 * [com.systemsgo.hex.vsphere.protocol.VSphereApiClient.acquireConsoleTicket]'s
 * doc comment flagged as not built yet — lets this app's existing plain-TCP
 * [com.systemsgo.hex.vnc.protocol.VncClient] consume a vCenter/ESXi WEBMKS
 * console without teaching that client a second transport. Same
 * "ServerSocket + relay" shape as [com.systemsgo.hex.proxmox.ProxmoxVncBridge]
 * — that class's doc comment (and
 * [com.systemsgo.hex.rdp.transport.RdpWebSocketTransport] before it) explains
 * the "why a loopback bridge, not a native change" reasoning in full; it
 * applies here identically.
 *
 * ## Is this actually the same wire format as Proxmox's noVNC bridge?
 * Yes, confirmed independently by three sources rather than assumed by
 * analogy: VMware's own vSphere 7.0 release notes describe the `webmks`
 * ticket type as offering "a VNC-over-websocket connection" (this is
 * literally why ESXi 7.0 removed its legacy direct-TCP VNC server — `webmks`
 * is its websocket-tunneled replacement, not a different protocol); Apache
 * Guacamole's own vSphere-support ticket (GUACAMOLE-1641) describes
 * `guacd`'s approach as "connect to the appropriate ESXi server and port to
 * setup the websocket and then pass binary frames containing the VNC
 * protocol" — the same raw-RFB-framed-as-binary-WebSocket-messages shape
 * Proxmox's noVNC frontend uses; and the HashiCorp Packer vsphere-iso issue
 * thread quotes the same VMware documentation. So this bridge is a
 * near-verbatim copy of [com.systemsgo.hex.proxmox.ProxmoxVncBridge] with
 * vSphere-specific naming/TLS-toggle wiring, not a new protocol
 * implementation.
 *
 * ## The one open question: RFB-level authentication
 * Unlike Proxmox (where [com.systemsgo.hex.proxmox.protocol.ProxmoxApiClient.vncWebSocketUrl]'s
 * doc explains the vncproxy ticket doubles as the RFB VNC-Auth password),
 * VMware's public docs don't say whether the WEBMKS ticket similarly stands
 * in for the guest's own VNC security below the websocket layer. What's
 * confirmed instead (VMware HTML Console SDK docs + a public CFME/ManageIQ
 * bug — "VMware WebMKS Console: VNC Authentication not Implemented",
 * Red Hat Bugzilla #1490641) is that most VMs run with VNC "Server security
 * type 1" (no in-band RFB auth — the ticket alone gates access) and WEBMKS
 * connects cleanly; VMs deliberately configured for RFB-level VNC
 * Authentication (security type 2, a separate, guest-specific password this
 * API doesn't expose anywhere) are a documented edge case even other
 * WEBMKS integrations stumble on. This app's own VNC engine already speaks
 * standard RFB VNC-Auth (used for Proxmox's own ticket-as-password flow), so
 * if a VM does present that challenge, [com.systemsgo.hex.ui.screens.VSphereManagementViewModel.openConsole]
 * still offers the WEBMKS ticket as the RFB password on the (unconfirmed)
 * chance it's accepted — same "best effort, not silently misbehaving"
 * posture as this file's protocol-accuracy notes elsewhere — but a
 * genuinely separate guest VNC password, if one is configured, isn't
 * something this client can supply automatically.
 *
 * One instance per console session — not reused across VMs/reconnects.
 * Caller owns the lifecycle: call [stop] when the console session ends
 * (screen onCleared/onDestroy), same as
 * [com.systemsgo.hex.proxmox.ProxmoxVncBridge].
 */
class VSphereVncBridge(
    private val wsUrl: String,
    private val acceptSelfSignedCertificate: Boolean,
) {
    interface Listener {
        /** The loopback socket is listening — point [com.systemsgo.hex.vnc.protocol.VncClient] at 127.0.0.1:[localPort]. */
        fun onBridgeReady(localPort: Int)
        /** The bridge failed before or during the relay (bad/expired ticket, TLS failure, network drop, ...). */
        fun onBridgeFailed(error: Throwable)
        /** The relay ended normally (either side closed). */
        fun onBridgeClosed()
    }

    private val tag = "VSphereVncBridge"
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
                // as ProxmoxVncBridge/RdpWebSocketTransport.
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
        // The webSocketUrl vCenter/ESXi hands back from console/tickets is
        // already the complete wss:// URL with the one-time ticket embedded
        // (see VSphereConsoleTicket's doc comment) — no separate query param
        // or auth header needs adding here, unlike Proxmox's vncticket which
        // this app has to append itself.
        val request = Request.Builder().url(wsUrl).build()

        webSocket = httpClient.newWebSocket(
            request,
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

    /** Same trust-all-when-opted-in shape as [com.systemsgo.hex.vsphere.protocol.VSphereApiClient]'s own HTTP client — vCenter/ESXi almost always serves a self-signed cert out of the box. */
    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // WebSocket relays are long-lived by nature — no read timeout,
            // matching ProxmoxVncBridge/RdpWebSocketTransport's own
            // websocket client config.
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
