package com.systemsgo.hex.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.systemsgo.hex.security.openEncryptedPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADD-CONNECTION PROTOCOL PICKER (Part 1/2).
 *
 * Non-secret UI state for the "Add Connection" protocol picker
 * (com.systemsgo.hex.ui.screens.addconnection.AddConnectionProtocolScreen):
 * which [com.systemsgo.hex.data.model.ProtocolCatalogEntry.id]s are favorited,
 * which were used most recently, and which protocols the user has already
 * seen the first-time introduction panel for. Nothing here is a credential —
 * same trust boundary and same EncryptedSharedPreferences + callbackFlow
 * pattern as [QsTilePreferences], for one more small piece of app-level state
 * rather than introducing a second persistence mechanism.
 *
 * Part 2 wires [recordUsed] into the moment the picker hands a chosen
 * protocol off to the connection editor, and wires [markIntroSeen] into the
 * first-time introduction panel's Continue button.
 */
@Singleton
class AddConnectionPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.openEncryptedPrefs("systemsgo_add_connection_picker")
    }

    private object Keys {
        const val FAVORITE_IDS = "favorite_protocol_ids"
        const val RECENT_IDS = "recent_protocol_ids" // ordered, most-recent-first, comma-joined
        const val INTRO_SEEN_IDS = "intro_seen_protocol_ids"
    }

    private companion object {
        const val MAX_RECENTS = 6
    }

    // ── Favorites ────────────────────────────────────────────────────────────

    fun favoriteIdsSnapshot(): Set<String> = prefs.getStringSet(Keys.FAVORITE_IDS, emptySet()) ?: emptySet()

    val favoriteIdsFlow: Flow<Set<String>> = prefsFlow(Keys.FAVORITE_IDS) { favoriteIdsSnapshot() }

    fun toggleFavorite(protocolId: String) {
        val current = favoriteIdsSnapshot().toMutableSet()
        if (!current.add(protocolId)) current.remove(protocolId)
        prefs.edit().putStringSet(Keys.FAVORITE_IDS, current).apply()
    }

    // ── Recents ──────────────────────────────────────────────────────────────

    fun recentIdsSnapshot(): List<String> =
        prefs.getString(Keys.RECENT_IDS, null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    val recentIdsFlow: Flow<List<String>> = prefsFlow(Keys.RECENT_IDS) { recentIdsSnapshot() }

    /** Call when a protocol is actually chosen to start a new connection — moves it to the front. */
    fun recordUsed(protocolId: String) {
        val updated = listOf(protocolId) + recentIdsSnapshot().filterNot { it == protocolId }
        prefs.edit().putString(Keys.RECENT_IDS, updated.take(MAX_RECENTS).joinToString(",")).apply()
    }

    // ── First-time introduction panel ───────────────────────────────────────

    fun hasSeenIntro(protocolId: String): Boolean =
        (prefs.getStringSet(Keys.INTRO_SEEN_IDS, emptySet()) ?: emptySet()).contains(protocolId)

    fun markIntroSeen(protocolId: String) {
        val current = (prefs.getStringSet(Keys.INTRO_SEEN_IDS, emptySet()) ?: emptySet()).toMutableSet()
        current.add(protocolId)
        prefs.edit().putStringSet(Keys.INTRO_SEEN_IDS, current).apply()
    }

    // ── Shared listener plumbing ────────────────────────────────────────────

    private fun <T> prefsFlow(key: String, snapshot: () -> T): Flow<T> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == null || changedKey == key) trySend(snapshot())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(snapshot())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
