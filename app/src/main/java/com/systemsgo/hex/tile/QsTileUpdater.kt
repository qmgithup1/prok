package com.systemsgo.hex.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

/**
 * QUICK-SETTINGS-TILE FEATURE (Part 1/2).
 *
 * Thin wrapper around [TileService.requestListeningState], the system API
 * that asks Android to re-deliver [QuickConnectTileService.onStartListening]
 * "soon" even if the Quick Settings panel isn't open right now — the normal
 * onStartListening/onStopListening lifecycle only fires while the panel is
 * actually visible, so without this a change made *elsewhere* in the app
 * (picking a different tile connection in Settings, or that connection's
 * session connecting/disconnecting) wouldn't be reflected on the tile until
 * the next time the user happened to pull the panel down.
 *
 * Every call site below already has a plain [Context], never anything Hilt-
 * injected — deliberately a stateless object (not a class needing DI) so it
 * can be called from [com.systemsgo.hex.data.repository.QsTilePreferences]
 * and [com.systemsgo.hex.data.repository.RdpProfileRepository] alike without
 * either needing to depend on the other.
 */
object QsTileUpdater {
    fun requestUpdate(context: Context) {
        try {
            TileService.requestListeningState(
                context,
                ComponentName(context, QuickConnectTileService::class.java)
            )
        } catch (_: Exception) {
            // Best-effort only: e.g. no Quick Settings host on this device/
            // form factor (rare, some Android TV/Auto builds). Never worth
            // crashing a profile save/connect over.
        }
    }
}
