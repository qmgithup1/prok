package com.systemsgo.hex.ssh.protocol

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.SharedPreferences
import com.systemsgo.hex.security.openEncryptedPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.ServerSocket

/**
 * Credentials for setting up an SSH tunnel jump-host.
 *
 * MULTIHOP FIX: a single [SshTunnelCredentials] now represents ONE hop in a
 * chain of any length (previously it was always exactly "the" jump host).
 * [SshTunnelManager] takes an ordered `List<SshTunnelCredentials>` — hop 0 is
 * the first server reachable from the device, the last entry is the server
 * adjacent to the final RDP/VNC/SSH target — and threads the SSH connection
 * through all of them, the same way OpenSSH's `-J host1,host2,host3` (or a
 * chain of `ProxyJump` directives in ssh_config) does.
 */
// REM-2 FIX: Sensitive credentials stored as CharArray instead of String.
// See SshCredentials in SshClient.kt for the full rationale.
// Not a data class — see the comment on SshCredentials for why.
class SshTunnelCredentials(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMode: SshAuthMode,
    password: String = "",
    privateKeyPem: String = "",
    privateKeyPassphrase: String = "",
) {
    val password: CharArray            = password.toCharArray()
    val privateKeyPem: CharArray       = privateKeyPem.toCharArray()
    val privateKeyPassphrase: CharArray = privateKeyPassphrase.toCharArray()

    /** Zero all sensitive fields. Must be called after JSch has consumed the secrets. */
    fun zero() {
        password.fill('\u0000')
        privateKeyPem.fill('\u0000')
        privateKeyPassphrase.fill('\u0000')
    }
}

/**
 * Result of a successfully established SSH tunnel.
 *
 * @param localPort  The localhost port that forwards to [remoteHost]:[remotePort]
 *                   through the SSH server (the LAST hop of the chain).
 * @param remoteHost The final destination host (as seen from the last hop).
 * @param remotePort The final destination port.
 */
data class SshTunnelResult(
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
)

/**
 * Result of a successfully established dynamic (SOCKS4/5) proxy — the
 * equivalent of OpenSSH's `ssh -D <port>`.
 */
data class SshDynamicProxyResult(
    val localPort: Int,
)

/**
 * Manages a chained SSH port-forwarding (local tunnel) session via JSch —
 * i.e. full multi-hop `ProxyJump` support, not just a single jump host.
 *
 * MULTIHOP FIX (was: single Jump Host only): the manager used to hold exactly
 * one [SshTunnelCredentials] and open exactly one [Session]. It now holds an
 * ordered chain `List<SshTunnelCredentials>` of one-or-more hops and builds a
 * pipeline of Sessions, each one connecting through a local port-forward
 * opened by the previous hop — the same technique OpenSSH itself uses to
 * implement `-J jump1,jump2,jump3`:
 *
 *   device --SSH--> hop[0] --SSH(fwd)--> hop[1] --SSH(fwd)--> ... --SSH(fwd)--> hop[n-1] --TCP--> target
 *
 * Usage (single hop — unchanged call shape via the convenience constructor):
 * ```
 * val mgr = SshTunnelManager(credentials, appContext)
 * val result = mgr.openTunnel(remoteHost = "10.0.0.5", remotePort = 3389)
 * mgr.close()
 * ```
 *
 * Usage (multi-hop chain):
 * ```
 * val mgr = SshTunnelManager(listOf(hop1Creds, hop2Creds, hop3Creds), appContext)
 * val result = mgr.openTunnel(remoteHost = "10.0.0.5", remotePort = 3389)
 * mgr.close()
 * ```
 */
