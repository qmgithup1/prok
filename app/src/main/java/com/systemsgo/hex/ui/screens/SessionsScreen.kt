package com.systemsgo.hex.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ConnectionState
import com.systemsgo.hex.session.SessionTab
import com.systemsgo.hex.ui.MainViewModel
import com.systemsgo.hex.ui.components.ProtocolIconBadge
import com.systemsgo.hex.ui.theme.*

/**
 * Feature-05 · شاشة الجلسات النشطة
 *
 * A dedicated, organized screen listing every open remote session — replaces
 * the cramped floating tab chip that used to sit at the top of the Home
 * screen. Tapping a session brings it to the foreground; the × button
 * reliably closes it (see MainViewModel.removeSessionTabLocally + the
 * tab-id-aware close handling in RdpSessionActivity).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel()
) {
    val sessionTabs by viewModel.sessionTabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = CometTail)
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.Layers, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.active_sessions), color = StarDust, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    // SPLIT-SCREEN FEATURE: automatically hidden on plain phone
                    // screens — see DeviceFormFactor.supportsDesktopFeatures.
                    if (com.systemsgo.hex.util.DeviceFormFactor.supportsDesktopFeatures(context)) {
                        IconButton(onClick = { navController.navigate("split_screen_picker") }) {
                            Icon(
                                Icons.Outlined.Splitscreen,
                                contentDescription = stringResource(R.string.split_screen),
                                tint = CometTail
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (sessionTabs.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Layers,
                        null,
                        tint = CometTail.copy(alpha = 0.4f),
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.sessions_empty), color = CometTail, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.sessions_empty_subtitle),
                        color = CometTail.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sessionTabs, key = { it.tabId }) { tab ->
                    SessionCard(
                        tab      = tab,
                        isActive = tab.tabId == activeTabId,
                        onOpen   = {
                            val intent = Intent(context, RdpSessionActivity::class.java)
                                .putExtra("profile_id", tab.profile.id)
                                .putExtra("tab_id", tab.tabId)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            context.startActivity(intent)
                        },
                        onClose  = {
                            // Remove instantly from the list, then ask the session
                            // Activity to tear down the underlying connection.
                            viewModel.removeSessionTabLocally(tab.tabId)
                            val intent = Intent(context, RdpSessionActivity::class.java)
                                .putExtra("profile_id", tab.profile.id)
                                .putExtra("tab_id", tab.tabId)
                                .putExtra("close_tab", true)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            context.startActivity(intent)
                        }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SessionCard(
    tab:      SessionTab,
    isActive: Boolean,
    onOpen:   () -> Unit,
    onClose:  () -> Unit,
) {
    val accent = PulsarCyan
    val (stateColor, stateLabel) = when (tab.state) {
        ConnectionState.CONNECTED     -> PlasmaGreen  to stringResource(R.string.session_state_connected)
        ConnectionState.CONNECTING    -> ConnectingAmber to stringResource(R.string.session_state_connecting)
        ConnectionState.RECONNECTING  -> ConnectingAmber to stringResource(R.string.session_state_reconnecting)
        ConnectionState.ERROR         -> ErrorRed to stringResource(R.string.session_state_error)
        ConnectionState.DISCONNECTED  -> CometTail.copy(alpha = 0.5f) to stringResource(R.string.session_state_disconnected)
        // CONNECTION-STATUS-INDICATOR FEATURE: see the matching comment in
        // SessionTabsBar.stateColor() — no live producer emits these on a
        // SessionTab yet, branches exist so this stays exhaustive.
        ConnectionState.AUTH_REQUIRED -> QuantumBlue to stringResource(R.string.session_state_auth_required)
        ConnectionState.SUSPENDED     -> SolarFlare to stringResource(R.string.session_state_suspended)
    }

    Surface(
        color  = NebulaSurface,
        shape  = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) accent.copy(alpha = 0.5f) else HorizonGray.copy(alpha = 0.3f)
        ),
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProtocolIconBadge(tab.profile.protocolType)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tab.profile.name,
                    color      = StarDust,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Text(
                    "${tab.profile.host}:${tab.profile.port}",
                    color    = CometTail,
                    style    = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(stateColor, CircleShape)
                    )
                    Text(stateLabel, color = stateColor, style = MaterialTheme.typography.labelSmall)
                }
            }

            // BUGFIX: touch target raised from 44.dp to the full 48.dp accessibility
            // minimum — this row has ample height to spare, so no layout cost.
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.session_close),
                    tint     = CometTail,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
