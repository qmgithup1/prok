package com.systemsgo.hex.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.ui.screens.RdpSessionActivity

/**
 * HOME-SCREEN-SHORTCUTS FEATURE: lets the user pin a home-screen shortcut for
 * any saved connection, so tapping it jumps straight into that session
 * without opening the app's connection list first.
 *
 * Uses the Android Pinned Shortcuts API (via [ShortcutManagerCompat], which
 * transparently no-ops on API levels/launchers that don't support it —
 * nothing here needs an SDK_INT check of its own; see [isPinningSupported]).
 * Each shortcut's Intent targets [RdpSessionActivity] directly (the same
 * Activity + "profile_id" extra HomeScreen already uses to launch a session —
 * see HomeScreen.kt's onConnect) rather than routing through MainActivity, so
 * the Launcher can jump straight to the session with no intermediate screen.
 * This requires RdpSessionActivity to be exported (see the AndroidManifest.xml
 * comment on that Activity): the Launcher process is external to our app, and
 * Android refuses to start a non-exported Activity from outside the app's own
 * process. The trust boundary this opens is the same one MainActivity's
 * existing "open a .rdp link" ACTION_VIEW intent-filter already relies on —
 * an external caller can only ever start a session for a profile_id that
 * references an existing, locally-saved profile; no credentials or
 * connection details are ever passed in the shortcut's Intent itself.
 *
 * Surviving app updates: the shortcut id is derived only from the stable
 * profile id (see [shortcutId]), and both the target component
 * (RdpSessionActivity) and its "profile_id" contract are part of the app's
 * own long-term API, not anything update-churned — so a pinned shortcut
 * keeps resolving correctly after any app update, exactly as Android's own
 * "pinned shortcuts must survive updates" guidance expects. No
 * re-registration step is needed on app start.
 *
 * App Lock: if the user has PIN/biometric App Lock enabled, tapping a
 * shortcut still lands in RdpSessionActivity, but that Activity now shows the
 * same lock screen used elsewhere before it proceeds to connect — see the
 * "from_shortcut" extra set below and RdpSessionActivity.onCreate().
 */
object ShortcutHelper {

    /** Stable shortcut id per profile, so re-pinning the same connection updates rather than duplicates it. */
    private fun shortcutId(profileId: String) = "profile_$profileId"

