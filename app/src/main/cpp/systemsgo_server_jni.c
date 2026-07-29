/*
 * systemsgo_server_jni.c — JNI bridge between Kotlin
 * (com.systemsgo.hex.rdp.native.AFreeRdpServerBridge) and FreeRDP's
 * SERVER-side peer/listener API (include/freerdp/peer.h,
 * include/freerdp/listener.h), i.e. libfreerdp-server3.so.
 *
 * SHADOW-SERVER FEATURE (milestone 2, on top of milestone 1 below):
 * nativePushFrame() (near the bottom of this file, next to the other JNI
 * entry points) lets Kotlin replace the static placeholder framebuffer with
 * real captured screen content — see ShadowScreenCaptureService.kt, which
 * drives it from Android's MediaProjection API. This file's job is only to
 * (a) hold on to the most recently pushed frame buffer and (b) fan it out
 * as a BitmapUpdate to every currently-connected peer, plus hand a
 * newly-Activate'd peer whatever the latest frame already is instead of
 * always starting from the grey placeholder. See the peer-registry block
 * (g_activePeers/g_peersMutex) and systemsgo_server_send_frame_to_peer() below
 * for the mechanics. Remote-client input (peerKeyEvents/peerMouseEvents,
 * already wired in milestone 1) is consumed on the Kotlin side by
 * RemoteInputAccessibilityService for the touch-injection half of Shadow
 * Server — nothing in this native file changes for that part.
 *
 * RDP-SERVER-API FEATURE (milestone 1 of the Server API / Shadow Server /
 * RDP Proxy roadmap described in SETUP.md):
 *
 * This is a SEPARATE native library/target from systemsgo_jni.c. systemsgo_jni.c
 * is exclusively a CLIENT (this device dials OUT to a remote RDP/VNC/SSH
 * host, via libfreerdp-client3.so). This file is the mirror image: this
 * device LISTENS for incoming RDP connections and acts as the RDP host,
 * via libfreerdp-server3.so — the library main.yml's "Build FreeRDP
 * prebuilt" step now also produces because that step passes
 * -DWITH_SERVER=ON (see that step's RDP-SERVER-API FEATURE comment).
 *
 * SCOPE OF THIS MILESTONE — read before wiring this into any UI:
 *   - Implements: TCP listener, X.224/MCS/GCC connection sequence (all
 *     handled internally by libfreerdp-server3 — this file only supplies
 *     the peer callbacks it requires), capability negotiation (accepting
 *     FreeRDP's negotiated defaults), PostConnect/Activate, and forwarding
 *     of keyboard/mouse input events from the connecting RDP client up to
 *     Kotlin.
 *   - Framebuffer: sends ONE static solid-colour full-screen bitmap update
 *     on Activate, then nativePushFrame() (below) lets Kotlin push
 *     replacement frames (as a raw BGRA/RGB Android Bitmap) at any time —
 *     but nothing in THIS milestone calls nativePushFrame() with real
 *     screen content yet. That is deliberately left for the next roadmap
 *     item ("Shadow Server", which wires Android's MediaProjection API as
 *     the frame source — see SETUP.md). Until that lands, connecting to
 *     this device with a real RDP client will show a single static colour
 *     screen, not the device's actual display — this milestone's purpose
 *     is to validate that the listen/accept/negotiate/input pipeline
 *     itself works end-to-end, which Shadow Server and RDP Proxy both
 *     build on directly.
 *   - Security (TLS-SERVER FEATURE, updated from milestone 1): when
 *     nativeStart()'s certPath/keyPath are non-null (the normal case now
 *     — Kotlin generates a self-signed pair via
 *     RdpServerCertificateGenerator.kt before every start()),
 *     systemsgo_server_configure_settings() below turns TlsSecurity ON and
 *     RdpSecurity OFF, handing FreeRDP's transport that certificate/key
 *     to perform a real TLS handshake with — this is the tier modern
 *     `mstsc` and most current RDP clients default to expecting, unlike
 *     milestone 1's legacy "Standard RDP Security" (still available as a
 *     fallback if certPath/keyPath are left null, e.g. for the old
 *     pipeline test with /sec:rdp against xfreerdp/AFreeRdpBridge).
 *     NlaSecurity stays OFF either way — see configure_settings()'s own
 *     comment for exactly why that's a separate, bigger, not-done-here
 *     piece of work, not just a flag flip. A lightweight app-level
 *     credential check (systemsgo_server_peer_logon(), also updated from
 *     milestone 1's unconditional-accept) is this feature's substitute
 *     for real NLA-grade authentication.
 *   - Single active listener at a time (module-level static state, no
 *     support for multiple concurrent listen ports) — matches this
 *     milestone's "prove the pipeline" scope; revisit if/when the RDP
 *     Proxy roadmap item needs more than one.
 *
 * NOT VERIFIED AGAINST A REAL COMPILE (same caveat this whole project
 * documents elsewhere for any change made without NDK/network access —
 * see SETUP.md's top-level notice, and systemsgo_jni.c's rdpei/rail comments
 * for the established precedent of flagging this explicitly): this is the
 * very first file in this project to include <freerdp/peer.h> and
 * <freerdp/listener.h> and link against libfreerdp-server3.so. The overall
 * shape (freerdp_listener_new/Open/accept loop; freerdp_peer_new via
 * PeerAccepted; ContextNew/ContextSize; Capabilities/PostConnect/Activate/
 * Logon callbacks; client->input->KeyboardEvent/MouseEvent/
 * UnicodeKeyboardEvent/ExtendedMouseEvent; client->update->BitmapUpdate)
 * mirrors FreeRDP's own server samples (server/shadow/shadow_server.c,
 * server/Mac/mf_peer.c, server/proxy/pf_server.c — all in the FreeRDP
 * source tree this project does not vendor a copy of), but individual
 * struct field names/callback signatures are version-sensitive and have
 * changed across FreeRDP releases before. Treat a build failure in THIS
 * file as expected-until-proven-otherwise, and fix it against the actual
 * vendored include/freerdp3/freerdp/{peer.h,listener.h,update.h} for
 * FREERDP_TAG 3.27.1 once CI produces them — that is the first place to
 * look, the same workflow already used for every other *_BACKEND_AVAILABLE
 * feature in systemsgo_jni.c.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>

#include <freerdp/freerdp.h>
#include <freerdp/peer.h>
#include <freerdp/listener.h>
#include <freerdp/codec/color.h>
#include <winpr/synch.h>
#include <winpr/ssl.h>
#include <winpr/crt.h> /* TLS-SERVER FEATURE: WideCharToMultiByte for the
                         * credential check in systemsgo_server_peer_logon().
                         * NOT VERIFIED AGAINST A REAL COMPILE: no other
                         * file in this project calls this WinPR shim yet,
                         * so its exact header/signature is unconfirmed
                         * here — check winpr/crt.h once CI's vendored
                         * headers are available if this doesn't link. */

