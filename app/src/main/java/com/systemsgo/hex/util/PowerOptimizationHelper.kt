package com.systemsgo.hex.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * OEM-COMPAT FIX: helps the user keep background RDP/VNC/SSH sessions alive
 * on device manufacturers whose custom Android skins kill apps far more
 * aggressively than stock Android — even apps correctly running a
 * foreground service with an active notification.
 *
 * Stock Android's contract is: a foreground service + a visible notification
 * should not be killed by routine memory/battery management. Xiaomi (MIUI),
 * Honor/Huawei (MagicUI/EMUI), Oppo (ColorOS), Vivo (FuntouchOS/OriginOS),
 * and to a lesser extent Samsung (One UI) and OnePlus (OxygenOS) layer
 * their own "smart" battery managers and per-app "Autostart"/"App launch"
 * controls on top of this that silently override it. There is no public
 * Android API to fix this from inside the app — the only real fix is asking
 * the user to flip the manufacturer-specific switch, since Google does not
 * standardise these settings. This object centralises that "ask nicely"
 * flow (also documented by the community-maintained
 * "Don't kill my app" project, which catalogues the same intents).
 */
object PowerOptimizationHelper {
    private const val TAG = "PowerOptimization"

    /** True if the app is already exempt from Doze/App-Standby battery optimisation. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Standard Android dialog asking the user to exempt this app from battery
     * optimisation. Works on every OEM (it's a stock AOSP API) but on
     * MIUI/MagicUI/ColorOS/FuntouchOS it is necessary-but-not-sufficient —
     * [autostartIntent] below covers the OEM-specific piece those skins add
     * on top.
     */
    fun ignoreBatteryOptimizationsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /** Generic fallback: the app's own battery-usage settings page. */
    fun appBatterySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * Manufacturer detected from [Build.MANUFACTURER]. Exposed so the UI can
     * show a tailored hint ("on your Xiaomi phone, also enable Autostart…").
     */
    fun detectedOem(): String = Build.MANUFACTURER.lowercase()

    /**
     * Best-effort Intent to the OEM's own "Autostart" / "Manage app launch" /
     * "Protected apps" / "Background app management" screen, where the user
     * must additionally whitelist this app. These are private, undocumented
     * activities that differ per manufacturer (and sometimes per OS version)
     * — there is no AOSP-standard equivalent. We try a short list of known
     * component names per brand and fall back to the generic app-details
     * page if none resolve, so this never crashes even on an unrecognised
     * build of the OEM's ROM.
     */
    fun autostartIntent(context: Context): Intent? {
        val pm = context.packageManager
        val candidates: List<Intent> = when {
            isXiaomi() -> listOf(
                componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                componentIntent("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
            )
            isHonor() -> listOf(
                componentIntent("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                componentIntent("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )
            isHuawei() -> listOf(
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            )
            isOppo() -> listOf(
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                componentIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
            isVivo() -> listOf(
                componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            )
            isSamsung() -> listOf(
                componentIntent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            )
            isOnePlus() -> listOf(
                componentIntent("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            )
            isAsus() -> listOf(
                componentIntent("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
            )
            else -> emptyList()
        }
        for (intent in candidates) {
            if (intent.resolveActivity(pm) != null) return intent
        }
        Log.i(TAG, "No known autostart screen for manufacturer=${Build.MANUFACTURER}; falling back to app details")
        return null
    }

    /** Human-readable OEM hint shown next to the autostart button in Settings. */
    fun autostartHintKey(): AutostartHint = when {
        isXiaomi() -> AutostartHint.XIAOMI
        isHonor() || isHuawei() -> AutostartHint.HONOR_HUAWEI
        isOppo() -> AutostartHint.OPPO
        isVivo() -> AutostartHint.VIVO
        isSamsung() -> AutostartHint.SAMSUNG
        isOnePlus() -> AutostartHint.ONEPLUS
        else -> AutostartHint.GENERIC
    }

    enum class AutostartHint { XIAOMI, HONOR_HUAWEI, OPPO, VIVO, SAMSUNG, ONEPLUS, GENERIC }

    private fun componentIntent(pkg: String, cls: String) =
        Intent().apply {
            component = android.content.ComponentName(pkg, cls)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun isXiaomi() = Build.MANUFACTURER.equals("Xiaomi", true) ||
        Build.MANUFACTURER.equals("Redmi", true) || Build.MANUFACTURER.equals("POCO", true)
    private fun isHonor() = Build.MANUFACTURER.equals("HONOR", true)
    private fun isHuawei() = Build.MANUFACTURER.equals("HUAWEI", true)
    private fun isOppo() = Build.MANUFACTURER.equals("OPPO", true) || Build.MANUFACTURER.equals("realme", true)
    private fun isVivo() = Build.MANUFACTURER.equals("vivo", true) || Build.MANUFACTURER.equals("iQOO", true)
    private fun isSamsung() = Build.MANUFACTURER.equals("samsung", true)
    private fun isOnePlus() = Build.MANUFACTURER.equals("OnePlus", true)
    private fun isAsus() = Build.MANUFACTURER.equals("asus", true)

    /** Launches [intent], falling back to the generic app-details page on any failure. */
    fun launchSafely(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Could not resolve OEM settings screen, falling back", e)
            try {
                context.startActivity(appBatterySettingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { /* nothing more we can do */ }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied opening OEM settings screen", e)
        }
    }
}
