package com.systemsgo.hex.rtsp.protocol

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.systemsgo.hex.remote.RemoteFrameUpdate
import com.systemsgo.hex.remote.RemoteMouseButton
import com.systemsgo.hex.remote.RemoteSessionClient
import com.systemsgo.hex.remote.RemoteSessionState
import com.systemsgo.hex.remote.TerminalOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory

private const val TAG = "RtspClient"

/**
 * RTSP FEATURE: native RTSP 1.0 (RFC 2326) client — no ExoPlayer/libVLC
 * dependency, consistent with how every other protocol in this app
 * (RDP/VNC/SSH/Telnet/Mosh/SPICE) speaks its wire protocol directly rather
 * than delegating to a third-party player. Implements the control-plane
 * handshake (OPTIONS → DESCRIBE → SETUP → PLAY, with Basic/Digest auth per
 * [RtspAuth] and periodic GET_PARAMETER keep-alives) plus TCP-interleaved
 * RTP transport, H.264 depacketization ([RtpH264Depacketizer]), and
 * MediaCodec decoding — surfaced through the same [RemoteSessionClient]
 * contract [com.systemsgo.hex.vnc.protocol.VncClient]/
 * [com.systemsgo.hex.ssh.protocol.SshClient] implement, so
 * [com.systemsgo.hex.ui.screens.RdpSessionActivity]'s existing
 * frame-rendering pipeline drives an RTSP camera session with no UI-layer
 * changes: every decoded frame is emitted as a fullScreen [RemoteFrameUpdate]
 * exactly like an RDP/VNC framebuffer repaint.
 *
 * Scope for this first version: one H.264 video track, view-only (an RTSP
 * camera has no keyboard/mouse/clipboard concept, so every input method
 * below is a no-op — same pattern SSH already uses for mouse events).
 * Audio (`m=audio` SDP sections) is parsed but not decoded/played yet.
 * [RtspTransportMode.UDP] SETUPs the transport correctly but this version
 * only wires up the TCP_INTERLEAVED read loop end-to-end; UDP media
 * delivery is a follow-up.
 */
