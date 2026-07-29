package com.systemsgo.hex.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

/**
 * VPN-AWARE-CONNECTIVITY: single source of truth for detecting active VPN
 * connections and steering remote-session sockets onto the right network.
 *
 * Android exposes exactly one signal for "is a VPN active", regardless of
 * which app provides it: [NetworkCapabilities.TRANSPORT_VPN] on one of
 * [ConnectivityManager.getAllNetworks]. Every VPN client on the platform —
 * Tailscale, ZeroTier, NetBird, WireGuard, OpenVPN, SoftEther, Cisco
 * AnyConnect, FortiClient, Palo Alto GlobalProtect, Microsoft Always On VPN,
 * the built-in Settings > VPN screen, or any other app built on
 * [android.net.VpnService] — surfaces this way, because VpnService is the
 * only public API third-party apps have for creating a tunnel interface.
 * There is therefore no vendor-specific code anywhere in this file: detecting
 * "TRANSPORT_VPN present" is both necessary and sufficient for every VPN app
 * listed above, including ones not explicitly named here.
 *
 * The other half of "VPN-aware" is steering traffic. [applyBinding] uses
 * [ConnectivityManager.bindProcessToNetwork], which — per its documented
 * behaviour — affects every socket this *process* subsequently opens,
 * Java or native, and every DNS query those sockets make (including a VPN's
 * own private DNS, e.g. Tailscale MagicDNS, since Android resolves hostnames
 * using whichever network a socket/query is bound to). Binding the process is
 * what makes this transparent to RDP (native FreeRDP sockets), SSH (JSch
 * sockets) and VNC (bVNC sockets) alike, with zero protocol-specific code and
 * no per-connection configuration required from the user beyond picking a
 * preference once in Settings.
 */
object VpnConnectivityManager {

    /** How a new remote-session connection should be allowed to route. */
    enum class NetworkBindingPreference {
        ANY, VPN_ONLY, WIFI_ONLY, CELLULAR_ONLY;

        companion object {
            fun fromSettingValue(value: String): NetworkBindingPreference =
                entries.firstOrNull { it.name == value } ?: ANY
        }
    }

    /** Snapshot of the currently active VPN connection, for UI display. */
    data class VpnStatus(
        val isActive: Boolean,
        val hasIpv4: Boolean = false,
        val hasIpv6: Boolean = false,
        val dnsServerCount: Int = 0,
        val interfaceName: String? = null,
    ) {
        companion object {
            val INACTIVE = VpnStatus(isActive = false)
        }
    }

    /** Coarse result of [checkHostReachability]. */
    enum class HostReachability { REACHABLE_ANY, VPN_ONLY, UNREACHABLE, UNKNOWN }

    private fun connectivityManager(context: Context): ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager

    /**
     * The currently active VPN [Network], if any. Works for every
     * VpnService-based app (see class doc) — there is nothing vendor-specific
     * to detect here.
     */
    fun activeVpnNetwork(context: Context): Network? {
        val cm = connectivityManager(context) ?: return null
        return try {
            cm.allNetworks.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Live-reads the current VPN status (IPv4/IPv6 support, DNS, interface name). */
    fun currentVpnStatus(context: Context): VpnStatus {
        val cm = connectivityManager(context) ?: return VpnStatus.INACTIVE
        val net = activeVpnNetwork(context) ?: return VpnStatus.INACTIVE
        val props: LinkProperties? = try {
            cm.getLinkProperties(net)
        } catch (_: Exception) {
            null
        }
        val hasIpv4 = props?.linkAddresses?.any { it.address.address.size == 4 } ?: false
        val hasIpv6 = props?.linkAddresses?.any { it.address.address.size == 16 } ?: false
        return VpnStatus(
            isActive = true,
            hasIpv4 = hasIpv4,
            hasIpv6 = hasIpv6,
            dnsServerCount = props?.dnsServers?.size ?: 0,
            interfaceName = props?.interfaceName,
        )
    }

    /**
     * Live VPN status, updated whenever a VPN connects, disconnects, or its
     * link properties change (e.g. Tailscale/ZeroTier renegotiating
     * addresses). Used to show VPN status before connecting and to detect
     * disconnections during an active session.
     */
    fun observeVpnStatus(context: Context): Flow<VpnStatus> = callbackFlow {
        val cm = connectivityManager(context)
        if (cm == null) {
            trySend(VpnStatus.INACTIVE)
            close()
            return@callbackFlow
        }
        trySend(currentVpnStatus(context))
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentVpnStatus(context))
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(currentVpnStatus(context))
            }
            override fun onLost(network: Network) {
                trySend(currentVpnStatus(context))
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
            // No VPN capability support on this device/OEM build — fail safe to INACTIVE.
        }
        awaitClose {
            try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) { android.util.Log.d("VpnConnectivityManager", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }.distinctUntilChanged()

    /**
     * Emits `true` the moment a VPN becomes available and `false` the moment
     * it's lost. Session reconnect logic uses this to wait for the tunnel to
     * come back instead of retrying blindly against a dead link (see
     * RdpSessionViewModel's reconnect scheduling).
     */
    fun vpnAvailabilityEvents(context: Context): Flow<Boolean> = callbackFlow {
        val cm = connectivityManager(context)
        if (cm == null) {
            trySend(false)
            close()
            return@callbackFlow
        }
        trySend(activeVpnNetwork(context) != null)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(activeVpnNetwork(context) != null) }
        }
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
        }
        awaitClose {
            try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) { android.util.Log.d("VpnConnectivityManager", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        }
    }.distinctUntilChanged()

