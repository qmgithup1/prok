package com.systemsgo.hex.remote

import com.systemsgo.hex.display.MonitorSelection
import com.systemsgo.hex.display.RemoteMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified state shared by every protocol implementation. RDP and VNC map
 * their richer internal states down into this; SSH (a text/terminal
 * protocol, not a framebuffer) also reports through here so the same
 * Activity/ViewModel scaffolding in [com.systemsgo.hex.ui.screens.RdpSessionActivity]
 * can drive any of the three.
 */
enum class RemoteSessionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, AUTH_FAILED, ERROR }

enum class RemoteMouseButton { LEFT, RIGHT, MIDDLE }

/** A rectangular framebuffer update, used by RDP and VNC. */
data class RemoteFrameUpdate(
    val x: Int, val y: Int, val width: Int, val height: Int,
    val pixels: IntArray, val fullScreen: Boolean = false
)

/** A chunk of raw terminal output, used by SSH. */
data class TerminalOutput(val text: String)

/**
 * UNTRUSTED-CERT DIALOG FEATURE: represents one in-flight "this server's
 * certificate isn't trusted yet" decision. The protocol layer creates one of
 * these on a background connection thread and blocks on [awaitDecision]
 * (must NOT be called from the main thread); the UI collects
 * [RemoteSessionClient.certificateChallenge], shows a confirmation dialog
 * with these details, and calls [respond] once the user taps a button,
 * which unblocks the waiting background thread.
 *
 * This replaces the old design where trust was decided ahead of time via a
 * per-profile "Accept self-signed certificate" toggle in the connection
 * form — that toggle silently pinned whatever certificate showed up first,
 * without the user ever seeing what they were trusting. Now the decision is
 * made at connect time, with the actual certificate details in front of the
 * user, the same way a browser handles an untrusted-certificate warning.
 */
class CertificateChallenge(
    val host: String,
    val port: Int,
    val commonName: String,
    val issuer: String,
    val fingerprint: String,
) {
    enum class Decision { REJECT, ACCEPT_ONCE, ACCEPT_ALWAYS }

    private val latch = java.util.concurrent.CountDownLatch(1)
    @Volatile private var decision: Decision = Decision.REJECT

    /** Blocks the calling (background) thread until [respond] is called. */
    fun awaitDecision(): Decision {
        latch.await()
        return decision
    }

    /** Called from the UI thread once the user picks an option in the dialog. */
    fun respond(decision: Decision) {
        this.decision = decision
        latch.countDown()
    }
}

/**
 * Common surface implemented by [com.systemsgo.hex.rdp.protocol.RdpClient],
 * [com.systemsgo.hex.vnc.protocol.VncClient], and
 * [com.systemsgo.hex.ssh.protocol.SshClient].
 *
 * Framebuffer-based protocols (RDP/VNC) emit [frameUpdates]; the terminal
 * protocol (SSH) emits [terminalOutput] instead and treats key events as
 * raw byte input rather than scan codes. Methods that don't apply to a given
 * protocol are simply no-ops (e.g. mouse events on an SSH session).
 */
interface RemoteSessionClient {
    val sessionState: StateFlow<RemoteSessionState>
    val frameUpdates: SharedFlow<RemoteFrameUpdate>
    val terminalOutput: SharedFlow<TerminalOutput>
    val error: SharedFlow<String>
    val latencyMs: Long

    suspend fun connect(): Boolean

