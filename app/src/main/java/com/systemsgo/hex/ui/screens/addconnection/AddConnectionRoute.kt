package com.systemsgo.hex.ui.screens.addconnection

/**
 * ADD-CONNECTION PROTOCOL PICKER (Part 2/2) — navigation host.
 *
 * Hosted at the "add_connection_protocol" NavHost route (see MainActivity),
 * this is what HomeScreen's onNewConnection now navigates to instead of
 * opening ProfileFormDialog directly (see HomeScreen's "IA CHANGE" notes).
 *
 * Owns the one decision the picker screen itself deliberately doesn't make
 * (see AddConnectionProtocolScreen's class doc): what happens after the user
 * picks a protocol. Branches on [ProtocolCatalogEntry.launchKind], not just
 * whether [ProtocolCatalogEntry.protocolType] is null — a protocol can be
 * fully working without going through the saved-profile flow at all:
 *   - SAVED_CONNECTION → this app already has a session client for it, so
 *     open the *existing* connection editor (ProfileFormDialog) pre-selected
 *     to that protocol — exactly the same dialog/call shape HomeScreen's
 *     "add new connection" flow has always used (see DeviceDiscoveryScreen's
 *     pendingDeviceProfile for the other call site following this same
 *     pattern), just triggered from here instead of straight off the +
 *     button.
 *   - STANDALONE_TRANSFER (FTP/FTPS, SMB, WebDAV, NFS) → these have their own
 *     working one-off dialog (no saved profile involved) that HomeScreen's
 *     Quick Transfer menu already opens directly — reuse that same dialog
 *     here instead of routing through the connection editor, which doesn't
 *     know how to configure them.
 *   - VIA_SSH_SESSION (SFTP, SCP) → there's no standalone "SFTP connection"
 *     to save; the file browser lives inside an already-open SSH session
 *     (RdpSessionActivity's FileTransferDialog). So the picker opens the SSH
 *     connection editor instead — once that SSH connection is saved and
 *     opened, its file-transfer panel is right there.
 *   - NOT_YET_SUPPORTED → no client exists yet: shows [RequestProtocolSheet]
 *     for that specific protocol.
 *
 * The bottom "Can't find what you need?" CTA on the picker screen itself
 * goes through the same sheet, just with entry = null (generic request,
 * not tied to any one catalog entry).
 */

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ProtocolCatalogEntry
import com.systemsgo.hex.data.model.ProtocolLaunchKind
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.ui.MainViewModel
import com.systemsgo.hex.ui.components.ProfileFormDialog
import com.systemsgo.hex.ui.screens.FtpTransferDialog
import com.systemsgo.hex.ui.screens.NfsTransferDialog
import com.systemsgo.hex.ui.screens.QrScannerActivity
import com.systemsgo.hex.ui.screens.SmbTransferDialog
import com.systemsgo.hex.ui.screens.TftpTransferDialog
import com.systemsgo.hex.ui.screens.WebDavTransferDialog
import com.systemsgo.hex.ui.theme.DeepSpace
import com.systemsgo.hex.ui.theme.NebulaSurface
import com.systemsgo.hex.ui.theme.PlasmaGreen
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.QuantumBlue
import com.systemsgo.hex.ui.theme.SolarFlare
import com.systemsgo.hex.ui.theme.StarDust
import com.systemsgo.hex.ui.theme.VoidPurple
import java.util.UUID
import kotlinx.coroutines.launch

/** Which standalone Quick Transfer dialog (if any) the "More" sheet opened — mirrors HomeScreen's old QuickTransferType. */
private enum class QuickTransferKind { FTP, SMB, WEBDAV, TFTP, NFS }

