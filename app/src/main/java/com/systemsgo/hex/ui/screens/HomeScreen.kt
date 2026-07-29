package com.systemsgo.hex.ui.screens

import android.app.Activity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import java.util.UUID
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ConnectionFolder
import com.systemsgo.hex.data.model.FolderColor
import com.systemsgo.hex.data.model.FolderIcon
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.session.CardStatusInfo
import com.systemsgo.hex.session.SessionTab
import com.systemsgo.hex.session.SessionTabManager
import com.systemsgo.hex.ui.MainViewModel
import com.systemsgo.hex.ui.UNFILED_FOLDER_ID
import com.systemsgo.hex.ui.components.*
import com.systemsgo.hex.ui.screens.RdpSessionActivity
import com.systemsgo.hex.ui.theme.*
import com.systemsgo.hex.util.normalizeDigits
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

// ── Protocol filter tabs ────────────────────────────────────────────────────
// RECENT-FILTER FEATURE: cap on how many profiles the "Recent" quick filter
// shows — it's meant as a fast jump to what you just used, not a full
// re-sort of the whole list.
private const val RECENT_FILTER_LIMIT = 15

private enum class ProtocolFilter(val protocol: ProtocolType?) {
    ALL(null),
    RDP(ProtocolType.RDP),
    VNC(ProtocolType.VNC),
    SSH(ProtocolType.SSH),
    TELNET(ProtocolType.TELNET),
    RLOGIN(ProtocolType.RLOGIN),
    SPICE(ProtocolType.SPICE),
    WEB(ProtocolType.WEB),
    // REDFISH-IPMI FEATURE
    REDFISH(ProtocolType.REDFISH),
    IPMI(ProtocolType.IPMI),
    // AMT-VPRO FEATURE
    AMT(ProtocolType.AMT),
    // RESTCONF FEATURE (Part 1/4)
    RESTCONF(ProtocolType.RESTCONF),
    // SNMP FEATURE
    SNMP(ProtocolType.SNMP),
    // NETCONF FEATURE
    NETCONF(ProtocolType.NETCONF),
    // GUACAMOLE-PROTOCOL FEATURE
    GUACAMOLE(ProtocolType.GUACAMOLE),
    // RTSP FEATURE
    RTSP(ProtocolType.RTSP),
    // MODBUS-TCP FEATURE (Part 2/2)
    MODBUS_TCP(ProtocolType.MODBUS_TCP),
    // MOSH FEATURE — pre-existing gap fix: was never added as a filter entry.
    MOSH(ProtocolType.MOSH),
    // PROXMOX-API FEATURE — pre-existing gap fix: was never added as a filter entry.
    PROXMOX(ProtocolType.PROXMOX),
    // VIRTUALBOX-VRDE FEATURE (Part 1/N)
    VIRTUALBOX_VRDE(ProtocolType.VIRTUALBOX_VRDE),
    // VMWARE-VSPHERE FEATURE (Part 1/N)
    VMWARE_VSPHERE(ProtocolType.VMWARE_VSPHERE),
    // SERIAL-CONSOLE FEATURE — pre-existing gap fix: ProtocolType had this
    // since the Serial Console feature landed, but ProtocolFilter never grew
    // a matching case (see VRDE/vSphere continuation task list). Added now
    // alongside the VRDE/vSphere filter work rather than left as a known gap.
    SERIAL_CONSOLE(ProtocolType.SERIAL_CONSOLE),
}

// ── Category Filter icon/label helpers ──────────────────────────────────────
// Single source of truth for a protocol chip's icon — used by both the
// "All protocols" flat list (legacy dropdown, kept for FilterChip/EmptyState)
// and the new grouped CategoryFilterPopup below.
private fun protocolFilterIcon(filter: ProtocolFilter) = when (filter.protocol) {
    ProtocolType.RDP -> Icons.Outlined.DesktopWindows
    ProtocolType.VNC -> Icons.Outlined.Monitor
    ProtocolType.SSH -> Icons.Outlined.Terminal
    ProtocolType.TELNET -> Icons.Outlined.SettingsEthernet
    ProtocolType.RLOGIN -> Icons.Outlined.SettingsEthernet
    ProtocolType.SPICE -> Icons.Outlined.DesktopWindows
    ProtocolType.WEB -> Icons.Outlined.Web
    ProtocolType.REDFISH -> Icons.Outlined.Dns
    ProtocolType.IPMI -> Icons.Outlined.SettingsRemote
    ProtocolType.AMT -> Icons.Outlined.Memory
    ProtocolType.SERIAL_CONSOLE -> Icons.Outlined.SettingsEthernet
    ProtocolType.RESTCONF -> Icons.Outlined.Api
    ProtocolType.SNMP -> Icons.Outlined.NetworkCheck
    ProtocolType.NETCONF -> Icons.Outlined.SettingsRemote
    ProtocolType.GUACAMOLE -> Icons.Outlined.DesktopWindows
    ProtocolType.RTSP -> Icons.Outlined.Videocam
    ProtocolType.MODBUS_TCP -> Icons.Outlined.NetworkCheck
    // PRE-EXISTING GAP FIX: MOSH and PROXMOX had no case here at all (this
    // `when` would not have compiled as delivered) — added now alongside
    // the VIRTUALBOX_VRDE/VMWARE_VSPHERE cases below rather than leaving a
    // known-broken `when` in place.
    ProtocolType.MOSH -> Icons.Outlined.SettingsEthernet
    ProtocolType.PROXMOX -> Icons.Outlined.Dns
    // VIRTUALBOX-VRDE FEATURE (Part 1/N): reuse the RDP/SPICE desktop icon.
    ProtocolType.VIRTUALBOX_VRDE -> Icons.Outlined.DesktopWindows
    // VMWARE-VSPHERE FEATURE (Part 1/N): no dedicated artwork yet — reuse the REDFISH server icon.
    ProtocolType.VMWARE_VSPHERE -> Icons.Outlined.Dns
    null -> Icons.Outlined.GridView
}

// Plain (non-@Composable) label for an individual protocol filter — every
// member of a category below is a real protocol (never ProtocolFilter.ALL),
// so this never needs stringResource. Kept separate from the ALL card's
// label (which *is* localized via R.string.filter_all) the same way the old
// dropdown handled it.
private fun protocolFilterLabel(filter: ProtocolFilter): String = when (filter) {
    ProtocolFilter.ALL -> "All"
    ProtocolFilter.RDP -> "RDP"
    ProtocolFilter.VNC -> "VNC"
    ProtocolFilter.SSH -> "SSH"
    ProtocolFilter.TELNET -> "Telnet"
    ProtocolFilter.RLOGIN -> "Rlogin"
    ProtocolFilter.SPICE -> "SPICE"
    ProtocolFilter.WEB -> "Web"
    ProtocolFilter.REDFISH -> "Redfish"
    ProtocolFilter.IPMI -> "IPMI"
    ProtocolFilter.AMT -> "Intel AMT"
    ProtocolFilter.RESTCONF -> "RESTCONF"
    ProtocolFilter.SNMP -> "SNMP"
    ProtocolFilter.NETCONF -> "NETCONF"
    ProtocolFilter.GUACAMOLE -> "Guacamole"
    ProtocolFilter.RTSP -> "RTSP"
    ProtocolFilter.MODBUS_TCP -> "Modbus TCP"
    ProtocolFilter.MOSH -> "Mosh"
    ProtocolFilter.PROXMOX -> "Proxmox API"
    ProtocolFilter.VIRTUALBOX_VRDE -> "VirtualBox VRDE"
    ProtocolFilter.VMWARE_VSPHERE -> "VMware vSphere"
    ProtocolFilter.SERIAL_CONSOLE -> "Serial Console"
}

// CATEGORY-FILTER-REDESIGN FEATURE: groups the flat ProtocolFilter entries
// into the small set of categories a user actually thinks in (reg spec:
// "Desktop & Remote Access", "Terminal", "Server Management", ...) instead
// of a single alphabet-soup list. Deliberately only groups protocols that
// already have a real ProtocolFilter/ProtocolType entry in this app — it
// does not invent filter states for protocols the connection list doesn't
// actually support yet (e.g. WebDAV/SMB are transport-only QuickTransferType
// flows, not saved RdpProfile.protocolType values, so they have nothing to
// filter here). Serial Console used to be excluded
// here too (ProtocolType had it, but ProtocolFilter never grew a matching
// case) — that gap is now closed, so it's grouped under Terminal alongside
// SSH/Telnet/Rlogin/Mosh. It still has no dedicated EmptyState copy of its
// own (falls through to the generic ALL empty state via that `when`'s
// `else` branch), same as RESTCONF/SNMP/NETCONF/GUACAMOLE.
private data class ProtocolCategoryDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val members: List<ProtocolFilter>,
)

@Composable
private fun protocolCategoryDefs(): List<ProtocolCategoryDef> = listOf(
    ProtocolCategoryDef(
        id       = "desktop",
        title    = stringResource(R.string.filter_category_desktop_title),
        subtitle = stringResource(R.string.filter_category_desktop_subtitle),
        icon     = Icons.Outlined.DesktopWindows,
        // VIRTUALBOX-VRDE FEATURE (Part 1/N): VRDE is a framebuffer protocol
        // routed through the same RdpRemoteAdapter path as RDP (see
        // ProtocolType.VIRTUALBOX_VRDE's doc comment) — belongs here, not
        // under "Server Management" with the other VM-host protocols.
        // BUGFIX: ProtocolFilter.RTSP had an icon (protocolFilterIcon) and a
        // label (protocolFilterLabel) but was never listed in ANY category's
        // `members` here — it was a real, filterable protocol that could
        // never actually be reached from the new grouped Category Filter UI.
        // RTSP is a video/streaming protocol (camera feeds) — same visual
        // bucket as RDP/VNC/SPICE/Guacamole/VRDE — so it's added here.
        members  = listOf(ProtocolFilter.RDP, ProtocolFilter.VNC, ProtocolFilter.SPICE, ProtocolFilter.GUACAMOLE, ProtocolFilter.VIRTUALBOX_VRDE, ProtocolFilter.RTSP),
    ),
    ProtocolCategoryDef(
        id       = "terminal",
        title    = stringResource(R.string.filter_category_terminal_title),
        subtitle = stringResource(R.string.filter_category_terminal_subtitle),
        icon     = Icons.Outlined.Terminal,
        // MOSH FEATURE — pre-existing gap fix: Mosh is a terminal/shell
        // protocol (SSH-bootstrapped), belongs alongside SSH/Telnet/Rlogin.
        // SERIAL-CONSOLE FEATURE — pre-existing gap fix: same bucket, a
        // plain-TCP/RFC-2217 terminal session like Telnet/Rlogin.
        members  = listOf(ProtocolFilter.SSH, ProtocolFilter.TELNET, ProtocolFilter.RLOGIN, ProtocolFilter.MOSH, ProtocolFilter.SERIAL_CONSOLE),
    ),
    ProtocolCategoryDef(
        id       = "server",
        title    = stringResource(R.string.filter_category_server_title),
        subtitle = stringResource(R.string.filter_category_server_subtitle),
        icon     = Icons.Outlined.Dns,
        // PROXMOX-API / VMWARE-VSPHERE FEATURES: both are hypervisor/VM-host
        // management APIs, same bucket as Redfish/IPMI/AMT.
        members  = listOf(ProtocolFilter.REDFISH, ProtocolFilter.IPMI, ProtocolFilter.AMT, ProtocolFilter.SNMP, ProtocolFilter.MODBUS_TCP, ProtocolFilter.PROXMOX, ProtocolFilter.VMWARE_VSPHERE),
    ),
    ProtocolCategoryDef(
        id       = "web",
        title    = stringResource(R.string.filter_category_web_title),
        subtitle = stringResource(R.string.filter_category_web_subtitle),
        icon     = Icons.Outlined.Web,
        members  = listOf(ProtocolFilter.WEB),
    ),
    ProtocolCategoryDef(
        id       = "network",
        title    = stringResource(R.string.filter_category_network_title),
        subtitle = stringResource(R.string.filter_category_network_subtitle),
        icon     = Icons.Outlined.Api,
        members  = listOf(ProtocolFilter.RESTCONF, ProtocolFilter.NETCONF),
    ),
)

// QUICK-TRANSFER FEATURE: picks which standalone file-transfer dialog to
// show. Intentionally separate from ProtocolType/ProtocolFilter above — FTP/
// SMB/WebDAV/TFTP here are quick, unsaved transfer sessions (FtpTransferDialog
// etc. in their own *TransferScreen.kt files), not RDP/VNC/SSH connection
// profiles, so they never touch the existing `when` blocks over ProtocolType.
private enum class QuickTransferType {
    FTP, SMB, WEBDAV, TFTP, NFS,
}

// GRID-VIEW FEATURE: two-finger pinch to change how many cards sit side by
// side. Only reacts once a *second* pointer is down, and only ever consumes
// those 2+-pointer events — a plain one-finger touch (the normal scroll /
// long-press-to-reorder / tap-to-connect gesture) is never intercepted, so
// scrolling the list and the existing drag-to-reorder gesture both keep
// working exactly as before at columnCount == 1.
// GRID-VIEW FEATURE: a single flattened list mixing section headers (which
// must span every column) and profile cards (which occupy one column each)
// is what LazyVerticalGrid needs in order to render a spanning header in the
// middle of a scrollable grid — see the `else` (columnCount > 1) branch below.
private sealed class GridListEntry {
    data class SectionHeader(val key: String, val textRes: Int) : GridListEntry()
    data class ProfileEntry(val profile: RdpProfile) : GridListEntry()
}

