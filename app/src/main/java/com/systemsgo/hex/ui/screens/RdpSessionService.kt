package com.systemsgo.hex.ui.screens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.systemsgo.hex.R
import com.systemsgo.hex.data.repository.AppSettingsRepository
import com.systemsgo.hex.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service that keeps the remote session alive when the user
 * leaves the app. The notification lets them tap back to the session.
 */
@AndroidEntryPoint
class RdpSessionService : Service() {

    @Inject lateinit var settingsRepository: AppSettingsRepository

    companion object {
        const val CHANNEL_ID   = "rdp_session"
        const val NOTIF_ID     = 1001
        const val EXTRA_HOST   = "host"
        const val ACTION_STOP  = "com.systemsgo.hex.STOP_SESSION"

        fun start(context: android.content.Context, host: String) {
            val intent = android.content.Intent(context, RdpSessionService::class.java)
                .putExtra(EXTRA_HOST, host)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(android.content.Intent(context, RdpSessionService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FALSE-NOTIFICATION FIX: this service holds no reference to the actual
        // native RDP handle — that lives only in RdpSessionActivity's process
        // memory. If the system kills the whole process, the real session is
        // gone with it; there is nothing left here to reconnect to or reflect.
        // START_STICKY used to make Android restart *this service alone* with
        // intent == null, and the code below would build/post the "Connected"
        // background-session notification unconditionally — a persistent,
        // tappable notification claiming a live session that no longer exists.
        // A null intent only ever happens on such a system-initiated restart
        // (a real caller always goes through start()/stop() with extras), so
        // treat it as "nothing to resume" and stop immediately without ever
        // showing the notification.
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP) {
            // BUG-COMPAT-1 FIX: Service.stopForeground(int) was only added in API 33.
            // Calling stopForeground(STOP_FOREGROUND_REMOVE) directly on API 26–32 resolves
            // to the int overload which doesn't exist at runtime → NoSuchMethodError crash.
            // ServiceCompat.stopForeground() handles the API split internally.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            releaseWakeLock()
            stopSelf()
            return START_NOT_STICKY
        }

        // BUGFIX: hardcoded "Remote Session" replaced with a localized string resource
        // — this fallback title is shown in the persistent system notification.
        val host = intent.getStringExtra(EXTRA_HOST) ?: getString(R.string.notif_remote_session_fallback)
        createNotificationChannel()

        val returnIntent = Intent(this, RdpSessionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingReturn = PendingIntent.getActivity(
            this, 0, returnIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, RdpSessionService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.background_session_title))
            .setContentText(getString(R.string.background_session_text, host))
            // BUG 7 FIX: ic_menu_compass is an internal Android drawable with no
            // guaranteed availability or size across versions. Use the app's own
            // launcher icon for a consistent, branded notification icon.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingReturn)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), pendingStop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        // OEM-COMPAT FIX: a foreground service alone keeps the *process* alive,
        // but on many devices the CPU is still allowed to suspend (Doze) once
        // the screen turns off, which stalls the blocking socket reads used by
        // the RDP/VNC/SSH clients — the session looks "frozen" instead of
        // disconnected. A partial wake lock keeps the CPU awake (screen can
        // still turn off) for as long as the session notification is showing.
        // The 6h timeout is a safety net only — it is always released
        // explicitly on disconnect/stop, never relied upon to fire normally.
        //
        // POWER FIX: this unconditionally blocked Doze for the entire background
        // session (up to 6h) even though the session is invisible the whole time.
        // If the user has opted into "Background power saving" in Settings, skip
        // the wake lock entirely and rely on the socket's TcpKeepAlive (already
        // enabled in systemsgo_jni.c / the VNC+SSH clients) to notice a dead
        // connection and keep it alive across Doze — a background session has no
        // frame-latency requirement, so the occasional Doze-induced delay in
        // reading the next update is not user-visible.
        if (!settingsRepository.isBackgroundPowerSavingEnabled()) {
            acquireWakeLock()
        }

        // FALSE-NOTIFICATION FIX: START_STICKY previously meant "if the system
        // kills this process, restart just this service and call
        // onStartCommand(null, ...) again" — which is exactly the path that
        // produced the stale "Connected" notification with no real session
        // behind it (see the null-intent guard above). Since this service
        // cannot resume a killed native session on its own, there is no
        // legitimate case where an automatic restart is useful; the real
        // lifecycle (start/stop) is always driven explicitly by
        // RdpSessionActivity via start()/stop().
        return START_NOT_STICKY
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SystemsGo:RdpSessionService"
        ).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L /* 6h safety timeout */)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.background_session_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            // CRIT-NEW-4 FIX: description was hardcoded English — Arabic users saw
            // English text in system notification settings.  Use string resource so
            // localisation applies correctly.
            description = getString(R.string.background_session_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