#define TAG "HexRdpServerJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Mirrors systemsgoContext in systemsgo_jni.c: FreeRDP requires the server-side
 * per-connection context to embed rdpContext as its first member
 * (freerdp_peer_context_new() memsets/positions based on that). One of
 * these is allocated per connecting client (client->ContextSize below). */
typedef struct
{
    rdpContext context;
    JavaVM* jvm;
    jobject bridgeObjGlobalRef;   /* AFreeRdpServerBridge instance, GlobalRef */
    jmethodID onPeerConnectedMethod;      /* (Ljava/lang/String;)V  — client address */
    jmethodID onPeerDisconnectedMethod;   /* (Ljava/lang/String;)V */
    jmethodID onPeerKeyboardMethod;       /* (IZI)V  — scancode, down, flags */
    jmethodID onPeerMouseMethod;          /* (III)V  — flags, x, y */
    char clientAddress[64];
} systemsgoServerPeerContext;

/* Module-level (single-listener) state — see top-of-file "single active
 * listener at a time" scope note. */
static freerdp_listener* g_listener = NULL;
static pthread_t g_acceptThread;
static volatile BOOL g_running = FALSE;
static UINT32 g_desktopWidth = 1280;
static UINT32 g_desktopHeight = 720;
static JavaVM* g_jvm = NULL;
static jobject g_bridgeGlobalRef = NULL;

/* TLS-SERVER FEATURE: PEM cert/key file paths handed in via nativeStart()'s
 * certPath/keyPath (Kotlin now always generates+passes a real self-signed
 * pair — see RdpServerCertificateGenerator.kt — these are no longer
 * "unused placeholders"). Owned (strdup'd) by this file, freed in
 * nativeStop(); NULL means "no cert configured", which keeps this
 * milestone's original Standard-RDP-Security-only fallback behavior so an
 * old caller that still passes null doesn't regress. */
static char* g_certPath = NULL;
static char* g_keyPath = NULL;

/* NLA-SERVER FEATURE: path to a WinPR SAM-format credential file (same
 * format `winpr-hash`/freerdp-shadow-cli's /sam-file: use — a text file
 * with lines like "username:::<NTLM-hash-hex>:::", one connectable
 * identity per line — see RdpServerNlaCredentials.kt for how Kotlin
 * generates it from a plain username/password without needing the
 * winpr-hash CLI tool). Handed to FreeRDP via the FreeRDP_NtlmSamFile
 * string setting (confirmed real FreeRDP 3.x API — libfreerdp/core/nla.c
 * and libfreerdp/core/credssp_auth.c both read settings->NtlmSamFile
 * directly), which lets FreeRDP's own nla_server_authenticate() do a real
 * CredSSP/NTLM handshake against it — this is genuine NLA, not the
 * app-level string-compare substitute systemsgo_server_peer_logon() used
 * before this file had a SAM file to point at. NULL (the default) means
 * NLA stays off and configure_settings() falls back to TLS-only or
 * Standard-RDP-Security, same as before this feature existed. */
static char* g_samFilePath = NULL;

/* SHADOW-SERVER FEATURE: registry of currently-connected peers, so
 * nativePushFrame() (below) has something to fan a new frame out to.
 * Deliberately a small fixed-size array (not a linked list) — matches this
 * whole file's "single active listener, LAN-only testing" scope; revisit if
 * RDP Proxy or real multi-viewer support ever needs more than a handful of
 * concurrent shadow viewers. Guarded by g_peersMutex because
 * systemsgo_server_peer_accepted()/the per-peer thread's cleanup run on
 * different threads than nativePushFrame()'s caller (a Kotlin capture
 * thread/coroutine). */
#define SYSTEMSGO_SERVER_MAX_PEERS 8
static freerdp_peer* g_activePeers[SYSTEMSGO_SERVER_MAX_PEERS];
static pthread_mutex_t g_peersMutex = PTHREAD_MUTEX_INITIALIZER;

/* SHADOW-SERVER FEATURE: the most recently pushed real frame (BGRX32, see
 * systemsgo_server_send_frame_to_peer's format note), so that (a) a peer
 * Activating after capture has already started gets real content
 * immediately instead of one grey frame first, and (b) nativePushFrame()
 * has a stable buffer to fan out from without re-deriving it from a Kotlin
 * Bitmap on every peer. NULL until the first nativePushFrame() call —
 * systemsgo_server_peer_activate() falls back to the solid-grey placeholder
 * (milestone 1 behaviour) until then, e.g. if the server API is started
 * without ever starting screen capture. Guarded by g_frameMutex. */
static BYTE* g_latestFrame = NULL;
static UINT32 g_latestFrameWidth = 0;
static UINT32 g_latestFrameHeight = 0;
static pthread_mutex_t g_frameMutex = PTHREAD_MUTEX_INITIALIZER;

