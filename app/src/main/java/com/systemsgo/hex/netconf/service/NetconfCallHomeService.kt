package com.systemsgo.hex.netconf.service

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
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.netconf.protocol.NetconfCallHomeConnection
import com.systemsgo.hex.netconf.protocol.CallHomeTransport
import com.systemsgo.hex.netconf.protocol.NetconfCallHomeListener
import com.systemsgo.hex.netconf.protocol.NetconfCallHomeTlsListener
import com.systemsgo.hex.netconf.protocol.NetconfCallHomeSession
import com.systemsgo.hex.netconf.protocol.NetconfCallHomeSessionRegistry
import com.systemsgo.hex.netconf.protocol.NetconfProfileMapper
import com.systemsgo.hex.ui.screens.NetconfSessionActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * NETCONF CALL HOME FEATURE (RFC 8071, Part 12): the piece that makes Call
 * Home a background capability rather than something that only exists
 * while a Session UI happens to be open. Mirrors [com.systemsgo.hex.proxy.
 * RdpProxyService]'s shape (a foreground `connectedDevice` service that
 * keeps running independent of any Activity) but owns potentially *several*
 * [NetconfCallHomeListener]s at once — one per distinct
 * [RdpProfile.netconfCallHomeListenPort] among currently-enabled NETCONF
 * Call Home profiles, since RFC 8071 puts no limit on how many devices'
 * client-list entries point at this app, and different devices/vendors
 * commonly ship different default Call Home ports.
 *
 * Lifecycle: started explicitly (see [start]/[ensureRunningIfNeeded]) and
 * left running as a foreground service for as long as at least one profile
 * has Call Home enabled — [SystemsGoApp.onCreate] calls
 * [ensureRunningIfNeeded] once at process start so a device that calls
 * home while the user hasn't opened the app recently is still caught,
 * matching how a real network-management station's Call Home listener is
 * always-on rather than opened on demand.
 *
 * Matching an accepted [android.net.Socket] to a profile: if exactly one
 * enabled profile listens on the port the connection arrived on, that
 * profile is used unconditionally. If more than one profile shares a port
 * (multiple devices Call-Home-ing to the same well-known port, e.g. the
 * RFC 8071/IANA default 4334), [RdpProfile.netconfCallHomeAllowedSourceHost]
 * is required to disambiguate — a connection whose source address doesn't
 * exactly match exactly one candidate is rejected (socket closed, no
 * session created) rather than guessed at, since silently authenticating
 * as the wrong profile's credentials against an unexpected device would be
 * a credential-disclosure bug, not just a UX papercut.
 */
@AndroidEntryPoint
class NetconfCallHomeService : Service() {

    companion object {
        private const val TAG = "NetconfCallHomeService"
        const val CHANNEL_ID = "netconf_call_home"
        const val CHANNEL_ID_SESSIONS = "netconf_call_home_sessions"
        private const val NOTIF_ID_FOREGROUND = 4001
        private const val ACTION_STOP = "com.systemsgo.hex.netconf.ACTION_STOP_CALL_HOME"

        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, NetconfCallHomeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NetconfCallHomeService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Called from [com.systemsgo.hex.SystemsGoApp.onCreate]: starts the
         * service iff at least one profile currently has Call Home enabled,
         * so the common case (nobody uses Call Home) never pays for an
         * always-on foreground service and its notification. Safe to call
         * repeatedly — starting an already-running service is a no-op
         * beyond a redundant onStartCommand, which just re-runs (harmless,
         * idempotent) listener reconciliation.
         */
        fun ensureRunningIfNeeded(context: Context, profileRepository: RdpProfileRepository, scope: CoroutineScope) {
            scope.launch {
                val hasAny = kotlinx.coroutines.flow.first(profileRepository.getAllProfiles()).any {
                    it.protocolType == ProtocolType.NETCONF && it.netconfCallHomeEnabled
                }
                if (hasAny) start(context)
            }
        }
    }

