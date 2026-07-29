/*
 * systemsgo_jni.c — JNI bridge between Kotlin (com.systemsgo.hex.rdp.native.AFreeRdpBridge)
 * and the FreeRDP client library (libfreerdp / libfreerdp-client).
 *
 * Compatible: FreeRDP 3.x (tested 3.24.x)
 *
 * Changes vs original:
 *  - Added (void) casts for freerdp_settings_set_* [[nodiscard]] return values (FreeRDP 3.23+)
 *  - Added freerdp_context_new() return value check
 *  - Added PIXEL_FORMAT_BGRA32 pixel-format guard (FreeRDP 3.x uses pixel_format.h)
 *  - Proper NULL check before update callback assignment
 *  - TLS-TOFU FIX: replaced the blanket FreeRDP_IgnoreCertificate on/off
 *    switch with a real VerifyCertificateEx/VerifyChangedCertificateEx
 *    callback pair that delegates the accept/reject/pin decision to Kotlin
 *    (RdpRemoteAdapter's TOFU store, mirroring SshClient/VncClient), so
 *    certificate verification always runs, untrusted certs are logged with
 *    CN/issuer/fingerprint instead of failing silently, and a certificate
 *    that changes between connections is detected instead of silently
 *    re-accepted.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include <freerdp/freerdp.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/channels/channels.h>
#include <freerdp/codec/color.h>
#include <winpr/synch.h>

/* LIVE-RESIZE FIX: MS-RDPEDISP Display Control virtual channel — lets an
 * already-connected session be resized (device rotation / external monitor
 * connect) without a full disconnect+reconnect. freerdp/client/disp.h
 * declares DispClientContext (the channel's client-side API, in particular
 * ->SendMonitorLayout); freerdp/channels/disp.h declares DISP_DVC_CHANNEL_NAME
 * used below to recognize the channel in the ChannelConnected event. */
#include <freerdp/client/disp.h>
#include <freerdp/channels/disp.h>

/* MULTITOUCH FEATURE: MS-RDPEI (Remote Desktop Protocol: Multitouch Input
 * Extension) — the dynamic virtual channel that carries real, multi-contact
 * touch frames to the server, instead of this client collapsing every
 * gesture down to a single synthesized mouse pointer (the pre-existing
 * TOUCHPAD/DIRECT MouseInputMode behaviour — see MouseInputMode.kt — which
 * remains available as a third, single-pointer option). freerdp/client/
 * rdpei.h declares RdpeiClientContext (obtained via ChannelConnectedEvent
 * Args::pInterface, same pattern as DispClientContext/CliprdrClientContext
 * above) and its ->TouchRawEvent send entry point; freerdp/channels/rdpei.h
 * declares RDPEI_DVC_CHANNEL_NAME ("rdpei").
 *
 * NOT VERIFIED AGAINST UPSTREAM SOURCE (same caveat as RDPDR_SVC_CHANNEL_NAME
 * above — this project only vendors a prebuilt FreeRDP + headers pulled at
 * CI time, not a local source checkout to grep against here). The
 * RdpeiClientContext member names used below (TouchRawEvent, maxTouchContacts)
 * reflect FreeRDP 3.x's public rdpei.h as of this writing; if the CI build
 * fails on this block, that confirms the struct shape moved upstream and
 * this is the first place to check — compare against the actual vendored
 * include/freerdp/client/rdpei.h for the FreeRDP version this project's
 * prebuilt pins (see SETUP.md) and adjust the callback signature / flag
 * macro names (RDPINPUT_CONTACT_FLAG_DOWN/UPDATE/UP) to match. */
#include <freerdp/client/rdpei.h>
#include <freerdp/channels/rdpei.h>

/* CLIPBOARD FIX: MS-RDPECLIP clipboard-redirection virtual channel.
 * freerdp/client/cliprdr.h declares CliprdrClientContext (the channel's
 * client-side API — the callback slots we fill in below, plus the
 * Client* functions we call to talk to the server); freerdp/channels/cliprdr.h
 * declares CLIPRDR_SVC_CHANNEL_NAME plus the PDU structs (CLIPRDR_FORMAT_LIST,
 * CLIPRDR_FORMAT_DATA_REQUEST/RESPONSE, etc.) used by those callbacks. */
#include <freerdp/client/cliprdr.h>
#include <freerdp/channels/cliprdr.h>

/* REMOTEAPP-WINDOWS FEATURE: MS-RDPERP "rail" static virtual channel —
 * multi-window RAIL support (see the REMOTEAPP FIX comment further down in
 * this file, which documents this as the one deliberate scope gap left by
 * the initial RemoteApp fix). freerdp/client/rail.h declares
 * RailClientContext (the channel's client-side API — obtained the same way
 * as CliprdrClientContext/DispClientContext, via ChannelConnectedEventArgs::
 * pInterface, see systemsgo_on_channel_connected); freerdp/channels/rail.h
 * declares RAIL_SVC_CHANNEL_NAME.
 *
 * VERIFIED AGAINST UPSTREAM SOURCE (FreeRDP 3.27.1 / master, since this
 * project only vendors prebuilt binaries + headers pulled at CI time, not a
 * local source checkout to grep — see systemsgo_rail_window_state()'s doc
 * comment below for exactly what was checked and where):
 *  - Per-window Window Create/Update/Delete/Icon orders do NOT arrive via
 *    RailClientContext callbacks or a PubSub event — they arrive via
 *    instance->context->update->window (an rdpWindowUpdate*), a plain
 *    function-pointer struct (WindowCreate/WindowUpdate/WindowDelete/
 *    WindowIcon/WindowCachedIcon/...) that every FreeRDP client (xfreerdp's
 *    xf_rail_init(), etc.) fills in once, unconditionally, in its pre-connect
 *    setup — see systemsgo_pre_connect() below. This struct exists on
 *    rdpContext->update regardless of whether the "rail" channel ever opens;
 *    the callbacks simply never fire if the server doesn't send window
 *    orders (RemoteApplicationMode off, or server has no RAIL support).
 *  - RailClientContext (obtained on ChannelConnected, same as
 *    dispContext/cliprdrContext) is FreeRDP's *send* path (ClientActivate,
 *    ClientSystemCommand, ClientWindowMove, ...) — not used for receiving
 *    window state. It's still recorded below (hctx->railContext) for
 *    parity with dispContext/cliprdrContext and as the natural extension
 *    point for a future "send window activate/move/close back to server"
 *    feature; today's fix is receive-only (window creation/move/resize/
 *    show/hide/title -> Kotlin), matching what was actually asked for. */
#include <freerdp/client/rail.h>
#include <freerdp/channels/rail.h>
/* CODEC-NEGOTIATION FEATURE (part 3): RdpgfxClientContext / RDPGFX_SURFACE_COMMAND
 * / RDPGFX_CODECID_* — see systemsgo_gfx_surface_command()'s doc comment below for
 * why the SurfaceCommand hook, not RdpgfxClientContext::CapsConfirm, is where this
 * build reports the actually-negotiated codec to Kotlin. */
#include <freerdp/client/rdpgfx.h>
#include <freerdp/channels/rdpgfx.h>

/* LIVE-CHANNEL-STATUS FEATURE: MS-RDPEFS "rdpdr" static device-redirection
 * channel — already used unconditionally further down in nativeConnect for
 * drive/printer/smartcard device registration (freerdp_client_add_device_channel),
 * but this file never needed the channel's *name* as a compile-time constant
 * until now (ChannelConnected/ChannelDisconnected only started needing to
 * recognize "rdpdr" specifically once printer/smartcard live-status
 * reporting was added — see hctx->rdpdrChannelConnected's doc comment).
 * freerdp/channels/rdpdr.h declares RDPDR_SVC_CHANNEL_NAME, same pattern as
 * RDPSND_CHANNEL_NAME/AUDIN_DVC_CHANNEL_NAME from freerdp/channels/rdpsnd.h
 * /audin.h above.
 *
 * NOT VERIFIED AGAINST UPSTREAM SOURCE (unlike the RAIL_SVC_CHANNEL_NAME
 * include-block comment above, which documents an actual source check) —
 * this project only vendors a prebuilt FreeRDP + its headers pulled at CI
 * time, not a local source checkout to grep. RDPDR_SVC_CHANNEL_NAME = "rdpdr"
 * is FreeRDP's standard, long-stable public macro for this channel name, but
 * if a CI build ever fails on this specific include/symbol, that confirms
 * the macro moved or was renamed upstream and this line is the first place
 * to check — falling back to the literal string "rdpdr" (same style already
 * used for "rdpecam" below, which has no such macro at all) is the correct
 * fix and requires no other change here. */
#include <freerdp/channels/rdpdr.h>

/* REMOTE-AUDIO FEATURE: MS-RDPEA "rdpsnd" (playback) / MS-RDPEAI "audin"
 * (capture) virtual channels. Both are *dynamic* virtual channels reported
 * through the same ChannelConnected/ChannelDisconnected PubSub events
 * already used for "disp" above — see systemsgo_on_channel_connected/
 * disconnected further down. freerdp/channels/rdpsnd.h declares
 * RDPSND_CHANNEL_NAME; freerdp/channels/audin.h declares
 * AUDIN_DVC_CHANNEL_NAME — those two are all this file actually needs.
 *
 * BUILD FIX: freerdp/client/rdpsnd.h and freerdp/client/audin.h were
 * previously assumed (per an earlier version of this comment) to declare
 * client-context types "RdpsndClientContext"/"AudinClientContext" the same
 * way freerdp/client/disp.h declares DispClientContext. They don't —
 * FreeRDP's real client/rdpsnd.h only exposes the *device backend* SPI
 * (rdpsndDevicePlugin, for authoring an audio output backend) and
 * client/audin.h has no client-context type at all, so those two identifiers
 * don't exist anywhere in FreeRDP's headers and failed to compile. See the
 * rdpsndContext/audinContext field comment in systemsgoContext below for how
 * that's handled (plain void*, since this file only NULL-checks them).
 *
 * SCOPE: this bridge records the channel context pointers and reports
 * connect/disconnect to Kotlin (AFreeRdpBridge.onNativeAudioChannelState) so
 * the UI can accurately show "audio channel connected" instead of just
 * "setting was sent". Actual PCM playback/capture happens inside FreeRDP's
 * own registered audio subsystem (see SYSTEMSGO_AUDIO_BACKEND_AVAILABLE above)
 * — as of the REAL-PCM FIX this is FreeRDP's built-in Android OpenSL ES
 * backend (-DWITH_OPENSLES=ON in .github/workflows/main.yml), so no custom
 * Android-side PCM sink needs to be wired through these contexts' device-
 * callback surface at all: bypassing FreeRDP's own subsystem that way was
 * considered and deliberately rejected in favor of using the backend
 * FreeRDP already ships, which needs no per-revision device-SPI struct
 * field guessing. See SETUP.md's "REAL-PCM FIX" section for the full
 * rationale. */
#include <freerdp/client/rdpsnd.h>
#include <freerdp/channels/rdpsnd.h>
#include <freerdp/client/audin.h>
#include <freerdp/channels/audin.h>

#ifndef PIXEL_FORMAT_BGRA32
#define PIXEL_FORMAT_BGRA32 PIXEL_FORMAT_BGRA32_VER
#endif

/* AUDIO-BACKEND FIX: whether the FreeRDP prebuilt this file is linked
 * against was itself built with a working Android audio subsystem for the
 * "rdpsnd"/"audin" channels (e.g. an OpenSL ES or AAudio client audio
 * backend). This is NOT something systemsgo_jni.c or CMakeLists.txt can detect
 * at build time by inspecting the prebuilt .so — FreeRDP has no exported
 * symbol or version field for "which audio subsystems were compiled in" —
 * so it is a manually-maintained compile flag, set from CMakeLists.txt's
 * SYSTEMSGO_AUDIO_BACKEND_AVAILABLE option.
 *
 * REAL-PCM FIX: now ON by default — .github/workflows/main.yml's "Build
 * FreeRDP prebuilt" step (both `build` and `release` jobs) passes
 * -DWITH_OPENSLES=ON, which compiles FreeRDP's own Android OpenSL ES
 * backend (channels/rdpsnd/client/opensles/, channels/audin/client/opensles/)
 * into the prebuilt this file links against. rdpsnd_process_connect() picks
 * "opensles" automatically the moment a server opens the rdpsnd channel —
 * no code in this file plays or captures PCM directly; see SETUP.md's
 * "REAL-PCM FIX" section for the full explanation. If the prebuilt is ever
 * rebuilt without that flag (or an older cached prebuilt is used), flip the
 * CMakeLists.txt option back to OFF to match.
 *
 * Consumed by nativeIsAudioBackendAvailable() below, which
 * AFreeRdpBridge.isAudioBackendAvailable calls to decide whether to show the
 * "Enable Remote Sound" toggle as usable or to grey it out with an
 * "unsupported" label (see Components.kt's ProtocolOptionsSection). The
 * FreeRDP_AudioPlayback/FreeRDP_AudioCapture settings block in nativeConnect
 * further down is deliberately unconditional either way — this flag only
 * changes what the UI *offers*, not the plumbing itself. */
#ifndef SYSTEMSGO_AUDIO_BACKEND_AVAILABLE
#define SYSTEMSGO_AUDIO_BACKEND_AVAILABLE 0
#endif

/* PRINTER-REDIRECT FEATURE: MS-RDPEPC printer redirection, registered on the
 * same "rdpdr" device-redirection channel DRIVE-REDIRECT FEATURE already uses
 * (see freerdp_client_add_device_channel() further down in nativeConnect),
 * just with a "printer" device instead of a "drive" one.
 *
 * Whether this build's FreeRDP prebuilt actually has a working printer
 * backend for that device is NOT detectable at build time by inspecting the
 * prebuilt .so — same reasoning as SYSTEMSGO_AUDIO_BACKEND_AVAILABLE above — so
 * this is a manually-maintained compile flag, set from CMakeLists.txt's
 * SYSTEMSGO_PRINT_BACKEND_AVAILABLE option.
 *
 * Currently OFF: this project's "Build FreeRDP prebuilt" CI step builds with
 * -DWITH_CUPS=OFF (see SETUP.md's printer-redirection section, which already
 * documents this alongside the smartcard/PCSC gap) — CUPS is the desktop/
 * Linux printing library upstream FreeRDP's printer channel plugin
 * (channels/printer/client) uses to enumerate/spool to real printers, and
 * isn't a stock part of an Android NDK build. Unlike the audio gap (closed by
 * flipping on FreeRDP's own OpenSL ES backend — see REAL-PCM FIX above),
 * there's no upstream non-CUPS printer backend to flip on the same way:
 * closing this gap for real needs either (a) building CUPS for Android
 * alongside FreeRDP for every ABI, or (b) a custom printer-channel backend
 * that skips CUPS's filter chain and hands raw print data straight to
 * Kotlin — which is exactly what com.systemsgo.hex.print.RemotePrintManager on
 * the Kotlin side is already written to consume the moment such a backend
 * exists (see its class doc for the "nothing here needs to change" note).
 *
 * Consumed by nativeIsPrinterBackendAvailable() below, which
 * AFreeRdpBridge.isPrinterBackendAvailable calls to decide whether to show
 * the "Redirect Printers" toggle as usable or to grey it out with an
 * "unsupported" label (see Components.kt's ProtocolOptionsSection) — same UX
 * pattern as SYSTEMSGO_AUDIO_BACKEND_AVAILABLE. The freerdp_client_add_device_channel()
 * call in nativeConnect further down is gated on this flag (unlike the
 * always-unconditional Audio/RedirectClipboard settings above) precisely
 * because, unlike those, there is currently no addin in this prebuilt for
 * FreeRDP to load for a "printer" device at all — registering the device
 * anyway would just be dead configuration. */
#ifndef SYSTEMSGO_PRINT_BACKEND_AVAILABLE
#define SYSTEMSGO_PRINT_BACKEND_AVAILABLE 0
#endif

/* WEBCAM-REDIRECT FEATURE: MS-RDPECAM camera redirection, registered as a
 * *dynamic* virtual channel (unlike printer/drive, which ride the static
 * "rdpdr" channel) via freerdp_client_add_dynamic_channel() further down in
 * nativeConnect — the same helper FreeRDP's own command-line client uses to
 * turn a "/dvc:rdpecam" argument into a registered dynamic channel.
 *
 * Whether this build's FreeRDP prebuilt actually has a working camera
 * backend for "rdpecam" is NOT detectable at build time by inspecting the
 * prebuilt .so — same reasoning as SYSTEMSGO_PRINT_BACKEND_AVAILABLE above —
 * so this is a manually-maintained compile flag, set from CMakeLists.txt's
 * SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE option.
 *
 * Unlike the printer/CUPS gap, this one does not need any extra desktop/
 * Linux library cross-compiled for Android: upstream FreeRDP shipped a
 * native Camera2-NDK capture backend for "rdpecam" specifically for
 * Android in PR #12894, first released in FreeRDP 3.27.1. main.yml pins
 * FREERDP_TAG=3.27.1 and passes -DCHANNEL_RDPECAM_CLIENT=ON, so this
 * defaults to ON to match (see CMakeLists.txt's option doc for the
 * "not yet confirmed against a real CI run" caveat).
 *
 * Consumed by nativeIsWebcamBackendAvailable() below, which
 * AFreeRdpBridge.isWebcamBackendAvailable calls to decide whether to show
 * the "Redirect Webcam" toggle as usable or to grey it out — same UX
 * pattern as isPrinterBackendAvailable. The freerdp_client_add_dynamic_channel()
 * call in nativeConnect further down is gated on this flag for the same
 * reason the printer block is gated on SYSTEMSGO_PRINT_BACKEND_AVAILABLE:
 * registering a dynamic channel FreeRDP has no addin for would just be
 * dead configuration. */
#ifndef SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE
#define SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE 0
#endif

/* SMARTCARD-REDIRECT FEATURE: MS-RDPESC smart-card redirection, registered
 * on the same static "rdpdr" device-redirection channel as drive/printer
 * above (see freerdp_client_add_device_channel() further down in
 * nativeConnect), just with a "smartcard" device instead of "drive"/
 * "printer".
 *
 * Whether this build's FreeRDP prebuilt actually has a working smartcard
 * backend for that device is NOT detectable at build time by inspecting the
 * prebuilt .so — same reasoning as SYSTEMSGO_PRINT_BACKEND_AVAILABLE above —
 * so this is a manually-maintained compile flag, set from CMakeLists.txt's
 * SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE option.
 *
 * Consumed by nativeIsSmartcardBackendAvailable() below, which
 * AFreeRdpBridge.isSmartcardBackendAvailable calls to decide whether to show
 * the "Redirect Smart Card" toggle as usable or to grey it out with an
 * "unsupported" label — same UX pattern as isPrinterBackendAvailable. The
 * freerdp_client_add_device_channel() call in nativeConnect further down is
 * gated on this flag for the same reason the printer block is gated on
 * SYSTEMSGO_PRINT_BACKEND_AVAILABLE: registering a device FreeRDP has no addin
 * for would just be dead configuration.
 *
 * IMPORTANT CAVEAT this flag does NOT cover, see CMakeLists.txt's
 * SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE doc comment for the full explanation:
 * even when this is 1 (libpcsclite linked into the FreeRDP prebuilt and the
 * "rdpdr" smartcard device registered below), an inserted card still needs
 * an actual PC/SC resource manager on-device to answer APDUs — normally
 * pcscd, which is not present in a stock Android app sandbox. This flag
 * only reflects whether the *channel plumbing* compiled in, not whether a
 * physical reader will actually work end-to-end. */
#ifndef SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE
#define SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE 0
#endif

/* CODEC-NEGOTIATION FEATURE: whether this build's FreeRDP prebuilt has a
 * working H.264 (AVC420/AVC444) decode backend for the RDPGFX graphics
 * pipeline. Same shape as SYSTEMSGO_PRINT_BACKEND_AVAILABLE above — a
 * manually-maintained compile flag, set from CMakeLists.txt's
 * SYSTEMSGO_H264_BACKEND_AVAILABLE option, because there is no way to detect
 * "was this .so built with a real H.264 decoder" by inspecting it at
 * runtime.
 *
 * Consumed by nativeIsH264BackendAvailable() below (AFreeRdpBridge.
 * isH264BackendAvailable) for the codec-preference UI, and by
 * systemsgo_apply_codec_preference() further down in nativeConnect, which is
 * what actually flips FreeRDP_GfxH264/FreeRDP_GfxAVC444/
 * FreeRDP_GfxAVC444v2 on. Advertising a codec this build cannot decode
 * would make the server pick it and every frame would then fail to
 * decode — see CMakeLists.txt's SYSTEMSGO_H264_BACKEND_AVAILABLE doc comment
 * for the full reasoning (same "advertise-without-a-decoder" trap
 * SYSTEMSGO_PRINT_BACKEND_AVAILABLE/SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE avoid for
 * their own channels). */
#ifndef SYSTEMSGO_H264_BACKEND_AVAILABLE
#define SYSTEMSGO_H264_BACKEND_AVAILABLE 0
#endif

/* CODEC-NEGOTIATION FEATURE: whether this build's FreeRDP prebuilt has a
 * working AV1 decode backend for the RDPGFX graphics pipeline. Same shape
 * as SYSTEMSGO_H264_BACKEND_AVAILABLE just above.
 *
 * AV1 is experimental upstream (added in FreeRDP 3.25) and, per FreeRDP's
 * own release notes, "currently works only with FreeRDP based servers" —
 * it will not be offered by a stock Windows RDP host, so even with this
 * flag on, AUTO/PREFER_AV1 will fall through to H.264 (or standard
 * RemoteFX/NSCodec) against ordinary Windows targets. See CMakeLists.txt's
 * SYSTEMSGO_AV1_BACKEND_AVAILABLE doc comment for the full caveat. The exact
 * settings key AV1 negotiation uses — "GfxCodecAV1" — is now confirmed
 * against FreeRDP's own merged PR #12527 (see
 * systemsgo_apply_codec_preference()'s doc comment below for the source);
 * systemsgo_apply_codec_preference() still sets it by name via
 * freerdp_settings_set_value_for_name() rather than a generated enum
 * constant (this project only clones FreeRDP source in CI, no local
 * checkout to pull the header from), which fails safe (logs and continues)
 * instead of a hard compile error if a future tag ever renames it again. */
#ifndef SYSTEMSGO_AV1_BACKEND_AVAILABLE
#define SYSTEMSGO_AV1_BACKEND_AVAILABLE 0
#endif

/* CODEC-NEGOTIATION FEATURE: mirrors AFreeRdpBridge.CodecPreference's four
 * values (Kotlin passes the ordinal straight through as jCodecPreference in
 * nativeConnect). See systemsgo_apply_codec_preference() below for how each
 * value maps onto FreeRDP's GFX settings. */
#define SYSTEMSGO_CODEC_PREFERENCE_AUTO             0
#define SYSTEMSGO_CODEC_PREFERENCE_PREFER_AV1       1
#define SYSTEMSGO_CODEC_PREFERENCE_PREFER_H264      2
#define SYSTEMSGO_CODEC_PREFERENCE_DISABLE_MODERN   3

#define TAG "systemsgo_jni"

/* Portable secure-zero helper.
 * explicit_bzero() is NOT declared on Android NDK below __ANDROID_API__ 28 —
 * Bionic only added it in API 28, while this project builds against
 * android-26 (see ANDROID_PLATFORM in the CI workflow), which is what
 * caused "call to undeclared function 'explicit_bzero'" on arm64-v8a.
 * A plain memset() can legally be eliminated by the compiler as a dead
 * store once it proves the buffer isn't read again afterward, so instead
 * we zero through a volatile pointer: writes through a volatile pointer
 * are required to be observable and cannot be optimized away, giving the
 * same guarantee explicit_bzero() would without depending on libc version. */
static void systemsgo_secure_bzero(void *buf, size_t len) {
    volatile unsigned char *p = (volatile unsigned char *)buf;
    while (len--) {
        *p++ = 0;
    }
}
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* GENERIC-VCHANNEL FEATURE: bounds for systemsgoContext::pendingDynamicChannelNames
 * — see that field's doc comment. 16 is generous (this file itself only ever
 * special-cases about a dozen channels total); 32 bytes matches FreeRDP's own
 * channel-name limit (CHANNEL_NAME_LEN + NUL, see freerdp/settings.h) with a
 * little headroom. */
#define SYSTEMSGO_MAX_PENDING_DYNAMIC_CHANNELS 16
#define SYSTEMSGO_DYNAMIC_CHANNEL_NAME_MAX 32

/* MULTI-MONITOR FEATURE: a single client-declared monitor, as received from
 * AFreeRdpBridge.connect()'s parallel-array calling convention (see
 * Java_..._nativeConnect below). Named distinctly from FreeRDP's own
 * rdpMonitor (freerdp/settings.h) so it's unambiguous which one is which
 * when both appear in the same function. Declared ahead of systemsgoContext
 * since that struct embeds a pointer to this one. */
typedef struct
{
    UINT32 id;
    INT32 x, y;
    UINT32 width, height;
    BOOL isPrimary;
    UINT32 orientationDegrees;
    UINT32 dpiScaleFactor;
} RDP_MONITOR_ANDROID;

/* REMOTEAPP-WINDOWS FEATURE: one decoded RAIL icon bitmap, cached by
 * (cacheId, cacheEntry) — mirrors client/X11/xf_rail.c's xfRailIcon, but
 * stores plain PIXEL_FORMAT_BGRA32 pixels (matching systemsgo_on_frame's
 * pixel format, so the same raw memcpy-into-jintArray trick produces an
 * Android Bitmap.Config.ARGB_8888-compatible int[] with no per-pixel
 * conversion) instead of X11's xfRailIcon::data (an X _NET_WM_ICON-shaped
 * long[] with a width/height header baked into the first two elements,
 * which has no Android equivalent). Declared ahead of systemsgoContext since
 * that struct embeds the cache array + scratch slot inline. */
typedef struct
{
    BYTE* argb;    /* width*height*4 bytes, PIXEL_FORMAT_BGRA32, or NULL if never decoded */
    UINT32 width;
    UINT32 height;
} systemsgoRailIcon;

