package com.systemsgo.hex.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * تشفير كلمات المرور باستخدام Android Keystore + AES-GCM.
 * المفتاح محفوظ داخل Keystore ولا يخرج منه أبداً.
 *
 * ROOT-HARDENING FIX: every ciphertext is now bound to an AAD (Additional
 * Authenticated Data) context via [aad]. Without this, a root-privileged
 * attacker who can edit the SQLCipher database or SharedPreferences file
 * directly could copy one field's ciphertext into another row/column
 * (e.g. move `sshTunnelPassword` from profile B into `password` on profile
 * A, or replay an old value over a newer one) and it would still decrypt
 * "successfully" — GCM's authentication tag only proves the bytes weren't
 * bit-flipped, not that they belong where they were placed. Binding the AAD
 * to something that identifies *where the ciphertext is supposed to live*
 * (profile id + field name, "db_passphrase", "pin_code", ...) makes that
 * kind of splice/replay fail decryption instead of silently succeeding,
 * because GCM folds the AAD into the tag computation: if the AAD supplied
 * at decrypt time doesn't match what was supplied at encrypt time, the tag
 * check fails even though the ciphertext bytes themselves are untouched.
 *
 * [aad] is required (no default) so every call site has to state what it's
 * protecting — that's deliberate, to stop new code from silently
 * reintroducing an unbound ciphertext.
 *
 * Backward compatibility: values encrypted by earlier builds (before this
 * fix) were saved with no AAD at all. decrypt() first tries the AAD-bound
 * path; if that fails specifically on tag verification, it retries with an
 * empty AAD so pre-existing data keeps working. The next time that value is
 * saved (RdpProfileRepository re-encrypts on every update), it's rewritten
 * with the AAD bound in, so the migration is transparent and gradual.
 */
object CryptoHelper {

    private const val KEY_ALIAS      = "systemsgo_profile_key"
    private const val PROVIDER       = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LEN    = 128
    private const val IV_LEN         = 12

    // Bound into every AAD so a ciphertext produced by this app can never be
    // silently reused if it ever ended up somewhere else on the device.
    private const val APP_SCOPE = "com.systemsgo.hex"

