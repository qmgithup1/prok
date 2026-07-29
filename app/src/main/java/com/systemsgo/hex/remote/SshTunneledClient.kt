package com.systemsgo.hex.remote

import android.content.Context

import android.util.Log
import com.systemsgo.hex.R
import com.systemsgo.hex.ssh.protocol.SshInteractivePrompt
import com.systemsgo.hex.ssh.protocol.SshTunnelCredentials
import com.systemsgo.hex.ssh.protocol.SshTunnelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A [RemoteSessionClient] decorator that transparently sets up an SSH
 * port-forwarding tunnel before the inner RDP/VNC client connects.
 *
 * Connection sequence on [connect]:
 *  1. Open SSH tunnel: localhost:[localPort] → [targetHost]:[targetPort],
 *     threaded through the full ordered [tunnelHops] chain (one hop is the
 *     historical "single jump host" case; more than one is a full
 *     OpenSSH-style `-J host1,host2,host3` ProxyJump chain — see
 *     [SshTunnelManager]'s doc comment for how the chain itself is walked).
 *  2. Build the inner client via [innerClientFactory](localPort).
 *  3. Call inner [RemoteSessionClient.connect].
 *
 * On [disconnect]: inner client is disconnected first, then the SSH tunnel
 * (every hop in the chain) is torn down.
 *
 * SSH-PROXYJUMP-CHAIN FEATURE: [tunnelHops] replaces the previous single
 * `tunnelCredentials: SshTunnelCredentials` constructor parameter — callers
 * (see [com.systemsgo.hex.remote.RemoteSessionFactory]) build this list from
 * [com.systemsgo.hex.data.model.RdpProfile.effectiveSshTunnelHops], which
 * transparently upgrades an older single-hop profile into a one-entry list,
 * so this class never needs to special-case "one hop" vs "a chain" itself.
 */
class SshTunneledClient(
    private val tunnelHops: List<SshTunnelCredentials>,
    private val targetHost: String,
    private val targetPort: Int,
    // BUG-H FIX: Context needed by SshTunnelManager to persist TOFU keys.
    private val appContext: Context,
    // PAC-SUPPORT FEATURE: forwarded straight through to SshTunnelManager's
    // own outboundProxy — see that class's doc comment. Resolved by the
    // caller (RemoteSessionFactory) via PacProxyResolver.resolve() using
    // PacProxyResolver.outboundDialTarget(profile), which for a tunneled
    // profile is exactly tunnelHops.first()'s host/port, i.e. targetHost/
    // targetPort ABOVE are NOT what this proxy applies to (those are the
    // final RDP/VNC/Telnet destination, reached only through the tunnel).
    private val outboundProxy: com.systemsgo.hex.proxy.PacProxyResolver.Resolved =
        com.systemsgo.hex.proxy.PacProxyResolver.Resolved.Direct,
    /** Factory receives the localhost forwarded port; returns the real RDP/VNC client. */
    private val innerClientFactory: (localPort: Int) -> RemoteSessionClient,
) : RemoteSessionClient {

    init {
        require(tunnelHops.isNotEmpty()) { "SshTunneledClient requires at least one tunnel hop" }
    }

    companion object {
        private const val TAG = "SshTunneledClient"
    }

    // ── Flows ──────────────────────────────────────────────────────────────

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    // FIX-buffer: raised from 2 → 8 to match RdpRemoteAdapter and VncClient.
    // With capacity=2 the tunnel could drop frames under SSH latency bursts.
    private val _frameUpdates  = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 8)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 64)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    override val latencyMs: Long
        get() = innerClient?.latencyMs ?: 0L

    // KBD-INT FIX: surfaces the jump-host's keyboard-interactive prompt (if the
    // SSH tunnel's auth requires TOTP/PAM) up through the same decorator surface
    // the UI already observes for sessionState/error. Not part of RemoteSessionClient
    // (it's SSH-specific) — callers access it via an `as? SshTunneledClient` cast,
    // the same pattern already used for `as? SshClient` elsewhere.
    val authPrompt: StateFlow<SshInteractivePrompt?> get() = tunnelManager.authPrompt

    fun submitAuthPromptResponse(responses: List<String>) = tunnelManager.submitAuthPromptResponse(responses)
    fun cancelAuthPrompt() = tunnelManager.cancelAuthPrompt()

    // ── Internal state ─────────────────────────────────────────────────────

    // FIX-MED-R3-5: scope is now a var so it can be recreated in every connect() call.
    // A val CoroutineScope becomes permanently cancelled after the first disconnect()
    // or failed connect(). Any subsequent scope.launch{} on the same instance would
    // silently no-op, making reconnection impossible. Recreating the scope at the top
    // of connect() ensures a clean coroutine context on every attempt.
    @Volatile private var scope = newScope()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // SSH-PROXYJUMP-CHAIN FEATURE: SshTunnelManager's primary constructor now
    // takes the full ordered chain (see that class's doc comment); this is
    // the same single-argument call shape whether tunnelHops has one entry
    // (the historical single jump-host case) or several.
    private val tunnelManager = SshTunnelManager(tunnelHops, appContext, outboundProxy)  // BUG-H FIX / PAC-SUPPORT FEATURE
    @Volatile private var innerClient: RemoteSessionClient? = null

    // ── RemoteSessionClient implementation ─────────────────────────────────

    override suspend fun connect(): Boolean {
        // FIX-MED-R3-5: always start with a fresh scope so re-connection after
        // disconnect() or a failed connect() works correctly.
        scope = newScope()
        return try {
            _sessionState.emit(RemoteSessionState.CONNECTING)

            // 1. Open SSH port-forward tunnel (chained through every hop)
            Log.i(TAG, "Opening SSH tunnel (${tunnelHops.size}-hop chain) for $targetHost:$targetPort")
            val tunnel = tunnelManager.openTunnel(targetHost, targetPort)
            Log.i(TAG, "Tunnel ready: localhost:${tunnel.localPort} → $targetHost:$targetPort")

            // 2. Build inner RDP/VNC client aimed at the loopback forwarded port
            val client = innerClientFactory(tunnel.localPort)
            innerClient = client

            // 3. Forward inner client's flows to our own so callers see one surface
            scope.launch { client.sessionState.collect { _sessionState.emit(it) } }
            scope.launch { client.frameUpdates.collect  { _frameUpdates.emit(it) } }
            scope.launch { client.terminalOutput.collect { _terminalOutput.emit(it) } }
            scope.launch { client.error.collect          { _error.emit(it) } }

            // 4. Connect inner client (goes to localhost:localPort via the tunnel)
            client.connect()
        } catch (e: Exception) {
            // BUG-10 FIX (mirrors SshClient): log only the exception class, never the raw
            // JSch message — it can contain the jump-host hostname/port.
            Log.e(TAG, "Tunnel/inner connect failed: ${e.javaClass.simpleName}")
            // BUG-i18n-LEAK FIX: this catch mainly surfaces SshTunnelManager.openTunnel()
            // failures (a JSch session to the jump host), so classify like SshClient.connect()
            // does and always emit a localized, opaque string — never e.message.
            val authFailure = e.message?.contains("Auth", ignoreCase = true) == true ||
                e.message?.contains("authentication", ignoreCase = true) == true
            val userMessage = when {
                authFailure ->
                    appContext.getString(R.string.disconnect_reason_auth)
                e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("timed out", ignoreCase = true) == true ->
                    appContext.getString(R.string.error_ssh_timeout)
                e.message?.contains("refused", ignoreCase = true) == true ->
                    appContext.getString(R.string.error_ssh_refused)
                else ->
                    appContext.getString(R.string.error_ssh_tunnel_failed)
            }
            _error.emit(userMessage)
            _sessionState.emit(if (authFailure) RemoteSessionState.AUTH_FAILED else RemoteSessionState.ERROR)
            // BUG-M1 FIX: innerClient was assigned before connect() threw but never
            // disconnected, leaving sockets/threads open indefinitely (resource leak).
            try { innerClient?.disconnect() } catch (e: Exception) { android.util.Log.d("SshTunneledClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
            tunnelManager.close()
            // BUG-X1 FIX: the 4 flow-forwarding coroutines launched above via scope.launch{}
            // remain active after tunnel/inner-connect failure unless the scope is explicitly
            // cancelled here. Without this, they keep collecting from a closed innerClient →
            // coroutine leak + silent exceptions.
            scope.cancel()
            false
        }
    }

    override fun disconnect() {
        scope.cancel()
        try { innerClient?.disconnect() } catch (e: Exception) { Log.w(TAG, "Inner disconnect error", e) }
        tunnelManager.close()
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }

    // ── Input — delegate to inner client ──────────────────────────────────

    override fun sendMouseMove(x: Int, y: Int)                                             = innerClient?.sendMouseMove(x, y) ?: Unit
    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean)  = innerClient?.sendMouseClick(x, y, button, down) ?: Unit
    override fun sendMouseScroll(x: Int, y: Int, delta: Int)                               = innerClient?.sendMouseScroll(x, y, delta) ?: Unit
    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean)             = innerClient?.sendKeyEvent(scanCode, down, extended) ?: Unit
    override fun sendCtrlAltDel()                                                           = innerClient?.sendCtrlAltDel() ?: Unit
    override fun sendText(text: String)                                                     = innerClient?.sendText(text) ?: Unit
}
