package com.systemsgo.hex.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * WARP-STATUS FEATURE.
 *
 * Best-effort detection of Cloudflare WARP (the "1.1.1.1: Faster Internet"
 * app, package [WARP_PACKAGE_NAME]) — surfaces the [R.string.warp_connected]
 * string that previously had no code behind it at all (see the audit
 * report's "strings with no code" section).
 *
 * IMPORTANT LIMITATION, stated up front rather than implied by a confident
 * green checkmark: normal Android apps have no API to ask "which app owns
 * the currently active VPN tunnel." [ConnectivityManager] can only tell us
 * *that* some VPN network is active (NetworkCapabilities.TRANSPORT_VPN), not
 * *whose*. So [CONNECTED] here means "the WARP app is installed AND some VPN
 * is currently active" — true whenever WARP itself is that VPN, but also
 * true if the user has WARP installed yet is connected through a different
 * VPN app entirely. This is disclosed to the user via
 * [R.string.warp_status_heuristic_notice] rather than presented as a
 * guaranteed fact.
 */
object WarpStatusChecker {

    const val WARP_PACKAGE_NAME = "com.cloudflare.onedotonedotonedotone"

    enum class WarpState {
        /** The WARP app isn't installed on this device at all. */
        NOT_INSTALLED,
        /** WARP is installed but no VPN network is currently active. */
        INSTALLED_DISCONNECTED,
        /** WARP is installed and *some* VPN network is active — see class doc's caveat. */
        CONNECTED,
    }

    fun isWarpAppInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(WARP_PACKAGE_NAME, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun isAnyVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun currentState(context: Context): WarpState {
        if (!isWarpAppInstalled(context)) return WarpState.NOT_INSTALLED
        return if (isAnyVpnActive(context)) WarpState.CONNECTED else WarpState.INSTALLED_DISCONNECTED
    }
}
