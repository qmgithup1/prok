package com.systemsgo.hex.data.model

/**
 * ADD-CONNECTION PROTOCOL PICKER (Part 1/2).
 *
 * Static catalog backing the "Add Connection" protocol-selection screen
 * (com.systemsgo.hex.ui.screens.addconnection.AddConnectionProtocolScreen).
 *
 * This is deliberately a *separate* model from [ProtocolType]: [ProtocolType]
 * is the enum RdpProfile/RemoteSessionFactory persist and dispatch on, and it
 * only exists for protocols that already have a working session client. This
 * catalog is the picker's product surface — it also lists protocols the
 * screen can *show and explain* before a client exists for them (e.g. SCP,
 * FTP/FTPS, SMB, NFS, Mosh, SNMP, NETCONF/RESTCONF, the industrial and
 * enterprise-virtualization families). Each entry optionally links back to
 * the [ProtocolType] it launches via [ProtocolCatalogEntry.protocolType];
 * entries with a null protocolType are catalog/informational-only for now
 * and the screen should route them to the "Request a protocol" CTA instead
 * of the connection editor (see AddConnectionProtocolViewModel doc).
 */

/**
 * Top-level grouping shown as filter chips and as "All Protocols" section headers.
 *
 * IMPORTANT: a [ProtocolCategory] is *purely* a display-time grouping label. It has
 * no behavior, no session logic, and nothing downstream (config screen, session
 * client, transport, auth) ever branches on it — that would collapse independent
 * protocols into a shared code path, which is exactly what this catalog is designed
 * to prevent. The only things that read a card's category are this picker's filter
 * chips/section headers and the icon/accent-color lookup in
 * AddConnectionProtocolScreen. Every protocol still resolves to its own
 * [ProtocolCatalogEntry.protocolType] (or null, pending a client) and, from there,
 * to its own entirely independent connection model, config screen, session
 * implementation, transport, auth, and capabilities. Adding a 101st protocol never
 * requires touching another protocol's code — only a new catalog entry (and, if it
 * needs one, a new category here).
 */
enum class ProtocolCategory(val label: String) {
    FAVORITES("Favorites"),
    RECENT("Recent"),
    DESKTOP("Remote Desktop"),
    TERMINAL("Terminal"),
    FILE_TRANSFER("File Transfer"),
    NETWORK("Network Management"),
    HARDWARE("Server Management"),
    VIRTUALIZATION("Virtualization"),
    INDUSTRIAL("Industrial"),
    CAMERAS("Cameras"),
    WAKE_ON_LAN("Wake-on-LAN"),
    WEB("Web"),
    MONITORING("Monitoring"),
    OTHER("Other");

    companion object {
        /** Categories that actually group protocols in "All Protocols" — FAVORITES/RECENT are filter-only. */
        val sectionCategories: List<ProtocolCategory> = listOf(
            DESKTOP, TERMINAL, FILE_TRANSFER, NETWORK, HARDWARE, VIRTUALIZATION,
            INDUSTRIAL, CAMERAS, WAKE_ON_LAN, WEB, MONITORING, OTHER,
        )
    }
}

/** Small colored badge shown on a protocol card. */
enum class ProtocolBadge { NEW, BETA, POPULAR, ENTERPRISE, COMING_SOON }

/**
 * How tapping this card in the picker actually gets the user to a working
 * feature — *not* the same axis as "is this protocol supported". A protocol
 * can be fully implemented today without going through the saved-profile /
 * [ProtocolType] flow at all (e.g. FTP is a one-off Quick Transfer dialog,
 * SFTP is a file browser inside an already-open SSH session) — see
 * ProtocolCatalog's per-entry comments for which real screen/class backs
 * each one.
 */
enum class ProtocolLaunchKind {
    /** Has a [ProtocolCatalogEntry.protocolType] — opens the standard saved-connection editor. */
    SAVED_CONNECTION,
    /** No saved profile; opens its own working one-off transfer dialog directly (FtpTransferDialog, SmbTransferDialog, WebDavTransferDialog — see HomeScreen's Quick Transfer). */
    STANDALONE_TRANSFER,
    /** No saved profile of its own; it's a file browser reachable from inside an SSH session (SftpFileBrowser / ScpTransfer in FileTransferManager.kt, surfaced via RdpSessionActivity's FileTransferDialog) — so the picker opens the SSH editor instead. */
    VIA_SSH_SESSION,
    /** No client exists yet at all — routes to the "request this protocol" sheet. */
    NOT_YET_SUPPORTED,
}

