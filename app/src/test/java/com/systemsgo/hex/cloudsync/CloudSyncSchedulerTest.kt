package com.systemsgo.hex.cloudsync

import com.systemsgo.hex.data.repository.CloudSyncSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CLOUD-SYNC FEATURE (Part 3/3).
 *
 * Covers [CloudSyncScheduler.determineSchedulingAction] — the pure
 * "enqueue, cancel, or leave alone" decision [CloudSyncScheduler.applySettings]
 * is built around, same reasoning as `CloudSyncManagerTest.kt` for
 * `determineSyncDirection`: a plain JVM test with no real `Context` or
 * `WorkManager` instance, since [CloudSyncScheduler.applySettings] itself
 * can't be unit-tested without a Robolectric/instrumented environment.
 */
class CloudSyncSchedulerTest {

    @Test
    fun `auto-sync disabled cancels even with a linked provider`() {
        val settings = CloudSyncSettings(
            linkedProvider = CloudProvider.GOOGLE_DRIVE,
            autoSyncEnabled = false,
            autoSyncIntervalMinutes = 60,
        )
        assertEquals(
            CloudSyncScheduler.SchedulingAction.Cancel,
            CloudSyncScheduler.determineSchedulingAction(settings),
        )
    }

    @Test
    fun `no linked provider cancels even if autoSyncEnabled is stale-true`() {
        // Defensive case: CloudSyncPreferences.clearLink() removes
        // AUTO_SYNC_ENABLED too, so linkedProvider=null with
        // autoSyncEnabled=true shouldn't normally be observable — but the
        // scheduler must never enqueue background work with no provider to
        // sync against regardless.
        val settings = CloudSyncSettings(
            linkedProvider = null,
            autoSyncEnabled = true,
            autoSyncIntervalMinutes = 60,
        )
        assertEquals(
            CloudSyncScheduler.SchedulingAction.Cancel,
            CloudSyncScheduler.determineSchedulingAction(settings),
        )
    }

    @Test
    fun `linked and enabled enqueues at the configured interval`() {
        val settings = CloudSyncSettings(
            linkedProvider = CloudProvider.DROPBOX,
            autoSyncEnabled = true,
            autoSyncIntervalMinutes = 120,
        )
        assertEquals(
            CloudSyncScheduler.SchedulingAction.Enqueue(120),
            CloudSyncScheduler.determineSchedulingAction(settings),
        )
    }

    @Test
    fun `interval below the minimum is clamped up before enqueuing`() {
        // CloudSyncPreferences.updateAutoSyncIntervalMinutes() already
        // clamps on write, but the scheduler clamps again defensively in
        // case a raw CloudSyncSettings ever reaches it some other way
        // (e.g. a future migration writing an old/unclamped value directly).
        val settings = CloudSyncSettings(
            linkedProvider = CloudProvider.GOOGLE_DRIVE,
            autoSyncEnabled = true,
            autoSyncIntervalMinutes = 5,
        )
        assertEquals(
            CloudSyncScheduler.SchedulingAction.Enqueue(CloudSyncSettings.MIN_AUTO_SYNC_INTERVAL_MINUTES),
            CloudSyncScheduler.determineSchedulingAction(settings),
        )
    }

    @Test
    fun `default interval is used unchanged when already at the minimum`() {
        val settings = CloudSyncSettings(
            linkedProvider = CloudProvider.GOOGLE_DRIVE,
            autoSyncEnabled = true,
            autoSyncIntervalMinutes = CloudSyncSettings.MIN_AUTO_SYNC_INTERVAL_MINUTES,
        )
        assertEquals(
            CloudSyncScheduler.SchedulingAction.Enqueue(CloudSyncSettings.MIN_AUTO_SYNC_INTERVAL_MINUTES),
            CloudSyncScheduler.determineSchedulingAction(settings),
        )
    }
}
