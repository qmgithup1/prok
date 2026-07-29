package com.systemsgo.hex.rtsp.protocol

/**
 * RTSP FEATURE: how the negotiated RTP/RTCP media actually travels once
 * PLAY starts.
 *
 * [TCP_INTERLEAVED] multiplexes RTP/RTCP as binary '$'-framed chunks over
 * the *same* TCP connection the RTSP control messages already use (RFC 2326
 * §10.12). This is the default and, for a mobile client, the only mode that
 * "just works" through carrier-grade NAT, corporate Wi-Fi, and VPNs without
 * any router configuration — exactly the same reasoning that already made
 * [com.systemsgo.hex.data.model.SerialRedirectMode.RAW_TCP]/RFC_2217 the
 * defaults elsewhere in this app.
 *
 * [UDP] opens two ephemeral local UDP ports (RTP + RTCP) and asks the
 * server to send media there instead — lower latency when it works, but
 * silently breaks behind strict NAT/firewalls with no fallback, so it's
 * opt-in only. See [RtspClient.connect] for exactly how each mode maps onto
 * the SETUP request's Transport header.
 */
enum class RtspTransportMode(val label: String) {
    TCP_INTERLEAVED("TCP (interleaved)"),
    UDP("UDP"),
}

/**
 * Connection details for one RTSP session — one video track, optionally
 * behind Basic or Digest authentication (RFC 2617), exactly like an IP
 * camera or NVR channel.
 *
 * @param path the stream path portion of the `rtsp://host:port/path` URL
 *   (e.g. "cam/realmonitor?channel=1&subtype=0" for a typical Dahua/Hikvision
 *   main stream, or "stream1", or blank for servers that serve their one
 *   stream off the bare root). Never includes the leading slash; [RtspClient]
 *   adds it.
 * @param username/password RTSP-layer credentials (Basic or Digest per RFC
 *   2617) — a different credential space from any RDP/VNC/SSH password also
 *   sitting on the same [com.systemsgo.hex.data.model.RdpProfile], since a
 *   camera's RTSP realm has nothing to do with those protocols.
 */
class RtspCredentials(
    val host: String,
    val port: Int,
    val path: String,
    val username: String = "",
    val password: String = "",
    val transport: RtspTransportMode = RtspTransportMode.TCP_INTERLEAVED,
    // RTSP-TLS FEATURE: "rtsps://" — wraps the control-connection socket in
    // TLS before the RTSP request line goes out, same TOFU/CertificateChallenge
    // flow as every other TLS-capable protocol in this app. Media still rides
    // the same (now-TLS) TCP socket when transport == TCP_INTERLEAVED; UDP
    // media is never encrypted by RTSP itself regardless of this flag (SRTP
    // is a separate, unrelated negotiation this app does not implement).
    val useTls: Boolean = false,
) {
    val rtspUrl: String
        get() = buildString {
            append(if (useTls) "rtsps://" else "rtsp://")
            append(host)
            append(':')
            append(port)
            if (path.isNotEmpty()) {
                append('/')
                append(path)
            }
        }
}
