package com.systemsgo.hex.security

import android.content.Context

/**
 * CLOUD-SYNC FEATURE (Part 1/3).
 *
 * Stores the passphrase used to encrypt/decrypt the connections backup that
 * gets uploaded to Drive/Dropbox (the same [BackupCrypto] AES-256-GCM format
 * as the local "Export All Connections" file, see [ConnectionBackupManager]).
 *
 * Why this exists at all: [ConnectionBackupManager.exportTo]/[importFrom]
 * take the password as a parameter and never store it, because a manual
 * export/import always has the user sitting right there to type it in.
 * Automatic background cloud sync (Part 3's WorkManager job) has no one to
 * ask — it needs to read the passphrase itself. Storing it in the clear
 * would mean anyone who can read this app's private storage on a rooted
 * device recovers every saved RDP/VNC/SSH credential, which is exactly what
 * [BackupCrypto]'s password-derived encryption exists to prevent.
 *
 * So this uses the *same* pattern [DatabaseKeyProvider] already uses for the
 * SQLCipher database passphrase: the value is wrapped with
 * [CryptoHelper] (AES-256-GCM, Android-Keystore-backed) under a dedicated AAD
 * context before being written to [openEncryptedPrefs] (itself
 * EncryptedSharedPreferences — see that function's doc for the tamper-
 * detection rationale). That is two independent layers, same as the DB key:
 * a root-privileged reader of this app's files still can't recover the
 * plaintext passphrase without also holding the Keystore key, which never
 * leaves hardware-backed storage on devices that support it.
 *
 * Unlike [DatabaseKeyProvider] this does NOT auto-generate a value — a
 * missing/lost passphrase here just means cloud sync can't run until the
 * user re-enters it (Settings → Cloud Sync), never "silently start
 * encrypting under a new key nobody chose." Losing this passphrase does not
 * affect the local database at all; it only means old cloud backup files
 * encrypted under the previous passphrase can no longer be decrypted, same
 * as forgetting the password on a manually exported file.
 */
object SyncPassphraseStore {

    private const val PREFS = "systemsgo_cloud_sync_secure"
    private const val KEY = "sync_passphrase_enc_v1"
    private const val AAD_CONTEXT = "cloud_sync_passphrase"

    /** True if a passphrase has been set and is currently decryptable (Keystore key intact). */
    fun hasPassphrase(context: Context): Boolean = getPassphrase(context) != null

    /**
     * Returns the stored passphrase, or null if none has been set yet, or if
     * the wrapping Keystore key was lost (factory reset, re-enrolment —
     * same failure mode [DatabaseKeyProvider] handles for the DB key). In
     * the latter case the old ciphertext is left in place rather than
     * silently deleted, in case a future Keystore recovery path is added;
     * callers should treat null the same as "not set" either way and prompt
     * the user to re-enter it.
     */
    fun getPassphrase(context: Context): String? {
        val sp = context.openEncryptedPrefs(PREFS)
        val stored = sp.getString(KEY, null) ?: return null
        return try {
            CryptoHelper.decrypt(stored, AAD_CONTEXT)
        } catch (_: SecurityException) {
            android.util.Log.w(
                "SyncPassphraseStore",
                "Keystore key lost — stored cloud-sync passphrase can no longer be decrypted"
            )
            null
        }
    }

    /** Encrypts and persists [passphrase]. Overwrites any previously stored value. */
    fun setPassphrase(context: Context, passphrase: String) {
        require(passphrase.isNotEmpty()) { "Cloud sync passphrase must not be empty" }
        val sp = context.openEncryptedPrefs(PREFS)
        val enc = CryptoHelper.encrypt(passphrase, AAD_CONTEXT)
        sp.edit().putString(KEY, enc).commit()
    }

    /** Forgets the stored passphrase — called when the user unlinks cloud sync entirely. */
    fun clear(context: Context) {
        context.openEncryptedPrefs(PREFS).edit().remove(KEY).commit()
    }
}
