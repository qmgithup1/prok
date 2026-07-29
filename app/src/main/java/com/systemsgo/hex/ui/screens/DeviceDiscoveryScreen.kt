package com.systemsgo.hex.ui.screens

import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.DeviceUnknown
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.discovery.DiscoveredDevice
import com.systemsgo.hex.discovery.DiscoveredOsGuess
import com.systemsgo.hex.discovery.DiscoveryError
import com.systemsgo.hex.discovery.DiscoveryPhase
import com.systemsgo.hex.discovery.DiscoverySource
import com.systemsgo.hex.discovery.DiscoveryUiState
import com.systemsgo.hex.discovery.NetworkDiscoveryManager
import com.systemsgo.hex.ui.MainViewModel
import com.systemsgo.hex.ui.components.ProfileFormDialog
import com.systemsgo.hex.ui.components.protocolColor
import com.systemsgo.hex.ui.components.protocolIcon
import com.systemsgo.hex.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AUTO-DISCOVERY FEATURE: thin ViewModel wrapper around
 * [NetworkDiscoveryManager] — the manager itself holds the actual
 * mDNS/port-scan logic (see its doc comment); this just exposes its state
 * to Compose and ties its lifetime to this screen's own ViewModel scope so
 * navigating away always stops the scan (see [onCleared]).
 */
