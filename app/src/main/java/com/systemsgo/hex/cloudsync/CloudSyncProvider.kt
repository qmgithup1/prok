package com.systemsgo.hex.cloudsync

/**
 * CLOUD-SYNC FEATURE (Part 1/3).
 *
 * Common surface both cloud backends are driven through. `CloudSyncManager`
 * (Part 2) and the Settings UI (Part 3) only ever talk to this interface —
 * neither needs to know whether it's holding a `GoogleDriveSyncProvider` or
 * a `DropboxSyncProvider` underneath. This mirrors how [ConnectionBackupManager]
 * is the single place that knows how to build/restore an encrypted backup
 * blob; a [CloudSyncProvider] is the single place that knows how to move
 * that already-encrypted blob to/from one specific cloud backend.
 *
 * Implementation note for Part 2: every provider stores exactly one file —
 * the encrypted connections backup — in an app-private location the user
 * can't casually browse into and delete from a generic file manager:
 *  - Drive: the `appDataFolder` special folder (invisible in the regular
 *    Drive UI/app, `https://developers.google.com/workspace/drive/api/guides/appdata`).
 *  - Dropbox: an app-folder-permission app (`/Apps/SystemsGo/...`), or the
 *    equivalent restricted-scope location.
 * Neither provider needs "browse the user's whole Drive/Dropbox" scope —
 * only `drive.appdata` (Drive) / `files.content.write`+`files.content.read`
 * scoped to the app folder (Dropbox). Least-privilege, same reasoning as
 * every permission already declared in AndroidManifest.xml.
 */
interface CloudSyncProvider {

    val provider: CloudProvider

    /** True once the user has linked an account and the stored token/session is still usable. */
    suspend fun isLinked(): Boolean

    /** Human-readable label for the linked account (usually an email), or null if unlinked. */
    suspend fun accountLabel(): String?

    /** Uploads [bytes] (already AES-256-GCM encrypted by [ConnectionBackupManager]) to this provider. */
    suspend fun upload(bytes: ByteArray): CloudUploadOutcome

    /** Downloads the encrypted backup currently stored with this provider, if any. */
    suspend fun download(): CloudDownloadOutcome

    /** Metadata for the remote backup file without downloading its contents — used for conflict checks. */
    suspend fun remoteMetadata(): CloudFileMetadata?

    /** Revokes/forgets the local session so [isLinked] returns false again. Does not delete the remote file. */
    suspend fun unlink()
}