private fun Modifier.pinchToResizeCards(
    onSpreadApart: () -> Unit,  // fingers moving apart  → fewer, bigger cards
    onPinchIn:     () -> Unit,  // fingers moving closer → more, smaller cards
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        var zoomAccum = 1f
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.size >= 2) {
                zoomAccum *= event.calculateZoom()
                when {
                    zoomAccum > 1.15f -> { onSpreadApart(); zoomAccum = 1f }
                    zoomAccum < 0.87f -> { onPinchIn();     zoomAccum = 1f }
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState  by viewModel.uiState.collectAsStateWithLifecycle()
    // FOLDERS-UI FEATURE: visibleProfiles/allTags already existed on the
    // ViewModel (folder+tag filtering logic was fully implemented) but nothing
    // in HomeScreen ever collected them, so the "All" list never actually
    // reflected the current folder/tag filter.
    val visibleProfiles by viewModel.visibleProfiles.collectAsStateWithLifecycle()
    val allTags         by viewModel.allTags.collectAsStateWithLifecycle()
    // PIN-CONNECTION FEATURE: multi-selection state — selectedProfileIds/
    // isSelectionMode already existed fully implemented on MainViewModel
    // (toggle/bulk-pin/bulk-unpin/clear), same situation visibleProfiles was
    // in before FOLDERS-UI FEATURE wired it up above — nothing in HomeScreen
    // ever collected them, so the selection toolbar/checkboxes below are what
    // actually makes the feature reachable.
    val selectedProfileIds by viewModel.selectedProfileIds.collectAsStateWithLifecycle()
    val isSelectionMode    by viewModel.isSelectionMode.collectAsStateWithLifecycle()
    val context            = LocalContext.current
    val haptics            = LocalHapticFeedback.current
    val sound              = LocalSoundManager.current
    val scope              = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Feature-05: live session tabs
    val sessionTabs  by viewModel.sessionTabs.collectAsStateWithLifecycle()
    // CONNECTION-STATUS-INDICATOR FEATURE: per-profile live status badge data
    // (see MainViewModel.cardStatuses' doc comment) — collected once here,
    // same as sessionTabs right above, and passed down to both the List and
    // Grid card call sites below.
    val cardStatuses by viewModel.cardStatuses.collectAsStateWithLifecycle()

    var showAddDialog    by remember { mutableStateOf(false) }
    // IA CHANGE: the + button now opens this small chooser first (New vs.
    // Import) instead of jumping straight into ProfileFormDialog — see
    // AddOptionsDialog below.
    var showAddOptions   by remember { mutableStateOf(false) }
    var editingProfile   by remember { mutableStateOf<RdpProfile?>(null) }
    var deletingProfile  by remember { mutableStateOf<RdpProfile?>(null) }
    // HOME-SCREEN-SHORTCUTS FEATURE: profile currently shown in the
    // "Create Home Screen Shortcut" naming dialog (see CreateShortcutDialog).
    var shortcutProfile  by remember { mutableStateOf<RdpProfile?>(null) }
    // QR-SHARE FEATURE: which profile (if any) the "Share via QR" dialog is
    // currently open for. Same pattern as shortcutProfile right above.
    var shareQrProfile   by remember { mutableStateOf<RdpProfile?>(null) }
    // UX FIX: was plain `remember` — an active protocol filter was silently
    // cleared back to ALL on rotation or process death, unlike the search
    // query right below it (see its BUGFIX-UI comment) which already
    // survives this. ProtocolFilter is a plain enum, so it round-trips
    // through the saved-instance-state Bundle with no extra Saver needed.
    var activeFilter     by rememberSaveable { mutableStateOf(ProtocolFilter.ALL) }
    // PROTOCOL-FILTER-DECLUTTER: the category filter popup used to always
    // list every protocol the app supports, even ones the user has never
    // added a single connection for — pure noise for anyone who, say, only
    // has RDP/SSH connections but still saw VNC/SPICE/Guacamole/Redfish/etc.
    // in the picker. Derived from the *full* profile set (not
    // visibleProfiles), so switching folders/tags doesn't make protocol
    // options flicker in and out — this only reflects what protocols exist
    // across all saved connections, same scope allTags already uses.
    val usedProtocols = remember(uiState.profiles) {
        uiState.profiles.map { it.protocolType }.toSet()
    }
    // FILTER-COUNTS + SORT-BY-USAGE: how many saved connections use each
    // protocol — drives the count badges in the Category Filter popup *and*
    // lets each category order its chips by actual usage (most-used
    // protocol first) instead of a fixed declaration order.
    val protocolCounts = remember(uiState.profiles) {
        uiState.profiles.groupingBy { it.protocolType }.eachCount()
    }
    // If the connection that was the last one of its protocol type gets
    // deleted (or edited to a different protocol) while that protocol is
    // the active filter, the filter would otherwise keep pointing at a
    // protocol with zero matching connections *and* zero entries in the
    // popup to select it back out of. Fall back to "All" automatically.
    LaunchedEffect(usedProtocols) {
        val filterProtocol = activeFilter.protocol
        if (filterProtocol != null && filterProtocol !in usedProtocols) {
            activeFilter = ProtocolFilter.ALL
        }
    }
    // RECENT-FILTER FEATURE: a lightweight, UI-only facet (no ViewModel/DB
    // changes) that narrows the list to the most recently connected
    // profiles — same tier as favoritesOnly, but doesn't need to survive
    // reinstall/backup, so a local rememberSaveable is enough.
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    val hasRecentConnections = remember(uiState.profiles) {
        uiState.profiles.any { it.lastConnected > 0L }
    }
    // Once the popup's "Recent" card no longer has anything to show (every
    // qualifying connection got deleted), don't leave the list silently
    // stuck filtered on a now-meaningless facet.
    LaunchedEffect(hasRecentConnections) {
        if (recentOnly && !hasRecentConnections) recentOnly = false
    }

    // EMPTY-FOLDER-SUGGESTION FEATURE (uses deletingFolder + snackbarHostState,
    // both declared further below) — see the LaunchedEffect right after
    // snackbarHostState's declaration for the actual watcher.
    var showQuickConnect by remember { mutableStateOf(false) }
    // QUICK-TRANSFER FEATURE: entry point for the 4 standalone file-transfer
    // dialogs (Ftp/Smb/WebDav/TftpTransferDialog). Deliberately kept outside
    // ProtocolType/RdpProfile — these are one-off transfer sessions, not
    // saved connection profiles, so they get their own tiny picker sheet
    // instead of any changes to the existing session/profile system.
    var showQuickTransferPicker   by remember { mutableStateOf(false) }
    var activeQuickTransferDialog by remember { mutableStateOf<QuickTransferType?>(null) }
    // ENTRA-ID-AUTH FEATURE: observed by HomeDialogs to disable/spin the
    // "Sign in with Microsoft" button while a sign-in is in flight — see
    // MainViewModel.entraSignInPending's doc comment.
    val entraSignInPending by viewModel.entraSignInPending.collectAsStateWithLifecycle()
    // Stable id for whichever profile is currently being *created* (not yet
    // saved) in the "add new connection" dialog — see ProfileFormDialog's
    // pendingProfileId doc comment for why this can't just be minted at
    // Save time. Regenerated every time that dialog is (re)opened so a
    // sign-in from a previous, abandoned "new connection" attempt never
    // gets attributed to the next one.
    var newProfilePendingId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    LaunchedEffect(showAddDialog) {
        if (showAddDialog) newProfilePendingId = UUID.randomUUID().toString()
    }
    // UX-04: text search — hidden by default, revealed by pull-down gesture
    // BUGFIX-UI: was plain `remember` — an in-progress search query was lost
    // if Android killed the app process in the background.
    var searchQuery      by rememberSaveable { mutableStateOf("") }
    var searchVisible    by remember { mutableStateOf(false) }
    // GRID-VIEW FEATURE: how many cards sit side-by-side. 1 = the original
    // full-detail, single-column, drag-to-reorder list (unchanged behavior).
    // 2-4 switch to the compact grid tile (GridProfileCard) — changed via
    // pinch-to-zoom on the list itself or the size button in the filter row.
    // Capped at 4 so the smallest size never packs in fewer than 3 per row.
    var columnCount by rememberSaveable { mutableStateOf(1) }
    // FOLDERS-UI FEATURE: dialog state for the folder management UI added below.
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renamingFolder      by remember { mutableStateOf<ConnectionFolder?>(null) }
    var deletingFolder      by remember { mutableStateOf<ConnectionFolder?>(null) }
    // GUEST-MODE FEATURE: gates the re-authenticated "Switch to Primary
    // Profile" action (see GuestModeBanner + SecurityConfirmDialog below).
    var showSwitchToPrimaryDialog by remember { mutableStateOf(false) }

    // ── Import .rdp file ──────────────────────────────────────────────────────
    val pendingImport by viewModel.pendingImportProfile.collectAsStateWithLifecycle()
    // BUG #1 FIX: collect importError and show it as a Snackbar
    val importError   by viewModel.importError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // EMPTY-FOLDER-SUGGESTION FEATURE: watches per-folder connection counts
    // and offers to delete a folder right after it's emptied out (last
    // connection removed or moved elsewhere) — reuses the existing
    // deletingFolder confirmation dialog rather than deleting directly.
    // Deliberately fires only on a *transition* into empty (previous count
    // tracked and > 0), never for a folder that's simply new/still empty.
    val folderProfileCounts = remember(uiState.profiles) {
        uiState.profiles.mapNotNull { it.folderId }
            .groupingBy { it }
            .eachCount()
    }
    val previousFolderCounts = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(folderProfileCounts, uiState.folders) {
        for (folder in uiState.folders) {
            val previousCount = previousFolderCounts[folder.id]
            val currentCount  = folderProfileCounts[folder.id] ?: 0
            if (previousCount != null && previousCount > 0 && currentCount == 0) {
                val result = snackbarHostState.showSnackbar(
                    message     = context.getString(R.string.folder_empty_suggest_delete, folder.name),
                    actionLabel = context.getString(R.string.delete),
                    duration    = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    deletingFolder = folder
                }
            }
        }
        previousFolderCounts.clear()
        previousFolderCounts.putAll(folderProfileCounts)
    }
    LaunchedEffect(importError) {
        val msg = importError
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearPendingImport()
        }
    }
    // ── Scan QR Code ────────────────────────────────────────────────────────
    // Mirrors the .rdp import collection immediately above.
    val pendingQr by viewModel.pendingQrProfile.collectAsStateWithLifecycle()
    val qrError   by viewModel.qrError.collectAsStateWithLifecycle()
    LaunchedEffect(qrError) {
        val msg = qrError
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearPendingQr()
        }
    }
    // CRIT-4 FIX: Show profile save Keystore errors (CRIT-1) as a Snackbar.
    // addProfile() and updateProfile() emit to profileSaveError when CryptoHelper.encrypt()
    // throws SecurityException. Without this LaunchedEffect the error was silently dropped
    // and the user had no feedback that their profile was NOT saved.
    val profileSaveError by viewModel.profileSaveError.collectAsStateWithLifecycle()
    LaunchedEffect(profileSaveError) {
        val msg = profileSaveError
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearProfileSaveError()
        }
    }
    // FIX L1 / FIX-i18n: Show Wake-on-LAN success / error as a localised Snackbar.
    // wolResult is now Boolean? (true=success, false=error) so we resolve the
    // correct string resource here rather than relying on a hardcoded English string
    // from the ViewModel.
    val wolResult by viewModel.wolResult.collectAsStateWithLifecycle()
    val wolSentText  = stringResource(R.string.wol_sent)
    val wolErrorText = stringResource(R.string.wol_error)
    LaunchedEffect(wolResult) {
        val success = wolResult
        if (success != null) {
            snackbarHostState.showSnackbar(if (success) wolSentText else wolErrorText)
            viewModel.clearWolResult()
        }
    }

    // ── Wake & Connect ──────────────────────────────────────────────────────────
    // Shared helper so both a normal "Connect" tap and the tail end of a
    // successful Wake & Connect launch the session Activity exactly the same
    // way (same MAX_TABS guard, same Intent shape).
    fun launchConnect(profile: RdpProfile) {
        // WAKE-ON-LAN-STANDALONE FEATURE: a WAKE_ON_LAN profile has no session
        // to open at all — "Connect" IS the action (send the Magic Packet),
        // so short-circuit here before the MAX_TABS/SessionLauncher machinery
        // below, which exists for actual sessions (RDP/SSH/VNC/...) taking up
        // a tab slot. Reuses the exact same MainViewModel.sendWakeOnLan(profile)
        // call — and the wolResult Snackbar wired up just above — that the
        // per-profile "Wake on LAN" menu action (onWakeOnLan) already uses for
        // every other protocol's wake-before-connect add-on.
        if (profile.protocolType == ProtocolType.WAKE_ON_LAN) {
            viewModel.sendWakeOnLan(profile)
            return
        }
        if (sessionTabs.size >= SessionTabManager.MAX_TABS) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.error_max_sessions, SessionTabManager.MAX_TABS)
                )
            }
        } else {
            // WEB-PORTAL FEATURE: SessionLauncher picks WebPortalActivity for
            // ProtocolType.WEB profiles, RdpSessionActivity for every other
            // protocol — see its doc comment.
            com.systemsgo.hex.remote.SessionLauncher.launch(context, profile)
        }
    }

    val wakeConnectState by viewModel.wakeConnectState.collectAsStateWithLifecycle()
    // Progress UI (Sending… / Waiting… / Online / Connecting / Failed) is a
    // small bottom-sheet-style dialog — see WakeConnectProgressDialog below —
    // driven purely off this state, so it can never block or freeze the rest
    // of the screen even if the network side is slow.
    WakeConnectProgressDialog(
        state         = wakeConnectState,
        profileName   = uiState.profiles.firstOrNull { it.id == (wakeConnectState.profileIdOrNull()) }?.name,
        onDismiss     = { viewModel.resetWakeConnectState() },
        onCancel      = { viewModel.cancelWakeAndConnect() },
    )
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.parseRdpUri(it, context.contentResolver) }
    }

    // ── Scan QR Code ────────────────────────────────────────────────────────
    // QR-SCANNER-REDESIGN: QrScannerActivity is our own CameraX + ML Kit
    // screen (square viewfinder, front/back flip with auto-fallback, and
    // "import from gallery") — see QrScannerActivity.kt. It hands back the
    // decoded text as a plain string extra, the same shape zxing's
    // ScanContract used to return, so parseQrContent() below needed no
    // changes at all.
    val qrScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val contents = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_CONTENT)
            if (!contents.isNullOrBlank()) {
                viewModel.parseQrContent(contents)
            }
        }
    }
    val cameraPermissionDeniedText = stringResource(R.string.error_camera_permission_denied)
    // GUEST-MODE FEATURE
    val guestSettingsBlockedText = stringResource(R.string.guest_mode_settings_blocked)
    fun launchQrScanner() {
        qrScanLauncher.launch(QrScannerActivity.intent(context))
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchQrScanner()
        } else {
            scope.launch { snackbarHostState.showSnackbar(cameraPermissionDeniedText) }
        }
    }
    // UX-03: drag-to-reorder local list
    // PERF-FIX: reorderableList is now reordered LIVE as the finger crosses a
    // neighbour's midpoint (not just once on release). Combined with
    // Modifier.animateItem() on every non-dragged card, the cards that make
    // way now slide smoothly into their new slot instead of snapping when the
    // drag ends. Items are tracked by stable `id`, not by index, since the
    // index of the dragged item keeps changing while the list is live-sorted.
    var reorderableList  by remember { mutableStateOf<List<RdpProfile>>(emptyList()) }
    var isDragging       by remember { mutableStateOf(false) }
    var draggingId        by remember { mutableStateOf<String?>(null) }
    var dragOffsetY       by remember { mutableStateOf(0f) }
    // heights of each card, keyed by stable profile id (survives reordering,
    // unlike an index-keyed map which would go stale the moment items swap)
    val itemHeightsById   = remember { mutableStateMapOf<String, Float>() }

    // DRAG-TO-FOLDER FEATURE: while a card is being dragged, we need to know
    // whether its current on-screen position overlaps one of the folder
    // chips pinned at the top (FolderFilterRow), so it can be dropped there
    // to move the connection into that folder — entirely separate from the
    // drag-to-reorder math above, which only ever operates on positions
    // *within* the list itself.
    // itemRootPositionsById/itemWidthsById: each card's natural (untranslated)
    // top-left position and width in root/window coordinates, refreshed on
    // every layout pass (including scroll), so they stay valid as the list
    // scrolls or reorders around the dragged card.
    val itemRootPositionsById = remember { mutableStateMapOf<String, Offset>() }
    val itemWidthsById        = remember { mutableStateMapOf<String, Float>() }
    // folderPillBoundsById: each visible folder chip's bounds in the same
    // root coordinate space, reported by FolderPill itself and cleaned up
    // automatically when a chip scrolls out of the LazyRow and is disposed.
    val folderPillBoundsById  = remember { mutableStateMapOf<String, Rect>() }
    // The folder chip currently under the dragged card, if any — drives both
    // the light glow on that chip and what onDragEnd does with the drop.
    var hoveredFolderId       by remember { mutableStateOf<String?>(null) }

    // DROP-INTO-FOLDER LANDING ANIMATION: when a card is released over a
    // folder chip, it no longer just vanishes — it flies from wherever it
    // was released, shrinking and fading, straight into the chip it was
    // dropped on, then the chip itself gives a little "received" pulse.
    // isLanding/landingProfileId identify which card (if any) is currently
    // mid-flight; the Animatables below drive its translation/scale/alpha
    // manually (same graphicsLayer slot the reorder-drag normally uses),
    // and the real move only happens in the ViewModel once the flight
    // animation finishes, so the list update and the visual landing always
    // stay in sync.
    var isLanding             by remember { mutableStateOf(false) }
    var landingProfileId      by remember { mutableStateOf<String?>(null) }
    val landingOffsetX        = remember { Animatable(0f) }
    val landingOffsetY        = remember { Animatable(0f) }
    val landingScale          = remember { Animatable(1f) }
    val landingAlpha          = remember { Animatable(1f) }
    // Which folder chip just "received" a card — briefly true right as the
    // flight lands, so the chip can pulse/glow green to acknowledge it.
    var receivingFolderId     by remember { mutableStateOf<String?>(null) }
    val landingScope          = rememberCoroutineScope()

    // DRAG-TO-PIN FEATURE: same idea as the folder-chip drop above, but for
    // the bottom navigation bar — dragging a card down onto it pins a Home
    // screen shortcut for that connection instead of reordering/refiling it.
    // bottomBarBounds is reported once by SpaceBottomBar itself (its bounds
    // don't depend on scroll, so this is simpler than the per-card map above).
    var bottomBarBounds        by remember { mutableStateOf<Rect?>(null) }
    var isDraggedOverBottomBar by remember { mutableStateOf(false) }

    // UX-05: Subscribe dialogs removed — they were opening Telegram on every
    // 3-day interval which felt like spam and harmed Store ratings.
    // UI-AUDIT FIX: this used to just silently call dismissFirstLaunchDialog()
    // with nothing shown to the user. WelcomeOverlay (see that file) is now
    // rendered below and dismissFirstLaunchDialog() is only called once the
    // user actually taps "Get Started" — matching GestureHintsOverlay's
    // markGestureHintsShown() pattern in RdpSessionActivity.kt.

    // UI-POLISH: count home-screen visits once per entry so the full text swipe-hint
    // banner can fade away automatically after the user has seen it a few times,
    // instead of repeating the same instruction forever on every card.
    LaunchedEffect(Unit) {
        viewModel.markHomeScreenOpened(uiState.settings.homeScreenOpenCount)
    }

    // IA CHANGE: entry point for the + button — offers "New connection" or
    // "Import .rdp file" instead of the bar carrying a separate permanent
    // Import icon. Picking an option here just flips the flag that already
    // drives the existing dialog/launcher below, so nothing about how those
    // actually work has changed.
    // PERF-FIX (recomposition scope): these six dialog triggers used to be six
    // separate inline conditional blocks sitting directly in HomeScreen's own
    // top-level body — the same scope that reads `searchQuery` a few lines
    // down to compute `filtered`. That meant every keystroke in the (rarely
    // used) search bar forced Compose to re-walk all six conditions here too.
    // Bundling them into one HomeDialogs(...) call means Compose can skip the
    // whole thing as a single unit whenever none of *these specific* six
    // values changed, regardless of what else invalidated HomeScreen's scope.
    if (uiState.showFirstLaunchDialog) {
        OnboardingScreen(onFinish = { viewModel.dismissFirstLaunchDialog() })
    }

    HomeDialogs(
        showAddOptions      = showAddOptions,
        onDismissAddOptions = { showAddOptions = false },
        onNewConnection     = {
            // ADD-CONNECTION PROTOCOL PICKER (Part 2/2): "New connection" now
            // opens the protocol picker first instead of jumping straight to
            // ProfileFormDialog (initialProtocolType = activeFilter.protocol
            // below is still used by the OTHER add-new-profile entry points —
            // import/QR/discovery — which don't need a protocol choice first).
            showAddOptions = false
            navController.navigate("add_connection_protocol")
        },
        onImportFile        = {
            showAddOptions = false
            importLauncher.launch(
                // IMPORT-FIX (was BUG-6 FIX): the previous list also included "*/*" as
                // a "fallback", but ACTION_OPEN_DOCUMENT treats EXTRA_MIME_TYPES as an
                // OR filter — including "*/*" alongside the real types silently defeats
                // the whole filter and lets the system picker show (and let the user
                // pick) literally any file again, images included. Dropping "*/*" makes
                // the picker actually hide files with a recognized, unrelated MIME type
                // (photos, PDFs, etc.) as intended. This is still only a first line of
                // defense — some file managers ignore MIME filters entirely — so
                // MainViewModel.parseRdpUri() also does a hard check on the real file
                // name extension after the picker returns, which is what actually
                // guarantees only *.rdp files get imported.
                arrayOf("application/x-rdp", "application/octet-stream")
            )
        },
        onScanQr            = {
            showAddOptions = false
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                launchQrScanner()
            } else {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        },
        onDiscoverDevices   = {
            showAddOptions = false
            navController.navigate("discover_devices")
        },
        onWebFeed           = {
            showAddOptions = false
            navController.navigate("webfeed")
        },
        onQuickTransfer     = {
            showAddOptions = false
            showQuickTransferPicker = true
        },
        showQuickTransferPicker    = showQuickTransferPicker,
        onDismissQuickTransferPicker = { showQuickTransferPicker = false },
        onSelectQuickTransfer      = { type ->
            showQuickTransferPicker = false
            activeQuickTransferDialog = type
        },
        activeQuickTransferDialog    = activeQuickTransferDialog,
        onDismissQuickTransferDialog = { activeQuickTransferDialog = null },
        showAddDialog       = showAddDialog,
        // BUGFIX #4: open the form pre-selected on whichever protocol tab
        // (RDP/VNC/SSH) the user currently has active, instead of always RDP.
        initialProtocolType = activeFilter.protocol,
        onDismissAddDialog  = { showAddDialog = false },
        onSaveNewProfile    = { profile -> viewModel.addProfile(profile); showAddDialog = false },
        pendingImport       = pendingImport,
        onDismissImport     = { viewModel.clearPendingImport() },
        onSaveImport        = { profile -> viewModel.addProfile(profile); viewModel.clearPendingImport() },
        pendingQr           = pendingQr,
        onDismissQr         = { viewModel.clearPendingQr() },
        onSaveQr            = { profile -> viewModel.addProfile(profile); viewModel.clearPendingQr() },
        showQuickConnect    = showQuickConnect,
        onDismissQuickConnect = { showQuickConnect = false },
        onQuickConnect      = { host, port, username, password ->
            showQuickConnect = false
            // FIX: same MAX_TABS guard as the profile card onConnect.
            if (sessionTabs.size >= SessionTabManager.MAX_TABS) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.error_max_sessions, SessionTabManager.MAX_TABS)
                    )
                }
            } else {
                // FIX #1 (Security): Store credentials in memory-only cache and pass
                // a one-time token instead of the plaintext password. Intent extras are
                // visible via `adb shell dumpsys activity` and appear in bug reports.
                val token = com.systemsgo.hex.security.QuickConnectCache.put(
                    host, port, username, password
                )
                val intent = android.content.Intent(context, RdpSessionActivity::class.java)
                    .putExtra("profile_id", "__quick__")
                    .putExtra("quick_token", token)
                context.startActivity(intent)
            }
        },
        editingProfile      = editingProfile,
        onDismissEdit       = { editingProfile = null },
        onSaveEdit          = { updated -> viewModel.updateProfile(updated); editingProfile = null },
        deletingProfile     = deletingProfile,
        onDismissDelete     = { deletingProfile = null },
        onConfirmDelete     = { profile -> viewModel.deleteProfile(profile); deletingProfile = null },
        shortcutProfile     = shortcutProfile,
        onDismissShortcut   = { shortcutProfile = null },
        onConfirmShortcut   = { profile, shortcutName ->
            val pinned = com.systemsgo.hex.util.ShortcutHelper.requestPinShortcut(context, profile, shortcutName)
            shortcutProfile = null
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(
                        if (pinned) R.string.pin_shortcut_requested else R.string.pin_shortcut_unsupported
                    )
                )
            }
        },
        // ENTRA-ID-AUTH FEATURE: `context as? Activity` is null in Preview/
        // non-Activity hosts, in which case sign-in silently does nothing
        // rather than crashing — same fail-safe shape as the camera/QR
        // permission checks above.
        entraSignInPending  = entraSignInPending,
        newProfilePendingId = newProfilePendingId,
        onSignInWithMicrosoft = { profileId ->
            (context as? Activity)?.let { activity -> viewModel.signInWithMicrosoft(activity, profileId) }
        },
        onSignOutMicrosoft    = { profileId -> viewModel.signOutMicrosoft(profileId) },
    )
    // QR-SHARE FEATURE: rendered directly here (rather than threaded through
    // HomeDialogs' already-large param list) since it only needs the one
    // profile + a dismiss callback, same self-contained shape as
    // ShareConnectionQrDialog itself.
    shareQrProfile?.let { profile ->
        ShareConnectionQrDialog(
            profile   = profile,
            onDismiss = { shareQrProfile = null },
        )
    }
    FolderDialogs(
        showNewFolderDialog = showNewFolderDialog,
        onDismissNewFolder  = { showNewFolderDialog = false },
        // FOLDER-APPEARANCE FEATURE: color/icon are now reported alongside
        // the name — see MainViewModel.createFolder's doc comment.
        onCreateFolder      = { name, color, icon -> viewModel.createFolder(name, color, icon); showNewFolderDialog = false },
        renamingFolder      = renamingFolder,
        onDismissRename     = { renamingFolder = null },
        onConfirmRename     = { folder, newName, color, icon -> viewModel.updateFolderAppearance(folder, newName, color, icon); renamingFolder = null },
        deletingFolder      = deletingFolder,
        onDismissDeleteFolder = { deletingFolder = null },
        onConfirmDeleteFolder = { folder -> viewModel.deleteFolder(folder); deletingFolder = null },
    )

    // GUEST-MODE FEATURE: "Switch to Primary Profile" requires successful
    // re-authentication — reuses the exact same PIN/biometric confirmation
    // already used to disable the lock from Settings (SecurityConfirmDialog),
    // verified against the REAL primary settings (viewModel.settingsState),
    // never the Guest-overridden uiState.settings shown while Guest is active.
    if (showSwitchToPrimaryDialog) {
        val primarySettings by viewModel.settingsState.collectAsStateWithLifecycle()
        SecurityConfirmDialog(
            pinLockEnabled       = primarySettings.pinLockEnabled,
            biometricLockEnabled = primarySettings.biometricLockEnabled,
            encryptedPin         = primarySettings.pinCode,
            onConfirmed = {
                showSwitchToPrimaryDialog = false
                viewModel.exitGuestMode()
            },
            onDismiss = { showSwitchToPrimaryDialog = false }
        )
    }

    // ── Filtered list (UX-04: includes text search) ──────────────────────────
    // FOLDERS-UI FEATURE: starts from visibleProfiles (folder + tag filter
    // already applied by the ViewModel) instead of the raw uiState.profiles,
    // then layers the protocol tab + search filters on top exactly as before.
    val filtered = remember(visibleProfiles, activeFilter, searchQuery, recentOnly) {
        visibleProfiles
            .let { list ->
                if (activeFilter.protocol == null) list
                else list.filter { it.protocolType == activeFilter.protocol }
            }
            .let { list ->
                if (searchQuery.isBlank()) list
                else list.filter { p ->
                    // I18N-FIX: normalize Arabic-Indic/Extended Arabic-Indic
                    // digits on both sides so searching by host/IP or port
                    // matches regardless of which digit style was used to
                    // type the query or to store the profile.
                    val normalizedQuery = searchQuery.normalizeDigits()
                    p.name.normalizeDigits().contains(normalizedQuery, ignoreCase = true) ||
                    p.host.normalizeDigits().contains(normalizedQuery, ignoreCase = true) ||
                    // SEARCH-TAGS FIX: the search box previously only looked
                    // at name/host, so a connection organized purely by tag
                    // (e.g. "production") was invisible to search even
                    // though that same tag is filterable from the Category
                    // Filter popup. No digit normalization needed here —
                    // tags are free-text labels, not addresses/ports.
                    p.tags.any { it.contains(normalizedQuery, ignoreCase = true) }
                }
            }
            .let { list ->
                // RECENT-FILTER FEATURE: layered on top of everything else,
                // same as favoritesOnly conceptually — narrows to profiles
                // that have actually been connected to at least once,
                // ordered most-recent-first, capped at RECENT_FILTER_LIMIT.
                if (!recentOnly) list
                else list
                    .filter { it.lastConnected > 0L }
                    .sortedByDescending { it.lastConnected }
                    .take(RECENT_FILTER_LIMIT)
            }
    }

    // UX-03: keep local reorderable list in sync with filtered
    LaunchedEffect(filtered) {
        if (!isDragging) reorderableList = filtered
    }

    StarfieldBackground(isDark = uiState.settings.isDarkMode, modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            // ── No top bar — removed per requirements ──────────────────────
            // BUG #1 FIX: host the snackbar that shows import errors
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                SpaceBottomBar(
                    hapticEnabled      = uiState.settings.hapticFeedback,
                    activeSessionCount = sessionTabs.size,
                    onSettingsClick    = {
                        // GUEST-MODE FEATURE: Settings (and, transitively,
                        // Connection History, which is only reachable from
                        // within Settings) exposes primary-only data —
                        // never navigable while the Guest profile is active.
                        if (uiState.isGuestMode) {
                            scope.launch { snackbarHostState.showSnackbar(guestSettingsBlockedText) }
                        } else {
                            navController.navigate("settings")
                        }
                    },
                    onAddClick         = {
                        if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.5f)
                        // IA CHANGE (v2): the intermediate "New / Import / Scan QR" chooser
                        // sheet added an extra tap in front of the most common action for no
                        // benefit, so the + button now jumps straight to the protocol picker
                        // — same destination the empty-state "add" button already used (see
                        // onAddClick a few dozen lines below in the EmptyState() call).
                        // Import file and Scan QR are no longer separate chooser rows; they
                        // now live as icon actions directly on that picker screen's top bar
                        // (see AddConnectionProtocolScreen's AddConnectionTopBar). Discover
                        // Devices / Web Feed / Quick Transfer moved there too, as a compact
                        // quick-actions row — see AddConnectionRoute.kt.
                        navController.navigate("add_connection_protocol")
                    },
                    onAddLongClick     = {
                        if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.5f)
                        showQuickConnect = true
                    },
                    onSessionsClick    = {
                        if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.5f)
                        navController.navigate("sessions")
                    },
                    // DRAG-TO-PIN FEATURE
                    isDropTarget    = isDraggedOverBottomBar,
                    onBoundsChanged = { rect -> bottomBarBounds = rect },
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // GUEST-MODE FEATURE: a persistent, unmissable indicator that this is
                // the isolated, temporary Guest profile — never the primary user's
                // real data — plus the only way back: re-authenticated switch to
                // Primary. See MainViewModel.isGuestMode / exitGuestMode().
                if (uiState.isGuestMode) {
                    GuestModeBanner(onSwitchToPrimary = { showSwitchToPrimaryDialog = true })
                }
                // NOTE: the old floating "active sessions" tab strip that used to sit
                // here (top of Home) was removed — it rendered as a cramped, clipped
                // badge with no room for the session name and an unreliable × button.
                // Active sessions now live in their own dedicated screen, reachable
                // from the "Sessions" button in the bottom bar (with a live count badge).

                // PIN-CONNECTION FEATURE: multi-selection toolbar. There's no
                // TopAppBar left to Crossfade into (removed per requirements,
                // see the Scaffold above) — this AnimatedVisibility is its
                // replacement, sliding a dedicated bar in above the card-size
                // row the moment isSelectionMode flips true and back out the
                // instant the last card is deselected (isSelectionMode is
                // derived from selectedProfileIds.isNotEmpty() on the
                // ViewModel, so it needs no separate dismiss/back handling).
                AnimatedVisibility(
                    visible = isSelectionMode,
                    enter   = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit    = shrinkVertically(tween(180)) + fadeOut(tween(180))
                ) {
                    SelectionModeToolbar(
                        selectedCount = selectedProfileIds.size,
                        onPinSelected   = { viewModel.bulkPinSelected() },
                        onUnpinSelected = { viewModel.bulkUnpinSelected() },
                        onCancel        = { viewModel.clearSelection() },
                    )
                }

                // ── Search Bar (UX-11) — pull-down to reveal ──────────────
                // Auto-hide on upward scroll handled by nestedScroll below.
                AnimatedVisibility(
                    visible = searchVisible,
                    enter   = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit    = shrinkVertically(tween(180)) + fadeOut(tween(180))
                ) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        SearchBar(
                            query         = searchQuery,
                            onQueryChange = { searchQuery = it },
                            modifier      = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                // ── Card-size control (GRID-VIEW FEATURE) ───────────────────
                // Cycles 1 → 2 → 3 → 4 → 1 cards per row; pinching on the list
                // below does the same thing continuously. Kept on its own row
                // so it never fights the horizontally-scrolling FolderFilterRow
                // for space.
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        text     = if (columnCount == 1) stringResource(R.string.card_size_large) else "${columnCount}×",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = CometTail
                    )
                    IconButton(
                        onClick = {
                            if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            columnCount = if (columnCount >= 4) 1 else columnCount + 1
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Outlined.GridView,
                            contentDescription = stringResource(R.string.card_size_toggle),
                            tint               = CometTail,
                            modifier           = Modifier.size(18.dp)
                        )
                    }
                }
                // ── Folder Filter Row (FOLDERS-UI feature) ─────────────────
                // FILTER-CONSOLIDATION: the protocol filter (formerly its own
                // ProtocolFilterRow) and the tag filter (formerly its own
                // TagFilterRow, shown only once a tag existed) now live
                // behind the trailing funnel icon at the end of this same
                // row, per Progressive Disclosure — this is the one row that
                // stays permanently visible, since folders are the primary,
                // most-used facet. See FilterMenuButton below for details.
                FolderFilterRow(
                    folders                = uiState.folders,
                    selectedFolderId       = uiState.selectedFolderId,
                    onSelectFolder         = { viewModel.selectFolder(it) },
                    onAddFolder            = { showNewFolderDialog = true },
                    onRenameFolder         = { renamingFolder = it },
                    onDeleteFolder         = { deletingFolder = it },
                    activeProtocolFilter   = activeFilter,
                    onSelectProtocolFilter = { activeFilter = it },
                    usedProtocols          = usedProtocols,
                    protocolCounts         = protocolCounts,
                    tags                   = allTags,
                    selectedTag            = uiState.selectedTag,
                    onSelectTag            = { viewModel.selectTag(it) },
                    favoritesOnly          = uiState.showFavoritesOnly,
                    onToggleFavorites      = { viewModel.toggleFavoritesOnly() },
                    recentOnly             = recentOnly,
                    onToggleRecent         = { recentOnly = !recentOnly },
                    hasRecentConnections   = hasRecentConnections,
                    // DRAG-TO-FOLDER FEATURE
                    hoveredFolderId        = hoveredFolderId,
                    onFolderPillBounds     = { folderId, rect ->
                        if (rect != null) folderPillBoundsById[folderId] = rect
                        else folderPillBoundsById.remove(folderId)
                    },
                    // DROP-INTO-FOLDER LANDING FEATURE: pulses the chip that just
                    // "received" a card at the end of its flight animation.
                    receivingFolderId      = receivingFolderId,
                )
                Spacer(Modifier.height(4.dp))

                // ── Content ────────────────────────────────────────────────
                if (uiState.isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SpaceLoadingIndicator()
                    }
                } else if (filtered.isEmpty()) {
                    // CLEAR-FILTERS FEATURE: distinguishes "zero connections
                    // at all" from "zero results because a filter/search/
                    // folder is narrowing them out" — only the latter gets
                    // the secondary "Clear filters" action, since offering
                    // to "clear filters" when there's nothing to clear (a
                    // genuinely empty library) would be confusing.
                    val hasActiveNarrowing = activeFilter != ProtocolFilter.ALL ||
                        uiState.selectedTag != null ||
                        uiState.showFavoritesOnly ||
                        recentOnly ||
                        searchQuery.isNotBlank() ||
                        uiState.selectedFolderId != null
                    EmptyState(
                        modifier      = Modifier.fillMaxSize(),
                        filter        = activeFilter,
                        // ADD-CONNECTION PROTOCOL PICKER (Part 2/2): same entry
                        // point as the + button's "New connection" option above.
                        onAddClick    = { navController.navigate("add_connection_protocol") },
                        hasActiveNarrowing = hasActiveNarrowing,
                        onClearFilters = {
                            activeFilter = ProtocolFilter.ALL
                            recentOnly   = false
                            searchQuery  = ""
                            if (uiState.selectedTag != null) viewModel.selectTag(null)
                            if (uiState.showFavoritesOnly) viewModel.toggleFavoritesOnly()
                            if (uiState.selectedFolderId != null) viewModel.selectFolder(null)
                        }
                    )
                } else {
                    // ── VPN status banner ───────────────────────────────────
                    // VPN-AWARE-CONNECTIVITY: shows the active VPN status
                    // *before* the user taps Connect (requirement: "Display
                    // the active VPN status before connecting"), for any
                    // VpnService-based VPN app — see VpnConnectivityManager.
                    AnimatedVisibility(
                        visible = uiState.vpnStatus.isActive,
                        enter   = expandVertically() + fadeIn(),
                        exit    = shrinkVertically() + fadeOut()
                    ) {
                        VpnStatusBanner(
                            status   = uiState.vpnStatus,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    // ── Network warning banner ─────────────────────────────
                    AnimatedVisibility(
                        visible = uiState.networkQuality == com.systemsgo.hex.ui.NetworkQuality.POOR,
                        enter   = expandVertically() + fadeIn(),
                        exit    = shrinkVertically() + fadeOut()
                    ) {
                        NetworkBanner(modifier = Modifier.padding(horizontal = 16.dp))
                    }

                    // UI-POLISH: one-time swipe-gesture hint, shown as a single banner
                    // (not repeated on every card) and only for the first 3 visits —
                    // replaces the old per-card duplicated hint text.
                    AnimatedVisibility(
                        visible = uiState.settings.homeScreenOpenCount < 3,
                        enter   = fadeIn() + expandVertically(),
                        exit    = fadeOut() + shrinkVertically()
                    ) {
                        SwipeHintBanner(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }

                    // UX-11: pull-down to reveal search bar
                    val nestedScrollConnection = remember {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                // Scrolling UP (negative y) → hide search if query empty
                                if (available.y < -8f && searchQuery.isEmpty()) {
                                    searchVisible = false
                                    keyboardController?.hide() // FIX 1: dismiss keyboard when bar collapses
                                }
                                return Offset.Zero
                            }
                            override fun onPostScroll(
                                consumed: Offset, available: Offset, source: NestedScrollSource
                            ): Offset {
                                // Pull-down at top of list (available.y > 0 after list consumed 0)
                                if (available.y > 8f) {
                                    searchVisible = true
                                }
                                return Offset.Zero
                            }
                        }
                    }

                    // UX-03: Drag-to-reorder list — full pointer-based implementation
                    // PERF-FIX (professional reorder): the list is now re-sorted the
                    // instant the dragged card's centre crosses a neighbour's midpoint
                    // (previously the reorder only happened once, on release, which is
                    // why every other card used to "teleport" — appearing to launch
                    // upward — the moment you touched it). Every non-dragged card now
                    // carries Modifier.animateItem(), so when its index changes it
                    // glides smoothly into its new slot instead of snapping. The
                    // dragged card itself is excluded from animateItem() and is
                    // positioned purely by translationY so it tracks the finger with
                    // zero animation lag.
                    val listState      = rememberLazyListState()
                    val density        = LocalDensity.current
                    val itemSpacingPx  = with(density) { 14.dp.toPx() }
                    val defaultItemHeightPx = with(density) { 90.dp.toPx() }

                    // GRID-VIEW FEATURE: columnCount == 1 keeps the original,
                    // full-detail single-column list below completely
                    // unchanged (drag-to-reorder, drag-to-folder, drag-to-pin
                    // all still work exactly as before). columnCount > 1
                    // switches to the compact multi-column grid further down.
                    val gridModifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(nestedScrollConnection)
                        .pinchToResizeCards(
                            onSpreadApart = { if (columnCount > 1) columnCount -= 1 },
                            onPinchIn     = { if (columnCount < 4) columnCount += 1 }
                        )

                    if (columnCount <= 1) {
                    LazyColumn(
                        state          = listState,
                        modifier       = gridModifier,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // PIN-CONNECTION FEATURE + FAVORITES FEATURE: reorderableList is
                        // always pinned-first, then favorites-first among the rest (see
                        // MainViewModel.visibleProfiles) — pinnedCount is the number of
                        // leading pinned entries, and favoritesCount now only counts
                        // favorites *after* that pinned block (a pinned+favorite profile
                        // lives in the pinned section only, never double-counted here —
                        // matches how visibleProfiles partitions pinned out before ever
                        // sorting by isFavorite). The onDrag clamp below keeps both
                        // boundaries intact while dragging.
                        val pinnedCount    = reorderableList.count { it.isPinned }
                        val favoritesCount = reorderableList.drop(pinnedCount).count { it.isFavorite }

                        reorderableList.forEachIndexed { index, profile ->
                            // ── Section headers ─────────────────────────────────
                            if (index == 0 && pinnedCount > 0) {
                                item(key = "__pinned_header__") {
                                    ConnectionSectionHeader(
                                        icon = Icons.Filled.PushPin,
                                        text = stringResource(R.string.pinned_badge_desc)
                                    )
                                }
                            }
                            if (index == pinnedCount && favoritesCount > 0) {
                                item(key = "__favorites_header__") {
                                    ConnectionSectionHeader(
                                        icon = Icons.Filled.Star,
                                        text = stringResource(R.string.favorites)
                                    )
                                }
                            }
                            if (index == pinnedCount + favoritesCount &&
                                (pinnedCount > 0 || favoritesCount > 0) &&
                                (pinnedCount + favoritesCount) < reorderableList.size) {
                                item(key = "__all_connections_header__") {
                                    ConnectionSectionHeader(
                                        icon = Icons.Outlined.GridView,
                                        text = stringResource(R.string.all_connections)
                                    )
                                }
                            }

                            val isBeingDragged = isDragging && profile.id == draggingId
                            // DROP-INTO-FOLDER LANDING: the flying-into-the-folder card
                            // stays excluded from animateItem() too (same reason as a
                            // reorder-drag — it's positioned manually, not by the list).
                            val isThisCardLanding = isLanding && profile.id == landingProfileId
                            val isPositionedManually = isBeingDragged || isThisCardLanding

                            item(key = profile.id) {
                                AnimatedVisibility(
                                visible  = true,
                                enter    = fadeIn(tween(300)) + slideInVertically(
                                    tween(300, easing = FastOutSlowInEasing)
                                ) { it / 3 },
                                // Cards making way for the dragged one animate their
                                // placement; the dragged/landing card is driven manually
                                // below so the two animation systems never fight each other.
                                modifier = if (isPositionedManually) Modifier else Modifier.animateItem(
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness    = Spring.StiffnessMediumLow
                                    )
                                )
                            ) {
                                ReorderableProfileCard(
                                    profile        = profile,
                                    isDragTarget   = isBeingDragged,
                                    isDropTarget   = false,
                                    dragOffsetY    = if (isBeingDragged) dragOffsetY else 0f,
                                    // DROP-INTO-FOLDER LANDING FEATURE
                                    isLanding      = isThisCardLanding,
                                    landingOffsetX = if (isThisCardLanding) landingOffsetX.value else 0f,
                                    landingOffsetY = if (isThisCardLanding) landingOffsetY.value else 0f,
                                    landingScale   = if (isThisCardLanding) landingScale.value else 1f,
                                    landingAlpha   = if (isThisCardLanding) landingAlpha.value else 1f,
                                    onConnect      = {
                                        // FIX: check MAX_TABS before launching Activity.
                                        // Previously the Activity was always launched; if
                                        // openTab() returned null inside the Activity the
                                        // session connected silently without a tab slot,
                                        // bypassing OOM protection.
                                        launchConnect(profile)
                                    },
                                    onEdit         = { editingProfile = profile },
                                    onDelete       = { deletingProfile = profile },
                                    // BUGFIX-UI-1: يبقى true طالما Dialog التعديل أو تأكيد
                                    // الحذف مفتوحاً لهذا البروفايل تحديداً؛ عند إغلاقه (إلغاء)
                                    // يعود false فيُعيد SwipeableProfileCard إظهار الكرت
                                    // بدلاً من أن يبقى مخفياً للأبد.
                                    actionPending  = editingProfile?.id == profile.id ||
                                                      deletingProfile?.id == profile.id ||
                                                      shortcutProfile?.id == profile.id,
                                    onWakeOnLan    = if (profile.wolEnabled && profile.wolMacAddress.isNotBlank()) ({
                                        viewModel.sendWakeOnLan(profile)
                                        // BUG #1 FIX: sound is SoundManager? (nullable) — use safe-call to prevent NPE
                                        sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TOGGLE)
                                    }) else null,
                                    onWakeAndConnect = if (profile.wolEnabled && profile.wolMacAddress.isNotBlank()) ({
                                        sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TOGGLE)
                                        viewModel.wakeAndConnect(profile) { launchConnect(profile) }
                                    }) else null,
                                    onOpenSnmpDashboard = if (profile.snmpMonitoringEnabled) ({
                                        com.systemsgo.hex.remote.SessionLauncher.launchSnmpDashboard(context, profile)
                                    }) else null,
                                    // HOME-SCREEN-SHORTCUTS FEATURE: opens the naming dialog first
                                    // (see CreateShortcutDialog / HomeDialogs) so the user can
                                    // customize the shortcut's label before it's pinned — the
                                    // actual ShortcutHelper.requestPinShortcut() call happens once
                                    // they confirm, in onConfirmShortcut below.
                                    onPinShortcut  = { shortcutProfile = profile },
                                    // FOLDERS-UI FEATURE: tapping a tag chip on the card jumps
                                    // straight to filtering the list by that tag.
                                    onTagClick     = { tag -> viewModel.selectTag(tag) },
                                    // FAVORITES FEATURE: toggles the star on this card.
                                    onToggleFavorite = { viewModel.toggleFavorite(profile) },
                                    // PIN-CONNECTION FEATURE
                                    onTogglePin      = { viewModel.togglePin(profile) },
                                    // DUPLICATE-CONNECTION FEATURE
                                    onDuplicate      = {
                                        viewModel.duplicateProfile(profile)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.connection_duplicated))
                                        }
                                    },
                                    // QR-SHARE FEATURE
                                    onShareQr        = { shareQrProfile = profile },
                                    isSelectionMode  = isSelectionMode,
                                    isSelected       = profile.id in selectedProfileIds,
                                    onToggleSelect   = { viewModel.toggleProfileSelection(profile.id) },
                                    // CONNECTION-STATUS-INDICATOR FEATURE: explicit
                                    // Offline fallback (not null) — every card must
                                    // show its status icon, including Offline, once
                                    // this feature is wired up at all.
                                    statusInfo       = cardStatuses[profile.id] ?: CardStatusInfo.Offline,
                                    onHeightMeasured = { h -> itemHeightsById[profile.id] = h },
                                    // DRAG-TO-FOLDER FEATURE: keep this card's natural
                                    // root position/width fresh on every layout pass so
                                    // hover-detection against folder chips stays accurate.
                                    onGeometryChanged = { pos, w ->
                                        itemRootPositionsById[profile.id] = pos
                                        itemWidthsById[profile.id] = w
                                    },
                                    onDragStart    = {
                                        isDragging  = true
                                        draggingId  = profile.id
                                        dragOffsetY = 0f
                                        hoveredFolderId = null
                                        isDraggedOverBottomBar = false
                                        // FIX #1: فحص إعداد hapticFeedback قبل التنفيذ
                                        if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag         = { deltaY ->
                                        dragOffsetY += deltaY
                                        val id = draggingId
                                        val currentIndex = if (id != null)
                                            reorderableList.indexOfFirst { it.id == id } else -1

                                        if (id != null && currentIndex != -1) {
                                        // Top (y) of the dragged card's current slot
                                        var accumulated = 0f
                                        for (i in 0 until currentIndex) {
                                            accumulated += (itemHeightsById[reorderableList[i].id]
                                                ?: defaultItemHeightPx) + itemSpacingPx
                                        }
                                        val myHeight = itemHeightsById[id] ?: defaultItemHeightPx
                                        val myCenter = accumulated + myHeight / 2f + dragOffsetY

                                        // Which slot does that centre now fall into?
                                        var acc2 = 0f
                                        var newTarget = currentIndex
                                        for (j in reorderableList.indices) {
                                            val hj = (itemHeightsById[reorderableList[j].id]
                                                ?: defaultItemHeightPx) + itemSpacingPx
                                            if (myCenter < acc2 + hj / 2f) {
                                                newTarget = j
                                                break
                                            }
                                            acc2 += hj
                                            newTarget = j
                                        }
                                        // PIN-CONNECTION FEATURE + FAVORITES FEATURE: clamp
                                        // the drop target to the dragged card's own section,
                                        // so a card can never be dragged across a Pinned /
                                        // Favorites / All Connections boundary — each section
                                        // always stays in that order, only the position
                                        // *within* a section changes (per requirements). Three
                                        // consecutive, non-overlapping ranges now instead of
                                        // the previous two:
                                        // [0, pinnedCount) → [pinnedCount, pinnedCount+favoritesCount)
                                        // → [pinnedCount+favoritesCount, size).
                                        val draggedIsPinned   = reorderableList[currentIndex].isPinned
                                        val draggedIsFavorite = reorderableList[currentIndex].isFavorite
                                        val groupRange = if (draggedIsPinned) {
                                            0 until pinnedCount
                                        } else if (draggedIsFavorite) {
                                            pinnedCount until (pinnedCount + favoritesCount)
                                        } else {
                                            (pinnedCount + favoritesCount) until reorderableList.size
                                        }
                                        newTarget = newTarget.coerceIn(
                                            groupRange.first,
                                            groupRange.last.coerceAtLeast(groupRange.first)
                                        )

                                        if (newTarget != currentIndex) {
                                            // Live-move the item now, so the other cards
                                            // animate out of the way immediately instead
                                            // of jumping at drag end.
                                            val mutable = reorderableList.toMutableList()
                                            val item = mutable.removeAt(currentIndex)
                                            mutable.add(newTarget, item)
                                            reorderableList = mutable

                                            // Compensate dragOffsetY by exactly how much the
                                            // slot's natural top moved, so the card's visual
                                            // position (naturalTop + dragOffsetY) stays
                                            // perfectly continuous under the finger — this is
                                            // what eliminates the "teleport" on swap.
                                            var newAccumulated = 0f
                                            for (i in 0 until newTarget) {
                                                newAccumulated += (itemHeightsById[mutable[i].id]
                                                    ?: defaultItemHeightPx) + itemSpacingPx
                                            }
                                            dragOffsetY += (accumulated - newAccumulated)
                                        }
                                        }

                                        // DRAG-TO-FOLDER FEATURE: computed last, using the
                                        // final dragOffsetY for this onDrag call (after any
                                        // reorder-compensation above), so the hit-test uses
                                        // the card's true current visual position. This is
                                        // deliberately independent of the reorder math above
                                        // it — it only ever looks at the folder chips' own
                                        // bounds (up near the top of the screen), so a plain
                                        // in-list reorder drag never comes near matching one
                                        // unless the card is actually dragged up into that
                                        // row, per requirement #3.
                                        run {
                                            val draggedId = draggingId
                                            val basePos   = if (draggedId != null) itemRootPositionsById[draggedId] else null
                                            if (draggedId != null && basePos != null) {
                                                val width       = itemWidthsById[draggedId] ?: 0f
                                                val height      = itemHeightsById[draggedId] ?: defaultItemHeightPx
                                                val visualTopY  = basePos.y + dragOffsetY
                                                val centerX     = basePos.x + width / 2f
                                                val slopPx      = with(density) { 20.dp.toPx() }
                                                val matched = folderPillBoundsById.entries.firstOrNull { (_, rect) ->
                                                    centerX  in (rect.left - slopPx)..(rect.right + slopPx) &&
                                                    visualTopY in (rect.top - slopPx)..(rect.bottom + slopPx)
                                                }?.key
                                                if (matched != hoveredFolderId) {
                                                    hoveredFolderId = matched
                                                    // Light haptic tick the moment the card
                                                    // enters a valid drop chip — mirrors the
                                                    // LongPress tick already used elsewhere,
                                                    // just for "arrived on target" feedback.
                                                    if (matched != null && uiState.settings.hapticFeedback) {
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                }

                                                // DRAG-TO-PIN FEATURE: same idea, but checking
                                                // proximity to the bottom bar instead — uses the
                                                // card's visual *center* Y (not just its top),
                                                // so "close to" the bar means the card has been
                                                // dragged down far enough that its middle is
                                                // near/inside the bar's own bounds, not merely
                                                // that its top edge grazes it. Entirely separate
                                                // from the folder check above and from the
                                                // reorder math earlier — only geometry, so a
                                                // plain in-list reorder drag (which never nears
                                                // the screen's bottom edge) can't trigger it.
                                                val bbRect = bottomBarBounds
                                                if (bbRect != null && matched == null) {
                                                    val visualCenterY = visualTopY + height / 2f
                                                    val nearBottomBar =
                                                        centerX  in (bbRect.left - slopPx)..(bbRect.right + slopPx) &&
                                                        visualCenterY in (bbRect.top - slopPx)..(bbRect.bottom + slopPx)
                                                    if (nearBottomBar != isDraggedOverBottomBar) {
                                                        isDraggedOverBottomBar = nearBottomBar
                                                        if (nearBottomBar && uiState.settings.hapticFeedback) {
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        }
                                                    }
                                                } else if (isDraggedOverBottomBar) {
                                                    // Card left the bottom-bar zone (or is now
                                                    // over a folder chip instead) — revert it.
                                                    isDraggedOverBottomBar = false
                                                }
                                            }
                                        }
                                    },
                                    onDragEnd      = {
                                        // DRAG-TO-PIN / DRAG-TO-FOLDER FEATURE: exactly one of
                                        // these can be true at drop time (the onDrag checks
                                        // above keep them mutually exclusive), so a plain
                                        // `when` picks whichever drop actually happened; the
                                        // final `else` is the original reorder-persist path,
                                        // completely unchanged from before either feature.
                                        val draggedId          = draggingId
                                        val targetFolderId     = hoveredFolderId
                                        val droppedOnBottomBar = isDraggedOverBottomBar
                                        when {
                                            droppedOnBottomBar && draggedId != null -> {
                                                // Same flow as tapping the pin icon on the card's
                                                // own menu: opens CreateShortcutDialog via the
                                                // existing shortcutProfile state — the actual
                                                // ShortcutHelper.requestPinShortcut() call happens
                                                // once the user confirms a name there, unchanged.
                                                val profileToPin = uiState.profiles.firstOrNull { it.id == draggedId }
                                                if (profileToPin != null) {
                                                    shortcutProfile = profileToPin
                                                    if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.SUCCESS)
                                                }
                                            }
                                            targetFolderId != null && draggedId != null -> {
                                                // DRAG-TO-FOLDER FEATURE + LANDING ANIMATION: card
                                                // released over a folder chip. Instead of moving it
                                                // and letting it vanish instantly, it now flies from
                                                // its drop point into the chip — shrinking + fading —
                                                // and only actually moves in the ViewModel once that
                                                // flight finishes. Skips the reorder-persist branch
                                                // below entirely, same as before.
                                                val movedProfile = uiState.profiles.firstOrNull { it.id == draggedId }
                                                val startPos     = itemRootPositionsById[draggedId]
                                                val folderRect   = folderPillBoundsById[targetFolderId]
                                                val cardW        = itemWidthsById[draggedId] ?: 0f
                                                val cardH        = itemHeightsById[draggedId] ?: defaultItemHeightPx
                                                if (movedProfile != null && startPos != null && folderRect != null) {
                                                    // Centre of the card at its current released
                                                    // position vs. centre of the folder chip it
                                                    // landed on — the delta is the flight path.
                                                    val cardCenterX    = startPos.x + cardW / 2f
                                                    val cardCenterYNow = startPos.y + dragOffsetY + cardH / 2f
                                                    val targetCenterX  = (folderRect.left + folderRect.right) / 2f
                                                    val targetCenterY  = (folderRect.top + folderRect.bottom) / 2f
                                                    val deltaX = targetCenterX - cardCenterX
                                                    val deltaY = targetCenterY - cardCenterYNow

                                                    landingProfileId = movedProfile.id
                                                    isLanding        = true
                                                    if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                                                    landingScope.launch {
                                                        landingOffsetX.snapTo(0f)
                                                        landingOffsetY.snapTo(dragOffsetY)
                                                        landingScale.snapTo(1f)
                                                        landingAlpha.snapTo(1f)

                                                        // Fly + shrink + fade toward the folder chip.
                                                        // Alpha starts a touch later so the card stays
                                                        // clearly visible for most of the flight and
                                                        // only dissolves right as it "enters" the icon.
                                                        coroutineScope {
                                                            launch { landingOffsetX.animateTo(deltaX, tween(360, easing = FastOutSlowInEasing)) }
                                                            launch { landingOffsetY.animateTo(deltaY, tween(360, easing = FastOutSlowInEasing)) }
                                                            launch { landingScale.animateTo(0.1f, tween(360, easing = FastOutSlowInEasing)) }
                                                            launch { landingAlpha.animateTo(0f, tween(220, delayMillis = 150, easing = LinearEasing)) }
                                                        }

                                                        // Only now does the connection actually move —
                                                        // the visual flight and the data change land
                                                        // together instead of the card disappearing
                                                        // before it visibly reaches the folder.
                                                        viewModel.moveProfileToFolder(movedProfile, targetFolderId)
                                                        sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.SUCCESS)

                                                        // Folder chip "receives" the card — brief pulse.
                                                        receivingFolderId = targetFolderId
                                                        isLanding         = false
                                                        landingProfileId  = null

                                                        delay(260)
                                                        if (receivingFolderId == targetFolderId) receivingFolderId = null
                                                    }
                                                } else if (movedProfile != null) {
                                                    // Fallback if a chip's bounds weren't available
                                                    // (e.g. scrolled off-screen) — keep the original
                                                    // instant-move behaviour rather than dropping the
                                                    // action on the floor.
                                                    viewModel.moveProfileToFolder(movedProfile, targetFolderId)
                                                    if (uiState.settings.hapticFeedback) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.SUCCESS)
                                                }
                                            }
                                            else -> {
                                        // The list is already in its final order (it was
                                        // sorted live during the drag) — just persist it.
                                        // PIN-CONNECTION FEATURE: if the dragged card belongs
                                        // to the pinned section, its final position is still
                                        // guaranteed (by the onDrag clamp above) to be within
                                        // reorderableList's first pinnedCount slots — persist
                                        // that block through the dedicated pinnedOrder-only
                                        // update instead of reorderProfiles() below, since the
                                        // latter would also rewrite every other field on every
                                        // currently-visible profile just to move a pinned card.
                                        val draggedIsPinned =
                                            draggedId != null &&
                                            reorderableList.firstOrNull { it.id == draggedId }?.isPinned == true
                                        if (draggedIsPinned) {
                                            viewModel.reorderPinnedProfiles(reorderableList.subList(0, pinnedCount))
                                        } else {
                                        // FIX 2: When a filter / search is active, reorderableList
                                        // is only a subset of all profiles.  Passing it directly to
                                        // reorderProfiles() would discard the positions of every
                                        // profile not currently visible.
                                        // Solution: rebuild the full ordered list by keeping the
                                        // relative order of hidden profiles intact, inserting the
                                        // reordered visible ones at their original positions.
                                        val fullList   = uiState.profiles
                                        val visibleIds = reorderableList.map { it.id }.toSet()
                                        val merged     = mutableListOf<RdpProfile>()
                                        var visIdx     = 0
                                        for (p in fullList) {
                                            if (p.id in visibleIds) {
                                                // Replace with the reordered version
                                                merged.add(reorderableList[visIdx++])
                                            } else {
                                                merged.add(p)
                                            }
                                        }
                                        viewModel.reorderProfiles(merged)
                                        }
                                            }
                                        }
                                        isDragging             = false
                                        draggingId             = null
                                        dragOffsetY            = 0f
                                        hoveredFolderId        = null
                                        isDraggedOverBottomBar = false
                                    },
                                    hapticEnabled  = uiState.settings.hapticFeedback,   // FIX #1
                                )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                    } else {
                        // GRID-VIEW FEATURE: compact multi-column grid.
                        // Drag-to-reorder / drag-to-folder / drag-to-pin are
                        // deliberately not reimplemented here — that geometry
                        // is inherently single-column — so cards keep the
                        // same favorites-first order and are reordered by
                        // switching back to the 1-column view. Tap connects,
                        // long-press opens the same Edit/Delete/⋯ menu.
                        val gridState          = rememberLazyGridState()
                        // PIN-CONNECTION FEATURE: same pinned-first/favorites-after
                        // partition as the List view above — see the comment next to
                        // pinnedCount/favoritesCount there for why favoritesCountGrid
                        // only counts favorites *after* the pinned block.
                        val pinnedCountGrid    = reorderableList.count { it.isPinned }
                        val favoritesCountGrid = reorderableList.drop(pinnedCountGrid).count { it.isFavorite }
                        val gridEntries = remember(reorderableList, pinnedCountGrid, favoritesCountGrid) {
                            buildList {
                                if (pinnedCountGrid > 0) {
                                    add(GridListEntry.SectionHeader("__pinned__", R.string.pinned_badge_desc))
                                }
                                if (favoritesCountGrid > 0) {
                                    add(GridListEntry.SectionHeader("__fav__", R.string.favorites))
                                }
                                reorderableList.forEachIndexed { idx, profile ->
                                    if (idx == pinnedCountGrid + favoritesCountGrid &&
                                        (pinnedCountGrid > 0 || favoritesCountGrid > 0) &&
                                        (pinnedCountGrid + favoritesCountGrid) < reorderableList.size
                                    ) {
                                        add(GridListEntry.SectionHeader("__all__", R.string.all_connections))
                                    }
                                    add(GridListEntry.ProfileEntry(profile))
                                }
                            }
                        }

                        LazyVerticalGrid(
                            columns               = GridCells.Fixed(columnCount),
                            state                 = gridState,
                            modifier              = gridModifier,
                            contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement   = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = gridEntries,
                                key   = { entry ->
                                    when (entry) {
                                        is GridListEntry.SectionHeader -> entry.key
                                        is GridListEntry.ProfileEntry  -> entry.profile.id
                                    }
                                },
                                span  = { entry ->
                                    when (entry) {
                                        is GridListEntry.SectionHeader -> GridItemSpan(maxLineSpan)
                                        is GridListEntry.ProfileEntry  -> GridItemSpan(1)
                                    }
                                }
                            ) { entry ->
                                // PIN-CONNECTION FEATURE / PERF: animateItem() glides a card
                                // into its new slot when pinning/unpinning re-sorts
                                // gridEntries, instead of it snapping there instantly (mirrors
                                // animateItem() already used on the List side above).
                                Box(modifier = Modifier.animateItem()) {
                                when (entry) {
                                    is GridListEntry.SectionHeader -> ConnectionSectionHeader(
                                        icon = when (entry.key) {
                                            "__pinned__" -> Icons.Filled.PushPin
                                            "__fav__"    -> Icons.Filled.Star
                                            else         -> Icons.Outlined.GridView
                                        },
                                        text = stringResource(entry.textRes)
                                    )
                                    is GridListEntry.ProfileEntry -> {
                                        val profile = entry.profile
                                        GridProfileCard(
                                            profile          = profile,
                                            columnCount      = columnCount,
                                            onConnect        = { launchConnect(profile) },
                                            onEdit           = { editingProfile = profile },
                                            onDelete         = { deletingProfile = profile },
                                            onWakeOnLan      = if (profile.wolEnabled && profile.wolMacAddress.isNotBlank()) ({
                                                viewModel.sendWakeOnLan(profile)
                                                sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TOGGLE)
                                            }) else null,
                                            onWakeAndConnect = if (profile.wolEnabled && profile.wolMacAddress.isNotBlank()) ({
                                                sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TOGGLE)
                                                viewModel.wakeAndConnect(profile) { launchConnect(profile) }
                                            }) else null,
                                            onOpenSnmpDashboard = if (profile.snmpMonitoringEnabled) ({
                                                com.systemsgo.hex.remote.SessionLauncher.launchSnmpDashboard(context, profile)
                                            }) else null,
                                            onPinShortcut    = { shortcutProfile = profile },
                                            onToggleFavorite = { viewModel.toggleFavorite(profile) },
                                            // PIN-CONNECTION FEATURE
                                            onTogglePin      = { viewModel.togglePin(profile) },
                                            // DUPLICATE-CONNECTION FEATURE
                                            onDuplicate      = {
                                                viewModel.duplicateProfile(profile)
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(context.getString(R.string.connection_duplicated))
                                                }
                                            },
                                            // QR-SHARE FEATURE
                                            onShareQr        = { shareQrProfile = profile },
                                            isSelectionMode  = isSelectionMode,
                                            isSelected       = profile.id in selectedProfileIds,
                                            onToggleSelect   = { viewModel.toggleProfileSelection(profile.id) },
                                            hapticEnabled    = uiState.settings.hapticFeedback,
                                            // CONNECTION-STATUS-INDICATOR FEATURE
                                            statusInfo       = cardStatuses[profile.id] ?: CardStatusInfo.Offline,
                                        )
                                    }
                                }
                                }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ── Protocol Filter Row ───────────────────────────────────────────────────────
