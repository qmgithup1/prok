package com.systemsgo.hex.data.model

import com.systemsgo.hex.ssh.protocol.SshAuthMode

/**
 * MOSH SUPPORT: connection profile for a Mosh session.
 *
 * Mosh has no listening daemon of its own reachable directly — a session
 * always starts with a plain SSH exec of `mosh-server` on the target host
 * (this is exactly how the real `mosh` client works too, not a shortcut
 * this app is taking). That command prints one line,
 * `MOSH CONNECT <udp-port> <base64-key>`, and then Mosh's own UDP-based
 * SSP (State Synchronization Protocol) takes over for the rest of the
 * session. See [com.systemsgo.hex.mosh.protocol.MoshSessionManager] for
 * the exec/parse step and mosh/NOTES.md for what's still native-code work.
 *
 * Fields here only cover the SSH-bootstrap phase (this class's own scope).
 * Terminal-emulation/display settings for the SSP phase itself belong on
 * whatever session-client model consumes [com.systemsgo.hex.mosh.native.MoshBridge]
 * once that exists (Part 4/N).
 */
data class MoshProfile(
    val id: Long = 0,
    val name: String = "",
    val host: String = "",
    val sshPort: Int = 22,
    val username: String = "",
    val authMode: SshAuthMode = SshAuthMode.PASSWORD,
    // Secrets are never stored in this model in plaintext long-term; the
    // repository layer follows the same encrypted-credential path used by
    // RdpProfile (see security/CryptoHelper + DatabaseKeyProvider).
    val savedPassword: String = "",
    val privateKeyAlias: String = "",

    /** Path to the `mosh-server` binary on the remote host. Almost always fine as-is; only matters on hosts where it's not on $PATH. */
    val remoteMoshServerCommand: String = "mosh-server",

    /** UDP port range passed to `mosh-server -p`, e.g. "60000:61000". Empty = let mosh-server pick from its own default range. */
    val udpPortRange: String = "",

    /** `LANG` value forwarded via `mosh-server -l LANG=...`. Empty = don't override the server's own locale. */
    val remoteLocale: String = "",

    /** `mosh-server -c <n>` — 8/16/88/256, matching the terminal's color support. */
    val colorMode: Int = 256,

    /** Predictive local echo mode, matching upstream `mosh --predict=`. */
    val predictionMode: MoshPredictionMode = MoshPredictionMode.ADAPTIVE,

    /** Jump-host chain for the SSH bootstrap step, reusing the same model already shared by SSH/RDP profiles. */
    val jumpHops: List<SshJumpHop> = emptyList(),
)

enum class MoshPredictionMode { ADAPTIVE, ALWAYS, NEVER }
