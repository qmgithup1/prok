package com.systemsgo.hex.data.model

import java.util.UUID

/**
 * SSH-PORT-FORWARD FEATURE: direction of a single static port-forwarding
 * rule — the equivalent of OpenSSH's `-L` (LOCAL) and `-R` (REMOTE) flags.
 *
 * Distinct from [RdpProfile.socksProxyEnabled] (`-D`, dynamic — any
 * destination the SOCKS client asks for) and [RdpProfile.sshTunnelEnabled]
 * (this app's own internal single-purpose tunnel for its RDP/VNC/Telnet
 * client): this is a user-defined *static* forward, wired up on the same
 * authenticated session right alongside the interactive shell, targeting one
 * fixed destination for the lifetime of the session.
 */
enum class SshPortForwardType {
    /** `ssh -L [bindAddress:]listenPort:destHost:destPort` — a port opened
     *  on THIS device is relayed to destHost:destPort as seen from the SSH
     *  server. */
    LOCAL,

    /** `ssh -R [bindAddress:]listenPort:destHost:destPort` — a port opened
     *  on the SSH SERVER is relayed back to destHost:destPort as seen from
     *  this device. Whether the server actually honours a non-loopback
     *  [SshPortForwardRule.bindAddress] depends on its own `GatewayPorts`
     *  policy — this app has no control over that. */
    REMOTE;

    companion object {
        fun fromName(name: String): SshPortForwardType =
            entries.firstOrNull { it.name == name } ?: LOCAL
    }
}

/**
 * A single static SSH port-forwarding rule (see [SshPortForwardType]).
 *
 * [id] exists only to give the profile-editor UI a stable key for list
 * editing (add/remove/reorder) — it has no meaning to JSch or the wire
 * protocol and is never persisted as part of the encoded form (see
 * [SshPortForwardCodec]); rules are matched by position on decode.
 */
data class SshPortForwardRule(
    val id: String = UUID.randomUUID().toString(),
    val type: SshPortForwardType = SshPortForwardType.LOCAL,
    // Interface the listening socket binds on.
    //  - LOCAL: on-device. "127.0.0.1" = this app/device only (recommended
    //    default); "0.0.0.0" opens the forward to the whole LAN through this
    //    device, same trade-off as ssh -L's own bind-address argument.
    //  - REMOTE: passed to the SSH server as ITS bind address. Most sshd
    //    configs ignore anything but "127.0.0.1" here unless the server's
    //    GatewayPorts setting allows otherwise.
    val bindAddress: String = "127.0.0.1",
    val listenPort: Int = 0,
    val destHost: String = "",
    val destPort: Int = 0,
) {
    /** True once every field holds a value JSch could actually act on. */
    val isValid: Boolean
        get() = bindAddress.isNotBlank() &&
            listenPort in 1..65535 &&
            destHost.isNotBlank() &&
            destPort in 1..65535
}

/**
 * Serialises a list of [SshPortForwardRule] to/from a single delimited
 * string for Room storage — the same lightweight approach already used for
 * [RdpProfile.tags] (see Converters.fromTagList/toTagList): "\u001F" (Unit
 * Separator) separates rules, "\u001E" (Record Separator) separates a
 * rule's own fields. Neither character can appear in a hostname, IP
 * address, or port number, so no escaping is needed.
 */
object SshPortForwardCodec {
    private const val RULE_SEPARATOR = "\u001F"
    private const val FIELD_SEPARATOR = "\u001E"

    fun encode(rules: List<SshPortForwardRule>): String =
        rules.joinToString(RULE_SEPARATOR) { rule ->
            listOf(
                rule.type.name,
                rule.bindAddress,
                rule.listenPort.toString(),
                rule.destHost,
                rule.destPort.toString(),
            ).joinToString(FIELD_SEPARATOR)
        }

    fun decode(value: String): List<SshPortForwardRule> {
        if (value.isEmpty()) return emptyList()
        return value.split(RULE_SEPARATOR).mapNotNull { record ->
            val f = record.split(FIELD_SEPARATOR)
            if (f.size != 5) return@mapNotNull null
            val listenPort = f[2].toIntOrNull() ?: return@mapNotNull null
            val destPort = f[4].toIntOrNull() ?: return@mapNotNull null
            SshPortForwardRule(
                type = SshPortForwardType.fromName(f[0]),
                bindAddress = f[1],
                listenPort = listenPort,
                destHost = f[3],
                destPort = destPort,
            )
        }
    }
}
