package com.systemsgo.hex.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropOriginal
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.systemsgo.hex.R
import com.systemsgo.hex.remote.RemoteChannelStatus
import com.systemsgo.hex.ui.coachmark.CoachMarkState
import com.systemsgo.hex.ui.coachmark.coachMarkTarget
import com.systemsgo.hex.ui.components.buildCursorBitmap
import com.systemsgo.hex.ui.theme.CometTail
import com.systemsgo.hex.ui.theme.DeepSpace
import com.systemsgo.hex.ui.theme.HorizonGray
import com.systemsgo.hex.ui.theme.NebulaSurface
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.StarDust
import kotlin.math.roundToInt

/**
 * TOOLBOX FEATURE (Stage 0): unified replacement for the old fixed
 * `SessionToolbar` — a floating, draggable, collapsible container that owns
 * a "Quick Bar" of pinned [SessionTool]s plus a "+" button opening a Drawer
 * with every available tool. See SessionToolbox_خطة_المراحل.md, Stage 0.
 *
 * [SessionToolbox] itself never needs new cases for new features: every
 * stage after this one only ever adds another [SessionTool] to the [tools]
 * list the caller passes in.
 *
 * @param tools     Every tool available *this session* (already filtered by
 *                  the caller for runtime availability — e.g. the PiP tool
 *                  is simply omitted from this list when `!pipSupported`,
 *                  same as the old SessionToolbar only rendering that
 *                  ToolbarIconButton conditionally).
 * @param state     Layout/selection state — see [rememberSessionToolboxState].
 * @param visible   Master visibility switch. Callers pass `!isInPip` here so
 *                  the whole Toolbox (Quick Bar, drag handle, Drawer) vanishes
 *                  during Picture-in-Picture exactly like the old
 *                  SessionToolbar/ExtraKeysBar did.
 * @param statusContent  Optional slot rendered at the start of the Quick Bar
 *                  when docked to a horizontal edge (top/bottom) — used by
 *                  the caller for the profile name / latency text that used
 *                  to live inside SessionToolbar itself.
 */
