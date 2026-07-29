package com.systemsgo.hex.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * HIGH-1 FIX: PBKDF2-based PIN hashing.
 *
 * Previously, the PIN was stored as:
 *   CryptoHelper.encrypt(rawPin)
 * so obtaining the Keystore key (rooted device, Keystore vulnerability) immediately
 * revealed the PIN in plaintext — no computation required.
 *
 * The PIN is processed through PBKDF2-HMAC-SHA256 with a random 16-byte salt
 * before being wrapped in CryptoHelper.encrypt(). Even with the Keystore key,
 * an attacker must run the full PBKDF2 iteration count per guess.
 *
 * ITERATIONS-HARDENING FIX: bumped from 120,000 to 600,000 (OWASP's current
 * PBKDF2-HMAC-SHA256 recommendation, ~Nov 2023 revision) — 120k was adequate
 * when this was first written but no longer reflects current guidance as
 * hardware (GPU/ASIC cracking rigs) keeps getting faster.
 *
 * Storage format (stored INSIDE the CryptoHelper ciphertext):
 *   v3 (current):  "v3:<pinLen>:<iterations>:<base64Salt>:<base64Hash>"
 *   v2 (legacy):   "v2:<pinLen>:<base64Salt>:<base64Hash>"   — iterations
 *                  implicitly 120,000, since v2 never stored the count.
 *
 * v3 stores the iteration count actually used *in the payload itself* rather
 * than assuming a fixed constant — this is what makes the count safe to bump
 * again in the future without a repeat of this problem: verifyV3() always
 * PBKDF2's with whatever count is embedded in the specific hash being
 * checked, never with "whatever ITERATIONS is today". Existing v3 hashes
 * keep verifying correctly forever, no matter how many times ITERATIONS is
 * raised later; only *new* hashes (via [hash]) pick up the new default.
 *
 * The stored PIN length lets AppLockScreen show the correct number of dot-slots
 * (HIGH-2 FIX) without knowing the PIN itself.
 *
 * Legacy detection: [isLegacy] returns true for both the raw-PIN format (no
 * prefix at all) and the old fixed-120k "v2:" format — both should be
 * re-hashed into v3 the next time the caller has the raw PIN available (e.g.
 * on next successful unlock), the same upgrade-on-verify pattern already
 * used for the v1→v2 transition.
 */
object PinHasher {

    private const val VERSION             = "v3"
    private const val VERSION_LEGACY_V2   = "v2"
    private const val ALGORITHM           = "PBKDF2WithHmacSHA256"
    // ITERATIONS-HARDENING FIX: 120_000 → 600_000 (OWASP current recommendation).
    private const val ITERATIONS          = 600_000
    // Fixed count implied by any "v2:" payload — v2 never stored this value,
    // so it must stay pinned to what v2 always actually used.
    private const val ITERATIONS_LEGACY_V2 = 120_000
    private const val KEY_LENGTH_BITS     = 256
    private const val SALT_BYTES          = 16

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Derives a PBKDF2 hash of [pin] and returns the storage payload
     * (the string that will be encrypted by CryptoHelper before persisting).
     * Always writes the current v3 format with the current [ITERATIONS].
     */
    fun hash(pin: String): String {
        require(pin.isNotBlank()) { "PIN must not be blank" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin.toCharArray(), salt, ITERATIONS)
        return "$VERSION:${pin.length}:$ITERATIONS:" +
            "${Base64.encodeToString(salt, Base64.NO_WRAP)}:" +
            Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    /**
     * Returns true if [pin] matches [storedPayload] (the decrypted storage value).
     *
     * Supports the current v3 format, the legacy fixed-120k v2 format, and the
     * original raw-PIN format.
     */
    fun verify(pin: String, storedPayload: String): Boolean {
        return try {
            when {
                storedPayload.startsWith("$VERSION:") -> verifyV3(pin, storedPayload)
                storedPayload.startsWith("$VERSION_LEGACY_V2:") -> verifyV2(pin, storedPayload)
                else -> {
                    // Legacy: storedPayload IS the raw PIN.
                    // Use constant-time comparison to prevent timing attacks.
                    MessageDigest.isEqual(
                        pin.toByteArray(Charsets.UTF_8),
                        storedPayload.toByteArray(Charsets.UTF_8)
                    )
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns true if [storedPayload] is NOT in the current v3 format — this
     * covers both the raw-PIN format and the old fixed-120k v2 format, both
     * of which should be upgraded to v3 on next successful verify.
     * Used by the caller to decide whether to upgrade the stored hash.
     */
    fun isLegacy(storedPayload: String): Boolean = !storedPayload.startsWith("$VERSION:")

    /**
     * Extracts the PIN length embedded in a v2 or v3 payload.
     * Returns 6 for legacy raw-PIN payloads (safe upper-bound for dot-slot display).
     *
     * HIGH-2 FIX: makes AppLockScreen show the right number of dots.
     */
    fun extractLength(storedPayload: String): Int {
        if (!storedPayload.startsWith("$VERSION:") && !storedPayload.startsWith("$VERSION_LEGACY_V2:")) return 6
        // Pin length sits at index 1 in both v2 ("v2:<len>:<salt>:<hash>") and
        // v3 ("v3:<len>:<iterations>:<salt>:<hash>") — the fields that follow
        // differ, but this one lines up in both.
        return storedPayload.split(":").getOrNull(1)?.toIntOrNull()?.coerceIn(4, 6) ?: 6
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun verifyV3(pin: String, payload: String): Boolean {
        val parts = payload.split(":")
        if (parts.size != 5) return false
        val iterations   = parts[2].toIntOrNull() ?: return false
        val salt         = Base64.decode(parts[3], Base64.NO_WRAP)
        val expectedHash = Base64.decode(parts[4], Base64.NO_WRAP)
        val actualHash   = pbkdf2(pin.toCharArray(), salt, iterations)
        return MessageDigest.isEqual(actualHash, expectedHash)
    }

    private fun verifyV2(pin: String, payload: String): Boolean {
        val parts = payload.split(":")
        if (parts.size != 4) return false
        val salt         = Base64.decode(parts[2], Base64.NO_WRAP)
        val expectedHash = Base64.decode(parts[3], Base64.NO_WRAP)
        val actualHash   = pbkdf2(pin.toCharArray(), salt, ITERATIONS_LEGACY_V2)
        return MessageDigest.isEqual(actualHash, expectedHash)
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec    = PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()   // zero the password copy held inside PBEKeySpec
        }
    }
}
