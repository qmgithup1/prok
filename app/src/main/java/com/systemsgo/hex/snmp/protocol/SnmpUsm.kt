package com.systemsgo.hex.snmp.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SNMPv3 User-based Security Model (USM, RFC 3414) — password-to-key
 * derivation, per-engine key localization, HMAC authentication, and
 * DES-CBC (RFC 3414 §8.1.1) / AES-CFB128 (RFC 3826) privacy.
 *
 * AES-192/256 aren't in either IETF RFC — RFC 3414 only defines DES and
 * RFC 3826 only defines AES-128 — but "extend the localized key by
 * repeated hashing until it's long enough" (see [extendKey]) is the de
 * facto convention every major implementation (Net-SNMP, SNMP4J, most
 * vendor agents) uses for them, so AES-192/256 here interoperate with
 * those in practice even though there's no RFC to cite.
 */
object SnmpUsm {
    private const val PASSWORD_EXPANSION_LEN = 1_048_576 // RFC 3414 Appendix A.2 — the fixed 1 MiB expansion length
    private val random = SecureRandom()

    // ── Key derivation (RFC 3414 Appendix A.2/A.3) ──────────────────────

    /** Ku — the non-localized key derived from a plaintext passphrase (Appendix A.2's "password to key" algorithm). */
    fun passwordToKey(passphrase: String, authProtocol: SnmpAuthProtocol): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        val password = passphrase.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance(authProtocol.digestAlgo)
        val chunk = ByteArray(64)
        var passIndex = 0
        var count = 0
        while (count < PASSWORD_EXPANSION_LEN) {
            for (i in 0 until 64) {
                chunk[i] = password[passIndex % password.size]
                passIndex++
            }
            digest.update(chunk)
            count += 64
        }
        return digest.digest()
    }

    /** Kul — [ku] localized to a specific engine (Appendix A.4: `H(Ku || engineID || Ku)`). */
    fun localizeKey(ku: ByteArray, engineId: ByteArray, authProtocol: SnmpAuthProtocol): ByteArray {
        val digest = MessageDigest.getInstance(authProtocol.digestAlgo)
        digest.update(ku)
        digest.update(engineId)
        digest.update(ku)
        return digest.digest()
    }

    /** Convenience: passphrase straight to a localized key for [engineId]. */
    fun deriveLocalizedKey(passphrase: String, engineId: ByteArray, authProtocol: SnmpAuthProtocol): ByteArray =
        localizeKey(passwordToKey(passphrase, authProtocol), engineId, authProtocol)

    /**
     * Stretches [kul] to [requiredLen] bytes when the auth digest is
     * shorter than the privacy protocol's key size (e.g. SHA-1's 20-byte
     * digest for AES-256's 32-byte key) — see class doc. No-op (truncated
     * if oversized) when [kul] is already long enough.
     */
    fun extendKey(ku: ByteArray, kul: ByteArray, authProtocol: SnmpAuthProtocol, requiredLen: Int): ByteArray {
        if (kul.size >= requiredLen) return kul.copyOf(requiredLen)
        var extended = kul
        while (extended.size < requiredLen) {
            val digest = MessageDigest.getInstance(authProtocol.digestAlgo)
            digest.update(ku)
            digest.update(extended)
            extended += digest.digest()
        }
        return extended.copyOf(requiredLen)
    }

    // ── Authentication (RFC 3414 §6.3, RFC 7860 for the SHA-2 truncation lengths) ──

    /**
     * HMACs [messageWithZeroedAuthParams] — the full serialized message
     * with the 12-(or wider, for SHA-2)-byte msgAuthenticationParameters
     * field zeroed out, per §6.3.1 step 4 — with the localized [authKey],
     * truncated to [SnmpAuthProtocol.digestBytes]. The caller (SnmpClient)
     * is responsible for the zero-then-splice placement since only it
     * knows the field's offset in the encoded message.
     */
    fun computeAuthDigest(messageWithZeroedAuthParams: ByteArray, authKey: ByteArray, authProtocol: SnmpAuthProtocol): ByteArray {
        val mac = Mac.getInstance(authProtocol.hmacAlgo)
        mac.init(SecretKeySpec(authKey, authProtocol.hmacAlgo))
        val full = mac.doFinal(messageWithZeroedAuthParams)
        return full.copyOf(authProtocol.digestBytes)
    }

    // ── Privacy (RFC 3414 §8.1 for DES, RFC 3826 for AES-128; §"AES-192/256" above) ──

    /** 8 random bytes for the per-message AES `msgPrivacyParameters` salt (RFC 3826 §3.1.1 — any locally-unique value works; random is simplest and avoids needing durable per-engine counter state). */
    fun generateSalt(): ByteArray = ByteArray(8).also { random.nextBytes(it) }

    /**
     * DES-CBC encrypt (RFC 3414 §8.1.1.1). [privKey] must be the 16-byte
     * localized privacy key (first 8 bytes = DES key, last 8 = pre-IV).
     * [salt] is the full 8-byte `msgPrivacyParameters` value that goes on
     * the wire alongside the ciphertext — conventionally
     * `snmpEngineBoots(4, big-endian) || a 4-byte local non-repeating
     * counter` (see [desSalt]); the IV is the pre-IV XORed with this
     * salt. Plaintext is zero-padded to a multiple of 8 bytes.
     */
    fun desEncrypt(plainText: ByteArray, privKey: ByteArray, salt: ByteArray): ByteArray {
        val padded = if (plainText.size % 8 == 0) plainText else plainText + ByteArray(8 - plainText.size % 8)
        val iv = desIv(privKey, salt)
        val cipher = Cipher.getInstance("DES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(privKey.copyOfRange(0, 8), "DES"), IvParameterSpec(iv))
        return cipher.doFinal(padded)
    }

    fun desDecrypt(cipherText: ByteArray, privKey: ByteArray, salt: ByteArray): ByteArray {
        val iv = desIv(privKey, salt)
        val cipher = Cipher.getInstance("DES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(privKey.copyOfRange(0, 8), "DES"), IvParameterSpec(iv))
        return cipher.doFinal(cipherText)
    }

    /** `msgPrivacyParameters` for a DES packet: engineBoots(4, BE) || a 4-byte local counter/random value that must not repeat under one engine-boots epoch. */
    fun desSalt(engineBoots: Int, localCounter: Int): ByteArray = intToBytes(engineBoots) + intToBytes(localCounter)

    private fun desIv(privKey: ByteArray, salt: ByteArray): ByteArray {
        val preIv = privKey.copyOfRange(8, 16)
        val iv = ByteArray(8)
        for (i in 0 until 8) iv[i] = (preIv[i].toInt() xor salt[i].toInt()).toByte()
        return iv
    }

    /** AES-CFB128 encrypt (RFC 3826 §3.1.2). [privKey] is the localized key (16/24/32 bytes, extended via [extendKey] if needed for 192/256). No padding needed — CFB is a stream mode. */
    fun aesEncrypt(plainText: ByteArray, privKey: ByteArray, engineBoots: Int, engineTime: Int, salt: ByteArray): ByteArray {
        val iv = intToBytes(engineBoots) + intToBytes(engineTime) + salt
        val cipher = Cipher.getInstance("AES/CFB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(privKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plainText)
    }

    fun aesDecrypt(cipherText: ByteArray, privKey: ByteArray, engineBoots: Int, engineTime: Int, salt: ByteArray): ByteArray {
        val iv = intToBytes(engineBoots) + intToBytes(engineTime) + salt
        val cipher = Cipher.getInstance("AES/CFB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(privKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(cipherText)
    }

    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