typedef struct
{
    rdpContext context;
    JavaVM* jvm;
    jobject bridgeObjGlobalRef;
    jmethodID onFrameMethod;
    jmethodID onStateMethod;
    jmethodID onErrorMethod;
    /* TLS-TOFU FIX: certificate accept/reject/pin decisions now live entirely
     * in Kotlin (AFreeRdpBridge.certificateVerifier -> RdpRemoteAdapter's TOFU
     * store), mirroring how SshClient/VncClient already handle host-key and
     * certificate pinning. Native's only job is to hand FreeRDP's verified
     * certificate fields to onNativeCertificateCheck() and relay the boolean
     * answer back — see systemsgo_verify_certificate_ex() below. */
    jmethodID onCertCheckMethod;
    /* LIVE-RESIZE FIX: set from systemsgo_on_channel_connected() once the "disp"
     * virtual channel comes up (only happens if the server actually supports
     * RDPEDISP — see systemsgo_pre_connect). NULL until then, and reset to NULL
     * on disconnect via systemsgo_on_channel_disconnected(); nativeResize()
     * below treats NULL as "live resize unavailable for this session" rather
     * than failing loudly, since plenty of real-world RDP servers (older
     * Windows, most third-party servers) simply don't implement this channel.
     *
     * THREAD-SAFETY FIX: written from FreeRDP's channel-manager thread
     * (systemsgo_on_channel_connected/disconnected) and read from the JNI/Kotlin
     * thread (nativeResize, nativeSelectMonitor) in a check-then-use pattern.
     * A plain pointer isn't enough here — the disconnect thread can null it
     * (or FreeRDP can internally free the object it points to) between the
     * NULL-check and the SendMonitorLayout() call on the other thread,
     * producing exactly the UAF/SIGSEGV class of bug this field used to be
     * exposed to. Guarded by dispLock; always read/write dispContext with
     * that lock held, and take a local snapshot before calling into it so
     * the lock isn't held across the (potentially blocking) channel send. */
    DispClientContext* dispContext;
    CRITICAL_SECTION dispLock;
    BOOL dispLockInitialized;

    /* MULTITOUCH FEATURE: mirrors dispContext immediately above in every
     * respect (same NULL-until-ChannelConnected lifecycle, same
     * check-then-use race between the touch-sending thread and
     * systemsgo_on_channel_disconnected on the channel-manager thread, same
     * critical-section fix) — set from systemsgo_on_channel_connected() once
     * the "rdpei" dynamic channel comes up, which only happens when
     * FreeRDP_MultiTouchInput was set TRUE in systemsgo_pre_connect() AND the
     * server actually opens the channel. NULL means nativeSendTouchFrame()
     * has nothing to send to and silently no-ops for that call — the same
     * graceful-degradation contract nativeResize() already has for
     * dispContext, since plenty of RDP servers (older Windows, most
     * third-party servers) don't implement RDPEI at all. */
    RdpeiClientContext* rdpeiContext;
    CRITICAL_SECTION rdpeiLock;
    BOOL rdpeiLockInitialized;

    /* CLIPBOARD FIX: mirrors dispContext above — set from
     * systemsgo_on_channel_connected() once the "cliprdr" static channel comes
     * up (only happens if enableClipboard was TRUE in nativeConnect AND the
     * server supports RDPECLIP), reset to NULL on disconnect. NULL means
     * "no clipboard sync available for this session", checked by
     * nativeSendClipboardText() and by the callbacks below. */
    CliprdrClientContext* cliprdrContext;
    /* Called from systemsgo_cliprdr_server_format_data_response() to hand the
     * remote clipboard's current plain-text content to Kotlin. */
    jmethodID onClipboardTextMethod;
    /* Most recently copied local (Android) clipboard text, kept as
     * NUL-terminated UTF-16LE (the wire format CF_UNICODETEXT requires) so
     * systemsgo_cliprdr_server_format_data_request() can answer the server
     * immediately without re-encoding on every request. NULL until the user
     * has copied something since this session connected. Guarded by
     * clipboardLock because it's written from the JNI/Kotlin thread
     * (nativeSendClipboardText) and read from the FreeRDP channel thread
     * (the cliprdr callbacks). */
    CRITICAL_SECTION clipboardLock;
    BOOL clipboardLockInitialized;
    WCHAR* localClipboardTextW;
    size_t localClipboardTextWLen;   /* in WCHAR units, excluding the NUL */

    /* MULTI-MONITOR FEATURE: hands the just-negotiated (or just re-selected)
     * monitor layout back to Kotlin — see AFreeRdpBridge.onNativeMonitorLayout.
     * Called once from systemsgo_post_connect() with the layout that was sent
     * at connect time, and again from nativeSelectMonitor() whenever it
     * succeeds in re-sending a MonitorLayout PDU over the disp channel. */
    jmethodID onMonitorLayoutMethod;

    /* MULTI-MONITOR FEATURE: the monitor layout this client declared at
     * connect time (see systemsgo_pre_connect), kept around so
     * nativeSelectMonitor() can rebuild a MONITOR_ATTRIBUTES / DISPLAY_CONTROL_MONITOR_LAYOUT
     * array reflecting only the monitor(s) currently selected, without the
     * caller having to resend the full layout on every switch. NULL/0 when
     * only a single monitor was declared (the pre-existing single-monitor
     * behavior) — nativeSelectMonitor() is then simply a no-op. */
    RDP_MONITOR_ANDROID* declaredMonitors;
    UINT32 declaredMonitorCount;

    /* CODEC-NEGOTIATION FEATURE (part 3): the "rdpgfx" dynamic virtual
     * channel's client context, captured in systemsgo_on_channel_connected()
     * the same way dispContext/cliprdrContext are above — NULL until the
     * channel opens (only happens when systemsgo_apply_codec_preference() left
     * FreeRDP_SupportGraphicsPipeline TRUE AND the server also supports
     * MS-RDPEGFX). See systemsgo_gfx_surface_command()'s doc comment for how
     * this is used to report the actually-negotiated codec to Kotlin. */
    RdpgfxClientContext* rdpgfxContext;

    /* CODEC-NEGOTIATION FEATURE (part 3): gdi_graphics_pipeline_init()
     * (called from gdi_init() in systemsgo_post_connect(), see
     * FreeRDP_SupportGraphicsPipeline above) installs its OWN
     * RdpgfxClientContext::SurfaceCommand handler to actually decode/paint
     * every GFX frame. systemsgo_post_connect() saves that real handler here
     * before replacing SurfaceCommand with systemsgo_gfx_surface_command(),
     * which observes cmd->codecId and then always chains to this pointer —
     * never swallowing a frame. NULL until that swap happens. */
    pfnRdpgfxSurfaceCommand gfxOrigSurfaceCommand;

    /* CODEC-NEGOTIATION FEATURE (part 3): last codec ID reported to Kotlin
     * via onNativeCodecNegotiated, so systemsgo_gfx_surface_command() only
     * calls back on an actual change (e.g. NetworkAutoDetect-driven
     * bandwidth pressure causing the server to drop from AVC444 to AVC420
     * mid-session) instead of once per frame. Only ever touched from the
     * channel thread that drives SurfaceCommand — no lock, same
     * single-thread assumption dispContext already relies on. */
    UINT32 lastReportedCodecId;
    BOOL hasReportedCodec;

    /* CODEC-NEGOTIATION FEATURE (part 3): see systemsgo_gfx_surface_command().
     * Matches AFreeRdpBridge.onNativeCodecNegotiated(codecName: String). */
    jmethodID onCodecNegotiatedMethod;

    /* XRDP-CAPABILITY-DETECTION FEATURE: see systemsgo_post_connect(). Matches
     * AFreeRdpBridge.onNativeSecurityProtocolNegotiated(protocolName: String).
     * Reported exactly once per connection (security negotiation happens
     * once, before PostConnect runs — unlike the codec, this can't change
     * mid-session), so this needs no "last reported" dedup state like
     * lastReportedCodecId/hasReportedCodec above. */
    jmethodID onSecurityProtocolNegotiatedMethod;

    /* REMOTE-AUDIO FEATURE: set from systemsgo_on_channel_connected() once the
     * "rdpsnd" (playback) / "audin" (capture) channels come up — mirrors
     * dispContext/cliprdrContext above. See the ChannelConnected hooks and
     * this file's top-of-file doc comment on SYSTEMSGO_AUDIO_BACKEND_AVAILABLE
     * for what is and isn't wired through these today.
     *
     * TYPE FIX: unlike "disp"/"cliprdr" (channels that DO expose a real
     * client-context struct — DispClientContext, CliprdrClientContext — via
     * ChannelConnectedEventArgs::pInterface), FreeRDP's client-side "rdpsnd"
     * and "audin" channels are internal addin plugins with no such public
     * struct: freerdp/client/rdpsnd.h only declares the *device backend* SPI
     * (rdpsndDevicePlugin, for writing an audio output backend), and
     * freerdp/client/audin.h has no client-context type at all —
     * "RdpsndClientContext"/"AudinClientContext" do not exist anywhere in
     * FreeRDP's headers. This file never dereferences either field (only
     * NULL-checks them — see systemsgo_notify_audio_channel_state and
     * nativeSendAudioCapture) purely to track "is this channel up right
     * now" for the UI, so a plain void* — still populated from
     * e->pInterface, still NULL when disconnected — is the correct,
     * buildable type here. */
    void* rdpsndContext;
    void* audinContext;
    jmethodID onAudioChannelStateMethod;
    jmethodID onAudioFrameMethod;

    /* LIVE-CHANNEL-STATUS FEATURE: printer/smartcard both ride the same
     * static "rdpdr" device-redirection channel (see the
     * SYSTEMSGO_PRINT_BACKEND_AVAILABLE/SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE
     * doc comments above) — ChannelConnected only ever reports "rdpdr"
     * opened, never which *device* the server actually accepted, so this
     * bridge cannot distinguish "printer device live" from "smartcard
     * device live" at the channel level the way rdpsnd/audin (two
     * separately-named channels) allow for audio above. The best honest
     * signal available without a deeper MS-RDPEFS IRP-level hook is
     * "rdpdr is connected AND this specific device was actually requested
     * (profile toggle on AND its backend compiled in)" — rdpdrChannelConnected
     * mirrors rdpsndContext/audinContext's NULL-means-down convention, and
     * printerRedirectRequested/smartcardRedirectRequested (set once, near
     * the top of nativeConnect, from jEnablePrinterRedirect &&
     * SYSTEMSGO_PRINT_BACKEND_AVAILABLE / jEnableSmartcardRedirect &&
     * SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE — the exact same condition already
     * gating whether freerdp_client_add_device_channel() is even called for
     * each device further down) are what systemsgo_notify_printer_channel_state/
     * systemsgo_notify_smartcard_channel_state AND the two together to derive
     * the boolean Kotlin actually sees. A future MS-RDPEFS-level fix (per-
     * device DR_CORE_DEVICE_ANNOUNCE_RESP tracking) would only need to
     * replace what feeds these two notify calls — the JNI contract itself
     * (onNativePrinterChannelState/onNativeSmartcardChannelState) does not
     * need to change. */
    BOOL rdpdrChannelConnected;
    BOOL printerRedirectRequested;
    BOOL smartcardRedirectRequested;
    jmethodID onPrinterChannelStateMethod;
    jmethodID onSmartcardChannelStateMethod;

    /* RDPDR-DEVICE-ANNOUNCE FIX: per-device counterpart to the
     * rdpdrChannelConnected heuristic above. Populated by
     * systemsgo_rdpdr_device_announce_result() (defined further down), which is
     * called by a small patch applied to FreeRDP's own
     * channels/rdpdr/client/rdpdr_main.c (see
     * the "Patch FreeRDP rdpdr for per-device announce result" CI step in .github/workflows/main.yml,
     * applied by .github/workflows/main.yml's "Patch FreeRDP for Android"
     * step) right where FreeRDP parses each server DR_CORE_DEVICE_ANNOUNCE_RSP
     * (PAKID_CORE_DEVICE_REPLY) — the one place that actually knows, per
     * DeviceId, both the device's type (looked up in rdpdr->devman->devices
     * before FreeRDP's existing devman_unregister_device() call removes it on
     * failure) and the server's ResultCode. This is the real answer to "which
     * device did the server actually accept", not the channel+requested-flag
     * approximation above.
     *
     * *AnnounceSeen is set the first time this fires for that device type in
     * a session — i.e. it doubles as a runtime feature-detect for whether the
     * FreeRDP prebuilt this .so is linked against actually carries the patch
     * (an older cached prebuilt built before the patch existed simply never
     * calls systemsgo_rdpdr_device_announce_result, so *AnnounceSeen stays
     * FALSE and systemsgo_notify_printer_channel_state/
     * systemsgo_notify_smartcard_channel_state below fall back to the old
     * heuristic automatically — no separate compile-time flag needed). */
    BOOL printerDeviceAnnounceSeen;
    BOOL printerDeviceAccepted;
    BOOL smartcardDeviceAnnounceSeen;
    BOOL smartcardDeviceAccepted;

    /* WEBCAM-REDIRECT FEATURE (live status): unlike printer/smartcard above,
     * "rdpecam" is its own dynamically-named virtual channel (see the
     * SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE doc comment), so ChannelConnected
     * firing for it IS an unambiguous, device-specific signal — no
     * requested-flag disambiguation needed, same clean case as
     * rdpsndContext/audinContext. */
    BOOL webcamChannelConnected;
    jmethodID onWebcamChannelStateMethod;

    /* MULTITOUCH FEATURE (live status): mirrors webcamChannelConnected
     * immediately above — "rdpei" is its own dynamic virtual channel, so
     * ChannelConnected/Disconnected firing for it is an unambiguous signal,
     * same clean case as rdpsndContext/audinContext/webcamChannelConnected.
     * This is what RdpRemoteAdapter.multiTouchSupported is folded from, so
     * the UI only offers MouseInputMode.MULTITOUCH once the server has
     * actually accepted MS-RDPEI — not just because this client requested
     * it in systemsgo_pre_connect(). */
    jmethodID onMultiTouchChannelStateMethod;

    /* ROOT-CAUSE FIX: set TRUE inside systemsgo_verify_certificate_ex() whenever
     * it returns 0 (Kotlin's TOFU check in RdpRemoteAdapter.verifyServerCertificate()
     * rejected the certificate — most commonly because the profile's "Accept
     * self-signed certificate" toggle is off and the server presents a
     * self-signed/otherwise-untrusted cert, the common case for home/workgroup
     * Windows RDP hosts, incl. ones reached over Tailscale). Kotlin has
     * *already* emitted a specific, actionable message for this
     * (TLS_UNTRUSTED_CERTIFICATE: ... enable "Accept self-signed
     * certificate" ...) via _error.tryEmit() at the moment the callback ran.
     * Reset to FALSE at the top of every nativeConnect() call. See its use
     * in the freerdp_connect() failure path below. */
    BOOL certRejectedLocally;

    /* REMOTEAPP-WINDOWS FEATURE: mirrors dispContext/cliprdrContext — set
     * from systemsgo_on_channel_connected() once the "rail" static channel
     * comes up (only happens if RemoteApplicationMode was requested in
     * nativeConnect AND the server supports MS-RDPERP), reset to NULL on
     * disconnect. Receiving window state still goes through
     * context->update->window, not this context (see the
     * RAIL_SVC_CHANNEL_NAME include-block comment above) — but this is now
     * also FreeRDP's *send* path used by nativeSendRailWindowMove() for
     * client -> server ClientWindowMove requests (local drag/resize). */
    RailClientContext* railContext;

    /* REMOTEAPP-WINDOWS FEATURE: JNI upcalls for AFreeRdpBridge's already-
     * fixed Kotlin-side contract (railWindowUpdates/railWindowRemovals —
     * see AFreeRdpBridge.onNativeRailWindowState/onNativeRailWindowDelete).
     * Resolved once in nativeInit() alongside onFrameMethod etc. */
    jmethodID onRailWindowStateMethod;
    jmethodID onRailWindowDeleteMethod;

    /* REMOTEAPP-WINDOWS FEATURE (icon decoding follow-up): see
     * systemsgoRailIcon's doc comment above and systemsgo_rail_icon_cache_get()
     * below. railIconCache is lazily calloc'd (NULL until the first Window
     * Icon/Cached Icon order actually arrives, since
     * FreeRDP_RemoteAppNumIconCaches/NumIconCacheEntries aren't meaningful
     * before PreConnect negotiates them) as
     * railIconCacheNumCaches * railIconCacheNumEntries systemsgoRailIcon
     * entries; railIconScratch is the fixed non-indexed slot used for
     * cacheId 0xFF ("SHOULD NOT be cached", MS-RDPERP 2.2.1.2.3). */
    systemsgoRailIcon* railIconCache;
    UINT32 railIconCacheNumCaches;
    UINT32 railIconCacheNumEntries;
    systemsgoRailIcon railIconScratch;
    jmethodID onRailWindowIconMethod;

    /* REMOTEAPP-WINDOWS FEATURE: WINDOW_STATE_ORDER (include/freerdp/
     * window.h) has no per-window server z-order field — real RAIL z-order
     * sync is a separate order (RAIL_ZORDER_SYNC) that is not exposed
     * through the WindowCreate/WindowUpdate/WindowDelete callback surface
     * this file hooks (see the RAIL_SVC_CHANNEL_NAME include-block comment
     * above), so wiring it up is out of scope here. This counter is
     * incremented on every WindowCreate/WindowUpdate and handed to Kotlin
     * as the "zOrder" for that window purely as a last-touched-goes-on-top
     * approximation (matches what a user would expect when they interact
     * with a window) — it is NOT the server's actual stacking order. */
    UINT32 railNextZOrder;

    /* GENERIC-VCHANNEL FEATURE: lets Kotlin ask for any additional
     * FreeRDP-shipped dynamic channel addin by name (beyond the ones this
     * file already special-cases above — disp/rdpei/rdpsnd/audin/rdpgfx/
     * rdpecam) without needing a new hardcoded block here every time. Filled
     * by nativeRegisterDynamicChannel() (must be called after nativeInit()
     * but before nativeConnect() — same "consumed once at connect time, not
     * toggleable mid-session" contract as every enableXxx flag in this
     * file), drained in nativeConnect()'s existing dynamic-channel section
     * via freerdp_client_add_dynamic_channel() — the exact same proven
     * mechanism the rdpecam block above already uses, just table-driven
     * instead of one `if` per channel. This can only load addins FreeRDP
     * itself ships and this build's FreeRDP prebuilt was compiled with (see
     * SETUP.md's "GENERIC-VCHANNEL FEATURE" section) — it is NOT a way to
     * inject an arbitrary, app-defined wire protocol; FreeRDP's dynamic
     * channel loader on a static/no-dlopen Android build only resolves
     * names present in its own compiled-in addin table. */
    char pendingDynamicChannelNames[SYSTEMSGO_MAX_PENDING_DYNAMIC_CHANNELS][SYSTEMSGO_DYNAMIC_CHANNEL_NAME_MAX];
    int pendingDynamicChannelCount;

    /* GENERIC-VCHANNEL FEATURE: fired for *every* channel connect/disconnect
     * (including the ones already special-cased above — disp, cliprdr, rail,
     * rdpsnd, audin, rdpdr, rdpecam, rdpgfx, rdpei — not just ones added via
     * pendingDynamicChannelNames) alongside their existing typed callbacks,
     * so Kotlin-side plugins (see AFreeRdpBridge.onNativeChannelConnected/
     * onNativeChannelDisconnected and RdpChannelPluginRegistry) can observe
     * channel lifecycle generically by name without this file growing a new
     * jmethodID/callback pair per feature. Resolved once in nativeInit()
     * alongside every other on*Method field. */
    jmethodID onChannelConnectedMethod;
    jmethodID onChannelDisconnectedMethod;
} systemsgoContext;

#define SYSTEMSGO_CTX(inst) ((systemsgoContext*)(inst)->context)

/* ── Callbacks invoked by FreeRDP's core on graphics updates ──────────── */

/* pEndPaint in the prebuilt FreeRDP3 headers is typedef'd as
 * BOOL (*pEndPaint)(rdpContext*) — no RECTANGLE_16 parameter.
 * The function must match that exact signature to satisfy the compiler.
 *
 * PARTIAL-REGION FIX: a missing RECTANGLE_16 parameter here does not mean the
 * dirty region is unavailable — FreeRDP's software GDI still tracks it on
 * gdi->primary->hdc->hwnd->invalid (a HGDI_RGN with x/y/w/h plus a `null`
 * flag meaning "nothing invalidated"). Read that instead of always assuming
 * the whole desktop changed: on typical updates (mouse cursor, a line of
 * typed text, a small UI redraw) this shrinks the copy/allocation from the
 * full w*h frame down to just the changed rectangle, which is what actually
 * matters for CPU/GC pressure on repeated updates — the previous code copied
 * and allocated a full-desktop IntArray on literally every EndPaint. */
static BOOL systemsgo_on_frame(rdpContext* context)
{
    systemsgoContext* hctx = (systemsgoContext*)context;
    rdpGdi* gdi = context->gdi;
    if (!gdi || !gdi->primary_buffer)
        return TRUE;

    /* BUG-5 FIX: attach thread if FreeRDP called this callback from a thread
     * that was not registered with the JVM (common on ARMv7 / older FreeRDP).
     * Without AttachCurrentThread, GetEnv returns JNI_EDETACHED and every
     * frame is silently dropped → black screen with no visible error. */
    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return TRUE;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return TRUE;
    }

    /* Default to a full repaint; narrowed to the actual dirty rectangle below
     * whenever the GDI invalid-region is available and looks sane. */
    int x = 0;
    int y = 0;
    int w = (int)gdi->width;
    int h = (int)gdi->height;
    jboolean isFullFrame = JNI_TRUE;

    if (gdi->primary && gdi->primary->hdc && gdi->primary->hdc->hwnd &&
        gdi->primary->hdc->hwnd->invalid && !gdi->primary->hdc->hwnd->invalid->null)
    {
        HGDI_RGN region = gdi->primary->hdc->hwnd->invalid;
        int rx = region->x, ry = region->y, rw = region->w, rh = region->h;
        /* Only trust the region if it is fully inside the desktop bounds —
         * fall back to a full repaint otherwise rather than risk an
         * out-of-bounds read from gdi->primary_buffer. */
        if (rx >= 0 && ry >= 0 && rw > 0 && rh > 0 &&
            rx + rw <= (int)gdi->width && ry + rh <= (int)gdi->height)
        {
            x = rx;
            y = ry;
            w = rw;
            h = rh;
            isFullFrame = (x == 0 && y == 0 &&
                           w == (int)gdi->width && h == (int)gdi->height) ? JNI_TRUE : JNI_FALSE;
        }
    }
    if (w <= 0 || h <= 0)
        return TRUE;

    /* CRIT-2 FIX: Guard against integer overflow before allocating the pixel buffer.
     * A malicious or misconfigured RDP server can advertise an enormous desktop
     * (e.g. 65535×65535 = ~4.3 billion pixels).  The expression `w * h` would
     * silently wrap to a small (or negative) value in 32-bit C arithmetic, causing
     * NewIntArray to allocate a tiny array while the memcpy loop writes far beyond
     * its end — undefined behaviour that manifests as a crash or memory corruption.
     *
     * Safe limit: 8K × 8K (64 M pixels × 4 bytes = 256 MB) is well above any
     * real-world remote desktop and well within Android's per-process memory budget.
     * Use int64_t for the comparison so the multiplication itself cannot overflow. */
#define SYSTEMSGO_MAX_PIXELS (8192 * 8192)  /* 64 M pixels */
    if ((int64_t)w * h > SYSTEMSGO_MAX_PIXELS) {
        LOGE("Desktop too large (%d×%d) — rejecting frame to prevent overflow", w, h);
        if (didAttach) (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
        return TRUE;
    }

    jintArray pixels = (*env)->NewIntArray(env, w * h);
    if (!pixels)
        return TRUE;

    jint* buf = (*env)->GetIntArrayElements(env, pixels, NULL);
    const UINT32 stride = gdi->stride;
    const BYTE* src = gdi->primary_buffer;
    /* PARTIAL-REGION FIX: offset each source row by (x, y) so we copy only the
     * dirty rectangle computed above, not the whole desktop. */
    for (int row = 0; row < h; row++)
    {
        const UINT32* srcRow = (const UINT32*)(src + (size_t)(row + y) * stride) + x;
        memcpy(buf + (size_t)row * w, srcRow, (size_t)w * sizeof(jint));
    }
    (*env)->ReleaseIntArrayElements(env, pixels, buf, 0);

    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onFrameMethod,
                            x, y, w, h, pixels, isFullFrame);
    (*env)->DeleteLocalRef(env, pixels);

    /* We've consumed the invalidated region — clear it so the next EndPaint
     * (if nothing further changed in between) doesn't redeliver the same
     * rectangle, mirroring what FreeRDP's own X11/Win32 clients do after
     * flushing gdi->primary_buffer out to their platform surface. */
    if (gdi->primary && gdi->primary->hdc && gdi->primary->hdc->hwnd &&
        gdi->primary->hdc->hwnd->invalid)
    {
        gdi->primary->hdc->hwnd->invalid->null = TRUE;
    }

    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
    return TRUE;
}

/* ── TLS certificate verification ───────────────────────────────────────── *
 *
 * TLS-FIX / TLS-TOFU FIX: FreeRDP_IgnoreCertificate (used to be the only cert
 * handling in this file) makes the TLS layer skip certificate verification
 * altogether, unconditionally and silently — no log line, no server identity
 * shown, and no memory of what certificate was accepted last time. That made
 * every failed RDP connection to a box with a self-signed cert (the common
 * case for home/workgroup Windows machines) come back as a bare, unexplained
 * FreeRDP error code, and made "acceptSelfSignedCertificate" an all-or-
 * nothing bypass with no protection if the certificate later changed
 * (e.g. a MITM swapping in a different self-signed cert).
 *
 * VerifyCertificateEx is the callback FreeRDP's TLS layer actually invokes
 * once it has built the certificate chain and could NOT validate it against
 * the system trust store (self-signed, expired, hostname/CN mismatch,
 * unknown CA, ...). It hands us the server host/port plus the certificate's
 * common name, subject, issuer and fingerprint. Native code does not decide
 * accept/reject itself — it relays those fields to Kotlin's
 * onNativeCertificateCheck(), which implements TOFU (trust-on-first-use)
 * pinning against an encrypted preference store, exactly like
 * SshClient.TofuHostKeyRepository and VncClient.VncTofuVerifier already do
 * for SSH and VNC. This keeps certificate-trust policy in one place per
 * protocol adapter instead of split between native and Kotlin.
 *
 * Return values expected by FreeRDP:
 *   0 = reject  (abort the TLS handshake — connection fails)
 *   1 = accept and remember permanently (FreeRDP persists it to its own cert store)
 *   2 = accept for this session only (nothing written to FreeRDP's store)
 * We always return 2 on acceptance: Kotlin already persisted (or matched)
 * the fingerprint in its own TOFU store, so there is no need for FreeRDP to
 * keep a second, separate copy on disk. */
static DWORD systemsgo_verify_certificate_ex(freerdp* instance, const char* host, UINT16 port,
                                           const char* common_name, const char* subject,
                                           const char* issuer, const char* fingerprint,
                                           DWORD flags)
{
    (void)subject; (void)flags;
    systemsgoContext* hctx = SYSTEMSGO_CTX(instance);

    const char* cn  = common_name ? common_name : "";
    const char* iss = issuer      ? issuer      : "";
    const char* fp  = fingerprint ? fingerprint : "";

    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK) {
            LOGE("TLS: could not attach thread to check certificate for %s:%u — rejecting (fail-safe)",
                 host, (unsigned)port);
            return 0;
        }
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        LOGE("TLS: could not get JNI env to check certificate for %s:%u — rejecting (fail-safe)",
             host, (unsigned)port);
        return 0;
    }

    jstring jHost = (*env)->NewStringUTF(env, host ? host : "");
    jstring jCn   = (*env)->NewStringUTF(env, cn);
    jstring jIss  = (*env)->NewStringUTF(env, iss);
    jstring jFp   = (*env)->NewStringUTF(env, fp);

    /* onNativeCertificateCheck() runs RdpRemoteAdapter's TOFU logic
     * synchronously and returns the accept/reject decision — see
     * AFreeRdpBridge.kt / RdpRemoteAdapter.kt. Any user-facing explanation
     * (untrusted cert, changed cert / possible MITM, ...) is emitted from
     * there via the existing `errors` flow, the same path VNC/SSH use. */
    jboolean accepted = (*env)->CallBooleanMethod(env, hctx->bridgeObjGlobalRef,
                                                   hctx->onCertCheckMethod,
                                                   jHost, (jint)port, jCn, jIss, jFp);

    (*env)->DeleteLocalRef(env, jHost);
    (*env)->DeleteLocalRef(env, jCn);
    (*env)->DeleteLocalRef(env, jIss);
    (*env)->DeleteLocalRef(env, jFp);
    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);

    if (accepted) {
        LOGI("TLS: certificate accepted for %s:%u (CN=%s, issuer=%s, fingerprint=%s)",
             host, (unsigned)port, cn, iss, fp);
        return 2; /* accept for this session; Kotlin owns persistence */
    }

    LOGE("TLS: certificate rejected for %s:%u (CN=%s, issuer=%s, fingerprint=%s)",
         host, (unsigned)port, cn, iss, fp);
    /* ROOT-CAUSE FIX: remember that this attempt's failure originates here,
     * so the generic FreeRDP error name surfaced after freerdp_connect()
     * returns FALSE (see below) does not overwrite the specific message
     * Kotlin's verifyServerCertificate() already emitted. */
    hctx->certRejectedLocally = TRUE;
    return 0;
}

/* Companion to VerifyCertificateEx: fires instead of it when a certificate
 * was previously accepted-and-stored *in FreeRDP's own store* (return 1
 * above) but the server now presents a different one. We always return 2
 * from VerifyCertificateEx, never 1, so FreeRDP itself never persists a
 * certificate for this app and this callback should not normally fire; it
 * is still registered so a stale/pre-existing store entry (e.g. carried
 * over from a different client sharing the same store path) is routed
 * through the same Kotlin TOFU check rather than silently accepted. Kotlin's
 * own TOFU store (keyed independently by host:port) is what actually
 * detects and blocks a changed certificate in the normal case. */
static DWORD systemsgo_verify_changed_certificate_ex(freerdp* instance, const char* host, UINT16 port,
                                                    const char* common_name, const char* subject,
                                                    const char* issuer, const char* new_fingerprint,
                                                    const char* old_subject, const char* old_issuer,
                                                    const char* old_fingerprint, DWORD flags)
{
    (void)old_subject; (void)old_issuer;
    LOGE("TLS: FreeRDP-store certificate for %s:%u changed (old fingerprint=%s, new fingerprint=%s) "
         "— deferring to Kotlin TOFU check", host, (unsigned)port,
         old_fingerprint ? old_fingerprint : "?", new_fingerprint ? new_fingerprint : "?");
    return systemsgo_verify_certificate_ex(instance, host, port, common_name, subject, issuer,
                                         new_fingerprint, flags);
}

/* ── LIVE-RESIZE FIX: Display Control ("disp") channel lifecycle ─────────── *
 *
 * The disp channel is a *dynamic* virtual channel: it only exists once the
 * server agrees to open it after freerdp_connect() completes, which FreeRDP
 * reports through the ChannelConnected/ChannelDisconnected PubSub events
 * rather than through PostConnect. We just record/clear the
 * DispClientContext pointer here; nativeResize() (below) is the only other
 * piece that touches it. */

/* CLIPBOARD FIX: MS-RDPECLIP ("cliprdr") clipboard-redirection channel
 * handlers.
 *
 * Written against the documented upstream FreeRDP 3.x client/cliprdr API
 * (freerdp/client/cliprdr.h, freerdp/channels/cliprdr.h) — like the rest of
 * this bridge, it could not be build-verified in this sandbox (no NDK
 * toolchain / no prebuilt FreeRDP headers available here; see the header
 * comment at the top of this file and app/src/main/cpp/SETUP.md for the
 * same limitation affecting everything else in systemsgo_jni.c).
 *
 * SCOPE: only CF_UNICODETEXT (plain text) is synchronized, in both
 * directions. This is intentionally narrow, matching the "one obvious
 * real-world use case, not the full channel surface" scope already used for
 * disp/audio elsewhere in this file:
 *  - File contents (CLIPRDR_FILECONTENTS_REQUEST/RESPONSE — copying a file
 *    in Explorer, or a file manager entry on Android) are not implemented.
 *  - Other formats (bitmaps, HTML/rich text, custom app formats) are not
 *    implemented; a hasText check is the only thing offered/requested.
 *  - Clipboard "locking" (Client/ServerLockClipboardData) is not
 *    implemented; every format-data exchange is treated as a one-shot
 *    request/response, which is fine for text and is what most simple
 *    cliprdr clients do.
 * A copy of an image or a file will therefore simply not appear as
 * pasteable content on the other side — the channel stays connected and
 * text keeps syncing, exactly the kind of "channel connects but only a
 * subset of what it can carry is wired up" gap already documented for
 * rail/audio above.
 */

/* Standard Win32 CF_UNICODETEXT clipboard format ID (UTF-16LE text,
 * NUL-terminated). Spelled out here rather than pulled from a winpr
 * clipboard header, since this project's Android build has no winpr
 * clipboard backend (see SETUP.md) — only the wire-format constant is
 * needed, not the rest of that API. */
#define SYSTEMSGO_CF_UNICODETEXT 13

/* Tells the server what this client's clipboard currently offers.
 * Called both right after the channel comes up (MonitorReady — MS-RDPECLIP
 * requires an initial format list even if empty) and whenever the user
 * copies something new locally (nativeSendClipboardText). */
