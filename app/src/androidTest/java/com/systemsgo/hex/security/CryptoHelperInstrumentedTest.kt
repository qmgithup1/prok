package com.systemsgo.hex.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TESTS-FIX: CryptoHelper needs a real Android Keystore-backed AES key
 * (KeyGenParameterSpec + "AndroidKeyStore" provider) — that only exists on a
 * real device/emulator, not the plain JVM `test` source set, so this lives
 * under `androidTest` and runs via `./gradlew connectedAndroidTest`.
 *
 * This is the highest-risk untested class in the project before this change:
 * it's the only thing standing between every saved RDP/VNC/SSH password and
 * plaintext-on-disk, and its own doc comment describes several deliberately
 * tricky corner cases (AAD binding, legacy no-AAD fallback, tamper/splice
 * detection) that are exactly the kind of logic that silently regresses
 * under refactoring without a test pinning the current behavior down.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class CryptoHelperInstrumentedTest {

    @Test
    fun encryptThenDecrypt_withMatchingAad_returnsOriginalPlaintext() {
        val plaintext = "correct horse battery staple"
        val aad = "profile:test-1:password"

        val ciphertext = CryptoHelper.encrypt(plaintext, aad)
        val decrypted = CryptoHelper.decrypt(ciphertext, aad)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun encrypt_neverReturnsPlaintextVerbatim() {
        // The stored value must not be recoverable by just reading the field —
        // guards against ever regressing to the "return plaintext on failure"
        // bug this class's own comments describe fixing.
        val plaintext = "super-secret-password-1234"
        val ciphertext = CryptoHelper.encrypt(plaintext, "pin_code")

        assert(ciphertext != plaintext) {
            "encrypt() must never return the plaintext unchanged"
        }
    }

    @Test
    fun decrypt_withWrongAad_throwsSecurityException() {
        // This is the ROOT-HARDENING behavior described in the class doc:
        // a ciphertext spliced/moved into a different logical field must fail
        // decryption, not silently succeed against the wrong context.
        val ciphertext = CryptoHelper.encrypt("hunter2", "profile:A:password")

        assertThrows(SecurityException::class.java) {
            CryptoHelper.decrypt(ciphertext, "profile:B:password")
        }
    }

    @Test
    fun decrypt_ofCorruptedShortValue_throwsSecurityException() {
        // Base64 of something shorter than the 12-byte IV — either corrupt
        // data or a forged short value; must be rejected, not accepted.
        val bogus = android.util.Base64.encodeToString(ByteArray(4), android.util.Base64.NO_WRAP)

        assertThrows(SecurityException::class.java) {
            CryptoHelper.decrypt(bogus, "some_context")
        }
    }

    @Test
    fun encrypt_ofEmptyString_returnsEmptyString_andSkipsEncryption() {
        // Documented fast-path for "field not configured" — must stay a no-op,
        // not an encrypted empty payload (which would break the isEmpty()
        // check on the read side).
        assertEquals("", CryptoHelper.encrypt("", "gatewayPassword"))
    }

    @Test
    fun decrypt_ofLegacyPlainBase64WithoutIvPrefix_isRejectedNotSilentlyAccepted() {
        // A random Base64 string that happens to decode but is too short to
        // contain a real IV+ciphertext must not be treated as valid — this is
        // the CRIT-3 FIX path (reject, prompt user to re-enter credentials)
        // rather than the old silent-success/silent-empty-return behavior.
        val notReallyEncrypted = android.util.Base64.encodeToString(
            "short".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
        )
        assertThrows(SecurityException::class.java) {
            CryptoHelper.decrypt(notReallyEncrypted, "password")
        }
    }
}
