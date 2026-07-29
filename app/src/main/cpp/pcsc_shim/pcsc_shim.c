/* SMARTCARD-REDIRECT FEATURE
 *
 * Builds as "libpcsclite.so" — deliberately the same SONAME real PCSC-lite
 * ships as — so that FreeRDP's WinPR smartcard module (which locates PC/SC
 * at *runtime* via dlopen("libpcsclite.so")/dlsym(), not link-time linking;
 * that's why FreeRDP still runs on systems with no PCSC-lite installed at
 * all) finds and loads *this* library instead of a real one. There is no
 * real PCSC-lite involved: every exported SCard* function below is
 * implemented directly against com.systemsgo.hex.smartcard.PcscUsbBridge
 * (via JNI), which in turn drives UsbCcidReader over Android's USB Host
 * API. No pcscd, no PCSC-lite client/daemon split — this shim collapses
 * both into one small library plus a Kotlin USB driver.
 *
 * ── The "double dlopen" trick, and why it's needed ──────────────────────
 * Because FreeRDP finds this library via dlopen() from a native worker
 * thread (typically with no Java call stack), a naive JNIEnv->FindClass()
 * call made *inside* an exported SCard* function would very likely fail —
 * FindClass falls back to the boot classloader when there's no Java frame
 * on the calling thread's stack, and the boot classloader can't see app
 * classes like PcscUsbBridge. This is the standard, well-documented Android
 * NDK JNI pitfall (see e.g. the NDK's own guidance on caching classes in
 * JNI_OnLoad).
 *
 * The fix used here: com.systemsgo.hex.smartcard.PcscUsbBridge itself
 * explicitly System.loadLibrary()s this exact shared object
 * ("pcsclite_shim" → libpcsclite.so) from ordinary Kotlin code — a normal
 * JVM thread with full classloader context — before a smart-card-enabled
 * session connects (see PcscUsbBridge.ensureShimInitialized(), called from
 * RdpSessionActivity.onCreate). That triggers JNI_OnLoad() below on a
 * thread where FindClass/GetStaticMethodID work correctly, and the results
 * are cached as C statics (g_bridgeClass, g_mid*). When WinPR later calls
 * dlopen("libpcsclite.so"), Android's dynamic linker resolves it to the
 * *same already-loaded module* (dlopen of an already-resident library just
 * increments its reference count — it does not reload or re-initialize
 * it), so those cached JNI references remain valid for every SCard* call
 * afterward, regardless of which thread makes it. JNI_OnLoad only runs
 * once per process either way, so this is safe even if
 * ensureShimInitialized() is somehow skipped and WinPR's dlopen ends up
 * being the *first* load — FindClass would then be attempted from
 * whatever thread that dlopen happens on, which is the one scenario this
 * trick is specifically here to avoid, so ensureShimInitialized() being
 * called first (from Kotlin) is a real correctness requirement, not just
 * an optimization. See PcscUsbBridge's class doc for the Kotlin side.
 *
 * ── Scope ────────────────────────────────────────────────────────────────
 * Single context, single card handle, single reader — matches
 * UsbCcidReader's single-reader/single-slot design (see its doc comment).
 * SCardGetAttrib forwards to PcscUsbBridge.nativeGetAttrib and returns real
 * data for the attributes it actually has (ATR string, protocol bitmask,
 * and — for a USB reader — vendor name/IFD serial from the real USB
 * descriptor); anything else comes back SCARD_E_UNSUPPORTED_FEATURE rather
 * than a guess. SCardControl/SCardSetAttrib are still stubbed to
 * SCARD_E_UNSUPPORTED_FEATURE — those are vendor-escape/vendor-config byte
 * layouts with no public spec, so there's nothing honest to forward, unlike
 * GetAttrib's read-only PC/SC-spec-defined attribute IDs; FreeRDP's
 * smart-card client is written to treat that as "reader doesn't support
 * this", not a fatal error.
 *
 * ⚠️ STILL UNVERIFIED ON A REAL BUILD/DEVICE, but the design assumption this
 * whole file rests on is now confirmed against FreeRDP's actual WinPR
 * source (not just the public PC/SC spec): winpr/libwinpr/smartcard/
 * smartcard_pcsc.c really does `g_PCSCModule = LoadLibraryA("libpcsclite.so")`
 * — a runtime dlopen by that exact unversioned SONAME, not a link-time
 * dependency — so this shim being picked up in place of a real PCSC-lite is
 * sound, not a guess. (Historical note: PR #1778, "Smart Card Complete
 * Overhaul," is where WinPR moved off link-time pcsc-lite to this
 * dlopen-based design in the first place.)
 *
 * Four gaps flagged in earlier passes here are now fixed rather than just
 * documented:
 *   - Protocol reporting (SCardConnect/SCardReconnect/SCardStatus/
 *     SCardTransmit) no longer hardcodes SCARD_PROTOCOL_T1. It calls
 *     get_active_protocol(), which reflects UsbCcidReader's real ATR
 *     TD-chain parse (ISO/IEC 7816-3 §8.2.3) — see UsbCcidReader.
 *     parseAtrProtocol's doc comment. A T=0-only card now reports T0
 *     correctly instead of silently misreporting T1.
 *   - SCardGetStatusChange no longer returns an instant snapshot. It blocks
 *     up to dwTimeout via nativeWaitForStatusChange, which is backed by the
 *     CCID interrupt-IN endpoint (RDR_to_PC_NotifySlotChange) when the
 *     reader exposes one; readers without one still block out the timeout
 *     window (via a Kotlin-side sleep) rather than busy-spinning, they just
 *     can't detect a genuine mid-wait change without that endpoint.
 *   - SCardStatus's reader-name output (mszReaderNames/pcchReaderLen) now
 *     honors SCARD_AUTOALLOCATE the same way its own ATR output (and
 *     SCardListReaders) already did, instead of always memcpy-ing the
 *     synthetic reader name into *mszReaderNames as if it were a raw fixed
 *     buffer. WinPR resolves SCardFreeMemory from this shim, which flips on
 *     WinPR's g_SCardAutoAllocate — meaning WinPR passes SCARD_AUTOALLOCATE
 *     for *this* buffer too, not just the ATR one. When it does,
 *     mszReaderNames is really the address of a char* it expects us to
 *     malloc and fill in; the old code instead wrote string bytes directly
 *     over that pointer-sized slot, corrupting it. A too-small fixed buffer
 *     now correctly returns SCARD_E_INSUFFICIENT_BUFFER instead of silently
 *     truncating, matching the ATR branch's behavior.
 *   - SCardGetAttrib no longer blanket-stubs every attribute ID to
 *     SCARD_E_UNSUPPORTED_FEATURE regardless of what PcscUsbBridge actually
 *     knows. It now calls nativeGetAttrib and returns the real bytes for
 *     whatever it has (ATR string, protocol bitmask, USB vendor name/IFD
 *     serial), honoring SCARD_AUTOALLOCATE/insufficient-buffer the same way
 *     the ATR output above does — only genuinely unanswerable attribute IDs
 *     still come back unsupported.
 *
 * What's still unverified is execution against a real WinPR build and a
 * real CCID reader: exact buffer-size conventions WinPR passes into these
 * calls, which optional entry points it actually invokes for a given
 * server's smart-card logon flow, and SCARD_AUTOALLOCATE edge cases beyond
 * SCardListReaders/SCardStatus. If FreeRDP's smartcard channel logs errors
 * this file doesn't explain, check WinPR's channels/smartcard/client and
 * libwinpr/smartcard sources directly for what it actually expects. */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "pcsc_shim_types.h"