class SshTunnelManager(
    private val hops: List<SshTunnelCredentials>,
    private val appContext: Context,
    // PAC-SUPPORT FEATURE: the resolved outbound proxy this device's own TCP
    // connection to hops.first() should go through, if any — resolved by the
    // caller via [com.systemsgo.hex.proxy.PacProxyResolver.resolve] (target
    // = [com.systemsgo.hex.proxy.PacProxyResolver.outboundDialTarget], which
    // is exactly hops.first().host/port) BEFORE constructing this manager.
    // Only ever applied to the FIRST hop's session in [connectHop] — every
    // later hop is reached through the previous hop's own forwarded
    // connection, never a fresh dial from this device, so it is never
    // itself subject to an outbound-proxy decision. Defaults to Direct so
    // every existing caller keeps connecting exactly as before.
    private val outboundProxy: com.systemsgo.hex.proxy.PacProxyResolver.Resolved =
        com.systemsgo.hex.proxy.PacProxyResolver.Resolved.Direct,
) {

    constructor(credentials: SshTunnelCredentials, appContext: Context) :
        this(listOf(credentials), appContext)

    init {
        require(hops.isNotEmpty()) { "SshTunnelManager requires at least one hop" }
    }

    companion object {
        private const val TAG = "SshTunnelManager"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val PREFS_TOFU_TUNNEL = "systemsgo_tofu_tunnel"
    }

    private inner class TofuHostKeyRepository(private val hopCredentials: SshTunnelCredentials) :
        com.jcraft.jsch.HostKeyRepository {

        private val pendingKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

        private fun mapKey(host: String): String {
            val bare = host.removePrefix("[").substringBefore("]")
            return if (':' in host) "$bare:${host.substringAfterLast(']').removePrefix(":")}"
            else "$bare:${hopCredentials.port}"
        }

        private val cachedPrefs: SharedPreferences by lazy {
            appContext.openEncryptedPrefs(PREFS_TOFU_TUNNEL)
        }
        private fun prefs(): SharedPreferences = cachedPrefs

        override fun check(host: String, key: ByteArray): Int {
            val mk = mapKey(host)
            val incoming = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
            val stored = prefs().getString(mk, null)
            return when {
                stored == null     -> { pendingKeys[mk] = incoming; com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED }
                stored == incoming -> com.jcraft.jsch.HostKeyRepository.OK
                else -> {
                    Log.w(TAG, "SSH tunnel host key CHANGED for $mk — possible MITM!")
                    com.jcraft.jsch.HostKeyRepository.CHANGED
                }
            }
        }

        override fun add(hostkey: com.jcraft.jsch.HostKey, ui: com.jcraft.jsch.UserInfo?) {
            val mk = mapKey(hostkey.host)
            pendingKeys.remove(mk)?.let { key ->
                prefs().edit().putString(mk, key).commit()
            }
        }

        override fun remove(host: String?, type: String?) {
            if (host != null) prefs().edit().remove(mapKey(host)).commit()
        }
        override fun remove(host: String?, type: String?, key: ByteArray?) = remove(host, type)
        override fun getKnownHostsRepositoryID() = "systemsgo-tunnel-tofu"
        override fun getHostKey()                = emptyArray<com.jcraft.jsch.HostKey>()
        override fun getHostKey(h: String?, t: String?) = emptyArray<com.jcraft.jsch.HostKey>()
    }

    private inner class InteractiveUserInfo(
        private val hopIndex: Int,
        private val hopCount: Int,
        private val hopLabel: String,
    ) : com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            val texts = prompt?.toList().orEmpty()
            val echoes = echo?.toList() ?: List(texts.size) { true }
            val fields = texts.mapIndexed { i, t ->
                SshInteractivePromptField(t, echoes.getOrElse(i) { true })
            }
            _authPrompt.value = SshInteractivePrompt(
                name = name.orEmpty(),
                instruction = instruction.orEmpty(),
                prompts = fields,
                hopIndex = hopIndex,
                hopCount = hopCount,
                hopLabel = hopLabel,
            )
            val answers = authPromptQueue.take()
            _authPrompt.value = null
            return answers?.toTypedArray()
        }

        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) {
            Log.d(TAG, "SSH tunnel server sent an interactive banner/message (length=${message?.length ?: 0}, hop=$hopIndex/$hopCount)")
        }
    }

    private val _authPrompt = MutableStateFlow<SshInteractivePrompt?>(null)
    val authPrompt: StateFlow<SshInteractivePrompt?> = _authPrompt.asStateFlow()

    @Volatile private var authPromptQueue = java.util.concurrent.LinkedBlockingQueue<List<String>?>()

    fun submitAuthPromptResponse(responses: List<String>) {
        _authPrompt.value = null
        authPromptQueue.put(responses)
    }

    fun cancelAuthPrompt() {
        _authPrompt.value = null
        authPromptQueue.put(null)
    }

    @Volatile private var hopSessions: List<Session> = emptyList()

    @Volatile private var socksProxyServer: SocksProxyServer? = null

    suspend fun openTunnel(remoteHost: String, remotePort: Int): SshTunnelResult =
        withContext(Dispatchers.IO) {
            val sess = connectChain()
            val localPort = pickFreePort()
            sess.setPortForwardingL(localPort, remoteHost, remotePort)
            val chainId = hops.joinToString("→") { Integer.toHexString(it.host.hashCode()).takeLast(6) }
            Log.i(TAG, "SSH tunnel open: local:$localPort → remote via ${hops.size}-hop chain ($chainId)")
            SshTunnelResult(localPort = localPort, remoteHost = remoteHost, remotePort = remotePort)
        }

    suspend fun openDynamicProxy(requestedPort: Int = 0): SshDynamicProxyResult =
        withContext(Dispatchers.IO) {
            val sess = connectChain()
            val proxy = SocksProxyServer(sess)
            val localPort = proxy.start(requestedPort)
            socksProxyServer = proxy
            val chainId = hops.joinToString("→") { Integer.toHexString(it.host.hashCode()).takeLast(6) }
            Log.i(TAG, "SSH dynamic SOCKS proxy open: local:$localPort via ${hops.size}-hop chain ($chainId)")
            SshDynamicProxyResult(localPort = localPort)
        }

    private fun connectChain(): Session {
        closeChain()
        authPromptQueue = java.util.concurrent.LinkedBlockingQueue()

        val connected = mutableListOf<Session>()
        try {
            var previous: Session? = null
            hops.forEachIndexed { index, hop ->
                val viaHost: String
                val viaPort: Int
                if (previous == null) {
                    viaHost = hop.host
                    viaPort = hop.port
                } else {
                    val localPort = pickFreePort()
                    previous!!.setPortForwardingL(localPort, hop.host, hop.port)
                    viaHost = "127.0.0.1"
                    viaPort = localPort
                }

                val sess = connectHop(
                    hop = hop,
                    dialHost = viaHost,
                    dialPort = viaPort,
                    hostKeyAlias = if (previous == null) null else hop.host,
                    hopIndex = index + 1,
                    hopCount = hops.size,
                )
                connected += sess
                previous = sess
            }
        } catch (e: Exception) {
            connected.asReversed().forEach { s -> try { s.disconnect() } catch (e: Exception) { android.util.Log.d("SshTunnelManager", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") } }
            throw e
        }

        hopSessions = connected
        return connected.last()
    }

    private fun connectHop(
        hop: SshTunnelCredentials,
        dialHost: String,
        dialPort: Int,
        hostKeyAlias: String?,
        hopIndex: Int,
        hopCount: Int,
    ): Session {
        val jsch = JSch()
        jsch.hostKeyRepository = TofuHostKeyRepository(hop)

        if (hop.authMode == SshAuthMode.PRIVATE_KEY && hop.privateKeyPem.isNotEmpty()) {
            val pemBuf   = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(hop.privateKeyPem))
            val pemBytes = ByteArray(pemBuf.remaining()).also { pemBuf.get(it) }
            val passBytes: ByteArray? = if (hop.privateKeyPassphrase.isNotEmpty()) {
                val buf = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(hop.privateKeyPassphrase))
                ByteArray(buf.remaining()).also { buf.get(it) }
            } else null
            try {
                jsch.addIdentity("systemsgo-tunnel-key-hop$hopIndex", pemBytes, null, passBytes)
            } finally {
                pemBytes.fill(0)
                passBytes?.fill(0)
                hop.privateKeyPem.fill('\u0000')
                hop.privateKeyPassphrase.fill('\u0000')
            }
        }

        val sess = jsch.getSession(hop.username, dialHost, dialPort)
        // PAC-SUPPORT FEATURE: only the first hop (hostKeyAlias == null,
        // i.e. `previous == null` in connectChain()) is an actual outbound
        // dial from this device — every later hop connects through the
        // previous hop's own local port-forward instead, so applying a
        // proxy there would be meaningless (and wrong: that "connection" is
        // really just this device talking to itself on 127.0.0.1).
        if (hostKeyAlias == null) {
            outboundProxy.toJschProxy()?.let { sess.setProxy(it) }
        }
        if (hostKeyAlias != null) {
            sess.setHostKeyAlias(hostKeyAlias)
        }
        if (hop.authMode == SshAuthMode.PASSWORD) {
            sess.setPassword(String(hop.password))
            hop.password.fill('\u0000')
        }
        sess.setConfig("StrictHostKeyChecking", "accept-new")
        sess.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        sess.userInfo = InteractiveUserInfo(
            hopIndex = hopIndex,
            hopCount = hopCount,
            hopLabel = "${hop.username}@${hop.host}",
        )
        sess.setConfig("ServerAliveInterval", "30")
        sess.setConfig("ServerAliveCountMax", "3")
        sess.timeout = CONNECT_TIMEOUT_MS

        sess.connect(CONNECT_TIMEOUT_MS)
        return sess
    }

    fun close() {
        _authPrompt.value = null
        authPromptQueue.offer(null)
        try {
            try { socksProxyServer?.stop() } catch (e: Exception) { android.util.Log.d("SshTunnelManager", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
            socksProxyServer = null
            closeChain()
            Log.i(TAG, "SSH tunnel closed (${hops.size}-hop chain)")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing SSH tunnel: ${e.javaClass.simpleName}")
        } finally {
            hopSessions = emptyList()
            socksProxyServer = null
        }
    }

    private fun closeChain() {
        hopSessions.asReversed().forEach { s -> try { s.disconnect() } catch (e: Exception) { android.util.Log.d("SshTunnelManager", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") } }
        hopSessions = emptyList()
    }

    val isConnected: Boolean
        get() = hopSessions.isNotEmpty() && hopSessions.all { it.isConnected }

    private fun pickFreePort(): Int {
        for (attempt in 1..5) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress("127.0.0.1", 0))
                val port = ss.localPort
                ss.close()
                return port
            } catch (e: java.net.BindException) {
                Log.w(TAG, "pickFreePort attempt $attempt failed: ${e.message}")
                if (attempt == 5) throw e
            }
        }
        throw java.net.BindException("Unable to find a free local port after 5 attempts")
    }
}
