package com.systemsgo.hex.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.data.repository.WidgetPreferences
import com.systemsgo.hex.remote.SessionLauncher
import com.systemsgo.hex.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * HOME-SCREEN-WIDGET FEATURE (Part 1/2).
 *
 * Places a resizable "saved connections" widget on the user's home screen — the
 * third way (alongside a pinned shortcut, [com.systemsgo.hex.util.ShortcutHelper],
 * and the Quick Settings tile, [com.systemsgo.hex.tile.QuickConnectTileService]) to
 * jump straight into a session without opening the app's own connection list.
 * Every configured instance (see [WidgetConfigureActivity]) is either bound to one
 * connection ([WidgetPreferences.DisplayMode.SINGLE_CONNECTION], rendered by
 * [buildSingleConnectionViews]) or shows a scrollable list of several
 * ([WidgetPreferences.DisplayMode.CONNECTION_LIST], rendered by
 * [buildConnectionListViews] + [RdpProfileWidgetService]) — see [chooseEffectiveMode]
 * for how the *placed* size can force a list instance down to the single-card
 * layout when there isn't room to usefully scroll.
 *
 * Same trust boundary as every other launch surface in this app: every click here
 * ultimately routes through [SessionLauncher.intentFor] with `"from_shortcut" = true`,
 * so App Lock (PIN/biometric) still gates the session exactly as it would from a
 * pinned shortcut or the tile — see [com.systemsgo.hex.util.ShortcutHelper]'s class
 * doc for the full reasoning. A home screen widget is visible (and, depending on the
 * user's own system setting, tappable) from the lock screen the same way a Quick
 * Settings tile can be, which is exactly why that gate matters here too. No
 * credentials are ever placed in a widget's [RemoteViews] or any [PendingIntent]'s
 * extras — only a `profile_id`, the same as every other launch surface.
 *
 * `@AndroidEntryPoint` on an [AppWidgetProvider] (itself a
 * [android.content.BroadcastReceiver] subclass) follows Hilt's documented
 * BroadcastReceiver support the same way [com.systemsgo.hex.tile.QuickConnectTileService]
 * follows its TileService support — see that class's doc comment for the general
 * shape. The one BroadcastReceiver-specific wrinkle: Hilt injects fields inside
 * *this class's own* `onReceive()`, so unlike a Service/Activity there has to be an
 * explicit (even if trivial) `onReceive()` override below calling `super.onReceive()`
 * first — without it, the injected fields used by [onUpdate]/[onAppWidgetOptionsChanged]/
 * [onDeleted] (all invoked from deeper inside [AppWidgetProvider]'s own `onReceive()`
 * dispatch logic) would still be null when those callbacks run.
 */
@AndroidEntryPoint
class SystemsGoAppWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var profileRepository: RdpProfileRepository
    @Inject lateinit var widgetPreferences: WidgetPreferences

    // Lives only as long as a single onReceive() dispatch (onUpdate/onAppWidgetOptionsChanged
    // are called on the main thread with no coroutine scope of their own to launch into,
    // same constraint RdpProfileRemoteViewsFactory documents for its own callbacks) — paired
    // with goAsync() below so the DB read in buildAndPushWidget() can run off the main thread
    // without the system tearing down this receiver mid-update.
    //
    // SCOPE-LEAK REVIEW (Part 2/2): AppWidgetProvider has no onDestroy the way a
    // Service/Activity does, so there's no symmetrical place to call
    // receiverScope.cancel() the way MainViewModel.onCleared() cancels its own
    // viewModelScope. In practice this doesn't leak: the platform constructs a
    // fresh AppWidgetProvider instance for each onReceive() dispatch (it's a plain
    // BroadcastReceiver subclass, never reused across broadcasts the way a Service
    // is), so this instance — and the CoroutineScope it holds — becomes eligible
    // for GC the moment its one launch{} block finishes and no other reference to
    // it survives; goAsync()'s pendingResult.finish() only signals the *system*
    // that background work is done, it doesn't by itself keep this instance alive.
    // Still, two changes below make this more defensive rather than relying purely
    // on that reasoning: a SupervisorJob so one appWidgetId throwing inside
    // onUpdate()'s loop can't cancel the sibling ids still to be processed in the
    // same dispatch, and an explicit cancel() in each handler's finally block so
    // the scope's job is torn down deterministically rather than left to GC even
    // in the (currently impossible, but not contract-guaranteed) case of the
    // platform reusing an instance.
    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        // See this class's doc comment: this trivial override exists solely so Hilt has
        // an onReceive() of its own to inject profileRepository/widgetPreferences into
        // before AppWidgetProvider's onReceive() dispatches down into onUpdate() etc.
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                appWidgetIds.forEach { appWidgetId ->
                    // Each id is wrapped individually (rather than letting one
                    // failure abort the forEach) so, e.g., a single instance
                    // whose bound profile id no longer resolves cleanly
                    // doesn't also leave every other placed instance stuck on
                    // stale content until the next unrelated broadcast.
                    try {
                        buildAndPushWidget(context, appWidgetManager, appWidgetId)
                    } catch (e: Exception) {
                        android.util.Log.e("SystemsGoAppWidgetProvider", "Failed to update widget $appWidgetId", e)
                    }
                }
            } finally {
                pendingResult.finish()
                receiverScope.cancel()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Fires whenever the user drags this instance to a new size — rebuild so
        // chooseEffectiveMode() can swap between the small card and the scrollable
        // list as it crosses the size threshold, exactly like a well-behaved
        // resizable platform widget (Calendar, Gmail, ...) does.
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                buildAndPushWidget(context, appWidgetManager, appWidgetId, newOptions)
            } catch (e: Exception) {
                android.util.Log.e("SystemsGoAppWidgetProvider", "Failed to update widget $appWidgetId on resize", e)
            } finally {
                pendingResult.finish()
                receiverScope.cancel()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Mirrors QsTilePreferences' "don't leak state for a thing that no longer
        // exists" cleanup — see WidgetPreferences.clearForWidget's doc comment.
        appWidgetIds.forEach { widgetPreferences.clearForWidget(it) }
    }

    /** Builds and pushes the correct [RemoteViews] for one instance, given its current placed size. */
    private suspend fun buildAndPushWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        options: Bundle = appWidgetManager.getAppWidgetOptions(appWidgetId),
    ) {
        val config = widgetPreferences.configSnapshot(appWidgetId)
        val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val effectiveMode = chooseEffectiveMode(config, minHeightDp)

        val views = if (effectiveMode == WidgetPreferences.DisplayMode.SINGLE_CONNECTION) {
            val profile = resolveSingleConnectionProfile(config)
            buildSingleConnectionViews(context, appWidgetId, profile)
        } else {
            buildConnectionListViews(context, appWidgetId, config)
        }
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    /**
     * A [WidgetPreferences.DisplayMode.CONNECTION_LIST] instance degrades to the
     * single-card layout when the user has resized it shorter than
     * [LIST_MIN_USABLE_HEIGHT_DP] — a `ListView` that can only ever show a sliver of
     * one row is worse than a single glanceable card, the same "graceful shrink"
     * behavior platform widgets like Calendar's agenda widget use at their own
     * smallest size. [WidgetPreferences.DisplayMode.SINGLE_CONNECTION] instances are
     * never affected — they're already the small layout at every size.
     */
    private fun chooseEffectiveMode(config: WidgetPreferences.WidgetConfig, minHeightDp: Int): WidgetPreferences.DisplayMode =
        if (config.mode == WidgetPreferences.DisplayMode.SINGLE_CONNECTION || minHeightDp in 1 until LIST_MIN_USABLE_HEIGHT_DP) {
            WidgetPreferences.DisplayMode.SINGLE_CONNECTION
        } else {
            WidgetPreferences.DisplayMode.CONNECTION_LIST
        }

    /**
     * For [WidgetPreferences.DisplayMode.SINGLE_CONNECTION] this is just the
     * explicitly bound profile. For a [WidgetPreferences.DisplayMode.CONNECTION_LIST]
     * instance that [chooseEffectiveMode] downgraded to the small layout, there's no
     * single bound profile to fall back to — show the same top-of-list connection
     * (favorites first, then most recently connected) the list would otherwise have
     * shown first, so the degraded card is never just an empty/dead tap target
     * whenever the user actually has connections saved.
     */
    private suspend fun resolveSingleConnectionProfile(config: WidgetPreferences.WidgetConfig): RdpProfile? {
        if (config.mode == WidgetPreferences.DisplayMode.SINGLE_CONNECTION) {
            val id = config.singleProfileId ?: return null
            return profileRepository.getProfileById(id)
        }
        val all = profileRepository.getAllProfiles().first()
        val filtered = when (config.listFilter) {
            WidgetPreferences.ListFilter.ALL -> all
            WidgetPreferences.ListFilter.FAVORITES_ONLY -> all.filter { it.isFavorite }
        }
        return filtered.sortedWith(compareByDescending<RdpProfile> { it.isFavorite }.thenByDescending { it.lastConnected })
            .firstOrNull()
    }

    /** Builds the single quick-connect card — bound profile, or an "unconfigured/tap to set up" state if [profile] is null. */
    private fun buildSingleConnectionViews(context: Context, appWidgetId: Int, profile: RdpProfile?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_connect_small)

        if (profile == null) {
            views.setTextViewText(android.R.id.text1, context.getString(R.string.widget_unconfigured_title))
            views.setTextViewText(android.R.id.text2, context.getString(R.string.widget_unconfigured_subtitle))
            views.setImageViewResource(R.id.widget_status_dot, R.drawable.widget_status_dot_disconnected)
            views.setImageViewResource(R.id.widget_icon, R.drawable.ic_tile_quick_connect)
            // Same "tap to set up" recovery as QuickConnectTileService.onClick()'s
            // unconfigured branch — jumps back into configuration rather than doing
            // nothing, e.g. after the bound connection was deleted elsewhere.
            views.setOnClickPendingIntent(
                R.id.widget_root_row,
                pendingActivity(context, appWidgetId, WidgetConfigureActivity.reconfigureIntent(context, appWidgetId)),
            )
            return views
        }

        WidgetViewBinder.bind(context, views, profile)
        val launchIntent = SessionLauncher.intentFor(context, profile)
            .setAction(Intent.ACTION_VIEW)
            .putExtra("from_shortcut", true)
        views.setOnClickPendingIntent(R.id.widget_root_row, pendingActivity(context, appWidgetId, launchIntent))
        return views
    }

    /** Builds the scrollable-list card: header + `RdpProfileWidgetService`-backed `ListView`. */
    private fun buildConnectionListViews(context: Context, appWidgetId: Int, config: WidgetPreferences.WidgetConfig): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_connections_list)

        // A unique data Uri (not just the extras) is required per widget id — the
        // framework de-dupes/caches RemoteViewsService binds by Intent, and Intent
        // extras alone don't factor into that comparison, so two instances would
        // otherwise share one adapter's data. This is the standard documented
        // work-around (same one Android's own AppWidget samples use).
        val adapterIntent = Intent(context, RdpProfileWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse("systemsgo-widget://widget/$appWidgetId")
        }
        views.setRemoteAdapter(android.R.id.list, adapterIntent)

        val emptyText = when (config.listFilter) {
            WidgetPreferences.ListFilter.ALL -> R.string.widget_empty_no_connections
            WidgetPreferences.ListFilter.FAVORITES_ONLY -> R.string.widget_empty_no_favorites
        }
        views.setTextViewText(R.id.widget_empty_text, context.getString(emptyText))
        views.setEmptyView(android.R.id.list, android.R.id.empty)

        // Template + per-row fillInIntent, the required pattern for a RemoteViewsService-
        // backed collection — see RdpProfileRemoteViewsFactory.getViewAt()'s doc comment.
        // FLAG_MUTABLE is required here specifically (unlike every other PendingIntent in
        // this file, which are immutable): the platform has to be able to merge each row's
        // fillInIntent into this template at click time, which an immutable PendingIntent
        // would refuse.
        val templateIntent = Intent(Intent.ACTION_VIEW)
        val templateFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val template = PendingIntent.getActivity(context, appWidgetId, templateIntent, templateFlags)
        views.setPendingIntentTemplate(android.R.id.list, template)

        // Header "open app" glyph — always available regardless of list contents.
        views.setOnClickPendingIntent(
            R.id.widget_open_app_button,
            pendingActivity(context, appWidgetId, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
        )
        return views
    }

    /** Builds an immutable, per-widget-id-unique activity [PendingIntent] — every non-list click in this file goes through here. */
    private fun pendingActivity(context: Context, appWidgetId: Int, intent: Intent): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        // requestCode = appWidgetId keeps every instance's PendingIntent distinct — without
        // this, two placed instances bound to different connections would collide and both
        // fire whichever Intent was registered last.
        return PendingIntent.getActivity(context, appWidgetId, intent, flags)
    }

    companion object {
        /** Below this placed height (dp), a CONNECTION_LIST instance degrades to the single-card layout — see [chooseEffectiveMode]. */
        private const val LIST_MIN_USABLE_HEIGHT_DP = 110
    }
}