static UINT systemsgo_cliprdr_announce_local_text(systemsgoContext* hctx)
{
    if (!hctx || !hctx->cliprdrContext || !hctx->cliprdrContext->ClientFormatList)
        return CHANNEL_RC_OK;

    BOOL haveText;
    EnterCriticalSection(&hctx->clipboardLock);
    haveText = hctx->localClipboardTextW != NULL;
    LeaveCriticalSection(&hctx->clipboardLock);

    CLIPRDR_FORMAT format;
    memset(&format, 0, sizeof(format));
    format.formatId = SYSTEMSGO_CF_UNICODETEXT;
    format.formatName = NULL;

    CLIPRDR_FORMAT_LIST formatList;
    memset(&formatList, 0, sizeof(formatList));
    formatList.common.msgType = CB_FORMAT_LIST;
    formatList.numFormats = haveText ? 1 : 0;
    formatList.formats = haveText ? &format : NULL;

    return hctx->cliprdrContext->ClientFormatList(hctx->cliprdrContext, &formatList);
}

static UINT systemsgo_cliprdr_monitor_ready(CliprdrClientContext* context, const CLIPRDR_MONITOR_READY* monitorReady)
{
    (void)monitorReady;
    return systemsgo_cliprdr_announce_local_text((systemsgoContext*)context->custom);
}

static UINT systemsgo_cliprdr_server_format_list(CliprdrClientContext* context, const CLIPRDR_FORMAT_LIST* formatList)
{
    systemsgoContext* hctx = (systemsgoContext*)context->custom;
    if (!hctx || !formatList)
        return CHANNEL_RC_OK;

    /* The server just told us what its clipboard now contains. Proactively
     * pull down CF_UNICODETEXT if it's offered, so the Android clipboard
     * stays in sync without requiring the user to paste first. */
    BOOL hasText = FALSE;
    for (UINT32 i = 0; i < formatList->numFormats; i++)
    {
        if (formatList->formats && formatList->formats[i].formatId == SYSTEMSGO_CF_UNICODETEXT)
        {
            hasText = TRUE;
            break;
        }
    }

    /* MS-RDPECLIP requires acknowledging every format list, regardless of
     * whether we intend to request any of the formats it offers. */
    if (context->ClientFormatListResponse)
    {
        CLIPRDR_FORMAT_LIST_RESPONSE response;
        memset(&response, 0, sizeof(response));
        response.common.msgType = CB_FORMAT_LIST_RESPONSE;
        response.common.msgFlags = CB_RESPONSE_OK;
        (void)context->ClientFormatListResponse(context, &response);
    }

    if (hasText && context->ClientFormatDataRequest)
    {
        CLIPRDR_FORMAT_DATA_REQUEST request;
        memset(&request, 0, sizeof(request));
        request.common.msgType = CB_FORMAT_DATA_REQUEST;
        request.requestedFormatId = SYSTEMSGO_CF_UNICODETEXT;
        return context->ClientFormatDataRequest(context, &request);
    }
    return CHANNEL_RC_OK;
}

/* Server is asking us to supply data for a format it saw us advertise
 * (via systemsgo_cliprdr_announce_local_text) — i.e. the user is pasting our
 * clipboard content into something on the remote desktop. */
static UINT systemsgo_cliprdr_server_format_data_request(CliprdrClientContext* context, const CLIPRDR_FORMAT_DATA_REQUEST* formatDataRequest)
{
    systemsgoContext* hctx = (systemsgoContext*)context->custom;
    if (!hctx || !formatDataRequest || !context->ClientFormatDataResponse)
        return CHANNEL_RC_OK;

    CLIPRDR_FORMAT_DATA_RESPONSE response;
    memset(&response, 0, sizeof(response));
    response.common.msgType = CB_FORMAT_DATA_RESPONSE;

    if (formatDataRequest->requestedFormatId != SYSTEMSGO_CF_UNICODETEXT)
    {
        /* Only plain text is implemented (see the scope note above) —
         * answer FAIL rather than leaving the server waiting. */
        response.common.msgFlags = CB_RESPONSE_FAIL;
        return context->ClientFormatDataResponse(context, &response);
    }

    EnterCriticalSection(&hctx->clipboardLock);
    const WCHAR* text = hctx->localClipboardTextW;
    size_t lenBytes = text ? (hctx->localClipboardTextWLen + 1) * sizeof(WCHAR) : 0;
    response.common.msgFlags = text ? CB_RESPONSE_OK : CB_RESPONSE_FAIL;
    response.common.dataLen = (UINT32)lenBytes;
    response.requestedFormatData = (const BYTE*)text;
    UINT rc = context->ClientFormatDataResponse(context, &response);
    LeaveCriticalSection(&hctx->clipboardLock);
    return rc;
}

/* Server's answer to the CF_UNICODETEXT request we sent from
 * systemsgo_cliprdr_server_format_list() — the actual remote clipboard content,
 * which we forward up to Kotlin so it can be written to the Android system
 * clipboard (see AFreeRdpBridge.onNativeClipboardText / RdpRemoteAdapter). */
static UINT systemsgo_cliprdr_server_format_data_response(CliprdrClientContext* context, const CLIPRDR_FORMAT_DATA_RESPONSE* formatDataResponse)
{
    systemsgoContext* hctx = (systemsgoContext*)context->custom;
    if (!hctx || !formatDataResponse)
        return CHANNEL_RC_OK;
    if (formatDataResponse->common.msgFlags != CB_RESPONSE_OK ||
        !formatDataResponse->requestedFormatData ||
        formatDataResponse->common.dataLen < sizeof(WCHAR))
        return CHANNEL_RC_OK;

    /* Data is UTF-16LE (CF_UNICODETEXT), NUL-terminated per MS-RDPECLIP.
     * JNI's NewString takes UTF-16 code units directly — jchar and WCHAR
     * are both 16-bit, and Android devices are little-endian (matching
     * CF_UNICODETEXT's wire byte order) — so no manual UTF-8 re-encoding
     * is needed or risked here. */
    UINT32 charCount = formatDataResponse->common.dataLen / (UINT32)sizeof(WCHAR);
    const jchar* chars = (const jchar*)formatDataResponse->requestedFormatData;
    if (charCount > 0 && chars[charCount - 1] == 0)
        charCount -= 1;  /* trim the protocol-required trailing NUL */

    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return CHANNEL_RC_OK;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return CHANNEL_RC_OK;
    }

    jstring jtext = (*env)->NewString(env, chars, (jsize)charCount);
    if (jtext)
    {
        (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onClipboardTextMethod, jtext);
        (*env)->DeleteLocalRef(env, jtext);
    }
    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);

    return CHANNEL_RC_OK;
}

/* REMOTE-AUDIO FEATURE: notifies Kotlin of the current rdpsnd/audin channel
 * connection state — see AFreeRdpBridge.onNativeAudioChannelState. Called
 * from both systemsgo_on_channel_connected/disconnected below whenever either
 * channel's state changes, so the UI's "audio connected" indicator always
 * reflects the union of both contexts rather than only the one that just
 * changed. */
static void systemsgo_notify_audio_channel_state(systemsgoContext* hctx)
{
    if (!hctx || !hctx->onAudioChannelStateMethod) return;
    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return;
    }
    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onAudioChannelStateMethod,
                            hctx->rdpsndContext != NULL ? JNI_TRUE : JNI_FALSE,
                            hctx->audinContext != NULL ? JNI_TRUE : JNI_FALSE);
    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
}

/* LIVE-CHANNEL-STATUS FEATURE: see the rdpdrChannelConnected/
 * printerRedirectRequested doc comment on systemsgoContext above for exactly
 * what this reports and why it's channel-level rather than device-level.
 * Called from systemsgo_on_channel_connected/disconnected whenever "rdpdr"'s
 * own state changes, same call shape as systemsgo_notify_audio_channel_state. */
static void systemsgo_notify_printer_channel_state(systemsgoContext* hctx)
{
    if (!hctx || !hctx->onPrinterChannelStateMethod) return;
    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return;
    }
    /* Prefer the precise per-device signal (see printerDeviceAnnounceSeen's
     * doc comment) once/if it has actually fired for this session; otherwise
     * fall back to the old channel+requested-flag approximation. */
    jboolean connected = hctx->printerDeviceAnnounceSeen
                              ? (hctx->printerDeviceAccepted ? JNI_TRUE : JNI_FALSE)
                              : ((hctx->rdpdrChannelConnected && hctx->printerRedirectRequested)
                                     ? JNI_TRUE : JNI_FALSE);
    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onPrinterChannelStateMethod, connected);
    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
}

/* LIVE-CHANNEL-STATUS FEATURE: smartcard counterpart to
 * systemsgo_notify_printer_channel_state immediately above — same "rdpdr"
 * channel-level signal, gated on smartcardRedirectRequested instead of
 * printerRedirectRequested. */
static void systemsgo_notify_smartcard_channel_state(systemsgoContext* hctx)
{
    if (!hctx || !hctx->onSmartcardChannelStateMethod) return;
    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return;
    }
    /* See systemsgo_notify_printer_channel_state's matching comment above. */
    jboolean connected = hctx->smartcardDeviceAnnounceSeen
                              ? (hctx->smartcardDeviceAccepted ? JNI_TRUE : JNI_FALSE)
                              : ((hctx->rdpdrChannelConnected && hctx->smartcardRedirectRequested)
                                     ? JNI_TRUE : JNI_FALSE);
    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onSmartcardChannelStateMethod, connected);
    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
}

/* RDPDR-DEVICE-ANNOUNCE FIX: called directly from FreeRDP's own
 * channels/rdpdr/client/rdpdr_main.c — specifically from the
 * PAKID_CORE_DEVICE_REPLY case of the function that used to be named
 * rdpdr_process_receive_data() as of FreeRDP 3.27.1's real source (verified
 * via github.com/FreeRDP/FreeRDP, see the patch file referenced on
 * printerDeviceAnnounceSeen's doc comment for the exact anchor text) — via a
 * small `extern` declaration added by that same patch, deliberately NOT by
 * adding a new field to the public RdpdrClientContext struct in
 * include/freerdp/client/rdpdr.h. Avoiding that header meant not having to
 * guess at that struct's exact field layout/ABI (which this project cannot
 * verify without a local FreeRDP source checkout — see this file's other
 * "NOT VERIFIED AGAINST UPSTREAM SOURCE" comments for why that matters here);
 * a plain extern function call has no such ABI-layout risk.
 *
 * rdpcontext is rdpdr->rdpcontext, i.e. the same rdpContext* as
 * instance->context — safe to cast directly to systemsgoContext* since (like
 * every FreeRDP client context struct, per freerdp_context_new()'s own
 * contract) systemsgoContext's first field is a plain `rdpContext context`,
 * not a pointer, so the address is identical either way.
 *
 * deviceType is one of freerdp/channels/rdpdr.h's RDPDR_DTYP_* constants
 * (already visible to this file via the RDPDR_SVC_CHANNEL_NAME include
 * above) as looked up from rdpdr->devman->devices by the patch BEFORE
 * FreeRDP's own devman_unregister_device() call can remove the entry on
 * failure. status is the raw DR_CORE_DEVICE_ANNOUNCE_RSP ResultCode
 * (0 = STATUS_SUCCESS = accepted; nonzero = rejected), same value FreeRDP
 * itself already checks via `if (status != 0)` right after this call. */
void systemsgo_rdpdr_device_announce_result(void* rdpcontext, UINT32 deviceId, UINT32 deviceType,
                                          UINT32 status)
{
    WINPR_UNUSED(deviceId);
    if (!rdpcontext)
        return;
    systemsgoContext* hctx = (systemsgoContext*)rdpcontext;
    BOOL accepted = (status == 0) ? TRUE : FALSE;

    if (deviceType == RDPDR_DTYP_PRINT)
    {
        hctx->printerDeviceAnnounceSeen = TRUE;
        hctx->printerDeviceAccepted = accepted;
        systemsgo_notify_printer_channel_state(hctx);
    }
    else if (deviceType == RDPDR_DTYP_SMARTCARD)
    {
        hctx->smartcardDeviceAnnounceSeen = TRUE;
        hctx->smartcardDeviceAccepted = accepted;
        systemsgo_notify_smartcard_channel_state(hctx);
    }
    /* Other device types (drive/serial/parallel) have no live-status UI
     * surface today — silently ignored, same as before this fix existed. */
}

/* WEBCAM-REDIRECT FEATURE (live status): unambiguous device-specific signal
 * — see hctx->webcamChannelConnected's doc comment. Called from
 * systemsgo_on_channel_connected/disconnected directly with the new state
 * (unlike the printer/smartcard notify functions above, this one takes the
 * value instead of re-deriving it from hctx, since there's nothing else to
 * AND it against). */
static void systemsgo_notify_webcam_channel_state(systemsgoContext* hctx, BOOL connected)
{
    if (!hctx || !hctx->onWebcamChannelStateMethod) return;
    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return;
    }
    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onWebcamChannelStateMethod,
                            connected ? JNI_TRUE : JNI_FALSE);
    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
}

/* MULTITOUCH FEATURE (live status): identical shape to
 * systemsgo_notify_webcam_channel_state immediately above, for "rdpei" instead
 * of "rdpecam". */
static void systemsgo_notify_multitouch_channel_state(systemsgoContext* hctx, BOOL connected)
{
    if (!hctx || !hctx->onMultiTouchChannelStateMethod) return;
    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return;
    }
    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onMultiTouchChannelStateMethod,
                            connected ? JNI_TRUE : JNI_FALSE);
    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
}
 * and update->window->WindowUpdate (see systemsgo_pre_connect) — mirrors
 * xf_rail_window_common()'s dual role in FreeRDP's own X11 client
 * (client/X11/xf_rail.c), which handles WINDOW_ORDER_STATE_NEW (creation)
 * and subsequent field updates in one function since a WindowUpdate order
 * often carries only the fields that changed (WINDOW_ORDER_INFO::fieldFlags)
 * while a WindowCreate carries the initial full set. We don't need to track
 * per-window state locally the way xfreerdp's appWindow cache does (Kotlin's
 * RemoteAppWindowManager owns that) — we just need to report whatever this
 * particular order actually contains, using the same field-presence checks
 * xf_rail_window_common uses, and let Kotlin's StateFlow-backed window map
 * merge it (existing window: partial update; unknown windowId: treated as
 * new by RemoteAppWindowManager).
 *
 * FIELD-FLAG / STRUCT-FIELD NAMES: taken from FreeRDP's actual
 * include/freerdp/window.h (WINDOW_ORDER_INFO::windowId/fieldFlags,
 * WINDOW_STATE_ORDER::windowOffsetX/Y, windowWidth/Height, showState,
 * titleInfo.{string,length}) and client/X11/xf_rail.c's
 * xf_rail_window_common(), both confirmed against upstream FreeRDP source
 * for this fix (not guessed) — see the RAIL_SVC_CHANNEL_NAME include-block
 * comment above.
 *
 * isVisible mapping: MS-RDPERP's WINDOW_SHOW_HIDDEN show-state value is 0;
 * every other defined show state (WINDOW_SHOW/_MINIMIZED/_MAXIMIZED, ...)
 * is a nonzero window-manager state that still corresponds to a window the
 * user can bring to front, so "visible" here means "not entirely hidden",
 * matching how xf_rail_window_common's own use of showState governs whether
 * xf_ShowWindow maps or unmaps the local X11 window. */
static BOOL systemsgo_rail_window_state(rdpContext* context, const WINDOW_ORDER_INFO* orderInfo,
                                      const WINDOW_STATE_ORDER* windowState)
{
    if (!context || !orderInfo || !windowState) return FALSE;
    systemsgoContext* hctx = (systemsgoContext*)context;
    if (!hctx->onRailWindowStateMethod) return TRUE;

    UINT32 fieldFlags = orderInfo->fieldFlags;

    /* Only fields actually present on this order are meaningful — for an
     * update-only order (not WINDOW_ORDER_STATE_NEW) that merely e.g. shows
     * or hides the window, position/size/title are simply absent. Kotlin's
     * RemoteAppWindowManager is expected to merge by windowId rather than
     * treat every call as a full snapshot; we still populate reasonable
     * fallbacks (0-sized rect, empty title) for anything absent so the JNI
     * call is always well-formed. */
    INT32 x = (fieldFlags & WINDOW_ORDER_FIELD_WND_OFFSET) ? windowState->windowOffsetX : 0;
    INT32 y = (fieldFlags & WINDOW_ORDER_FIELD_WND_OFFSET) ? windowState->windowOffsetY : 0;
    UINT32 width  = (fieldFlags & WINDOW_ORDER_FIELD_WND_SIZE) ? windowState->windowWidth  : 0;
    UINT32 height = (fieldFlags & WINDOW_ORDER_FIELD_WND_SIZE) ? windowState->windowHeight : 0;
    /* WINDOW_SHOW_HIDDEN == 0 (MS-RDPERP 2.2.1.3.1.1) — treat "no SHOW field
     * on this order" as "unknown, assume still visible" rather than hidden,
     * since most update orders that move/resize a window don't repeat its
     * show state. */
    BOOL isVisible = (fieldFlags & WINDOW_ORDER_FIELD_SHOW) ? (windowState->showState != 0) : TRUE;

    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return TRUE;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return TRUE;
    }

    /* RAIL_UNICODE_STRING (titleInfo) is UTF-16LE with an explicit byte
     * length (freerdp/rail.h) — same wire representation as CF_UNICODETEXT,
     * so the same direct-to-jchar NewString approach used for clipboard text
     * in systemsgo_cliprdr_server_format_data_response() above applies, without
     * that function's NUL-trim (RAIL_UNICODE_STRING carries an explicit
     * length and is not documented as NUL-terminated the way CF_UNICODETEXT
     * is — xf_rail_window_common passes titleInfo.length straight through to
     * its WCHAR->UTF-8 conversion with no such trim, so we don't invent one
     * either). Absent WINDOW_ORDER_FIELD_TITLE, report an empty title rather
     * than leaving Kotlin's last-known title stale-but-unlabeled; Kotlin's
     * merge-by-windowId is expected to keep the previous title if this
     * string is empty on a non-creation update. */
    jstring jtitle;
    if ((fieldFlags & WINDOW_ORDER_FIELD_TITLE) && windowState->titleInfo.string &&
        windowState->titleInfo.length > 0)
    {
        jtitle = (*env)->NewString(env, (const jchar*)windowState->titleInfo.string,
                                    (jsize)(windowState->titleInfo.length / sizeof(WCHAR)));
    }
    else
    {
        jtitle = (*env)->NewStringUTF(env, "");
    }

    if (jtitle)
    {
        UINT32 zOrder = ++hctx->railNextZOrder;
        (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onRailWindowStateMethod,
                                (jint)orderInfo->windowId, jtitle,
                                (jint)x, (jint)y, (jint)width, (jint)height,
                                (jboolean)(isVisible ? JNI_TRUE : JNI_FALSE),
                                (jint)zOrder);
        (*env)->DeleteLocalRef(env, jtitle);
    }

    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
    return TRUE;
}

/* REMOTEAPP-WINDOWS FEATURE: registered as update->window->WindowDelete —
 * MS-RDPERP Window Delete order, one per closed RAIL window. */
static BOOL systemsgo_rail_window_delete(rdpContext* context, const WINDOW_ORDER_INFO* orderInfo)
{
    if (!context || !orderInfo) return FALSE;
    systemsgoContext* hctx = (systemsgoContext*)context;
    if (!hctx->onRailWindowDeleteMethod) return TRUE;

    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return TRUE;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return TRUE;
    }

    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onRailWindowDeleteMethod,
                            (jint)orderInfo->windowId);

    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
    return TRUE;
}

/* REMOTEAPP-WINDOWS FEATURE: WindowIcon/WindowCachedIcon (MS-RDPERP Icon /
 * Cached Icon orders) — registered below (systemsgo_pre_connect) alongside
 * WindowCreate/WindowUpdate/WindowDelete, same "dispatcher needs a non-NULL
 * callback the moment RemoteApp mode is on" reasoning documented on those.
 *
 * Verified against xf_rail.c's convert_rail_icon()/xf_rail_window_icon()/
 * xf_rail_window_cached_icon() (fetched from
 * https://pub.freerdp.com/api/xf__rail_8c_source.html), including
 * RailIconCache_Lookup's cacheId==0xFF "scratch, don't index the table"
 * special case:
 *  1. systemsgo_rail_icon_cache_get() below returns the cache slot for
 *     (cacheId, cacheEntry), lazily sizing the table from
 *     FreeRDP_RemoteAppNumIconCaches/NumIconCacheEntries on first use.
 *  2. systemsgo_rail_window_icon (WindowIcon order, carries pixel data)
 *     decodes ICON_INFO into that slot via freerdp_image_copy_from_icon_data
 *     — the same public FreeRDP codec entry point convert_rail_icon() calls,
 *     so DIB-format quirks (16-bit is RGB555 not RGB565, 32-bit is BGRA
 *     order, etc.) are handled by FreeRDP itself, not reimplemented here.
 *  3. systemsgo_rail_window_cached_icon (WindowCachedIcon order, no pixel data
 *     — just a cache reference) looks the slot back up and re-emits
 *     whatever was already decoded into it; an empty slot is a soft no-op,
 *     matching xf_rail_window_cached_icon's own "!icon -> return TRUE".
 * Both hand decoded PIXEL_FORMAT_BGRA32 pixels to Kotlin as a jintArray via
 * systemsgo_rail_emit_icon(), the same raw-memcpy convention systemsgo_on_frame()
 * already uses — see systemsgoRailIcon's doc comment for why BGRA32 (not
 * xf_rail.c's PIXEL_FORMAT_ARGB32, which targets X11's _NET_WM_ICON) is
 * this file's icon-decode target. */

/* Mirrors xf_rail.c's RailIconCache_Lookup: same 0xFF-is-scratch special
 * case (MS-RDPERP 2.2.1.2.3 TS_ICON_INFO CacheId 0xFF = "do not cache"),
 * same lazy-size-from-settings behavior. Returns NULL only for a genuinely
 * out-of-range cacheId/cacheEntry (misbehaving server) or an allocation
 * failure. */
static systemsgoRailIcon* systemsgo_rail_icon_cache_get(systemsgoContext* hctx, UINT32 cacheId, UINT32 cacheEntry)
{
    if (cacheId == 0xFF)
        return &hctx->railIconScratch;

    if (!hctx->railIconCache)
    {
        rdpSettings* settings = hctx->context.settings;
        UINT32 numCaches  = settings ? freerdp_settings_get_uint32(settings, FreeRDP_RemoteAppNumIconCaches) : 0;
        UINT32 numEntries = settings ? freerdp_settings_get_uint32(settings, FreeRDP_RemoteAppNumIconCacheEntries) : 0;
        if (numCaches == 0 || numEntries == 0)
            return NULL;
        systemsgoRailIcon* table = (systemsgoRailIcon*)calloc((size_t)numCaches * numEntries, sizeof(systemsgoRailIcon));
        if (!table)
            return NULL;
        hctx->railIconCache = table;
        hctx->railIconCacheNumCaches = numCaches;
        hctx->railIconCacheNumEntries = numEntries;
    }

    if (cacheId >= hctx->railIconCacheNumCaches || cacheEntry >= hctx->railIconCacheNumEntries)
        return NULL;

    return &hctx->railIconCache[hctx->railIconCacheNumEntries * cacheId + cacheEntry];
}

/* Hands a decoded icon's PIXEL_FORMAT_BGRA32 pixels to Kotlin, same
 * raw-memcpy-into-jintArray convention systemsgo_on_frame uses (no per-pixel
 * reformatting needed for Android's Bitmap.Config.ARGB_8888). */
static void systemsgo_rail_emit_icon(systemsgoContext* hctx, UINT32 windowId, const systemsgoRailIcon* icon)
{
    if (!hctx->onRailWindowIconMethod || !icon->argb || icon->width == 0 || icon->height == 0)
        return;

    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return;
    }

    jsize count = (jsize)(icon->width * icon->height);
    jintArray pixels = (*env)->NewIntArray(env, count);
    if (pixels)
    {
        (*env)->SetIntArrayRegion(env, pixels, 0, count, (const jint*)icon->argb);
        (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onRailWindowIconMethod,
                                (jint)windowId, (jint)icon->width, (jint)icon->height, pixels);
        (*env)->DeleteLocalRef(env, pixels);
    }

    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
}

static BOOL systemsgo_rail_window_icon(rdpContext* context, const WINDOW_ORDER_INFO* orderInfo,
                                     const WINDOW_ICON_ORDER* windowIcon)
{
    if (!context || !orderInfo || !windowIcon || !windowIcon->iconInfo) return TRUE;
    systemsgoContext* hctx = (systemsgoContext*)context;
    const ICON_INFO* iconInfo = windowIcon->iconInfo;
    if (iconInfo->width == 0 || iconInfo->height == 0) return TRUE;

    systemsgoRailIcon* icon = systemsgo_rail_icon_cache_get(hctx, iconInfo->cacheId, iconInfo->cacheEntry);
    if (!icon)
    {
        LOGE("RAIL WindowIcon: bad/unavailable icon cache slot %02X:%04X", iconInfo->cacheId, iconInfo->cacheEntry);
        return TRUE;
    }

    size_t needed = (size_t)iconInfo->width * iconInfo->height * 4;
    if (icon->argb == NULL || icon->width != iconInfo->width || icon->height != iconInfo->height)
    {
        BYTE* buf = (BYTE*)realloc(icon->argb, needed);
        if (!buf) return TRUE;
        icon->argb = buf;
    }

    /* freerdp_image_copy_from_icon_data — the same public FreeRDP codec
     * function convert_rail_icon() calls; nDstStep 0 tells it to derive the
     * stride from width*bpp itself (matches convert_rail_icon passing 0
     * too). Width/height are UINT16 on the wire (MS-RDPERP TS_ICON_INFO) —
     * iconInfo->width/height are UINT32 in the parsed struct but always
     * fit, same assumption xf_rail.c makes with its
     * WINPR_ASSERTING_INT_CAST(UINT16, ...) casts. */
    BOOL ok = freerdp_image_copy_from_icon_data(
        icon->argb, PIXEL_FORMAT_BGRA32, 0, 0, 0,
        (UINT16)iconInfo->width, (UINT16)iconInfo->height,
        iconInfo->bitsColor, (UINT16)iconInfo->cbBitsColor,
        iconInfo->bitsMask, (UINT16)iconInfo->cbBitsMask,
        iconInfo->colorTable, (UINT16)iconInfo->cbColorTable,
        iconInfo->bpp);
    if (!ok)
    {
        LOGE("RAIL WindowIcon: freerdp_image_copy_from_icon_data failed for window %08X", orderInfo->windowId);
        return TRUE;
    }

    icon->width = iconInfo->width;
    icon->height = iconInfo->height;
    systemsgo_rail_emit_icon(hctx, orderInfo->windowId, icon);
    return TRUE;
}

static BOOL systemsgo_rail_window_cached_icon(rdpContext* context, const WINDOW_ORDER_INFO* orderInfo,
                                            const WINDOW_CACHED_ICON_ORDER* windowCachedIcon)
{
    if (!context || !orderInfo || !windowCachedIcon) return TRUE;
    systemsgoContext* hctx = (systemsgoContext*)context;

    systemsgoRailIcon* icon = systemsgo_rail_icon_cache_get(hctx, windowCachedIcon->cachedIcon.cacheId,
                                                        windowCachedIcon->cachedIcon.cacheEntry);
    if (!icon)
    {
        LOGE("RAIL WindowCachedIcon: bad/unavailable icon cache slot %02X:%04X",
             windowCachedIcon->cachedIcon.cacheId, windowCachedIcon->cachedIcon.cacheEntry);
        return TRUE;
    }
    /* No pixel data on this order by design (MS-RDPERP 2.2.1.3.1.4.3) — it
     * only makes sense once WindowIcon has actually populated this slot;
     * an empty slot is a soft no-op, same as xf_rail_window_cached_icon's
     * own "!icon" branch. */
    systemsgo_rail_emit_icon(hctx, orderInfo->windowId, icon);
    return TRUE;
}

/* GENERIC-VCHANNEL FEATURE: JNI upcall shared by systemsgo_on_channel_connected/
 * disconnected below — see systemsgoContext::onChannelConnectedMethod's doc
 * comment. Attaches the calling thread the same way every other cross-thread
 * upcall in this file does (FreeRDP's channel-manager thread is not the
 * thread nativeConnect() was called from). */
