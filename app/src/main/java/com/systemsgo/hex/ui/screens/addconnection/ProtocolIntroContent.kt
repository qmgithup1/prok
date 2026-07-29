package com.systemsgo.hex.ui.screens.addconnection

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ProtocolCatalogEntry

/** One row in the intro panel's "Key Features" list. */
data class ProtocolFeature(val icon: ImageVector, val title: String, val subtitle: String)

/**
 * ADD-CONNECTION PROTOCOL PICKER — FIRST-TIME EXPERIENCE (Part 2 groundwork).
 *
 * Content shown by [ProtocolIntroPanel] the first time a user opens a given
 * protocol, and again on demand from "What is this protocol?" inside the
 * connection editor.
 *
 * [previewImageRes] is intentionally nullable and unset for every entry
 * below — it's the slot for a real product screenshot/illustration per
 * protocol (the "Preview" section in the reference design). Drop a drawable
 * into res/drawable and reference it here per protocol; until then
 * [ProtocolIntroPanel] renders a themed placeholder in that exact spot so
 * the layout, rounding, and spacing are already final.
 *
 * i18n: every piece of copy below is resolved through [stringResource] inside
 * [protocolIntroContentFor], so this whole file is a @Composable-only API —
 * it must be called from composition (see [ProtocolIntroPanel]'s
 * `val content = protocolIntroContentFor(entry)`), not cached across
 * recompositions with a plain `remember { ... }` block, since `remember`'s
 * calculation lambda disallows composable calls.
 */
data class ProtocolIntroContent(
    val headline: String,
    val keyFeatures: List<ProtocolFeature>,
    val useCases: List<String>,
    @DrawableRes val previewImageRes: Int? = null,
    val learnMoreUrl: String? = null,
)

