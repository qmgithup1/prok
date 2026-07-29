package com.systemsgo.hex.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.ui.components.ProtocolIconBadge
import com.systemsgo.hex.ui.components.SpaceButton
import com.systemsgo.hex.ui.theme.*
import com.systemsgo.hex.util.DeviceFormFactor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SplitScreenPickerViewModel @Inject constructor(
    repository: RdpProfileRepository
) : ViewModel() {
    val profiles: StateFlow<List<RdpProfile>> =
        repository.getAllProfiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}

/**
 * SPLIT-SCREEN FEATURE — entry point UI.
 *
 * Lets the user pick a saved connection for each pane, in any protocol
 * combination (RDP+RDP, RDP+VNC, RDP+SSH, VNC+SSH, SSH+SSH, ...), then
 * launches [SplitScreenActivity]. Gated on [DeviceFormFactor.supportsDesktopFeatures]
 * so it never offers Split Screen on a plain phone screen — see that
 * object's doc for exactly what "desktop-class" means here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitScreenPickerScreen(
    navController: NavController,
    viewModel: SplitScreenPickerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val supported = remember { DeviceFormFactor.supportsDesktopFeatures(context) }

    var leftId by remember { mutableStateOf<String?>(null) }
    var rightId by remember { mutableStateOf<String?>(null) }
    // Which pane the next tap in the list assigns to.
    var pickingPane by remember { mutableStateOf(PanePick.LEFT) }

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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Splitscreen, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.split_screen_pick_title), color = StarDust, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        if (!supported) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Outlined.Splitscreen, null, tint = CometTail.copy(alpha = 0.5f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.split_screen_unsupported), color = StarDust, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.split_screen_unsupported_detail), color = CometTail, style = MaterialTheme.typography.bodySmall)
                }
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.split_screen_pick_hint),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Pane-target chooser: which of the two slots does the next tap fill?
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PaneChip(
                    label = stringResource(R.string.split_screen_pick_left),
                    profile = profiles.firstOrNull { it.id == leftId },
                    selected = pickingPane == PanePick.LEFT,
                    onClick = { pickingPane = PanePick.LEFT },
                    modifier = Modifier.weight(1f)
                )
                PaneChip(
                    label = stringResource(R.string.split_screen_pick_right),
                    profile = profiles.firstOrNull { it.id == rightId },
                    selected = pickingPane == PanePick.RIGHT,
                    onClick = { pickingPane = PanePick.RIGHT },
                    modifier = Modifier.weight(1f)
                )
            }

            if (profiles.size < 2) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.split_screen_need_two_profiles),
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
                        val isLeft = profile.id == leftId
                        val isRight = profile.id == rightId
                        ProfilePickRow(
                            profile = profile,
                            marker = when {
                                isLeft -> "L"
                                isRight -> "R"
                                else -> null
                            },
                            onClick = {
                                when (pickingPane) {
                                    PanePick.LEFT -> {
                                        leftId = profile.id
                                        if (rightId == profile.id) rightId = null
                                        pickingPane = PanePick.RIGHT
                                    }
                                    PanePick.RIGHT -> {
                                        rightId = profile.id
                                        if (leftId == profile.id) leftId = null
                                    }
                                }
                            }
                        )
                    }
                }
            }

            AnimatedVisibility(visible = leftId != null && rightId != null) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    SpaceButton(
                        text = stringResource(R.string.split_screen_start),
                        onClick = {
                            val l = leftId ?: return@SpaceButton
                            val r = rightId ?: return@SpaceButton
                            context.startActivity(SplitScreenActivity.intent(context, l, r))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private enum class PanePick { LEFT, RIGHT }

@Composable
private fun PaneChip(
    label: String,
    profile: RdpProfile?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) PulsarCyan.copy(alpha = 0.15f) else NebulaSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = CometTail, style = MaterialTheme.typography.labelSmall)
            Text(
                profile?.name ?: "—",
                color = StarDust,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ProfilePickRow(
    profile: RdpProfile,
    marker: String?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = NebulaSurface,
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
            if (marker != null) {
                Surface(color = PulsarCyan.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)) {
                    Text(marker, color = PulsarCyan, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            } else {
                Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = CometTail.copy(alpha = 0.4f))
            }
        }
    }
}
