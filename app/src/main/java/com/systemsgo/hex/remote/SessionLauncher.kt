package com.systemsgo.hex.remote

import android.content.Context
import android.content.Intent
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.ui.screens.BmcManagementActivity
import com.systemsgo.hex.ui.screens.ModbusManagementActivity
import com.systemsgo.hex.ui.screens.NetconfSessionActivity
import com.systemsgo.hex.ui.screens.ProxmoxManagementActivity
import com.systemsgo.hex.ui.screens.RdpSessionActivity
import com.systemsgo.hex.ui.screens.RestconfExplorerActivity
import com.systemsgo.hex.ui.screens.SftpFileTransferActivity
import com.systemsgo.hex.ui.screens.SnmpManagementActivity
import com.systemsgo.hex.ui.screens.VSphereManagementActivity
import com.systemsgo.hex.web.WebPortalActivity

/**
 * WEB-PORTAL FEATURE: single choke point that decides which Activity a
 * profile's "Connect" tap actually opens. Before this existed, every screen
 * (HomeScreen, ConnectionHistoryScreen, SessionsScreen, ShortcutHelper's
 * pinned-shortcut launch intent) built `Intent(context, RdpSessionActivity::class.java)`
 * directly, which was correct back when RDP/VNC/SSH/Telnet were the only
 * four protocols — every one of them is a [RemoteSessionClient] that
 * RdpSessionActivity/RemoteSessionFactory know how to drive. [ProtocolType.WEB]
 * is not: it's a plain embedded WebView with no framebuffer and no terminal,
 * so it needs a different target Activity entirely ([WebPortalActivity]).
 * Routing every call site through here means adding a *sixth* protocol later
 * only requires a change in one place, not an audit of every screen that can
 * launch a session.
 */
object SessionLauncher {

    /**
     * Builds the correct launch [Intent] for [profile] — [WebPortalActivity]
     * for [ProtocolType.WEB], [RdpSessionActivity] for everything else —
     * with the "profile_id" extra every existing call site already relies on.
     * Does not call [Context.startActivity] itself so callers can still
     * apply their own flags/extras (e.g. Quick Connect's "quick_token")
     * exactly as before.
     */
    fun intentFor(context: Context, profile: RdpProfile): Intent {
        val targetActivity = when (profile.protocolType) {
            ProtocolType.WEB -> WebPortalActivity::class.java
            ProtocolType.REDFISH, ProtocolType.IPMI, ProtocolType.AMT -> BmcManagementActivity::class.java
            ProtocolType.RESTCONF -> RestconfExplorerActivity::class.java
            ProtocolType.SNMP -> SnmpManagementActivity::class.java
            ProtocolType.NETCONF -> NetconfSessionActivity::class.java
            // MODBUS-TCP FEATURE (Part 2/2): like REDFISH/IPMI/AMT/SNMP/NETCONF
            // above, Modbus has its own dedicated register/point management
            // screen, not a RemoteSessionClient session.
            ProtocolType.MODBUS_TCP -> ModbusManagementActivity::class.java
            // PROXMOX-API FEATURE (Part 3/N): same "management API, not a
            // framebuffer session" shape as MODBUS_TCP above.
            ProtocolType.PROXMOX -> ProxmoxManagementActivity::class.java
            // VMWARE-VSPHERE FEATURE (Part 3/N): same "management API, not a
            // framebuffer session" shape as PROXMOX/MODBUS_TCP above — see
            // VSphereManagementActivity's class doc for the full Part 1-3 chain.
            ProtocolType.VMWARE_VSPHERE -> VSphereManagementActivity::class.java
            // WAKE-ON-LAN-STANDALONE FEATURE: a WAKE_ON_LAN profile has no
            // session Activity at all to open — "Connect" sends a Magic Packet
            // and that's the entire interaction (see MainViewModel.sendWakeOnLan).
            // Every call site that can reach a WAKE_ON_LAN profile (HomeScreen's
            // launchConnect, ConnectionHistoryScreen, pinned shortcuts) checks
            // profile.protocolType == WAKE_ON_LAN and calls sendWakeOnLan()
            // *before* ever asking SessionLauncher for an Intent, so this
            // branch should be unreachable in practice — it only exists so the
            // `when` here stays exhaustive, and falls back to the harmless
            // no-op of opening RdpSessionActivity (which itself will refuse to
            // start a WAKE_ON_LAN session — see RemoteSessionFactory's
            // WAKE_ON_LAN branch) rather than crashing outright if some future
            // call site forgets that check.
            ProtocolType.WAKE_ON_LAN -> RdpSessionActivity::class.java
            // SFTP-STANDALONE FEATURE: a standalone SFTP connection has no
            // terminal/framebuffer session either — see SftpFileTransferActivity's
            // class doc, same "not a RemoteSessionClient" family as WEB above.
            ProtocolType.SFTP -> SftpFileTransferActivity::class.java
            // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: same "no terminal/
            // framebuffer session" reasoning as SFTP immediately above — these
            // five reuse the exact same SftpFileTransferActivity/
            // FileTransferScreen shell SFTP does (see FileTransferScreen's
            // per-protocol config-building, extended alongside the `isSsh`
            // flag for these five), rather than five near-duplicate Activities.
            ProtocolType.FTP, ProtocolType.FTPS, ProtocolType.WEBDAV,
            ProtocolType.SMB, ProtocolType.NFS -> SftpFileTransferActivity::class.java
            else -> RdpSessionActivity::class.java
        }
        return Intent(context, targetActivity).putExtra("profile_id", profile.id)
    }

