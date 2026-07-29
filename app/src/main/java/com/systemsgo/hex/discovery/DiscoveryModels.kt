package com.systemsgo.hex.discovery

import com.systemsgo.hex.data.model.ProtocolType

/**
 * AUTO-DISCOVERY FEATURE: how a [DiscoveredDevice] (or one of its detected
 * protocols) was found. A single device can be found by more than one
 * mechanism at once (e.g. announced over mDNS *and* answering a raw port
 * probe) — both are recorded rather than one overwriting the other, since
 * that's useful context for the user ("this is a real advertised service,
 * not just an open port").
 */
enum class DiscoverySource {
    /** Found via Android's NsdManager (mDNS/DNS-SD) — zero-config, no
     *  active network scanning required; the device announced itself. */
    MDNS,

    /** Found via a lightweight, bounded TCP connect probe against the
     *  well-known ports of the protocols this app supports, restricted to
     *  the device's own local subnet only (see [SubnetInfo]). */
    PORT_SCAN,
}

/**
 * Best-effort OS classification for the device icon shown in the
 * "Discovered Devices" list. This is a heuristic only (derived from the
 * mDNS service metadata / hostname / which protocols answered) — it is
 * never used for anything security-sensitive, purely cosmetic (choosing an
 * icon).
 */
enum class DiscoveredOsGuess {
    WINDOWS,
    LINUX,
    MACOS,
    UNKNOWN,
}

/**
 * A single device found on the local network, potentially offering more
 * than one of the supported remote-access protocols (e.g. a Linux box
 * answering both SSH and VNC).
 */
data class DiscoveredDevice(
    val ipAddress: String,
    // mDNS service/instance name, or a reverse-DNS hostname on a best-effort
    // basis — null if neither resolved (still perfectly usable by IP).
    val hostname: String? = null,
    val ports: Map<ProtocolType, Int> = emptyMap(),
    val sources: Set<DiscoverySource> = emptySet(),
    val osGuess: DiscoveredOsGuess = DiscoveredOsGuess.UNKNOWN,
    // Wall-clock time (System.currentTimeMillis()) this device was last
    // seen/updated during the current scan — lets the UI show the most
    // recently confirmed entries first if ever needed.
    val lastSeenAt: Long = System.currentTimeMillis(),
) {
    val protocols: Set<ProtocolType> get() = ports.keys

    /** Stable display label: hostname if we have one, otherwise the IP. */
    val displayName: String get() = hostname?.takeIf { it.isNotBlank() } ?: ipAddress
}

/** Coarse phase of the overall discovery run, driving the status UI. */
enum class DiscoveryPhase {
    IDLE,
    /** Checking connectivity / resolving the local subnet before scanning. */
    PREPARING,
    SCANNING,
    COMPLETED,
    STOPPED,
}

/** Why discovery could not run (or could not run in full). */
enum class DiscoveryError {
    /** No active network connection at all. */
    NO_NETWORK,
    /** Connected, but not to Wi-Fi/Ethernet (e.g. cellular data only) — the
     *  local-subnet port scan is deliberately never attempted in that case. */
    NOT_ON_LOCAL_NETWORK,
    /** A permission or platform policy blocked discovery outright (e.g. a
     *  SecurityException from NsdManager on a locked-down device/MDM). */
    PERMISSION_DENIED,
}

/**
 * Describes the local subnet discovery is bounded to, purely for display
 * ("Scanning 192.168.1.0/24…") and to let the manager decide whether a full
 * bounded port sweep is battery/network-friendly enough to attempt.
 */
data class SubnetInfo(
    val networkAddress: String,
    val prefixLength: Int,
    val hostCount: Int,
    val ssidOrDisplayName: String?,
)

data class DiscoveryUiState(
    val phase: DiscoveryPhase = DiscoveryPhase.IDLE,
    val devices: List<DiscoveredDevice> = emptyList(),
    val scannedHosts: Int = 0,
    val totalHosts: Int = 0,
    val subnet: SubnetInfo? = null,
    val mdnsSupported: Boolean = true,
    // True once we've decided the local subnet is too large to sweep with a
    // bounded port scan without hurting battery/network usage — mDNS still
    // runs normally in this case, only the active probe is skipped.
    val portScanSkippedSubnetTooLarge: Boolean = false,
    val error: DiscoveryError? = null,
)