// FILTER-CONSOLIDATION: no longer called from HomeScreen's main layout — its
// chips now render inside FilterMenuButton's DropdownMenu instead. Left in
// place (not deleted) since the alternate layout experiments explore
// bringing a row like this back in a different arrangement.
@Suppress("unused")
@Composable
private fun ProtocolFilterRow(
    active: ProtocolFilter,
    onSelect: (ProtocolFilter) -> Unit,
    // FAVORITES FEATURE: optional so any other (hypothetical) call site of
    // this row keeps compiling/rendering unchanged if it doesn't pass these.
    favoritesOnly: Boolean = false,
    onToggleFavorites: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProtocolFilter.entries.forEach { filter ->
            FilterChip(
                filter   = filter,
                selected = filter == active,
                onClick  = { onSelect(filter) },
                modifier = Modifier.weight(1f)
            )
        }
        if (onToggleFavorites != null) {
            FavoritesOnlyToggle(active = favoritesOnly, onClick = onToggleFavorites)
        }
    }
}

// ── Favorites-only filter toggle ───────────────────────────────────────────────
// A small circular star toggle, styled like the ⋮ overflow chip on each
// profile card (same 44.dp touch target, same gradient/sweep-border
// language) so it reads as part of the same design system rather than a
// bolted-on control.
// STAR-IN-FILTER MERGE: no longer called — favorites-only now lives inside
// FilterMenuButton's dropdown (see the MenuActionItem row added there).
// Left in place rather than deleted in case a future layout wants a
// standalone toggle again.
@Suppress("unused")
@Composable
private fun FavoritesOnlyToggle(
    active:  Boolean,
    onClick: () -> Unit,
) {
    val sound = LocalSoundManager.current
    val tint by animateColorAsState(
        targetValue   = if (active) SolarFlare else CometTail,
        animationSpec = tween(220),
        label         = "favorites_toggle_tint"
    )
    IconButton(
        onClick  = {
            sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
            onClick()
        },
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(
                    listOf(SolarFlare.copy(alpha = if (active) 0.20f else 0.10f), CometTail.copy(alpha = 0.08f))
                ),
                shape = CircleShape
            )
            .border(
                width = 1.dp,
                color = if (active) SolarFlare.copy(alpha = 0.6f) else HorizonGray.copy(alpha = 0.5f),
                shape = CircleShape
            )
    ) {
        Icon(
            imageVector = if (active) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = stringResource(R.string.favorites_only),
            tint     = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun FilterChip(
    filter: ProtocolFilter,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent    = PulsarCyan
    val secondary = QuantumBlue
    val surface   = NebulaSurface
    val sound     = LocalSoundManager.current

    val bgColor by animateColorAsState(
        targetValue   = if (selected) accent.copy(alpha = 0.18f) else surface,
        animationSpec = tween(220),
        label         = "chip_bg"
    )
    val borderColor by animateColorAsState(
        targetValue   = if (selected) accent else HorizonGray.copy(alpha = 0.5f),
        animationSpec = tween(220),
        label         = "chip_border"
    )
    val textColor by animateColorAsState(
        targetValue   = if (selected) accent else CometTail,
        animationSpec = tween(220),
        label         = "chip_text"
    )
    val scale by animateFloatAsState(
        targetValue   = if (selected) 1.04f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
        label         = "chip_scale"
    )

    val icon = when (filter.protocol) {
        ProtocolType.RDP -> Icons.Outlined.DesktopWindows
        ProtocolType.VNC -> Icons.Outlined.Monitor
        ProtocolType.SSH -> Icons.Outlined.Terminal
        ProtocolType.TELNET -> Icons.Outlined.SettingsEthernet
        ProtocolType.RLOGIN -> Icons.Outlined.SettingsEthernet
        ProtocolType.SPICE -> Icons.Outlined.DesktopWindows
        ProtocolType.WEB -> Icons.Outlined.Web
        ProtocolType.REDFISH -> Icons.Outlined.Dns
        ProtocolType.IPMI -> Icons.Outlined.SettingsRemote
        ProtocolType.AMT -> Icons.Outlined.Memory
        ProtocolType.SERIAL_CONSOLE -> Icons.Outlined.SettingsEthernet
        ProtocolType.RESTCONF -> Icons.Outlined.Api
        ProtocolType.SNMP -> Icons.Outlined.NetworkCheck
        ProtocolType.NETCONF -> Icons.Outlined.SettingsRemote
        ProtocolType.GUACAMOLE -> Icons.Outlined.DesktopWindows
        ProtocolType.RTSP -> Icons.Outlined.Videocam
        ProtocolType.MODBUS_TCP -> Icons.Outlined.NetworkCheck
        // PRE-EXISTING GAP FIX: same missing MOSH/PROXMOX cases as
        // protocolFilterIcon() above — added here too, plus the new
        // VIRTUALBOX_VRDE/VMWARE_VSPHERE cases.
        ProtocolType.MOSH -> Icons.Outlined.SettingsEthernet
        ProtocolType.PROXMOX -> Icons.Outlined.Dns
        ProtocolType.VIRTUALBOX_VRDE -> Icons.Outlined.DesktopWindows
        ProtocolType.VMWARE_VSPHERE -> Icons.Outlined.Dns
        null             -> Icons.Outlined.GridView
    }

    Box(
        modifier = modifier
            .scale(scale)
            .height(48.dp) // FIX-TOUCH: was 42dp — below Material Design 48dp minimum touch target
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = textColor, modifier = Modifier.size(16.dp)) // BUG-12 FIX: was 14dp, below 16dp minimum legibility threshold
            Spacer(Modifier.width(4.dp))
            Text(
                text  = when (filter) {
                    ProtocolFilter.ALL -> stringResource(R.string.filter_all)
                    ProtocolFilter.RDP -> "RDP"
                    ProtocolFilter.VNC -> "VNC"
                    ProtocolFilter.SSH -> "SSH"
                    ProtocolFilter.TELNET -> "Telnet"
                    ProtocolFilter.RLOGIN -> "Rlogin"
                    ProtocolFilter.WEB -> "Web"
                    ProtocolFilter.REDFISH -> "Redfish"
                    ProtocolFilter.IPMI -> "IPMI"
                    ProtocolFilter.AMT -> "Intel AMT"
                    ProtocolFilter.MODBUS_TCP -> "Modbus TCP"
                    // COMPILE-BREAK FIX: this `when` was missing these 6 cases
                    // entirely (pre-existing, unrelated to VRDE/vSphere) — an
                    // exhaustive `when` used as an expression can't compile
                    // without every enum case covered, so this file could not
                    // have built as delivered. Labels match protocolFilterLabel().
                    ProtocolFilter.SPICE -> "SPICE"
                    ProtocolFilter.RESTCONF -> "RESTCONF"
                    ProtocolFilter.SNMP -> "SNMP"
                    ProtocolFilter.NETCONF -> "NETCONF"
                    ProtocolFilter.GUACAMOLE -> "Guacamole"
                    ProtocolFilter.RTSP -> "RTSP"
                    // MOSH / PROXMOX / VIRTUALBOX_VRDE / VMWARE_VSPHERE: newly
                    // added ProtocolFilter cases (see enum above).
                    ProtocolFilter.MOSH -> "Mosh"
                    ProtocolFilter.PROXMOX -> "Proxmox API"
                    ProtocolFilter.VIRTUALBOX_VRDE -> "VirtualBox VRDE"
                    ProtocolFilter.VMWARE_VSPHERE -> "VMware vSphere"
                    // SERIAL-CONSOLE FEATURE: pre-existing gap fix, see enum above.
                    ProtocolFilter.SERIAL_CONSOLE -> "Serial Console"
                },
                color = textColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
        // Glow line below selected tab
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.5f)
                    .height(2.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, accent, Color.Transparent)
                        ),
                        shape = RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

// ── Multi-selection toolbar (PIN-CONNECTION FEATURE) ──────────────────────────
// Replaces the (already-removed) TopAppBar area while isSelectionMode is
// active. Deliberately its own small composable rather than inlined at the
// call site — same reasoning as ConnectionSectionHeader below: it's pure
// presentation with no drag/measurement coupling to the list around it.
@Composable
private fun SelectionModeToolbar(
    selectedCount:   Int,
    onPinSelected:   () -> Unit,
    onUnpinSelected: () -> Unit,
    onCancel:        () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text     = stringResource(R.string.selection_mode_count, selectedCount),
            color    = StarDust,
            style    = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        // Both disabled (rather than hidden) when nothing is selected — in
        // practice isSelectionMode is derived from a non-empty selection so
        // this never actually happens, but it's a cheap guard against a
        // stray 0-item bulk call if that invariant ever changes.
        TextButton(onClick = onPinSelected, enabled = selectedCount > 0) {
            Icon(Icons.Filled.PushPin, null, tint = if (selectedCount > 0) PulsarCyan else CometTail, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.selection_mode_pin_selected), color = if (selectedCount > 0) PulsarCyan else CometTail)
        }
        TextButton(onClick = onUnpinSelected, enabled = selectedCount > 0) {
            Icon(Icons.Outlined.PushPin, null, tint = if (selectedCount > 0) PulsarCyan else CometTail, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.selection_mode_unpin_selected), color = if (selectedCount > 0) PulsarCyan else CometTail)
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel), color = CometTail)
        }
    }
}

