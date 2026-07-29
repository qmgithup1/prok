package com.systemsgo.hex.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.systemsgo.hex.rdp.transport.RdpTransportMode
import java.util.UUID

/**
 * The remote protocol a profile connects with. Determines which fields in
 * [RdpProfile] are relevant and which client implementation handles the
 * session (see com.systemsgo.hex.remote.RemoteSessionClient).
 */
/**
 * How the byte stream for MS-RDPESP serial-port redirection ([RdpProfile.
 * enableSerialRedirect]) actually reaches its endpoint. See
 * [RdpProfile.serialRedirectMode]'s doc comment and
 * `com.systemsgo.hex.rdp.serial.SerialNetworkBridge` for the client-side
 * implementation of the two network modes.
 */
enum class SerialRedirectMode(val label: String) {
    /** [RdpProfile.serialPortPath] is a local device node, e.g. /dev/ttyUSB0. */
    LOCAL_DEVICE("Local device"),
    /** Plain byte-for-byte TCP relay to [RdpProfile.serialNetworkHost]:[RdpProfile.serialNetworkPort] — no line-control signalling. */
    RAW_TCP("Raw TCP"),
    /** RFC 2217 (telnet COM-PORT-OPTION) to [RdpProfile.serialNetworkHost]:[RdpProfile.serialNetworkPort] — baud/parity/modem-control signalling included. */
    RFC_2217("RFC 2217"),
}

/** Parity setting for a Serial Console line — RFC 2217 §3 SET-PARITY code in parentheses. */
enum class SerialParity(val label: String, val rfc2217Code: Int) {
    NONE("None", 1), ODD("Odd", 2), EVEN("Even", 3), MARK("Mark", 4), SPACE("Space", 5)
}

/** Stop-bits setting for a Serial Console line — RFC 2217 §3 SET-STOPSIZE code in parentheses. */
enum class SerialStopBits(val label: String, val rfc2217Code: Int) {
    ONE("1", 1), TWO("2", 2), ONE_POINT_FIVE("1.5", 3)
}

enum class ProtocolType(val defaultPort: Int, val label: String) {
    RDP(3389, "RDP"),
    VNC(5900, "VNC"),
    SSH(22, "SSH"),
    TELNET(23, "Telnet"),
    // SERIAL-CONSOLE FEATURE (Part 1/N): a standalone terminal session onto a
    // serial console, independent of the RDP-serial-redirect feature below
    // (SerialRedirectMode/SerialNetworkBridge, which forwards a serial
    // device *into* an RDP session) — this is a session in its own right,
    // exactly like TELNET/RLOGIN, driven by
    // com.systemsgo.hex.serialconsole.protocol.SerialConsoleClient through
    // TerminalScreen. Reuses [host]/[port] (default 2217, the conventional
    // RFC 2217 port also used as ser2net's default) the same way every other
    // terminal protocol does. All three transport modes are real and
    // working today: RAW_TCP/RFC_2217 (reusing SerialRedirectMode's enum
    // since the wire-level distinction is identical to the RDP-redirect
    // feature's), plus LOCAL_DEVICE (a directly-attached USB-OTG serial
    // adapter), implemented via Android's own USB Host API against the
    // CDC-ACM/CP210x/FTDI/CH340-CH341/PL2303 drivers in
    // com.systemsgo.hex.serialconsole.usb.UsbSerialProbe — see
    // SerialConsoleClient's class doc for details. Unlike the RDP-redirect
    // feature's own LOCAL_DEVICE mode (which leans on FreeRDP's native tty
    // open() against a real remote-OS driver), this one talks to the USB
    // device itself, so it's pure Android-side USB host code with no
    // native/JNI component.
    SERIAL_CONSOLE(2217, "Serial Console"),
    // RLOGIN FEATURE: RFC 1282 rlogin — a plain-TCP terminal protocol like
    // TELNET, but with one difference that actually matters day-to-day: the
    // client sends its local username, the desired remote username, and the
    // terminal type as part of the connection handshake itself (see
    // [com.systemsgo.hex.rlogin.protocol.RloginClient]), so a correctly
    // configured server (a matching ~/.rhosts or /etc/hosts.equiv entry) can
    // skip the login prompt entirely — something Telnet has no protocol-level
    // mechanism for at all. Historically found on Unix/BSD hosts and still
    // common on older network appliances and lab/test equipment.
    RLOGIN(513, "Rlogin"),
    // MOSH FEATURE: mobile shell — SSH-bootstrapped (see the
    // moshRemoteServerCommand/moshUdpPortRange/moshRemoteLocale/moshColorMode/
    // moshPredictionMode fields below), then a UDP-based SSP (State
    // Synchronization Protocol) session for the rest of its lifetime, unlike
    // every other terminal protocol here which stays on one TCP connection
    // throughout. See com.systemsgo.hex.mosh.protocol.MoshSessionClient (the
    // RemoteSessionClient) and MoshSessionManager (the SSH exec/bootstrap
    // step) for the actual implementation, and RemoteSessionFactory.create's
    // MOSH branch for how a saved RdpProfile becomes the MoshProfile those
    // classes take. Default port 22 because the bootstrap itself is plain
    // SSH — Mosh's real UDP port is negotiated per-session, never fixed.
    MOSH(22, "Mosh"),
    // SPICE-PROTOCOL FEATURE (Part 1/N): مضاف هنا فقط ليمثّل نوع البروفايل —
    // لا يوجد بعد SpiceSessionClient يطبّق RemoteSessionClient لهذا النوع
    // (سيأتي في Part 4/N)، ولا حقول بروفايل خاصة بـ SPICE (tls_port،
    // كلمة مرور منفصلة) في RdpProfile بعد. حالياً هذا الإدخال غير مُستهلك
    // من RemoteSessionFactory — إضافته الآن فقط لتثبيت الاسم/المنفذ
    // الافتراضي قبل بناء بقية الطبقات فوقه. راجع
    // com.systemsgo.hex.spice.native.SpiceBridge وsystemsgo_spice_jni.c
    // لخريطة الأجزاء القادمة.
    SPICE(5900, "SPICE"),
    // RTSP FEATURE: native RTSP 1.0 client for IP-camera/NVR live video — see
    // com.systemsgo.hex.rtsp.protocol.RtspClient (the RemoteSessionClient
    // implementation: control-plane handshake, RTP/H.264 depacketization,
    // MediaCodec decode) and RtspCredentials for the connection-detail model
    // it actually takes. Reuses host/port/username/password exactly like
    // every other profile; rtspStreamPath/rtspTransportMode/rtspUseTls below
    // are the only protocol-specific fields it needs. View-only by design —
    // a camera stream has no keyboard/mouse to redirect — so it behaves like
    // SSH's "no-op input" pattern on the input side, but like RDP/VNC on the
    // output side (frameUpdates), which is what lets it reuse the existing
    // RdpSessionActivity rendering pipeline unchanged.
    RTSP(554, "RTSP"),
    // WEB-PORTAL FEATURE: not a framebuffer/terminal protocol like the four
    // above — an embedded-browser session (see com.systemsgo.hex.web.WebPortalActivity)
    // for RD Web Access (webfeed.aspx / RDWeb) or any other HTTPS management
    // portal (Guacamole, ESXi/vCenter, iDRAC/iLO, Proxmox, pfSense, ...).
    // Because it isn't a RemoteSessionClient, it deliberately never reaches
    // RemoteSessionFactory.create() or RdpSessionActivity —
    // com.systemsgo.hex.remote.SessionLauncher routes WEB profiles to
    // WebPortalActivity instead, before either of those is ever consulted.
    WEB(443, "Web"),
    // REDFISH-IPMI FEATURE: native BMC/out-of-band server management — not an
    // embedded browser like WEB (which can already reach a Redfish/iDRAC/iLO
    // *web UI*), but a structured client speaking the actual management
    // protocol. See com.systemsgo.hex.redfish.protocol.RedfishClient and
    // com.systemsgo.hex.ipmi.protocol.IpmiClient, and
    // com.systemsgo.hex.ui.screens.BmcManagementScreen for the session UI.
    // Reuses host/port/username/password/acceptSelfSignedCertificate exactly
    // like every other protocol; the only protocol-specific column is
    // ipmiPrivilegeLevel (irrelevant to REDFISH, which uses whatever role the
    // BMC account already has).
    REDFISH(443, "Redfish"),
    IPMI(623, "IPMI"),
    // AMT-VPRO FEATURE: Intel AMT / vPro out-of-band management — a third
    // member of the same BMC-management family as REDFISH/IPMI above, but
    // talking to Intel's own Management Engine firmware directly (port
    // 16992 plain / 16993 TLS) instead of a separate BMC chip. See
    // com.systemsgo.hex.amt.protocol.AmtClient and BmcManagementScreen's
    // AMT tab for the session UI. Reuses host/port/username/password/
    // acceptSelfSignedCertificate exactly like REDFISH/IPMI; the one
    // AMT-specific column is amtUseTls (port alone doesn't disambiguate
    // plain-vs-TLS the way it does for e.g. HTTP/HTTPS-on-80/443, since
    // AMT's plain/TLS ports are both non-standard and a user could still
    // point either mode at a nonstandard port).
    AMT(16992, "Intel AMT"),
    // RESTCONF FEATURE (Part 1/4): native RFC 8040 client — a REST/YANG
    // management protocol, architecturally closer to REDFISH (REST over
    // HTTPS, JSON/XML bodies) than to the framebuffer/terminal protocols
    // above, but generic rather than a fixed DMTF resource tree — see
    // com.systemsgo.hex.restconf.protocol.RestconfClient. Routed to its own
    // RestconfExplorerActivity by SessionLauncher (BmcManagementActivity is
    // Redfish/IPMI/AMT-specific UI, not a fit here), same pattern as WEB.
    // Reuses host/username/password/acceptSelfSignedCertificate; port
    // defaults to 443 since RESTCONF servers are overwhelmingly HTTPS-only,
    // but restconfUseHttps below still lets a lab/dev HTTP-only server work.
    RESTCONF(443, "RESTCONF"),

    // SNMP FEATURE: full v1/v2c/v3 SNMP manager — device dashboard, MIB
    // browser, Get/Set/Walk tool, and trap/inform receiver. See
    // com.systemsgo.hex.snmp.protocol.SnmpClient (the native ASN.1 BER +
    // UDP client, no third-party SNMP library) and
    // com.systemsgo.hex.ui.screens.SnmpManagementScreen for the session UI
    // — routed there the same way REDFISH/IPMI/AMT route to
    // BmcManagementActivity (see SessionLauncher), since SNMP is likewise
    // not a RemoteSessionClient/framebuffer session. A profile can *also*
    // enable SNMP as a monitoring add-on on top of any other protocol
    // (RDP/SSH/... a server you already connect to) without changing its
    // protocolType — see [RdpProfile.snmpMonitoringEnabled] for that mode;
    // this SNMP entry itself is only for a profile whose *sole* purpose is
    // SNMP management of a device with no other remote-access protocol
    // (a switch, printer, UPS, etc).
    SNMP(161, "SNMP"),

    // NETCONF FEATURE: native NETCONF-over-SSH (RFC 6242) — a structured
    // config-management RPC protocol, not a framebuffer/terminal session
    // like the four `isTerminal` entries below. See
    // com.systemsgo.hex.netconf.protocol.NetconfClient (transport/RPC
    // engine) and com.systemsgo.hex.ui.screens.NetconfSessionActivity (the
    // session UI) — reuses the same SSH auth/jump-host/proxy machinery as
    // SSH itself (NetconfCredentials/NetconfJumpHop mirror
    // SshCredentials/SshJumpHop) but is never routed through
    // RemoteSessionFactory/RdpSessionActivity, the same way REDFISH/IPMI/AMT
    // route to BmcManagementScreen instead — see
    // com.systemsgo.hex.remote.SessionLauncher for the dispatch.
    NETCONF(830, "NETCONF"),

    // GUACAMOLE-PROTOCOL FEATURE: unlike WEB (an embedded browser pointed at
    // a portal page), this is a *native* client: it authenticates against
    // the Guacamole REST API, lists the account's actual connections, and
    // opens the guacd protocol tunnel directly (see
    // com.systemsgo.hex.guacamole.protocol.GuacamoleTunnelClient),
    // rendering the resulting framebuffer through
    // com.systemsgo.hex.guacamole.protocol.GuacamoleDisplayRenderer and
    // driving it through RemoteSessionFactory/RdpSessionActivity exactly
    // like RDP/VNC do — a real RemoteSessionClient, not a WebView. Default
    // port 8080 is Guacamole's common Tomcat/embedded-server default; most
    // real deployments sit it behind a reverse proxy on 80/443 instead, so
    // this default is a starting point in the editor, not an assumption.
    GUACAMOLE(8080, "Guacamole"),

    // PROXMOX-API FEATURE (Part 1/N): native Proxmox VE REST API client —
    // not an embedded browser like WEB (which can already reach the Proxmox
    // web UI itself, autofilled via WebPortalLoginAutofill's PROXMOX
    // selectors), but a structured client that lists nodes/VMs/containers,
    // shows live status, and drives start/stop/shutdown/reboot directly.
    // Routed to com.systemsgo.hex.ui.screens.ProxmoxManagementActivity by
    // SessionLauncher — same "not a RemoteSessionClient" family as
    // REDFISH/IPMI/AMT/SNMP/NETCONF/RESTCONF, since Proxmox's own API is a
    // management/monitoring surface, not a framebuffer itself. Console
    // access (VNC for QEMU VMs, opened from inside that screen) borrows the
    // Proxmox VNC-proxy ticket and bridges it into the existing VNC engine
    // via RdpSessionActivity's Quick-Connect path — see
    // ProxmoxManagementActivity's openVncConsole() and
    // com.systemsgo.hex.proxmox.ProxmoxVncBridge for that plumbing. Default
    // port 8006 is the Proxmox VE management API's standard HTTPS port.
    // Auth is either an API token (proxmoxAuthMode = TOKEN, the
    // proxmoxTokenId/proxmoxTokenSecret columns) or a realm login
    // (proxmoxAuthMode = PASSWORD, reusing username/password — entered as
    // user@realm, e.g. root@pam) — see com.systemsgo.hex.proxmox.protocol.ProxmoxApiClient.
    PROXMOX(8006, "Proxmox API"),

    // MODBUS-TCP FEATURE (Part 2/2 — wiring the Part-1 protocol engine into
    // the app): native Modbus/TCP master/client — see
    // com.systemsgo.hex.modbus.protocol.ModbusTcpClient (the hand-rolled
    // MBAP + PDU implementation, no third-party Modbus library, mirroring
    // SnmpClient's approach) and com.systemsgo.hex.modbus.ModbusProfileMapper
    // (RdpProfile -> ModbusConnectionConfig -> ModbusTcpClient, the same
    // "reconstruct connection config from saved profile columns" seam every
    // other protocol module here uses). Routed to
    // com.systemsgo.hex.ui.screens.ModbusManagementActivity by
    // SessionLauncher — same "not a RemoteSessionClient" family as
    // REDFISH/IPMI/AMT/SNMP/NETCONF/RESTCONF/PROXMOX, since Modbus/TCP is a
    // register-level polling/monitoring protocol, not a framebuffer (unlike
    // RTSP above, which reuses RdpSessionActivity's rendering pipeline).
    // Default port 502 is the standard Modbus/TCP port. The unit identifier
    // (RdpProfile.modbusUnitId) is Modbus's RTU-heritage slave/device
    // address; most Modbus/TCP-native devices ignore it entirely and it
    // only matters when a TCP/serial gateway routes on it.
    MODBUS_TCP(502, "Modbus TCP"),