@Composable
fun SessionToolbox(
    tools: List<SessionTool>,
    state: SessionToolboxState,
    visible: Boolean,
    modifier: Modifier = Modifier,
    statusContent: (@Composable () -> Unit)? = null,
    // "How to use the session toolbar" spotlight tour — optional so every
    // existing call site keeps compiling unchanged. When provided, the whole
    // floating toolbox (Quick Bar or its collapsed handle, whichever is
    // showing) is registered as a single coach-mark target under the key
    // "rdp_toolbox" — see RdpSessionScreen for the tour that points at it.
    coachMarkState: CoachMarkState? = null,
) {
    if (!visible) return

    val toolsById = remember(tools) { tools.associateBy { it.id } }
    val pinnedTools = remember(tools, state.quickToolIds) {
        // Order: user's pinned order first, then any tool that must show
        // itself automatically (forceVisible, e.g. Stage 1's "stop
        // recording") but wasn't explicitly pinned by the user.
        val byOrder = state.quickToolIds.mapNotNull { toolsById[it] }
        val forced = tools.filter { it.forceVisible && !state.quickToolIds.contains(it.id) }
        byOrder + forced
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }

        // Container size estimate used to keep the drag within bounds — a
        // real measured size would need onSizeChanged, but a fixed estimate
        // is enough to keep the handle from being dragged fully off-screen,
        // and gets corrected every recomposition anyway since the offset is
        // recomputed from the fraction, not accumulated.
        var containerSizePx by remember { mutableStateOf(androidx.compose.ui.geometry.Size(56f, 56f)) }

        val offsetX = (state.posXFraction * (maxWidthPx - containerSizePx.width)).roundToInt()
        val offsetY = (state.posYFraction * (maxHeightPx - containerSizePx.height)).roundToInt()

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX, offsetY) }
                .onGloballyPositionedSize { containerSizePx = it }
                .let { base ->
                    if (coachMarkState != null) base.coachMarkTarget("rdp_toolbox", coachMarkState) else base
                }
        ) {
            AnimatedVisibility(
                visible = !state.isCollapsed,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.9f, animationSpec = tween(180)),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.9f, animationSpec = tween(150)),
            ) {
                QuickBar(
                    pinnedTools = pinnedTools,
                    dockEdge = state.dockEdge,
                    statusContent = statusContent,
                    onDragBy = { dx, dy ->
                        val newX = (offsetX + dx) / (maxWidthPx - containerSizePx.width).coerceAtLeast(1f)
                        val newY = (offsetY + dy) / (maxHeightPx - containerSizePx.height).coerceAtLeast(1f)
                        state.updatePosition(newX, newY)
                    },
                    onDragEnd = { _, _ ->
                        // STAGE 11 AUDIT FIX: the two arguments here used to be
                        // dragAccumX/dragAccumY from QuickBar — the *delta*
                        // accumulated over this one drag gesture (reset to 0 at
                        // onDragStart), not an absolute container-relative
                        // pixel position. Feeding that straight into
                        // nearestDockEdge (which subtracts it from
                        // maxWidthPx/maxHeightPx as if it were absolute)
                        // picked essentially the wrong edge for any drag that
                        // didn't start at (0,0) — e.g. a short left-to-right
                        // nudge from the middle of the screen produced a tiny
                        // "xPx", which nearestDockEdge would read as "almost
                        // at the left edge" regardless of where the container
                        // actually ended up. state.posXFraction/posYFraction
                        // are already kept correct on every intermediate
                        // onDragBy call above, so re-deriving the absolute
                        // pixel position from them (instead of the raw delta)
                        // is both correct and simpler.
                        val finalXPx = state.posXFraction * maxWidthPx
                        val finalYPx = state.posYFraction * maxHeightPx
                        // Snap to nearest edge — RTL-aware: START is the
                        // right edge in an RTL layout, not the left one.
                        val nearestEdge = nearestDockEdge(
                            xPx = finalXPx, yPx = finalYPx,
                            maxWidthPx = maxWidthPx, maxHeightPx = maxHeightPx,
                            layoutDirection = layoutDirection,
                        )
                        state.dockTo(nearestEdge)
                        // STAGE 11 AUDIT FIX: dockTo() above only changes the
                        // Quick Bar's internal Row/Column orientation
                        // (isHorizontal) — it never actually moved the
                        // container back against the physical edge the plan
                        // asks for ("قابل للسحب لأي حافة من الشاشة"), so a
                        // "docked" bar could still visually sit anywhere.
                        // Snap the perpendicular axis flush to that edge here
                        // (RTL-aware, same START/END → left/right mapping as
                        // nearestDockEdge above) while leaving the position
                        // along the edge exactly where the user dropped it.
                        val snappedX = when (nearestEdge) {
                            DockEdge.START -> if (layoutDirection == LayoutDirection.Ltr) 0f else 1f
                            DockEdge.END   -> if (layoutDirection == LayoutDirection.Ltr) 1f else 0f
                            else           -> state.posXFraction
                        }
                        val snappedY = when (nearestEdge) {
                            DockEdge.TOP    -> 0f
                            DockEdge.BOTTOM -> 1f
                            else            -> state.posYFraction
                        }
                        state.updatePosition(snappedX, snappedY)
                        state.commitPosition()
                    },
                    onOpenDrawer = { state.isDrawerOpen = true },
                    onCollapse = { state.isCollapsed = true },
                )
            }

            AnimatedVisibility(
                visible = state.isCollapsed,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.9f, animationSpec = tween(180)),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.9f, animationSpec = tween(150)),
            ) {
                CollapsedHandle(onClick = { state.isCollapsed = false })
            }
        }
    }

    if (state.isDrawerOpen) {
        ToolboxDrawer(
            allTools = tools,
            isPinned = { state.isPinned(it) },
            onToggle = { state.toggleQuickBar(it) },
            onDismiss = { state.isDrawerOpen = false },
        )
    }
}

/** Small helper: reports the settled size of its content in px, as a Size (float) instead of onSizeChanged's IntSize. */
@Composable
private fun Modifier.onGloballyPositionedSize(onSize: (androidx.compose.ui.geometry.Size) -> Unit): Modifier =
    this.then(
        Modifier.onSizeChanged { intSize ->
            onSize(androidx.compose.ui.geometry.Size(intSize.width.toFloat(), intSize.height.toFloat()))
        }
    )

private fun nearestDockEdge(
    xPx: Float, yPx: Float,
    maxWidthPx: Float, maxHeightPx: Float,
    layoutDirection: LayoutDirection,
): DockEdge {
    val distTop = yPx
    val distBottom = maxHeightPx - yPx
    val distLeft = xPx
    val distRight = maxWidthPx - xPx
    val minVert = minOf(distTop, distBottom)
    val minHoriz = minOf(distLeft, distRight)
    return if (minVert <= minHoriz) {
        if (distTop <= distBottom) DockEdge.TOP else DockEdge.BOTTOM
    } else {
        val isLeftEdge = distLeft <= distRight
        // In LTR, left == START. In RTL, left == END.
        val leftIsStart = layoutDirection == LayoutDirection.Ltr
        when {
            isLeftEdge && leftIsStart -> DockEdge.START
            isLeftEdge && !leftIsStart -> DockEdge.END
            !isLeftEdge && leftIsStart -> DockEdge.END
            else -> DockEdge.START
        }
    }
}