// ── Connection list section headers (FAVORITES FEATURE) ───────────────────────
// Simple in-list header used to introduce the "Favorites" group at the top of
// the connection list, and the "All Connections" group beneath it once at
// least one favorite exists. Purely presentational — it's a separate,
// non-draggable LazyColumn item, so it never affects the drag-to-reorder
// height/offset math computed for the cards around it (see the onDrag clamp
// next to its call site).
// PIN-CONNECTION FEATURE: also reused for the "Pinned" header — icon ==
// Icons.Filled.PushPin gets the same PulsarCyan tint the Pin action uses
// everywhere else on these cards.
@Composable
private fun ConnectionSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    val iconTint = when (icon) {
        Icons.Filled.Star    -> SolarFlare
        Icons.Filled.PushPin -> PulsarCyan
        else                 -> CometTail
    }
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(14.dp))
        Text(
            text       = text,
            color      = CometTail,
            style      = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}


// This is the missing UI for a feature whose backend (ConnectionFolderRepository,
// MainViewModel.createFolder/renameFolder/deleteFolder/selectFolder, the
// per-theme folder drawables) already existed in full — there was simply no
// screen that ever called createFolder(), and no way to filter the list by
// folder. Tapping a folder filters the list (mirrors ProtocolFilterRow);
// long-pressing a user-created folder opens Rename/Delete; the trailing "+"
// chip is the only way to actually create one.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderFilterRow(
    folders:          List<ConnectionFolder>,
    selectedFolderId: String?,
    onSelectFolder:   (String?) -> Unit,
    onAddFolder:      () -> Unit,
    onRenameFolder:   (ConnectionFolder) -> Unit,
    onDeleteFolder:   (ConnectionFolder) -> Unit,
    // FILTER-CONSOLIDATION: protocol + tag filters are no longer their own
    // permanent rows (ProtocolFilterRow / TagFilterRow) — they now live
    // behind the trailing FilterMenuButton in this same row. Favorites-only
    // stays a separate, always-one-tap toggle right next to it, since it's
    // expected to be used more often than switching protocol/tag (see the
    // prompt's default-assumption note — flag if that assumption is wrong).
    activeProtocolFilter:   ProtocolFilter,
    onSelectProtocolFilter: (ProtocolFilter) -> Unit,
    // PROTOCOL-FILTER-DECLUTTER: protocols that at least one saved connection
    // actually uses — passed through to FilterMenuButton/CategoryFilterPopup
    // so the popup only ever offers protocols the user could plausibly want
    // to filter by.
    usedProtocols:          Set<ProtocolType>,
    protocolCounts:         Map<ProtocolType, Int>,
    tags:                   List<String>,
    selectedTag:            String?,
    onSelectTag:            (String?) -> Unit,
    favoritesOnly:          Boolean,
    onToggleFavorites:      () -> Unit,
    // RECENT-FILTER FEATURE
    recentOnly:             Boolean,
    onToggleRecent:         () -> Unit,
    hasRecentConnections:   Boolean,
    // DRAG-TO-FOLDER FEATURE: which folder chip (by folder.id) the currently
    // dragged card is hovering over, if any — drives the glow on that chip.
    hoveredFolderId:        String? = null,
    // Reports each real folder chip's bounds in root coordinates as it's
    // laid out (rect != null) or removed from composition (rect == null),
    // keyed by folder.id, so HomeScreen can hit-test the dragged card
    // against them without FolderFilterRow needing to know anything about
    // dragging itself.
    onFolderPillBounds:     (String, Rect?) -> Unit = { _, _ -> },
    // DROP-INTO-FOLDER LANDING FEATURE: the folder chip that just received a
    // card at the end of its flight animation — drives a brief "received"
    // pulse on that chip, separate from (and after) the drag-hover glow.
    receivingFolderId:      String? = null,
) {
    val sound = LocalSoundManager.current

    // CORNER-FILTER + FOLDER-DECLUTTER: the funnel/star button used to live
    // *inside* the scrollable folder LazyRow, so once enough folders existed
    // it could scroll off-screen entirely. It's now pinned outside the
    // scrolling row, anchored to the corner of this bar, so it's always
    // reachable in one tap regardless of how many folders exist.
    //
    // The "All" / "Unfiled" pills and every folder chip are hidden by
    // default too — with zero folders created, a folder filter row has
    // nothing meaningful to filter, so it added visual noise for no
    // benefit. Only a single "+" (new folder) button shows. The moment the
    // first folder is created, the full row (All / Unfiled / folders / +)
    // reappears, since folder-filtering is now actually useful.
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(Modifier.width(16.dp))

        FilterMenuButton(
            activeProtocolFilter   = activeProtocolFilter,
            onSelectProtocolFilter = onSelectProtocolFilter,
            usedProtocols          = usedProtocols,
            protocolCounts         = protocolCounts,
            tags                   = tags,
            selectedTag            = selectedTag,
            onSelectTag            = onSelectTag,
            favoritesOnly          = favoritesOnly,
            onToggleFavorites      = onToggleFavorites,
            recentOnly             = recentOnly,
            onToggleRecent         = onToggleRecent,
            hasRecentConnections   = hasRecentConnections,
        )

        if (folders.isEmpty()) {
            // Nothing to filter yet — just the create-folder entry point.
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(ChipBg)
                    .border(1.dp, HorizonGray.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                            onAddFolder()
                        }
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.new_folder), tint = PulsarCyan, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(16.dp))
        } else {
            LazyRow(
                modifier              = Modifier.weight(1f),
                contentPadding        = PaddingValues(end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "__all_folders__") {
                    FolderPill(
                        label    = stringResource(R.string.filter_all),
                        icon     = Icons.Outlined.GridView,
                        selected = selectedFolderId == null,
                        onClick  = { sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f); onSelectFolder(null) }
                    )
                }
                item(key = UNFILED_FOLDER_ID) {
                    FolderPill(
                        label    = stringResource(R.string.unfiled_folder),
                        icon     = Icons.Outlined.FolderOff,
                        selected = selectedFolderId == UNFILED_FOLDER_ID,
                        onClick  = { sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f); onSelectFolder(UNFILED_FOLDER_ID) }
                    )
                }
                items(folders, key = { it.id }) { folder ->
                    FolderPill(
                        label      = folder.name,
                        icon       = folder.resolvedIcon(),
                        accentColor = folder.resolvedColor(),
                        selected   = selectedFolderId == folder.id,
                        onClick    = { sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f); onSelectFolder(folder.id) },
                        folder     = folder,
                        onRename   = onRenameFolder,
                        onDelete   = onDeleteFolder,
                        // DRAG-TO-FOLDER FEATURE
                        isDropTarget    = folder.id == hoveredFolderId,
                        onBoundsChanged = { rect -> onFolderPillBounds(folder.id, rect) },
                        // DROP-INTO-FOLDER LANDING FEATURE
                        isReceiving     = folder.id == receivingFolderId,
                    )
                }
                item(key = "__add_folder__") {
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(ChipBg)
                            .border(1.dp, HorizonGray.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                                    onAddFolder()
                                }
                            )
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.new_folder), tint = PulsarCyan, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ── Consolidated Filter Menu Button (protocol + tags) ─────────────────────────
// FILTER-CONSOLIDATION FEATURE: replaces the old always-visible
// ProtocolFilterRow + TagFilterRow (which together added up to 1-2 extra
// stacked rows above the connection list) with a single funnel icon that
// opens a dropdown on tap. This follows:
//   - Progressive Disclosure (NN/g): secondary/less-used options hidden
//     behind one on-demand entry, never more than one level deep.
//   - Material Design 3 / eBay filter-bar guidance: one clean row of chips,
//     with less-common facets grouped under a single "dropdown filter chip"
//     instead of wrapping or stacking.
//   - The Termius precedent (same-category competitor): collapsed a
//     similarly cluttered nav bar into one entry point.
// FolderFilterRow itself is untouched above this point — folders are the
// primary, most-used facet, so that row stays permanently visible.
@Composable
private fun FilterMenuButton(
    activeProtocolFilter:   ProtocolFilter,
    onSelectProtocolFilter: (ProtocolFilter) -> Unit,
    usedProtocols:          Set<ProtocolType>,
    protocolCounts:         Map<ProtocolType, Int>,
    tags:                   List<String>,
    selectedTag:            String?,
    onSelectTag:            (String?) -> Unit,
    // STAR-IN-FILTER MERGE: the old standalone circular "favorites only"
    // star toggle has been folded into this one button — it's now the first
    // row of the dropdown, and the funnel icon itself swaps to a filled
    // star while the toggle is active, so there's still a single glance
    // signal that favorites-only is on without a second control next to it.
    favoritesOnly:          Boolean,
    onToggleFavorites:      () -> Unit,
    // RECENT-FILTER FEATURE
    recentOnly:             Boolean,
    onToggleRecent:         () -> Unit,
    hasRecentConnections:   Boolean,
) {
    val sound = LocalSoundManager.current
    var menuExpanded by remember { mutableStateOf(false) }

    // Anything other than the default state lights up the icon (border +
    // tint + a small status dot) so the user never wonders why the list
    // looks filtered without an obvious visible cause.
    val hasActiveFilter = activeProtocolFilter != ProtocolFilter.ALL || selectedTag != null || favoritesOnly || recentOnly
    val borderColor by animateColorAsState(
        targetValue   = if (hasActiveFilter) PulsarCyan else HorizonGray.copy(alpha = 0.5f),
        animationSpec = tween(220),
        label         = "filter_menu_border"
    )
    val iconTint by animateColorAsState(
        targetValue   = if (hasActiveFilter) PulsarCyan else CometTail,
        animationSpec = tween(220),
        label         = "filter_menu_icon_tint"
    )

    Box {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ChipBg)
                .border(1.dp, borderColor, RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                        menuExpanded = true
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = if (favoritesOnly) Icons.Filled.Star else Icons.Outlined.FilterAlt,
                contentDescription = stringResource(R.string.filter_options),
                tint     = if (favoritesOnly) SolarFlare else iconTint,
                modifier = Modifier.size(18.dp)
            )
            // Active-filter status dot. Uses Alignment.TopEnd (not a fixed
            // left/right offset) so it lands on the correct visual corner
            // automatically under RTL layout mirroring.
            if (hasActiveFilter) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(PulsarCyan)
                )
            }
        }

        // CATEGORY-FILTER-REDESIGN FEATURE: replaces the old flat DropdownMenu
        // (favorites row + one MenuActionItem per protocol + one per tag,
        // every entry always on screen at once) with a large floating,
        // searchable popup that groups protocols into categories. See
        // CategoryFilterPopup below for the full implementation.
        CategoryFilterPopup(
            visible                = menuExpanded,
            onDismiss              = { menuExpanded = false },
            activeProtocolFilter   = activeProtocolFilter,
            onSelectProtocolFilter = onSelectProtocolFilter,
            usedProtocols          = usedProtocols,
            protocolCounts         = protocolCounts,
            tags                   = tags,
            selectedTag            = selectedTag,
            onSelectTag            = onSelectTag,
            favoritesOnly          = favoritesOnly,
            onToggleFavorites      = onToggleFavorites,
            recentOnly             = recentOnly,
            onToggleRecent         = onToggleRecent,
            hasRecentConnections   = hasRecentConnections,
        )
    }
}