/** Curated content for the protocols users hit first. Extend this map as more get written. */
@Composable
private fun curatedIntroContent(): Map<String, ProtocolIntroContent> = mapOf(
    "RDP" to ProtocolIntroContent(
        headline = stringResource(R.string.protocol_intro_rdp_headline),
        keyFeatures = listOf(
            ProtocolFeature(Icons.Outlined.Monitor, stringResource(R.string.protocol_intro_rdp_feature1_title), stringResource(R.string.protocol_intro_rdp_feature1_subtitle)),
            ProtocolFeature(Icons.Outlined.Shield, stringResource(R.string.protocol_intro_rdp_feature2_title), stringResource(R.string.protocol_intro_rdp_feature2_subtitle)),
            ProtocolFeature(Icons.Outlined.DesktopWindows, stringResource(R.string.protocol_intro_rdp_feature3_title), stringResource(R.string.protocol_intro_rdp_feature3_subtitle)),
            ProtocolFeature(Icons.Outlined.ContentPaste, stringResource(R.string.protocol_intro_rdp_feature4_title), stringResource(R.string.protocol_intro_rdp_feature4_subtitle)),
            ProtocolFeature(Icons.Outlined.Badge, stringResource(R.string.protocol_intro_rdp_feature5_title), stringResource(R.string.protocol_intro_rdp_feature5_subtitle)),
            ProtocolFeature(Icons.Outlined.Storage, stringResource(R.string.protocol_intro_rdp_feature6_title), stringResource(R.string.protocol_intro_rdp_feature6_subtitle)),
        ),
        useCases = listOf(
            stringResource(R.string.protocol_intro_rdp_usecase1),
            stringResource(R.string.protocol_intro_rdp_usecase2),
            stringResource(R.string.protocol_intro_rdp_usecase3),
        ),
        learnMoreUrl = "https://learn.microsoft.com/windows-server/remote/remote-desktop-services/",
    ),
    "SSH" to ProtocolIntroContent(
        headline = stringResource(R.string.protocol_intro_ssh_headline),
        keyFeatures = listOf(
            ProtocolFeature(Icons.Outlined.Terminal, stringResource(R.string.protocol_intro_ssh_feature1_title), stringResource(R.string.protocol_intro_ssh_feature1_subtitle)),
            ProtocolFeature(Icons.Outlined.Lock, stringResource(R.string.protocol_intro_ssh_feature2_title), stringResource(R.string.protocol_intro_ssh_feature2_subtitle)),
            ProtocolFeature(Icons.Outlined.VerifiedUser, stringResource(R.string.protocol_intro_ssh_feature3_title), stringResource(R.string.protocol_intro_ssh_feature3_subtitle)),
            ProtocolFeature(Icons.Outlined.Folder, stringResource(R.string.protocol_intro_ssh_feature4_title), stringResource(R.string.protocol_intro_ssh_feature4_subtitle)),
        ),
        useCases = listOf(
            stringResource(R.string.protocol_intro_ssh_usecase1),
            stringResource(R.string.protocol_intro_ssh_usecase2),
            stringResource(R.string.protocol_intro_ssh_usecase3),
        ),
    ),
    "VNC" to ProtocolIntroContent(
        headline = stringResource(R.string.protocol_intro_vnc_headline),
        keyFeatures = listOf(
            ProtocolFeature(Icons.Outlined.Monitor, stringResource(R.string.protocol_intro_vnc_feature1_title), stringResource(R.string.protocol_intro_vnc_feature1_subtitle)),
            ProtocolFeature(Icons.Outlined.Speed, stringResource(R.string.protocol_intro_vnc_feature2_title), stringResource(R.string.protocol_intro_vnc_feature2_subtitle)),
            ProtocolFeature(Icons.Outlined.Security, stringResource(R.string.protocol_intro_vnc_feature3_title), stringResource(R.string.protocol_intro_vnc_feature3_subtitle)),
        ),
        useCases = listOf(
            stringResource(R.string.protocol_intro_vnc_usecase1),
            stringResource(R.string.protocol_intro_vnc_usecase2),
        ),
    ),
    "sftp" to ProtocolIntroContent(
        headline = stringResource(R.string.protocol_intro_sftp_headline),
        keyFeatures = listOf(
            ProtocolFeature(Icons.Outlined.Lock, stringResource(R.string.protocol_intro_sftp_feature1_title), stringResource(R.string.protocol_intro_sftp_feature1_subtitle)),
            ProtocolFeature(Icons.Outlined.Folder, stringResource(R.string.protocol_intro_sftp_feature2_title), stringResource(R.string.protocol_intro_sftp_feature2_subtitle)),
            ProtocolFeature(Icons.Outlined.VerifiedUser, stringResource(R.string.protocol_intro_sftp_feature3_title), stringResource(R.string.protocol_intro_sftp_feature3_subtitle)),
        ),
        useCases = listOf(
            stringResource(R.string.protocol_intro_sftp_usecase1),
            stringResource(R.string.protocol_intro_sftp_usecase2),
        ),
    ),
    "SPICE" to ProtocolIntroContent(
        headline = stringResource(R.string.protocol_intro_spice_headline),
        keyFeatures = listOf(
            ProtocolFeature(Icons.Outlined.Speed, stringResource(R.string.protocol_intro_spice_feature1_title), stringResource(R.string.protocol_intro_spice_feature1_subtitle)),
            ProtocolFeature(Icons.Outlined.Monitor, stringResource(R.string.protocol_intro_spice_feature2_title), stringResource(R.string.protocol_intro_spice_feature2_subtitle)),
            ProtocolFeature(Icons.Outlined.ContentPaste, stringResource(R.string.protocol_intro_spice_feature3_title), stringResource(R.string.protocol_intro_spice_feature3_subtitle)),
        ),
        useCases = listOf(
            stringResource(R.string.protocol_intro_spice_usecase1),
            stringResource(R.string.protocol_intro_spice_usecase2),
        ),
    ),
)