static void systemsgo_server_register_peer(freerdp_peer* client)
{
    pthread_mutex_lock(&g_peersMutex);
    for (int i = 0; i < SYSTEMSGO_SERVER_MAX_PEERS; i++)
    {
        if (g_activePeers[i] == NULL)
        {
            g_activePeers[i] = client;
            break;
        }
    }
    pthread_mutex_unlock(&g_peersMutex);
}

static void systemsgo_server_unregister_peer(freerdp_peer* client)
{
    pthread_mutex_lock(&g_peersMutex);
    for (int i = 0; i < SYSTEMSGO_SERVER_MAX_PEERS; i++)
    {
        if (g_activePeers[i] == client)
        {
            g_activePeers[i] = NULL;
            break;
        }
    }
    pthread_mutex_unlock(&g_peersMutex);
}

/* ── Peer input callbacks ─────────────────────────────────────────────── */

static void systemsgo_server_attach_env(JNIEnv** envOut, systemsgoServerPeerContext* spc, BOOL* didAttach)
{
    *didAttach = FALSE;
    if ((*spc->jvm)->GetEnv(spc->jvm, (void**)envOut, JNI_VERSION_1_6) != JNI_OK)
    {
        if ((*spc->jvm)->AttachCurrentThread(spc->jvm, envOut, NULL) == JNI_OK)
            *didAttach = TRUE;
        else
            *envOut = NULL;
    }
}

static BOOL systemsgo_server_input_keyboard(rdpInput* input, UINT16 flags, UINT8 code)
{
    systemsgoServerPeerContext* spc = (systemsgoServerPeerContext*)input->context;
    JNIEnv* env;
    BOOL didAttach;
    systemsgo_server_attach_env(&env, spc, &didAttach);
    if (env && spc->onPeerKeyboardMethod)
    {
        (*env)->CallVoidMethod(env, spc->bridgeObjGlobalRef, spc->onPeerKeyboardMethod,
                                (jint)code, (jboolean)((flags & KBD_FLAGS_RELEASE) == 0), (jint)flags);
    }
    if (didAttach) (*spc->jvm)->DetachCurrentThread(spc->jvm);
    return TRUE;
}

static BOOL systemsgo_server_input_unicode(rdpInput* input, UINT16 flags, UINT16 code)
{
    /* NOT VERIFIED: routed through the same numeric callback as scancode
     * input for this milestone (flags carries KBD_FLAGS_UNICODE-equivalent
     * context on the Kotlin side is NOT currently distinguished — a
     * follow-up can split this into its own onPeerUnicodeKeyboard callback
     * if IME/non-Latin input turns out to need it). */
    return systemsgo_server_input_keyboard(input, flags, (UINT8)(code & 0xFF));
}

static BOOL systemsgo_server_input_mouse(rdpInput* input, UINT16 flags, UINT16 x, UINT16 y)
{
    systemsgoServerPeerContext* spc = (systemsgoServerPeerContext*)input->context;
    JNIEnv* env;
    BOOL didAttach;
    systemsgo_server_attach_env(&env, spc, &didAttach);
    if (env && spc->onPeerMouseMethod)
    {
        (*env)->CallVoidMethod(env, spc->bridgeObjGlobalRef, spc->onPeerMouseMethod,
                                (jint)flags, (jint)x, (jint)y);
    }
    if (didAttach) (*spc->jvm)->DetachCurrentThread(spc->jvm);
    return TRUE;
}

static BOOL systemsgo_server_input_extended_mouse(rdpInput* input, UINT16 flags, UINT16 x, UINT16 y)
{
    /* Wheel / X1-X2 buttons — folded into the same mouse callback for this
     * milestone (flags distinguishes PTR_XFLAGS_* from PTR_FLAGS_*). */
    return systemsgo_server_input_mouse(input, flags, x, y);
}

/* ── Peer lifecycle callbacks ─────────────────────────────────────────── */

static BOOL systemsgo_server_peer_capabilities(freerdp_peer* client)
{
    /* Accept FreeRDP's own negotiated capability defaults — no custom
     * capability trimming in this milestone. */
    (void)client;
    return TRUE;
}

/* RDP-SERVER-API FEATURE: sends the single static framebuffer this
 * milestone ships (see top-of-file SCOPE note) via the classic raw
 * BitmapUpdate path — the most version-stable server output primitive
 * FreeRDP exposes (predates the RDPGFX pipeline systemsgo_jni.c's CLIENT side
 * uses; a server-side RDPGFX path is a reasonable future optimization once
 * Shadow Server needs the bandwidth savings, not needed to prove the
 * pipeline here).
 *
 * NOT VERIFIED: BITMAP_UPDATE / BITMAP_DATA field names below reflect
 * FreeRDP's long-standing (since 1.x) freerdp/update.h shape; confirm
 * against the actual vendored header if CI reports a mismatch here. */
/* SHADOW-SERVER FEATURE: sends one full-screen BitmapUpdate to a single
 * peer. If [sourceBuffer] is non-NULL (and its dimensions match this
 * server's configured desktop size) it is sent as-is — this is the path
 * nativePushFrame() and a freshly-Activate'd peer (when a frame has already
 * been captured) both use. If NULL, falls back to the milestone-1 solid
 * mid-grey placeholder, allocated here. Format is BGRX32 either way (see
 * ShadowScreenCaptureService.kt's RGBA->BGRX swap on the Kotlin side —
 * FreeRDP's raw BitmapUpdate path, unlike RDPGFX, has no built-in RGBA
 * variant to lean on instead). */
