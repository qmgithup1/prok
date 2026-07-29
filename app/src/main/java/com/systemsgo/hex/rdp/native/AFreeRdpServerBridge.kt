package com.systemsgo.hex.rdp.native

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * RDP-SERVER-API FEATURE: JNI bridge to the native RDP SERVER (listener +
 * peer) library — see `app/src/main/cpp/systemsgo_server_jni.c` and
 * `app/src/main/cpp/SETUP.md`'s "RDP-SERVER-API FEATURE" section for the
 * full architecture picture and this milestone's scope.
 *
 * This is deliberately a SEPARATE class/`.so` from [AFreeRdpBridge]:
 * [AFreeRdpBridge] dials OUT to a remote host (this device is the RDP
 * client). This class listens for and accepts INCOMING RDP connections
 * (this device is the RDP host) — the two never share native state.
 *
 * MILESTONE 1 SCOPE (see systemsgo_server_jni.c's top-of-file comment for the
 * full list): proves the listen/accept/negotiate/input pipeline end-to-end.
 * On its own, a connecting RDP client only sees a static placeholder frame,
 * and keyboard/mouse input from the connecting client is delivered back up
 * to Kotlin via [onNativePeerKeyboard] / [onNativePeerMouse] but nothing
 * consumes it yet.
 *
 * SHADOW-SERVER FEATURE (milestone 2, built on top of milestone 1): [pushFrame]
 * feeds real captured screen content in — see [ShadowScreenCaptureService]
 * (`com.systemsgo.hex.shadow`), which drives it from Android's
 * MediaProjection API at a throttled interval. [peerKeyEvents]/
 * [peerMouseEvents] (declared below, milestone 1) are consumed by
 * `RemoteInputAccessibilityService` for the input-injection half. Neither
 * half requires any change to this class's milestone-1 shape — Shadow
 * Server only adds [pushFrame]/[nativePushFrame] alongside what was
 * already here.
 *
 * SECURITY NOTE (TLS-SERVER FEATURE, updated from milestone 1; NLA-SERVER
 * FEATURE, updated again): [start] with a real certPath/keyPath gets a
 * real TLS handshake. Calling [setSamFile] ON TOP of that turns on genuine
 * NLA — FreeRDP's own CredSSP/NTLM code validates the connecting client's
 * credentials against the SAM file BEFORE the session starts (see that
 * function's doc). Without [setSamFile], [setExpectedCredentials] alone is
 * still only an app-level check, not NLA-grade — see its own doc. A
 * self-signed cert (see [RdpServerCertificateGenerator]) means a
 * connecting client still has no CA-backed way to verify it's talking to
 * the device it thinks it is — real NLA fixes *credential* validation, not
 * *server identity* validation; still treat this as LAN-only/trusted-VPN
 * rather than "safe on the open internet" for that reason. This matters
 * MORE once Shadow Server is enabled: a connecting peer sees the device's
 * real screen and can inject real input into it.
 */
class AFreeRdpServerBridge {

    companion object {
        private const val TAG = "AFreeRdpServerBridge"

        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("systemsgo_server_jni")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.i(TAG, "Native RDP server library not present — incoming RDP connections " +
                    "are unavailable until CI rebuilds the FreeRDP prebuilt with -DWITH_SERVER=ON " +
                    "(see app/src/main/cpp/SETUP.md, RDP-SERVER-API FEATURE).")
                false
            } catch (e: Throwable) {
                Log.w(TAG, "Unexpected error probing native RDP server library", e)
                false
            }
        }
    }

    /** Emits the connecting client's address whenever a peer completes PostConnect. */
    private val _peerConnected = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val peerConnected = _peerConnected.asSharedFlow()

    /** Emits the disconnecting client's address. */
    private val _peerDisconnected = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val peerDisconnected = _peerDisconnected.asSharedFlow()

    /** (scancode, isDown, rawFlags) from a connected client's keyboard. */
    data class PeerKeyEvent(val scancode: Int, val isDown: Boolean, val flags: Int)
    private val _peerKeyEvents = MutableSharedFlow<PeerKeyEvent>(extraBufferCapacity = 64)
    val peerKeyEvents = _peerKeyEvents.asSharedFlow()

    /** (flags, x, y) from a connected client's mouse. */
    data class PeerMouseEvent(val flags: Int, val x: Int, val y: Int)
    private val _peerMouseEvents = MutableSharedFlow<PeerMouseEvent>(extraBufferCapacity = 64)
    val peerMouseEvents = _peerMouseEvents.asSharedFlow()

    /**
     * TLS-SERVER FEATURE: starts listening for incoming RDP connections on
     * [port]. [certPath]/[keyPath], if both non-null, point at PEM
     * cert/key files that the native side now actually wires up
     * (`FreeRDP_CertificateFile`/`FreeRDP_PrivateKeyFile` + `TlsSecurity`)
     * for a real TLS handshake — see [RdpServerCertificateGenerator] for
     * the normal way to get a pair (call it once per session start; it
     * caches its self-signed cert on disk and only regenerates when it
     * expires). Passing null for both keeps this milestone's original
     * unauthenticated-transport "Standard RDP Security" fallback — see the
     * class doc SECURITY NOTE, still accurate for that fallback path only.
     *
     * Returns false immediately if the native library isn't available or a
     * listener is already running (single-listener limit, see
     * systemsgo_server_jni.c).
     */
    fun start(port: Int, width: Int, height: Int, certPath: String? = null, keyPath: String? = null): Boolean {
        if (!isAvailable) {
            Log.w(TAG, "start() called but native RDP server library is unavailable")
            return false
        }
        return nativeStart(port, width, height, certPath, keyPath)
    }

    /**
     * NLA-SERVER FEATURE: points FreeRDP's own CredSSP/NTLM handshake at a
     * WinPR SAM-format credential file (see [RdpServerNlaCredentials] for
     * how to generate one from a plain username/password) via
     * `FreeRDP_NtlmSamFile`. When set (and [start] was also given a
     * cert/key — NLA requires TLS underneath per the CredSSP spec),
     * connecting peers must complete a real NLA/CredSSP handshake BEFORE
     * the RDP session even starts — this IS full NLA, not the app-level
     * substitute [setExpectedCredentials] provides on its own. Pass null
     * to disable and fall back to whatever [start] was given (TLS-only or
     * Standard RDP Security). Call before [start]; safe to call while
     * running, affects only future peers.
     */
    fun setSamFile(samFilePath: String?) {
        if (!isAvailable) return
        nativeSetSamFile(samFilePath)
    }

    /**
     * TLS-SERVER FEATURE: gates [onNativePeerLogon]-time access behind a
     * simple username/password check (see systemsgo_server_peer_logon()'s doc
     * comment in systemsgo_server_jni.c for exactly what this is/isn't a
     * substitute for). When [setSamFile] is ALSO configured, real NLA
     * already gated the connection before this callback even fires, so
     * this becomes a harmless defense-in-depth double-check rather than
     * this feature's only line of defense. Call before [start]; pass null
     * for both to disable the check and restore the old accept-everyone
     * behavior. Safe to call while already running — it only affects
     * logons that happen after this call returns.
     */
    fun setExpectedCredentials(username: String?, password: String?) {
        if (!isAvailable) return
        nativeSetExpectedCredentials(username, password)
    }

    fun stop() {
        if (!isAvailable) return
        nativeStop()
    }

    val isRunning: Boolean
        get() = isAvailable && nativeIsRunning()

    /**
     * SHADOW-SERVER FEATURE: pushes one already-BGRX32 [frame] buffer
     * (`width * height * 4` bytes, B-G-R-X byte order per pixel — see
     * [ShadowScreenCaptureService]'s `toBgrx()` for how a captured RGBA
     * frame gets there) out to every connected RDP peer. Returns false
     * (silently, not an error worth logging on every frame) if the server
     * isn't running or the native library isn't available — a capture tick
     * racing a [stop] is expected, not exceptional. Called at whatever rate
     * the capture source produces frames; no internal throttling here (see
     * [ShadowScreenCaptureService] for the frame-rate cap instead — it's
     * the natural place to change that without touching this bridge).
     */
    fun pushFrame(frame: ByteArray, width: Int, height: Int): Boolean {
        if (!isAvailable || !isRunning) return false
        return nativePushFrame(frame, width, height)
    }

    // ── Native entry points (systemsgo_server_jni.c) ──────────────────────
    private external fun nativeStart(port: Int, width: Int, height: Int, certPath: String?, keyPath: String?): Boolean
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativePushFrame(frame: ByteArray, width: Int, height: Int): Boolean
    private external fun nativeSetExpectedCredentials(username: String?, password: String?)
    private external fun nativeSetSamFile(samFilePath: String?)

    // ── Callbacks invoked FROM native (systemsgo_server_jni.c) ────────────
    private fun onNativePeerConnected(clientAddress: String) {
        _peerConnected.tryEmit(clientAddress)
    }

    private fun onNativePeerDisconnected(clientAddress: String) {
        _peerDisconnected.tryEmit(clientAddress)
    }

    private fun onNativePeerKeyboard(scancode: Int, isDown: Boolean, flags: Int) {
        _peerKeyEvents.tryEmit(PeerKeyEvent(scancode, isDown, flags))
    }

    private fun onNativePeerMouse(flags: Int, x: Int, y: Int) {
        _peerMouseEvents.tryEmit(PeerMouseEvent(flags, x, y))
    }
}
