package com.systemsgo.hex.netconf.protocol

import android.util.Log
import com.jcraft.jsch.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.net.SocketFactory

/**
 * NETCONF CALL HOME FEATURE (RFC 8071, Part 12): a [com.jcraft.jsch.Proxy]
 * that does not actually proxy anything — it hands JSch the streams of a
 * [Socket] that some *other* piece of code (here, [NetconfCallHomeListener])
 * already accepted, instead of dialing out itself. This is the entire
 * mechanism by which [NetconfClient] can run as an ordinary SSH *client*
 * (auth, kex, "netconf" subsystem channel — see [NetconfClient.doConnect])
 * over a TCP connection the *device* initiated, which is what RFC 8071 §3
 * means by "Call Home": only the direction of the initial TCP SYN is
 * reversed, not which side plays SSH client vs. SSH server.
 *
 * JSch's own outbound-dial code path (`Session.connect()` when no Proxy is
 * set) always calls `new Socket(host, port)` itself; setting a [Proxy] is
 * the only extension point JSch exposes for substituting a different
 * transport, so this is the standard trick for wiring JSch to a
 * pre-existing connection rather than a private fork or reflection into
 * JSch internals.
 */
class NetconfCallHomeProxy(private val socket: Socket) : Proxy {
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** [host]/[port]/[socket_factory] are ignored — see class doc comment. JSch calls this once, synchronously, at the very start of [com.jcraft.jsch.Session.connect]. */
    override fun connect(socket_factory: SocketFactory?, host: String?, port: Int, timeout: Int) {
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    override fun getInputStream(): InputStream = input
        ?: throw IllegalStateException("NetconfCallHomeProxy.getInputStream() called before connect()")
    override fun getOutputStream(): OutputStream = output
        ?: throw IllegalStateException("NetconfCallHomeProxy.getOutputStream() called before connect()")
    override fun getSocket(): Socket = socket
    override fun close() {
        runCatching { socket.close() }
    }
}

/** Common shape of [NetconfCallHomeListener] and [NetconfCallHomeTlsListener] — lets `NetconfCallHomeService` hold both transports' listeners in one map without casting. */
interface CallHomeListenerHandle {
    val isListening: Boolean
    fun start(): Boolean
    fun stop()
}

/**
 * Which RFC 8071 Call Home variant accepted a [NetconfCallHomeConnection] —
 * [SSH] (RFC 6242 framing over an SSH `netconf` subsystem channel, the
 * original Part 12 implementation, IANA port 4334/`netconf-ch-ssh`) or
 * [TLS] (RFC 7589 framing directly over a TLS record layer, IANA port
 * 4335/`netconf-ch-tls`). Either way [NetconfCallHomeConnection.socket] is
 * a bare, not-yet-secured [Socket] — for [TLS] it's [NetconfClient] (not
 * the listener) that performs the TLS handshake once a profile/identity is
 * known to key TOFU pinning by, exactly mirroring how [NetconfClient]
 * already performs the SSH handshake for [SSH] rather than the listener
 * doing it.
 */
enum class CallHomeTransport { SSH, TLS }

/** One inbound TCP connection accepted by [NetconfCallHomeListener]/[NetconfCallHomeTlsListener], not yet matched to a profile. */
data class NetconfCallHomeConnection(
    val socket: Socket,
    val remoteAddress: String,
    val localPort: Int,
    val transport: CallHomeTransport = CallHomeTransport.SSH,
    val acceptedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Shared accept-loop-with-graceful-shutdown engine behind both
 * [NetconfCallHomeListener] (SSH) and [NetconfCallHomeTlsListener] (TLS).
 * Deliberately dumb, same as the two classes wrapping it: this does not
 * know about [NetconfClient], [com.systemsgo.hex.data.model.RdpProfile],
 * or which device is allowed to connect — that matching policy lives in
 * `NetconfCallHomeService`. Hoisted out once the TLS variant needed the
 * exact same bind/accept/hand-off/stop shape as the original SSH listener,
 * so that shape has exactly one implementation instead of two copies that
 * could drift.
 */
private class CallHomeAcceptEngine(
    private val listenPort: Int,
    private val transport: CallHomeTransport,
    private val logTag: String,
    private val onAccept: suspend (NetconfCallHomeConnection) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var isListening: Boolean = false
        private set

    /** Starts the accept loop. Returns false (and leaves [isListening] false) if the port couldn't be bound — e.g. already in use by another listener or a different app. */
    fun start(): Boolean {
        val srv = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(listenPort))
            }
        } catch (e: Exception) {
            Log.w(logTag, "Failed to bind Call Home ($transport) listener on port $listenPort", e)
            return false
        }
        serverSocket = srv
        isListening = true
        acceptJob = scope.launch { acceptLoop(srv) }
        return true
    }

    private suspend fun acceptLoop(srv: ServerSocket) {
        while (!srv.isClosed) {
            val socket = try {
                srv.accept()
            } catch (e: SocketException) {
                // Expected on stop() closing the socket out from under accept() — not an error.
                break
            } catch (e: Exception) {
                Log.w(logTag, "Call Home ($transport) accept() failed on port $listenPort", e)
                continue
            }
            try {
                socket.tcpNoDelay = true
                val conn = NetconfCallHomeConnection(
                    socket = socket,
                    remoteAddress = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "unknown",
                    localPort = listenPort,
                    transport = transport,
                )
                Log.i(logTag, "Call Home ($transport) connection accepted on port $listenPort from ${conn.remoteAddress}")
                // Handed off to its own coroutine — onAccept typically drives
                // a full SSH or TLS handshake + <hello> exchange (seconds,
                // not milliseconds), and accept() for the *next* device must
                // not be blocked on that if two devices share one listener.
                scope.launch {
                    try {
                        onAccept(conn)
                    } catch (e: Exception) {
                        Log.w(logTag, "Call Home ($transport) onAccept handler failed — closing socket", e)
                        runCatching { socket.close() }
                    }
                }
            } catch (e: Exception) {
                Log.w(logTag, "Failed to prepare accepted Call Home ($transport) socket — closing", e)
                runCatching { socket.close() }
            }
        }
    }

    fun stop() {
        isListening = false
        runCatching { serverSocket?.close() }
        acceptJob?.cancel()
        serverSocket = null
        acceptJob = null
    }
}