#define TAG "pcsc_shim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ── Cached JNI state, set once in JNI_OnLoad — see file header comment ── */
static JavaVM* g_jvm = NULL;
static jclass  g_bridgeClass = NULL; /* global ref to PcscUsbBridge */
static jmethodID g_midListReaders    = NULL; /* ()[Ljava/lang/String; */
static jmethodID g_midConnect        = NULL; /* (Ljava/lang/String;)Z */
static jmethodID g_midDisconnect     = NULL; /* ()V */
static jmethodID g_midGetAtr         = NULL; /* ()[B */
static jmethodID g_midIsPresent      = NULL; /* ()Z */
static jmethodID g_midTransmit       = NULL; /* ([B)[B */
static jmethodID g_midGetProtocol    = NULL; /* ()I  — real T0/T1 from ATR, see UsbCcidReader.parseAtrProtocol */
static jmethodID g_midWaitForChange  = NULL; /* (I)Z — blocking wait on the interrupt endpoint */
static jmethodID g_midGetAttrib      = NULL; /* (I)[B — real ATR/protocol/vendor-name/serial data, see PcscUsbBridge.nativeGetAttrib */

/* Single fake context/handle "value" — meaningful only as a non-zero
 * sentinel FreeRDP passes back to later calls; this shim doesn't track
 * multiple real contexts (see file header "Scope" section). */
#define SYSTEMSGO_FAKE_CONTEXT ((SCARDCONTEXT)0x48455844u) /* 'HEXD' */
#define SYSTEMSGO_FAKE_HANDLE  ((SCARDHANDLE)0x43415244u)  /* 'CARD' */

