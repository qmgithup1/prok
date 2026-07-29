package com.systemsgo.hex.spice.protocol

import android.util.Log
import com.systemsgo.hex.remote.*
import com.systemsgo.hex.spice.native.SpiceBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * SPICE-PROTOCOL FEATURE, Part 4/N: [RemoteSessionClient] implementation
 * over [SpiceBridge] (which itself now has real SpiceSession/Main/Display/
 * Inputs channel logic — see systemsgo_spice_jni.c's Part 3/N doc comment).
 *
 * Shape mirrors [com.systemsgo.hex.vnc.protocol.VncClient] deliberately:
 * both are framebuffer protocols with no monitor/channel-status/codec
 * concepts beyond the RemoteSessionClient defaults, so this class only
 * overrides the members that differ from VncClient's own overrides
 * (frameUpdates/sessionState/error/mouse/keyboard/disconnect) and leaves
 * everything else (monitors, channelStatus, clipboardSyncState,
 * certificateChallenge, negotiatedCodec, negotiatedSecurityProtocol) at
 * [RemoteSessionClient]'s "unsupported" defaults — none of that is wired
 * up for SPICE yet. Clipboard *sync* in particular is a real SPICE channel
 * (spice_main_channel's "agent" clipboard messages) and is still out of
 * scope; a future part could implement
 * [com.systemsgo.hex.remote.clipboard.ClipboardCapableSession] the same
 * way VncClient does once that's prioritized. [sendText] (paste-as-
 * keystrokes) does NOT need that channel and is implemented below — see
 * its doc comment and [SpiceUsKeyboardLayout].
 */
class SpiceCredentials(
    val host: String,
    val port: Int,
    password: String,
) {
    // CRIT-2 FIX pattern (matches VncCredentials/RdpCredentials): CharArray
    // instead of String so it can be zeroed after use rather than lingering
    // on the heap for an unpredictable duration.
    val password: CharArray = password.toCharArray()
    fun zero() { password.fill('\u0000') }
}

class SpiceSessionClient(
    private val credentials: SpiceCredentials,
) : RemoteSessionClient {

    companion object {
        private const val TAG = "SpiceSessionClient"

        // Verified against spice-protocol's spice/enums.h (SpiceMouseButton
        // enum: LEFT=1, MIDDLE=2, RIGHT=3, UP=4, DOWN=5 — matches below
        // exactly) and cross-checked against the Spice-GTK reference doc's
        // spice_inputs_button_press/release(channel, gint button, gint
        // button_state) signature. Button *numbers* (for press/release) and
        // button *mask bits* (for position/motion) are two different scales
        // in the SPICE wire protocol — mixing them up is a classic bug here,
        // so they're named distinctly rather than reusing one constant set.
        private const val SPICE_MOUSE_BUTTON_LEFT = 1
        private const val SPICE_MOUSE_BUTTON_MIDDLE = 2
        private const val SPICE_MOUSE_BUTTON_RIGHT = 3
        private const val SPICE_MOUSE_BUTTON_UP = 4    // wheel up, sent as a press+release "button"
        private const val SPICE_MOUSE_BUTTON_DOWN = 5  // wheel down, ditto

        // Verified against spice-client-glib's actual SPICE_MOUSE_BUTTON_MASK_*
        // enum: LEFT is bit 0, not bit 1 — an earlier off-by-one guess here
        // shifted every mask bit up by one position (LEFT=1<<1, MIDDLE=1<<2,
        // RIGHT=1<<3), which would have sent RIGHT's bit pattern for LEFT
        // clicks and left bit 0 always zero.
        private const val SPICE_MOUSE_MASK_LEFT = 1 shl 0
        private const val SPICE_MOUSE_MASK_MIDDLE = 1 shl 1
        private const val SPICE_MOUSE_MASK_RIGHT = 1 shl 2

        // PC/AT Set-1 (XT) scancodes for the keys sendCtrlAltDel needs.
        // Same values VncClient's scanCodeToKeysym table already treats as
        // the canonical input scancode space for this app (RDP-style raw
        // scancodes, matching what RdpSessionActivity's keyboard input
        // already produces for every protocol).
        private const val SC_CONTROL_L = 0x1D
        private const val SC_ALT_L = 0x38
        private const val SC_DELETE = 0x53 // extended
    }

    private val _sessionState = MutableStateFlow(RemoteSessionState.DISCONNECTED)
    override val sessionState: StateFlow<RemoteSessionState> = _sessionState.asStateFlow()

    private val _frameUpdates = MutableSharedFlow<RemoteFrameUpdate>(extraBufferCapacity = 8)
    override val frameUpdates: SharedFlow<RemoteFrameUpdate> = _frameUpdates.asSharedFlow()

    private val _terminalOutput = MutableSharedFlow<TerminalOutput>(extraBufferCapacity = 1)
    override val terminalOutput: SharedFlow<TerminalOutput> = _terminalOutput.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val error: SharedFlow<String> = _error.asSharedFlow()

    override var latencyMs: Long = 0L
        private set

    @Volatile private var connected = false

    // Running SPICE button-mask state, needed because spice_inputs_
    // position() (called on every mouse move) takes the *current* combined
    // button mask alongside x/y, not just the coordinates — unlike VNC's
    // sendPointerEvent, which folds position+buttons into one call anyway
    // and so has no separate state to track. Written only from the UI/
    // input-calling thread; SpiceBridge's own JNI calls are synchronous
    // pass-throughs so no extra synchronization is needed here.
    private var buttonMask = 0

    @Volatile private var sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Bridges native onNativeFrame callbacks (arriving on systemsgo_spice_jni.c's
    // internal GLib thread — see that file's thread-model doc comment) into
    // this class's _frameUpdates SharedFlow. A small subclass rather than a
    // lambda/interface because JNI's GetMethodID(..., "onNativeFrame", ...)
    // resolves the method directly on the SpiceBridge instance's class (see
    // nativeInit in systemsgo_spice_jni.c) — it must be an actual override, not
    // a callback field.
    private inner class Bridge : SpiceBridge() {
        override fun onNativeFrame(x: Int, y: Int, w: Int, h: Int, pixels: IntArray, fullFrame: Boolean) {
            // tryEmit is safe from any thread (matches VncClient's
            // rfbClient.onServerCutText -> _remoteClipboardUpdates.tryEmit
            // pattern for the same reason: this runs off a native/JNI
            // thread, not a coroutine, so a suspending emit() isn't an
            // option here).
            _frameUpdates.tryEmit(
                RemoteFrameUpdate(x = x, y = y, width = w, height = h, pixels = pixels, fullScreen = fullFrame)
            )
        }
    }

    @Volatile private var bridge: Bridge? = null

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        _sessionState.value = RemoteSessionState.CONNECTING

        if (!SpiceBridge.isAvailable) {
            Log.w(TAG, "SpiceBridge.isAvailable == false — native SPICE library missing for this build")
            _error.tryEmit("SPICE support is not available in this build.")
            _sessionState.value = RemoteSessionState.ERROR
            return@withContext false
        }

        val b = Bridge()
        if (!b.init()) {
            Log.e(TAG, "SpiceBridge.init() failed (native allocation failure)")
            _error.tryEmit("Failed to initialize SPICE session.")
            _sessionState.value = RemoteSessionState.ERROR
            return@withContext false
        }
        bridge = b

        val passwordStr = String(credentials.password)
        val ok = try {
            // nativeConnect blocks (up to ~20s, see systemsgo_spice_jni.c) until
            // the main channel reports success/failure — same synchronous
            // contract as AFreeRdpBridge.connect/RfbConnectable.connect, so
            // no extra timeout wrapper is needed here.
            b.connect(credentials.host, credentials.port, passwordStr)
        } catch (e: Exception) {
            Log.e(TAG, "SpiceBridge.connect threw", e)
            false
        } finally {
            credentials.zero()
        }

        if (ok) {
            connected = true
            _sessionState.value = RemoteSessionState.CONNECTED
        } else {
            _error.tryEmit(
                // BUG-i18n-LEAK FIX pattern (matches VncClient/SshClient): a
                // generic, already-localized string rather than any raw
                // native/GLib error text, which could contain the server
                // hostname or other connection detail.
                appContextErrorFallback()
            )
            _sessionState.value = RemoteSessionState.ERROR
            b.release()
            bridge = null
        }
        ok
    }

    // BUG-i18n-LEAK FIX: unlike VncClient/SshClient, this class isn't handed
    // an appContext today (SpiceBridge needs none for its own connect path —
    // no TLS/certificate Context dependency the way RfbConnectable has), so
    // there's no R.string resource lookup available here yet. Kept as its
    // own function (rather than a plain string literal inline above) so a
    // future part that does thread an appContext through can swap this one
    // line for `appContext.getString(R.string.error_connection_lost)` to
    // match VncClient/SshClient exactly, without touching connect()'s logic.
    private fun appContextErrorFallback(): String =
        "Failed to connect to the SPICE server. Check the host, port, and password."

    // ── Input ────────────────────────────────────────────────────────────

    override fun sendMouseMove(x: Int, y: Int) {
        bridge?.sendMousePosition(x, y, buttonMask)
    }

    override fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) {
        val b = bridge ?: return
        val (num, mask) = when (button) {
            RemoteMouseButton.LEFT -> SPICE_MOUSE_BUTTON_LEFT to SPICE_MOUSE_MASK_LEFT
            RemoteMouseButton.MIDDLE -> SPICE_MOUSE_BUTTON_MIDDLE to SPICE_MOUSE_MASK_MIDDLE
            RemoteMouseButton.RIGHT -> SPICE_MOUSE_BUTTON_RIGHT to SPICE_MOUSE_MASK_RIGHT
        }
        buttonMask = if (down) buttonMask or mask else buttonMask and mask.inv()
        // Position first so the server has the up-to-date coordinate before
        // it processes the button transition — matches the order SPICE
        // clients conventionally use (position, then button), though the
        // protocol doesn't strictly require it since both carry x/y-less
        // button state separately.
        b.sendMousePosition(x, y, buttonMask)
        b.sendMouseButton(num, down, buttonMask)
    }

    override fun sendMouseScroll(x: Int, y: Int, delta: Int) {
        val b = bridge ?: return
        b.sendMousePosition(x, y, buttonMask)
        val wheelButton = if (delta > 0) SPICE_MOUSE_BUTTON_UP else SPICE_MOUSE_BUTTON_DOWN
        // Wheel events have no separate "hold" state in SPICE — a quick
        // press+release per notch, same convention VncClient's
        // sendMouseScroll uses for RFB's wheel-as-button-4/5 encoding.
        b.sendMouseButton(wheelButton, true, buttonMask)
        b.sendMouseButton(wheelButton, false, buttonMask)
    }

    override fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean) {
        val b = bridge ?: return
        // Verified against spice-client-glib: the real extended-key marker
        // is 0x100, not 0xE000. spice-gtk's own input handling takes a raw
        // PC/AT Set-1 scancode and ORs in 0x100 for e0-prefixed (extended)
        // keys before calling spice_inputs_key_press/key_release — it does
        // NOT reuse the XT "0xE0 prefix" convention as a 0xE000 bit as an
        // earlier guess here assumed. Using 0xE000 would have made every
        // extended key (arrows, Insert/Delete/Home/End, right Ctrl/Alt)
        // send a scancode spice-server doesn't recognize.
        val code = if (extended) 0x100 or scanCode else scanCode
        b.sendKey(code, down)
    }

    override fun sendCtrlAltDel() {
        val b = bridge ?: return
        b.sendKey(SC_CONTROL_L, true)
        b.sendKey(SC_ALT_L, true)
        b.sendKey(0x100 or SC_DELETE, true)
        b.sendKey(0x100 or SC_DELETE, false)
        b.sendKey(SC_ALT_L, false)
        b.sendKey(SC_CONTROL_L, false)
    }

    override fun sendText(text: String) {
        // SPICE-PASTE-AS-KEYSTROKES FEATURE: SPICE's Inputs channel has no
        // Unicode-keysym message (unlike RFB — see VncClient.sendText's
        // 0x01000000-OR-codepoint approach), so "pasting" means
        // synthesizing the scancode sequence a physical US keyboard would
        // send for each character. This reuses the *same* Inputs-channel
        // sendKey path sendKeyEvent already uses below — no additional
        // SPICE channel is involved or needed. See SpiceUsKeyboardLayout's
        // class doc for the (deliberate, hardware-inherent) US-QWERTY-only
        // scope limit: a character with no key on a US keyboard has no
        // scancode to synthesize, full stop — those are skipped and logged
        // rather than silently corrupting the pasted text.
        val b = bridge ?: return
        var shiftHeld = false
        var skipped = 0
        for (ch in text) {
            val mapped = SpiceUsKeyboardLayout.scancodeFor(ch)
            if (mapped == null) {
                skipped++
                continue
            }
            val (code, needsShift) = mapped
            // Group Shift transitions across consecutive characters instead
            // of pressing+releasing it per character — matches how a real
            // keyboard/typist holds Shift down across "ABC", not three
            // separate taps, and halves the message count for runs of
            // same-case text.
            if (needsShift != shiftHeld) {
                b.sendKey(SpiceUsKeyboardLayout.SC_SHIFT_L, needsShift)
                shiftHeld = needsShift
            }
            b.sendKey(code, true)
            b.sendKey(code, false)
        }
        if (shiftHeld) b.sendKey(SpiceUsKeyboardLayout.SC_SHIFT_L, false)
        if (skipped > 0) {
            Log.i(TAG, "sendText: $skipped of ${text.length} character(s) had no US-QWERTY scancode and were skipped")
        }
    }

    override fun disconnect() {
        connected = false
        sessionScope.cancel()
        val b = bridge
        bridge = null
        try {
            b?.disconnect()
            b?.release()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect: error releasing native SPICE session", e)
        }
        _sessionState.tryEmit(RemoteSessionState.DISCONNECTED)
    }
}
