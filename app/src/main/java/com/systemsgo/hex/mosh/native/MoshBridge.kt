package com.systemsgo.hex.mosh.native

import android.util.Log

/**
 * MOSH-PROTOCOL FEATURE: native AES-128-OCB crypto wrapper.
 *
 * UPDATE (this pass): everything about Mosh's SSP (State Synchronization
 * Protocol) that *isn't* the raw crypto primitive -- sequencing/ack/
 * retransmission timing, datagram fragmentation, and the
 * TransportInstruction/HostMessage/UserMessage protobuf schema -- is now
 * implemented in pure Kotlin: see
 * [com.systemsgo.hex.mosh.protocol.MoshTransport] and
 * [com.systemsgo.hex.mosh.protocol.MoshSessionClient]. Those field numbers
 * and the outer datagram's byte layout were ported from mobile-shell/mosh's
 * own documented wire format, cross-checked against the independent,
 * wire-compatible "unixshells/mosh-go" (MIT-licensed) reference
 * implementation -- see mosh/NOTES.md for exactly what was verified and
 * against what.
 *
 * This class is now deliberately narrow: it owns one native handle holding
 * the decoded 16-byte AES key for the session, and exposes exactly two
 * operations -- encrypt one datagram's plaintext, decrypt+authenticate one
 * datagram's ciphertext -- both implemented in `systemsgo_mosh_crypto.c` via
 * OpenSSL's `EVP_aes_128_ocb`, the same module that was already locally
 * round-trip- and tamper-tested before this pass (see mosh/NOTES.md).
 * There is no socket, no fragment loop, and no message parsing in this
 * class or its native side anymore -- [com.systemsgo.hex.mosh.protocol.MoshTransport]
 * calls [encrypt]/[decrypt] once per outgoing/incoming datagram and does
 * everything else itself, in Kotlin, where it can be unit-tested without a
 * native toolchain (see app/src/test/java/com/systemsgo/hex/mosh/).
 */
/**
 * Minimal AEAD surface [MoshTransport] needs — extracted as an interface
 * (rather than depending on the concrete [MoshBridge] class directly) so
 * the SSP sequencing/ack/retransmission logic in [MoshTransport] can be
 * unit-tested on a plain JVM with a fake implementation, without ever
 * touching the native `systemsgo_mosh_jni` library (which plain `./gradlew
 * test` can't load — it's only present in an actual Android runtime). See
 * app/src/test/java/com/systemsgo/hex/mosh/protocol/MoshTransportTest.kt's
 * `FakeMoshCrypto` for the test double this makes possible.
 */
interface MoshCrypto {
    fun encrypt(nonce: ByteArray, plaintext: ByteArray): ByteArray?
    fun decrypt(nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray?
}

class MoshBridge : MoshCrypto {

    companion object {
        private const val TAG = "MoshBridge"
        const val NONCE_LEN = 12
        const val TAG_LEN = 16
        const val KEY_B64_LEN = 22

        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("systemsgo_mosh_jni")
                nativeIsAvailable()
            } catch (e: UnsatisfiedLinkError) {
                Log.i(TAG, "Native Mosh crypto library not present in this build -- Mosh " +
                    "support is unavailable. See app/src/main/cpp/CMakeLists.txt's " +
                    "MOSH-PROTOCOL FEATURE section.")
                false
            } catch (e: Throwable) {
                Log.w(TAG, "Unexpected error probing native Mosh crypto library", e)
                false
            }
        }

        private external fun nativeIsAvailable(): Boolean
    }

    private var handle: Long = 0

    /** Allocates the native session handle. Returns false if allocation failed. */
    fun init(): Boolean {
        handle = nativeInit()
        return handle != 0L
    }

    /**
     * Decodes and validates the 22-char unpadded-base64 session key from a
     * `MOSH CONNECT <port> <key>` line (see [com.systemsgo.hex.mosh.protocol.MoshSessionManager]).
     * Must succeed before [encrypt]/[decrypt] will do anything but fail.
     */
    fun setKey(sessionKeyB64: String): Boolean {
        if (handle == 0L) return false
        return nativeSetKey(handle, sessionKeyB64)
    }

    /**
     * AES-128-OCB encrypts [plaintext] with the given 12-byte [nonce] (see
     * [com.systemsgo.hex.mosh.protocol.MoshTransport] for how the nonce is
     * built from the direction bit + sequence number). Returns
     * ciphertext with the 16-byte OCB tag appended, or null on failure
     * (uninitialized key, native library missing, or an unexpected OpenSSL
     * failure -- never expected in normal operation).
     */
    override fun encrypt(nonce: ByteArray, plaintext: ByteArray): ByteArray? {
        if (handle == 0L || nonce.size != NONCE_LEN) return null
        return nativeEncrypt(handle, nonce, plaintext)
    }

    /**
     * AES-128-OCB decrypts+authenticates [ciphertextAndTag] (ciphertext
     * with the 16-byte tag appended -- the same layout [encrypt] returns)
     * with the given 12-byte [nonce]. Returns the plaintext on success, or
     * null if authentication fails. Per SSP's threat model, a null return
     * MUST be treated as "silently drop this packet", never surfaced as a
     * connection error -- a single corrupted/replayed/spoofed UDP datagram
     * is expected and normal on an unreliable transport.
     */
    override fun decrypt(nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray? {
        if (handle == 0L || nonce.size != NONCE_LEN) return null
        return nativeDecrypt(handle, nonce, ciphertextAndTag)
    }

    fun release() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0
        }
    }

    private external fun nativeInit(): Long
    private external fun nativeSetKey(handle: Long, sessionKeyB64: String): Boolean
    private external fun nativeEncrypt(handle: Long, nonce: ByteArray, plaintext: ByteArray): ByteArray?
    private external fun nativeDecrypt(handle: Long, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray?
    private external fun nativeFree(handle: Long)
}
