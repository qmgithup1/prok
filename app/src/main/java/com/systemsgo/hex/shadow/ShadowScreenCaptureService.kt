package com.systemsgo.hex.shadow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.systemsgo.hex.R
import com.systemsgo.hex.rdp.native.AFreeRdpServerBridge
import com.systemsgo.hex.rdp.native.RdpServerCertificateGenerator
import com.systemsgo.hex.rdp.native.RdpServerNlaCredentials
import com.systemsgo.hex.ui.MainActivity

/**
 * SHADOW-SERVER FEATURE: the MediaProjection half of "Shadow Server" (see
 * `systemsgo_server_jni.c`'s and `AFreeRdpServerBridge.kt`'s SHADOW-SERVER
 * FEATURE comments for the native/JNI side this feeds). This service:
 *
 * 1. Starts [AFreeRdpServerBridge] listening (milestone 1's job, unchanged).
 * 2. Uses an already-granted [MediaProjection] (the result of the
 *    `MediaProjectionManager.createScreenCaptureIntent()` system consent
 *    dialog — obtained in [ShadowServerActivity], NOT here: a Service
 *    cannot itself launch that dialog) to mirror the device's actual
 *    display into an [ImageReader] via a [VirtualDisplay].
 * 3. Converts each captured frame from Android's RGBA_8888 to the BGRX32
 *    byte order FreeRDP's raw BitmapUpdate path expects, and pushes it to
 *    [AFreeRdpServerBridge.pushFrame].
 *
 * Remote-client INPUT (the other half of "Shadow Server" — turning a
 * connecting viewer's mouse/keyboard into real taps/keystrokes on this
 * device) is handled separately by [RemoteInputAccessibilityService]; this
 * service only owns the outgoing video path.
 *
 * FRAME RATE: capped at [TARGET_FPS] by simply dropping a capture tick if
 * the previous frame's convert+push hasn't finished yet ([isEncoding]) —
 * deliberately simple backpressure, matching this whole feature's
 * "prove it end-to-end first" scope; a real encoder (H.264 via RDPGFX) is
 * future work noted in SETUP.md, not attempted here. Raw BGRX32 at even a
 * modest resolution is already a lot of bytes per frame over the classic
 * BitmapUpdate path, so [TARGET_FPS] is intentionally conservative.
 *
 * SECURITY: identical warning to [AFreeRdpServerBridge] — this only ever
 * negotiates the legacy "Standard RDP Security" tier (no TLS/NLA yet).
 * Starting this service means ANY device that can reach the configured
 * port on the network and speaks that legacy tier sees this device's real
 * screen and (once RemoteInputAccessibilityService is enabled) can control
 * it. LAN/VPN only — never port-forward this to the open internet.
 */
class ShadowScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ShadowScreenCapture"
        const val CHANNEL_ID = "shadow_server"
        const val NOTIF_ID = 2001
        private const val TARGET_FPS = 12

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PORT = "port"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DENSITY = "density"
        const val EXTRA_INBOUND_USERNAME = "inboundUsername"
        const val EXTRA_INBOUND_PASSWORD = "inboundPassword"
        const val ACTION_STOP = "com.systemsgo.hex.shadow.STOP"

        /** Currently-running instance, if any — lets [ShadowServerActivity]
         * reflect actual state without its own separate bookkeeping. Set/
         * cleared only from this service's own lifecycle methods. */
        @Volatile var isRunning: Boolean = false
            private set

        /** The [AFreeRdpServerBridge] this service is currently feeding
         * frames into, or null when not running. [RemoteInputAccessibilityService]
         * reads this once (in its onServiceConnected) to subscribe to
         * [AFreeRdpServerBridge.peerMouseEvents]/[peerKeyEvents] — see that
         * class's onServiceConnected doc for the ordering caveat this
         * implies (enable Accessibility before/while starting sharing). */
        @Volatile var activeBridge: AFreeRdpServerBridge? = null
            private set

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            port: Int,
            width: Int,
            height: Int,
            densityDpi: Int,
            inboundUsername: String? = null,
            inboundPassword: String? = null,
        ) {
            val intent = Intent(context, ShadowScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_WIDTH, width)
                .putExtra(EXTRA_HEIGHT, height)
                .putExtra(EXTRA_DENSITY, densityDpi)
                .putExtra(EXTRA_INBOUND_USERNAME, inboundUsername)
                .putExtra(EXTRA_INBOUND_PASSWORD, inboundPassword)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ShadowScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var isEncoding = false
    @Volatile private var lastFrameNanos = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The system can revoke a MediaProjection grant at any time
            // (e.g. user taps "Stop sharing" in the system's own capture
            // notification) — treat exactly like our own stop() being called.
            Log.i(TAG, "MediaProjection stopped by system — tearing down")
            stopSelf()
        }
    }

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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val port = intent.getIntExtra(EXTRA_PORT, 3389)
        val width = intent.getIntExtra(EXTRA_WIDTH, 1280)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 720)
        val densityDpi = intent.getIntExtra(EXTRA_DENSITY, 160)
        val inboundUsername = intent.getStringExtra(EXTRA_INBOUND_USERNAME)
        val inboundPassword = intent.getStringExtra(EXTRA_INBOUND_PASSWORD)

        if (resultData == null) {
            Log.e(TAG, "onStartCommand: missing MediaProjection result data — cannot start capture")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!AFreeRdpServerBridge.isAvailable) {
            Log.w(TAG, "onStartCommand: native RDP server library unavailable — see SETUP.md")
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(resultCode, resultData, port, width, height, densityDpi, inboundUsername, inboundPassword)
        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent,
        port: Int,
        width: Int,
        height: Int,
        densityDpi: Int,
        inboundUsername: String?,
        inboundPassword: String?,
    ) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null — grant may have been consumed already")
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        val thread = HandlerThread("ShadowCapture").apply { start() }
        handlerThread = thread
        val bgHandler = Handler(thread.looper)
        handler = bgHandler

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        // TLS-SERVER FEATURE: generate (or reuse the cached, still-valid)
        // self-signed cert/key pair so this listener speaks real TLS
        // instead of the old unauthenticated-transport fallback — see
        // RdpServerCertificateGenerator's doc and AFreeRdpServerBridge.start()'s
        // updated doc for what changes on the wire. KNOWN CAVEAT: this runs
        // on the same thread as onStartCommand (main thread) — RSA-2048
        // keygen is only a one-time cost (cached 24h after), but a slow
        // device could see a brief hitch the very first time this service
        // starts after a cache miss/expiry. Not moved off-thread in this
        // pass to keep this change scoped to enabling TLS itself.
        val serverCert = RdpServerCertificateGenerator.getOrCreate(applicationContext)
        if (serverCert == null) {
            Log.w(TAG, "Falling back to no-TLS Standard RDP Security — " +
                "certificate generation failed, see RdpServerCertificateGenerator logs")
        }

        // NLA-SERVER FEATURE: only meaningful on top of a real cert (NLA
        // needs TLS underneath — see AFreeRdpServerBridge's class doc).
        // If no username/password was supplied this call, this clears any
        // previous SAM file so the listener falls back to TLS-only.
        if (serverCert != null && !inboundUsername.isNullOrBlank() && inboundPassword != null) {
            val samPath = RdpServerNlaCredentials.writeSamFile(applicationContext, inboundUsername, inboundPassword)
            server.setSamFile(samPath)
            if (samPath == null) {
                Log.w(TAG, "Failed to write NLA SAM file — falling back to TLS-only, see RdpServerNlaCredentials logs")
            }
        } else {
            RdpServerNlaCredentials.clear(applicationContext)
            server.setSamFile(null)
        }
        // Defense-in-depth app-level check regardless of NLA state — see
        // setExpectedCredentials()'s doc for why this stays harmless once
        // real NLA is also active.
        server.setExpectedCredentials(inboundUsername, inboundPassword)

        // AFreeRdpServerBridge.start() must happen before the first capture
        // tick so nativePushFrame() has a running listener to broadcast to
        // (it is a safe, logged no-op otherwise — see that function's doc —
        // but starting in this order avoids relying on that fallback).
        val bridge = server
        if (!bridge.start(port, width, height, serverCert?.certPath, serverCert?.keyPath)) {
            Log.e(TAG, "AFreeRdpServerBridge.start($port, $width, $height) failed")
            teardown()
            stopSelf()
            return
        }

        reader.setOnImageAvailableListener({ onImageAvailable(bridge, width, height) }, bgHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "HexRdpShadowServer",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, bgHandler,
        )

        isRunning = true
        activeBridge = bridge
        Log.i(TAG, "Shadow Server capture started: ${width}x$height @ port $port")
    }

    private fun onImageAvailable(bridge: AFreeRdpServerBridge, width: Int, height: Int) {
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            // FRAME-RATE CAP: drop this tick entirely (still releasing the
            // Image below) if either we're already converting a previous
            // frame, or less than 1/TARGET_FPS has elapsed since the last
            // one we actually pushed.
            val now = System.nanoTime()
            val minIntervalNanos = 1_000_000_000L / TARGET_FPS
            if (isEncoding || now - lastFrameNanos < minIntervalNanos) return
            isEncoding = true
            lastFrameNanos = now

            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val bgrx = toBgrx(plane.buffer, rowStride, pixelStride, width, height)
            bridge.pushFrame(bgrx, width, height)
        } finally {
            isEncoding = false
            image.close()
        }
    }

    /**
     * Converts one RGBA_8888 [ImageReader] plane (which may have row
     * padding — [rowStride] can exceed `width * pixelStride`, hence the
     * per-row copy below rather than one bulk copy) into a tightly-packed
     * BGRX32 buffer: swap R and B, drop/ignore alpha into the X byte, same
     * as `systemsgo_server_jni.c`'s expected `PIXEL_FORMAT_BGRX32` layout.
     */
    private fun toBgrx(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val out = ByteArray(width * height * 4)
        val rowBytes = ByteArray(rowStride)
        var outIdx = 0
        val duplicated = buffer.duplicate()
        for (row in 0 until height) {
            duplicated.position(row * rowStride)
            val remaining = minOf(rowStride, duplicated.remaining())
            duplicated.get(rowBytes, 0, remaining)
            var inIdx = 0
            for (col in 0 until width) {
                val r = rowBytes[inIdx]
                val g = rowBytes[inIdx + 1]
                val b = rowBytes[inIdx + 2]
                out[outIdx] = b       // B
                out[outIdx + 1] = g   // G
                out[outIdx + 2] = r   // R
                out[outIdx + 3] = 0   // X (unused)
                inIdx += pixelStride
                outIdx += 4
            }
        }
        return out
    }

    private fun teardown() {
        isRunning = false
        activeBridge = null
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null

        server.stop()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.shadow_server_notification_channel),
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
            .setContentTitle(getString(R.string.shadow_server_notification_title))
            .setContentText(getString(R.string.shadow_server_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private val server = AFreeRdpServerBridge()
}