@HiltViewModel
class DeviceDiscoveryViewModel @Inject constructor(
    private val discoveryManager: NetworkDiscoveryManager,
) : ViewModel() {

    val state: StateFlow<DiscoveryUiState> get() = discoveryManager.state

    init {
        // Discovery starts automatically when the screen opens — matches
        // every other "scan on entry" pattern in the app (e.g. the QR
        // scanner opening straight into its camera preview).
        discoveryManager.start(viewModelScope)
    }

    fun refresh() = discoveryManager.start(viewModelScope)
    fun stop() = discoveryManager.stop()

    override fun onCleared() {
        super.onCleared()
        discoveryManager.stop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDiscoveryScreen(
    navController: NavController,
    discoveryViewModel: DeviceDiscoveryViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val discovery by discoveryViewModel.state.collectAsStateWithLifecycle()
    val homeState by mainViewModel.uiState.collectAsStateWithLifecycle()

    // One-tap profile creation: tapping a device opens the exact same
    // ProfileFormDialog used everywhere else in the app (manual add / .rdp
    // import / QR scan), pre-filled with what was detected, so the user
    // reviews and completes credentials before it's actually saved.
    var pendingDeviceProfile by remember { mutableStateOf<RdpProfile?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val addedLabel = stringResource(R.string.discover_devices_profile_added)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = CometTail)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Wifi, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.discover_devices_title), color = StarDust, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    val isScanning = discovery.phase == DiscoveryPhase.SCANNING || discovery.phase == DiscoveryPhase.PREPARING
                    if (isScanning) {
                        IconButton(onClick = { discoveryViewModel.stop() }) {
                            Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.discover_devices_stop), tint = ErrorRed)
                        }
                    } else {
                        IconButton(onClick = { discoveryViewModel.refresh() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.discover_devices_refresh), tint = CometTail)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            DiscoveryStatusBar(discovery)

            if (discovery.devices.isEmpty()) {
                DiscoveryEmptyState(
                    phase = discovery.phase,
                    error = discovery.error,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // UI-FIX (professional pass): while a scan is still running,
                // the status bar above only shows scan progress ("12/254"),
                // not how many devices have actually turned up so far — the
                // list itself was the only signal, which read as a plain,
                // unstyled dump of rows. A small live counter gives the same
                // "results are accumulating" feedback other discovery UIs
                // (AirDrop, Bluetooth pairing, etc.) give, without repeating
                // the "Scan complete" wording already shown once finished.
                val isScanning = discovery.phase == DiscoveryPhase.SCANNING || discovery.phase == DiscoveryPhase.PREPARING
                if (isScanning) {
                    Text(
                        text = stringResource(R.string.discover_devices_found_so_far, discovery.devices.size),
                        color = CometTail.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, top = 4.dp, bottom = 2.dp),
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(discovery.devices, key = { _, d -> d.ipAddress }) { index, device ->
                        DiscoveredDeviceCard(
                            device = device,
                            // Staggered a little by list position so devices that
                            // resolved together during a scan don't all snap in on
                            // the exact same frame — reads as results settling into
                            // place rather than a static list just being there.
                            entryDelayMs = (index * 40).coerceAtMost(240),
                            modifier = Modifier.animateItem(),
                            onAdd = { protocol ->
                                pendingDeviceProfile = RdpProfile(
                                    name = device.displayName,
                                    protocolType = protocol,
                                    host = device.ipAddress,
                                    port = device.ports[protocol] ?: protocol.defaultPort,
                                    username = "",
                                    password = "",
                                )
                            },
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    // ENTRA-ID-AUTH FEATURE: `stub` always already has a stable id (RdpProfile's
    // default UUID, assigned at the `onAdd` click above and unchanged while this
    // dialog is open), so — unlike HomeScreen's "add new connection" dialog —
    // no separate pendingProfileId is needed here; stub.id IS that stable id.
    val entraSignInPending by mainViewModel.entraSignInPending.collectAsStateWithLifecycle()
    val context = LocalContext.current

    pendingDeviceProfile?.let { stub ->
        ProfileFormDialog(
            profile = stub,
            folders = homeState.folders,
            onDismiss = { pendingDeviceProfile = null },
            onSave = { profile ->
                mainViewModel.addProfile(profile)
                pendingDeviceProfile = null
                scope.launch { snackbarHostState.showSnackbar(addedLabel) }
            },
            onSignInWithMicrosoft = {
                (context as? Activity)?.let { activity -> mainViewModel.signInWithMicrosoft(activity, stub.id) }
            },
            onSignOutMicrosoft = { mainViewModel.signOutMicrosoft(stub.id) },
            entraSignInPending = entraSignInPending,
        )
    }
}

@Composable
private fun DiscoveryStatusBar(discovery: DiscoveryUiState) {
    val (text, tint) = when (discovery.phase) {
        DiscoveryPhase.IDLE, DiscoveryPhase.PREPARING ->
            stringResource(R.string.discover_devices_status_preparing) to CometTail
        DiscoveryPhase.SCANNING -> {
            val label = if (discovery.totalHosts > 0) {
                stringResource(R.string.discover_devices_status_scanning_progress, discovery.scannedHosts, discovery.totalHosts)
            } else {
                stringResource(R.string.discover_devices_status_scanning)
            }
            label to PulsarCyan
        }
        DiscoveryPhase.COMPLETED ->
            stringResource(R.string.discover_devices_status_completed, discovery.devices.size) to PlasmaGreen
        DiscoveryPhase.STOPPED -> {
            val label = when (discovery.error) {
                DiscoveryError.NO_NETWORK -> stringResource(R.string.discover_devices_error_no_network)
                DiscoveryError.NOT_ON_LOCAL_NETWORK -> stringResource(R.string.discover_devices_error_not_local)
                DiscoveryError.PERMISSION_DENIED -> stringResource(R.string.discover_devices_error_permission)
                null -> stringResource(R.string.discover_devices_status_stopped)
            }
            label to (if (discovery.error != null) ErrorRed else CometTail)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "discovery-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "discovery-pulse-alpha",
    )
    val isScanning = discovery.phase == DiscoveryPhase.SCANNING || discovery.phase == DiscoveryPhase.PREPARING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(tint.copy(alpha = if (isScanning) pulseAlpha else 1f), CircleShape)
        )
        Text(text, color = tint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (isScanning) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = tint)
        }
    }

    if (discovery.subnet != null && discovery.portScanSkippedSubnetTooLarge) {
        Text(
            stringResource(R.string.discover_devices_subnet_too_large),
            color = CometTail.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
    if (!discovery.mdnsSupported) {
        Text(
            stringResource(R.string.discover_devices_mdns_unsupported),
            color = CometTail.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun DiscoveryEmptyState(phase: DiscoveryPhase, error: DiscoveryError?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val icon = if (error != null) Icons.Outlined.WifiOff else Icons.Outlined.SearchOff
            Icon(icon, null, tint = CometTail.copy(alpha = 0.4f), modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            val message = when {
                phase == DiscoveryPhase.SCANNING || phase == DiscoveryPhase.PREPARING ->
                    stringResource(R.string.discover_devices_searching)
                error == DiscoveryError.NO_NETWORK -> stringResource(R.string.discover_devices_error_no_network)
                error == DiscoveryError.NOT_ON_LOCAL_NETWORK -> stringResource(R.string.discover_devices_error_not_local)
                error == DiscoveryError.PERMISSION_DENIED -> stringResource(R.string.discover_devices_error_permission)
                else -> stringResource(R.string.discover_devices_empty)
            }
            Text(message, color = CometTail, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun DiscoveredDeviceCard(
    device: DiscoveredDevice,
    onAdd: (ProtocolType) -> Unit,
    modifier: Modifier = Modifier,
    entryDelayMs: Int = 0,
) {
    val osIcon: ImageVector = when (device.osGuess) {
        DiscoveredOsGuess.WINDOWS -> Icons.Outlined.DesktopWindows
        DiscoveredOsGuess.MACOS -> Icons.Outlined.Laptop
        DiscoveredOsGuess.LINUX -> Icons.Outlined.Terminal
        DiscoveredOsGuess.UNKNOWN -> Icons.Outlined.DeviceUnknown
    }
    // A device can answer more than one protocol (e.g. SSH + VNC on the
    // same Linux box); default the one-tap "Add" action to whichever was
    // detected first, but let the user pick a different one if there's a
    // choice via the small protocol chips below.
    var selectedProtocol by remember(device.ipAddress) {
        mutableStateOf(device.protocols.minByOrNull { it.ordinal } ?: ProtocolType.RDP)
    }
    val selectedColor = protocolColor(selectedProtocol)

    // UI-FIX (professional pass): each card now settles into place with a
    // brief warp-in (scale + fade) instead of just appearing — matches the
    // motion language used everywhere else a new surface/element enters
    // (see SpaceMotion). entryDelayMs staggers cards that arrived in the
    // same scan tick so they don't all pop in on one frame.
    var visible by remember(device.ipAddress) { mutableStateOf(false) }
    LaunchedEffect(device.ipAddress) {
        if (entryDelayMs > 0) kotlinx.coroutines.delay(entryDelayMs.toLong())
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(260, easing = FastOutSlowInEasing)) +
                scaleIn(initialScale = 0.94f, animationSpec = tween(260, easing = FastOutSlowInEasing)),
    ) {
        Surface(
            color = NebulaSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, selectedColor.copy(alpha = 0.22f)),
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                // ── Identity row ────────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(PulsarCyan.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                            .border(1.5.dp, PulsarCyan.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(osIcon, contentDescription = null, tint = PulsarCyan, modifier = Modifier.size(21.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            device.displayName,
                            color = StarDust,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            // Monospace for the IP itself — it's data, not prose,
                            // and the app's own MonoFontFamily (labelMedium) is
                            // already reserved for exactly this kind of reading
                            // (console/HUD-style values) elsewhere in the app.
                            if (device.hostname != null) {
                                Text(
                                    device.ipAddress,
                                    color = CometTail,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DiscoverySourceBadge(device.sources)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = HorizonGray.copy(alpha = 0.15f))
                Spacer(Modifier.height(10.dp))

                // ── Protocol picker + action row ────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        device.protocols.sortedBy { it.ordinal }.forEach { protocol ->
                            DiscoveredProtocolChip(
                                protocol = protocol,
                                selected = protocol == selectedProtocol,
                                onClick = { selectedProtocol = protocol },
                            )
                        }
                    }

                    FilledIconButton(
                        onClick = { onAdd(selectedProtocol) },
                        modifier = Modifier.size(38.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = selectedColor.copy(alpha = 0.16f),
                            contentColor = selectedColor,
                        ),
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.discover_devices_add_connection),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

// Brand-colored, selectable protocol chip — reuses the same protocolIcon()/
// protocolColor() the rest of the app (connection cards, history) already
// uses, so "RDP is blue, VNC is purple, SSH is green" reads consistently
// here too instead of every chip sharing one flat accent color regardless
// of protocol.
@Composable
private fun DiscoveredProtocolChip(protocol: ProtocolType, selected: Boolean, onClick: () -> Unit) {
    val color = protocolColor(protocol)
    Surface(
        color = if (selected) color.copy(alpha = 0.16f) else HorizonGray.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = if (selected) BorderStroke(1.dp, color.copy(alpha = 0.6f)) else null,
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Icon(protocolIcon(protocol), null, tint = if (selected) color else CometTail, modifier = Modifier.size(12.dp))
            Text(
                protocol.label,
                color = if (selected) color else CometTail,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

// Small icon-only indicator for *how* a device was found — zero-config
// mDNS announcement vs. an active port probe — so a curious user can tell
// "this one told us about itself" from "we went looking for it", without
// spending chip/text space most people will never need to read.
@Composable
private fun DiscoverySourceBadge(sources: Set<DiscoverySource>) {
    val icon = if (DiscoverySource.MDNS in sources) Icons.Outlined.Sensors else Icons.Outlined.Search
    val description = if (DiscoverySource.MDNS in sources)
        stringResource(R.string.discover_devices_source_announced)
    else
        stringResource(R.string.discover_devices_source_scanned)
    Icon(
        icon,
        contentDescription = description,
        tint = CometTail.copy(alpha = 0.5f),
        modifier = Modifier.size(11.dp),
    )
}