static JNIEnv* attach_current_thread(int* didAttach) {
    JNIEnv* env = NULL;
    *didAttach = 0;
    if (!g_jvm) return NULL;
    jint r = (*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6);
    if (r == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) {
            LOGE("AttachCurrentThread failed");
            return NULL;
        }
        *didAttach = 1;
    } else if (r != JNI_OK) {
        LOGE("GetEnv failed (r=%d)", r);
        return NULL;
    }
    return env;
}

static void detach_if_needed(int didAttach) {
    if (didAttach && g_jvm) (*g_jvm)->DetachCurrentThread(g_jvm);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;

    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv failed — cannot cache PcscUsbBridge, smart-card redirection will not work this session");
        return JNI_VERSION_1_6;
    }

    jclass local = (*env)->FindClass(env, "com/systemsgo/hex/smartcard/PcscUsbBridge");
    if (!local) {
        LOGE("JNI_OnLoad: FindClass(PcscUsbBridge) failed — smart-card redirection will not work this session");
        (*env)->ExceptionClear(env);
        return JNI_VERSION_1_6;
    }
    g_bridgeClass = (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);

    g_midListReaders = (*env)->GetStaticMethodID(env, g_bridgeClass, "nativeListReaders", "()[Ljava/lang/String;");
    g_midConnect     = (*env)->GetStaticMethodID(env, g_bridgeClass, "nativeConnect", "(Ljava/lang/String;)Z");
    g_midDisconnect  = (*env)->GetStaticMethodID(env, g_bridgeClass, "nativeDisconnect", "()V");
    g_midGetAtr      = (*env)->GetStaticMethodID(env, g_bridgeClass, "nativeGetAtr", "()[B");
    g_midIsPresent   = (*env)->GetStaticMethodID(env, g_bridgeClass, "nativeIsCardPresent", "()Z");
    g_midTransmit    = (*env)->GetStaticMethodID(env, g_bridgeClass, "nativeTransmit", "([B)[B");
    g_midGetProtocol   = (*env)->GetStaticMethodID(env, g_bridgeClass, "nativeGetProtocol", "()I");
    g_midWaitForChange = (*env)->GetStaticMethodID(env, g_bridgeClass, "nativeWaitForStatusChange", "(I)Z");
    g_midGetAttrib     = (*env)->GetStaticMethodID(env, g_bridgeClass, "nativeGetAttrib", "(I)[B");

    if (!g_midListReaders || !g_midConnect || !g_midDisconnect || !g_midGetAtr || !g_midIsPresent || !g_midTransmit ||
        !g_midGetProtocol || !g_midWaitForChange || !g_midGetAttrib) {
        LOGE("JNI_OnLoad: one or more PcscUsbBridge method IDs not found — check method signatures match");
        (*env)->ExceptionClear(env);
    }

    LOGI("libpcsclite.so shim initialized (backed by PcscUsbBridge/UsbCcidReader, not real PCSC-lite/pcscd)");
    return JNI_VERSION_1_6;
}

/* ── Helpers ─────────────────────────────────────────────────────────── */

static int bridge_ready(void) {
    return g_jvm != NULL && g_bridgeClass != NULL;
}

/* Real T0/T1 protocol, parsed from the card's actual ATR by
 * UsbCcidReader.parseAtrProtocol (see its doc comment for the ISO/IEC
 * 7816-3 TD-chain algorithm) — replaces the previous hardcoded
 * SCARD_PROTOCOL_T1 assumption everywhere a protocol value is reported. */
static DWORD get_active_protocol(void) {
    if (!bridge_ready() || !g_midGetProtocol) return SCARD_PROTOCOL_T1; /* no bridge — keep old default */
    int didAttach = 0;
    JNIEnv* env = attach_current_thread(&didAttach);
    if (!env) return SCARD_PROTOCOL_T1;
    jint proto = (*env)->CallStaticIntMethod(env, g_bridgeClass, g_midGetProtocol);
    detach_if_needed(didAttach);
    return (proto == 1) ? SCARD_PROTOCOL_T1 : SCARD_PROTOCOL_T0;
}

/* ── Exported WinSCard API ───────────────────────────────────────────── */

JNIEXPORT LONG SCardEstablishContext(DWORD dwScope, LPCVOID pvReserved1, LPCVOID pvReserved2, LPSCARDCONTEXT phContext) {
    (void)dwScope; (void)pvReserved1; (void)pvReserved2;
    if (!phContext) return SCARD_E_INVALID_PARAMETER;
    *phContext = SYSTEMSGO_FAKE_CONTEXT;
    return SCARD_S_SUCCESS;
}

JNIEXPORT LONG SCardReleaseContext(SCARDCONTEXT hContext) {
    (void)hContext;
    return SCARD_S_SUCCESS;
}

JNIEXPORT LONG SCardIsValidContext(SCARDCONTEXT hContext) {
    return (hContext == SYSTEMSGO_FAKE_CONTEXT) ? SCARD_S_SUCCESS : SCARD_E_INVALID_HANDLE;
}

