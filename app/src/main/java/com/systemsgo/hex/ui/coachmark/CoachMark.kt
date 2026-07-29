package com.systemsgo.hex.ui.coachmark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.systemsgo.hex.ui.theme.CometTail
import com.systemsgo.hex.ui.theme.NebulaSurface
import com.systemsgo.hex.ui.theme.StarDust
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// COACH MARK / SPOTLIGHT ENGINE
//
// A small, screen-agnostic library for the "spotlight on one control at a
// time + short explanation" tutorials (e.g. "How to create a connection").
// Deliberately generic and free of any single screen's imports so it can be
// reused for the Add-Connection flow, the RDP session toolbar, or anywhere
// else later — only the *step content* (title/description/order) is
// screen-specific and lives where the screen is built.
//
// USAGE
//   val coach = rememberCoachMarkState()
//   Box {
//       Column {
//           SearchField(Modifier.coachMarkTarget("search", coach))
//           ConnectButton(Modifier.coachMarkTarget("connect", coach))
//       }
//       CoachMarkOverlay(state = coach)
//   }
//   LaunchedEffect(Unit) {
//       if (shouldShowTutorial) {
//           coach.start(listOf(
//               CoachMarkStep("search", "Search", "Find any protocol fast."),
//               CoachMarkStep("connect", "Connect", "Tap to start the session."),
//           ))
//       }
//   }
//
// DESIGN NOTES (see also the research summary shared in chat):
//   - One highlighted element at a time (avoids clutter).
//   - Non-interactive spotlight — the highlighted element is NOT clickable
//     while the tour is active; navigation is only via the tooltip's
//     Next/Skip controls. This avoids the classic confusion where users
//     expect the dimmed screen to be tappable (NN/g's Wimbledon-app finding).
//   - Contextual: callers should trigger `start()` the first time the user
//     reaches a given screen/moment, not the first time the whole app opens.
//   - Persistence of "already seen" is intentionally NOT handled here — see
//     CoachMarkPreferences, which callers read before invoking `start()`.
// ─────────────────────────────────────────────────────────────────────────────

/** Shape drawn around a target when the scrim is punched out. */
sealed class CoachMarkShape {
    data class RoundedRect(val cornerRadius: Dp = 16.dp) : CoachMarkShape()
    data object Oval : CoachMarkShape()
}

/** One stop of a coach-mark tour. [targetKey] must match a `Modifier.coachMarkTarget(key, state)`. */
data class CoachMarkStep(
    val targetKey: String,
    val title: String,
    val description: String,
    val shape: CoachMarkShape = CoachMarkShape.RoundedRect(),
    /** Extra breathing room (px→dp) added around the target's measured bounds before cutting the hole. */
    val spotlightPadding: Dp = 8.dp,
)

/** Holds the running tour (if any) and the live screen-position of every registered target. */
@Stable
class CoachMarkState {
    internal val targets = mutableStateMapOf<String, LayoutCoordinates>()

    var steps: List<CoachMarkStep> = emptyList()
        private set

    var activeIndex by mutableStateOf(-1)
        private set

    val isRunning: Boolean get() = activeIndex in steps.indices
    val currentStep: CoachMarkStep? get() = steps.getOrNull(activeIndex)
    val isLastStep: Boolean get() = activeIndex == steps.lastIndex

    /** Begins a tour. No-ops on an empty list. */
    fun start(steps: List<CoachMarkStep>) {
        if (steps.isEmpty()) return
        this.steps = steps
        activeIndex = 0
    }

    fun next() {
        if (!isRunning) return
        if (isLastStep) stop() else activeIndex++
    }

    /** Ends the tour early (Skip). */
    fun stop() {
        activeIndex = -1
        steps = emptyList()
    }

    internal fun registerTarget(key: String, coordinates: LayoutCoordinates) {
        targets[key] = coordinates
    }

    internal fun unregisterTarget(key: String) {
        targets.remove(key)
    }
}

@Composable
fun rememberCoachMarkState(): CoachMarkState = remember { CoachMarkState() }

/** Marks a composable as a possible coach-mark target under [key]. Order of application doesn't matter. */
fun Modifier.coachMarkTarget(key: String, state: CoachMarkState): Modifier = composed {
    DisposableEffect(key, state) {
        onDispose { state.unregisterTarget(key) }
    }
    onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) state.registerTarget(key, coordinates)
    }
}

/**
 * Full-screen overlay that renders the current step of [state]'s tour, if any.
 * Place it LAST inside the same root [Box] as the screen content, so its
 * root-relative coordinates line up with the targets' `boundsInRoot()`.
 *
 * [guideAvatar] is optional — pass the mascot painter to show it next to the
 * tooltip copy (static image only; no animation is wired here on purpose).
 */
