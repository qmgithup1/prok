package com.systemsgo.hex.security

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom

/**
 * DELAYED-RESET FEATURE: performs the actual destructive "erase everything"
 * operation that used to run immediately when the user confirmed
 * "Reset Application Data" (see AppLockScreen.kt / DataResetWorker.kt for the
 * new 24h-delayed flow around it).
 *
 * This is intentionally more thorough than a bare
 * `ActivityManager.clearApplicationUserData()` call:
 *
 *  - Android Keystore entries are NOT guaranteed to be removed by
 *    clearApplicationUserData() on every OEM/API level (they are tied to the
 *    app UID at the keystore-daemon level, not to the files under
 *    /data/data/<pkg>). We explicitly delete every alias this app creates so
 *    a stale hardware-backed key can never outlive "erase everything".
 *  - Best-effort content-shredding (overwrite with random bytes before
 *    unlink) is applied to the specific files that hold plaintext-adjacent
 *    secrets (encrypted prefs, the SQLCipher database, cached session
 *    thumbnails) before they are deleted. `clearApplicationUserData()` only
 *    unlinks files — it does not overwrite their contents first.
 *  - `clearApplicationUserData()` is still called last as a catch-all: it
 *    also revokes runtime permissions, clears notifications, and removes any
 *    Uri grants tied to the app, then kills the process so the next launch
 *    starts completely clean.
 */
object SecureWipe {

    /** Every EncryptedSharedPreferences file this app creates anywhere. */
    private val ENCRYPTED_PREFS_FILES = listOf(
        "systemsgo_settings",
        "systemsgo_tofu_ssh",
        "systemsgo_tofu_tunnel",
        // MISSING-3 FIX: these three TOFU (trust-on-first-use) host/certificate
        // fingerprint stores were being created (RdpRemoteAdapter.PREFS_TOFU_RDP,
        // VncClient.PREFS_TOFU_VNC, FileTransferManager/SftpFileBrowser's
        // PREFS_TOFU_SFTP) but were never listed here, so wipeEverything() never
        // shredded them — clearApplicationUserData() would still eventually
        // unlink them, but without the random-overwrite pass every other
        // encrypted file gets, leaving recoverable ciphertext-adjacent data on
        // disk after a "secure wipe".
        "systemsgo_tofu_rdp",
        "systemsgo_tofu_vnc",
        "systemsgo_tofu_sftp",
        "pin_lockout",
        "systemsgo_db_meta",
        DataResetManager.SCHEDULE_PREFS,
    )

    /** Android Keystore aliases this app generates. */
    private val KEYSTORE_ALIASES = listOf(
        "systemsgo_profile_key",              // CryptoHelper
        "_androidx_security_master_key_",  // default androidx.security.crypto MasterKey alias
    )

    /**
     * Runs the full secure wipe. Safe to call from a background thread
     * (e.g. a WorkManager worker) — does not touch the UI.
     *
     * @return true if `clearApplicationUserData()` was invoked successfully
     *         (per platform contract this also kills the process).
     */
    fun wipeEverything(context: Context): Boolean {
        shredEncryptedPrefs(context)
        shredDatabase(context)
        shredDirectoryContents(context.cacheDir)
        shredDirectoryContents(context.filesDir)
        context.getExternalFilesDir(null)?.let { shredDirectoryContents(it) }
        deleteKeystoreAliases()

        // Final catch-all: equivalent to the user tapping "Clear storage" in
        // Android's own App Info screen. Also revokes permissions, clears
        // notifications/Uri grants, and kills the process.
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.clearApplicationUserData()
    }

    private fun shredEncryptedPrefs(context: Context) {
        for (name in ENCRYPTED_PREFS_FILES) {
            try {
                val prefsFile = File(
                    File(context.applicationInfo.dataDir, "shared_prefs"),
                    "$name.xml"
                )
                shredFile(prefsFile)
                context.deleteSharedPreferences(name)
            } catch (_: Exception) {
                // Best-effort: a missing/never-created prefs file is expected
                // (e.g. TOFU prefs before the user ever connects anywhere).
            }
        }
    }

    private fun shredDatabase(context: Context) {
        try {
            val dbName = com.systemsgo.hex.data.db.SystemsGoDatabase.DATABASE_NAME
            val dbFile = context.getDatabasePath(dbName)
            // SQLite keeps up to three auxiliary files alongside the main one.
            listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"), File(dbFile.path + "-journal"))
                .forEach { shredFile(it) }
            context.deleteDatabase(dbName)
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    /** Recursively overwrites-then-deletes every file under [dir], keeping [dir] itself. */
    private fun shredDirectoryContents(dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            try {
                if (child.isDirectory) {
                    shredDirectoryContents(child)
                    child.delete()
                } else {
                    shredFile(child)
                }
            } catch (_: Exception) {
                // Best-effort — continue shredding the rest even if one file
                // is locked/unreadable.
            }
        }
    }

    /** Overwrites a single file with random bytes, fsyncs, then deletes it. */
    private fun shredFile(file: File) {
        if (!file.exists() || !file.isFile) return
        try {
            val length = file.length()
            if (length > 0) {
                RandomAccessFile(file, "rws").use { raf ->
                    val random = SecureRandom()
                    val buffer = ByteArray(minOf(length, 64 * 1024).toInt())
                    var remaining = length
                    raf.seek(0)
                    while (remaining > 0) {
                        random.nextBytes(buffer)
                        val chunk = minOf(remaining, buffer.size.toLong()).toInt()
                        raf.write(buffer, 0, chunk)
                        remaining -= chunk
                    }
                    raf.fd.sync()
                }
            }
        } catch (_: Exception) {
            // Overwrite is best-effort (e.g. read-only filesystem edge cases);
            // the delete below still removes the plaintext-adjacent file.
        } finally {
            file.delete()
        }
    }

    private fun deleteKeystoreAliases() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            for (alias in KEYSTORE_ALIASES) {
                try {
                    if (ks.containsAlias(alias)) ks.deleteEntry(alias)
                } catch (_: Exception) {
                    // Best-effort per-alias — keep deleting the rest.
                }
            }
        } catch (_: Exception) {
            // Keystore unavailable entirely — nothing more we can do here.
        }
    }
}