    fun sendMouseMove(x: Int, y: Int)
    fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean)
    fun sendMouseScroll(x: Int, y: Int, delta: Int)
    fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean = false)
    fun sendCtrlAltDel()

    /** For terminal sessions: send raw text typed/pasted by the user. */
    fun sendText(text: String)

    /**
     * LIVE-RESIZE FIX: notify the underlying protocol that the local display
     * size changed — device rotation or an external monitor was connected/
     * disconnected — so the remote desktop can be resized in-session to
     * match, instead of the new dimensions only taking effect on the next
     * manual reconnect.
     *
     * Default is a no-op: SSH is a text terminal with no framebuffer
     * resolution to renegotiate. RDP ([com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter])
     * and VNC ([com.systemsgo.hex.vnc.protocol.VncClient]) override this with a
     * real protocol-level resize request. Neither protocol guarantees the
     * server will honor it (older servers / some VNC servers simply ignore
     * the request); the framebuffer keeps its current size in that case and
     * the session continues normally — this must never throw or disconnect.
     */
    fun resize(width: Int, height: Int) {}

    /**
     * HIRES-ZOOM FEATURE: best-effort request for a fresh, non-incremental
     * repaint of the entire framebuffer — used once a client-side pinch/pan
     * gesture settles, so the now-still viewport is guaranteed to reflect a
     * complete frame rather than whatever partial incremental updates
     * happened to arrive mid-gesture.
     *
     * Default is a no-op: SSH has no framebuffer to refresh, and RDP
     * ([com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter]) relies on FreeRDP's
     * own internal surface invalidation rather than a client-driven full
     * repaint. VNC ([com.systemsgo.hex.vnc.protocol.VncClient]) overrides this
     * with a real RFB non-incremental FramebufferUpdateRequest. Like
     * [resize], this must never throw or disconnect — a server/protocol
     * that ignores it simply keeps relying on its normal incremental
     * update flow.
     */
    fun refresh() {}

    /**
     * MULTI-MONITOR FEATURE: the monitor layout currently in effect for this
     * session, or an empty list when the protocol/server doesn't support
     * (or hasn't yet negotiated) multiple monitors.
     *
     * Default is a permanently-empty StateFlow: SSH has no framebuffer
     * concept of monitors at all, and VNC's RFB protocol has no multi-monitor
     * extension either. Only [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter]
     * overrides this — see its doc for what "detecting" a monitor layout
     * means for RDP specifically (client-declared, not server-queried).
     *
     * UI consumers (the monitor selector in SessionToolbar) key their
     * visibility directly off `monitors.size > 1`, so a protocol/session
     * that never populates this beyond one entry automatically hides the
     * whole feature, satisfying "hide the feature automatically when
     * unsupported" without every call site needing its own capability check.
     */
    val monitors: StateFlow<List<RemoteMonitor>>
        get() = EMPTY_MONITORS

    /**
     * MULTI-MONITOR FEATURE: switch which monitor(s) are shown, without
     * reconnecting. Default is a no-op — see [monitors] doc. RDP implements
     * this via the same MS-RDPEDISP "disp" channel already used for
     * [resize] (SendMonitorLayout with a different active subset), so it
     * shares the same best-effort semantics: a server that doesn't support
     * it, or a session where the disp channel never connected, simply
     * ignores the request and keeps showing whatever it already was.
     */
    fun selectMonitor(selection: MonitorSelection) {}

    /**
     * MULTITOUCH FEATURE: forward one raw multi-contact touch frame (every
     * currently down/changed finger, already mapped to remote-pixel space —
     * see [com.systemsgo.hex.ui.screens.RemoteTouchContact]) to the protocol
     * layer, for [com.systemsgo.hex.ui.screens.MouseInputMode.MULTITOUCH]
     * sessions.
     *
     * Default is a no-op, matching [resize]/[refresh]'s "unsupported
     * protocols simply ignore the call" contract: SSH has no pointer concept
     * at all, and VNC's standard RFB protocol has no multi-touch extension
     * to send this over (a real touch injection there needs the specific
     * server-side extension the target VNC server supports, if any — see
     * [multiTouchSupported]). Only
     * [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter] overrides this today,
     * forwarding to [com.systemsgo.hex.rdp.native.AFreeRdpBridge.sendTouchFrame]
     * (MS-RDPEI). Must never throw or disconnect — a server without RDPEI
     * support just never opens the channel and this becomes a silent no-op
     * on the native side too.
     */
    fun sendTouchFrame(contacts: List<com.systemsgo.hex.ui.screens.RemoteTouchContact>) {}

    /**
     * MULTITOUCH FEATURE: whether this session can actually carry real
     * multi-contact touch right now — i.e. whether offering
     * [com.systemsgo.hex.ui.screens.MouseInputMode.MULTITOUCH] in the UI
     * makes sense for this connection. Default false (SSH, VNC without a
     * supported extension). RDP flips this true once the "rdpei" channel
     * actually connects (mirrors [channelStatus]'s live, post-negotiation
     * signal rather than a static per-protocol capability flag — a server
     * without MS-RDPEI support must not advertise this as available).
     */
    val multiTouchSupported: StateFlow<Boolean>
        get() = FALSE_MULTITOUCH_SUPPORTED

    fun disconnect()

    /**
     * LIVE-CHANNEL-STATUS FEATURE: whether each redirected device channel is
     * actually connected *right now*, for the in-session Toolbox status
     * indicators — distinct from the pre-connect setup screen's toggles
     * (which only say "will this be requested", not "did the server accept
     * it"). Mirrors [monitors]/[clipboardSyncState]'s "unsupported protocols
     * don't override, callers key visibility/appearance off the default"
     * pattern: SSH/VNC have no such channels and simply never flip these on.
     *
     * Only [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter] overrides this
     * today, forwarding [com.systemsgo.hex.rdp.native.AFreeRdpBridge]'s
     * printerChannelState/audioChannelState/smartcardChannelState/
     * webcamChannelState SharedFlows into one StateFlow so the UI has a
     * single current snapshot to read instead of four fire-and-forget event
     * streams. Printer/smartcard share the same "rdpdr" static channel, but
     * RDPDR-DEVICE-ANNOUNCE FIX means that no longer makes their live signal
     * coarser than audio/webcam's own dynamically-named channels: a small
     * FreeRDP patch applied at CI build time reports each device's own
     * DR_CORE_DEVICE_ANNOUNCE_RSP (type + accept/reject), so printerConnected
     * and smartcardConnected below each reflect that specific device's own
     * server-side acceptance — see AFreeRdpBridge.printerChannelState's doc
     * comment for exactly how.
     */
    val channelStatus: StateFlow<RemoteChannelStatus>
        get() = EMPTY_CHANNEL_STATUS

    /**
     * CLIPBOARD-SYNC FEATURE (Stage 9): current clipboard-sync state for this
     * session —
     *  - `null` means unsupported/not started: SSH (no clipboard channel at
     *    all, [com.systemsgo.hex.remote.clipboard.ClipboardCapableSession] not
     *    implemented), or an RDP/VNC session whose profile disabled clipboard
     *    redirection outright (no
     *    [com.systemsgo.hex.remote.clipboard.ClipboardSyncManager] was ever
     *    created — see [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter] /
     *    [com.systemsgo.hex.vnc.protocol.VncClient]).
     *  - `true`/`false` means sync is actually wired up, and reflects whether
     *    it is currently active.
     *
     * A single nullable flow (rather than a separate "supported" boolean plus
     * an "enabled" boolean) is deliberate: the Toolbox tool only needs to
     * know "should I show this tool, and if so what does it look like right
     * now", and collapsing both questions into one flow means there is only
     * ever one source of truth to keep in sync.
     *
     * Default is a permanently-null StateFlow, matching [monitors]'s
     * "unsupported protocols don't override, callers key visibility off the
     * default" pattern.
     */
    val clipboardSyncState: StateFlow<Boolean?>
        get() = NULL_CLIPBOARD_SYNC_STATE

    /**
     * CLIPBOARD-SYNC FEATURE (Stage 9): lets the UI toggle clipboard sync
     * on/off for this connection at runtime. Default is a no-op (SSH, or any
     * protocol where [clipboardSyncState] is permanently null); RDP/VNC
     * override this to forward the call to their
     * [com.systemsgo.hex.remote.clipboard.ClipboardSyncManager].
     */
    fun setClipboardSyncEnabled(enabled: Boolean) {}

    /**
     * UNTRUSTED-CERT DIALOG FEATURE: non-null exactly while a
     * [CertificateChallenge] is awaiting the user's decision. The UI should
     * collect this and show a trust-confirmation dialog whenever it flips
     * from null to non-null, and dismiss it when it flips back to null.
     *
     * Default is a permanently-null StateFlow — SSH keeps its own separate
     * host-key TOFU flow, and VNC isn't wired to this yet. Only
     * [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter] overrides this so far.
     */
    val certificateChallenge: StateFlow<CertificateChallenge?>
        get() = NULL_CERTIFICATE_CHALLENGE

    /**
     * CODEC-NEGOTIATION FEATURE (part 3): the codec name FreeRDP's RDPGFX
     * graphics pipeline is actually using for this session right now, or
     * `null` when there's nothing to report — mirrors [clipboardSyncState]'s
     * single nullable flow shape (rather than a separate "supported" +
     * "value" pair) for the same reason: the diagnostics display only needs
     * one flow to answer both "is the GFX pipeline even active" (null vs
     * non-null) and "which codec" (the string itself) at once.
     *
     * `null` covers: SSH/VNC (no RDPGFX concept at all), an RDP session
     * that's still on FreeRDP's classic (non-GFX) path because neither end
     * negotiated MS-RDPEGFX, or a GFX session where the first
     * RDPGFX_SURFACE_COMMAND simply hasn't arrived yet. Once non-null it
     * reflects the real per-frame codec the server is using — see
     * [com.systemsgo.hex.rdp.native.AFreeRdpBridge.negotiatedCodec]'s doc
     * comment for why this is derived from SurfaceCommand rather than
     * CapsConfirm — and can change again mid-session if the server switches
     * codecs (e.g. in response to NetworkAutoDetect-measured conditions).
     *
     * Default is a permanently-null StateFlow, matching [clipboardSyncState]/
     * [monitors]'s "unsupported protocols don't override, callers key
     * visibility off the default" pattern. Only
     * [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter] overrides this so far.
     */
    val negotiatedCodec: StateFlow<String?>
        get() = NULL_NEGOTIATED_CODEC

    /**
     * XRDP-CAPABILITY-DETECTION FEATURE: which security protocol
     * ([MS-RDPBCGR] 2.2.1.1.1 RDP Negotiation Request) the server actually
     * selected for this connection — "NLA", "TLS", or "RDP" (Standard RDP
     * Security) — or `null` when there's nothing to report yet/at all.
     * Mirrors [negotiatedCodec]'s single-nullable-flow shape for the same
     * reason: one flow answers both "do we know yet" (null vs non-null) and
     * "which one" (the string) at once.
     *
     * Derived from `FreeRDP_NlaSecurity`/`FreeRDP_TlsSecurity`/
     * `FreeRDP_RdpSecurity` read back from `rdpSettings` in
     * `systemsgo_post_connect()` — FreeRDP's `nego_security_connect()`
     * overwrites those three booleans in place with whichever one was
     * actually agreed on, so reading them back after connect (rather than
     * before) reports the negotiated outcome, not just what this client
     * requested. See [com.systemsgo.hex.rdp.native.AFreeRdpBridge]'s
     * `onNativeSecurityProtocolNegotiated` doc comment.
     *
     * `null` covers: SSH/VNC/Telnet (no concept of this at all) and any RDP
     * session before `PostConnect` has run. Default is a permanently-null
     * StateFlow, matching [negotiatedCodec]'s pattern. Only
     * [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter] overrides this.
     */
    val negotiatedSecurityProtocol: StateFlow<String?>
        get() = NULL_NEGOTIATED_SECURITY_PROTOCOL

    companion object {
        val EMPTY_MONITORS: StateFlow<List<RemoteMonitor>> = MutableStateFlow<List<RemoteMonitor>>(emptyList()).asStateFlow()
        val NULL_CLIPBOARD_SYNC_STATE: StateFlow<Boolean?> = MutableStateFlow<Boolean?>(null).asStateFlow()
        val NULL_CERTIFICATE_CHALLENGE: StateFlow<CertificateChallenge?> = MutableStateFlow<CertificateChallenge?>(null).asStateFlow()
        val EMPTY_CHANNEL_STATUS: StateFlow<RemoteChannelStatus> = MutableStateFlow(RemoteChannelStatus()).asStateFlow()
        val NULL_NEGOTIATED_CODEC: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
        val NULL_NEGOTIATED_SECURITY_PROTOCOL: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
        val FALSE_MULTITOUCH_SUPPORTED: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    }
}

/**
 * LIVE-CHANNEL-STATUS FEATURE: see [RemoteSessionClient.channelStatus]. One
 * snapshot of "is the redirected device channel connected" per device this
 * app can redirect and has a live native signal for.
 */
data class RemoteChannelStatus(
    val printerConnected: Boolean = false,
    val audioPlaybackConnected: Boolean = false,
    val audioCaptureConnected: Boolean = false,
    val smartcardConnected: Boolean = false,
    val webcamConnected: Boolean = false,
)