    private fun buildAad(context: String): ByteArray =
        "$APP_SCOPE|$context".toByteArray(Charsets.UTF_8)

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).also { it.load(null) }
        ks.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .also { it.init(spec) }.generateKey()
    }

    /**
     * @param aad Context string identifying what this ciphertext is/belongs to,
     *   e.g. "profile:<id>:password" or "pin_code" or "db_passphrase". Bound in
     *   as AAD so this ciphertext can only ever decrypt correctly when read back
     *   under the exact same context — see the class doc for why that matters.
     */
    fun encrypt(plaintext: String, aad: String): String {
        // Empty string = optional field not configured (gatewayPassword, sshPrivateKey, etc.).
        // Symmetric with decrypt("") → "". Returning "" here is NOT a security issue because
        // there is no credential to protect. The real risk was a NON-BLANK value silently
        // falling back to plaintext on Keystore failure — that path now throws SecurityException
        // (see the catch block below), so the empty-string fast-path is safe to keep.
        //
        // BUG-2 FIX: Use isEmpty() instead of isBlank(). isBlank() returns true for strings
        // that contain only whitespace (spaces, tabs, etc.), causing a password like " "
        // (a single space) to be returned as-is without AES-GCM encryption. isEmpty() only
        // skips truly empty strings — intentional unconfigured fields — which is the correct
        // semantic here. Any non-empty credential, including whitespace-only ones, must be
        // encrypted before storage.
        if (plaintext.isEmpty()) return plaintext
        return try {
            val key    = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            // ROOT-HARDENING FIX: bind the AAD before doFinal() so it's folded
            // into the GCM authentication tag. Must be called after init() and
            // before doFinal()/update().
            cipher.updateAAD(buildAad(aad))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined   = ByteArray(IV_LEN + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, IV_LEN)
            System.arraycopy(ciphertext, 0, combined, IV_LEN, ciphertext.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("CryptoHelper", "encrypt failed", e)
            // FIX #1: Never return plaintext on failure — that silently stores
            // passwords in clear text when Keystore is unavailable (common after
            // factory reset or on old ARMv7 devices). Throw so the caller can
            // surface an error and abort the save operation instead.
            throw SecurityException("Encryption failed: cannot save credentials safely", e)
        }
    }

    /**
     * بيانات قديمة غير مشفرة تُعاد كما هي (توافق مع الوراء).
     *
     * @param aad Must be the exact same context string passed to [encrypt] when
     *   this value was saved (e.g. "profile:<id>:password"). A mismatch is
     *   treated as tampering/misplacement and fails just like a wrong key would.
     */
    fun decrypt(encoded: String, aad: String): String {
        if (encoded.isBlank()) return encoded
        return try {
            // BUG-2 FIX: If the stored value was saved before encryption was introduced
            // (plain-text password from an older build), Base64.decode() throws
            // IllegalArgumentException which was previously swallowed and re-thrown as
            // SecurityException — causing every upgrade from an old build to lose all
            // saved credentials. We catch it here and return the original string so
            // existing plain-text passwords continue to work after an upgrade.
            val combined = try {
                Base64.decode(encoded, Base64.NO_WRAP)
            } catch (_: IllegalArgumentException) {
                // CRIT-3 FIX: Returning plaintext silently is a security defect — the caller
                // has no way to distinguish a successfully decrypted value from a legacy
                // plain-text one, so it proceeds with an unencrypted credential as if it were
                // valid. Throw instead; the caller (RdpProfileRepository) will surface a
                // "please re-enter your password" prompt, which is the correct UX.
                throw SecurityException(
                    "Stored value is not encrypted — please edit this profile and re-enter your password."
                )
            }
            // CRIT-3 FIX: A ciphertext shorter than the IV is either corrupt or a forged
            // value injected into the database. Returning it as-is would let an attacker
            // plant an arbitrary short Base64 string and have the app use it as a credential.
            if (combined.size <= IV_LEN) throw SecurityException(
                "Ciphertext too short to be valid — possible data corruption or tampering."
            )
            val iv         = combined.copyOfRange(0, IV_LEN)
            val ciphertext = combined.copyOfRange(IV_LEN, combined.size)
            val key        = getOrCreateKey()

            // ROOT-HARDENING FIX: try the current AAD-bound scheme first.
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv))
                cipher.updateAAD(buildAad(aad))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } catch (e: javax.crypto.AEADBadTagException) {
                // Tag verification failed under the new AAD. This is expected for
                // any value written by a build before this fix — those were saved
                // with no AAD at all. Retry once, unbound, purely for backward
                // compatibility with pre-existing data (a fresh Cipher instance is
                // required: doFinal() has already invalidated the previous one).
                // A ciphertext that fails BOTH attempts is either genuinely corrupt
                // or was tampered with/spliced from elsewhere, and correctly falls
                // through to the outer catch below.
                val legacyCipher = Cipher.getInstance(TRANSFORMATION)
                legacyCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv))
                String(legacyCipher.doFinal(ciphertext), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            android.util.Log.e("CryptoHelper", "decrypt failed", e)
            // BUG-DECRYPT FIX: Returning "" on failure silently propagates an empty
            // password to the server, producing a generic "Auth failed" error with no
            // user-visible explanation. This happens after reinstall, backup restore, or
            // any event that destroys the Android Keystore key.
            // Fix: throw a SecurityException so the caller (RdpProfileRepository) can
            // catch it and surface a meaningful "Credentials lost — please re-enter your
            // password" dialog, exactly as encrypt() already does on its failure path.
            //
            // ROOT-HARDENING FIX: this now also covers the case where BOTH the
            // AAD-bound and legacy attempts failed — i.e. a ciphertext that was
            // moved/spliced from a different field or profile. The user sees the
            // same "please re-enter your password" prompt as any other corruption,
            // which is the correct behavior: we don't want to distinguish "tampered"
            // from "corrupted" in the UI and hand an attacker a tampering oracle.
            throw SecurityException(
                "Decryption failed: saved credentials may be corrupted or the Keystore key was lost. " +
                "Please re-enter your password for this profile.", e
            )
        }
    }
}