static void systemsgo_notify_channel_lifecycle(systemsgoContext* hctx, const char* name, BOOL connected)
{
    if (!hctx || !name || !hctx->jvm) return;
    jmethodID method = connected ? hctx->onChannelConnectedMethod : hctx->onChannelDisconnectedMethod;
    if (!method) return;

    JNIEnv* env = NULL;
    BOOL didAttach = FALSE;
    if ((*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
    {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK) return;
        didAttach = TRUE;
    }

    jstring jname = (*env)->NewStringUTF(env, name);
    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, method, jname);
    (*env)->DeleteLocalRef(env, jname);

    if (didAttach) (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
}

static void systemsgo_on_channel_connected(void* context, const ChannelConnectedEventArgs* e)
{
    if (!context || !e || !e->name) return;
    systemsgo_notify_channel_lifecycle((systemsgoContext*)context, e->name, TRUE);
    /* Mirrors systemsgo_on_frame(rdpContext* context): systemsgoContext embeds
     * `rdpContext context` as its first member, so the rdpContext* PubSub
     * hands us IS the start of our systemsgoContext — a direct cast is valid,
     * no freerdp* round-trip needed (unlike the SYSTEMSGO_CTX(instance) macro,
     * which expects a freerdp* and is used everywhere else in this file). */
    systemsgoContext* hctx = (systemsgoContext*)context;
    if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0)
    {
        if (hctx->dispLockInitialized) EnterCriticalSection(&hctx->dispLock);
        hctx->dispContext = (DispClientContext*)e->pInterface;
        if (hctx->dispLockInitialized) LeaveCriticalSection(&hctx->dispLock);
        LOGI("Display Control channel connected — live resize available for this session");
    }
    /* CLIPBOARD FIX: "cliprdr" is a *static* virtual channel (unlike "disp",
     * a dynamic one) but is reported through this same ChannelConnected
     * event once freerdp_client_load_addins() (called from
     * systemsgo_pre_connect) has loaded it and the server has agreed to open
     * it — i.e. only when enableClipboard was TRUE for this connection AND
     * the server supports RDPECLIP. */
    else if (strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0)
    {
        CliprdrClientContext* cliprdr = (CliprdrClientContext*)e->pInterface;
        hctx->cliprdrContext = cliprdr;
        cliprdr->custom = hctx;
        cliprdr->MonitorReady = systemsgo_cliprdr_monitor_ready;
        cliprdr->ServerFormatList = systemsgo_cliprdr_server_format_list;
        cliprdr->ServerFormatDataRequest = systemsgo_cliprdr_server_format_data_request;
        cliprdr->ServerFormatDataResponse = systemsgo_cliprdr_server_format_data_response;
        LOGI("Clipboard channel connected — clipboard sync available for this session");
    }
    /* REMOTE-AUDIO FEATURE: "rdpsnd" (MS-RDPEA, playback) — only opens when
     * FreeRDP_AudioPlayback was requested (enableSound) AND the server
     * supports it. Recording the context here lets the UI show an accurate
     * "connected" state even on builds without a compiled-in audio
     * subsystem (SYSTEMSGO_AUDIO_BACKEND_AVAILABLE == 0) — see this file's
     * top-of-file scope note on rdpsnd/audin for what remains a follow-up. */
    else if (strcmp(e->name, RDPSND_CHANNEL_NAME) == 0)
    {
        hctx->rdpsndContext = (void*)e->pInterface;
        LOGI("Remote audio playback (rdpsnd) channel connected");
        systemsgo_notify_audio_channel_state(hctx);
    }
    /* REMOTE-AUDIO FEATURE: "audin" (MS-RDPEAI, microphone capture) — only
     * opens when FreeRDP_AudioCapture was requested (enableMicRedirect) AND
     * the server supports it. */
    else if (strcmp(e->name, AUDIN_DVC_CHANNEL_NAME) == 0)
    {
        hctx->audinContext = (void*)e->pInterface;
        LOGI("Remote microphone redirection (audin) channel connected");
        systemsgo_notify_audio_channel_state(hctx);
    }
    /* REMOTEAPP-WINDOWS FEATURE: "rail" is a *static* virtual channel (like
     * cliprdr, unlike disp/rdpsnd/audin's dynamic ones) reported through this
     * same ChannelConnected event once freerdp_client_load_addins() (called
     * from systemsgo_pre_connect) has loaded it — which only happens when
     * FreeRDP_RemoteApplicationMode was set TRUE in nativeConnect (see the
     * REMOTEAPP FIX settings block there) AND the server actually opens the
     * channel. Recording railContext itself is not required for the
     * receive-only window-tracking this fix adds (see the
     * RAIL_SVC_CHANNEL_NAME include-block comment for why), but is captured
     * here for parity with dispContext/cliprdrContext and as the natural
     * hook point for a future send-path (activate/move/close) feature. */
    else if (strcmp(e->name, RAIL_SVC_CHANNEL_NAME) == 0)
    {
        hctx->railContext = (RailClientContext*)e->pInterface;
        LOGI("RemoteApp (RAIL) channel connected — multi-window tracking available for this session");
    }
    /* LIVE-CHANNEL-STATUS FEATURE: "rdpdr" (MS-RDPEFS device redirection) —
     * carries drive/printer/smartcard devices alike, so this only tells us
     * the channel itself is up, not which device(s) the server actually
     * accepted. See hctx->rdpdrChannelConnected's doc comment above for why
     * printer/smartcard notification is AND'd with each device's own
     * requested flag instead of firing on this alone. RDPDR_SVC_CHANNEL_NAME
     * comes from freerdp/channels/rdpdr.h (included above). */
    else if (strcmp(e->name, RDPDR_SVC_CHANNEL_NAME) == 0)
    {
        hctx->rdpdrChannelConnected = TRUE;
        LOGI("Device redirection (rdpdr) channel connected");
        systemsgo_notify_printer_channel_state(hctx);
        systemsgo_notify_smartcard_channel_state(hctx);
    }
    /* WEBCAM-REDIRECT FEATURE (live status): "rdpecam" is its own dynamic
     * virtual channel — see hctx->webcamChannelConnected's doc comment for
     * why this is an unambiguous, device-specific signal unlike rdpdr above. */
    else if (strcmp(e->name, "rdpecam") == 0)
    {
        hctx->webcamChannelConnected = TRUE;
        LOGI("Webcam (rdpecam) channel connected");
        systemsgo_notify_webcam_channel_state(hctx, TRUE);
    }
    /* CODEC-NEGOTIATION FEATURE (part 3): "rdpgfx" is a dynamic virtual
     * channel — like disp/rdpsnd/audin above, it connects (and this event
     * fires) before systemsgo_post_connect() runs, so only the raw context is
     * captured here. The actual SurfaceCommand hook is installed in
     * systemsgo_post_connect() *after* gdi_init(), because gdi_init() ->
     * gdi_graphics_pipeline_init() is what installs the real handler this
     * file wraps — installing the wrap here would just get silently
     * overwritten by that later call. See systemsgo_gfx_surface_command()'s
     * doc comment for the wrap itself. Only fires at all when
     * systemsgo_apply_codec_preference() left FreeRDP_SupportGraphicsPipeline
     * TRUE AND the server also supports MS-RDPEGFX. */
    else if (strcmp(e->name, RDPGFX_DVC_CHANNEL_NAME) == 0)
    {
        hctx->rdpgfxContext = (RdpgfxClientContext*)e->pInterface;
        hctx->rdpgfxContext->custom = hctx;
        LOGI("Graphics pipeline (rdpgfx) channel connected");
    }
    /* MULTITOUCH FEATURE: "rdpei" is a dynamic virtual channel (like
     * disp/rdpsnd/audin/rdpgfx above) — only opens when FreeRDP_MultiTouchInput
     * was set TRUE in systemsgo_pre_connect() AND the server supports MS-RDPEI.
     * Mirrors dispContext's locked check-then-use pattern exactly, since
     * nativeSendTouchFrame() below can be called from the UI/JNI thread at
     * up to display-refresh-rate frequency while a disconnect can null this
     * out concurrently from the channel-manager thread. */
    else if (strcmp(e->name, RDPEI_DVC_CHANNEL_NAME) == 0)
    {
        if (hctx->rdpeiLockInitialized) EnterCriticalSection(&hctx->rdpeiLock);
        hctx->rdpeiContext = (RdpeiClientContext*)e->pInterface;
        if (hctx->rdpeiLockInitialized) LeaveCriticalSection(&hctx->rdpeiLock);
        LOGI("Multitouch (rdpei) channel connected — real multi-contact touch available for this session");
        systemsgo_notify_multitouch_channel_state(hctx, TRUE);
    }
}

static void systemsgo_on_channel_disconnected(void* context, const ChannelDisconnectedEventArgs* e)
{
    if (!context || !e || !e->name) return;
    systemsgo_notify_channel_lifecycle((systemsgoContext*)context, e->name, FALSE);
    systemsgoContext* hctx = (systemsgoContext*)context;
    if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0)
    {
        if (hctx->dispLockInitialized) EnterCriticalSection(&hctx->dispLock);
        hctx->dispContext = NULL;
        if (hctx->dispLockInitialized) LeaveCriticalSection(&hctx->dispLock);
    }
    else if (strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0)
    {
        hctx->cliprdrContext = NULL;
    }
    else if (strcmp(e->name, RDPSND_CHANNEL_NAME) == 0)
    {
        hctx->rdpsndContext = NULL;
        systemsgo_notify_audio_channel_state(hctx);
    }
    else if (strcmp(e->name, AUDIN_DVC_CHANNEL_NAME) == 0)
    {
        hctx->audinContext = NULL;
        systemsgo_notify_audio_channel_state(hctx);
    }
    else if (strcmp(e->name, RAIL_SVC_CHANNEL_NAME) == 0)
    {
        hctx->railContext = NULL;
        /* Deliberately NOT clearing hctx->railNextZOrder or telling Kotlin
         * to drop its window list here: a channel disconnect on its own
         * doesn't mean the session ended (nativeConnect's per-attempt reset
         * further down is what actually starts a session fresh), and
         * RemoteAppWindowManager already receives an explicit
         * onNativeRailWindowDelete for each window as the server tears them
         * down, same as how dispContext going NULL doesn't itself trigger
         * any monitor-layout side effect above. */
    }
    else if (strcmp(e->name, RDPDR_SVC_CHANNEL_NAME) == 0)
    {
        hctx->rdpdrChannelConnected = FALSE;
        /* Also clear the precise per-device state (see
         * printerDeviceAnnounceSeen's doc comment) so a future reconnect
         * doesn't briefly report a stale accepted/rejected value left over
         * from this session before its own DR_CORE_DEVICE_ANNOUNCE_RSP
         * arrives. */
        hctx->printerDeviceAnnounceSeen = FALSE;
        hctx->printerDeviceAccepted = FALSE;
        hctx->smartcardDeviceAnnounceSeen = FALSE;
        hctx->smartcardDeviceAccepted = FALSE;
        systemsgo_notify_printer_channel_state(hctx);
        systemsgo_notify_smartcard_channel_state(hctx);
    }
    else if (strcmp(e->name, "rdpecam") == 0)
    {
        hctx->webcamChannelConnected = FALSE;
        systemsgo_notify_webcam_channel_state(hctx, FALSE);
    }
    else if (strcmp(e->name, RDPGFX_DVC_CHANNEL_NAME) == 0)
    {
        hctx->rdpgfxContext = NULL;
        /* Deliberately NOT resetting gfxOrigSurfaceCommand/hasReportedCodec/
         * lastReportedCodecId here: a mid-session GFX channel drop is
         * unusual and the diagnostics screen showing the last-known codec
         * until a fresh nativeConnect() attempt resets the whole hctx
         * (see its CODEC-NEGOTIATION reset block) matches how
         * railContext's disconnect handling above deliberately leaves
         * RemoteAppWindowManager's state alone too. */
    }
    else if (strcmp(e->name, RDPEI_DVC_CHANNEL_NAME) == 0)
    {
        if (hctx->rdpeiLockInitialized) EnterCriticalSection(&hctx->rdpeiLock);
        hctx->rdpeiContext = NULL;
        if (hctx->rdpeiLockInitialized) LeaveCriticalSection(&hctx->rdpeiLock);
        systemsgo_notify_multitouch_channel_state(hctx, FALSE);
    }
}

/* ── FreeRDP lifecycle callbacks ────────────────────────────────────────── */

static BOOL systemsgo_pre_connect(freerdp* instance)
{
    rdpSettings* settings = instance->context->settings;
    (void)freerdp_settings_set_bool(settings, FreeRDP_SoftwareGdi, TRUE);

    /* LIVE-RESIZE FIX: advertise + load the "disp" virtual channel so the
     * server can be asked to resize the session in-place. This is purely
     * additive capability negotiation — a server that doesn't implement
     * RDPEDISP never opens the channel, ChannelConnected never fires,
     * hctx->dispContext stays NULL, and nativeResize() becomes a no-op; the
     * rest of the session is completely unaffected either way. */
    (void)freerdp_settings_set_bool(settings, FreeRDP_SupportDisplayControl, TRUE);
    (void)freerdp_settings_set_bool(settings, FreeRDP_DynamicResolutionUpdate, TRUE);

    /* MULTITOUCH FEATURE: advertise + load the "rdpei" dynamic channel so
     * real, multi-contact touch frames (not just a single synthesized mouse
     * pointer) can reach the server — see the RDPEI_DVC_CHANNEL_NAME
     * include-block comment above. Purely additive, same shape as
     * FreeRDP_SupportDisplayControl just above: a server without MS-RDPEI
     * support never opens the channel, ChannelConnected never fires for
     * "rdpei", hctx->rdpeiContext stays NULL, and nativeSendTouchFrame()
     * becomes a no-op — the rest of the session (including the pre-existing
     * single-pointer TOUCHPAD/DIRECT mouse modes) is unaffected either way.
     * FreeRDP_MultiTouchGestures is deliberately left at its default
     * (FALSE) — that flag controls FreeRDP's *own* OS-level pinch/pan
     * gesture recognizer on some platforms, which this project doesn't use
     * (gesture recognition already happens locally in Compose — see
     * detectTransformGestures in RdpSessionActivity.kt); this client only
     * wants raw contact frames relayed, not FreeRDP synthesizing gesture
     * PDUs on top of them. */
    (void)freerdp_settings_set_bool(settings, FreeRDP_MultiTouchInput, TRUE);

    if (!freerdp_client_load_addins(instance->context->channels, settings))
        LOGE("freerdp_client_load_addins failed — live resize (disp channel) / multitouch (rdpei channel) unavailable this session");

    /* REMOTEAPP-WINDOWS FEATURE: register the RAIL window-order callbacks
     * unconditionally, the same way xfreerdp's xf_rail_init() does — this is
     * purely registering function pointers on instance->context->update->
     * window (always non-NULL; allocated by FreeRDP core's own update_new(),
     * unrelated to whether "rail" the channel ever opens — see the
     * RAIL_SVC_CHANNEL_NAME include-block comment above), so it's harmless
     * for a non-RemoteApp session: if FreeRDP_RemoteApplicationMode is FALSE
     * (the pre-existing default) or the server has no MS-RDPERP support, the
     * server simply never sends window orders and these callbacks never
     * fire, exactly like dispContext/cliprdrContext above when their channel
     * doesn't open. WindowIcon/WindowCachedIcon must be set alongside
     * WindowCreate/WindowUpdate/WindowDelete for the same reason — leaving
     * them NULL would crash FreeRDP's core dispatcher if a server ever
     * sends an Icon order — and now actually decode the icon's pixel data
     * (see systemsgo_rail_window_icon's doc comment) rather than discarding
     * it. */
    rdpUpdate* update = instance->context->update;
    if (update && update->window)
    {
        update->window->WindowCreate     = systemsgo_rail_window_state;
        update->window->WindowUpdate     = systemsgo_rail_window_state;
        update->window->WindowDelete     = systemsgo_rail_window_delete;
        update->window->WindowIcon       = systemsgo_rail_window_icon;
        update->window->WindowCachedIcon = systemsgo_rail_window_cached_icon;
    }
    else
    {
        LOGE("instance->context->update->window unavailable — RemoteApp multi-window tracking unavailable this session");
    }

    return TRUE;
}

/* Small helper used by systemsgo_notify_monitor_layout()'s single-monitor
 * synthetic-entry branch below — reads back the negotiated desktop size
 * from rdpSettings (post-capability exchange, so this reflects what the
 * server actually agreed to, not just what was requested). Declared ahead
 * of systemsgo_notify_monitor_layout since C requires the callee to be visible
 * before the call site. */
static BOOL instance_settings_desktop_size(systemsgoContext* hctx, UINT32* outWidth, UINT32* outHeight)
{
    if (!hctx) return FALSE;
    rdpSettings* settings = hctx->context.settings;
    if (!settings) return FALSE;
    *outWidth = freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth);
    *outHeight = freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight);
    return TRUE;
}

/* MULTI-MONITOR FEATURE: hands the currently-declared/selected monitor
 * layout to Kotlin (AFreeRdpBridge.onNativeMonitorLayout). Called once from
 * systemsgo_post_connect() right after a successful connect (so the UI's
 * monitor selector has something to show immediately), and again from
 * nativeSelectMonitor() whenever a live switch succeeds. A NULL/empty
 * declaredMonitors (single-monitor session) still emits one entry so the
 * UI can tell "1 monitor total, feature hidden" from "no data yet". */
static void systemsgo_notify_monitor_layout(systemsgoContext* hctx, JNIEnv* env)
{
    if (!hctx || !hctx->onMonitorLayoutMethod) return;

    UINT32 count = hctx->declaredMonitorCount > 0 ? hctx->declaredMonitorCount : 1;
    jintArray jIds     = (*env)->NewIntArray(env, (jsize)count);
    jintArray jXs      = (*env)->NewIntArray(env, (jsize)count);
    jintArray jYs      = (*env)->NewIntArray(env, (jsize)count);
    jintArray jWidths  = (*env)->NewIntArray(env, (jsize)count);
    jintArray jHeights = (*env)->NewIntArray(env, (jsize)count);
    jbooleanArray jPrimary = (*env)->NewBooleanArray(env, (jsize)count);
    jintArray jOrients = (*env)->NewIntArray(env, (jsize)count);
    jintArray jDpis    = (*env)->NewIntArray(env, (jsize)count);
    if (!jIds || !jXs || !jYs || !jWidths || !jHeights || !jPrimary || !jOrients || !jDpis)
        return; /* OOM — best-effort feature, skip this notification rather than crash */

    if (hctx->declaredMonitorCount > 0 && hctx->declaredMonitors)
    {
        for (UINT32 i = 0; i < count; i++)
        {
            jint id = (jint)hctx->declaredMonitors[i].id;
            jint x = (jint)hctx->declaredMonitors[i].x;
            jint y = (jint)hctx->declaredMonitors[i].y;
            jint w = (jint)hctx->declaredMonitors[i].width;
            jint h = (jint)hctx->declaredMonitors[i].height;
            jboolean primary = hctx->declaredMonitors[i].isPrimary ? JNI_TRUE : JNI_FALSE;
            jint orient = (jint)hctx->declaredMonitors[i].orientationDegrees;
            jint dpi = (jint)hctx->declaredMonitors[i].dpiScaleFactor;
            (*env)->SetIntArrayRegion(env, jIds, (jsize)i, 1, &id);
            (*env)->SetIntArrayRegion(env, jXs, (jsize)i, 1, &x);
            (*env)->SetIntArrayRegion(env, jYs, (jsize)i, 1, &y);
            (*env)->SetIntArrayRegion(env, jWidths, (jsize)i, 1, &w);
            (*env)->SetIntArrayRegion(env, jHeights, (jsize)i, 1, &h);
            (*env)->SetBooleanArrayRegion(env, jPrimary, (jsize)i, 1, &primary);
            (*env)->SetIntArrayRegion(env, jOrients, (jsize)i, 1, &orient);
            (*env)->SetIntArrayRegion(env, jDpis, (jsize)i, 1, &dpi);
        }
    }
    else
    {
        /* Single-monitor session: one synthetic entry using the connected
         * desktop size, so UI code can uniformly read monitors[0] rather
         * than special-casing "no multi-monitor data at all". */
        jint zero = 0, one = 1, hundred = 100;
        UINT32 w = 0, h = 0;
        if (instance_settings_desktop_size(hctx, &w, &h))
        {
            jint jw = (jint)w, jh = (jint)h;
            (*env)->SetIntArrayRegion(env, jWidths, 0, 1, &jw);
            (*env)->SetIntArrayRegion(env, jHeights, 0, 1, &jh);
        }
        (*env)->SetIntArrayRegion(env, jIds, 0, 1, &zero);
        (*env)->SetIntArrayRegion(env, jXs, 0, 1, &zero);
        (*env)->SetIntArrayRegion(env, jYs, 0, 1, &zero);
        jboolean primary = JNI_TRUE;
        (*env)->SetBooleanArrayRegion(env, jPrimary, 0, 1, &primary);
        (*env)->SetIntArrayRegion(env, jOrients, 0, 1, &zero);
        (*env)->SetIntArrayRegion(env, jDpis, 0, 1, &hundred);
        (void)one;
    }

    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onMonitorLayoutMethod,
                            jIds, jXs, jYs, jWidths, jHeights, jPrimary, jOrients, jDpis);
    (*env)->DeleteLocalRef(env, jIds);
    (*env)->DeleteLocalRef(env, jXs);
    (*env)->DeleteLocalRef(env, jYs);
    (*env)->DeleteLocalRef(env, jWidths);
    (*env)->DeleteLocalRef(env, jHeights);
    (*env)->DeleteLocalRef(env, jPrimary);
    (*env)->DeleteLocalRef(env, jOrients);
    (*env)->DeleteLocalRef(env, jDpis);
}

static BOOL systemsgo_post_connect(freerdp* instance)
{
    if (!gdi_init(instance, PIXEL_FORMAT_BGRA32))
        return FALSE;

    rdpUpdate* update = instance->context->update;
    if (update)
        update->EndPaint = systemsgo_on_frame;  // BUG-1 FIX: was NULL → black screen on all RDP sessions

    /* BUG-STATE FIX: onNativeState was stored in nativeInit but never called,
     * so bridge.stateChanges never emitted any value from native, leaving
     * RdpRemoteAdapter._sessionState stuck at CONNECTING forever and the UI
     * showing a loading screen even when the connection succeeded.
     * Notify Kotlin that we are now CONNECTED (state = 2). */
    systemsgoContext* hctx = (systemsgoContext*)instance->context;
    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return TRUE;   /* connection is alive; state notification failure is non-fatal */
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return TRUE;
    }
    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onStateMethod, 2 /* CONNECTED */);

    /* XRDP-CAPABILITY-DETECTION FEATURE: report which security protocol was
     * actually negotiated — not just what this client requested. FreeRDP's
     * nego_security_connect() (libfreerdp/core/nego.c) overwrites
     * FreeRDP_NlaSecurity/TlsSecurity/RdpSecurity in place on `settings`
     * with the single outcome it settled on before PostConnect ever runs,
     * so reading them back here (post-connect) reflects the real result of
     * negotiation, not this client's original request — using only the
     * public freerdp_settings_get_bool() API (nego.c's PROTOCOL_* enum and
     * SelectedProtocol field live in libfreerdp/core/nego.h, an internal
     * header this client-only JNI target does not link against). Checked in
     * NLA-first order since a NLA-capable connection also has TlsSecurity
     * TRUE (NLA is CredSSP-over-TLS), so checking TLS first would
     * misreport every NLA connection as plain TLS. */
    {
        rdpSettings* negSettings = instance->context->settings;
        const char* securityProtocolName = "RDP";
        if (freerdp_settings_get_bool(negSettings, FreeRDP_NlaSecurity))
            securityProtocolName = "NLA";
        else if (freerdp_settings_get_bool(negSettings, FreeRDP_TlsSecurity))
            securityProtocolName = "TLS";
        else if (freerdp_settings_get_bool(negSettings, FreeRDP_RdpSecurity))
            securityProtocolName = "RDP";
        if (hctx->onSecurityProtocolNegotiatedMethod)
        {
            (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onSecurityProtocolNegotiatedMethod,
                                    (*env)->NewStringUTF(env, securityProtocolName));
        }
    }

    /* MULTI-MONITOR FEATURE: hand the negotiated/declared layout to Kotlin
     * now that the desktop size is final (post capability exchange), so the
     * monitor selector has data the moment the UI shows CONNECTED. */
    systemsgo_notify_monitor_layout(hctx, env);

    /* CODEC-NEGOTIATION FEATURE (part 3): gdi_init() just above is what
     * runs gdi_graphics_pipeline_init() (when FreeRDP_SupportGraphicsPipeline
     * is TRUE — see systemsgo_apply_codec_preference()), and THAT is what
     * installs the real RdpgfxClientContext::SurfaceCommand handler that
     * decodes/paints every GFX frame. The wrap must happen here, after
     * gdi_init() has run, not in systemsgo_on_channel_connected() where
     * rdpgfxContext is first captured — installing it there would just be
     * silently clobbered by gdi_graphics_pipeline_init() a moment later.
     * See systemsgo_gfx_surface_command()'s doc comment for what the wrapper
     * does; guarded by !gfxOrigSurfaceCommand so a second PostConnect (e.g.
     * a reconnect within the same nativeConnect() attempt, if FreeRDP ever
     * calls PostConnect more than once) doesn't wrap its own wrapper.
     *
     * CAVEAT: if a future FreeRDP tag restructures
     * gdi_graphics_pipeline_init() to install SurfaceCommand lazily (e.g.
     * on first frame) instead of inline during gdi_init(), this ordering
     * assumption breaks — codec reporting would silently stop updating the
     * diagnostics screen without affecting the actual rendered session.
     * Verify against the real FreeRDP tag if that's ever observed. */
    if (hctx->rdpgfxContext && !hctx->gfxOrigSurfaceCommand)
    {
        hctx->rdpgfxContext->custom = hctx;
        hctx->gfxOrigSurfaceCommand = hctx->rdpgfxContext->SurfaceCommand;
        hctx->rdpgfxContext->SurfaceCommand = systemsgo_gfx_surface_command;
    }

    /* CODEC-NEGOTIATION FEATURE (part 3) — compatibility verification: does
     * installing the SurfaceCommand wrap above break any of the following
     * existing features? Checked against this file's actual resize/monitor
     * code paths, not assumed:
     *
     *   - LIVE-RESIZE FIX / Dynamic Resolution Update: nativeResize() below
     *     sends a DISPLAY_CONTROL_MONITOR_LAYOUT over the already-open "disp"
     *     channel (DispClientContext::SendMonitorLayout) — it never touches
     *     rdpgfxContext, never re-runs gdi_init(), and systemsgo_post_connect()
     *     (this function) only runs once per connection attempt. So the
     *     SurfaceCommand wrap installed above is installed exactly once and
     *     stays installed across any number of live resizes — no re-wrap, no
     *     dangling pointer, no gap where codec reporting silently stops.
     *     (The one real risk to this ordering assumption is the
     *     gdi_graphics_pipeline_init()-timing CAVEAT immediately above this
     *     block, which is about a future FreeRDP tag, not about resize.)
     *   - MULTI-MONITOR FEATURE: nativeSelectMonitor() below is the same
     *     story — it only calls disp->SendMonitorLayout with a different
     *     monitor subset; rdpgfxContext and its wrapped SurfaceCommand are
     *     untouched. Switching monitors mid-session does not interrupt or
     *     reset codec reporting.
     *   - Samsung DeX / external-display, Split Screen (SplitScreenActivity),
     *     Picture-in-Picture: all three are Android-side presentation changes
     *     (which Activity/window shows the same rendered frames, and at what
     *     size) — none of them create a second native connection or call
     *     back into this JNI layer at all. SplitScreenActivity in particular
     *     hosts two fully independent RdpSessionScreen instances, each with
     *     its own RdpRemoteAdapter -> its own AFreeRdpBridge -> its own
     *     systemsgoContext, so there is no shared rdpgfxContext/wrap state
     *     between panes to worry about. DeX/PiP resizing the Android surface
     *     that frames are drawn into is exactly what LIVE-RESIZE FIX's
     *     nativeResize() already handles above, so it inherits that same
     *     "wrap survives" guarantee.
     *   - REMOTE-AUDIO FEATURE: "rdpsnd"/"audin" are entirely separate
     *     dynamic virtual channels from "rdpgfx" (see systemsgo_on_channel_
     *     connected()) with their own ChannelConnected hooks, wired a few
     *     lines below this block. No shared state with rdpgfxContext/
     *     gfxOrigSurfaceCommand.
     *   - High-DPI: this project has no dedicated high-DPI code path — DPI
     *     is communicated to the server as DesktopScaleFactor/
     *     DeviceScaleFactor fields on the same DISPLAY_CONTROL_MONITOR_LAYOUT
     *     struct nativeResize()/nativeSelectMonitor() already send over
     *     "disp" (see hctx->declaredMonitors[i].dpiScaleFactor). Decoding a
     *     higher-resolution/higher-DPI surface is purely a matter of the
     *     negotiated codec's own tile size handling inside
     *     gfxOrigSurfaceCommand, which this wrap always chains to
     *     unconditionally — no DPI-specific interaction with the wrap. */
    /* REMOTE-AUDIO FEATURE: rdpsnd/audin ChannelConnected events for static
     * channels can fire before PostConnect in some negotiation orders and
     * after it in others — re-check here so the very first UI update after
     * CONNECTED already reflects an audio channel that connected early. */
    systemsgo_notify_audio_channel_state(hctx);

    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);

    return TRUE;
}

static void systemsgo_post_disconnect(freerdp* instance)
{
    gdi_free(instance);

    /* BUG-STATE FIX: mirror of the post_connect fix — notify Kotlin that the
     * session is now DISCONNECTED (state = 0) so the UI can react correctly. */
    systemsgoContext* hctx = (systemsgoContext*)instance->context;
    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return;
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return;
    }

    /* XRDP-ERRINFO FIX: freerdp_connect() failing outright (handled in
     * nativeConnect below) only covers connections that never came up. It
     * says nothing about a session that connected fine and was then torn
     * down by the *server* — which is exactly what happens when xrdp's
     * sesman can't reach/start the Xorg (or Xvnc) backend after a
     * successful login: xrdp sends a Set Error Info PDU and closes the
     * channel. FreeRDP surfaces that PDU's code via freerdp_error_info(),
     * completely separately from freerdp_get_last_error(). Until now
     * nothing here ever read it, so that whole class of failure reached
     * Kotlin as a bare, reason-less DISCONNECTED (state=0) — indistinguishable
     * from the user tapping "disconnect". Route it through the same
     * onErrorMethod nativeConnect's failure path already uses (see below);
     * RdpErrorMessages.kt on the Kotlin side recognizes the ERRINFO_*
     * name and turns it into an xrdp-aware, human-readable message instead
     * of the FreeRDP-internal symbol name. Deliberately emitted *before*
     * the state callback so any UI code that clears the error banner on
     * DISCONNECTED (state=0) still sees this message land first. */
    UINT32 errInfo = freerdp_error_info(instance);
    if (errInfo != 0 /* ERRINFO_SUCCESS/NONE */)
    {
        const char* errInfoName = freerdp_get_error_info_name(errInfo);
        (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onErrorMethod,
                                (*env)->NewStringUTF(env, errInfoName ? errInfoName : "ERRINFO_UNKNOWN"));
    }

    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onStateMethod, 0 /* DISCONNECTED */);
    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
}

/* ── JNI exported functions ─────────────────────────────────────────────── */