    /** Finds a [Network] matching [preference], or null if none is currently available. */
    fun findNetwork(context: Context, preference: NetworkBindingPreference): Network? {
        if (preference == NetworkBindingPreference.ANY) return null
        val cm = connectivityManager(context) ?: return null
        val wantedTransport = when (preference) {
            NetworkBindingPreference.VPN_ONLY -> NetworkCapabilities.TRANSPORT_VPN
            NetworkBindingPreference.WIFI_ONLY -> NetworkCapabilities.TRANSPORT_WIFI
            NetworkBindingPreference.CELLULAR_ONLY -> NetworkCapabilities.TRANSPORT_CELLULAR
            NetworkBindingPreference.ANY -> return null
        }
        return try {
            cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null &&
                    caps.hasTransport(wantedTransport) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Binds this process's default network — for all *new* sockets, Java and
     * native alike — to the network matching [preference]. Because
     * [ConnectivityManager.bindProcessToNetwork] is process-wide, this
     * transparently covers the native FreeRDP bridge (RDP), JSch (SSH) and
     * bVNC (VNC) without any protocol-specific plumbing, and it makes DNS
     * resolution (including a VPN's own private DNS, e.g. Tailscale
     * MagicDNS) go over the same network automatically.
     *
     * [NetworkBindingPreference.ANY] clears any existing binding so the OS
     * resumes picking its normal default network. For the other preferences,
     * returns `false` (and leaves any previous binding untouched) if no
     * matching network is currently available, so callers can surface a
     * clear "VPN/Wi-Fi/Cellular not available" message instead of silently
     * connecting over the wrong network.
     */
    fun applyBinding(context: Context, preference: NetworkBindingPreference): Boolean {
        val cm = connectivityManager(context)
            ?: return preference == NetworkBindingPreference.ANY
        if (preference == NetworkBindingPreference.ANY) {
            return try {
                cm.bindProcessToNetwork(null)
                true
            } catch (_: Exception) {
                false
            }
        }
        val network = findNetwork(context, preference) ?: return false
        return try {
            cm.bindProcessToNetwork(network)
        } catch (_: Exception) {
            false
        }
    }

    /** Releases any process-wide network binding, returning to the OS default. */
    fun clearBinding(context: Context) {
        try {
            connectivityManager(context)?.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
    }

    /**
     * Best-effort probe of whether [host]:[port] is reachable over the active
     * VPN, a non-VPN network, both, or neither — used to warn the user before
     * connecting that a target is only reachable through their VPN. Each
     * attempt is scoped to a specific [Network] via [Network.getSocketFactory]
     * (and DNS is resolved on that same network via [Network.getAllByName],
     * which is what lets this correctly resolve VPN-private hostnames like
     * Tailscale MagicDNS names), so this never touches the process-wide
     * binding an active session might already depend on.
     */
    suspend fun checkHostReachability(
        context: Context,
        host: String,
        port: Int,
        timeoutMs: Int = 2_000,
    ): HostReachability = withContext(Dispatchers.IO) {
        val cm = connectivityManager(context) ?: return@withContext HostReachability.UNKNOWN
        val vpnNet = activeVpnNetwork(context)
        val nonVpnNet = try {
            cm.allNetworks.firstOrNull { net ->
                net != vpnNet &&
                    cm.getNetworkCapabilities(net)?.let { caps ->
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    } == true
            }
        } catch (_: Exception) {
            null
        }

        val vpnReachable = vpnNet?.let { probe(it, host, port, timeoutMs) } ?: false
        val nonVpnReachable = nonVpnNet?.let { probe(it, host, port, timeoutMs) } ?: false

        return@withContext when {
            vpnReachable && !nonVpnReachable -> HostReachability.VPN_ONLY
            vpnReachable || nonVpnReachable -> HostReachability.REACHABLE_ANY
            vpnNet == null && nonVpnNet == null -> HostReachability.UNKNOWN
            else -> HostReachability.UNREACHABLE
        }
    }

    private fun probe(network: Network, host: String, port: Int, timeoutMs: Int): Boolean =
        try {
            val address = network.getAllByName(host).firstOrNull() ?: return false
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(address, port), timeoutMs)
            }
            true
        } catch (_: Exception) {
            false
        }
}
