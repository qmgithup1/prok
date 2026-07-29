package com.systemsgo.hex.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.systemsgo.hex.R
import com.systemsgo.hex.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * DELAYED-RESET FEATURE: coordinates the "Reset Application Data" flow.
 *
 * Instead of wiping the app immediately when the user confirms the reset
 * (the old behaviour — see git history of AppLockScreen.kt's
 * `wipeAllAppData`), the destructive action is now scheduled 24 hours into
 * the future:
 *
 *  - [scheduleReset] enqueues a unique, one-time [DataResetWorker] via
 *    WorkManager with a 24h initial delay (anchored to SystemClock.elapsedRealtime,
 *    so it can't be delayed or skipped by changing the device's date/time),
 *    persists that anchor so the UI can show a tamper-resistant live countdown,
 *    and posts a persistent, ongoing notification with the same countdown
 *    (self-refreshed roughly once a minute by [NotificationUpdateWorker] —
 *    see that class for why we don't use Notification's built-in chronometer).
 *  - WorkManager persists enqueued work in its own database and automatically
 *    re-arms the underlying alarm after a device reboot (it ships its own
 *    manifest-registered BOOT_COMPLETED receiver), so no extra boot-receiver
 *    code is needed here for the *deletion* to survive a reboot. The
 *    *notification* itself does not survive a reboot (the system clears all
 *    notifications on boot) — [repostNotificationIfScheduled] is called from
 *    SystemsGoApp.onCreate() to re-show it on the next process start if a reset
 *    is still pending.
 *  - [cancelReset] is only ever called after the caller has already gated it
 *    behind a successful biometric/PIN confirmation (see
 *    SecurityConfirmDialog / DataResetBanner) — this object itself does not
 *    perform authentication.
 */
object DataResetManager {

    // Exposed so SecureWipe can also erase this file as part of the wipe.
    const val SCHEDULE_PREFS = "data_reset_schedule"
    private const val KEY_TRIGGER_AT_MS = "trigger_at_ms"

    // Tamper-resistant anchor: SystemClock.elapsedRealtime() is time-since-boot
    // and is NOT affected by the user (or malware) changing the wall-clock
    // date/time in Settings. WorkManager's own delay is already scheduled off
    // this same clock internally, so the *actual* wipe can't be delayed or
    // hastened by clock changes. This anchor lets the UI countdown make the
    // same guarantee, instead of trusting System.currentTimeMillis().
    private const val KEY_TRIGGER_AT_ELAPSED_REALTIME = "trigger_at_elapsed_realtime_ms"

    private const val UNIQUE_WORK_NAME = "secure_data_reset"
    private const val NOTIF_REFRESH_WORK_NAME = "secure_data_reset_notif_refresh"
    private const val CHANNEL_ID = "data_reset"
    private const val NOTIF_ID = 2001

    // How often the notification's countdown text is refreshed while a reset
    // is pending. A full-app-open user sees the real, per-second countdown in
    // DataResetBanner already; the notification just needs to stay roughly
    // current, so once a minute is plenty and keeps wakeups/battery use low.
    private val NOTIF_REFRESH_INTERVAL_MILLIS: Long = TimeUnit.MINUTES.toMillis(1)

    val DELAY_MILLIS: Long = TimeUnit.HOURS.toMillis(24)

    /** Schedules the delayed wipe and shows the persistent countdown notification. */
    fun scheduleReset(context: Context) {
        ensureNotificationPermission(context)

        val triggerAtMs = System.currentTimeMillis() + DELAY_MILLIS
        val triggerAtElapsedRealtime = SystemClock.elapsedRealtime() + DELAY_MILLIS

        context.openEncryptedPrefs(SCHEDULE_PREFS).edit()
            .putLong(KEY_TRIGGER_AT_MS, triggerAtMs)
            .putLong(KEY_TRIGGER_AT_ELAPSED_REALTIME, triggerAtElapsedRealtime)
            // commit(): synchronous — the WorkManager enqueue below must never
            // race ahead of this state being durably written.
            .commit()

        val request = OneTimeWorkRequestBuilder<DataResetWorker>()
            .setInitialDelay(DELAY_MILLIS, TimeUnit.MILLISECONDS)
            // No network/charging constraints on purpose: the wipe must run
            // even if the device is offline or on battery — this is a
            // security-critical deadline, not a deferrable background task.
            .setConstraints(Constraints.NONE)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )

        showOrUpdateNotification(context, remainingMillis(context) ?: DELAY_MILLIS)
        scheduleNotificationRefresh(context)
    }

    /**
     * Cancels a pending reset. Callers MUST have already verified the user's
     * identity (biometric or PIN) before invoking this — see
     * DataResetBanner.kt.
     */
    fun cancelReset(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(NOTIF_REFRESH_WORK_NAME)
        // .clear() removes both KEY_TRIGGER_AT_MS and KEY_TRIGGER_AT_ELAPSED_REALTIME.
        context.openEncryptedPrefs(SCHEDULE_PREFS).edit().clear().commit()
        dismissNotification(context)
    }

    /** The epoch-millis timestamp the wipe is scheduled for, or null if none is pending. */
    fun scheduledTriggerAtMillis(context: Context): Long? {
        val value = try {
            context.openEncryptedPrefs(SCHEDULE_PREFS).getLong(KEY_TRIGGER_AT_MS, 0L)
        } catch (_: Exception) {
            0L
        }
        return value.takeIf { it > 0L }
    }

    fun isScheduled(context: Context): Boolean = scheduledTriggerAtMillis(context) != null

    /**
     * Tamper-resistant remaining time for UI countdowns, in milliseconds.
     * Unlike [scheduledTriggerAtMillis] (wall-clock based, and therefore
     * spoofable by changing the device's date/time in Settings), this is
     * derived from [SystemClock.elapsedRealtime], the same monotonic,
     * boot-relative clock WorkManager itself schedules the real wipe against.
     * Returns null if no reset is currently pending.
     */
    fun remainingMillis(context: Context): Long? {
        val triggerElapsed = try {
            context.openEncryptedPrefs(SCHEDULE_PREFS)
                .getLong(KEY_TRIGGER_AT_ELAPSED_REALTIME, 0L)
        } catch (_: Exception) {
            0L
        }
        if (triggerElapsed <= 0L) return null
        return (triggerElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    /**
     * Called from SystemsGoApp.onCreate() on every process start. Notifications
     * do not survive a device reboot, but the underlying WorkManager job and
     * our persisted trigger anchor do — this just re-shows the countdown
     * notification and re-arms its periodic refresh so the user still sees
     * it after a reboot.
     */
    fun repostNotificationIfScheduled(context: Context) {
        remainingMillis(context)?.let { remaining ->
            showOrUpdateNotification(context, remaining)
            scheduleNotificationRefresh(context)
        }
    }

    /** Called by [DataResetWorker] once the wipe itself has run. */
    internal fun dismissNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }

    /**
     * Enqueues a single, tamper-resistant [NotificationUpdateWorker] run,
     * delayed by [NOTIF_REFRESH_INTERVAL_MILLIS]. The worker itself
     * re-schedules the next tick (chained one-shots), so this only ever
     * needs to be called once per "generation" of the countdown — on
     * [scheduleReset] and on [repostNotificationIfScheduled] after a reboot.
     */
    internal fun scheduleNotificationRefresh(context: Context) {
        val request = OneTimeWorkRequestBuilder<NotificationUpdateWorker>()
            .setInitialDelay(NOTIF_REFRESH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOTIF_REFRESH_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** Called by [NotificationUpdateWorker] to redraw the notification with a fresh countdown. */
    internal fun refreshNotificationText(context: Context, remainingMillis: Long) {
        showOrUpdateNotification(context, remainingMillis)
    }

    /**
     * Renders the persistent notification with our own countdown text derived
     * from [remainingMillis] (elapsedRealtime-anchored), rather than the
     * OS-drawn chronometer widget — see [NotificationUpdateWorker] for why.
     */
    private fun showOrUpdateNotification(context: Context, remainingMillis: Long) {
        createChannelIfNeeded(context)

        val openAppIntent = android.content.Intent(context, MainActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val countdownText = context.getString(
            R.string.data_reset_notif_text,
            formatRemainingForNotification(remainingMillis)
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.data_reset_notif_title))
            .setContentText(countdownText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            // Ongoing + not user-dismissable, per the "persistent notification" requirement.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification)
    }

    private fun formatRemainingForNotification(remainingMillis: Long): String {
        val totalMinutes = remainingMillis / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return String.format(java.util.Locale.getDefault(), "%dh %02dm", hours, minutes)
    }

    private fun ensureNotificationPermission(context: Context) {
        // Same fire-and-forget runtime-permission pattern already used for
        // POST_NOTIFICATIONS in RdpSessionActivity. Without this on API 33+,
        // the countdown notification silently fails to post — the scheduled
        // wipe itself (WorkManager) is unaffected either way.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val activity = context as? android.app.Activity ?: return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun createChannelIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.data_reset_notif_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.data_reset_notif_channel_desc)
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }
}
