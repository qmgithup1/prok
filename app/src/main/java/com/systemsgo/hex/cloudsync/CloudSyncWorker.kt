package com.systemsgo.hex.cloudsync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * CLOUD-SYNC FEATURE (Part 3/3).
 *
 * Background counterpart to Settings → Cloud Sync's manual "Sync Now"
 * button — both go through the exact same [CloudSyncManager.syncNow], so
 * this worker deliberately contains **no sync logic of its own**: it just
 * invokes that call and translates its [CloudSyncManager.SyncOutcome] into a
 * WorkManager [Result]. `syncNow()` itself already records the
 * attempt/success/error into [com.systemsgo.hex.data.repository.CloudSyncPreferences]
 * regardless of who called it, so Settings shows an accurate "last synced"/
 * "last error" even for a run the user never saw happen.
 *
 * Unlike [com.systemsgo.hex.security.DataResetWorker] /
 * [com.systemsgo.hex.security.NotificationUpdateWorker] — which reach a
 * plain `object` (`DataResetManager`) via `applicationContext` and need no
 * DI — this worker depends on [CloudSyncManager], a real `@Singleton
 * @Inject` Hilt binding (it in turn depends on the multibound
 * `Map<CloudProvider, CloudSyncProvider>` from `di/CloudSyncModule.kt`).
 * There is no `@HiltWorker` precedent elsewhere in this project to follow,
 * so this introduces the standard androidx.hilt wiring for it:
 * `@HiltWorker` + `@AssistedInject` here, and `HiltWorkerFactory` wired into
 * `SystemsGoApp`'s `Configuration.Provider` (see that class). Workers that
 * aren't `@HiltWorker` (the two above) are unaffected — `HiltWorkerFactory`
 * only intercepts classes it recognizes and falls through to WorkManager's
 * default reflection-based instantiation for everything else.
 */
@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cloudSyncManager: CloudSyncManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return when (val outcome = cloudSyncManager.syncNow()) {
            is CloudSyncManager.SyncOutcome.Uploaded,
            is CloudSyncManager.SyncOutcome.Downloaded,
            -> Result.success()

            is CloudSyncManager.SyncOutcome.Failure -> mapFailureToResult(outcome.error)
        }
    }

    /**
     * Not every [CloudSyncError] is worth WorkManager's default exponential
     * backoff retry:
     * - [CloudSyncError.NotLinked] — the user unlinked cloud sync since this
     *   run was scheduled (or never linked it). [CloudSyncScheduler] cancels
     *   the periodic work on unlink/disable, but a run already in flight at
     *   that exact moment could still land here — retrying forever for a
     *   provider that's now gone would just burn battery for nothing.
     * - [CloudSyncError.AuthExpired] — retrying immediately re-fails the
     *   same way until the user re-links from Settings; [CloudSyncManager]
     *   already recorded this into `CloudSyncPreferences` so Settings shows
     *   the "reconnect needed" state next time it's opened (see Part 2's
     *   note on `UserRecoverableAuthIOException`) — no in-app dialog is
     *   popped from a background worker.
     * - Everything else (network errors, transient provider-side failures)
     *   is exactly what `Result.retry()` + WorkManager's default backoff
     *   policy exists for.
     */
    private fun mapFailureToResult(error: CloudSyncError): Result = when (error) {
        is CloudSyncError.NotLinked, is CloudSyncError.AuthExpired -> Result.failure()
        else -> Result.retry()
    }
}
