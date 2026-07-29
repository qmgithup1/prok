package com.systemsgo.hex.netconf.protocol

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelSubsystem
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.systemsgo.hex.proxy.PacProxyResolver
import com.systemsgo.hex.ssh.protocol.SshAuthMode
import com.systemsgo.hex.ssh.protocol.SshTunnelCredentials
import com.systemsgo.hex.ssh.protocol.SshTunnelManager
import com.systemsgo.hex.ssh.protocol.TofuHostKeyRepository
import com.systemsgo.hex.ssh.protocol.toJschProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.Element
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.xml.parsers.DocumentBuilderFactory

/**
 * NETCONF FEATURE (Part 1/N — core transport): authentication method for the
 * underlying SSH transport (RFC 6242 §3 requires SSH; NETCONF itself has no
 * transport-independent auth of its own — every credential type below is
 * really "how does the SSH layer authenticate", exactly like [SshAuthMode]).
 *
 * PASSWORD / PRIVATE_KEY / KEYBOARD_INTERACTIVE map 1:1 onto what
 * [com.systemsgo.hex.ssh.protocol.SshClient] already supports. Key *type*
 * (RSA / Ed25519 / ECDSA, OpenSSH vs. PEM container, encrypted vs. plain) is
 * not a separate enum value here — mwiede/jsch (this project's JSch fork,
 * see gradle/libs.versions.toml) auto-detects all of those from the key
 * blob itself in `addIdentity()`, the same as it does for SSH terminal
 * sessions today.
 *
 * Certificate authentication is PRIVATE_KEY plus [NetconfCredentials.
 * openSshCertificate] populated: an OpenSSH certificate (the contents of an
 * `id_ed25519-cert.pub`/`id_rsa-cert.pub` file, signed by a CA the NETCONF
 * server trusts via `TrustedUserCAKeys`) is — at the wire-protocol level —
 * just a specially-formatted SSH public key, so it rides the exact same
 * `publickey` auth method mwiede/jsch already implements; no separate code
 * path is needed the way it would be for e.g. X.509/TLS client-cert auth.
 */
enum class NetconfAuthMode { PASSWORD, PRIVATE_KEY, KEYBOARD_INTERACTIVE }

/**
 * NETCONF FEATURE (Part 1/N): credentials + key material for one NETCONF-
 * over-SSH session. Mirrors [com.systemsgo.hex.ssh.protocol.SshCredentials]'s
 * CharArray-for-secrets shape (REM-2 FIX in that file) for the identical
 * reason — plaintext password/passphrase material should not sit in an
 * interned, ungovernable JVM String any longer than JSch needs it.
 */
class NetconfCredentials(
    val host: String,
    val port: Int = 830, // IANA-assigned NETCONF-over-SSH port, RFC 6242 §10.1
    val username: String,
    val authMode: NetconfAuthMode,
    password: String = "",
    privateKeyPem: String = "",
    privateKeyPassphrase: String = "",
    /** OpenSSH certificate blob — see [NetconfAuthMode]'s doc comment. Empty for plain pubkey auth. */
    val openSshCertificate: String = "",
    /**
     * CALL-HOME-TLS FEATURE (RFC 7589 §7): optional PEM bundle — an X.509
     * client certificate immediately followed by its matching PKCS#8
     * private key (`-----BEGIN PRIVATE KEY-----`; PKCS#1/SEC1
     * `RSA PRIVATE KEY`/`EC PRIVATE KEY` blocks are not supported — convert
     * with `openssl pkcs8 -topk8 -nocrypt`) — used only when this client is
     * built over a TLS transport (see [NetconfClient]'s `tlsSocket`
     * parameter). Ignored entirely for NETCONF/SSH sessions, where
     * client-side identity comes from [authMode] instead. Blank means the
     * TLS handshake proceeds without a client certificate.
     */
    tlsClientCertificatePem: String = "",
) {
    val password: CharArray = password.toCharArray()
    val privateKeyPem: CharArray = privateKeyPem.toCharArray()
    val privateKeyPassphrase: CharArray = privateKeyPassphrase.toCharArray()
    val tlsClientCertificatePem: CharArray = tlsClientCertificatePem.toCharArray()

    fun zero() {
        password.fill('\u0000')
        privateKeyPem.fill('\u0000')
        privateKeyPassphrase.fill('\u0000')
        tlsClientCertificatePem.fill('\u0000')
    }
}

/** One SSH jump-hop en route to the NETCONF target — same shape/order rules as [com.systemsgo.hex.data.model.SshJumpHop]. */
data class NetconfJumpHop(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMode: SshAuthMode,
    val password: String = "",
    val privateKeyPem: String = "",
    val privateKeyPassphrase: String = "",
)

enum class NetconfSessionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, AUTH_FAILED, ERROR }

/** One `<capability>` entry from either side's `<hello>`. */
data class NetconfCapability(val uri: String) {
    /** The bit after the last ':' for base capabilities, e.g. "1.1" from "urn:ietf:params:netconf:base:1.1"; the full URI otherwise. */
    val shortLabel: String
        get() = uri.substringAfterLast(':').ifBlank { uri }
}