/* AUDIO-BACKEND FIX: static (no handle/instance — this reports a compile-time
 * property of this .so, not per-connection state), matching AFreeRdpBridge's
 * @JvmStatic external fun declaration. See SYSTEMSGO_AUDIO_BACKEND_AVAILABLE's
 * doc comment above for what this actually reflects and why it can't be
 * computed at runtime instead. */
JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeIsAudioBackendAvailable(JNIEnv* env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return SYSTEMSGO_AUDIO_BACKEND_AVAILABLE ? JNI_TRUE : JNI_FALSE;
}

/* PRINTER-REDIRECT FEATURE: static (no handle/instance), same reasoning as
 * nativeIsAudioBackendAvailable() just above — see
 * SYSTEMSGO_PRINT_BACKEND_AVAILABLE's doc comment for what this reflects. */
JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeIsPrinterBackendAvailable(JNIEnv* env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return SYSTEMSGO_PRINT_BACKEND_AVAILABLE ? JNI_TRUE : JNI_FALSE;
}

/* WEBCAM-REDIRECT FEATURE: static (no handle/instance), same reasoning as
 * nativeIsPrinterBackendAvailable() just above — see
 * SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE's doc comment for what this reflects. */
JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeIsWebcamBackendAvailable(JNIEnv* env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE ? JNI_TRUE : JNI_FALSE;
}

/* SMARTCARD-REDIRECT FEATURE: static (no handle/instance), same reasoning as
 * nativeIsPrinterBackendAvailable() above — see
 * SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE's doc comment for what this reflects. */
JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeIsSmartcardBackendAvailable(JNIEnv* env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE ? JNI_TRUE : JNI_FALSE;
}

/* CODEC-NEGOTIATION FEATURE: static (no handle/instance), same reasoning as
 * nativeIsPrinterBackendAvailable() above — see
 * SYSTEMSGO_H264_BACKEND_AVAILABLE's doc comment for what this reflects. */
JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeIsH264BackendAvailable(JNIEnv* env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return SYSTEMSGO_H264_BACKEND_AVAILABLE ? JNI_TRUE : JNI_FALSE;
}

/* CODEC-NEGOTIATION FEATURE: static (no handle/instance), same reasoning as
 * nativeIsH264BackendAvailable() just above — see
 * SYSTEMSGO_AV1_BACKEND_AVAILABLE's doc comment (both here and in
 * CMakeLists.txt) for what this reflects and its experimental/
 * FreeRDP-server-only caveat. */
JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeIsAv1BackendAvailable(JNIEnv* env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return SYSTEMSGO_AV1_BACKEND_AVAILABLE ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeInit(JNIEnv* env, jobject thiz)
{
    freerdp* instance = freerdp_new();
    if (!instance)
        return 0;

    instance->ContextSize = sizeof(systemsgoContext);
    instance->ContextNew  = NULL;
    instance->ContextFree = NULL;

    /* freerdp_context_new returns BOOL in FreeRDP 3.x */
    if (!freerdp_context_new(instance))
    {
        freerdp_free(instance);
        return 0;
    }

    systemsgoContext* hctx = SYSTEMSGO_CTX(instance);
    (*env)->GetJavaVM(env, &hctx->jvm);
    hctx->bridgeObjGlobalRef = (*env)->NewGlobalRef(env, thiz);

    jclass cls = (*env)->GetObjectClass(env, thiz);
    hctx->onFrameMethod = (*env)->GetMethodID(env, cls, "onNativeFrame", "(IIII[IZ)V");
    hctx->onStateMethod = (*env)->GetMethodID(env, cls, "onNativeState", "(I)V");
    hctx->onErrorMethod = (*env)->GetMethodID(env, cls, "onNativeError", "(Ljava/lang/String;)V");
    /* TLS-TOFU FIX: synchronous Kotlin callback consulted from
     * systemsgo_verify_certificate_ex() for every certificate FreeRDP could not
     * verify automatically — see AFreeRdpBridge.onNativeCertificateCheck(). */
    hctx->onCertCheckMethod = (*env)->GetMethodID(env, cls, "onNativeCertificateCheck",
                                                   "(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
    /* CLIPBOARD FIX: see systemsgo_cliprdr_server_format_data_response(). */
    hctx->onClipboardTextMethod = (*env)->GetMethodID(env, cls, "onNativeClipboardText", "(Ljava/lang/String;)V");
    /* MULTI-MONITOR FEATURE: see systemsgo_post_connect() / nativeSelectMonitor(). */
    hctx->onMonitorLayoutMethod = (*env)->GetMethodID(env, cls, "onNativeMonitorLayout",
                                                       "([I[I[I[I[I[Z[I[I)V");
    /* GENERIC-VCHANNEL FEATURE: see systemsgoContext::onChannelConnectedMethod's
     * doc comment — fired for every channel, typed or not. */
    hctx->onChannelConnectedMethod = (*env)->GetMethodID(env, cls, "onNativeChannelConnected", "(Ljava/lang/String;)V");
    hctx->onChannelDisconnectedMethod = (*env)->GetMethodID(env, cls, "onNativeChannelDisconnected", "(Ljava/lang/String;)V");

    /* REMOTE-AUDIO FEATURE: see systemsgo_on_channel_connected/disconnected(). */
    hctx->onAudioChannelStateMethod = (*env)->GetMethodID(env, cls, "onNativeAudioChannelState", "(ZZ)V");
    // LIVE-CHANNEL-STATUS FEATURE: mirrors onAudioChannelStateMethod
    // immediately above — see AFreeRdpBridge.onNativePrinterChannelState/
    // onNativeSmartcardChannelState/onNativeWebcamChannelState.
    hctx->onPrinterChannelStateMethod = (*env)->GetMethodID(env, cls, "onNativePrinterChannelState", "(Z)V");
    hctx->onSmartcardChannelStateMethod = (*env)->GetMethodID(env, cls, "onNativeSmartcardChannelState", "(Z)V");
    hctx->onWebcamChannelStateMethod = (*env)->GetMethodID(env, cls, "onNativeWebcamChannelState", "(Z)V");
    /* MULTITOUCH FEATURE (live status): mirrors onWebcamChannelStateMethod
     * immediately above. */
    hctx->onMultiTouchChannelStateMethod = (*env)->GetMethodID(env, cls, "onNativeMultiTouchChannelState", "(Z)V");
    hctx->onAudioFrameMethod = (*env)->GetMethodID(env, cls, "onNativeAudioFrame", "([BIII)V");
    /* REMOTEAPP-WINDOWS FEATURE: signatures matched exactly to
     * AFreeRdpBridge.onNativeRailWindowState(windowId: Int, title: String,
     * x: Int, y: Int, width: Int, height: Int, isVisible: Boolean, zOrder: Int)
     * and onNativeRailWindowDelete(windowId: Int) — see systemsgo_rail_window_state
     * / systemsgo_rail_window_delete above. */
    hctx->onRailWindowStateMethod = (*env)->GetMethodID(env, cls, "onNativeRailWindowState",
                                                         "(ILjava/lang/String;IIIIZI)V");
    hctx->onRailWindowDeleteMethod = (*env)->GetMethodID(env, cls, "onNativeRailWindowDelete", "(I)V");
    /* REMOTEAPP-WINDOWS FEATURE (icon decoding): matches
     * AFreeRdpBridge.onNativeRailWindowIcon(windowId: Int, width: Int,
     * height: Int, pixels: IntArray) — see systemsgo_rail_emit_icon() above. */
    hctx->onRailWindowIconMethod = (*env)->GetMethodID(env, cls, "onNativeRailWindowIcon", "(III[I)V");
    /* CODEC-NEGOTIATION FEATURE (part 3): see systemsgo_gfx_surface_command()/
     * systemsgo_notify_codec_negotiated() above. Matches
     * AFreeRdpBridge.onNativeCodecNegotiated(codecName: String). */
    hctx->onCodecNegotiatedMethod = (*env)->GetMethodID(env, cls, "onNativeCodecNegotiated", "(Ljava/lang/String;)V");
    /* XRDP-CAPABILITY-DETECTION FEATURE: see systemsgo_post_connect(). Matches
     * AFreeRdpBridge.onNativeSecurityProtocolNegotiated(protocolName: String). */
    hctx->onSecurityProtocolNegotiatedMethod = (*env)->GetMethodID(env, cls,
        "onNativeSecurityProtocolNegotiated", "(Ljava/lang/String;)V");

    /* CLIPBOARD FIX: no clipboard content and no channel until the cliprdr
     * channel actually connects (see systemsgo_on_channel_connected) and/or the
     * user copies something (nativeSendClipboardText). */
    hctx->cliprdrContext = NULL;
    hctx->localClipboardTextW = NULL;
    hctx->localClipboardTextWLen = 0;
    InitializeCriticalSection(&hctx->clipboardLock);
    hctx->clipboardLockInitialized = TRUE;

    /* MULTI-MONITOR / REMOTE-AUDIO FEATURE: no layout declared and no audio
     * channel connected until nativeConnect()/systemsgo_on_channel_connected()
     * set these, respectively. */
    hctx->declaredMonitors = NULL;
    hctx->declaredMonitorCount = 0;
    hctx->rdpsndContext = NULL;
    hctx->audinContext = NULL;

    /* CODEC-NEGOTIATION FEATURE (part 3): no rdpgfx channel and nothing
     * reported until systemsgo_on_channel_connected()/systemsgo_post_connect()/
     * systemsgo_gfx_surface_command() set these — mirrors the audio
     * initialization immediately above. */
    hctx->rdpgfxContext = NULL;
    hctx->gfxOrigSurfaceCommand = NULL;
    hctx->lastReportedCodecId = 0;
    hctx->hasReportedCodec = FALSE;

    /* REMOTEAPP-WINDOWS FEATURE: no RAIL channel and no windows tracked yet
     * until systemsgo_on_channel_connected()/systemsgo_rail_window_state() set
     * these — mirrors the disp/audio initialization immediately above. */
    hctx->railContext = NULL;
    hctx->railNextZOrder = 0;
    /* REMOTEAPP-WINDOWS FEATURE (icon decoding): no icon cache table until
     * systemsgo_rail_icon_cache_get() lazily allocates it on the first Window
     * Icon/Cached Icon order — see that function's doc comment. */
    hctx->railIconCache = NULL;
    hctx->railIconCacheNumCaches = 0;
    hctx->railIconCacheNumEntries = 0;
    hctx->railIconScratch.argb = NULL;
    hctx->railIconScratch.width = 0;
    hctx->railIconScratch.height = 0;

    instance->PreConnect    = systemsgo_pre_connect;
    instance->PostConnect   = systemsgo_post_connect;
    instance->PostDisconnect = systemsgo_post_disconnect;
    /* TLS-FIX: without these, FreeRDP had no interactive/decision path for a
     * certificate it can't automatically verify — the only lever was the
     * blanket FreeRDP_IgnoreCertificate flag (see systemsgo_verify_certificate_ex
     * above for the full rationale). */
    instance->VerifyCertificateEx        = systemsgo_verify_certificate_ex;
    instance->VerifyChangedCertificateEx = systemsgo_verify_changed_certificate_ex;

    /* LIVE-RESIZE FIX */
    hctx->dispContext = NULL;
    InitializeCriticalSection(&hctx->dispLock);
    hctx->dispLockInitialized = TRUE;
    /* MULTITOUCH FEATURE: mirrors dispLock immediately above. */
    hctx->rdpeiContext = NULL;
    InitializeCriticalSection(&hctx->rdpeiLock);
    hctx->rdpeiLockInitialized = TRUE;
    PubSub_SubscribeChannelConnected(instance->context->pubSub, systemsgo_on_channel_connected);
    PubSub_SubscribeChannelDisconnected(instance->context->pubSub, systemsgo_on_channel_disconnected);

    return (jlong)(intptr_t)instance;
}

/* GENERIC-VCHANNEL FEATURE: queues one dynamic-channel name to be requested
 * the next time nativeConnect() runs for this handle — see
 * systemsgoContext::pendingDynamicChannelNames's doc comment for the mechanism
 * and its limits. Must be called after nativeInit() (which allocates hctx)
 * and before nativeConnect() (which drains the table); calling it after
 * connecting has no effect until the *next* connect on this handle, exactly
 * like every enableXxx flag in this file. Returns false if handle is
 * invalid, name is empty/too long (see SYSTEMSGO_DYNAMIC_CHANNEL_NAME_MAX), or
 * the table is already full (see SYSTEMSGO_MAX_PENDING_DYNAMIC_CHANNELS) —
 * none of these are fatal to the session, the caller just doesn't get that
 * one extra channel. */
JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeRegisterDynamicChannel(
    JNIEnv* env, jobject thiz, jlong handle, jstring jName)
{
    (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance || !jName) return JNI_FALSE;

    systemsgoContext* hctx = SYSTEMSGO_CTX(instance);
    if (hctx->pendingDynamicChannelCount >= SYSTEMSGO_MAX_PENDING_DYNAMIC_CHANNELS)
    {
        LOGE("nativeRegisterDynamicChannel: pending table full (max %d) — ignoring request",
             SYSTEMSGO_MAX_PENDING_DYNAMIC_CHANNELS);
        return JNI_FALSE;
    }

    const char* name = (*env)->GetStringUTFChars(env, jName, NULL);
    if (!name) return JNI_FALSE;
    size_t len = strlen(name);
    jboolean ok = JNI_FALSE;
    if (len > 0 && len < SYSTEMSGO_DYNAMIC_CHANNEL_NAME_MAX)
    {
        strncpy(hctx->pendingDynamicChannelNames[hctx->pendingDynamicChannelCount], name,
                SYSTEMSGO_DYNAMIC_CHANNEL_NAME_MAX - 1);
        hctx->pendingDynamicChannelNames[hctx->pendingDynamicChannelCount][SYSTEMSGO_DYNAMIC_CHANNEL_NAME_MAX - 1] = '\0';
        hctx->pendingDynamicChannelCount++;
        ok = JNI_TRUE;
    }
    else
    {
        LOGE("nativeRegisterDynamicChannel: name empty or too long (max %d chars) — ignoring",
             SYSTEMSGO_DYNAMIC_CHANNEL_NAME_MAX - 1);
    }
    (*env)->ReleaseStringUTFChars(env, jName, name);
    return ok;
}

/* CODEC-NEGOTIATION FEATURE (part 3): maps a RDPGFX_CODECID_* wire value (as
 * sent by the server in every RDPGFX_SURFACE_COMMAND, MS-RDPEGFX 2.2.2.2) to
 * the human-readable name AFreeRdpBridge.onNativeCodecNegotiated / the
 * session diagnostics screen expect. Falls back to a numeric
 * "Unknown (0x..)" string — written into `scratch` — for any codec ID this
 * build doesn't recognize by name (e.g. a real AV1 codec ID from a future
 * confirmed FreeRDP tag; see the CAVEAT below) rather than silently
 * mislabeling it as one of the known codecs — same "degrade, don't guess"
 * approach systemsgo_apply_codec_preference() already takes for the unconfirmed
 * "GfxAV1" settings key. */
static const char* systemsgo_gfx_codec_name(UINT32 codecId, char* scratch, size_t scratchLen)
{
    switch (codecId)
    {
        case RDPGFX_CODECID_UNCOMPRESSED:     return "Uncompressed";
        case RDPGFX_CODECID_CAVIDEO:          return "RemoteFX";
        case RDPGFX_CODECID_CLEARCODEC:       return "ClearCodec";
        case RDPGFX_CODECID_PLANAR:           return "Planar (NSCodec-family)";
        case RDPGFX_CODECID_ALPHA:            return "Alpha";
        case RDPGFX_CODECID_CAPROGRESSIVE:    return "RemoteFX Progressive";
        case RDPGFX_CODECID_CAPROGRESSIVE_V2: return "RemoteFX Progressive v2";
        case RDPGFX_CODECID_AVC420:           return "H.264 AVC420";
        case RDPGFX_CODECID_AVC444:           return "H.264 AVC444";
        case RDPGFX_CODECID_AVC444v2:         return "H.264 AVC444v2";
        default:
            /* CAVEAT: no RDPGFX_CODECID_AV1 exists in any FreeRDP tag this
             * project has confirmed headers against (mirrors the AV1 CAVEAT
             * in systemsgo_apply_codec_preference() below) — if this build's
             * FreeRDP does carry AV1 GFX support under a numeric ID this
             * switch doesn't recognize, it is reported as "Unknown (0x..)"
             * here rather than guessed at. Once a real tag with a confirmed
             * RDPGFX_CODECID_AV1 (or equivalent) is pinned, add its case
             * above explicitly instead of relying on this fallback. */
            snprintf(scratch, scratchLen, "Unknown (0x%02X)", (unsigned)codecId);
            return scratch;
    }
}

/* CODEC-NEGOTIATION FEATURE (part 3): notifies Kotlin
 * (AFreeRdpBridge.onNativeCodecNegotiated) of the codec name for `codecId`.
 * Attaches to the JVM if called from a detached native thread, same pattern
 * as systemsgo_notify_audio_channel_state()/systemsgo_notify_monitor_layout(). */
static void systemsgo_notify_codec_negotiated(systemsgoContext* hctx, UINT32 codecId)
{
    if (!hctx || !hctx->onCodecNegotiatedMethod) return;

    JNIEnv* env;
    bool didAttach = false;
    int getEnvResult = (*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if ((*hctx->jvm)->AttachCurrentThread(hctx->jvm, &env, NULL) != JNI_OK)
            return; /* best-effort feature — skip this notification rather than crash */
        didAttach = true;
    } else if (getEnvResult != JNI_OK) {
        return;
    }

    char scratch[32];
    const char* name = systemsgo_gfx_codec_name(codecId, scratch, sizeof(scratch));
    jstring jName = (*env)->NewStringUTF(env, name);
    if (jName)
    {
        (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onCodecNegotiatedMethod, jName);
        (*env)->DeleteLocalRef(env, jName);
        LOGI("codec negotiation: server is using %s", name);
    }

    if (didAttach)
        (*hctx->jvm)->DetachCurrentThread(hctx->jvm);
}

/* CODEC-NEGOTIATION FEATURE (part 3): wraps — does NOT replace — the
 * SurfaceCommand handler gdi_graphics_pipeline_init() installs on
 * rdpgfxContext when gdi_init() runs in systemsgo_post_connect(). That handler
 * is what actually decodes/paints every GFX frame and MUST keep running
 * unmodified; this wrapper only *observes* cmd->codecId (the real per-
 * command codec the server chose, MS-RDPEGFX 2.2.2.2
 * RDPGFX_CMDID_WIRETOSURFACE_1/2) and then always chains to the saved
 * original.
 *
 * This — not RdpgfxClientContext::CapsConfirm — is this build's "CapsConfirm
 * or its equivalent": RDPGFX_CAPS_CONFIRM_PDU only reports which capability
 * VERSION the server accepted (e.g. v10.6, which permits both AVC420 *and*
 * AVC444 simultaneously), not which codec the server then actually uses
 * frame-to-frame — SurfaceCommand's codecId is the only place that is
 * unambiguous, and it can legitimately change mid-session (see
 * lastReportedCodecId's doc comment on NetworkAutoDetect-driven changes),
 * which CapsConfirm — sent once, at connection setup — could never reflect
 * anyway.
 *
 * Reports to Kotlin only on the first command and again on any change, not
 * on every frame — see hctx->lastReportedCodecId/hasReportedCodec. */
static UINT systemsgo_gfx_surface_command(RdpgfxClientContext* context, const RDPGFX_SURFACE_COMMAND* cmd)
{
    systemsgoContext* hctx = (context && context->custom) ? (systemsgoContext*)context->custom : NULL;

    if (hctx && cmd && (!hctx->hasReportedCodec || cmd->codecId != hctx->lastReportedCodecId))
    {
        hctx->lastReportedCodecId = cmd->codecId;
        hctx->hasReportedCodec = TRUE;
        systemsgo_notify_codec_negotiated(hctx, cmd->codecId);
    }

    /* Chain to the real decoder/painter — never swallow the command. A NULL
     * original (shouldn't happen once wired up in systemsgo_post_connect(), but
     * defensive here the same way every other optional-channel path in this
     * file is) just means the frame silently doesn't paint, same failure
     * shape as if this wrapper had never been installed at all. */
    if (hctx && hctx->gfxOrigSurfaceCommand)
        return hctx->gfxOrigSurfaceCommand(context, cmd);
    return CHANNEL_RC_OK;
}

/* CODEC-NEGOTIATION FEATURE: applies `preference` (one of the
 * SYSTEMSGO_CODEC_PREFERENCE_* values — see their doc comment above) to
 * `settings`, called from nativeConnect() below before freerdp_connect().
 * This only sets what the *client offers*; the server still makes the
 * final codec choice during the RDPGFX capability exchange (CapsAdvertise/
 * CapsConfirm), and a server that supports none of what was offered simply
 * falls back to the pre-GFX bitmap/RemoteFX/NSCodec path FreeRDP has always
 * supported — see the DISABLE_MODERN_CODECS branch below for how that same
 * fallback is reached deliberately. Nothing here can abort or interrupt the
 * connection: every branch only flips FreeRDP settings, it never returns
 * failure.
 *
 * Requirement mapping:
 *   - "Support AV1 whenever available and supported by both ends" / "Use
 *     the highest quality codec supported by both client and server" ->
 *     AUTO tries AV1 first (if SYSTEMSGO_AV1_BACKEND_AVAILABLE), then H.264
 *     AVC444/AVC420 (if SYSTEMSGO_H264_BACKEND_AVAILABLE); FreeRDP's own GFX
 *     capability negotiation (not this function) is what actually picks
 *     the best *mutually* supported one from everything advertised here —
 *     this function's job is only to make sure every codec this build can
 *     decode gets offered, so the negotiation has the best set to choose
 *     from.
 *   - "If AV1 unavailable, fall back to H.264" / "If H.264 unavailable,
 *     fall back to standard RDP codecs" -> both AV1 and H.264 are gated on
 *     their own *_BACKEND_AVAILABLE flag, so a build without one simply
 *     never advertises it, and a build/preference with neither set never
 *     enables SupportGraphicsPipeline at all, leaving the connection on
 *     FreeRDP's non-GFX path (RemoteFX/NSCodec/bitmap — see
 *     FreeRDP_RemoteFxCodecMode above, already configured unconditionally).
 */
static void systemsgo_apply_codec_preference(rdpSettings* settings, jint preference)
{
    const BOOL disableModern = (preference == SYSTEMSGO_CODEC_PREFERENCE_DISABLE_MODERN);
    const BOOL wantAv1  = SYSTEMSGO_AV1_BACKEND_AVAILABLE  && !disableModern &&
                          (preference == SYSTEMSGO_CODEC_PREFERENCE_AUTO ||
                           preference == SYSTEMSGO_CODEC_PREFERENCE_PREFER_AV1);
    const BOOL wantH264 = SYSTEMSGO_H264_BACKEND_AVAILABLE && !disableModern &&
                          (preference == SYSTEMSGO_CODEC_PREFERENCE_AUTO ||
                           preference == SYSTEMSGO_CODEC_PREFERENCE_PREFER_H264 ||
                           /* AUTO/PREFER_AV1 both still advertise H.264 as the
                            * fallback codec within the SAME capability
                            * exchange — this is what lets a server that
                            * doesn't understand AV1 (i.e. every stock
                            * Windows host today, see
                            * SYSTEMSGO_AV1_BACKEND_AVAILABLE's caveat) still
                            * land on H.264 instead of dropping to RemoteFX,
                            * without a second connection attempt. */
                           preference == SYSTEMSGO_CODEC_PREFERENCE_PREFER_AV1);

    if (disableModern || (!wantAv1 && !wantH264)) {
        /* "Disable modern codecs" (explicit) or nothing to offer (neither
         * codec available in this build, e.g. the CI prebuilt hasn't
         * cross-compiled openh264/dav1d yet — see the *_BACKEND_AVAILABLE
         * doc comments in CMakeLists.txt). Leave SupportGraphicsPipeline
         * off entirely: FreeRDP falls back to its pre-GFX path
         * (RemoteFX/NSCodec/bitmap orders), already configured
         * unconditionally above via FreeRDP_RemoteFxCodecMode. This is the
         * "gracefully fall back to the standard RDP codecs" requirement. */
        (void)freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, FALSE);
        LOGI("codec negotiation: modern (GFX) codecs disabled — using "
             "standard RemoteFX/NSCodec/bitmap path");
        return;
    }

    (void)freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, TRUE);
    /* Let the server pick progressive tiling for whichever codec it settles
     * on — reduces bytes-on-the-wire during scrolling/partial redraws
     * regardless of AV1 vs H.264 vs neither, same rationale as the
     * BANDWIDTH FIX block above for the non-GFX path. */
    (void)freerdp_settings_set_bool(settings, FreeRDP_GfxProgressive, TRUE);

    if (wantH264) {
        (void)freerdp_settings_set_bool(settings, FreeRDP_GfxH264,     TRUE); /* AVC420 */
        (void)freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444,   TRUE);
        (void)freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444v2, TRUE);
        LOGI("codec negotiation: offering H.264 (AVC420/AVC444/AVC444v2)");
    }

    if (wantAv1) {
        /* CONFIRMED (this pass, live web access): the real FreeRDP_Settings_
         * Keys_Bool AV1 key is "GfxCodecAV1", not "GfxAV1" as this file
         * previously guessed. Verified directly against FreeRDP's own merged
         * PR #12527 ("[settings,av1] add AV1 related settings", commit
         * 6232229, merged as 7d7de34 into FreeRDP:master ahead of the 3.25.0
         * release that shipped this feature): its commit message states
         * explicitly "GfxCodecAV1 to enable/disable support" and
         * "GfxCodecAV1Profile to set quality profile used". Corrected below
         * — still set by name (not by a generated FreeRDP_GfxCodecAV1 enum
         * constant) purely because this project only clones FreeRDP source
         * in CI rather than keeping a local checkout to pull the generated
         * enum header from, same reasoning as every other by-name
         * settings-set call in this file; the *value* being set is no
         * longer a guess. This FreeRDP 3.x API still resolves the key
         * string at runtime and returns FALSE (rather than failing to
         * compile, or crashing) if a future tag ever renames it again — so
         * this remains safe even if it drifts further. */
        if (!freerdp_settings_set_value_for_name(settings, "GfxCodecAV1", "true")) {
            LOGE("codec negotiation: could not enable AV1 (settings key "
                 "not recognized by this FreeRDP build/tag) — falling back to "
                 "H.264/standard codecs only");
        } else {
            LOGI("codec negotiation: offering AV1 (experimental, "
                 "FreeRDP-server-only per upstream release notes)");
        }
    }
}

JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeConnect(
    JNIEnv* env, jobject thiz, jlong handle,
    jstring jHost, jint jPort, jstring jUsername, jstring jPassword, jstring jDomain,
    jint jWidth, jint jHeight, jboolean jUseNla,
    jboolean jGatewayEnabled, jstring jGwHost, jint jGwPort, jstring jGwUser, jstring jGwPass, jstring jGwDomain,
    /* ENTRA-ID-AUTH FEATURE: jGatewayAuthMode is
     * AFreeRdpBridge.GatewayAuthMode.ordinal (0=PASSWORD, 1=ENTRA_ID).
     * jGatewayBearerToken is the MSAL access token acquired by
     * GatewayTokenProvider on the Kotlin side, non-empty only when
     * jGatewayAuthMode is ENTRA_ID. When ENTRA_ID is active, gwUser/gwPass/
     * gwDomain above are deliberately left unset on the FreeRDP settings
     * (see the Gateway block below) and jGatewayBearerToken is set as
     * FreeRDP_GatewayHttpExtAuthBearer instead — confirmed present on this
     * project's FreeRDP tag (FREERDP_TAG=3.27.1, see CMakeLists.txt): the
     * RDG client (libfreerdp/core/gateway/rdg.c, rdg_establish_data_connection)
     * already reads `context->settings->GatewayHttpExtAuthBearer` and, if
     * non-empty, sets extAuth = HTTP_EXTENDED_AUTH_BEARER before building the
     * RDG_IN_DATA/RDG_OUT_DATA HTTP CONNECT requests — i.e. this is a
     * first-class, no-patch-needed FreeRDP setting on this tag, not a
     * hand-rolled header injection. */
    jint jGatewayAuthMode, jstring jGatewayBearerToken,
    /* OUTBOUND-PROXY FEATURE: routes THIS device's own TCP connection to
     * jHost:jPort (or, when Gateway is also enabled, to the gateway) through
     * a SOCKS4/SOCKS5/HTTP CONNECT proxy — the FreeRDP-native counterpart to
     * mstsc's /proxy: CLI flag, distinct from both FreeRDP_GatewayHostname
     * above (an RD Gateway is an RDP-aware relay the *server side* trusts;
     * this is a generic network proxy the *client's own OS route* goes
     * through) and SocksProxyServer.kt (that one is an in-app SOCKS server
     * exposed to OTHER apps over SSH; this is the RDP client itself dialing
     * OUT through someone else's proxy). jProxyType mirrors FreeRDP's
     * PROXY_TYPE enum ordinal for 0=NONE/1=HTTP/2=SOCKS, plus this app's own
     * 3=HTTPS (NOT a stock FreeRDP value — requires
     * the "Patch FreeRDP proxy for HTTPS support" CI step in .github/workflows/main.yml to be applied; see
     * AFreeRdpBridge.ProxyType's doc comment for the full picture) — see
     * AFreeRdpBridge.ProxyType's doc comment for why the two must stay in
     * lockstep. See the FreeRDP_ProxyType settings block below (right after
     * the Gateway block) for how this is actually applied; CAVEAT there
     * about which of these keys exist unchanged across FreeRDP point
     * releases (no network access here to check the exact 3.27.1 header —
     * same reasoning as systemsgo_apply_codec_preference()'s AV1 branch). */
    jboolean jProxyEnabled, jint jProxyType, jstring jProxyHost, jint jProxyPort,
    jstring jProxyUsername, jstring jProxyPassword,
    jboolean jRemoteAppEnabled, jstring jRemoteAppProgram, jstring jRemoteAppWorkingDir, jstring jRemoteAppCmdLine,
    jint jColorDepth, jint jCompressionQuality, jint jPerformanceMode, jboolean jIgnoreCert,
    /* CODEC-NEGOTIATION FEATURE: one of the SYSTEMSGO_CODEC_PREFERENCE_* values
     * defined above (mirrors AFreeRdpBridge.CodecPreference's ordinal) — see
     * systemsgo_apply_codec_preference() below for what each does. */
    jint jCodecPreference,
    /* UDP-TRANSPORT FEATURE: MS-RDPEMT — see the FreeRDP_SupportMultitransport
     * / FreeRDP_MultitransportFlags block below (right after the NLA/TLS
     * settings) for what this actually configures. Kotlin's
     * AFreeRdpBridge.connect()'s enableUdpTransport doc has the full
     * behavioural contract (purely additive, transparent TCP fallback). */
    jboolean jEnableUdpTransport,
    jboolean jEnableSound, jboolean jEnableMicRedirect, jboolean jEnableClipboard,
    jboolean jEnableDriveRedirect, jstring jDrivePath,
    jboolean jEnablePrinterRedirect,
    jboolean jEnableWebcamRedirect,
    jboolean jEnableSmartcardRedirect,
    jboolean jEnableParallelRedirect, jstring jParallelPath,
    jboolean jEnableSerialRedirect, jstring jSerialPath,
    /* MULTI-MONITOR FEATURE: parallel arrays, one entry per declared
     * monitor — see AFreeRdpBridge.connect()'s `monitors` param and
     * NativeMonitor. All-empty means single-monitor (pre-existing
     * behavior); see the FreeRDP_UseMultimon block below. */
    jintArray jMonitorIds, jintArray jMonitorXs, jintArray jMonitorYs,
    jintArray jMonitorWidths, jintArray jMonitorHeights,
    jbooleanArray jMonitorPrimary, jintArray jMonitorOrientations, jintArray jMonitorDpiScales)