/**
 * LIVE-CHANNEL-STATUS FEATURE: small row of dots next to the profile
 * name/latency text in [SessionToolbox]'s statusContent slot (see the call
 * site in RdpSessionActivity.kt) — one dot per redirected device channel
 * *this profile actually requested*, colored [PulsarCyan] once the server
 * has actually accepted the channel (mirrors the pinned-tool "lit up when
 * active" look already used elsewhere in the Toolbox, e.g. the connected
 * PulsarCyan glow on toggles in the setup screen) and dimmed to [CometTail]
 * while still connecting/negotiating. This is a status-only read — tapping
 * these does nothing; the enable/disable toggle for each still lives on the
 * connection setup screen, same separation of concerns as
 * [RemoteSessionClient.channelStatus]'s doc explains for why this differs
 * from that screen's toggle icons.
 */
@Composable
fun ChannelStatusRow(
    channelStatus: RemoteChannelStatus,
    showPrinter: Boolean,
    showAudio: Boolean,
    showMic: Boolean,
    showWebcam: Boolean = false,
    showSmartcard: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showPrinter) {
            ChannelStatusDot(
                icon = Icons.Outlined.Print,
                connected = channelStatus.printerConnected,
                contentDescriptionConnected = stringResource(R.string.cd_channel_status_printer_connected),
                contentDescriptionWaiting = stringResource(R.string.cd_channel_status_printer_waiting),
            )
        }
        if (showAudio) {
            ChannelStatusDot(
                icon = Icons.Outlined.VolumeUp,
                connected = channelStatus.audioPlaybackConnected,
                contentDescriptionConnected = stringResource(R.string.cd_channel_status_audio_connected),
                contentDescriptionWaiting = stringResource(R.string.cd_channel_status_audio_waiting),
            )
        }
        if (showMic) {
            ChannelStatusDot(
                icon = Icons.Outlined.Mic,
                connected = channelStatus.audioCaptureConnected,
                contentDescriptionConnected = stringResource(R.string.cd_channel_status_mic_connected),
                contentDescriptionWaiting = stringResource(R.string.cd_channel_status_mic_waiting),
            )
        }
        if (showWebcam) {
            ChannelStatusDot(
                icon = Icons.Default.Videocam,
                connected = channelStatus.webcamConnected,
                contentDescriptionConnected = stringResource(R.string.cd_channel_status_webcam_connected),
                contentDescriptionWaiting = stringResource(R.string.cd_channel_status_webcam_waiting),
            )
        }
        if (showSmartcard) {
            ChannelStatusDot(
                icon = Icons.Outlined.CreditCard,
                connected = channelStatus.smartcardConnected,
                contentDescriptionConnected = stringResource(R.string.cd_channel_status_smartcard_connected),
                contentDescriptionWaiting = stringResource(R.string.cd_channel_status_smartcard_waiting),
            )
        }
    }
}

@Composable
private fun ChannelStatusDot(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    connected: Boolean,
    contentDescriptionConnected: String,
    contentDescriptionWaiting: String,
) {
    Icon(
        icon,
        contentDescription = if (connected) contentDescriptionConnected else contentDescriptionWaiting,
        tint = if (connected) PulsarCyan else CometTail.copy(alpha = 0.5f),
        modifier = Modifier.size(14.dp),
    )
}

