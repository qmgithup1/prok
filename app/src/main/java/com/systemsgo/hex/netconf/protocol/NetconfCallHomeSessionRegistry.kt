package com.systemsgo.hex.netconf.protocol

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/** One live NETCONF Call Home session — a device connected in, authenticated, and completed the `<hello>` exchange. */
data class NetconfCallHomeSession(
    val token: String,
    val profileId: String,
    val profileName: String,
    val remoteAddress: String,
    val client: NetconfClient,
    val connectedAtMs: Long = System.currentTimeMillis(),
)

/**
 * NETCONF CALL HOME FEATURE (Part 12): process-wide registry of currently
 * live Call Home sessions, bridging `NetconfCallHomeService` (which owns
 * the [NetconfCallHomeListener]s, accepts sockets, and builds+connects a
 * [NetconfClient] for each one) to [com.systemsgo.hex.ui.screens.
 * NetconfSessionActivity] (which the user opens by tapping the Call Home
 * notification, or from an in-app "Live Call Home Sessions" list).
 *
 * This split exists because a Call Home session's *lifetime* is owned by
 * the foreground Service (it must keep running, and keep the
 * already-connected [NetconfClient], even while no Activity/Session UI is
 * open — that's the entire point of Call Home: the device decides when to
 * connect, not the user tapping a "Connect" button), whereas a normal
 * outbound NETCONF profile's [NetconfClient] is owned by
 * `NetconfSessionViewModel` and dies with its Activity. A plain
 * in-memory map (not Room, not DataStore) is correct here precisely
 * because none of this should survive a process death — if the app
 * process is killed, every live SSH session it held is gone with it
 * regardless of what this map says, and the device will simply call home
 * again per its own reconnect-strategy configuration (RFC 8071 §3.2).
 */
object NetconfCallHomeSessionRegistry {
    private val sessions = ConcurrentHashMap<String, NetconfCallHomeSession>()

    private val _newSession = MutableSharedFlow<NetconfCallHomeSession>(extraBufferCapacity = 16)
    /** Emits once per newly-registered session — for in-app UI (e.g. a future "Live Sessions" list) to react live; `NetconfCallHomeService` itself is only ever the producer here. */
    val newSession: SharedFlow<NetconfCallHomeSession> = _newSession.asSharedFlow()

    private val _sessionEnded = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val sessionEnded: SharedFlow<String> = _sessionEnded.asSharedFlow()

    fun register(session: NetconfCallHomeSession) {
        sessions[session.token] = session
        _newSession.tryEmit(session)
    }

    fun get(token: String): NetconfCallHomeSession? = sessions[token]

    fun all(): List<NetconfCallHomeSession> = sessions.values.toList()

    /** Called once a session's [NetconfClient] disconnects (device dropped, or manual stop) — does NOT itself disconnect the client, just stops tracking it. */
    fun unregister(token: String) {
        if (sessions.remove(token) != null) _sessionEnded.tryEmit(token)
    }

    fun clear() {
        sessions.keys.toList().forEach { unregister(it) }
    }
}
