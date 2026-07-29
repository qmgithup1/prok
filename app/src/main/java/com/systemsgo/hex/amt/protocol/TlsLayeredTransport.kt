package com.systemsgo.hex.amt.protocol

import android.content.Context
import com.systemsgo.hex.security.TofuTrustManager
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * AMT-VPRO FEATURE phase 6 (CIRA), "TLS over CIRA" follow-up — closes the
 * question [AMT_VPRO_ROADMAP.md] and `BmcManagementActivity`'s
 * `ciraWsmanPort`/`ciraRedirectionPort` doc comments flagged as unresolved:
 * whether a CIRA-forwarded channel to one of AMT's own TLS-mode ports
 * (16993 WS-Man, 16995 redirection) needs a *second*, session-level TLS
 * handshake on top of the already-encrypted relay/APF connection.
 *
 * ## The answer (confirmed against MeshCentral's own MPS implementation)
 * Yes, conditionally — and the condition is the *port*, not the tunnel.
 * Intel's own APF Port Forwarding Protocol Reference Manual specifies that
 * a forwarded-tcpip channel's bound port is 16992 *or* 16993 (16994/16995
 * for redirection) "depends on the TLS mode inside Intel AMT" — a setting
 * entirely local to the device's embedded server, independent of whether
 * the outer relay/APF hop is itself encrypted. MeshCentral's MPS server
 * (`mpsserver.js`) implements exactly this split: for a CIRA-originated
 * connection it consults which ports the device itself requested to be
 * forwarded (`ciraconn.tag.boundPorts`, populated from the device's own
 * real `APF_TCP_FORWARD_LISTEN` requests) to decide whether TLS applies to
 * a given channel, and for its Relay/LMS connection types it explicitly
 * tries a TLS handshake on 16993 first and falls back to plain 16992. In
 * both cases the decision is "is *this* port a TLS port", never "is the
 * tunnel already encrypted, so skip TLS" — CIRA/relay transport security
 * and AMT's own local TLS mode are two independent layers that happen to
 * nest.
 *
 * ## What this class does
 * A tiny [AmtRedirectionTransport] *decorator*: wraps an already-open one
 * (a [CiraRelayTransport] channel already opened to 16993/16995 in
 * practice — the *caller* picks the right port before handing this class
 * the channel; this class only adds the handshake on top of whatever
 * channel it's given) and layers a client-side TLS handshake on top of its
 * raw `inputStream`/`outputStream`, using the same "trust-on-first-use,
 * keyed by identity" pattern [AmtClient]'s own direct-connect TLS path and
 * [CiraRelayTransport]'s own `wss://` mode already use — see
 * [TofuTrustManager]'s doc comment. Once the handshake completes, [wrap]
 * returns something that *is* an [AmtRedirectionTransport] itself (the
 * same drop-in shape that interface's own doc comment describes), so
 * nothing above it — [CiraWsmanHttpTransport], [AmtSolSession],
 * [AmtKvmSession], [AmtIderSession] — needs to know it's talking through a
 * second TLS layer at all.
 *
 * ## Why a fake [Socket] instead of a hand-rolled [javax.net.ssl.SSLEngine]
 * [javax.net.ssl.SSLSocketFactory.createSocket(Socket, String, int,
 * boolean)] layers a full TLS client handshake plus record protocol on top
 * of *any* already-connected [Socket]'s [Socket.getInputStream]/
 * [Socket.getOutputStream] — it never calls [Socket.connect] itself, so a
 * minimal [Socket] subclass that only overrides those accessors (plus the
 * handful of state queries the JDK's TLS provider checks) is a complete,
 * correct "connected socket" as far as the TLS layer is concerned. This is
 * the same technique mail libraries (e.g. `javax.mail`) use to implement
 * STARTTLS on top of a socket that's already mid-conversation, and it's a
 * small fraction of the code a correct non-blocking [javax.net.ssl.SSLEngine]
 * wrapper (manual handshake loop, wrap/unwrap buffering, its
 * `HandshakeStatus` state machine) would need for behaviour the platform
 * already implements correctly. Unverified against real hardware — see
 * this pass's note in AMT_VPRO_ROADMAP.md — but the technique itself is
 * standard JDK/Android TLS-provider behaviour, not something specific to
 * AMT.
 */
internal class TlsLayeredTransport private constructor(
    private val sslSocket: SSLSocket,
) : AmtRedirectionTransport {

    override val inputStream: InputStream = sslSocket.inputStream
    override val outputStream: OutputStream = sslSocket.outputStream

    override var soTimeout: Int
        get() = sslSocket.soTimeout
        set(value) { sslSocket.soTimeout = value }

    /** Closing [sslSocket] also closes the underlying [TransportBackedSocket]
     *  (created with `autoClose = true` in [wrap]), which in turn closes the
     *  wrapped [AmtRedirectionTransport] — no separate inner `close()` call
     *  needed here. */
    override fun close() {
        runCatching { sslSocket.close() }
    }

    companion object {
        /**
         * Performs the inner TLS handshake over [inner]'s raw byte pipe and
         * returns a new [AmtRedirectionTransport] that speaks decrypted
         * bytes on both ends — analogous to how [CiraRelayTransport.open]
         * itself blocks until its own (outer, relay-level) handshake and
         * channel-open-ack complete before returning. Callers should invoke
         * this from a background/IO dispatcher, same as
         * [CiraRelayTransport.open] — [SSLSocket.startHandshake] blocks.
         *
         * @param identity the [TofuTrustManager] pinning key. Callers use
         *   `"<ciraDeviceId>:<targetPort>"`, the CIRA counterpart to
         *   [AmtClient]'s own `"$host:$port"` convention, since a
         *   CIRA-addressed device has no directly-dialable host/port of its
         *   own for this pin to key off instead.
         * @param acceptSelfSignedCertificate mirrors [AmtClient]'s own
         *   parameter of the same name: true (the common case — AMT's
         *   local TLS cert is self-signed unless the device was
         *   provisioned into ACM against a real CA) uses [TofuTrustManager]
         *   exactly like [AmtClient]'s direct-connect TLS path; false uses
         *   the platform's default trust store (real CA-signed certs
         *   only), matching [AmtClient.httpClient]'s identical branch.
         * @param appContext required (non-null) whenever
         *   [acceptSelfSignedCertificate] is true — same fail-closed
         *   contract [AmtClient]/[CiraRelayTransport]'s own TOFU paths
         *   already have, for the same reason (a Context is needed to
         *   store the pinned fingerprint).
         */
        fun wrap(
            inner: AmtRedirectionTransport,
            identity: String,
            acceptSelfSignedCertificate: Boolean,
            appContext: Context?,
            handshakeTimeoutMs: Int = 8000,
        ): TlsLayeredTransport {
            require(!acceptSelfSignedCertificate || appContext != null) {
                "TLS over CIRA is on for '$identity' but no appContext was supplied to " +
                    "TlsLayeredTransport.wrap — TOFU certificate pinning requires a Context to " +
                    "store the pinned fingerprint. Refusing to connect with a trust-all fallback."
            }

            val sslContext = SSLContext.getInstance("TLS")
            if (acceptSelfSignedCertificate) {
                // See this class's top doc comment — same TOFU pattern as
                // AmtClient's own direct-connect TLS path and
                // CiraRelayTransport's `wss://` mode.
                val trustManager: X509TrustManager = TofuTrustManager(appContext!!, identity)
                sslContext.init(null, arrayOf(trustManager), SecureRandom())
            } else {
                // Platform default trust store — real CA-signed certs only,
                // matching AmtClient.httpClient's identical
                // (useTls && !acceptSelfSignedCertificate) branch.
                sslContext.init(null, null, SecureRandom())
            }

            val plainSocket = TransportBackedSocket(inner)
            plainSocket.soTimeout = handshakeTimeoutMs
            // host/port passed here are only used by the TLS provider for
            // session-cache keying and (when hostname verification is left
            // on) SNI/CN matching — never dialed. Hostname verification is
            // explicitly disabled below in the TOFU case since the pinned
            // fingerprint is already a strictly stronger identity check;
            // in the non-self-signed case there genuinely is no meaningful
            // DNS name for a CIRA device id, so a real CA-signed AMT cert
            // would need to have been issued for something matchable — a
            // pre-existing constraint of provisioning AMT into ACM, not
            // something this class introduces.
            val sslSocket = sslContext.socketFactory.createSocket(
                plainSocket,
                identity.substringBeforeLast(':'),
                0,
                true,
            ) as SSLSocket
            sslSocket.useClientMode = true
            if (acceptSelfSignedCertificate) {
                val params = sslSocket.sslParameters
                params.endpointIdentificationAlgorithm = null
                sslSocket.sslParameters = params
            }

            try {
                sslSocket.startHandshake()
            } catch (e: Exception) {
                runCatching { sslSocket.close() }
                throw IOException(
                    "TLS handshake over the CIRA channel to '$identity' failed: ${e.message}. This " +
                        "usually means AMT's local WS-Man/redirection service on this port isn't " +
                        "actually running in TLS mode, or the relay forwarded the wrong port.",
                    e,
                )
            }
            // Handshake-only timeout above; real per-read timeouts are set
            // via AmtRedirectionTransport.soTimeout afterward by the same
            // callers that already do this for DirectSocketTransport.
            sslSocket.soTimeout = 0

            return TlsLayeredTransport(sslSocket)
        }
    }
}

/**
 * A [Socket] facade over an [AmtRedirectionTransport]'s already-open byte
 * pipe, whose only purpose is satisfying
 * [javax.net.ssl.SSLSocketFactory.createSocket]'s signature — see
 * [TlsLayeredTransport]'s doc comment for why this is preferable to a
 * hand-rolled [javax.net.ssl.SSLEngine] loop. Never opens a real network
 * connection: `connect()`-family methods are never called on it (the
 * wrapped [inner] transport is already open by the time this wraps it),
 * and every state query the JDK's TLS provider might make is overridden to
 * reflect that "already connected, closed only via [close]" reality
 * instead of a fresh unconnected [Socket]'s real defaults.
 */
private class TransportBackedSocket(private val inner: AmtRedirectionTransport) : Socket() {
    @Volatile private var closed = false

    override fun getInputStream(): InputStream = inner.inputStream
    override fun getOutputStream(): OutputStream = inner.outputStream
    override fun isConnected(): Boolean = true
    override fun isBound(): Boolean = true
    override fun isClosed(): Boolean = closed
    override fun isInputShutdown(): Boolean = closed
    override fun isOutputShutdown(): Boolean = closed

    override fun setSoTimeout(timeout: Int) {
        inner.soTimeout = timeout
    }

    override fun getSoTimeout(): Int = inner.soTimeout

    override fun close() {
        closed = true
        inner.close()
    }
}