/**
 * Generic fallback for every catalog entry without curated content yet — built
 * entirely from data already in [ProtocolCatalogEntry] so the panel never has
 * an empty state, even for protocols nobody has written copy for.
 */
@Composable
private fun genericIntroContent(entry: ProtocolCatalogEntry): ProtocolIntroContent {
    val portLabel = entry.defaultPort?.toString() ?: stringResource(R.string.protocol_intro_generic_port_varies)
    return ProtocolIntroContent(
        headline = entry.description,
        keyFeatures = listOf(
            ProtocolFeature(
                Icons.Outlined.Security,
                stringResource(R.string.protocol_intro_generic_feature_title_fmt, entry.category.label),
                stringResource(R.string.protocol_intro_generic_feature_subtitle_fmt, portLabel),
            ),
        ),
        useCases = listOf(
            stringResource(R.string.protocol_intro_generic_usecase_fmt, entry.name, entry.category.label.lowercase()),
        ),
    )
}

/**
 * Preview screenshot/illustration per protocol, keyed by [ProtocolCatalogEntry.id]
 * (matches the ids assigned in ProtocolCatalog.kt — either `ProtocolType.X.name`
 * or the explicit string id for entries that share a [ProtocolType], e.g.
 * "kvm_over_ip" also uses ProtocolType.VNC). Applied uniformly in
 * [protocolIntroContentFor] so both curated and generic content get a preview
 * image without duplicating this table per entry.
 */
private val protocolPreviewImages: Map<String, Int> = mapOf(
    "RDP" to R.drawable.protocol_rdp,
    "VNC" to R.drawable.protocol_vnc,
    "SPICE" to R.drawable.protocol_spice,
    "guacamole" to R.drawable.protocol_guacamole,
    "rdp_over_websocket" to R.drawable.protocol_rdp_over_websocket,
    "IPMI" to R.drawable.protocol_ipmi,
    "REDFISH" to R.drawable.protocol_redfish,
    "AMT" to R.drawable.protocol_amt,
    "kvm_over_ip" to R.drawable.protocol_kvm_over_ip,
    "WAKE_ON_LAN" to R.drawable.protocol_wake_on_lan,
    "vmware_api" to R.drawable.protocol_vmware_api,
    "proxmox_api" to R.drawable.protocol_proxmox_api,
    "virtualbox_vrde" to R.drawable.protocol_virtualbox_vrde,
    "RTSP" to R.drawable.protocol_rtsp,
    "SFTP" to R.drawable.protocol_sftp,
    "sftp" to R.drawable.protocol_sftp,
    "scp" to R.drawable.protocol_scp,
    "FTP" to R.drawable.protocol_ftp,
    "FTPS" to R.drawable.protocol_ftps,
    "WEBDAV" to R.drawable.protocol_webdav,
    "SMB" to R.drawable.protocol_smb,
    "NFS" to R.drawable.protocol_nfs,
    "SSH" to R.drawable.protocol_ssh,
    "TELNET" to R.drawable.protocol_telnet,
    "SERIAL_CONSOLE" to R.drawable.protocol_serial_console,
    "MOSH" to R.drawable.protocol_mosh,
    "RLOGIN" to R.drawable.protocol_rlogin,
    "SNMP" to R.drawable.protocol_snmp,
    "NETCONF" to R.drawable.protocol_netconf,
    "RESTCONF" to R.drawable.protocol_restconf,
    "MODBUS_TCP" to R.drawable.protocol_modbus_tcp,
    "WEB" to R.drawable.protocol_web,
)

@Composable
fun protocolIntroContentFor(entry: ProtocolCatalogEntry): ProtocolIntroContent {
    val content = curatedIntroContent()[entry.id] ?: genericIntroContent(entry)
    val previewImageRes = protocolPreviewImages[entry.id]
    return if (previewImageRes != null) content.copy(previewImageRes = previewImageRes) else content
}