/**
 * One selectable entry in the picker.
 *
 * @param id stable identifier used for favorites/recents persistence — for entries
 *   backed by a [ProtocolType] this is `protocolType.name`, so favorite/recent state
 *   survives even after Part 2 wires the picker into navigation.
 * @param protocolType the session type this launches, or null if this protocol either
 *   has no client yet, or has one that isn't reached through the saved-profile flow
 *   (see [launchKind]).
 * @param tags extra search terms beyond name/description, e.g. platform hints
 *   ("windows", "linux", "macos") so "Windows" finds RDP and "Linux" finds SSH/SPICE.
 * @param aliases alternate names a user might type, e.g. "NoMachine" for NX.
 * @param launchKind defaults to [ProtocolLaunchKind.SAVED_CONNECTION] when [protocolType]
 *   is set and [ProtocolLaunchKind.NOT_YET_SUPPORTED] otherwise; override it explicitly
 *   for protocols that are implemented but reached a different way.
 */
data class ProtocolCatalogEntry(
    val id: String,
    val protocolType: ProtocolType?,
    val name: String,
    val description: String,
    val category: ProtocolCategory,
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val badges: List<ProtocolBadge> = emptyList(),
    val isPopular: Boolean = false,
    val defaultPort: Int? = protocolType?.defaultPort,
    val launchKind: ProtocolLaunchKind =
        if (protocolType != null) ProtocolLaunchKind.SAVED_CONNECTION else ProtocolLaunchKind.NOT_YET_SUPPORTED,
) {
    /** All text the search box matches against, lower-cased once at catalog-build time. */
    val searchIndex: String = buildString {
        append(name); append(' ')
        append(description); append(' ')
        append(category.label); append(' ')
        tags.forEach { append(it); append(' ') }
        aliases.forEach { append(it); append(' ') }
    }.lowercase()
}

/**
 * The master catalog, in the order the spec lists them under "All Protocols".
 * [ProtocolCatalogViewModels] (Part 1) reads this directly; nothing here is
 * loaded from network or DB — it's a static product catalog, like an app's
 * onboarding copy.
 */
object ProtocolCatalog {