    // WAKE-ON-LAN-STANDALONE FEATURE: promotes Wake-on-LAN from a per-profile
    // "wake before connect" add-on (wolEnabled/wolMacAddress/wolBroadcastAddress/
    // wolPort below, still used that way by every other protocol) into a real,
    // independently saveable connection of its own — for a machine the user only
    // ever wants to power on (a home NAS, an office desktop) with no RDP/SSH/VNC
    // profile to attach it to. Reuses the exact same wol* columns and the exact
    // same com.systemsgo.hex.util.WakeOnLanManager.sendMagicPacket() call as the
    // add-on does — a WAKE_ON_LAN profile is really just an RdpProfile with
    // wolEnabled forced on and no meaningful host/port of its own (see
    // ProfileFormDialog's `isWol` bypass of the generic host/port requirement,
    // and MainViewModel.sendWakeOnLan). Not a RemoteSessionClient — same
    // "SessionLauncher intercepts before RdpSessionActivity/RemoteSessionFactory"
    // shape as WEB/PROXMOX/MODBUS_TCP/REDFISH/IPMI/AMT/SNMP/NETCONF/RESTCONF
    // above, except there's no dedicated management Activity either: "Connect"
    // on a WAKE_ON_LAN profile just sends the Magic Packet right where the tap
    // happened (see HomeScreen's launchConnect). Default port 9 is the
    // conventional WoL discard port (WakeOnLanManager.DEFAULT_WOL_PORT) — kept
    // here too so ProtocolCatalogEntry.defaultPort (which reads
    // protocolType.defaultPort) shows something sensible in the picker, even
    // though the profile's actual send always goes through wolPort, not port.
    WAKE_ON_LAN(9, "Wake-on-LAN"),

    // SFTP-STANDALONE FEATURE: promotes SFTP from "only reachable inside an
    // already-open SSH terminal session's file panel" (see
    // ProtocolCatalog's former VIA_SSH_SESSION launchKind for "sftp") into a
    // real, independently saveable connection — for someone who only ever
    // wants file transfer with a given host and has no interest in a
    // terminal session at all. Deliberately reuses the exact same
    // host/port/username/password/sshAuthType/sshPrivateKey/
    // sshPrivateKeyPassphrase columns ProtocolType.SSH already has (see
    // FileTransferScreen.kt's `usesSftpEngine` flag, which now builds the
    // same SftpFileBrowser/SftpConfig for either protocol) — an SFTP profile
    // really is just an SSH profile whose only purpose is the file-transfer
    // channel, not the shell. What it deliberately does NOT carry over from
    // ProtocolType.SSH: the SSH tunnel chain, dynamic SOCKS proxy, X11
    // forwarding, and static port forwards — all shell/terminal-session
    // features FileTransferManager's SftpConfig has no use for (see
    // ProfileFormDialog's ProtocolType.SFTP branch, which renders only the
    // Authentication sub-section, not the rest of ProtocolType.SSH's
    // options). Routed by SessionLauncher straight to the new
    // com.systemsgo.hex.ui.screens.SftpFileTransferActivity — a small,
    // dedicated Activity around the same FileTransferScreen composable
    // RdpSessionActivity's in-session file panel already uses — instead of
    // RdpSessionActivity's terminal/framebuffer UI, since there's no shell
    // to open. Default port 22, same as SSH.
    SFTP(22, "SFTP"),

    // FTP/FTPS-STANDALONE FEATURE: promotes FTP and FTPS from HomeScreen's
    // one-off "Quick Transfer" dialog (STANDALONE_TRANSFER in
    // ProtocolCatalog, FtpTransferDialog + FileTransferManager.FtpConfig)
    // into real, independently saveable connections — same motivation as
    // ProtocolType.SFTP above. FTP and FTPS are deliberately *two*
    // ProtocolType entries sharing *one* set of profile columns (see
    // [RdpProfile.ftpSecurity]/[RdpProfile.ftpPassiveMode]) rather than one
    // entry with a mode flag, so they get separate catalog cards, separate
    // icons, and separate ShortcutHelper/RemoteSessionFactory branches — the
    // same "two entries, shared columns" shape SFTP/SCP have inside a single
    // ProtocolType.SFTP, just inverted (there it's one ProtocolType with two
    // catalog cards; here it's two ProtocolTypes sharing columns) because FTP
    // vs FTPS is a real on-the-wire distinction (TLS or not) that deserves
    // its own saved identity, unlike SFTP vs SCP which are just two upload/
    // download engines over the identical SSH session. Routed by
    // SessionLauncher to a dedicated file-transfer Activity, the same
    // FileTransferScreen-hosting pattern as SftpFileTransferActivity. Default
    // port 21, the standard FTP/FTPS control-connection port for both (FTPS
    // implicit mode's classic port 990 is set per-profile by the user, not
    // assumed from protocolType.defaultPort, since explicit FTPS — the more
    // common mode — still uses 21).
    FTP(21, "FTP"),
    FTPS(21, "FTPS"),

    // WEBDAV-STANDALONE FEATURE: promotes WebDAV from HomeScreen's Quick
    // Transfer dialog (WebDavTransferDialog + FileTransferManager.WebDavConfig)
    // into a real saved connection — same motivation as SFTP/FTP above. Its
    // one connection detail is a full base URL (see
    // [RdpProfile.webdavBaseUrl]'s doc comment for why host/port alone can't
    // represent it), so unlike every other entry here host/port are ignored
    // for this type. Default port 443 only seeds ProtocolCatalogEntry's
    // display; the actual scheme/port live inside webdavBaseUrl itself.
    WEBDAV(443, "WebDAV"),

    // SMB-STANDALONE FEATURE: promotes SMB from HomeScreen's Quick Transfer
    // dialog (SmbTransferDialog + FileTransferManager.SmbConfig) into a real
    // saved connection — same motivation as SFTP/FTP/WEBDAV above. Adds
    // [RdpProfile.smbShare] (required — SMB has no "browse the whole server"
    // mode this app implements, only a specific share) and
    // [RdpProfile.smbDomain] (optional workgroup/domain) alongside the
    // existing host/port/username/password columns. Default port 445, the
    // standard SMB-over-TCP (no NetBIOS) port.
    SMB(445, "SMB"),

    // NFS-STANDALONE FEATURE: promotes NFS from HomeScreen's Quick Transfer
    // dialog (NfsTransferDialog + FileTransferManager.NfsConfig) into a real
    // saved connection — same motivation as SFTP/FTP/WEBDAV/SMB above. The
    // odd one out of the five: NFSv3/AUTH_SYS has no username/password at
    // all (see [RdpProfile.nfsExportPath]'s doc comment), so its
    // ProfileFormDialog branch hides the Authentication section entirely
    // and shows [RdpProfile.nfsUid]/[RdpProfile.nfsGid]/[RdpProfile.nfsMountdPort]
    // instead. Default port 2049, the standard NFS port (used for the NFS
    // protocol itself once mountd has handed back a file handle — see
    // nfsMountdPort for the separate, usually-dynamic mountd port).
    NFS(2049, "NFS"),

    // VIRTUALBOX-VRDE FEATURE (Part 1/N): VirtualBox's Remote Display
    // Extension is wire-compatible RDP — VirtualBox's built-in VRDE server
    // *is* an RDP server (no NLA/CredSSP support in stock builds, and no
    // separate framebuffer protocol of its own) — so unlike PROXMOX/AMT/
    // MODBUS_TCP/etc. above, this does NOT get its own RemoteSessionClient.
    // It reuses RdpRemoteAdapter/FreeRDP exactly like ProtocolType.RDP does;
    // the only behavioral difference RemoteSessionFactory.create applies is
    // forcing useNla off for this protocol type (see that file's
    // VIRTUALBOX_VRDE branch), since VRDE's NLA support is the exception
    // rather than the rule. vrdeAuthType/vrdeMultiConnectionAllowed below
    // are host-side VRDE settings surfaced in the editor for the user's
    // reference (what the VirtualBox VM's own VRDE config expects) — not
    // yet sent by the client, since VRDE auth method is negotiated
    // server-side, not client-side; see
    // com.systemsgo.hex.virtualbox.VirtualBoxVrdeCredentials's doc comment.
    VIRTUALBOX_VRDE(3389, "VirtualBox VRDE"),

    // VMWARE-VSPHERE FEATURE (Part 3/N): native vSphere/ESXi management API
    // client — same "management API" family as PROXMOX/REDFISH/IPMI/AMT/
    // SNMP/NETCONF/RESTCONF/MODBUS_TCP above, not a RemoteSessionClient.
    // Lists VMs and hosts across the connected vCenter/ESXi host's REST
    // inventory, drives power operations, and opens a console via a WEBMKS
    // ticket (POST .../console/tickets) bridged into a loopback WebSocket
    // VNC session — see com.systemsgo.hex.vsphere.protocol.VSphereModels /
    // VSphereApiClient (Part 1/N: config/model layer + REST client),
    // com.systemsgo.hex.vsphere.VSphereVncBridge (Part 2/N: the WEBMKS
    // bridge — see its doc comment for why this is the same raw-RFB-over-
    // websocket shape as ProxmoxVncBridge, confirmed against VMware's own
    // docs rather than assumed), and
    // com.systemsgo.hex.ui.screens.VSphereManagementActivity (Part 3/N: the
    // inventory/power/console screen, wired end to end via
    // com.systemsgo.hex.remote.SessionLauncher). vsphereApiMode below picks
    // REST (vSphere 6.7+ /api endpoints — the only mode this app's client
    // implements) vs SOAP (vim25 — needed for a standalone ESXi host with no
    // vCenter in front, or older/thinner-REST-coverage vCenters; not
    // implemented — VSphereApiClient throws a clear error if a profile is
    // set to SOAP rather than silently misbehaving). Default port 443 since
    // both REST and SOAP endpoints are HTTPS-only.
    VMWARE_VSPHERE(443, "VMware vSphere API");

    /**
     * TELNET FEATURE: whether this protocol is a raw text terminal (no
     * framebuffer) — the same "simpler UI" branch RdpSessionActivity/
     * Components.kt already carve out for SSH via TerminalScreen. Telnet
     * shares that exact UI/session shape (see
     * [com.systemsgo.hex.telnet.protocol.TelnetClient]), so every place that
     * used to special-case `== ProtocolType.SSH` to mean "this is a
     * terminal, not a canvas" now checks [isTerminal] instead, without
     * changing what SSH-*specific* behaviour (key auth, agent forwarding,
     * TOFU host keys, keyboard-interactive prompts) applies to.
     */
    val isTerminal: Boolean get() = this == SSH || this == TELNET || this == RLOGIN || this == SERIAL_CONSOLE || this == MOSH

    companion object {
        fun fromName(name: String): ProtocolType =
            entries.firstOrNull { it.name == name } ?: RDP
    }
}

/** SSH authentication method. */
enum class SshAuthType { PASSWORD, PRIVATE_KEY }

/** PROXMOX-API FEATURE: how a Proxmox profile authenticates — an API token (stateless, revocable independent of the user) or a realm login (username@realm/password, ticket-based). */
enum class ProxmoxAuthMode { TOKEN, PASSWORD }

/**
 * VIRTUALBOX-VRDE FEATURE: mirrors VirtualBox's own `--vrdeauthtype`
 * setting (NULL / EXTERNAL / GUEST), shown in the editor so the user picks
 * the client-side username/password behavior that matches how the *host*
 * VM's VRDE is actually configured. NULL means the VM takes any
 * credentials (or none); EXTERNAL delegates to a VRDEAuthLibrary module on
 * the host (e.g. Active Directory) and expects real credentials; GUEST
 * passes credentials through to the guest OS's own login screen. Purely
 * informational today — see [ProtocolType.VIRTUALBOX_VRDE]'s doc comment.
 */
enum class VrdeAuthType { NULL_AUTH, EXTERNAL, GUEST }

/** VMWARE-VSPHERE FEATURE: which API family a profile talks to — see [ProtocolType.VMWARE_VSPHERE]'s doc comment. */
enum class VSphereApiMode { REST, SOAP }

/**
 * RESTCONF FEATURE (Part 1/4): every auth mechanism the RESTCONF client
 * ([com.systemsgo.hex.restconf.protocol.RestconfClient]) supports, stored
 * as this enum's name in [RdpProfile.restconfAuthType] — same
 * string-backed-enum convention as [SshAuthType]/`gatewayAuthMode`. See
 * `com.systemsgo.hex.restconf.protocol.RestconfAuth` for how each one is
 * actually wired onto the HTTP client.
 */
enum class RestconfAuthType {
    NONE, BASIC, DIGEST, BEARER_TOKEN, OAUTH2, API_KEY,
    CLIENT_CERTIFICATE, MUTUAL_TLS, JWT, CUSTOM_HEADER;

    companion object {
        fun fromName(value: String): RestconfAuthType =
            entries.firstOrNull { it.name == value } ?: BASIC
    }
}

/** RESTCONF FEATURE (Part 1/4): JSON vs XML — RFC 8040 requires a RESTCONF server to support both; this is the client's *preferred* format (sent as Accept/Content-Type), not a hard requirement, since [com.systemsgo.hex.restconf.protocol.RestconfResponse.detectedFormat] sniffs the actual reply either way. */
enum class RestconfDataFormat {
    JSON, XML;

    companion object {
        fun fromName(value: String): RestconfDataFormat =
            entries.firstOrNull { it.name == value } ?: JSON
    }
}

/**
 * REMOTEAPP-WINDOWS FEATURE: how a RemoteApp (RAIL) session's window(s) are
 * presented locally once real per-window tracking exists — see
 * [com.systemsgo.hex.session.RemoteAppWindowManager] and the "rail" channel
 * wiring this enum is waiting on in systemsgo_jni.c (still just capability
 * negotiation today — see nativeConnect's REMOTEAPP FIX comment).
 *
 * This is a pure Kotlin/rendering-layer choice, unlike remoteAppEnabled/
 * Program/WorkingDir/CmdLine above: the server is never told which of these
 * two modes the client picked, only that RemoteApp mode is on at all. Both
 * modes receive the exact same Window State Order PDUs from the server; the
 * only difference is how RdpSessionActivity composites them once the "rail"
 * channel actually delivers window rects (tracked in
 * RemoteAppWindowManager.windows).
 */
enum class RemoteAppDisplayMode {
    /** One remote window filling the screen, no local title bar, no desktop
     *  background — the common "just run this one published app" case. If
     *  the server opens more than one window for the published app, only
     *  the most recently activated one is shown; switching between them
     *  still requires MULTI_WINDOW. */
    SINGLE_WINDOW,

    /** Every open remote window is selectable through a switcher (horizontal
     *  row of window icons — see Components.kt's RemoteAppDisplayModePicker
     *  for the mode toggle and RdpSessionActivity for the switcher itself),
     *  matching a real desktop RemoteApp client (e.g. mstsc /remoteapp,
     *  RD Web) where several windows of the same published app (or several
     *  different published apps) can be open and switched between at once. */
    MULTI_WINDOW;

    companion object {
        fun fromName(name: String): RemoteAppDisplayMode =
            entries.firstOrNull { it.name == name } ?: SINGLE_WINDOW
    }
}

