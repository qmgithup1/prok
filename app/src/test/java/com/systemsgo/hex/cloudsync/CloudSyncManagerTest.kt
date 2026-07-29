package com.systemsgo.hex.cloudsync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CLOUD-SYNC FEATURE (Part 2/3).
 *
 * Covers [CloudSyncManager.determineSyncDirection] — the pure "should this
 * pass upload or download?" decision `syncNow()` is built around. Kept as a
 * standalone `companion object` function specifically so this can be a
 * plain JVM unit test (`./gradlew test`, no emulator/Robolectric) with no
 * need to construct a real [CloudSyncManager] (which requires a live
 * `Context`) or call a real [CloudSyncProvider] SDK — see
 * [FakeCloudSyncProvider] below, used only to demonstrate the decision
 * would be driven off whatever a real provider's `remoteMetadata()`
 * returns, same shape as [CloudSyncManager.syncNow] actually consumes it.
 *
 * Mirrors the narrow-scope, one-bug-class-per-test style of
 * `RdpProfileEnumsTest.kt`.
 */
class CloudSyncManagerTest {

    /** Minimal [CloudSyncProvider] stand-in — every call is a hardcoded/pending result, never real network I/O. */
    private class FakeCloudSyncProvider(
        override val provider: CloudProvider = CloudProvider.GOOGLE_DRIVE,
        private val remoteMetadata: CloudFileMetadata?,
    ) : CloudSyncProvider {
        override suspend fun isLinked(): Boolean = true
        override suspend fun accountLabel(): String = "fake@example.com"
        override suspend fun upload(bytes: ByteArray): CloudUploadOutcome =
            throw UnsupportedOperationException("not needed for direction tests")
        override suspend fun download(): CloudDownloadOutcome =
            throw UnsupportedOperationException("not needed for direction tests")
        override suspend fun remoteMetadata(): CloudFileMetadata? = remoteMetadata
        override suspend fun unlink() = Unit
    }

    @Test
    fun `no remote file yet uploads`() {
        val direction = CloudSyncManager.determineSyncDirection(
            remoteMetadata = null,
            lastSyncSuccessAtEpochMs = 0L,
        )
        assertEquals(CloudSyncManager.SyncDirection.UPLOAD_NO_REMOTE, direction)
    }

    @Test
    fun `remote newer than last successful sync downloads`() {
        val remote = CloudFileMetadata(remoteModifiedAtEpochMs = 2_000L, sizeBytes = 42L)
        val direction = CloudSyncManager.determineSyncDirection(
            remoteMetadata = remote,
            lastSyncSuccessAtEpochMs = 1_000L,
        )
        assertEquals(CloudSyncManager.SyncDirection.DOWNLOAD_NEWER_REMOTE, direction)
    }

    @Test
    fun `remote older than last successful sync uploads`() {
        val remote = CloudFileMetadata(remoteModifiedAtEpochMs = 500L, sizeBytes = 42L)
        val direction = CloudSyncManager.determineSyncDirection(
            remoteMetadata = remote,
            lastSyncSuccessAtEpochMs = 1_000L,
        )
        assertEquals(CloudSyncManager.SyncDirection.UPLOAD_UPDATE_REMOTE, direction)
    }

    @Test
    fun `remote exactly at last successful sync uploads, not downloads`() {
        // Boundary case: strictly-greater-than in determineSyncDirection()
        // means "same instant" must resolve to upload (local becomes the
        // authoritative state), never a no-op or a spurious download.
        val remote = CloudFileMetadata(remoteModifiedAtEpochMs = 1_000L, sizeBytes = 42L)
        val direction = CloudSyncManager.determineSyncDirection(
            remoteMetadata = remote,
            lastSyncSuccessAtEpochMs = 1_000L,
        )
        assertEquals(CloudSyncManager.SyncDirection.UPLOAD_UPDATE_REMOTE, direction)
    }

    @Test
    fun `never-synced device with an existing remote file downloads it`() {
        // lastSyncSuccessAtEpochMs = 0L is CloudSyncSettings' documented
        // "never synced" sentinel — any real remote timestamp must be
        // treated as newer than that, so a fresh device link pulls down
        // whatever's already in the cloud rather than clobbering it.
        val remote = CloudFileMetadata(remoteModifiedAtEpochMs = 1L, sizeBytes = 10L)
        val direction = CloudSyncManager.determineSyncDirection(
            remoteMetadata = remote,
            lastSyncSuccessAtEpochMs = 0L,
        )
        assertEquals(CloudSyncManager.SyncDirection.DOWNLOAD_NEWER_REMOTE, direction)
    }

    @Test
    fun `FakeCloudSyncProvider feeds remoteMetadata through unchanged`() = kotlinx.coroutines.runBlocking {
        // Demonstrates a fake CloudSyncProvider driving the same decision a
        // real GoogleDriveSyncProvider/DropboxSyncProvider's remoteMetadata()
        // would, without touching either SDK.
        val remote = CloudFileMetadata(remoteModifiedAtEpochMs = 5_000L, sizeBytes = 99L)
        val fakeProvider: CloudSyncProvider = FakeCloudSyncProvider(remoteMetadata = remote)

        val direction = CloudSyncManager.determineSyncDirection(
            remoteMetadata = fakeProvider.remoteMetadata(),
            lastSyncSuccessAtEpochMs = 4_000L,
        )
        assertEquals(CloudSyncManager.SyncDirection.DOWNLOAD_NEWER_REMOTE, direction)
    }
}