class RtspClient(
    private val credentials: RtspCredentials,
) : RemoteSessionClient {

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 2)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    override val terminalOutput: SharedFlow<TerminalOutput> = MutableSharedFlow<TerminalOutput>().asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    @Volatile override var latencyMs: Long = 0
        private set

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: DataOutputStream? = null
    private var rtspSessionId: String? = null
    private val cSeq = AtomicInteger(1)
    private var digestChallenge: RtspAuth.DigestChallenge? = null

    private var decoder: MediaCodec? = null
    private var videoTrack: SdpVideoTrack? = null
    private val depacketizer = RtpH264Depacketizer()
    private var frameWidth = 0
    private var frameHeight = 0

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var readJob: Job? = null
    private var keepAliveJob: Job? = null

    override suspend fun connect(): Boolean {
        _sessionState.value = RemoteSessionState.CONNECTING
        return try {
            openSocket()
            request("OPTIONS", credentials.rtspUrl)

            val describeResponse = request("DESCRIBE", credentials.rtspUrl, extraHeaders = mapOf("Accept" to "application/sdp"))
            val sdpBody = describeResponse.body ?: throw IOException("DESCRIBE returned no SDP body")
            val track = SdpParser.parseFirstH264VideoTrack(sdpBody)
                ?: throw IOException("No H.264 video track found in SDP")
            videoTrack = track

            val controlUri = SdpParser.resolveControlUri(credentials.rtspUrl, track.controlUri)
            val transportHeader = when (credentials.transport) {
                RtspTransportMode.TCP_INTERLEAVED -> "RTP/AVP/TCP;unicast;interleaved=0-1"
                RtspTransportMode.UDP -> "RTP/AVP;unicast;client_port=${UDP_RTP_PORT}-${UDP_RTP_PORT + 1}"
            }
            val setupResponse = request("SETUP", controlUri, extraHeaders = mapOf("Transport" to transportHeader))
            rtspSessionId = setupResponse.headers["session"]?.substringBefore(';')?.trim()
                ?: throw IOException("SETUP response had no Session header")

            request("PLAY", credentials.rtspUrl, extraHeaders = mapOf("Range" to "npts=0.000-"))

            startDecoder(track)
            _sessionState.value = RemoteSessionState.CONNECTED

            readJob = scope.launch { readInterleavedLoop() }
            keepAliveJob = scope.launch { keepAliveLoop() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "RTSP connect failed", e)
            _error.tryEmit(e.message ?: "RTSP connection failed")
            _sessionState.value = RemoteSessionState.ERROR
            teardownQuietly()
            false
        }
    }

    private fun openSocket() {
        val plain = Socket()
        plain.connect(InetSocketAddress(credentials.host, credentials.port), CONNECT_TIMEOUT_MS)
        socket = if (credentials.useTls) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(
                plain, credentials.host, credentials.port, true,
            )
        } else {
            plain
        }
        input = BufferedInputStream(socket!!.getInputStream())
        output = DataOutputStream(socket!!.getOutputStream())
    }

    // ── RTSP request/response ────────────────────────────────────────────

    private class RtspResponse(val statusCode: Int, val headers: Map<String, String>, val body: String?)

    private fun request(
        method: String,
        uri: String,
        extraHeaders: Map<String, String> = emptyMap(),
        isRetryWithAuth: Boolean = false,
    ): RtspResponse {
        val headers = LinkedHashMap<String, String>()
        headers["CSeq"] = cSeq.getAndIncrement().toString()
        rtspSessionId?.let { headers["Session"] = it }
        headers["User-Agent"] = "SystemsGo-Hex-RTSP/1.0"
        if (credentials.username.isNotEmpty()) {
            digestChallenge?.let { challenge ->
                headers["Authorization"] = RtspAuth.digestAuthorizationHeader(
                    credentials.username, credentials.password, method, uri, challenge,
                )
            }
        }
        headers.putAll(extraHeaders)

        val requestText = buildString {
            append("$method $uri RTSP/1.0\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        output!!.write(requestText.toByteArray(Charsets.US_ASCII))
        output!!.flush()

        val response = readResponse()

        // RFC 2617: first 401 reveals the challenge; retry the same request once, authenticated.
        if (response.statusCode == 401 && !isRetryWithAuth && credentials.username.isNotEmpty()) {
            val wwwAuth = response.headers["www-authenticate"] ?: ""
            digestChallenge = RtspAuth.parseChallenge(wwwAuth)
            if (digestChallenge != null || RtspAuth.isBasicChallenge(wwwAuth)) {
                if (digestChallenge == null) {
                    // Basic auth path: stash a synthetic header directly rather than through digestChallenge.
                    val basicHeaders = extraHeaders + ("Authorization" to RtspAuth.basicAuthorizationHeader(credentials.username, credentials.password))
                    return request(method, uri, basicHeaders, isRetryWithAuth = true)
                }
                return request(method, uri, extraHeaders, isRetryWithAuth = true)
            }
        }

        if (response.statusCode !in 200..299) {
            throw IOException("$method failed: RTSP/1.0 ${response.statusCode}")
        }
        return response
    }

    /** Reads one full RTSP response (status line + headers + optional Content-Length body). */
    private fun readResponse(): RtspResponse {
        val stream = input!!
        val statusLine = readLine(stream) ?: throw IOException("RTSP connection closed by peer")
        val statusCode = Regex("RTSP/1\\.0 (\\d+)").find(statusLine)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("Malformed RTSP status line: $statusLine")

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(stream) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buffer = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = stream.read(buffer, read, contentLength - read)
                if (n < 0) throw IOException("RTSP body truncated")
                read += n
            }
            String(buffer, Charsets.UTF_8)
        } else null

        return RtspResponse(statusCode, headers, body)
    }

    private fun readLine(stream: InputStream): String? {
        val line = StringBuilder()
        var previousWasCr = false
        while (true) {
            val b = stream.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) {
                if (previousWasCr && line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                return line.toString()
            }
            line.append(b.toChar())
            previousWasCr = b == '\r'.code
        }
    }

    // ── Media decode ──────────────────────────────────────────────────────

    private fun startDecoder(track: SdpVideoTrack) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        track.spropSps?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + it)) }
        track.spropPps?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + it)) }
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, 0)
        codec.start()
        decoder = codec
    }

    /** Feeds one Annex-B access unit to the decoder and drains any ready output frame(s). */
    private fun decodeAccessUnit(accessUnit: ByteArray) {
        val codec = decoder ?: return
        try {
            val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(accessUnit)
                codec.queueInputBuffer(inIndex, 0, accessUnit.size, System.nanoTime() / 1000, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            while (outIndex >= 0) {
                val outputFormat = codec.outputFormat
                frameWidth = outputFormat.getInteger(MediaFormat.KEY_WIDTH)
                frameHeight = outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                val outputBuffer = codec.getOutputBuffer(outIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    val yuv = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.get(yuv)
                    val argb = yuv420ToArgb(yuv, frameWidth, frameHeight)
                    _frameUpdates.tryEmit(
                        RemoteFrameUpdate(0, 0, frameWidth, frameHeight, argb, fullScreen = true),
                    )
                }
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Decode error", e)
        }
    }

    /** Planar/semi-planar YUV420 → ARGB_8888, BT.601 coefficients (standard for camera/H.264 sources). */
    private fun yuv420ToArgb(yuv: ByteArray, width: Int, height: Int): IntArray {
        val argb = IntArray(width * height)
        val frameSize = width * height
        if (yuv.size < frameSize + frameSize / 2) return argb
        for (row in 0 until height) {
            for (col in 0 until width) {
                val yIndex = row * width + col
                val uvRow = row / 2
                val uvCol = col / 2
                val uIndex = frameSize + uvRow * width + uvCol * 2
                val vIndex = uIndex + 1

                val y = (yuv[yIndex].toInt() and 0xFF) - 16
                val u = (yuv.getOrElse(uIndex) { 0 }.toInt() and 0xFF) - 128
                val v = (yuv.getOrElse(vIndex) { 0 }.toInt() and 0xFF) - 128

                var r = (1.164 * y + 1.596 * v).toInt()
                var g = (1.164 * y - 0.392 * u - 0.813 * v).toInt()
                var b = (1.164 * y + 2.017 * u).toInt()
                r = r.coerceIn(0, 255); g = g.coerceIn(0, 255); b = b.coerceIn(0, 255)

                argb[yIndex] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return argb
    }

    // ── Interleaved RTP read loop ─────────────────────────────────────────

    private suspend fun readInterleavedLoop() {
        val stream = input ?: return
        try {
            while (_sessionState.value == RemoteSessionState.CONNECTED) {
                val marker = stream.read()
                if (marker < 0) break
                if (marker != '$'.code) continue // resync: skip stray bytes between RTSP responses (e.g. late keep-alive reply)

                val channel = stream.read()
                val lenHigh = stream.read()
                val lenLow = stream.read()
                if (channel < 0 || lenHigh < 0 || lenLow < 0) break
                val length = (lenHigh shl 8) or lenLow

                val packet = ByteArray(length)
                var readBytes = 0
                while (readBytes < length) {
                    val n = stream.read(packet, readBytes, length - readBytes)
                    if (n < 0) throw IOException("Interleaved stream closed mid-packet")
                    readBytes += n
                }

                if (channel == 0) { // RTP (video) — channel 1 is RTCP, not needed for rendering
                    handleRtpPacket(packet)
                }
            }
        } catch (e: Exception) {
            if (_sessionState.value == RemoteSessionState.CONNECTED) {
                Log.w(TAG, "RTSP read loop ended", e)
                _error.tryEmit(e.message ?: "RTSP stream ended unexpectedly")
                _sessionState.value = RemoteSessionState.ERROR
            }
        }
    }

    private fun handleRtpPacket(packet: ByteArray) {
        if (packet.size < 12) return
        val marker = (packet[1].toInt() and 0x80) != 0
        // Skip the 12-byte fixed RTP header (CSRC list ignored — cameras don't use it).
        val payload = packet.copyOfRange(12, packet.size)
        val accessUnit = depacketizer.onRtpPayload(payload, marker) ?: return
        decodeAccessUnit(accessUnit)
    }

    private suspend fun keepAliveLoop() {
        while (_sessionState.value == RemoteSessionState.CONNECTED) {
            kotlinx.coroutines.delay(KEEP_ALIVE_INTERVAL_MS)
            try {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    request("GET_PARAMETER", credentials.rtspUrl)
                }
            } catch (e: Exception) {
                Log.w(TAG, "RTSP keep-alive failed", e)
            }
        }
    }

    // ── No-op input surface (view-only, matches SSH's "protocol has no such concept" pattern) ──

    override fun sendMouseMove(x: Int, y: Int) {}
    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) {}
    override fun sendMouseScroll(x: Int, y: Int, delta: Int) {}
    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {}
    override fun sendCtrlAltDel() {}
    override fun sendText(text: String) {}

    override fun disconnect() {
        _sessionState.value = RemoteSessionState.DISCONNECTED
        readJob?.cancel()
        keepAliveJob?.cancel()
        teardownQuietly()
    }

    private fun teardownQuietly() {
        try {
            if (rtspSessionId != null) {
                runCatching { request("TEARDOWN", credentials.rtspUrl) }
            }
        } catch (_: Exception) {
        }
        depacketizer.reset()
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        rtspSessionId = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val REQUEST_TIMEOUT_MS = 5_000L
        const val KEEP_ALIVE_INTERVAL_MS = 30_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val UDP_RTP_PORT = 45_000
    }
}
