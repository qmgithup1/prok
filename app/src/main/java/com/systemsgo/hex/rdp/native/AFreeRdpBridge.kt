package com.systemsgo.hex.rdp.native

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Thin JNI bridge to the native aFreeRDP library (see
 * `app/src/main/cpp/systemsgo_jni.c` and `app/src/main/cpp/SETUP.md`).
 *
 * The native `.so` is only present once prebuilt FreeRDP/OpenSSL libraries
 * exist under `app/src/main/cpp/freerdp-prebuilt/<ABI>/` — produced
 * automatically by CI (see `.github/workflows/main.yml`) or manually by
 * following `app/src/main/cpp/SETUP.md`. It cannot be produced in this
 * sandboxed environment (no internet access / NDK toolchain).
 * [isAvailable] safely detects whether the library loaded.
 *
 * DOC FIX: this comment previously claimed the app "transparently falls back
 * to the pure-Kotlin RDP implementation" via a class called
 * `com.systemsgo.hex.rdp.protocol.RdpSessionFactory` — neither of those exists.
 * There is no Kotlin-only RDP implementation anymore (see the class doc on
 * [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter]: "the pure-Kotlin
 * hand-written RDP parser has been removed; FreeRDP is the only supported
 * backend"). The real consumer of [isAvailable] is
 * [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter.connect], which — when
 * `isAvailable` is false — emits a user-facing error explaining that the
 * native library must be built (see SETUP.md) rather than connecting through
 * any fallback engine.
 */
class AFreeRdpBridge {

    companion object {
        private const val TAG = "AFreeRdpBridge"

        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("systemsgo_jni")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.i(TAG, "Native aFreeRDP library not present — RDP connections will fail until " +
                    "it is built. See app/src/main/cpp/SETUP.md.")
                false
            } catch (e: Throwable) {
                Log.w(TAG, "Unexpected error probing native aFreeRDP library", e)
                false
            }
        }

        /**
         * AUDIO-BACKEND FIX: whether the linked native library was built with
         * a real Android audio backend (e.g. OpenSL ES) for the "rdpsnd"
         * (playback) / "audin" (capture) channels — see systemsgo_jni.c's
         * nativeConnect doc comment on FreeRDP_AudioPlayback/AudioCapture for
         * the full explanation of the gap this closes.
         *
         * Previously the "Enable Remote Sound" toggle set FreeRDP_AudioPlayback
         * correctly all the way down to the native layer, but on every build
         * produced by this project's CI the channel would still silently do
         * nothing: the FreeRDP prebuilt is built with WITH_PULSE=OFF and no
         * WITH_OPENSLES equivalent enabled (see .github/workflows/main.yml's
         * "Build FreeRDP prebuilt" step, and app/src/main/cpp/SETUP.md), so
         * there is no audio subsystem for the channel to attach to — the
         * toggle looked like it worked but never produced any sound. Rather
         * than continue to present a setting that quietly does nothing, the
         * UI (see ProtocolOptionsSection's enableSound SpaceSwitch) now
         * disables it and shows an explicit "unsupported" subtitle whenever
         * this returns false.
         *
         * Backed by a compile-time flag (SYSTEMSGO_AUDIO_BACKEND_AVAILABLE in
         * CMakeLists.txt), not a runtime probe — FreeRDP has no public API to
         * ask "was I built with a working audio subsystem?" after the fact.
         * This keeps the door open for a real fix later (build FreeRDP with
         * an OpenSL ES/AAudio subsystem, flip the CMake option to ON) without
         * requiring any further Kotlin/JNI signature changes: the toggle
         * re-enables itself automatically once this returns true.
         */
        val isAudioBackendAvailable: Boolean by lazy {
            if (!isAvailable) {
                false
            } else {
                try {
                    nativeIsAudioBackendAvailable()
                } catch (e: Throwable) {
                    Log.w(TAG, "Unexpected error probing native audio backend support", e)
                    false
                }
            }
        }

        // AUDIO-BACKEND FIX: static — doesn't need a connection handle, just
        // reports how this .so was compiled. See isAudioBackendAvailable doc.
        @JvmStatic
        private external fun nativeIsAudioBackendAvailable(): Boolean

        /**
         * MULTI-MONITOR FEATURE: whether this native build understands the
         * FreeRDP_UseMultimon / FreeRDP_MonitorCount / FreeRDP_MonitorDefArray
         * settings used to declare a multi-monitor layout before connecting
         * (see systemsgo_jni.c's systemsgo_pre_connect). Unlike
         * [isAudioBackendAvailable] this doesn't depend on an optional Android
         * audio subsystem being compiled in — MonitorDefArray is part of
         * core FreeRDP settings present in every FreeRDP 3.x build — so this
         * is expected to be true whenever [isAvailable] is, but is still
         * probed explicitly (rather than assumed) so a future FreeRDP
         * upgrade that removes/renames the setting fails safe: the monitor
         * selector simply hides itself instead of sending a connect request
         * built from settings that no longer exist.
         */
        val isMultiMonitorAvailable: Boolean by lazy {
            if (!isAvailable) false else try {
                nativeIsMultiMonitorAvailable()
            } catch (e: Throwable) {
                Log.w(TAG, "Unexpected error probing native multi-monitor support", e)
                false
            }
        }

        @JvmStatic
        private external fun nativeIsMultiMonitorAvailable(): Boolean

        /**
         * PRINTER-REDIRECT FEATURE: whether this native build's FreeRDP
         * prebuilt has a working printer-redirection backend for the "rdpdr"
         * channel's printer device (MS-RDPEPC). Same shape as
         * [isAudioBackendAvailable] — a compile-time flag
         * (SYSTEMSGO_PRINT_BACKEND_AVAILABLE in CMakeLists.txt), not a runtime
         * probe, since FreeRDP has no public API to ask "was I built with a
         * printer backend?" after the fact.
         *
         * Currently false: this project's FreeRDP prebuilt is built with
         * WITH_CUPS=OFF (see app/src/main/cpp/SETUP.md's printer-redirection
         * section) — CUPS is a desktop/Linux printing library that upstream
         * FreeRDP's printer channel plugin (channels/printer/client) uses to
         * enumerate/spool to real printers, and it isn't a stock part of an
         * Android NDK build. Rather than present a "Redirect Printers" toggle
         * that silently does nothing (the same trap the audio toggle avoided
         * before the OpenSL ES fix), the UI (see ProtocolOptionsSection's
         * enablePrinterRedirect SpaceSwitch) disables it and shows an
         * explicit "unsupported" subtitle whenever this returns false. The
         * Android-side half of this feature (handing a redirected job to
         * Android's own Print Framework — see
         * [com.systemsgo.hex.print.RemotePrintManager]) is fully implemented
         * and ready to consume print data the moment this flips to true.
         */
        val isPrinterBackendAvailable: Boolean by lazy {
            if (!isAvailable) false else try {
                nativeIsPrinterBackendAvailable()
            } catch (e: Throwable) {
                Log.w(TAG, "Unexpected error probing native printer backend support", e)
                false
            }
        }

        @JvmStatic
        private external fun nativeIsPrinterBackendAvailable(): Boolean

        /**
         * WEBCAM-REDIRECT FEATURE: whether this native build's FreeRDP
         * prebuilt has a working camera-redirection backend for the
         * "rdpecam" dynamic channel (MS-RDPECAM). Same shape as
         * [isPrinterBackendAvailable] — a compile-time flag
         * (SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE in CMakeLists.txt), not a runtime
         * probe.
         *
         * Unlike the printer/CUPS gap, this does not need a desktop/Linux
         * library cross-compiled for Android: upstream FreeRDP shipped a
         * native Camera2-NDK backend for "rdpecam" on Android in FreeRDP
         * 3.27.1 (PR #12894), and main.yml now pins that tag with
         * -DCHANNEL_RDPECAM_CLIENT=ON — see systemsgo_jni.c's
         * SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE doc comment for the full
         * reasoning and the "not yet confirmed against a real CI run"
         * caveat. The UI (ProtocolOptionsSection's enableWebcamRedirect
         * toggle) disables the switch and shows an "unsupported" subtitle
         * whenever this returns false, same as the printer toggle.
         */
        val isWebcamBackendAvailable: Boolean by lazy {
            if (!isAvailable) false else try {
                nativeIsWebcamBackendAvailable()
            } catch (e: Throwable) {
                Log.w(TAG, "Unexpected error probing native webcam backend support", e)
                false
            }
        }

        @JvmStatic
        private external fun nativeIsWebcamBackendAvailable(): Boolean

        /**
         * SMARTCARD-REDIRECT FEATURE: whether this native build's FreeRDP
         * prebuilt has a working smart-card redirection backend for the
         * "rdpdr" channel's smartcard device (MS-RDPESC). Same shape as
         * [isPrinterBackendAvailable] — a compile-time flag
         * (SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE in CMakeLists.txt), not a
         * runtime probe.
         *
         * This build's FreeRDP prebuilt is now built with WITH_PCSC=ON,
         * linked against a PCSC-lite (libpcsclite) cross-compiled for
         * Android by main.yml's "Build PCSC-lite prebuilt" step — see
         * systemsgo_jni.c's SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE doc comment for
         * the full reasoning.
         *
         * IMPORTANT: unlike [isPrinterBackendAvailable]/[isWebcamBackendAvailable],
         * `true` here means only that the *channel plumbing* was compiled
         * in — not that a physical card will actually be readable. FreeRDP's
         * smartcard client still needs a live PC/SC resource manager
         * on-device to answer APDUs, normally `pcscd`, which does not run
         * inside a stock Android app sandbox. See
         * [CMakeLists.txt]'s SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE comment for
         * the full explanation. The UI (ProtocolOptionsSection's
         * enableSmartcardRedirect toggle) still disables the switch and
         * shows an "unsupported" subtitle whenever this returns false, same
         * as the printer/webcam toggles — but even when it's usable, treat
         * end-to-end card reads as experimental until an in-app PC/SC
         * resource-manager bridge exists.
         */
        val isSmartcardBackendAvailable: Boolean by lazy {
            if (!isAvailable) false else try {
                nativeIsSmartcardBackendAvailable()
            } catch (e: Throwable) {
                Log.w(TAG, "Unexpected error probing native smartcard backend support", e)
                false
            }
        }

        @JvmStatic
        private external fun nativeIsSmartcardBackendAvailable(): Boolean

        /**
         * CODEC-NEGOTIATION FEATURE: whether this native build's FreeRDP
         * prebuilt has a working H.264 (AVC420/AVC444) decode backend for
         * the RDPGFX graphics pipeline. Same shape as
         * [isPrinterBackendAvailable] — a compile-time flag
         * (SYSTEMSGO_H264_BACKEND_AVAILABLE in CMakeLists.txt), not a runtime
         * probe, since FreeRDP has no public API to ask "was I built with a
         * working H.264 decoder?" after the fact.
         *
         * Currently false: this project's FreeRDP prebuilt is built with
         * neither `-DWITH_OPENH264=ON` nor `-DWITH_FFMPEG=ON` (the latter
         * explicitly OFF — see SETUP.md), so enabling FreeRDP_GfxH264/
         * FreeRDP_GfxAVC444 would advertise a codec this build cannot
         * decode. See systemsgo_jni.c's SYSTEMSGO_H264_BACKEND_AVAILABLE doc
         * comment for the full reasoning, and CMakeLists.txt's for what
         * flipping this to ON requires (an "openh264" cross-compile CI
         * step, the same shape as CUPS/PCSC). Consumed by
         * [connect]'s `codecPreference` param — see
         * [CodecPreference] for how the Advanced Settings UI should use
         * this to grey out "Prefer H.264" when false.
         */
        val isH264BackendAvailable: Boolean by lazy {
            if (!isAvailable) false else try {
                nativeIsH264BackendAvailable()
            } catch (e: Throwable) {
                Log.w(TAG, "Unexpected error probing native H.264 backend support", e)
                false
            }
        }

        @JvmStatic
        private external fun nativeIsH264BackendAvailable(): Boolean

        /**
         * CODEC-NEGOTIATION FEATURE: whether this native build's FreeRDP
         * prebuilt has a working AV1 decode backend for the RDPGFX
         * graphics pipeline. Same shape as [isH264BackendAvailable] above.
         *
         * Currently false, same reasoning (no `-DWITH_AV1=ON` +
         * dav1d/aom prebuilt in this project's CI yet — see
         * systemsgo_jni.c's SYSTEMSGO_AV1_BACKEND_AVAILABLE doc comment).
         *
         * IMPORTANT CAVEAT even once this is true: AV1 is experimental
         * upstream (FreeRDP 3.25+) and, per FreeRDP's own release notes,
         * currently only negotiates against FreeRDP-based servers — a
         * stock Windows RDP host has no AV1 codec to offer, so "Auto" and
         * "Prefer AV1" will still transparently land on H.264 (or standard
         * codecs) against ordinary Windows targets even when this is true.
         * The Advanced Settings UI should surface this as "experimental"
         * rather than implying it will always be used when available.
         */
        val isAv1BackendAvailable: Boolean by lazy {
            if (!isAvailable) false else try {
                nativeIsAv1BackendAvailable()
            } catch (e: Throwable) {
                Log.w(TAG, "Unexpected error probing native AV1 backend support", e)
                false
            }
        }

        @JvmStatic
        private external fun nativeIsAv1BackendAvailable(): Boolean
    }

    /**
     * CODEC-NEGOTIATION FEATURE: the user-facing codec preference passed to
     * [connect]'s `codecPreference` param, mirrored 1:1 (by ordinal) onto
     * systemsgo_jni.c's SYSTEMSGO_CODEC_PREFERENCE_* constants and
     * systemsgo_apply_codec_preference() — see that function's doc comment for
     * exactly what each value enables. This is intentionally an ordinal
     * (not a named int constant) contract between Kotlin and native, the
     * same convention this file already uses for e.g. NativeMonitor's
     * parallel-array marshalling.
     *
     * All four map onto FreeRDP's own, officially-supported RDPGFX
     * capability-exchange mechanism — nothing here bypasses or replaces
     * FreeRDP's negotiation, it only changes what the client is willing to
     * *offer* the server:
     *   - [AUTO] (the default / "Auto (Recommended)"): offer AV1 (if
     *     [isAv1BackendAvailable]) and H.264 (if [isH264BackendAvailable])
     *     together, so FreeRDP's own capability negotiation lands on the
     *     best codec both ends actually support; falls back to standard
     *     RemoteFX/NSCodec automatically if the server understands
     *     neither.
     *   - [PREFER_AV1]: same offer as AUTO (H.264 is still included as an
     *     in-negotiation fallback, since most servers don't speak AV1 yet
     *     — see [isAv1BackendAvailable]'s caveat) — kept as a distinct
     *     option mainly so power users can express intent / for future use
     *     once AV1 support is less experimental server-side.
     *   - [PREFER_H264]: only offers H.264, skipping AV1 even if available
     *     — useful once AV1 exists but a user wants the more mature/widely
     *     compatible codec.
     *   - [DISABLE_MODERN_CODECS]: skips the RDPGFX pipeline entirely,
     *     landing on FreeRDP's classic RemoteFX/NSCodec/bitmap-cache path
     *     — useful for servers with known GFX-pipeline bugs, or to match
     *     legacy/low-power behavior.
     */
    enum class CodecPreference {
        AUTO,
        PREFER_AV1,
        PREFER_H264,
        DISABLE_MODERN_CODECS,
    }

    /**
     * MULTI-MONITOR FEATURE: one monitor in the layout this client declares
     * to the server before connecting — see [connect]'s `monitors` parameter
     * and systemsgo_jni.c's systemsgo_pre_connect (FreeRDP_MonitorDefArray).
     * Coordinates are in the same virtual-desktop pixel space mstsc uses:
     * the primary monitor's top-left is conventionally (0,0), and every
     * other monitor is placed relative to it.
     */
    data class NativeMonitor(
        val id: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val isPrimary: Boolean,
        val orientationDegrees: Int = 0,
        val dpiScaleFactor: Int = 100,
    )

    // Callback channels — bridged from native code into Kotlin Flows that
    // RdpRemoteAdapter (built by RemoteSessionFactory) collects and re-emits
    // through the common RemoteSessionClient surface (frameUpdates / error /
    // sessionState) shared with the VNC and SSH clients.
    val frames = MutableSharedFlow<NativeFrame>(extraBufferCapacity = 4)
    val stateChanges = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    // CLIPBOARD FIX: text the remote Windows session has just placed on its
    // clipboard (MS-RDPECLIP "cliprdr" channel), emitted from native via
    // onNativeClipboardText below. RdpRemoteAdapter collects this and writes
    // it to the Android system clipboard (ClipboardManager). Only plain
    // text (CF_UNICODETEXT) is synchronized — see systemsgo_jni.c's cliprdr
    // callbacks for the full scope note.
    val clipboardTextFromRemote = MutableSharedFlow<String>(extraBufferCapacity = 4)

    // MULTI-MONITOR FEATURE: the layout actually acknowledged for this
    // session — normally just an echo of what connect()'s `monitors`
    // parameter declared, but re-emitted from native (onNativeMonitorLayout)
    // so a live change made via selectMonitor() (which re-sends a
    // MonitorLayout PDU over the "disp" channel — see systemsgo_jni.c's
    // nativeSelectMonitor) is reflected here too, without RdpRemoteAdapter
    // needing to track two separate sources of truth for "what's the
    // current layout".
    val monitorLayout = MutableSharedFlow<List<NativeMonitor>>(extraBufferCapacity = 4, replay = 1)

    // REMOTE-AUDIO FEATURE: decoded PCM audio frames arriving from the
    // remote session's "rdpsnd" (playback) channel, once
    // isAudioBackendAvailable is true. See systemsgo_jni.c's RDPSND
    // ChannelConnected hook for what populates this today, and its doc
    // comment for the exact scope of what's wired vs. pending (real-time
    // PCM callback plumbing needs the exact audio-device SPI field names for
    // whichever FreeRDP prebuilt is linked — see SETUP.md).
    val audioFrames = MutableSharedFlow<NativeAudioFrame>(extraBufferCapacity = 16)

    // REMOTE-AUDIO FEATURE: reported once when the "rdpsnd" (playback) /
    // "audin" (capture) channels connect or disconnect, independent of
    // whether any PCM has actually flowed yet — lets
    // RemoteAudioManager/the UI distinguish "channel didn't even negotiate"
    // (server/profile didn't enable it) from "channel connected, no audio
    // played yet" (see RemoteAudioManager.AudioChannelState).
    val audioChannelState = MutableSharedFlow<AudioChannelEvent>(extraBufferCapacity = 4, replay = 1)

    // CODEC-NEGOTIATION FEATURE (part 3): the codec name actually in use for
    // this session's RDPGFX graphics pipeline, e.g. "H.264 AVC444" or
    // "RemoteFX" — reported once the first surface command arrives, and
    // again on any change (see systemsgo_jni.c's systemsgo_gfx_surface_command
    // doc comment for why this is derived from RDPGFX_SURFACE_COMMAND's
    // codecId rather than RDPGFX_CAPS_CONFIRM_PDU: the caps-confirm PDU
    // only says which capability *version* the server accepted, which can
    // permit more than one codec at once, not which one it actually ends
    // up using). Same MutableSharedFlow(replay = 1) "current state" shape
    // as monitorLayout/audioChannelState above rather than a literal
    // StateFlow, for consistency with this file's existing convention.
    // Null (no replay yet) until the first RDPGFX surface command arrives
    // — i.e. while the connection is still on the classic (non-GFX) path,
    // or before any frame has painted yet.
    val negotiatedCodec = MutableSharedFlow<String>(extraBufferCapacity = 4, replay = 1)

    // XRDP-CAPABILITY-DETECTION FEATURE: mirrors [negotiatedCodec] immediately
    // above — the security protocol ("NLA" / "TLS" / "RDP") FreeRDP's
    // nego_security_connect() actually settled on for this connection,
    // reported once from systemsgo_post_connect() (it cannot change again
    // mid-session — security negotiation only happens once, before
    // PostConnect runs — unlike negotiatedCodec, which can). See
    // RemoteSessionClient.negotiatedSecurityProtocol's doc comment for
    // where the three booleans this is derived from come from.
    val negotiatedSecurityProtocol = MutableSharedFlow<String>(extraBufferCapacity = 4, replay = 1)

    data class NativeAudioFrame(
        val pcm: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    )

    data class AudioChannelEvent(val playbackConnected: Boolean, val captureConnected: Boolean)

    // PRINTER-REDIRECT FEATURE: connect/disconnect of the redirected printer
    // device on the "rdpdr" channel (mirrors audioChannelState above, one
    // boolean instead of a pair since there's only one device direction
    // here). See systemsgo_jni.c's systemsgo_notify_printer_channel_state (wired
    // from systemsgo_on_channel_connected/disconnected's "rdpdr" branch) for
    // what actually feeds this.
    //
    // RDPDR-DEVICE-ANNOUNCE FIX: this is now a true per-device signal, not a
    // channel-level approximation — a small patch applied to FreeRDP's own
    // channels/rdpdr/client/rdpdr_main.c during the CI build (see
    // the "Patch FreeRDP rdpdr for per-device announce result" CI step in .github/workflows/main.yml
    // and .github/workflows/main.yml's "Patch FreeRDP rdpdr for per-device
    // announce result" step) reports each server DR_CORE_DEVICE_ANNOUNCE_RSP
    // (DeviceId + type + ResultCode) straight to systemsgo_jni.c, which now
    // knows for certain whether the *printer* device specifically was
    // accepted — see hctx->printerDeviceAnnounceSeen's doc comment in
    // systemsgo_jni.c. The old "rdpdr up AND this profile requested printer
    // redirect" heuristic is kept only as an automatic fallback (used if a
    // stale cached FreeRDP prebuilt from before this patch existed is ever
    // linked in — printerDeviceAnnounceSeen simply never flips TRUE in that
    // case), so isPrinterBackendAvailable being false still means this
    // simply never turns true, same as before.
    val printerChannelState = MutableSharedFlow<Boolean>(extraBufferCapacity = 4, replay = 1)

    // SMARTCARD-REDIRECT FEATURE (live status): smartcard counterpart to
    // printerChannelState immediately above — same per-device accuracy now
    // (see systemsgo_notify_smartcard_channel_state and
    // hctx->smartcardDeviceAnnounceSeen), gated on this profile's own
    // enableSmartcardRedirect + isSmartcardBackendAvailable instead of the
    // printer toggle. Remember isSmartcardBackendAvailable's own caveat too:
    // even when this flips true, it only means the "rdpdr" smartcard device
    // was announced and accepted by the server — not that a physical reader
    // is present or a card is inserted, since that needs a live PC/SC
    // resource manager this app sandbox doesn't run.
    val smartcardChannelState = MutableSharedFlow<Boolean>(extraBufferCapacity = 4, replay = 1)

    // WEBCAM-REDIRECT FEATURE (live status): connect/disconnect of the
    // "rdpecam" dynamic virtual channel (MS-RDPECAM) — unlike printer/
    // smartcard above, this is an unambiguous device-specific signal (its
    // own dynamically-named channel, not a shared "rdpdr" device), reported
    // straight from systemsgo_on_channel_connected/disconnected's "rdpecam"
    // branch via systemsgo_notify_webcam_channel_state. Only ever turns true
    // when isWebcamBackendAvailable was also true for this build — see that
    // property's doc comment.
    val webcamChannelState = MutableSharedFlow<Boolean>(extraBufferCapacity = 4, replay = 1)

    // MULTITOUCH FEATURE (live status): connect/disconnect of the "rdpei"
    // dynamic virtual channel (MS-RDPEI) — same unambiguous, device-specific
    // shape as webcamChannelState immediately above. Only ever turns true
    // when the server actually accepted the channel this client requested
    // in systemsgo_pre_connect() (FreeRDP_MultiTouchInput = TRUE); a server
    // without MS-RDPEI support leaves this permanently false/never-emitted.
    val multiTouchChannelState = MutableSharedFlow<Boolean>(extraBufferCapacity = 4, replay = 1)

    // PRINTER-REDIRECT FEATURE: one chunk of raw print data the remote
    // session's print spooler sent to the redirected printer device,
    // forwarded from native (onNativePrintJobData). RemotePrintManager
    // spools these by jobId until isFinalChunk, then hands the assembled
    // document to Android's Print Framework.
    val printJobData = MutableSharedFlow<NativePrintJobData>(extraBufferCapacity = 16)

    data class NativePrintJobData(val jobId: Int, val data: ByteArray, val isFinalChunk: Boolean)

    // REMOTEAPP-WINDOWS FEATURE: one window's current state, as reported by
    // a Window State Order PDU on the "rail" virtual channel — see
    // com.systemsgo.hex.session.RemoteAppWindowManager, the consumer of
    // this flow. Emitted from systemsgo_rail_window_state() in systemsgo_jni.c
    // (registered as both WindowCreate and WindowUpdate — see
    // systemsgo_pre_connect()) whenever RemoteApp mode is on and the server
    // supports MS-RDPERP.
    data class NativeRailWindow(
        val windowId: Int,
        val title: String,
        val x: Int, val y: Int, val width: Int, val height: Int,
        val isVisible: Boolean,
        val zOrder: Int,
    )
    val railWindowUpdates = MutableSharedFlow<NativeRailWindow>(extraBufferCapacity = 16)

    // REMOTEAPP-WINDOWS FEATURE: a previously-reported window has closed
    // (MS-RDPERP Window Delete order) — carries just the windowId that's no
    // longer valid.
    val railWindowRemovals = MutableSharedFlow<Int>(extraBufferCapacity = 16)

    // REMOTEAPP-WINDOWS FEATURE (icon decoding): a window's icon bitmap, as
    // decoded natively from a Window Icon / Cached Icon order — see
    // systemsgo_rail_window_icon()/systemsgo_rail_window_cached_icon() in
    // systemsgo_jni.c. RemoteAppWindowManager merges this into the matching
    // RailWindow.icon by windowId; a window with no icon order yet (or
    // whose server never sent one) simply keeps RailWindow.icon == null.
    data class NativeRailWindowIcon(val windowId: Int, val icon: Bitmap)
    val railWindowIcons = MutableSharedFlow<NativeRailWindowIcon>(extraBufferCapacity = 16)

    /**
     * GENERIC-VCHANNEL FEATURE: fires for *every* virtual channel this
     * session opens or closes — disp, cliprdr, rail, rdpsnd, audin, rdpdr,
     * rdpecam, rdpgfx, rdpei, and any name registered via
     * [registerDynamicChannel] — not just ones without their own typed flow
     * above. This is the event stream [com.systemsgo.hex.rdp.channels.RdpChannelPluginRegistry]
     * dispatches to registered [com.systemsgo.hex.rdp.channels.RdpChannelPlugin]s by
     * name; it exists alongside the typed flows above rather than replacing
     * them; existing callers of e.g. [webcamChannelState] are unaffected.
     * See systemsgo_jni.c's systemsgo_notify_channel_lifecycle().
     */
    data class ChannelLifecycleEvent(val name: String, val connected: Boolean)
    val channelLifecycle = MutableSharedFlow<ChannelLifecycleEvent>(extraBufferCapacity = 32)

    /**
     * TLS-TOFU FIX: invoked synchronously from native (systemsgo_jni.c's
     * systemsgo_verify_certificate_ex) whenever FreeRDP's TLS layer could not
     * automatically verify the server's certificate against the system
     * trust store. Must be set by the caller (RdpRemoteAdapter) *before*
     * [connect] is invoked, since a TLS handshake — and therefore a call to
     * this verifier — can happen synchronously inside that call.
     *
     * Return true to accept the certificate for this connection, false to
     * reject it. There is no default implementation here: certificate trust
     * policy (TOFU pinning, matching credentials.acceptSelfSignedCertificate,
     * emitting a user-facing explanation) lives in RdpRemoteAdapter, the same
     * layer that already owns this decision for VNC (VncClient.VncTofuVerifier)
     * and SSH (SshClient.TofuHostKeyRepository). If left unset, certificates
     * are rejected (fail-safe default — see [onNativeCertificateCheck]).
     */
    var certificateVerifier: ((host: String, port: Int, commonName: String, issuer: String, fingerprint: String) -> Boolean)? = null

    /** OUTBOUND-PROXY FEATURE: mirrors FreeRDP's PROXY_TYPE enum ordinal
     * exactly (NONE=0, HTTP=1, SOCKS=2, HTTPS=3) — systemsgo_jni.c's
     * nativeConnect passes `.ordinal` straight through to FreeRDP_ProxyType
     * with no translation table, so this order must never change without
     * updating that file too. SOCKS covers SOCKS4 and SOCKS5 both —
     * FreeRDP's own PROXY_TYPE has no separate SOCKS4/SOCKS5 value; it
     * auto-negotiates.
     *
     * HTTPS-PROXY FEATURE — INVESTIGATION RESULT (checked against FreeRDP
     * 3.27.1, the FREERDP_TAG pinned in main.yml, via
     * libfreerdp/core/proxy.c): stock FreeRDP has **no** PROXY_TYPE_HTTPS.
     * Its PROXY_TYPE enum is only `{ PROXY_TYPE_NONE=0, PROXY_TYPE_HTTP=1,
     * PROXY_TYPE_SOCKS=2, PROXY_TYPE_IGNORE=0xFFFF }`
     * (winpr/include/winpr/settings.h — search FREERDP_SETTINGS_TYPES_H /
     * PROXY_TYPE_HTTP for confirmation on a future FreeRDP bump). Its "HTTP"
     * proxy type (`http_proxy_connect()` in proxy.c) sends a plaintext
     * `CONNECT host:port HTTP/1.1` request directly over the raw/buffered
     * BIO to the proxy socket — it never wraps that BIO in TLS, so it has no
     * way to reach a proxy that itself requires HTTPS (a "secure forward
     * proxy", not to be confused with proxying an HTTPS *destination*, which
     * plain CONNECT already handles regardless of proxy type).
     *
     * This app's HTTPS value (ordinal 3) is therefore NOT a stock FreeRDP
     * value. `systemsgo_jni.c` passes it straight through as-is, exactly like
     * HTTP/SOCKS, but it only does anything useful because
     * the "Patch FreeRDP proxy for HTTPS support" CI step in .github/workflows/main.yml (applied by
     * main.yml's "Patch FreeRDP proxy for HTTPS support" CI step, right
     * after the existing "Patch FreeRDP for Android" step) adds a
     * `case PROXY_TYPE_HTTPS:` arm to proxy.c's `proxy_connect_impl()` that
     * pushes an OpenSSL `BIO_f_ssl()` filter onto the buffered BIO, performs
     * a TLS handshake with the proxy, and then runs the existing
     * `http_proxy_connect()` CONNECT-request logic over that TLS-wrapped
     * BIO instead of the raw one. If that CI patch step ever fails (its
     * anchor regex stops matching a future FreeRDP release), selecting
     * HTTPS here will silently behave like PROXY_TYPE_IGNORE/undefined on
     * the native side — check the CI patch step's own loud failure first,
     * since it is designed to fail the build rather than no-op. */
    enum class ProxyType { NONE, HTTP, SOCKS, HTTPS }

    /**
     * ENTRA-ID-AUTH FEATURE. Mirrors com.systemsgo.hex.data.model.GatewayAuthMode
     * 1:1 (see that enum's doc comment for the full picture and
     * RdpRemoteAdapter's toBridgeGatewayAuthMode() for the mapping), kept as
     * a *separate* enum here the same way ProxyType just above duplicates
     * com.systemsgo.hex.data.model.ProxyType — this file intentionally has
     * no dependency on the data.model package, only on primitives/enums it
     * owns itself.
     *
     * Wiring is implemented in systemsgo_jni.c's nativeConnect: when this is
     * ENTRA_ID, `gatewayBearerToken` is set via the FreeRDP
     * `FreeRDP_GatewayHttpExtAuthBearer` setting (confirmed present on the
     * FreeRDP version this app vendors), which rdg_establish_data_connection()
     * (libfreerdp/core/gateway/rdg.c) picks up to add the Authorization:
     * Bearer header itself. gatewayUsername/gatewayPassword/gatewayDomain
     * are left unset in that branch so FreeRDP's own NTLM/Basic gateway auth
     * never fires alongside the bearer token.
     */
    enum class GatewayAuthMode { PASSWORD, ENTRA_ID }

    data class NativeFrame(
        val x: Int, val y: Int, val width: Int, val height: Int,
        val pixels: IntArray, val fullScreen: Boolean
    )

    private var handle: Long = 0L

    // SERIAL-OVER-NETWORK FEATURE: the live bridge for the current
    // session's serial redirection, when serialRedirectMode isn't
    // LOCAL_DEVICE — null otherwise (including the entire life of any
    // session that doesn't use serial redirection at all). Owned by this
    // class so disconnect()/free() below can tear it down deterministically
    // instead of leaking a background socket/thread past session end. See
    // resolveEffectiveSerialPath() and com.systemsgo.hex.rdp.serial.
    // SerialNetworkBridge's class doc.
    private var serialNetworkBridge: com.systemsgo.hex.rdp.serial.SerialNetworkBridge? = null

    // SERIAL-OVER-NETWORK FEATURE: native handle returned by
    // nativeSerialBridgeOpen() (a pointer to systemsgo_serial_bridge.c's
    // internal bridge struct) — 0L when no serial-over-network bridge is
    // active for the current session. Torn down in disconnect() below via
    // nativeSerialBridgeClose(), mirroring how `handle` itself is torn down.
    private var serialBridgeHandle: Long = 0L
    private var serialLocalServerSocket: android.net.LocalServerSocket? = null

    /**
     * Resolves what [nativeConnect]'s `serialPath` argument should actually
     * be, given the profile's chosen [mode].
     *
     * - LOCAL_DEVICE (the default): unconditionally returns [localPath]
     *   untouched — every existing profile/connection keeps behaving
     *   exactly as before this feature existed.
     * - RAW_TCP / RFC_2217: connects a [com.systemsgo.hex.rdp.serial.
     *   SerialNetworkBridge] to [host]:[port], stands up a local
     *   abstract-namespace [android.net.LocalServerSocket] and hands its
     *   name to [nativeSerialBridgeOpen], which `openpty()`s a PTY pair,
     *   `connect()`s to that socket from native code, and relays the PTY
     *   master <-> local-socket <-> SerialNetworkBridge <-> remote TCP
     *   endpoint (see `systemsgo_serial_bridge.c`'s doc comment for the full
     *   data flow and its caveats). Returns the PTY *slave* path so
     *   `systemsgo_jni.c`'s existing "serial" freerdp_client_add_device_channel
     *   block can open it exactly like a real /dev node. Returns "" (same
     *   as an unset serialPath — the "serial" rdpdr device is simply
     *   skipped) on any connect/negotiate/native-open failure, logging why.
     */
    private fun resolveEffectiveSerialPath(
        enabled: Boolean,
        localPath: String,
        mode: com.systemsgo.hex.data.model.SerialRedirectMode,
        host: String,
        port: Int,
    ): String {
        if (!enabled) return localPath
        if (mode == com.systemsgo.hex.data.model.SerialRedirectMode.LOCAL_DEVICE) return localPath
        if (host.isBlank()) return ""
        tearDownSerialBridge()

        val socketName = "systemsgo-serial-${System.nanoTime()}"
        val bridge = com.systemsgo.hex.rdp.serial.SerialNetworkBridge(mode, host, port)
        serialNetworkBridge = bridge
        if (!bridge.connectRemote()) {
            Log.w("AFreeRdpBridge", "Serial-over-network connect to $host:$port ($mode) failed — skipping serial redirect.")
            tearDownSerialBridge()
            return ""
        }

        return try {
            // Listen on the abstract-namespace socket *before* calling
            // native code, so its connect() below can never race ahead of
            // our accept() — see SerialNetworkBridge.attachLocalPeer's doc.
            val serverSocket = android.net.LocalServerSocket(socketName)
            serialLocalServerSocket = serverSocket
            val acceptThread = Thread({
                try {
                    val peer = serverSocket.accept()
                    bridge.attachLocalPeer(peer)
                } catch (e: java.io.IOException) {
                    Log.w("AFreeRdpBridge", "Serial-over-network local peer accept() failed", e)
                }
            }, "SerialBridge-accept").apply { isDaemon = true; start() }

            val slavePathOut = arrayOfNulls<String>(1)
            val bridgeHandle = nativeSerialBridgeOpen(socketName, bridge, slavePathOut)
            if (bridgeHandle == 0L) {
                Log.w("AFreeRdpBridge", "nativeSerialBridgeOpen failed for $host:$port ($mode) — skipping serial redirect.")
                acceptThread.interrupt()
                tearDownSerialBridge()
                return ""
            }
            serialBridgeHandle = bridgeHandle
            slavePathOut[0] ?: ""
        } catch (e: java.io.IOException) {
            Log.w("AFreeRdpBridge", "Failed to set up serial-over-network local socket for $host:$port ($mode)", e)
            tearDownSerialBridge()
            ""
        }
    }

    private fun tearDownSerialBridge() {
        if (serialBridgeHandle != 0L) {
            nativeSerialBridgeClose(serialBridgeHandle)
            serialBridgeHandle = 0L
        }
        try { serialLocalServerSocket?.close() } catch (e: java.io.IOException) { android.util.Log.d("AFreeRdpBridge", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
        serialLocalServerSocket = null
        serialNetworkBridge?.close()
        serialNetworkBridge = null
    }

    /**
     * USB-REDIRECT FEATURE (Part 3/3): fired exactly once when this
     * session's native [handle] becomes usable for USB redirection (right
     * after a successful [connect]) and exactly once when it stops being
     * usable (right before [disconnect] tears the native session down, or
     * before [free] does if [disconnect] was never called — e.g. the
     * connect-failure cleanup path in `RdpRemoteAdapter.connect()`). A
     * plain callback rather than a direct `UsbRedirectionManager`
     * reference, so this class stays free of any USB-specific dependency —
     * see `RdpRemoteAdapter`'s wiring of this property right after
     * `bridge.init()` for the sole subscriber today.
     *
     * [announceSessionHandle] is idempotent (guarded by
     * [lastAnnouncedSessionHandle]), so calling [disconnect] and then
     * [free] — or racing a concurrent device attach/detach against either —
     * can never fire this twice for the same transition nor skip the
     * pre-disconnect firing.
     */
    var onUsbSessionHandleChanged: ((Long) -> Unit)? = null

    private val sessionHandleAnnounceLock = Any()
    private var lastAnnouncedSessionHandle = 0L

    private fun announceSessionHandle(newHandle: Long) {
        synchronized(sessionHandleAnnounceLock) {
            if (lastAnnouncedSessionHandle == newHandle) return
            lastAnnouncedSessionHandle = newHandle
            onUsbSessionHandleChanged?.invoke(newHandle)
        }
    }

    fun init() {
        handle = nativeInit()
    }

    fun connect(
        host: String, port: Int, username: String, password: String, domain: String,
        width: Int, height: Int, useNla: Boolean,
        gatewayEnabled: Boolean, gatewayHost: String, gatewayPort: Int,
        gatewayUsername: String, gatewayPassword: String, gatewayDomain: String,
        // ENTRA-ID-AUTH FEATURE: see GatewayAuthMode's doc comment just
        // above for the full picture. Defaults keep every existing caller's
        // behavior (PASSWORD mode, gatewayUsername/gatewayPassword as sent
        // today) unchanged.
        //
        // PART 2 STATUS: both parameters are now forwarded to
        // nativeConnect() below — its JNI signature and systemsgo_jni.c both
        // consume `jGatewayAuthMode`/`jGatewayBearerToken` per the
        // GatewayAuthMode doc comment. When ENTRA_ID is selected,
        // gatewayUsername/gatewayPassword/gatewayDomain are left unset on
        // the FreeRDP settings and gatewayBearerToken is set as
        // FreeRDP_GatewayHttpExtAuthBearer instead, so the RDG transport
        // authenticates with the Entra ID token rather than NTLM/Basic.
        gatewayAuthMode: GatewayAuthMode = GatewayAuthMode.PASSWORD,
        gatewayBearerToken: String = "",
        // OUTBOUND-PROXY FEATURE: see systemsgo_jni.c's nativeConnect doc
        // comment on jProxyEnabled for the full picture (how this differs
        // from Gateway just above and from SocksProxyServer.kt). Defaults
        // keep every existing caller's behavior (no proxy) unchanged.
        proxyEnabled: Boolean = false,
        proxyType: ProxyType = ProxyType.SOCKS,
        proxyHost: String = "",
        proxyPort: Int = 1080,
        proxyUsername: String = "",
        proxyPassword: String = "",
        // REMOTEAPP: RAIL (MS-RDPERP) single-published-app mode. See
        // systemsgo_jni.c's nativeConnect for how these map onto
        // FreeRDP_RemoteApplication{Mode,Program,WorkingDir,CmdLine}.
        remoteAppEnabled: Boolean = false,
        remoteAppProgram: String = "",
        remoteAppWorkingDir: String = "",
        remoteAppCmdLine: String = "",
        colorDepth: Int = 32,          // FIX #3: was never passed to native layer
        compressionQuality: Int = 75,  // FIX #4: was never passed to native layer
        performanceMode: Int = 3,      // FIX #8: was silently discarded (UNUSED_PARAMETER)
        ignoreCert: Boolean = false,   // BUG-4 FIX: was always TRUE in C → MITM vulnerability
        // CODEC-NEGOTIATION FEATURE: see [CodecPreference] doc for the full
        // mapping. Defaults to AUTO, which is the "keep negotiation
        // completely automatic by default" requirement — every existing
        // caller that doesn't pass this keeps getting the best codec both
        // ends support with zero UI/config changes.
        codecPreference: CodecPreference = CodecPreference.AUTO,
        // MIC-REDIRECT FEATURE: audio playback (remote → local speaker,
        // MS-RDPEA "rdpsnd") and audio capture (local mic → remote,
        // MS-RDPEAI "audin"). Both simply set an FreeRDP_Audio* setting in
        // systemsgo_jni.c's nativeConnect *before* systemsgo_pre_connect() calls
        // freerdp_client_load_addins() — the same load_addins call already
        // used for the "disp" channel (see LIVE-RESIZE FIX comments) then
        // loads the "rdpsnd"/"audin" client channel plugins automatically
        // based on these flags. See enableWebcamRedirect below for the
        // camera/RDPECAM equivalent (a dynamic channel, wired differently).
        enableSound: Boolean = false,
        enableMicRedirect: Boolean = false,
        // CLIPBOARD FIX: MS-RDPECLIP "cliprdr" channel — see the
        // FreeRDP_RedirectClipboard line in systemsgo_jni.c's nativeConnect for
        // what this actually flips, and the cliprdr callbacks further down
        // in that file for how synchronization itself works once the
        // channel connects.
        enableClipboard: Boolean = true,
        // DRIVE-REDIRECT FEATURE: MS-RDPEFS "rdpdr" device-redirection channel.
        // Same best-effort pattern as the channels above: setting this flag
        // (plus drivePath) simply tells systemsgo_pre_connect()'s existing
        // freerdp_client_load_addins() call to also load "rdpdr" with one
        // registered drive device — see systemsgo_jni.c's nativeConnect for the
        // FreeRDP_DeviceRedirection / freerdp_client_add_device_channel block.
        // If the server doesn't support RDPEFS, the channel simply never
        // opens and the remote session has no "android" drive, same as any
        // other unsupported channel in this file.
        enableDriveRedirect: Boolean = false,
        // Absolute path of the local (Android-side) directory exposed as the
        // virtual drive. Ignored when enableDriveRedirect is false.
        drivePath: String = "",
        // PRINTER-REDIRECT FEATURE: MS-RDPEPC printer redirection, over the
        // same "rdpdr" channel enableDriveRedirect/drivePath above already
        // use, just with a "printer" device instead of a "drive" one. See
        // systemsgo_jni.c's nativeConnect for the
        // freerdp_client_add_device_channel(..., "printer", ...) block this
        // sets up — guarded by SYSTEMSGO_PRINT_BACKEND_AVAILABLE (see
        // isPrinterBackendAvailable's doc for why this build has it off).
        enablePrinterRedirect: Boolean = false,
        // WEBCAM-REDIRECT FEATURE: MS-RDPECAM camera redirection, registered
        // as a *dynamic* virtual channel ("rdpecam") rather than riding
        // "rdpdr" like drive/printer above. See systemsgo_jni.c's nativeConnect
        // for the freerdp_client_add_dynamic_channel(..., "rdpecam", ...)
        // block this sets up — guarded by SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE
        // (see isWebcamBackendAvailable's doc for why this build has it on).
        // Opening the camera itself additionally needs the CAMERA runtime
        // permission granted before this call (see RdpSessionActivity).
        enableWebcamRedirect: Boolean = false,
        // SMARTCARD-REDIRECT FEATURE: MS-RDPESC smart-card redirection, over
        // the same static "rdpdr" channel enableDriveRedirect/
        // enablePrinterRedirect above already use, just with a "smartcard"
        // device instead of "drive"/"printer". See systemsgo_jni.c's
        // nativeConnect for the freerdp_client_add_device_channel(...,
        // "smartcard", ...) block this sets up — guarded by
        // SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE (see
        // isSmartcardBackendAvailable's doc for the caveat that "channel
        // compiled in" does not by itself mean a physical card is readable,
        // since there is no pcscd resource manager running on-device yet).
        enableSmartcardRedirect: Boolean = false,
        // PARALLEL-REDIRECT FEATURE: RDPDR parallel-port redirection, over
        // the same "rdpdr" channel enableDriveRedirect/enablePrinterRedirect/
        // enableSmartcardRedirect above already use, just with a "parallel"
        // device instead of "drive"/"printer"/"smartcard". See
        // systemsgo_jni.c's nativeConnect for the
        // freerdp_client_add_device_channel(..., "parallel", ...) block this
        // sets up — unconditional (no *_BACKEND_AVAILABLE gate), same as
        // enableDriveRedirect, because FreeRDP's parallel-port channel needs
        // no extra desktop library the way printer/smartcard do. Unlike
        // drivePath above, there is no meaningful default: [parallelPath]
        // must point at a real local device node or the channel is skipped
        // even when this is true (mirrors the native drive block's own
        // "skip if path empty" guard).
        enableParallelRedirect: Boolean = false,
        // Absolute path of the local (Android-side) device node exposed as
        // the remote session's parallel port (e.g. "/dev/ttyUSB0"). Ignored
        // when enableParallelRedirect is false or this is blank.
        parallelPath: String = "",
        // SERIAL-REDIRECT FEATURE: RDPDR serial-port redirection (MS-RDPESP),
        // over the same "rdpdr" channel as every other device above, just
        // with a "serial" device instead of "parallel"/"drive"/etc. Same
        // shape/reasoning as enableParallelRedirect/parallelPath
        // immediately above — see systemsgo_jni.c's nativeConnect for the
        // freerdp_client_add_device_channel(..., "serial", ...) block.
        enableSerialRedirect: Boolean = false,
        // Absolute path of the local (Android-side) device node exposed as
        // the remote session's serial port (e.g. "/dev/ttyUSB0",
        // "/dev/ttyACM0"). Ignored when enableSerialRedirect is false or
        // this is blank.
        serialPath: String = "",
        // SERIAL-OVER-NETWORK FEATURE: when [serialRedirectMode] isn't
        // LOCAL_DEVICE, [serialPath] above is ignored and this connect()
        // call instead resolves the effective serial path from a live
        // com.systemsgo.hex.rdp.serial.SerialNetworkBridge talking to
        // [serialNetworkHost]:[serialNetworkPort] — see
        // resolveEffectiveSerialPath() below. See RdpProfile.
        // serialRedirectMode's doc comment for the two network modes
        // (RAW_TCP / RFC_2217) and SerialNetworkBridge's class doc for the
        // "NEXT STEPS" this still needs on the native side before a
        // resolved path can actually be handed to systemsgo_jni.c.
        serialRedirectMode: com.systemsgo.hex.data.model.SerialRedirectMode =
            com.systemsgo.hex.data.model.SerialRedirectMode.LOCAL_DEVICE,
        serialNetworkHost: String = "",
        serialNetworkPort: Int = 2217,
        // MULTI-MONITOR FEATURE: the client-declared monitor layout (see
        // NativeMonitor doc). Empty (the default) means "single monitor,
        // width x height above" — exactly the pre-existing behavior, so
        // every caller that doesn't pass this keeps working unchanged.
        // A non-empty list is only meaningful when isMultiMonitorAvailable
        // is true; systemsgo_jni.c's systemsgo_pre_connect sets
        // FreeRDP_UseMultimon/MonitorCount/MonitorDefArray from it.
        monitors: List<NativeMonitor> = emptyList(),
        // UDP-TRANSPORT FEATURE: MS-RDPEMT ("Multiple Transport Extension") —
        // lets the session move bulk graphics traffic onto UDP (reliable
        // "UDP-FECR" + best-effort "UDP-FECL") alongside the classic TCP
        // channel, when *both* the client and the server support it. Purely
        // additive: TCP remains the control channel regardless, and if the
        // server (or an RD Gateway / firewall in between) doesn't support or
        // allow UDP, FreeRDP transparently falls back to TCP-only — see
        // systemsgo_jni.c's nativeConnect for the FreeRDP_SupportMultitransport
        // / FreeRDP_MultitransportFlags settings this flips. Defaults to
        // false so existing callers/profiles keep today's TCP-only behavior
        // until a caller opts in (e.g. from a future "Performance" setting).
        enableUdpTransport: Boolean = false,
    ): Boolean {
        if (handle == 0L) return false
        // SERIAL-OVER-NETWORK FEATURE: resolve the *effective* serial path
        // up front. LOCAL_DEVICE keeps today's behavior (serialPath passed
        // through untouched); RAW_TCP/RFC_2217 start a SerialNetworkBridge
        // and are meant to hand nativeConnect a locally-backed path that
        // bridge exposes instead — see resolveEffectiveSerialPath()'s doc
        // for exactly what's wired up already vs. still pending (native PTY
        // support is Part 2, tracked in SerialNetworkBridge's class doc).
        val effectiveSerialPath = resolveEffectiveSerialPath(
            enableSerialRedirect, serialPath, serialRedirectMode, serialNetworkHost, serialNetworkPort,
        )
        val connected = nativeConnect(
            handle, host, port, username, password, domain, width, height, useNla,
            gatewayEnabled, gatewayHost, gatewayPort, gatewayUsername, gatewayPassword, gatewayDomain,
            gatewayAuthMode.ordinal, gatewayBearerToken,
            proxyEnabled, proxyType.ordinal, proxyHost, proxyPort, proxyUsername, proxyPassword,
            remoteAppEnabled, remoteAppProgram, remoteAppWorkingDir, remoteAppCmdLine,
            colorDepth, compressionQuality, performanceMode, ignoreCert,
            codecPreference.ordinal,
            enableUdpTransport,
            enableSound, enableMicRedirect, enableClipboard,
            enableDriveRedirect, drivePath,
            enablePrinterRedirect,
            enableWebcamRedirect,
            enableSmartcardRedirect,
            enableParallelRedirect, parallelPath,
            enableSerialRedirect, effectiveSerialPath,
            monitors.map { it.id }.toIntArray(),
            monitors.map { it.x }.toIntArray(),
            monitors.map { it.y }.toIntArray(),
            monitors.map { it.width }.toIntArray(),
            monitors.map { it.height }.toIntArray(),
            monitors.map { it.isPrimary }.toBooleanArray(),
            monitors.map { it.orientationDegrees }.toIntArray(),
            monitors.map { it.dpiScaleFactor }.toIntArray(),
        )
        // USB-REDIRECT FEATURE (Part 3/3): announce *after* nativeConnect
        // returns true — this is the earliest point handle is actually
        // usable for a URBDRC channel registration (nativeSetChannelActive
        // needs a live FreeRDP session, not just an allocated context).
        if (connected) announceSessionHandle(handle)
        return connected
    }

    /**
     * MULTI-MONITOR FEATURE: switch which monitor(s) are active without
     * reconnecting, by re-sending a MonitorLayout PDU over the same "disp"
     * channel [resize] already uses (see systemsgo_jni.c's nativeSelectMonitor).
     * Best-effort exactly like [resize]: false (without throwing) whenever
     * the disp channel never connected or this build predates multi-monitor
     * support ([isMultiMonitorAvailable] is false) — the session just keeps
     * showing its current layout.
     *
     * @param monitorId ignored when [showAll] is true.
     */
    fun selectMonitor(monitorId: Int, showAll: Boolean): Boolean =
        if (handle != 0L) nativeSelectMonitor(handle, monitorId, showAll) else false

    /**
     * AUDIN-CAPTURE FIX: no longer called anywhere in this app — see
     * [com.systemsgo.hex.audio.RemoteAudioManager]'s class doc for the full
     * reasoning. Short version: it fed a PCM stream captured by a Kotlin-side
     * [android.media.AudioRecord] into [nativeSendAudioCapture], which is
     * itself a deliberate permanent no-op (FreeRDP's own OpenSL ES audin
     * backend captures the mic directly — see systemsgo_jni.c's doc comment)
     * — so that AudioRecord accomplished nothing but battery/mic overhead,
     * and possibly competed with FreeRDP's own capture for the same
     * privacy-sensitive source in the same process besides. Kept as a
     * callable binding, not removed, so it mirrors [nativeSendAudioCapture]'s
     * own "safe to still call, does nothing" contract rather than being a
     * live capture path.
     */
    fun sendAudioCapture(pcm: ByteArray) {
        if (handle != 0L) nativeSendAudioCapture(handle, pcm)
    }

    /**
     * CLIPBOARD FIX: pushes locally-copied text (from the Android system
     * clipboard) to the remote session. Best-effort: a no-op if the cliprdr
     * channel never connected (server doesn't support it, clipboard was
     * disabled for this connection, or the channel simply hasn't finished
     * opening yet) — see systemsgo_jni.c's nativeSendClipboardText.
     */
    fun sendClipboardText(text: String) {
        if (handle != 0L) nativeSendClipboardText(handle, text)
    }

    fun sendMouse(x: Int, y: Int, flags: Int) {
        if (handle != 0L) nativeSendMouse(handle, x, y, flags)
    }

    /**
     * MULTITOUCH FEATURE: pushes one MS-RDPEI touch frame (every finger's
     * current contact state, not just one) for the current session. Each of
     * the four arrays must be the same length as [contacts]; entry i is one
     * finger. [TouchAction.DOWN]/[UPDATE]/[UP] map to
     * RDPINPUT_CONTACT_FLAG_DOWN/UPDATE/UP on the native side (see
     * systemsgo_jni.c's nativeSendTouchFrame). Best-effort, same contract as
     * [sendClipboardText]/live-resize: silently does nothing if the "rdpei"
     * channel never connected — the server doesn't support MS-RDPEI, or the
     * channel hasn't finished opening yet.
     */
    fun sendTouchFrame(contacts: List<TouchContact>) {
        if (handle == 0L || contacts.isEmpty()) return
        val ids = IntArray(contacts.size) { contacts[it].contactId }
        val xs = IntArray(contacts.size) { contacts[it].x }
        val ys = IntArray(contacts.size) { contacts[it].y }
        val actions = IntArray(contacts.size) { contacts[it].action.ordinal }
        nativeSendTouchFrame(handle, ids, xs, ys, actions, contacts.size)
    }

    /** One finger's state within a [sendTouchFrame] call. */
    data class TouchContact(val contactId: Int, val x: Int, val y: Int, val action: TouchAction)

    enum class TouchAction { DOWN, UPDATE, UP }

    fun sendKey(scanCode: Int, down: Boolean, extended: Boolean) {
        if (handle != 0L) nativeSendKey(handle, scanCode, down, extended)
    }

    /**
     * TOOLBOX FEATURE (Stage 2): sends one UTF-16 code unit as a
     * KBD_FLAGS_UNICODE keyboard event — see systemsgo_jni.c's
     * nativeSendUnicode for why this (rather than sendKey's scancodes) is
     * required for Arabic/any non-ANSI-layout character. Characters outside
     * the Basic Multilingual Plane (surrogate pairs) are sent as two
     * consecutive events, one per UTF-16 code unit, which is how the RDP
     * Unicode keyboard PDU is designed to carry them.
     */
    fun sendUnicode(codeUnit: Int) {
        if (handle != 0L) nativeSendUnicode(handle, codeUnit)
    }

    /**
     * LIVE-RESIZE FIX: asks the server to resize the remote desktop in-session
     * via the MS-RDPEDISP ("disp") Display Control virtual channel — see
     * systemsgo_jni.c's systemsgo_pre_connect (advertises + loads the channel) and
     * nativeResize (sends the new monitor layout). Returns false, without
     * error, whenever the channel never connected (server doesn't support
     * RDPEDISP) — the session simply keeps its original resolution, exactly
     * like any other best-effort resize request.
     */
    fun resize(width: Int, height: Int): Boolean =
        if (handle != 0L) nativeResize(handle, width, height) else false

    /**
     * REMOTEAPP-WINDOWS FEATURE: local drag/resize, client -> server half —
     * see systemsgo_jni.c's nativeSendRailWindowMove doc comment for exactly
     * what this sends (a single RAIL_WINDOW_MOVE_ORDER) and how it differs
     * from FreeRDP's own X11 client's live-gesture tracking. [left]/[top]/
     * [right]/[bottom] are the window's new position in the same
     * screen-space coordinates as [com.systemsgo.hex.data.model.RailWindow.rect]
     * (right/bottom one-past-the-edge, i.e. `rect.right`/`rect.bottom` as
     * Android's [android.graphics.Rect] already represents them — no extra
     * width/height conversion needed at the call site). Best-effort: a
     * silent no-op if the "rail" channel never connected.
     */
    fun sendRailWindowMove(windowId: Int, left: Int, top: Int, right: Int, bottom: Int) {
        if (handle != 0L) nativeSendRailWindowMove(handle, windowId, left, top, right, bottom)
    }

    fun disconnect() {
        if (handle != 0L) {
            // USB-REDIRECT FEATURE (Part 3/3): must run *before*
            // nativeDisconnect — UsbRedirectionManager.onSessionHandleChanged(0)
            // needs to call nativeSetChannelActive(handle, false) while
            // `handle` is still a live FreeRDP session, not a freed one.
            announceSessionHandle(0L)
            nativeDisconnect(handle)
        }
        // SERIAL-OVER-NETWORK FEATURE: stop the native PTY bridge and the
        // remote TCP/RFC2217 connection (if this session ever started one)
        // regardless of whether `handle` was still live — mirrors the
        // idempotent, always-safe-to-call shape free() below already uses
        // for nativeFree.
        tearDownSerialBridge()
    }

    fun free() {
        if (handle != 0L) {
            // Idempotent no-op if disconnect() already announced 0L — covers
            // callers (e.g. RdpRemoteAdapter's connect-failure/OOM cleanup
            // path) that call free() without ever calling disconnect().
            announceSessionHandle(0L)
            nativeFree(handle)
            handle = 0L
        }
    }

    // ── Called from native code (systemsgo_jni.c) ──────────────────────────────
    @Suppress("unused")
    fun onNativeFrame(x: Int, y: Int, width: Int, height: Int, pixels: IntArray, fullScreen: Boolean) {
        frames.tryEmit(NativeFrame(x, y, width, height, pixels, fullScreen))
    }

    @Suppress("unused")
    fun onNativeState(state: Int) {
        stateChanges.tryEmit(state)
    }

    // XRDP-ERRINFO FIX: native (both nativeConnect's failure path and
    // systemsgo_post_disconnect's Set-Error-Info-PDU capture — see systemsgo_jni.c)
    // only ever hands us FreeRDP's raw ERRCONNECT_*/ERRINFO_* symbol name.
    // RdpErrorMessages.humanize() turns known names into an xrdp-aware,
    // human-readable message; anything it doesn't recognize passes through
    // unchanged, so this can't regress existing behaviour for Windows RDP
    // servers or any error name not yet in that table.
    @Suppress("unused")
    fun onNativeError(message: String) {
        errors.tryEmit(com.systemsgo.hex.rdp.protocol.RdpErrorMessages.humanize(message))
    }

    // CLIPBOARD FIX: called from native (systemsgo_jni.c's
    // systemsgo_cliprdr_server_format_data_response) whenever the remote
    // session's clipboard content has been fetched as CF_UNICODETEXT.
    @Suppress("unused")
    fun onNativeClipboardText(text: String) {
        clipboardTextFromRemote.tryEmit(text)
    }

    // TLS-TOFU FIX: called synchronously from systemsgo_verify_certificate_ex()
    // on the connect thread while freerdp_connect() is still running — this
    // must return quickly and must not suspend. Fails safe (rejects) if no
    // verifier has been configured, e.g. if connect() is somehow reached
    // before RdpRemoteAdapter wires one up.
    @Suppress("unused")
    fun onNativeCertificateCheck(host: String, port: Int, commonName: String, issuer: String, fingerprint: String): Boolean {
        val verifier = certificateVerifier
        if (verifier == null) {
            Log.w(TAG, "onNativeCertificateCheck called with no certificateVerifier set — rejecting $host:$port")
            return false
        }
        return verifier(host, port, commonName, issuer, fingerprint)
    }

    // MULTI-MONITOR FEATURE: called from native whenever the acknowledged
    // monitor layout changes — once right after connect (echoing what was
    // requested), and again any time selectMonitor() succeeds. Parallel
    // arrays mirror the connect()/nativeConnect() calling convention used
    // for the same reason (JNI has no clean way to pass an array of
    // structs) — see systemsgo_jni.c's systemsgo_notify_monitor_layout.
    @Suppress("unused")
    fun onNativeMonitorLayout(
        ids: IntArray, xs: IntArray, ys: IntArray, widths: IntArray, heights: IntArray,
        primaries: BooleanArray, orientations: IntArray, dpiScales: IntArray,
    ) {
        val list = ids.indices.map { i ->
            NativeMonitor(ids[i], xs[i], ys[i], widths[i], heights[i], primaries[i], orientations[i], dpiScales[i])
        }
        monitorLayout.tryEmit(list)
    }

    // REMOTE-AUDIO FEATURE: called from native when the "rdpsnd"/"audin"
    // channels connect or disconnect — see systemsgo_jni.c's ChannelConnected/
    // ChannelDisconnected hooks for RDPSND_CHANNEL_NAME/AUDIN_DVC_CHANNEL_NAME.
    @Suppress("unused")
    fun onNativeAudioChannelState(playbackConnected: Boolean, captureConnected: Boolean) {
        audioChannelState.tryEmit(AudioChannelEvent(playbackConnected, captureConnected))
    }

    // CODEC-NEGOTIATION FEATURE (part 3): called from native
    // (systemsgo_gfx_surface_command / systemsgo_notify_codec_negotiated) once the
    // server's actual per-frame codec choice is known, and again any time it
    // changes mid-session — see [negotiatedCodec]'s doc comment for why this
    // is keyed off RDPGFX_SURFACE_COMMAND rather than the caps-confirm PDU.
    @Suppress("unused")
    fun onNativeCodecNegotiated(codecName: String) {
        negotiatedCodec.tryEmit(codecName)
    }

    // XRDP-CAPABILITY-DETECTION FEATURE: called once from native
    // (systemsgo_post_connect) with whichever of "NLA"/"TLS"/"RDP" FreeRDP's
    // security negotiation actually settled on for this connection — see
    // [negotiatedSecurityProtocol]'s doc comment.
    @Suppress("unused")
    fun onNativeSecurityProtocolNegotiated(protocolName: String) {
        negotiatedSecurityProtocol.tryEmit(protocolName)
    }

    // REMOTE-AUDIO FEATURE: called from native with a decoded PCM frame from
    // the "rdpsnd" channel — see systemsgo_jni.c's audio-device callback doc
    // comment for the current implementation status of this call site.
    @Suppress("unused")
    fun onNativeAudioFrame(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        audioFrames.tryEmit(NativeAudioFrame(pcm, sampleRate, channels, bitsPerSample))
    }

    // PRINTER-REDIRECT FEATURE: called from native (systemsgo_notify_printer_
    // channel_state) whenever "rdpdr"'s connect state changes AND this
    // profile actually requested printer redirect — see printerChannelState's
    // doc comment above for the channel-vs-device-level caveat.
    @Suppress("unused")
    fun onNativePrinterChannelState(connected: Boolean) {
        printerChannelState.tryEmit(connected)
    }

    // SMARTCARD-REDIRECT FEATURE (live status): called from native
    // (systemsgo_notify_smartcard_channel_state) — smartcard counterpart to
    // onNativePrinterChannelState immediately above.
    @Suppress("unused")
    fun onNativeSmartcardChannelState(connected: Boolean) {
        smartcardChannelState.tryEmit(connected)
    }

    // WEBCAM-REDIRECT FEATURE (live status): called from native
    // (systemsgo_notify_webcam_channel_state) whenever the "rdpecam" dynamic
    // channel connects or disconnects — see webcamChannelState's doc
    // comment above for why this one is unambiguous unlike printer/smartcard.
    @Suppress("unused")
    fun onNativeWebcamChannelState(connected: Boolean) {
        webcamChannelState.tryEmit(connected)
    }

    // MULTITOUCH FEATURE (live status): called from native
    // (systemsgo_notify_multitouch_channel_state) whenever the "rdpei" dynamic
    // channel connects or disconnects — see multiTouchChannelState's doc
    // comment above.
    @Suppress("unused")
    fun onNativeMultiTouchChannelState(connected: Boolean) {
        multiTouchChannelState.tryEmit(connected)
    }

    // GENERIC-VCHANNEL FEATURE: called from native
    // (systemsgo_notify_channel_lifecycle) for every channel connect/disconnect
    // — see channelLifecycle's doc comment above.
    @Suppress("unused")
    fun onNativeChannelConnected(name: String) {
        channelLifecycle.tryEmit(ChannelLifecycleEvent(name, connected = true))
    }

    @Suppress("unused")
    fun onNativeChannelDisconnected(name: String) {
        channelLifecycle.tryEmit(ChannelLifecycleEvent(name, connected = false))
    }

    /**
     * GENERIC-VCHANNEL FEATURE: requests one additional named dynamic
     * channel be loaded the next time [connect] runs on this bridge — see
     * systemsgo_jni.c's nativeRegisterDynamicChannel/systemsgoContext::
     * pendingDynamicChannelNames for the underlying mechanism. Must be
     * called *before* [connect] (calling it after connecting queues it for
     * the *next* connect on this handle, not the current session — same
     * "consumed once at connect time" contract as every enableXxx flag
     * [connect] already takes). Only channel names this build's FreeRDP
     * prebuilt actually ships an addin for (e.g. any of "rdpecam", "rdpsnd",
     * "audin", "rdpgfx", "rdpei", "disp" — the ones this file already has
     * dedicated support for above, requestable generically instead, plus
     * any other addin FreeRDP itself ships and this prebuilt was compiled
     * with) will ever actually connect; anything else is a safe no-op that
     * simply never opens, exactly like an unsupported server. This is
     * **not** a way to define a brand-new, app-specific channel protocol —
     * see SETUP.md's "GENERIC-VCHANNEL FEATURE" section.
     *
     * Returns false (without throwing) if [handle] isn't initialized yet,
     * [name] is blank/too long, or the pending table is already full.
     */
    fun registerDynamicChannel(name: String): Boolean =
        if (handle != 0L) nativeRegisterDynamicChannel(handle, name) else false

    // PRINTER-REDIRECT FEATURE: called from native with one chunk of raw
    // print data the remote session's spooler sent to the redirected
    // printer device. isFinalChunk marks the end of one print job (the
    // device's Close IRP) — see RemotePrintManager for how chunks are
    // spooled and handed to Android's Print Framework.
    @Suppress("unused")
    fun onNativePrintJobData(jobId: Int, data: ByteArray, isFinalChunk: Boolean) {
        printJobData.tryEmit(NativePrintJobData(jobId, data, isFinalChunk))
    }

    // REMOTEAPP-WINDOWS FEATURE: called from native (once systemsgo_jni.c wires
    // up the "rail" channel's window-order callbacks — see railWindowUpdates'
    // doc comment) whenever a RAIL window is created or its position/size/
    // title/visibility/z-order changes.
    @Suppress("unused")
    fun onNativeRailWindowState(
        windowId: Int, title: String,
        x: Int, y: Int, width: Int, height: Int,
        isVisible: Boolean, zOrder: Int,
    ) {
        railWindowUpdates.tryEmit(NativeRailWindow(windowId, title, x, y, width, height, isVisible, zOrder))
    }

    // REMOTEAPP-WINDOWS FEATURE: called from native when a RAIL window
    // closes (Window Delete order) — see railWindowRemovals' doc comment.
    @Suppress("unused")
    fun onNativeRailWindowDelete(windowId: Int) {
        railWindowRemovals.tryEmit(windowId)
    }

    // REMOTEAPP-WINDOWS FEATURE (icon decoding): called from native
    // (systemsgo_rail_emit_icon() in systemsgo_jni.c) with a decoded icon's raw
    // ARGB pixels — same packed-int convention onNativeFrame's `pixels`
    // already uses, so Bitmap.createBitmap can consume it directly with no
    // per-pixel conversion here.
    @Suppress("unused")
    fun onNativeRailWindowIcon(windowId: Int, width: Int, height: Int, pixels: IntArray) {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return
        val icon = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        railWindowIcons.tryEmit(NativeRailWindowIcon(windowId, icon))
    }

    // ── Native methods (implemented in systemsgo_jni.c) ─────────────────────────
    private external fun nativeInit(): Long
    private external fun nativeConnect(
        handle: Long, host: String, port: Int, username: String, password: String, domain: String,
        width: Int, height: Int, useNla: Boolean,
        gatewayEnabled: Boolean, gatewayHost: String, gatewayPort: Int,
        gatewayUsername: String, gatewayPassword: String, gatewayDomain: String,
        // ENTRA-ID-AUTH FEATURE: gatewayAuthMode is GatewayAuthMode.ordinal
        // (mirrors the OUTBOUND-PROXY proxyType convention just below —
        // systemsgo_jni.c's nativeConnect switches on the raw ordinal, so this
        // order must never change without updating that file too).
        // gatewayBearerToken is the MSAL access token acquired by
        // GatewayTokenProvider, non-empty only when gatewayAuthMode is
        // ENTRA_ID — see systemsgo_jni.c's nativeConnect for the
        // FreeRDP_GatewayHttpExtAuthBearer this becomes, and
        // AFreeRdpBridge.GatewayAuthMode's doc comment for the full picture.
        gatewayAuthMode: Int, gatewayBearerToken: String,
        // OUTBOUND-PROXY FEATURE: proxyType is ProxyType.ordinal (mirrors
        // FreeRDP's PROXY_TYPE enum — see systemsgo_jni.c's nativeConnect for
        // the FreeRDP_ProxyType/ProxyHostname/ProxyPort/ProxyUsername/
        // ProxyPassword block this maps onto).
        proxyEnabled: Boolean, proxyType: Int, proxyHost: String, proxyPort: Int,
        proxyUsername: String, proxyPassword: String,
        remoteAppEnabled: Boolean, remoteAppProgram: String, remoteAppWorkingDir: String, remoteAppCmdLine: String,
        colorDepth: Int, compressionQuality: Int, performanceMode: Int,
        ignoreCert: Boolean,  // BUG-4 FIX
        // CODEC-NEGOTIATION FEATURE: CodecPreference's ordinal — see
        // systemsgo_jni.c's SYSTEMSGO_CODEC_PREFERENCE_* constants and
        // systemsgo_apply_codec_preference() for how each value is consumed.
        codecPreference: Int,
        // UDP-TRANSPORT FEATURE: see connect()'s enableUdpTransport doc — maps
        // to FreeRDP_SupportMultitransport/FreeRDP_MultitransportFlags in
        // systemsgo_jni.c's nativeConnect.
        enableUdpTransport: Boolean,
        enableSound: Boolean, enableMicRedirect: Boolean,  // MIC-REDIRECT FEATURE
        enableClipboard: Boolean,  // CLIPBOARD FIX
        enableDriveRedirect: Boolean, drivePath: String,  // DRIVE-REDIRECT FEATURE
        enablePrinterRedirect: Boolean,  // PRINTER-REDIRECT FEATURE
        enableWebcamRedirect: Boolean,  // WEBCAM-REDIRECT FEATURE
        enableSmartcardRedirect: Boolean,  // SMARTCARD-REDIRECT FEATURE
        enableParallelRedirect: Boolean, parallelPath: String,  // PARALLEL-REDIRECT FEATURE
        enableSerialRedirect: Boolean, serialPath: String,  // SERIAL-REDIRECT FEATURE
        // MULTI-MONITOR FEATURE: parallel arrays describing each declared
        // monitor (see NativeMonitor / connect()'s `monitors` param). All
        // empty means "single monitor" (pre-existing behavior).
        monitorIds: IntArray, monitorXs: IntArray, monitorYs: IntArray,
        monitorWidths: IntArray, monitorHeights: IntArray,
        monitorPrimary: BooleanArray, monitorOrientations: IntArray, monitorDpiScales: IntArray,
    ): Boolean
    private external fun nativeResize(handle: Long, width: Int, height: Int): Boolean
    // MULTI-MONITOR FEATURE: see AFreeRdpBridge.selectMonitor doc.
    private external fun nativeSelectMonitor(handle: Long, monitorId: Int, showAll: Boolean): Boolean
    // REMOTE-AUDIO FEATURE: see AFreeRdpBridge.sendAudioCapture doc.
    private external fun nativeSendAudioCapture(handle: Long, pcm: ByteArray)
    private external fun nativeSendMouse(handle: Long, x: Int, y: Int, flags: Int)
    private external fun nativeSendTouchFrame(
        handle: Long, contactIds: IntArray, xs: IntArray, ys: IntArray, actions: IntArray, count: Int
    )
    private external fun nativeSendKey(handle: Long, scanCode: Int, down: Boolean, extended: Boolean)
    private external fun nativeSendUnicode(handle: Long, codeUnit: Int)
    // CLIPBOARD FIX: best-effort — see AFreeRdpBridge.sendClipboardText doc.
    private external fun nativeSendClipboardText(handle: Long, text: String)
    // REMOTEAPP-WINDOWS FEATURE: see AFreeRdpBridge.sendRailWindowMove doc.
    private external fun nativeSendRailWindowMove(
        handle: Long, windowId: Int, left: Int, top: Int, right: Int, bottom: Int,
    )
    private external fun nativeDisconnect(handle: Long)

    // SERIAL-OVER-NETWORK FEATURE: implemented in systemsgo_serial_bridge.c
    // (built into the same systemsgo_jni.so, see CMakeLists.txt), not
    // systemsgo_jni.c — see that file's top-of-file doc comment for the full
    // PTY <-> local-socket <-> SerialNetworkBridge data flow.
    //
    // localSocketName: the android.net.LocalServerSocket (abstract
    //   namespace) name this call should connect() to from native code —
    //   the caller must already be listening on it (see
    //   resolveEffectiveSerialPath) before calling this, to avoid a race.
    // kotlinBridge: the live SerialNetworkBridge instance whose
    //   setDtr(Boolean)/setRts(Boolean)/setBaudRate(Int) methods native
    //   code calls (via JNI) when it detects FreeRDP changed them on the
    //   PTY slave — do not rename/re-sign those three methods without
    //   updating systemsgo_serial_bridge.c's GetMethodID calls to match.
    // slavePathOut: a caller-allocated arrayOfNulls<String>(1); on success
    //   this call writes the pty slave path (e.g. "/dev/pts/12") into
    //   slavePathOut[0].
    // Returns an opaque non-zero bridge handle on success (pass to
    // nativeSerialBridgeClose to tear down), or 0L on failure.
    private external fun nativeSerialBridgeOpen(
        localSocketName: String,
        kotlinBridge: com.systemsgo.hex.rdp.serial.SerialNetworkBridge,
        slavePathOut: Array<String?>,
    ): Long

    /** Stops the relay thread and closes the pty/local-socket fds for a handle returned by [nativeSerialBridgeOpen]. Safe to call once per successful open; systemsgo_serial_bridge.c does not guard against a double-close. */
    private external fun nativeSerialBridgeClose(bridgeHandle: Long)
    private external fun nativeFree(handle: Long)
    // GENERIC-VCHANNEL FEATURE: see AFreeRdpBridge.registerDynamicChannel doc
    // and systemsgo_jni.c's nativeRegisterDynamicChannel/systemsgoContext::
    // pendingDynamicChannelNames for the mechanism and its limits.
    private external fun nativeRegisterDynamicChannel(handle: Long, name: String): Boolean
}