static BOOL systemsgo_server_send_frame_to_peer(freerdp_peer* client, const BYTE* sourceBuffer,
                                              UINT32 srcWidth, UINT32 srcHeight)
{
    rdpSettings* settings = client->context->settings;
    const UINT32 width = freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth);
    const UINT32 height = freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight);
    const int bpp = 4; /* PIXEL_FORMAT_BGRX32 */

    BOOL ownBuffer = FALSE;
    const BYTE* buffer = NULL;

    if (sourceBuffer != NULL && srcWidth == width && srcHeight == height)
    {
        buffer = sourceBuffer;
    }
    else
    {
        /* Placeholder path (milestone 1) — also the safety fallback if a
         * pushed frame's dimensions ever don't match this server's fixed
         * desktop size (e.g. captured display rotated after Shadow Server
         * started) rather than sending a corrupt/mismatched buffer. */
        BYTE* grey = (BYTE*)calloc((size_t)width * height, bpp);
        if (!grey)
        {
            LOGE("systemsgo_server_send_frame_to_peer: OOM allocating %ux%u frame", width, height);
            return FALSE;
        }
        memset(grey, 0x40, (size_t)width * height * bpp);
        buffer = grey;
        ownBuffer = TRUE;
    }

    BITMAP_DATA bitmapData = { 0 };
    bitmapData.width = width;
    bitmapData.height = height;
    bitmapData.destLeft = 0;
    bitmapData.destTop = 0;
    bitmapData.destRight = width - 1;
    bitmapData.destBottom = height - 1;
    bitmapData.bitsPerPixel = 32;
    bitmapData.cbScanWidth = width * bpp;
    bitmapData.cbUncompressedSize = width * height * bpp;
    bitmapData.bitmapDataStream = (BYTE*)buffer; /* FreeRDP's struct field isn't const-qualified */
    bitmapData.bitmapLength = width * height * bpp;
    bitmapData.compressed = FALSE;

    BITMAP_UPDATE bitmapUpdate = { 0 };
    bitmapUpdate.count = bitmapUpdate.number = 1;
    bitmapUpdate.rectangles = &bitmapData;

    BOOL ok = FALSE;
    if (client->context->update && client->context->update->BitmapUpdate)
        ok = client->context->update->BitmapUpdate(client->context, &bitmapUpdate);
    else
        LOGE("systemsgo_server_send_frame_to_peer: update->BitmapUpdate is NULL");

    if (ownBuffer)
        free((BYTE*)buffer);
    return ok;
}

/* SHADOW-SERVER FEATURE: fans [buffer] out to every currently-registered
 * peer — this is nativePushFrame()'s actual broadcast primitive. Held
 * under g_peersMutex for the duration, same as register/unregister
 * above. */
static void systemsgo_server_broadcast_frame(const BYTE* buffer, UINT32 width, UINT32 height)
{
    pthread_mutex_lock(&g_peersMutex);
    for (int i = 0; i < SYSTEMSGO_SERVER_MAX_PEERS; i++)
    {
        if (g_activePeers[i] != NULL)
            systemsgo_server_send_frame_to_peer(g_activePeers[i], buffer, width, height);
    }
    pthread_mutex_unlock(&g_peersMutex);
}

static BOOL systemsgo_server_peer_post_connect(freerdp_peer* client)
{
    systemsgoServerPeerContext* spc = (systemsgoServerPeerContext*)client->context;
    rdpSettings* settings = client->context->settings;

    LOGI("SystemsGo server: PostConnect from %s (%ux%u requested)", spc->clientAddress,
         freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth),
         freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight));

    /* Pin the session to this module's configured resolution rather than
     * whatever the connecting client asked for — real per-client resize
     * negotiation (RDPEDISP, server side) is a follow-up item, mirroring
     * the CLIENT-side LIVE-RESIZE FIX in systemsgo_jni.c but not yet ported
     * to the server direction. */
    (void)freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth, g_desktopWidth);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight, g_desktopHeight);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, 32);

    JNIEnv* env;
    BOOL didAttach;
    systemsgo_server_attach_env(&env, spc, &didAttach);
    if (env && spc->onPeerConnectedMethod)
    {
        jstring jaddr = (*env)->NewStringUTF(env, spc->clientAddress);
        (*env)->CallVoidMethod(env, spc->bridgeObjGlobalRef, spc->onPeerConnectedMethod, jaddr);
        (*env)->DeleteLocalRef(env, jaddr);
    }
    if (didAttach) (*spc->jvm)->DetachCurrentThread(spc->jvm);

    return TRUE;
}

static BOOL systemsgo_server_peer_activate(freerdp_peer* client)
{
    client->context->settings->CompressionEnabled = FALSE;

    /* SHADOW-SERVER FEATURE: hand this newly-activated peer whatever the
     * latest captured frame already is, instead of always starting from
     * the grey placeholder — matters for a viewer that connects after
     * capture has already been running for a while. */
    pthread_mutex_lock(&g_frameMutex);
    BOOL ok = systemsgo_server_send_frame_to_peer(client, g_latestFrame, g_latestFrameWidth, g_latestFrameHeight);
    pthread_mutex_unlock(&g_frameMutex);
    return ok;
}

/* TLS-SERVER FEATURE: application-level credential check, run once the
 * connecting client has sent its logon identity (Standard RDP Security
 * clients send this over the now-TLS-protected channel when haveCert is
 * true above; it also fires, unauthenticated-transport, on the fallback
 * no-cert path). g_expectedUsername/g_expectedPassword are whatever the
 * user set up on this device's own Shadow Server / RDP Proxy "who can
 * connect" screen (see AFreeRdpServerBridge.setExpectedCredentials) — if
 * neither was configured (both NULL, this feature's off-by-default state)
 * this keeps the previous milestone's accept-everyone behavior so nothing
 * already shipping regresses.
 *
 * This is deliberately simple string comparison, not SAM/NTLM hashing —
 * identity->Username/Password here arrive already as plain UTF-16/UTF-8 by
 * the time this callback fires (FreeRDP decoded them off whatever wire
 * tier is active), so there's no hash to compare against. Good enough as
 * "only someone who knows this device's PIN-like credential gets in", not
 * a substitute for real NLA/CredSSP (see the configure_settings() comment
 * above for why that's a separate, bigger piece of work). */
static char* g_expectedUsername = NULL;
static char* g_expectedPassword = NULL;