/**
 * CODEC-NEGOTIATION FEATURE: per-connection preference for which RDPGFX
 * codec(s) this client offers the server, mirrored 1:1 (by ordinal) onto
 * [com.systemsgo.hex.rdp.native.AFreeRdpBridge.CodecPreference] — see that
 * enum's doc comment for the exhaustive explanation of what each value
 * actually changes in the RDPGFX capability exchange and
 * systemsgo_apply_codec_preference() in systemsgo_jni.c.
 *
 * This is deliberately a *separate* pure-Kotlin enum rather than a direct
 * reference to the native-bridge one, following the same layering
 * [RemoteAppDisplayMode] above already uses: [RdpProfile]/[RdpCredentials]
 * are plain data classes with no dependency on the native/JNI layer, so
 * Room, the profile form, and RemoteSessionFactory never need to know
 * AFreeRdpBridge exists. RdpRemoteAdapter.connect() is the one place that
 * translates this into AFreeRdpBridge.CodecPreference right before calling
 * into the bridge.
 */
enum class CodecPreference {
    AUTO,
    PREFER_AV1,
    PREFER_H264,
    DISABLE_MODERN_CODECS;

    companion object {
        fun fromName(name: String): CodecPreference =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * RDP Connection Profile stored in local database.
 * Supports multiple simultaneous sessions, and — despite the historical name
 * kept for migration simplicity — now supports RDP, VNC, and SSH connections
 * via [protocolType].
 */
@Entity(tableName = "rdp_profiles")
data class RdpProfile(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,                          // Custom display name e.g. "Work Server"
    val protocolType: ProtocolType = ProtocolType.RDP,
    val host: String,                          // IP or hostname
    val port: Int = 3389,                      // Default RDP port
    val username: String,
    val password: String,                      // Stored encrypted

    // ── RDP-specific ───────────────────────────────────────────────────────
    val domain: String = "",                   // Windows domain
    val width: Int = 0,                        // 0 = auto detect
    val height: Int = 0,                       // 0 = auto detect
    val colorDepth: Int = 32,                  // 16, 24, or 32 bit
    val enableSound: Boolean = false,
    // MIC-REDIRECT FEATURE: audio *capture* redirection (MS-RDPEAI "audin"
    // channel) — lets the remote session use this device's microphone as its
    // input device (e.g. for a voice call or dictation running on the remote
    // desktop). This is the input-direction counterpart to [enableSound]
    // above, which only covers *playback* (MS-RDPEA "rdpsnd", remote → local
    // speaker). Requires RECORD_AUDIO at runtime (requested once in
    // RdpSessionActivity.onCreate) and a FreeRDP prebuilt that was actually
    // built with an audio-capture backend (see app/src/main/cpp/SETUP.md);
    // if the server doesn't support RDPEAI either, this is simply a no-op
    // for that session, same as [enableSound] would be.
    val enableMicRedirect: Boolean = false,
    val enableClipboard: Boolean = true,
    val enableDriveRedirect: Boolean = false,
    // PRINTER-REDIRECT FEATURE: MS-RDPEPC printer-redirection over the same
    // "rdpdr" device-redirection channel enableDriveRedirect above already
    // uses (a "printer" device instead of a "drive" one). Lets the remote
    // session see this device as a printer (e.g. "Android (Redirected)") and
    // send it print jobs, which RemotePrintManager then hands to Android's
    // own Print Framework (android.print.PrintManager) — so the job is
    // fulfilled by whatever the device already knows how to print to:
    // a Wi-Fi/network printer, a USB printer (if a matching print service is
    // installed), or "Save as PDF" (built into every Android Print Framework
    // install). Defaults to disabled, matching every other redirection
    // toggle's safe-by-default, explicit opt-in pattern. Whether this
    // actually does anything for a given build also depends on
    // AFreeRdpBridge.isPrinterBackendAvailable — see that property's doc and
    // app/src/main/cpp/SETUP.md's printer-redirection section for the
    // current native-build gap (this build's FreeRDP prebuilt has
    // WITH_CUPS=OFF, same as the smartcard/PCSC gap documented there).
    val enablePrinterRedirect: Boolean = false,
    // WEBCAM-REDIRECT FEATURE: MS-RDPECAM camera redirection, registered as
    // a dynamic virtual channel ("rdpecam") — unlike enablePrinterRedirect
    // above, this does not ride the "rdpdr" device-redirection channel.
    // Lets the remote session use the device's camera as if it were a
    // locally-attached webcam (e.g. for Teams/Zoom calls run on the remote
    // desktop). Defaults to disabled, same safe-by-default, explicit opt-in
    // pattern as every other redirection toggle. Whether this actually does
    // anything for a given build also depends on
    // AFreeRdpBridge.isWebcamBackendAvailable — see that property's doc and
    // app/src/main/cpp/SETUP.md's webcam-redirection section. Opening the
    // camera itself additionally requires the CAMERA runtime permission
    // (requested in RdpSessionActivity.onCreate, mirroring the existing
    // RECORD_AUDIO request for enableMicRedirect).
    val enableWebcamRedirect: Boolean = false,
    // SMARTCARD-REDIRECT FEATURE: MS-RDPESC smart-card redirection over the
    // same "rdpdr" device-redirection channel enableDriveRedirect/
    // enablePrinterRedirect above already use (a "smartcard" device instead
    // of "drive"/"printer"). Lets the remote session see a PC/SC reader
    // (e.g. "Android Smart Card") backed by whatever card is presented on
    // the device — the main enterprise use case being smart-card logon /
    // NLA with a PIV or CAC card once inserted. Defaults to disabled, same
    // safe-by-default, explicit opt-in pattern as every other redirection
    // toggle. Whether this actually does anything for a given build also
    // depends on AFreeRdpBridge.isSmartcardBackendAvailable — see that
    // property's doc and app/src/main/cpp/SETUP.md's smart-card section.
    // IMPORTANT: even when the backend is available, this project does not
    // yet ship an in-app PC/SC resource-manager bridge (no pcscd-equivalent
    // wired to a USB-CCID or NFC/OMAPI reader) — see
    // isSmartcardBackendAvailable's doc comment for what that gap means in
    // practice before relying on this for an actual smart-card logon.
    val enableSmartcardRedirect: Boolean = false,
    // PARALLEL-REDIRECT FEATURE: MS-RDPEFS "rdpdr" device-redirection channel,
    // registered with a "parallel" device instead of "drive"/"printer"/
    // "smartcard" — same static "rdpdr" channel enableDriveRedirect/
    // enablePrinterRedirect/enableSmartcardRedirect above already use. Unlike
    // drive redirect (which always points at this app's own sandboxed
    // external-files directory), a parallel port has no meaningful
    // Android-side default: it must point at an actual local device node
    // (e.g. a USB-OTG parallel/serial adapter enumerated under /dev), so the
    // path is user-supplied per profile — see [parallelPortPath] below.
    // Defaults to disabled, same safe-by-default, explicit opt-in pattern as
    // every other redirection toggle. See systemsgo_jni.c's nativeConnect for
    // the freerdp_client_add_device_channel(..., "parallel", ...) block this
    // sets up — unconditional (no *_BACKEND_AVAILABLE gate) because, unlike
    // printer/smartcard, FreeRDP's parallel-port channel plugin has no extra
    // desktop library dependency (no CUPS/PCSC equivalent) — it just opens
    // the given device path directly, so it is already compiled into every
    // FreeRDP build this app ships.
    val enableParallelRedirect: Boolean = false,
    // Absolute path of the local (Android-side) device node exposed as the
    // remote session's parallel port (e.g. "/dev/ttyUSB0" for a USB-OTG
    // adapter that exposes one). Ignored when enableParallelRedirect is
    // false. Left blank by default — the toggle above has no effect until
    // the user fills this in, mirroring the "skip if path empty" guard the
    // drive-redirect native code already uses for its own path argument.
    val parallelPortPath: String = "",
    // SERIAL-REDIRECT FEATURE: MS-RDPESP serial-port redirection over the
    // same "rdpdr" channel as every device above, just with a "serial"
    // device instead of "parallel"/"drive"/"printer"/"smartcard". Same
    // reasoning/shape as enableParallelRedirect/parallelPortPath
    // immediately above — no Android-side default, so [serialPortPath]
    // must be a real local device node (typically /dev/ttyUSB* or
    // /dev/ttyACM* from a USB-OTG serial adapter). Unconditional (no
    // *_BACKEND_AVAILABLE gate), same reason as parallel: FreeRDP's serial
    // channel plugin needs no extra desktop library.
    val enableSerialRedirect: Boolean = false,
    val serialPortPath: String = "",
    // SERIAL-OVER-NETWORK FEATURE: lets the serial redirect above be backed
    // by a network endpoint instead of a physical local device node —
    // useful when there is no USB-OTG adapter at all and the "serial port"
    // is actually served by a network-attached device server (e.g.
    // ser2net, a KVM/PDU's serial-over-LAN port, an industrial gateway).
    // [serialRedirectMode] picks how the byte stream reaches that endpoint;
    // [serialNetworkHost]/[serialNetworkPort] are only read when the mode
    // isn't [SerialRedirectMode.LOCAL_DEVICE] (in which case
    // [serialPortPath] above is used exactly as before). See
    // com.systemsgo.hex.rdp.serial.SerialNetworkBridge for the client
    // implementation of both network modes, and its class doc for how
    // RFC 2217's telnet COM-PORT-OPTION subnegotiation maps onto the same
    // termios/modem-control surface FreeRDP's serial channel expects from a
    // real tty — the native plumbing that lets FreeRDP hand that surface to
    // a network socket instead of an actual device path is tracked
    // separately, see SerialNetworkBridge's "NEXT STEPS" doc section.
    val serialRedirectMode: SerialRedirectMode = SerialRedirectMode.LOCAL_DEVICE,
    val serialNetworkHost: String = "",
    val serialNetworkPort: Int = 2217,
    // SERIAL-CONSOLE FEATURE (Part 1/N): standalone-session fields for
    // ProtocolType.SERIAL_CONSOLE — do not confuse with
    // enableSerialRedirect/serialPortPath/serialRedirectMode/
    // serialNetworkHost/serialNetworkPort above, which back a completely
    // different feature (a serial device redirected *into* an RDP session).
    // This profile's own [host]/[port] are the Serial Console's endpoint
    // (an RFC 2217 or raw-TCP serial-device server, e.g. ser2net); the
    // fields below are the serial-line parameters this client asks that
    // server for (RFC_2217) or sets on the local USB-OTG adapter
    // (LOCAL_DEVICE, via UsbSerialDriverPort.setLineCoding) — RAW_TCP has
    // no line-control channel to carry them over at all (the byte-for-byte
    // pipe expects both ends already agree out of band). See
    // SerialConsoleClient's class doc for exactly which transport reads
    // which of these.
    val serialConsoleTransport: SerialRedirectMode = SerialRedirectMode.RFC_2217,
    val serialConsoleBaudRate: Int = 9600,
    val serialConsoleDataBits: Int = 8,
    val serialConsoleParity: SerialParity = SerialParity.NONE,
    val serialConsoleStopBits: SerialStopBits = SerialStopBits.ONE,
    // Only read when serialConsoleTransport == LOCAL_DEVICE — see
    // SerialConsoleClient.connectLocalDevice().
    val serialConsoleDevicePath: String = "",
    // Hardware RTS/CTS flow control for the LOCAL_DEVICE transport, wired
    // to UsbSerialDriverPort.setFlowControl on each chipset driver in
    // UsbSerialDrivers.kt (PL2303/FTDI/CP210x/CH340-CH341 do real
    // register-level flow control; CDC-ACM is a documented no-op — see
    // that file for why). No RFC_2217 equivalent — the COM-PORT-OPTION
    // subnegotiation this class speaks for that transport has no
    // flow-control command. Defaults to false, matching every chipset's
    // own power-on default.
    val serialConsoleHardwareFlowControl: Boolean = false,
    // BUG-3 FIX: ignoreCert was hard-wired to false after the MITM-vuln fix, which broke
    // connections to home/office RDP servers with self-signed certificates.
    // Adding an explicit opt-in field lets users accept a specific server's cert
    // without opening a blanket MITM hole.
    val acceptSelfSignedCertificate: Boolean = false,
    // DEAD-CODE FIX (item #8): this column is no longer read anywhere at connect
    // time — RemoteSessionFactory.create() only ever consults the single global
    // AppSettings.performanceLevel (Settings → Connection), not this per-profile
    // value. MainViewModel.addProfile() also no longer computes/overwrites it on
    // save (previously did a live network-quality lookup here for no benefit,
    // since the result was never read back). The field/column is kept only for
    // Room schema compatibility with existing installs — removing it outright
    // would require a destructive migration for no behavioural gain. New history
    // (BUG-N2 FIX) kept for context: default was once RdpPerformance.LAN (= 3);
    // it was changed to AUTO (= 4) to fix a now-removed addProfile() sentinel bug
    // where LAN's non-zero value skipped a (since-removed) recommendation branch.
    val performanceFlags: Int = RdpPerformance.AUTO,

    // ── RD Gateway (RDP only) ──────────────────────────────────────────────
    val gatewayEnabled: Boolean = false,
    val gatewayHost: String = "",
    val gatewayPort: Int = 443,
    val gatewayUsername: String = "",
    val gatewayPassword: String = "",
    val gatewayDomain: String = "",
    // ENTRA-ID-AUTH FEATURE: how the Gateway hop authenticates — see
    // GatewayAuthMode's doc comment. Stored as the enum's name, same
    // String-backed-enum pattern as remoteAppDisplayMode/codecPreference
    // below (MIGRATION_29_30 adds this column, default 'PASSWORD' so every
    // existing profile keeps using gatewayUsername/gatewayPassword exactly
    // as before until a user explicitly switches a profile to Entra ID).
    val gatewayAuthMode: String = GatewayAuthMode.PASSWORD.name,
    // The UPN (e.g. "user@contoso.com") of the Entra ID account last used to
    // sign in for this profile's Gateway hop — display-only ("Signed in as
    // ..." in GatewaySection), never used for auth itself. The actual MSAL
    // account object / tokens live in MSAL's own encrypted cache (see
    // EntraIdAuthManager); this column just lets the UI show *which* account
    // is linked without re-hitting MSAL's cache on every recomposition. Kept
    // in sync with EntraSignInLinkStore's encrypted-prefs copy — see that
    // class's doc comment for why both exist.
    val entraLinkedUpn: String = "",
    // ENTRA-ID-AUTH FEATURE: the Application ID URI scope of the Azure AD
    // Application Proxy application that fronts THIS profile's Gateway —
    // e.g. "api://12345678-abcd-1234-abcd-1234567890ab/user_impersonation"
    // (Azure Portal -> Enterprise Applications -> the App Proxy app ->
    // Expose an API -> Application ID URI + the "user_impersonation" scope
    // name). Per-profile, not app-wide: SystemsGo is a general-purpose client
    // that can connect to any organization's RD Gateway, each behind its
    // own distinct Azure AD tenant/app registration with its own
    // Application ID URI — there is no single value that works for every
    // user (see MIGRATION_30_31's doc comment for why this couldn't just be
    // a hardcoded constant in GatewayTokenProvider). Only meaningful when
    // gatewayAuthMode is ENTRA_ID; GatewayTokenProvider.resolve() returns
    // Result.MissingScope if this is blank in that mode, so
    // GatewaySection/EntraGatewaySignInSection can prompt the user to fill
    // it in rather than acquiring a token for the wrong audience.
    val gatewayScopeUri: String = "",

    // ── OUTBOUND-PROXY FEATURE (RDP only) ───────────────────────────────────
    // Routes THIS device's own TCP connection to gatewayHost:gatewayPort (if
    // Gateway is enabled) or straight to host:port (if not) through a
    // SOCKS/HTTP proxy — see AFreeRdpBridge.connect()'s proxyEnabled doc
    // comment for how this differs from RD Gateway just above (an RDP-aware
    // relay the server trusts) and from socksProxyEnabled below (this app's
    // own outbound SSH-tunneled SOCKS server for *other* apps — unrelated
    // direction). Stored as ProxyType's enum name, same String-backed-enum
    // pattern as remoteAppDisplayMode/codecPreference below — needs a new
    // Room column + migration in SystemsGoDatabase.kt, same as those.
    val proxyEnabled: Boolean = false,
    val proxyType: ProxyType = ProxyType.SOCKS,
    val proxyHost: String = "",
    val proxyPort: Int = 1080,
    val proxyUsername: String = "",
    val proxyPassword: String = "",

    // PAC-SUPPORT FEATURE: URL of a Proxy Auto-Config (.pac) file that
    // decides the outbound proxy *dynamically*, per destination, instead of
    // the fixed proxyHost/proxyPort above. See
    // [com.systemsgo.hex.util.PacFileParser] (fetch + execute the script)
    // and [com.systemsgo.hex.proxy.PacProxyResolver] (the glue that turns a
    // profile + this URL into the actual proxy decision right before
    // connect — see that class's doc comment for the full resolution flow).
    // Applies to BOTH the RDP outbound-proxy path (AFreeRdpBridge, same as
    // proxyEnabled/proxyType above) and any SSH-based outbound connection
    // this profile makes (a direct SSH/Telnet-over-SSH-tunnel session, or
    // the first hop of an RDP/VNC-over-SSH-tunnel session — see
    // PacProxyResolver.outboundDialTarget) via SshClient/SshTunnelManager's
    // `outboundProxy` parameter.
    //
    // PRIORITY when this AND proxyEnabled/proxyType/proxyHost/... are BOTH
    // set (the profile form does not force them to be mutually exclusive —
    // a user may have typed a manual proxy first, then added a PAC URL
    // later without clearing the old fields): [pacUrl] wins outright
    // whenever it resolves successfully; the static fields underneath only
    // ever apply as a FALLBACK when [pacUrl] is blank, or when it is set but
    // fetching/evaluating that PAC file fails for this connect attempt
    // (unreachable PAC server, malformed script, etc.) — see
    // PacProxyResolver.resolve()'s doc comment for the exact fallback
    // order (static fields if proxyEnabled, else DIRECT; a broken PAC file
    // never fails the whole connection outright, matching how mainstream
    // browsers already handle PAC failures).
    //
    // Left blank by default so every existing profile keeps using the
    // static proxy* fields (or no proxy at all) exactly as before.
    @androidx.room.ColumnInfo(defaultValue = "")
    val pacUrl: String = "",

    // ── RDP-OVER-WEBSOCKET FEATURE (RDP only) ───────────────────────────────
    // Selects how the RDP byte stream reaches the server — see
    // [RdpTransportMode]'s doc comment. Stored as the enum's name, same
    // String-backed-enum pattern already used for gatewayAuthMode above
    // (MIGRATION_39_40 adds this column, default 'TCP' so every existing
    // profile keeps opening a plain TCP socket to host:port exactly as
    // before, until a user explicitly switches a profile to WS/WSS).
    @androidx.room.ColumnInfo(defaultValue = "TCP")
    val transportMode: String = RdpTransportMode.TCP.name,
    // Everything else the WS/WSS transport needs (URL, headers, TLS
    // options, reconnect policy, timeouts — see [RdpWebSocketConfig]'s doc
    // comment for the full field list) — encoded into one column by
    // [com.systemsgo.hex.rdp.transport.RdpWebSocketConfigCodec], the same
    // single-delimited-string approach already used for sshPortForwards/
    // sshTunnelHops above rather than a join table, since this config has
    // no identity of its own and always belongs to exactly one profile.
    // Left blank by default (decodes to RdpWebSocketConfig()'s all-default
    // instance) so this column is a no-op for every profile that has never
    // touched WebSocket settings — see transportMode above for why that
    // matters more here than usual: a profile only ever reaches
    // RdpWebSocketTransport if transportMode resolves to WS/WSS in the
    // first place, so a populated-but-unused webSocketConfig can never by
    // itself change behavior either.
    @androidx.room.ColumnInfo(defaultValue = "")
    val webSocketConfig: String = "",

    // ── Redfish / IPMI (BMC out-of-band management) ─────────────────────────
    // REDFISH-IPMI FEATURE: both protocols reuse host/port/username/password/
    // acceptSelfSignedCertificate above unchanged. This is the one field
    // specific to IPMI — the RAKP "Requested Maximum Privilege Level" (see
    // com.systemsgo.hex.ipmi.protocol.IpmiClient.IpmiPrivilege) — stored as
    // its enum name, same pattern as sshAuthType/gatewayAuthMode. Irrelevant
    // to REDFISH (which just uses whatever role the BMC account already
    // has), and irrelevant to every non-IPMI profile.
    @androidx.room.ColumnInfo(defaultValue = "ADMINISTRATOR")
    val ipmiPrivilegeLevel: String = "ADMINISTRATOR",
    // IPMI-KG-FEATURE: the BMC's separately-configured "Kg"/"BMC key" for a
    // two-key RAKP login — see IpmiSession's kgKey constructor param and
    // IpmiCrypto.deriveSik's doc comment for what this actually changes.
    // Blank (the default) means "one-key login, no BMC key configured" —
    // the factory default on essentially every BMC — same behavior every
    // IPMI profile had before this column existed. Irrelevant to REDFISH/
    // AMT and every non-IPMI protocol, same as ipmiPrivilegeLevel above.
    @androidx.room.ColumnInfo(defaultValue = "")
    val ipmiKgKey: String = "",

    // ── Intel AMT / vPro (BMC out-of-band management, ME-native) ────────────
    // AMT-VPRO FEATURE: whether to speak WS-Management over TLS (port 16993,
    // typical for enterprise ACM-provisioned deployments) instead of plain
    // HTTP (port 16992, the common lab/SMB "admin control mode" default).
    // Defaults to false so a freshly created AMT profile works against the
    // far more common unprovisioned/plain-HTTP case with zero extra
    // configuration, matching ipmiPrivilegeLevel's "sane default, zero
    // config needed" reasoning above. Irrelevant to every non-AMT profile.
    @androidx.room.ColumnInfo(defaultValue = "0")
    val amtUseTls: Boolean = false,

    // AMT-VPRO FEATURE — Phase 6 (CIRA, setup UI only so far; see
    // AMT_VPRO_ROADMAP.md's "Phase 6" section for the full picture, and
    // AmtRedirectionTransport.kt for the SOL/KVM/IDE-R transport seam this
    // will plug into once CiraRelayTransport itself exists). This app can't
    // itself be a Management Presence Server (no stable address for a
    // device to dial into from a mobile connection), so "CIRA support"
    // here means: connect through a small companion relay/MPS component
    // deployed elsewhere, addressed by [ciraRelayHost]/[ciraRelayPort],
    // rather than dialing [host]/[port] on the AMT device directly.
    // [ciraEnabled] toggles which of those two addressing modes a saved
    // AMT profile uses — mutually exclusive, not layered, since a CIRA
    // device has no directly-reachable host/port for this app to fall
    // back to. Irrelevant to every non-AMT profile.
    @androidx.room.ColumnInfo(defaultValue = "0")
    val ciraEnabled: Boolean = false,
    // Hostname/IP of the relay/MPS component (Phase 6, path (b)) this app
    // connects to instead of the AMT device itself. Only meaningful when
    // [ciraEnabled] is true.
    @androidx.room.ColumnInfo(defaultValue = "")
    val ciraRelayHost: String = "",
    // Port the relay's own WebSocket protocol listens on — a project-owned
    // port, unrelated to AMT's 16992-16995 (those are what the relay
    // speaks *to the device* over APF, on the other side of it).
    @androidx.room.ColumnInfo(defaultValue = "8081")
    val ciraRelayPort: Int = 8081,
    // Credential this app authenticates to the relay with — a separate
    // secret from both the relay's own device-facing APF credentials and
    // this profile's [username]/[password] (which, when ciraEnabled, are
    // instead the AMT WS-Man/redirection-port credentials the relay is
    // asked to use on this app's behalf once a channel is open, same as a
    // direct connection would need).
    @androidx.room.ColumnInfo(defaultValue = "")
    val ciraRelayUsername: String = "",
    @androidx.room.ColumnInfo(defaultValue = "")
    val ciraRelayPassword: String = "",
    // The relay's own identifier for which AMT device to open a channel
    // to — CIRA has no per-device IP for this app to dial, so the relay
    // needs some other handle (however it chooses to key its own device
    // table) to route on.
    @androidx.room.ColumnInfo(defaultValue = "")
    val ciraDeviceId: String = "",
    // AMT-VPRO FEATURE — Phase 6, Part 3: whether [CiraRelayTransport] connects
    // to the relay with `wss://` instead of `ws://` — see that class's top doc
    // comment ("wss:// trust mode") for the full handshake this toggles TLS
    // on and for why certificate verification in that mode is always
    // [TofuTrustManager] (silent Trust-On-First-Use pinned per
    // `ciraRelayHost:ciraRelayPort`) rather than the four-way trust-mode
    // matrix [RdpProfile] exposes for RDP-over-WebSocket — there's no
    // separate pinning/custom-CA UI for CIRA, just this one switch. Defaults
    // to false (plain `ws://`) for the same "existing profiles keep working
    // unchanged" reasoning as [amtUseTls]/[ciraEnabled] above; irrelevant
    // unless [ciraEnabled] is also true.
    @androidx.room.ColumnInfo(defaultValue = "0")
    val ciraRelayUseTls: Boolean = false,

    // ── RemoteApp / RAIL (RDP only) ─────────────────────────────────────────
    // "RemoteApp" (MS-RDPERP, the RAIL — Remote Applications Integrated
    // Locally — extension) runs a single published program full-screen
    // instead of the full remote desktop shell.
    val remoteAppEnabled: Boolean = false,
    // Either a full path on the server (e.g. "C:\Windows\system32\notepad.exe")
    // or, far more commonly on locked-down deployments, a "||alias" published
    // by the server admin via RemoteApp Manager / RD Web (e.g. "||notepad").
    val remoteAppProgram: String = "",
    val remoteAppWorkingDir: String = "",
    val remoteAppCmdLine: String = "",
    // REMOTEAPP-WINDOWS FEATURE: user's default choice of single-vs-multi
    // window presentation for this profile's RemoteApp session — see
    // RemoteAppDisplayMode's doc comment. Stored per-profile (like the other
    // remoteApp* fields above) but, unlike them, never reaches the native
    // layer — RdpRemoteAdapter reads it straight into
    // RemoteAppWindowManager, which is what RdpSessionActivity's window
    // switcher / fullscreen renderer consults. Persisted as its enum name
    // (Room's default String-backed storage for enums via a TypeConverter —
    // see SystemsGoDatabase — same pattern already used for protocolType).
    val remoteAppDisplayMode: RemoteAppDisplayMode = RemoteAppDisplayMode.SINGLE_WINDOW,

    // ── CODEC-NEGOTIATION FEATURE (RDP only) ────────────────────────────────
    // Which RDPGFX codec(s) this profile offers the server — see
    // [CodecPreference]'s doc comment for the full mapping and
    // com.systemsgo.hex.rdp.native.AFreeRdpBridge.CodecPreference for what
    // each value changes in the native capability exchange. Defaults to
    // AUTO, matching AFreeRdpBridge.connect()'s own default, so every
    // existing profile (created before this column existed) keeps getting
    // fully-automatic codec negotiation with no behavior change. Stored as
    // its enum name (Converters.fromCodecPreference/toCodecPreference), same
    // String-backed-enum pattern as remoteAppDisplayMode immediately above —
    // see SystemsGoDatabase.MIGRATION_20_21.
    @androidx.room.ColumnInfo(defaultValue = "AUTO")
    val codecPreference: CodecPreference = CodecPreference.AUTO,

    // ── VNC-specific ────────────────────────────────────────────────────────
    // VNC classically authenticates with a session password only (no
    // username). `password` above is reused as that VNC password.
    val vncViewOnly: Boolean = false,
    // ULTRAVNC-REPEATER FEATURE: routes the connection through an UltraVNC
    // Repeater in Mode II (ID-based). When enabled, `host`/`port` above are
    // the *repeater's* address, and `vncRepeaterId` is the ID string the
    // target server registered with that same repeater (server side runs
    // something like `winvnc -connect <repeaterHost>:5500 -id:<vncRepeaterId>`).
    // See RfbConnectable's class doc for the exact wire format of the ID
    // frame this sends before the RFB handshake. Defaults keep every
    // existing VNC profile behaving exactly as before (direct connection).
    @androidx.room.ColumnInfo(defaultValue = "0")
    val vncRepeaterEnabled: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "")
    val vncRepeaterId: String = "",
    // ULTRAVNC-REPEATER FEATURE (Mode I/II): see VncRepeaterMode's doc.
    // Defaults to MODE_II so every profile persisted before this column
    // existed (MIGRATION_31_32) keeps sending the ID frame exactly as
    // before. Only meaningful when vncRepeaterEnabled is true; MODE_I
    // needs no vncRepeaterId (the repeater's own config maps the port).
    @androidx.room.ColumnInfo(defaultValue = "MODE_II")
    val vncRepeaterMode: VncRepeaterMode = VncRepeaterMode.MODE_II,
    // LISTEN-MODE FEATURE (reverse VNC): standard RFB "listening viewer"
    // mode — this app opens a socket on `vncListenPort` and waits for the
    // remote VNC *server* to dial in, instead of dialing out to `host`/
    // `port` itself. See Connection.useListenMode's doc comment for the
    // full protocol rationale. Mutually exclusive with vncRepeaterEnabled
    // in the UI (RemoteSessionFactory only reads vncListenPort when this is
    // true). Defaults keep every existing VNC profile dialing out exactly
    // as before.
    @androidx.room.ColumnInfo(defaultValue = "0")
    val vncListenModeEnabled: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "5500")
    val vncListenPort: Int = 5500,
    // VENCRYPT FIX: implemented — RfbConnectable (the hand-written RFB
    // client backing VncClient) now negotiates VeNCrypt/TLS (RFB
    // security-type 19) automatically whenever the server offers it,
    // preferring certificate-based TLS, then anonymous TLS, then cleartext
    // VeNCrypt-Plain (see RfbConnectable's class doc and
    // VENCRYPT_SUBTYPE_PREFERENCE). No profile toggle is needed: this is
    // negotiated per-connection based purely on what the server offers,
    // the same way RDP's TLS/NLA negotiation already works. `username`
    // (shared across all three protocols) is reused for the VeNCrypt-Plain/
    // X509Plain/TLSPlain sub-types' optional username field — see
    // RfbConnectable.sendVeNCryptPlainCredentials. Certificate trust for the
    // X509* sub-types is TOFU (trust-on-first-use), stored the same way
    // SshClient.TofuHostKeyRepository pins SSH host keys — see
    // VncClient.VncTofuVerifier.

