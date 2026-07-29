/*
 * systemsgo_mosh_jni.c — MOSH-PROTOCOL FEATURE, Part 3/N (real transport unblocked).
 *
 * UPDATE (this pass): the SSP (State Synchronization Protocol) transport —
 * sequencing/ack/retransmission, datagram fragmentation, and the
 * TransportInstruction/HostMessage/UserMessage protobuf schema — is now
 * implemented in pure Kotlin (see
 * com.systemsgo.hex.mosh.protocol.{MoshWireProtocol,MoshFragment,MoshTransport,
 * MoshSessionClient}), ported field-for-field from mobile-shell/mosh's own
 * wire format (cross-checked against the independent, wire-compatible
 * "unixshells/mosh-go" reference implementation, MIT-licensed — see
 * mosh/NOTES.md for the exact field numbers and byte layout this was
 * verified against). Keeping that logic in Kotlin — rather than porting it
 * into C — means it can be unit-tested directly (see
 * app/src/test/java/com/systemsgo/hex/mosh/) without a native toolchain,
 * the same way NETCONF/SNMP/AMT's protocol logic in this app already is.
 *
 * This file's only remaining job, therefore, is exactly what
 * systemsgo_mosh_crypto.c already implements: AES-128-OCB encrypt/decrypt of
 * one UDP payload at a time, keyed by the session key from the
 * `MOSH CONNECT` line. There is deliberately no UDP socket, no fragment
 * loop, and no protobuf handling in this file — all of that lives in
 * MoshTransport.kt/MoshSessionClient.kt now, which call these two
 * functions once per outgoing/incoming datagram.
 *
 * Session-lifetime state (the decoded AES key) still lives in a native
 * handle rather than being passed as a byte[] on every call, so the
 * decoded key bytes never have to cross the JNI boundary more than once
 * per session and get wiped (memset) exactly once, in nativeFree.
 */

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>

#include "systemsgo_mosh_crypto.h"

#define TAG "systemsgo_mosh_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    unsigned char aesKey[SYSTEMSGO_MOSH_KEY_LEN];
    volatile int keyValid;
} systemsgoMoshSession;

JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_mosh_native_MoshBridge_nativeIsAvailable(JNIEnv* env, jclass clazz)
{
    (void)env;
    (void)clazz;
    /* The crypto slice this .so provides (key decode + AES-128-OCB) is real
     * and locally tested (see mosh/NOTES.md), and it's all MoshTransport.kt
     * needs from native code now that the SSP logic itself lives in
     * Kotlin — so "available" here correctly means "usable", not a
     * placeholder as it did in the original Part 1/N scaffold. */
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_systemsgo_hex_mosh_native_MoshBridge_nativeInit(JNIEnv* env, jobject thiz)
{
    (void)env;
    (void)thiz;

    systemsgoMoshSession* session = (systemsgoMoshSession*)calloc(1, sizeof(systemsgoMoshSession));
    if (!session) {
        LOGE("nativeInit: calloc failed");
        return 0;
    }
    return (jlong)(intptr_t)session;
}

/*
 * Decodes and validates the 22-char base64 session key from the
 * `MOSH CONNECT <port> <key>` line. No host/port here anymore — the UDP
 * socket is owned entirely by MoshSessionClient.kt (plain java.net.DatagramSocket),
 * since nothing about opening a UDP socket needs native code on Android.
 */
JNIEXPORT jboolean JNICALL
Java_com_systemsgo_hex_mosh_native_MoshBridge_nativeSetKey(
    JNIEnv* env, jobject thiz, jlong handle, jstring jSessionKeyB64)
{
    (void)thiz;

    systemsgoMoshSession* session = (systemsgoMoshSession*)(intptr_t)handle;
    if (!session) {
        LOGE("nativeSetKey: null session handle");
        return JNI_FALSE;
    }
    if (!jSessionKeyB64) {
        LOGE("nativeSetKey: null session key");
        return JNI_FALSE;
    }

    const char* keyB64 = (*env)->GetStringUTFChars(env, jSessionKeyB64, NULL);
    int rc = keyB64 ? systemsgo_mosh_decode_key(keyB64, session->aesKey) : -1;
    if (keyB64) (*env)->ReleaseStringUTFChars(env, jSessionKeyB64, keyB64);

    session->keyValid = (rc == 0) ? 1 : 0;
    if (!session->keyValid) {
        LOGE("nativeSetKey: session key missing or malformed (expected 22-char base64)");
    }
    return session->keyValid ? JNI_TRUE : JNI_FALSE;
}

/*
 * Encrypts one SSP datagram's plaintext (already-fragmented, already
 * length-bounded by MoshTransport's caller — this function does no
 * chunking of its own). `jNonce` must be exactly SYSTEMSGO_MOSH_NONCE_LEN
 * (12) bytes — see systemsgo_mosh_crypto.h for how MoshTransport.kt must
 * build it (4 zero bytes + 8-byte big-endian direction+sequence).
 *
 * Returns ciphertext with the SYSTEMSGO_MOSH_TAG_LEN-byte OCB tag appended
 * (i.e. length == plaintext length + 16), matching the layout
 * MoshTransport.kt's Kotlin port of mosh-go's wire format expects
 * (ciphertext-then-tag, the same convention EVP_CIPHER_CTX_ctrl's
 * EVP_CTRL_AEAD_GET_TAG already produces separately — this function just
 * concatenates them for a single byte[] return across JNI). Returns null
 * on any failure.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_systemsgo_hex_mosh_native_MoshBridge_nativeEncrypt(
    JNIEnv* env, jobject thiz, jlong handle, jbyteArray jNonce, jbyteArray jPlaintext)
{
    (void)thiz;

    systemsgoMoshSession* session = (systemsgoMoshSession*)(intptr_t)handle;
    if (!session || !session->keyValid) {
        LOGE("nativeEncrypt: no session or key not set");
        return NULL;
    }
    if (!jNonce || (*env)->GetArrayLength(env, jNonce) != SYSTEMSGO_MOSH_NONCE_LEN) {
        LOGE("nativeEncrypt: nonce must be exactly %d bytes", SYSTEMSGO_MOSH_NONCE_LEN);
        return NULL;
    }

    jsize ptLen = jPlaintext ? (*env)->GetArrayLength(env, jPlaintext) : 0;
    unsigned char nonce[SYSTEMSGO_MOSH_NONCE_LEN];
    (*env)->GetByteArrayRegion(env, jNonce, 0, SYSTEMSGO_MOSH_NONCE_LEN, (jbyte*)nonce);

    unsigned char* pt = NULL;
    if (ptLen > 0) {
        pt = (unsigned char*)malloc((size_t)ptLen);
        if (!pt) {
            LOGE("nativeEncrypt: OOM allocating %d-byte plaintext buffer", (int)ptLen);
            return NULL;
        }
        (*env)->GetByteArrayRegion(env, jPlaintext, 0, ptLen, (jbyte*)pt);
    }

    unsigned char* ct = (unsigned char*)malloc((size_t)ptLen + SYSTEMSGO_MOSH_TAG_LEN);
    if (!ct) {
        free(pt);
        LOGE("nativeEncrypt: OOM allocating ciphertext+tag buffer");
        return NULL;
    }
    unsigned char tag[SYSTEMSGO_MOSH_TAG_LEN];

    int rc = systemsgo_mosh_ocb_encrypt(session->aesKey, nonce, pt, (int)ptLen, ct, tag);
    free(pt);

    if (rc < 0) {
        free(ct);
        LOGE("nativeEncrypt: OCB encrypt failed");
        return NULL;
    }
    memcpy(ct + rc, tag, SYSTEMSGO_MOSH_TAG_LEN);

    jbyteArray result = (*env)->NewByteArray(env, rc + SYSTEMSGO_MOSH_TAG_LEN);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, rc + SYSTEMSGO_MOSH_TAG_LEN, (jbyte*)ct);
    }
    free(ct);
    return result;
}

/*
 * Decrypts+authenticates one SSP datagram. `jCiphertextAndTag` is the
 * ciphertext with the 16-byte OCB tag appended (the same layout
 * [nativeEncrypt] produces) — MoshTransport.kt is responsible for having
 * already stripped the 8-byte cleartext direction+sequence header before
 * calling this. Returns the plaintext on success, or null if
 * authentication fails — callers MUST treat null as "drop this packet
 * silently", per SSP's threat model (see systemsgo_mosh_crypto.h), not
 * surface it as a connection error.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_systemsgo_hex_mosh_native_MoshBridge_nativeDecrypt(
    JNIEnv* env, jobject thiz, jlong handle, jbyteArray jNonce, jbyteArray jCiphertextAndTag)
{
    (void)thiz;

    systemsgoMoshSession* session = (systemsgoMoshSession*)(intptr_t)handle;
    if (!session || !session->keyValid) {
        return NULL;
    }
    if (!jNonce || (*env)->GetArrayLength(env, jNonce) != SYSTEMSGO_MOSH_NONCE_LEN) {
        LOGE("nativeDecrypt: nonce must be exactly %d bytes", SYSTEMSGO_MOSH_NONCE_LEN);
        return NULL;
    }

    jsize totalLen = jCiphertextAndTag ? (*env)->GetArrayLength(env, jCiphertextAndTag) : 0;
    if (totalLen < SYSTEMSGO_MOSH_TAG_LEN) {
        return NULL; /* too short to even contain a tag — malformed/truncated packet */
    }
    jsize ctLen = totalLen - SYSTEMSGO_MOSH_TAG_LEN;

    unsigned char nonce[SYSTEMSGO_MOSH_NONCE_LEN];
    (*env)->GetByteArrayRegion(env, jNonce, 0, SYSTEMSGO_MOSH_NONCE_LEN, (jbyte*)nonce);

    unsigned char* buf = (unsigned char*)malloc((size_t)totalLen);
    if (!buf) {
        LOGE("nativeDecrypt: OOM allocating %d-byte input buffer", (int)totalLen);
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, jCiphertextAndTag, 0, totalLen, (jbyte*)buf);

    unsigned char* pt = ctLen > 0 ? (unsigned char*)malloc((size_t)ctLen) : NULL;
    if (ctLen > 0 && !pt) {
        free(buf);
        LOGE("nativeDecrypt: OOM allocating %d-byte plaintext buffer", (int)ctLen);
        return NULL;
    }

    int rc = systemsgo_mosh_ocb_decrypt(session->aesKey, nonce, buf, (int)ctLen, buf + ctLen, pt);
    free(buf);

    if (rc < 0) {
        free(pt);
        return NULL; /* authentication failed — drop silently, do not log the plaintext attempt */
    }

    jbyteArray result = (*env)->NewByteArray(env, rc);
    if (result && rc > 0) {
        (*env)->SetByteArrayRegion(env, result, 0, rc, (jbyte*)pt);
    }
    free(pt);
    return result;
}

JNIEXPORT void JNICALL
Java_com_systemsgo_hex_mosh_native_MoshBridge_nativeFree(JNIEnv* env, jobject thiz, jlong handle)
{
    (void)env;
    (void)thiz;

    systemsgoMoshSession* session = (systemsgoMoshSession*)(intptr_t)handle;
    if (!session) return;

    memset(session->aesKey, 0, sizeof(session->aesKey));
    free(session);
}
