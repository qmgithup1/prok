package com.systemsgo.hex.ui.screens

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.systemsgo.hex.R
import com.systemsgo.hex.audio.SoundManager
import com.systemsgo.hex.ui.components.LocalSoundManager
import com.systemsgo.hex.ui.theme.*
import com.systemsgo.hex.util.DeviceFormFactor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * SPLIT-SCREEN FEATURE
 *
 * Hosts exactly two independent remote sessions (any combination of RDP /
 * VNC / SSH — each pane is a full [RdpSessionViewModel], the same class a
 * single, full-screen [RdpSessionActivity] uses) inside one Activity, laid
 * out side by side (or stacked) with a draggable divider.
 *
 * Independence: each pane gets its OWN [RdpSessionViewModel] instance
 * (`hiltViewModel(key = "split_left" / "split_right")`), so connection
 * state, input, keyboard, mouse, clipboard, and zoom/pan are entirely
 * separate — they are literally two unrelated ViewModels, each owning its
 * own [com.systemsgo.hex.remote.RemoteSessionClient], exactly as if they were
 * two separate [RdpSessionActivity] instances. Nothing about a pane's
 * connection is torn down by resizing, maximizing, or switching layout —
 * only the Compose layout size changes; the underlying session keeps
 * running (see [PaneMaximizeState] below).
 *
 * Gating: only reachable from [SplitScreenPickerScreen], which itself is
 * only offered when [DeviceFormFactor.supportsDesktopFeatures] is true.
 * This Activity re-checks that condition on its own too (defense in depth —
 * e.g. a Samsung DeX session that was undocked while this screen was in the
 * background) and exits split mode automatically if the surface shrinks
 * below desktop-class.
 */
@AndroidEntryPoint
class SplitScreenActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID_LEFT = "profile_id_left"
        const val EXTRA_PROFILE_ID_RIGHT = "profile_id_right"

        fun intent(context: android.content.Context, leftProfileId: String, rightProfileId: String) =
            Intent(context, SplitScreenActivity::class.java)
                .putExtra(EXTRA_PROFILE_ID_LEFT, leftProfileId)
                .putExtra(EXTRA_PROFILE_ID_RIGHT, rightProfileId)
    }

    @Inject lateinit var soundManager: SoundManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // MIC-REDIRECT FEATURE parity: mirrors RdpSessionActivity — either pane
        // may be an RDP profile with mic redirection enabled.
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 0)
        // WEBCAM-REDIRECT FEATURE parity: same reasoning — either pane may be
        // an RDP profile with webcam redirection enabled.
        requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 0)

        val leftProfileId = intent.getStringExtra(EXTRA_PROFILE_ID_LEFT)
        val rightProfileId = intent.getStringExtra(EXTRA_PROFILE_ID_RIGHT)
        if (leftProfileId == null || rightProfileId == null) {
            finish()
            return
        }

        val metrics = resources.displayMetrics

        setContent {
            // Theming note: the split-screen container uses the app's
            // default theme resolution (system dark-mode + "space" variant)
            // for its own thin top bar chrome; each pane's RdpSessionScreen
            // still renders with the user's real saved theme once connected
            // since it reads settings from its own RdpSessionViewModel
            // exactly like a normal single session.
            SystemsGoTheme {
                CompositionLocalProvider(LocalSoundManager provides soundManager) {
                    SplitScreenRoot(
                        leftProfileId = leftProfileId,
                        rightProfileId = rightProfileId,
                        deviceWidth = metrics.widthPixels,
                        deviceHeight = metrics.heightPixels,
                        onExit = { finish() }
                    )
                }
            }
        }
    }

    // LIVE-RESIZE: same rationale as RdpSessionActivity — undocking from DeX
    // or unfolding/folding a foldable changes Configuration without always
    // recreating the Activity when configChanges is declared in the
    // manifest (see AndroidManifest.xml entry for this Activity, mirrored
    // from RdpSessionActivity's).
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
    }
}

private enum class PaneMaximizeState { NONE, LEFT, RIGHT }
private enum class SplitOrientation { HORIZONTAL, VERTICAL }

