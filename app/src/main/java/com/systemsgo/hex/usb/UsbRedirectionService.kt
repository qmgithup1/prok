package com.systemsgo.hex.usb

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
import androidx.core.app.NotificationCompat
import com.systemsgo.hex.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * USB-REDIRECT FEATURE (Part 1/3): foreground service keeping
 * [UsbRedirectionManager] (and therefore every open
 * [android.hardware.usb.UsbDeviceConnection]) alive while the RDP session
 * activity is backgrounded — same rationale as every other "must keep
 * running while the user tabs away" feature in this app (see
 * [com.systemsgo.hex.remote.clipboard] / background audio handling):
 * Android can and will tear down USB connections held only by a
 * backgrounded, non-foreground process.
 *
 * Started only when both the "Enable USB Redirection" setting is on AND at
 * least one device is currently approved/redirected — see
 * [UsbRedirectionManager.deviceListFlow] consumers that call
 * [start]/[stop]. Never started speculatively, to avoid an always-on
 * notification for users who never touch this feature.
 *
 * `foregroundServiceType="connectedDevice"` (declared in AndroidManifest.xml)
 * is the correct Android 14+ (API 34) FGS type for USB-host-adjacent work —
 * see Android's foreground service types documentation.
 */
@AndroidEntryPoint
class UsbRedirectionService : Service() {

    @Inject lateinit var usbRedirectionManager: UsbRedirectionManager

    companion object {
        private const val CHANNEL_ID = "usb_redirection"
        private const val NOTIFICATION_ID = 4271

        fun start(context: Context) {
            val intent = Intent(context, UsbRedirectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsbRedirectionService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        val notification = buildNotification(deviceCount = 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        usbRedirectionManager.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky: a device mid-transfer when the process is killed for memory
        // should have its redirection state restored, not silently dropped —
        // mirrors reconnectAutomatically's intent at the service level too.
        return START_STICKY
    }

    override fun onDestroy() {
        usbRedirectionManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.usb_redirection_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.usb_redirection_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(deviceCount: Int): Notification {
        val contentText = if (deviceCount > 0) {
            getString(R.string.usb_redirection_notification_text_active, deviceCount)
        } else {
            getString(R.string.usb_redirection_notification_text_idle)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, flags)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.usb_redirection_notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }
}