JNIEXPORT LONG SCardCancel(SCARDCONTEXT hContext) {
    (void)hContext;
    return SCARD_S_SUCCESS; /* nothing async is outstanding in this shim to cancel */
}

/* Fetches the reader name list from PcscUsbBridge; returns a
 * heap-allocated, multi-string-terminated (double-NUL-terminated,
 * PC/SC's "multi-string" convention) buffer via *outBuf/*outLen, or
 * SCARD_E_NO_READERS_AVAILABLE if none. Caller (SCardListReaders) copies
 * or hands off ownership depending on autoallocate mode. */
static LONG fetch_reader_multistring(char** outBuf, DWORD* outLen) {
    if (!bridge_ready() || !g_midListReaders) return SCARD_E_NO_READERS_AVAILABLE;

    int didAttach = 0;
    JNIEnv* env = attach_current_thread(&didAttach);
    if (!env) return SCARD_E_NO_READERS_AVAILABLE;

    jobjectArray names = (jobjectArray)(*env)->CallStaticObjectMethod(env, g_bridgeClass, g_midListReaders);
    jsize count = names ? (*env)->GetArrayLength(env, names) : 0;
    if (count == 0) {
        if (names) (*env)->DeleteLocalRef(env, names);
        detach_if_needed(didAttach);
        return SCARD_E_NO_READERS_AVAILABLE;
    }

    /* Total size: each name's UTF-8 bytes + 1 NUL, plus one final NUL to
     * terminate the multi-string (PC/SC's mszReaders convention: "A\0B\0\0"). */
    size_t total = 1;
    char* names_utf8[64];
    jsize use_count = count > 64 ? 64 : count;
    for (jsize i = 0; i < use_count; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, names, i);
        const char* utf = (*env)->GetStringUTFChars(env, s, NULL);
        names_utf8[i] = strdup(utf ? utf : "");
        (*env)->ReleaseStringUTFChars(env, s, utf);
        (*env)->DeleteLocalRef(env, s);
        total += strlen(names_utf8[i]) + 1;
    }
    (*env)->DeleteLocalRef(env, names);
    detach_if_needed(didAttach);

    char* buf = (char*)malloc(total);
    if (!buf) {
        for (jsize i = 0; i < use_count; i++) free(names_utf8[i]);
        return SCARD_E_NO_MEMORY;
    }
    size_t off = 0;
    for (jsize i = 0; i < use_count; i++) {
        size_t len = strlen(names_utf8[i]) + 1;
        memcpy(buf + off, names_utf8[i], len);
        off += len;
        free(names_utf8[i]);
    }
    buf[off] = '\0'; /* final terminator */

    *outBuf = buf;
    *outLen = (DWORD)total;
    return SCARD_S_SUCCESS;
}

JNIEXPORT LONG SCardListReaders(SCARDCONTEXT hContext, LPCSTR mszGroups, LPSTR mszReaders, LPDWORD pcchReaders) {
    (void)hContext; (void)mszGroups;
    if (!pcchReaders) return SCARD_E_INVALID_PARAMETER;

    char* buf = NULL;
    DWORD len = 0;
    LONG rc = fetch_reader_multistring(&buf, &len);
    if (rc != SCARD_S_SUCCESS) {
        *pcchReaders = 0;
        return rc;
    }

    if (*pcchReaders == SYSTEMSGO_SCARD_AUTOALLOCATE) {
        /* pcsc-lite autoallocate convention: caller passed the address of a
         * char* (via mszReaders) expecting us to malloc and hand back the
         * pointer, to be freed later with SCardFreeMemory. */
        *(char**)mszReaders = buf;
        *pcchReaders = len;
        return SCARD_S_SUCCESS;
    }

    if (!mszReaders) {
        *pcchReaders = len; /* size query */
        free(buf);
        return SCARD_S_SUCCESS;
    }
    if (*pcchReaders < len) {
        *pcchReaders = len;
        free(buf);
        return SCARD_E_INSUFFICIENT_BUFFER;
    }
    memcpy(mszReaders, buf, len);
    *pcchReaders = len;
    free(buf);
    return SCARD_S_SUCCESS;
}

