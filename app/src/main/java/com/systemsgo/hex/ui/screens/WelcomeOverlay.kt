package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.systemsgo.hex.R
import com.systemsgo.hex.ui.theme.CometTail
import com.systemsgo.hex.ui.theme.HorizonGray
import com.systemsgo.hex.ui.theme.NebulaSurface
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.StarDust

// ─────────────────────────────────────────────────────────────────────────────
// UI-AUDIT FIX: first-launch onboarding
// ─────────────────────────────────────────────────────────────────────────────
// MainViewModel/HomeScreen already carried the full plumbing for a first-launch
// experience (showFirstLaunchDialog / dismissFirstLaunchDialog / hasShownFirstLaunch),
// but the screen itself had been removed at some point and HomeScreen's
// LaunchedEffect just silently auto-dismissed the flag with nothing shown to
// the user (see the comment left there at the time). New users landed
// straight on an empty connections list with no explanation of what the app
// does or why it will later ask for camera/mic/storage access.
//
// This is a short, skippable overlay — not a permission-request flow itself.
// It intentionally does NOT trigger any Android permission dialogs; those
// stay contextual (e.g. camera is requested right when the user opens the QR
// scanner, mic right when they enable audio redirect) per Android's own
// best-practice guidance. It just sets expectations up front so those later
// system dialogs don't feel random, matching GestureHintsOverlay's existing
// pattern in RdpSessionActivity.kt.
@Composable
fun WelcomeOverlay(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = { /* not dismissable by back/outside-tap — must tap Get Started */ },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        WelcomeOverlayContent(onDismiss)
    }
}

@Composable
private fun WelcomeOverlayContent(onDismiss: () -> Unit) {
    var dismissing by remember { mutableStateOf(false) }

    val points = listOf(
        Triple(Icons.Outlined.Devices, stringResource(R.string.welcome_protocols_title), stringResource(R.string.welcome_protocols_desc)),
        Triple(Icons.Outlined.Security, stringResource(R.string.welcome_security_title), stringResource(R.string.welcome_security_desc)),
        Triple(Icons.Outlined.CameraAlt, stringResource(R.string.welcome_camera_title), stringResource(R.string.welcome_camera_desc)),
        Triple(Icons.Outlined.Mic, stringResource(R.string.welcome_mic_title), stringResource(R.string.welcome_mic_desc)),
        Triple(Icons.Outlined.FolderOpen, stringResource(R.string.welcome_storage_title), stringResource(R.string.welcome_storage_desc)),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE05060C))
            .then(
                if (!dismissing)
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {} // absorb touches; dismissal only via the button below
                    )
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(PulsarCyan.copy(alpha = 0.15f), CircleShape)
                    .border(1.dp, PulsarCyan.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Devices, null, tint = PulsarCyan, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.titleLarge,
                color = StarDust,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = CometTail,
            )
            Spacer(Modifier.height(24.dp))

            points.forEach { (icon, title, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(NebulaSurface, CircleShape)
                            .border(1.dp, HorizonGray, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                    }
                    Column {
                        Text(title, color = StarDust, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(desc, color = CometTail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Surface(
                color = PulsarCyan.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .border(1.dp, PulsarCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .clickable {
                        dismissing = true
                        onDismiss()
                    }
                    .padding(horizontal = 32.dp, vertical = 12.dp),
            ) {
                Text(
                    stringResource(R.string.welcome_get_started),
                    color = PulsarCyan,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