static BOOL systemsgo_server_peer_logon(freerdp_peer* client, SEC_WINNT_AUTH_IDENTITY* identity, BOOL automatic)
{
    (void)client;
    (void)automatic;

    if (!g_expectedUsername || !g_expectedPassword ||
        g_expectedUsername[0] == '\0')
    {
        /* Feature not configured — preserve prior behavior. */
        return TRUE;
    }

    if (!identity || !identity->User || !identity->Password)
    {
        LOGW("SystemsGo server: peer sent no credentials but this device requires them — rejecting");
        return FALSE;
    }

    /* NOT VERIFIED AGAINST A REAL COMPILE: assumes SEC_WINNT_AUTH_IDENTITY's
     * User/Password are UINT16* (UTF-16LE) with separate *Length fields —
     * the long-standing shape of that struct across FreeRDP/WinPR
     * releases, but this project doesn't vendor winpr/sspi.h to confirm it
     * for 3.27.1, and no other file here uses this struct yet (unlike most
     * of this file's other NOT-VERIFIED notes, there's no existing
     * in-project precedent to lean on for this one). Fix against the
     * vendored winpr/sspi.h if CI reports a mismatch. */
    char userUtf8[256];
    char passUtf8[256];
    int userLen = WideCharToMultiByte(CP_UTF8, 0, (LPCWSTR)identity->User,
                                       (int)identity->UserLength, userUtf8,
                                       (int)sizeof(userUtf8) - 1, NULL, NULL);
    int passLen = WideCharToMultiByte(CP_UTF8, 0, (LPCWSTR)identity->Password,
                                       (int)identity->PasswordLength, passUtf8,
                                       (int)sizeof(passUtf8) - 1, NULL, NULL);
    if (userLen < 0) userLen = 0;
    if (passLen < 0) passLen = 0;
    userUtf8[userLen] = '\0';
    passUtf8[passLen] = '\0';

    BOOL ok = (strcmp(userUtf8, g_expectedUsername) == 0) &&
              (strcmp(passUtf8, g_expectedPassword) == 0);
    if (!ok)
        LOGW("SystemsGo server: rejecting peer — credential mismatch");
    return ok;
}

static BOOL systemsgo_server_peer_context_new(freerdp_peer* client, rdpContext* context)
{
    systemsgoServerPeerContext* spc = (systemsgoServerPeerContext*)context;
    spc->jvm = g_jvm;
    spc->bridgeObjGlobalRef = g_bridgeGlobalRef;

    JNIEnv* env;
    BOOL didAttach;
    systemsgo_server_attach_env(&env, spc, &didAttach);
    if (env)
    {
        jclass cls = (*env)->GetObjectClass(env, spc->bridgeObjGlobalRef);
        spc->onPeerConnectedMethod = (*env)->GetMethodID(env, cls, "onNativePeerConnected", "(Ljava/lang/String;)V");
        spc->onPeerDisconnectedMethod = (*env)->GetMethodID(env, cls, "onNativePeerDisconnected", "(Ljava/lang/String;)V");
        spc->onPeerKeyboardMethod = (*env)->GetMethodID(env, cls, "onNativePeerKeyboard", "(IZI)V");
        spc->onPeerMouseMethod = (*env)->GetMethodID(env, cls, "onNativePeerMouse", "(III)V");
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, cls);
    }
    if (didAttach) (*spc->jvm)->DetachCurrentThread(spc->jvm);

    return TRUE;
}

static void systemsgo_server_peer_context_free(freerdp_peer* client, rdpContext* context)
{
    (void)client;
    (void)context;
}

/* TLS-SERVER FEATURE: settings applied to EVERY accepted peer before its
 * own connection sequence starts.
 *
 * When g_certPath/g_keyPath are both set (the normal case now — see
 * RdpServerCertificateGenerator.kt, which nativeStart()'s caller runs
 * before every start()), this configures the "TLS Security" tier: a real
 * X.509 cert/key pair is handed to FreeRDP's own transport, which performs
 * the TLS handshake internally (this file never touches OpenSSL directly
 * for it — same division of responsibility FreeRDP's own server samples
 * use). RdpSecurity is turned OFF in that case so a connecting client
 * can't be silently downgraded to the old unauthenticated-transport tier;
 * NlaSecurity stays OFF here too — see the NLA-SERVER-FEATURE note below,
 * this function only ever turns on TLS, not full CredSSP/NLA.
 *
 * When no cert/key is configured (nativeStart() called with null certPath/
 * keyPath — kept only for backward compatibility / the old milestone-1
 * pipeline test), this falls back to the original Standard RDP Security
 * behavior: FreeRDP generates its own throwaway RSA keypair internally,
 * no certificate file needed, but the connection is unauthenticated at
 * the transport level. Never treat that fallback path as safe outside a
 * LAN/VPN — see AFreeRdpServerBridge.kt's doc comment, still accurate for
 * this path specifically.
 *
 * NLA-SERVER-FEATURE (now actually done, superseding the note this
 * paragraph used to have): confirmed against FreeRDP's own upstream
 * source (libfreerdp/core/nla.c's nla_new(), which does
 * `if (settings->NtlmSamFile) nla->SamFile = _strdup(settings->NtlmSamFile)`,
 * and libfreerdp/core/credssp_auth.c's ntlm_settings->samFile wiring) —
 * settings->NtlmSamFile is a real, plain string setting (same
 * freerdp_settings_set_string() pattern already used above for
 * CertificateFile/PrivateKeyFile), pointing at a WinPR SAM-format text
 * file (one line per connectable identity: "username:::<NTLM-hash-hex>:::"
 * — the exact format freerdp-shadow-cli's own /sam-file: option and the
 * winpr-hash CLI tool use). When g_samFilePath is set, FreeRDP's own
 * nla_server code performs the actual CredSSP/NTLM handshake against it
 * BEFORE the RDP session starts — this is genuine NLA, not the
 * app-level string-compare systemsgo_server_peer_logon() still does as a
 * defense-in-depth extra check afterward (harmless once NLA already
 * gated the connection, since by then the credential already matched).
 * NLA still requires TLS underneath per the CredSSP spec (MS-CSSP) — see
 * the haveNla branch below, which only takes effect when a cert is ALSO
 * configured. See RdpServerNlaCredentials.kt for how the SAM file itself
 * gets generated from a plain username/password without needing the
 * winpr-hash CLI tool (an MD4-based NTLM hash, computed with
 * BouncyCastle, already a project dependency). */