// ── Category Filter Popup (2026 redesign) ──────────────────────────────────
// Replaces the old flat protocol DropdownMenu. Design goals straight from
// the product spec: a large floating rounded (24dp) popup, protocols grouped
// into a handful of categories instead of listed one-by-one, in-popup search,
// premium rounded "cards" (icon + title + subtitle + protocol count +
// chevron) for each category, a glowing accent border on whichever category
// currently contains the active filter, and closing automatically on
// selection. Motion is a fade + scale entrance (Material Motion-ish) rather
// than the platform's default dropdown-grow animation.
//
// Category *cards* are drill-in headers, not multi-select filters — tapping
// one expands it in place to reveal its individual protocol chips (mirrors
// how Linear/Raycast-style command palettes group-then-drill-down); tapping
// a chip is what actually applies the filter. This deliberately avoids
// widening ProtocolFilter/the profile-list predicate into multi-protocol
// matching, which would ripple into every other `when (activeFilter.protocol)`
// branch in this file (EmptyState, add-connection pre-select, etc.) for a
// UI-only redesign.
@Composable
private fun CategoryFilterPopup(
    visible:                Boolean,
    onDismiss:              () -> Unit,
    activeProtocolFilter:   ProtocolFilter,
    onSelectProtocolFilter: (ProtocolFilter) -> Unit,
    usedProtocols:          Set<ProtocolType>,
    protocolCounts:         Map<ProtocolType, Int>,
    tags:                   List<String>,
    selectedTag:            String?,
    onSelectTag:            (String?) -> Unit,
    favoritesOnly:          Boolean,
    onToggleFavorites:      () -> Unit,
    // RECENT-FILTER FEATURE
    recentOnly:             Boolean,
    onToggleRecent:         () -> Unit,
    hasRecentConnections:   Boolean,
) {
    if (!visible) return

    val sound = LocalSoundManager.current
    val categories = protocolCategoryDefs()

    // Whichever category owns the currently-active protocol filter starts
    // expanded — the closest thing to "remember last selected category"
    // that doesn't require a new persistence layer: the real source of
    // truth (activeProtocolFilter) already survives for the life of the
    // screen, so re-deriving from it here re-opens the right group every
    // time the popup is (re)opened.
    var query by rememberSaveable(visible) { mutableStateOf("") }
    var expandedId by remember(visible) {
        mutableStateOf(categories.firstOrNull { activeProtocolFilter in it.members }?.id)
    }
    var tagsExpanded by remember(visible) { mutableStateOf(selectedTag != null) }

    val normalizedQuery = query.trim()
    val searching = normalizedQuery.isNotEmpty()

    // Category -> the subset of its members that satisfy the search (all of
    // them when not searching). A category with zero matches is hidden
    // entirely rather than shown empty.
    //
    // PROTOCOL-FILTER-DECLUTTER: before search is even applied, each
    // category is first narrowed to `availableMembers` — the members that
    // have at least one saved connection (usedProtocols). A category with no
    // available members at all is dropped from the popup entirely, same as
    // a category with zero search matches was already dropped. This keeps
    // the popup showing only what the user could actually filter *to*
    // something non-empty, instead of every protocol the app supports.
    //
    // SORT-BY-USAGE: availableMembers is also ordered by protocolCounts
    // descending, so the protocol the user relies on most within a category
    // (e.g. RDP over VNC in Desktop) surfaces first instead of a fixed
    // declaration order.
    val visibleCategories = remember(normalizedQuery, categories, usedProtocols, protocolCounts) {
        categories.mapNotNull { cat ->
            val availableMembers = cat.members
                .filter { it.protocol in usedProtocols }
                .sortedByDescending { protocolCounts[it.protocol] ?: 0 }
            if (availableMembers.isEmpty()) return@mapNotNull null
            if (!searching) return@mapNotNull cat to availableMembers
            val titleMatch = cat.title.contains(normalizedQuery, ignoreCase = true)
            val memberMatches = availableMembers.filter {
                protocolFilterLabel(it).contains(normalizedQuery, ignoreCase = true)
            }
            when {
                titleMatch -> cat to availableMembers
                memberMatches.isNotEmpty() -> cat to memberMatches
                else -> null
            }
        }
    }
    val visibleTags = remember(normalizedQuery, tags) {
        if (!searching) tags else tags.filter { it.contains(normalizedQuery, ignoreCase = true) }
    }
    val showAllCard = !searching || "all".contains(normalizedQuery, ignoreCase = true) ||
        "connections".contains(normalizedQuery, ignoreCase = true)
    val showFavoritesCard = !searching || "favorites".contains(normalizedQuery, ignoreCase = true)
    // RECENT-FILTER FEATURE: only offered once at least one connection has
    // actually been used — an empty "Recent" card that always filters down
    // to zero results would be worse than not showing it at all.
    val showRecentCard = hasRecentConnections &&
        (!searching || "recent".contains(normalizedQuery, ignoreCase = true))
    val showTagsCard = tags.isNotEmpty() && (!searching || visibleTags.isNotEmpty() ||
        "tags".contains(normalizedQuery, ignoreCase = true))
    val nothingFound = !showAllCard && !showFavoritesCard && !showRecentCard && !showTagsCard && visibleCategories.isEmpty()

    // Auto-expand every category (and the Tags card) that has a search hit,
    // so results are immediately visible instead of behind another tap.
    LaunchedEffect(normalizedQuery) {
        if (searching) {
            expandedId = visibleCategories.firstOrNull()?.first?.id
            tagsExpanded = visibleTags.isNotEmpty()
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties       = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val entrance = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            entrance.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f * entrance.value))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.78f)
                    .graphicsLayer {
                        scaleX = 0.92f + 0.08f * entrance.value
                        scaleY = 0.92f + 0.08f * entrance.value
                        alpha  = entrance.value
                    }
                    // Swallow taps so they don't fall through to the scrim's
                    // dismiss handler.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .clip(RoundedCornerShape(24.dp))
                    .background(NebulaSurface)
                    // Glass + Material 3: a soft accent-tinted gradient over
                    // the surface stands in for real blur (unavailable below
                    // API 31), plus a faint glowing outline like the other
                    // Dialog-based sheets in this file.
                    .background(
                        Brush.linearGradient(
                            listOf(PulsarCyan.copy(alpha = 0.08f), Color.Transparent, VoidPurple.copy(alpha = 0.06f))
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(listOf(PulsarCyan.copy(0.30f), Color.Transparent, VoidPurple.copy(0.20f))),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(20.dp)
            ) {
                // ── Header ──────────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.FilterAlt, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text       = stringResource(R.string.filter_categories_title),
                        color      = StarDust,
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier   = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ChipBg)
                    ) {
                        // A11Y FIX: was contentDescription = null on an
                        // icon-only close button with no adjacent text.
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_close), tint = CometTail, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Search ──────────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(InputBg)
                        .border(1.dp, InputBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp)
                ) {
                    Icon(Icons.Outlined.Search, null, tint = CometTail, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                text  = stringResource(R.string.filter_categories_search_hint),
                                color = CometTail.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = StarDust),
                            cursorBrush = SolidColor(PulsarCyan),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (query.isNotEmpty()) {
                        // UX FIX: was a bare 16dp Icon with .clickable directly on
                        // it — a touch target well under Android's 48dp minimum.
                        // Wrapped in an IconButton with a 40dp touch target
                        // (compact enough for this inline search row, but a lot
                        // closer to a comfortably tappable size) while the icon
                        // itself stays visually small.
                        IconButton(
                            onClick = { query = "" },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                // A11Y FIX: was contentDescription = null — icon-only
                                // clear button with no adjacent text label.
                                contentDescription = stringResource(R.string.cd_clear_search),
                                tint = CometTail,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Scrollable category list ───────────────────────────
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (showAllCard) {
                        item(key = "all") {
                            CategoryFilterCard(
                                icon        = Icons.Outlined.GridView,
                                title       = stringResource(R.string.filter_all),
                                subtitle    = stringResource(R.string.filter_categories_all_desc),
                                count       = null,
                                highlighted = activeProtocolFilter == ProtocolFilter.ALL,
                                expandable  = false,
                                expanded    = false,
                                onClick     = {
                                    sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                                    onSelectProtocolFilter(ProtocolFilter.ALL)
                                    onDismiss()
                                }
                            )
                        }
                    }

                    if (showFavoritesCard) {
                        item(key = "favorites") {
                            CategoryFilterCard(
                                icon        = if (favoritesOnly) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                title       = stringResource(R.string.favorites_only),
                                subtitle    = null,
                                count       = null,
                                highlighted = favoritesOnly,
                                expandable  = false,
                                expanded    = false,
                                iconTint    = if (favoritesOnly) SolarFlare else null,
                                onClick     = {
                                    sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                                    onToggleFavorites()
                                    onDismiss()
                                }
                            )
                        }
                    }

                    if (showRecentCard) {
                        item(key = "recent") {
                            CategoryFilterCard(
                                icon        = Icons.Outlined.History,
                                title       = stringResource(R.string.filter_recent),
                                subtitle    = stringResource(R.string.filter_categories_recent_desc),
                                count       = null,
                                highlighted = recentOnly,
                                expandable  = false,
                                expanded    = false,
                                onClick     = {
                                    sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                                    onToggleRecent()
                                    onDismiss()
                                }
                            )
                        }
                    }

                    items(visibleCategories, key = { it.first.id }) { (cat, members) ->
                        val isExpanded = expandedId == cat.id
                        val highlighted = activeProtocolFilter in cat.members
                        Column {
                            CategoryFilterCard(
                                icon        = cat.icon,
                                title       = cat.title,
                                subtitle    = cat.subtitle,
                                count       = members.sumOf { protocolCounts[it.protocol] ?: 0 },
                                highlighted = highlighted,
                                expandable  = true,
                                expanded    = isExpanded,
                                onClick     = {
                                    sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                                    expandedId = if (isExpanded) null else cat.id
                                }
                            )
                            AnimatedVisibility(
                                visible = isExpanded,
                                enter   = fadeIn(tween(160)) + expandVertically(tween(200)),
                                exit    = fadeOut(tween(120)) + shrinkVertically(tween(160)),
                            ) {
                                FlowRowChips(
                                    modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 2.dp)
                                ) {
                                    members.forEach { filter ->
                                        val selected = filter == activeProtocolFilter
                                        FilterProtocolChip(
                                            icon     = protocolFilterIcon(filter),
                                            label    = protocolFilterLabel(filter),
                                            count    = filter.protocol?.let { protocolCounts[it] },
                                            selected = selected,
                                            onClick  = {
                                                sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                                                onSelectProtocolFilter(filter)
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (showTagsCard) {
                        item(key = "tags") {
                            Column {
                                CategoryFilterCard(
                                    icon        = Icons.Outlined.Label,
                                    title       = stringResource(R.string.filter_categories_tags_title),
                                    subtitle    = null,
                                    count       = tags.size,
                                    highlighted = selectedTag != null,
                                    expandable  = true,
                                    expanded    = tagsExpanded,
                                    onClick     = {
                                        sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                                        tagsExpanded = !tagsExpanded
                                    }
                                )
                                AnimatedVisibility(
                                    visible = tagsExpanded,
                                    enter   = fadeIn(tween(160)) + expandVertically(tween(200)),
                                    exit    = fadeOut(tween(120)) + shrinkVertically(tween(160)),
                                ) {
                                    FlowRowChips(
                                        modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp, bottom = 2.dp)
                                    ) {
                                        visibleTags.forEach { tag ->
                                            val selected = tag == selectedTag
                                            FilterProtocolChip(
                                                icon     = Icons.Outlined.Label,
                                                label    = tag,
                                                selected = selected,
                                                onClick  = {
                                                    sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                                                    // Tapping the already-selected tag clears it
                                                    // — mirrors the old TagFilterRow's toggle.
                                                    onSelectTag(if (selected) null else tag)
                                                    onDismiss()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (nothingFound) {
                        item(key = "empty") {
                            Text(
                                text  = stringResource(R.string.filter_categories_no_results),
                                color = CometTail,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// One premium rounded card in the Category Filter popup: icon, title,
// optional subtitle, optional protocol-count badge, and (for expandable
// category/tags cards) a chevron that rotates to point down while expanded.
// [highlighted] draws a glowing accent border — used both for a directly
// selected leaf ("All"/Favorites) and for a category that currently
// contains the active protocol filter.
@Composable
private fun CategoryFilterCard(
    icon:        androidx.compose.ui.graphics.vector.ImageVector,
    title:       String,
    subtitle:    String?,
    count:       Int?,
    highlighted: Boolean,
    expandable:  Boolean,
    expanded:    Boolean,
    onClick:     () -> Unit,
    iconTint:    Color? = null,
) {
    val borderColor by animateColorAsState(
        targetValue   = if (highlighted) PulsarCyan else CardBorderColor,
        animationSpec = tween(220),
        label         = "category_card_border"
    )
    val chevronRotation by animateFloatAsState(
        targetValue   = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label         = "category_card_chevron"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (highlighted) PulsarCyan.copy(alpha = 0.10f) else NebulaSurface)
            .border(if (highlighted) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (highlighted) PulsarCyan.copy(alpha = 0.18f) else ChipBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint ?: (if (highlighted) PulsarCyan else CometTail), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = title,
                color      = if (highlighted) PulsarCyan else StarDust,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text     = subtitle,
                    color    = CometTail,
                    style    = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        if (count != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChipBg)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text  = count.toString(),
                    color = CometTail,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        if (expandable) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = CometTail,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }
    }
}

// A small pill chip for one leaf protocol/tag inside an expanded category
// card. Deliberately smaller/quieter than CategoryFilterCard so the visual
// hierarchy stays "category first, protocol second" even while expanded.
@Composable
private fun FilterProtocolChip(
    icon:     androidx.compose.ui.graphics.vector.ImageVector,
    label:    String,
    selected: Boolean,
    onClick:  () -> Unit,
    // FILTER-COUNTS FEATURE: optional connection-count badge — null for tag
    // chips (a tag's usage count isn't tracked the same way) and for any
    // protocol with somehow zero connections (shouldn't happen once
    // PROTOCOL-FILTER-DECLUTTER's usedProtocols filtering is in place, but
    // staying null-safe here costs nothing).
    count:    Int? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) PulsarCyan.copy(alpha = 0.16f) else ChipBg)
            .border(1.dp, if (selected) PulsarCyan else HorizonGray.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(icon, null, tint = if (selected) PulsarCyan else CometTail, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text       = label,
            color      = if (selected) PulsarCyan else StarDust,
            style      = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        if (count != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                text  = "· $count",
                color = if (selected) PulsarCyan.copy(alpha = 0.8f) else CometTail,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// Simple wrapping row for chip content (Compose Foundation has no built-in
// FlowRow in the version this project targets) — lays children out
// left-to-right, wrapping to a new line once the available width runs out.
// RTL-safe: relies on LayoutDirection-aware placement via Modifier, same as
// every other row in this screen, rather than hard-coding left/right.
@Composable
private fun FlowRowChips(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 8.dp,
    verticalSpacing: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val hGapPx = horizontalSpacing.roundToPx()
        val vGapPx = verticalSpacing.roundToPx()
        val maxWidth = constraints.maxWidth
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }

        data class Line(val items: MutableList<androidx.compose.ui.layout.Placeable> = mutableListOf(), var width: Int = 0, var height: Int = 0)
        val lines = mutableListOf(Line())
        placeables.forEach { p ->
            var line = lines.last()
            val extra = if (line.items.isEmpty()) 0 else hGapPx
            if (line.items.isNotEmpty() && line.width + extra + p.width > maxWidth) {
                line = Line()
                lines.add(line)
            }
            val gap = if (line.items.isEmpty()) 0 else hGapPx
            line.items.add(p)
            line.width += gap + p.width
            line.height = maxOf(line.height, p.height)
        }

        val totalHeight = lines.sumOf { it.height } + vGapPx * (lines.size - 1).coerceAtLeast(0)
        layout(maxWidth, totalHeight) {
            var y = 0
            lines.forEach { line ->
                var x = 0
                line.items.forEach { p ->
                    p.placeRelative(x, y)
                    x += p.width + hGapPx
                }
                y += line.height + vGapPx
            }
        }
    }
}


// A single folder/"All"/"Unfiled" pill. [folder] is only non-null for real,
// user-created folders — that's what gates the long-press Rename/Delete menu
// (the synthetic "All" and "Unfiled" entries can't be renamed or deleted).
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderPill(
    label:       String,
    icon:        androidx.compose.ui.graphics.vector.ImageVector,
    selected:    Boolean,
    onClick:     () -> Unit,
    // FOLDER-APPEARANCE FEATURE: the folder's chosen swatch (resolvedColor()),
    // or null for the original PulsarCyan/CometTail treatment — "All",
    // "Unfiled" and tag pills (which pass no folder) always get null here,
    // same look as before this feature existed.
    accentColor: Color? = null,
    folder:      ConnectionFolder? = null,
    onRename:    ((ConnectionFolder) -> Unit)? = null,
    onDelete:    ((ConnectionFolder) -> Unit)? = null,
    // DRAG-TO-FOLDER FEATURE: when true, this chip is the current drop target
    // for a card being dragged — shows a light glow/border change so the
    // user can tell exactly where it will land if released now.
    isDropTarget:    Boolean = false,
    // Reports this chip's bounds in root coordinates on every layout pass
    // (rect != null), and null once when the chip leaves composition (e.g.
    // scrolled out of the LazyRow), so the caller's bounds map never holds a
    // stale entry for a chip that's no longer on screen.
    onBoundsChanged: ((Rect?) -> Unit)? = null,
    // DROP-INTO-FOLDER LANDING FEATURE: true for one brief moment right as a
    // dragged card's flight animation finishes landing on this chip — drives
    // a quick green "received it" bounce + glow, distinct from the ongoing
    // amber hover-glow shown by isDropTarget while a card is still in flight.
    isReceiving: Boolean = false,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // FOLDER-APPEARANCE FEATURE: a chosen accentColor replaces PulsarCyan as
    // the "selected" tint (background/border/text/icon), same behaviour
    // otherwise — unset (null) folders, "All", "Unfiled" and tag pills are
    // byte-for-byte the same as before this feature existed.
    val tint = accentColor ?: PulsarCyan
    val bgColor   by animateColorAsState(if (selected) tint.copy(alpha = 0.18f) else NebulaSurface, tween(220), label = "folder_pill_bg")
    val borderColor by animateColorAsState(
        when {
            isDropTarget -> SolarFlare
            selected     -> tint
            else         -> HorizonGray.copy(alpha = 0.5f)
        },
        tween(180), label = "folder_pill_border"
    )
    val borderWidth by animateDpAsState(if (isDropTarget) 2.dp else 1.dp, tween(180), label = "folder_pill_border_w")
    val textColor by animateColorAsState(if (selected) tint else CometTail, tween(220), label = "folder_pill_text")
    // Slight scale-up + glow while a dragged card hovers over this chip — the
    // same light, unobtrusive "valid drop target" language used elsewhere.
    val dropScale by animateFloatAsState(
        targetValue   = if (isDropTarget) 1.08f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label         = "folder_pill_drop_scale"
    )
    val glowAlpha by animateFloatAsState(if (isDropTarget) 0.35f else 0f, tween(180), label = "folder_pill_glow")

    // DROP-INTO-FOLDER LANDING FEATURE: a short-lived pulse — snaps to 1 the
    // instant a card lands here, then eases back down — used for both an
    // extra scale "bounce" on top of dropScale and a green flash overlay.
    val receivePulse = remember { Animatable(0f) }
    LaunchedEffect(isReceiving) {
        if (isReceiving) {
            receivePulse.snapTo(1f)
            receivePulse.animateTo(0f, tween(520, easing = FastOutSlowInEasing))
        }
    }
    val receiveBounce = 1f + receivePulse.value * 0.22f
    val receiveGlowColor = PlasmaGreen

    if (onBoundsChanged != null) {
        DisposableEffect(Unit) {
            onDispose { onBoundsChanged(null) }
        }
    }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(40.dp)
                .graphicsLayer {
                    // DROP-INTO-FOLDER LANDING FEATURE: receiveBounce briefly
                    // stacks on top of the hover-driven dropScale, so the chip
                    // gives an extra little "pop" right as the card lands —
                    // separate from (and after) the hover scale-up.
                    scaleX = dropScale * receiveBounce
                    scaleY = dropScale * receiveBounce
                }
                .then(
                    if (onBoundsChanged != null)
                        Modifier.onGloballyPositioned { coords ->
                            val pos = coords.positionInRoot()
                            onBoundsChanged(
                                Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height)
                            )
                        }
                    else Modifier
                )
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
                .then(
                    if (isDropTarget)
                        Modifier.background(SolarFlare.copy(alpha = glowAlpha), RoundedCornerShape(20.dp))
                    else Modifier
                )
                .then(
                    // DROP-INTO-FOLDER LANDING FEATURE: green "received" flash,
                    // fading out over receivePulse's decay.
                    if (receivePulse.value > 0f)
                        Modifier.background(receiveGlowColor.copy(alpha = receivePulse.value * 0.4f), RoundedCornerShape(20.dp))
                    else Modifier
                )
                .border(borderWidth, borderColor, RoundedCornerShape(20.dp))
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                    onLongClick = if (folder != null) { { menuExpanded = true } } else null
                )
                .padding(horizontal = 14.dp)
        ) {
            // FOLDER-APPEARANCE FEATURE: the icon glyph itself always carries
            // the folder's chosen color (full tint when selected, dimmed
            // when not) so folders stay visually distinguishable at rest —
            // not just after being tapped. Folders with no color chosen, and
            // "All"/"Unfiled"/tag pills, keep the original textColor-only look.
            val iconTint = if (accentColor != null) {
                if (selected) tint else tint.copy(alpha = 0.75f)
            } else textColor
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text       = label,
                color      = textColor,
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines   = 1,
                overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        if (folder != null) {
            // UI-FIX: matches the themed border/shape + icon-badge treatment now
            // used by the profile card's overflow menu, instead of a bare stock
            // Material dropdown with unbadged icons.
            DropdownMenu(
                expanded        = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape           = RoundedCornerShape(16.dp),
                containerColor  = NebulaSurface,
                tonalElevation  = 0.dp,
                shadowElevation = 10.dp,
                border          = BorderStroke(1.dp, CardBorderColor),
                modifier        = Modifier.padding(vertical = 4.dp)
            ) {
                com.systemsgo.hex.ui.components.MenuActionItem(
                    text    = stringResource(R.string.rename),
                    icon    = Icons.Outlined.DriveFileRenameOutline,
                    tint    = PulsarCyan,
                    onClick = { menuExpanded = false; onRename?.invoke(folder) }
                )
                com.systemsgo.hex.ui.components.MenuActionItem(
                    text      = stringResource(R.string.delete),
                    icon      = Icons.Outlined.Delete,
                    tint      = ErrorRed,
                    textColor = ErrorRed,
                    onClick   = { menuExpanded = false; onDelete?.invoke(folder) }
                )
            }
        }
    }
}

// ── Tag Filter Row (FOLDERS-UI feature) ───────────────────────────────────────
// Same idea as FolderFilterRow but for tags: tapping a tag filters the list;
// tapping the already-selected tag again clears the filter. There's no "add"
// action here — tags are created implicitly from the Organize section of the
// Add/Edit Connection form, not managed as their own entities like folders.
// FILTER-CONSOLIDATION: no longer called from HomeScreen's main layout — its
// chips now render inside FilterMenuButton's DropdownMenu instead. Left in
// place for the same reason as ProtocolFilterRow above.
@Suppress("unused")
@Composable
private fun TagFilterRow(
    tags:        List<String>,
    selectedTag: String?,
    onSelectTag: (String?) -> Unit,
) {
    val sound = LocalSoundManager.current
    LazyRow(
        modifier              = Modifier.fillMaxWidth(),
        contentPadding        = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tags, key = { it }) { tag ->
            val selected = selectedTag == tag
            FolderPill(
                label    = tag,
                icon     = Icons.Outlined.Label,
                selected = selected,
                onClick  = {
                    sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                    onSelectTag(if (selected) null else tag)
                }
            )
        }
    }
}

// ── Space Bottom Navigation Bar ───────────────────────────────────────────────
// REDESIGN v3: v2 tried to cut a real notch into the pill using a boolean
// Path.combine(Difference) shape, then applied .shadow()/.border() to that
// same generic (non-round-rect) Outline. Compose/Android only renders shadows
// and borders on generic/concave outlines correctly on newer API levels; on
// most devices it silently falls back to a plain rectangle. Net effect: the
// notch never visually appears, the bar reads as a flat pill, and the FAB —
// which WAS correctly positioned to overlap it — looks like a totally
// separate floating button with a big dead gap above the bar. That's the
// disjointed look this was meant to fix in the first place.
//
// This version never asks the shadow/border system to draw a concave shape.
// The dock is a plain RoundedCornerShape (always renders correctly). The
// "notch" is faked with a simple, bulletproof trick: a small circular
// "collar" in the exact same glass color as the dock, sitting behind the FAB.
// Where the collar overlaps the dock the colors match and the seam vanishes;
// where it pokes above the dock it reads as one continuous raised piece —
// no path booleans, no API-level surprises, same result on every device.
//
// IA CHANGE: bottom bar now holds exactly 2 destinations you revisit often
// (Sessions, Settings) plus the primary Add action — not a mix of
// destinations and one-off actions. Import wasn't a "place", it was just
// another way to create a connection, so it now lives inside the Add flow
// (see AddOptionsDialog) instead of competing for its own permanent icon.
@Composable
private fun SpaceBottomBar(
    hapticEnabled:      Boolean = true,   // FIX #1: لتمرير الإعداد إلى AddFab
    activeSessionCount: Int = 0,
    onSettingsClick:    () -> Unit,
    onAddClick:         () -> Unit,
    onAddLongClick:     () -> Unit,
    onSessionsClick:    () -> Unit,
    // DRAG-TO-PIN FEATURE: while true, the dock temporarily swaps its normal
    // three actions (Settings / FAB / Sessions) for a single full-width
    // "Add to Home screen" drop zone — shown while a card is being dragged
    // down near this bar. This composable only renders whichever state it's
    // told; HomeScreen owns deciding when that's true and what happens on
    // drop, so drag logic never has to live in here.
    isDropTarget:       Boolean = false,
    // Reports this bar's outer bounds in root coordinates on every layout
    // pass. The outer Box's size never changes between the two states above
    // (only its inner content does), so these bounds stay stable and simple
    // to hit-test against, unlike the per-card positions used for reordering.
    onBoundsChanged:    ((Rect) -> Unit)? = null,
) {
    val glass     = NebulaSurface
    val accent    = PulsarCyan
    val secondary = QuantumBlue
    // DRAG-TO-PIN FEATURE: reuse the same accent already used for "valid drop
    // target" elsewhere (folder chip glow), so the visual language for "you
    // can drop here" stays consistent across the whole screen.
    val dropAccent = SolarFlare

    val pillHeight   = 68.dp
    val fabSize      = 60.dp
    val fabRaise     = 24.dp                 // how much of the FAB pokes above the dock
    val collarMoat   = 7.dp                  // visible glass-colored ring around the FAB
    val collarSize   = fabSize + collarMoat * 2
    val cornerRadius = 30.dp
    val dockShape    = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }

    val dockBorderColor by animateColorAsState(
        if (isDropTarget) dropAccent else accent.copy(0.34f),
        tween(180), label = "bottom_bar_border"
    )
    val dockGlowAlpha by animateFloatAsState(
        if (isDropTarget) 0.16f else 0f, tween(180), label = "bottom_bar_glow"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .height(pillHeight + fabRaise)
            .then(
                if (onBoundsChanged != null)
                    Modifier.onGloballyPositioned { coords ->
                        val pos = coords.positionInRoot()
                        onBoundsChanged(
                            Rect(pos.x, pos.y, pos.x + coords.size.width, pos.y + coords.size.height)
                        )
                    }
                else Modifier
            )
    ) {
        // ── Floating glass dock — plain rounded pill, renders identically
        // on every API level (no generic/concave outline involved). ────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(pillHeight)
                .shadow(
                    elevation    = if (isDropTarget) 24.dp else 18.dp,
                    shape        = dockShape,
                    ambientColor = if (isDropTarget) dropAccent.copy(alpha = 0.30f) else accent.copy(alpha = 0.20f),
                    spotColor    = if (isDropTarget) dropAccent.copy(alpha = 0.40f) else accent.copy(alpha = 0.28f)
                )
                .clip(dockShape)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(glass.copy(alpha = 0.90f), glass.copy(alpha = 0.99f))
                    )
                )
                // Light glow tint while a card is hovering, drawn on top of
                // the normal glass background, same "touch of highlight
                // color" language as the folder-chip drop target.
                .then(
                    if (dockGlowAlpha > 0f)
                        Modifier.background(dropAccent.copy(alpha = dockGlowAlpha), dockShape)
                    else Modifier
                )
                .border(
                    width = if (isDropTarget) 2.dp else 1.dp,
                    brush = if (isDropTarget)
                        Brush.horizontalGradient(
                            listOf(dockBorderColor.copy(0.35f), dockBorderColor, dockBorderColor.copy(0.35f))
                        )
                    else
                        Brush.horizontalGradient(
                            listOf(accent.copy(0.08f), accent.copy(0.34f), accent.copy(0.08f))
                        ),
                    shape = dockShape
                )
        ) {
            Crossfade(targetState = isDropTarget, animationSpec = tween(180), label = "bottom_bar_content") { dropMode ->
                if (dropMode) {
                    // ── Drop zone: replaces all three actions while a card
                    // is being dragged down near this bar. ──────────────────
                    Row(
                        modifier              = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Outlined.AddToHomeScreen,
                            contentDescription = null,
                            tint     = dropAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text       = stringResource(R.string.pin_shortcut),
                            color      = dropAccent,
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // ── One destination each side of the FAB — symmetric, same weight.
                    Row(
                        modifier              = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        NavBarButton(
                            icon    = Icons.Outlined.Settings,
                            label   = stringResource(R.string.settings),
                            onClick = onSettingsClick,
                            tint    = CometTail
                        )

                        // Reserve the center gap for the collar + FAB.
                        Spacer(Modifier.width(collarSize))

                        NavBarButton(
                            icon    = Icons.Outlined.Layers,
                            label   = stringResource(R.string.sessions),
                            onClick = onSessionsClick,
                            tint    = if (activeSessionCount > 0) accent else CometTail,
                            badgeCount = activeSessionCount
                        )
                    }
                }
            }
        }

        // ── Center: collar + Add Connection FAB, glued to the dock ─────────
        // Fades out while the drop zone is showing, since the FAB's normal
        // purpose (add a new connection) isn't relevant mid-drag and the
        // full-width drop zone message needs the space to itself.
        AnimatedVisibility(
            visible  = !isDropTarget,
            modifier = Modifier.align(Alignment.TopCenter),
            enter    = fadeIn(tween(150)),
            exit     = fadeOut(tween(120))
        ) {
            // NOTE: taller than the collar itself (matches the full poke-height
            // of the dock) so the "Add" caption below has real, un-clipped
            // room to sit in — rather than being squeezed inside the circle.
            Box(
                modifier         = Modifier
                    .width(collarSize)
                    .height(pillHeight + fabRaise),
                contentAlignment = Alignment.TopCenter
            ) {
                // Collar: same color/gradient as the dock, so the boundary
                // between them disappears wherever the two overlap.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .size(collarSize)
                        .shadow(elevation = 10.dp, shape = CircleShape, ambientColor = accent.copy(alpha = 0.15f))
                        .clip(CircleShape)
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(glass.copy(alpha = 0.90f), glass.copy(alpha = 0.99f))
                            )
                        )
                )
                AddFab(
                    onClick       = onAddClick,
                    onLongClick   = onAddLongClick,
                    hapticEnabled = hapticEnabled,
                    size          = fabSize,
                    accent        = accent,
                    secondary     = secondary,
                    modifier      = Modifier.align(Alignment.TopCenter)
                )

                // ADD-LABEL FEATURE: a small caption sitting in the dock's
                // own flat area just below the collar — the same visual
                // language Settings/Sessions already use (icon + caption),
                // so the center action reads as fully labeled too instead
                // of relying on shape/position alone to communicate intent.
                Text(
                    text       = stringResource(R.string.add_short),
                    style      = MaterialTheme.typography.labelSmall,
                    color      = accent,
                    fontWeight = FontWeight.Medium,
                    maxLines   = 1,
                    modifier   = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun NavBarButton(
    icon:    androidx.compose.ui.graphics.vector.ImageVector,
    label:   String,
    onClick: () -> Unit,
    tint:    Color,
    modifier:   Modifier = Modifier,
    badgeCount: Int = 0,
) {
    val sound = LocalSoundManager.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .pressScale(onClick = { sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f); onClick() })
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Box {
            Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 7.dp, y = (-4).dp)
                        .size(14.dp)
                        .background(PulsarCyan, CircleShape)
                        .border(1.5.dp, NebulaSurface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = if (badgeCount > 9) "9+" else badgeCount.toString(),
                        color = DeepSpace,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        maxLines = 1
                    )
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint, // BUG-9 FIX: was tint.copy(alpha=0.7f) — double-dimming CometTail fails WCAG contrast
            maxLines = 1
        )
    }
}

// ROCKET-LAUNCH FEATURE: the plain "+" FAB is now a small rocket sitting on
// its launch pad (the existing collar/bloom ring below already reads as a
// pad). Idle, it just flickers a small flame at its base. On tap it ignites,
// lifts off, and "explodes" into a burst of particles right as the 3-option
// add sheet appears — so that sheet reads as the rocket's debris settling
// into place rather than a plain dialog popping up. Long-press (Quick
// Connect) is untouched — only the tap flow gets the launch treatment.
@Composable
private fun AddFab(
    onClick:       () -> Unit,
    onLongClick:   () -> Unit = {},
    hapticEnabled: Boolean = true,   // FIX #1
    size:          Dp = 62.dp,
    accent:        Color,
    secondary:     Color,
    modifier:      Modifier = Modifier,
) {
    val interSrc = remember { MutableInteractionSource() }
    val sound    = LocalSoundManager.current
    val haptics  = LocalHapticFeedback.current
    val scope    = rememberCoroutineScope()
    val density  = LocalDensity.current
    val addConnectionLabel = stringResource(R.string.add_connection)

    val pressed by interSrc.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue   = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "fab_press_scale"
    )

    // ROCKET-LAUNCH FEATURE: drives the whole tap sequence — ignition,
    // liftoff (translate/scale/wobble), then the rocket vanishes right as
    // explosionProgress plays out its particle burst. isLaunching blocks a
    // second tap from starting a new sequence mid-flight.
    var isLaunching by remember { mutableStateOf(false) }
    val launchTranslateY  = remember { Animatable(0f) }
    val launchScale       = remember { Animatable(1f) }
    val launchRotation    = remember { Animatable(0f) }
    val launchAlpha       = remember { Animatable(1f) }
    val flameBoost        = remember { Animatable(0f) }
    val explosionProgress = remember { Animatable(0f) }
    // FORCEFUL-LAUNCH FEATURE: ignitionFlash is a one-shot bright burst timed
    // to the instant the rocket breaks from the pad (sells the "power"
    // moment); liftProgress tracks 0→1 across the whole ascent and drives
    // the motion-streak lines in RocketIcon so the rocket visibly looks
    // like it's straining/tearing upward rather than just sliding up.
    val ignitionFlash     = remember { Animatable(0f) }
    val liftProgress      = remember { Animatable(0f) }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // ROCKET-LAUNCH FEATURE: the explosion burst is deliberately drawn
        // *outside* the clipped circular pad below (this Box has no clip),
        // so its particles can spread past the FAB's own circle instead of
        // being cut off at its edge.
        if (explosionProgress.value > 0f) {
            ExplosionBurst(
                progress  = explosionProgress.value,
                size      = size,
                accent    = accent,
                secondary = secondary
            )
        }

        // FORCEFUL-LAUNCH FEATURE: a bright radial flash timed to the
        // instant of ignition — a quick, punchy flare that sells the
        // "sudden burst of power" the moment before liftoff, separate from
        // (and drawn under) the rocket itself so it isn't clipped by the
        // pad's circular shape.
        if (ignitionFlash.value > 0f) {
            Box(
                modifier = Modifier
                    .size(size * 1.9f)
                    .graphicsLayer { alpha = ignitionFlash.value }
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color.White, Color(0xFF9AD8FF).copy(alpha = 0.6f), Color.Transparent)
                            )
                        )
                    }
            )
        }

        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX       = pressScale * launchScale.value
                    scaleY       = pressScale * launchScale.value
                    translationY = launchTranslateY.value
                    rotationZ    = launchRotation.value
                    this.alpha   = launchAlpha.value
                }
                .drawBehind drawScope@{
                    // Layered static bloom — three soft, fading rings read as a
                    // smooth diffuse halo instead of one hard-edged glow disc.
                    // NOTE: explicit receiver label needed — the enclosing AddFab
                    // `size: Dp` parameter otherwise shadows DrawScope's `size`.
                    val r = this@drawScope.size.minDimension / 2
                    drawCircle(color = accent.copy(alpha = 0.10f), radius = r + 22.dp.toPx())
                    drawCircle(color = accent.copy(alpha = 0.16f), radius = r + 13.dp.toPx())
                    drawCircle(color = accent.copy(alpha = 0.22f), radius = r + 6.dp.toPx())
                }
                .shadow(elevation = 12.dp, shape = CircleShape, ambientColor = accent, spotColor = secondary)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(secondary, accent),
                        start  = Offset(0f, 0f),
                        end    = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                )
                // Thin bright sweep-gradient rim instead of a flat single-alpha
                // border — catches the eye as a subtle ring of light.
                .border(
                    width = 1.5.dp,
                    brush = Brush.sweepGradient(
                        listOf(accent.copy(alpha = 0.9f), secondary.copy(alpha = 0.9f), Color.White.copy(alpha = 0.5f), accent.copy(alpha = 0.9f))
                    ),
                    shape = CircleShape
                )
                .semantics { contentDescription = addConnectionLabel }
                .combinedClickable(
                    interactionSource = interSrc,
                    indication        = null,
                    enabled           = !isLaunching,
                    onClick           = {
                        sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.5f)
                        if (isLaunching) return@combinedClickable
                        isLaunching = true
                        scope.launch {
                            if (hapticEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                            // FORCEFUL-LAUNCH FEATURE: anticipation crouch — the
                            // rocket squats down slightly first, like coiling
                            // before a jump, so the burst that follows reads as
                            // released energy rather than a plain slide upward.
                            launchScale.animateTo(0.88f, tween(90, easing = FastOutLinearInEasing))

                            // Ignition — flame flares up and the ignition flash
                            // punches in right as the crouch bottoms out.
                            coroutineScope {
                                launch { flameBoost.animateTo(1f, tween(120, easing = FastOutLinearInEasing)) }
                                launch {
                                    ignitionFlash.animateTo(1f, tween(60, easing = LinearEasing))
                                    ignitionFlash.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                                }
                            }

                            // Liftoff — a harder, faster launch than before:
                            // translate up + shrink (perspective) + a stronger
                            // multi-wobble, all in parallel. liftProgress tracks
                            // the ascent 0→1 to drive RocketIcon's motion streaks.
                            val liftPx = with(density) { size.toPx() } * 3.4f
                            coroutineScope {
                                launch { launchTranslateY.animateTo(-liftPx, tween(320, easing = FastOutSlowInEasing)) }
                                launch { launchScale.animateTo(0.42f, tween(320, easing = FastOutSlowInEasing)) }
                                launch { liftProgress.animateTo(1f, tween(320, easing = LinearOutSlowInEasing)) }
                                launch {
                                    launchRotation.animateTo(-11f, tween(70, easing = FastOutSlowInEasing))
                                    launchRotation.animateTo(9f, tween(110, easing = FastOutSlowInEasing))
                                    launchRotation.animateTo(-4f, tween(90, easing = FastOutSlowInEasing))
                                    launchRotation.animateTo(0f, tween(90, easing = FastOutSlowInEasing))
                                }
                            }

                            // Explosion — rocket vanishes, particles burst.
                            launchAlpha.snapTo(0f)
                            sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.SUCCESS)
                            explosionProgress.snapTo(0f)
                            explosionProgress.animateTo(1f, tween(380, easing = LinearOutSlowInEasing))

                            // Reveal the 3-option sheet right as the burst peaks,
                            // so it reads as the debris settling into a UI.
                            onClick()

                            delay(90)
                            explosionProgress.snapTo(0f)

                            // Reset the rocket back to its resting state on the
                            // pad, ready for next time.
                            launchTranslateY.snapTo(0f)
                            launchScale.snapTo(1f)
                            launchRotation.snapTo(0f)
                            flameBoost.snapTo(0f)
                            liftProgress.snapTo(0f)
                            ignitionFlash.snapTo(0f)
                            launchAlpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
                            isLaunching = false
                        }
                    },
                    onLongClick       = {
                        // FIX #1: فحص إعداد hapticFeedback قبل التنفيذ
                        if (hapticEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.7f)
                        onLongClick()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            RocketIcon(
                flameBoost   = flameBoost.value,
                liftProgress = liftProgress.value,
                size         = size,
                accent       = accent,
                secondary    = secondary
            )
        }
    }
}

// ROCKET-LAUNCH FEATURE: the rocket is now the user-supplied reference
// artwork (converted 1:1 from their SVG into ic_rocket_ref_body /
// ic_rocket_ref_flame — see those files' headers) instead of the old
// hand-drawn Canvas shape. The two vector drawables share the exact same
// viewport, so they're stacked in perfect alignment here: the body stays
// fixed while only the flame layer is scaled/faded, which keeps the same
// idle-flicker + ignition-boost behavior the old Canvas version had.
@Composable
private fun RocketIcon(
    flameBoost:   Float,
    liftProgress: Float = 0f,
    size:         Dp,
    accent:       Color,
    secondary:    Color,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rocket_idle_flicker")
    val idleFlicker by infiniteTransition.animateFloat(
        initialValue  = 0.7f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(240, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_flicker"
    )
    val flameEnergy = (idleFlicker + flameBoost).coerceIn(0f, 2f)

    // The reference art's own aspect ratio (viewport ≈ 344 × 746, roughly
    // 1 : 2.17) is taller/narrower than the FAB's circular pad, so it's
    // fit by HEIGHT (not width) — the rocket naturally tapers to a point
    // at both the nose and the flame tip, which lines up with how a
    // circle's available width also tapers toward its top/bottom edges,
    // so nothing gets clipped by the pad's circular mask. Fitting by
    // width instead would push the nose/flame past the circle's edge.
    val artHeight = size
    val artWidth  = size * (344.05f / 745.93f)

    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter            = painterResource(id = R.drawable.ic_rocket_ref_body),
            contentDescription = null,
            modifier           = Modifier
                .width(artWidth)
                .height(artHeight)
        )
        Image(
            painter            = painterResource(id = R.drawable.ic_rocket_ref_flame),
            contentDescription = null,
            modifier           = Modifier
                .width(artWidth)
                .height(artHeight)
                .graphicsLayer {
                    // Flame lives near the bottom of the shared viewport,
                    // attaching to the body at roughly 64% of the way down
                    // — pivoting there (not the image center) means growing
                    // the flame stretches it downward from where it's
                    // actually attached, instead of drifting the whole jet.
                    transformOrigin = TransformOrigin(0.5f, 0.64f)
                    scaleY = 0.55f + 0.5f * flameEnergy
                    scaleX = 0.85f + 0.15f * flameEnergy
                    alpha  = (0.55f + 0.45f * flameEnergy).coerceIn(0f, 1f)
                }
        )

        // ── Motion-streak lines — short fading dashes flanking the hull,
        // only visible while liftProgress > 0 (i.e. mid-launch). They grow
        // longer and more numerous the further into the ascent the rocket
        // is, reading as air/vapor tearing past a rocket under real thrust
        // rather than it simply sliding upward. ─────────────────────────
        if (liftProgress > 0f) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .width(artWidth)
                    .height(artHeight)
            ) {
                val w = this.size.width
                val h = this.size.height
                val cx = w / 2f
                val streakAlpha = (liftProgress * 1.4f).coerceIn(0f, 1f)
                val streakLen   = h * (0.07f + 0.11f * liftProgress)
                val xs = listOf(-0.62f, -0.42f, 0.42f, 0.62f)
                xs.forEachIndexed { i, xf ->
                    val sx = cx + w * xf
                    val sy = h * (0.10f + 0.06f * (i % 2))
                    drawLine(
                        color       = Color.White.copy(alpha = streakAlpha * 0.7f),
                        start       = Offset(sx, sy),
                        end         = Offset(sx, sy + streakLen),
                        strokeWidth = w * 0.012f,
                        cap         = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
            }
        }
    }
}


// ROCKET-LAUNCH FEATURE: the burst of particles that plays right as the
// rocket "explodes" — a simple ring of small circles radiating outward
// from the center plus a fading shockwave ring, all driven by a single
// 0..1 progress value the caller animates once per launch.
@Composable
private fun ExplosionBurst(
    progress:  Float,
    size:      Dp,
    accent:    Color,
    secondary: Color,
) {
    val particleCount = 10
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size * 2.4f)) {
        val cx       = this.size.width / 2f
        val cy       = this.size.height / 2f
        val maxDist  = this.size.minDimension / 2f
        val fadeAlpha = (1f - progress).coerceIn(0f, 1f)

        for (i in 0 until particleCount) {
            val angle    = (2 * PI / particleCount) * i
            val dist     = maxDist * progress
            val px       = cx + cos(angle).toFloat() * dist
            val py       = cy + sin(angle).toFloat() * dist
            val radius   = (this.size.minDimension * 0.045f) * (1f - progress * 0.65f)
            drawCircle(
                color  = (if (i % 2 == 0) accent else secondary).copy(alpha = fadeAlpha),
                radius = radius.coerceAtLeast(1f),
                center = Offset(px, py)
            )
        }
        drawCircle(
            color  = Color.White.copy(alpha = fadeAlpha * 0.8f),
            radius = maxDist * progress * 0.55f,
            center = Offset(cx, cy),
            style  = Stroke(width = 3.dp.toPx())
        )
    }
}