@Composable
private fun QuickBar(
    pinnedTools: List<SessionTool>,
    dockEdge: DockEdge,
    statusContent: (@Composable () -> Unit)?,
    onDragBy: (dx: Float, dy: Float) -> Unit,
    onDragEnd: (finalXPx: Float, finalYPx: Float) -> Unit,
    onOpenDrawer: () -> Unit,
    onCollapse: () -> Unit,
) {
    val isHorizontal = dockEdge == DockEdge.TOP || dockEdge == DockEdge.BOTTOM
    var activeTool by remember { mutableStateOf<SessionTool?>(null) }
    var dragAccumX by remember { mutableStateOf(0f) }
    var dragAccumY by remember { mutableStateOf(0f) }

    Surface(
        color = DeepSpace.copy(alpha = 0.93f),
        border = BorderStroke(1.dp, HorizonGray),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(4.dp)
    ) {
        val content: @Composable () -> Unit = {
            // Drag handle — long-press anywhere on it and drag to move the
            // whole container; a short tap collapses it (mirrors the old
            // SessionToolbar's ExpandLess/hide button).
            Box(
                // STAGE 11 AUDIT FIX: was sizeIn(32.dp, 32.dp) — under the
                // 48.dp accessibility minimum every other new Toolbox button
                // uses (ToolboxToolButton/ToolboxIconOnlyButton/
                // CollapsedHandle all already sizeIn(48.dp, 48.dp)); this
                // handle both collapses the whole Toolbox on tap *and* is the
                // drag target for repositioning it, so it's exactly the kind
                // of frequently-used, high-consequence control the plan's
                // requirement #3 is about.
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(onClick = onCollapse)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { dragAccumX = 0f; dragAccumY = 0f },
                            onDragEnd = { onDragEnd(dragAccumX, dragAccumY) },
                            onDragCancel = { onDragEnd(dragAccumX, dragAccumY) },
                        ) { change, dragAmount ->
                            change.consume()
                            onDragBy(dragAmount.x, dragAmount.y)
                            dragAccumX += dragAmount.x
                            dragAccumY += dragAmount.y
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.DragIndicator, contentDescription = stringResource(R.string.cd_toggle_toolbar), tint = CometTail, modifier = Modifier.size(18.dp))
            }

            statusContent?.let { if (isHorizontal) it() }

            pinnedTools.forEach { tool ->
                ToolboxToolButton(tool = tool, onOpenPopup = { activeTool = tool })
            }

            ToolboxIconOnlyButton(
                icon = Icons.Default.Add,
                contentDescription = stringResource(R.string.toolbox_open_drawer),
                onClick = onOpenDrawer,
            )
        }

        if (isHorizontal) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) { content() }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { content() }
        }
    }

    activeTool?.popupContent?.let { popup ->
        ToolboxPopupAnchor(onDismiss = { activeTool = null }) { dismiss -> popup(dismiss) }
    }
}

@Composable
private fun CollapsedHandle(onClick: () -> Unit) {
    Surface(
        color = DeepSpace.copy(alpha = 0.93f),
        border = BorderStroke(1.dp, HorizonGray),
        shape = CircleShape,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.DragIndicator, contentDescription = stringResource(R.string.cd_toggle_toolbar), tint = PulsarCyan, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ToolboxToolButton(tool: SessionTool, onOpenPopup: () -> Unit) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = tool.enabled) {
                if (tool.popupContent != null) onOpenPopup() else tool.onClick?.invoke()
            }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                tool.icon,
                contentDescription = null,
                tint = (tool.tint ?: PulsarCyan).let { if (tool.enabled) it else it.copy(alpha = 0.4f) },
                modifier = Modifier.size(22.dp),
            )
            Text(
                tool.label,
                style = MaterialTheme.typography.labelSmall,
                color = CometTail,
                fontSize = 9.sp,
            )
        }
        tool.badge?.invoke()
    }
    // Accessibility label carried on the clickable Box's semantics via the
    // Text above; contentDescription intentionally null on the Icon to avoid
    // TalkBack double-announcing (same fix as ToolbarIconButton, BUGFIX-UI-2).
}

@Composable
private fun ToolboxIconOnlyButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = PulsarCyan,
) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ToolboxPopupAnchor(onDismiss: () -> Unit, content: @Composable (dismiss: () -> Unit) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = DeepSpace,
            border = BorderStroke(1.dp, HorizonGray),
            shape = RoundedCornerShape(16.dp),
        ) {
            Box(Modifier.padding(16.dp)) { content(onDismiss) }
        }
    }
}

/**
 * TOOLBOX FEATURE (Stage 1) — "تصوير الجلسة" popup content: lets the user
 * take a photo or start a video recording (with a quality picker) of the
 * remote frame only. Shown as a [SessionTool.popupContent] via
 * [ToolboxPopupAnchor], so it already renders inside a small anchored
 * Dialog card — this composable only needs to fill that card's content.
 */