static void systemsgo_server_configure_settings(rdpSettings* settings)
{
    const BOOL haveCert = (g_certPath != NULL && g_certPath[0] != '\0' &&
                            g_keyPath  != NULL && g_keyPath[0]  != '\0');
    const BOOL haveNla  = (haveCert && g_samFilePath != NULL && g_samFilePath[0] != '\0');

    if (haveNla)
    {
        (void)freerdp_settings_set_bool(settings, FreeRDP_RdpSecurity, FALSE);
        (void)freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, TRUE);
        (void)freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, TRUE);
        (void)freerdp_settings_set_string(settings, FreeRDP_CertificateFile, g_certPath);
        (void)freerdp_settings_set_string(settings, FreeRDP_PrivateKeyFile, g_keyPath);
        (void)freerdp_settings_set_string(settings, FreeRDP_NtlmSamFile, g_samFilePath);
    }
    else if (haveCert)
    {
        (void)freerdp_settings_set_bool(settings, FreeRDP_RdpSecurity, FALSE);
        (void)freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, TRUE);
        (void)freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, FALSE);
        (void)freerdp_settings_set_string(settings, FreeRDP_CertificateFile, g_certPath);
        (void)freerdp_settings_set_string(settings, FreeRDP_PrivateKeyFile, g_keyPath);
    }
    else
    {
        (void)freerdp_settings_set_bool(settings, FreeRDP_RdpSecurity, TRUE);
        (void)freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, FALSE);
        (void)freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, FALSE);
    }

    (void)freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth, g_desktopWidth);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight, g_desktopHeight);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, 32);
    (void)freerdp_settings_set_bool(settings, FreeRDP_RemoteFxCodec, FALSE);
    (void)freerdp_settings_set_bool(settings, FreeRDP_NSCodec, FALSE);
    (void)freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, FALSE);
}

/* One thread per connected peer — mirrors FreeRDP's own server samples
 * (e.g. server/Mac/mf_peer.c's per-client loop). */
static void* systemsgo_server_peer_thread(void* arg)
{
    freerdp_peer* client = (freerdp_peer*)arg;
    systemsgoServerPeerContext* spc = (systemsgoServerPeerContext*)client->context;

    client->Capabilities = systemsgo_server_peer_capabilities;
    client->PostConnect = systemsgo_server_peer_post_connect;
    client->Activate = systemsgo_server_peer_activate;
    client->Logon = systemsgo_server_peer_logon;

    systemsgo_server_configure_settings(client->context->settings);

    if (!client->Initialize(client))
    {
        LOGE("SystemsGo server: peer Initialize() failed for %s", spc->clientAddress);
        freerdp_peer_free(client);
        return NULL;
    }

    client->context->input->KeyboardEvent = systemsgo_server_input_keyboard;
    client->context->input->UnicodeKeyboardEvent = systemsgo_server_input_unicode;
    client->context->input->MouseEvent = systemsgo_server_input_mouse;
    client->context->input->ExtendedMouseEvent = systemsgo_server_input_extended_mouse;

    /* SHADOW-SERVER FEATURE: registered here (post-Initialize, same point
     * the input callbacks above are wired) rather than in
     * systemsgo_server_peer_accepted() — Initialize() is what actually starts
     * the negotiation that eventually calls Activate(), so this is the
     * earliest point a broadcast frame arriving concurrently on another
     * thread can safely be handed to this peer. */
    systemsgo_server_register_peer(client);

    while (g_running)
    {
        HANDLE handles[MAXIMUM_WAIT_OBJECTS];
        DWORD count = client->GetEventHandles(client, handles, MAXIMUM_WAIT_OBJECTS);
        if (count == 0)
        {
            LOGE("SystemsGo server: GetEventHandles failed for %s", spc->clientAddress);
            break;
        }

        DWORD status = WaitForMultipleObjects(count, handles, FALSE, 1000);
        if (status == WAIT_FAILED)
        {
            LOGE("SystemsGo server: WaitForMultipleObjects failed for %s", spc->clientAddress);
            break;
        }
        if (status == WAIT_TIMEOUT)
            continue;

        if (!client->CheckFileDescriptor(client))
            break; /* client disconnected or protocol error */
    }

    /* SHADOW-SERVER FEATURE: unregister BEFORE Disconnect/teardown below so
     * a broadcast racing on another thread can never be handed a peer
     * that's mid-teardown. */
    systemsgo_server_unregister_peer(client);

    JNIEnv* env;
    BOOL didAttach;
    systemsgo_server_attach_env(&env, spc, &didAttach);
    if (env && spc->onPeerDisconnectedMethod)
    {
        jstring jaddr = (*env)->NewStringUTF(env, spc->clientAddress);
        (*env)->CallVoidMethod(env, spc->bridgeObjGlobalRef, spc->onPeerDisconnectedMethod, jaddr);
        (*env)->DeleteLocalRef(env, jaddr);
    }
    if (didAttach) (*spc->jvm)->DetachCurrentThread(spc->jvm);

    LOGI("SystemsGo server: peer %s disconnected", spc->clientAddress);
    client->Disconnect(client);
    freerdp_peer_context_free(client);
    freerdp_peer_free(client);
    return NULL;
}