@Composable
private fun SplitScreenRoot(
    leftProfileId: String,
    rightProfileId: String,
    deviceWidth: Int,
    deviceHeight: Int,
    onExit: () -> Unit
) {
    val context = LocalContext.current

    // Defense in depth: re-check desktop-class eligibility inside the
    // Activity itself, not just at the picker's entry point (task
    // requirement: "Automatically disable Split Screen on small phone
    // screens").
    if (!DeviceFormFactor.supportsDesktopFeatures(context)) {
        UnsupportedSplitScreenNotice(onExit = onExit)
        return
    }

    // Two fully independent ViewModel instances — see class doc above.
    val leftViewModel: RdpSessionViewModel = hiltViewModel(key = "split_left")
    val rightViewModel: RdpSessionViewModel = hiltViewModel(key = "split_right")

    LaunchedEffect(leftProfileId) {
        leftViewModel.loadAndConnect(leftProfileId, deviceWidth, deviceHeight)
    }
    LaunchedEffect(rightProfileId) {
        rightViewModel.loadAndConnect(rightProfileId, deviceWidth, deviceHeight)
    }

    var orientation by rememberSaveable { mutableStateOf(SplitOrientation.HORIZONTAL) }
    var splitFraction by rememberSaveable { mutableStateOf(0.5f) }
    var maximized by rememberSaveable { mutableStateOf(PaneMaximizeState.NONE) }

    // Back press: restore a maximized pane first, then exit split screen —
    // never silently disconnect both sessions on an accidental back-press.
    BackHandler {
        if (maximized != PaneMaximizeState.NONE) {
            maximized = PaneMaximizeState.NONE
        } else {
            onExit()
        }
    }

    Column(Modifier.fillMaxSize().background(DeepSpace)) {
        SplitScreenTopBar(
            orientation = orientation,
            onToggleOrientation = {
                orientation = if (orientation == SplitOrientation.HORIZONTAL)
                    SplitOrientation.VERTICAL else SplitOrientation.HORIZONTAL
            },
            maximized = maximized,
            onSetMaximized = { maximized = it },
            onExit = onExit
        )

        BoxWithConstraints(Modifier.fillMaxSize().weight(1f)) {
            val totalPx = if (orientation == SplitOrientation.HORIZONTAL) constraints.maxWidth else constraints.maxHeight
            val dividerThicknessDp = 6.dp

            // Effective weights for the two panes + the divider. Deliberately
            // kept as ONE stable Row/Column structure (same call sites for
            // both SessionPane composables on every recomposition) rather
            // than branching into separate composable trees per
            // maximize-state — branching would move each RdpSessionScreen to
            // a different slot-table position on every maximize/restore
            // toggle, which resets its *local* Compose state (zoom level,
            // pan offset, toolbar visibility). Only the weights change here,
            // so that local UI state survives maximizing exactly like the
            // live connection itself already does.
            val leftWeight = when (maximized) {
                PaneMaximizeState.LEFT -> 1f
                PaneMaximizeState.RIGHT -> 0.0001f
                PaneMaximizeState.NONE -> splitFraction
            }
            val rightWeight = when (maximized) {
                PaneMaximizeState.LEFT -> 0.0001f
                PaneMaximizeState.RIGHT -> 1f
                PaneMaximizeState.NONE -> 1f - splitFraction
            }
            val dividerEnabled = maximized == PaneMaximizeState.NONE
            val dividerModifier = if (dividerEnabled) Modifier else Modifier.size(0.dp)

            if (orientation == SplitOrientation.HORIZONTAL) {
                Row(Modifier.fillMaxSize()) {
                    SessionPane(leftViewModel, Modifier.fillMaxHeight().weight(leftWeight))
                    if (dividerEnabled) {
                        SplitDivider(
                            orientation = orientation,
                            thickness = dividerThicknessDp,
                            onDrag = { deltaPx ->
                                splitFraction = (splitFraction + deltaPx / totalPx.toFloat()).coerceIn(0.2f, 0.8f)
                            }
                        )
                    }
                    SessionPane(rightViewModel, Modifier.fillMaxHeight().weight(rightWeight))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    SessionPane(leftViewModel, Modifier.fillMaxWidth().weight(leftWeight))
                    if (dividerEnabled) {
                        SplitDivider(
                            orientation = orientation,
                            thickness = dividerThicknessDp,
                            onDrag = { deltaPx ->
                                splitFraction = (splitFraction + deltaPx / totalPx.toFloat()).coerceIn(0.2f, 0.8f)
                            }
                        )
                    }
                    SessionPane(rightViewModel, Modifier.fillMaxWidth().weight(rightWeight))
                }
            }
        }
    }
}

