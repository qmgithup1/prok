package com.systemsgo.hex.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.google.gson.JsonParser
import com.systemsgo.hex.data.model.ProtocolType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * AUTO-DISCOVERY FEATURE: finds RDP/VNC/SSH/Telnet/Rlogin hosts — and, as of
 * the REDFISH-IPMI/AMT-VPRO/PROXMOX-VE gap fix below, AMT, IPMI, Redfish
 * BMCs and Proxmox VE hosts too — on the phone's *current* local network
 * (Wi-Fi/Ethernet only — never cellular) using two complementary, both
 * fully asynchronous mechanisms:
 *
 *  1. mDNS / DNS-SD via Android's standard [NsdManager] — zero network
 *     scanning at all, just listens for services other devices already
 *     announce (`_rdp._tcp`, `_ssh._tcp`, `_sftp-ssh._tcp`, `_rfb._tcp` for
 *     VNC). This is the "proper", battery-friendliest discovery path and
 *     the one every result should ideally come from. There is no
 *     widely-deployed DNS-SD service type for AMT/IPMI/Redfish/Proxmox, so
 *     those four are only ever found via the port scan below.
 *
 *  2. A lightweight, bounded connect-probe fallback restricted to the
 *     exact local subnet the device itself is on (derived from
 *     [ConnectivityManager]/[LinkProperties] — never guessed, never
 *     expanded beyond the actual prefix length). This exists purely to
 *     catch older devices — and, now, out-of-band management controllers —
 *     that don't announce themselves at all. It is capped
 *     (see [MAX_SCAN_HOSTS]) and bounded-concurrency (see [SCAN_CONCURRENCY])
 *     so it can't meaningfully hurt battery or generate excessive network
 *     traffic, and is skipped outright if the local subnet is too large to
 *     sweep responsibly. Most of this fallback is a plain TCP connect-probe
 *     (see [PORT_PROBES]), but two protocols need more than that to be
 *     detected honestly rather than falsely — see [probeIpmiUdp] (IPMI is
 *     UDP, not TCP — a TCP probe against it would always fail) and
 *     [probeRedfish] (port 443 alone is answered by almost any device with
 *     a web UI, so it's only reported as Redfish once the device actually
 *     answers a real Redfish Service Root request).
 *
 * Not a singleton: a fresh instance is created per screen (per ViewModel),
 * so [stop] tears down exactly the work that screen started.
 */
class NetworkDiscoveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "NetworkDiscovery"

        // Never sweep a subnet larger than this many usable hosts — a /23
        // (510 hosts) is the practical ceiling for a phone-friendly scan;
        // anything bigger (e.g. a flat /16 office network) is skipped in
        // favor of mDNS-only discovery to respect battery/network usage.
        private const val MAX_SCAN_HOSTS = 512

        // Bounded concurrency so the fallback scan never opens more than a
        // handful of sockets at once.
        private const val SCAN_CONCURRENCY = 32

        // Per-socket connect timeout for the port probe.
        private const val CONNECT_TIMEOUT_MS = 350

        // REDFISH-IPMI FEATURE: Redfish needs a real HTTPS round-trip
        // (TLS handshake + a GET + parsing the body), not just a TCP
        // connect, so it gets a more generous timeout than the plain
        // connect-probes above. Still short enough to not meaningfully
        // slow a scan down — this only runs against hosts that already
        // answered on 443 in the first place.
        private const val REDFISH_PROBE_TIMEOUT_MS = 800L

        // Best-effort reverse-DNS lookup timeout — never allowed to stall
        // the scan waiting on a resolver that won't answer.
        private const val HOSTNAME_LOOKUP_TIMEOUT_MS = 300L

        // REDFISH-IPMI FEATURE: IPMI's RMCP transport is UDP (see
        // probeIpmiUdp's doc comment) — 623 is its one and only
        // well-known port, there's no "alternate ports" list the way
        // VNC has.
        private const val IPMI_PORT = 623

        // REDFISH-IPMI FEATURE: same reasoning as IPMI_PORT above — 443
        // is Redfish's one well-known port, but unlike every other port in
        // this file it is never treated as sufficient on its own (see
        // probeRedfish).
        private const val REDFISH_PORT = 443

        // Service type -> protocol this app cares about (DNS-SD names).
        private val MDNS_SERVICE_TYPES: Map<String, ProtocolType> = mapOf(
            "_rdp._tcp." to ProtocolType.RDP,
            "_ssh._tcp." to ProtocolType.SSH,
            "_sftp-ssh._tcp." to ProtocolType.SSH,
            "_rfb._tcp." to ProtocolType.VNC,
            "_telnet._tcp." to ProtocolType.TELNET,
            // RLOGIN FEATURE: DNS-SD registers rlogin's classic Unix
            // /etc/services name "login" (not "rlogin") as its service type.
            "_login._tcp." to ProtocolType.RLOGIN,
        )

        // Ports probed by the fallback scan for each protocol. VNC commonly
        // runs on 5900 + display number, so a few common alternates are
        // included per the requirements.
        //
        // REDFISH-IPMI/AMT-VPRO/PROXMOX-VE FEATURE: this app also supports
        // AMT, IPMI, Redfish and (via ProtocolType.WEB — see its enum doc
        // comment, Proxmox VE has no dedicated ProtocolType of its own)
        // Proxmox VE, but none of the four were ever probed here, so
        // "Discovered Devices" could never find a BMC or hypervisor host
        // even on a network that had one. Three of the four are simple
        // additions to this same plain-TCP-connect list, exactly like
        // every entry above, because their ports are distinctive enough
        // (unlike 443) that a bare open socket is already a good signal:
        //  - AMT: 16992 plain / 16993 TLS, Intel's own out-of-band ports.
        //  - Proxmox VE: 8006, its default management-UI port.
        // IPMI (623) and Redfish (443) are deliberately NOT in this list —
        // see probeIpmiUdp and probeRedfish for why each needs its own,
        // protocol-aware check instead of a bare connect-probe.
        private val PORT_PROBES: List<Pair<ProtocolType, Int>> = listOf(
            ProtocolType.RDP to 3389,
            ProtocolType.SSH to 22,
            ProtocolType.VNC to 5900,
            ProtocolType.VNC to 5901,
            ProtocolType.VNC to 5902,
            ProtocolType.TELNET to 23,
            ProtocolType.RLOGIN to 513,
            ProtocolType.AMT to 16992,
            ProtocolType.AMT to 16993,
            ProtocolType.WEB to 8006,
        )
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    // REDFISH-IPMI FEATURE: a minimal client used only to ask "is this
    // /redfish/v1/ real?" during discovery — no auth, no session, nothing
    // credential-bearing. Self-signed certs are trusted purely so the
    // handshake itself doesn't fail before we ever get to look at the
    // response body, same as com.systemsgo.hex.redfish.protocol.RedfishClient
    // when acceptSelfSignedCertificate is on (BMC web UIs are overwhelmingly
    // self-signed out of the box).
    private val redfishProbeClient: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .connectTimeout(REDFISH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(REDFISH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }

    private val _state = MutableStateFlow(DiscoveryUiState())
    val state: StateFlow<DiscoveryUiState> = _state.asStateFlow()

    // Keyed by IP address so results from mDNS and the port scan merge into
    // a single entry instead of duplicating a device found both ways.
    private val devicesByIp = ConcurrentHashMap<String, DiscoveredDevice>()

    private var runJob: Job? = null
    private val activeNsdListeners = mutableListOf<NsdManager.DiscoveryListener>()

    /** Starts (or restarts) a discovery run on [scope]. Safe to call again
     *  while already running — it stops the previous run first. */
    fun start(scope: CoroutineScope) {
        stop()
        devicesByIp.clear()
        _state.value = DiscoveryUiState(phase = DiscoveryPhase.PREPARING)

        runJob = scope.launch(Dispatchers.IO) {
            val localNetwork = findLocalNetworkOrNull()
            if (localNetwork == null) {
                _state.value = DiscoveryUiState(
                    phase = DiscoveryPhase.STOPPED,
                    error = if (hasAnyActiveNetwork()) DiscoveryError.NOT_ON_LOCAL_NETWORK
                            else DiscoveryError.NO_NETWORK,
                )
                return@launch
            }

            val subnet = localNetwork.subnet
            _state.update {
                it.copy(
                    phase = DiscoveryPhase.SCANNING,
                    subnet = subnet,
                    totalHosts = subnet?.hostCount ?: 0,
                    portScanSkippedSubnetTooLarge = subnet != null && subnet.hostCount > MAX_SCAN_HOSTS,
                )
            }

            // mDNS keeps listening for the lifetime of this job (devices can
            // announce themselves at any point during the scan window);
            // the bounded port scan below is what actually completes and
            // flips the phase to COMPLETED.
            launch { runMdnsDiscovery() }

            if (subnet != null && subnet.hostCount in 1..MAX_SCAN_HOSTS) {
                runPortScan(localNetwork)
            }

            if (isActive) {
                _state.update { it.copy(phase = DiscoveryPhase.COMPLETED) }
            }
        }
    }

    /** Stops any in-progress scan and mDNS listening. Idempotent. */
    fun stop() {
        runJob?.cancel()
        runJob = null
        synchronized(activeNsdListeners) {
            activeNsdListeners.forEach { listener ->
                try {
                    nsdManager?.stopServiceDiscovery(listener)
                } catch (e: IllegalArgumentException) {
                    // Already stopped/never fully started — safe to ignore.
                } catch (e: SecurityException) {
                    Log.w(TAG, "stopServiceDiscovery denied", e)
                }
            }
            activeNsdListeners.clear()
        }
        if (_state.value.phase == DiscoveryPhase.SCANNING || _state.value.phase == DiscoveryPhase.PREPARING) {
            _state.update { it.copy(phase = DiscoveryPhase.STOPPED) }
        }
    }

    // ── Network / subnet resolution ─────────────────────────────────────────

    private data class LocalNetwork(
        val network: Network,
        val myAddress: Inet4Address,
        val prefixLength: Int,
        val subnet: SubnetInfo?,
    )

    private fun hasAnyActiveNetwork(): Boolean {
        val active = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Requirement: "Never scan outside the user's local subnet" — this is
     *  the single source of truth for what that subnet actually is. Only
     *  Wi-Fi or Ethernet transports are considered "local network"; a
     *  cellular-only connection deliberately yields null so no scan of any
     *  kind (mDNS included, since it wouldn't find anything meaningful and
     *  would just cost battery) is attempted over mobile data. */
    private fun findLocalNetworkOrNull(): LocalNetwork? {
        val candidates = connectivityManager.allNetworks.toList()
        // Prefer the active network if it qualifies; otherwise fall back to
        // the first other qualifying network (e.g. Ethernet while Wi-Fi is
        // also technically "connected" but not the one carrying traffic).
        val ordered = listOfNotNull(connectivityManager.activeNetwork) +
            candidates.filterNot { it == connectivityManager.activeNetwork }

        for (network in ordered) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            val isLocalTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isLocalTransport) continue

            val linkProperties: LinkProperties = connectivityManager.getLinkProperties(network) ?: continue
            val ipv4Link = linkProperties.linkAddresses.firstOrNull {
                it.address is Inet4Address && !it.address.isLoopbackAddress
            } ?: continue

            val myAddress = ipv4Link.address as Inet4Address
            val prefixLength = ipv4Link.prefixLength

            val subnet = subnetInfoFor(myAddress, prefixLength, caps)
            return LocalNetwork(network, myAddress, prefixLength, subnet)
        }
        return null
    }

    private fun subnetInfoFor(address: Inet4Address, prefixLength: Int, caps: NetworkCapabilities): SubnetInfo? {
        if (prefixLength !in 1..32) return null
        val hostBits = 32 - prefixLength
        // Guard against absurd/malformed prefix data rather than trying to
        // enumerate billions of hosts.
        if (hostBits > 24) return SubnetInfo(
            networkAddress = "-",
            prefixLength = prefixLength,
            hostCount = Int.MAX_VALUE,
            ssidOrDisplayName = displayNameFor(caps),
        )
        val addressInt = ipv4ToInt(address)
        val mask = if (hostBits == 32) 0 else (-1 shl hostBits)
        val networkInt = addressInt and mask
        val usableHosts = if (hostBits == 0) 0 else (1 shl hostBits) - 2
        return SubnetInfo(
            networkAddress = intToIpv4(networkInt),
            prefixLength = prefixLength,
            hostCount = usableHosts.coerceAtLeast(0),
            ssidOrDisplayName = displayNameFor(caps),
        )
    }

    private fun displayNameFor(caps: NetworkCapabilities): String? {
        return try {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                @Suppress("DEPRECATION")
                wifiManager?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            } else null
        } catch (e: SecurityException) {
            null
        }
    }

    // ── mDNS / DNS-SD (NsdManager) ──────────────────────────────────────────

    private suspend fun runMdnsDiscovery() {
        val manager = nsdManager
        if (manager == null) {
            _state.update { it.copy(mdnsSupported = false) }
            return
        }
        try {
            MDNS_SERVICE_TYPES.keys.forEach { serviceType ->
                startDiscoveryFor(manager, serviceType)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "mDNS discovery denied", e)
            _state.update { it.copy(mdnsSupported = false) }
        } catch (e: RuntimeException) {
            // Some OEM builds throw here instead of using onStartDiscoveryFailed.
            Log.w(TAG, "mDNS discovery unsupported on this device", e)
            _state.update { it.copy(mdnsSupported = false) }
        }
    }

    private fun startDiscoveryFor(manager: NsdManager, serviceType: String) {
        val protocol = MDNS_SERVICE_TYPES[serviceType] ?: return

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Common and harmless (e.g. FAILURE_ALREADY_ACTIVE while a
                // previous resolve for the same service is in flight) —
                // just skip this one result rather than surfacing an error.
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host ?: return
                if (host !is Inet4Address) return
                val ip = host.hostAddress ?: return
                val port = serviceInfo.port.takeIf { it in 1..65535 }
                    ?: protocol.defaultPort

                mergeDevice(
                    ip = ip,
                    hostname = serviceInfo.serviceName,
                    protocol = protocol,
                    port = port,
                    source = DiscoverySource.MDNS,
                )
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                try {
                    manager.resolveService(serviceInfo, resolveListener)
                } catch (e: IllegalArgumentException) {
                    // A resolve for this exact service is already active.
                } catch (e: SecurityException) {
                    Log.w(TAG, "resolveService denied", e)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // Devices leaving the network mid-scan are intentionally
                // left in the results list rather than removed — a
                // transient mDNS timeout shouldn't make a device the user
                // is looking at disappear from underneath them; it stays
                // until the user refreshes.
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                try {
                    manager.stopServiceDiscovery(this)
                } catch (e: IllegalArgumentException) { /* not started */ }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            synchronized(activeNsdListeners) { activeNsdListeners.add(discoveryListener) }
        } catch (e: SecurityException) {
            Log.w(TAG, "discoverServices denied for $serviceType", e)
        }
    }

    // ── Bounded local-subnet port scan (fallback) ───────────────────────────

    private suspend fun runPortScan(localNetwork: LocalNetwork) {
        val subnet = localNetwork.subnet ?: return
        if (subnet.hostCount <= 0 || subnet.hostCount > MAX_SCAN_HOSTS) return

        val myAddressInt = ipv4ToInt(localNetwork.myAddress)
        val hostBits = 32 - localNetwork.prefixLength
        val mask = if (hostBits == 32) 0 else (-1 shl hostBits)
        val networkInt = myAddressInt and mask
        val broadcastInt = networkInt or hostBits.let { if (it == 32) -1 else (1 shl it) - 1 }

        val semaphore = Semaphore(SCAN_CONCURRENCY)
        val scanned = java.util.concurrent.atomic.AtomicInteger(0)

        withContext(Dispatchers.IO) {
            val jobs = (networkInt + 1 until broadcastInt).map { hostInt ->
                launch {
                    if (hostInt == myAddressInt) return@launch
                    semaphore.withPermit {
                        probeHost(intToIpv4(hostInt))
                    }
                    val done = scanned.incrementAndGet()
                    _state.update { it.copy(scannedHosts = done, devices = devicesByIp.values.sortedForDisplay()) }
                }
            }
            jobs.joinAll()
        }
    }

    private suspend fun probeHost(ip: String) {
        val address = try {
            InetAddress.getByName(ip)
        } catch (e: Exception) {
            return
        }

        var resolvedHostname: String? = null

        suspend fun report(protocol: ProtocolType, port: Int) {
            if (resolvedHostname == null) {
                resolvedHostname = withTimeoutOrNull(HOSTNAME_LOOKUP_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { safeReverseLookup(address, ip) }
                }
            }
            mergeDevice(
                ip = ip,
                hostname = resolvedHostname,
                protocol = protocol,
                port = port,
                source = DiscoverySource.PORT_SCAN,
            )
        }

        for ((protocol, port) in PORT_PROBES) {
            val open = withContext(Dispatchers.IO) { tryConnect(address, port) }
            if (open) report(protocol, port)
        }

        // REDFISH-IPMI FEATURE: IPMI's RMCP transport is UDP — see
        // probeIpmiUdp's doc comment for why it can't share the plain
        // TCP PORT_PROBES loop above the way every other protocol does.
        if (withContext(Dispatchers.IO) { probeIpmiUdp(address) }) {
            report(ProtocolType.IPMI, IPMI_PORT)
        }

        // REDFISH-IPMI FEATURE: cheap TCP connect first (matches the
        // pattern of every other probe, and skips the HTTPS round-trip
        // entirely on hosts with nothing listening on 443 at all); only
        // pay for the real Redfish Service Root check — see probeRedfish's
        // doc comment for why that check is mandatory rather than optional
        // — once something actually answers the port.
        if (withContext(Dispatchers.IO) { tryConnect(address, REDFISH_PORT) } &&
            withContext(Dispatchers.IO) { probeRedfish(ip) }
        ) {
            report(ProtocolType.REDFISH, REDFISH_PORT)
        }
    }

    private fun tryConnect(address: InetAddress, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * REDFISH-IPMI FEATURE: IPMI's network transport (RMCP, DMTF DSP0136 /
     * IPMI v2.0 §3.2.4.1) is UDP, not TCP — real BMCs simply never have
     * anything listening for a TCP SYN on 623, so [tryConnect] against that
     * port would report "closed" 100% of the time even for a perfectly
     * reachable IPMI device. This sends the same RMCP "Presence Ping" ASF
     * datagram `ipmitool lan discover`/`ipmiping` use, and only reports
     * success if a well-formed "Presence Pong" (ASF message type 0x40,
     * matching message tag) comes back — the actual, spec-defined way to
     * detect IPMI over a network, not a guess.
     */
    private fun probeIpmiUdp(address: InetAddress): Boolean {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = CONNECT_TIMEOUT_MS

                // RMCP header (4 bytes): version 0x06, reserved 0x00,
                // sequence 0xFF ("no RMCP ACK requested" — ASF pings don't
                // use RMCP-level acks), class 0x06 (ASF).
                // ASF message header (8 bytes): IANA enterprise number 4542
                // (0x000011BE, big-endian) identifying this as an ASF
                // message, message type 0x80 (Presence Ping), an arbitrary
                // message tag we can match on the reply, a reserved byte,
                // and a data-length byte of 0x00 (a ping carries no data).
                val messageTag = (System.nanoTime() and 0xFF).toInt()
                val request = byteArrayOf(
                    0x06, 0x00, 0xFF.toByte(), 0x06,
                    0x00, 0x00, 0x11, 0xBE.toByte(),
                    0x80.toByte(), messageTag.toByte(), 0x00, 0x00,
                )
                socket.send(DatagramPacket(request, request.size, address, IPMI_PORT))

                val responseBuffer = ByteArray(64)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(responsePacket)

                isPresencePong(responseBuffer, responsePacket.length, messageTag)
            }
        } catch (e: Exception) {
            // SocketTimeoutException — nothing answered, by far the most
            // common outcome since most hosts aren't BMCs — or any other
            // I/O failure both simply mean "no IPMI here".
            false
        }
    }

    /** Validates an RMCP/ASF "Presence Pong" per DMTF DSP0136 §13.2.4 —
     *  same fields `ipmiping`/`ipmitool` check before believing a reply. */
    private fun isPresencePong(data: ByteArray, length: Int, expectedTag: Int): Boolean {
        // RMCP header (4 bytes) + ASF header (4-byte IANA number + type +
        // tag + reserved + data-length = 8 bytes) = 12 bytes minimum,
        // before even looking at any pong payload.
        if (length < 12) return false
        if (data[0] != 0x06.toByte()) return false                  // RMCP version
        if ((data[3].toInt() and 0x7F) != 0x06) return false        // RMCP class: ASF
        val iana = ((data[4].toInt() and 0xFF) shl 24) or
            ((data[5].toInt() and 0xFF) shl 16) or
            ((data[6].toInt() and 0xFF) shl 8) or
            (data[7].toInt() and 0xFF)
        if (iana != 4542) return false                              // ASF's own IANA enterprise number
        if (data[8] != 0x40.toByte()) return false                  // ASF message type: Presence Pong
        if ((data[9].toInt() and 0xFF) != expectedTag) return false // reply to *our* ping, not a stray packet
        return true
    }

    /**
     * REDFISH-IPMI FEATURE: 443 alone is not a usable discovery signal —
     * almost any device with a web UI (routers, printers, NAS boxes, even
     * this phone's own hotspot config page) answers there, so treating an
     * open 443 as "found a Redfish BMC" would misfire on nearly every
     * device on a typical network, which is worse than not detecting
     * Redfish at all. This instead requests the Redfish Service Root
     * (DSP0266 §6.3, GET /redfish/v1/) and only reports success once the
     * response is actually shaped like one. Self-signed certs are trusted
     * here purely for detection (see [redfishProbeClient]'s doc comment) —
     * no credentials are ever sent by this probe.
     */
    private fun probeRedfish(ip: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("https://$ip:$REDFISH_PORT/redfish/v1/")
                .get()
                .build()
            redfishProbeClient.newCall(request).execute().use { response ->
                if (response.code !in 200..299) return false
                val body = response.body?.string()
                if (body.isNullOrBlank()) return false
                val root = JsonParser.parseString(body).asJsonObject
                val odataType = root.get("@odata.type")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                root.has("RedfishVersion") || odataType.contains("ServiceRoot", ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun safeReverseLookup(address: InetAddress, fallbackIp: String): String? {
        return try {
            val name = address.canonicalHostName
            // getCanonicalHostName() silently returns the IP itself when no
            // PTR record resolves — treat that as "no hostname available".
            name?.takeIf { it.isNotBlank() && it != fallbackIp }
        } catch (e: Exception) {
            null
        }
    }

    // ── Merge helper ─────────────────────────────────────────────────────────

    private fun mergeDevice(ip: String, hostname: String?, protocol: ProtocolType, port: Int, source: DiscoverySource) {
        devicesByIp.merge(
            ip,
            DiscoveredDevice(
                ipAddress = ip,
                hostname = hostname,
                ports = mapOf(protocol to port),
                sources = setOf(source),
                osGuess = guessOs(protocol, hostname),
            ),
        ) { existing, incoming ->
            existing.copy(
                hostname = existing.hostname ?: incoming.hostname,
                ports = existing.ports + incoming.ports,
                sources = existing.sources + incoming.sources,
                osGuess = if (existing.osGuess != DiscoveredOsGuess.UNKNOWN) existing.osGuess
                          else guessOs(protocol, existing.hostname ?: incoming.hostname),
                lastSeenAt = System.currentTimeMillis(),
            )
        }
        _state.update { it.copy(devices = devicesByIp.values.sortedForDisplay()) }
    }

    private fun guessOs(protocol: ProtocolType, hostname: String?): DiscoveredOsGuess {
        val lowerHost = hostname?.lowercase().orEmpty()
        return when {
            lowerHost.contains("android") -> DiscoveredOsGuess.LINUX
            lowerHost.endsWith(".local") && (lowerHost.contains("mac") || lowerHost.contains("imac") || lowerHost.contains("macbook")) ->
                DiscoveredOsGuess.MACOS
            protocol == ProtocolType.RDP -> DiscoveredOsGuess.WINDOWS
            protocol == ProtocolType.SSH -> DiscoveredOsGuess.LINUX
            protocol == ProtocolType.TELNET -> DiscoveredOsGuess.LINUX
            protocol == ProtocolType.RLOGIN -> DiscoveredOsGuess.LINUX
            else -> DiscoveredOsGuess.UNKNOWN
        }
    }

    private fun Collection<DiscoveredDevice>.sortedForDisplay(): List<DiscoveredDevice> =
        sortedWith(compareBy({ it.displayName }, { it.ipAddress }))

    private fun ipv4ToInt(address: Inet4Address): Int {
        val bytes = address.address
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    private fun intToIpv4(value: Int): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }
}