static BOOL systemsgo_server_peer_accepted(freerdp_listener* instance, freerdp_peer* client)
{
    (void)instance;
    client->ContextSize = sizeof(systemsgoServerPeerContext);
    client->ContextNew = systemsgo_server_peer_context_new;
    client->ContextFree = systemsgo_server_peer_context_free;

    if (!freerdp_peer_context_new(client))
    {
        LOGE("SystemsGo server: freerdp_peer_context_new failed");
        freerdp_peer_free(client);
        return FALSE;
    }

    systemsgoServerPeerContext* spc = (systemsgoServerPeerContext*)client->context;
    snprintf(spc->clientAddress, sizeof(spc->clientAddress), "%s", client->hostname ? client->hostname : "unknown");

    pthread_t tid;
    if (pthread_create(&tid, NULL, systemsgo_server_peer_thread, client) != 0)
    {
        LOGE("SystemsGo server: pthread_create failed for incoming peer");
        freerdp_peer_context_free(client);
        freerdp_peer_free(client);
        return FALSE;
    }
    pthread_detach(tid);
    return TRUE;
}

/* ── Accept loop (listener) thread ────────────────────────────────────── */

static void* systemsgo_server_accept_thread(void* arg)
{
    (void)arg;
    LOGI("SystemsGo server: accept loop starting");
    while (g_running)
    {
        HANDLE handles[MAXIMUM_WAIT_OBJECTS];
        DWORD count = g_listener->GetEventHandles(g_listener, handles, MAXIMUM_WAIT_OBJECTS);
        if (count == 0)
        {
            LOGE("SystemsGo server: listener GetEventHandles failed");
            break;
        }

        DWORD status = WaitForMultipleObjects(count, handles, FALSE, 1000);
        if (status == WAIT_FAILED)
        {
            LOGE("SystemsGo server: listener WaitForMultipleObjects failed");
            break;
        }
        if (status == WAIT_TIMEOUT)
            continue;

        if (!g_listener->CheckFileDescriptor(g_listener))
        {
            LOGE("SystemsGo server: listener CheckFileDescriptor failed");
            break;
        }
    }
    LOGI("SystemsGo server: accept loop exiting");
    return NULL;
}

/* ── JNI entry points (com.systemsgo.hex.rdp.native.AFreeRdpServerBridge) ── */

JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpServerBridge_nativeStart(
    JNIEnv* env, jobject thiz, jint port, jint width, jint height,
    jstring certPath, jstring keyPath)
{
    /* TLS-SERVER FEATURE: certPath/keyPath are real now — copy them into
     * module state so systemsgo_server_configure_settings() (called once per
     * accepted peer, later) can see them. Free any leftovers from a
     * previous start()/stop() cycle first. */
    free(g_certPath); g_certPath = NULL;
    free(g_keyPath);  g_keyPath  = NULL;
    if (certPath)
    {
        const char* c = (*env)->GetStringUTFChars(env, certPath, NULL);
        if (c) { g_certPath = strdup(c); (*env)->ReleaseStringUTFChars(env, certPath, c); }
    }
    if (keyPath)
    {
        const char* k = (*env)->GetStringUTFChars(env, keyPath, NULL);
        if (k) { g_keyPath = strdup(k); (*env)->ReleaseStringUTFChars(env, keyPath, k); }
    }

    if (g_running)
    {
        LOGW("SystemsGo server: nativeStart called while already running — ignoring (single-listener limit, see top-of-file SCOPE)");
        return JNI_FALSE;
    }

    winpr_InitializeSSL(WINPR_SSL_INIT_DEFAULT);

    g_desktopWidth = (UINT32)(width > 0 ? width : 1280);
    g_desktopHeight = (UINT32)(height > 0 ? height : 720);

    (*env)->GetJavaVM(env, &g_jvm);
    g_bridgeGlobalRef = (*env)->NewGlobalRef(env, thiz);

    g_listener = freerdp_listener_new();
    if (!g_listener)
    {
        LOGE("SystemsGo server: freerdp_listener_new failed");
        return JNI_FALSE;
    }
    g_listener->PeerAccepted = systemsgo_server_peer_accepted;

    /* NOT VERIFIED: Open() vs OpenLocal()/OpenFromSocket() — Open(instance,
     * bindAddress, port) with bindAddress=NULL (bind all interfaces) is the
     * long-standing signature used by every FreeRDP server sample; confirm
     * against the vendored listener.h if CI reports otherwise. */
    if (!g_listener->Open(g_listener, NULL, (UINT16)port))
    {
        LOGE("SystemsGo server: listener Open() failed on port %d", (int)port);
        freerdp_listener_free(g_listener);
        g_listener = NULL;
        return JNI_FALSE;
    }

    g_running = TRUE;
    if (pthread_create(&g_acceptThread, NULL, systemsgo_server_accept_thread, NULL) != 0)
    {
        LOGE("SystemsGo server: pthread_create (accept loop) failed");
        g_running = FALSE;
        g_listener->Close(g_listener);
        freerdp_listener_free(g_listener);
        g_listener = NULL;
        return JNI_FALSE;
    }

    LOGI("SystemsGo server: listening on port %d (%ux%u, %s)", (int)port,
         g_desktopWidth, g_desktopHeight,
         (g_samFilePath && g_samFilePath[0] && g_certPath && g_certPath[0] && g_keyPath && g_keyPath[0])
             ? "NLA Security"
         : (g_certPath && g_certPath[0] && g_keyPath && g_keyPath[0])
             ? "TLS Security" : "Standard RDP Security (no cert configured)");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpServerBridge_nativeSetExpectedCredentials(
    JNIEnv* env, jobject thiz, jstring username, jstring password)
{
    (void)thiz;
    free(g_expectedUsername); g_expectedUsername = NULL;
    free(g_expectedPassword); g_expectedPassword = NULL;
    if (username)
    {
        const char* u = (*env)->GetStringUTFChars(env, username, NULL);
        if (u) { g_expectedUsername = strdup(u); (*env)->ReleaseStringUTFChars(env, username, u); }
    }
    if (password)
    {
        const char* p = (*env)->GetStringUTFChars(env, password, NULL);
        if (p) { g_expectedPassword = strdup(p); (*env)->ReleaseStringUTFChars(env, password, p); }
    }
}

/* NLA-SERVER FEATURE: set (or clear, if samFilePath is null) the SAM file
 * systemsgo_server_configure_settings() points FreeRDP_NtlmSamFile at for the
 * NEXT accepted peer — see that function's doc for what this actually
 * turns on. Call before start() (same convention as
 * nativeSetExpectedCredentials); safe to call while already running, it
 * only affects peers accepted after this returns. */
JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpServerBridge_nativeSetSamFile(
    JNIEnv* env, jobject thiz, jstring samFilePath)
{
    (void)thiz;
    free(g_samFilePath); g_samFilePath = NULL;
    if (samFilePath)
    {
        const char* s = (*env)->GetStringUTFChars(env, samFilePath, NULL);
        if (s) { g_samFilePath = strdup(s); (*env)->ReleaseStringUTFChars(env, samFilePath, s); }
    }
}

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpServerBridge_nativeStop(JNIEnv* env, jobject thiz)
{
    (void)thiz;
    if (!g_running)
        return;

    g_running = FALSE;
    pthread_join(g_acceptThread, NULL);

    if (g_listener)
    {
        g_listener->Close(g_listener);
        freerdp_listener_free(g_listener);
        g_listener = NULL;
    }

    if (g_bridgeGlobalRef)
    {
        (*env)->DeleteGlobalRef(env, g_bridgeGlobalRef);
        g_bridgeGlobalRef = NULL;
    }
    g_jvm = NULL;

    /* SHADOW-SERVER FEATURE: clear peer registry (per-peer threads have
     * already unregistered themselves as they exit, but this covers any
     * still-shutting-down peer) and drop the cached frame so a later
     * nativeStart() doesn't hand a stale screenshot to a peer that
     * Activates before the next nativePushFrame() call. */
    pthread_mutex_lock(&g_peersMutex);
    memset(g_activePeers, 0, sizeof(g_activePeers));
    pthread_mutex_unlock(&g_peersMutex);

    pthread_mutex_lock(&g_frameMutex);
    free(g_latestFrame);
    g_latestFrame = NULL;
    g_latestFrameWidth = 0;
    g_latestFrameHeight = 0;
    pthread_mutex_unlock(&g_frameMutex);

    LOGI("SystemsGo server: stopped");
}

JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpServerBridge_nativeIsRunning(JNIEnv* env, jobject thiz)
{
    (void)env;
    (void)thiz;
    return g_running ? JNI_TRUE : JNI_FALSE;
}

/* SHADOW-SERVER FEATURE: called from Kotlin (AFreeRdpServerBridge.pushFrame)
 * once per captured screen frame — see ShadowScreenCaptureService.kt, which
 * is the only current caller (via MediaProjection + ImageReader). [frame]
 * is a flat BGRX32 buffer already sized width*height*4 bytes (Kotlin does
 * the RGBA->BGRX channel swap before calling this — see that file's
 * toBgrx() for why the swap happens there and not here: avoids a second JNI
 * round-trip per frame just to hand back a re-ordered copy).
 *
 * No-op (returns false) if the server isn't running — a capture tick can
 * legitimately race a stop() from the UI thread; this is the expected,
 * silent way that race resolves, not an error worth surfacing to Kotlin.
 *
 * SINGLE-PRODUCER ASSUMPTION: this function takes g_frameMutex only long
 * enough to copy the new bytes in, then broadcasts from a raw snapshot
 * pointer afterwards (see comment lower down) — that snapshot is only safe
 * because ShadowScreenCaptureService calls this from one capture
 * thread/coroutine at a time, never concurrently with itself. Two
 * genuinely concurrent nativePushFrame() calls could race a realloc()
 * against the snapshot read. If a second frame producer is ever added
 * (e.g. RDP Proxy), serialize its calls into this one first rather than
 * relying on this function to do it. */
JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_rdp_native_AFreeRdpServerBridge_nativePushFrame(
    JNIEnv* env, jobject thiz, jbyteArray frame, jint width, jint height)
{
    (void)thiz;
    if (!g_running || width <= 0 || height <= 0)
        return JNI_FALSE;

    jsize len = (*env)->GetArrayLength(env, frame);
    const size_t expected = (size_t)width * (size_t)height * 4;
    if ((size_t)len < expected)
    {
        LOGE("nativePushFrame: buffer too small (%d bytes, need %zu for %dx%d)", (int)len, expected, width, height);
        return JNI_FALSE;
    }

    pthread_mutex_lock(&g_frameMutex);
    if (g_latestFrameWidth != (UINT32)width || g_latestFrameHeight != (UINT32)height || g_latestFrame == NULL)
    {
        BYTE* resized = (BYTE*)realloc(g_latestFrame, expected);
        if (!resized)
        {
            pthread_mutex_unlock(&g_frameMutex);
            LOGE("nativePushFrame: OOM (re)allocating %zu-byte frame buffer", expected);
            return JNI_FALSE;
        }
        g_latestFrame = resized;
        g_latestFrameWidth = (UINT32)width;
        g_latestFrameHeight = (UINT32)height;
    }
    (*env)->GetByteArrayRegion(env, frame, 0, (jsize)expected, (jbyte*)g_latestFrame);
    /* Snapshot pointer/size under the lock, then broadcast outside it below
     * — holding g_frameMutex across the whole per-peer BitmapUpdate fan-out
     * (which itself takes g_peersMutex) would serialize every peer's send
     * behind this same lock for no benefit, and risks a lock-order
     * inversion against any future code that takes g_peersMutex first. */
    BYTE* snapshot = g_latestFrame;
    UINT32 snapWidth = g_latestFrameWidth;
    UINT32 snapHeight = g_latestFrameHeight;
    pthread_mutex_unlock(&g_frameMutex);

    systemsgo_server_broadcast_frame(snapshot, snapWidth, snapHeight);
    return JNI_TRUE;
}