    // ── SSH-specific ────────────────────────────────────────────────────────
    val sshAuthType: SshAuthType = SshAuthType.PASSWORD,
    val sshPrivateKey: String = "",            // PEM contents, if PRIVATE_KEY
    val sshPrivateKeyPassphrase: String = "",
    // Forwards the local private-key identity to the remote host over the
    // "auth-agent@openssh.com" channel (the equivalent of OpenSSH's `ssh -A`),
    // so a further `ssh`/`git`/`scp` run *on* the remote host can authenticate
    // onward using this device's key without the key ever being copied there.
    // Only meaningful for PRIVATE_KEY auth (there is no local identity to
    // forward for PASSWORD auth) — enforced at save time in the profile form
    // and defensively ignored by SshClient otherwise. Defaults to false since
    // a malicious or compromised remote host could otherwise use the
    // forwarded agent for the lifetime of the session.
    val sshAgentForwardingEnabled: Boolean = false,

    // ── MOSH-specific ───────────────────────────────────────────────────────
    // MOSH FEATURE: merged straight into RdpProfile instead of the standalone
    // MoshProfile (see com.systemsgo.hex.data.model.MoshProfile's class doc,
    // kept only as the reference the SSH-bootstrap fields below were copied
    // from) — same pattern already used for TELNET/RLOGIN/SERIAL_CONSOLE.
    // A Mosh profile's SSH-bootstrap phase reuses host/port/username/password/
    // sshAuthType/sshPrivateKey/sshPrivateKeyPassphrase above exactly like the
    // SSH protocol itself does (RemoteSessionFactory maps them onto a
    // MoshProfile at connect time); only the fields with no SSH/RDP/VNC
    // equivalent are new here.
    /** Path to the `mosh-server` binary on the remote host. Almost always fine as-is. */
    val moshRemoteServerCommand: String = "mosh-server",
    /** UDP port range passed to `mosh-server -p`, e.g. "60000:61000". Empty = server's own default range. */
    val moshUdpPortRange: String = "",
    /** `LANG` value forwarded via `mosh-server -l LANG=...`. Empty = don't override the server's locale. */
    val moshRemoteLocale: String = "",
    /** `mosh-server -c <n>` — 8/16/88/256, matching the terminal's color support. */
    val moshColorMode: Int = 256,
    /** Predictive local echo mode, matching upstream `mosh --predict=`. Stored as [MoshPredictionMode]'s name. */
    val moshPredictionMode: String = MoshPredictionMode.ADAPTIVE.name,

