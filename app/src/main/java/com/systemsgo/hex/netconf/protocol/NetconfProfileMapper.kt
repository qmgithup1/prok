package com.systemsgo.hex.netconf.protocol

import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.model.SshAuthType
import com.systemsgo.hex.proxy.PacProxyResolver
import com.systemsgo.hex.ssh.protocol.SshAuthMode

/**
 * NETCONF FEATURE: builds a [NetconfClient] straight from a
 * [RdpProfile] whose `protocolType` is `NETCONF`, the same role
 * [com.systemsgo.hex.remote.RemoteSessionFactory] plays for RDP/VNC/SSH —
 * kept as its own small object (rather than folded into that factory)
 * because [NetconfClient] is not a [com.systemsgo.hex.remote.RemoteSessionClient]
 * and is driven by its own session Activity, not [com.systemsgo.hex.ui.
 * screens.RdpSessionActivity].
 */
object NetconfProfileMapper {

    fun toCredentials(profile: RdpProfile): NetconfCredentials {
        require(profile.protocolType == com.systemsgo.hex.data.model.ProtocolType.NETCONF) {
            "NetconfProfileMapper.toCredentials called with a non-NETCONF profile"
        }
        val authMode = when (profile.sshAuthType) {
            SshAuthType.PASSWORD -> NetconfAuthMode.PASSWORD
            SshAuthType.PRIVATE_KEY -> NetconfAuthMode.PRIVATE_KEY
        }
        return NetconfCredentials(
            host = profile.host,
            port = profile.port,
            username = profile.username,
            authMode = authMode,
            password = profile.password,
            privateKeyPem = profile.sshPrivateKey,
            privateKeyPassphrase = profile.sshPrivateKeyPassphrase,
            openSshCertificate = profile.netconfOpenSshCertificate,
            tlsClientCertificatePem = profile.netconfCallHomeTlsClientCertificatePem,
        )
    }

    fun toJumpHops(profile: RdpProfile): List<NetconfJumpHop> =
        profile.sshTunnelHops.map { hop ->
            NetconfJumpHop(
                host = hop.host,
                port = hop.port,
                username = hop.username,
                authMode = when (hop.authType) {
                    SshAuthType.PASSWORD -> SshAuthMode.PASSWORD
                    SshAuthType.PRIVATE_KEY -> SshAuthMode.PRIVATE_KEY
                },
                password = hop.password,
                privateKeyPem = hop.privateKey,
                privateKeyPassphrase = hop.privateKeyPassphrase,
            )
        }

    fun toRequestedCapabilities(profile: RdpProfile): List<String> {
        val base = listOf(
            "urn:ietf:params:netconf:base:1.0",
            "urn:ietf:params:netconf:base:1.1",
        )
        val extra = profile.netconfExtraCapabilities.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return base + extra
    }

    /** Builds a ready-to-connect client. Does not call [NetconfClient.connect] itself. */
    fun buildClient(
        profile: RdpProfile,
        appContext: android.content.Context,
        outboundProxy: PacProxyResolver.Resolved = PacProxyResolver.Resolved.Direct,
        /** CALL-HOME FEATURE (RFC 8071, SSH variant): non-null only when built by `NetconfCallHomeService` from an accepted inbound socket tagged [CallHomeTransport.SSH] — see [NetconfClient]'s preAcceptedSocket doc comment. Jump hops / outbound proxy are meaningless (and silently unused) when this is set. Mutually exclusive with [preAcceptedTlsSocket]. */
        preAcceptedSocket: java.net.Socket? = null,
        /** CALL-HOME-TLS FEATURE (RFC 8071, TLS variant): non-null only when built by `NetconfCallHomeService` from an accepted inbound socket tagged [CallHomeTransport.TLS] — see [NetconfClient]'s tlsSocket doc comment. Mutually exclusive with [preAcceptedSocket]. */
        preAcceptedTlsSocket: java.net.Socket? = null,
    ): NetconfClient = NetconfClient(
        credentials = toCredentials(profile),
        appContext = appContext,
        jumpHops = toJumpHops(profile),
        preAcceptedSocket = preAcceptedSocket,
        tlsSocket = preAcceptedTlsSocket,
        callHomeIdentity = if (preAcceptedSocket != null || preAcceptedTlsSocket != null) "callhome:${profile.id}" else null,
        outboundProxy = outboundProxy,
        connectTimeoutMs = profile.netconfConnectTimeoutMs,
        sshKeepAliveMs = profile.netconfKeepAliveMs,
        compressionEnabled = profile.netconfCompressionEnabled,
        requestedCapabilities = toRequestedCapabilities(profile),
        autoReconnect = profile.netconfAutoReconnect,
    )
}