    /**
     * Same routing as [intentFor], for call sites (e.g. reconnecting from a
     * [com.systemsgo.hex.data.model.ConnectionLog] row) that know a
     * profile's id and [ProtocolType] but don't have the full [RdpProfile]
     * loaded yet — RdpSessionActivity/WebPortalActivity both load the rest
     * of the profile themselves from the "profile_id" extra either way.
     */
    fun intentForProtocol(context: Context, profileId: String, protocolType: ProtocolType): Intent {
        val targetActivity = when (protocolType) {
            ProtocolType.WEB -> WebPortalActivity::class.java
            ProtocolType.REDFISH, ProtocolType.IPMI, ProtocolType.AMT -> BmcManagementActivity::class.java
            ProtocolType.RESTCONF -> RestconfExplorerActivity::class.java
            ProtocolType.SNMP -> SnmpManagementActivity::class.java
            ProtocolType.NETCONF -> NetconfSessionActivity::class.java
            ProtocolType.MODBUS_TCP -> ModbusManagementActivity::class.java
            ProtocolType.PROXMOX -> ProxmoxManagementActivity::class.java
            ProtocolType.VMWARE_VSPHERE -> VSphereManagementActivity::class.java
            // WAKE-ON-LAN-STANDALONE FEATURE: see intentFor's WAKE_ON_LAN branch
            // just above — should be unreachable in practice.
            ProtocolType.WAKE_ON_LAN -> RdpSessionActivity::class.java
            ProtocolType.SFTP -> SftpFileTransferActivity::class.java
            // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: see intentFor's
            // matching branch just above.
            ProtocolType.FTP, ProtocolType.FTPS, ProtocolType.WEBDAV,
            ProtocolType.SMB, ProtocolType.NFS -> SftpFileTransferActivity::class.java
            else -> RdpSessionActivity::class.java
        }
        return Intent(context, targetActivity).putExtra("profile_id", profileId)
    }

    /** Convenience for the common "just connect" case with no extra flags. */
    fun launch(context: Context, profile: RdpProfile) {
        context.startActivity(intentFor(context, profile))
    }

    /**
     * SNMP FEATURE: opens [SnmpManagementActivity] for [profile]'s SNMP
     * monitoring add-on (see [RdpProfile.snmpMonitoringEnabled]) — unlike
     * [launch]/[intentFor], this always targets [SnmpManagementActivity]
     * regardless of [profile]'s own [ProtocolType] (an RDP/SSH/... profile
     * with the add-on enabled still stores real `snmp*` credential columns,
     * which [SnmpManagementActivity]/`toSnmpCredentials()` read exactly the
     * same way as a native [ProtocolType.SNMP] profile does). Call sites are
     * expected to only surface this when [RdpProfile.snmpMonitoringEnabled]
     * is true — see `RdpProfileCard`'s "SNMP Dashboard" menu row.
     */
    fun launchSnmpDashboard(context: Context, profile: RdpProfile) {
        context.startActivity(Intent(context, SnmpManagementActivity::class.java).putExtra("profile_id", profile.id))
    }
}
