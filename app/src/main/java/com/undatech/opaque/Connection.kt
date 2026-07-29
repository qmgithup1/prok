package com.undatech.opaque

/**
 * Plain data holder describing a VNC (RFB) connection target, consumed by
 * [RfbConnectable] (a hand-written, pure-Kotlin RFB client — see that class
 * for protocol details). No external VNC library or native dependency is
 * required.
 */
class Connection {
    var address: String = ""
    var port: Int = 5900
    var password: String = ""
    /** نوع الإدخال — يتطابق مع ثوابت RemotePointer.INPUT_MODE_* */
    var inputMode: String = ""
    var userName: String = ""
    var rdpDomain: String = ""

    // ── ULTRAVNC-REPEATER FEATURE ───────────────────────────────────────
    // [useRepeater] means this connection goes through an UltraVNC Repeater
    // rather than straight to the VNC server; which of the repeater's two
    // modes is used is [repeaterMode] — see RepeaterMode's doc.
    //
    // Mode II (ID-based): [address]/[port] above point at the *repeater*,
    // not the real VNC server. Immediately after the TCP handshake —
    // before any RFB version negotiation — the client sends a fixed
    // 250-byte "ID:<repeaterId>" frame; the repeater uses it to splice
    // this socket onto whichever server-side connection registered the same
    // ID (the server reaches the repeater with something like
    // `winvnc -connect repeaterHost:5500 -id:<repeaterId>`). See
    // RfbConnectable.sendRepeaterIdFrame for the exact wire format.
    //
    // Mode I (port-mapped): the repeater's own config maps [address]/[port]
    // straight through to one fixed target server — indistinguishable from
    // a direct connection at the protocol level, so RfbConnectable sends no
    // ID frame at all when repeaterMode == MODE_I. [repeaterId] is unused.
    var useRepeater: Boolean = false
    var repeaterId: String = ""

    /** Which of UltraVNC repeater's two connection modes [useRepeater] means. */
    enum class RepeaterMode { MODE_I, MODE_II }
    var repeaterMode: RepeaterMode = RepeaterMode.MODE_II

    // ── LISTEN-MODE FEATURE (reverse VNC) ───────────────────────────────
    // Standard RFB "listening viewer" mode (RFB Protocol §7.1, also called
    // Listen mode / reverse connection in RealVNC, UltraVNC and TightVNC):
    // instead of this app dialing out to [address]/[port], it opens a
    // ServerSocket on [listenPort] and waits — the remote VNC *server*
    // is the one that initiates the TCP connection (e.g. `x11vnc -connect
    // thisDevice:5500`, or a server-side "Send RFB session" action). Useful
    // when the server sits behind NAT/firewall and cannot be dialed
    // directly, or for unattended-support workflows where a technician's
    // viewer sits waiting and the end-user's machine calls out to it.
    // [address] is ignored in this mode — RfbConnectable never dials
    // anywhere; it only accepts. Mutually exclusive with [useRepeater]
    // (an incoming connection is never routed through a repeater).
    var useListenMode: Boolean = false
    var listenPort: Int = 5500
}

