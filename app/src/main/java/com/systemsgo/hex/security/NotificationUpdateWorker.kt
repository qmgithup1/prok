package com.systemsgo.hex.security

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * DELAYED-RESET FEATURE: keeps the persistent countdown notification's text
 * in sync with [DataResetManager.remainingMillis].
 *
 * We deliberately do NOT use Notification's built-in chronometer
 * (setUsesChronometer/setWhen) for this: that widget is drawn by the OS using
 * the device's wall-clock time, so a user who winds the system date/time
 * backward would see a stale/incorrect countdown even though the underlying
 * WorkManager wipe job (scheduled off SystemClock.elapsedRealtime) is
 * completely unaffected. Rendering our own text from remainingMillis() keeps
 * what's displayed honest and consistent with what will actually happen.
 *
 * This worker re-enqueues itself (via [DataResetManager.scheduleNotificationRefresh])
 * roughly once a minute for as long as a reset is pending — a full second-by-second
 * tick isn't warranted for a static notification and would just burn battery/wakeups.
 * The in-app [com.systemsgo.hex.ui.screens.DataResetBanner] already provides the
 * live per-second countdown while the app is open.
 */
class NotificationUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val remaining = DataResetManager.remainingMillis(applicationContext)
        if (remaining == null) {
            // Reset was cancelled (or already completed) since this was scheduled.
            return Result.success()
        }

        DataResetManager.refreshNotificationText(applicationContext, remaining)

        if (remaining > 0L) {
            DataResetManager.scheduleNotificationRefresh(applicationContext)
        }
        return Result.success()
    }
}
