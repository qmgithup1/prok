package com.systemsgo.hex.tile

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.QsTilePreferences
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.remote.SessionLauncher
import com.systemsgo.hex.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * QUICK-SETTINGS-TILE FEATURE (Part 1/2).
 *
 * Adds a "Quick Connect" tile to the system Quick Settings panel (the
 * shade you pull down with two fingers), bound to whichever saved
 * connection the user picked in Settings → Quick Settings Tile (see
 * [QsTilePreferences] / [com.systemsgo.hex.ui.screens.QuickTileSettingsScreen]).
 * Tapping it jumps straight into that session, exactly like tapping a
 * pinned home-screen shortcut ([com.systemsgo.hex.util.ShortcutHelper]) —
 * same target Activity via [SessionLauncher], same "from_shortcut" App Lock
 * gate, same "no credentials in the Intent" trust boundary. A Quick
 * Settings tile is reachable from the lock screen depending on the user's
 * own system privacy setting for "Show on lock screen", which is exactly
 * why App Lock (PIN/biometric) must still apply here — this tile must never
 * become a way to reach a session while bypassing it.
 *
 * Only one connection can be bound at a time: a QS tile has exactly one
 * icon and one label, so unlike the app's dynamic (long-press) shortcuts —
 * which can show several favorites at once — there's no way to represent a
 * whole list here. The bound connection's own name is used as the tile's
 * label so it's still clear *which* connection a tap will reach.
 *
 * @AndroidEntryPoint on a [TileService] is supported by the Hilt Gradle
 * plugin's bytecode transform the same way it is for any other Android
 * component subclass (Activity/Service/BroadcastReceiver/...) — nothing
 * TileService-specific is needed beyond declaring it as a Hilt entry point
 * like every other injected class in this app.
 */
@AndroidEntryPoint
class QuickConnectTileService : TileService() {

    @Inject lateinit var profileRepository: RdpProfileRepository
    @Inject lateinit var qsTilePreferences: QsTilePreferences

    private var listeningJob: Job? = null
    // Lives for the whole service instance, not just one listening session —
    // TileService instances can be reused by the system across multiple
    // onStartListening/onStopListening cycles.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Last profile this tile resolved to, kept for [onClick] so a tap doesn't need its own extra DB read. */
    private var boundProfile: RdpProfile? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            combine(
                qsTilePreferences.selectedProfileIdFlow,
                profileRepository.getAllProfiles(),
            ) { selectedId, profiles ->
                selectedId?.let { id -> profiles.firstOrNull { it.id == id } }
            }.distinctUntilChanged().collect { profile ->
                boundProfile = profile
                render(profile)
            }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Draws the tile's icon/label/subtitle/state for the current [profile] (null = unconfigured). */
    private fun render(profile: RdpProfile?) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_quick_connect)

        if (profile == null) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.qs_tile_label_unconfigured)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.qs_tile_subtitle_unconfigured)
            }
        } else {
            tile.state = if (profile.isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = profile.name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = protocolSubtitle(profile.protocolType)
            }
        }
        tile.updateTile()
    }

    private fun protocolSubtitle(protocolType: ProtocolType): String =
        getString(R.string.qs_tile_subtitle_connect_via, protocolType.label)

    override fun onClick() {
        super.onClick()
        val profile = boundProfile
        if (profile == null) {
            // Unconfigured: send the user to the picker instead of doing nothing.
            launchAndCollapse(MainActivity.quickTileSetupIntent(this))
            return
        }
        // Same routing + App Lock gate as a pinned/dynamic shortcut — see
        // ShortcutHelper's class doc for the full trust-boundary reasoning.
        val launchIntent = SessionLauncher.intentFor(this, profile)
            .setAction(android.content.Intent.ACTION_VIEW)
            .putExtra("from_shortcut", true)
        launchAndCollapse(launchIntent)
    }

    /**
     * [TileService.startActivityAndCollapse(Intent)] is deprecated as of
     * API 34 and throws [UnsupportedOperationException] when called on an
     * API 34+ device — the platform requires a [PendingIntent] instead from
     * that point on. minSdk here is 26, so both paths are reachable on real
     * devices and both must be kept.
     */
    private fun launchAndCollapse(intent: android.content.Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
