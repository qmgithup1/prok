package com.systemsgo.hex.ssh.protocol

import android.util.Log
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.ProxySOCKS5
import com.systemsgo.hex.data.model.ProxyType
import com.systemsgo.hex.proxy.PacProxyResolver

/**
 * OUTBOUND-PROXY-FOR-SSH FEATURE: maps [PacProxyResolver.Resolved] — this
 * app's protocol-agnostic outbound-proxy decision, already resolved from
 * either [com.systemsgo.hex.data.model.RdpProfile.pacUrl] or the static
 * proxy* fields (see [PacProxyResolver.resolve]'s doc comment for that
 * priority) — onto JSch's own `com.jcraft.jsch.Proxy` interface, so
 * [SshClient]/[SshTunnelManager] can apply it via `Session.setProxy()`
 * before `connect()`. This is the SSH-side counterpart to how
 * [com.systemsgo.hex.rdp.native.AFreeRdpBridge].connect() already consumes
 * the exact same [PacProxyResolver.Resolved] shape for RDP (translated to
 * proxyEnabled/proxyType.ordinal/proxyHost/proxyPort right before the
 * native `nativeConnect` call).
 *
 * Returns `null` for [PacProxyResolver.Resolved.Direct] — the natural
 * "don't call setProxy() at all" case, since a plain JSch [Session] dials
 * directly whenever no [Proxy] has been set (its own built-in default).
 *
 * SOCKS4-VS-SOCKS5 NOTE: a PAC script's `SOCKS` keyword predates the SOCKS5
 * protocol (the original Netscape PAC spec only had SOCKS4 in mind) and
 * doesn't distinguish a version at all. [ProxySOCKS5] is used here rather
 * than JSch's `ProxySOCKS4` since v5 is a superset (supports v4-style
 * anonymous CONNECT with no extra config, plus optional auth) and is what
 * virtually every proxy actually deployed today speaks; a server that is
 * genuinely SOCKS4-only would need a future per-profile toggle to opt into
 * `ProxySOCKS4` instead — not needed by anything this app's PAC/static
 * config can currently express.
 *
 * HTTPS-PROXY LIMITATION: [ProxyType.HTTPS] (this app's own RDP-side
 * extension — see that enum's doc comment for why stock FreeRDP needed a
 * patch to support it at all) has no JSch equivalent: JSch's [ProxyHTTP]
 * performs a plaintext CONNECT to the proxy, the same as FreeRDP's
 * PROXY_TYPE_HTTP, with no TLS handshake to the proxy itself. A profile
 * whose *static* proxyType is HTTPS but is being applied to an SSH-based
 * connection can only reach this function via [PacProxyResolver]'s
 * static-fallback path — a PAC script itself can never produce HTTPS, only
 * PROXY/SOCKS/DIRECT — and is downgraded to plain HTTP here rather than
 * silently failing or crashing the connection; this is logged so the
 * downgrade is visible instead of being an invisible behavior change.
 */
fun PacProxyResolver.Resolved.toJschProxy(): Proxy? = when (this) {
    is PacProxyResolver.Resolved.Direct -> null
    is PacProxyResolver.Resolved.UseProxy -> when (type) {
        ProxyType.NONE -> null
        ProxyType.HTTP -> ProxyHTTP(host, port).withOptionalAuth(username, password)
        ProxyType.HTTPS -> {
            Log.w(
                "SshProxyMapper",
                "proxyType=HTTPS has no JSch/SSH equivalent (no TLS-to-proxy support) — " +
                    "downgrading to plain HTTP CONNECT for this SSH-based connection to $host:$port"
            )
            ProxyHTTP(host, port).withOptionalAuth(username, password)
        }
        ProxyType.SOCKS -> ProxySOCKS5(host, port).withOptionalAuth(username, password)
    }
}

private fun ProxyHTTP.withOptionalAuth(username: String, password: String): ProxyHTTP =
    apply { if (username.isNotEmpty()) setUserPasswd(username, password) }

private fun ProxySOCKS5.withOptionalAuth(username: String, password: String): ProxySOCKS5 =
    apply { if (username.isNotEmpty()) setUserPasswd(username, password) }