JNIEXPORT LONG SCardListReaderGroups(SCARDCONTEXT hContext, LPSTR mszGroups, LPDWORD pcchGroups) {
    (void)hContext;
    static const char kGroup[] = "SCard$AllReaders\0";
    DWORD len = (DWORD)sizeof(kGroup);
    if (!pcchGroups) return SCARD_E_INVALID_PARAMETER;

    if (*pcchGroups == SYSTEMSGO_SCARD_AUTOALLOCATE) {
        char* buf = (char*)malloc(len);
        if (!buf) return SCARD_E_NO_MEMORY;
        memcpy(buf, kGroup, len);
        *(char**)mszGroups = buf;
        *pcchGroups = len;
        return SCARD_S_SUCCESS;
    }
    if (!mszGroups) { *pcchGroups = len; return SCARD_S_SUCCESS; }
    if (*pcchGroups < len) { *pcchGroups = len; return SCARD_E_INSUFFICIENT_BUFFER; }
    memcpy(mszGroups, kGroup, len);
    *pcchGroups = len;
    return SCARD_S_SUCCESS;
}

JNIEXPORT LONG SCardFreeMemory(SCARDCONTEXT hContext, LPCVOID pvMem) {
    (void)hContext;
    free((void*)pvMem);
    return SCARD_S_SUCCESS;
}

JNIEXPORT LONG SCardConnect(SCARDCONTEXT hContext, LPCSTR szReader, DWORD dwShareMode, DWORD dwPreferredProtocols,
                            LPSCARDHANDLE phCard, LPDWORD pdwActiveProtocol) {
    (void)hContext; (void)dwShareMode; (void)dwPreferredProtocols;
    if (!phCard || !pdwActiveProtocol) return SCARD_E_INVALID_PARAMETER;
    if (!bridge_ready() || !g_midConnect) return SCARD_E_READER_UNAVAILABLE;

    int didAttach = 0;
    JNIEnv* env = attach_current_thread(&didAttach);
    if (!env) return SCARD_E_READER_UNAVAILABLE;

    jstring jReader = (*env)->NewStringUTF(env, szReader ? szReader : "");
    jboolean ok = (*env)->CallStaticBooleanMethod(env, g_bridgeClass, g_midConnect, jReader);
    (*env)->DeleteLocalRef(env, jReader);
    detach_if_needed(didAttach);

    if (!ok) return SCARD_E_NO_SMARTCARD;

    *phCard = SYSTEMSGO_FAKE_HANDLE;
    /* FIXED (was hardcoded T1): now reports whatever UsbCcidReader actually
     * parsed out of the card's real ATR TD-chain in powerOn() — see
     * get_active_protocol()'s doc comment. */
    *pdwActiveProtocol = get_active_protocol();
    return SCARD_S_SUCCESS;
}

JNIEXPORT LONG SCardReconnect(SCARDHANDLE hCard, DWORD dwShareMode, DWORD dwPreferredProtocols,
                               DWORD dwInitialization, LPDWORD pdwActiveProtocol) {
    (void)hCard; (void)dwShareMode; (void)dwPreferredProtocols; (void)dwInitialization;
    if (!pdwActiveProtocol) return SCARD_E_INVALID_PARAMETER;
    *pdwActiveProtocol = get_active_protocol();
    return SCARD_S_SUCCESS;
}

JNIEXPORT LONG SCardDisconnect(SCARDHANDLE hCard, DWORD dwDisposition) {
    (void)hCard;
    if (dwDisposition == SCARD_UNPOWER_CARD || dwDisposition == SCARD_RESET_CARD) {
        if (bridge_ready() && g_midDisconnect) {
            int didAttach = 0;
            JNIEnv* env = attach_current_thread(&didAttach);
            if (env) {
                (*env)->CallStaticVoidMethod(env, g_bridgeClass, g_midDisconnect);
                detach_if_needed(didAttach);
            }
        }
    }
    return SCARD_S_SUCCESS;
}

JNIEXPORT LONG SCardBeginTransaction(SCARDHANDLE hCard) {
    (void)hCard;
    return SCARD_S_SUCCESS; /* single-client model — nothing else can contend for the reader */
}

JNIEXPORT LONG SCardEndTransaction(SCARDHANDLE hCard, DWORD dwDisposition) {
    (void)hCard; (void)dwDisposition;
    return SCARD_S_SUCCESS;
}