// ── One-time swipe gesture hint (replaces old per-card duplicated text) ───────
@Composable
private fun SwipeHintBanner(modifier: Modifier = Modifier) {
    Surface(
        color    = NebulaSurface.copy(alpha = 0.6f),
        shape    = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.SwipeLeft, null, tint = PulsarCyan.copy(0.8f), modifier = Modifier.size(16.dp))
            Text(
                stringResource(R.string.swipe_hint_combined),
                style    = MaterialTheme.typography.labelSmall,
                color    = CometTail.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── Network Banner ────────────────────────────────────────────────────────────
@Composable
private fun NetworkBanner(modifier: Modifier = Modifier) {
    Surface(
        color    = SolarFlare.copy(alpha = 0.12f),
        shape    = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.WifiOff, null, tint = SolarFlare, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.poor_network_warning),
                style = MaterialTheme.typography.bodySmall,
                color = SolarFlare
            )
        }
    }
}

// VPN-AWARE-CONNECTIVITY: small informational banner shown whenever a VPN is
// active (any VpnService-based app — Tailscale/ZeroTier/NetBird/WireGuard/
// OpenVPN/SoftEther/AnyConnect/FortiClient/GlobalProtect/Always-On VPN/etc.).
// Deliberately a neutral/positive color (unlike NetworkBanner's warning
// color) since an active VPN is informational, not a problem.
@Composable
private fun VpnStatusBanner(
    status: com.systemsgo.hex.util.VpnConnectivityManager.VpnStatus,
    modifier: Modifier = Modifier,
) {
    Surface(
        color    = PulsarCyan.copy(alpha = 0.12f),
        shape    = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.VpnLock, null, tint = PulsarCyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            val ipLabel = when {
                status.hasIpv4 && status.hasIpv6 -> stringResource(R.string.vpn_status_ipv4_ipv6)
                status.hasIpv6                    -> stringResource(R.string.vpn_status_ipv6)
                else                               -> stringResource(R.string.vpn_status_ipv4)
            }
            Text(
                stringResource(R.string.vpn_active_status, status.interfaceName ?: ipLabel),
                style = MaterialTheme.typography.bodySmall,
                color = PulsarCyan
            )
        }
    }
}

// ── Loading indicator ─────────────────────────────────────────────────────────
@Composable
private fun SpaceLoadingIndicator() {
    val accent     = PulsarCyan
    // BUGFIX-UI-10: نفس إصلاح BUG-13 المطبَّق في StarfieldBackground — إيقاف
    // الأنيميشن اللانهائي عند انتقال التطبيق للخلفية لتوفير البطارية.
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // BUGFIX: infiniteRepeatable(snap()) has a 0-duration and crashes with
    // "Animation to be infinitely repeated cannot have a 0-duration" as soon as
    // isResumed flips to false (e.g. Activity paused when Settings or a session
    // card is opened). Replaced with Animatable-driven loops that simply don't
    // (re)start while paused — no crash, and the animation truly pauses.
    val rotationAnim = remember { Animatable(0f) }
    LaunchedEffect(isResumed) {
        if (isResumed) {
            while (true) {
                rotationAnim.animateTo(360f, tween(1200, easing = LinearEasing))
                rotationAnim.snapTo(0f)
            }
        }
    }
    val rotation = rotationAnim.value

    val pulseAnim = remember { Animatable(0.6f) }
    LaunchedEffect(isResumed) {
        if (isResumed) {
            while (true) {
                pulseAnim.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
                pulseAnim.animateTo(0.6f, tween(800, easing = FastOutSlowInEasing))
            }
        }
    }
    val pulse = pulseAnim.value
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(56.dp)
                .rotate(rotation)
        ) {
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color      = accent,
                startAngle = 0f, sweepAngle = 270f,
                useCenter  = false, style = stroke
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.initializing), // BUG-3 FIX: was hardcoded "INITIALIZING..." — not translatable
            style = MaterialTheme.typography.labelMedium,
            color = accent.copy(alpha = pulse)
        )
    }
}

