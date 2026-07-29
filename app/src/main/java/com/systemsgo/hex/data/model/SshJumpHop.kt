package com.systemsgo.hex.data.model

import java.util.UUID

/**
 * SSH-PROXYJUMP-CHAIN FEATURE: a single hop in a full SSH `ProxyJump` chain
 * (OpenSSH's `-J host1,host2,host3`), stored on [RdpProfile.sshTunnelHops].
 *
 * Ordering matters: index 0 is the first server reachable from the device,
 * the last entry is the hop adjacent to the RDP/VNC/Telnet target — the same
 * ordering [com.systemsgo.hex.ssh.protocol.SshTunnelManager] expects for its
 * `hops: List<SshTunnelCredentials>` constructor (see that class's doc
 * comment). [com.systemsgo.hex.remote.RemoteSessionFactory] is what converts
 * a `List<SshJumpHop>` into a `List<SshTunnelCredentials>` right before
 * building an [com.systemsgo.hex.remote.SshTunneledClient].
 *
 * [id] exists only to give the (future) profile-editor UI a stable key for
 * list editing (add/remove/reorder) and to give each hop's secrets a stable
 * per-hop AAD binding in [com.systemsgo.hex.data.repository.RdpProfileRepository]
 * — the same reasoning as [SshPortForwardRule.id]. It has no meaning to JSch
 * or the wire protocol and is never persisted as part of the encoded form's
 * ordering (hops are matched by position on decode, same as port-forward
 * rules), only alongside each hop's own fields.
 */
data class SshJumpHop(
    val id: String = UUID.randomUUID().toString(),
    val host: String = "",             // SSH jump-host IP or hostname
    val port: Int = 22,                // SSH server port (usually 22)
    val username: String = "",
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val password: String = "",         // Stored encrypted (see RdpProfileRepository)
    val privateKey: String = "",       // PEM contents, if PRIVATE_KEY. Stored encrypted.
    val privateKeyPassphrase: String = "", // Stored encrypted.
) {
    /** True once every field holds a value a connection attempt could actually use. */
    val isValid: Boolean
        get() = host.isNotBlank() && port in 1..65535 && username.isNotBlank()
}

/**
 * Serialises a list of [SshJumpHop] to/from a single delimited string for
 * Room storage — the same lightweight approach already used for
 * [RdpProfile.sshPortForwards] (see [SshPortForwardCodec]): "\u001F" (Unit
 * Separator) separates hops, "\u001E" (Record Separator) separates a hop's
 * own fields. Neither character can appear in a hostname, username, port
 * number, or (in practice) a password/PEM key, so no escaping is needed —
 * same assumption [SshPortForwardCodec] already makes for its own fields.
 */
object SshJumpHopCodec {
    private const val HOP_SEPARATOR = "\u001F"
    private const val FIELD_SEPARATOR = "\u001E"

    fun encode(hops: List<SshJumpHop>): String =
        hops.joinToString(HOP_SEPARATOR) { hop ->
            listOf(
                hop.id,
                hop.host,
                hop.port.toString(),
                hop.username,
                hop.authType.name,
                hop.password,
                hop.privateKey,
                hop.privateKeyPassphrase,
            ).joinToString(FIELD_SEPARATOR)
        }

    fun decode(value: String): List<SshJumpHop> {
        if (value.isEmpty()) return emptyList()
        return value.split(HOP_SEPARATOR).mapNotNull { record ->
            val f = record.split(FIELD_SEPARATOR)
            if (f.size != 8) return@mapNotNull null
            val port = f[2].toIntOrNull() ?: return@mapNotNull null
            SshJumpHop(
                id = f[0].ifEmpty { UUID.randomUUID().toString() },
                host = f[1],
                port = port,
                username = f[3],
                authType = SshAuthType.entries.firstOrNull { it.name == f[4] } ?: SshAuthType.PASSWORD,
                password = f[5],
                privateKey = f[6],
                privateKeyPassphrase = f[7],
            )
        }
    }
}