@Composable
fun AddConnectionRoute(
    navController: NavController,
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val homeState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val entraSignInPending by mainViewModel.entraSignInPending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Which protocol the editor should open on — null means "editor closed".
    var editorProtocolType by remember { mutableStateOf<ProtocolType?>(null) }
    // RDP-OVER-WEBSOCKET FEATURE: the picker has no general "preset" concept
    // for catalog entries (every other SAVED_CONNECTION entry just opens a
    // blank profile for its protocolType), so this is deliberately narrow —
    // one nullable template used only by the "rdp_over_websocket" entry
    // below, not a new general mechanism. Non-null only for the moment
    // between that entry being tapped and ProfileFormDialog opening; reset
    // to null alongside editorProtocolType on dismiss/save so it never
    // leaks into the next time the editor is opened from a different entry.
    var editorPresetProfile by remember { mutableStateOf<com.systemsgo.hex.data.model.RdpProfile?>(null) }

    // Which standalone Quick Transfer dialog (if any) is open — FTP/FTPS,
    // SMB, and WebDAV each have their own working one-off dialog with no
    // saved profile involved (see class doc's STANDALONE_TRANSFER branch).
    // Reached either by picking FTP/SMB/WebDAV/NFS straight from the grid,
    // or via the "More" sheet's Quick Transfer option below.
    var standaloneTransferEntry by remember { mutableStateOf<ProtocolCatalogEntry?>(null) }
    var quickTransferKind by remember { mutableStateOf<QuickTransferKind?>(null) }

    // Same reasoning as HomeScreen's newProfilePendingId: a stable id for the
    // profile this editor instance is (maybe) about to create, generated
    // fresh each time the editor opens so an abandoned Entra sign-in from a
    // previous attempt never gets attributed to the next one.
    var pendingProfileId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    LaunchedEffect(editorProtocolType) {
        if (editorProtocolType != null) pendingProfileId = UUID.randomUUID().toString()
    }

    // Request-a-protocol sheet target: null entry with showRequestSheet =
    // true means the generic "Can't find what you need?" CTA; a non-null
    // entry means the user picked a specific not-yet-supported protocol.
    var showRequestSheet by remember { mutableStateOf(false) }
    var requestSheetEntry by remember { mutableStateOf<ProtocolCatalogEntry?>(null) }

    // TOP-BAR QUICK ACTIONS (v2): Import file and Scan QR used to live behind
    // HomeScreen's + button chooser dialog (AddOptionsDialog). That dialog
    // added a tap in front of this very screen for no reason, so it's gone —
    // the + button now opens this screen directly, and Import/Scan QR moved
    // here as top-bar icon actions. The launcher/permission plumbing below
    // mirrors exactly what HomeScreen used to do for those two actions.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { mainViewModel.parseRdpUri(it, context.contentResolver) }
    }

    val qrScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val contents = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_CONTENT)
            if (!contents.isNullOrBlank()) {
                mainViewModel.parseQrContent(contents)
            }
        }
    }
    fun launchQrScanner() {
        qrScanLauncher.launch(QrScannerActivity.intent(context))
    }
    val cameraPermissionDeniedText = stringResource(R.string.error_camera_permission_denied)
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchQrScanner()
        } else {
            scope.launch { snackbarHostState.showSnackbar(cameraPermissionDeniedText) }
        }
    }

    // Import / Scan QR results are reviewed (pre-filled ProfileFormDialog)
    // before being saved — same pattern HomeScreen used.
    val pendingImport by mainViewModel.pendingImportProfile.collectAsStateWithLifecycle()
    val importError by mainViewModel.importError.collectAsStateWithLifecycle()
    LaunchedEffect(importError) {
        val msg = importError
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            mainViewModel.clearPendingImport()
        }
    }
    val pendingQr by mainViewModel.pendingQrProfile.collectAsStateWithLifecycle()
    val qrError by mainViewModel.qrError.collectAsStateWithLifecycle()
    LaunchedEffect(qrError) {
        val msg = qrError
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            mainViewModel.clearPendingQr()
        }
    }

    // "More" overflow — the 3 less-common chooser options (Discover Devices,
    // Web Feed, Quick Transfer) that don't need permanent top-bar real
    // estate of their own. See MoreAddOptionsSheet/QuickTransferPickerSheet.
    var showMoreOptions by remember { mutableStateOf(false) }
    var showQuickTransferPicker by remember { mutableStateOf(false) }

    AddConnectionProtocolScreen(
        onBack = { navController.popBackStack() },
        onProtocolChosen = { entry ->
            when (entry.launchKind) {
                ProtocolLaunchKind.SAVED_CONNECTION -> {
                    editorProtocolType = entry.protocolType
                    // RDP-OVER-WEBSOCKET FEATURE: this one catalog entry
                    // wants the editor to open with transportMode already
                    // set to WSS (and, per Components.kt's expandTransport/
                    // showOptionalSettings fix, that section already
                    // visible) instead of a plain blank RDP profile — see
                    // this consultation's reasoning for why a preset
                    // rather than a whole separate ProtocolType.
                    editorPresetProfile = if (entry.id == "rdp_over_websocket") {
                        com.systemsgo.hex.data.model.RdpProfile(
                            name = "", host = "", username = "", password = "",
                            transportMode = com.systemsgo.hex.rdp.transport.RdpTransportMode.WSS.name,
                        )
                    } else if (entry.protocolType == ProtocolType.WAKE_ON_LAN) {
                        // WAKE-ON-LAN-STANDALONE FEATURE: same preset-profile trick as
                        // rdp_over_websocket above — opens the editor with the
                        // Wake-on-LAN fields section already expanded/enabled
                        // (wolEnabled = true) instead of making the user find and
                        // flip that toggle themselves on a protocol that's *only*
                        // Wake-on-LAN. Host/username/password stay blank; ProfileFormDialog's
                        // `isWol` check (see Components.kt) means blank host doesn't
                        // block Save the way it would for RDP/SSH/etc.
                        com.systemsgo.hex.data.model.RdpProfile(
                            name = "", host = "", username = "", password = "",
                            wolEnabled = true,
                        )
                    } else null
                }
                ProtocolLaunchKind.STANDALONE_TRANSFER -> standaloneTransferEntry = entry
                ProtocolLaunchKind.VIA_SSH_SESSION -> editorProtocolType = ProtocolType.SSH
                ProtocolLaunchKind.NOT_YET_SUPPORTED -> {
                    requestSheetEntry = entry
                    showRequestSheet = true
                }
            }
        },
        onRequestProtocol = {
            requestSheetEntry = null
            showRequestSheet = true
        },
        onScanQr = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchQrScanner()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        onImportFile = {
            // Same "MIME filter is only a first line of defense" note as
            // HomeScreen's importLauncher used to have — MainViewModel's
            // parseRdpUri() does the real *.rdp extension check on return.
            importLauncher.launch(arrayOf("application/x-rdp", "application/octet-stream"))
        },
        onMoreOptions = { showMoreOptions = true },
    )

    // Connection editor — opened on top of the picker, pre-selected to the
    // chosen protocol. Saving (or cancelling) closes the editor; Save also
    // pops the picker off the back stack so the user lands back on Home
    // seeing their new connection, matching what happened before this
    // screen existed.
    editorProtocolType?.let { type ->
        ProfileFormDialog(
            initialProtocolType = type,
            profile = editorPresetProfile,
            folders = homeState.folders,
            onDismiss = { editorProtocolType = null; editorPresetProfile = null },
            onSave = { profile ->
                mainViewModel.addProfile(profile)
                editorProtocolType = null
                editorPresetProfile = null
                navController.popBackStack()
            },
            onSignInWithMicrosoft = {
                (context as? Activity)?.let { activity ->
                    mainViewModel.signInWithMicrosoft(activity, pendingProfileId)
                }
            },
            onSignOutMicrosoft = { mainViewModel.signOutMicrosoft(pendingProfileId) },
            entraSignInPending = entraSignInPending,
            pendingProfileId = pendingProfileId,
        )
    }

    // Import .rdp — shows ProfileFormDialog pre-filled with parsed data,
    // same review-before-save pattern as the connection editor above.
    // Saving also pops the picker off the back stack, same as New Connection.
    pendingImport?.let { importedProfile ->
        ProfileFormDialog(
            profile = importedProfile,
            onDismiss = { mainViewModel.clearPendingImport() },
            onSave = { profile ->
                mainViewModel.addProfile(profile)
                mainViewModel.clearPendingImport()
                navController.popBackStack()
            },
            onSignInWithMicrosoft = {
                (context as? Activity)?.let { activity ->
                    mainViewModel.signInWithMicrosoft(activity, importedProfile.id)
                }
            },
            onSignOutMicrosoft = { mainViewModel.signOutMicrosoft(importedProfile.id) },
            entraSignInPending = entraSignInPending,
        )
    }

    // Scan QR Code — mirrors the .rdp import flow immediately above.
    pendingQr?.let { scannedProfile ->
        ProfileFormDialog(
            profile = scannedProfile,
            onDismiss = { mainViewModel.clearPendingQr() },
            onSave = { profile ->
                mainViewModel.addProfile(profile)
                mainViewModel.clearPendingQr()
                navController.popBackStack()
            },
            onSignInWithMicrosoft = {
                (context as? Activity)?.let { activity ->
                    mainViewModel.signInWithMicrosoft(activity, scannedProfile.id)
                }
            },
            onSignOutMicrosoft = { mainViewModel.signOutMicrosoft(scannedProfile.id) },
            entraSignInPending = entraSignInPending,
        )
    }

    // Standalone Quick Transfer dialogs — same working dialogs HomeScreen's
    // Quick Transfer menu already opens, reached either straight from the
    // protocol grid (standaloneTransferEntry) or via the "More" sheet's
    // Quick Transfer picker (quickTransferKind).
    standaloneTransferEntry?.let { entry ->
        when (entry.id) {
            "ftp", "ftps" -> FtpTransferDialog(onDismiss = { standaloneTransferEntry = null })
            "smb" -> SmbTransferDialog(onDismiss = { standaloneTransferEntry = null })
            "webdav" -> WebDavTransferDialog(onDismiss = { standaloneTransferEntry = null })
            "nfs" -> NfsTransferDialog(onDismiss = { standaloneTransferEntry = null })
        }
    }
    quickTransferKind?.let { kind ->
        when (kind) {
            QuickTransferKind.FTP -> FtpTransferDialog(onDismiss = { quickTransferKind = null })
            QuickTransferKind.SMB -> SmbTransferDialog(onDismiss = { quickTransferKind = null })
            QuickTransferKind.WEBDAV -> WebDavTransferDialog(onDismiss = { quickTransferKind = null })
            QuickTransferKind.TFTP -> TftpTransferDialog(onDismiss = { quickTransferKind = null })
            QuickTransferKind.NFS -> NfsTransferDialog(onDismiss = { quickTransferKind = null })
        }
    }

    if (showRequestSheet) {
        RequestProtocolSheet(
            entry = requestSheetEntry,
            onDismiss = { showRequestSheet = false },
        )
    }

    if (showMoreOptions) {
        MoreAddOptionsSheet(
            onDismiss = { showMoreOptions = false },
            onDiscoverDevices = {
                showMoreOptions = false
                navController.navigate("discover_devices")
            },
            onWebFeed = {
                showMoreOptions = false
                navController.navigate("webfeed")
            },
            onQuickTransfer = {
                showMoreOptions = false
                showQuickTransferPicker = true
            },
        )
    }

    if (showQuickTransferPicker) {
        QuickTransferPickerSheet(
            onDismiss = { showQuickTransferPicker = false },
            onSelect = { kind ->
                showQuickTransferPicker = false
                quickTransferKind = kind
            },
        )
    }

    // Floating snackbar host for import/QR/camera-permission errors — this
    // screen's own Scaffold (inside AddConnectionProtocolScreen) doesn't
    // expose a snackbarHost slot, so this sits on top instead.
    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * "More ways to add a connection" — the 3 chooser options that don't get
 * their own top-bar icon (Discover Devices, Web Feed, Quick Transfer),
 * reached from [AddConnectionProtocolScreen]'s top-bar overflow icon.
 * Deliberately much simpler than the old 6-option AddOptionsDialog it
 * replaces: same translucent-card visual language as [RequestProtocolSheet]
 * in this same package, no entrance choreography needed for 3 rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreAddOptionsSheet(
    onDismiss: () -> Unit,
    onDiscoverDevices: () -> Unit,
    onWebFeed: () -> Unit,
    onQuickTransfer: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DeepSpace,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.add_connection),
                color = StarDust,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            MoreOptionRow(Icons.Outlined.Wifi, stringResource(R.string.add_options_discover_title), PlasmaGreen, onDiscoverDevices)
            MoreOptionRow(Icons.Outlined.Cloud, stringResource(R.string.add_options_webfeed_title), QuantumBlue, onWebFeed)
            MoreOptionRow(Icons.Outlined.Bolt, stringResource(R.string.add_options_quick_transfer_title), SolarFlare, onQuickTransfer)
        }
    }
}

