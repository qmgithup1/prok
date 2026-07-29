package com.systemsgo.hex.mosh.protocol

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.systemsgo.hex.data.model.MoshPredictionMode
import com.systemsgo.hex.data.model.MoshProfile
import com.systemsgo.hex.ssh.protocol.SshAuthMode
import com.systemsgo.hex.ssh.protocol.SshInteractivePrompt
import com.systemsgo.hex.ssh.protocol.SshInteractivePromptField
import com.systemsgo.hex.ssh.protocol.TofuHostKeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * MOSH SUPPORT: runs `mosh-server` on the remote host over a plain SSH
 * exec channel and parses its `MOSH CONNECT <port> <key>` reply. This is
 * the *entire* SSH-bootstrap half of Mosh support — real work, usable and
 * testable on its own, and it does not depend on any of the native JNI
 * scaffolding in [com.systemsgo.hex.mosh.native.MoshBridge] or mosh/NOTES.md's
 * Part 2/3 (those cover the UDP/SSP phase that starts *after* this class
 * hands back a [MoshConnectInfo]).
 *
 * Uses a dedicated JSch [Session] (not shared with an interactive
 * [com.systemsgo.hex.ssh.protocol.SshClient]), the same
 * [com.systemsgo.hex.ssh.protocol.TofuHostKeyRepository]-backed host-key
 * persistence, and a one-shot exec-and-collect-stdout helper.
 *
 * What this class does NOT do: anything after the CONNECT line. Opening
 * the UDP socket, AES-128-OCB framing, and mosh's own terminal emulator
 * are native-code work tracked in mosh/NOTES.md (Parts 2-4) — this class
 * only gets you the host/port/key you'd hand to that layer.
 */