    /** Whether the current launcher supports pinning shortcuts at all (not all OEM launchers do). */
    fun isPinningSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * Picks a shortcut icon based on the connection's protocol. There is no
     * per-profile custom-icon field in [RdpProfile] today, so this is the
     * only icon source; if one is ever added, it should be checked here
     * first, with this protocol icon kept as the fallback.
     */
    private fun iconResFor(protocolType: ProtocolType): Int = when (protocolType) {
        ProtocolType.RDP -> R.drawable.ic_shortcut_rdp
        ProtocolType.VNC -> R.drawable.ic_shortcut_vnc
        ProtocolType.SSH -> R.drawable.ic_shortcut_ssh
        // No dedicated Telnet artwork exists yet — reuse the SSH terminal
        // icon, since both are the same "text terminal" shortcut shape.
        ProtocolType.TELNET -> R.drawable.ic_shortcut_ssh
        ProtocolType.RLOGIN -> R.drawable.ic_shortcut_ssh
        // MOSH FEATURE: no dedicated Mosh artwork yet — reuse the SSH
        // terminal icon, same reasoning as TELNET/RLOGIN just above.
        ProtocolType.MOSH -> R.drawable.ic_shortcut_ssh
        // SPICE-PROTOCOL FEATURE (Part 1/N): no dedicated SPICE artwork yet
        // — reuse the RDP shortcut icon as a placeholder (both are
        // framebuffer/canvas protocols) until real SPICE artwork lands.
        ProtocolType.SPICE -> R.drawable.ic_shortcut_rdp
        // RTSP FEATURE: no dedicated shortcut glyph yet — reuses the generic
        // RDP-family icon like every other non-terminal, non-web protocol
        // above that also has no bespoke drawable (REDFISH/IPMI/AMT/SNMP/GUACAMOLE).
        ProtocolType.RTSP -> R.drawable.ic_shortcut_rdp
        // WEB-PORTAL FEATURE: dedicated browser-chrome + globe artwork, see
        // ic_shortcut_web.xml.
        ProtocolType.WEB -> R.drawable.ic_shortcut_web
        // REDFISH-IPMI FEATURE: no dedicated BMC/server artwork exists yet —
        // reuse the RDP icon (both are "manage a remote machine" shortcuts);
        // swap for dedicated art if/when it's added, per this fun's doc above.
        ProtocolType.REDFISH -> R.drawable.ic_shortcut_rdp
        ProtocolType.IPMI -> R.drawable.ic_shortcut_rdp
        // AMT-VPRO FEATURE: same "no dedicated artwork yet, reuse RDP icon"
        // reasoning as REDFISH/IPMI just above.
        ProtocolType.AMT -> R.drawable.ic_shortcut_rdp
        // SERIAL-CONSOLE FEATURE: no dedicated artwork yet — reuse the
        // Telnet-family icon reasoning above isn't available here (drawable,
        // not a vector), so fall back to the same "reuse RDP icon" choice
        // REDFISH/IPMI/AMT already make above.
        ProtocolType.SERIAL_CONSOLE -> R.drawable.ic_shortcut_rdp
        // RESTCONF FEATURE (Part 1/4): no dedicated artwork yet — reuse the
        // Web-portal icon (both are HTTP/REST-based), same "reuse the
        // closest existing icon" reasoning as REDFISH/IPMI/AMT above.
        ProtocolType.RESTCONF -> R.drawable.ic_shortcut_web
        // SNMP FEATURE: same "no dedicated artwork yet" situation as the
        // rest of this list — reuse the RDP icon for now.
        ProtocolType.SNMP -> R.drawable.ic_shortcut_rdp
        // NETCONF FEATURE: NETCONF is SSH-transported, structured-config
        // management (not a framebuffer) — closer in spirit to the SSH
        // shortcut than to RDP, so reuse ic_shortcut_ssh rather than the
        // REDFISH/IPMI/AMT "reuse RDP icon" convention above.
        ProtocolType.NETCONF -> R.drawable.ic_shortcut_ssh
        // GUACAMOLE-PROTOCOL FEATURE: same "no dedicated artwork yet, reuse
        // RDP icon" reasoning as REDFISH/IPMI/AMT/SERIAL_CONSOLE above —
        // Guacamole is a framebuffer/canvas protocol from the
        // shortcut-icon's point of view, same family as RDP.
        ProtocolType.GUACAMOLE -> R.drawable.ic_shortcut_rdp
        // PROXMOX-API FEATURE: no dedicated artwork yet — reuse the Web-portal
        // icon (Proxmox's own API is REST/HTTPS, same family as RESTCONF above).
        ProtocolType.PROXMOX -> R.drawable.ic_shortcut_web
        // MODBUS-TCP FEATURE (Part 2/2): no dedicated artwork yet — reuse the RDP icon.
        ProtocolType.MODBUS_TCP -> R.drawable.ic_shortcut_rdp
        ProtocolType.VIRTUALBOX_VRDE -> R.drawable.ic_shortcut_rdp
        ProtocolType.VMWARE_VSPHERE -> R.drawable.ic_shortcut_web
        // WAKE-ON-LAN-STANDALONE FEATURE: no dedicated artwork yet — reuse the
        // RDP icon, same "reuse the closest existing icon" fallback as
        // REDFISH/IPMI/AMT/SERIAL_CONSOLE/SNMP/MODBUS_TCP above.
        ProtocolType.WAKE_ON_LAN -> R.drawable.ic_shortcut_rdp
        // SFTP-STANDALONE FEATURE: no dedicated artwork yet — reuse the SSH
        // icon, same family as TELNET/RLOGIN/MOSH above (this is the same
        // SSH/SFTP wire protocol, just a file browser instead of a shell).
        ProtocolType.SFTP -> R.drawable.ic_shortcut_ssh
        // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: no dedicated artwork
        // for any of the five yet — reuse the RDP icon, same "reuse the
        // closest existing icon" fallback every protocol without bespoke art
        // uses above (REDFISH/IPMI/AMT/SNMP/MODBUS_TCP/WAKE_ON_LAN/...).
        ProtocolType.FTP -> R.drawable.ic_shortcut_rdp
        ProtocolType.FTPS -> R.drawable.ic_shortcut_rdp
        ProtocolType.WEBDAV -> R.drawable.ic_shortcut_rdp
        ProtocolType.SMB -> R.drawable.ic_shortcut_rdp
        ProtocolType.NFS -> R.drawable.ic_shortcut_rdp
    }

