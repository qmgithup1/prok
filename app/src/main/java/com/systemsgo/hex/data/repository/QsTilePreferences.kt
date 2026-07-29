package com.systemsgo.hex.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.systemsgo.hex.security.openEncryptedPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.channels.awaitClose
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QUICK-SETTINGS-TILE FEATURE (Part 1/2).
 *
 * Non-secret state for "Settings → Quick Settings Tile": remembers which
 * saved connection (by [com.systemsgo.hex.data.model.RdpProfile.id]) the
 * app's Quick Settings tile ([com.systemsgo.hex.tile.QuickConnectTileService])
 * connects to when tapped. No credentials live here — same trust boundary as
 * [com.systemsgo.hex.util.ShortcutHelper]'s pinned/dynamic shortcuts: only a
 * profile id is stored, and the tile's launch Intent (built the exact same
 * way as a shortcut's) never carries anything sensitive itself.
 *
 * Follows the same EncryptedSharedPreferences + callbackFlow pattern as
 * [CloudSyncPreferences] rather than introducing a second, different
 * persistence mechanism for one more small piece of app-level state.
 */
@Singleton
class QsTilePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.openEncryptedPrefs("systemsgo_qs_tile_settings")
    }

    private object Keys {
        const val SELECTED_PROFILE_ID = "selected_profile_id"
    }

    /** Null = tile has no connection bound yet ("tap to set up"). */
    fun currentSelectionSnapshot(): String? = prefs.getString(Keys.SELECTED_PROFILE_ID, null)

    val selectedProfileIdFlow: Flow<String?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == Keys.SELECTED_PROFILE_ID) {
                trySend(currentSelectionSnapshot())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(currentSelectionSnapshot())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Called from Settings → Quick Settings Tile when the user picks a connection. */
    fun setSelectedProfile(profileId: String) {
        prefs.edit().putString(Keys.SELECTED_PROFILE_ID, profileId).apply()
        com.systemsgo.hex.tile.QsTileUpdater.requestUpdate(context)
    }

    /** Called from the same screen's "Clear" action. */
    fun clearSelection() {
        prefs.edit().remove(Keys.SELECTED_PROFILE_ID).apply()
        com.systemsgo.hex.tile.QsTileUpdater.requestUpdate(context)
    }

    /**
     * Called on profile delete (see MainViewModel.deleteProfile), mirroring
     * [com.systemsgo.hex.util.ShortcutHelper.disableShortcut]'s "clean up
     * after the thing this pointed to is gone" responsibility — without
     * this, a deleted profile would stay bound to the tile forever, so
     * tapping it would silently do nothing (getProfileById returns null).
     * No-op if [profileId] isn't the one currently selected.
     */
    fun clearIfSelected(profileId: String) {
        if (currentSelectionSnapshot() == profileId) clearSelection()
    }
}