// ── Empty State ───────────────────────────────────────────────────────────────
@Composable
private fun EmptyState(
    modifier:    Modifier = Modifier,
    filter:      ProtocolFilter,
    onAddClick:  () -> Unit,
    // CLEAR-FILTERS FEATURE
    hasActiveNarrowing: Boolean = false,
    onClearFilters:     () -> Unit = {}
) {
    // BUGFIX-UI-10: نفس إصلاح BUG-13 — إيقاف الأنيميشن اللانهائي عند الخلفية.
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val (icon, title, subtitle) = when (filter) {
        ProtocolFilter.ALL -> Triple(Icons.Outlined.RocketLaunch, stringResource(R.string.no_connections), stringResource(R.string.add_first_connection))
        ProtocolFilter.RDP -> Triple(Icons.Outlined.DesktopWindows, stringResource(R.string.no_rdp_connections), stringResource(R.string.no_rdp_connections_desc))
        ProtocolFilter.VNC -> Triple(Icons.Outlined.Monitor, stringResource(R.string.no_vnc_connections), stringResource(R.string.no_vnc_connections_desc))
        ProtocolFilter.SSH -> Triple(Icons.Outlined.Terminal, stringResource(R.string.no_ssh_connections), stringResource(R.string.no_ssh_connections_desc))
        ProtocolFilter.TELNET -> Triple(Icons.Outlined.SettingsEthernet, stringResource(R.string.no_telnet_connections), stringResource(R.string.no_telnet_connections_desc))
        ProtocolFilter.RLOGIN -> Triple(Icons.Outlined.SettingsEthernet, stringResource(R.string.no_rlogin_connections), stringResource(R.string.no_rlogin_connections_desc))
        // EMPTY-STATE-GAP FIX: these four used to fall through to the
        // generic "ALL" copy below even while filtered to one specific
        // protocol — confusing (e.g. tapping "Add Connection" from a
        // Redfish-filtered empty state visually implied a generic add,
        // not specifically a Redfish one). Each now gets its own icon/
        // title/subtitle, same treatment as every other protocol above.
        ProtocolFilter.WEB -> Triple(Icons.Outlined.Web, stringResource(R.string.no_web_connections), stringResource(R.string.no_web_connections_desc))
        ProtocolFilter.REDFISH -> Triple(Icons.Outlined.Dns, stringResource(R.string.no_redfish_connections), stringResource(R.string.no_redfish_connections_desc))
        ProtocolFilter.IPMI -> Triple(Icons.Outlined.SettingsRemote, stringResource(R.string.no_ipmi_connections), stringResource(R.string.no_ipmi_connections_desc))
        ProtocolFilter.AMT -> Triple(Icons.Outlined.Memory, stringResource(R.string.no_amt_connections), stringResource(R.string.no_amt_connections_desc))
        // RESTCONF/SNMP/NETCONF/GUACAMOLE still have no dedicated copy of
        // their own — same pre-existing gap, left as-is since it's outside
        // this fix's scope (falls back to the generic ALL empty state
        // rather than leaving this `when` non-exhaustive).
        else -> Triple(Icons.Outlined.RocketLaunch, stringResource(R.string.no_connections), stringResource(R.string.add_first_connection))
    }

    Column(
        modifier              = modifier,
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.Center
    ) {
        ProtocolOrbitVisual(filter = filter, icon = icon, isResumed = isResumed)
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = StarDust, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = CometTail,
            modifier = Modifier.padding(horizontal = 48.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(40.dp))
        SpaceButton(
            text     = stringResource(R.string.add_connection),
            onClick  = onAddClick,
            modifier = Modifier.width(220.dp)
        )
        if (hasActiveNarrowing) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onClearFilters) {
                Text(
                    text  = stringResource(R.string.clear_filters),
                    color = PulsarCyan,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

// ── UX: Orbit visual for the empty state ────────────────────────────────────
// A small "planetary system" behind the empty-state icon instead of a flat glow:
// two counter-rotating dashed orbit rings each carrying a satellite dot, plus
// a soft pulsing nebula glow. Each protocol tab also gets its own accent color
// and a small signature motion so ALL / RDP / VNC / SSH don't feel like
// re-skins of the same screen: RDP pings outward like a broadcast signal, VNC
// gets a scanning refresh line, SSH gets a blinking terminal cursor.
@Composable
private fun ProtocolOrbitVisual(
    filter:     ProtocolFilter,
    icon:       androidx.compose.ui.graphics.vector.ImageVector,
    isResumed:  Boolean
) {
    val accent = when (filter) {
        ProtocolFilter.ALL -> PulsarCyan
        ProtocolFilter.RDP -> QuantumBlue
        ProtocolFilter.VNC -> VoidPurple
        ProtocolFilter.SSH -> PlasmaGreen
        ProtocolFilter.TELNET -> SolarFlare
        ProtocolFilter.RLOGIN -> SolarFlare
        // EMPTY-STATE-GAP FIX: matches the dedicated copy added to the
        // Triple `when` above — each of these four now gets its own accent
        // instead of silently reusing ALL's PulsarCyan.
        ProtocolFilter.WEB -> QuantumBlue
        ProtocolFilter.REDFISH -> NovaPink
        ProtocolFilter.IPMI -> VoidPurple
        ProtocolFilter.AMT -> SolarPulse
        // RESTCONF/SNMP/NETCONF/GUACAMOLE: same pre-existing gap as above,
        // outside this fix's scope.
        else -> PulsarCyan
    }

    // Two orbit rings, rotating in opposite directions at different speeds.
    val outerRing = remember { Animatable(0f) }
    LaunchedEffect(isResumed) {
        if (isResumed) {
            while (true) {
                outerRing.animateTo(360f, tween(10000, easing = LinearEasing))
                outerRing.snapTo(0f)
            }
        }
    }
    val innerRing = remember { Animatable(0f) }
    LaunchedEffect(isResumed) {
        if (isResumed) {
            while (true) {
                innerRing.animateTo(-360f, tween(6500, easing = LinearEasing))
                innerRing.snapTo(0f)
            }
        }
    }

    // Gentle float + breathing glow behind the whole system.
    val floatAnim = remember { Animatable(-8f) }
    LaunchedEffect(isResumed) {
        if (isResumed) {
            while (true) {
                floatAnim.animateTo(8f, tween(2400, easing = FastOutSlowInEasing))
                floatAnim.animateTo(-8f, tween(2400, easing = FastOutSlowInEasing))
            }
        }
    }
    val glowAnim = remember { Animatable(0.35f) }
    LaunchedEffect(isResumed) {
        if (isResumed) {
            while (true) {
                glowAnim.animateTo(0.8f, tween(1800, easing = FastOutSlowInEasing))
                glowAnim.animateTo(0.35f, tween(1800, easing = FastOutSlowInEasing))
            }
        }
    }

    // RDP: two outward "broadcast" rings, staggered, like a remote signal ping.
    val ping1 = remember { Animatable(0f) }
    val ping2 = remember { Animatable(0f) }
    LaunchedEffect(isResumed, filter) {
        if (isResumed && filter == ProtocolFilter.RDP) {
            while (true) {
                ping1.snapTo(0f)
                ping1.animateTo(1f, tween(2600, easing = LinearOutSlowInEasing))
            }
        }
    }
    LaunchedEffect(isResumed, filter) {
        if (isResumed && filter == ProtocolFilter.RDP) {
            delay(1300)
            while (true) {
                ping2.snapTo(0f)
                ping2.animateTo(1f, tween(2600, easing = LinearOutSlowInEasing))
            }
        }
    }

    // VNC: a scan-line sweeping down the screen icon, like a frame refresh.
    val scanLine = remember { Animatable(0f) }
    LaunchedEffect(isResumed, filter) {
        if (isResumed && filter == ProtocolFilter.VNC) {
            while (true) {
                scanLine.animateTo(1f, tween(1900, easing = LinearEasing))
                scanLine.snapTo(0f)
                delay(300)
            }
        }
    }

    // SSH: a blinking terminal cursor beside the prompt icon.
    val cursorAlpha = remember { Animatable(1f) }
    LaunchedEffect(isResumed, filter) {
        if (isResumed && filter == ProtocolFilter.SSH) {
            while (true) {
                cursorAlpha.animateTo(0f, tween(500, easing = LinearEasing))
                cursorAlpha.animateTo(1f, tween(500, easing = LinearEasing))
            }
        }
    }

    Box(
        modifier         = Modifier.size(168.dp),
        contentAlignment = Alignment.Center
    ) {
        // Soft nebula glow + RDP broadcast pings, all beneath the rings.
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(accent.copy(alpha = glowAnim.value * 0.16f), radius = size.minDimension / 2 * 0.9f)
            drawCircle(accent.copy(alpha = glowAnim.value * 0.08f), radius = size.minDimension / 2)

            if (filter == ProtocolFilter.RDP) {
                val baseR = size.minDimension / 2 * 0.42f
                listOf(ping1.value, ping2.value).forEach { p ->
                    if (p > 0f) {
                        drawCircle(
                            color  = accent.copy(alpha = (1f - p) * 0.55f),
                            radius = baseR + baseR * 1.1f * p,
                            style  = Stroke(width = 1.4.dp.toPx())
                        )
                    }
                }
            }
        }

        // Outer dashed orbit ring + satellite dot with a comet-tail glow.
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(152.dp)
                .rotate(outerRing.value)
        ) {
            drawCircle(
                color  = accent.copy(alpha = 0.32f),
                radius = size.minDimension / 2,
                style  = Stroke(
                    width      = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 8.dp.toPx()))
                )
            )
            val r = size.minDimension / 2
            val dot = Offset(center.x + r, center.y)
            drawCircle(accent.copy(alpha = 0.18f), radius = 7.dp.toPx(), center = dot)
            drawCircle(accent, radius = 2.8.dp.toPx(), center = dot)
        }

        // Inner dashed orbit ring + smaller counter-rotating satellite.
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(112.dp)
                .rotate(innerRing.value)
        ) {
            drawCircle(
                color  = accent.copy(alpha = 0.22f),
                radius = size.minDimension / 2,
                style  = Stroke(
                    width      = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx()))
                )
            )
            val r = size.minDimension / 2
            val dot = Offset(center.x - r, center.y)
            drawCircle(accent.copy(alpha = 0.85f), radius = 2.2.dp.toPx(), center = dot)
        }

        // Center "planet" badge carrying the protocol icon.
        Box(
            modifier = Modifier
                .size(78.dp)
                .offset(y = floatAnim.value.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.20f), Color.Transparent)
                        )
                    )
                    drawCircle(
                        color  = accent.copy(alpha = 0.45f),
                        radius = size.minDimension / 2,
                        style  = Stroke(width = 1.dp.toPx())
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = accent.copy(alpha = 0.9f), modifier = Modifier.size(38.dp))

            if (filter == ProtocolFilter.VNC) {
                androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                    val y = size.height * 0.22f + size.height * 0.56f * scanLine.value
                    drawLine(
                        color       = accent.copy(alpha = 0.6f),
                        start       = Offset(size.width * 0.2f, y),
                        end         = Offset(size.width * 0.8f, y),
                        strokeWidth = 1.6.dp.toPx()
                    )
                }
            }
            if (filter == ProtocolFilter.SSH) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 2.dp)
                        .size(width = 6.dp, height = 12.dp)
                        .background(accent.copy(alpha = cursorAlpha.value), RoundedCornerShape(1.dp))
                )
            }
        }
    }
}

// ── UX-04: Search Bar ─────────────────────────────────────────────────────────
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent   = PulsarCyan
    val surface  = NebulaSurface
    var focused  by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue   = if (focused) accent else HorizonGray.copy(alpha = 0.4f),
        animationSpec = tween(200),
        label         = "search_border"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(surface, RoundedCornerShape(14.dp))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = stringResource(R.string.cd_search_icon),
            tint     = if (focused) accent else CometTail,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value         = query,
            onValueChange = onQueryChange,
            modifier      = Modifier
                .weight(1f)
                .onFocusChanged { focused = it.isFocused },
            singleLine    = true,
            textStyle     = MaterialTheme.typography.bodyMedium.copy(color = StarDust),
            cursorBrush   = SolidColor(accent),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = CometTail.copy(alpha = 0.5f)
                        )
                    }
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            // UX FIX: was a bare 18dp Icon with .clickable directly on it — a
            // touch target well under Android's 48dp minimum. Wrapped in an
            // IconButton with a 40dp touch target while the icon stays
            // visually compact, matching the same fix in the dialog search bar.
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.cd_clear_search),
                    tint = CometTail,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ── UX-03: Reorderable Profile Card wrapper — full drag & drop ────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReorderableProfileCard(
    profile:          RdpProfile,
    isDragTarget:     Boolean,
    isDropTarget:     Boolean,
    dragOffsetY:      Float,
    onConnect:        () -> Unit,
    onEdit:           () -> Unit,
    onDelete:         () -> Unit,
    onWakeOnLan:      (() -> Unit)? = null,
    onWakeAndConnect: (() -> Unit)? = null,
    onPinShortcut:    (() -> Unit)? = null,
    // SNMP FEATURE: pass-through to RdpProfileCard — see its own doc comment.
    onOpenSnmpDashboard: (() -> Unit)? = null,
    onTagClick:       ((String) -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    // PIN-CONNECTION FEATURE: same optional-callback pattern as every other
    // row here; see RdpProfileCard's own doc comment for the full contract.
    onTogglePin:      (() -> Unit)? = null,
    // DUPLICATE-CONNECTION FEATURE: pass-through to RdpProfileCard.
    onDuplicate:      (() -> Unit)? = null,
    // QR-SHARE FEATURE: pass-through to RdpProfileCard.
    onShareQr:        (() -> Unit)? = null,
    isSelectionMode:  Boolean = false,
    isSelected:       Boolean = false,
    onToggleSelect:   (() -> Unit)? = null,
    // CONNECTION-STATUS-INDICATOR FEATURE: pass-through to RdpProfileCard.
    statusInfo:       CardStatusInfo? = null,
    onHeightMeasured: (Float) -> Unit,
    // DRAG-TO-FOLDER FEATURE: reports this card's current natural (untranslated)
    // top-left position in root coordinates + its width, refreshed on every
    // layout pass, so the caller can hit-test it against folder chip bounds
    // while this card is being dragged. Optional so nothing else calling this
    // composable needs to change.
    onGeometryChanged: ((Offset, Float) -> Unit)? = null,
    onDragStart:      () -> Unit,
    onDrag:           (Float) -> Unit,
    onDragEnd:        () -> Unit,
    hapticEnabled:    Boolean = true,   // FIX #1: تمرير إعداد hapticFeedback للـ RdpProfileCard
    actionPending:    Boolean = false,  // BUGFIX-UI-1: هل هناك Dialog حذف/تعديل مفتوح لهذا البروفايل؟
    // DROP-INTO-FOLDER LANDING FEATURE: while true, this card is mid-flight
    // into a folder chip — its position/scale/alpha are driven directly by
    // the caller's Animatables (landingOffsetX/Y/Scale/Alpha) instead of the
    // normal isDragTarget-based animateFloatAsState values below, since the
    // caller needs frame-by-frame control over the whole flight, not just a
    // single target value to animate toward.
    isLanding:        Boolean = false,
    landingOffsetX:   Float = 0f,
    landingOffsetY:   Float = 0f,
    landingScale:     Float = 1f,
    landingAlpha:     Float = 1f,
) {
    // BUGFIX-UI-9: نتتبع هنا ما إذا كانت قائمة الخيارات (MoreVert) مفتوحة لهذا
    // الكرت، لتعطيل كاشف السحب الطويل أثناء تفاعل المستخدم مع القائمة.
    var isMenuOpen by remember { mutableStateOf(false) }
    val elevation by animateDpAsState(
        targetValue   = if (isDragTarget) 16.dp else 0.dp,
        animationSpec = tween(150),
        label         = "card_elev"
    )
    val scale by animateFloatAsState(
        targetValue   = if (isDragTarget) 1.04f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label         = "card_scale"
    )
    val alpha by animateFloatAsState(
        targetValue   = if (isDragTarget) 0.82f else 1f,
        animationSpec = tween(150),
        label         = "card_alpha"
    )

    // Drop target highlight colour
    val dropHighlight by animateFloatAsState(
        targetValue   = if (isDropTarget) 1f else 0f,
        animationSpec = tween(120),
        label         = "drop_highlight"
    )

    Box(
        modifier = Modifier
            .onGloballyPositioned { coords ->
                onHeightMeasured(coords.size.height.toFloat())
                onGeometryChanged?.invoke(coords.positionInRoot(), coords.size.width.toFloat())
            }
            .zIndex(if (isDragTarget || isLanding) 1f else 0f)
            .graphicsLayer {
                // DROP-INTO-FOLDER LANDING FEATURE: once the flight starts,
                // it fully takes over the card's transform — the normal
                // drag/idle scale+alpha animations are ignored for this card
                // until the flight ends and isLanding goes back to false.
                if (isLanding) {
                    scaleX       = landingScale
                    scaleY       = landingScale
                    this.alpha   = landingAlpha
                    shadowElevation = elevation.toPx()
                    translationX = landingOffsetX
                    translationY = landingOffsetY
                } else {
                    scaleX       = scale
                    scaleY       = scale
                    this.alpha   = alpha
                    shadowElevation = elevation.toPx()
                    translationY = if (isDragTarget) dragOffsetY else 0f
                }
            }
            // Drop-target accent border
            .then(
                if (isDropTarget)
                    Modifier.border(
                        width = 2.dp,
                        color = CometTail.copy(alpha = dropHighlight),
                        shape = RoundedCornerShape(16.dp)
                    )
                else Modifier
            )
            // LONG-PRESS DISAMBIGUATION: a long-press that's released without
            // moving opens the Edit/Delete menu; a long-press that turns into
            // a drag reorders the card. Both start from the same gesture, so
            // they're told apart by whether the finger has moved past the
            // system touch-slop by the time it lifts — `onDragStart()` (the
            // caller's reorder-drag callback, which flips isDragging/haptics)
            // is deliberately deferred until that threshold is crossed, so a
            // plain long-press-then-release never visually "picks up" the
            // card before falling back to opening the menu.
            // BUGFIX-UI-9: مُعطّل بالكامل أثناء فتح القائمة (isMenuOpen) بدل
            // ترك كاشف السحب الطويل نشطاً فوق منطقة قائمة مفتوحة.
            // PIN-CONNECTION FEATURE: also disabled while isSelectionMode is
            // active — a long-press mid-selection would otherwise still try
            // to open the menu or start a reorder drag, fighting with the
            // plain-tap-to-toggle-selection behavior RdpProfileCard's own
            // whole-card tap layer switches to in that state.
            .then(
                if (!isMenuOpen && !isSelectionMode)
                    Modifier.pointerInput(Unit) {
                        var moved = false
                        detectDragGesturesAfterLongPress(
                            onDragStart = { moved = false },
                            onDrag      = { change, dragAmount ->
                                if (!moved &&
                                    (kotlin.math.abs(dragAmount.x) > touchSlop || kotlin.math.abs(dragAmount.y) > touchSlop)
                                ) {
                                    moved = true
                                    onDragStart()
                                }
                                if (moved) {
                                    change.consume()
                                    onDrag(dragAmount.y)
                                }
                            },
                            onDragEnd    = {
                                if (moved) onDragEnd() else isMenuOpen = true
                                moved = false
                            },
                            onDragCancel = {
                                if (moved) onDragEnd()
                                moved = false
                            }
                        )
                    }
                else Modifier
            )
    ) {
        RdpProfileCard(
            profile        = profile,
            onConnect      = onConnect,
            onEdit         = onEdit,
            onDelete       = onDelete,
            onWakeOnLan    = onWakeOnLan,
            onWakeAndConnect = onWakeAndConnect,
            onPinShortcut  = onPinShortcut,
            onOpenSnmpDashboard = onOpenSnmpDashboard,
            onTagClick     = onTagClick,
            onToggleFavorite = onToggleFavorite,
            onTogglePin    = onTogglePin,
            onDuplicate    = onDuplicate,
            onShareQr      = onShareQr,
            isSelectionMode  = isSelectionMode,
            isSelected       = isSelected,
            onToggleSelect   = onToggleSelect,
            statusInfo     = statusInfo,
            hapticEnabled  = hapticEnabled,   // FIX #1
            actionPending  = actionPending,   // BUGFIX-UI-1
            menuExpanded   = isMenuOpen,
            onMenuExpandedChange = { isMenuOpen = it },  // BUGFIX-UI-9
        )
        // UI FIX: removed the standalone DragHandle "≡" mark that sat on top of
        // the card — it was a redundant visual mark since long-pressing anywhere
        // on the card already starts the reorder drag (see pointerInput above).
    }
}

// ── Wake & Connect progress ───────────────────────────────────────────────────
// Small helper so the dialog below can look up which profile a given state
// refers to (for the "Waking <name>…" title) without a `when` at every call site.
private fun MainViewModel.WakeConnectState.profileIdOrNull(): String? = when (this) {
    is MainViewModel.WakeConnectState.SendingPacket    -> profileId
    is MainViewModel.WakeConnectState.WaitingForHost   -> profileId
    is MainViewModel.WakeConnectState.HostOnline       -> profileId
    is MainViewModel.WakeConnectState.Connecting       -> profileId
    is MainViewModel.WakeConnectState.Failed           -> profileId
    MainViewModel.WakeConnectState.Idle                -> null
}

/**
 * Shows live progress for the "Wake & Connect" action:
 * Sending Wake-on-LAN packet… → Waiting for the computer to start… →
 * Computer is online. → Connecting… (auto-dismisses once the session
 * Activity launches) or Wake failed. (with a Dismiss action).
 *
 * Purely a reflection of [MainViewModel.wakeConnectState] — all the actual
 * networking runs in the ViewModel on Dispatchers.IO, so this dialog never
 * blocks the UI thread; it just renders whatever state is current and lets
 * the person cancel or dismiss at any point.
 */
@Composable
private fun WakeConnectProgressDialog(
    state:       MainViewModel.WakeConnectState,
    profileName: String?,
    onDismiss:   () -> Unit,
    onCancel:    () -> Unit,
) {
    if (state == MainViewModel.WakeConnectState.Idle) return

    val title = profileName ?: stringResource(R.string.wol_wake_and_connect)
    val (message, showSpinner, isTerminal) = when (state) {
        is MainViewModel.WakeConnectState.SendingPacket ->
            Triple(stringResource(R.string.wol_progress_sending), true, false)
        is MainViewModel.WakeConnectState.WaitingForHost ->
            Triple(
                if (state.attempt > 0)
                    stringResource(R.string.wol_progress_waiting_attempt, state.attempt, state.maxAttempts)
                else
                    stringResource(R.string.wol_progress_waiting),
                true, false
            )
        is MainViewModel.WakeConnectState.HostOnline ->
            Triple(stringResource(R.string.wol_progress_online), false, false)
        is MainViewModel.WakeConnectState.Connecting ->
            Triple(stringResource(R.string.wol_progress_connecting), true, false)
        is MainViewModel.WakeConnectState.Failed ->
            Triple(
                when (state.reason) {
                    MainViewModel.WakeConnectState.FailReason.SEND_FAILED -> stringResource(R.string.wol_error)
                    MainViewModel.WakeConnectState.FailReason.TIMEOUT     -> stringResource(R.string.wol_progress_failed)
                },
                false, true
            )
        MainViewModel.WakeConnectState.Idle -> Triple("", false, true)
    }

    AlertDialog(
        onDismissRequest = { if (isTerminal) onDismiss() },
        containerColor   = NebulaSurface,
        shape            = RoundedCornerShape(20.dp),
        title            = { Text(title, color = StarDust, fontWeight = FontWeight.SemiBold) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showSpinner) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color       = PulsarCyan
                    )
                    Spacer(Modifier.width(12.dp))
                } else if (state is MainViewModel.WakeConnectState.Failed) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                } else if (state is MainViewModel.WakeConnectState.HostOnline) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = ConnectedGreen, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Text(message, color = CometTail, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            if (isTerminal) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            } else {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}