    // ── PROXMOX-API FEATURE (Part 1/N) ──────────────────────────────────────
    // host/port above are the Proxmox VE API host (default port 8006).
    /** [ProxmoxAuthMode]'s name — TOKEN (proxmoxTokenId/proxmoxTokenSecret) or PASSWORD (username/password, e.g. root@pam). */
    val proxmoxAuthMode: String = "TOKEN",
    /** Full token id in `user@realm!tokenid` form, e.g. `automation@pve!mobile-app`. Only read when [proxmoxAuthMode] is TOKEN. */
    val proxmoxTokenId: String = "",
    /** The token's UUID secret, shown only once at creation time in Proxmox's UI. Only read when [proxmoxAuthMode] is TOKEN. */
    val proxmoxTokenSecret: String = "",
    /** Proxmox's management API almost always sits behind a self-signed cert unless the admin fronted it with a real one — off by default like every other protocol's acceptSelfSignedCertificate, but called out here since it's the *common* case for Proxmox specifically. */
    val proxmoxAcceptSelfSignedCertificate: Boolean = true,

    // ── MODBUS-TCP FEATURE (Part 2/2) ────────────────────────────────────
    // host/port above are the Modbus/TCP endpoint (default port 502).
    // Reconstructed into a ModbusConnectionConfig by
    // com.systemsgo.hex.modbus.ModbusProfileMapper.toModbusConnectionConfig()
    // — see that file for the Part-1/Part-2 seam this replaces.
    /** Modbus unit/slave identifier (spec §4.1) — see ProtocolType.MODBUS_TCP's doc comment. */
    val modbusUnitId: Int = 1,
    val modbusConnectTimeoutMs: Int = 5000,
    /** Per-request response timeout — Modbus/TCP has no session/keep-alive concept, so every read/write is its own request-response round trip that can simply time out and be retried. */
    val modbusResponseTimeoutMs: Int = 3000,
    val modbusRetries: Int = 1,
    /** Cyclic-poll interval (ms) for the dashboard's saved points, below. */
    val modbusPollIntervalMs: Int = 1000,
    // The user-defined register/point list shown on the dashboard — a flat
    // delimited string rather than a join table (same tradeoff every other
    // small/unbounded string set on this entity already makes, e.g.
    // snmpFavoriteOids). One point per `;`-separated entry; within a point,
    // `:`-separated fields are registerType:address:label:dataFormat (see
    // com.systemsgo.hex.modbus.modbusPointList/toModbusPointsColumn for the
    // exact codec and com.systemsgo.hex.modbus.protocol.ModbusRegisterType/
    // ModbusDataFormat for the enums encoded in each field). Example: a
    // holding-register float32 tank level at address 100, plus a coil
    // reading a pump's run status: "HOLDING_REGISTER:100:Tank
    // Level:FLOAT32;COIL:0:Pump Run:UINT16".
    val modbusPoints: String = "",

    // ── VIRTUALBOX-VRDE FEATURE (Part 1/N) ────────────────────────────────
    // host/port/username/password/domain/acceptSelfSignedCertificate above
    // are reused as-is (this connects through RdpRemoteAdapter, same as a
    // plain RDP profile — see ProtocolType.VIRTUALBOX_VRDE's doc comment).
    /** [VrdeAuthType]'s name — informational only today, see that enum's doc comment. */
    val vrdeAuthType: String = VrdeAuthType.NULL_AUTH.name,
    /** Whether the VM's VRDE is configured to allow more than one simultaneous RDP client (VirtualBox's MultiConnection setting) — informational only, doesn't change client behavior. */
    val vrdeMultiConnectionAllowed: Boolean = false,

    // ── VMWARE-VSPHERE FEATURE (Part 1/N) ─────────────────────────────────
    // host/port above are the vCenter/ESXi API host (default port 443);
    // username/password are the vSphere account (e.g. administrator@vsphere.local).
    /** [VSphereApiMode]'s name — REST or SOAP, see that enum's doc comment. */
    val vsphereApiMode: String = VSphereApiMode.REST.name,
    /** vCenter/ESXi almost always sits behind a self-signed cert out of the box — same reasoning as [proxmoxAcceptSelfSignedCertificate]. */
    val vsphereAcceptSelfSignedCertificate: Boolean = true,
    /** Optional datacenter name to scope inventory listing when the account can see more than one (vCenter only — ESXi hosts have a single implicit datacenter). Blank = list across all datacenters visible to the account. */
    val vsphereDatacenter: String = "",

    // ── SSH Dynamic SOCKS5 Proxy (SSH only) ─────────────────────────────────
    // Equivalent of OpenSSH's `ssh -D <port>`: starts a local SOCKS4/5 proxy
    // alongside the interactive terminal, on the same authenticated session.
    // Unlike sshTunnelEnabled below (a fixed local→remote forward used only to
    // carry this app's own RDP/VNC traffic), any SOCKS-capable app on the
    // device can point at 127.0.0.1:socksProxyPort and route its own traffic
    // through this SSH server — a general-purpose tunnel through the jump
    // host, not limited to one predetermined destination.
    val socksProxyEnabled: Boolean = false,
    val socksProxyPort: Int = 1080,          // 1080 is the conventional SOCKS port

    // ── SSH X11 Forwarding (SSH only) ────────────────────────────────────────
    // Equivalent of OpenSSH's `ssh -X`/`-Y`: on shell-channel open, requests
    // the server-side "x11-req" so that any GUI program launched in the
    // remote shell (xterm, xclock, a full app, ...) has its X11 traffic
    // tunneled back through this same authenticated SSH session instead of
    // needing its own direct network path. The far end of that tunnel — the
    // *actual* X server the windows get drawn on — is not something this app
    // implements itself (that would mean shipping a full X11 server); instead
    // it relays to whatever local X server the device already has running,
    // e.g. Termux:X11 or an XSDL-style app listening on TCP, addressed by
    // [x11DisplayHost]:[x11DisplayNumber] (TCP port = 6000 + display number,
    // matching the X11 convention). See SshClient.connect() for the JSch
    // wiring (Session#setX11Host/setX11Port/setX11Cookie + ChannelShell#
    // setXForwarding) and X11AuthCookie.kt for the MIT-MAGIC-COOKIE-1 handling.
    val x11ForwardingEnabled: Boolean = false,
    val x11DisplayHost: String = "127.0.0.1",  // local X server host (usually loopback)
    val x11DisplayNumber: Int = 0,             // X display number; local TCP port = 6000 + this
    // Hex-encoded MIT-MAGIC-COOKIE-1 (32 hex chars = 16 bytes) matching the
    // *local* X server's own Xauthority entry for that display, so JSch's
    // auth-substitution (fake cookie over the wire, real cookie to the local
    // server) actually authenticates. Left blank to have SystemsGo generate a
    // fresh random cookie each connection — works with X servers that accept
    // any/no local auth (many mobile X server apps in their default mode),
    // but will fail auth against a local server enforcing its own fixed
    // MIT-MAGIC-COOKIE-1; in that case paste the cookie from the X server
    // app (e.g. Termux:X11's `xauth list`) here instead.
    val x11AuthCookie: String = "",

    // ── SSH Local/Remote Port Forwarding (SSH only) ───────────────────────────
    // Equivalent of OpenSSH's `ssh -L`/`-R`: user-defined static forwards, set
    // up on the same authenticated session right alongside the interactive
    // shell. Unlike socksProxyEnabled above (`-D`, any destination the SOCKS
    // client asks for) each rule here targets one fixed destHost:destPort.
    // See SshPortForwardRule/SshPortForwardType and SshClient.connect() for
    // the JSch wiring (Session#setPortForwardingL / #setPortForwardingR).
    val sshPortForwards: List<SshPortForwardRule> = emptyList(),

    // ── RD Web Feed (RDP only) ────────────────────────────────────────────────
    // RD-WEB-FEED FEATURE: set when this profile was created from a
    // [com.systemsgo.hex.data.model.WebFeedSubscription] resource (see
    // com.systemsgo.hex.webfeed.RdWebFeedClient / WebFeedRepository) rather
    // than being entered/imported manually. webFeedSubscriptionId references
    // WebFeedSubscription.id; webFeedAlias is that resource's stable Alias
    // from the feed XML (Resource@Alias — see MS-TSWP), used on the next
    // refresh to tell "this is the same published app, just update it" apart
    // from "this is a new resource, add it". Both blank ("") for every
    // profile added any other way. Deliberately plain Strings, not a Room
    // @ForeignKey — same reasoning as folderId above: WebFeedRepository nulls
    // these out before deleting a subscription rather than relying on FK
    // cascade, so no enforcement is needed here.
    @androidx.room.ColumnInfo(defaultValue = "")
    val webFeedSubscriptionId: String = "",
    @androidx.room.ColumnInfo(defaultValue = "")
    val webFeedAlias: String = "",

    // ── Telnet-specific ──────────────────────────────────────────────────────

    // TELNET-TLS FEATURE: wraps the raw Telnet socket in a TLS handshake
    // before the Telnet option negotiation starts (the "telnets" convention,
    // historically port 992, though the port itself stays user-editable
    // here same as every other profile field). Plain Telnet has no
    // in-protocol notion of server identity at all — anyone on-path can
    // impersonate the host — so this is the only way a Telnet profile in
    // this app gets the same "is this really the server I think it is"
    // guarantee RDP/VNC/SSH already have. See
    // [com.systemsgo.hex.telnet.protocol.TelnetClient] for the handshake and
    // the same first-use certificate-pinning flow ([CertificateChallenge])
    // already used by RdpRemoteAdapter/VncClient.
    val telnetUseTls: Boolean = false,

    // ── RTSP-specific ────────────────────────────────────────────────────────

    // RTSP FEATURE: the stream path portion of `rtsp://host:port/path` —
    // see com.systemsgo.hex.rtsp.protocol.RtspCredentials.path's doc comment
    // for the exact join rule (no leading slash here). [host]/[port] above
    // are reused unchanged, same as every other protocol. Typical values:
    // "cam/realmonitor?channel=1&subtype=0" (Dahua/Hikvision-style DVRs),
    // "stream1", or blank for a camera that serves its one stream at the
    // bare root URL.
    @androidx.room.ColumnInfo(defaultValue = "")
    val rtspStreamPath: String = "",
    // RTSP FEATURE: stored by [com.systemsgo.hex.rtsp.protocol.RtspTransportMode]
    // name — TCP_INTERLEAVED (default, NAT/firewall-safe) or UDP (lower
    // latency, opt-in). See that enum's doc comment for the tradeoff.
    @androidx.room.ColumnInfo(defaultValue = "TCP_INTERLEAVED")
    val rtspTransportMode: String = "TCP_INTERLEAVED",
    // RTSP-TLS FEATURE: "rtsps://" instead of "rtsp://" — see
    // RtspCredentials.useTls's doc comment for exactly what this does and
    // doesn't cover (media over UDP is never encrypted by this flag).
    @androidx.room.ColumnInfo(defaultValue = "false")
    val rtspUseTls: Boolean = false,

    // ── Rlogin-specific ──────────────────────────────────────────────────────

    // RLOGIN FEATURE: the remote username to log in as, sent in the RFC 1282
    // handshake's second field. Left blank, [username] above (already the
    // "who am I on the remote host" field for RDP/VNC/SSH/etc.) is reused for
    // this too — a new profile only needs to fill this in when the local
    // account name it's already typing differs from the account name it
    // wants on the rlogin server. See
    // [com.systemsgo.hex.rlogin.protocol.RloginClient] for how the two
    // usernames (this app's own "client user name" — effectively this
    // device's identity — vs. this field's "server user name") are actually
    // used in the handshake.
    @androidx.room.ColumnInfo(defaultValue = "")
    val rloginRemoteUsername: String = "",
    // RLOGIN FEATURE: the terminal type/speed string sent as the handshake's
    // third field (RFC 1282), e.g. "xterm/38400" — mirrors the $TERM a real
    // rlogin(1) client would send, since some rlogin servers change escape
    // sequences or disable features based on it. "xterm/38400" is a safe,
    // widely-recognized default; changed only if a particular server needs
    // something else (e.g. "vt100/9600").
    @androidx.room.ColumnInfo(defaultValue = "xterm/38400")
    val rloginTerminalType: String = "xterm/38400",

