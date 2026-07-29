package com.systemsgo.hex.cloudsync

/**
 * CLOUD-SYNC FEATURE (Part 1/3).
 *
 * Metadata about the backup file currently sitting in the linked cloud
 * account, as reported by the provider (Drive's `modifiedTime`/`size`,
 * Dropbox's `client_modified`/`size`, ...). Used by `CloudSyncManager`
 * (Part 2) to decide whether a "Sync Now" should upload (local backup is
 * newer / remote doesn't exist yet) or download (remote is newer than the
 * last sync this device did) instead of blindly overwriting one side.
 */
data class CloudFileMetadata(
    val remoteModifiedAtEpochMs: Long,
    val sizeBytes: Long,
)

/** Outcome of a [CloudSyncProvider.upload] call. */
sealed class CloudUploadOutcome {
    data class Success(val metadata: CloudFileMetadata) : CloudUploadOutcome()
    data class Failure(val error: CloudSyncError) : CloudUploadOutcome()
}

/** Outcome of a [CloudSyncProvider.download] call. */
sealed class CloudDownloadOutcome {
    data class Success(val bytes: ByteArray, val metadata: CloudFileMetadata) : CloudDownloadOutcome()
    /** No backup file exists in the linked account yet — not an error, just nothing to restore. */
    object NotFound : CloudDownloadOutcome()
    data class Failure(val error: CloudSyncError) : CloudDownloadOutcome()
}

/**
 * Everything that can go wrong talking to a cloud provider, collapsed to the
 * handful of categories the Settings UI (Part 3) actually needs to react to
 * differently — e.g. [AuthExpired] means "show a re-link button", while
 * [NetworkError] means "just say try again later". Provider implementations
 * (Part 2) are responsible for mapping their own SDK's exception types into
 * this set.
 */
sealed class CloudSyncError(val userMessage: String) {
    object NotLinked : CloudSyncError("This device isn't linked to a cloud account yet.")
    object AuthExpired : CloudSyncError("Cloud account access expired — please reconnect.")
    object NetworkError : CloudSyncError("Couldn't reach the cloud service. Check your connection and try again.")
    object Cancelled : CloudSyncError("Sync was cancelled.")
    object QuotaExceeded : CloudSyncError("The linked cloud account is out of storage space.")
    data class Unknown(val detail: String) : CloudSyncError(detail)
}

/**
 * CLOUD-SYNC FEATURE (Part 2/3).
 *
 * Result of a provider-specific account-linking flow —
 * [GoogleDriveSyncProvider.link] / [DropboxSyncProvider.completeLinkIfPending].
 * Deliberately separate from [CloudUploadOutcome]/[CloudDownloadOutcome]:
 * linking is a one-time, provider-specific UI interaction (account picker /
 * OAuth browser redirect) that Part 3's Settings UI drives directly, not
 * part of the common upload/download surface every [CloudSyncProvider]
 * shares and that [CloudSyncManager] talks to.
 */
sealed class CloudLinkResult {
    /**
     * [displayName] and [photoUrl] are best-effort, provider-supplied profile
     * info shown in the Settings UI's account card (Part 3-b) — Google Drive
     * populates both from the signed-in Google identity's ID token; Dropbox
     * has no equivalent in this app's PKCE flow, so it always passes both as
     * null and the UI falls back to a plain initials/icon avatar.
     */
    data class Success(
        val accountLabel: String,
        val displayName: String? = null,
        val photoUrl: String? = null,
    ) : CloudLinkResult()
    data class Failure(val error: CloudSyncError) : CloudLinkResult()
    object Cancelled : CloudLinkResult()
}