JNIEXPORT LONG SCardStatus(SCARDHANDLE hCard, LPSTR mszReaderNames, LPDWORD pcchReaderLen,
                           LPDWORD pdwState, LPDWORD pdwProtocol, LPBYTE pbAtr, LPDWORD pcbAtrLen) {
    (void)hCard;
    if (!bridge_ready() || !g_midGetAtr || !g_midIsPresent) return SCARD_E_READER_UNAVAILABLE;

    int didAttach = 0;
    JNIEnv* env = attach_current_thread(&didAttach);
    if (!env) return SCARD_E_READER_UNAVAILABLE;

    jboolean present = (*env)->CallStaticBooleanMethod(env, g_bridgeClass, g_midIsPresent);
    jbyteArray jAtr = (jbyteArray)(*env)->CallStaticObjectMethod(env, g_bridgeClass, g_midGetAtr);
    jsize atrLen = jAtr ? (*env)->GetArrayLength(env, jAtr) : 0;

    if (pdwState) *pdwState = present ? SCARD_POWERED : SCARD_ABSENT;
    if (pdwProtocol) *pdwProtocol = get_active_protocol();

    if (pbAtr && pcbAtrLen) {
        DWORD cap = *pcbAtrLen;
        if (cap == SYSTEMSGO_SCARD_AUTOALLOCATE) {
            unsigned char* buf = (unsigned char*)malloc(atrLen > 0 ? (size_t)atrLen : 1);
            if (buf && atrLen > 0) (*env)->GetByteArrayRegion(env, jAtr, 0, atrLen, (jbyte*)buf);
            *(unsigned char**)pbAtr = buf;
            *pcbAtrLen = (DWORD)atrLen;
        } else if ((DWORD)atrLen <= cap) {
            if (atrLen > 0) (*env)->GetByteArrayRegion(env, jAtr, 0, atrLen, (jbyte*)pbAtr);
            *pcbAtrLen = (DWORD)atrLen;
        } else {
            *pcbAtrLen = (DWORD)atrLen;
            if (jAtr) (*env)->DeleteLocalRef(env, jAtr);
            detach_if_needed(didAttach);
            return SCARD_E_INSUFFICIENT_BUFFER;
        }
    }

    if (pcchReaderLen) {
        static const char kName[] = "Android Smart Card 00 00";
        DWORD needed = (DWORD)sizeof(kName);

        /* QUERY-LENGTH-ONLY FIX: the standard PC/SC two-call idiom lets a
         * caller pass mszReaderNames==NULL (with *pcchReaderLen typically 0,
         * though we don't require that) purely to learn how big a buffer to
         * allocate, then call again with a real buffer of that size. The
         * previous version of this branch was gated on
         * `mszReaderNames && pcchReaderLen && *pcchReaderLen > 0`, so a
         * NULL mszReaderNames skipped the block entirely and *pcchReaderLen
         * was left untouched — silently breaking that idiom for any caller
         * that uses it (WinPR's own SCardStatus wrapper supports exactly
         * this pattern). Now the needed length is always reported through
         * *pcchReaderLen whenever the caller asked for it (pcchReaderLen
         * non-NULL), independent of whether mszReaderNames was supplied. */
        if (!mszReaderNames) {
            *pcchReaderLen = needed;
        } else if (*pcchReaderLen == SYSTEMSGO_SCARD_AUTOALLOCATE) {
            /* Same convention as SCardListReaders/the ATR branch above:
             * mszReaderNames is really the address of a char* that WinPR
             * expects us to malloc and fill in, later freed via
             * SCardFreeMemory. Treating it as a raw fixed buffer here (as
             * this branch used to) would memcpy a string on top of that
             * pointer-sized slot instead of writing a pointer into it —
             * corrupting whatever WinPR does with it next. */
            char* buf = (char*)malloc(needed);
            if (!buf) {
                if (jAtr) (*env)->DeleteLocalRef(env, jAtr);
                detach_if_needed(didAttach);
                return SCARD_E_NO_MEMORY;
            }
            memcpy(buf, kName, needed);
            *(char**)mszReaderNames = buf;
            *pcchReaderLen = needed;
        } else if (needed <= *pcchReaderLen) {
            memcpy(mszReaderNames, kName, needed);
            *pcchReaderLen = needed;
        } else {
            *pcchReaderLen = needed;
            if (jAtr) (*env)->DeleteLocalRef(env, jAtr);
            detach_if_needed(didAttach);
            return SCARD_E_INSUFFICIENT_BUFFER;
        }
    }

    if (jAtr) (*env)->DeleteLocalRef(env, jAtr);
    detach_if_needed(didAttach);
    return present ? SCARD_S_SUCCESS : SCARD_W_UNPOWERED_CARD;
}

/* Each SCardGetStatusChange wait is broken into chunks of at most this many
 * ms so an INFINITE (0xFFFFFFFF) dwTimeout never has to be represented as a
 * single (overflowing/negative) jint, and so the loop below can periodically
 * re-check that the bridge is still alive while waiting. */
#define SYSTEMSGO_STATUS_WAIT_CHUNK_MS 5000