    val entries: List<ProtocolCatalogEntry> = listOf(
        // ── Desktop & Remote Access ────────────────────────────────────────
        ProtocolCatalogEntry(
            id = ProtocolType.RDP.name, protocolType = ProtocolType.RDP,
            name = "RDP", description = "Full desktop access to Windows machines and servers.",
            category = ProtocolCategory.DESKTOP,
            tags = listOf("windows", "remote desktop", "microsoft"),
            badges = emptyList(), isPopular = true,
        ),
        ProtocolCatalogEntry(
            id = ProtocolType.VNC.name, protocolType = ProtocolType.VNC,
            name = "VNC", description = "Cross-platform screen sharing over the RFB protocol.",
            category = ProtocolCategory.DESKTOP,
            tags = listOf("linux", "macos", "windows", "screen sharing", "rfb"),
            isPopular = true,
        ),
        ProtocolCatalogEntry(
            id = ProtocolType.SPICE.name, protocolType = ProtocolType.SPICE,
            name = "SPICE", description = "High-performance display protocol for virtual machines.",
            category = ProtocolCategory.DESKTOP,
            tags = listOf("linux", "kvm", "qemu", "virtual machine"),
            isPopular = true,
        ),
        ProtocolCatalogEntry(
            // GUACAMOLE-PROTOCOL FEATURE: now a real, implemented protocol
            // (native REST auth + WebSocket tunnel client — see
            // com.systemsgo.hex.guacamole.protocol.GuacamoleSessionClient's
            // class doc), not just a catalog placeholder — protocolType is
            // no longer null, so tapping this entry in the "Add Connection"
            // picker routes straight to the real connection editor instead
            // of the generic "protocol not yet supported" request-it flow.
            id = "guacamole", protocolType = ProtocolType.GUACAMOLE,
            name = "Guacamole", description = "Native Apache Guacamole client — authenticates, browses your connections, and renders the session directly (not an embedded browser).",
            category = ProtocolCategory.DESKTOP,
            tags = listOf("web", "gateway", "html5"), isPopular = true,
        ),
        ProtocolCatalogEntry(
            // RDP-OVER-WEBSOCKET FEATURE: flipped from protocolType = null.
            // Unlike most NOT_YET_SUPPORTED entries, this one had a fully
            // working implementation already sitting behind the flag —
            // RdpWebSocketTransport (loopback bridge + OkHttp WebSocket +
            // TLS/TOFU pinning), RdpProfile.transportMode/webSocketConfig,
            // RdpRemoteAdapter's connect() branch, and the
            // WebSocketTransportSettings UI section in Components.kt's
            // ProfileFormDialog were all real and wired end-to-end; only
            // this catalog flag was never flipped.
            //
            // Routes to the normal RDP editor (protocolType = ProtocolType.RDP)
            // but, unlike every other SAVED_CONNECTION entry, ALSO carries a
            // preset: AddConnectionRoute's onProtocolChosen recognizes this
            // specific id and opens ProfileFormDialog with a blank RdpProfile
            // template whose transportMode is already RdpTransportMode.WSS —
            // see that file's editorPresetProfile and Components.kt's
            // expandTransport/showOptionalSettings fix (without which the
            // WebSocket section would be pre-selected but still hidden
            // inside a collapsed "Advanced" group). No other catalog entry
            // has this preset mechanism; it was built specifically for this
            // one, not as a new general-purpose feature.
            id = "rdp_over_websocket", protocolType = ProtocolType.RDP,
            name = "RDP over WebSocket", description = "RDP tunneled through a WebSocket, for hosts reachable only over HTTPS.",
            category = ProtocolCategory.DESKTOP,
            tags = listOf("windows", "tunnel", "websocket"), badges = listOf(ProtocolBadge.BETA),
        ),

        // ── Server Management ───────────────────────────────────────────────
        ProtocolCatalogEntry(
            id = ProtocolType.IPMI.name, protocolType = ProtocolType.IPMI,
            name = "IPMI", description = "Out-of-band server management independent of the host OS.",
            category = ProtocolCategory.HARDWARE, tags = listOf("bmc", "server"),
        ),
        ProtocolCatalogEntry(
            id = ProtocolType.REDFISH.name, protocolType = ProtocolType.REDFISH,
            name = "Redfish", description = "Modern REST-based standard for server and BMC management.",
            category = ProtocolCategory.HARDWARE, tags = listOf("bmc", "server", "rest"),
        ),
        ProtocolCatalogEntry(
            id = ProtocolType.AMT.name, protocolType = ProtocolType.AMT,
            name = "Intel AMT", description = "Intel vPro out-of-band management via the Management Engine.",
            category = ProtocolCategory.HARDWARE, tags = listOf("intel", "vpro", "bmc"),
        ),
        ProtocolCatalogEntry(
            // KVM-OVER-IP FEATURE: flipped from protocolType = null. Unlike
            // Intel AMT's own built-in KVM redirection (AmtKvmSession, a
            // ProtocolType.AMT sub-feature reached from BmcManagementScreen —
            // that's a *different*, vendor-specific redirection path), this
            // entry is for standalone/vendor-neutral KVM-over-IP hardware
            // (PiKVM, TinyPilot, NanoKVM, JetKVM, and most third-party IP-KVM
            // switches), which overwhelmingly expose their video/input
            // console as plain RFB/VNC. Wire-compatible the same way
            // ProtocolType.VIRTUALBOX_VRDE is wire-compatible RDP — it opens
            // the normal VNC connection editor and RemoteSessionFactory.create
            // dispatches it straight through the existing VncClient, no new
            // protocol engine needed. A device that instead only exposes a
            // vendor-specific web console still has the "Web" entry above.
            id = "kvm_over_ip", protocolType = ProtocolType.VNC,
            name = "KVM over IP", description = "Remote keyboard, video, and mouse access at the hardware level.",
            category = ProtocolCategory.HARDWARE, tags = listOf("bmc", "console", "pikvm", "tinypilot"),
            badges = listOf(ProtocolBadge.NEW),
        ),

        // ── Wake-on-LAN ──────────────────────────────────────────────────────
        // WAKE-ON-LAN-STANDALONE FEATURE: flipped from protocolType = null (which
        // routed here to the "Request this protocol" sheet even though WoL was
        // already fully implemented — see WakeOnLanManager and the wol* fields on
        // RdpProfile — just not reachable as its own saved connection). Now a
        // real ProtocolType, so this is a plain SAVED_CONNECTION entry like RDP/
        // SSH/etc.: tapping it opens the standard connection editor, pre-set to
        // enable the Wake-on-LAN fields (see AddConnectionRoute's wake_on_lan
        // preset profile) instead of the per-connection "wake before connect"
        // add-on toggle.
        ProtocolCatalogEntry(
            id = ProtocolType.WAKE_ON_LAN.name, protocolType = ProtocolType.WAKE_ON_LAN,
            name = "Wake-on-LAN", description = "Powers on a remote machine with a magic network packet.",
            category = ProtocolCategory.WAKE_ON_LAN, tags = listOf("wol", "power"),
        ),

        // ── Virtualization ───────────────────────────────────────────────────
        ProtocolCatalogEntry(
            // VMWARE-VSPHERE FEATURE (Part 3/N): flipped from protocolType = null
            // now that a real client exists — VSphereApiClient (Part 1),
            // VSphereVncBridge (Part 2), and VSphereManagementActivity
            // (Part 3, VM/host inventory + power actions + WEBMKS console)
            // are all wired end to end via SessionLauncher, same as PROXMOX.
            // Like PROXMOX, this is a management-API protocol, not a
            // framebuffer session — tapping the card opens the connection
            // editor (host/port/credentials + vsphereApiMode/vsphereDatacenter),
            // and Connect routes to VSphereManagementActivity, not
            // RemoteSessionFactory.
            id = "vmware_api", protocolType = ProtocolType.VMWARE_VSPHERE,
            name = "VMware API", description = "vSphere/ESXi management API for provisioning and controlling virtual machines.",
            category = ProtocolCategory.VIRTUALIZATION, tags = listOf("vmware", "esxi", "vcenter", "hypervisor"),
            badges = listOf(ProtocolBadge.NEW),
        ),
        ProtocolCatalogEntry(
            // PROXMOX-API FEATURE (Part 3/N): flipped from protocolType = null
            // now that a real client exists — ProxmoxApiClient (Part 1),
            // ProxmoxVncBridge (Part 2), and ProxmoxManagementActivity
            // (Part 3, node/guest inventory + power actions + VNC console)
            // are all wired end to end via SessionLauncher. Like PROXMOX
            // above, this is a management-API protocol, not a framebuffer
            // session — tapping the card opens the connection editor
            // (host/port + token or password credentials), and Connect
            // routes to ProxmoxManagementActivity, not RemoteSessionFactory.
            id = "proxmox_api", protocolType = ProtocolType.PROXMOX,
            name = "Proxmox API", description = "Proxmox VE's REST API for managing VMs and containers.",
            category = ProtocolCategory.VIRTUALIZATION, tags = listOf("proxmox", "kvm", "lxc", "hypervisor"),
            badges = listOf(ProtocolBadge.NEW),
        ),
        ProtocolCatalogEntry(
            // VIRTUALBOX-VRDE FEATURE (Part 1/N): flipped from protocolType = null.
            // Unlike vmware_api above, this is fully connectable today —
            // VRDE is wire-compatible RDP, so it opens the normal RDP-style
            // connection editor and RemoteSessionFactory.create dispatches
            // it straight through RdpRemoteAdapter/FreeRDP (with NLA forced
            // off) — see ProtocolType.VIRTUALBOX_VRDE's doc comment.
            id = "virtualbox_vrde", protocolType = ProtocolType.VIRTUALBOX_VRDE,
            name = "VirtualBox VRDE", description = "VirtualBox's Remote Display Extension for accessing guest VM consoles.",
            category = ProtocolCategory.VIRTUALIZATION, tags = listOf("virtualbox", "oracle", "hypervisor", "rdp"),
            badges = listOf(ProtocolBadge.NEW),
        ),

        // ── Cameras ──────────────────────────────────────────────────────────
        ProtocolCatalogEntry(
            // RTSP FEATURE: flipped from protocolType = null now that a real
            // client exists — see com.systemsgo.hex.rtsp.protocol.RtspClient
            // (control-plane handshake + RTP/H.264 decode) and
            // RemoteSessionFactory.create's RTSP branch. Routes to the
            // standard saved-connection editor like any other
            // SAVED_CONNECTION entry; view-only (no keyboard/mouse/PTZ) —
            // camera discovery and PTZ control (ONVIF) are a separate,
            // not-yet-implemented protocol and have no catalog entry here.
            id = ProtocolType.RTSP.name, protocolType = ProtocolType.RTSP,
            name = "RTSP", description = "Real Time Streaming Protocol for live video from IP cameras and encoders.",
            category = ProtocolCategory.CAMERAS, tags = listOf("camera", "streaming", "video"),
        ),

        // ── File Transfer ────────────────────────────────────────────────────
        // SFTP-STANDALONE FEATURE: flipped from protocolType = null /
        // VIA_SSH_SESSION (which forced opening the full SSH editor just to
        // reach a file browser) to a real ProtocolType.SFTP — a plain
        // SAVED_CONNECTION entry like RDP/SSH/etc. now, opening a dedicated
        // file-transfer connection editor instead. See ProtocolType.SFTP's
        // doc comment in RdpProfile.kt for exactly what it reuses from SSH
        // and what it deliberately leaves out.
        ProtocolCatalogEntry(
            id = ProtocolType.SFTP.name, protocolType = ProtocolType.SFTP,
            name = "SFTP", description = "Secure file transfer — its own saved connection, no SSH terminal session required.",
            category = ProtocolCategory.FILE_TRANSFER, tags = listOf("ssh", "files"), isPopular = true,
        ),
        ProtocolCatalogEntry(
            id = "scp", protocolType = ProtocolType.SFTP,
            name = "SCP", description = "Simple, fast file copy — pick the SCP transfer engine inside a saved SFTP connection.",
            category = ProtocolCategory.FILE_TRANSFER, tags = listOf("ssh", "files"),
            // SFTP-STANDALONE FEATURE: SCP was never a separate wire protocol
            // from this app's point of view — it's the alternate transfer
            // engine (FileTransferMode.SCP in FileTransferScreen.kt) selectable
            // inside the very same file-transfer screen an SFTP connection
            // opens (directory listing is always via SFTP; SCP only replaces
            // how upload/download bytes move). So this card now opens the same
            // SFTP editor/screen as the SFTP card above, rather than a
            // dead-end "SCP connection" that doesn't exist as its own thing.
        ),
        ProtocolCatalogEntry(
            // FTP-STANDALONE FEATURE: flipped from protocolType = null /
            // STANDALONE_TRANSFER (HomeScreen's one-off Quick Transfer dialog)
            // to a real ProtocolType.FTP — a plain SAVED_CONNECTION entry like
            // SFTP above, opening the standard connection editor instead of a
            // dialog that forgot everything after one use.
            id = ProtocolType.FTP.name, protocolType = ProtocolType.FTP,
            name = "FTP", description = "Classic unencrypted file transfer — its own saved connection.",
            category = ProtocolCategory.FILE_TRANSFER, tags = listOf("files"),
        ),
        ProtocolCatalogEntry(
            // FTP-STANDALONE FEATURE: same flip as FTP above, using
            // ProtocolType.FTPS and RdpProfile.ftpSecurity = FTPS_EXPLICIT/
            // FTPS_IMPLICIT (chosen in the editor) instead of PLAIN.
            id = ProtocolType.FTPS.name, protocolType = ProtocolType.FTPS,
            name = "FTPS", description = "FTP secured with TLS — its own saved connection.",
            category = ProtocolCategory.FILE_TRANSFER, tags = listOf("files", "tls"),
        ),
        ProtocolCatalogEntry(
            // WEBDAV-STANDALONE FEATURE: flipped from protocolType = null /
            // STANDALONE_TRANSFER to a real ProtocolType.WEBDAV — see
            // RdpProfile.webdavBaseUrl's doc comment for why this profile's
            // editor asks for a full base URL instead of host/port.
            id = ProtocolType.WEBDAV.name, protocolType = ProtocolType.WEBDAV,
            name = "WebDAV", description = "File access and editing over HTTP/HTTPS — its own saved connection.",
            category = ProtocolCategory.FILE_TRANSFER, tags = listOf("files", "http"),
        ),
        ProtocolCatalogEntry(
            // SMB-STANDALONE FEATURE: flipped from protocolType = null /
            // STANDALONE_TRANSFER to a real ProtocolType.SMB — adds
            // RdpProfile.smbShare/smbDomain to the standard host/port/
            // username/password columns.
            id = ProtocolType.SMB.name, protocolType = ProtocolType.SMB,
            name = "SMB", description = "Windows network file and printer sharing — its own saved connection.",
            category = ProtocolCategory.FILE_TRANSFER, tags = listOf("windows", "files", "samba"),
        ),
        ProtocolCatalogEntry(
            // NFS-STANDALONE FEATURE: flipped from protocolType = null /
            // STANDALONE_TRANSFER to a real ProtocolType.NFS (NFSv3, AUTH_SYS
            // only — hand-rolled RPC/XDR/MOUNT/NFSv3 stack in
            // com.systemsgo.hex.nfs, no library available). No username/
            // password — see RdpProfile.nfsExportPath's doc comment for why
            // this profile's editor shows nfsUid/nfsGid instead.
            id = ProtocolType.NFS.name, protocolType = ProtocolType.NFS,
            name = "NFS", description = "Unix/Linux network file system sharing — its own saved connection.",
            category = ProtocolCategory.FILE_TRANSFER, tags = listOf("linux", "files"),
        ),

        // ── Terminal ─────────────────────────────────────────────────────────
        ProtocolCatalogEntry(
            id = ProtocolType.SSH.name, protocolType = ProtocolType.SSH,
            name = "SSH", description = "Encrypted command-line access to Linux, macOS, and network gear.",
            category = ProtocolCategory.TERMINAL,
            tags = listOf("linux", "macos", "terminal", "shell"), isPopular = true,
        ),
        ProtocolCatalogEntry(
            id = ProtocolType.TELNET.name, protocolType = ProtocolType.TELNET,
            name = "Telnet", description = "Unencrypted plain-text terminal access — legacy devices only.",
            category = ProtocolCategory.TERMINAL, tags = listOf("legacy", "terminal"),
        ),
        ProtocolCatalogEntry(
            id = ProtocolType.SERIAL_CONSOLE.name, protocolType = ProtocolType.SERIAL_CONSOLE,
            name = "Serial", description = "Direct terminal access to a device's serial console.",
            category = ProtocolCategory.TERMINAL, tags = listOf("console", "rs232"),
        ),
        ProtocolCatalogEntry(
            // MOSH FEATURE: was id = "mosh", protocolType = null (placeholder,
            // unselectable — see RemoteSessionFactory/RdpProfile/Components.kt
            // for the rest of the implementation this now unlocks).
            id = ProtocolType.MOSH.name, protocolType = ProtocolType.MOSH,
            name = "Mosh", description = "Roaming, connection-resilient terminal built on top of SSH.",
            category = ProtocolCategory.TERMINAL, tags = listOf("ssh", "terminal", "roaming"),
            badges = listOf(ProtocolBadge.NEW),
        ),
        ProtocolCatalogEntry(
            id = ProtocolType.RLOGIN.name, protocolType = ProtocolType.RLOGIN,
            name = "Rlogin", description = "Legacy Unix remote login protocol with host-based trust.",
            category = ProtocolCategory.TERMINAL, tags = listOf("unix", "legacy"),
        ),

        // ── Network ──────────────────────────────────────────────────────────
        ProtocolCatalogEntry(
            id = ProtocolType.SNMP.name, protocolType = ProtocolType.SNMP,
            name = "SNMP", description = "Native v1/v2c/v3 SNMP manager: device dashboard, MIB browser, Get/Set/Walk, and trap receiver.",
            category = ProtocolCategory.NETWORK, tags = listOf("monitoring", "network device"),
            badges = listOf(ProtocolBadge.NEW),
        ),
        ProtocolCatalogEntry(
            id = ProtocolType.NETCONF.name, protocolType = ProtocolType.NETCONF,
            name = "NETCONF", description = "Native NETCONF-over-SSH configuration management for network equipment — get/edit-config, commit workflows, YANG capabilities, and live event notifications.",
            category = ProtocolCategory.NETWORK, tags = listOf("network device", "config", "yang", "router", "switch", "automation"),
            aliases = listOf("RFC 6241", "RFC 6242"),
            badges = listOf(ProtocolBadge.NEW),
        ),
        ProtocolCatalogEntry(
            id = ProtocolType.RESTCONF.name, protocolType = ProtocolType.RESTCONF,
            name = "RESTCONF", description = "Native RFC 8040 REST/YANG client for network device configuration and operational data.",
            category = ProtocolCategory.NETWORK, tags = listOf("network device", "rest", "yang", "api"),
            badges = listOf(ProtocolBadge.NEW),
        ),

        // ── Industrial ───────────────────────────────────────────────────────
        ProtocolCatalogEntry(
            // MODBUS-TCP FEATURE (Part 2/2): flipped from protocolType =
            // null — now a real, implemented protocol backed by
            // com.systemsgo.hex.modbus.protocol.ModbusTcpClient.
            id = ProtocolType.MODBUS_TCP.name, protocolType = ProtocolType.MODBUS_TCP,
            name = "Modbus TCP", description = "Native Modbus/TCP master: live dashboard of user-defined points and read/write access to coils, discrete inputs, and holding/input registers.",
            category = ProtocolCategory.INDUSTRIAL, tags = listOf("plc", "scada", "automation"),
            badges = listOf(ProtocolBadge.NEW),
        ),

        // ── Web ──────────────────────────────────────────────────────────────
        ProtocolCatalogEntry(
            id = ProtocolType.WEB.name, protocolType = ProtocolType.WEB,
            name = "Web", description = "Embedded-browser session for RD Web Access or any HTTPS management portal.",
            category = ProtocolCategory.WEB, tags = listOf("browser", "rdweb", "portal"),
        ),
    )

