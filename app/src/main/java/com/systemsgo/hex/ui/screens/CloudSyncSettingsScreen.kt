package com.systemsgo.hex.ui.screens

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.systemsgo.hex.R
import com.systemsgo.hex.cloudsync.CloudProvider
import com.systemsgo.hex.cloudsync.CloudSyncError
import com.systemsgo.hex.cloudsync.DropboxSyncProvider
import com.systemsgo.hex.cloudsync.GoogleDriveSyncProvider
import com.systemsgo.hex.data.repository.CloudSyncSettings
import com.systemsgo.hex.ui.components.*
import com.systemsgo.hex.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CLOUD-SYNC FEATURE (Part 3-b).
 *
 * Settings → Cloud Sync. Reads/writes only through [CloudSyncViewModel] —
 * see that class for why linking, syncing, and disconnecting each go
 * through [com.systemsgo.hex.cloudsync.GoogleDriveSyncProvider]/
 * [com.systemsgo.hex.cloudsync.DropboxSyncProvider]/[com.systemsgo.hex.cloudsync.CloudSyncManager]
 * directly rather than any WorkManager/[com.systemsgo.hex.cloudsync.CloudSyncScheduler]
 * call — every [com.systemsgo.hex.data.repository.CloudSyncPreferences] write
 * this screen makes (auto-sync toggle, interval) is picked up by
 * `SystemsGoApp`'s own settingsFlow collector, which is the only thing that
 * ever touches the scheduler.
 *
 * Deliberately its own top-level screen/route (`settings/cloud_sync`) rather
 * than a section inlined into [SettingsScreen] itself — this feature has
 * enough state (link status, passphrase, auto-sync + interval, last
 * success/error) to warrant the same "hub → focused screen" treatment as
 * Security/Data/USB Redirection.
 */
