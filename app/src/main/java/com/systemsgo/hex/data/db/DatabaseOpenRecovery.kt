package com.systemsgo.hex.data.db

import android.content.Context
import android.util.Log
import android.database.sqlite.SQLiteDatabaseCorruptException

/**
 * Recovers from the case where the SQLCipher passphrase currently held by
 * [DatabaseKeyProvider] no longer matches the key the on-disk database was
 * encrypted with.
 *
 * This happens whenever the Android Keystore key wrapping the stored
 * passphrase is lost or invalidated (factory reset of the Keystore, restoring
 * app data onto a different device/ROM, forced Keystore re-enrollment, etc.).
 * [DatabaseKeyProvider.getOrCreate] reacts to that by generating a brand-new
 * passphrase — but the existing database file is still encrypted with the
 * *old*, now-unrecoverable key, so SQLCipher can no longer open it.
 *
 * SQLCipher cannot distinguish "wrong key" from "the file is genuinely
 * corrupt" — both surface as the identical low-level SQLite error
 * (SQLITE_NOTADB, "file is not a database"). This is intentional on
 * SQLCipher's part: it stops an attacker from using differing error
 * behaviour to brute-force the key. Either way, the correct and only
 * recovery is the same — the file cannot be read with the key we have, so it
 * is deleted (together with its -wal/-shm companions) and a fresh, empty,
 * encrypted database is created with the current key.
 *
 * Detection is deliberately narrow: only that specific "cannot open" SQLite
 * signature triggers deletion. Any other failure — disk I/O errors, a locked
 * file, a missing/broken Room [androidx.room.migration.Migration] — is
 * rethrown untouched so real bugs are never silently masked as data loss.
 */
object DatabaseOpenRecovery {

    private const val TAG = "DatabaseOpenRecovery"

    /**
     * Builds and opens [SystemsGoDatabase], recovering automatically if the
     * existing database file cannot be decrypted with the key [buildDatabase]
     * is using.
     *
     * @param buildDatabase Builds a new Room/SupportOpenHelperFactory-backed
     *   [SystemsGoDatabase] instance using the current passphrase. Must be safe
     *   to call more than once (e.g. it should take a fresh defensive copy of
     *   the passphrase each time rather than consuming/zeroing a shared one).
     */
    fun openWithRecovery(context: Context, buildDatabase: () -> SystemsGoDatabase): SystemsGoDatabase {
        var database = buildDatabase()
        try {
            // Room/SupportSQLiteOpenHelper opens the underlying SQLCipher
            // connection lazily, on first real use. Force that to happen now,
            // synchronously and inside this try block, so an invalid-key
            // failure surfaces here — where it can be recovered from — instead
            // of crashing later on an arbitrary DAO call, possibly on a
            // background coroutine with no crash handler.
            database.openHelper.writableDatabase
            return database
        } catch (e: Throwable) {
            if (!isUndecryptableDatabaseError(e)) {
                // Not a key/corruption signature (e.g. a Room migration error,
                // disk-full, permissions) — do not touch the database file,
                // just propagate so the real problem is visible.
                throw e
            }

            // No sensitive data (key material, profile contents, file paths)
            // is logged — only the exception type, which is what makes this
            // safe to leave enabled in production builds for debugging.
            Log.w(
                TAG,
                "Database could not be opened with the current encryption key " +
                    "(${e.javaClass.simpleName}); treating as invalid/lost key and recreating database."
            )

            closeQuietly(database)
            val deletedCleanly = deleteDatabaseFiles(context)
            Log.i(
                TAG,
                "Removed unreadable database files (clean=$deletedCleanly); " +
                    "creating a new encrypted database with the current key."
            )

            database = buildDatabase()
            // The file no longer exists, so SQLCipher will create it fresh
            // with the current key. If this second attempt still fails, it is
            // not a key problem (we just deleted the old file) — let it
            // propagate rather than looping or deleting anything further.
            database.openHelper.writableDatabase
            Log.i(TAG, "Database recreated successfully after key-loss recovery.")
            return database
        }
    }

    private fun closeQuietly(database: SystemsGoDatabase) {
        try {
            database.close()
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring error while closing unreadable database: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Deletes the main database file plus its -wal and -shm companions.
     * Returns true only if every file that existed was actually deleted.
     */
    private fun deleteDatabaseFiles(context: Context): Boolean {
        var allDeleted = true
        for (suffix in arrayOf("", "-wal", "-shm")) {
            val file = context.getDatabasePath(SystemsGoDatabase.DATABASE_NAME + suffix)
            if (file.exists() && !file.delete()) {
                allDeleted = false
                Log.w(TAG, "Failed to delete stale database file: ${file.name}")
            }
        }
        return allDeleted
    }

    /**
     * True only for the specific SQLCipher/SQLite signature produced when a
     * database cannot be decrypted with the supplied key (equivalently, is
     * corrupt beyond use). False for everything else — including Room's own
     * migration exceptions — so unrelated failures are never mistaken for a
     * key problem and the database is never deleted because of them.
     */
    private fun isUndecryptableDatabaseError(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is SQLiteDatabaseCorruptException) return true
            val message = cause.message?.lowercase() ?: ""
            if (message.contains("file is not a database") ||
                message.contains("not a database") ||
                message.contains("hmac")
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
}