@Composable
fun CoachMarkOverlay(
    state: CoachMarkState,
    modifier: Modifier = Modifier,
    guideAvatar: Painter? = null,
    nextLabel: String,
    doneLabel: String,
    skipLabel: String,
    onFinished: () -> Unit = {},
) {
    val step = state.currentStep ?: return
    val coordinates = state.targets[step.targetKey]

    // Target isn't laid out yet (off-screen in a list, not composed this frame, etc.)
    // Skip forward instead of showing a full-screen scrim pointing at nothing.
    if (coordinates == null || !coordinates.isAttached) {
        LaunchedEffect(step) { state.next() }
        return
    }

    val targetRect = coordinates.boundsInRoot()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
        val screenHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }

        Spotlight(
            targetRect = targetRect,
            shape = step.shape,
            padding = step.spotlightPadding,
            modifier = Modifier
                .fillMaxSize()
                // Swallow every tap on the scrim — the tour only advances via
                // the tooltip's own buttons (see design notes above).
                .pointerInput(step.targetKey) {
                    detectTapGestures { /* consume — modal while a tour is active */ }
                },
        )

        CoachMarkTooltip(
            step = step,
            targetRect = targetRect,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            stepIndex = state.activeIndex,
            stepCount = state.steps.size,
            guideAvatar = guideAvatar,
            nextLabel = if (state.isLastStep) doneLabel else nextLabel,
            skipLabel = skipLabel,
            showSkip = !state.isLastStep,
            onNext = {
                if (state.isLastStep) {
                    state.stop()
                    onFinished()
                } else {
                    state.next()
                }
            },
            onSkip = {
                state.stop()
                onFinished()
            },
        )
    }
}

/** Draws the dimmed scrim with a transparent hole cut around [targetRect]. */
@Composable
private fun Spotlight(
    targetRect: Rect,
    shape: CoachMarkShape,
    padding: Dp,
    modifier: Modifier = Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Canvas(
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val padPx = with(density) { padding.toPx() }
        val hole = Rect(
            left = targetRect.left - padPx,
            top = targetRect.top - padPx,
            right = targetRect.right + padPx,
            bottom = targetRect.bottom + padPx,
        )

        drawRect(color = Color.Black.copy(alpha = 0.80f))

        when (shape) {
            is CoachMarkShape.RoundedRect -> drawRoundRect(
                color = Color.Transparent,
                topLeft = hole.topLeft,
                size = hole.size,
                cornerRadius = CornerRadius(with(density) { shape.cornerRadius.toPx() }),
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
            )
            CoachMarkShape.Oval -> drawOval(
                color = Color.Transparent,
                topLeft = hole.topLeft,
                size = hole.size,
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
            )
        }

        // Thin glowing ring right at the cutout edge so the highlighted area
        // reads clearly even against busy content behind it.
        when (shape) {
            is CoachMarkShape.RoundedRect -> drawRoundRect(
                color = PulsarCyanStatic,
                topLeft = hole.topLeft,
                size = hole.size,
                cornerRadius = CornerRadius(with(density) { shape.cornerRadius.toPx() }),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = with(density) { 2.dp.toPx() }),
            )
            CoachMarkShape.Oval -> drawOval(
                color = PulsarCyanStatic,
                topLeft = hole.topLeft,
                size = hole.size,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = with(density) { 2.dp.toPx() }),
            )
        }
    }
}

// Canvas draw calls run outside @Composable scope, so the theme-aware
// PulsarCyan getter (which reads a CompositionLocal) can't be called inside
// them — resolve it once, above, in composable scope, and reuse the value.
private val PulsarCyanStatic: Color = Color(0xFF35D6FF)

@Composable
private fun CoachMarkTooltip(
    step: CoachMarkStep,
    targetRect: Rect,
    screenWidthPx: Float,
    screenHeightPx: Float,
    stepIndex: Int,
    stepCount: Int,
    guideAvatar: Painter?,
    nextLabel: String,
    skipLabel: String,
    showSkip: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val margin = with(density) { 16.dp.toPx() }
    val tooltipWidthDp = 280.dp
    val tooltipWidthPx = with(density) { tooltipWidthDp.toPx() }

    // Prefer placing the tooltip below the target; flip above it if there
    // isn't roughly a card's-height of room left underneath.
    val roomBelow = screenHeightPx - targetRect.bottom
    val placeBelow = roomBelow > with(density) { 220.dp.toPx() }
    val tooltipY = if (placeBelow) targetRect.bottom + margin else null
    val tooltipYFromBottom = if (!placeBelow) screenHeightPx - targetRect.top + margin else null

    // Center horizontally on the target, clamped so the card stays on-screen.
    val idealX = targetRect.left + targetRect.width / 2f - tooltipWidthPx / 2f
    val clampedX = idealX.coerceIn(margin, (screenWidthPx - tooltipWidthPx - margin).coerceAtLeast(margin))

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset {
                    val y = tooltipY ?: (screenHeightPx - (tooltipYFromBottom ?: 0f))
                    IntOffset(clampedX.roundToInt(), y.roundToInt())
                }
                .width(tooltipWidthDp)
                .clip(RoundedCornerShape(20.dp))
                .background(NebulaSurface)
                .border(1.dp, PulsarCyanStatic.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            androidx.compose.foundation.layout.Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (guideAvatar != null) {
                        androidx.compose.foundation.Image(
                            painter = guideAvatar,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = step.title,
                        color = StarDust,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = step.description,
                    color = CometTail,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(stepCount) { index ->
                            Box(
                                modifier = Modifier
                                    .size(if (index == stepIndex) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == stepIndex) PulsarCyanStatic
                                        else CometTail.copy(alpha = 0.35f),
                                    ),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showSkip) {
                            Text(
                                text = skipLabel,
                                color = CometTail,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .then(clickableNoRipple(onSkip)),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(PulsarCyanStatic)
                                .then(clickableNoRipple(onNext))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = nextLabel,
                                color = Color(0xFF02060F),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun clickableNoRipple(onClick: () -> Unit): Modifier = androidx.compose.foundation.clickable(
    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)
