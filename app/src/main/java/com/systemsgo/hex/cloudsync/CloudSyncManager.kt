package com.systemsgo.hex.cloudsync

import android.content.Context
import com.systemsgo.hex.data.backup.ConnectionBackupManager
import com.systemsgo.hex.data.repository.CloudSyncPreferences
import com.systemsgo.hex.security.SyncPassphraseStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CLOUD-SYNC FEATURE (Part 2/3).
 *
 * Coordinates one "Sync Now" pass between [ConnectionBackupManager] (builds/
 * restores the encrypted backup bytes), the currently linked
 * [CloudSyncProvider] (moves those bytes to/from Drive or Dropbox — resolved
 * dynamically via the `Map<CloudProvider, CloudSyncProvider>` Hilt
 * multibinding in `di/CloudSyncModule.kt`), and [SyncPassphraseStore] (the
 * backup password, since [syncNow] has no user present to ask for one — see
 * [SyncPassphraseStore]'s own doc for why that matters).
 *
 * This is the single call site Part 3's manual "Sync Now" button AND its
 * WorkManager background job both go through — neither needs to know any of
 * the direction-decision or error-mapping logic below.
 */
@Singleton
class CloudSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providers: Map<CloudProvider, @JvmSuppressWildcards CloudSyncProvider>,
    private val backupManager: ConnectionBackupManager,
    private val cloudSyncPreferences: CloudSyncPreferences,
) {

    /** Result of one [syncNow] pass. */
    sealed class SyncOutcome {
        /** No remote backup existed yet (or the remote wasn't newer) — the local backup was uploaded. */
        data class Uploaded(val metadata: CloudFileMetadata) : SyncOutcome()
        /** The remote backup was newer than this device's last successful sync — it was downloaded and merged in. */
        data class Downloaded(
            val importResult: ConnectionBackupManager.ImportResult,
            val metadata: CloudFileMetadata,
        ) : SyncOutcome()
        data class Failure(val error: CloudSyncError) : SyncOutcome()
    }

    /** Which way one [syncNow] pass should move data. */
    internal enum class SyncDirection {
        /** No remote file at all yet. */
        UPLOAD_NO_REMOTE,
        /** A remote file exists and is newer than the last successful sync recorded locally. */
        DOWNLOAD_NEWER_REMOTE,
        /** A remote file exists but this device's local backup is at least as current. */
        UPLOAD_UPDATE_REMOTE,
    }

    companion object {
        /**
         * Pure decision logic, deliberately a standalone function (not an
         * instance method) so it's testable with plain JUnit — no Context,
         * no Hilt graph, no real Drive/Dropbox SDK — see
         * `app/src/test/java/com/systemsgo/hex/cloudsync/CloudSyncManagerTest.kt`.
         *
         * No remote file → upload. A remote file newer than the last
         * successful sync this device recorded → download. Otherwise →
         * upload (local is caught up or ahead, so it becomes the new remote
         * state). Deliberately compares against
         * [CloudSyncSettings.lastSyncSuccessAtEpochMs] — the last time *this
         * device* completed a sync — rather than any timestamp on the local
         * backup file itself, since a local edit doesn't necessarily bump a
         * file-modified time this class can see, but it always happens
         * after this device's last successful sync.
         */
        internal fun determineSyncDirection(
            remoteMetadata: CloudFileMetadata?,
            lastSyncSuccessAtEpochMs: Long,
        ): SyncDirection = when {
            remoteMetadata == null -> SyncDirection.UPLOAD_NO_REMOTE
            remoteMetadata.remoteModifiedAtEpochMs > lastSyncSuccessAtEpochMs -> SyncDirection.DOWNLOAD_NEWER_REMOTE
            else -> SyncDirection.UPLOAD_UPDATE_REMOTE
        }
    }

    suspend fun syncNow(): SyncOutcome {
        val settings = cloudSyncPreferences.currentSettingsSnapshot()

        val linkedProvider = settings.linkedProvider
            ?: return fail(CloudSyncError.NotLinked)

        val provider = providers[linkedProvider]
            ?: return fail(CloudSyncError.Unknown("No CloudSyncProvider registered for $linkedProvider"))

        val passphrase = SyncPassphraseStore.getPassphrase(context)
            ?: return fail(CloudSyncError.Unknown("No cloud sync passphrase set — open Settings → Cloud Sync to set one."))

        return try {
            val remoteMetadata = provider.remoteMetadata()
            when (determineSyncDirection(remoteMetadata, settings.lastSyncSuccessAtEpochMs)) {
                SyncDirection.UPLOAD_NO_REMOTE, SyncDirection.UPLOAD_UPDATE_REMOTE ->
                    performUpload(provider, passphrase)
                SyncDirection.DOWNLOAD_NEWER_REMOTE ->
                    performDownload(provider, passphrase)
            }
        } catch (e: Exception) {
            // Defense in depth: every provider is responsible for mapping
            // its own SDK's exceptions into CloudSyncError (see
            // GoogleDriveSyncProvider/DropboxSyncProvider's mapExceptionToError()),
            // but a raw exception must never reach this method's caller
            // regardless — e.g. from ConnectionBackupManager.buildBackupBytes()/
            // restoreFromBytes() itself (a corrupt/invalid-password backup,
            // an I/O error building the local payload).
            fail(mapUnexpectedException(e))
        }
    }

    private suspend fun performUpload(provider: CloudSyncProvider, passphrase: String): SyncOutcome {
        val (bytes, _) = backupManager.buildBackupBytes(passphrase)
        return when (val outcome = provider.upload(bytes)) {
            is CloudUploadOutcome.Success -> {
                cloudSyncPreferences.recordSyncSuccess()
                SyncOutcome.Uploaded(outcome.metadata)
            }
            is CloudUploadOutcome.Failure -> fail(outcome.error)
        }
    }

    private suspend fun performDownload(
        provider: CloudSyncProvider,
        passphrase: String,
    ): SyncOutcome = when (val outcome = provider.download()) {
        is CloudDownloadOutcome.Success -> {
            val importResult = try {
                // ConnectionBackupManager.restoreFromBytes() already dedupes
                // by id/signature (see profileDedupeKey()), so a downloaded
                // backup that overlaps with what's already local is a safe
                // no-op merge, not a duplicate-creating overwrite.
                backupManager.restoreFromBytes(outcome.bytes, passphrase)
            } catch (e: ConnectionBackupManager.BackupException.InvalidPassword) {
                return fail(CloudSyncError.Unknown("The cloud backup couldn't be decrypted with the stored sync passphrase."))
            } catch (e: ConnectionBackupManager.BackupException.CorruptFile) {
                return fail(CloudSyncError.Unknown(e.message ?: "The cloud backup file is corrupted."))
            }
            cloudSyncPreferences.recordSyncSuccess()
            SyncOutcome.Downloaded(importResult, outcome.metadata)
        }
        // The remote file existed a moment ago (we just read its metadata to
        // get here) but is gone now — treat like "nothing to sync yet" and
        // upload the local state instead of failing outright.
        CloudDownloadOutcome.NotFound -> performUpload(provider, passphrase)
        is CloudDownloadOutcome.Failure -> fail(outcome.error)
    }

    private suspend fun fail(error: CloudSyncError): SyncOutcome.Failure {
        cloudSyncPreferences.recordSyncError(error.userMessage)
        return SyncOutcome.Failure(error)
    }

    private fun mapUnexpectedException(e: Exception): CloudSyncError = when (e) {
        is java.io.IOException -> CloudSyncError.NetworkError
        else -> CloudSyncError.Unknown(e.message ?: "Unexpected cloud sync error")
    }
}