    // ── Web/HTTPS portal-specific ───────────────────────────────────────────
    // WEB-PORTAL FEATURE: the full URL of the portal this profile opens — any
    // HTTPS management console (Guacamole, ESXi/vCenter, iDRAC/iLO, Proxmox,
    // pfSense, ...), or the RD Web Access "Pages" login itself as a fallback
    // when [WebFeedSubscription]'s Basic-auth feed endpoint isn't available
    // (see [com.systemsgo.hex.webfeed.RdWebFeedClient]'s AuthRequired case).
    // [host]/[port] above are still populated (from the URL's authority)
    // purely so this profile sorts/searches/exports the same way every other
    // profile does, but WebPortalActivity always navigates from [webUrl]
    // itself, since a portal URL commonly carries a path (e.g.
    // "/RDWeb/webclient") that host/port alone would lose.
    @androidx.room.ColumnInfo(defaultValue = "")
    val webUrl: String = "",
    // Mirrors RdpProfile.acceptSelfSignedCertificate's reasoning for the one
    // other place this app makes a TLS connection outside RemoteSessionClient:
    // WebPortalActivity's WebView. Off by default — trusting an untrusted
    // certificate must be an explicit per-profile opt-in, never silent.
    @androidx.room.ColumnInfo(defaultValue = "0")
    val webTrustSelfSignedCertificate: Boolean = false,
    // Whether [username]/[password] above should be offered automatically to
    // an HTTP Basic/Digest auth challenge (WebViewClient.onReceivedHttpAuthRequest)
    // from the portal itself. Covers only that browser-level challenge — see
    // [webAutoFillLoginForm] just below for the separate, in-page HTML login
    // form this field used to disclaim ever touching.
    @androidx.room.ColumnInfo(defaultValue = "1")
    val webAutoFillHttpAuth: Boolean = true,
    // WEB-PORTAL-SMART-AUTOFILL FEATURE: whether WebPortalScreen's
    // onPageFinished callback should inject a small, read-only-until-typed
    // JS snippet (see WebPortalLoginAutofill.kt) that recognizes a handful
    // of common self-hosted portals' *in-page* HTML login form — Guacamole,
    // ESXi/vCenter, iDRAC/iLO, Proxmox VE — and fills [username]/[password]
    // into it, the same way a password manager's autofill would. Separate
    // from [webAutoFillHttpAuth] above because it's a materially different
    // trust surface: that one answers a browser-chrome auth challenge with
    // no page content involved, this one touches DOM form fields on
    // whatever page actually loaded. Never auto-submits the form — the user
    // still reviews and taps sign-in themselves — and only recognizes a
    // fixed, hand-verified set of selectors, so it silently no-ops on any
    // portal not in that list rather than guessing at arbitrary markup.
    @androidx.room.ColumnInfo(defaultValue = "1")
    val webAutoFillLoginForm: Boolean = true,

    // ── SSH Tunnel (for RDP / VNC / Telnet) ─────────────────────────────────
    // Forwards the RDP/VNC connection through an SSH tunnel, eliminating the
    // need for an RD Gateway in many environments.
    val sshTunnelEnabled: Boolean = false,

    // SSH-PROXYJUMP-CHAIN FEATURE: the fields below (sshTunnelHost..
    // sshTunnelPrivateKeyPassphrase) used to be the ONLY representation of
    // the tunnel — exactly one jump host. They're superseded by
    // [sshTunnelHops] (an ordered list of [SshJumpHop], supporting a full
    // OpenSSH-style `-J host1,host2,host3` chain of any length), but kept
    // here — still real Room columns — purely as a migration path: a
    // profile saved by an older build of this app has [sshTunnelHops] empty
    // and its one jump host in these fields, and [effectiveSshTunnelHops]
    // transparently upgrades that into a single-entry list on read. Never
    // write new code against these fields directly — read [sshTunnelHops]
    // via [effectiveSshTunnelHops] instead. New profiles created by the
    // (future) multi-hop editor UI leave these blank/false and populate
    // [sshTunnelHops] only.
    @Deprecated("Replaced by sshTunnelHops; kept for migration only — use effectiveSshTunnelHops")
    @Suppress("DEPRECATION")
    val sshTunnelHost: String = "",           // SSH jump-host IP or hostname
    @Deprecated("Replaced by sshTunnelHops; kept for migration only — use effectiveSshTunnelHops")
    @Suppress("DEPRECATION")
    val sshTunnelPort: Int = 22,              // SSH server port (usually 22)
    @Deprecated("Replaced by sshTunnelHops; kept for migration only — use effectiveSshTunnelHops")
    @Suppress("DEPRECATION")
    val sshTunnelUsername: String = "",
    @Deprecated("Replaced by sshTunnelHops; kept for migration only — use effectiveSshTunnelHops")
    @Suppress("DEPRECATION")
    val sshTunnelAuthType: SshAuthType = SshAuthType.PASSWORD,
    @Deprecated("Replaced by sshTunnelHops; kept for migration only — use effectiveSshTunnelHops")
    @Suppress("DEPRECATION")
    val sshTunnelPassword: String = "",
    @Deprecated("Replaced by sshTunnelHops; kept for migration only — use effectiveSshTunnelHops")
    @Suppress("DEPRECATION")
    val sshTunnelPrivateKey: String = "",
    @Deprecated("Replaced by sshTunnelHops; kept for migration only — use effectiveSshTunnelHops")
    @Suppress("DEPRECATION")
    val sshTunnelPrivateKeyPassphrase: String = "",

    // SSH-PROXYJUMP-CHAIN FEATURE: ordered chain of jump hosts — see
    // [SshJumpHop]'s doc comment for ordering and [SshJumpHopCodec] for how
    // this is persisted (a single delimited-string Room column, same
    // approach as [sshPortForwards]/[SshPortForwardCodec]). May be empty
    // even when [sshTunnelEnabled] is true, for any profile saved before
    // this feature existed — see [effectiveSshTunnelHops].
    val sshTunnelHops: List<SshJumpHop> = emptyList(),

    // ── Wake-on-LAN ─────────────────────────────────────────────────────────────
    // Sends a UDP "Magic Packet" to wake the target machine before connecting.
    val wolEnabled: Boolean = false,
    val wolMacAddress: String = "",            // e.g. "AA:BB:CC:DD:EE:FF"
    val wolBroadcastAddress: String = "255.255.255.255", // subnet broadcast
    // WAKE-CONNECT FEATURE: the Magic Packet's own UDP destination port
    // (conventionally 7 or 9 — see WakeOnLanManager.DEFAULT_WOL_PORT), plus the
    // parameters that drive "Wake & Connect": how long to wait in total for the
    // target's RDP/VNC/SSH port to answer, how often to retry a TCP probe, and
    // the hard cap on retry attempts. Kept per-profile since wake time varies a
    // lot by machine (fast SSD boot vs. a slow BIOS + full disk check).
    val wolPort: Int = com.systemsgo.hex.util.WakeOnLanManager.DEFAULT_WOL_PORT,
    val wolConnectTimeoutSeconds: Int = 60,
    val wolRetryIntervalSeconds: Int = 3,
    val wolMaxRetries: Int = 20,

    // BUG-M FIX: Store only the filename (relative to filesDir/screenshots/), NOT an
    // absolute path. Absolute paths like /storage/emulated/0/... break after backup
    // restore to a different device or factory reset. Callers must resolve to
    // context.filesDir/screenshots/<lastScreenshotFilename> at display time.
    val lastScreenshotFilename: String? = null,  // filename only, e.g. "preview_<id>.png"
    // BUG-DEPRECATED-COL FIX: The @Deprecated field must carry @ColumnInfo so Room
    // knows the column is nullable with no default — this prevents a schema mismatch
    // crash on devices that somehow skipped a migration. Also suppresses the Kotlin
    // deprecation warning at the Room-generated accessor site.
    @Deprecated("Replaced by lastScreenshotFilename; kept for Room migration only")
    @androidx.room.ColumnInfo(defaultValue = "")
    @Suppress("DEPRECATION")
    val lastScreenshotPath: String? = null,
    val lastConnected: Long = 0L,
    val isConnected: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,

    // ── Folders & Tags ───────────────────────────────────────────────────────
    // The folder (category) this connection is filed under, or null for
    // "unfiled". References ConnectionFolder.id. Deliberately not a Room
    // @ForeignKey: ConnectionFolderRepository.deleteFolder() nulls this out on
    // every affected profile *before* deleting the folder row, so orphaned
    // references never occur without needing FK enforcement/cascades.
    val folderId: String? = null,
    // Free-form labels for this connection; a connection can have any number
    // of tags. Stored as a single delimited string column via a Room
    // TypeConverter (see Converters.kt) rather than a separate join table —
    // tags have no identity of their own beyond their text, so this stays
    // lightweight (no extra entity, DAO, or migration table to maintain).
    val tags: List<String> = emptyList(),

    // ── Favorites ────────────────────────────────────────────────────────────
    // Marks this connection as a favorite. Favorites are always shown before
    // non-favorites in the connection list (see MainViewModel/HomeScreen),
    // while sorting *within* each group is left untouched (folders, tags,
    // search, and drag-to-reorder all keep working exactly as before — this
    // flag only adds a stable "favorites first" grouping on top). Defaults to
    // false so every existing profile is simply unfavorited after upgrade.
    val isFavorite: Boolean = false,

    // ── PIN-CONNECTION FEATURE ───────────────────────────────────────────────
    // Whether this connection is pinned. Pinned connections always render
    // before every unpinned one (see MainViewModel.visibleProfiles), taking
    // priority even over the isFavorite-first grouping above — a card can be
    // both pinned and favorited, in which case pin wins for placement.
    // Purely additive column (MIGRATION_44_45); defaults to false so every
    // existing profile is simply unpinned after upgrade, same pattern as
    // isFavorite (MIGRATION_13_14).
    @androidx.room.ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false,
    // Stable manual ordering *among pinned connections only* — independent
    // of [sortOrder] (which continues to drive unpinned drag-to-reorder /
    // the default list order exactly as before). Assigned as "current max
    // pinnedOrder among pinned rows + 1" the moment a connection is pinned
    // (see MainViewModel.togglePin/bulkPinSelected), and rewritten
    // sequentially (0, 1, 2, ...) whenever the pinned section itself is
    // drag-reordered (see RdpProfileRepository.reorderPinnedProfiles).
    // Meaningless (and never read) while isPinned is false — unpinning does
    // not bother resetting it, since it's simply ignored until the
    // connection is pinned again. Long (not Int) to leave headroom for a
    // very large pinned set without ever needing a renumbering pass.
    @androidx.room.ColumnInfo(defaultValue = "0")
    val pinnedOrder: Long = 0L,

    // ── MULTI-MONITOR FEATURE ────────────────────────────────────────────────
    // Requirement: "Remember the selected monitor for each saved connection."
    // Stores the user's last choice in the session toolbar's monitor
    // selector: com.systemsgo.hex.display.MonitorSelection.ALL_MONITORS_ID (-1,
    // the default) for "All Monitors", or a non-negative monitor id (matching
    // com.systemsgo.hex.display.RemoteMonitor.id / the index the monitor was
    // declared at — see AFreeRdpBridge.NativeMonitor) for a single specific
    // monitor. Read/written by RdpSessionViewModel around
    // RemoteSessionClient.selectMonitor(); ignored entirely by protocols/
    // sessions that never populate RemoteSessionClient.monitors beyond one
    // entry (VNC, SSH, and any RDP session the server didn't grant a
    // multi-monitor layout to). New Room column — see
    // SystemsGoDatabase.MIGRATION_15_16.
    @androidx.room.ColumnInfo(defaultValue = "-1")
    val preferredMonitorId: Int = com.systemsgo.hex.display.MonitorSelection.ALL_MONITORS_ID,

