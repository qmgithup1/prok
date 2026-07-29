package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.QsTilePreferences
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.ui.components.ProtocolIconBadge
import com.systemsgo.hex.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * QUICK-SETTINGS-TILE FEATURE (Part 1/2).
 *
 * State for Settings → Quick Settings Tile: the list of connections to pick
 * from, and which one (if any) is currently bound to the tile. Writes go
 * straight to [QsTilePreferences] — same "no local UI-only state, the
 * repository's Flow is the single source of truth" shape as
 * [CloudSyncViewModel] uses for its own settings screen.
 */
@HiltViewModel
class QuickTileViewModel @Inject constructor(
    profileRepository: RdpProfileRepository,
    private val qsTilePreferences: QsTilePreferences,
) : ViewModel() {

    val profiles: StateFlow<List<RdpProfile>> =
        profileRepository.getAllProfiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedProfileId: StateFlow<String?> =
        qsTilePreferences.selectedProfileIdFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Convenience for the screen's "currently bound: <name>" summary, so it
    // doesn't have to re-derive this from profiles + selectedProfileId itself.
    val selectedProfile: StateFlow<RdpProfile?> =
        combine(profiles, selectedProfileId) { list, id -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun selectProfile(profileId: String) = viewModelScope.launch {
        qsTilePreferences.setSelectedProfile(profileId)
    }

    fun clearSelection() = viewModelScope.launch {
        qsTilePreferences.clearSelection()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickTileSettingsScreen(
    navController: NavController,
    viewModel: QuickTileViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedProfileId.collectAsStateWithLifecycle()
    val spaceColors = LocalSpaceColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(spaceColors.backgroundGradient))
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = PulsarCyan)
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Bolt, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.qs_tile_settings_title), color = StarDust, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text(
                    stringResource(R.string.qs_tile_settings_hint),
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                if (selectedId != null) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        TextButton(onClick = { viewModel.clearSelection() }) {
                            Text(stringResource(R.string.qs_tile_clear_selection), color = CometTail)
                        }
                    }
                }

                if (profiles.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.qs_tile_no_profiles),
                            color = CometTail,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(profiles, key = { it.id }) { profile ->
                            QuickTileProfileRow(
                                profile = profile,
                                selected = profile.id == selectedId,
                                onClick = { viewModel.selectProfile(profile.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTileProfileRow(
    profile: RdpProfile,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) PulsarCyan.copy(alpha = 0.12f) else NebulaSurface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProtocolIconBadge(type = profile.protocolType)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, color = StarDust, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("${profile.host}:${profile.port}", color = CometTail, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (selected) {
                Icon(Icons.Filled.CheckCircle, null, tint = PulsarCyan)
            } else {
                Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = CometTail.copy(alpha = 0.4f))
            }
        }
    }
}
