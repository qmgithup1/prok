package com.systemsgo.hex.guacamole.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/** Mirrors [com.systemsgo.hex.remote.RemoteSessionState]'s shape but kept local until Part 2/N wires a real RemoteSessionClient. */
enum class GuacamoleTunnelState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * GUACAMOLE-PROTOCOL FEATURE (Part 1/N).
 *
 * Everything this needs to open a tunnel to a *already-authenticated,
 * already-resolved* Guacamole connection. [authToken]/[dataSource]/
 * [connectionIdentifier] come from [com.systemsgo.hex.guacamole.GuacamoleRepository]
 * (the REST login + connection-listing calls) — this class is deliberately
 * unaware of how those were obtained.
 *
 * [width]/[height]/[dpi] seed the initial display size guacd renders at;
 * resolution *changes* mid-session (the reg.txt spec's "Resolution change")
 * are a `size` client instruction sent over the open tunnel, which belongs
 * to the Part 2/N input layer, not connection setup.
 */
data class GuacamoleTunnelConfig(
    val tunnelWebSocketUrl: String, // e.g. "wss://guac.example.com/guacamole/websocket-tunnel"
    val authToken: String,
    val dataSource: String,
    val connectionIdentifier: String,
    /** "c" for a single connection, "g" for a connection group — see GUAC_TYPE in guacamole-client's TunnelRequestUtils. */
    val identifierType: String = "c",
    val width: Int,
    val height: Int,
    val dpi: Int = 96,
    /** Audio mimetypes to advertise support for, e.g. "audio/L16;rate=44100,channels=2". Empty = no audio channel requested. */
    val audioMimetypes: List<String> = emptyList(),
    /** Image mimetypes the client can decode beyond the mandatory image/png — e.g. "image/jpeg", "image/webp". */
    val imageMimetypes: List<String> = emptyList(),
    val timezone: String? = null,
    /**
     * Fallback-only trust-all escape hatch — see
     * [GuacamoleTunnelClient]'s `certificateVerifier` constructor param
     * doc for why the real decision now happens there
     * (reg.txt's "Trust on first use (optional)" / "Certificate pinning
     * (optional)", added Part 6/N via [GuacamoleCertificateVerifier]) and
     * this flag only still matters when no verifier is supplied.
     */
    val acceptSelfSignedCertificate: Boolean = false,
)

/**
 * Owns one WebSocket connection to a Guacamole server's tunnel endpoint and
 * translates it into a coroutine-friendly [SharedFlow] of decoded
 * [GuacamoleInstruction]s plus a [send] for outbound ones. This is the
 * Guacamole analogue of guacamole-common-js's `Guacamole.Tunnel` +
 * `Guacamole.WebSocketTunnel` — it does *not* interpret drawing
 * instructions into pixels (that's Part 2/N's `GuacamoleDisplayRenderer`,
 * the actual rendering pipeline reg.txt's DISPLAY section calls for) or
 * turn touch/keyboard events into `mouse`/`key` instructions (Part 2/N's
 * input layer) — it is purely the transport + wire-format layer, mirroring
 * how [com.systemsgo.hex.telnet.protocol.TelnetClient] is transport-only
 * and leaves terminal interpretation to the UI layer.
 *
 * Query-string handshake: guacamole-client's websocket-tunnel endpoint
 * expects `token`, `GUAC_DATA_SOURCE`, `GUAC_ID`, `GUAC_TYPE`, and the
 * initial `GUAC_WIDTH`/`GUAC_HEIGHT`/`GUAC_DPI`/`GUAC_AUDIO`/`GUAC_IMAGE`/
 * `GUAC_TIMEZONE` parameters as query parameters on the WebSocket upgrade
 * request itself — the actual guacd `select`/`args`/`connect` handshake
 * happens entirely server-side inside the tunnel servlet before the socket
 * ever reaches this client, so nothing here needs to replay it. This is
 * why [GuacamoleTunnelConfig] takes an already-resolved [connectionIdentifier]
 * rather than a raw `guacProtocol` (vnc/rdp/ssh/...) — that choice was made
 * server-side when the connection was created in Guacamole's admin UI (or
 * via its REST API), not by this app.
 */
class GuacamoleTunnelClient(
    private val config: GuacamoleTunnelConfig,
    private val callTimeoutSeconds: Long = 15,
    // GUACAMOLE-PROTOCOL FEATURE (Part 6/N): when provided, TLS trust for
    // this connection goes through the real interactive TOFU flow instead
    // of config.acceptSelfSignedCertificate's blind trust-all — see
    // GuacamoleCertificateVerifier's class doc for the full reasoning and
    // why this is a constructor param here rather than folded into
    // GuacamoleTunnelConfig (a verifier is a stateful, UI-observable object
    // scoped to one live session, not a plain config value).
    private val certificateVerifier: GuacamoleCertificateVerifier? = null,
) {
    private val decoder = GuacamoleInstructionDecoder()
    private var webSocket: WebSocket? = null

    private val _state = MutableStateFlow(GuacamoleTunnelState.DISCONNECTED)
    val state: StateFlow<GuacamoleTunnelState> = _state.asStateFlow()

    private val _instructions = MutableSharedFlow<GuacamoleInstruction>(extraBufferCapacity = 256)
    /** Every instruction guacd sends, in order, relayed unmodified — Part 2/N's renderer/clipboard/audio consumers subscribe here. */
    val instructions: SharedFlow<GuacamoleInstruction> = _instructions

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val client: OkHttpClient by lazy { buildHttpClient() }

    /** Opens the tunnel. Safe to call once per instance — build a new [GuacamoleTunnelClient] to reconnect. */
    fun connect() {
        if (_state.value != GuacamoleTunnelState.DISCONNECTED) return
        _state.value = GuacamoleTunnelState.CONNECTING

        val url = buildTunnelUrl()
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = GuacamoleTunnelState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncoming(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = GuacamoleTunnelState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _lastError.value = t.message ?: t.javaClass.simpleName
                _state.value = GuacamoleTunnelState.ERROR
            }
        })
    }

    /** Sends one instruction to guacd (client input, `sync` acks, `size` on rotation, clipboard, ...). No-op if not connected. */
    fun send(instruction: GuacamoleInstruction): Boolean {
        val ws = webSocket ?: return false
        return ws.send(instruction.encode())
    }

    /** Cleanly ends the session — sends the `disconnect` instruction before closing the socket, per protocol convention. */
    fun disconnect() {
        webSocket?.let {
            send(GuacamoleInstruction.of("disconnect"))
            it.close(1000, "client disconnect")
        }
        webSocket = null
        _state.value = GuacamoleTunnelState.DISCONNECTED
    }

    private fun handleIncoming(text: String) {
        val decoded = try {
            decoder.feed(text)
        } catch (e: GuacamoleProtocolException) {
            _lastError.value = "Protocol error: ${e.message}"
            _state.value = GuacamoleTunnelState.ERROR
            disconnect()
            return
        }
        for (instruction in decoded) {
            when (instruction.opcode) {
                // Flow-control ack: guacd emits `sync <server-timestamp>` after each
                // rendered frame; echoing it back is how the server measures round-trip
                // latency and paces itself. Harmless — and required for a well-behaved
                // client — even before Part 2/N's renderer exists to actually draw the
                // frame this timestamp corresponds to.
                "sync" -> instruction.args.firstOrNull()?.let { ts -> send(GuacamoleInstruction.of("sync", ts)) }
                "error" -> _lastError.value = instruction.args.getOrNull(0) ?: "Unknown server error"
                "disconnect" -> disconnect()
            }
            _instructions.tryEmit(instruction)
        }
    }

    private fun buildTunnelUrl(): String {
        val builder = config.tunnelWebSocketUrl.toHttpUrl().newBuilder()
            .addQueryParameter("token", config.authToken)
            .addQueryParameter("GUAC_DATA_SOURCE", config.dataSource)
            .addQueryParameter("GUAC_ID", config.connectionIdentifier)
            .addQueryParameter("GUAC_TYPE", config.identifierType)
            .addQueryParameter("GUAC_WIDTH", config.width.toString())
            .addQueryParameter("GUAC_HEIGHT", config.height.toString())
            .addQueryParameter("GUAC_DPI", config.dpi.toString())
        config.imageMimetypes.forEach { builder.addQueryParameter("GUAC_IMAGE", it) }
        config.audioMimetypes.forEach { builder.addQueryParameter("GUAC_AUDIO", it) }
        config.timezone?.let { builder.addQueryParameter("GUAC_TIMEZONE", it) }
        return builder.build().toString()
    }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .callTimeout(0, TimeUnit.MILLISECONDS) // WebSockets are long-lived; no blanket call timeout
            .connectTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)

        // ACCEPT-SELF-SIGNED FEATURE (Part 1/N) / TOFU (Part 6/N): when a
        // GuacamoleCertificateVerifier is supplied, use the real interactive
        // trust-on-first-use flow (see its class doc); config.acceptSelfSignedCertificate's
        // blind trust-all is now only the fallback for contexts with no
        // verifier — e.g. a standalone GuacamoleTunnelClient built without a
        // hosting GuacamoleSessionClient/UI to show a CertificateChallenge to.
        val verifier = certificateVerifier
        if (verifier != null) {
            val url = config.tunnelWebSocketUrl.toHttpUrl()
            val trustManager = verifier.buildTrustManager(url.host, url.port)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        } else if (config.acceptSelfSignedCertificate) {
            // SECURITY FIX (TLS-TOFU-NO-FALLBACK): a missing certificateVerifier
            // used to fall back to a trust-all X509TrustManager (every
            // certificate accepted, no pinning, no MITM detection) — exactly
            // the vulnerability the TOFU verifier exists to close. Fail
            // closed instead of silently downgrading to an insecure
            // connection.
            throw IllegalStateException(
                "acceptSelfSignedCertificate is on for '${config.tunnelWebSocketUrl}' but no " +
                    "certificateVerifier was supplied to GuacamoleTunnelClient — TOFU certificate " +
                    "pinning requires a verifier to check/store the pinned fingerprint. Refusing " +
                    "to connect with a trust-all fallback. Pass a GuacamoleCertificateVerifier.",
            )
        }

        return builder.build()
    }
}