/* BUG-1 FIX: added jColorDepth, jCompressionQuality, jPerformanceMode (were declared in
 * Kotlin external fun but missing here → UnsatisfiedLinkError / stack corruption on connect).
 * BUG-4 FIX: added jIgnoreCert so TLS cert validation is not permanently disabled.
 * REMOTEAPP: added jRemoteAppEnabled/Program/WorkingDir/CmdLine — see the
 * FreeRDP_RemoteApplication* settings block below for what these do and,
 * importantly, what they do *not* do (no RAIL virtual-channel window
 * management — see the comment there).
 * MIC-REDIRECT FEATURE: added jEnableSound/jEnableMicRedirect — see the
 * FreeRDP_AudioPlayback/FreeRDP_AudioCapture block below.
 * CLIPBOARD FIX: added jEnableClipboard — see the FreeRDP_RedirectClipboard
 * line below and the cliprdr callbacks earlier in this file.
 * DRIVE-REDIRECT FEATURE: added jEnableDriveRedirect/jDrivePath — see the
 * FreeRDP_DeviceRedirection / freerdp_client_add_device_channel block below.
 * PRINTER-REDIRECT FEATURE: added jEnablePrinterRedirect — see the printer
 * freerdp_client_add_device_channel block right after the drive one below.
 * WEBCAM-REDIRECT FEATURE: added jEnableWebcamRedirect — see the
 * freerdp_client_add_dynamic_channel("rdpecam") block right after the
 * printer one below.
 * SMARTCARD-REDIRECT FEATURE: added jEnableSmartcardRedirect — see the
 * "smartcard" freerdp_client_add_device_channel block right after the
 * webcam one below.
 * PARALLEL-REDIRECT FEATURE: added jEnableParallelRedirect/jParallelPath —
 * see the "parallel" freerdp_client_add_device_channel block right after
 * the smartcard one below. Same shape as jEnableDriveRedirect/jDrivePath
 * (a device that needs a local path), not jEnablePrinterRedirect/
 * jEnableSmartcardRedirect (which need no path) — except here the path has
 * no app-owned fallback, so an empty jParallelPath skips registration
 * entirely, same as an empty jDrivePath already does for drive.
 * SERIAL-REDIRECT FEATURE: added jEnableSerialRedirect/jSerialPath — see
 * the "serial" freerdp_client_add_device_channel block right after the
 * parallel one below. Same shape/reasoning as jEnableParallelRedirect/
 * jParallelPath immediately above, just a "serial" RDPDR device (MS-RDPESP)
 * instead of "parallel". */
{
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance) return JNI_FALSE;
    rdpSettings* settings = instance->context->settings;

    /* ROOT-CAUSE FIX: reset per-attempt so a rejection from a *previous*
     * connect() call (e.g. an earlier reconnect attempt) can't suppress the
     * generic-error path for a failure that has nothing to do with the
     * certificate this time. */
    SYSTEMSGO_CTX(instance)->certRejectedLocally = FALSE;

    /* LIVE-CHANNEL-STATUS FEATURE: reset + re-derive per-attempt, same
     * lifecycle as certRejectedLocally just above — a stale TRUE from a
     * previous connect() (e.g. an earlier reconnect attempt that had
     * printer redirect on) must not leak into a new attempt whose profile
     * turned it off. Mirrors the exact condition gating the
     * freerdp_client_add_device_channel() calls for these two devices
     * further down, so "requested" here always means the same thing as
     * "actually registered" there. See hctx->printerRedirectRequested's
     * doc comment for why this can't just be jEnablePrinterRedirect/
     * jEnableSmartcardRedirect alone. */
    SYSTEMSGO_CTX(instance)->rdpdrChannelConnected = FALSE;
    SYSTEMSGO_CTX(instance)->printerDeviceAnnounceSeen = FALSE;
    SYSTEMSGO_CTX(instance)->printerDeviceAccepted = FALSE;
    SYSTEMSGO_CTX(instance)->smartcardDeviceAnnounceSeen = FALSE;
    SYSTEMSGO_CTX(instance)->smartcardDeviceAccepted = FALSE;
    SYSTEMSGO_CTX(instance)->printerRedirectRequested =
        (jEnablePrinterRedirect && SYSTEMSGO_PRINT_BACKEND_AVAILABLE) ? TRUE : FALSE;
    SYSTEMSGO_CTX(instance)->smartcardRedirectRequested =
        (jEnableSmartcardRedirect && SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE) ? TRUE : FALSE;
    SYSTEMSGO_CTX(instance)->webcamChannelConnected = FALSE;

    /* CODEC-NEGOTIATION FEATURE (part 3): reset per-attempt, same lifecycle
     * as webcamChannelConnected just above — a stale "last reported codec"
     * from a previous connect() attempt must not suppress the first report
     * of this fresh attempt (which may legitimately renegotiate a different
     * codec, e.g. a reconnect after toggling CodecPreference in Advanced
     * Settings). rdpgfxContext/gfxOrigSurfaceCommand are intentionally left
     * alone here — they're re-derived from scratch by
     * systemsgo_on_channel_connected()/systemsgo_post_connect() regardless, and
     * this block runs before either has a chance to run for this attempt. */
    SYSTEMSGO_CTX(instance)->lastReportedCodecId = 0;
    SYSTEMSGO_CTX(instance)->hasReportedCodec = FALSE;

    const char* host   = (*env)->GetStringUTFChars(env, jHost,     NULL);
    const char* user   = (*env)->GetStringUTFChars(env, jUsername, NULL);
    const char* pass   = (*env)->GetStringUTFChars(env, jPassword, NULL);
    const char* domain = (*env)->GetStringUTFChars(env, jDomain,   NULL);
    /* BUG-6 FIX: GetStringUTFChars returns NULL on OOM (low-RAM devices with ~512MB).
     * Passing NULL to freerdp_settings_set_string causes a native crash. */
    if (!host || !user || !pass || !domain) {
        if (host)   (*env)->ReleaseStringUTFChars(env, jHost,     host);
        if (user)   (*env)->ReleaseStringUTFChars(env, jUsername, user);
        if (pass)   (*env)->ReleaseStringUTFChars(env, jPassword, pass);
        if (domain) (*env)->ReleaseStringUTFChars(env, jDomain,   domain);
        return JNI_FALSE;
    }

    /* (void) casts suppress [[nodiscard]] warnings in FreeRDP 3.23+ */
    (void)freerdp_settings_set_string(settings, FreeRDP_ServerHostname, host);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_ServerPort,     (UINT32)jPort);
    (void)freerdp_settings_set_string(settings, FreeRDP_Username,       user);
    (void)freerdp_settings_set_string(settings, FreeRDP_Password,       pass);
    (void)freerdp_settings_set_string(settings, FreeRDP_Domain,         domain);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth,   (UINT32)jWidth);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight,  (UINT32)jHeight);

    /* ARABIC-KEYBOARD FIX (xrdp/Unicode-input audit): without this, every
     * character sent by nativeSendUnicode() below (the real virtual
     * keyboard's whole Arabic/English text path — see SessionKeyboard.kt's
     * top-of-file doc comment and RdpRemoteAdapter.sendText) is silently
     * dropped and NEVER reaches the server, on xrdp or Windows alike.
     *
     * freerdp_input_send_unicode_keyboard_event() (called from
     * nativeSendUnicode) is gated, on both the fastpath and slow-path send
     * routines, behind the client-side FreeRDP_UnicodeInput setting:
     *   if (!freerdp_settings_get_bool(input->context->settings, FreeRDP_UnicodeInput))
     *   {
     *       WLog_WARN(TAG, "Unicode input not supported by server.");
     *       return FALSE;
     *   }
     * (libfreerdp/core/input.c, both input_send_fastpath_unicode_keyboard_event()
     * and its slow-path counterpart). This is FreeRDP's client-side opt-in
     * for the Unicode keyboard PDU — the same flag xfreerdp's `+unicode`
     * command-line switch sets — and it defaults to FALSE like every other
     * FreeRDP toggle option, so it has to be turned on explicitly here.
     * Since nativeSendUnicode() discards the BOOL return value
     * ((void)freerdp_input_send_unicode_keyboard_event(...)), this failure
     * was completely silent: no crash, no error surfaced to Kotlin, no log
     * visible outside FreeRDP's own WLog — just Arabic (and, in fact, every
     * other character typed through the on-screen keyboard, English
     * included) never arriving at the remote session.
     *
     * This is unconditional, not xrdp-specific — every RDP server this app
     * can connect to (xrdp or Windows) requires the client to opt in this
     * way before it will accept the Unicode keyboard PDU — but it's exactly
     * what makes the xrdp Arabic-typing path work end to end. */
    (void)freerdp_settings_set_bool(settings, FreeRDP_UnicodeInput, TRUE);

    /* XRDP-AUDIT NOTE: FreeRDP_KeyboardLayout/-Type/-SubType are deliberately
     * left unset (FreeRDP's own default) rather than hardcoded to an Arabic
     * (101/102) or US layout id here. Two reasons:
     *  1. xrdp specifically ignores the client-advertised layout for actual
     *     key mapping — it maps scancodes using the *server-side* X11
     *     keyboard layout (whatever the xrdp host's X session is configured
     *     with), so a value sent here would be cosmetic for xrdp at best and
     *     misleading at worst if it disagreed with the server's real layout.
     *  2. This client's entire Arabic-typing path (the on-screen keyboard,
     *     see SessionKeyboard.kt) goes over the Unicode keyboard PDU set up
     *     just above — freerdp_input_send_unicode_keyboard_event() carries a
     *     raw UTF-16 code unit and is entirely independent of whichever
     *     KeyboardLayout id was negotiated at connect time, on both xrdp and
     *     Windows hosts. Only the *physical* hardware-keyboard/scancode path
     *     (nativeSendKey, ExtraKeysBar, the sticky-modifier combo mode in
     *     SessionKeyboard.kt) would ever consult it, and even there it's the
     *     server's own local layout that ultimately resolves the scancode —
     *     matching how a real physical Arabic keyboard plugged into any RDP
     *     client behaves.
     *  Leaving this at FreeRDP's default (letting the server decide) is
     *  therefore the correct choice for this app, not an oversight. */

    /* MULTI-MONITOR FEATURE: mirrors mstsc's "Use all my monitors for the
     * remote session" — this client declares the monitor layout it wants
     * the session spanned across (FreeRDP_UseMultimon +
     * FreeRDP_MonitorCount + one rdpMonitor per entry in
     * FreeRDP_MonitorDefArray), all *before* freerdp_connect() below, since
     * MS-RDPBCGR negotiates monitor layout as part of the initial capability
     * exchange, not something queried from the server afterward. A server
     * that doesn't support multi-monitor sessions simply renders only the
     * primary monitor's region — the rest of this session is unaffected.
     * hctx->declaredMonitors is kept so nativeSelectMonitor() can later
     * re-send a MonitorLayout PDU (over the "disp" channel, once connected)
     * restricted to whichever subset the user picks, without needing the
     * caller to resend the full layout on every switch. */
    {
        systemsgoContext* hctxMon = SYSTEMSGO_CTX(instance);
        jsize monitorCount = jMonitorIds ? (*env)->GetArrayLength(env, jMonitorIds) : 0;
        hctxMon->declaredMonitors = NULL;
        hctxMon->declaredMonitorCount = 0;

        if (monitorCount > 1)
        {
            jint* ids       = (*env)->GetIntArrayElements(env, jMonitorIds, NULL);
            jint* xs        = (*env)->GetIntArrayElements(env, jMonitorXs, NULL);
            jint* ys        = (*env)->GetIntArrayElements(env, jMonitorYs, NULL);
            jint* widths    = (*env)->GetIntArrayElements(env, jMonitorWidths, NULL);
            jint* heights   = (*env)->GetIntArrayElements(env, jMonitorHeights, NULL);
            jboolean* prims = (*env)->GetBooleanArrayElements(env, jMonitorPrimary, NULL);
            jint* orients   = (*env)->GetIntArrayElements(env, jMonitorOrientations, NULL);
            jint* dpis      = (*env)->GetIntArrayElements(env, jMonitorDpiScales, NULL);

            if (ids && xs && ys && widths && heights && prims && orients && dpis)
            {
                rdpMonitor* defs = (rdpMonitor*)calloc((size_t)monitorCount, sizeof(rdpMonitor));
                RDP_MONITOR_ANDROID* declared =
                    (RDP_MONITOR_ANDROID*)calloc((size_t)monitorCount, sizeof(RDP_MONITOR_ANDROID));
                if (defs && declared)
                {
                    for (jsize i = 0; i < monitorCount; i++)
                    {
                        defs[i].x = xs[i];
                        defs[i].y = ys[i];
                        defs[i].width = widths[i];
                        defs[i].height = heights[i];
                        defs[i].is_primary = prims[i] ? TRUE : FALSE;
                        defs[i].orig_screen = (UINT32)i;
                        /* attributes (physical size / orientation / scale) are
                         * best-effort metadata only — omitted servers still
                         * get a correct layout from x/y/width/height/is_primary. */
                        defs[i].attributes.orientation = (UINT32)orients[i];
                        defs[i].attributes.desktopScaleFactor = (UINT32)dpis[i];
                        defs[i].attributes.deviceScaleFactor = 100;

                        declared[i].id = (UINT32)ids[i];
                        declared[i].x = xs[i];
                        declared[i].y = ys[i];
                        declared[i].width = (UINT32)widths[i];
                        declared[i].height = (UINT32)heights[i];
                        declared[i].isPrimary = prims[i] ? TRUE : FALSE;
                        declared[i].orientationDegrees = (UINT32)orients[i];
                        declared[i].dpiScaleFactor = (UINT32)dpis[i];
                    }
                    (void)freerdp_settings_set_bool(settings, FreeRDP_UseMultimon, TRUE);
                    (void)freerdp_settings_set_uint32(settings, FreeRDP_MonitorCount, (UINT32)monitorCount);
                    /* freerdp_settings_set_pointer_len() is the documented FreeRDP 3.x
                     * settings API for a pointer+length pair setting such as
                     * MonitorDefArray (mirrors how DeviceArray/ChannelDefArray are set
                     * elsewhere in FreeRDP's own client code) — takes ownership of
                     * `defs` (copies it internally), so it is intentionally not freed
                     * here. */
                    if (!freerdp_settings_set_pointer_len(settings, FreeRDP_MonitorDefArray,
                                                           defs, (size_t)monitorCount))
                    {
                        LOGE("freerdp_settings_set_pointer_len(MonitorDefArray) failed — "
                             "falling back to single-monitor session");
                        (void)freerdp_settings_set_bool(settings, FreeRDP_UseMultimon, FALSE);
                        (void)freerdp_settings_set_uint32(settings, FreeRDP_MonitorCount, 0);
                    }
                    free(defs);
                    hctxMon->declaredMonitors = declared;
                    hctxMon->declaredMonitorCount = (UINT32)monitorCount;
                    LOGI("Multi-monitor: declared %d monitors to server", (int)monitorCount);
                }
                else
                {
                    free(defs);
                    free(declared);
                    LOGE("Multi-monitor: failed to allocate monitor layout — falling back to single monitor");
                }
            }

            if (ids)     (*env)->ReleaseIntArrayElements(env, jMonitorIds, ids, JNI_ABORT);
            if (xs)      (*env)->ReleaseIntArrayElements(env, jMonitorXs, xs, JNI_ABORT);
            if (ys)      (*env)->ReleaseIntArrayElements(env, jMonitorYs, ys, JNI_ABORT);
            if (widths)  (*env)->ReleaseIntArrayElements(env, jMonitorWidths, widths, JNI_ABORT);
            if (heights) (*env)->ReleaseIntArrayElements(env, jMonitorHeights, heights, JNI_ABORT);
            if (prims)   (*env)->ReleaseBooleanArrayElements(env, jMonitorPrimary, prims, JNI_ABORT);
            if (orients) (*env)->ReleaseIntArrayElements(env, jMonitorOrientations, orients, JNI_ABORT);
            if (dpis)    (*env)->ReleaseIntArrayElements(env, jMonitorDpiScales, dpis, JNI_ABORT);
        }
    }
    (void)freerdp_settings_set_bool  (settings, FreeRDP_NlaSecurity,    jUseNla ? TRUE : FALSE);
    (void)freerdp_settings_set_bool  (settings, FreeRDP_TlsSecurity,    TRUE);
    // CRIT-1 FIX: RdpSecurity=TRUE allows the server to negotiate a downgrade
    // to Classic RDP (RC4, no server authentication) even when NLA/TLS are
    // requested.  A malicious server or MITM can exploit this to capture
    // credentials.  Always disable it so only TLS/NLA are accepted.
    (void)freerdp_settings_set_bool  (settings, FreeRDP_RdpSecurity,    FALSE);
    /* TLS-TOFU FIX: FreeRDP_IgnoreCertificate is always FALSE — full TLS
     * certificate verification runs on every connection, no exceptions.
     * jIgnoreCert is intentionally no longer read here: the accept/reject/
     * pin decision for an unverified certificate is now made entirely in
     * Kotlin (RdpRemoteAdapter's TOFU store, via
     * AFreeRdpBridge.certificateVerifier / onNativeCertificateCheck — see
     * systemsgo_verify_certificate_ex() above), which already has
     * credentials.acceptSelfSignedCertificate in scope. Keeping the
     * parameter in the JNI signature avoids an unrelated churn of
     * AFreeRdpBridge.kt/RdpRemoteAdapter.kt call sites for a parameter that
     * still documents caller intent even though native no longer acts on it.
     * BUG-4 FIX (superseded by the above): was always TRUE → every session
     * vulnerable to MITM; that regression cannot recur now that this line
     * is a hardcoded FALSE. */
    (void)freerdp_settings_set_bool  (settings, FreeRDP_IgnoreCertificate, FALSE);
    (void)jIgnoreCert;
    /* UDP-TRANSPORT FEATURE: MS-RDPEMT ("Multiple Transport Extension").
     * Advertising support here is only ever a *request* — the server (and
     * any RD Gateway / firewall sitting in front of it) independently
     * decides whether to actually open the side-channel UDP sockets during
     * the post-connect capability exchange. When it declines, or when this
     * flag is FALSE, the session is unaffected and runs over TCP exactly as
     * before — this is why enableUdpTransport defaults to FALSE upstream in
     * AFreeRdpBridge.connect() without changing existing behavior for any
     * caller that doesn't opt in.
     *   - TRANSPORT_TYPE_UDP_FECR: the *reliable* UDP transport (used for
     *     the main graphics/RemoteFX Progressive stream when negotiated).
     *   - TRANSPORT_TYPE_UDP_FECL: the *lossy*, best-effort UDP transport
     *     (used opportunistically for latency-sensitive updates).
     * Both bits are requested together; FreeRDP/the server settle on
     * whichever one(s) are actually usable. See MS-RDPEMT §1.3.1 and
     * MS-RDPBCGR §2.2.1.4.1 (multiTransportChannelData) for the protocol
     * background these constants come from. */
    (void)freerdp_settings_set_bool  (settings, FreeRDP_SupportMultitransport, jEnableUdpTransport ? TRUE : FALSE);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_MultitransportFlags,
        jEnableUdpTransport ? (TRANSPORT_TYPE_UDP_FECR | TRANSPORT_TYPE_UDP_FECL) : 0);
    /* OEM-COMPAT FIX: enable TCP keepalive probes on the RDP socket. Without
     * this, idle background sessions are silently dropped by carrier-grade
     * NAT and by aggressive OEM network-suspension policies in Doze mode
     * (Xiaomi/MIUI, Honor/MagicUI, Oppo/ColorOS, Vivo/FuntouchOS all reclaim
     * "idle" sockets more aggressively than stock Android) — the app would
     * otherwise appear frozen instead of reporting a disconnect. */
    (void)freerdp_settings_set_bool  (settings, FreeRDP_TcpKeepAlive,        TRUE);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveDelay,   15);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveInterval, 15);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_TcpKeepAliveRetries, 3);
    /* BUG-1 FIX: apply the three parameters that were declared in Kotlin but ignored in C. */
    (void)freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth,        (UINT32)jColorDepth);
    /* BUG-FLAGS FIX: jPerformanceMode is now a real FreeRDP_PerformanceFlags bitmask
     * (PERF_DISABLE_WALLPAPER | PERF_DISABLE_FULLWINDOWDRAG | ...), computed on the
     * Kotlin side by RdpPerformance.flagsFor() from the 0-4 UI level. Previously the
     * raw 0-4 level was written straight into this bitmask field, so "Low Bandwidth"
     * (0) disabled nothing and "LAN" (3) disabled effects by coincidence rather than
     * intent. */
    (void)freerdp_settings_set_uint32(settings, FreeRDP_PerformanceFlags,  (UINT32)jPerformanceMode);
    /* BANDWIDTH FIX: these were never enabled, so every session — regardless of the
     * chosen performance level — sent full uncompressed bitmap updates and re-fetched
     * unchanged screen regions from the server instead of a local cache. Enabling
     * them cuts bytes-on-the-wire substantially, which shortens radio wake time
     * (the largest mobile battery cost after the screen) and helps low-bandwidth
     * connections the most.
     *   - BitmapCacheEnabled: reuse previously-seen tiles instead of re-sending them.
     *   - OffscreenSupportLevel: let the server composite off-screen instead of
     *     streaming every occluded/restored region.
     *   - FastPathOutput: use the compact fast-path PDU framing instead of full
     *     slow-path T.128 headers for every update.
     *   - NetworkAutoDetect: let FreeRDP measure RTT/bandwidth and adapt codec
     *     behaviour itself, complementing (not replacing) the static PerformanceFlags.
     *   - CompressionEnabled: turn on RDP-level MPPC/bulk compression for
     *     non-bitmap PDUs (clipboard, input, etc.).
     */
    (void)freerdp_settings_set_bool  (settings, FreeRDP_BitmapCacheEnabled,   TRUE);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_OffscreenSupportLevel, 1);
    (void)freerdp_settings_set_bool  (settings, FreeRDP_FastPathOutput,      TRUE);
    (void)freerdp_settings_set_bool  (settings, FreeRDP_NetworkAutoDetect,   TRUE);
    (void)freerdp_settings_set_bool  (settings, FreeRDP_CompressionEnabled,  TRUE);
    /* BUG-QUALITY FIX: jCompressionQuality is an app-level value in the range 0–100
     * (higher = better quality). FreeRDP_RemoteFxCodecMode expects a 0–2 enum:
     *   0 = VIDEO (high-motion, lower quality)
     *   1 = IMAGE (low-motion, higher quality)
     * Map the 0-100 range: ≥50 → IMAGE (1, higher quality), <50 → VIDEO (0, lower quality).
     * Previously passing a raw value like 75 was way outside the valid enum range and
     * caused undefined behaviour in the FreeRDP codec. */
    {
        UINT32 rfxMode = (jCompressionQuality >= 50) ? 1 /* IMAGE */ : 0 /* VIDEO */;
        (void)freerdp_settings_set_uint32(settings, FreeRDP_RemoteFxCodecMode, rfxMode);
    }

    /* CODEC-NEGOTIATION FEATURE (part 3): FreeRDP_NetworkAutoDetect (set TRUE
     * above) + this build's RDPGFX capability advertisement below are
     * DELIBERATELY the entire client-side story for bandwidth-adaptive
     * quality — no complementary dynamic FreeRDP_CompressionLevel/GFX
     * quality-cap logic is added here, for two independent reasons:
     *
     *   1. Scope mismatch: FreeRDP_CompressionLevel/CompressionEnabled (set
     *      just above) govern MPPC bulk compression on the classic slow/
     *      fast-path PDU stream (clipboard, input, non-GFX bitmap orders —
     *      MS-RDPBCGR). They do not touch the RDPGFX video bitstream at all,
     *      so flipping FreeRDP_CompressionLevel in response to a
     *      negotiatedCodec change would be a no-op for exactly the sessions
     *      this feature is about (H.264/AV1 over RDPGFX), and is already
     *      configured unconditionally for the non-GFX fallback path via
     *      CompressionEnabled above regardless of which codec eventually
     *      gets negotiated.
     *   2. No confirmed client-side knob exists: once FreeRDP_NetworkAutoDetect
     *      is TRUE, per-frame RDPGFX quality/QP/bitrate adaptation in
     *      response to the measured RTT/bandwidth (MS-RDPBCGR Auto-Detect
     *      PDUs) is the SERVER's decision, encoded directly in each
     *      RDPGFX_SURFACE_COMMAND's codec-specific payload — this is
     *      precisely the mechanism that can make negotiatedCodec itself
     *      change mid-session (see systemsgo_gfx_surface_command()'s doc
     *      comment) without any further client action. This build's
     *      confirmed FreeRDP 3.x headers (see FREERDP_TAG in CMakeLists.txt)
     *      expose no separate "client-requested GFX quality cap" setting to
     *      push a preference the other direction; inventing one by string
     *      name the way systemsgo_apply_codec_preference()'s AV1 branch does
     *      for "GfxCodecAV1" would be guessing at a key without confirming
     *      it exists first, exactly the mistake that key's own history
     *      already made once (see that function's doc comment) before
     *      being corrected against FreeRDP's actual PR #12527.
     *
     * Net effect: FreeRDP_NetworkAutoDetect + the standard RDPGFX capability
     * exchange below are already sufficient for the "adapt bitrate/quality to
     * network conditions" requirement — this is a "verified sufficient,
     * intentionally no extra code" decision, not a gap. If a future confirmed
     * FreeRDP tag exposes a real client-side GFX quality-cap setting, wire it
     * here, gated the same defensive way GfxCodecAV1 is. */

    /* CODEC-NEGOTIATION FEATURE: AV1 (when available) / H.264 AVC420+AVC444
     * over the RDPGFX graphics pipeline, automatically negotiated against
     * whatever this build + the server both support, with a transparent
     * fallback to the RemoteFxCodecMode path configured just above when
     * neither is available/enabled — see systemsgo_apply_codec_preference()'s
     * doc comment for the full requirement mapping. jCodecPreference is
     * AFreeRdpBridge.CodecPreference's ordinal (default Auto=0). */
    systemsgo_apply_codec_preference(settings, jCodecPreference);

    /* NEW-CRIT-3 FIX: Zero pass immediately after handing it to FreeRDP and before
     * freerdp_connect() — which can block for 5-30 seconds (TCP + TLS + NLA handshake).
     * During that window, a native heap snapshot would expose the cleartext password.
     * systemsgo_secure_bzero() is used instead of memset() because the compiler is permitted to
     * eliminate a plain memset() as a Dead Store if it determines the buffer is not read
     * afterward. It writes through a volatile pointer, which the compiler cannot optimize
     * away, so it is guaranteed to execute regardless of NDK/API level (unlike libc's
     * explicit_bzero(), which Bionic only exposes starting at __ANDROID_API__ 28).
     * FreeRDP has already copied the string into its own internal settings buffer via
     * freerdp_settings_set_string(), so zeroing our copy is safe here.
     * domain contains no secret but is zeroed for uniformity (minimises attack surface). */
    systemsgo_secure_bzero((void*)pass,   strlen(pass));
    (*env)->ReleaseStringUTFChars(env, jPassword, pass);
    pass = NULL;
    systemsgo_secure_bzero((void*)domain, strlen(domain));
    (*env)->ReleaseStringUTFChars(env, jDomain,   domain);
    domain = NULL;

    if (jGatewayEnabled)
    {
        const char* gwHost   = (*env)->GetStringUTFChars(env, jGwHost,   NULL);
        const char* gwUser   = (*env)->GetStringUTFChars(env, jGwUser,   NULL);
        const char* gwPass   = (*env)->GetStringUTFChars(env, jGwPass,   NULL);
        const char* gwDomain = (*env)->GetStringUTFChars(env, jGwDomain, NULL);
        /* ENTRA-ID-AUTH FEATURE: jGatewayBearerToken is only non-empty (and
         * only actually used below) when jGatewayAuthMode == ENTRA_ID
         * (ordinal 1) — see AFreeRdpBridge.GatewayAuthMode. Extracted
         * unconditionally here, alongside gwHost/gwUser/gwPass/gwDomain
         * above, so the single OOM/null-check block just below can cover
         * all five jstrings the same way BUG-OOM FIX already does for the
         * other four. */
        const char* gwBearerToken = (*env)->GetStringUTFChars(env, jGatewayBearerToken, NULL);

        /* BUG-OOM FIX: GetStringUTFChars returns NULL on OOM (low-RAM devices).
         * Passing NULL to freerdp_settings_set_string causes a native crash.
         * Release whatever was allocated and bail out cleanly. */
        if (!gwHost || !gwUser || !gwPass || !gwDomain || !gwBearerToken)
        {
            if (gwHost)   (*env)->ReleaseStringUTFChars(env, jGwHost,   gwHost);
            if (gwUser)   (*env)->ReleaseStringUTFChars(env, jGwUser,   gwUser);
            if (gwPass)   { systemsgo_secure_bzero((void*)gwPass, strlen(gwPass)); (*env)->ReleaseStringUTFChars(env, jGwPass,   gwPass); }
            if (gwDomain) (*env)->ReleaseStringUTFChars(env, jGwDomain, gwDomain);
            if (gwBearerToken) { systemsgo_secure_bzero((void*)gwBearerToken, strlen(gwBearerToken)); (*env)->ReleaseStringUTFChars(env, jGatewayBearerToken, gwBearerToken); }
            (*env)->ReleaseStringUTFChars(env, jHost,     host);
            (*env)->ReleaseStringUTFChars(env, jUsername, user);
            /* pass and domain already released above */
            return JNI_FALSE;
        }

        (void)freerdp_settings_set_bool  (settings, FreeRDP_GatewayEnabled,      TRUE);
        (void)freerdp_settings_set_string(settings, FreeRDP_GatewayHostname,     gwHost);
        (void)freerdp_settings_set_uint32(settings, FreeRDP_GatewayPort,         (UINT32)jGwPort);
        (void)freerdp_settings_set_uint32(settings, FreeRDP_GatewayUsageMethod,  1 /* TSC_PROXY_MODE_DIRECT */);

        /* ENTRA-ID-AUTH FEATURE: PASSWORD (0) keeps the pre-existing
         * NTLM/Basic gateway credentials behavior exactly as before.
         * ENTRA_ID (1) must NOT set GatewayUsername/GatewayPassword/
         * GatewayDomain at all — even to empty strings is fine value-wise,
         * but we skip the calls entirely so there is no ambiguity — and
         * instead sets FreeRDP_GatewayHttpExtAuthBearer to the MSAL token,
         * which rdg_establish_data_connection() (libfreerdp/core/gateway/
         * rdg.c) checks first and, when non-empty, forces
         * extAuth = HTTP_EXTENDED_AUTH_BEARER before the RDG HTTPS
         * transport's HTTP CONNECT/RDG_IN_DATA request is built — meaning
         * the Authorization: Bearer <token> header is added by FreeRDP
         * itself, not by any code in this file. This is what keeps NTLM/
         * Basic gateway auth from firing in parallel with the bearer token
         * (see GatewayAuthMode's doc comment in AFreeRdpBridge.kt). */
        if (jGatewayAuthMode == 1 /* GatewayAuthMode.ENTRA_ID */)
        {
            (void)freerdp_settings_set_string(settings, FreeRDP_GatewayHttpExtAuthBearer, gwBearerToken);
            LOGI("nativeConnect: Gateway auth mode = ENTRA_ID (bearer token, %zu chars)",
                 strlen(gwBearerToken));
        }
        else
        {
            (void)freerdp_settings_set_string(settings, FreeRDP_GatewayUsername, gwUser);
            (void)freerdp_settings_set_string(settings, FreeRDP_GatewayPassword, gwPass);
            (void)freerdp_settings_set_string(settings, FreeRDP_GatewayDomain,   gwDomain);
        }

        /* NEW-CRIT-3 FIX: Zero gwPass/gwBearerToken before freerdp_connect() for the
         * same reason as pass — both are secrets FreeRDP has already copied into its
         * own internal settings buffer via freerdp_settings_set_string(), so zeroing
         * our copy here is safe. gwBearerToken is zeroed unconditionally even in
         * PASSWORD mode (where it was extracted but never used) since it's still a
         * live credential in this stack frame otherwise. */
        (*env)->ReleaseStringUTFChars(env, jGwHost,   gwHost);
        (*env)->ReleaseStringUTFChars(env, jGwUser,   gwUser);
        systemsgo_secure_bzero((void*)gwPass,   strlen(gwPass));
        (*env)->ReleaseStringUTFChars(env, jGwPass,   gwPass);
        (*env)->ReleaseStringUTFChars(env, jGwDomain, gwDomain);
        systemsgo_secure_bzero((void*)gwBearerToken, strlen(gwBearerToken));
        (*env)->ReleaseStringUTFChars(env, jGatewayBearerToken, gwBearerToken);
    }

    /* OUTBOUND-PROXY FEATURE: see the jProxyEnabled doc comment on
     * nativeConnect()'s signature above for what this is and how it differs
     * from Gateway (just above) and SocksProxyServer.kt.
     *
     * CAVEAT: FreeRDP_ProxyType/ProxyHostname/ProxyPort/ProxyUsername/
     * ProxyPassword and the PROXY_TYPE enum (NONE=0/HTTP=1/SOCKS=2) have
     * existed in FreeRDP's settings.h since the /proxy: CLI flag was added
     * (well before the 3.27.1 tag this project pins — see FREERDP_TAG in
     * main.yml) and are documented in `xfreerdp --help`'s "/proxy" entry,
     * but — same as systemsgo_apply_codec_preference()'s AV1 branch and the
     * WITH_PROXY block in CMakeLists.txt below — this was written with no
     * network access to diff them against the exact 3.27.1 header in this
     * repo's prebuilt. If a build ever fails here with an unknown
     * FreeRDP_Proxy* enumerator, that mismatch is the first thing to check
     * (e.g. via `strings libfreerdp3.so | grep -i proxytype` or the
     * installed freerdp3/freerdp/settings.h from the CI artifact) rather
     * than assuming the whole approach is wrong — the settings keys used
     * below are correct as of the FreeRDP versions this comment was checked
     * against, just not re-verified against this exact prebuilt. */
    if (jProxyEnabled)
    {
        const char* proxyHost = (*env)->GetStringUTFChars(env, jProxyHost, NULL);
        const char* proxyUser = (*env)->GetStringUTFChars(env, jProxyUsername, NULL);
        const char* proxyPass = (*env)->GetStringUTFChars(env, jProxyPassword, NULL);

        /* BUG-OOM FIX: same GetStringUTFChars-can-return-NULL-on-OOM
         * reasoning as the Gateway block above. */
        if (!proxyHost || !proxyUser || !proxyPass)
        {
            if (proxyHost) (*env)->ReleaseStringUTFChars(env, jProxyHost, proxyHost);
            if (proxyUser) (*env)->ReleaseStringUTFChars(env, jProxyUsername, proxyUser);
            if (proxyPass) {
                systemsgo_secure_bzero((void*)proxyPass, strlen(proxyPass));
                (*env)->ReleaseStringUTFChars(env, jProxyPassword, proxyPass);
            }
            (*env)->ReleaseStringUTFChars(env, jHost,     host);
            (*env)->ReleaseStringUTFChars(env, jUsername, user);
            return JNI_FALSE;
        }

        /* PROXY_TYPE_NONE=0, PROXY_TYPE_HTTP=1, PROXY_TYPE_SOCKS=2 — kept
         * as a raw UINT32 rather than a named enum constant because the
         * enum's exact spelling (ProxyType vs PROXY_TYPE, HTTP vs
         * PROXY_TYPE_HTTP) is exactly the kind of point-release detail
         * flagged as unverified above; passing the ordinal directly means a
         * mismatched *symbol name* upstream can't cause a compile error —
         * only a genuinely different *ordinal mapping* would, which is far
         * less likely to have changed. AFreeRdpBridge.ProxyType's ordinal
         * order MUST stay NONE, HTTP, SOCKS to match.
         *
         * HTTPS-PROXY FEATURE: ordinal 3 (HTTPS) is NOT one of stock
         * FreeRDP's own PROXY_TYPE values — confirmed against 3.27.1's
         * libfreerdp/core/proxy.c, whose PROXY_TYPE enum stops at
         * PROXY_TYPE_SOCKS=2 (plus PROXY_TYPE_IGNORE=0xFFFF, unused here).
         * Passing 3 only does anything useful because
         * the "Patch FreeRDP proxy for HTTPS support" CI step in .github/workflows/main.yml adds a
         * `case PROXY_TYPE_HTTPS:` (== 3) arm to proxy_connect_impl() in
         * the prebuilt this binary links against — see that patch file and
         * AFreeRdpBridge.ProxyType's doc comment for the full picture. If
         * that CI patch step's anchor ever stops matching a future FreeRDP
         * release, it fails the build loudly rather than silently
         * no-opping (same convention as the other freerdp-patches/ entries),
         * so an HTTPS proxy silently behaving like "no proxy" here would
         * mean that patch step was skipped or bypassed, not that this
         * ordinal is wrong. */
        (void)freerdp_settings_set_uint32(settings, FreeRDP_ProxyType, (UINT32)jProxyType);
        (void)freerdp_settings_set_string(settings, FreeRDP_ProxyHostname, proxyHost);
        (void)freerdp_settings_set_uint32(settings, FreeRDP_ProxyPort,     (UINT32)jProxyPort);
        if (proxyUser[0] != '\0')
        {
            (void)freerdp_settings_set_string(settings, FreeRDP_ProxyUsername, proxyUser);
            (void)freerdp_settings_set_string(settings, FreeRDP_ProxyPassword, proxyPass);
        }

        (*env)->ReleaseStringUTFChars(env, jProxyHost, proxyHost);
        (*env)->ReleaseStringUTFChars(env, jProxyUsername, proxyUser);
        systemsgo_secure_bzero((void*)proxyPass, strlen(proxyPass));
        (*env)->ReleaseStringUTFChars(env, jProxyPassword, proxyPass);
    }

    /* REMOTEAPP FIX: RemoteApp (MS-RDPERP / RAIL) — request a single
     * published program instead of the full remote desktop shell.
     *
     * FreeRDP_RemoteApplicationMode plus a non-empty
     * FreeRDP_RemoteApplicationProgram is what actually gets negotiated
     * into this session's capability exchange (rdpSettings read during
     * systemsgo_pre_connect's capability setup, before freerdp_connect() below
     * establishes the connection) — this is the part that tells the server
     * "start/attach just this one program, not a full desktop session".
     *
     * REMOTEAPP-WINDOWS FEATURE UPDATE (previously an "IMPORTANT SCOPE
     * NOTE" documenting this as a gap — no longer accurate, kept as history
     * rather than deleted, matching this file's own BUILD FIX/TLS-TOFU FIX
     * convention of leaving prior reasoning visible): setting these flags
     * used to be *only* the capability-negotiation half of RemoteApp, with
     * the "rail" *virtual channel* itself (multi-window creation/move/
     * resize/show/hide/title, driven by Window Order PDUs) unhandled. That
     * channel is now loaded (via this same freerdp_client_load_addins() call
     * in systemsgo_pre_connect(), automatically once FreeRDP_RemoteApplicationMode
     * is TRUE here — see the RAIL_SVC_CHANNEL_NAME include-block comment
     * near the top of this file) and its window orders are reported to
     * Kotlin — see systemsgo_pre_connect()'s update->window registration and
     * systemsgo_rail_window_state()/systemsgo_rail_window_delete() above.
     * systemsgo_on_frame's whole-framebuffer GDI capture is unchanged by this:
     * it still captures the full remote surface exactly as before, and
     * RdpSessionActivity is expected to crop/composite per-window using the
     * rects this fix now reports (RemoteAppWindowManager), not by asking
     * native to render windows separately. Window *icons* (WindowIcon/
     * WindowCachedIcon orders) are now decoded and forwarded too — see
     * systemsgo_rail_window_icon()'s doc comment. A local drag/resize also now
     * has a client -> server path: nativeSendRailWindowMove() sends
     * RAIL_WINDOW_MOVE_ORDER via railContext->ClientWindowMove — see that
     * function's doc comment for how it differs from xf_rail.c's local-move
     * gesture tracking (xf_rail_adjust_position/xf_rail_end_local_move).
     * Remaining documented gap: per-window z-order is still approximated
     * (last-touched-goes-on-top) rather than the server's true stacking
     * order, since MS-RDPERP's Z-Order Sync order isn't exposed through the
     * callback surface this fix hooks.
     *
     * CLIPBOARD FIX: the "cliprdr" (RDPECLIP) and Audio*
     * (FreeRDP_AudioPlayback/FreeRDP_AudioCapture, MIC-REDIRECT FEATURE)
     * channels *are* now loaded via the same freerdp_client_load_addins()
     * call in systemsgo_pre_connect() — see the FreeRDP_RedirectClipboard line
     * and the cliprdr callbacks earlier in this file. DRIVE-REDIRECT
     * FEATURE: RdpProfile's remaining channel toggle, enableDriveRedirect
     * ("rdpdr", drive redirection), is now wired the same way — see the
     * FreeRDP_DeviceRedirection / freerdp_client_add_device_channel block
     * further down in this function. */
    if (jRemoteAppEnabled)
    {
        const char* raProgram = (*env)->GetStringUTFChars(env, jRemoteAppProgram, NULL);
        const char* raWorkDir = (*env)->GetStringUTFChars(env, jRemoteAppWorkingDir, NULL);
        const char* raCmdLine = (*env)->GetStringUTFChars(env, jRemoteAppCmdLine, NULL);

        /* BUG-OOM FIX (mirrors the gateway block above): bail out cleanly on
         * GetStringUTFChars OOM rather than passing NULL to FreeRDP. */
        if (!raProgram || !raWorkDir || !raCmdLine)
        {
            if (raProgram) (*env)->ReleaseStringUTFChars(env, jRemoteAppProgram,    raProgram);
            if (raWorkDir) (*env)->ReleaseStringUTFChars(env, jRemoteAppWorkingDir, raWorkDir);
            if (raCmdLine) (*env)->ReleaseStringUTFChars(env, jRemoteAppCmdLine,    raCmdLine);
            (*env)->ReleaseStringUTFChars(env, jHost,     host);
            (*env)->ReleaseStringUTFChars(env, jUsername, user);
            return JNI_FALSE;
        }

        /* Only actually switch the session into RAIL mode if a program/alias
         * was given — an enabled toggle with a blank field would otherwise
         * ask the server to run "nothing", which servers reject. */
        if (raProgram[0] != '\0')
        {
            (void)freerdp_settings_set_bool  (settings, FreeRDP_RemoteApplicationMode, TRUE);
            (void)freerdp_settings_set_string(settings, FreeRDP_RemoteApplicationProgram, raProgram);
            if (raWorkDir[0] != '\0')
                (void)freerdp_settings_set_string(settings, FreeRDP_RemoteApplicationWorkingDir, raWorkDir);
            if (raCmdLine[0] != '\0')
                (void)freerdp_settings_set_string(settings, FreeRDP_RemoteApplicationCmdLine, raCmdLine);
        }
        else
        {
            LOGE("RemoteApp was enabled but remoteAppProgram is blank — ignoring, session will be a normal desktop.");
        }

        (*env)->ReleaseStringUTFChars(env, jRemoteAppProgram,    raProgram);
        (*env)->ReleaseStringUTFChars(env, jRemoteAppWorkingDir, raWorkDir);
        (*env)->ReleaseStringUTFChars(env, jRemoteAppCmdLine,    raCmdLine);
    }

    /* MIC-REDIRECT FEATURE: audio playback (remote → local speaker,
     * MS-RDPEA "rdpsnd") and audio capture (local mic → remote,
     * MS-RDPEAI "audin"). These two settings are exactly what
     * systemsgo_pre_connect()'s existing freerdp_client_load_addins() call
     * reads to decide which client channel plugins to load — the same
     * mechanism the "disp" channel already relies on (see LIVE-RESIZE FIX
     * above), so no separate load_addins call is needed here.
     *
     * Both are still purely best-effort, same as disp:
     *  - If the server doesn't support RDPEA/RDPEAI, the channel simply
     *    never opens and audio for that direction is silently unavailable —
     *    the rest of the session is unaffected.
     *  - If the FreeRDP prebuilt this app links against was built without an
     *    audio backend (WITH_OPENSLES, the only viable in-process option on
     *    Android since WITH_PULSE/WITH_LIBSYSTEMD/ALSA are not — see
     *    app/src/main/cpp/SETUP.md's "قيود مقصودة في أعلام بناء FreeRDP"
     *    section, which documents this exact class of gap for
     *    smartcard/printer), load_addins finds no usable subsystem for
     *    "rdpsnd"/"audin" and again the channel just never opens. There is
     *    no separate flag in this file for that — it lives entirely in how
     *    FreeRDP itself was configured when the prebuilt .so was built.
     *  - FreeRDP_AudioCapture additionally requires the app to hold the
     *    RECORD_AUDIO runtime permission before freerdp_connect() reaches
     *    the point of opening the mic (see RECORD_AUDIO request in
     *    RdpSessionActivity.onCreate); FreeRDP does not surface a distinct
     *    error for "permission denied" here, so a missing permission looks
     *    identical to "server/build doesn't support it" from this file's
     *    point of view — same fail-open-safe behaviour, just without a
     *    diagnostic message.
     *
     * WEBCAM-REDIRECT FEATURE: unlike audio, the camera toggle is not a
     * plain settings flag here — "rdpecam" is a *dynamic* virtual channel,
     * registered via freerdp_client_add_dynamic_channel() right after the
     * printer block below (same shape as jEnablePrinterRedirect), gated on
     * SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE. See that macro's doc comment above
     * for why this build now has an Android rdpecam backend where earlier
     * revisions of this file did not. */
    (void)freerdp_settings_set_bool(settings, FreeRDP_AudioPlayback, jEnableSound ? TRUE : FALSE);
    (void)freerdp_settings_set_bool(settings, FreeRDP_AudioCapture,  jEnableMicRedirect ? TRUE : FALSE);

    /* CLIPBOARD FIX: MS-RDPECLIP "cliprdr" channel. Exactly the same
     * mechanism as the Audio* flags right above — freerdp_client_load_addins()
     * (called from systemsgo_pre_connect, which runs as part of freerdp_connect()
     * below) reads this setting to decide whether to load the "cliprdr"
     * static channel plugin. When disabled, this is FALSE and load_addins
     * never loads cliprdr — behavior is then identical to before this fix
     * (no channel, no callbacks ever fire, nativeSendClipboardText is a
     * silent no-op). When the server doesn't support RDPECLIP either, the
     * channel simply never opens (hctx->cliprdrContext stays NULL) — same
     * best-effort, fail-open-safe pattern as disp/audio. */
    (void)freerdp_settings_set_bool(settings, FreeRDP_RedirectClipboard, jEnableClipboard ? TRUE : FALSE);

    /* DRIVE-REDIRECT FEATURE: MS-RDPEFS "rdpdr" device-redirection channel,
     * drive sub-type only (no printer/smartcard redirection here — those
     * additionally need CUPS/PCSC, which this build's FreeRDP prebuilt does
     * not include, see SETUP.md). Exactly the same load mechanism as the
     * Audio and RedirectClipboard flags above: systemsgo_pre_connect()'s
     * freerdp_client_load_addins() call (invoked from inside the
     * freerdp_connect() call below) reads FreeRDP_DeviceRedirection plus the
     * registered device list to decide whether to load "rdpdr" at all, and
     * with which devices.
     *
     * freerdp_client_add_device_channel() is the same helper FreeRDP's own
     * command-line client uses to turn a "/drive:name,path" argument into a
     * registered RDPDR_DRIVE device — it takes an argv-style {name, alias,
     * path} triple. When disabled, this whole block is skipped: settings
     * stay exactly as before this feature (FreeRDP_DeviceRedirection
     * FALSE-by-default, no device registered), so existing behavior for
     * profiles with enableDriveRedirect=false (the default) is unchanged.
     *
     * Best-effort, fail-open-safe like every other channel here: if the
     * server doesn't support RDPEFS, the channel simply never opens and the
     * remote session has no "android" drive — the rest of the session is
     * unaffected. */
    if (jEnableDriveRedirect)
    {
        const char* drivePath = (*env)->GetStringUTFChars(env, jDrivePath, NULL);
        if (drivePath && drivePath[0] != '\0')
        {
            char* driveArgs[3];
            driveArgs[0] = (char*)"drive";
            driveArgs[1] = (char*)"android";   /* drive label shown on the remote desktop */
            driveArgs[2] = (char*)drivePath;
            if (!freerdp_client_add_device_channel(settings, 3, driveArgs))
            {
                LOGE("freerdp_client_add_device_channel(drive) failed — rdpdr redirection unavailable this session");
            }
            else
            {
                (void)freerdp_settings_set_bool(settings, FreeRDP_DeviceRedirection, TRUE);
            }
        }
        else
        {
            LOGE("enableDriveRedirect was set but drivePath is empty — skipping rdpdr redirection.");
        }
        if (drivePath) (*env)->ReleaseStringUTFChars(env, jDrivePath, drivePath);
    }

    /* PRINTER-REDIRECT FEATURE: MS-RDPEPC printer redirection, on the same
     * "rdpdr" channel as the drive device just above, just with a "printer"
     * device instead of a "drive" one. Gated on SYSTEMSGO_PRINT_BACKEND_AVAILABLE
     * (unlike the drive block above, which is unconditional) because — as of
     * this build — there is no printer-channel addin compiled into this
     * FreeRDP prebuilt for freerdp_client_load_addins() to load at all (see
     * that macro's doc comment for the WITH_CUPS=OFF reasoning); registering
     * the device anyway would just be configuration nothing ever reads.
     * When the flag is off, this is a clean, honest no-op with a log line
     * explaining why — the same "gracefully notify" contract
     * isPrinterBackendAvailable()/the UI's disabled toggle already give the
     * user, just logged here too for anyone debugging a build. */
    if (jEnablePrinterRedirect)
    {
#if SYSTEMSGO_PRINT_BACKEND_AVAILABLE
        /* "printer" is the subsystem name FreeRDP's own command-line client
         * uses for a "/printer:name,driver" argument — freerdp_client_add_device_channel()
         * turns it into a registered RDPDR_PRINTER device the same way the
         * drive block above does for "drive". Leaving the driver name empty
         * lets the printer backend fall back to its own default driver
         * selection once one is actually compiled in. */
        char* printerArgs[3];
        printerArgs[0] = (char*)"printer";
        printerArgs[1] = (char*)"Android (Redirected)"; /* printer name shown on the remote desktop */
        printerArgs[2] = (char*)"";                      /* driver: let the backend pick a default */
        if (!freerdp_client_add_device_channel(settings, 3, printerArgs))
        {
            LOGE("freerdp_client_add_device_channel(printer) failed — rdpdr printer redirection unavailable this session");
        }
        else
        {
            (void)freerdp_settings_set_bool(settings, FreeRDP_DeviceRedirection, TRUE);
        }
#else
        LOGI("enablePrinterRedirect was set but this build has no printer backend "
             "(SYSTEMSGO_PRINT_BACKEND_AVAILABLE=0, WITH_CUPS=OFF) — skipping rdpdr "
             "printer redirection this session. See app/src/main/cpp/SETUP.md.");
#endif
    }

    /* WEBCAM-REDIRECT FEATURE: MS-RDPECAM camera redirection. Unlike the
     * drive/printer devices above (which ride the static "rdpdr" channel via
     * freerdp_client_add_device_channel), "rdpecam" is a *dynamic* virtual
     * channel — freerdp_client_add_dynamic_channel() is the same helper
     * FreeRDP's own command-line client uses to turn a "/dvc:rdpecam"
     * argument into a registered dynamic channel entry that
     * systemsgo_pre_connect()'s existing freerdp_client_load_addins() call
     * (invoked from inside freerdp_connect() below) then actually loads —
     * same load mechanism "disp"/audio/cliprdr above already rely on, no
     * separate load_addins call needed here either.
     *
     * Gated on SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE for the same reason the
     * printer block above is gated on SYSTEMSGO_PRINT_BACKEND_AVAILABLE: if
     * this build's FreeRDP prebuilt has no rdpecam addin compiled in,
     * registering the dynamic channel anyway would just be configuration
     * nothing ever reads.
     *
     * Best-effort, fail-open-safe like every other channel here: if the
     * server doesn't support MS-RDPECAM, the channel simply never opens and
     * the rest of the session is unaffected. Opening the camera itself
     * additionally requires the app to hold the CAMERA runtime permission
     * before freerdp_connect() reaches the point the rdpecam backend tries
     * to open it (see CAMERA request in RdpSessionActivity.onCreate,
     * mirroring the existing RECORD_AUDIO request for audin) — same
     * "missing permission looks identical to unsupported" caveat noted for
     * FreeRDP_AudioCapture above, since FreeRDP does not surface a distinct
     * error for that case here either. */
    if (jEnableWebcamRedirect)
    {
#if SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE
        char* webcamArgs[1];
        webcamArgs[0] = (char*)"rdpecam";
        if (freerdp_client_add_dynamic_channel(settings, 1, webcamArgs) < 0)
        {
            LOGE("freerdp_client_add_dynamic_channel(rdpecam) failed — webcam redirection unavailable this session");
        }
#else
        LOGI("enableWebcamRedirect was set but this build has no webcam backend "
             "(SYSTEMSGO_WEBCAM_BACKEND_AVAILABLE=0) — skipping rdpecam webcam "
             "redirection this session. See app/src/main/cpp/SETUP.md.");
#endif
    }

    /* GENERIC-VCHANNEL FEATURE: table-driven equivalent of the rdpecam block
     * just above, for any additional named dynamic channel Kotlin queued via
     * nativeRegisterDynamicChannel() before this call — see
     * systemsgoContext::pendingDynamicChannelNames's doc comment for exactly
     * what this can and can't load. Same best-effort, fail-open-safe
     * contract as every other channel block in this function. */
    {
        systemsgoContext* hctxDyn = SYSTEMSGO_CTX(instance);
        for (int systemsgo_i = 0; systemsgo_i < hctxDyn->pendingDynamicChannelCount; systemsgo_i++)
        {
            char* dynArgs[1];
            dynArgs[0] = hctxDyn->pendingDynamicChannelNames[systemsgo_i];
            if (freerdp_client_add_dynamic_channel(settings, 1, dynArgs) < 0)
            {
                LOGE("freerdp_client_add_dynamic_channel(%s) failed — this generically-"
                     "requested channel is unavailable this session",
                     hctxDyn->pendingDynamicChannelNames[systemsgo_i]);
            }
        }
    }

    /* SMARTCARD-REDIRECT FEATURE: MS-RDPESC smart-card redirection, on the
     * same static "rdpdr" channel as drive/printer above (unlike webcam's
     * dynamic "rdpecam" channel) — freerdp_client_add_device_channel() with
     * a "smartcard" subsystem name is the same helper FreeRDP's own
     * command-line client uses for a "/smartcard[:<name>]" argument.
     *
     * Gated on SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE for the same reason the
     * printer block above is gated on SYSTEMSGO_PRINT_BACKEND_AVAILABLE: if
     * this build's FreeRDP prebuilt has no smartcard addin compiled in
     * (WITH_PCSC=OFF), registering the device anyway would just be dead
     * configuration. See that macro's doc comment above — and
     * CMakeLists.txt's SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE comment — for the
     * bigger caveat even when it IS compiled in: FreeRDP's smartcard client
     * still needs a live PC/SC resource manager on-device (normally pcscd,
     * not present in a stock Android sandbox) to actually answer APDUs from
     * an inserted card. Best-effort, fail-open-safe like every other channel
     * here: if no resource manager responds, the channel simply reports no
     * reader present and the rest of the session is unaffected. */
    if (jEnableSmartcardRedirect)
    {
#if SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE
        char* smartcardArgs[2];
        smartcardArgs[0] = (char*)"smartcard";
        smartcardArgs[1] = (char*)"Android Smart Card"; /* reader name shown on the remote desktop */
        if (!freerdp_client_add_device_channel(settings, 2, smartcardArgs))
        {
            LOGE("freerdp_client_add_device_channel(smartcard) failed — rdpdr smartcard redirection unavailable this session");
        }
        else
        {
            (void)freerdp_settings_set_bool(settings, FreeRDP_DeviceRedirection, TRUE);
            (void)freerdp_settings_set_bool(settings, FreeRDP_RedirectSmartCards, TRUE);
        }
#else
        LOGI("enableSmartcardRedirect was set but this build has no smartcard backend "
             "(SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE=0, WITH_PCSC=OFF) — skipping rdpdr "
             "smartcard redirection this session. See app/src/main/cpp/SETUP.md.");
#endif
    }

    /* PARALLEL-REDIRECT FEATURE: RDPDR parallel-port redirection, on the
     * same static "rdpdr" channel as drive/printer/smartcard above —
     * freerdp_client_add_device_channel() with a "parallel" subsystem name
     * is the same helper FreeRDP's own command-line client uses for a
     * "/parallel:name,path" argument, taking the same argv-style
     * {name, alias, path} triple the drive block above uses.
     *
     * Unlike printer/smartcard, this is NOT gated on a *_BACKEND_AVAILABLE
     * flag: FreeRDP's parallel-port channel plugin has no extra desktop
     * library dependency (no CUPS/PCSC equivalent) — it just opens the given
     * local path with the platform's own file I/O, so it is already
     * compiled into every FreeRDP build this app ships, same as drive.
     *
     * Unlike drivePath above, there is no app-owned fallback path: Android
     * has no built-in notion of a "parallel port", so jParallelPath must be
     * an actual local device node the user configured for this profile
     * (typically exposed by a USB-OTG parallel/serial adapter). Empty path
     * skips registration entirely, same as an empty jDrivePath already does
     * for drive above.
     *
     * Best-effort, fail-open-safe like every other channel here: if the
     * server doesn't support this redirection or the local path can't be
     * opened, the channel simply never opens and the rest of the session is
     * unaffected. */
    if (jEnableParallelRedirect)
    {
        const char* parallelPath = (*env)->GetStringUTFChars(env, jParallelPath, NULL);
        if (parallelPath && parallelPath[0] != '\0')
        {
            char* parallelArgs[3];
            parallelArgs[0] = (char*)"parallel";
            parallelArgs[1] = (char*)"android";   /* port label shown on the remote desktop */
            parallelArgs[2] = (char*)parallelPath;
            if (!freerdp_client_add_device_channel(settings, 3, parallelArgs))
            {
                LOGE("freerdp_client_add_device_channel(parallel) failed — rdpdr parallel-port redirection unavailable this session");
            }
            else
            {
                (void)freerdp_settings_set_bool(settings, FreeRDP_DeviceRedirection, TRUE);
            }
        }
        else
        {
            LOGE("enableParallelRedirect was set but parallelPath is empty — skipping rdpdr parallel-port redirection.");
        }
        if (parallelPath) (*env)->ReleaseStringUTFChars(env, jParallelPath, parallelPath);
    }

    /* SERIAL-REDIRECT FEATURE: MS-RDPESP serial-port redirection, on the
     * same static "rdpdr" channel as drive/printer/smartcard/parallel above
     * — freerdp_client_add_device_channel() with a "serial" subsystem name
     * is the same helper FreeRDP's own command-line client uses for a
     * "/serial:name,path[,driver[,permissive]]" argument. Only the required
     * {name, alias, path} triple is used here, same as the parallel block
     * immediately above — no custom driver name or permissive-mode flag is
     * exposed in this UI yet.
     *
     * Unconditional, same reasoning as the parallel block above: FreeRDP's
     * serial-port channel plugin has no extra desktop library dependency
     * either — it opens the given local path directly — so it is already
     * compiled into every FreeRDP build this app ships.
     *
     * Same "no app-owned fallback path" situation as parallel: Android has
     * no built-in notion of a "serial port" either, so jSerialPath must be
     * an actual local device node the user configured for this profile
     * (typically /dev/ttyUSB* or /dev/ttyACM* from a USB-OTG serial
     * adapter). Empty path skips registration entirely.
     *
     * Best-effort, fail-open-safe like every other channel here. */
    if (jEnableSerialRedirect)
    {
        const char* serialPath = (*env)->GetStringUTFChars(env, jSerialPath, NULL);
        if (serialPath && serialPath[0] != '\0')
        {
            char* serialArgs[3];
            serialArgs[0] = (char*)"serial";
            serialArgs[1] = (char*)"android";   /* port label shown on the remote desktop */
            serialArgs[2] = (char*)serialPath;
            if (!freerdp_client_add_device_channel(settings, 3, serialArgs))
            {
                LOGE("freerdp_client_add_device_channel(serial) failed — rdpdr serial-port redirection unavailable this session");
            }
            else
            {
                (void)freerdp_settings_set_bool(settings, FreeRDP_DeviceRedirection, TRUE);
            }
        }
        else
        {
            LOGE("enableSerialRedirect was set but serialPath is empty — skipping rdpdr serial-port redirection.");
        }
        if (serialPath) (*env)->ReleaseStringUTFChars(env, jSerialPath, serialPath);
    }

    (*env)->ReleaseStringUTFChars(env, jHost,     host);
    (*env)->ReleaseStringUTFChars(env, jUsername, user);
    /* pass and domain already zeroed and released before this block */

    BOOL ok = freerdp_connect(instance);
    if (!ok)
    {
        systemsgoContext* hctx = SYSTEMSGO_CTX(instance);
        UINT32 code = freerdp_get_last_error(instance->context);
        const char* name = freerdp_get_last_error_name(code);

        /* ROOT-CAUSE FIX: when this attempt failed because
         * systemsgo_verify_certificate_ex() rejected the certificate,
         * RdpRemoteAdapter.verifyServerCertificate() has already emitted a
         * specific, actionable message on the same `error` flow this
         * generic FreeRDP code (typically an opaque ERRCONNECT_TLS_* /
         * ERRCONNECT_CANCELLED name) would otherwise overwrite — turning a
         * clearly-diagnosable "untrusted certificate, enable Accept
         * self-signed certificate" failure into what looks to the user like
         * an indistinguishable generic connection failure. Skip the
         * duplicate, less specific emission in that case; the state machine
         * below (DISCONNECTED/ERROR) is unaffected either way. */
        if (!hctx->certRejectedLocally)
        {
            (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onErrorMethod,
                                    (*env)->NewStringUTF(env, name ? name : "Unknown FreeRDP error"));
        }
        else
        {
            LOGI("nativeConnect: freerdp_connect() failed (%s) after this attempt's "
                 "certificate was already rejected by Kotlin's TOFU check — suppressing "
                 "the generic error so the specific TLS_UNTRUSTED_CERTIFICATE message "
                 "reaches the UI instead.", name ? name : "unknown");
        }

        /* CRIT-NEW-1 FIX: Emit AUTH_FAILED (state=3) for logon/credential errors so
         * Kotlin can stop auto-reconnect immediately instead of retrying 3 times with
         * the same wrong password.  FreeRDP auth-failure codes:
         *   ERRCONNECT_LOGON_FAILURE          0x00020014
         *   ERRCONNECT_WRONG_PASSWORD         0x00020009  (some server variants)
         *   ERRCONNECT_ACCOUNT_LOCKED_OUT     0x00020004
         *   ERRCONNECT_ACCOUNT_DISABLED       0x00020003
         *   ERRCONNECT_PASSWORD_CERTAINLY_EXPIRED 0x0002000D
         * All share the high nibble 0x0002xxxx ("connect error" category).
         * We check the specific values that are unambiguously credential errors. */
        BOOL isAuthFailure =
            code == 0x00020014 /* ERRCONNECT_LOGON_FAILURE          */ ||
            code == 0x00020009 /* ERRCONNECT_WRONG_PASSWORD         */ ||
            code == 0x00020004 /* ERRCONNECT_ACCOUNT_LOCKED_OUT     */ ||
            code == 0x00020003 /* ERRCONNECT_ACCOUNT_DISABLED       */ ||
            code == 0x0002000D /* ERRCONNECT_PASSWORD_CERTAINLY_EXPIRED */;

        JNIEnv* stateEnv = env;  /* same thread; env is valid */
        if (isAuthFailure)
        {
            /* state 3 = AUTH_FAILED (mapped in AFreeRdpBridge.onNativeState / RdpRemoteAdapter) */
            (*stateEnv)->CallVoidMethod(stateEnv, hctx->bridgeObjGlobalRef,
                                         hctx->onStateMethod, 3 /* AUTH_FAILED */);
        }
    }

    /* CRIT-NEW-2 FIX: nativeConnect is declared jboolean but had no return statement
     * on the success path — undefined behaviour in C.  On ARM64 the return register
     * (x0) is left holding whatever the last CallVoidMethod call placed there (usually
     * 0 / false), so Kotlin always received false even when freerdp_connect() returned
     * TRUE.  RdpRemoteAdapter.connect() then cancelled adapterScope and freed the live
     * native connection, making every RDP session appear to fail immediately after
     * the native side successfully connected. */
    return (jboolean)ok;
}

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeSendMouse(
    JNIEnv* env, jobject thiz, jlong handle, jint x, jint y, jint flags)
{
    (void)env; (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance || !instance->context->input) return;
    (void)freerdp_input_send_mouse_event(instance->context->input,
                                          (UINT16)flags, (UINT16)x, (UINT16)y);
}