private enum class PendingPassphraseAction { NONE, SYNC_NOW, ENABLE_AUTO_SYNC }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncSettingsScreen(
    navController: NavController,
    viewModel: CloudSyncViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val hasPassphrase by viewModel.hasPassphrase.collectAsStateWithLifecycle()
    val isLinking by viewModel.isLinking.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val spaceColors = LocalSpaceColors.current

    // Dropbox's PKCE flow redirects out to a browser and back — the result
    // is only readable from onResume, never synchronously from startLink().
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, evt ->
            if (evt == Lifecycle.Event.ON_RESUME) {
                viewModel.completeDropboxLinkIfPending()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Strings resolved once here (not inside the LaunchedEffect below) since
    // stringResource() is a @Composable call.
    val uploadedMsg = stringResource(R.string.cloud_sync_result_uploaded)
    val downloadedMsg = stringResource(R.string.cloud_sync_result_downloaded_fmt)
    val networkErrorMsg = stringResource(R.string.cloud_sync_error_network)
    val authExpiredMsg = stringResource(R.string.cloud_sync_error_auth_expired)
    val wrongPassphraseMsg = stringResource(R.string.cloud_sync_error_wrong_passphrase)
    val notLinkedMsg = stringResource(R.string.cloud_sync_error_not_linked)

    LaunchedEffect(event) {
        val e = event ?: return@LaunchedEffect
        val message = when (e) {
            is CloudSyncEvent.Uploaded -> uploadedMsg
            is CloudSyncEvent.Downloaded -> String.format(downloadedMsg, e.importedProfiles)
            is CloudSyncEvent.SyncFailed -> describeSyncError(
                e.error, networkErrorMsg, authExpiredMsg, wrongPassphraseMsg, notLinkedMsg
            )
            is CloudSyncEvent.LinkFailed -> e.message
        }
        snackbarHostState.showSnackbar(message)
        viewModel.consumeSyncEvent()
    }

    var showPassphraseDialog by remember { mutableStateOf(false) }
    var pendingPassphraseAction by remember { mutableStateOf(PendingPassphraseAction.NONE) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    fun requirePassphraseThen(action: PendingPassphraseAction) {
        if (hasPassphrase) {
            when (action) {
                PendingPassphraseAction.SYNC_NOW -> viewModel.syncNow()
                PendingPassphraseAction.ENABLE_AUTO_SYNC -> viewModel.updateAutoSyncEnabled(true)
                PendingPassphraseAction.NONE -> {}
            }
        } else {
            pendingPassphraseAction = action
            showPassphraseDialog = true
        }
    }

    // AuthExpired is recorded into CloudSyncSettings.lastSyncErrorMessage as
    // its plain userMessage (CloudSyncManager has no separate error-code
    // field to persist) — matching against that literal is how this screen
    // tells "needs reconnect" apart from any other last error on open,
    // without popping a dialog for it (see the Part 3-b prompt).
    val isAuthExpired = settings.linkedProvider != null &&
        settings.lastSyncErrorMessage == CloudSyncError.AuthExpired.userMessage

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(spaceColors.backgroundGradient))
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.cloud_sync),
                            style = MaterialTheme.typography.titleLarge,
                            color = StarDust, fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = PulsarCyan)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CloudSyncStatusCard(settings = settings, isAuthExpired = isAuthExpired)

                // Mirrors the "Manage your Google Account" action that sits
                // right under the identity card in Google's own account
                // switcher (Gmail/Drive) — a direct hand-off to the user's
                // actual Google Account page for anything this screen
                // itself has no business managing (security, other
                // sessions, storage quota, etc).
                if (settings.linkedProvider == CloudProvider.GOOGLE_DRIVE && !isAuthExpired) {
                    SettingsItem(
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        title = stringResource(R.string.cloud_sync_manage_google_account),
                        subtitle = settings.linkedAccountLabel,
                        onClick = { safeOpenUrl(context, "https://myaccount.google.com") }
                    )
                }

                if (isAuthExpired) {
                    SettingsItem(
                        icon = Icons.Outlined.Sync,
                        title = stringResource(R.string.cloud_sync_reconnect),
                        subtitle = stringResource(R.string.cloud_sync_error_auth_expired),
                        tint = ErrorRed,
                        onClick = { (context as? Activity)?.let { viewModel.reconnect(it) } }
                    )
                } else if (settings.lastSyncErrorMessage != null && settings.linkedProvider != null) {
                    SettingsItem(
                        icon = Icons.Outlined.ErrorOutline,
                        title = stringResource(R.string.cloud_sync_last_error),
                        subtitle = settings.lastSyncErrorMessage,
                        tint = ErrorRed,
                        onClick = {}
                    )
                }

                if (settings.linkedProvider == null) {
                    SettingsSection(icon = Icons.Outlined.Cloud, title = stringResource(R.string.cloud_sync_connect_section))

                    // UI-CONFIG-GUARD FIX: previously these two rows were always
                    // tappable even when the app was shipped with the placeholder
                    // OAuth client ID / App Key still in GoogleDriveSyncProvider /
                    // DropboxSyncProvider — tapping them ran straight into a
                    // confusing low-level SDK failure. Now disabled + explained
                    // instead, until isConfigured is actually true.
                    GoogleBrandedSettingsItem(
                        title = stringResource(R.string.cloud_sync_connect_google_drive),
                        subtitle = when {
                            !GoogleDriveSyncProvider.isConfigured -> stringResource(R.string.cloud_sync_provider_not_configured)
                            isLinking -> stringResource(R.string.cloud_sync_linking)
                            else -> null
                        },
                        enabled = GoogleDriveSyncProvider.isConfigured,
                        onClick = { (context as? Activity)?.let { viewModel.linkGoogleDrive(it) } }
                    )
                    SettingsItem(
                        icon = Icons.Outlined.Cloud,
                        title = stringResource(R.string.cloud_sync_connect_dropbox),
                        subtitle = when {
                            !DropboxSyncProvider.isConfigured -> stringResource(R.string.cloud_sync_provider_not_configured)
                            isLinking -> stringResource(R.string.cloud_sync_linking)
                            else -> null
                        },
                        enabled = DropboxSyncProvider.isConfigured,
                        onClick = { (context as? Activity)?.let { viewModel.startDropboxLink(it) } }
                    )
                } else {
                    SettingsSection(icon = Icons.Outlined.Key, title = stringResource(R.string.cloud_sync_passphrase_title))
                    SettingsItem(
                        icon = Icons.Outlined.Key,
                        title = stringResource(R.string.cloud_sync_passphrase_title),
                        subtitle = if (hasPassphrase)
                            stringResource(R.string.cloud_sync_passphrase_set)
                        else
                            stringResource(R.string.cloud_sync_passphrase_not_set),
                        tint = if (hasPassphrase) PulsarCyan else ConnectingAmber,
                        onClick = {
                            pendingPassphraseAction = PendingPassphraseAction.NONE
                            showPassphraseDialog = true
                        }
                    )

                    SettingsSection(icon = Icons.Outlined.Sync, title = stringResource(R.string.cloud_sync_auto_sync_section))
                    SettingsToggle(
                        icon = Icons.Outlined.Sync,
                        title = stringResource(R.string.cloud_sync_auto_sync_toggle),
                        subtitle = stringResource(R.string.cloud_sync_auto_sync_desc),
                        checked = settings.autoSyncEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                requirePassphraseThen(PendingPassphraseAction.ENABLE_AUTO_SYNC)
                            } else {
                                viewModel.updateAutoSyncEnabled(false)
                            }
                        }
                    )

                    AnimatedVisibility(visible = settings.autoSyncEnabled) {
                        SettingsSlider(
                            icon = Icons.Outlined.Timer,
                            title = stringResource(R.string.cloud_sync_interval_title),
                            value = settings.autoSyncIntervalMinutes.toFloat(),
                            valueRange = CloudSyncSettings.MIN_AUTO_SYNC_INTERVAL_MINUTES.toFloat()..240f,
                            steps = 14, // 15-minute stops between 15 and 240
                            valueLabel = { v -> String.format(stringResource(R.string.cloud_sync_interval_minutes_fmt), v.toInt()) },
                            onValueChange = { v ->
                                val minutes = (v.toInt() / 15) * 15
                                viewModel.updateAutoSyncIntervalMinutes(
                                    minutes.coerceAtLeast(CloudSyncSettings.MIN_AUTO_SYNC_INTERVAL_MINUTES)
                                )
                            }
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    SettingsItem(
                        icon = Icons.Outlined.Sync,
                        title = if (isSyncing) stringResource(R.string.cloud_sync_syncing) else stringResource(R.string.cloud_sync_now),
                        subtitle = null,
                        onClick = { requirePassphraseThen(PendingPassphraseAction.SYNC_NOW) }
                    )

                    Spacer(Modifier.height(8.dp))
                    SettingsSection(icon = Icons.Outlined.SwitchAccount, title = stringResource(R.string.cloud_sync_switch_account))
                    if (settings.linkedProvider == CloudProvider.GOOGLE_DRIVE) {
                        GoogleBrandedSettingsItem(
                            title = stringResource(R.string.cloud_sync_switch_account),
                            subtitle = stringResource(R.string.cloud_sync_switch_account_desc),
                            enabled = !isLinking,
                            onClick = { (context as? Activity)?.let { viewModel.switchAccount(it) } }
                        )
                    } else {
                        SettingsItem(
                            icon = Icons.Outlined.SwitchAccount,
                            title = stringResource(R.string.cloud_sync_switch_account),
                            subtitle = stringResource(R.string.cloud_sync_switch_account_desc),
                            enabled = !isLinking,
                            onClick = { (context as? Activity)?.let { viewModel.switchAccount(it) } }
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    SettingsSection(icon = Icons.Outlined.LinkOff, title = stringResource(R.string.cloud_sync_disconnect_section))
                    SettingsItem(
                        icon = Icons.AutoMirrored.Outlined.Logout,
                        title = stringResource(R.string.cloud_sync_disconnect),
                        subtitle = null,
                        tint = ErrorRed,
                        onClick = { showDisconnectConfirm = true }
                    )
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showPassphraseDialog) {
        SyncPassphraseDialog(
            onConfirm = { passphrase ->
                viewModel.setPassphrase(passphrase)
                showPassphraseDialog = false
                when (pendingPassphraseAction) {
                    PendingPassphraseAction.SYNC_NOW -> viewModel.syncNow()
                    PendingPassphraseAction.ENABLE_AUTO_SYNC -> viewModel.updateAutoSyncEnabled(true)
                    PendingPassphraseAction.NONE -> {}
                }
                pendingPassphraseAction = PendingPassphraseAction.NONE
            },
            onDismiss = {
                showPassphraseDialog = false
                pendingPassphraseAction = PendingPassphraseAction.NONE
            }
        )
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(stringResource(R.string.cloud_sync_disconnect_confirm_title)) },
            text = { Text(stringResource(R.string.cloud_sync_disconnect_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.disconnect()
                    showDisconnectConfirm = false
                }) { Text(stringResource(R.string.disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun GoogleBrandedSettingsItem(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    // ASSET SOURCE: ic_google_logo_light / ic_google_logo_dark are the actual
    // "Sign in with Google" icon-only, Square-shape PNGs from Google's own
    // downloadable brand kit (developers.google.com/identity/branding-guidelines
    // → "Download Pre-Approved Brand Icons"), replacing the earlier
    // hand-approximated vector. Each PNG already bakes in its own
    // on-brand chip background + border per Google's spec — we render it
    // as-is at native size rather than re-wrapping it in another
    // background box, so we don't stack two chips on top of each other.
    //
    // THEME ADAPTATION: Google's guidelines fix the "G" mark's own four
    // brand colors — those must never be recolored to match app theming.
    // What *can* (and should) adapt is which pre-approved chip variant we
    // show: the Light asset (light chip + dark border) for this app's
    // light-mode themes, the Dark asset (dark chip + light border) for its
    // dark-mode themes — so the icon always sits correctly against
    // StarfieldSurface regardless of which of the app's 6 theme
    // combinations (space/nebula/aurora × light/dark) is active.
    val isDark = LocalSpaceColors.current.isDark
    val logoRes = if (isDark) R.drawable.ic_google_logo_dark else R.drawable.ic_google_logo_light

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StarfieldSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(onClick = if (enabled) onClick else {{}})
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .alpha(contentAlpha),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(logoRes),
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CometTail)
                }
            }
            if (enabled) {
                Icon(Icons.Filled.ChevronRight, null, tint = CometTail, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CloudSyncStatusCard(settings: CloudSyncSettings, isAuthExpired: Boolean) {
    val statusColor = when {
        settings.linkedProvider == null -> CometTail
        isAuthExpired -> ErrorRed
        settings.lastSyncErrorMessage != null -> ConnectingAmber
        else -> ConnectedGreen
    }
    val providerLabel = when (settings.linkedProvider) {
        CloudProvider.GOOGLE_DRIVE -> stringResource(R.string.cloud_sync_provider_google_drive)
        CloudProvider.DROPBOX -> stringResource(R.string.cloud_sync_provider_dropbox)
        null -> stringResource(R.string.cloud_sync_status_not_linked)
    }
    // Prefer the account's display name as the headline (feels like a real
    // account card, not just a plumbing status row); fall back to the
    // provider name, then to the raw email, so the card never looks empty.
    val headline = settings.linkedAccountDisplayName?.takeIf { it.isNotBlank() } ?: providerLabel
    val showEmailAsSubline = settings.linkedAccountDisplayName != null && settings.linkedAccountLabel != null
    val initials = (settings.linkedAccountDisplayName ?: settings.linkedAccountLabel)
        ?.trim()?.split(" ")?.filter { it.isNotBlank() }
        ?.take(2)?.mapNotNull { it.firstOrNull()?.uppercaseChar() }
        ?.joinToString("")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(20.dp), spotColor = statusColor.copy(alpha = 0.35f))
            .background(
                brush = Brush.linearGradient(listOf(GradientCardStart, GradientCardEnd)),
                shape = RoundedCornerShape(20.dp)
            )
            .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ── Avatar: Google profile photo when we have one, else a
                // themed initials/icon circle — either way ringed in the
                // current status color with a small colored dot badge, the
                // same "who + is it healthy" pattern as a real account switcher.
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .border(2.dp, statusColor.copy(alpha = 0.6f), CircleShape)
                            .background(statusColor.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (settings.linkedAccountPhotoUrl != null) {
                            AsyncImage(
                                model = settings.linkedAccountPhotoUrl,
                                contentDescription = stringResource(R.string.cd_account_avatar),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                            )
                        } else if (!initials.isNullOrBlank()) {
                            Text(
                                initials,
                                style = MaterialTheme.typography.titleMedium,
                                color = statusColor,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                if (settings.linkedProvider == null) Icons.Outlined.CloudOff else Icons.Outlined.AccountCircle,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    if (settings.linkedProvider != null) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .border(2.dp, GradientCardEnd, CircleShape)
                                .clip(CircleShape)
                                .background(statusColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isAuthExpired || settings.lastSyncErrorMessage != null) Icons.Outlined.PriorityHigh else Icons.Outlined.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(headline, style = MaterialTheme.typography.titleSmall, color = StarDust, fontWeight = FontWeight.Bold)
                    if (showEmailAsSubline) {
                        Text(settings.linkedAccountLabel!!, style = MaterialTheme.typography.bodySmall, color = CometTail)
                    } else if (settings.linkedProvider != null && settings.linkedAccountLabel != null) {
                        Text(settings.linkedAccountLabel, style = MaterialTheme.typography.bodySmall, color = CometTail)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Status pill + last-sync line, visually separated from the
            // identity row above by a subtle divider so the card reads as
            // "who" (top) then "state" (bottom) rather than one dense block.
            HorizontalDivider(color = statusColor.copy(alpha = 0.15f))
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.14f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                settings.linkedProvider == null -> stringResource(R.string.cloud_sync_status_not_linked)
                                isAuthExpired -> stringResource(R.string.cloud_sync_error_auth_expired)
                                settings.lastSyncErrorMessage != null -> stringResource(R.string.cloud_sync_last_error)
                                else -> providerLabel
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = CometTail, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (settings.lastSyncSuccessAtEpochMs == 0L)
                        stringResource(R.string.cloud_sync_last_success_never)
                    else
                        String.format(stringResource(R.string.cloud_sync_last_success_fmt), formatSyncTime(settings.lastSyncSuccessAtEpochMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = CometTail
                )
            }
        }
    }
}

/**
 * Shared by "set passphrase" and "change passphrase" — a single field, since
 * (unlike the manual export password in SettingsScreen.kt's
 * BackupPasswordDialog) losing it just means the *next* cloud sync prompts
 * again, there's no confirmation-field need for a value nothing local gets
 * permanently re-encrypted under.
 */
@Composable
private fun SyncPassphraseDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // SECURITY FIX: contains a sync passphrase field — see security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()
    var passphrase by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val emptyError = stringResource(R.string.cloud_sync_passphrase_empty_error)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = NebulaSurface) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.cloud_sync_passphrase_title),
                    color = StarDust,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                // Clarifies this is not the same secret as manual export/import.
                Text(
                    stringResource(R.string.cloud_sync_passphrase_desc),
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it; error = null },
                    label = { Text(stringResource(R.string.cloud_sync_passphrase_field_label)) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (visible) R.string.cd_hide_password else R.string.cd_show_password),
                                tint = CometTail
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        if (passphrase.isEmpty()) error = emptyError else onConfirm(passphrase)
                    }) { Text(stringResource(R.string.cloud_sync_passphrase_save)) }
                }
            }
        }
    }
}

/** Maps a [CloudSyncError] to a user-facing message, distinguishing a wrong
 *  sync passphrase from a network failure per the Part 3-b prompt — neither
 *  is exposed as its own [CloudSyncError] subtype, so the wrong-passphrase
 *  case is recognized by the literal message [CloudSyncManager] records for
 *  it (see CloudSyncManager.performDownload's InvalidPassword catch). */
private fun describeSyncError(
    error: CloudSyncError,
    networkErrorMsg: String,
    authExpiredMsg: String,
    wrongPassphraseMsg: String,
    notLinkedMsg: String,
): String = when (error) {
    is CloudSyncError.NetworkError -> networkErrorMsg
    is CloudSyncError.AuthExpired -> authExpiredMsg
    is CloudSyncError.NotLinked -> notLinkedMsg
    is CloudSyncError.Unknown ->
        if (error.detail.contains("decrypted", ignoreCase = true)) wrongPassphraseMsg else error.detail
    else -> error.userMessage
}

private fun formatSyncTime(epochMs: Long): String =
    SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))