    /** Pixel size to rasterize shortcut icons at — see [iconCompatFor]. */
    private const val ICON_BITMAP_SIZE_PX = 192

    /**
     * ICON-FIX: builds the actual [IconCompat] used by both pinned and
     * dynamic shortcuts below.
     *
     * Previously this called `IconCompat.createWithResource(context, resId)`
     * directly, which just wraps the resource id and defers loading it to
     * whichever process later reads the shortcut — normally the Launcher
     * app, which is a *different process* than ours. That resolution can
     * silently fail (showing no icon / a generic fallback, with the label
     * sometimes missing too) when: the Launcher's Resources lookup for our
     * package doesn't line up (some OEM launchers are inconsistent about
     * this for vector drawables specifically), or — the likely cause here,
     * since this build has isMinifyEnabled/isShrinkResources on for release
     * (see app/build.gradle.kts) — R8's resource shrinker/aapt2 renumbers
     * resource ids between builds, and a stale id can end up pinned to the
     * home screen from an older install.
     *
     * Rasterizing to a [Bitmap] *in our own process* and shipping that
     * pixel data via [IconCompat.createWithBitmap] sidesteps both problems
     * entirely: there is no cross-process resource id to resolve, so the
     * icon always renders, independent of Launcher/OEM/build quirks.
     */
    private fun iconCompatFor(context: Context, protocolType: ProtocolType): IconCompat {
        val resId = iconResFor(protocolType)
        val bitmap: Bitmap? = ContextCompat.getDrawable(context, resId)?.toBitmap(
            width = ICON_BITMAP_SIZE_PX,
            height = ICON_BITMAP_SIZE_PX,
            config = Bitmap.Config.ARGB_8888,
        )
        return if (bitmap != null) {
            IconCompat.createWithBitmap(bitmap)
        } else {
            // Extremely unlikely (resource missing entirely) — fall back to
            // the old behavior rather than crash.
            IconCompat.createWithResource(context, resId)
        }
    }

