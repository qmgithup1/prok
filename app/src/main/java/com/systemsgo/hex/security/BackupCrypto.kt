package com.systemsgo.hex.security

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for the "Export All Connections" backup file
 * (see [com.systemsgo.hex.data.backup.ConnectionBackupManager]).
 *
 * This is deliberately separate from [CryptoHelper]: CryptoHelper's key lives
 * inside the Android Keystore and never leaves the device, which is exactly
 * right for the on-device database but is *not* portable — a backup
 * encrypted with it could only ever be restored to the same device/install,
 * defeating the point of a backup. BackupCrypto instead derives a key from a
 * user-supplied password via PBKDF2, so the resulting file can be decrypted
 * on any device given the correct password.
 *
 * File layout (all integers big-endian):
 *   [8 bytes]  magic   = "SYSTEMSGOBK"
 *   [1 byte]   version = 2
 *   [16 bytes] salt      (PBKDF2)
 *   [4 bytes]  iterations
 *   [12 bytes] iv        (AES-GCM)
 *   [n bytes]  ciphertext (AES-256-GCM, includes the 16-byte auth tag)
 *
 * ROOT-HARDENING FIX (v2): the header (magic + version + salt + iterations +
 * iv) is now bound in as AAD when encrypting/decrypting the payload. In v1,
 * the GCM tag only covered the ciphertext — the header fields sat in front
 * of it, readable and *writable* by anyone with file access, but not
 * cryptographically tied to it. That meant an attacker (or a corrupted
 * transfer) could splice a different salt/iterations/iv into an otherwise
 * valid v1 file — e.g. drop `iterations` to a tiny number to weaken the
 * PBKDF2 step for an offline password-guessing attack — and the file would
 * still "look" structurally valid; only a wrong-password error at restore
 * time would reveal something was off, with no way to tell tampering from a
 * typo. Folding the header into the AAD means the auth tag itself certifies
 * those fields, so any change to them makes decryption fail outright.
 * v1 backups (no AAD) are still readable — [decrypt] retries without the
 * header AAD if the v2 check fails tag verification — but every *new*
 * export is written as v2.
 */
object BackupCrypto {

    private val MAGIC = "SYSTEMSGOBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 2
    private const val VERSION_V1: Byte = 1 // legacy, no header AAD — read-only support
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val ITERATIONS = 210_000
    private const val KEY_LEN_BITS = 256
    private const val GCM_TAG_LEN_BITS = 128
    private const val HEADER_LEN = 8 + 1 + SALT_LEN + 4 + IV_LEN

    class InvalidPasswordException :
        Exception("Incorrect password, or the backup file is corrupted.")

    class CorruptBackupException(message: String) : Exception(message)

    /** Encrypts [plaintext] with a key derived from [password]. */
    fun encrypt(plaintext: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // GCM default IV length is 12 bytes

        // ROOT-HARDENING FIX: build the header first so it can be bound as AAD —
        // this authenticates magic/version/salt/iterations/iv, not just the
        // ciphertext. Must be built before doFinal() since it needs to be
        // passed to updateAAD() before the ciphertext is produced.
        val header = ByteArrayOutputStream(HEADER_LEN).apply {
            write(MAGIC)
            write(VERSION.toInt())
            write(salt)
            write(intToBytes(ITERATIONS))
            write(iv)
        }.toByteArray()
        cipher.updateAAD(header)
        val ciphertext = cipher.doFinal(plaintext)

        val out = ByteArrayOutputStream(HEADER_LEN + ciphertext.size)
        out.write(header)
        out.write(ciphertext)
        return out.toByteArray()
    }

    /**
     * Decrypts a backup previously produced by [encrypt].
     * Throws [InvalidPasswordException] on a wrong password (GCM auth failure)
     * and [CorruptBackupException] if the file isn't a recognizable backup.
     */
    fun decrypt(data: ByteArray, password: String): ByteArray {
        if (data.size < HEADER_LEN) {
            throw CorruptBackupException("File is too small to be a valid backup.")
        }
        var offset = 0
        val magic = data.copyOfRange(offset, offset + MAGIC.size); offset += MAGIC.size
        if (!magic.contentEquals(MAGIC)) {
            throw CorruptBackupException("This file is not a Systems Go connections backup.")
        }
        val version = data[offset]
        if (version != VERSION && version != VERSION_V1) {
            throw CorruptBackupException("This backup was created by an incompatible app version.")
        }
        // ROOT-HARDENING FIX: the header (everything up to and including iv)
        // is what gets bound as AAD for v2 — capture it before advancing past
        // it, so we don't have to re-derive/re-slice it afterward.
        val headerStart = 0
        offset += 1
        val salt = data.copyOfRange(offset, offset + SALT_LEN); offset += SALT_LEN
        val iterations = bytesToInt(data, offset); offset += 4
        if (iterations <= 0 || iterations > 5_000_000) {
            throw CorruptBackupException("Backup file header is invalid.")
        }
        val iv = data.copyOfRange(offset, offset + IV_LEN); offset += IV_LEN
        val header = data.copyOfRange(headerStart, offset)
        val ciphertext = data.copyOfRange(offset, data.size)

        val key = deriveKey(password, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN_BITS, iv))
        // v1 files were encrypted with no AAD at all, so only bind the header
        // for v2 — binding it for a v1 file would make every legacy backup
        // unreadable, since the tag was never computed with it in the first place.
        if (version == VERSION) {
            cipher.updateAAD(header)
        }
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            // Wrong password, tampered/corrupted ciphertext, or (for v2) a
            // header field that was altered after encryption — GCM's auth tag
            // check failed. Never surface partial/garbage plaintext to the
            // caller, and don't distinguish the cause in the message: telling
            // an attacker "the header was tampered with" vs "wrong password"
            // would hand them a tampering oracle.
            throw InvalidPasswordException()
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val passwordChars = password.toCharArray()
        val spec = PBEKeySpec(passwordChars, salt, iterations, KEY_LEN_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
            passwordChars.fill('\u0000')
        }
    }

    private fun intToBytes(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun bytesToInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
        ((b[offset + 1].toInt() and 0xFF) shl 16) or
        ((b[offset + 2].toInt() and 0xFF) shl 8) or
        (b[offset + 3].toInt() and 0xFF)
}
