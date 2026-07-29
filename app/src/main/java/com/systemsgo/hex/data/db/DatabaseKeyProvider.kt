package com.systemsgo.hex.data.db

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.systemsgo.hex.security.CryptoHelper
import com.systemsgo.hex.security.openEncryptedPrefs
import java.security.SecureRandom

/**
 * HIGH-3 FIX: Manages the SQLCipher passphrase used to encrypt [SystemsGoDatabase].
 *
 * Key lifecycle:
 *  1. First run — generates 32 random bytes (256-bit key).
 *  2. Encodes as Base64, wraps with CryptoHelper.encrypt() (AES-256-GCM / Keystore),
 *     stores in a private SharedPreferences file.
 *  3. Every subsequent run — decrypts from SharedPreferences and returns.
 *
 * If the Keystore key is ever lost (factory reset, re-enrolment), decryption
 * throws SecurityException.  [getOrCreate] catches this, generates a new key,
 * and stores it.  The existing SQLCipher database file is still encrypted
 * with the old, now-unrecoverable key, so it can no longer be opened with the
 * new one.  [DatabaseOpenRecovery] (invoked from AppModule.provideDatabase)
 * is what actually handles that: it detects the resulting "cannot open
 * database" failure, deletes the unreadable file (+ -wal/-shm), and recreates
 * an empty encrypted database with the new key so the app starts normally.
 * This is the safest failure mode: no silent plain-text fallback.
 */
object DatabaseKeyProvider {

    private const val PREFS = "systemsgo_db_meta"
    private const val KEY   = "passphrase_enc_v1"

    fun getOrCreate(context: Context): ByteArray {
        // REM-1 FIX: Use EncryptedSharedPreferences (AES-256-GCM) instead of plain
        // SharedPreferences. A root-privileged attacker can read *and tamper with*
        // plain SharedPreferences files (e.g. replace passphrase_enc_v1 with a crafted
        // value that forces key regeneration → all stored connection data lost).
        // EncryptedSharedPreferences detects any tampering via the GCM authentication
        // tag and throws at read-time before the corrupted value is ever acted on.
        // All other sensitive prefs in this codebase (TOFU, PIN lockout) already use
        // openEncryptedPrefs — this was the only remaining inconsistency.
        val sp = context.openEncryptedPrefs(PREFS)
        val stored = sp.getString(KEY, null)
        if (stored != null) {
            return try {
                // ROOT-HARDENING FIX: dedicated AAD context ("db_passphrase") so
                // this ciphertext — the key to the entire encrypted database —
                // can't be swapped for some other stored value.
                Base64.decode(CryptoHelper.decrypt(stored, "db_passphrase"), Base64.NO_WRAP)
            } catch (_: SecurityException) {
                // Keystore key was lost — generate a new key.
                // The old encrypted DB will be unreadable; Room will trigger
                // fallbackToDestructiveMigration so the app starts fresh.
                android.util.Log.w(
                    "DatabaseKeyProvider",
                    "Keystore key lost — generating new DB passphrase; existing data will be lost"
                )
                createAndSave(sp)
            }
        }
        return createAndSave(sp)
    }

    private fun createAndSave(sp: SharedPreferences): ByteArray {
        val key    = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val b64    = Base64.encodeToString(key, Base64.NO_WRAP)
        val enc    = CryptoHelper.encrypt(b64, "db_passphrase")
        sp.edit().putString(KEY, enc).commit()
        return key
    }
}