JNIEXPORT LONG SCardGetStatusChange(SCARDCONTEXT hContext, DWORD dwTimeout, SCARD_READERSTATE_A* rgReaderStates, DWORD cReaders) {
    (void)hContext;
    if (!rgReaderStates || cReaders == 0) return SCARD_S_SUCCESS;
    if (!bridge_ready() || !g_midIsPresent || !g_midWaitForChange) return SCARD_E_READER_UNAVAILABLE;

    int didAttach = 0;
    JNIEnv* env = attach_current_thread(&didAttach);
    if (!env) return SCARD_E_READER_UNAVAILABLE;

    /* FIXED (was an instant snapshot-and-return): now actually blocks up to
     * dwTimeout for a real change, backed by UsbCcidReader's interrupt-IN
     * listener via PcscUsbBridge.nativeWaitForStatusChange — see that
     * method's doc for how it degrades (still blocks, just can't detect a
     * genuine mid-wait change) on readers with no interrupt endpoint. */
    if (dwTimeout == 0xFFFFFFFFu) {
        /* INFINITE: keep waiting in bounded chunks until a change occurs or
         * the bridge/shim goes away (e.g. session teardown). */
        for (;;) {
            jboolean changed = (*env)->CallStaticBooleanMethod(
                env, g_bridgeClass, g_midWaitForChange, (jint)SYSTEMSGO_STATUS_WAIT_CHUNK_MS);
            if (changed || !bridge_ready()) break;
        }
    } else {
        DWORD remaining = dwTimeout;
        while (remaining > 0) {
            jint chunk = (jint)((remaining > SYSTEMSGO_STATUS_WAIT_CHUNK_MS) ? SYSTEMSGO_STATUS_WAIT_CHUNK_MS : remaining);
            jboolean changed = (*env)->CallStaticBooleanMethod(env, g_bridgeClass, g_midWaitForChange, chunk);
            if (changed) break;
            if ((DWORD)chunk >= remaining) break;
            remaining -= (DWORD)chunk;
        }
    }

    jboolean present = (*env)->CallStaticBooleanMethod(env, g_bridgeClass, g_midIsPresent);
    detach_if_needed(didAttach);

    for (DWORD i = 0; i < cReaders; i++) {
        DWORD newState = present ? (SCARD_STATE_PRESENT) : (SCARD_STATE_EMPTY);
        if (rgReaderStates[i].dwCurrentState != SCARD_STATE_UNAWARE &&
            (rgReaderStates[i].dwCurrentState & (SCARD_STATE_PRESENT | SCARD_STATE_EMPTY)) != newState) {
            newState |= SCARD_STATE_CHANGED;
        }
        rgReaderStates[i].dwEventState = newState;
    }
    return SCARD_S_SUCCESS;
}

JNIEXPORT LONG SCardTransmit(SCARDHANDLE hCard, const SCARD_IO_REQUEST* pioSendPci, LPCBYTE pbSendBuffer, DWORD cbSendLength,
                             SCARD_IO_REQUEST* pioRecvPci, LPBYTE pbRecvBuffer, LPDWORD pcbRecvLength) {
    (void)hCard; (void)pioSendPci;
    if (pioRecvPci) pioRecvPci->dwProtocol = get_active_protocol();
    if (!pbSendBuffer || !pbRecvBuffer || !pcbRecvLength) return SCARD_E_INVALID_PARAMETER;
    if (!bridge_ready() || !g_midTransmit) return SCARD_E_READER_UNAVAILABLE;

    int didAttach = 0;
    JNIEnv* env = attach_current_thread(&didAttach);
    if (!env) return SCARD_E_READER_UNAVAILABLE;

    jbyteArray jApdu = (*env)->NewByteArray(env, (jsize)cbSendLength);
    (*env)->SetByteArrayRegion(env, jApdu, 0, (jsize)cbSendLength, (const jbyte*)pbSendBuffer);
    jbyteArray jResp = (jbyteArray)(*env)->CallStaticObjectMethod(env, g_bridgeClass, g_midTransmit, jApdu);
    (*env)->DeleteLocalRef(env, jApdu);

    if (!jResp) {
        detach_if_needed(didAttach);
        *pcbRecvLength = 0;
        return SCARD_E_NOT_TRANSACTED;
    }

    jsize respLen = (*env)->GetArrayLength(env, jResp);
    if ((DWORD)respLen > *pcbRecvLength) {
        *pcbRecvLength = (DWORD)respLen;
        (*env)->DeleteLocalRef(env, jResp);
        detach_if_needed(didAttach);
        return SCARD_E_INSUFFICIENT_BUFFER;
    }
    (*env)->GetByteArrayRegion(env, jResp, 0, respLen, (jbyte*)pbRecvBuffer);
    *pcbRecvLength = (DWORD)respLen;
    (*env)->DeleteLocalRef(env, jResp);
    detach_if_needed(didAttach);
    return SCARD_S_SUCCESS;
}