/* MULTITOUCH FEATURE: called once per Compose pointer-event batch (see
 * RdpCanvas's awaitPointerEventScope loop in RdpSessionActivity.kt), with
 * one entry per finger currently down/changed in that batch — i.e. a real
 * multi-contact RDPEI frame, not N sequential single-pointer calls. contactId
 * is this client's own stable per-finger ID (Compose's PointerId, cast to
 * INT32), reused for the DOWN/UPDATE/UP sequence of that same finger so the
 * server can track it as one contact across the gesture. action is 0=DOWN,
 * 1=UPDATE (move while still down), 2=UP — mapped below to
 * RDPINPUT_CONTACT_FLAG_DOWN/UPDATE/UP.
 *
 * Silently no-ops (same contract as nativeResize/dispContext) when
 * hctx->rdpeiContext is NULL — either the server never opened "rdpei"
 * (no MS-RDPEI support) or the session already disconnected. */
JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeSendTouchFrame(
    JNIEnv* env, jobject thiz, jlong handle,
    jintArray jContactIds, jintArray jXs, jintArray jYs, jintArray jActions, jint count)
{
    (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance || count <= 0) return;
    systemsgoContext* hctx = (systemsgoContext*)instance->context;

    jint* contactIds = (*env)->GetIntArrayElements(env, jContactIds, NULL);
    jint* xs         = (*env)->GetIntArrayElements(env, jXs, NULL);
    jint* ys         = (*env)->GetIntArrayElements(env, jYs, NULL);
    jint* actions    = (*env)->GetIntArrayElements(env, jActions, NULL);
    if (!contactIds || !xs || !ys || !actions) goto cleanup;

    if (hctx->rdpeiLockInitialized) EnterCriticalSection(&hctx->rdpeiLock);
    if (hctx->rdpeiContext && hctx->rdpeiContext->TouchRawEvent)
    {
        for (jint i = 0; i < count; i++)
        {
            UINT32 contactFlags;
            switch (actions[i])
            {
                case 0:  contactFlags = RDPINPUT_CONTACT_FLAG_DOWN;   break;
                case 2:  contactFlags = RDPINPUT_CONTACT_FLAG_UP;     break;
                default: contactFlags = RDPINPUT_CONTACT_FLAG_UPDATE; break;
            }
            /* IN-CONTACT-ID FIX: externalId (this client's own per-finger ID,
             * stable across DOWN/UPDATE/UP for one finger) and contactId are
             * the same INT32 slot here — passed both as the external ID and
             * as the in/out contact-id pointer, matching the shape FreeRDP's
             * own client backends (e.g. client/X11's xf_input.c) use when
             * they already maintain their own per-finger ID rather than
             * asking RDPEI to assign one. See this file's top-of-block
             * NOT VERIFIED comment — confirm this against the vendored
             * rdpei.h if the build fails here. */
            INT32 contactId = (INT32)contactIds[i];
            (void)hctx->rdpeiContext->TouchRawEvent(
                hctx->rdpeiContext, (INT32)contactIds[i],
                (INT32)xs[i], (INT32)ys[i], &contactId, contactFlags);
        }
    }
    if (hctx->rdpeiLockInitialized) LeaveCriticalSection(&hctx->rdpeiLock);

cleanup:
    if (contactIds) (*env)->ReleaseIntArrayElements(env, jContactIds, contactIds, JNI_ABORT);
    if (xs)         (*env)->ReleaseIntArrayElements(env, jXs, xs, JNI_ABORT);
    if (ys)         (*env)->ReleaseIntArrayElements(env, jYs, ys, JNI_ABORT);
    if (actions)    (*env)->ReleaseIntArrayElements(env, jActions, actions, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeSendKey(
    JNIEnv* env, jobject thiz, jlong handle, jint scanCode, jboolean down, jboolean extended)
{
    (void)env; (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance || !instance->context->input) return;
    UINT16 kflags = (UINT16)((down ? 0 : KBD_FLAGS_RELEASE) | (extended ? KBD_FLAGS_EXTENDED : 0));
    (void)freerdp_input_send_keyboard_event(instance->context->input, kflags, (UINT16)scanCode);
}

/* TOOLBOX FEATURE (Stage 2) — "لوحة المفاتيح الحقيقية" (real virtual
 * keyboard, Arabic/English): scancode-based input (nativeSendKey above)
 * assumes an ANSI/US keyboard layout on *both* ends — it has no way to
 * express Arabic letters, since there is no standard PC/AT scancode for
 * them. freerdp_input_send_unicode_keyboard_event() sends the
 * KBD_FLAGS_UNICODE variant of the same PDU, carrying a raw UTF-16 code
 * unit instead of a scancode: the remote Windows session's own IME/keyboard
 * layout handling turns it into the correct character regardless of which
 * physical keyboard layout is active there. This is exactly what
 * mstsc.exe/most modern RDP clients do for non-Latin input, and is the only
 * reliable way to type Arabic (or any non-ANSI-layout script) into the
 * remote session from our own on-screen keyboard.
 *
 * Only a key-down is sent — unicode keyboard events are logically a single
 * "this character was typed" event per the RDP spec (unlike KBD_FLAGS_RELEASE
 * for physical scancodes, a matching "unicode key up" has no separate
 * effect on the character delivered, so callers should not send one; the
 * `down` parameter is kept for symmetry with nativeSendKey / future use
 * rather than needed by the protocol itself).
 *
 * BUILD FIX: there is no "KBD_FLAGS_UNICODE" macro anywhere in FreeRDP's
 * headers (freerdp/input.h only defines KBD_FLAGS_EXTENDED(1)/_DOWN/_RELEASE
 * — flags for *scancode* events); it doesn't exist because it isn't needed:
 * freerdp_input_send_unicode_keyboard_event() is already the dedicated entry
 * point that marks a PDU as carrying a raw UTF-16 code unit instead of a
 * scancode (see input_send_fastpath_unicode_keyboard_event() upstream), so
 * the `flags` argument here only needs KBD_FLAGS_RELEASE for a key-up, which
 * (per the comment above) this function deliberately never sends — so 0. */
JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeSendUnicode(
    JNIEnv* env, jobject thiz, jlong handle, jint utf16CodeUnit)
{
    (void)env; (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance || !instance->context->input) return;
    (void)freerdp_input_send_unicode_keyboard_event(instance->context->input,
                                                      0,
                                                      (UINT16)utf16CodeUnit);
}

/* CLIPBOARD FIX: called from AFreeRdpBridge.sendClipboardText() whenever the
 * Android system clipboard changes locally. Stores the text (as UTF-16LE,
 * the CF_UNICODETEXT wire format) so a later ServerFormatDataRequest can be
 * answered immediately (see systemsgo_cliprdr_server_format_data_request), and
 * announces it to the server right away via ClientFormatList — best-effort,
 * a silent no-op if the cliprdr channel never connected (disabled for this
 * connection, or the server doesn't support RDPECLIP). */
JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeSendClipboardText(
    JNIEnv* env, jobject thiz, jlong handle, jstring jText)
{
    (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance) return;
    systemsgoContext* hctx = SYSTEMSGO_CTX(instance);
    if (!hctx || !hctx->clipboardLockInitialized) return;

    /* GetStringChars gives UTF-16 code units directly — exactly what
     * CF_UNICODETEXT needs on the wire, no re-encoding required (see the
     * scope note above the cliprdr callbacks). */
    jsize len = (*env)->GetStringLength(env, jText);
    const jchar* srcChars = (*env)->GetStringChars(env, jText, NULL);
    if (!srcChars) return;

    WCHAR* copy = (WCHAR*)malloc(((size_t)len + 1) * sizeof(WCHAR));
    if (!copy)
    {
        (*env)->ReleaseStringChars(env, jText, srcChars);
        return;
    }
    memcpy(copy, srcChars, (size_t)len * sizeof(WCHAR));
    copy[len] = 0;
    (*env)->ReleaseStringChars(env, jText, srcChars);

    EnterCriticalSection(&hctx->clipboardLock);
    free(hctx->localClipboardTextW);
    hctx->localClipboardTextW = copy;
    hctx->localClipboardTextWLen = (size_t)len;
    LeaveCriticalSection(&hctx->clipboardLock);

    systemsgo_cliprdr_announce_local_text(hctx);
}

/* REMOTEAPP-WINDOWS FEATURE: local drag/move, the client -> server half —
 * see RailWindow.rect's doc comment ("not something the local user can
 * currently drag, since no client -> server WindowMove request is wired
 * yet") for the gap this closes. Sends a single RAIL_WINDOW_MOVE_ORDER via
 * railContext->ClientWindowMove, exactly the call xf_rail.c's
 * xf_rail_adjust_position()/xf_rail_end_local_move() make once a local X11
 * drag/resize finishes (see
 * https://pub.freerdp.com/api/xf__rail_8c_source.html) — left/top/right/
 * bottom are the window's new screen-space rect in that same INT16 order
 * (right/bottom are one-past-the-edge, not width/height, matching
 * RAIL_WINDOW_MOVE_ORDER's field layout confirmed against
 * https://pub.freerdp.com/api/structRAIL__WINDOW__MOVE__ORDER.html).
 *
 * DELIBERATE SCOPE DIFFERENCE from xf_rail.c: xfreerdp tracks an entire
 * local-move *gesture* (ServerLocalMoveSize start/end, live X11
 * ButtonPress/MotionNotify/ButtonRelease, a synthetic button-up to end it)
 * because X11 windows are real OS windows the WM lets the user drag
 * directly. This app's RAIL windows are just crops of one shared bitmap
 * (see RailWindow's NATIVE SCOPE NOTE) with no OS-level drag of their own —
 * Kotlin owns the drag gesture entirely (pointer tracking against the
 * RailWindow.rect it already renders) and calls this once per drag-end (or
 * throttled during the drag for live server-side feedback), passing the
 * final rect. There is deliberately no ServerLocalMoveSize/
 * RAIL_LOCALMOVESIZE_ORDER handling here to match: this app never asks the
 * server for permission to start a move the way xfreerdp's WM-driven drags
 * do, since there is no local WM initiating one — Kotlin decides when a
 * drag starts and ends entirely on its own. */
JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeSendRailWindowMove(
    JNIEnv* env, jobject thiz, jlong handle, jint windowId,
    jint left, jint top, jint right, jint bottom)
{
    (void)env; (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance) return;
    systemsgoContext* hctx = SYSTEMSGO_CTX(instance);
    if (!hctx || !hctx->railContext || !hctx->railContext->ClientWindowMove) return;

    RAIL_WINDOW_MOVE_ORDER windowMove = { 0 };
    windowMove.windowId = (UINT32)windowId;
    windowMove.left   = (INT16)left;
    windowMove.top    = (INT16)top;
    windowMove.right  = (INT16)right;
    windowMove.bottom = (INT16)bottom;
    (void)hctx->railContext->ClientWindowMove(hctx->railContext, &windowMove);
}

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeDisconnect(JNIEnv* env, jobject thiz, jlong handle)
{
    (void)env; (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance) return;
    freerdp_disconnect(instance);
}

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeFree(JNIEnv* env, jobject thiz, jlong handle)
{
    (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance) return;
    systemsgoContext* hctx = SYSTEMSGO_CTX(instance);
    /* LIVE-RESIZE FIX: mirror the nativeInit subscriptions so a freed/reused
     * pubSub handler list never calls back into a context we're about to free. */
    if (instance->context && instance->context->pubSub)
    {
        PubSub_UnsubscribeChannelConnected(instance->context->pubSub, systemsgo_on_channel_connected);
        PubSub_UnsubscribeChannelDisconnected(instance->context->pubSub, systemsgo_on_channel_disconnected);
    }
    if (hctx && hctx->bridgeObjGlobalRef)
        (*env)->DeleteGlobalRef(env, hctx->bridgeObjGlobalRef);
    /* CLIPBOARD FIX: release the stored local-clipboard buffer and the lock
     * that guards it, mirroring how every other per-session native
     * resource here is torn down exactly once in nativeFree. */
    if (hctx && hctx->clipboardLockInitialized)
    {
        free(hctx->localClipboardTextW);
        hctx->localClipboardTextW = NULL;
        DeleteCriticalSection(&hctx->clipboardLock);
        hctx->clipboardLockInitialized = FALSE;
    }
    /* THREAD-SAFETY FIX: mirror the clipboardLock teardown above for the
     * dispContext lock. */
    if (hctx && hctx->dispLockInitialized)
    {
        DeleteCriticalSection(&hctx->dispLock);
        hctx->dispLockInitialized = FALSE;
    }
    /* MULTITOUCH FEATURE: mirrors the dispContext lock teardown immediately
     * above. */
    if (hctx && hctx->rdpeiLockInitialized)
    {
        DeleteCriticalSection(&hctx->rdpeiLock);
        hctx->rdpeiLockInitialized = FALSE;
    }
    /* MULTI-MONITOR FEATURE: release the declared-monitor snapshot kept for
     * nativeSelectMonitor(). Note this does NOT free the `defs` array handed
     * to freerdp_settings_set_pointer_len() in nativeConnect() — that call
     * copies the array internally, and freerdp_context_free()/freerdp_free()
     * below own that copy's lifetime as part of the settings object. */
    if (hctx)
    {
        free(hctx->declaredMonitors);
        hctx->declaredMonitors = NULL;
        hctx->declaredMonitorCount = 0;
    }
    /* REMOTEAPP-WINDOWS FEATURE (icon decoding): release every decoded
     * icon's pixel buffer plus the cache table itself and the scratch
     * (cacheId 0xFF) slot — mirrors RailIconCache_Free's teardown loop in
     * xf_rail.c, and the same "exactly once in nativeFree" convention as
     * every other per-session native resource in this function. */
    if (hctx && hctx->railIconCache)
    {
        UINT32 total = hctx->railIconCacheNumCaches * hctx->railIconCacheNumEntries;
        for (UINT32 i = 0; i < total; i++)
            free(hctx->railIconCache[i].argb);
        free(hctx->railIconCache);
        hctx->railIconCache = NULL;
        hctx->railIconCacheNumCaches = 0;
        hctx->railIconCacheNumEntries = 0;
    }
    if (hctx)
    {
        free(hctx->railIconScratch.argb);
        hctx->railIconScratch.argb = NULL;
    }
    freerdp_context_free(instance);
    freerdp_free(instance);
}

JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeResize(
    JNIEnv* env, jobject thiz, jlong handle, jint width, jint height)
{
    (void)env; (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance || !instance->context) return JNI_FALSE;
    if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return JNI_FALSE;

    systemsgoContext* hctx = SYSTEMSGO_CTX(instance);
    /* No disp channel connected — either the server doesn't support RDPEDISP,
     * or freerdp_client_load_addins() failed in systemsgo_pre_connect(), or the
     * channel hasn't finished opening yet. This is an expected, non-fatal
     * outcome (see the dispContext field doc): the caller (AFreeRdpBridge /
     * RdpRemoteAdapter) treats `false` as "resize not applied, session
     * continues at its current resolution" rather than an error. */
    if (!hctx) return JNI_FALSE;

    /* THREAD-SAFETY FIX: snapshot dispContext under the lock instead of
     * re-reading hctx->dispContext after the check — the channel thread can
     * null it out (channel disconnect) between a check and a later use.
     * The lock is released before SendMonitorLayout() so a blocking channel
     * send never holds it. */
    DispClientContext* disp;
    EnterCriticalSection(&hctx->dispLock);
    disp = hctx->dispContext;
    LeaveCriticalSection(&hctx->dispLock);

    if (!disp || !disp->SendMonitorLayout)
    {
        LOGI("LIVE-RESIZE: disp channel not available — ignoring resize request to %dx%d", width, height);
        return JNI_FALSE;
    }

    /* MS-RDPEDISP §2.2.2.2 requires each monitor's width to be even. */
    UINT32 w = (UINT32)(width - (width % 2));
    UINT32 h = (UINT32)height;

    DISPLAY_CONTROL_MONITOR_LAYOUT layout;
    memset(&layout, 0, sizeof(layout));
    layout.Flags               = DISPLAY_CONTROL_MONITOR_PRIMARY;
    layout.Left                = 0;
    layout.Top                 = 0;
    layout.Width               = w;
    layout.Height              = h;
    layout.PhysicalWidth       = 0;
    layout.PhysicalHeight      = 0;
    layout.Orientation         = ORIENTATION_LANDSCAPE;
    layout.DesktopScaleFactor  = 100;
    layout.DeviceScaleFactor   = 100;

    UINT rc = disp->SendMonitorLayout(disp, 1, &layout);
    if (rc != CHANNEL_RC_OK)
    {
        LOGE("LIVE-RESIZE: SendMonitorLayout failed (rc=0x%08X) for %ux%u", rc, w, h);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/* MULTI-MONITOR FEATURE: static (compile-time capability, not per-session) —
 * see AFreeRdpBridge.isMultiMonitorAvailable's doc for why this is expected
 * true whenever isAvailable is, but still probed rather than assumed. */
JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeIsMultiMonitorAvailable(JNIEnv* env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return JNI_TRUE;
}

/* MULTI-MONITOR FEATURE: switch which monitor(s) are active, live, over the
 * same "disp" channel nativeResize() above already uses. See
 * AFreeRdpBridge.selectMonitor's doc for the best-effort semantics.
 *
 * showAll==TRUE re-sends every declared monitor (the original layout from
 * nativeConnect). showAll==FALSE sends a single-monitor layout built from
 * hctx->declaredMonitors[monitorId], repositioned to (0,0) — from the
 * server's point of view this behaves like an ordinary single-monitor
 * session sized to that one monitor, which is what "Monitor N" (as opposed
 * to "All Monitors") means in the requirements this implements. */
JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeSelectMonitor(
    JNIEnv* env, jobject thiz, jlong handle, jint monitorId, jboolean showAll)
{
    (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance || !instance->context) return JNI_FALSE;
    systemsgoContext* hctx = SYSTEMSGO_CTX(instance);
    if (!hctx) return JNI_FALSE;

    /* THREAD-SAFETY FIX: see nativeResize() above for why this must be a
     * locked snapshot rather than a raw hctx->dispContext read/use. */
    DispClientContext* disp;
    EnterCriticalSection(&hctx->dispLock);
    disp = hctx->dispContext;
    LeaveCriticalSection(&hctx->dispLock);

    if (!disp || !disp->SendMonitorLayout)
    {
        LOGI("MULTI-MONITOR: disp channel not available — ignoring monitor switch request");
        return JNI_FALSE;
    }
    if (hctx->declaredMonitorCount == 0 || !hctx->declaredMonitors)
    {
        LOGI("MULTI-MONITOR: session has no declared multi-monitor layout — ignoring switch request");
        return JNI_FALSE;
    }

    UINT rc;
    if (showAll)
    {
        DISPLAY_CONTROL_MONITOR_LAYOUT* layouts =
            (DISPLAY_CONTROL_MONITOR_LAYOUT*)calloc(hctx->declaredMonitorCount, sizeof(DISPLAY_CONTROL_MONITOR_LAYOUT));
        if (!layouts) return JNI_FALSE;
        for (UINT32 i = 0; i < hctx->declaredMonitorCount; i++)
        {
            layouts[i].Flags = hctx->declaredMonitors[i].isPrimary ? DISPLAY_CONTROL_MONITOR_PRIMARY : 0;
            layouts[i].Left = hctx->declaredMonitors[i].x;
            layouts[i].Top = hctx->declaredMonitors[i].y;
            layouts[i].Width = hctx->declaredMonitors[i].width - (hctx->declaredMonitors[i].width % 2);
            layouts[i].Height = hctx->declaredMonitors[i].height;
            layouts[i].Orientation = ORIENTATION_LANDSCAPE;
            layouts[i].DesktopScaleFactor = hctx->declaredMonitors[i].dpiScaleFactor;
            layouts[i].DeviceScaleFactor = 100;
        }
        rc = disp->SendMonitorLayout(disp, hctx->declaredMonitorCount, layouts);
        free(layouts);
    }
    else
    {
        UINT32 idx = (UINT32)-1;
        for (UINT32 i = 0; i < hctx->declaredMonitorCount; i++)
        {
            if (hctx->declaredMonitors[i].id == (UINT32)monitorId) { idx = i; break; }
        }
        if (idx == (UINT32)-1)
        {
            LOGE("MULTI-MONITOR: monitorId %d not found in declared layout", (int)monitorId);
            return JNI_FALSE;
        }
        DISPLAY_CONTROL_MONITOR_LAYOUT layout;
        memset(&layout, 0, sizeof(layout));
        layout.Flags = DISPLAY_CONTROL_MONITOR_PRIMARY;
        layout.Left = 0;
        layout.Top = 0;
        layout.Width = hctx->declaredMonitors[idx].width - (hctx->declaredMonitors[idx].width % 2);
        layout.Height = hctx->declaredMonitors[idx].height;
        layout.Orientation = ORIENTATION_LANDSCAPE;
        layout.DesktopScaleFactor = hctx->declaredMonitors[idx].dpiScaleFactor;
        layout.DeviceScaleFactor = 100;
        rc = disp->SendMonitorLayout(disp, 1, &layout);
    }

    if (rc != CHANNEL_RC_OK)
    {
        LOGE("MULTI-MONITOR: SendMonitorLayout failed (rc=0x%08X)", rc);
        return JNI_FALSE;
    }
    systemsgo_notify_monitor_layout(hctx, env);
    return JNI_TRUE;
}

/* REMOTE-AUDIO FEATURE: was intended to forward locally-captured microphone
 * PCM (e.g. from an Android-side AudioRecord in Kotlin) into the "audin"
 * channel by hand. As of the REAL-PCM FIX this path is superseded, not
 * merely unimplemented: FreeRDP's own compiled-in Android audin backend
 * (-DWITH_OPENSLES=ON, channels/audin/client/opensles/ — see SETUP.md)
 * records the microphone itself via OpenSL ES and feeds the "audin" channel
 * directly inside libfreerdp-client3.so, entirely independent of this call.
 * Deliberately left as a safe permanent no-op (rather than removed) so
 * AFreeRdpBridge.sendAudioCapture's JNI binding keeps working if any caller
 * still invokes it; it never needs to do anything now, and wiring it up to
 * also inject PCM would just create a second, redundant capture path
 * feeding the same channel. */
JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpBridge_nativeSendAudioCapture(
    JNIEnv* env, jobject thiz, jlong handle, jbyteArray pcm)
{
    (void)thiz; (void)pcm;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance || !instance->context) return;
    systemsgoContext* hctx = SYSTEMSGO_CTX(instance);
    if (!hctx || !hctx->audinContext)
        return; /* audin channel not connected — best-effort no-op */
    (void)env;
    /* Intentional no-op — see doc comment above. */
}
