package com.systemsgo.hex.ssh.protocol

import android.content.Context
import android.util.Log
import com.systemsgo.hex.R
import com.systemsgo.hex.remote.*
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream

enum class SshAuthMode { PASSWORD, PRIVATE_KEY }

// KBD-INT FIX: Data carried to the UI when the server asks a keyboard-interactive
// question (TOTP code, PAM challenge, "Enter OTP:", etc.). JSch delivers this as an
// array of prompt strings each with its own echo flag (echo=false for secrets like
// a verification code, echo=true for things like a numbered menu choice).
data class SshInteractivePrompt(
    val name: String,
    val instruction: String,
    val prompts: List<SshInteractivePromptField>,
    // MULTIHOP FIX: which hop in the chain is asking. Defaulted so the
    // existing single-hop SshClient call site (always hop 1 of 1) doesn't
    // need to change. SshTunnelManager's multi-hop chain fills these in so
    // the UI can label the dialog (e.g. "Jump host 2 of 3 — 10.0.0.5").
    val hopIndex: Int = 1,
    val hopCount: Int = 1,
    val hopLabel: String = "",
)

data class SshInteractivePromptField(
    val text: String,
    val echo: Boolean,
)

// REM-2 FIX: Sensitive credentials stored as CharArray instead of String.
//
// Java/Kotlin String objects are immutable and interned — they cannot be explicitly
// zeroed and persist in the JVM heap until the GC decides to collect them (potentially
// minutes after last use). A heap dump taken at any point during that window exposes
// plaintext passwords and private keys.
//
// CharArray is mutable: calling fill('\u0000') overwrites every character immediately
// and deterministically, eliminating the exposure window. The arrays are zeroed as soon
// as each secret has been consumed by JSch (after addIdentity() / setPassword()).
//
// Note: The class is NOT a data class deliberately — data class would auto-generate
// toString() that prints the CharArray contents, and equals()/hashCode() based on
// array identity (not content), both of which are incorrect for a security credential.
class SshCredentials(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMode: SshAuthMode,
    password: String = "",
    privateKeyPem: String = "",
    privateKeyPassphrase: String = "",
    // AGENT-FWD: requests the "auth-agent@openssh.com" channel type on the
    // shell session (RFC 4254 §6.10 equivalent used by OpenSSH's `-A` flag).
    // When the remote host later runs its own `ssh`/`git`/`scp`, that process
    // can ask back through this channel to sign a challenge with the identity
    // JSch already holds locally (added via JSch#addIdentity in connect()) —
    // the private key material itself is never sent to the remote host.
    // Only meaningful for PRIVATE_KEY auth; see RdpProfile.sshAgentForwardingEnabled.
    val agentForwardingEnabled: Boolean = false,
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
 * SSH client backed by JSch, exposing an interactive shell (PTY) channel
 * through the same [RemoteSessionClient] surface used by RDP/VNC.
 *
 * Unlike RDP/VNC this is a *terminal*, not a framebuffer — [frameUpdates] is
 * never emitted; instead raw terminal bytes are surfaced via
 * [terminalOutput], and [sendText] (rather than scan-code key events) is the
 * primary input path, matching how [com.systemsgo.hex.ui.screens.terminal.TerminalScreen]
 * drives it.
 */
class SshClient(
    private val credentials: SshCredentials,
    private val termCols: Int = 100,
    private val termRows: Int = 32,
    // BUG-H FIX: Context needed to persist TOFU keys across app restarts.
    private val appContext: Context,
    // DYN-PROXY: when true, a local SOCKS4/5 proxy (the equivalent of
    // OpenSSH's `ssh -D socksProxyPort`) is started on the SAME authenticated
    // session as the interactive shell, right after the shell channel opens.
    // Any app on the device that can be pointed at a manual SOCKS proxy can
    // then route its own traffic through this SSH server — a general-purpose
    // tunnel, not limited to this app's own RDP/VNC/terminal use of the
    // connection. See SshTunnelManager.openDynamicProxy() for the equivalent
    // capability when no interactive shell is needed.
    private val socksProxyEnabled: Boolean = false,
    private val socksProxyPort: Int = 1080,
    // PAC-SUPPORT FEATURE: the resolved outbound proxy this device's own
    // TCP connection to credentials.host:credentials.port should go
    // through, if any — resolved by the caller via
    // [com.systemsgo.hex.proxy.PacProxyResolver.resolve] BEFORE
    // constructing this SshClient (same "resolve before, pass a plain
    // value in" shape as gatewayBearerToken already uses for RDP — see
    // GatewayTokenProvider's doc comment). Defaults to Direct so every
    // existing caller/test keeps connecting exactly as before. Applied via
    // `Session.setProxy()` in connect(), below — see
    // [com.systemsgo.hex.ssh.protocol.toJschProxy] for the mapping and its
    // HTTPS-has-no-JSch-equivalent caveat. Distinct from [socksProxyEnabled]
    // above: this is the direction the app's OWN outbound connection to the
    // SSH server travels; that one is a SOCKS server this app exposes to
    // OTHER apps once already connected — opposite direction, unrelated.
    private val outboundProxy: com.systemsgo.hex.proxy.PacProxyResolver.Resolved =
        com.systemsgo.hex.proxy.PacProxyResolver.Resolved.Direct,
    // X11 FORWARDING FEATURE: the equivalent of OpenSSH's `ssh -X`/`-Y`,
    // requested on the SAME shell channel right alongside the PTY. When
    // enabled, JSch relays any X11 connection the remote host's `x11-req`
    // virtual display receives back through this SSH session to a local X
    // server at x11DisplayHost:(6000 + x11DisplayNumber) — see
    // RdpProfile.x11ForwardingEnabled's doc comment for the full picture of
    // why a local X server app (Termux:X11, XSDL, ...) is still required on
    // the device, and X11AuthCookie for the cookie handling.
    private val x11ForwardingEnabled: Boolean = false,
    private val x11DisplayHost: String = "127.0.0.1",
    private val x11DisplayNumber: Int = 0,
    private val x11AuthCookie: String = "",
    // SSH-PORT-FORWARD FEATURE: user-defined static `-L`/`-R` forwards, set
    // up on this same session right alongside the interactive shell and the
    // SOCKS/X11 blocks above. See com.systemsgo.hex.data.model.SshPortForwardRule.
    private val portForwards: List<com.systemsgo.hex.data.model.SshPortForwardRule> = emptyList(),
) : RemoteSessionClient {

    companion object {
        private const val TAG = "SshClient"
        private const val CONNECT_TIMEOUT_MS = 15_000
        // BUG-L1 FIX: tofuPendingKeys removed from companion object (was a shared
        // static map). When two SshClient instances connected simultaneously to the
        // same host, check() of client-2 overwrote client-1's pending entry, causing
        // add() of client-1 to persist client-2's key — wrong key stored permanently.
        // The map is now an instance field inside TofuHostKeyRepository.
    }

    // TOFU REFACTOR: TofuHostKeyRepository (FIX #2 — replaces
    // StrictHostKeyChecking=no with real, persisted TOFU) is no longer a
    // private inner class here. It's hoisted out to a top-level, reusable
    // class (ssh/protocol/TofuHostKeyRepository.kt) so other SSH-based
    // clients (e.g. MoshSessionManager's SSH bootstrap step) get the same
    // persisted MITM-detection guarantee as this client, instead of falling
    // back to JSch's in-memory-only "accept-new" default. See that file's
    // doc comment for the full behavior; instantiated in connect() below.

    /**
     * KBD-INT FIX: Bridges JSch's synchronous UserInfo/UIKeyboardInteractive
     * callbacks to the coroutine/Compose world above.
     *
     * Password and private-key-passphrase auth are already handled up front via
     * sess.setPassword() / jsch.addIdentity() before connect() is ever called, so
     * getPassword()/getPassphrase()/promptPassword()/promptPassphrase() are not
     * expected to fire for this app's flows; they deny by default rather than
     * silently invent an empty credential. Host-key trust is handled exclusively
     * by TofuHostKeyRepository (StrictHostKeyChecking=accept-new), so promptYesNo()
     * is likewise unexpected and denies by default.
     *
     * promptKeyboardInteractive() is the one that matters: it's what JSch calls
     * when the server asks a real interactive question (TOTP code, PAM challenge).
     */
    private inner class InteractiveUserInfo : com.jcraft.jsch.UserInfo, com.jcraft.jsch.UIKeyboardInteractive {

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

            // Hand the question to whoever is observing authPrompt (the terminal
            // UI), then block THIS thread — the JSch/session thread, not Main —
            // until the user answers via submitAuthPromptResponse() or cancels
            // via cancelAuthPrompt(). See field comment on why this is safe.
            _authPrompt.value = SshInteractivePrompt(
                name = name.orEmpty(),
                instruction = instruction.orEmpty(),
                prompts = fields,
            )
            val answers = authPromptQueue.take()
            _authPrompt.value = null
            // Returning null tells JSch the user declined/cancelled, failing this
            // auth attempt cleanly instead of hanging or guessing a blank answer.
            return answers?.toTypedArray()
        }

        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false

        override fun showMessage(message: String?) {
            // BUG-10/CRIT-1 style: don't log the raw server-supplied banner text —
            // just note that one arrived, same reasoning as elsewhere in this file.
            Log.d(TAG, "SSH server sent an interactive banner/message (length=${message?.length ?: 0})")
        }
    }

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 1)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 64)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    // KBD-INT FIX: Real two-factor auth (TOTP/PAM) needs a UIKeyboardInteractive
    // callback wired into JSch. Without one, JSch silently has no path to ask the
    // user anything — a server requesting `keyboard-interactive` with a real
    // interactive question just fails auth with no chance for the user to answer.
    //
    // JSch invokes UserInfo/UIKeyboardInteractive callbacks *synchronously* on
    // whatever thread called session.connect() — here that's the Dispatchers.IO
    // thread `connect()` is already running on (see withContext(Dispatchers.IO)
    // above), never the Main/UI thread. So blocking that thread on a queue while
    // the UI collects authPrompt and later calls submitAuthPromptResponse() /
    // cancelAuthPrompt() is safe — it never blocks the UI thread itself.
    private val _authPrompt = MutableStateFlow<SshInteractivePrompt?>(null)
    val authPrompt: StateFlow<SshInteractivePrompt?> = _authPrompt.asStateFlow()

    // Recreated per connect() (same reasoning as ioScope below): reusing an
    // SshClient instance across a disconnect()/connect() cycle must not let a
    // response (or poison pill) meant for a previous attempt satisfy this one.
    @Volatile private var authPromptQueue = java.util.concurrent.LinkedBlockingQueue<List<String>?>()

    /** Called by the UI once the user answers a keyboard-interactive prompt. */
    fun submitAuthPromptResponse(responses: List<String>) {
        _authPrompt.value = null
        authPromptQueue.put(responses)
    }

    /** Called by the UI if the user dismisses/cancels the prompt (e.g. back button). */
    fun cancelAuthPrompt() {
        _authPrompt.value = null
        authPromptQueue.put(null)
    }

    override var latencyMs: Long = 0L
        private set

    // DYN-PROXY: the local port the SOCKS proxy is actually bound to, once
    // started, or null while disconnected / if the proxy wasn't requested.
    // Named distinctly from the constructor's `socksProxyPort` (the requested
    // config value) since this reflects live/actual state. Exposed so the UI
    // (or, currently, our own terminal banner in connect()) can tell the user
    // where to point a SOCKS-capable app.
    private val _activeSocksProxyPort = MutableStateFlow<Int?>(null)
    val activeSocksProxyPort: StateFlow<Int?> = _activeSocksProxyPort.asStateFlow()

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var channelOut: OutputStream? = null
    private var channelIn: InputStream? = null
    // DYN-PROXY: JSch has no built-in setPortForwardingD/delPortForwardingD
    // (dynamic/-D forwarding was never part of its API) — SocksProxyServer
    // supplies it by relaying a local SOCKS4/5 listener through this
    // session's "direct-tcpip" channels. See that class for details.
    private var socksProxyServer: SocksProxyServer? = null
    // SSH-PORT-FORWARD FEATURE: (bindAddress, port) of every -L/-R forward
    // actually set up on the current session, so cleanup() can explicitly
    // tear each one down (deterministic release, same reasoning as
    // socksProxyServer?.stop() below — session.disconnect() would tear them
    // down anyway, but not necessarily before this call returns).
    private val activeLocalForwards = mutableListOf<Pair<String, Int>>()
    private val activeRemoteForwards = mutableListOf<Pair<String, Int>>()
    @Volatile private var connected = false

    // NEW-BUG-1 FIX: Changed from `val` to `@Volatile var` so the scope can be
    // recreated at the start of every connect() call.
    // With `val`, calling disconnect() → ioScope.cancel() permanently kills the scope.
    // Any subsequent connect() call would then:
    //   • ioScope.launch { readLoop() }  → silently no-ops (scope is cancelled)
    //   • ioScope.launch { write(...) }  → same in sendText()/sendControlByte()
    // The SSH channel opens successfully but no terminal output ever arrives and
    // no input is ever sent — the terminal appears connected but is completely frozen.
    // This mirrors the identical fix already applied to VncClient (BUG-4) and the
    // pattern used in SshTunneledClient (scope recreated in connect()).
    @Volatile private var ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        // NEW-BUG-1 FIX: Recreate scope at the start of every connect() so that
        // reusing the same SshClient instance after disconnect() works correctly.
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // KBD-INT FIX: fresh queue per attempt — see field declaration for why.
        authPromptQueue = java.util.concurrent.LinkedBlockingQueue()
        try {
            _sessionState.emit(RemoteSessionState.CONNECTING)

            val jsch = JSch()

            // FIX #2: Use TOFU host-key verification instead of StrictHostKeyChecking=no.
            // accept-new auto-accepts keys for genuinely new hosts (TOFU first use) but
            // rejects a connection when the stored key no longer matches (MITM detection).
            jsch.hostKeyRepository = TofuHostKeyRepository(appContext, credentials.port)

            if (credentials.authMode == SshAuthMode.PRIVATE_KEY && credentials.privateKeyPem.isNotEmpty()) {
                // REM-2 FIX: Encode CharArray → ByteArray via CharsetEncoder without
                // materialising an intermediate String (which would be interned and
                // GC-dependent). The byte arrays are zeroed immediately after addIdentity().
                val pemBuf   = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(credentials.privateKeyPem))
                val pemBytes = ByteArray(pemBuf.remaining()).also { pemBuf.get(it) }
                val passBytes: ByteArray? = if (credentials.privateKeyPassphrase.isNotEmpty()) {
                    val buf = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(credentials.privateKeyPassphrase))
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } else null
                try {
                    jsch.addIdentity("systemsgo-key", pemBytes, null, passBytes)
                } finally {
                    pemBytes.fill(0)
                    passBytes?.fill(0)
                    // Zero CharArrays now — key material no longer needed after addIdentity().
                    credentials.privateKeyPem.fill('\u0000')
                    credentials.privateKeyPassphrase.fill('\u0000')
                }
            }

            val sess = jsch.getSession(credentials.username, credentials.host, credentials.port)
            // PAC-SUPPORT FEATURE: apply the caller-resolved outbound proxy
            // (Direct/no-op if PAC/static proxy isn't configured for this
            // profile — see [outboundProxy]'s doc comment) before connect()
            // so the actual TCP dial to credentials.host goes through it.
            outboundProxy.toJschProxy()?.let { sess.setProxy(it) }
            if (credentials.authMode == SshAuthMode.PASSWORD) {
                // REM-2 FIX: JSch setPassword() requires String. We construct it from
                // the CharArray and zero the array immediately after — the temporary String
                // object will be in the heap until GC, but the CharArray window is closed
                // deterministically here. This is the standard Java security pattern when
                // the underlying API cannot accept char[]/byte[] directly.
                sess.setPassword(String(credentials.password))
                credentials.password.fill('\u0000')
            }
            // FIX-HIGH-R3-2: Use "accept-new" instead of "yes".
            //
            // With "yes", JSch treats NOT_INCLUDED as an immediate hard rejection and
            // never calls TofuHostKeyRepository.add(). Since add() is the method that
            // persists a first-seen key to EncryptedSharedPreferences, no first
            // connection to any SSH server ever succeeds — JSch throws
            // JSchException("reject HostKey") unconditionally.
            //
            // "accept-new" is the correct TOFU mode: JSch calls check() first, and
            // only when NOT_INCLUDED is returned does it call add(), which our repo
            // uses to persist the fingerprint. On all subsequent connections check()
            // returns OK (match) or CHANGED (mismatch → MITM rejection), making
            // "accept-new" strictly stronger than "yes" in the TOFU context.
            sess.setConfig("StrictHostKeyChecking", "accept-new")
            sess.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
            // KBD-INT FIX: without this, a server that requests `keyboard-interactive`
            // with a genuine interactive question (TOTP/PAM) has no path to the user —
            // JSch just fails auth silently. InteractiveUserInfo bridges the callback
            // to authPrompt (StateFlow) + submitAuthPromptResponse()/cancelAuthPrompt().
            sess.userInfo = InteractiveUserInfo()
            sess.timeout = CONNECT_TIMEOUT_MS
            // OEM-COMPAT FIX: send an SSH-level keepalive (@openssh.com keepalive
            // request) every 15s, tolerating up to 3 missed replies before JSch
            // tears the session down. Without this, idle SSH sessions are silently
            // dropped by carrier NAT and by aggressive OEM background network
            // suspension (Xiaomi/Honor/Oppo/Vivo Doze-style policies) long before
            // the user notices — the terminal just looks frozen. This also makes
            // disconnects detectable quickly instead of hanging indefinitely on
            // the next blocking read.
            sess.serverAliveInterval = 15_000
            sess.serverAliveCountMax = 3
            session = sess

            val connectStart = System.currentTimeMillis()
            sess.connect(CONNECT_TIMEOUT_MS)
            latencyMs = System.currentTimeMillis() - connectStart

            val ch = sess.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color", termCols, termRows, 0, 0)
            ch.setPty(true)
            // AGENT-FWD: defensively re-check PRIVATE_KEY auth here even though the
            // profile form already restricts the toggle to that mode — there is no
            // local identity to forward under PASSWORD auth, so requesting the
            // channel in that case would just be a no-op capability offered to the
            // server for nothing gained. credentials.privateKeyPem is already zeroed
            // by this point (see addIdentity() above), so authMode is checked instead.
            if (credentials.agentForwardingEnabled && credentials.authMode == SshAuthMode.PRIVATE_KEY) {
                ch.setAgentForwarding(true)
            }
            // X11 FORWARDING FEATURE: must be configured on the Session BEFORE
            // the shell channel connects — JSch reads session.getX11Host()/
            // getX11Port()/getX11Cookie() at the moment it sends the "x11-req"
            // (triggered by ChannelSession#setXForwarding(true) below) to build
            // that request, and needs them already in place to service any
            // "x11" channel-open the server sends back afterwards.
            var resolvedX11Cookie: String? = null
            if (x11ForwardingEnabled) {
                resolvedX11Cookie = X11AuthCookie.resolve(x11AuthCookie)
                sess.setX11Host(x11DisplayHost)
                sess.setX11Port(6000 + x11DisplayNumber)
                sess.setX11Cookie(resolvedX11Cookie)
                ch.setXForwarding(true)
            }
            channelIn = ch.inputStream
            channelOut = ch.outputStream
            ch.connect(CONNECT_TIMEOUT_MS)
            channel = ch

            // X11 FORWARDING FEATURE: non-fatal, same reasoning as the SOCKS
            // proxy banner below — a GUI-less remote host will simply never
            // open an "x11" channel back, which is harmless, so a banner
            // (not a connection failure) is the right way to surface this.
            if (x11ForwardingEnabled && resolvedX11Cookie != null) {
                _terminalOutput.emit(
                    TerminalOutput(
                        appContext.getString(
                            R.string.ssh_x11_forwarding_started_banner,
                            x11DisplayHost,
                            6000 + x11DisplayNumber,
                        ) + "\r\n"
                    )
                )
            }

            // DYN-PROXY: start the SOCKS4/5 dynamic proxy on this same session,
            // right alongside the interactive shell — the equivalent of running
            // `ssh -D socksProxyPort user@host` together with a normal login.
            // Deliberately non-fatal: a bind failure (e.g. the requested port is
            // already in use by another app) should not tear down an otherwise
            // healthy terminal session. We surface it as a banner line in the
            // terminal itself rather than failing connect() or the session state.
            if (socksProxyEnabled) {
                try {
                    val proxy = SocksProxyServer(sess)
                    val boundPort = proxy.start(socksProxyPort)
                    socksProxyServer = proxy
                    _activeSocksProxyPort.emit(boundPort)
                    _terminalOutput.emit(
                        TerminalOutput(
                            appContext.getString(R.string.ssh_socks_proxy_started_banner, boundPort) + "\r\n"
                        )
                    )
                } catch (e: Exception) {
                    // SEC-LOG: never log/emit the raw JSch message — see the
                    // Log.e(TAG, ...) reasoning further down this function for why.
                    Log.w(TAG, "SOCKS proxy bind failed: ${e.javaClass.simpleName}")
                    _terminalOutput.emit(
                        TerminalOutput(appContext.getString(R.string.ssh_socks_proxy_failed_banner) + "\r\n")
                    )
                }
            }

            // SSH-PORT-FORWARD FEATURE: user-defined static -L/-R forwards,
            // set up on this same session right alongside the interactive
            // shell — the equivalent of running `ssh -L ...` / `ssh -R ...`
            // together with a normal login. Deliberately non-fatal per rule,
            // same reasoning as the SOCKS proxy block above: a single bad
            // rule (port already in use locally, or the server's
            // GatewayPorts policy rejecting a -R bind address) should not
            // tear down an otherwise healthy terminal session, so failures
            // are surfaced as banner lines rather than connect() errors.
            for (rule in portForwards) {
                if (!rule.isValid) continue
                try {
                    when (rule.type) {
                        com.systemsgo.hex.data.model.SshPortForwardType.LOCAL -> {
                            val boundPort = sess.setPortForwardingL(
                                rule.bindAddress, rule.listenPort, rule.destHost, rule.destPort
                            )
                            activeLocalForwards.add(rule.bindAddress to boundPort)
                            _terminalOutput.emit(
                                TerminalOutput(
                                    appContext.getString(
                                        R.string.ssh_port_forward_local_started_banner,
                                        boundPort, rule.destHost, rule.destPort,
                                    ) + "\r\n"
                                )
                            )
                        }
                        com.systemsgo.hex.data.model.SshPortForwardType.REMOTE -> {
                            sess.setPortForwardingR(
                                rule.bindAddress, rule.listenPort, rule.destHost, rule.destPort
                            )
                            activeRemoteForwards.add(rule.bindAddress to rule.listenPort)
                            _terminalOutput.emit(
                                TerminalOutput(
                                    appContext.getString(
                                        R.string.ssh_port_forward_remote_started_banner,
                                        rule.listenPort, rule.destHost, rule.destPort,
                                    ) + "\r\n"
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    // SEC-LOG: same reasoning as elsewhere in this function — never
                    // log/emit the raw JSch message, only the exception class.
                    Log.w(TAG, "Port forward (${rule.type}) failed: ${e.javaClass.simpleName}")
                    _terminalOutput.emit(
                        TerminalOutput(
                            appContext.getString(
                                R.string.ssh_port_forward_failed_banner,
                                rule.type.name, rule.listenPort,
                            ) + "\r\n"
                        )
                    )
                }
            }

            connected = true
            _sessionState.emit(RemoteSessionState.CONNECTED)
            ioScope.launch { readLoop() }

            true
        } catch (e: Exception) {
            // BUG-10 FIX: Log only the exception class, not the raw JSch message.
            // JSch error messages often contain the hostname, port, and negotiated
            // algorithm — information visible to anyone with logcat access (ADB,
            // accessibility services, OEM diagnostic tools) that helps an attacker
            // fingerprint the server. The class name alone is sufficient for debugging.
            Log.e(TAG, "SSH connect failed: ${e.javaClass.simpleName}")
            val authFailure = e.message?.contains("Auth", ignoreCase = true) == true ||
                e.message?.contains("authentication", ignoreCase = true) == true
            // CRIT-1 FIX: Never surface the raw JSch message to the UI.
            // JSch error strings embed server hostname, port and algorithm negotiation
            // details (e.g. "Algorithm negotiation fail for kex: server: ...").
            // Map to a localised, opaque message that is useful to the user without
            // disclosing infrastructure details that aid server fingerprinting.
            val userMessage = when {
                authFailure ->
                    appContext.getString(R.string.disconnect_reason_auth)
                e.message?.contains("timeout", ignoreCase = true) == true ||
                e.message?.contains("timed out", ignoreCase = true) == true ->
                    appContext.getString(R.string.error_ssh_timeout)
                e.message?.contains("refused", ignoreCase = true) == true ->
                    appContext.getString(R.string.error_ssh_refused)
                else ->
                    appContext.getString(R.string.error_ssh_connect_failed)
            }
            _error.emit(userMessage)
            _sessionState.emit(if (authFailure) RemoteSessionState.AUTH_FAILED else RemoteSessionState.ERROR)
            // BUG-SSHSCOPE FIX: ioScope was never cancelled on connect failure.
            // The SupervisorJob + Dispatchers.IO scope remained open indefinitely,
            // leaking one scope per failed connection attempt. Cancel it here,
            // matching the same pattern used in VncClient.connect() failure handling.
            ioScope.cancel()
            cleanup()
            false
        }
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(8192)
        // BUG-UTF8 FIX: String(buffer, 0, n, Charsets.UTF_8) decodes each read()
        // independently. A multi-byte UTF-8 sequence (e.g. Arabic = 2 bytes, emoji = 4 bytes)
        // can be split across two consecutive read() calls, producing \uFFFD replacement
        // characters and garbled terminal output. Fix: use a stateful CharsetDecoder that
        // retains incomplete sequences between calls, emitting them only when the remaining
        // bytes arrive in the next read().
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        val inBuf  = java.nio.ByteBuffer.wrap(buffer)
        val outBuf = java.nio.CharBuffer.allocate(buffer.size * 2)
        try {
            val stream = channelIn ?: return
            while (connected) {
                val n = stream.read(buffer)
                if (n < 0) break
                inBuf.limit(n).position(0)
                outBuf.clear()
                decoder.decode(inBuf, outBuf, false)
                outBuf.flip()
                if (outBuf.hasRemaining()) {
                    _terminalOutput.emit(TerminalOutput(outBuf.toString()))
                }
            }
            // BUG-8 FIX: Flush the CharsetDecoder when the stream ends.
            // The decoder retains incomplete multi-byte UTF-8 sequences between read() calls
            // (e.g. the first byte of a 3-byte Arabic codepoint). Without this flush, the
            // final character or line of a session can be silently lost or emitted as \uFFFD.
            // Passing endOfInput=true causes decode() to emit any buffered partial sequence
            // (as \uFFFD per CodingErrorAction.REPLACE), then flush() pushes any remaining
            // output characters out of the decoder's internal buffer.
            outBuf.clear()
            decoder.decode(java.nio.ByteBuffer.allocate(0), outBuf, true)
            decoder.flush(outBuf)
            outBuf.flip()
            if (outBuf.hasRemaining()) {
                _terminalOutput.emit(TerminalOutput(outBuf.toString()))
            }
        } catch (e: Exception) {
            if (connected) {
                // BUG-10 FIX: Log class name only — JSch stream errors can contain
                // session metadata (host, port, channel ID) that leaks server info.
                Log.e(TAG, "SSH read loop error: ${e.javaClass.simpleName}")
                // CRIT-1 FIX: Emit a generic localised string rather than e.message
                // — JSch I/O errors can include channel IDs and connection metadata.
                _error.emit(appContext.getString(R.string.error_connection_lost))
                _sessionState.emit(RemoteSessionState.ERROR)
            }
        } finally {
            connected = false
            _sessionState.emit(RemoteSessionState.DISCONNECTED)
        }
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        try {
            channel?.setPtySize(cols, rows, cols * 8, rows * 16)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resize PTY", e)
        }
    }

    // ── Input — terminal sessions take raw text, not framebuffer events ────

    override fun sendText(text: String) {
        // FIX-IO: write() and flush() are blocking calls. They must not run on
        // the Main/UI thread (which is where Compose event handlers fire).
        // ioScope is already pinned to Dispatchers.IO, so launch here is safe.
        ioScope.launch {
            try {
                channelOut?.write(text.toByteArray(Charsets.UTF_8))
                channelOut?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send terminal input", e)
            }
        }
    }

    /** Sends a single raw control byte, e.g. Ctrl+C = 0x03. */
    fun sendControlByte(byte: Int) {
        // FIX-IO: same reasoning as sendText — dispatch to IO thread.
        ioScope.launch {
            try {
                channelOut?.write(byte)
                channelOut?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send control byte", e)
            }
        }
    }

    override fun sendCtrlAltDel() { /* not meaningful over a terminal */ }
    override fun sendMouseMove(x: Int, y: Int) { /* terminal sessions don't use pointer input */ }
    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) { }
    override fun sendMouseScroll(x: Int, y: Int, delta: Int) { }
    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        // Only forward key-down for control keys relevant in a terminal —
        // see TerminalScreen's extra-keys row, which calls sendControlByte /
        // sendText directly for the keys it cares about. Scan-code based
        // input (hardware keyboard via the shared ExtraKeysBar) is mapped to
        // ANSI escape sequences here for the navigation/function keys.
        if (!down) return
        val seq = SshKeyMap.scanCodeToAnsiSequence(scanCode, extended) ?: return
        sendText(seq)
    }

    override fun disconnect() {
        connected = false
        ioScope.cancel()
        // KBD-INT FIX: if connect() is currently blocked inside
        // InteractiveUserInfo.promptKeyboardInteractive() waiting on the user
        // (e.g. the screen was backed out of before entering a TOTP code), release
        // it with a "cancelled" answer instead of leaving that thread parked
        // forever on authPromptQueue.take().
        _authPrompt.value = null
        authPromptQueue.offer(null)
        cleanup()
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }

    private fun cleanup() {
        // DYN-PROXY: explicitly remove the SOCKS listener before disconnecting —
        // session.disconnect() tears down all forwarding rules anyway, but this
        // guarantees the local listening socket is released deterministically
        // even if disconnect() itself throws partway through. Mirrors the same
        // pattern in SshTunnelManager.close().
        try { socksProxyServer?.stop() } catch (e: Exception) { android.util.Log.d("SshClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        socksProxyServer = null
        _activeSocksProxyPort.value = null
        // SSH-PORT-FORWARD FEATURE: explicitly remove each forward before
        // disconnecting the session — same deterministic-release reasoning
        // as the SOCKS listener just above.
        session?.let { sess ->
            activeLocalForwards.forEach { (bindAddress, port) ->
                try { sess.delPortForwardingL(bindAddress, port) } catch (e: Exception) { android.util.Log.d("SshClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
            }
            activeRemoteForwards.forEach { (_, port) ->
                // NOTE: unlike delPortForwardingL, JSch's delPortForwardingR
                // only ever takes the remote port — it has no bind-address
                // overload (it tracks/cancels the "tcpip-forward" request by
                // port on the JSch side regardless of which address it was
                // originally bound to).
                try { sess.delPortForwardingR(port) } catch (e: Exception) { android.util.Log.d("SshClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
        activeLocalForwards.clear()
        activeRemoteForwards.clear()
        try { channel?.disconnect() } catch (e: Exception) { android.util.Log.d("SshClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        try { session?.disconnect() } catch (e: Exception) { android.util.Log.d("SshClient", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        channel = null; session = null; channelIn = null; channelOut = null
    }
}