@Composable
private fun MoreOptionRow(
    icon: ImageVector,
    title: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NebulaSurface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent)
        }
        Text(text = title, color = StarDust, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Picker for which standalone Quick Transfer type to open — same 5 options HomeScreen's QuickTransferPickerDialog offered. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickTransferPickerSheet(
    onDismiss: () -> Unit,
    onSelect: (QuickTransferKind) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DeepSpace,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.quick_transfer_picker_title),
                color = StarDust,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            MoreOptionRow(Icons.Outlined.Cloud, stringResource(R.string.quick_transfer_ftp_title), SolarFlare) { onSelect(QuickTransferKind.FTP) }
            MoreOptionRow(Icons.Outlined.Cloud, stringResource(R.string.quick_transfer_smb_title), QuantumBlue) { onSelect(QuickTransferKind.SMB) }
            MoreOptionRow(Icons.Outlined.Cloud, stringResource(R.string.quick_transfer_webdav_title), VoidPurple) { onSelect(QuickTransferKind.WEBDAV) }
            MoreOptionRow(Icons.Outlined.Cloud, stringResource(R.string.quick_transfer_tftp_title), PlasmaGreen) { onSelect(QuickTransferKind.TFTP) }
            MoreOptionRow(Icons.Outlined.Cloud, stringResource(R.string.quick_transfer_nfs_title), PulsarCyan) { onSelect(QuickTransferKind.NFS) }
        }
    }
}
