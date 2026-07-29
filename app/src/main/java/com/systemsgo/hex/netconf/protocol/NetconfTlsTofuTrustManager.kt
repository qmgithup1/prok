package com.systemsgo.hex.netconf.protocol

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.systemsgo.hex.security.openEncryptedPrefs
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Thrown by [NetconfTlsTofuTrustManager.checkServerTrusted] when the presented certificate's fingerprint doesn't match the one pinned on first connect — the TLS-transport equivalent of JSch's [com.jcraft.jsch.HostKeyRepository.CHANGED] / the "possible MITM" abort SSH Call Home already gets from [com.systemsgo.hex.ssh.protocol.TofuHostKeyRepository]. */
class NetconfTlsPinMismatchException(message: String) : CertificateException(message)

/**
 * CALL-HOME-TLS FEATURE (RFC 8071 `netconf-ch-tls` / RFC 7589 transport):
 * TOFU (Trust-On-First-Use) certificate pinning for NETCONF/TLS sessions —
 * the TLS-transport counterpart of
 * [com.systemsgo.hex.ssh.protocol.TofuHostKeyRepository], which already
 * does the equivalent job for every SSH-based transport in this app
 * (interactive SSH, RDP/VNC-over-SSH tunnels, Mosh's SSH bootstrap step,
 * and NETCONF/SSH including SSH Call Home).
 *
 * RFC 7589 §7 describes two conformant ways for a NETCONF/TLS client to
 * authenticate the server's certificate: validating it against a configured
 * trust anchor (a real PKI — not available offline in this app, same
 * constraint noted throughout the YANG/XML-editor phases), or pinning it by
 * a fixed fingerprint. TOFU pinning is the fingerprint approach applied
 * automatically instead of requiring the user to transcribe a fingerprint
 * by hand, exactly mirroring the trust model this app already uses for SSH
 * host keys — first connection wins and is remembered, every later
 * connection must present the same certificate, and NETCONF Call Home over
 * TLS is a case where TOFU is the *only* even-somewhat-automatable option:
 * there is no dial target to have pre-supplied a trust anchor for (see
 * [NetconfClient]'s `callHomeIdentity` doc comment — the same reasoning
 * that already applies to SSH Call Home's TOFU key applies here to TLS Call
 * Home's TOFU certificate).
 *
 * One instance is created per Call Home connection attempt (mirrors
 * [com.systemsgo.hex.ssh.protocol.TofuHostKeyRepository]'s per-session
 * lifetime), keyed by [identity] — the same stable
 * `"callhome:${profile.id}"` string [NetconfClient] already uses to key SSH
 * Call Home's TOFU host key, since a TLS Call Home connection has no fixed
 * host:port of its own either (the source address is whatever the device's
 * own outbound route happens to be).
 */
class NetconfTlsTofuTrustManager(
    private val appContext: Context,
    private val identity: String,
) : X509TrustManager {

    companion object {
        private const val TAG = "NetconfTlsTofuTrustManager"
        const val PREFS_TOFU_NETCONF_TLS = "systemsgo_tofu_netconf_tls"

        /** Removes the pinned certificate for [identity], so the next connection is treated as a fresh first-use (e.g. after the user confirms a legitimate certificate reissue on the device). Exposed statically since callers (a future "forget this device" Settings action) shouldn't need to construct a whole trust manager just to clear one pin. */
        fun clearPin(appContext: Context, identity: String) {
            appContext.openEncryptedPrefs(PREFS_TOFU_NETCONF_TLS).edit().remove(identity).commit()
        }
    }

    // MED-R1 FIX parity (see TofuHostKeyRepository): EncryptedSharedPreferences,
    // not plain prefs — a root-privileged attacker shouldn't be able to silently
    // swap a pinned fingerprint to defeat MITM detection.
    private val prefs: SharedPreferences by lazy { appContext.openEncryptedPrefs(PREFS_TOFU_NETCONF_TLS) }

    /**
     * Never actually invoked in practice: this app is always the TLS
     * *client* in a NETCONF/TLS session (RFC 7589 §7 — Call Home reverses
     * only which side dials the initial TCP connection, not which side
     * plays TLS client vs. TLS server; see [NetconfCallHomeTlsListener]'s
     * doc comment), so the platform never asks a client-side
     * [X509TrustManager] to validate a *client* certificate chain.
     * Implemented as a no-op (accept) rather than throwing, purely so this
     * class remains a well-behaved [X509TrustManager] if some TLS provider
     * ever calls it defensively.
     */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    /** Called by the TLS provider mid-handshake with the device's presented certificate chain — throwing here aborts the handshake, exactly like [com.jcraft.jsch.HostKeyRepository.CHANGED] aborting an SSH connection. */
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("NETCONF/TLS peer presented an empty certificate chain")
        val fingerprint = sha256Fingerprint(leaf)
        val stored = prefs.getString(identity, null)
        when {
            stored == null -> {
                // LIVE-HIGH-1 FIX parity (see TofuHostKeyRepository.add): commit()
                // (synchronous), not apply() — an OOM kill right after a successful
                // first handshake must not lose the pin, or the next connection
                // would silently re-pin as "new" with no MITM warning ever shown.
                prefs.edit().putString(identity, fingerprint).commit()
                Log.i(TAG, "TLS TOFU: pinned new certificate for '$identity' (SHA-256 $fingerprint)")
            }
            stored == fingerprint -> Unit
            else -> {
                Log.w(TAG, "TLS TOFU: certificate fingerprint CHANGED for '$identity' — possible MITM!")
                throw NetconfTlsPinMismatchException(
                    "The certificate presented for '$identity' does not match the one pinned on first " +
                        "connect. If the device's certificate was legitimately reissued, clear the saved " +
                        "pin before reconnecting.",
                )
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
