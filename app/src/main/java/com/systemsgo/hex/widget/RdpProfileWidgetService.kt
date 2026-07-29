package com.systemsgo.hex.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.data.repository.WidgetPreferences
import com.systemsgo.hex.remote.SessionLauncher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * HOME-SCREEN-WIDGET FEATURE (Part 1/2).
 *
 * Backs [android.widget.ListView]'s `android.R.id.list` in
 * widget_connections_list.xml for every [WidgetPreferences.DisplayMode.CONNECTION_LIST]
 * instance of [HexRdpAppWidgetProvider] — the standard "collection widget" split
 * Android requires: [HexRdpAppWidgetProvider] can only ever build the *static* parts
 * of a widget's [RemoteViews] (see its own class doc), and hands off anything
 * list-shaped to a separate [RemoteViewsService] like this one, which the Launcher
 * process binds to directly and re-queries on its own whenever the list needs to
 * redraw (`android:targetSdkVersion` doesn't change this — it's a platform
 * requirement for every `ListView`/`GridView`/`StackView` widget, not a version
 * quirk).
 *
 * `@AndroidEntryPoint` here works exactly like it does on
 * [com.systemsgo.hex.tile.QuickConnectTileService] or
 * [com.systemsgo.hex.ui.screens.RdpSessionService] — Hilt supports it on any
 * [android.app.Service] subclass, and [RemoteViewsService] is one. The injected
 * [RdpProfileRepository] is handed straight to [RdpProfileRemoteViewsFactory]'s
 * constructor below rather than trying to make the *factory* itself a Hilt entry
 * point: [onGetViewFactory] is a synchronous platform callback with no Hilt
 * component of its own to attach to, so passing the already-injected dependency
 * through by hand is the simplest correct option — no new DI pattern needed for
 * one call site.
 */
@AndroidEntryPoint
class RdpProfileWidgetService : RemoteViewsService() {

    @Inject lateinit var profileRepository: RdpProfileRepository
    @Inject lateinit var widgetPreferences: WidgetPreferences

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID,
            android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return RdpProfileRemoteViewsFactory(applicationContext, profileRepository, widgetPreferences, appWidgetId)
    }
}

/**
 * Supplies one row (widget_list_item.xml) per saved connection, filtered per this
 * widget instance's [WidgetPreferences.WidgetConfig.listFilter] and capped at
 * [WidgetPreferences.WidgetConfig.maxItems].
 *
 * Runs entirely on the Launcher-process binder thread the system calls it from
 * ([getCount]/[getViewAt]/[onDataSetChanged] are all synchronous platform
 * callbacks — there is no coroutine scope to launch into here), so DB/DataStore
 * reads use `runBlocking { flow.first() }`, the same pattern Android's own
 * StackWidget sample and every real-world `RemoteViewsFactory` use for this exact
 * reason: the calling thread is already a background thread dedicated to this
 * call, blocking it briefly is expected and harmless.
 */
class RdpProfileRemoteViewsFactory(
    private val context: Context,
    private val profileRepository: RdpProfileRepository,
    private val widgetPreferences: WidgetPreferences,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var rows: List<RdpProfile> = emptyList()

    override fun onCreate() {
        // Real content is loaded in onDataSetChanged(), called once up-front by
        // the framework right after onCreate() — nothing to do here.
    }

    /**
     * Re-reads the connection list from the repository. Called by the framework
     * whenever [android.appwidget.AppWidgetManager.notifyAppWidgetViewDataChanged]
     * fires for `android.R.id.list` (see [WidgetUpdater.requestUpdate]) — this is
     * what makes an add/edit/delete/favorite/connect-state change elsewhere in the
     * app actually show up in the list, not just a fresh [getViewAt] call reusing
     * stale data.
     */
    override fun onDataSetChanged() {
        val config = widgetPreferences.configSnapshot(appWidgetId)
        val allProfiles = runBlocking { profileRepository.getAllProfiles().first() }
        val filtered = when (config.listFilter) {
            WidgetPreferences.ListFilter.ALL -> allProfiles
            WidgetPreferences.ListFilter.FAVORITES_ONLY -> allProfiles.filter { it.isFavorite }
        }
        rows = filtered
            .sortedWith(compareByDescending<RdpProfile> { it.isFavorite }.thenByDescending { it.lastConnected })
            .take(config.maxItems)
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val profile = rows.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_list_item)

        val views = RemoteViews(context.packageName, R.layout.widget_list_item)
        WidgetViewBinder.bind(context, views, profile)

        // Completes the ListView-wide PendingIntent template set by
        // HexRdpAppWidgetProvider.buildConnectionListViews() — see that method's
        // doc comment and widget_list_item.xml's for why a row-level
        // setOnClickPendingIntent isn't the mechanism used here. Only the fields
        // that differ per row (target component/extras) need to be supplied;
        // FLAG_FILL_IN_COMPONENT below lets fillInIntent override the template's
        // otherwise-unset target Activity per protocol (RdpSessionActivity vs
        // WebPortalActivity — see SessionLauncher.intentFor()).
        val fillInIntent = SessionLauncher.intentFor(context, profile)
            .putExtra("from_shortcut", true)
        views.setOnClickFillInIntent(R.id.widget_list_item_root, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}

/**
 * Shared population logic for both widget sizes — widget_quick_connect_small.xml
 * (a single card) and widget_list_item.xml (one row) intentionally use the exact
 * same view-id shape (see both layouts' doc comments) so a connection's
 * name/host:port·protocol/connected-dot rendering can never drift between the two.
 */
object WidgetViewBinder {
    fun bind(context: Context, views: RemoteViews, profile: RdpProfile) {
        views.setTextViewText(android.R.id.text1, profile.name)
        views.setTextViewText(
            android.R.id.text2,
            context.getString(R.string.widget_row_subtitle, profile.host, profile.port, profile.protocolType.label),
        )
        views.setImageViewResource(
            R.id.widget_status_dot,
            if (profile.isConnected) R.drawable.widget_status_dot_connected else R.drawable.widget_status_dot_disconnected,
        )
        // Deliberately the same generic "connect" glyph for every protocol — see
        // ic_tile_quick_connect.xml's own doc comment for why a per-protocol icon
        // isn't used here either.
        views.setImageViewResource(R.id.widget_icon, R.drawable.ic_tile_quick_connect)
    }
}