    /** Fast id → entry lookup for favorites/recents hydration. */
    val byId: Map<String, ProtocolCatalogEntry> = entries.associateBy { it.id }

    /**
     * The subset of [entries] the "Add Connection" picker actually shows *today*.
     *
     * REVISED product decision (was: hide [ProtocolLaunchKind.NOT_YET_SUPPORTED]
     * entries entirely). Hiding them meant a user searching for e.g. "Proxmox"
     * or "Wake-on-LAN" (both NOT_YET_SUPPORTED at the time) found nothing at
     * all and had no way to know the app was even aware of the protocol, let
     * alone that it's on the roadmap. Every catalog entry is now shown;
     * [ProtocolCatalogEntry.launchKind] still decides what tapping it does
     * (open the editor vs. hand off to [ProtocolLaunchKind.NOT_YET_SUPPORTED]'s
     * "request this protocol" sheet — see AddConnectionRoute), and the UI
     * additionally renders a "Coming Soon" badge on not-yet-supported cards
     * (see AddConnectionProtocolScreen's
     * ProtocolCard) so the distinction from a working protocol stays obvious.
     *
     * NOTE: as of today every entry below has a real [ProtocolType] and
     * launches SAVED_CONNECTION — there are currently no NOT_YET_SUPPORTED
     * placeholder entries left. Protocols with no client implementation at
     * all yet (e.g. ONVIF) simply have no entry here rather than a
     * NOT_YET_SUPPORTED placeholder; add one only once real work on that
     * protocol begins.
     */
    val available: List<ProtocolCatalogEntry> = entries

    val popular: List<ProtocolCatalogEntry> = available.filter { it.isPopular }
}