class MoshSessionManager(
    private val profile: MoshProfile,
    private val password: CharArray,
    private val appContext: Context,
    // MOSH-RDPPROFILE-MERGE FEATURE: RdpProfile carries PEM key material
    // directly (sshPrivateKey/sshPrivateKeyPassphrase — see RdpProfile.kt's
    // MOSH-specific fields doc comment), the same way SshCredentials does,
    // rather than MoshProfile's own keystore-alias design
    // (privateKeyAlias) that nothing has ever implemented loading from.
    // RemoteSessionFactory passes these straight through when building the
    // MoshProfile for a MOSH-type RdpProfile; empty for PASSWORD auth.
    private val privateKeyPem: CharArray = CharArray(0),
    private val privateKeyPassphrase: CharArray = CharArray(0),
) {

    private var session: Session? = null

    // KBD-INT FIX: same bridge as SshClient's InteractiveUserInfo (see that
    // class's doc comment for the full reasoning) — the SSH bootstrap here
    // uses JSch exactly like SshClient does, so a server that challenges
    // with real `keyboard-interactive` (TOTP/PAM) needs the exact same
    // synchronous-callback-to-StateFlow bridge, or auth just fails silently
    // with no chance for the user to answer. Previously this class had no
    // UserInfo/UIKeyboardInteractive at all.
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
            _authPrompt.value = SshInteractivePrompt(
                name = name.orEmpty(),
                instruction = instruction.orEmpty(),
                prompts = fields,
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
        override fun showMessage(message: String?) {}
    }

    private val _authPrompt = MutableStateFlow<SshInteractivePrompt?>(null)
    val authPrompt: StateFlow<SshInteractivePrompt?> = _authPrompt.asStateFlow()

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

    suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        authPromptQueue = java.util.concurrent.LinkedBlockingQueue()
        val jsch = JSch()
        // Same persisted, encrypted TOFU store SshClient uses, in its own
        // prefs bucket so a Mosh profile and an unrelated SSH connection to
        // the same host:port are tracked independently.
        jsch.hostKeyRepository = TofuHostKeyRepository(
            appContext = appContext,
            defaultPort = profile.sshPort,
            prefsName = TofuHostKeyRepository.PREFS_TOFU_DEFAULT + "_mosh",
        )
        // PRIVATE-KEY-AUTH FIX: previously this threw unconditionally for any
        // PRIVATE_KEY profile. Wired up now exactly like SshClient.connect()
        // does — addIdentity() before getSession()/connect(), key material
        // zeroed immediately after JSch has consumed it.
        if (profile.authMode == SshAuthMode.PRIVATE_KEY && privateKeyPem.isNotEmpty()) {
            val pemBuf = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(privateKeyPem))
            val pemBytes = ByteArray(pemBuf.remaining()).also { pemBuf.get(it) }
            val passBytes: ByteArray? = if (privateKeyPassphrase.isNotEmpty()) {
                val passBuf = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(privateKeyPassphrase))
                ByteArray(passBuf.remaining()).also { passBuf.get(it) }
            } else null
            try {
                jsch.addIdentity("systemsgo-mosh-key", pemBytes, null, passBytes)
            } finally {
                privateKeyPem.fill('\u0000')
                privateKeyPassphrase.fill('\u0000')
            }
        }
        val sess = jsch.getSession(profile.username, profile.host, profile.sshPort)
        if (profile.authMode == SshAuthMode.PASSWORD) {
            sess.setPassword(String(password))
        }
        password.fill('\u0000')
        sess.userInfo = InteractiveUserInfo()
        sess.setConfig("StrictHostKeyChecking", "accept-new")
        sess.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        sess.timeout = 15_000
        sess.connect(15_000)
        session = sess
    }

    fun disconnect() {
        // KBD-INT FIX: same reasoning as SshClient.disconnect() — don't leave
        // InteractiveUserInfo.promptKeyboardInteractive() blocked forever on
        // authPromptQueue.take() if disconnect() is called while a prompt is
        // still pending (e.g. user backs out of the connect screen).
        _authPrompt.value = null
        authPromptQueue.offer(null)
        session?.disconnect()
        session = null
    }

    /**
     * Runs `mosh-server new ...` per [profile]'s settings and returns the
     * parsed CONNECT line. Throws [MoshProtocolException] if the command
     * fails or its reply doesn't contain a recognizable CONNECT line
     * (wrong `mosh-server` version, no mosh-server installed, a shell
     * banner/MOTD swallowing stdout in an unusual way, etc.).
     */
    suspend fun startSession(): MoshConnectInfo = withContext(Dispatchers.IO) {
        val args = buildList {
            add("new")
            add("-s") // stateless: don't write a utmp entry for this session
            add("-c"); add(profile.colorMode.toString())
            if (profile.udpPortRange.isNotBlank()) {
                add("-p"); add(profile.udpPortRange)
            }
            if (profile.remoteLocale.isNotBlank()) {
                add("-l"); add("LANG=${profile.remoteLocale}")
            }
        }
        val command = "${profile.remoteMoshServerCommand} ${args.joinToString(" ") { shellQuote(it) }}"
        parseConnectLine(exec(command))
    }

    // --- internals ---------------------------------------------------

    private fun exec(command: String): String {
        val sess = session ?: throw MoshProtocolException("Not connected")
        val ch = sess.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        ch.outputStream = null
        ch.setErrStream(stderr)
        val inStream = ch.inputStream
        ch.connect(10_000)
        val buf = ByteArray(4096)
        while (true) {
            val n = inStream.read(buf)
            if (n < 0) break
            stdout.write(buf, 0, n)
        }
        // Best-effort wait for the channel to actually close so exit-status is valid.
        // mosh-server daemonizes after printing the CONNECT line (closes its
        // inherited stdout/stderr as part of detaching from the SSH session),
        // so this returns as soon as that happens rather than blocking for the
        // lifetime of the remote shell it started.
        while (!ch.isClosed) Thread.sleep(20)
        val exitStatus = ch.exitStatus
        ch.disconnect()
        val stdoutText = stdout.toString(Charsets.UTF_8.name())
        // Deliberately not checking exitStatus != 0: some mosh-server builds
        // exit non-zero from the *foreground* process by design once the
        // real session has detached, even on success. The CONNECT line's
        // presence/absence is the actual signal.
        if (!stdoutText.contains("MOSH CONNECT")) {
            throw MoshProtocolException(
                "mosh-server did not return a MOSH CONNECT line (exit $exitStatus): $command\n" +
                "stdout: $stdoutText\nstderr: ${stderr.toString(Charsets.UTF_8.name())}"
            )
        }
        return stdoutText
    }

    private fun parseConnectLine(reply: String): MoshConnectInfo {
        // "MOSH CONNECT <port> <key>" — key uses mosh's own base64-like alphabet
        // (RFC 4648 base64 with padding stripped and length fixed at 22 chars
        // for a 128-bit key), which is why it's kept as an opaque string here
        // rather than decoded: the SSP/crypto layer that actually consumes it
        // (Part 3/N, mosh/NOTES.md) is the right place to interpret it, since
        // that's also where the matching encoder/decoder needs to live.
        val line = reply.lineSequence().firstOrNull { it.trim().startsWith("MOSH CONNECT") }
            ?: throw MoshProtocolException("No MOSH CONNECT line in reply: $reply")
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 4) {
            throw MoshProtocolException("Malformed MOSH CONNECT line: $line")
        }
        val udpPort = fields[2].toIntOrNull()
            ?: throw MoshProtocolException("Non-numeric UDP port in CONNECT line: $line")
        val key = fields[3]
        return MoshConnectInfo(
            remoteHost = profile.host,
            udpPort = udpPort,
            sessionKey = key,
        )
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