/**
 * NETCONF CALL HOME FEATURE (Part 12): the listening half of RFC 8071's SSH
 * variant — binds one [ServerSocket] on [listenPort] (the app-configured
 * port a device's `call-home` client-list entry is configured to dial —
 * RFC 8071 assigns 4334/TCP, `netconf-ch-ssh`, as the IANA default, but
 * Call Home is explicitly configurable per device, hence this being a
 * per-profile setting rather than a hardcoded constant) and hands every
 * accepted [Socket], tagged [CallHomeTransport.SSH], to [onAccept].
 */
class NetconfCallHomeListener(
    private val listenPort: Int,
    private val onAccept: suspend (NetconfCallHomeConnection) -> Unit,
) : CallHomeListenerHandle {
    companion object {
        private const val TAG = "NetconfCallHomeListener"
        /** RFC 8071 §3.1 / IANA: the assigned default port for NETCONF Call Home over SSH. */
        const val DEFAULT_PORT = 4334
    }

    private val engine = CallHomeAcceptEngine(listenPort, CallHomeTransport.SSH, TAG, onAccept)

    override val isListening: Boolean get() = engine.isListening
    override fun start(): Boolean = engine.start()
    override fun stop() = engine.stop()
}

/**
 * NETCONF CALL HOME FEATURE (Part 13): the listening half of RFC 8071's TLS
 * variant (`netconf-ch-tls`, IANA port 4335/TCP) — same bind/accept/hand-off
 * shape as [NetconfCallHomeListener], the only difference being every
 * accepted [Socket] is tagged [CallHomeTransport.TLS], so downstream
 * ([NetconfClient] via `NetconfCallHomeService`) performs a TLS handshake
 * (RFC 7589 §7 — this app is still the TLS *client*, the device is still
 * the TLS *server*, even though the device dialed the TCP connection) and
 * runs NETCONF's chunked/EOM framing (RFC 6242 §4's framing text applies to
 * NETCONF/TLS unchanged — see RFC 7589 §7) directly over the TLS record
 * layer instead of over an SSH subsystem channel.
 */
class NetconfCallHomeTlsListener(
    private val listenPort: Int,
    private val onAccept: suspend (NetconfCallHomeConnection) -> Unit,
) : CallHomeListenerHandle {
    companion object {
        private const val TAG = "NetconfCallHomeTlsListener"
        /** RFC 8071 §3.1 / IANA: the assigned default port for NETCONF Call Home over TLS. */
        const val DEFAULT_PORT = 4335
    }

    private val engine = CallHomeAcceptEngine(listenPort, CallHomeTransport.TLS, TAG, onAccept)

    override val isListening: Boolean get() = engine.isListening
    override fun start(): Boolean = engine.start()
    override fun stop() = engine.stop()
}
