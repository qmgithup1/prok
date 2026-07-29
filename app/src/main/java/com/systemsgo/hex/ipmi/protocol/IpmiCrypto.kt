package com.systemsgo.hex.ipmi.protocol

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * IPMI 2.0 (RMCP+) crypto helpers.
 *
 * Implements both "Cipher Suite 3" (RAKP-HMAC-SHA1 / HMAC-SHA1-96 /
 * AES-CBC-128 — the combination every BMC we've tested pre-~2020,
 * Supermicro/iDRAC 6-9/iLO 4/5/generic ASPEED AST2500, supports, and the one
 * ipmitool defaults to as `-C 3`) and "Cipher Suite 17" (RAKP-HMAC-SHA256 /
 * HMAC-SHA256-128 / AES-CBC-128, `-C 17`) — newer firmware (iDRAC9 with
 * FIPS/CNSA settings, iLO5+, some AST2600 boards) increasingly disables
 * SHA-1-based suites entirely, so [IpmiSession] tries 17 first and falls
 * back to 3. Both suites share the same AES-CBC-128 confidentiality
 * algorithm — only the hash used for RAKP auth, SIK/K1/K2 derivation, and
 * per-packet integrity differs.
 */
internal object IpmiCrypto {

    private val random = SecureRandom()

    fun randomBytes(len: Int): ByteArray = ByteArray(len).also { random.nextBytes(it) }

    fun hmacSha1(key: ByteArray, vararg data: ByteArray): ByteArray {
        // A zero-length key is legal in IPMI (anonymous / null-password
        // logins) but javax.crypto.Mac rejects zero-length HMAC keys, so we
        // pad to one zero byte — matches ipmitool's behaviour.
        val effectiveKey = if (key.isEmpty()) ByteArray(1) else key
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(effectiveKey, "HmacSHA1"))
        for (d in data) mac.update(d)
        return mac.doFinal()
    }

    /** Truncates a full HMAC-SHA1 (20 bytes) to the 12-byte AuthCode used per-packet by HMAC-SHA1-96. */
    fun hmacSha1_96(key: ByteArray, vararg data: ByteArray): ByteArray =
        hmacSha1(key, *data).copyOf(12)

    fun hmacSha256(key: ByteArray, vararg data: ByteArray): ByteArray {
        val effectiveKey = if (key.isEmpty()) ByteArray(1) else key
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(effectiveKey, "HmacSHA256"))
        for (d in data) mac.update(d)
        return mac.doFinal()
    }

    /** Truncates a full HMAC-SHA256 (32 bytes) to the 16-byte AuthCode used per-packet by HMAC-SHA256-128 (cipher suite 17). */
    fun hmacSha256_128(key: ByteArray, vararg data: ByteArray): ByteArray =
        hmacSha256(key, *data).copyOf(16)

    /**
     * SIK (Session Integrity Key) per IPMI 2.0 §13.32:
     * SIK = HMAC(K_g_or_password; R_m + R_c + role_byte + unameLen + uname)
     * using whichever hash the negotiated RAKP auth algorithm specifies
     * (SHA-1 for cipher suite 3, SHA-256 for cipher suite 17).
     *
     * [bmcKey] is the BMC's separately-configured "Kg"/"BMC key" — pass it
     * (non-empty) for a "two-key" login and it's used as the HMAC key here
     * instead of [password]. Pass null/empty for the far more common
     * "one-key" login (no BMC key configured, the factory default on
     * essentially every BMC), where K_g == the user's password and this
     * falls back to [password] — same behavior this function always had
     * before two-key support existed. Note RAKP2/RAKP3's own AuthCodes
     * (see [IpmiSession.rakp1And2]/[IpmiSession.rakp3And4]) are *not*
     * affected by Kg either way — those always use the password, per spec;
     * Kg only ever changes SIK (and therefore K1/K2, and therefore every
     * per-packet integrity/encryption operation once the session is up).
     */
    fun deriveSik(
        password: ByteArray,
        remoteConsoleRandom: ByteArray,
        managedSystemRandom: ByteArray,
        requestedPrivByte: Int,
        username: ByteArray,
        useSha256: Boolean,
        bmcKey: ByteArray? = null,
    ): ByteArray {
        val sikKey = if (bmcKey != null && bmcKey.isNotEmpty()) bmcKey else password
        val args = arrayOf(
            remoteConsoleRandom, managedSystemRandom,
            byteArrayOf(requestedPrivByte.toByte()), byteArrayOf(username.size.toByte()), username,
        )
        return if (useSha256) hmacSha256(sikKey, *args) else hmacSha1(sikKey, *args)
    }

    /** K1 = HMAC(SIK, 0x01 * N) — used to key per-packet integrity (HMAC-SHA1-96 or HMAC-SHA256-128). */
    fun deriveK1(sik: ByteArray, useSha256: Boolean): ByteArray =
        if (useSha256) hmacSha256(sik, ByteArray(32) { 0x01 }) else hmacSha1(sik, ByteArray(20) { 0x01 })

    /** K2 = HMAC(SIK, 0x02 * N) — first 16 bytes become the AES-CBC-128 key either way. */
    fun deriveK2(sik: ByteArray, useSha256: Boolean): ByteArray =
        if (useSha256) hmacSha256(sik, ByteArray(32) { 0x02 }) else hmacSha1(sik, ByteArray(20) { 0x02 })

    fun aesCbc128Encrypt(key16: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = randomBytes(16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key16.copyOf(16), "AES"), IvParameterSpec(iv))
        // IPMI pads with 1,2,3...,N then a final byte = pad length, so the
        // total is always a multiple of 16 and the padding is self-describing.
        val padLen = 16 - ((plaintext.size + 1) % 16).let { if (it == 0) 0 else 16 - it }
        val padded = ByteArray(plaintext.size + padLen + 1)
        System.arraycopy(plaintext, 0, padded, 0, plaintext.size)
        for (i in 0 until padLen) padded[plaintext.size + i] = (i + 1).toByte()
        padded[padded.size - 1] = padLen.toByte()
        val ct = cipher.doFinal(padded)
        return iv + ct
    }

    fun aesCbc128Decrypt(key16: ByteArray, ivAndCiphertext: ByteArray): ByteArray {
        require(ivAndCiphertext.size >= 16) { "AES-CBC payload too short" }
        val iv = ivAndCiphertext.copyOfRange(0, 16)
        val ct = ivAndCiphertext.copyOfRange(16, ivAndCiphertext.size)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key16.copyOf(16), "AES"), IvParameterSpec(iv))
        val padded = cipher.doFinal(ct)
        if (padded.isEmpty()) return padded
        val padLen = padded.last().toInt() and 0xFF
        val dataLen = (padded.size - 1 - padLen).coerceAtLeast(0)
        return padded.copyOf(dataLen)
    }
}