data class NetconfHelloInfo(
    val sessionId: Int?,
    val capabilities: List<NetconfCapability>,
    val supportsBase11: Boolean,
    val supportedDatastores: List<String>,
)

/** Live counters for the Session UI's connection-statistics panel. */
data class NetconfConnectionStats(
    val rpcCount: Long = 0,
    val notificationCount: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val connectedSinceMs: Long? = null,
    val lastLatencyMs: Long = 0,
    val reconnectCount: Int = 0,
)

/** Raw XML that crossed the wire in either direction — feeds the RPC log / XML viewer (later phase) without this class needing to know about them. */
data class NetconfWireMessage(val outbound: Boolean, val xml: String, val timestampMs: Long = System.currentTimeMillis())

class NetconfException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * NETCONF FEATURE (Part 1/N — core transport): native NETCONF-over-SSH
 * (RFC 6242) client. Architecturally this mirrors
 * [com.systemsgo.hex.ssh.protocol.SshClient] (JSch session + TOFU host-key
 * verification + jump-host chaining via [SshTunnelManager] + PAC/static
 * outbound proxy) but does **not** implement
 * [com.systemsgo.hex.remote.RemoteSessionClient] — NETCONF is a structured
 * RPC protocol with no framebuffer or terminal-byte-stream concept, so it
 * follows the same standalone-client shape as
 * [com.systemsgo.hex.redfish.protocol.RedfishClient] /
 * [com.systemsgo.hex.ipmi.protocol.IpmiClient] instead, meant to be driven
 * by its own session screen (a future NetconfSessionActivity, the NETCONF
 * counterpart of BmcManagementScreen) rather than [RdpSessionActivity].
 *
 * This class covers exactly the "core transport" slice of the full NETCONF
 * feature: connection + auth + hello/capability exchange + automatic
 * reconnect + health monitoring + statistics. The full RPC operation set
 * (get/get-config/edit-config/commit/lock/.../create-subscription) is the
 * next layer, built on top of [sendRawRpc] below — that generic
 * "send this rpc body, get back the matching rpc-reply" primitive already
 * has to exist here regardless, since the hello exchange and the keepalive
 * probe both need it.
 *
 * SESSION-RESUME NOTE: unlike Mosh (which this app's `mosh/` module already
 * supports for terminals), NETCONF has no protocol-level notion of
 * transport-independent session roaming — RFC 6241 §2 ties a NETCONF
 * session's lifetime directly to its transport connection; when the SSH
 * connection drops, the server discards the session (and any lock it held)
 * unconditionally. What this class calls "session resume" is therefore:
 * automatic reconnect using the same [NetconfCredentials] + capabilities,
 * followed by best-effort re-acquisition of any datastore lock the caller
 * held at disconnect time (tracked via [notifyLockAcquired]/
 * [notifyLockReleased]) — not a resumption of the *same* session-id, which
 * the server always reassigns.
 */
