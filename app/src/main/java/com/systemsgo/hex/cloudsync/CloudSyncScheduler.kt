package com.systemsgo.hex.cloudsync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.systemsgo.hex.data.repository.CloudSyncSettings
import java.util.concurrent.TimeUnit

/**
 * CLOUD-SYNC FEATURE (Part 3/3).
 *
 * Owns the periodic [CloudSyncWorker] enqueue/cancel — the only two
 * WorkManager operations this feature needs, called from two places:
 *  1. [com.systemsgo.hex.SystemsGoApp], which collects
 *     [com.systemsgo.hex.data.repository.CloudSyncPreferences.settingsFlow]
 *     for the app's whole lifetime and calls [applySettings] on every
 *     emission — this is what makes "toggle auto-sync off" or "change the
 *     interval" in Settings (Part 3's UI) take effect immediately without
 *     the UI layer needing to know anything about WorkManager itself.
 *  2. Settings' "Disconnect" action, indirectly: unlinking clears
 *     `autoSyncEnabled` as a side effect of
 *     `CloudSyncPreferences.clearLink()` (see that class), which the same
 *     `settingsFlow` collector then turns into a cancel via [applySettings]
 *     — no separate "did they just disconnect?" branch is needed here.
 *
 * Deliberately an `object`, same shape as
 * [com.systemsgo.hex.security.DataResetManager] — nothing here holds
 * per-instance state; [WorkManager.getInstance] is itself already a
 * process-wide singleton.
 */
object CloudSyncScheduler {

    /** Stable name so re-enqueuing with the same settings is a no-op and changed settings replace it in place. */
    private const val UNIQUE_WORK_NAME = "cloud_sync_periodic_work"

    /**
     * Pure decision of what WorkManager action a given [CloudSyncSettings]
     * implies — split out from [applySettings] itself so it's testable with
     * plain JUnit (no `Context`, no real `WorkManager`), same reasoning as
     * [CloudSyncManager.determineSyncDirection]. See
     * `CloudSyncSchedulerTest.kt`.
     */
    internal sealed class SchedulingAction {
        /** [intervalMinutes] is already clamped to [CloudSyncSettings.MIN_AUTO_SYNC_INTERVAL_MINUTES]. */
        data class Enqueue(val intervalMinutes: Int) : SchedulingAction()
        object Cancel : SchedulingAction()
    }

    internal fun determineSchedulingAction(settings: CloudSyncSettings): SchedulingAction {
        // Auto-sync toggled off, or no provider linked at all (e.g. right
        // after Disconnect clears both linkedProvider and autoSyncEnabled
        // together) — either alone is enough to mean "nothing should run in
        // the background".
        if (!settings.autoSyncEnabled || settings.linkedProvider == null) {
            return SchedulingAction.Cancel
        }
        val interval = settings.autoSyncIntervalMinutes
            .coerceAtLeast(CloudSyncSettings.MIN_AUTO_SYNC_INTERVAL_MINUTES)
        return SchedulingAction.Enqueue(interval)
    }

    /** Call on every [CloudSyncSettings] emission — see class doc. Cheap/idempotent to call repeatedly with unchanged settings. */
    fun applySettings(context: Context, settings: CloudSyncSettings) {
        when (val action = determineSchedulingAction(settings)) {
            is SchedulingAction.Enqueue -> enqueue(context, action.intervalMinutes)
            SchedulingAction.Cancel -> cancel(context)
        }
    }

    private fun enqueue(context: Context, intervalMinutes: Int) {
        // NetworkType.CONNECTED (not UNMETERED): the backup payload is a
        // single small encrypted file (connection profiles, not session
        // recordings/frame buffers), so gating an already-infrequent
        // (>=15 min) background sync behind Wi-Fi-only would just mean it
        // silently never runs for a user who's mobile-data-only that day —
        // not worth it for something this size. Revisit only if a future
        // backup format grows to carry large attachments.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(
            intervalMinutes.toLong(), TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        // UPDATE (not KEEP): a changed interval must actually take effect —
        // WorkManager has no "update the period of an existing periodic
        // request" call, so the only way to change it is replace the whole
        // request. Since the new request carries the same UNIQUE_WORK_NAME,
        // this is also exactly how "auto-sync just got turned back on"
        // re-establishes the schedule after a prior Cancel.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