/* PCSC-GETATTRIB FEATURE: forwards to PcscUsbBridge.nativeGetAttrib, which
 * answers only attributes it has real data for (ATR string, protocol
 * bitmask from any reader; vendor name/IFD serial from UsbCcidReader's
 * actual USB descriptor) — see that method's doc comment. A null return
 * means "no real data for this attribute", mapped here to
 * SCARD_E_UNSUPPORTED_FEATURE, never a fabricated value. Buffer handling
 * mirrors SCardStatus's ATR branch above: SCARD_AUTOALLOCATE means
 * *pbAttr is really the address of a pointer WinPR expects us to malloc
 * and fill in (later freed via SCardFreeMemory); otherwise pbAttr is a
 * caller-owned fixed buffer of capacity *pcbAttrLen. */
JNIEXPORT LONG SCardGetAttrib(SCARDHANDLE hCard, DWORD dwAttrId, LPBYTE pbAttr, LPDWORD pcbAttrLen) {
    (void)hCard;
    if (!pcbAttrLen) return SCARD_E_INVALID_PARAMETER;
    if (!bridge_ready() || !g_midGetAttrib) {
        *pcbAttrLen = 0;
        return SCARD_E_UNSUPPORTED_FEATURE;
    }

    int didAttach = 0;
    JNIEnv* env = attach_current_thread(&didAttach);
    if (!env) {
        *pcbAttrLen = 0;
        return SCARD_E_UNSUPPORTED_FEATURE;
    }

    jbyteArray jAttr = (jbyteArray)(*env)->CallStaticObjectMethod(env, g_bridgeClass, g_midGetAttrib, (jint)dwAttrId);
    if (!jAttr) {
        detach_if_needed(didAttach);
        *pcbAttrLen = 0;
        return SCARD_E_UNSUPPORTED_FEATURE;
    }

    jsize attrLen = (*env)->GetArrayLength(env, jAttr);
    DWORD cap = *pcbAttrLen;

    if (!pbAttr) {
        /* Query-length-only idiom, same as SCardStatus's reader-name branch. */
        *pcbAttrLen = (DWORD)attrLen;
    } else if (cap == SYSTEMSGO_SCARD_AUTOALLOCATE) {
        unsigned char* buf = (unsigned char*)malloc(attrLen > 0 ? (size_t)attrLen : 1);
        if (!buf) {
            (*env)->DeleteLocalRef(env, jAttr);
            detach_if_needed(didAttach);
            *pcbAttrLen = 0;
            return SCARD_E_NO_MEMORY;
        }
        if (attrLen > 0) (*env)->GetByteArrayRegion(env, jAttr, 0, attrLen, (jbyte*)buf);
        *(unsigned char**)pbAttr = buf;
        *pcbAttrLen = (DWORD)attrLen;
    } else if ((DWORD)attrLen <= cap) {
        if (attrLen > 0) (*env)->GetByteArrayRegion(env, jAttr, 0, attrLen, (jbyte*)pbAttr);
        *pcbAttrLen = (DWORD)attrLen;
    } else {
        *pcbAttrLen = (DWORD)attrLen;
        (*env)->DeleteLocalRef(env, jAttr);
        detach_if_needed(didAttach);
        return SCARD_E_INSUFFICIENT_BUFFER;
    }

    (*env)->DeleteLocalRef(env, jAttr);
    detach_if_needed(didAttach);
    return SCARD_S_SUCCESS;
}

/* SCardControl (CCID escape/vendor-IOCTL passthrough) — deliberately NOT
 * implemented, same reasoning as PcscCardReader.control's doc comment:
 * the command/response byte layout is vendor-specific with no public
 * spec, so there is nothing honest to forward here. Always unsupported. */
JNIEXPORT LONG SCardControl(SCARDHANDLE hCard, DWORD dwControlCode, LPCVOID pbSendBuffer, DWORD cbSendLength,
                            LPVOID pbRecvBuffer, DWORD cbRecvLength, LPDWORD lpBytesReturned) {
    (void)hCard; (void)dwControlCode; (void)pbSendBuffer; (void)cbSendLength; (void)pbRecvBuffer; (void)cbRecvLength;
    if (lpBytesReturned) *lpBytesReturned = 0;
    return SCARD_E_UNSUPPORTED_FEATURE; /* no CCID escape-command support — see file header "Scope" */
}

/* SCardSetAttrib — deliberately NOT implemented, same reasoning as
 * PcscCardReader.setAttrib's doc comment: every writable PC/SC attribute
 * is reader/vendor-specific with no public spec for which bytes a given
 * IFD expects. Silently returning success would tell the RDP server a
 * setting took effect when nothing happened. Always unsupported. */
JNIEXPORT LONG SCardSetAttrib(SCARDHANDLE hCard, DWORD dwAttrId, LPCBYTE pbAttr, DWORD cbAttrLen) {
    (void)hCard; (void)dwAttrId; (void)pbAttr; (void)cbAttrLen;
    return SCARD_E_UNSUPPORTED_FEATURE;
}
