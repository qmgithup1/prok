package com.systemsgo.hex.proxy

import android.util.Log
import com.systemsgo.hex.rdp.native.AFreeRdpBridge
import com.systemsgo.hex.rdp.native.AFreeRdpServerBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * PROXY-RELAY FEATURE: a "MITM RDP proxy" built by composing two bridges
 * this project ALREADY has, rather than wrapping FreeRDP's own
 * `server/proxy` (pf_*) component.
 *
 * WHY NOT wrap `server/proxy` directly: that component is a standalone
 * daemon (its own config file format, module/plugin system, `pf_server_main`
 * entry point) designed to be built as `freerdp-proxy`/deployed on a
 * server/desktop OS — not a small embeddable library with a stable,
 * documented C API the way `libfreerdp-server3.so`/`libfreerdp-client3.so`
 * already are for this project's existing bridges. Wrapping it properly
 * would mean a brand-new prebuilt library, a brand-new (largely unverified,
 * no network access here to check the exact 3.27.1 `proxy/pf_*.h` surface)
 * JNI bridge, and its own config-translation layer — all before the first
 * end-to-end connection. See app/src/main/cpp/SETUP.md's existing CAVEAT
 * comments (CUPS, PCSC, AV1) for what that class of "unverified against a
 * real build" risk already looks like elsewhere in this project.
 *
 * WHAT THIS DOES INSTEAD: this device runs BOTH halves of a real RDP
 * session at once — [AFreeRdpServerBridge] listens for and accepts an
 * incoming RDP client (exactly like Shadow Server already does), while
 * [AFreeRdpBridge] simultaneously dials OUT to the real target server
 * (exactly like a normal outbound connection already does). This class
 * glues the two: frames arriving from the outbound session are pushed to
 * the inbound listener's connected peer(s), and input arriving from the
 * inbound peer is forwarded to the outbound session. Both halves are
 * already proven, already-shipping code paths in this project — this class
 * adds no new native surface at all, only Kotlin glue.
 *
 * TRADEOFFS vs a real `server/proxy`-based relay (be upfront about these):
 *  - No session recording, no MS-RDPEDYC-level plugin/filter framework —
 *    just a live relay of decoded framebuffer + input. A future upgrade to
 *    a real `server/proxy` build could add capture/audit logging that this
 *    class structurally cannot (it never sees raw wire bytes, only decoded
 *    frames/input, same as any other AFreeRdpBridge consumer).
 *  - The outbound session is a full RDP *client* (same feature set as any
 *    other connection this app makes — NLA, codecs, channels, ...), so the
 *    proxy transparently supports anything this app's own client already
 *    does. A real `server/proxy` build negotiates its own capability set
 *    independently on each side and can rewrite/filter it; this can't.
 *  - Single target per relay instance, single inbound listener — this is
 *    NOT a multi-tenant/multi-target proxy daemon. Good enough for "route
 *    my own second device through this phone to reach an RDP host it can't
 *    reach directly", not for a shared enterprise jump-box.
 *
 * SECURITY NOTE (same severity as AFreeRdpServerBridge's own — read that
 * class's doc comment too): the inbound half only supports Standard RDP
 * Security today (no TLS/NLA on the LISTENING side — the outbound side to
 * the real target is unaffected and keeps whatever security that
 * connection profile specifies). Never expose [listenPort] outside a
 * trusted LAN/VPN. A peer that completes the inbound handshake gets a full,
 * live RDP session to whatever [targetHost] this relay was started with —
 * there is no per-peer authentication of its own beyond that.
 */
class RdpProxyRelay(
    private val serverBridge: AFreeRdpServerBridge = AFreeRdpServerBridge(),
    private val clientBridge: AFreeRdpBridge = AFreeRdpBridge(),
) {
    companion object {
        private const val TAG = "RdpProxyRelay"

        /** Both halves' native libraries must be present — see the two
         * bridges' own `isAvailable` docs. The inbound half additionally
         * requires the CI's FreeRDP prebuilt to have been built with
         * `-DWITH_SERVER=ON` (see main.yml's "RDP-SERVER-API FEATURE" step
         * and this project's SETUP.md — that flag was OFF prior to the
         * PROXY-RELAY FEATURE change that added this class, meaning Shadow
         * Server itself was already silently non-functional in CI builds
         * until then; flipping it ON is a prerequisite this class shares
         * with that pre-existing feature, not something new to just this
         * class). */
        val isAvailable: Boolean
            get() = AFreeRdpServerBridge.isAvailable
    }

    private var relayJob: Job? = null
    private var frameJob: Job? = null
    private var keyJob: Job? = null
    private var mouseJob: Job? = null

    /** Full-frame BGRX32 canvas the outbound session's (possibly partial)
     * frame updates are painted into before being pushed to the inbound
     * listener — [AFreeRdpServerBridge.pushFrame] pushes one complete frame
     * at a time, same as [com.systemsgo.hex.shadow.ShadowScreenCaptureService]
     * already does for its own capture source. */
    private var canvas: IntArray = IntArray(0)
    private var canvasWidth = 0
    private var canvasHeight = 0

    val isRunning: Boolean
        get() = serverBridge.isRunning

    /**
     * Starts the relay: begins listening on [listenPort] AND immediately
     * dials out to [targetHost]:[targetPort]. Returns false immediately if
     * either half fails to start (in which case the other half, if it
     * already started, is torn back down — never left half-running).
     *
     * [scope] should be a long-lived scope tied to the owning
     * service/foreground-service lifecycle (see [RdpProxyService]), not a
     * short-lived Activity scope — the relay must keep running with the
     * screen off / Activity destroyed.
     */
    fun start(
        context: android.content.Context,
        scope: CoroutineScope,
        listenPort: Int,
        targetHost: String, targetPort: Int,
        targetUsername: String, targetPassword: String, targetDomain: String,
        width: Int = 1280, height: Int = 800,
        useNla: Boolean = true, ignoreCert: Boolean = false,
        /** NLA-SERVER FEATURE: credentials required from anyone connecting
         * to THIS device's inbound listener (unrelated to targetUsername/
         * targetPassword above, which are for the outbound half dialing
         * OUT to the real RDP host). Null/blank disables the inbound
         * check/NLA — see AFreeRdpServerBridge's class doc. */
        inboundUsername: String? = null, inboundPassword: String? = null,
    ): Boolean {
        if (!isAvailable) {
            Log.w(TAG, "start() called but the inbound (server) native library is unavailable — see isAvailable's doc")
            return false
        }
        if (isRunning) {
            Log.w(TAG, "start() called while already running — stop() first")
            return false
        }

        canvasWidth = width
        canvasHeight = height
        canvas = IntArray(width * height)

        // TLS-SERVER FEATURE: same self-signed cert/key generation Shadow
        // Server uses (see ShadowScreenCaptureService.kt) — the inbound
        // half of this relay is otherwise exactly as exposed as Shadow
        // Server's listener, same SECURITY NOTE in this class's doc
        // comment applies. KNOWN CAVEAT: same main-thread-on-first-call
        // cost noted in ShadowScreenCaptureService — RdpProxyService calls
        // this from onStartCommand.
        val serverCert = com.systemsgo.hex.rdp.native.RdpServerCertificateGenerator.getOrCreate(
            context.applicationContext
        )
        if (serverCert == null) {
            Log.w(TAG, "Falling back to no-TLS Standard RDP Security on the inbound half — " +
                "certificate generation failed, see RdpServerCertificateGenerator logs")
        }

        if (serverCert != null && !inboundUsername.isNullOrBlank() && inboundPassword != null) {
            val samPath = com.systemsgo.hex.rdp.native.RdpServerNlaCredentials.writeSamFile(
                context.applicationContext, inboundUsername, inboundPassword
            )
            serverBridge.setSamFile(samPath)
            if (samPath == null) {
                Log.w(TAG, "Failed to write NLA SAM file for inbound half — falling back to TLS-only")
            }
        } else {
            com.systemsgo.hex.rdp.native.RdpServerNlaCredentials.clear(context.applicationContext)
            serverBridge.setSamFile(null)
        }
        serverBridge.setExpectedCredentials(inboundUsername, inboundPassword)

        if (!serverBridge.start(listenPort, width, height, serverCert?.certPath, serverCert?.keyPath)) {
            Log.e(TAG, "Failed to start inbound listener on port $listenPort")
            return false
        }

        clientBridge.init()
        val connected = clientBridge.connect(
            host = targetHost, port = targetPort,
            username = targetUsername, password = targetPassword, domain = targetDomain,
            width = width, height = height, useNla = useNla, ignoreCert = ignoreCert,
            gatewayEnabled = false, gatewayHost = "", gatewayPort = 443,
            gatewayUsername = "", gatewayPassword = "", gatewayDomain = "",
        )
        if (!connected) {
            Log.e(TAG, "Failed to connect outbound to $targetHost:$targetPort — tearing down inbound listener")
            serverBridge.stop()
            return false
        }

        // Outbound frames -> paint into canvas -> push full frame to inbound peer(s).
        // UNTHROTTLED (unlike ShadowScreenCaptureService's capture-rate cap) — a
        // reasonable follow-up if this proves too CPU/battery-heavy for a given
        // target's update rate; left simple here since a real RDP session's own
        // update cadence (not a fixed-interval capture poll) already gates this.
        frameJob = clientBridge.frames.onEach { frame ->
            paintRegion(frame.x, frame.y, frame.width, frame.height, frame.pixels, frame.fullScreen)
            serverBridge.pushFrame(canvasToBgrx32(), canvasWidth, canvasHeight)
        }.launchIn(scope)

        // Inbound peer input -> forwarded to the outbound session.
        // KBD_FLAGS_EXTENDED = 0x0100 (MS-RDPBCGR "Fast-Path Keyboard Event" /
        // Slow-Path Keyboard Event flags) — see systemsgo_jni.c's nativeSendKey
        // for the same bit on the sending side.
        keyJob = serverBridge.peerKeyEvents.onEach { evt ->
            clientBridge.sendKey(evt.scancode, evt.isDown, (evt.flags and 0x0100) != 0)
        }.launchIn(scope)

        mouseJob = serverBridge.peerMouseEvents.onEach { evt ->
            clientBridge.sendMouse(evt.x, evt.y, evt.flags)
        }.launchIn(scope)

        relayJob = scope.launch(Dispatchers.Default) {
            // Keeps this CoroutineScope's job tree alive/cancellable as a
            // single unit from stop() below; the actual work happens in the
            // three onEach flows above.
        }

        Log.i(TAG, "Relay started: listening on $listenPort, relaying to $targetHost:$targetPort")
        return true
    }

    fun stop() {
        frameJob?.cancel(); frameJob = null
        keyJob?.cancel(); keyJob = null
        mouseJob?.cancel(); mouseJob = null
        relayJob?.cancel(); relayJob = null
        clientBridge.disconnect()
        serverBridge.stop()
        canvas = IntArray(0)
        Log.i(TAG, "Relay stopped")
    }

    /** Paints one (possibly partial) ARGB region from the outbound session
     * into [canvas]. [fullScreen] short-circuits to a bulk copy when the
     * update covers the entire negotiated desktop (the common case for the
     * very first frame and for most RemoteFX/GFX full-screen refreshes). */
    private fun paintRegion(x: Int, y: Int, w: Int, h: Int, pixels: IntArray, fullScreen: Boolean) {
        if (canvasWidth == 0 || canvasHeight == 0) return
        if (fullScreen && w == canvasWidth && h == canvasHeight) {
            System.arraycopy(pixels, 0, canvas, 0, minOf(pixels.size, canvas.size))
            return
        }
        for (row in 0 until h) {
            val destY = y + row
            if (destY < 0 || destY >= canvasHeight) continue
            val srcOffset = row * w
            val destOffset = destY * canvasWidth + x
            val copyWidth = minOf(w, canvasWidth - x)
            if (copyWidth <= 0) continue
            System.arraycopy(pixels, srcOffset, canvas, destOffset, copyWidth)
        }
    }

    /** ARGB (packed Int, as produced by AFreeRdpBridge.NativeFrame) ->
     * BGRX32 (as required by AFreeRdpServerBridge.pushFrame) — same byte
     * layout ShadowScreenCaptureService.toBgrx() produces from a different
     * source (MediaProjection's RGBA_8888 plane) for the same sink. */
    private fun canvasToBgrx32(): ByteArray {
        val out = ByteArray(canvasWidth * canvasHeight * 4)
        var o = 0
        for (px in canvas) {
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            out[o++] = b.toByte()
            out[o++] = g.toByte()
            out[o++] = r.toByte()
            out[o++] = 0xFF.toByte() // X byte — unused by PIXEL_FORMAT_BGRX32
        }
        return out
    }
}
