package com.systemsgo.hex.amt.protocol

import android.content.Context
import com.systemsgo.hex.security.TofuTrustManager
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * AMT-VPRO FEATURE phase 6 (CIRA), path (b) from AMT_VPRO_ROADMAP.md: the
 * actual [AmtRedirectionTransport] implementation that talks to this
 * project's own companion relay/MPS component over a WebSocket, instead of
 * [DirectSocketTransport]'s plain socket straight to the AMT device. See
 * that interface's doc comment for why this drop-in shape means
 * [AmtSolSession]/[AmtKvmSession]/[AmtIderSession] need zero changes to
 * their own SOL/RFB/IDE-R logic to use this.
 *
 * ## The relay's wire protocol (this app's own — first defined here)
 * The relay component (a separate, non-Android piece of this project, not
 * built in this pass — see AMT_VPRO_ROADMAP.md) terminates *real* APF from
 * devices and exposes this deliberately simple framing to console clients
 * like this app:
 *
 * 1. **Connect**: a standard RFC 6455 WebSocket handshake to
 *    `ws://{ciraRelayHost}:{ciraRelayPort}/cira/v1/channel`, or
 *    `wss://` on the same host/port/path when
 *    [RdpProfile.ciraRelayUseTls] is set (Phase 6 Part 3b — see
 *    [open]'s `useTls` parameter). If
 *    [RdpProfile.ciraRelayUsername]/[RdpProfile.ciraRelayPassword] are
 *    non-empty, they're sent as an `Authorization: Basic ...` header on the
 *    handshake request itself (this app's own credential to the *relay*,
 *    separate from the AMT device's WS-Man/redirection-port credentials —
 *    see that field's doc comment in `RdpProfile.kt`). **Plain `ws://`
 *    (the default) sends that header, and every byte of the tunneled AMT
 *    session, across the network in the clear — acceptable for a relay
 *    reachable only over a VPN/private network, not for one exposed on the
 *    open internet; enabling [RdpProfile.ciraRelayUseTls] is how a
 *    real/exposed deployment closes that gap.**
 *
 *    **`wss://` trust mode:** [RdpProfile] has no per-CIRA-profile
 *    pinning/custom-CA fields of its own (unlike
 *    [com.systemsgo.hex.rdp.transport.RdpWebSocketTransport]'s
 *    `TlsOptions`, which exposes four distinct trust modes because RDP
 *    profiles have UI for all four) — `ciraRelayUseTls` is a single
 *    on/off switch. Rather than add that same four-way matrix here for a
 *    field that doesn't expose it, `wss://` mode always uses
 *    [TofuTrustManager] (silent Trust-On-First-Use, keyed by
 *    `"$relayHost:$relayPort"`, hostname verification skipped in favor of
 *    the exact-fingerprint pin — same reasoning as [AmtClient]'s own
 *    `acceptSelfSignedCertificate` path, which this mirrors): the first
 *    certificate the relay presents is pinned automatically (no interactive
 *    prompt — this call is made from a background coroutine with no
 *    session-scoped UI to show one through, same constraint `AmtClient`
 *    has), and every later connection to that same relay must present the
 *    identical certificate or the handshake hard-aborts as a likely MITM.
 *    This is deliberately *not* the system CA store: self-hosted relays
 *    (the expected deployment — see `AMT_VPRO_ROADMAP.md`'s Phase 6
 *    section) very commonly run a self-signed certificate, and TOFU gives
 *    real MITM detection without requiring the user to provision a CA-
 *    signed cert or manually import one into the device's trust store.
 *    [open] requires a non-null `appContext` whenever `useTls = true`
 *    (fails closed with [IllegalArgumentException] rather than silently
 *    falling back to trust-all if one isn't supplied — same fail-closed
 *    contract [AmtClient]/[RdpWebSocketTransport]'s own TOFU paths have).
 *
 * 2. **Channel open**: once the socket is open, this app sends exactly one
 *    JSON *text* frame:
 *    ```json
 *    {"type": "channel-open", "version": 1, "deviceId": "<ciraDeviceId>", "targetPort": 16994}
 *    ```
 *    `targetPort` is whichever AMT port this transport is for (16994/16995
 *    for SOL/IDE-R, or KVM's equivalent — the same
 *    `redirectionPort` [AmtClient.openSolSession] etc. already compute for
 *    the direct-connect case). The relay is expected to already have (or
 *    open, if the device isn't already dialed in) an APF connection from
 *    that `deviceId` and issue its own `APF_CHANNEL_OPEN`
 *    (`chan_type="forwarded-tcpip"`) for that port against it — see
 *    AMT_VPRO_ROADMAP.md's Phase 6 section for the full APF flow this is
 *    standing in front of.
 *
 * 3. **Channel open acknowledgement**: the relay replies with one JSON
 *    text frame before any data flows:
 *    ```json
 *    {"type": "channel-open-ack", "status": "ok"}
 *    {"type": "channel-open-ack", "status": "error", "message": "..."}
 *    ```
 *    An `"error"` status (or a `{"type": "error", "message": "..."}` frame
 *    sent instead) fails [open] with [AmtException] and never proceeds to
 *    data flow.
 *
 * 4. **Data**: after a successful ack, every WebSocket *binary* frame in
 *    either direction is raw payload bytes — exactly the same bytes
 *    [DirectSocketTransport] would have carried, i.e. already-unwrapped
 *    `APF_CHANNEL_DATA` payload on the relay's side, and (from
 *    [AmtSolSession]/[AmtKvmSession]/[AmtIderSession]'s point of view) the
 *    literal `StartRedirectionSession`/RFB/IDE-R-envelope bytes those
 *    classes already know how to speak. No additional length prefix or
 *    framing is added — WebSocket already frames messages, and frame
 *    boundaries carry no meaning to either side beyond "some bytes
 *    arrived"; a single logical write on one side may arrive as one or
 *    more frames on the other, so readers must treat the stream as a plain
 *    byte pipe, never assume a frame == one protocol message (exactly the
 *    same assumption [AmtSolSession.receive] etc. already make about
 *    [DirectSocketTransport]'s plain socket).
 *
 * 5. **Keepalive**: plain WebSocket ping/pong at 20-second intervals (via
 *    OkHttp's `pingInterval`), not an application-level message — nothing
 *    the relay needs to parse as JSON. The relay is expected to translate
 *    an idle channel into its own `APF_KEEPALIVE_REQUEST`/`_REPLY` traffic
 *    with the device on its side of the tunnel if needed; that's entirely
 *    the relay's concern, invisible on this wire.
 *
 * 6. **Close**: either side may close the WebSocket normally (code 1000).
 *    The relay may also send `{"type": "channel-close"}` before closing to
 *    distinguish "the device closed its `APF_CHANNEL_CLOSE`" from a
 *    transport-level failure — this app treats both the same way (clean
 *    EOF on [inputStream]), since none of [AmtSolSession]/[AmtKvmSession]/
 *    [AmtIderSession] currently distinguish the two either.
 *
 * This framing is intentionally minimal — a JSON control preamble plus a
 * raw binary pipe — specifically so the relay component (built in a
 * follow-up pass) has the smallest possible contract to implement and
 * this app has the smallest possible contract to depend on, per
 * AMT_VPRO_ROADMAP.md's stated reasoning for picking path (b) over
 * speaking MeshCentral's own undocumented browser-relay protocol.
 *
 * ## What this class does NOT cover
 * [AmtClient]'s own WS-Man HTTP calls (port 16992/16993 — connectivity
 * check, power control, the `enableSolListener`/`enableKvmRedirection`/
 * `enableIderListener` best-effort calls [openSolSession] etc. make before
 * handing off to a session) are a separate `OkHttpClient` talking plain
 * HTTP directly to [AmtClient]'s own `host`/`port`, not anything routed
 * through [AmtRedirectionTransport] at all. Under real CIRA there is no
 * directly-reachable `host`/`port` for those calls to reach, so they will
 * simply fail (harmlessly, since they're already wrapped in `runCatching`
 * as best-effort) rather than being tunneled through the relay too — see
 * `BmcManagementActivity.connect()`'s CIRA branch and item 4 of this
 * phase's brief for why that gap is being flagged rather than silently
 * closed in this pass: forwarding WS-Man itself would need `AmtClient`'s
 * HTTP layer to gain a transport seam of its own (a bigger change than
 * this file), which is follow-up work, not done here. **Practical effect:**
 * this pass's CIRA support only carries SOL/KVM/IDE-R traffic; general
 * WS-Man-driven device management (power control, boot options, etc.)
 * over CIRA is not implemented and a CIRA-enabled profile's `connect()`
 * only opens a redirection session, never an `AmtClient` management
 * screen.
 */
internal class CiraRelayTransport private constructor(
    private val webSocket: WebSocket,
    private val client: OkHttpClient,
    private val relayInput: RelayInputStream,
) : AmtRedirectionTransport {

    private val closed = AtomicBoolean(false)

    override val inputStream: InputStream = relayInput
    override val outputStream: OutputStream = RelayOutputStream()

    /** Read timeout for [inputStream] — unlike [DirectSocketTransport], this
     *  isn't a real [java.net.Socket] so there's nothing to delegate to;
     *  mirrored into [RelayInputStream.pollTimeoutMs] on every set, which
     *  [RelayInputStream.read] applies via [LinkedBlockingQueue.poll]'s own
     *  timeout — same observable contract as `Socket.soTimeout`. */
    override var soTimeout: Int = 8000
        set(value) {
            field = value
            relayInput.pollTimeoutMs = value
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { webSocket.close(1000, "session closed") }
        relayInput.signalEof()
        // Only this transport's own client — not shared with AmtClient's
        // WS-Man OkHttpClient or any other session's relay connection (see
        // Companion.open, a fresh OkHttpClient per call) — so shutting it
        // down here can't affect anything else.
        client.dispatcher.executorService.shutdown()
    }

    private inner class RelayOutputStream : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get()) throw IOException("CIRA relay transport is closed")
            if (len == 0) return
            val frame = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            // WebSocket.send() queues on OkHttp's own writer thread and
            // returns immediately; it only returns false once the socket is
            // already closed/closing/failed (OkHttp buffers everything else
            // internally), so false here reliably means "this write was
            // lost" rather than ordinary backpressure.
            if (!webSocket.send(frame.toByteString())) {
                throw IOException("CIRA relay: failed to send data frame (connection closed)")
            }
        }
    }

    /** Bridges [WebSocketListener.onMessage] binary frames — which arrive
     *  on OkHttp's own callback thread — to a blocking [InputStream] the
     *  way [AmtSolSession]/[AmtKvmSession]/[AmtIderSession]'s `input.read()`
     *  calls expect, using a bounded per-frame queue plus a leftover-bytes
     *  cursor so a caller reading fewer bytes than one frame contained
     *  doesn't lose the remainder (a plain [Socket]'s stream never loses
     *  partial reads either). */
    private class RelayInputStream : InputStream() {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var current: ByteArray? = null
        private var pos = 0

        @Volatile private var terminalError: IOException? = null

        /** Timeout for the next [queue] poll — set by the enclosing
         *  transport's [soTimeout] on every read, mirroring
         *  [DirectSocketTransport]'s `Socket.soTimeout` semantics (settable
         *  more than once over the transport's lifetime). */
        @Volatile var pollTimeoutMs: Int = 8000

        fun offer(data: ByteArray) {
            if (data.isNotEmpty()) queue.put(data)
        }

        fun signalEof() = queue.put(EOF_MARKER)

        fun signalError(e: IOException) {
            terminalError = e
            queue.put(EOF_MARKER)
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val cur = current
            if (cur == null || pos >= cur.size) {
                val chunk = queue.poll(pollTimeoutMs.toLong().coerceAtLeast(1), TimeUnit.MILLISECONDS)
                    ?: throw SocketTimeoutException("CIRA relay: read timed out")
                if (chunk === EOF_MARKER) {
                    terminalError?.let { throw it }
                    return -1
                }
                current = chunk
                pos = 0
                return read(b, off, len) // re-enter now that `current` is populated
            }
            val n = minOf(cur.size - pos, len)
            System.arraycopy(cur, pos, b, off, n)
            pos += n
            return n
        }

        companion object {
            /** Sentinel distinguished by reference identity (never produced
             *  by [offer], which drops empty arrays) rather than by
             *  contents, so a genuine empty data frame can never be
             *  mistaken for EOF. */
            val EOF_MARKER = ByteArray(0)
        }
    }

    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val WS_PATH = "/cira/v1/channel"

        /**
         * Opens a CIRA relay channel for one AMT redirection port and blocks
         * (caller is expected to already be on [kotlinx.coroutines.Dispatchers.IO],
         * same convention as [AmtSolSession.open]/[AmtKvmSession.open]/
         * [AmtIderSession.open]'s own [DirectSocketTransport] connect) until
         * the relay has confirmed the channel — see this class's top doc
         * comment for the full handshake. Throws [AmtException] on any
         * connect/auth/channel-open failure.
         *
         * @param useTls [RdpProfile.ciraRelayUseTls] — connects with
         *   `wss://` instead of `ws://` when true. See this class's top doc
         *   comment ("`wss://` trust mode") for what backs certificate
         *   verification in that case.
         * @param appContext required (non-null) whenever [useTls] is true —
         *   backs the [TofuTrustManager] that pins the relay's certificate.
         *   Throws [IllegalArgumentException] immediately if [useTls] is
         *   true and this is null, rather than falling back to an insecure
         *   trust-all default.
         */
        fun open(
            relayHost: String,
            relayPort: Int,
            relayUsername: String,
            relayPassword: String,
            deviceId: String,
            targetPort: Int,
            connectTimeoutMs: Int = 8000,
            useTls: Boolean = false,
            appContext: Context? = null,
        ): CiraRelayTransport {
            require(relayHost.isNotBlank()) { "CIRA relay host is blank" }
            require(deviceId.isNotBlank()) { "CIRA device id is blank" }
            require(!useTls || appContext != null) {
                "ciraRelayUseTls is on for '$relayHost:$relayPort' but no appContext was supplied " +
                    "to CiraRelayTransport.open — TOFU certificate pinning requires a Context to " +
                    "store the pinned fingerprint. Refusing to connect with a trust-all fallback."
            }

            val clientBuilder = OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived stream — per-read timeouts are RelayInputStream's own poll timeout, not OkHttp's
                .pingInterval(20, TimeUnit.SECONDS) // see top doc comment's "Keepalive" section

            if (useTls) {
                // See this class's top doc comment's "wss:// trust mode"
                // section for why TOFU (not the system CA store) is the
                // only mode CiraRelayTransport offers.
                val identity = "$relayHost:$relayPort"
                val trustManager: X509TrustManager = TofuTrustManager(appContext!!, identity)
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(trustManager), SecureRandom())
                }
                clientBuilder.sslSocketFactory(sslContext.socketFactory, trustManager)
                // Safe to skip: the trust manager above already pins the
                // exact certificate fingerprint, a strictly stronger
                // identity guarantee than a CN/SAN hostname match.
                clientBuilder.hostnameVerifier(HostnameVerifier { _, _ -> true })
            }
            val client = clientBuilder.build()

            val scheme = if (useTls) "wss" else "ws"
            val requestBuilder = Request.Builder().url("$scheme://$relayHost:$relayPort$WS_PATH")
            if (relayUsername.isNotEmpty() || relayPassword.isNotEmpty()) {
                requestBuilder.addHeader("Authorization", Credentials.basic(relayUsername, relayPassword))
            }

            val relayInput = RelayInputStream()
            val openLatch = CountDownLatch(1)
            val ackLatch = CountDownLatch(1)
            val openError = AtomicReference<IOException?>(null)
            val ackError = AtomicReference<IOException?>(null)

            val webSocket = client.newWebSocket(
                requestBuilder.build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val channelOpen = JSONObject().apply {
                            put("type", "channel-open")
                            put("version", PROTOCOL_VERSION)
                            put("deviceId", deviceId)
                            put("targetPort", targetPort)
                        }
                        webSocket.send(channelOpen.toString())
                        openLatch.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (ackLatch.count > 0L) {
                            val json = runCatching { JSONObject(text) }.getOrNull()
                            when (json?.optString("type")) {
                                "channel-open-ack" -> {
                                    if (json.optString("status") == "ok") {
                                        ackLatch.countDown()
                                    } else {
                                        ackError.set(
                                            IOException(
                                                "CIRA relay refused channel to device '$deviceId' port $targetPort: " +
                                                    json.optString("message", "no reason given"),
                                            ),
                                        )
                                        ackLatch.countDown()
                                    }
                                }
                                "error" -> {
                                    ackError.set(IOException("CIRA relay error: ${json.optString("message", "unknown")}"))
                                    ackLatch.countDown()
                                }
                                else -> {
                                    ackError.set(IOException("CIRA relay: unexpected message while awaiting channel-open-ack: $text"))
                                    ackLatch.countDown()
                                }
                            }
                            return
                        }
                        // Post-ack: the only text frame this protocol still
                        // defines is a device-side close notification (see
                        // top doc comment's "Close" section); anything else
                        // is out of protocol and ignored rather than fed
                        // into the byte stream, which would corrupt it.
                        val json = runCatching { JSONObject(text) }.getOrNull()
                        when (json?.optString("type")) {
                            "channel-close" -> relayInput.signalEof()
                            "error" -> relayInput.signalError(IOException("CIRA relay error: ${json.optString("message", "unknown")}"))
                            else -> Unit // unrecognized control frame — ignored, not fed into the data stream
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        relayInput.offer(bytes.toByteArray())
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        relayInput.signalEof()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val e = IOException("CIRA relay connection failed: ${t.message}", t)
                        if (openLatch.count > 0) { openError.set(e); openLatch.countDown() }
                        if (ackLatch.count > 0) { ackError.set(e); ackLatch.countDown() }
                        relayInput.signalError(e)
                    }
                },
            )

            if (!openLatch.await(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                webSocket.cancel()
                throw AmtException("Timed out connecting to CIRA relay at $relayHost:$relayPort")
            }
            openError.get()?.let {
                throw AmtException(it.message ?: "CIRA relay connection failed", cause = it)
            }

            if (!ackLatch.await(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                webSocket.cancel()
                throw AmtException("Timed out waiting for CIRA relay to open a channel to device '$deviceId' port $targetPort")
            }
            ackError.get()?.let {
                webSocket.cancel()
                throw AmtException(it.message ?: "CIRA relay rejected the channel", cause = it)
            }

            return CiraRelayTransport(webSocket, client, relayInput)
        }
    }
}