class NetconfClient(
    private val credentials: NetconfCredentials,
    private val appContext: Context,
    /** Ordered jump-host chain, first-hop-first — empty for a direct connection. Same ordering convention as [com.systemsgo.hex.data.model.SshJumpHop]. */
    private val jumpHops: List<NetconfJumpHop> = emptyList(),
    /**
     * CALL-HOME FEATURE (RFC 8071): when non-null, this client does not dial
     * out at all — [credentials].host/port/jumpHops/outboundProxy are all
     * ignored for the purpose of *establishing* the TCP connection, and the
     * already-accepted [java.net.Socket] handed to us by
     * [NetconfCallHomeListener] is used instead, wrapped in
     * [NetconfCallHomeProxy] so JSch runs the exact same SSH client
     * state-machine (auth, kex, the "netconf" subsystem channel, hello
     * exchange) over it that it would over a normal outbound socket. RFC
     * 8071 §3 is explicit that Call Home reverses only *which side dials*;
     * the device is still the SSH server and this app is still the SSH
     * client for every purpose after the TCP handshake, so nothing else in
     * this class (framing, RPC engine, reconnect/lock-resume, health
     * monitor) needs to know this connection came in "backwards" — that's
     * the whole point of isolating the reversal to [doConnect]'s dial step.
     */
    private val preAcceptedSocket: java.net.Socket? = null,
    /**
     * CALL-HOME FEATURE: stable per-profile identity string used as the
     * "host" JSch/[TofuHostKeyRepository] key the device's SSH host key is
     * pinned under, since a Call Home connection has no meaningful
     * host:port of its own to key TOFU by (the source IP is whatever the
     * device's outbound route happens to be, and RFC 8071 explicitly does
     * not tie a Call Home identity to it). Ignored when [preAcceptedSocket]
     * is null. Callers (see [NetconfProfileMapper.buildClient]) pass
     * something like `"callhome:${profile.id}"` so re-pinning survives the
     * device reconnecting from a different address.
     */
    private val callHomeIdentity: String? = null,
    /**
     * CALL-HOME-TLS FEATURE (RFC 8071's `netconf-ch-tls` variant, RFC 7589
     * transport): the TLS-transport counterpart to [preAcceptedSocket] —
     * when non-null, this client skips SSH/JSch entirely (no [Session], no
     * `netconf` subsystem channel) and instead performs a TLS handshake
     * directly over this already-accepted, not-yet-secured socket, then
     * runs NETCONF framing straight over the resulting
     * [javax.net.ssl.SSLSocket]'s streams. Mutually exclusive with
     * [preAcceptedSocket] in practice (a connection is one transport or the
     * other — [NetconfCallHomeConnection.transport] is what a caller
     * inspects to decide which parameter to pass); both being non-null is
     * not a defined state and TLS wins if it somehow happened, since
     * [doConnect] checks this parameter first. [callHomeIdentity] is reused
     * unchanged as the TOFU pin key (see [NetconfTlsTofuTrustManager]) for
     * the device's TLS server certificate, the same way it already keys the
     * SSH TOFU host key for [preAcceptedSocket].
     */
    private val tlsSocket: java.net.Socket? = null,
    private val outboundProxy: PacProxyResolver.Resolved = PacProxyResolver.Resolved.Direct,
    private val connectTimeoutMs: Int = 15_000,
    /** SSH-level keepalive interval (server-alive-interval); NETCONF itself has no keepalive RPC in the base spec. */
    private val sshKeepAliveMs: Int = 15_000,
    private val compressionEnabled: Boolean = false,
    /** Client-advertised capabilities. base:1.0/1.1 are always included regardless of this list. */
    private val requestedCapabilities: List<String> = listOf(
        "urn:ietf:params:netconf:base:1.0",
        "urn:ietf:params:netconf:base:1.1",
    ),
    /** How often the background health probe (a lightweight `<get>` with an empty filter) runs while connected. */
    private val healthCheckIntervalMs: Long = 30_000,
    private val autoReconnect: Boolean = true,
    private val maxReconnectBackoffMs: Long = 30_000,
) {
    companion object {
        private const val TAG = "NetconfClient"
        private const val PREFS_TOFU_NETCONF = "systemsgo_tofu_netconf"
    }

    private val _sessionState = MutableStateFlow(NetconfSessionState.DISCONNECTED)
    val sessionState: StateFlow<NetconfSessionState> = _sessionState.asStateFlow()

    private val _hello = MutableStateFlow<NetconfHelloInfo?>(null)
    val hello: StateFlow<NetconfHelloInfo?> = _hello.asStateFlow()

    private val _stats = MutableStateFlow(NetconfConnectionStats())
    val stats: StateFlow<NetconfConnectionStats> = _stats.asStateFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val error: SharedFlow<String> = _error.asSharedFlow()

    /** Every `<rpc>`/`<rpc-reply>`/`<notification>` that crosses the wire, for the RPC log / XML viewer built on top of this client. */
    private val _wireMessages = MutableSharedFlow<NetconfWireMessage>(extraBufferCapacity = 256)
    val wireMessages: SharedFlow<NetconfWireMessage> = _wireMessages.asSharedFlow()

    /** Server-initiated `<notification>` payloads (create-subscription) — full RPC-engine phase populates filtering on top of this. */
    private val _notifications = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val notifications: SharedFlow<String> = _notifications.asSharedFlow()

    // SESSION-UI FEATURE: negotiated cipher/kex algorithm names for the
    // Session UI's connection-details panel. mwiede/jsch (the fork this
    // project depends on — see gradle/libs.versions.toml) does not expose
    // these as a stable public getter on every version, so they're read via
    // reflection against whichever accessor name that build actually has,
    // failing gracefully to null rather than a hard compile-time dependency
    // on a method signature this module can't verify offline. The one field
    // that IS guaranteed public API on every JSch/mwiede version —
    // [Session.getHostKey]'s [com.jcraft.jsch.HostKey.getType] — is used
    // directly for the SSH key algorithm instead of guessing.
    private fun reflectStringGetter(vararg names: String): String? {
        val s = session ?: return null
        for (n in names) {
            runCatching {
                val m = s.javaClass.getMethod(n)
                (m.invoke(s) as? String)?.let { if (it.isNotBlank()) return it }
            }
        }
        return null
    }
    val negotiatedCipher: String? get() = reflectStringGetter("getCipher", "getNegotiatedCipherC2S", "getNegotiatedCipher")
    val negotiatedKex: String? get() = reflectStringGetter("getKex", "getNegotiatedKex")
    val negotiatedHostKeyType: String? get() = runCatching { session?.hostKey?.type }.getOrNull()

    /** True for a client built over an already-accepted RFC 8071 Call Home socket, SSH or TLS — see [preAcceptedSocket]/[tlsSocket]'s doc comments. Read by [com.systemsgo.hex.netconf.protocol.NetconfCallHomeSessionRegistry] / the Session UI to label the connection appropriately (no dial target to show). */
    val isCallHome: Boolean get() = preAcceptedSocket != null || tlsSocket != null

    /** True when this session rides NETCONF/TLS (RFC 7589) rather than NETCONF/SSH (RFC 6242) — the Session UI's connection-details panel uses this to show a TLS-appropriate label instead of SSH cipher/kex names, which don't exist for this transport. */
    val isTlsTransport: Boolean get() = tlsSocket != null

    private var session: Session? = null
    private var jumpTunnel: SshTunnelManager? = null
    private var subsystem: ChannelSubsystem? = null
    /** CALL-HOME-TLS FEATURE: the [SSLSocket] wrapping [tlsSocket] once [doTlsConnect] has handshaked it — tracked separately from [tlsSocket] itself so [cleanup] can close whichever layer actually got established (handshake failure leaves this null, but the raw socket still needs closing). */
    private var secureTlsSocket: SSLSocket? = null
    private var frameReader: NetconfFrameReader? = null
    private var frameWriter: NetconfFrameWriter? = null

    private val messageIdCounter = AtomicInteger(1)
    private val pendingReplies = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val locksHeld = java.util.concurrent.CopyOnWriteArraySet<String>() // datastore names

    @Volatile private var ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var readLoopJob: Job? = null
    @Volatile private var healthJob: Job? = null
    @Volatile private var reconnecting = false
    @Volatile private var manuallyDisconnected = false

    // ── public API ────────────────────────────────────────────────────

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        manuallyDisconnected = false
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            _sessionState.value = NetconfSessionState.CONNECTING
            doConnect()
            _sessionState.value = NetconfSessionState.CONNECTED
            _stats.value = _stats.value.copy(connectedSinceMs = System.currentTimeMillis())
            readLoopJob = ioScope.launch { readLoop() }
            healthJob = ioScope.launch { healthMonitorLoop() }
            true
        } catch (e: com.jcraft.jsch.JSchException) {
            Log.w(TAG, "NETCONF connect failed (SSH layer)", e)
            _sessionState.value = if (e.message?.contains("Auth", ignoreCase = true) == true)
                NetconfSessionState.AUTH_FAILED else NetconfSessionState.ERROR
            _error.tryEmit(e.message ?: "SSH connection failed")
            cleanup()
            false
        } catch (e: Exception) {
            Log.w(TAG, "NETCONF connect failed", e)
            _sessionState.value = NetconfSessionState.ERROR
            _error.tryEmit(e.message ?: "NETCONF connection failed")
            cleanup()
            false
        }
    }

    private suspend fun doConnect() {
        // CALL-HOME-TLS FEATURE: entirely separate path — no JSch, no SSH
        // session/subsystem, just a TLS handshake directly over the
        // accepted socket followed by NETCONF framing on top of it. Checked
        // first and returns immediately so none of the SSH-specific setup
        // below (JSch, TofuHostKeyRepository, jump-host tunnelling) runs.
        if (tlsSocket != null) {
            doTlsConnect()
            return
        }

        val jsch = JSch()

        // CALL-HOME FEATURE: a Call Home connection has no fixed host:port
        // of its own (see [callHomeIdentity]'s doc comment above), so TOFU
        // is keyed by that stable per-profile identity string instead of
        // credentials.port/host — using a synthetic, never-network-visible
        // "port" (0) keeps [TofuHostKeyRepository.hostMapKey]'s "host:port"
        // formatting well-defined without implying any real port number.
        jsch.hostKeyRepository = if (preAcceptedSocket != null)
            TofuHostKeyRepository(appContext, 0, PREFS_TOFU_NETCONF)
        else
            TofuHostKeyRepository(appContext, credentials.port, PREFS_TOFU_NETCONF)

        var dialHost: String
        var dialPort: Int

        if (preAcceptedSocket != null) {
            // CALL-HOME FEATURE (RFC 8071): the device already dialed *us* —
            // jump hosts and the outbound proxy are meaningless here (there
            // is nothing left to tunnel; the TCP connection already exists)
            // — skip straight to wrapping the accepted socket. host/port
            // passed to JSch.getSession are never actually dialed (see
            // NetconfCallHomeProxy.connect below); they only need to be
            // non-blank/non-zero for JSch's own internal bookkeeping and are
            // never sent over the wire.
            dialHost = callHomeIdentity ?: "callhome"
            dialPort = 830
        } else {
            // JUMP-HOST FEATURE: reuse SshTunnelManager exactly as RDP/VNC/SSH-
            // over-jump-host already do — establish the full ProxyJump chain
            // first, then dial the *actual* NETCONF target through the local
            // port it forwards, with this client's own (independent) NETCONF
            // credentials, never the jump hops' credentials.
            dialHost = credentials.host
            dialPort = credentials.port
            if (jumpHops.isNotEmpty()) {
                val hopCreds = jumpHops.map {
                    SshTunnelCredentials(it.host, it.port, it.username, it.authMode, it.password, it.privateKeyPem, it.privateKeyPassphrase)
                }
                val mgr = SshTunnelManager(hopCreds, appContext, outboundProxy)
                jumpTunnel = mgr
                val result = mgr.openTunnel(credentials.host, credentials.port)
                dialHost = "127.0.0.1"
                dialPort = result.localPort
            }
        }

        if (credentials.authMode == NetconfAuthMode.PRIVATE_KEY && credentials.privateKeyPem.isNotEmpty()) {
            val pemBuf = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(credentials.privateKeyPem))
            val pemBytes = ByteArray(pemBuf.remaining()).also { pemBuf.get(it) }
            val passBytes: ByteArray? = if (credentials.privateKeyPassphrase.isNotEmpty()) {
                val buf = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(credentials.privateKeyPassphrase))
                ByteArray(buf.remaining()).also { buf.get(it) }
            } else null
            // Certificate auth: the OpenSSH certificate blob takes the slot
            // JSch calls "pubkey" — see NetconfAuthMode's doc comment for why
            // that's correct (a cert IS a specially-formatted public key).
            val certBytes = credentials.openSshCertificate.takeIf { it.isNotBlank() }?.toByteArray(Charsets.US_ASCII)
            try {
                jsch.addIdentity("hexnetconf-key", pemBytes, certBytes, passBytes)
            } finally {
                pemBytes.fill(0)
                passBytes?.fill(0)
                credentials.privateKeyPem.fill('\u0000')
                credentials.privateKeyPassphrase.fill('\u0000')
            }
        }

        val sess = jsch.getSession(credentials.username, dialHost, dialPort)
        if (preAcceptedSocket != null) {
            // CALL-HOME FEATURE: this Proxy is the entire reversal — JSch
            // thinks it's dialing dialHost:dialPort like any other session,
            // but NetconfCallHomeProxy.connect() ignores both arguments and
            // just hands JSch the streams of the socket the device already
            // opened to us. See NetconfCallHomeProxy's doc comment.
            sess.setProxy(NetconfCallHomeProxy(preAcceptedSocket))
        } else if (jumpHops.isEmpty()) {
            outboundProxy.toJschProxy()?.let { sess.setProxy(it) }
        }
        if (credentials.authMode == NetconfAuthMode.PASSWORD) {
            sess.setPassword(String(credentials.password))
            credentials.password.fill('\u0000')
        }
        sess.setConfig("StrictHostKeyChecking", "accept-new")
        sess.setConfig(
            "PreferredAuthentications",
            when (credentials.authMode) {
                NetconfAuthMode.PASSWORD -> "password,keyboard-interactive"
                NetconfAuthMode.PRIVATE_KEY -> "publickey"
                NetconfAuthMode.KEYBOARD_INTERACTIVE -> "keyboard-interactive,password"
            },
        )
        if (compressionEnabled) {
            sess.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
            sess.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
            sess.setConfig("compression_level", "6")
        }
        sess.userInfo = object : com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
            override fun promptKeyboardInteractive(
                destination: String?, name: String?, instruction: String?,
                prompt: Array<out String>?, echo: BooleanArray?,
            ): Array<String>? {
                // KEYBOARD_INTERACTIVE for NETCONF is used almost exclusively
                // for a plain password re-prompt (RADIUS/TACACS+-backed auth
                // on network gear) — answer every prompt with the supplied
                // password rather than opening a synchronous UI round-trip,
                // matching how headless automation clients (ncclient et al.)
                // handle it. A server asking a genuinely different interactive
                // question (OTP) with this auth mode will fail closed, same
                // as an empty answer would.
                val pw = String(credentials.password)
                return prompt?.map { pw }?.toTypedArray()
            }
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = null
            override fun promptPassword(message: String?) = false
            override fun promptPassphrase(message: String?) = false
            override fun promptYesNo(message: String?) = false
            override fun showMessage(message: String?) {}
        }
        sess.timeout = connectTimeoutMs
        sess.serverAliveInterval = sshKeepAliveMs
        sess.serverAliveCountMax = 3
        session = sess
        sess.connect(connectTimeoutMs)

        val ch = sess.openChannel("subsystem") as ChannelSubsystem
        ch.setSubsystem("netconf")
        ch.setPty(false)
        val out: OutputStream = ch.outputStream
        val `in`: InputStream = ch.inputStream
        ch.connect(connectTimeoutMs)
        subsystem = ch

        val reader = NetconfFrameReader(`in`)
        val writer = NetconfFrameWriter(out)
        frameReader = reader
        frameWriter = writer

        performHelloExchange(reader, writer)
    }

    /**
     * CALL-HOME-TLS FEATURE (RFC 8071 `netconf-ch-tls` / RFC 7589): the TLS
     * counterpart of the SSH branch of [doConnect] above — wraps [tlsSocket]
     * (an already-accepted, still-plaintext socket handed to us by
     * [NetconfCallHomeTlsListener]) in a TLS handshake and then runs the
     * exact same [performHelloExchange] on top of the resulting
     * [SSLSocket]'s streams, since NETCONF framing itself (RFC 6242 §4's
     * EOM/chunked delimiters) is transport-agnostic — RFC 7589 §7 says
     * explicitly that NETCONF/TLS reuses it unchanged rather than defining
     * a third framing scheme.
     */
    private fun doTlsConnect() {
        val socket = tlsSocket!!
        val identity = callHomeIdentity ?: "callhome-tls"

        val trustManager = NetconfTlsTofuTrustManager(appContext, identity)
        val keyManagers = buildTlsClientKeyManagers(credentials.tlsClientCertificatePem)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, arrayOf<TrustManager>(trustManager), null)

        // RFC 7589 §7: Call Home reverses only which side dials the TCP
        // connection — this app remains the TLS *client* and the device
        // remains the TLS *server* for the handshake itself, so the
        // already-accepted plaintext socket is wrapped and driven as a TLS
        // client (useClientMode = true), exactly mirroring how
        // NetconfCallHomeProxy keeps this app as the SSH client for the SSH
        // variant. [identity] (not a real host:port — see its doc comment
        // on [callHomeIdentity]) is passed only for SSLSession bookkeeping/
        // SNI, since the socket is already connected and nothing is dialed.
        val sslSocket = sslContext.socketFactory
            .createSocket(socket, identity, socket.port.takeIf { it > 0 } ?: 830, true) as SSLSocket
        sslSocket.useClientMode = true
        sslSocket.soTimeout = connectTimeoutMs
        try {
            sslSocket.startHandshake()
        } catch (e: javax.net.ssl.SSLException) {
            // NetconfTlsTofuTrustManager throws NetconfTlsPinMismatchException
            // (a CertificateException) on a pin mismatch, which the TLS
            // provider wraps in an SSLHandshakeException — surface the
            // original message (the MITM warning) rather than a generic
            // "handshake failed".
            throw NetconfException(e.cause?.message ?: e.message ?: "TLS handshake failed")
        }
        secureTlsSocket = sslSocket

        val reader = NetconfFrameReader(sslSocket.inputStream)
        val writer = NetconfFrameWriter(sslSocket.outputStream)
        frameReader = reader
        frameWriter = writer

        performHelloExchange(reader, writer)
    }

    /**
     * Shared by both transports (see [doConnect]'s SSH branch and
     * [doTlsConnect]): the RFC 6241 §8.1 `<hello>` exchange, always
     * EOM-framed per RFC 6242 §4 regardless of which base capability either
     * side ends up advertising, followed by switching both directions to
     * chunked framing (RFC 6242 §4.2) once — and only once — BOTH sides
     * have advertised base:1.1; switching unilaterally would desync framing
     * against a base:1.0-only peer.
     */
    private fun performHelloExchange(reader: NetconfFrameReader, writer: NetconfFrameWriter) {
        val clientHello = buildHello(requestedCapabilities)
        writer.writeMessage(clientHello)
        _wireMessages.tryEmit(NetconfWireMessage(outbound = true, xml = clientHello))
        _stats.value = _stats.value.copy(bytesSent = _stats.value.bytesSent + clientHello.length)

        val serverHelloXml = reader.readMessage()
            ?: throw NetconfException("Connection closed before server <hello> arrived")
        _wireMessages.tryEmit(NetconfWireMessage(outbound = false, xml = serverHelloXml))
        _stats.value = _stats.value.copy(bytesReceived = _stats.value.bytesReceived + serverHelloXml.length)

        val info = parseHello(serverHelloXml)
        _hello.value = info

        if (info.supportsBase11 && requestedCapabilities.any { it.endsWith("base:1.1") }) {
            reader.switchToChunked()
            writer.switchToChunked()
        }
    }

    /**
     * CALL-HOME-TLS FEATURE: parses [NetconfCredentials.tlsClientCertificatePem]
     * (see its doc comment for the exact expected format — PKCS#8 key only)
     * into [KeyManager]s for [SSLContext.init], or returns null (handshake
     * proceeds without a client certificate) if it's blank or malformed.
     * Built as an in-memory [KeyStore] rather than anything touching disk,
     * consistent with how private key material is handled for SSH
     * ([NetconfClient]'s `addIdentity` call — bytes only, never a temp
     * file).
     */
    private fun buildTlsClientKeyManagers(pemBundle: CharArray): Array<KeyManager>? {
        if (pemBundle.isEmpty()) return null
        val bundle = String(pemBundle)
        return try {
            val certPem = extractPemBlock(bundle, "CERTIFICATE")
                ?: run { Log.w(TAG, "TLS client cert configured but no CERTIFICATE PEM block found"); return null }
            val keyPem = extractPemBlock(bundle, "PRIVATE KEY")
                ?: run { Log.w(TAG, "TLS client cert configured but no PKCS#8 PRIVATE KEY PEM block found (PKCS#1/SEC1 'RSA PRIVATE KEY'/'EC PRIVATE KEY' are not supported — convert with 'openssl pkcs8 -topk8')"); return null }

            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(android.util.Base64.decode(certPem, android.util.Base64.DEFAULT).inputStream()) as X509Certificate
            val keyBytes = android.util.Base64.decode(keyPem, android.util.Base64.DEFAULT)
            val spec = PKCS8EncodedKeySpec(keyBytes)
            val privateKey = try {
                KeyFactory.getInstance("RSA").generatePrivate(spec)
            } catch (e: Exception) {
                KeyFactory.getInstance("EC").generatePrivate(spec)
            }

            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("netconf-tls-client", privateKey, CharArray(0), arrayOf(cert))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, CharArray(0))
            kmf.keyManagers
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse TLS client certificate — connecting without one", e)
            null
        }
    }

    private fun extractPemBlock(bundle: String, label: String): String? =
        Regex("""-----BEGIN $label-----(.*?)-----END $label-----""", RegexOption.DOT_MATCHES_ALL)
            .find(bundle)?.groupValues?.get(1)?.replace(Regex("\\s"), "")

    /**
     * Sends one `<rpc>` body (the caller supplies everything between
     * `<rpc ...>` and `</rpc>`, e.g. `<get-config><source><running/></source></get-config>`)
     * and suspends until the matching `<rpc-reply>` arrives, correlated by
     * `message-id`. This is the low-level primitive the full RPC-operation
     * layer (get/edit-config/commit/lock/...) will be built on in the next
     * phase; already used internally by [doConnect]'s keepalive-adjacent
     * health probe.
     */
    suspend fun sendRawRpc(rpcBodyXml: String, timeoutMs: Long = 30_000): String = withContext(Dispatchers.IO) {
        val writer = frameWriter ?: throw NetconfException("Not connected")
        val id = messageIdCounter.getAndIncrement().toString()
        val envelope = """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<rpc xmlns="urn:ietf:params:xml:ns:netconf:base:1.0" message-id="$id">$rpcBodyXml</rpc>"""
        val deferred = CompletableDeferred<String>()
        pendingReplies[id] = deferred
        try {
            writer.writeMessage(envelope)
            _wireMessages.tryEmit(NetconfWireMessage(outbound = true, xml = envelope))
            _stats.value = _stats.value.copy(
                bytesSent = _stats.value.bytesSent + envelope.length,
                rpcCount = _stats.value.rpcCount + 1,
            )
            val start = System.currentTimeMillis()
            val reply = withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: throw NetconfException("Timed out waiting for rpc-reply to message-id=$id")
            _stats.value = _stats.value.copy(lastLatencyMs = System.currentTimeMillis() - start)
            reply
        } finally {
            pendingReplies.remove(id)
        }
    }

    /** Called by the RPC-engine layer once a lock request for [datastore] succeeds, so reconnect can try to restore it. */
    fun notifyLockAcquired(datastore: String) { locksHeld.add(datastore) }
    fun notifyLockReleased(datastore: String) { locksHeld.remove(datastore) }

    fun disconnect() {
        manuallyDisconnected = true
        cleanup()
        _sessionState.value = NetconfSessionState.DISCONNECTED
        _stats.value = _stats.value.copy(connectedSinceMs = null)
    }

    // ── internals ─────────────────────────────────────────────────────

    private fun readLoop() {
        val reader = frameReader ?: return
        try {
            while (true) {
                val msg = reader.readMessage() ?: break // clean EOF
                _wireMessages.tryEmit(NetconfWireMessage(outbound = false, xml = msg))
                _stats.value = _stats.value.copy(bytesReceived = _stats.value.bytesReceived + msg.length)
                routeIncoming(msg)
            }
        } catch (e: Exception) {
            if (!manuallyDisconnected) {
                Log.w(TAG, "NETCONF read loop ended unexpectedly", e)
                _error.tryEmit("Connection lost: ${e.message}")
            }
        }
        if (!manuallyDisconnected) {
            _sessionState.value = NetconfSessionState.DISCONNECTED
            // CALL-HOME FEATURE: a Call Home socket (SSH or TLS) is
            // single-use — the device dialed *us* once via
            // NetconfCallHomeListener/NetconfCallHomeTlsListener, and that
            // accepted java.net.Socket cannot be "redialed" the way a normal
            // outbound host:port can. When this connection drops, the
            // correct behavior is to wait for the device to call home again
            // (a fresh accept → a fresh NetconfClient instance, wired up by
            // NetconfCallHomeService), not to spin this instance's own
            // reconnectLoop against a dead proxy/socket.
            if (autoReconnect && preAcceptedSocket == null && tlsSocket == null) ioScope.launch { reconnectLoop() }
        }
    }

    private fun routeIncoming(xml: String) {
        if (xml.contains("<notification")) {
            _notifications.tryEmit(xml)
            _stats.value = _stats.value.copy(notificationCount = _stats.value.notificationCount + 1)
            return
        }
        val id = Regex("""message-id\s*=\s*"([^"]+)"""").find(xml)?.groupValues?.get(1)
            ?: Regex("""message-id\s*=\s*'([^']+)'""").find(xml)?.groupValues?.get(1)
        if (id != null) {
            pendingReplies.remove(id)?.complete(xml)
        } else {
            Log.w(TAG, "Received rpc-reply with no matching message-id (len=${xml.length})")
        }
    }

    private suspend fun healthMonitorLoop() {
        while (_sessionState.value == NetconfSessionState.CONNECTED) {
            delay(healthCheckIntervalMs)
            if (_sessionState.value != NetconfSessionState.CONNECTED) break
            try {
                // Lightweight liveness probe: an empty-filter <get> is legal
                // against every NETCONF server (RFC 6241 §7.7) and doubles as
                // a real latency measurement, unlike an SSH-level no-op.
                sendRawRpc("<get><filter type=\"subtree\"/></get>", timeoutMs = 10_000)
            } catch (e: Exception) {
                Log.w(TAG, "NETCONF health probe failed", e)
                // Let the read loop's own EOF/exception path drive reconnect;
                // this probe failing on its own (e.g. one slow reply) isn't
                // by itself proof the transport died.
            }
        }
    }

    private suspend fun reconnectLoop() {
        if (reconnecting) return
        reconnecting = true
        _sessionState.value = NetconfSessionState.RECONNECTING
        var backoff = 1_000L
        try {
            while (!manuallyDisconnected) {
                cleanup()
                try {
                    doConnect()
                    _sessionState.value = NetconfSessionState.CONNECTED
                    _stats.value = _stats.value.copy(
                        connectedSinceMs = System.currentTimeMillis(),
                        reconnectCount = _stats.value.reconnectCount + 1,
                    )
                    readLoopJob = ioScope.launch { readLoop() }
                    healthJob = ioScope.launch { healthMonitorLoop() }
                    // SESSION-RESUME: best-effort re-acquire any lock the
                    // caller held before the drop — see class doc comment
                    // for why this is re-acquisition, not true resumption.
                    locksHeld.toList().forEach { ds ->
                        runCatching { sendRawRpc("<lock><target><$ds/></target></lock>") }
                    }
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "NETCONF reconnect attempt failed, retrying in ${backoff}ms", e)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(maxReconnectBackoffMs)
                }
            }
        } finally {
            reconnecting = false
        }
    }

    private fun cleanup() {
        runCatching { subsystem?.disconnect() }
        runCatching { session?.disconnect() }
        runCatching { jumpTunnel?.close() }
        // CALL-HOME FEATURE: belt-and-suspenders — Session.disconnect()
        // above calls Proxy.close() (NetconfCallHomeProxy.close(), which
        // closes this same socket) on every path JSch itself knows about,
        // but a failure earlier in doConnect() (e.g. auth rejected before a
        // Proxy was ever installed) could leave the accepted socket open
        // with nothing else going to close it.
        runCatching { preAcceptedSocket?.close() }
        // CALL-HOME-TLS FEATURE: same belt-and-suspenders reasoning as
        // above — close the SSLSocket if the handshake completed, and
        // always close the raw accepted socket underneath it (SSLSocket's
        // autoClose=true from createSocket(..., true) already does this on
        // a clean close(), but a handshake failure before secureTlsSocket
        // was even assigned would otherwise leak the raw socket).
        runCatching { secureTlsSocket?.close() }
        runCatching { tlsSocket?.close() }
        subsystem = null
        session = null
        jumpTunnel = null
        secureTlsSocket = null
        frameReader = null
        frameWriter = null
        pendingReplies.values.forEach { it.cancel() }
        pendingReplies.clear()
    }

    private fun buildHello(capabilities: List<String>): String {
        val caps = capabilities.joinToString("") { "<capability>$it</capability>" }
        return """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<hello xmlns="urn:ietf:params:xml:ns:netconf:base:1.0"><capabilities>$caps</capabilities></hello>"""
    }

    /**
     * YANG-DISCOVERY FEATURE (foundation for the later YANG-browser phase):
     * parses `<hello>` with a real DOM parser (not regex) — the server's
     * capability list is exactly what tells us which datastores exist
     * ([NetconfHelloInfo.supportedDatastores]) and what YANG modules/
     * revisions/features/deviations it supports (encoded as
     * `...?module=NAME&revision=DATE&features=...` query strings on each
     * `urn:ietf:params:xml:ns:yang:...` capability URI), so getting this
     * parse right now avoids re-deriving it from raw text later.
     */
    private fun parseHello(xml: String): NetconfHelloInfo {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        val capNodes = doc.getElementsByTagNameNS("*", "capability")
        val capabilities = (0 until capNodes.length).map {
            NetconfCapability((capNodes.item(it) as Element).textContent.trim())
        }
        val sessionIdNodes = doc.getElementsByTagNameNS("*", "session-id")
        val sessionId = if (sessionIdNodes.length > 0) (sessionIdNodes.item(0) as Element).textContent.trim().toIntOrNull() else null

        val datastoreNames = listOf("running", "candidate", "startup", "operational", "intended")
        val datastores = capabilities.filter { cap ->
            datastoreNames.any { ds -> cap.uri.contains(":$ds") || cap.uri.contains(":writable-running") && ds == "running" }
        }.mapNotNull { cap -> datastoreNames.firstOrNull { ds -> cap.uri.contains(":$ds") } }
            .distinct()
            .ifEmpty { listOf("running") } // RFC 6241: :running is implicit/mandatory even if never listed explicitly

        return NetconfHelloInfo(
            sessionId = sessionId,
            capabilities = capabilities,
            supportsBase11 = capabilities.any { it.uri.endsWith("base:1.1") },
            supportedDatastores = datastores,
        )
    }
}
