package com.systemsgo.hex.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * HOME-SCREEN-WIDGET FEATURE (Part 1/2).
 *
 * Thin wrapper that asks the system to redraw every placed instance of
 * [SystemsGoAppWidgetProvider] — the widget equivalent of [com.systemsgo.hex.tile.QsTileUpdater]'s
 * `requestListeningState` poke, for the same reason: a change made *elsewhere* in the
 * app (a connection added/edited/deleted, a session connecting/disconnecting, a
 * favorite toggled) has no lifecycle callback of its own on
 * [android.appwidget.AppWidgetProvider] the way `onStartListening` does for a
 * [android.service.quicksettings.TileService] — the host (Launcher) only re-invokes
 * `onUpdate` on its own schedule (`updatePeriodMillis`, which this widget deliberately
 * sets to 0 — see systemsgo_widget_info.xml — precisely so it never redraws on a stale
 * timer instead of on real data changes). Without an explicit push here, a widget
 * showing a connection's live status or a list of favorites would only ever catch up
 * the next time the widget was resized or the device rebooted.
 *
 * Every call site already has a plain [Context], never anything Hilt-injected —
 * deliberately a stateless object, mirroring [com.systemsgo.hex.tile.QsTileUpdater],
 * so it can be called from [com.systemsgo.hex.data.repository.WidgetPreferences] and
 * [com.systemsgo.hex.data.repository.RdpProfileRepository] alike without either
 * needing to depend on the other.
 */
object WidgetUpdater {

    /** Refreshes every placed instance of the widget (list contents + single-connection state). */
    fun requestUpdateAll(context: Context) {
        val ids = allWidgetIds(context)
        if (ids.isEmpty()) return
        requestUpdate(context, ids)
    }

    /** Refreshes only the given widget instances — used when just one instance's binding changed (e.g. its bound profile was deleted). */
    fun requestUpdate(context: Context, appWidgetIds: List<Int>) {
        if (appWidgetIds.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        // Tells any CONNECTION_LIST instance among these ids that its row data may have
        // changed, so RdpProfileRemoteViewsFactory.onDataSetChanged() re-reads the DB
        // before the list redraws — without this, notifyAppWidgetViewDataChanged alone
        // (below) would refresh a stale cached row set on some OEM launchers.
        manager.notifyAppWidgetViewDataChanged(appWidgetIds.toIntArray(), android.R.id.list)
        val intent = Intent(context, SystemsGoAppWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds.toIntArray())
        }
        context.sendBroadcast(intent)
    }

    /** All ids the system currently has placed for [SystemsGoAppWidgetProvider], across every home screen/launcher host. */
    fun allWidgetIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, SystemsGoAppWidgetProvider::class.java)
        return manager.getAppWidgetIds(component)
    }
}
