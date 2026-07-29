package com.systemsgo.hex.guacamole.protocol

import android.content.Context
import com.systemsgo.hex.remote.CertificateChallenge
import com.systemsgo.hex.security.openEncryptedPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * GUACAMOLE-PROTOCOL FEATURE (Part 6/N).
 *
 * Real TOFU (trust-on-first-use) certificate verification for
 * [GuacamoleTunnelClient]'s WebSocket connection, replacing the interim
 * trust-all `acceptSelfSignedCertificate` escape hatch with the same
 * "ask the user on first contact, pin the fingerprint, hard-reject a later
 * change" flow [com.systemsgo.hex.telnet.protocol.TelnetClient] and
 * [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter] already use — see
 * [buildTrustManager]'s doc comment for the exact decision flow, and
 * [com.systemsgo.hex.remote.CertificateChallenge]'s own class doc for why
 * this replaced a blind accept-everything toggle in the first place.
 *
 * Owned by [GuacamoleSessionClient] (one instance per session — see
 * [GuacamoleSessionClient.certificateChallenge], which
 * [com.systemsgo.hex.ui.screens.RdpSessionActivity] already collects
 * generically for any [com.systemsgo.hex.remote.RemoteSessionClient], so
 * no new UI plumbing is needed for the prompt to actually show up). NOT
 * used for [GuacamoleAuthClient]'s REST calls — those happen *before* a
 * [GuacamoleSessionClient] (and therefore any `certificateChallenge`
 * surface) exists, during [com.systemsgo.hex.guacamole.GuacamoleRepository.login];
 * see that REST leg's own silent-TOFU handling in [GuacamoleAuthClient]
 * for why it can't use this same interactive flow, and why the two legs
 * deliberately use separate pinned-fingerprint stores rather than sharing
 * one (so the REST leg's silent auto-pin-on-first-contact can never
 * pre-empt the tunnel's interactive prompt from ever firing).
 */
class GuacamoleCertificateVerifier(private val appContext: Context) {

    private val _certificateChallenge = MutableStateFlow<CertificateChallenge?>(null)
    val certificateChallenge: StateFlow<CertificateChallenge?> = _certificateChallenge.asStateFlow()

    private val certPrefs by lazy { appContext.openEncryptedPrefs(PREFS_TOFU_GUACAMOLE_TUNNEL) }

    /** Called from the UI once the person responds to a shown [certificateChallenge] — mirrors every other protocol's respond-and-clear pattern. */
    fun respond(decision: CertificateChallenge.Decision) {
        _certificateChallenge.value?.respond(decision)
        _certificateChallenge.value = null
    }

    /**
     * Builds an [X509TrustManager] for [host]:[port] that performs the TOFU
     * decision flow instead of validating against the platform trust store
     * or trusting everything. Called once per [GuacamoleTunnelClient]
     * connection attempt (see that class's `buildHttpClient`).
     */
    fun buildTrustManager(host: String, port: Int): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw java.security.cert.CertificateException("No server certificate presented")
            val fingerprint = sha256Fingerprint(leaf)
            val commonName = leaf.subjectX500Principal?.name.orEmpty()
            val issuer = leaf.issuerX500Principal?.name.orEmpty()
            val trusted = verify(host, port, commonName, issuer, fingerprint)
            if (!trusted) throw java.security.cert.CertificateException("Certificate not trusted")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Same TOFU decision flow as `TelnetClient.verifyServerCertificate`:
     * first connection to a host pins the fingerprint (after the user
     * confirms via [CertificateChallenge]); later connections compare
     * silently, and a changed fingerprint is always a hard reject (possible
     * MITM) rather than a re-prompt. Called synchronously from the TLS
     * handshake thread — must not suspend, but may block on
     * [CertificateChallenge.awaitDecision].
     */
    private fun verify(host: String, port: Int, commonName: String, issuer: String, fingerprint: String): Boolean {
        val key = "$host:$port"
        val stored = certPrefs.getString(key, null)
        if (stored != null) return stored == fingerprint // Changed fingerprint = hard reject, never a re-prompt — see doc comment.

        val challenge = CertificateChallenge(host, port, commonName, issuer, fingerprint)
        _certificateChallenge.value = challenge
        val decision = challenge.awaitDecision()
        _certificateChallenge.value = null

        return when (decision) {
            CertificateChallenge.Decision.REJECT -> false
            CertificateChallenge.Decision.ACCEPT_ONCE -> true
            CertificateChallenge.Decision.ACCEPT_ALWAYS -> {
                certPrefs.edit().putString(key, fingerprint).commit()
                true
            }
        }
    }

    private fun sha256Fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private companion object {
        const val PREFS_TOFU_GUACAMOLE_TUNNEL = "guacamole_tofu_tunnel_certs"
    }
}