    // ── RESTCONF FEATURE (Part 1/4) ───────────────────────────────────────────
    // Reuses host/port/username/password/acceptSelfSignedCertificate/proxy*/
    // pacUrl exactly like every other protocol (see ProtocolType.RESTCONF's
    // doc comment) — everything below is RESTCONF-specific. Stored as the
    // enum's name, same string-backed-enum convention as sshAuthType/
    // gatewayAuthMode/ipmiPrivilegeLevel. See
    // com.systemsgo.hex.restconf.protocol.RestconfAuth for what each auth
    // type actually does with these fields.
    @androidx.room.ColumnInfo(defaultValue = "BASIC")
    val restconfAuthType: String = RestconfAuthType.BASIC.name,
    @androidx.room.ColumnInfo(defaultValue = "JSON")
    val restconfDataFormat: String = RestconfDataFormat.JSON.name,
    // Plain-HTTP lab/dev servers exist even though RESTCONF is HTTPS-only in
    // practice; port alone can't disambiguate (a user may run either on a
    // nonstandard port), same reasoning as AMT's amtUseTls.
    @androidx.room.ColumnInfo(defaultValue = "1")
    val restconfUseHttps: Boolean = true,
    val restconfBearerToken: String = "",
    val restconfJwtToken: String = "",
    @androidx.room.ColumnInfo(defaultValue = "X-API-Key")
    val restconfApiKeyHeaderName: String = "X-API-Key",
    val restconfApiKeyValue: String = "",
    // Serialized as "Header-Name: value" lines (one per header) via
    // Converters.kt, same delimited-string-column approach as `tags` above —
    // custom headers have no identity beyond name+value, so no join table.
    val restconfCustomHeaders: String = "",
    // Android Keystore alias for the client cert+key used by
    // CLIENT_CERTIFICATE/MUTUAL_TLS — the PKCS12 blob itself lives in the
    // platform KeyStore (see com.systemsgo.hex.security.CryptoHelper), never
    // in this Room row, same principle as SSH private keys.
    val restconfClientCertAlias: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0")
    val restconfMutualTlsEnabled: Boolean = false,
    val restconfOAuth2TokenUrl: String = "",
    val restconfOAuth2ClientId: String = "",
    val restconfOAuth2ClientSecret: String = "",
    val restconfOAuth2Scope: String = "",
    // Comma-separated "sha256/BASE64..." SPKI pins (OkHttp CertificatePinner
    // format) — empty means no pinning, just the acceptSelfSignedCertificate/
    // platform-trust-store check above.
    val restconfCertificatePins: String = "",
    @androidx.room.ColumnInfo(defaultValue = "1")
    val restconfHttp2Enabled: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "1")
    val restconfCompressionEnabled: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "60")
    val restconfKeepAliveSeconds: Int = 60,

    // ── SNMP FEATURE ─────────────────────────────────────────────────────────
    // Every field below is read for a protocolType==SNMP profile, and *also*
    // for any other profile with [snmpMonitoringEnabled] set — i.e. SNMP can
    // be either a profile's whole reason to exist, or an add-on dashboard
    // layered onto an RDP/SSH/... connection to the same device. Reuses
    // [host] for the agent address either way; [snmpPort] is separate from
    // the profile's main [port] specifically so the add-on mode doesn't
    // collide with (e.g.) an SSH profile's port 22.
    @androidx.room.ColumnInfo(defaultValue = "V2C")
    val snmpVersion: String = "V2C", // com.systemsgo.hex.snmp.protocol.SnmpVersion name — V1/V2C/V3
    @androidx.room.ColumnInfo(defaultValue = "public")
    val snmpCommunity: String = "public", // v1/v2c only
    @androidx.room.ColumnInfo(defaultValue = "")
    val snmpV3Username: String = "",
    @androidx.room.ColumnInfo(defaultValue = "AUTH_PRIV")
    val snmpV3SecurityLevel: String = "AUTH_PRIV", // SnmpSecurityLevel name
    @androidx.room.ColumnInfo(defaultValue = "SHA1")
    val snmpV3AuthProtocol: String = "SHA1", // SnmpAuthProtocol name
    @androidx.room.ColumnInfo(defaultValue = "")
    val snmpV3AuthPassphrase: String = "",
    @androidx.room.ColumnInfo(defaultValue = "AES128")
    val snmpV3PrivProtocol: String = "AES128", // SnmpPrivProtocol name
    @androidx.room.ColumnInfo(defaultValue = "")
    val snmpV3PrivPassphrase: String = "",
    @androidx.room.ColumnInfo(defaultValue = "")
    val snmpV3ContextName: String = "",
    @androidx.room.ColumnInfo(defaultValue = "161")
    val snmpPort: Int = 161,
    // The "add SNMP as a monitoring layer on top of any other protocol" mode
    // described above — irrelevant (and false) for a protocolType==SNMP
    // profile, which is already always-SNMP regardless of this flag.
    @androidx.room.ColumnInfo(defaultValue = "0")
    val snmpMonitoringEnabled: Boolean = false,
    // Comma-separated dotted OIDs the user pinned in the MIB browser/Get
    // tool for this device (e.g. "1.3.6.1.2.1.1.5.0,1.3.6.1.2.1.25.2.2.0") —
    // deliberately a flat delimited string rather than a join table, the
    // same tradeoff [ConnectionFolder] membership and every other
    // small/unbounded string set in this entity already makes.
    @androidx.room.ColumnInfo(defaultValue = "")
    val snmpFavoriteOids: String = "",

    // ── NETCONF-specific (NETCONF FEATURE) ──────────────────────────────────
    // Connection identity/auth/proxy/jump-host reuse host/port/username/
    // password/sshAuthType/sshPrivateKey/sshPrivateKeyPassphrase/
    // sshTunnelHops/proxyType/pacUrl exactly as SSH profiles already do (see
    // com.systemsgo.hex.netconf.protocol.NetconfCredentials/NetconfJumpHop,
    // built from those same columns by NetconfProfileMapper) — only what's
    // genuinely NETCONF-specific gets its own column below.
    /** Which datastore the Session UI/RPC Builder default to on open — see com.systemsgo.hex.netconf.protocol.NetconfDatastore. */
    @androidx.room.ColumnInfo(defaultValue = "running")
    val netconfDefaultDatastore: String = "running",
    /** Extra capability URIs to advertise beyond base:1.0/1.1, one per line. */
    @androidx.room.ColumnInfo(defaultValue = "")
    val netconfExtraCapabilities: String = "",
    @androidx.room.ColumnInfo(defaultValue = "15000")
    val netconfKeepAliveMs: Int = 15_000,
    @androidx.room.ColumnInfo(defaultValue = "15000")
    val netconfConnectTimeoutMs: Int = 15_000,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val netconfCompressionEnabled: Boolean = false,
    /** OpenSSH certificate blob (id_*-cert.pub contents) for certificate auth — see NetconfAuthMode's doc comment. Stored encrypted like sshPrivateKey. */
    @androidx.room.ColumnInfo(defaultValue = "")
    val netconfOpenSshCertificate: String = "",
    @androidx.room.ColumnInfo(defaultValue = "1")
    val netconfAutoReconnect: Boolean = true,
    // CALL-HOME FEATURE (RFC 8071, Part 12): reverses which side dials —
    // the device connects out to *this app* instead of the app dialing the
    // device, for devices behind NAT/firewalls that can't accept inbound
    // SSH themselves. This app is still the SSH client and NETCONF client
    // for every purpose once the TCP connection exists (see
    // com.systemsgo.hex.netconf.protocol.NetconfCallHomeProxy's doc
    // comment) — host/username/password/sshAuthType/sshPrivateKey/etc.
    // above are reused unchanged as the *authentication* credentials; only
    // [host]/[port] stop being a dial target and become purely informational
    // once Call Home is enabled (a device's actual source address is
    // whatever its own outbound route is, which RFC 8071 does not require
    // to match any address the app has on file).
    @androidx.room.ColumnInfo(defaultValue = "0")
    val netconfCallHomeEnabled: Boolean = false,
    /** Local TCP port `NetconfCallHomeService` listens on for this profile — see com.systemsgo.hex.netconf.protocol.NetconfCallHomeListener.DEFAULT_PORT (RFC 8071/IANA default: 4334). Multiple profiles may share one port; disambiguated by [netconfCallHomeAllowedSourceHost] when more than one profile listens on the same port. */
    @androidx.room.ColumnInfo(defaultValue = "4334")
    val netconfCallHomeListenPort: Int = 4334,
    /** Optional source-address allowlist entry (exact IP, or blank). Required only when two or more enabled Call Home profiles share the same [netconfCallHomeListenPort] — otherwise the port alone is an unambiguous match. Blank means "accept from any address on this port". */
    @androidx.room.ColumnInfo(defaultValue = "")
    val netconfCallHomeAllowedSourceHost: String = "",
    // CALL-HOME-TLS FEATURE (RFC 8071 §3.1's TLS variant, `netconf-ch-tls`,
    // carrying NETCONF/TLS per RFC 7589 instead of NETCONF/SSH per RFC
    // 6242): a second transport for the same reversed-dial idea above — the
    // device still dials out to this app, but the session that rides on top
    // of the accepted socket is a TLS record layer + NETCONF framing
    // directly on it, not an SSH "netconf" subsystem channel. Whether a
    // given [netconfCallHomeListenPort] is interpreted as an SSH listener
    // or a TLS listener is entirely driven by this column — the port column
    // itself is reused rather than duplicated, since exactly one transport
    // applies per listener. "SSH" preserves the exact pre-existing
    // behavior for every profile that upgrades through this migration.
    @androidx.room.ColumnInfo(defaultValue = "SSH")
    val netconfCallHomeTransport: String = "SSH",
    /**
     * Optional PEM bundle (an X.509 client certificate immediately followed
     * by its matching PKCS#8/EC private key, both as `-----BEGIN ...-----`
     * blocks in one string) used as this app's TLS client identity per RFC
     * 7589 §7 when [netconfCallHomeTransport] is "TLS". Left blank, the TLS
     * handshake proceeds without presenting a client certificate — some
     * devices/labs accept this, but RFC 7589-conformant mutual
     * authentication requires it. The device's own server certificate is
     * *not* configured here: it's pinned on first connect via
     * NetconfTlsTofuTrustManager, the same trust-on-first-use model
     * [com.systemsgo.hex.ssh.protocol.TofuHostKeyRepository] already uses
     * for SSH host keys. Stored encrypted like sshPrivateKey/
     * netconfOpenSshCertificate.
     */
    @androidx.room.ColumnInfo(defaultValue = "")
    val netconfCallHomeTlsClientCertificatePem: String = "",

    // ── GUACAMOLE-PROTOCOL FEATURE ───────────────────────────────────────
    // Unlike REDFISH/IPMI/AMT (which reuse [host]/[port] directly), Guacamole
    // needs a full base URL — scheme, host, port, AND an arbitrary context
    // path — so a bare host/port pair isn't expressive enough; [username]/
    // [password]/[acceptSelfSignedCertificate] ARE reused as-is, same as
    // REDFISH/IPMI/AMT, since those three map onto Guacamole's REST login
    // exactly. See com.systemsgo.hex.data.model.GuacamoleProfile's class doc
    // for the full field-by-field reasoning — this is that same model's
    // shape, flattened onto RdpProfile's Room entity for persistence.
    @androidx.room.ColumnInfo(defaultValue = "''")
    val guacServerUrl: String = "",
    // null/blank = use the data source returned by the login response
    // (GuacamoleAuthResult.dataSource) rather than a specific one the user
    // picked — most single-backend Guacamole deployments never need this set.
    @androidx.room.ColumnInfo(defaultValue = "''")
    val guacDataSource: String = "",
    // The specific connection (GuacamoleConnection.identifier) this profile
    // launches, chosen from GuacamoleRepository.listConnections() in the
    // connection-picker UI (GuacamoleConnectionPickerDialog).
    @androidx.room.ColumnInfo(defaultValue = "''")
    val guacConnectionIdentifier: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''")
    val guacConnectionName: String = "",
    // Display-only (icon/label in the connection list) — never a branch
    // condition, per reg.txt's "must not hardcode protocols" requirement.
    @androidx.room.ColumnInfo(defaultValue = "''")
    val guacConnectionProtocol: String = "",
    // reg.txt's SESSION → "Remember session" — see GuacamoleRepository.tryRestoreSession's
    // doc comment for what's actually persisted (the auth token, never the
    // password) and why a stale restored token is never treated as a hard failure.
    @androidx.room.ColumnInfo(defaultValue = "0")
    val guacRememberSession: Boolean = false,

    // ── FTP/FTPS-STANDALONE FEATURE ─────────────────────────────────────────
    // Promotes FTP/FTPS from HomeScreen's one-off "Quick Transfer" dialog
    // (FtpTransferDialog, backed by FileTransferManager.FtpConfig) into a
    // real saved profile, the same way SFTP was promoted out of
    // VIA_SSH_SESSION in the previous pass — see ProtocolType.FTP/FTPS's doc
    // comments. FTP and FTPS deliberately share this one set of columns
    // (mirroring FileTransferManager.FtpConfig, which already takes a single
    // FtpSecurity value for both) exactly like SFTP/SCP share ProtocolType.SFTP
    // above: [ftpSecurity] is what actually distinguishes an FTP profile
    // (FtpSecurity.PLAIN) from an FTPS one (FTPS_EXPLICIT/FTPS_IMPLICIT), not
    // a separate column per protocol. [host]/[port]/[username]/[password]
    // above are reused as-is. Persisted as the enum's name — same
    // String-backed-enum pattern as codecPreference/remoteAppDisplayMode.
    @androidx.room.ColumnInfo(defaultValue = "PLAIN")
    val ftpSecurity: com.systemsgo.hex.transfer.FtpSecurity = com.systemsgo.hex.transfer.FtpSecurity.PLAIN,
    // Mirrors FileTransferManager.FtpConfig.passiveMode's doc comment:
    // passive mode is the correct default for a mobile client behind
    // carrier/Wi-Fi NAT, since active mode would need an inbound connection
    // to the phone.
    @androidx.room.ColumnInfo(defaultValue = "1")
    val ftpPassiveMode: Boolean = true,

    // ── SMB-STANDALONE FEATURE ───────────────────────────────────────────────
    // Promotes SMB from HomeScreen's Quick Transfer dialog (SmbTransferDialog,
    // backed by FileTransferManager.SmbConfig) into a real saved profile.
    // [host]/[username]/[password] above are reused as-is; [port] is reused
    // too (SmbConfig.port, normally 445). [smbShare] and [smbDomain] are the
    // two fields SmbConfig needs that no other protocol's columns cover.
    @androidx.room.ColumnInfo(defaultValue = "''")
    val smbShare: String = "",
    // Optional — most SMB shares (especially modern Samba/NAS setups) never
    // need a domain/workgroup at all; SmbConfig treats blank the same way
    // FtpConfig/WebDavConfig treat their own optional fields.
    @androidx.room.ColumnInfo(defaultValue = "''")
    val smbDomain: String = "",

    // ── WEBDAV-STANDALONE FEATURE ────────────────────────────────────────────
    // Promotes WebDAV from HomeScreen's Quick Transfer dialog
    // (WebDavTransferDialog, backed by FileTransferManager.WebDavConfig) into
    // a real saved profile. Unlike every other file-transfer protocol here,
    // WebDavConfig takes a single [baseUrl] (e.g. "https://host/remote.php/dav/")
    // rather than a separate host/port — the URL already carries the scheme
    // and port, and WebDAV base paths vary too much (Nextcloud, IIS, Apache
    // mod_dav, ...) to reconstruct reliably from host/port alone. [username]/
    // [password] above are still reused as-is; [host]/[port] are left at
    // their defaults and ignored for this protocol type (see
    // ProfileFormDialog's ProtocolType.WEBDAV branch, which hides them and
    // shows [webdavBaseUrl] instead — same "hide what doesn't apply" pattern
    // NFS's username/password-less branch uses below).
    @androidx.room.ColumnInfo(defaultValue = "''")
    val webdavBaseUrl: String = "",

    // ── NFS-STANDALONE FEATURE ───────────────────────────────────────────────
    // Promotes NFS from HomeScreen's Quick Transfer dialog (NfsTransferDialog,
    // backed by FileTransferManager.NfsConfig) into a real saved profile.
    // NFSv3/AUTH_SYS (this app's only supported mode — see NfsConfig's doc
    // comment in FileTransferManager.kt) has no username/password at all, so
    // unlike every other protocol here this profile leaves [username]/
    // [password] unused and blank (see ProfileFormDialog's ProtocolType.NFS
    // branch, which hides the Authentication section entirely and shows
    // [nfsUid]/[nfsGid] instead — AUTH_SYS identifies the caller by numeric
    // uid/gid, not a login). [host] above is reused as-is; [port] is left at
    // its default and ignored (NFS itself has no fixed port — it's obtained
    // per-export from the remote portmapper/rpcbind — only [nfsMountdPort]
    // below is ever a fixed, user-supplied port, and only when the export's
    // mountd isn't using the portmapper-advertised one).
    @androidx.room.ColumnInfo(defaultValue = "''")
    val nfsExportPath: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0")
    val nfsUid: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val nfsGid: Int = 0,
    // 0 = let NfsClient ask the remote portmapper for mountd's port, same as
    // NfsConfig.mountdPort's own default; a nonzero value is only needed for
    // an export whose mountd sits on a fixed, non-portmapper-advertised port.
    @androidx.room.ColumnInfo(defaultValue = "0")
    val nfsMountdPort: Int = 0,
) {
    // SSH-PROXYJUMP-CHAIN FEATURE: the single migration point every caller
    // (RemoteSessionFactory, the future multi-hop editor UI, ...) should use
    // instead of reading [sshTunnelHops] or the deprecated sshTunnelHost/
    // sshTunnelPort/... fields directly.
    //
    //  - If [sshTunnelHops] already has entries, it's returned as-is — this
    //    is a profile created (or already re-saved) by a build that has this
    //    feature.
    //  - Otherwise, if the legacy single-hop fields are populated
    //    ([sshTunnelEnabled] and a non-blank [sshTunnelHost]), they're
    //    converted into a single-entry list on the fly — this is a profile
    //    saved by an older build. Nothing is written back to the database
    //    here; the conversion happens fresh on every read so it works
    //    equally for a profile that's never been touched since being loaded
    //    from Room, one restored from an old backup, etc. The next time the
    //    profile is saved (RdpProfileRepository.saveProfile/updateProfile),
    //    whichever code path did the save decides whether to persist the
    //    migrated form into [sshTunnelHops] or leave the legacy fields as
    //    they were.
    //  - Otherwise (tunnel never configured), empty.
    // @Ignore: this is a derived/computed view over other columns, not a
    // column of its own — without this, Room would try (and fail) to map a
    // getter-only property with no matching constructor parameter.
    @get:androidx.room.Ignore
    @Suppress("DEPRECATION")
    val effectiveSshTunnelHops: List<SshJumpHop>
        get() = when {
            sshTunnelHops.isNotEmpty() -> sshTunnelHops
            sshTunnelEnabled && sshTunnelHost.isNotBlank() -> listOf(
                SshJumpHop(
                    host = sshTunnelHost,
                    port = sshTunnelPort,
                    username = sshTunnelUsername,
                    authType = sshTunnelAuthType,
                    password = sshTunnelPassword,
                    privateKey = sshTunnelPrivateKey,
                    privateKeyPassphrase = sshTunnelPrivateKeyPassphrase,
                )
            )
            else -> emptyList()
        }
}


object RdpPerformance {
    const val LOW_BANDWIDTH = 0    // 2G / very slow
    const val MEDIUM = 1           // 3G
    const val WIFI = 2             // WiFi
    const val LAN = 3              // LAN / Fast
    const val AUTO = 4             // Auto-detect and adapt

    // AUTO-COLOR-DEPTH FEATURE: sentinel for AppSettings.colorDepth meaning
    // "pick automatically based on live network speed" instead of a fixed
    // 16/24/32-bit value. Resolved to a concrete depth right before each
    // connect/reconnect by NetworkQualityDetector.resolveColorDepth() — see
    // that function's doc comment. 0 is safe as a sentinel here since no
    // real color depth is ever 0.
    const val COLOR_DEPTH_AUTO = 0