    @Inject lateinit var profileRepository: RdpProfileRepository

    private var serviceScope: CoroutineScope? = null

    /** (listenPort, transport) -> live listener — a SSH listener and a TLS listener can coexist on the *same* port number (different sockets — SSH is TCP straight up, TLS Call Home reuses TCP too, but they're independent listen ports in practice since two profiles wouldn't normally share a literal port across transports; the pair key keeps that theoretical case correct rather than assuming it can't happen). */
    private val listeners = java.util.concurrent.ConcurrentHashMap<Pair<Int, CallHomeTransport>, com.systemsgo.hex.netconf.protocol.CallHomeListenerHandle>()
    /** Same (port, transport) key -> the enabled NETCONF Call Home profiles currently bound to it (kept in sync by [reconcile]). */
    private val portProfiles = java.util.concurrent.ConcurrentHashMap<Pair<Int, CallHomeTransport>, List<RdpProfile>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannels()
        val notification = buildForegroundNotification(activeSessionCount = NetconfCallHomeSessionRegistry.all().size)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIF_ID_FOREGROUND, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID_FOREGROUND, notification)
        }

        if (serviceScope == null) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            serviceScope = scope
            scope.launch {
                profileRepository.getAllProfiles().collectLatest { profiles ->
                    reconcile(profiles.filter { it.protocolType == ProtocolType.NETCONF && it.netconfCallHomeEnabled })
                }
            }
        }
        isRunning = true
        return START_STICKY
    }

    /** Starts/stops [NetconfCallHomeListener]/[NetconfCallHomeTlsListener]s to match the current set of enabled profiles — called on every DB emission, so toggling Call Home on/off for a profile (or changing its port or transport) takes effect immediately without restarting the service. */
    private fun reconcile(enabledProfiles: List<RdpProfile>) {
        val byKey = enabledProfiles.groupBy { it.netconfCallHomeListenPort to it.transport() }

        // Stop listeners for (port, transport) pairs no longer referenced by any enabled profile.
        listeners.keys.toList().filterNot { it in byKey.keys }.forEach { key ->
            listeners.remove(key)?.stop()
            portProfiles.remove(key)
            Log.i(TAG, "Call Home listener on port ${key.first} (${key.second}) stopped (no enabled profile references it anymore)")
        }

        // Update the profile list for (port, transport) pairs that already have a listener
        // (a listener's *candidate profile set* can change — e.g. a second
        // profile enabling Call Home on the same port/transport — without the
        // listener itself needing to be rebound).
        byKey.forEach { (key, profiles) -> portProfiles[key] = profiles }

        // Start listeners for newly-referenced (port, transport) pairs.
        byKey.keys.filterNot { it in listeners.keys }.forEach { key ->
            val (port, transport) = key
            val listener = when (transport) {
                CallHomeTransport.SSH -> NetconfCallHomeListener(port) { conn -> handleAccept(key, conn) }
                CallHomeTransport.TLS -> NetconfCallHomeTlsListener(port) { conn -> handleAccept(key, conn) }
            }
            if (listener.start()) {
                listeners[key] = listener
                Log.i(TAG, "Call Home ($transport) listener started on port $port for ${byKey[key]?.size ?: 0} profile(s)")
            } else {
                Log.w(TAG, "Could not bind Call Home ($transport) listener on port $port — already in use?")
            }
        }
    }

    /** [RdpProfile.netconfCallHomeTransport] is a free-text Room column (for cheap, purely-additive migrations — see MIGRATION_47_48) rather than a Room-native enum; parsed here with a safe fallback to SSH so a malformed/future value never silently drops a profile's Call Home listener. */
    private fun RdpProfile.transport(): CallHomeTransport =
        runCatching { CallHomeTransport.valueOf(netconfCallHomeTransport.uppercase()) }.getOrDefault(CallHomeTransport.SSH)

    /**
     * Matches [conn] to exactly one candidate profile for [port] (see class
     * doc comment for the matching rule), builds+connects a [NetconfClient]
     * over the accepted socket, and on success registers it in
     * [NetconfCallHomeSessionRegistry] and raises a tappable notification.
     * Any failure to match, authenticate, or complete the `<hello>`
     * exchange simply closes the socket — the device's own Call Home
     * reconnect-strategy (RFC 8071 §3.2) is what drives retrying, not this
     * service.
     */
    private suspend fun handleAccept(key: Pair<Int, CallHomeTransport>, conn: NetconfCallHomeConnection) {
        val (port, transport) = key
        val candidates = portProfiles[key].orEmpty()
        val profile = when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates.first()
            else -> candidates.filter {
                it.netconfCallHomeAllowedSourceHost.isNotBlank() && it.netconfCallHomeAllowedSourceHost == conn.remoteAddress
            }.singleOrNull()
        }
        if (profile == null) {
            Log.w(TAG, "Rejecting Call Home ($transport) connection from ${conn.remoteAddress} on port $port — no unambiguous profile match (${candidates.size} candidate(s))")
            runCatching { conn.socket.close() }
            return
        }

        val client = when (transport) {
            CallHomeTransport.SSH -> NetconfProfileMapper.buildClient(profile, applicationContext, preAcceptedSocket = conn.socket)
            CallHomeTransport.TLS -> NetconfProfileMapper.buildClient(profile, applicationContext, preAcceptedTlsSocket = conn.socket)
        }
        val ok = client.connect()
        if (!ok) {
            Log.w(TAG, "Call Home ($transport) session for profile '${profile.name}' from ${conn.remoteAddress} failed to authenticate/handshake")
            return
        }

        val token = UUID.randomUUID().toString()
        val session = NetconfCallHomeSession(
            token = token,
            profileId = profile.id,
            profileName = profile.name,
            remoteAddress = conn.remoteAddress,
            client = client,
        )
        NetconfCallHomeSessionRegistry.register(session)
        Log.i(TAG, "Call Home session established: profile='${profile.name}' from=${conn.remoteAddress}")
        notifyNewSession(session)

        // Once this Call Home connection itself drops (device closed it, or
        // the SSH transport died), stop tracking it — see
        // NetconfCallHomeSessionRegistry's class doc comment for why this
        // does not attempt to redial.
        serviceScope?.launch {
            client.sessionState.collect { state ->
                if (state == com.systemsgo.hex.netconf.protocol.NetconfSessionState.DISCONNECTED ||
                    state == com.systemsgo.hex.netconf.protocol.NetconfSessionState.ERROR
                ) {
                    NetconfCallHomeSessionRegistry.unregister(token)
                    return@collect
                }
            }
        }
    }

    private fun notifyNewSession(session: NetconfCallHomeSession) {
        val openIntent = Intent(this, NetconfSessionActivity::class.java)
            .putExtra("profile_id", session.profileId)
            .putExtra("call_home_token", session.token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, session.token.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_SESSIONS)
            .setContentTitle(getString(R.string.netconf_call_home_session_notif_title, session.profileName))
            .setContentText(getString(R.string.netconf_call_home_session_notif_text, session.remoteAddress))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(session.token.hashCode(), notification)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        listeners.values.forEach { it.stop() }
        listeners.clear()
        portProfiles.clear()
        serviceScope?.cancel()
        serviceScope = null
        isRunning = false
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.netconf_call_home_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_SESSIONS, getString(R.string.netconf_call_home_sessions_channel_name), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun buildForegroundNotification(activeSessionCount: Int): Notification {
        val openIntent = Intent(this, com.systemsgo.hex.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.netconf_call_home_notification_title))
            .setContentText(
                getString(R.string.netconf_call_home_notification_text, activeSessionCount)
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
