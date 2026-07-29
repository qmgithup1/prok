package com.systemsgo.hex.security

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Thrown by [TofuTrustManager.checkServerTrusted] when the presented
 * certificate's fingerprint doesn't match the one pinned on first
 * connect — the same "possible MITM, hard abort" signal as
 * [com.systemsgo.hex.netconf.protocol.NetconfTlsPinMismatchException] and
 * [com.jcraft.jsch.HostKeyRepository.CHANGED].
 */
class TofuCertificateMismatchException(message: String) : CertificateException(message)

/**
 * SECURITY FIX (TLS-TOFU-PARITY): shared silent TOFU (Trust-On-First-Use)
 * [X509TrustManager], used by every client whose "accept self-signed
 * certificate" opt-in previously fell back to a blind trust-all
 * `X509TrustManager` (empty `checkServerTrusted`, no pinning, no MITM
 * detection ever) instead of the TOFU pattern this app already uses
 * elsewhere — see [com.systemsgo.hex.telnet.protocol.TelnetClient]
 * ("verifyServerCertificate"), `RdpRemoteAdapter.verifyServerCertificate`,
 * [com.systemsgo.hex.netconf.protocol.NetconfTlsTofuTrustManager], and
 * [com.systemsgo.hex.guacamole.protocol.GuacamoleCertificateVerifier].
 *
 * Before this fix, once a person turned on "accept self-signed
 * certificate" for RESTCONF, Redfish, or an RD Web feed, *every* future
 * connection for that profile trusted *any* certificate presented, with
 * hostname verification disabled on top — meaning a later on-path attacker
 * could swap in their own certificate at any time and the app would never
 * notice, unlike Telnet/RDP/NETCONF/Guacamole which detect and hard-reject
 * a changed certificate. This class closes that gap for clients (RESTCONF,
 * Redfish, RD Web feed) that talk to a server on a background/API thread
 * with no session-scoped UI surface to show an interactive
 * `CertificateChallenge` dialog through — the first certificate seen for
 * [identity] is pinned automatically (no prompt, matching the pre-existing
 * "just works" expectation of the self-signed toggle), and every later
 * connection must present that exact same certificate or the handshake is
 * aborted. This mirrors [com.systemsgo.hex.netconf.protocol.NetconfTlsTofuTrustManager]'s
 * own "silent" flavor (used for Call Home, which is equally non-interactive)
 * rather than [com.systemsgo.hex.guacamole.protocol.GuacamoleCertificateVerifier]'s
 * interactive one.
 *
 * Callers should still disable hostname verification alongside this, the
 * same way [com.systemsgo.hex.rdp.transport.RdpWebSocketTransport] and
 * [com.systemsgo.hex.guacamole.protocol.GuacamoleTunnelClient] already do:
 * an exact certificate fingerprint pin is a *strictly stronger* identity
 * check than a CN/SAN hostname match (it verifies the literal bytes the
 * server presented, not just a name in them), so skipping the now-redundant
 * hostname check once a fingerprint pin is enforced is not a regression.
 *
 * One instance per connection attempt, keyed by [identity] (callers use
 * `"$host:$port"`, same convention as [com.systemsgo.hex.telnet.protocol.TelnetClient]
 * and [com.systemsgo.hex.guacamole.protocol.GuacamoleCertificateVerifier]).
 */
class TofuTrustManager(
    private val appContext: Context,
    private val identity: String,
) : X509TrustManager {

    companion object {
        private const val TAG = "TofuTrustManager"
        const val PREFS_TOFU_SHARED = "systemsgo_tofu_shared_http"

        /** Removes the pinned certificate for [identity] — e.g. a future "forget this device/server" Settings action, after the user confirms a legitimate certificate reissue. */
        fun clearPin(appContext: Context, identity: String) {
            appContext.openEncryptedPrefs(PREFS_TOFU_SHARED).edit().remove(identity).commit()
        }
    }

    // EncryptedSharedPreferences, not plain prefs — same reasoning as every
    // other TOFU store in this app: a root-privileged attacker shouldn't be
    // able to silently swap a pinned fingerprint to defeat MITM detection.
    private val prefs by lazy { appContext.openEncryptedPrefs(PREFS_TOFU_SHARED) }

    /**
     * Never actually invoked in practice: this app is always the TLS
     * *client*, so the platform never asks a client-side [X509TrustManager]
     * to validate a *client* certificate chain. Implemented as a no-op
     * (accept) rather than throwing, purely so this class remains a
     * well-behaved [X509TrustManager] if some TLS provider ever calls it
     * defensively — same as every other TOFU trust manager in this app.
     */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    /** Called by the TLS provider mid-handshake with the server's presented certificate chain — throwing here aborts the handshake. */
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("TLS peer for '$identity' presented an empty certificate chain")
        val fingerprint = sha256Fingerprint(leaf)
        val stored = prefs.getString(identity, null)
        when {
            stored == null -> {
                // commit() (synchronous), not apply() — an OOM kill right
                // after a successful first handshake must not lose the pin,
                // or the next connection would silently re-pin as "new"
                // with no MITM warning ever shown.
                prefs.edit().putString(identity, fingerprint).commit()
                Log.i(TAG, "TLS TOFU: pinned new certificate for '$identity' (SHA-256 $fingerprint)")
            }
            stored == fingerprint -> Unit
            else -> {
                Log.w(TAG, "TLS TOFU: certificate fingerprint CHANGED for '$identity' — possible MITM!")
                throw TofuCertificateMismatchException(
                    "The certificate presented for '$identity' does not match the one pinned on first " +
                        "connect. If the certificate was legitimately reissued, clear the saved pin " +
                        "before reconnecting.",
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