@Composable
fun CaptureToolPopup(
    isRecording: Boolean,
    onTakePhoto: () -> Unit,
    onStartRecording: (SessionRecorder.Quality) -> Unit,
    onStopRecording: () -> Unit,
    dismiss: () -> Unit,
) {
    var selectedQuality by remember { mutableStateOf(SessionRecorder.Quality.P480) }

    Column(modifier = Modifier.widthIn(min = 220.dp)) {
        Text(
            stringResource(R.string.capture_session_tool),
            style = MaterialTheme.typography.titleSmall,
            color = StarDust,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))

        if (isRecording) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(NovaPinkColorAlias(), CircleShape))
                Text(stringResource(R.string.recording_indicator), color = StarDust, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            CaptureActionButton(
                icon = Icons.Default.Stop,
                label = stringResource(R.string.capture_video_stop),
                tint = NovaPinkColorAlias(),
                onClick = { onStopRecording(); dismiss() },
            )
        } else {
            CaptureActionButton(
                icon = Icons.Default.CameraAlt,
                label = stringResource(R.string.capture_photo_action),
                tint = PlasmaGreenColorAlias(),
                onClick = { onTakePhoto(); dismiss() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.capture_video_quality),
                style = MaterialTheme.typography.labelSmall,
                color = CometTail,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SessionRecorder.Quality.entries.forEach { q ->
                    val selected = q == selectedQuality
                    Surface(
                        color = if (selected) PulsarCyan.copy(alpha = 0.2f) else NebulaSurface,
                        border = BorderStroke(1.dp, if (selected) PulsarCyan else HorizonGray),
                        shape = RoundedCornerShape(8.dp),
                        // STAGE 11 AUDIT FIX: was sizeIn(40.dp, 32.dp) — well
                        // under the 48.dp accessibility minimum. A full 48x48
                        // per chip doesn't fit six quality options
                        // (144p..1080p) across this popup's 220.dp min width
                        // without wrapping or a horizontal scroll the plan
                        // never asked for, so — same tradeoff already made by
                        // this app's own ExtraKeysBar (see its "BUGFIX-UI"
                        // comment, 40x44.dp) — height is raised to the 44.dp
                        // it uses, which is the largest that still keeps all
                        // six chips on one row without regressing the layout.
                        modifier = Modifier
                            .sizeIn(minWidth = 40.dp, minHeight = 44.dp)
                            .clickable { selectedQuality = q },
                    ) {
                        Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                            Text(q.label, style = MaterialTheme.typography.labelSmall, color = if (selected) PulsarCyan else StarDust)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            CaptureActionButton(
                icon = Icons.Default.Videocam,
                label = stringResource(R.string.capture_video_action),
                tint = PulsarCyan,
                onClick = { onStartRecording(selectedQuality); dismiss() },
            )
        }
    }
}

@Composable
private fun CaptureActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Surface(
        color = NebulaSurface,
        border = BorderStroke(1.dp, HorizonGray),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(label, color = StarDust, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// Small helpers so this file's top-level @Composable functions (which run
// outside RdpSessionActivity.kt's call sites) can read the same Theme.kt
// color aliases without re-importing PlasmaGreen/NovaPink under a name that
// would collide with the ones already imported at file scope above.
@Composable private fun NovaPinkColorAlias(): Color = com.systemsgo.hex.ui.theme.NovaPink
@Composable private fun PlasmaGreenColorAlias(): Color = com.systemsgo.hex.ui.theme.PlasmaGreen

/**
 * TOOLBOX FEATURE (Stage 5) — "قلب الشاشة" popup content: a small list of
 * the four [ScreenFlipMode] states (the plan's "قائمة منسدلة" option — a
 * single-tap-to-cycle affordance would also satisfy the plan, but this list
 * is more discoverable/accessible and matches the same anchored-popup
 * pattern already used by [CaptureToolPopup] and the pinned-state list in
 * [ToolboxDrawer]). Tapping an option applies it immediately and closes the
 * popup; the currently active mode is highlighted with a checkmark.
 */
@Composable
fun ScreenFlipPopup(
    currentMode: ScreenFlipMode,
    onSelect: (ScreenFlipMode) -> Unit,
    dismiss: () -> Unit,
) {
    data class FlipOption(val mode: ScreenFlipMode, val icon: androidx.compose.ui.graphics.vector.ImageVector, val labelRes: Int)

    val options = listOf(
        FlipOption(ScreenFlipMode.NORMAL,     Icons.Default.CropOriginal, R.string.screen_flip_normal),
        FlipOption(ScreenFlipMode.HORIZONTAL, Icons.Default.Flip,         R.string.screen_flip_horizontal),
        FlipOption(ScreenFlipMode.VERTICAL,   Icons.Default.SwapVert,     R.string.screen_flip_vertical),
        FlipOption(ScreenFlipMode.ROTATE_180, Icons.Default.RotateRight,  R.string.screen_flip_180),
    )

    Column(modifier = Modifier.widthIn(min = 220.dp)) {
        Text(
            stringResource(R.string.screen_flip_tool),
            style = MaterialTheme.typography.titleSmall,
            color = StarDust,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        options.forEachIndexed { index, option ->
            val selected = option.mode == currentMode
            Surface(
                color = if (selected) PulsarCyan.copy(alpha = 0.15f) else NebulaSurface,
                border = BorderStroke(1.dp, if (selected) PulsarCyan else HorizonGray),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .clickable { onSelect(option.mode); dismiss() },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(option.icon, contentDescription = null, tint = if (selected) PulsarCyan else CometTail, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(option.labelRes),
                        color = StarDust,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = PulsarCyan, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (index != options.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * REMOTEAPP-WINDOWS FEATURE — in-session single/multi window toggle popup.
 * Same anchored-popup, checkmark-on-selected pattern as [ScreenFlipPopup]
 * immediately above. This is the "live switcher" referenced by
 * RemoteAppDisplayModePicker's doc comment in Components.kt (the profile
 * editor's picker only sets the *starting* mode for a future connection;
 * this is what actually lets the choice change for the rest of an
 * already-connected session, via RdpSessionViewModel.setRemoteAppDisplayMode
 * → RemoteAppWindowManager.setDisplayMode — a pure local rendering switch,
 * never sent to the server).
 */
@Composable
fun RemoteAppDisplayModePopup(
    currentMode: com.systemsgo.hex.data.model.RemoteAppDisplayMode,
    onSelect: (com.systemsgo.hex.data.model.RemoteAppDisplayMode) -> Unit,
    dismiss: () -> Unit,
) {
    data class ModeOption(
        val mode: com.systemsgo.hex.data.model.RemoteAppDisplayMode,
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val labelRes: Int,
        val hintRes: Int,
    )

    val options = listOf(
        ModeOption(
            com.systemsgo.hex.data.model.RemoteAppDisplayMode.SINGLE_WINDOW,
            Icons.Outlined.Fullscreen,
            R.string.remote_app_display_mode_single,
            R.string.remote_app_display_mode_single_hint,
        ),
        ModeOption(
            com.systemsgo.hex.data.model.RemoteAppDisplayMode.MULTI_WINDOW,
            Icons.Outlined.ViewCarousel,
            R.string.remote_app_display_mode_multi,
            R.string.remote_app_display_mode_multi_hint,
        ),
    )

    Column(modifier = Modifier.widthIn(min = 220.dp)) {
        Text(
            stringResource(R.string.remote_app_display_mode),
            style = MaterialTheme.typography.titleSmall,
            color = StarDust,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        options.forEachIndexed { index, option ->
            val selected = option.mode == currentMode
            Surface(
                color = if (selected) PulsarCyan.copy(alpha = 0.15f) else NebulaSurface,
                border = BorderStroke(1.dp, if (selected) PulsarCyan else HorizonGray),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .clickable { onSelect(option.mode); dismiss() },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(option.icon, contentDescription = null, tint = if (selected) PulsarCyan else CometTail, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(option.labelRes), color = StarDust, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(option.hintRes), color = CometTail, style = MaterialTheme.typography.labelSmall)
                    }
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = PulsarCyan, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (index != options.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * TOOLBOX FEATURE (Stage 8, Part 2) — "جودة الاتصال" popup content: lets the
 * user switch [com.systemsgo.hex.data.model.RdpPerformance] level for the
 * *current, already-connected* session via
 * `RdpSessionViewModel.changeSessionQuality()`. That function does not
 * change the live protocol session in place (aFreeRDP/VNC here have no such
 * capability — see docs/stage8-quality-live-change-investigation.md); it
 * opens a second client at the new quality in the background and swaps to
 * it only once CONNECTED, so the user never sees a "Connecting…" screen.
 * This popup only presents the choice and reflects in-flight/settled state
 * — same anchored-Dialog-card pattern as [ScreenFlipPopup] above (five
 * options here instead of four, no icon per row since the label alone is
 * unambiguous, same as [CursorShapePopup]'s style rows minus the preview
 * swatch).
 *
 * Same five levels, same order, and same labels as
 * Settings → Connection → "جودة العرض" (`R.string.network_quality_*`), per
 * requirement #1, so the indicator here never contradicts the Settings
 * screen.
 *
 * @param currentLevel  `settings.value.performanceLevel` as read by the
 *                       caller — the level actually *running*, not a
 *                       locally-buffered selection, since
 *                       `changeSessionQuality()` reverts this setting on a
 *                       failed swap (single source of truth, requirement
 *                       #3). This is why selection here doesn't optimistically
 *                       move the checkmark itself — it moves only once
 *                       [currentLevel] itself changes upstream.
 * @param inProgress     Mirrors `RdpSessionViewModel.qualityChangeInProgress`.
 *                       While true, every row is disabled (can't queue a
 *                       second background swap mid-flight — the ViewModel
 *                       already debounces this too, this is just the UI
 *                       reflecting why nothing happens on tap) and a small
 *                       spinner replaces the title row's trailing space.
 */
@Composable
fun QualityToolPopup(
    currentLevel: Int,
    inProgress: Boolean,
    onSelect: (Int) -> Unit,
    dismiss: () -> Unit,
) {
    data class QualityOption(val level: Int, val labelRes: Int)

    val options = listOf(
        QualityOption(com.systemsgo.hex.data.model.RdpPerformance.LOW_BANDWIDTH, R.string.network_quality_very_weak),
        QualityOption(com.systemsgo.hex.data.model.RdpPerformance.MEDIUM,        R.string.network_quality_weak),
        QualityOption(com.systemsgo.hex.data.model.RdpPerformance.WIFI,          R.string.network_quality_medium),
        QualityOption(com.systemsgo.hex.data.model.RdpPerformance.LAN,           R.string.network_quality_strong),
        QualityOption(com.systemsgo.hex.data.model.RdpPerformance.AUTO,          R.string.network_quality_very_strong),
    )

    Column(modifier = Modifier.widthIn(min = 220.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.connection_quality_tool),
                style = MaterialTheme.typography.titleSmall,
                color = StarDust,
                fontWeight = FontWeight.SemiBold,
            )
            // Small, non-blocking loading indicator (requirement #2) — not a
            // full "Connecting…" screen — visible for as long as the
            // background swap client hasn't reached CONNECTED yet.
            if (inProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = PulsarCyan,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        options.forEachIndexed { index, option ->
            val selected = option.level == currentLevel
            Surface(
                color = if (selected) PulsarCyan.copy(alpha = 0.15f) else NebulaSurface,
                border = BorderStroke(1.dp, if (selected) PulsarCyan else HorizonGray),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    // Disabled while a swap is already in flight (debounced
                    // upstream too, per changeSessionQuality()'s
                    // qualityChangeInProgress guard) and for the
                    // already-selected level (nothing to do).
                    .clickable(enabled = !inProgress && !selected) { onSelect(option.level); dismiss() },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(option.labelRes),
                        color = if (inProgress && !selected) CometTail else StarDust,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = PulsarCyan, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (index != options.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * TOOLBOX FEATURE (Stage 6b) — "شكل المؤشر" popup content: lets the user
 * pick the local cursor's style and size mid-session, without leaving to
 * Settings. Reuses the exact same [buildCursorBitmap] renderer (and the
 * same four styles: default/crosshair/dot/circle) already offered by
 * `SettingsCursorChoice` in SettingsScreen.kt — this popup is a quicker,
 * session-scoped door to the *same* persisted `cursorStyle`/`cursorSize`
 * settings, not a separate/duplicate preference.
 *
 * This tool is only meaningful in [MouseInputMode.DIRECT] (see the
 * `enabled = mouseInputMode == MouseInputMode.DIRECT` gate on its
 * [SessionTool] in RdpSessionActivity.kt) — a disabled tool never opens its
 * popupContent (see [ToolboxToolButton]'s `clickable(enabled = ...)`), so
 * this composable itself doesn't need to special-case TOUCHPAD mode.
 */
@Composable
fun CursorShapePopup(
    currentStyle: String,
    currentSize: Int,
    accent: Color,
    onStyleSelect: (String) -> Unit,
    onSizeChange: (Int) -> Unit,
    dismiss: () -> Unit,
) {
    data class StyleOption(val key: String, val labelRes: Int)

    val options = listOf(
        StyleOption("default",   R.string.cursor_default),
        StyleOption("crosshair", R.string.cursor_crosshair),
        StyleOption("dot",       R.string.cursor_dot),
        StyleOption("circle",    R.string.cursor_circle),
    )

    Column(modifier = Modifier.widthIn(min = 240.dp)) {
        Text(
            stringResource(R.string.cursor_shape_tool),
            style = MaterialTheme.typography.titleSmall,
            color = StarDust,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        options.forEachIndexed { index, option ->
            val selected = option.key == currentStyle
            Surface(
                color = if (selected) PulsarCyan.copy(alpha = 0.15f) else NebulaSurface,
                border = BorderStroke(1.dp, if (selected) PulsarCyan else HorizonGray),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .clickable { onStyleSelect(option.key) },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CursorShapePreviewIcon(cursorStyle = option.key, cursorSize = currentSize, accent = accent)
                    Text(
                        stringResource(option.labelRes),
                        color = StarDust,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = PulsarCyan, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (index != options.lastIndex) Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.cursor_size), style = MaterialTheme.typography.bodySmall, color = CometTail)
            Text("${currentSize}px", style = MaterialTheme.typography.bodySmall, color = PulsarCyan)
        }
        Slider(
            value = currentSize.toFloat(),
            valueRange = 12f..48f,
            onValueChange = { onSizeChange(it.roundToInt()) },
            colors = SliderDefaults.colors(
                thumbColor = PulsarCyan,
                activeTrackColor = PulsarCyan,
                inactiveTrackColor = HorizonGray,
            ),
        )
    }
}

/**
 * Small live preview swatch for one cursor style inside [CursorShapePopup] —
 * same rendering approach as SettingsScreen.kt's private `CursorPreviewBox`
 * (kept as a separate copy here rather than a shared export since it's a
 * two-line wrapper around the already-shared [buildCursorBitmap], and the
 * two screens' surrounding layout/sizing differ enough that sharing the
 * wrapper itself wouldn't save much).
 */
@Composable
private fun CursorShapePreviewIcon(cursorStyle: String, cursorSize: Int, accent: Color) {
    val previewBitmap = remember(cursorStyle, cursorSize, accent) {
        buildCursorBitmap(cursorStyle, cursorSize.coerceIn(16, 32), accent).asImageBitmap()
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(DeepSpace, RoundedCornerShape(8.dp))
            .border(1.dp, HorizonGray.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(bitmap = previewBitmap, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/**
 * "كل الأدوات" — full catalog of every tool, whether pinned or not. Tapping
 * a tool toggles its Quick Bar membership (checkmark = pinned). This keeps
 * every add/remove action a single accessible tap in addition to the
 * drag-style mental model described in the plan — a fully free-form drag
 * between two independent lists adds real complexity (drop-zone detection,
 * autoscroll, RTL-mirrored hit-testing) for the same end result a tap
 * already gives reliably and accessibly; the drag handle inside the Quick
 * Bar itself still supports true drag for *repositioning/removal* (dragging
 * the whole container off an edge), so the two together cover the plan's
 * "drag anywhere" intent without a fragile custom DnD stack.
 */
@Composable
private fun ToolboxDrawer(
    allTools: List<SessionTool>,
    isPinned: (String) -> Boolean,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = DeepSpace,
            border = BorderStroke(1.dp, HorizonGray),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 480.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.toolbox_drawer_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = StarDust,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ToolboxIconOnlyButton(
                        icon = Icons.Default.Close,
                        // STAGE 11 AUDIT FIX: was stringResource(R.string.cd_toggle_toolbar)
                        // — a leftover copy-paste from the drag handle's own
                        // contentDescription. This button *closes the Drawer
                        // dialog*, it doesn't toggle the toolbar, so a
                        // TalkBack user heard the wrong action announced.
                        // cd_close already exists and is used for the exact
                        // same X-icon/dismiss pattern elsewhere in the app
                        // (SessionTabsBar.kt, FileTransferScreen.kt).
                        contentDescription = stringResource(R.string.cd_close),
                        onClick = onDismiss,
                        tint = CometTail,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(allTools, key = { it.id }) { tool ->
                        val pinned = isPinned(tool.id)
                        Surface(
                            color = if (pinned) PulsarCyan.copy(alpha = 0.12f) else NebulaSurface,
                            border = BorderStroke(1.dp, if (pinned) PulsarCyan else HorizonGray),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 48.dp)
                                .clickable { onToggle(tool.id) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(tool.icon, contentDescription = null, tint = tool.tint ?: PulsarCyan, modifier = Modifier.size(20.dp))
                                Text(tool.label, style = MaterialTheme.typography.bodyMedium, color = StarDust, modifier = Modifier.weight(1f))
                                if (pinned) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = PulsarCyan, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
