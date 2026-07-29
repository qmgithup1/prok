package com.systemsgo.hex.data.repository

import android.appwidget.AppWidgetManager
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
 * HOME-SCREEN-WIDGET FEATURE (Part 1/2).
 *
 * Which connection(s) each placed instance of [com.systemsgo.hex.widget.HexRdpAppWidgetProvider]
 * shows, keyed by that instance's own [AppWidgetManager]-assigned `appWidgetId` — a
 * user can drop the widget on their home screen more than once (e.g. one small
 * "Home Server" quick-connect button and one larger "All Connections" list), and
 * each instance needs its own independent configuration, the same way each pinned
 * shortcut ([com.systemsgo.hex.util.ShortcutHelper]) or the Quick Settings tile
 * ([QsTilePreferences]) is configured independently.
 *
 * Same trust boundary and persistence choice as [QsTilePreferences]: only ids/flags
 * are stored here, never credentials, and it's EncryptedSharedPreferences purely for
 * consistency with the rest of app-level settings storage — nothing stored here is
 * actually sensitive on its own.
 */
@Singleton
class WidgetPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** How a given widget instance decides what to show — set once in [com.systemsgo.hex.widget.WidgetConfigureActivity]. */
    enum class DisplayMode { SINGLE_CONNECTION, CONNECTION_LIST }

    /** Which profiles populate a [DisplayMode.CONNECTION_LIST] instance. */
    enum class ListFilter { ALL, FAVORITES_ONLY }

    /** Immutable snapshot of one widget instance's configuration. */
    data class WidgetConfig(
        val mode: DisplayMode = DisplayMode.CONNECTION_LIST,
        /** Only meaningful when [mode] is [DisplayMode.SINGLE_CONNECTION]. */
        val singleProfileId: String? = null,
        /** Only meaningful when [mode] is [DisplayMode.CONNECTION_LIST]. */
        val listFilter: ListFilter = ListFilter.ALL,
        /** Cap on rows rendered by [com.systemsgo.hex.widget.RdpProfileRemoteViewsFactory] — keeps very large connection lists from producing an unbounded RemoteViews list. */
        val maxItems: Int = DEFAULT_MAX_ITEMS,
    )

    private val prefs: SharedPreferences by lazy {
        context.openEncryptedPrefs("systemsgo_widget_settings")
    }

    private object Keys {
        fun mode(id: Int) = "widget_${id}_mode"
        fun singleProfileId(id: Int) = "widget_${id}_single_profile_id"
        fun listFilter(id: Int) = "widget_${id}_list_filter"
        fun maxItems(id: Int) = "widget_${id}_max_items"
        /** Every per-widget key above, so [clearForWidget] can wipe an instance without needing to know its shape. */
        fun allFor(id: Int) = listOf(mode(id), singleProfileId(id), listFilter(id), maxItems(id))
    }

    fun configSnapshot(appWidgetId: Int): WidgetConfig = WidgetConfig(
        mode = prefs.getString(Keys.mode(appWidgetId), null)
            ?.let { runCatching { DisplayMode.valueOf(it) }.getOrNull() }
            ?: DisplayMode.CONNECTION_LIST,
        singleProfileId = prefs.getString(Keys.singleProfileId(appWidgetId), null),
        listFilter = prefs.getString(Keys.listFilter(appWidgetId), null)
            ?.let { runCatching { ListFilter.valueOf(it) }.getOrNull() }
            ?: ListFilter.ALL,
        maxItems = prefs.getInt(Keys.maxItems(appWidgetId), DEFAULT_MAX_ITEMS),
    )

    /** Live updates for one widget instance's config, for [com.systemsgo.hex.widget.WidgetConfigureActivity] to reflect external changes (there are none today, but this mirrors [QsTilePreferences.selectedProfileIdFlow]'s shape). */
    fun configFlow(appWidgetId: Int): Flow<WidgetConfig> = callbackFlow {
        val relevantKeys = Keys.allFor(appWidgetId).toSet()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in relevantKeys) trySend(configSnapshot(appWidgetId))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(configSnapshot(appWidgetId))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Called by [com.systemsgo.hex.widget.WidgetConfigureActivity]'s "Save" action. Does not itself trigger a RemoteViews refresh — callers push through [com.systemsgo.hex.widget.WidgetUpdater] once they're done writing. */
    fun saveConfig(appWidgetId: Int, config: WidgetConfig) {
        prefs.edit()
            .putString(Keys.mode(appWidgetId), config.mode.name)
            .apply {
                if (config.singleProfileId != null) {
                    putString(Keys.singleProfileId(appWidgetId), config.singleProfileId)
                } else {
                    remove(Keys.singleProfileId(appWidgetId))
                }
            }
            .putString(Keys.listFilter(appWidgetId), config.listFilter.name)
            .putInt(Keys.maxItems(appWidgetId), config.maxItems)
            .apply()
    }

    /**
     * Called from [com.systemsgo.hex.widget.HexRdpAppWidgetProvider.onDeleted] when the
     * user drags a widget instance off their home screen — mirrors
     * [QsTilePreferences.clearSelection]'s "don't leak state for a widget that no longer
     * exists" reasoning. [AppWidgetManager] never reuses a removed `appWidgetId`, so
     * there's no risk of this accidentally clearing a still-live instance's config.
     */
    fun clearForWidget(appWidgetId: Int) {
        val editor = prefs.edit()
        Keys.allFor(appWidgetId).forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * Called on profile delete (see MainViewModel.deleteProfile), mirroring
     * [QsTilePreferences.clearIfSelected] / [com.systemsgo.hex.util.ShortcutHelper.disableShortcut]'s
     * "clean up after the thing this pointed to is gone" responsibility. Only clears the
     * [DisplayMode.SINGLE_CONNECTION] binding — a [DisplayMode.CONNECTION_LIST] instance
     * needs no cleanup here, since [com.systemsgo.hex.widget.RdpProfileRemoteViewsFactory]
     * always re-reads the live profile list and a deleted profile simply stops appearing.
     *
     * @return the ids of widget instances that were bound to [profileId] and are now
     *   unconfigured, so the caller can refresh just those (see [com.systemsgo.hex.widget.WidgetUpdater]).
     */
    fun clearIfSelected(profileId: String, allWidgetIds: IntArray): List<Int> {
        val affected = allWidgetIds.filter { id ->
            configSnapshot(id).let { it.mode == DisplayMode.SINGLE_CONNECTION && it.singleProfileId == profileId }
        }
        if (affected.isEmpty()) return affected
        val editor = prefs.edit()
        affected.forEach { id -> editor.remove(Keys.singleProfileId(id)) }
        editor.apply()
        return affected
    }

    companion object {
        const val DEFAULT_MAX_ITEMS = 10
    }
}