// New home for what used to be a standalone "Import" icon on the bottom bar.
// Opened by a single tap on the + FAB; long-press still goes straight to
// Quick Connect as before. See the IA CHANGE notes on SpaceBottomBar.
// PERF-FIX: bundles all six of HomeScreen's dialog triggers into one call so
// Compose can skip the whole group as a single unit whenever none of these
// specific values changed (e.g. while the user is typing in the search bar,
// which invalidates HomeScreen's own scope but never touches any of these).
@Composable
private fun HomeDialogs(
    showAddOptions: Boolean,
    onDismissAddOptions: () -> Unit,
    onNewConnection: () -> Unit,
    onImportFile: () -> Unit,
    onScanQr: () -> Unit,
    onDiscoverDevices: () -> Unit,
    onWebFeed: () -> Unit,
    // QUICK-TRANSFER FEATURE: opens the small FTP/SMB/WebDAV/TFTP picker
    // sheet from the same "+" chooser as New/Import/Scan/Discover/Web Feed.
    onQuickTransfer: () -> Unit,
    showQuickTransferPicker: Boolean,
    onDismissQuickTransferPicker: () -> Unit,
    onSelectQuickTransfer: (QuickTransferType) -> Unit,
    activeQuickTransferDialog: QuickTransferType?,
    onDismissQuickTransferDialog: () -> Unit,
    showAddDialog: Boolean,
    initialProtocolType: ProtocolType?,
    onDismissAddDialog: () -> Unit,
    onSaveNewProfile: (RdpProfile) -> Unit,
    pendingImport: RdpProfile?,
    onDismissImport: () -> Unit,
    onSaveImport: (RdpProfile) -> Unit,
    pendingQr: RdpProfile?,
    onDismissQr: () -> Unit,
    onSaveQr: (RdpProfile) -> Unit,
    showQuickConnect: Boolean,
    onDismissQuickConnect: () -> Unit,
    onQuickConnect: (host: String, port: Int, username: String, password: String) -> Unit,
    editingProfile: RdpProfile?,
    onDismissEdit: () -> Unit,
    onSaveEdit: (RdpProfile) -> Unit,
    deletingProfile: RdpProfile?,
    onDismissDelete: () -> Unit,
    onConfirmDelete: (RdpProfile) -> Unit,
    // HOME-SCREEN-SHORTCUTS FEATURE: naming dialog shown before a pinned
    // shortcut is actually requested — see CreateShortcutDialog.
    shortcutProfile: RdpProfile?,
    onDismissShortcut: () -> Unit,
    onConfirmShortcut: (RdpProfile, String) -> Unit,
    // ENTRA-ID-AUTH FEATURE: see MainViewModel.signInWithMicrosoft/
    // signOutMicrosoft doc comments. onSignInWithMicrosoft/onSignOutMicrosoft
    // take the profileId to link/unlink — HomeDialogs supplies the right one
    // per ProfileFormDialog instance below (newProfilePendingId for the
    // "add new connection" dialog, profile.id everywhere a real RdpProfile
    // already exists).
    entraSignInPending: Boolean = false,
    newProfilePendingId: String = "",
    onSignInWithMicrosoft: (profileId: String) -> Unit = {},
    onSignOutMicrosoft: (profileId: String) -> Unit = {},
) {
    if (showAddOptions) {
        AddOptionsDialog(
            onDismiss       = onDismissAddOptions,
            onNewConnection = onNewConnection,
            onImportFile    = onImportFile,
            onScanQr        = onScanQr,
            onDiscoverDevices = onDiscoverDevices,
            onWebFeed       = onWebFeed,
            onQuickTransfer = onQuickTransfer,
        )
    }

    // QUICK-TRANSFER FEATURE: small picker sheet, then whichever standalone
    // transfer dialog was chosen. Both live entirely outside the
    // RdpProfile/ProtocolType session system — see QuickTransferType.
    if (showQuickTransferPicker) {
        QuickTransferPickerDialog(
            onDismiss = onDismissQuickTransferPicker,
            onSelect  = onSelectQuickTransfer,
        )
    }

    when (activeQuickTransferDialog) {
        QuickTransferType.FTP    -> FtpTransferDialog(onDismiss = onDismissQuickTransferDialog)
        QuickTransferType.SMB    -> SmbTransferDialog(onDismiss = onDismissQuickTransferDialog)
        QuickTransferType.WEBDAV -> WebDavTransferDialog(onDismiss = onDismissQuickTransferDialog)
        QuickTransferType.TFTP   -> TftpTransferDialog(onDismiss = onDismissQuickTransferDialog)
        QuickTransferType.NFS    -> NfsTransferDialog(onDismiss = onDismissQuickTransferDialog)
        null -> Unit
    }

    if (showAddDialog) {
        ProfileFormDialog(
            initialProtocolType = initialProtocolType,
            onDismiss = onDismissAddDialog,
            onSave    = onSaveNewProfile,
            onSignInWithMicrosoft = { onSignInWithMicrosoft(newProfilePendingId) },
            onSignOutMicrosoft    = { onSignOutMicrosoft(newProfilePendingId) },
            entraSignInPending    = entraSignInPending,
            pendingProfileId      = newProfilePendingId,
        )
    }

    // Import .rdp — shows ProfileFormDialog pre-filled with parsed data
    pendingImport?.let { importedProfile ->
        ProfileFormDialog(
            profile   = importedProfile,
            onDismiss = onDismissImport,
            onSave    = onSaveImport,
            onSignInWithMicrosoft = { onSignInWithMicrosoft(importedProfile.id) },
            onSignOutMicrosoft    = { onSignOutMicrosoft(importedProfile.id) },
            entraSignInPending    = entraSignInPending,
        )
    }

    // Scan QR Code — shows ProfileFormDialog pre-filled with parsed data,
    // same review-before-save pattern as the .rdp import above.
    pendingQr?.let { scannedProfile ->
        ProfileFormDialog(
            profile   = scannedProfile,
            onDismiss = onDismissQr,
            onSave    = onSaveQr,
            onSignInWithMicrosoft = { onSignInWithMicrosoft(scannedProfile.id) },
            onSignOutMicrosoft    = { onSignOutMicrosoft(scannedProfile.id) },
            entraSignInPending    = entraSignInPending,
        )
    }

    // UX-08: Quick connect — no profile saved, immediate connection
    if (showQuickConnect) {
        QuickConnectDialog(
            onDismiss = onDismissQuickConnect,
            onConnect = onQuickConnect
        )
    }

    editingProfile?.let { profile ->
        ProfileFormDialog(
            profile   = profile,
            onDismiss = onDismissEdit,
            onSave    = onSaveEdit,
            onSignInWithMicrosoft = { onSignInWithMicrosoft(profile.id) },
            onSignOutMicrosoft    = { onSignOutMicrosoft(profile.id) },
            entraSignInPending    = entraSignInPending,
        )
    }

    deletingProfile?.let { profile ->
        DeleteConfirmDialog(
            profileName = profile.name,
            onConfirm   = { onConfirmDelete(profile) },
            onDismiss   = onDismissDelete
        )
    }

    shortcutProfile?.let { profile ->
        CreateShortcutDialog(
            profile   = profile,
            onConfirm = { shortcutName -> onConfirmShortcut(profile, shortcutName) },
            onDismiss = onDismissShortcut
        )
    }
}

// ── Guest Mode banner (REQ-3: Forgot PIN → Continue as Guest) ────────────────
// A persistent, unmissable indicator that this is the isolated, temporary
// Guest profile — never the primary user's real saved connections, passwords,
// settings, or history — plus the only way back to Primary.
@Composable
private fun GuestModeBanner(onSwitchToPrimary: () -> Unit) {
    Surface(
        color    = ConnectingAmber.copy(alpha = 0.14f),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                tint = ConnectingAmber,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.guest_mode_banner_label),
                    color      = StarDust,
                    fontWeight = FontWeight.Bold,
                    style      = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.guest_mode_banner_desc),
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onSwitchToPrimary) {
                Text(stringResource(R.string.guest_mode_switch_primary), color = PulsarCyan, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Folder create/rename/delete dialogs (FOLDERS-UI feature) ─────────────────
@Composable
private fun FolderDialogs(
    showNewFolderDialog:   Boolean,
    onDismissNewFolder:    () -> Unit,
    // FOLDER-APPEARANCE FEATURE: (name, color, icon) — color/icon are
    // FolderColor/FolderIcon enum names, or "" if left unset.
    onCreateFolder:        (String, String, String) -> Unit,
    renamingFolder:        ConnectionFolder?,
    onDismissRename:       () -> Unit,
    onConfirmRename:       (ConnectionFolder, String, String, String) -> Unit,
    deletingFolder:        ConnectionFolder?,
    onDismissDeleteFolder: () -> Unit,
    onConfirmDeleteFolder: (ConnectionFolder) -> Unit,
) {
    if (showNewFolderDialog) {
        NewFolderDialog(
            onConfirm = onCreateFolder,
            onDismiss = onDismissNewFolder
        )
    }
    renamingFolder?.let { folder ->
        RenameFolderDialog(
            folder    = folder,
            onConfirm = { newName, color, icon -> onConfirmRename(folder, newName, color, icon) },
            onDismiss = onDismissRename
        )
    }
    deletingFolder?.let { folder ->
        DeleteFolderDialog(
            folder    = folder,
            onConfirm = { onConfirmDeleteFolder(folder) },
            onDismiss = onDismissDeleteFolder
        )
    }
}

@Composable
private fun AddOptionsDialog(
    onDismiss:       () -> Unit,
    onNewConnection: () -> Unit,
    onImportFile:    () -> Unit,
    onScanQr:        () -> Unit,
    onDiscoverDevices: () -> Unit,
    onWebFeed:       () -> Unit,
    onQuickTransfer: () -> Unit,
) {
    val accent    = PulsarCyan
    val secondary = QuantumBlue

    // DEBRIS-ASSEMBLE ENTRANCE (ROCKET-LAUNCH FEATURE): the header + each of
    // the 3 options fly in from their own scattered offset/rotation and
    // settle into place with a slight bouncy overshoot, staggered a beat
    // apart — reading as fragments of the rocket's explosion assembling
    // into this sheet, instead of the sheet just fading in as a whole.
    val entranceCount = 7 // header + 6 options
    val entranceProgress = remember { List(entranceCount) { Animatable(0f) } }
    LaunchedEffect(Unit) {
        entranceProgress.forEachIndexed { index, anim ->
            launch {
                delay(index * 60L)
                anim.animateTo(
                    targetValue   = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness    = Spring.StiffnessLow
                    )
                )
            }
        }
    }

    // A single fading shockwave ring behind the sheet, once, on open —
    // reinforces that this sheet is the aftermath of the explosion.
    val shockwave = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        shockwave.animateTo(1f, tween(520, easing = LinearOutSlowInEasing))
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties       = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier         = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            // Shockwave ring — drawn behind the sheet, unclipped, so it can
            // spread beyond the sheet's own rounded-corner bounds.
            androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                val maxR = this.size.maxDimension * 0.5f
                drawCircle(
                    color  = accent.copy(alpha = (1f - shockwave.value) * 0.30f),
                    radius = maxR * shockwave.value,
                    style  = Stroke(width = 3.dp.toPx() * (1f - shockwave.value * 0.6f))
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(NebulaSurface)
                    // Faint glowing outline on the dialog itself — the same
                    // accent-tinted border treatment used on cards/docks
                    // elsewhere, so the sheet reads as part of the app
                    // instead of a bare stock dialog.
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(listOf(accent.copy(0.30f), Color.Transparent, accent.copy(0.12f))),
                        shape = RoundedCornerShape(22.dp)
                    )
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                DebrisEntrance(
                    progress     = entranceProgress[0].value,
                    fromOffsetX  = (-36).dp,
                    fromOffsetY  = (-26).dp,
                    fromRotation = -16f
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Icon badge reuses the same rocket art as the FAB
                        // itself, so the header visually ties back to what
                        // just launched — no separate "+" glyph needed.
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(listOf(secondary, accent))),
                            contentAlignment = Alignment.Center
                        ) {
                            RocketIcon(flameBoost = 0f, liftProgress = 0f, size = 22.dp, accent = Color.White, secondary = accent)
                        }
                        Text(stringResource(R.string.add_connection), color = StarDust,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }

                // UI-FIX (design feedback): this row previously reused the same
                // "+" glyph as the sheet's own header badge just above it — two
                // identical add-icons stacked in the same small area read as a
                // clash rather than two distinct choices. Dns (a host/server
                // glyph) reads clearly as "connect to a host" and is visually
                // distinct from the header's plain plus.
                DebrisEntrance(
                    progress     = entranceProgress[1].value,
                    fromOffsetX  = (-64).dp,
                    fromOffsetY  = 34.dp,
                    fromRotation = -24f
                ) {
                    AddOptionRow(
                        icon        = Icons.Outlined.Dns,
                        title       = stringResource(R.string.add_options_new_title),
                        description = stringResource(R.string.add_options_new_desc),
                        accent      = accent,
                        onClick     = onNewConnection
                    )
                }
                DebrisEntrance(
                    progress     = entranceProgress[2].value,
                    fromOffsetX  = 58.dp,
                    fromOffsetY  = 48.dp,
                    fromRotation = 20f
                ) {
                    AddOptionRow(
                        icon        = Icons.Outlined.FolderOpen,
                        title       = stringResource(R.string.import_rdp_label),
                        description = stringResource(R.string.add_options_import_desc),
                        accent      = VoidPurple,
                        onClick     = onImportFile
                    )
                }
                DebrisEntrance(
                    progress     = entranceProgress[3].value,
                    fromOffsetX  = (-48).dp,
                    fromOffsetY  = 58.dp,
                    fromRotation = 26f
                ) {
                    AddOptionRow(
                        icon        = Icons.Outlined.QrCodeScanner,
                        title       = stringResource(R.string.add_options_scan_title),
                        description = stringResource(R.string.add_options_scan_desc),
                        accent      = SolarFlare,
                        onClick     = onScanQr
                    )
                }
                DebrisEntrance(
                    progress     = entranceProgress[4].value,
                    fromOffsetX  = 40.dp,
                    fromOffsetY  = 66.dp,
                    fromRotation = -18f
                ) {
                    AddOptionRow(
                        icon        = Icons.Outlined.Wifi,
                        title       = stringResource(R.string.add_options_discover_title),
                        description = stringResource(R.string.add_options_discover_desc),
                        accent      = PlasmaGreen,
                        onClick     = onDiscoverDevices
                    )
                }
                DebrisEntrance(
                    progress     = entranceProgress[5].value,
                    fromOffsetX  = (-40).dp,
                    fromOffsetY  = 74.dp,
                    fromRotation = 18f
                ) {
                    AddOptionRow(
                        icon        = Icons.Outlined.Cloud,
                        title       = stringResource(R.string.add_options_webfeed_title),
                        description = stringResource(R.string.add_options_webfeed_desc),
                        accent      = QuantumBlue,
                        onClick     = onWebFeed
                    )
                }
                // QUICK-TRANSFER FEATURE: opens the FTP/SMB/WebDAV/TFTP picker
                // sheet — a quick, unsaved transfer session, not a new
                // connection profile, hence the distinct "swap" glyph rather
                // than another host/server icon.
                DebrisEntrance(
                    progress     = entranceProgress[6].value,
                    fromOffsetX  = 46.dp,
                    fromOffsetY  = 82.dp,
                    fromRotation = -22f
                ) {
                    AddOptionRow(
                        icon        = Icons.Outlined.SwapHoriz,
                        title       = stringResource(R.string.add_options_quick_transfer_title),
                        description = stringResource(R.string.add_options_quick_transfer_desc),
                        accent      = PlasmaGreen,
                        onClick     = onQuickTransfer
                    )
                }

                SpaceButton(stringResource(R.string.cancel), onDismiss, variant = ButtonVariant.GHOST, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// QUICK-TRANSFER FEATURE: small chooser between the 4 standalone
// FTP/SMB/WebDAV/TFTP transfer dialogs, reached from AddOptionsDialog's new
// "Quick Transfer" row above. Reuses the same NebulaSurface card + accent
// border + AddOptionRow language as AddOptionsDialog so it reads as part of
// the same "+" flow rather than a bolted-on sheet. Each of the 4 dialogs it
// opens (FtpTransferDialog, SmbTransferDialog, WebDavTransferDialog,
// TftpTransferDialog) is fully self-contained and unrelated to
// RdpProfile/ProtocolType — see QuickTransferType.
@Composable
private fun QuickTransferPickerDialog(
    onDismiss: () -> Unit,
    onSelect:  (QuickTransferType) -> Unit,
) {
    val accent = PulsarCyan

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties       = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(NebulaSurface)
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(listOf(accent.copy(0.30f), Color.Transparent, accent.copy(0.12f))),
                        shape = RoundedCornerShape(22.dp)
                    )
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.linearGradient(listOf(QuantumBlue, accent))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.SwapHoriz, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Text(stringResource(R.string.quick_transfer_picker_title), color = StarDust,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                }

                AddOptionRow(
                    icon        = Icons.Outlined.CloudUpload,
                    title       = stringResource(R.string.quick_transfer_ftp_title),
                    description = stringResource(R.string.quick_transfer_ftp_desc),
                    accent      = PulsarCyan,
                    onClick     = { onSelect(QuickTransferType.FTP) }
                )
                AddOptionRow(
                    icon        = Icons.Outlined.Storage,
                    title       = stringResource(R.string.quick_transfer_smb_title),
                    description = stringResource(R.string.quick_transfer_smb_desc),
                    accent      = VoidPurple,
                    onClick     = { onSelect(QuickTransferType.SMB) }
                )
                AddOptionRow(
                    icon        = Icons.Outlined.Public,
                    title       = stringResource(R.string.quick_transfer_webdav_title),
                    description = stringResource(R.string.quick_transfer_webdav_desc),
                    accent      = SolarFlare,
                    onClick     = { onSelect(QuickTransferType.WEBDAV) }
                )
                AddOptionRow(
                    icon        = Icons.Outlined.Router,
                    title       = stringResource(R.string.quick_transfer_tftp_title),
                    description = stringResource(R.string.quick_transfer_tftp_desc),
                    accent      = PlasmaGreen,
                    onClick     = { onSelect(QuickTransferType.TFTP) }
                )
                AddOptionRow(
                    icon        = Icons.Outlined.Storage,
                    title       = stringResource(R.string.quick_transfer_nfs_title),
                    description = stringResource(R.string.quick_transfer_nfs_desc),
                    accent      = SolarFlare,
                    onClick     = { onSelect(QuickTransferType.NFS) }
                )

                SpaceButton(stringResource(R.string.cancel), onDismiss, variant = ButtonVariant.GHOST, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ROCKET-LAUNCH FEATURE: wraps a single header/option row so it can fly in
// from a scattered starting offset+rotation+shrink and settle into its
// normal identity transform as `progress` goes from 0 to 1 — one "shard"
// of the debris-assemble entrance used by AddOptionsDialog.
@Composable
private fun DebrisEntrance(
    progress:     Float,
    fromOffsetX:  Dp,
    fromOffsetY:  Dp,
    fromRotation: Float,
    content:      @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier.graphicsLayer {
            val remaining = 1f - progress
            translationX = with(density) { fromOffsetX.toPx() } * remaining
            translationY = with(density) { fromOffsetY.toPx() } * remaining
            rotationZ    = fromRotation * remaining
            scaleX       = 0.6f + 0.4f * progress
            scaleY       = 0.6f + 0.4f * progress
            this.alpha   = progress.coerceIn(0f, 1f)
        }
    ) {
        content()
    }
}


// Same gradient-card + accent-border + icon-badge + chevron language as
// SettingsCategoryCard (SettingsScreen.kt) — reused here so the two "Add
// Connection" choices feel like the rest of the app instead of a plain
// generic list row.
@Composable
private fun AddOptionRow(
    icon:        androidx.compose.ui.graphics.vector.ImageVector,
    title:       String,
    description: String,
    accent:      Color,
    onClick:     () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                brush = Brush.linearGradient(listOf(GradientCardStart, GradientCardEnd)),
                shape = RoundedCornerShape(16.dp)
            )
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
            .pressScale(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.14f)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color      = StarDust,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.height(1.dp))
                Text(description, color = CometTail, style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = CometTail, modifier = Modifier.size(20.dp))
        }
    }
}

// ── UX-08: Quick Connect Dialog ───────────────────────────────────────────────
@Composable
private fun QuickConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (host: String, port: Int, username: String, password: String) -> Unit,
) {
    // SECURITY FIX: contains a password field — see security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()
    var hostPort      by remember { mutableStateOf("") }
    var username      by remember { mutableStateOf("") }
    var password      by remember { mutableStateOf("") }
    var showPass      by remember { mutableStateOf(false) }
    // FIX-VALID: track whether Connect was attempted so we only show
    // validation errors after the first press (not on first render).
    var connectTried  by remember { mutableStateOf(false) }
    val accent        = PulsarCyan

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = NebulaSurface,
        shape            = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.FlashOn, null, tint = accent, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.quick_connect_title), color = StarDust,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.quick_connect_dialog_desc),
                    color = CometTail, style = MaterialTheme.typography.bodySmall)

                // Host:Port field
                // UX-FIX: previously this field was silently required — the
                // confirmButton's `valid` check already blocked Connect when
                // it was blank, but nothing on the field itself told the user
                // *why* nothing happened when they tapped Connect. Now mirrors
                // the username field's error border/label/supporting-text
                // treatment below, revealed the same way (only after the
                // first Connect attempt).
                val hostIsEmpty = connectTried && hostPort.isBlank()
                OutlinedTextField(
                    value         = hostPort,
                    // I18N-FIX: normalize Arabic-Indic/Extended Arabic-Indic
                    // digits to ASCII as the user types/pastes, so the
                    // host:port parsing below (and the port range check)
                    // behaves identically for either digit style.
                    onValueChange = { hostPort = it.normalizeDigits() },
                    label         = { Text(stringResource(R.string.field_host_port), color = if (hostIsEmpty) SolarFlare else CometTail) },
                    placeholder   = { Text(stringResource(R.string.quick_connect_placeholder), color = CometTail.copy(alpha = 0.4f)) },
                    isError       = hostIsEmpty,
                    supportingText = if (hostIsEmpty) ({
                        Text(stringResource(R.string.error_host_required), color = SolarFlare,
                            style = MaterialTheme.typography.labelSmall)
                    }) else null,
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = if (hostIsEmpty) SolarFlare else accent,
                        unfocusedBorderColor = if (hostIsEmpty) SolarFlare else HorizonGray.copy(alpha = 0.4f),
                        focusedTextColor     = StarDust,
                        unfocusedTextColor   = StarDust,
                        errorBorderColor     = SolarFlare,
                        errorLabelColor      = SolarFlare,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                // FIX-VALID: show error border + supporting text when username is blank
                // after the user has pressed Connect at least once.
                val usernameIsEmpty = connectTried && username.isBlank()
                OutlinedTextField(
                    value         = username,
                    onValueChange = { username = it },
                    label         = { Text(stringResource(R.string.username), color = if (usernameIsEmpty) SolarFlare else CometTail) },
                    isError       = usernameIsEmpty,
                    supportingText = if (usernameIsEmpty) ({
                        Text(stringResource(R.string.error_username_required), color = SolarFlare,
                            style = MaterialTheme.typography.labelSmall)
                    }) else null,
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = if (usernameIsEmpty) SolarFlare else accent,
                        unfocusedBorderColor = if (usernameIsEmpty) SolarFlare else HorizonGray.copy(alpha = 0.4f),
                        focusedTextColor     = StarDust,
                        unfocusedTextColor   = StarDust,
                        errorBorderColor     = SolarFlare,
                        errorLabelColor      = SolarFlare,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                // UX-FIX: password is intentionally NOT treated as a hard "required"
                // field — ProfileFormDialog and every other connection entry point in
                // the app already allow a blank password (some servers/accounts don't
                // need one), so Quick Connect shouldn't silently start enforcing a rule
                // the rest of the app doesn't. What was actually missing is any signal
                // at all that the field is empty — a blank password is also the single
                // most common reason a Quick Connect attempt fails right after tapping
                // Connect, so this shows a plain (non-error, non-blocking) reminder
                // instead of a red "required" state.
                val passwordIsEmpty = connectTried && password.isBlank()
                OutlinedTextField(
                    value         = password,
                    onValueChange = { password = it },
                    label         = { Text(stringResource(R.string.password), color = CometTail) },
                    supportingText = if (passwordIsEmpty) ({
                        Text(stringResource(R.string.hint_password_optional_quick_connect), color = ConnectingAmber,
                            style = MaterialTheme.typography.labelSmall)
                    }) else null,
                    singleLine    = true,
                    visualTransformation = if (showPass)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(
                                if (showPass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                // BUGFIX-UI: was contentDescription = null — no adjacent
                                // text label on this icon-only toggle.
                                contentDescription = stringResource(
                                    if (showPass) R.string.cd_hide_password else R.string.cd_show_password
                                ),
                                tint = CometTail,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = accent,
                        unfocusedBorderColor = HorizonGray.copy(alpha = 0.4f),
                        focusedTextColor     = StarDust,
                        unfocusedTextColor   = StarDust,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            // FIX B4: دعم عناوين IPv6 في QuickConnect.
            // الكود القديم كان يستخدم split(":") مما يكسر العناوين مثل [2001:db8::1]:3389
            // الآن نستخدم نفس منطق RdpFileParser لمعالجة الحالات الثلاث:
            //   1. [IPv6]:port   مثل "[::1]:3389"
            //   2. host:port     مثل "192.168.1.1:3389"
            //   3. host فقط     مثل "myserver.local"
            val raw = hostPort.trim()
            val host: String
            val port: Int
            when {
                raw.startsWith("[") -> {
                    // IPv6 بصيغة أقواس: "[2001:db8::1]:3390" أو "[::1]"
                    val closing = raw.indexOf(']')
                    if (closing >= 0) {
                        host = raw.substring(0, closing + 1)
                        port = raw.getOrNull(closing + 1)
                            ?.takeIf { it == ':' }
                            ?.let { raw.substring(closing + 2).toIntOrNull() }
                            ?: 3389
                    } else {
                        host = raw
                        port = 3389
                    }
                }
                raw.contains(":") && !raw.startsWith("[") -> {
                    // IPv4 أو اسم مضيف مع port: "192.168.1.1:3389"
                    val lastColon = raw.lastIndexOf(':')
                    val possiblePort = raw.substring(lastColon + 1).toIntOrNull()
                    if (possiblePort != null) {
                        host = raw.substring(0, lastColon)
                        port = possiblePort
                    } else {
                        host = raw
                        port = 3389
                    }
                }
                else -> {
                    host = raw
                    port = 3389
                }
            }
            // FIX-VALID: valid only when both host AND username are non-blank.
            val valid = host.isNotBlank() && username.isNotBlank()
            SpaceButton(
                text     = stringResource(R.string.connect),
                onClick  = {
                    connectTried = true   // FIX-VALID: reveal validation errors after first press
                    if (valid) onConnect(host, port, username, password)
                },
                modifier = Modifier.fillMaxWidth(),
                variant  = if (valid) ButtonVariant.PRIMARY else ButtonVariant.GHOST
            )
        },
        dismissButton = {
            SpaceButton(stringResource(R.string.cancel), onDismiss, variant = ButtonVariant.GHOST, modifier = Modifier.fillMaxWidth())
        }
    )
}