    // BUG-FLAGS FIX: FreeRDP_PerformanceFlags is a real bitmask (PERF_DISABLE_*),
    // not a sequential enum. Passing the raw 0-4 level straight into
    // FreeRDP_PerformanceFlags meant LOW_BANDWIDTH (0) disabled nothing at all,
    // and LAN (3) happened to disable wallpaper + full-window-drag by accident.
    // flagsFor() converts a UI performance level into the actual FreeRDP bitmask
    // so "Low Bandwidth" genuinely turns off wallpaper/animations/theming/etc.
    private const val PERF_DISABLE_WALLPAPER = 0x01
    private const val PERF_DISABLE_FULLWINDOWDRAG = 0x02
    private const val PERF_DISABLE_MENUANIMATIONS = 0x04
    private const val PERF_DISABLE_THEMING = 0x08
    private const val PERF_DISABLE_CURSOR_SHADOW = 0x20

    /**
     * Converts a UI-level performance setting ([LOW_BANDWIDTH]..[LAN]) into the
     * actual FreeRDP_PerformanceFlags bitmask that should be sent to the native
     * bridge.
     *
     * BUG-AUTO-QUALITY FIX: [AUTO] should always be resolved to a concrete level
     * via [com.systemsgo.hex.util.NetworkQualityDetector] *before* it reaches this
     * function (see RdpSessionViewModel's connect paths). It is still accepted
     * here defensively — treated the same as [LAN] (no effects disabled) — so a
     * caller that forgets to resolve it fails safe rather than crashing, but
     * this is not itself network-aware; the real adaptation lives in
     * NetworkQualityDetector.
     */
    fun flagsFor(level: Int): Int = when (level) {
        LOW_BANDWIDTH -> PERF_DISABLE_WALLPAPER or PERF_DISABLE_FULLWINDOWDRAG or
            PERF_DISABLE_MENUANIMATIONS or PERF_DISABLE_THEMING or PERF_DISABLE_CURSOR_SHADOW
        MEDIUM -> PERF_DISABLE_WALLPAPER or PERF_DISABLE_FULLWINDOWDRAG or PERF_DISABLE_MENUANIMATIONS
        WIFI -> PERF_DISABLE_WALLPAPER
        LAN, AUTO -> 0x00
        else -> PERF_DISABLE_WALLPAPER or PERF_DISABLE_FULLWINDOWDRAG
    }

    // QUALITY-UNIFY FIX: "frame compression quality" (a raw 0-100 codec dial)
    // and "performance level" (an effects on/off switch) used to be two
    // separate settings that both claimed to control picture quality —
    // confusing, since a user with a bad connection had to know to turn
    // down *both* to actually get a responsive session. There is now a
    // single 5-level network-quality control (see AppSettings.performanceLevel
    // and the Settings → Connection screen); this function supplies the
    // codec-quality half of that one control, mirroring flagsFor() above
    // which supplies the effects half. Both move together as one dial.
    //
    // BUG-AUTO-QUALITY FIX: [AUTO] ("Auto-detect and adapt") used to be treated
    // as a synonym for the single best possible quality (100) regardless of
    // the device's actual network — a user on a weak connection who picked
    // "Auto" got the heaviest codec settings instead of an adapted one, the
    // opposite of what the name promises. Real adaptation now happens in
    // com.systemsgo.hex.util.NetworkQualityDetector, which resolves AUTO to a
    // concrete level (based on live signal/bandwidth) before this function is
    // ever called (see RdpSessionViewModel's connect paths). The AUTO branch
    // below is only a defensive fallback for a caller that forgot to resolve
    // it first, so it intentionally returns a moderate value rather than 100.
    fun codecQualityFor(level: Int): Int = when (level) {
        LOW_BANDWIDTH -> 10    // very weak network: favor responsiveness over fidelity
        MEDIUM        -> 35
        WIFI          -> 60
        LAN           -> 85
        AUTO          -> 60    // unresolved AUTO fallback: same as WIFI, not max quality
        else          -> 75
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR,
    // CONNECTION-STATUS-INDICATOR FEATURE: two additional terminal/interactive
    // states surfaced by the connection-status badge on each connection card.
    // AUTH_REQUIRED is a specialization of what used to always collapse into
    // ERROR — the session-state collector in RdpSessionActivity already knows
    // (from the underlying client's own state machine, not string-sniffing)
    // when a drop was specifically an authentication failure; that branch now
    // reports AUTH_REQUIRED so the card can show a distinct blue shield/lock
    // instead of a generic red failure. SUSPENDED has no live producer yet in
    // this codebase (no protocol client currently reports a paused-but-alive
    // session back through SessionTabManager) — it exists here so the status
    // model and UI are ready for the day one does (a suspend/resume-capable
    // protocol reporting a paused-but-alive session through the shared
    // session-tab pipeline is the most natural future source).
    AUTH_REQUIRED,
    SUSPENDED
}

data class RdpCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val domain: String,
    val useNla: Boolean = true,
    val acceptSelfSignedCertificate: Boolean = false,  // BUG-3 FIX
    val gatewayEnabled: Boolean = false,
    val gatewayHost: String = "",
    val gatewayPort: Int = 443,
    val gatewayUsername: String = "",
    val gatewayPassword: String = "",
    val gatewayDomain: String = "",
    // ENTRA-ID-AUTH FEATURE: mirrors RdpProfile.gatewayAuthMode — see that
    // field's doc comment. Kept as the enum type here (not a raw String,
    // unlike the Room column) since RdpCredentials is a pure runtime value,
    // never persisted directly.
    val gatewayAuthMode: GatewayAuthMode = GatewayAuthMode.PASSWORD,
    // The freshly MSAL-acquired bearer token for the Gateway's Application ID
    // URI, resolved by the caller (see GatewayTokenProvider) immediately
    // before RemoteSessionFactory.create() — never persisted, never written
    // to Room/EncryptedPrefsHelper: it is a short-lived (~60-90 min) access
    // token, re-acquired fresh (silently, via MSAL's own refresh-token
    // rotation) on every connect attempt. Ignored entirely when
    // gatewayAuthMode == PASSWORD. systemsgo_jni.c's nativeConnect (Part 2)
    // consumes this to set the RDG HTTPS transport's Authorization header —
    // see AFreeRdpBridge.connect()'s gatewayBearerToken doc comment.
    val gatewayBearerToken: String = "",
    // OUTBOUND-PROXY FEATURE: mirrors RdpProfile's proxy* fields above — see
    // that class's doc comment. Copied through by RemoteSessionFactory /
    // RdpRemoteAdapter the same way gateway*/remoteApp* already are.
    val proxyEnabled: Boolean = false,
    val proxyType: ProxyType = ProxyType.SOCKS,
    val proxyHost: String = "",
    val proxyPort: Int = 1080,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    // PAC-SUPPORT FEATURE: mirrors RdpProfile.pacUrl — see that field's doc
    // comment for the full pacUrl-vs-static-fields priority/fallback rules.
    // Carried through here purely for traceability/logging at the point
    // RdpRemoteAdapter/AFreeRdpBridge actually connect (so a bug report
    // showing "proxyEnabled=true, proxyHost=1.2.3.4" can also show whether
    // that came from a PAC resolution or a hand-entered static proxy) —
    // by the time RdpCredentials exists, proxyEnabled/proxyType/proxyHost/
    // proxyPort above ALREADY hold the fully-resolved effective proxy
    // (see com.systemsgo.hex.proxy.PacProxyResolver.resolve(), called by
    // the caller before RemoteSessionFactory.create() builds this object —
    // same shape as gatewayBearerToken below). This field is never itself
    // re-resolved here.
    val pacUrl: String = "",
    // RDP-OVER-WEBSOCKET FEATURE: mirrors RdpProfile.transportMode/
    // webSocketConfig above — see those fields' doc comments. Kept as the
    // real enum/data-class types here (not the Room-column String
    // encodings), same reasoning as gatewayAuthMode's enum-vs-String split
    // just above: RdpCredentials is a pure runtime value, never persisted
    // directly. RdpRemoteAdapter.connect() reads these to decide whether
    // to stand up an [com.systemsgo.hex.rdp.transport.RdpWebSocketTransport]
    // loopback bridge before calling AFreeRdpBridge.connect(), or to skip
    // straight to host/port exactly as it always has.
    val transportMode: com.systemsgo.hex.rdp.transport.RdpTransportMode =
        com.systemsgo.hex.rdp.transport.RdpTransportMode.TCP,
    val webSocketConfig: com.systemsgo.hex.rdp.transport.RdpWebSocketConfig =
        com.systemsgo.hex.rdp.transport.RdpWebSocketConfig(),
    val remoteAppEnabled: Boolean = false,
    val remoteAppProgram: String = "",
    val remoteAppWorkingDir: String = "",
    val remoteAppCmdLine: String = "",
    // REMOTEAPP-WINDOWS FEATURE: see RdpProfile.remoteAppDisplayMode's doc
    // comment. Copied through by RemoteSessionFactory.create() the same way
    // as the remoteApp* fields above; RdpRemoteAdapter reads it directly
    // (never forwarded to AFreeRdpBridge.connect()/nativeConnect — this is
    // purely a local rendering choice, not something the server is told).
    val remoteAppDisplayMode: RemoteAppDisplayMode = RemoteAppDisplayMode.SINGLE_WINDOW,
    // MIC-REDIRECT FEATURE: RdpCredentials previously carried neither of
    // these two audio flags at all — RemoteSessionFactory built this object
    // straight from RdpProfile but never copied profile.enableSound over, so
    // the "Enable sound" toggle in the connection form was dead: saved to
    // Room, shown back in the UI on next edit, but never reached
    // RdpRemoteAdapter/AFreeRdpBridge/systemsgo_jni.c. Both flags now flow
    // through to nativeConnect (see AFreeRdpBridge.connect()), which sets
    // FreeRDP_AudioPlayback / FreeRDP_AudioCapture before
    // freerdp_client_load_addins() runs.
    val enableSound: Boolean = false,
    val enableMicRedirect: Boolean = false,
    // CLIPBOARD FIX: mirrors the enableSound/enableMicRedirect fix above —
    // RdpProfile.enableClipboard existed (UI toggle, Room column, .rdp file
    // parsing) but RemoteSessionFactory never copied it onto RdpCredentials,
    // so it never reached RdpRemoteAdapter/AFreeRdpBridge/systemsgo_jni.c and the
    // MS-RDPECLIP "cliprdr" channel was never advertised, regardless of what
    // the user chose. Now flows through to nativeConnect (see
    // AFreeRdpBridge.connect()), which sets FreeRDP_RedirectClipboard before
    // freerdp_client_load_addins() runs.
    val enableClipboard: Boolean = true,
    // DRIVE-REDIRECT FIX: mirrors the enableClipboard fix immediately above —
    // RdpProfile.enableDriveRedirect existed (Room column, .rdp file parsing
    // via RdpFileParser's "redirectdrives" property) but RemoteSessionFactory
    // never copied it onto RdpCredentials, so it never reached
    // RdpRemoteAdapter/AFreeRdpBridge/systemsgo_jni.c and the MS-RDPEFS "rdpdr"
    // channel was never advertised, regardless of what the profile said. Now
    // flows through to nativeConnect (see AFreeRdpBridge.connect()), which
    // sets FreeRDP_DeviceRedirection and registers an "android" drive device
    // pointing at this app's external files directory before
    // freerdp_client_load_addins() runs.
    val enableDriveRedirect: Boolean = false,

    // PRINTER-REDIRECT FIX: mirrors the enableDriveRedirect fix immediately
    // above — RdpProfile.enablePrinterRedirect exists (Room column, .rdp file
    // parsing via RdpFileParser's "redirectprinters" property) but needs the
    // same copy-through RemoteSessionFactory does for every other redirection
    // flag, or it never reaches RdpRemoteAdapter/AFreeRdpBridge/systemsgo_jni.c
    // and the printer device is never registered on the "rdpdr" channel.
    val enablePrinterRedirect: Boolean = false,

    // WEBCAM-REDIRECT FEATURE: mirrors the enablePrinterRedirect fix
    // immediately above — RdpProfile.enableWebcamRedirect exists (Room
    // column) but needs the same copy-through RemoteSessionFactory does for
    // every other redirection flag, or it never reaches
    // RdpRemoteAdapter/AFreeRdpBridge/systemsgo_jni.c and the "rdpecam" dynamic
    // channel is never registered.
    val enableWebcamRedirect: Boolean = false,

    // SMARTCARD-REDIRECT FEATURE: mirrors the enableWebcamRedirect fix
    // immediately above — RdpProfile.enableSmartcardRedirect exists (Room
    // column) but needs the same copy-through RemoteSessionFactory does for
    // every other redirection flag, or it never reaches
    // RdpRemoteAdapter/AFreeRdpBridge/systemsgo_jni.c and the "smartcard" rdpdr
    // device is never registered.
    val enableSmartcardRedirect: Boolean = false,

    // PARALLEL-REDIRECT FEATURE: mirrors the enableSmartcardRedirect copy-
    // through immediately above — RdpProfile.enableParallelRedirect/
    // parallelPortPath exist (Room columns) but need the same
    // RemoteSessionFactory copy-through every other redirection flag gets,
    // or they never reach RdpRemoteAdapter/AFreeRdpBridge/systemsgo_jni.c and
    // the "parallel" rdpdr device is never registered.
    val enableParallelRedirect: Boolean = false,
    val parallelPortPath: String = "",

    // SERIAL-REDIRECT FEATURE: mirrors the enableParallelRedirect copy-
    // through immediately above — RdpProfile.enableSerialRedirect/
    // serialPortPath exist (Room columns) but need the same
    // RemoteSessionFactory copy-through every other redirection flag gets.
    val enableSerialRedirect: Boolean = false,
    val serialPortPath: String = "",
    // SERIAL-OVER-NETWORK FEATURE: mirrors the copy-through above —
    // RdpProfile.serialRedirectMode/serialNetworkHost/serialNetworkPort
    // exist (Room columns) but need the same RemoteSessionFactory
    // copy-through every other redirection field gets. See
    // RdpProfile.serialRedirectMode's doc comment.
    val serialRedirectMode: SerialRedirectMode = SerialRedirectMode.LOCAL_DEVICE,
    val serialNetworkHost: String = "",
    val serialNetworkPort: Int = 2217,

    // MULTI-MONITOR FEATURE: the saved per-connection monitor preference
    // (see RdpProfile.preferredMonitorId's doc). RdpRemoteAdapter applies
    // this once via selectMonitor() right after the session reaches
    // CONNECTED and a multi-monitor layout is available, so a saved
    // connection reopens showing whatever the user picked last time instead
    // of always defaulting to "All Monitors".
    val preferredMonitorId: Int = com.systemsgo.hex.display.MonitorSelection.ALL_MONITORS_ID,

    // CODEC-NEGOTIATION FEATURE: mirrors the preferredMonitorId copy-through
    // immediately above — RdpProfile.codecPreference exists (Room column)
    // but needs the same RemoteSessionFactory copy-through every other
    // per-profile setting gets, or it never reaches
    // RdpRemoteAdapter/AFreeRdpBridge/systemsgo_jni.c and every connection
    // silently keeps fully-automatic negotiation regardless of what the
    // profile says. RdpRemoteAdapter.connect() translates this
    // (data.model.CodecPreference) into
    // com.systemsgo.hex.rdp.native.AFreeRdpBridge.CodecPreference right
    // before calling bridge.connect() — see [CodecPreference]'s doc comment
    // for why that translation exists instead of just reusing one enum.
    val codecPreference: CodecPreference = CodecPreference.AUTO,
)

data class RdpSessionInfo(
    val profileId: String,
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val errorMessage: String? = null,
    val latencyMs: Long = 0L,
    val bandwidthKbps: Int = 0
)
