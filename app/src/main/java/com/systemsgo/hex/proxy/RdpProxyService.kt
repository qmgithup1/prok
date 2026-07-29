package com.systemsgo.hex.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.systemsgo.hex.R
import com.systemsgo.hex.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * PROXY-RELAY FEATURE: foreground service that owns an [RdpProxyRelay]'s
 * lifecycle. Simpler than [com.systemsgo.hex.shadow.ShadowScreenCaptureService]
 * in one respect — no MediaProjection consent dialog is needed here, so
 * (unlike that service) this one CAN be started directly from
 * [RdpProxyActivity] without an ActivityResult round-trip. `connectedDevice`
 * is the correct foregroundServiceType (same as `RdpSessionService` for a
 * normal outbound connection — this service is, functionally, running BOTH
 * an inbound and an outbound RDP session at once).
 */
class RdpProxyService : Service() {

    companion object {
        private const val TAG = "RdpProxyService"
        const val CHANNEL_ID = "rdp_proxy_relay"
        const val NOTIF_ID = 3001

        private const val ACTION_STOP = "com.systemsgo.hex.proxy.ACTION_STOP"
        private const val EXTRA_LISTEN_PORT = "listenPort"
        private const val EXTRA_TARGET_HOST = "targetHost"
        private const val EXTRA_TARGET_PORT = "targetPort"
        private const val EXTRA_TARGET_USERNAME = "targetUsername"
        private const val EXTRA_TARGET_PASSWORD = "targetPassword"
        private const val EXTRA_TARGET_DOMAIN = "targetDomain"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_INBOUND_USERNAME = "inboundUsername"
        private const val EXTRA_INBOUND_PASSWORD = "inboundPassword"

        /** Cross-process/Activity visibility into whether a relay is
         * currently active — same shape as ShadowScreenCaptureService not
         * exposing this either; RdpProxyActivity tracks its own local
         * running state instead, initialized from this on launch. */
        @Volatile var isRunning: Boolean = false
            private set

        fun start(
            context: Context,
            listenPort: Int,
            targetHost: String, targetPort: Int,
            targetUsername: String, targetPassword: String, targetDomain: String,
            width: Int = 1280, height: Int = 800,
            /** NLA-SERVER FEATURE: credentials required from anyone
             * connecting to THIS device's inbound listener — unrelated to
             * targetUsername/targetPassword above (those authenticate
             * OUT to the real RDP host). Null/blank disables inbound
             * NLA — see AFreeRdpServerBridge's class doc. */
            inboundUsername: String? = null, inboundPassword: String? = null,
        ) {
            val intent = Intent(context, RdpProxyService::class.java)
                .putExtra(EXTRA_LISTEN_PORT, listenPort)
                .putExtra(EXTRA_TARGET_HOST, targetHost)
                .putExtra(EXTRA_TARGET_PORT, targetPort)
                .putExtra(EXTRA_TARGET_USERNAME, targetUsername)
                .putExtra(EXTRA_TARGET_PASSWORD, targetPassword)
                .putExtra(EXTRA_TARGET_DOMAIN, targetDomain)
                .putExtra(EXTRA_WIDTH, width)
                .putExtra(EXTRA_HEIGHT, height)
                .putExtra(EXTRA_INBOUND_USERNAME, inboundUsername)
                .putExtra(EXTRA_INBOUND_PASSWORD, inboundPassword)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RdpProxyService::class.java).setAction(ACTION_STOP))
        }
    }

    private var serviceScope: CoroutineScope? = null
    private val relay = RdpProxyRelay()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val listenPort = intent.getIntExtra(EXTRA_LISTEN_PORT, 3389)
        val targetHost = intent.getStringExtra(EXTRA_TARGET_HOST) ?: ""
        val targetPort = intent.getIntExtra(EXTRA_TARGET_PORT, 3389)
        val targetUsername = intent.getStringExtra(EXTRA_TARGET_USERNAME) ?: ""
        val targetPassword = intent.getStringExtra(EXTRA_TARGET_PASSWORD) ?: ""
        val targetDomain = intent.getStringExtra(EXTRA_TARGET_DOMAIN) ?: ""
        val width = intent.getIntExtra(EXTRA_WIDTH, 1280)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 800)
        val inboundUsername = intent.getStringExtra(EXTRA_INBOUND_USERNAME)
        val inboundPassword = intent.getStringExtra(EXTRA_INBOUND_PASSWORD)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serviceScope = scope

        val started = relay.start(
            context = this,
            scope = scope,
            listenPort = listenPort,
            targetHost = targetHost, targetPort = targetPort,
            targetUsername = targetUsername, targetPassword = targetPassword, targetDomain = targetDomain,
            width = width, height = height,
            inboundUsername = inboundUsername, inboundPassword = inboundPassword,
        )
        if (!started) {
            Log.e(TAG, "RdpProxyRelay.start() failed — stopping service")
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        relay.stop()
        serviceScope?.cancel()
        serviceScope = null
        isRunning = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.rdp_proxy_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.rdp_proxy_notification_title))
            .setContentText(getString(R.string.rdp_proxy_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
