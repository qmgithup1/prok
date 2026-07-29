package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.systemsgo.hex.data.repository.AppSettings
import com.systemsgo.hex.ui.theme.ConnectingAmber
import com.systemsgo.hex.ui.theme.DeepSpace
import com.systemsgo.hex.ui.theme.ErrorRed
import com.systemsgo.hex.ui.theme.PlasmaGreen
import com.systemsgo.hex.ui.theme.StarDust
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * TOOLBOX FEATURE (Stage 7 follow-up): user-requested upgrade to the FPS /
 * سرعة الاستجابة (latency) overlay — instead of a fixed corner, it can be
 * dragged anywhere on screen with one finger and pinch-resized with two,
 * independently of the Toolbox's own position/size.
 *
 * Mirrors [SessionToolboxState]'s "position as a 0f..1f fraction of the
 * available area" approach (so it re-anchors sensibly across rotations and
 * screen sizes) but deliberately skips [DockEdge] snapping — this is a
 * read-only readout, not something users open a drawer for or dock to an
 * edge, so fully free placement is simpler and matches the request ("نضعه
 * بأي مكان").
 */
class CounterOverlayState internal constructor(
    initialPosXFraction: Float,
    initialPosYFraction: Float,
    initialScale: Float,
    private val persistPosition: (xFraction: Float, yFraction: Float) -> Unit,
    private val persistScale: (Float) -> Unit,
) {
    var posXFraction: Float by mutableStateOf(initialPosXFraction)
        private set
    var posYFraction: Float by mutableStateOf(initialPosYFraction)
        private set
    var scale: Float by mutableStateOf(initialScale)
        private set

    /** Called continuously while dragging/pinching — cheap in-memory update only, no I/O. */
    fun updateLive(xFraction: Float, yFraction: Float, newScale: Float) {
        posXFraction = xFraction.coerceIn(0f, 1f)
        posYFraction = yFraction.coerceIn(0f, 1f)
        scale = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /**
     * Commits the current position/scale to storage. The caller (see
     * [CounterOverlay] below) debounces this — [detectTransformGestures] has
     * no built-in "gesture ended" callback the way [detectDragGestures]'s
     * onDragEnd does (which is what SessionToolbox.kt uses for its own
     * drag), so writing on every single pointer-move frame would hit
     * EncryptedSharedPreferences far more often than needed. A short delay
     * after the last movement gives the same "commit once, at the end"
     * result without needing a custom gesture detector.
     */
    fun commit() {
        persistPosition(posXFraction, posYFraction)
        persistScale(scale)
    }

    companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 2.2f
        /** Sentinel meaning "the user never dragged this yet" — see [rememberCounterOverlayState]. */
        const val UNSET = -1f
    }
}

@Composable
fun rememberCounterOverlayState(
    settings: AppSettings,
    onPositionChanged: (xFraction: Float, yFraction: Float) -> Unit,
    onScaleChanged: (Float) -> Unit,
): CounterOverlayState {
    val layoutDirection = LocalLayoutDirection.current
    return remember {
        val hasCustomPosition =
            settings.counterPosXFraction >= 0f && settings.counterPosYFraction >= 0f
        CounterOverlayState(
            // RTL-aware default corner (bottom-start, roughly matching the old
            // fixed-position overlay) for a brand-new install / a user who
            // never dragged it — once they do, their own fraction from
            // settings is used on every later session instead.
            initialPosXFraction = if (hasCustomPosition) settings.counterPosXFraction
                else if (layoutDirection == LayoutDirection.Rtl) 1f else 0f,
            initialPosYFraction = if (hasCustomPosition) settings.counterPosYFraction else 0.82f,
            initialScale = settings.counterScale,
            persistPosition = onPositionChanged,
            persistScale = onScaleChanged,
        )
    }
}

/**
 * Renders the FPS/latency chip(s) at [state]'s current position/scale, and
 * wires up the one-finger-drag + two-finger-pinch gesture that updates it.
 * Shows nothing if both [showFps] and [showLatency] are false — same
 * independent-visibility contract as the two Toolbox tools that control them.
 */
@Composable
fun CounterOverlay(
    state: CounterOverlayState,
    showFps: Boolean,
    showLatency: Boolean,
    // PERF FIX (smoothness pass): was a plain `fpsText: String` computed by
    // the caller (RdpSessionScreen) from a `frameRateMs` value collected at
    // that composable's own top-level scope — meaning every remote frame
    // update (15-60×/sec) invalidated RdpSessionScreen's entire ~2000-line
    // scope just to keep this one small chip's text current. Collecting the
    // StateFlow here instead confines that per-frame read to this tiny
    // composable. Mirrors the same fix applied to RdpCanvas's bitmap.
    frameRateFlow: StateFlow<Long>,
    latencyMs: Long,
) {
    if (!showFps && !showLatency) return

    val frameRateMs by frameRateFlow.collectAsStateWithLifecycle()
    val fpsText = remember(frameRateMs) {
        if (frameRateMs > 0L) "${(1000L / frameRateMs.coerceAtLeast(1L)).coerceAtMost(999)}fps"
        else "-- fps"
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }

        // Measured size estimate used for drag/pinch bounds-checking — like
        // SessionToolbox.kt's own containerSizePx, corrected every
        // recomposition rather than accumulated, so it self-heals if the
        // text length (and therefore the chip's width) changes.
        var containerSizePx by remember { mutableStateOf(Size(80f, 32f)) }

        val offsetX = (state.posXFraction * (maxWidthPx - containerSizePx.width)).roundToInt()
        val offsetY = (state.posYFraction * (maxHeightPx - containerSizePx.height)).roundToInt()

        // Debounced persistence — see the [CounterOverlayState.commit] doc
        // comment for why this isn't done on every gesture frame.
        var commitTick by remember { mutableStateOf(0) }
        LaunchedEffect(commitTick) {
            if (commitTick == 0) return@LaunchedEffect
            delay(400)
            state.commit()
        }

        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX, offsetY) }
                .onSizeChanged { containerSizePx = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(Unit) {
                    // One finger → pan only (drag to reposition). Two fingers →
                    // pan + zoom together (drag while resizing), same natural
                    // gesture users already know from maps/photo apps.
                    detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                        val widthRange = (maxWidthPx - containerSizePx.width).coerceAtLeast(1f)
                        val heightRange = (maxHeightPx - containerSizePx.height).coerceAtLeast(1f)
                        val newXFraction = (offsetX + pan.x) / widthRange
                        val newYFraction = (offsetY + pan.y) / heightRange
                        state.updateLive(newXFraction, newYFraction, state.scale * zoom)
                        commitTick++
                    }
                }
                .graphicsLayer(scaleX = state.scale, scaleY = state.scale)
                .background(DeepSpace.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showFps) {
                Text(
                    text = fpsText,
                    style = MaterialTheme.typography.labelSmall,
                    color = StarDust,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (showLatency) {
                Text(
                    text = "${latencyMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        latencyMs < 100 -> PlasmaGreen
                        latencyMs < 300 -> ConnectingAmber
                        else -> ErrorRed
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