@Composable
private fun SessionPane(viewModel: RdpSessionViewModel, modifier: Modifier = Modifier) {
    Box(modifier) {
        RdpSessionScreen(
            viewModel = viewModel,
            onClose = { /* Closing a single pane is handled by the top bar / exit, not the pane itself. */ },
            enableBackHandler = false
        )
    }
}

@Composable
private fun SplitDivider(
    orientation: SplitOrientation,
    thickness: androidx.compose.ui.unit.Dp,
    onDrag: (Float) -> Unit
) {
    val handleModifier = if (orientation == SplitOrientation.HORIZONTAL) {
        Modifier
            .fillMaxHeight()
            .width(thickness)
            .pointerInput(orientation) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x)
                }
            }
    } else {
        Modifier
            .fillMaxWidth()
            .height(thickness)
            .pointerInput(orientation) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.y)
                }
            }
    }

    Box(
        handleModifier.background(NebulaSurface),
        contentAlignment = Alignment.Center
    ) {
        // Small grip indicator so the divider visibly reads as draggable.
        val gripModifier = if (orientation == SplitOrientation.HORIZONTAL)
            Modifier.width(3.dp).height(36.dp) else Modifier.height(3.dp).width(36.dp)
        Box(gripModifier.background(PulsarCyan.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun SplitScreenTopBar(
    orientation: SplitOrientation,
    onToggleOrientation: () -> Unit,
    maximized: PaneMaximizeState,
    onSetMaximized: (PaneMaximizeState) -> Unit,
    onExit: () -> Unit
) {
    Surface(color = NebulaSurface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Splitscreen, null, tint = PulsarCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.split_screen_title),
                    color = StarDust,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (maximized == PaneMaximizeState.NONE) {
                    IconButton(onClick = onToggleOrientation) {
                        Icon(
                            if (orientation == SplitOrientation.HORIZONTAL) Icons.Outlined.ViewColumn else Icons.Outlined.ViewAgenda,
                            contentDescription = stringResource(R.string.split_screen_toggle_layout),
                            tint = CometTail
                        )
                    }
                    IconButton(onClick = { onSetMaximized(PaneMaximizeState.LEFT) }) {
                        Icon(Icons.Outlined.OpenInFull, contentDescription = stringResource(R.string.split_screen_maximize_left), tint = CometTail)
                    }
                    IconButton(onClick = { onSetMaximized(PaneMaximizeState.RIGHT) }) {
                        Icon(Icons.Outlined.OpenInFull, contentDescription = stringResource(R.string.split_screen_maximize_right), tint = CometTail)
                    }
                } else {
                    TextButton(onClick = { onSetMaximized(PaneMaximizeState.NONE) }) {
                        Icon(Icons.Outlined.CloseFullscreen, null, tint = PulsarCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.split_screen_restore), color = PulsarCyan)
                    }
                }
                IconButton(onClick = onExit) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.split_screen_exit), tint = NovaPink)
                }
            }
        }
    }
}

@Composable
private fun UnsupportedSplitScreenNotice(onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(DeepSpace), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Outlined.Splitscreen, null, tint = CometTail.copy(alpha = 0.5f), modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.split_screen_unsupported),
                color = StarDust,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.split_screen_unsupported_detail),
                color = CometTail,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onExit) { Text(stringResource(R.string.close_session), color = PulsarCyan) }
        }
    }
}