    /**
     * Requests that the system pin a home-screen shortcut for [profile].
     * Tapping it launches [RdpSessionActivity] with that profile's id, which
     * connects immediately (or, if App Lock is on, prompts for it first) —
     * identical to tapping the connection's card in the app itself, just
     * without an intermediate screen.
     *
     * @param shortcutName user-customizable label shown under the icon;
     *   falls back to the connection's own name if left blank.
     * @return false if the current launcher doesn't support shortcut
     *   pinning; callers should let the user know in that case.
     */
    fun requestPinShortcut(
        context: Context,
        profile: RdpProfile,
        shortcutName: String = profile.name,
    ): Boolean {
        if (!isPinningSupported(context)) return false

        val label = shortcutName.trim().ifBlank { profile.name }

        val launchIntent = com.systemsgo.hex.remote.SessionLauncher.intentFor(context, profile)
            .setAction(Intent.ACTION_VIEW) // ShortcutInfoCompat requires a non-null action.
            // Tells RdpSessionActivity this launch came from outside the app's
            // own navigation, so it should gate on App Lock before connecting.
            .putExtra("from_shortcut", true)

        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(profile.id))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(iconCompatFor(context, profile.protocolType))
            .setIntent(launchIntent)
            .build()

        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    /**
     * Disables the shortcut associated with a deleted connection, and drops
     * it if it was only ever a long-lived (not actually pinned) shortcut.
     * Android gives apps no way to force-remove a shortcut the user has
     * already pinned to their home screen — only the user can drag it off —
     * so "disable" is the documented best practice for "this shortcut's
     * target no longer exists": the Launcher greys it out and shows
     * [R.string.shortcut_disabled_message] instead of silently trying to
     * launch into a profile that's gone. Safe to call unconditionally on
     * every profile delete, even if no shortcut for that profile was ever
     * created or pinned.
     */
    fun disableShortcut(context: Context, profileId: String) {
        val id = shortcutId(profileId)
        val existing = ShortcutManagerCompat.getShortcuts(
            context,
            ShortcutManagerCompat.FLAG_MATCH_PINNED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC
        )
        if (existing.none { it.id == id }) return

        val disabledMessage = context.getString(R.string.shortcut_disabled_message)
        ShortcutManagerCompat.disableShortcuts(context, listOf(id), disabledMessage)
        ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(id))
    }

    /** Distinct id-namespace from [shortcutId] so a favorite and a pinned shortcut for the same profile never collide. */
    private fun favoriteShortcutId(profileId: String) = "favorite_$profileId"

    /**
     * FAVORITE-SHORTCUTS FEATURE: publishes the app's "Dynamic Shortcuts" —
     * the entries the system shows in the popup menu when the user
     * long-presses the app's launcher icon. Unlike a pinned shortcut (one
     * per connection, placed by the user one at a time), this whole set is
     * replaced every time it's called, so callers should simply call it
     * again with the current list whenever it changes (add/remove favorite,
     * connect to one, delete a profile, etc.) rather than trying to diff it
     * themselves — see MainViewModel's call site for where that's wired up.
     *
     * @param favoriteProfiles the connections to show, already filtered to
     *   [RdpProfile.isFavorite] == true and ordered most-relevant-first
     *   (callers sort by recency of use); only the first few are kept, per
     *   [maxShortcuts].
     *
     * Security — same trust boundary as pinned shortcuts (see this object's
     * class doc), with one addition: every dynamic shortcut's Intent sets
     * "from_shortcut" = true, exactly like [requestPinShortcut] does. That
     * flag is what makes RdpSessionActivity show the App Lock screen (PIN /
     * biometric) before connecting when the user has one configured — see
     * RdpSessionActivity.onCreate's `lockRequired` check. Long-press
     * shortcuts launch that same Activity the same way a pinned shortcut
     * does, so deliberately keeping this flag here (rather than, say,
     * optimizing it away for a "faster" long-press launch) is what prevents
     * this feature from becoming a way to reach a session without passing
     * whatever App Lock the user turned on. Only the connection's own name
     * and protocol are ever exposed on the icon/label — no credentials.
     */
    fun updateFavoriteShortcuts(context: Context, favoriteProfiles: List<RdpProfile>) {
        // Some OEMs report 0/negative here instead of the documented minimum
        // of 3 when queried too early in the process lifecycle; never let
        // that suppress the feature entirely.
        val deviceMax = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val maxShortcuts = if (deviceMax > 0) minOf(3, deviceMax) else 3

        val shortcuts = favoriteProfiles.take(maxShortcuts).mapIndexed { index, profile ->
            val launchIntent = com.systemsgo.hex.remote.SessionLauncher.intentFor(context, profile)
                .setAction(Intent.ACTION_VIEW)
                .putExtra("from_shortcut", true) // see security note above — keeps the App Lock gate

            ShortcutInfoCompat.Builder(context, favoriteShortcutId(profile.id))
                .setShortLabel(profile.name)
                .setLongLabel(profile.name)
                .setIcon(iconCompatFor(context, profile.protocolType))
                .setIntent(launchIntent)
                .setRank(index) // preserves the caller's most-relevant-first ordering in the popup
                .build()
        }

        // setDynamicShortcuts() fully replaces the previous set (including
        // removing entries for profiles that are no longer favorites), so
        // there's no separate "remove" step needed when a favorite is
        // unstarred or deleted — the next call with the updated list handles it.
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }
}
