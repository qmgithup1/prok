package com.systemsgo.hex.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import com.systemsgo.hex.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.systemsgo.hex.data.model.ConnectionState
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.session.SessionTab
import com.systemsgo.hex.ui.theme.*

/**
 * Feature-05 · تعدد الجلسات
 *
 * A horizontally-scrollable tab strip that shows all open remote sessions.
 * Tapping a tab brings that session to the foreground by launching
 * [RdpSessionActivity] with the matching tab id.
 * The × button closes the tab (the Activity handles its own disconnect via
 * [SessionTabManager.closeTab]).
 */
@Composable
fun SessionTabsBar(
    tabs: List<SessionTab>,
    activeTabId: String?,
    onTabClick: (SessionTab) -> Unit,
    onTabClose: (SessionTab) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return

    LazyRow(
        modifier            = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        NebulaSurface.copy(alpha = 0.7f)
                    )
                )
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tabs, key = { it.tabId }) { tab ->
            SessionTabChip(
                tab         = tab,
                isActive    = tab.tabId == activeTabId,
                onTabClick  = { onTabClick(tab) },
                onTabClose  = { onTabClose(tab) }
            )
        }
    }
}

@Composable
private fun SessionTabChip(
    tab:        SessionTab,
    isActive:   Boolean,
    onTabClick: () -> Unit,
    onTabClose: () -> Unit,
) {
    val accent   = PulsarCyan
    val surface  = NebulaSurface

    val bgColor by animateColorAsState(
        targetValue   = if (isActive) accent.copy(alpha = 0.18f) else surface,
        animationSpec = tween(200),
        label         = "tab_bg"
    )
    val borderColor by animateColorAsState(
        targetValue   = if (isActive) accent else HorizonGray.copy(alpha = 0.4f),
        animationSpec = tween(200),
        label         = "tab_border"
    )
    val scale by animateFloatAsState(
        targetValue   = if (isActive) 1.03f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label         = "tab_scale"
    )

    val stateDot = stateColor(tab.state)
    val protoIcon = when (tab.profile.protocolType) {
        ProtocolType.RDP -> Icons.Outlined.DesktopWindows
        ProtocolType.VNC -> Icons.Outlined.Monitor
        ProtocolType.SSH -> Icons.Outlined.Terminal
        ProtocolType.TELNET -> Icons.Outlined.SettingsEthernet
        ProtocolType.RLOGIN -> Icons.Outlined.SettingsEthernet
        // MOSH FEATURE: same tab shape as SSH/Telnet/Rlogin above — a real
        // SessionTabManager tab (unlike WEB/REDFISH/etc. below), so this
        // branch is reachable in practice, not just for exhaustiveness.
        ProtocolType.MOSH -> Icons.Outlined.SettingsEthernet
        // SPICE-PROTOCOL FEATURE (Part 1/N): same canvas-tab shape as RDP/
        // VNC, reuse the desktop icon as a placeholder until dedicated
        // SPICE artwork exists.
        ProtocolType.SPICE -> Icons.Outlined.DesktopWindows
        // RTSP FEATURE: a live camera view is a real SessionTabManager tab
        // just like RDP/VNC/SPICE above (it renders through the same
        // frameUpdates pipeline) — reachable in practice, not just for
        // exhaustiveness.
        ProtocolType.RTSP -> Icons.Outlined.Videocam
        // WEB-PORTAL FEATURE: a Web/HTTPS session is its own standalone
        // WebPortalActivity, never a SessionTabManager tab like RDP/VNC/SSH/
        // Telnet — so this branch is unreachable in practice, but the `when`
        // must still be exhaustive over ProtocolType.
        ProtocolType.WEB -> Icons.Outlined.Web
        // REDFISH-IPMI FEATURE: same "own standalone Activity, never a
        // SessionTabManager tab" reasoning as WEB just above — unreachable
        // in practice, but the `when` must stay exhaustive.
        ProtocolType.REDFISH -> Icons.Outlined.Dns
        ProtocolType.IPMI -> Icons.Outlined.SettingsRemote
        // AMT-VPRO FEATURE: same "own standalone Activity, never a
        // SessionTabManager tab" reasoning as REDFISH/IPMI just above —
        // unreachable in practice, but the `when` must stay exhaustive.
        ProtocolType.AMT -> Icons.Outlined.Memory
        // SERIAL-CONSOLE FEATURE: a terminal session like Telnet/Rlogin, so
        // it does get a SessionTabManager tab (unlike REDFISH/IPMI/AMT above).
        ProtocolType.SERIAL_CONSOLE -> Icons.Outlined.SettingsEthernet
        // RESTCONF FEATURE (Part 1/4): same "own standalone Activity
        // (RestconfExplorerActivity), never a SessionTabManager tab"
        // reasoning as WEB/REDFISH/IPMI/AMT above — unreachable in
        // practice, but the `when` must stay exhaustive.
        ProtocolType.RESTCONF -> Icons.Outlined.Api
        // SNMP FEATURE: same "own standalone Activity, never a
        // SessionTabManager tab" reasoning as REDFISH/IPMI/AMT above —
        // unreachable in practice, but the `when` must stay exhaustive.
        ProtocolType.SNMP -> Icons.Outlined.NetworkCheck
        // NETCONF FEATURE: own standalone NetconfSessionActivity, never a
        // SessionTabManager tab — same "unreachable in practice, `when`
        // must stay exhaustive" reasoning as REDFISH/IPMI/AMT above.
        ProtocolType.NETCONF -> Icons.Outlined.SettingsRemote
        // GUACAMOLE-PROTOCOL FEATURE: unlike WEB/REDFISH/IPMI/AMT/RESTCONF/
        // SNMP above, this DOES get a real SessionTabManager tab — a
        // Guacamole session is a genuine RemoteSessionClient, same family
        // as RDP/VNC/SSH — so this branch is reachable in practice.
        ProtocolType.GUACAMOLE -> Icons.Outlined.DesktopWindows
        // PROXMOX-API FEATURE: own standalone ProxmoxManagementActivity,
        // never a SessionTabManager tab — same "unreachable in practice,
        // `when` must stay exhaustive" reasoning as WEB/REDFISH/IPMI/AMT/
        // RESTCONF/SNMP/NETCONF above.
        ProtocolType.PROXMOX -> Icons.Outlined.Dns
        // MODBUS-TCP FEATURE (Part 2/2): own standalone
        // ModbusManagementActivity, never a SessionTabManager tab — same
        // "unreachable in practice, `when` must stay exhaustive" reasoning
        // as PROXMOX above.
        ProtocolType.MODBUS_TCP -> Icons.Outlined.NetworkCheck
        ProtocolType.VIRTUALBOX_VRDE -> Icons.Outlined.DesktopWindows
        ProtocolType.VMWARE_VSPHERE -> Icons.Outlined.Dns
        // SFTP-STANDALONE FEATURE: own standalone SftpFileTransferActivity,
        // never a SessionTabManager tab — same "unreachable in practice,
        // `when` must stay exhaustive" reasoning as WEB/REDFISH/IPMI/AMT/
        // RESTCONF/SNMP/NETCONF/PROXMOX/MODBUS_TCP above.
        ProtocolType.SFTP -> Icons.Outlined.Terminal
        // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: own standalone
        // SftpFileTransferActivity (shared with SFTP), never a
        // SessionTabManager tab — same "unreachable in practice, `when`
        // must stay exhaustive" reasoning as SFTP immediately above.
        ProtocolType.FTP, ProtocolType.FTPS -> Icons.Outlined.Terminal
        ProtocolType.WEBDAV -> Icons.Outlined.Terminal
        ProtocolType.SMB -> Icons.Outlined.Terminal
        ProtocolType.NFS -> Icons.Outlined.Terminal
        // WAKE-ON-LAN-STANDALONE FEATURE: never opens a SessionTabManager tab
        // — RdpSessionActivity's WAKE_ON_LAN branch sends the Magic Packet
        // and returns early via the ErrorOverlay before ever reaching
        // openTab() (see that fun's WAKE_ON_LAN check) — so this branch is
        // unreachable in practice, but the `when` must stay exhaustive.
        ProtocolType.WAKE_ON_LAN -> Icons.Outlined.Wifi
    }

    // NESTED-CLICK FIX: the close (×) button used to sit *inside* the same Row that
    // carried the tab-select `.clickable(onClick = onTabClick)`. A tap landing on the
    // close button's 36×36dp hit box was therefore inside two overlapping click regions
    // at once — the same anti-pattern fixed in SettingsToggle (see SettingsScreen.kt).
    // Here the consequence is worse than a visual flicker: tapping × to close a
    // background tab could also fire onTabClick, switching the *active* session to the
    // very tab that's being closed a moment later — the screen would jump to that
    // session and then immediately close it out from under the user.
    // Fix: only the [dot / icon / name] region is wrapped in the tab-select clickable
    // now; the close button is a sibling outside that region, so the two actions can
    // never both fire from a single tap.
    Row(
        modifier = Modifier
            .scale(scale)
            .height(36.dp)
            .background(bgColor, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = onTabClick
                ),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // State indicator dot
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(stateDot, CircleShape)
            )
            // Protocol icon
            Icon(
                protoIcon, null,
                tint     = if (isActive) accent else CometTail,
                modifier = Modifier.size(13.dp)
            )
            // Profile name
            Text(
                text      = tab.profile.name,
                color     = if (isActive) accent else CometTail,
                style     = MaterialTheme.typography.labelSmall,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis,
                modifier  = Modifier.widthIn(max = 100.dp)
            )
        }
        // Close button — BUG-C FIX: was Icon(size=14.dp).clickable — 14×14dp touch target is practically untappable.
        // Wrapped in a Box so the click area is 36×36dp while the icon stays 14dp.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onClick           = onTabClose
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cd_close),
                tint     = CometTail.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun stateColor(state: ConnectionState): Color = when (state) {
    ConnectionState.CONNECTED     -> PlasmaGreen
    ConnectionState.CONNECTING,
    ConnectionState.RECONNECTING  -> ConnectingAmber
    ConnectionState.ERROR         -> ErrorRed
    ConnectionState.DISCONNECTED  -> CometTail.copy(alpha = 0.4f)
    // CONNECTION-STATUS-INDICATOR FEATURE: no live producer emits these on a
    // SessionTab yet (see the enum's own doc comment in RdpProfile.kt) — the
    // branches exist purely so this exhaustive `when` keeps compiling and
    // already renders sensibly the moment something does.
    ConnectionState.AUTH_REQUIRED -> QuantumBlue
    ConnectionState.SUSPENDED     -> SolarFlare
}
