package com.systemsgo.hex.ui.components

// CONNECTION-STATUS-INDICATOR FEATURE
//
// The visual half of the live connection-status badge shown on every
// connection card. The data half (CardConnectionStatus / CardStatusInfo /
// resolveCardStatus) already exists in session/CardConnectionStatus.kt — this
// file is purely Compose: no StateFlow, no ViewModel, no session-engine
// knowledge. See STATUS_INDICATOR_CONTINUE_PROMPT.md for the full feature
// spec this implements.

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.session.CardConnectionStatus
import com.systemsgo.hex.session.CardStatusInfo
import com.systemsgo.hex.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale

// ── Colors ───────────────────────────────────────────────────────────────────
// CONNECTION-STATUS-INDICATOR FEATURE: Suspended has no dedicated token in
// ui/theme/Color.kt's SpaceColors (only success/warning/danger exist there,
// and warning is already claimed by Reconnecting). Adding a field there would
// break every one of the 6 SpaceColors instances (space/nebula/aurora ×
// light/dark) that construct it positionally — out of scope for this feature.
// Two fixed light/dark values defined locally instead, same idea as the
// existing Dark/LightSuccess-etc. constants at the top of Color.kt.
private val SuspendedYellowDark  = Color(0xFFFFD600L)
private val SuspendedYellowLight = Color(0xFF9A7B00L)

/** Resolves the tint color for a given status, theme-aware via LocalSpaceColors. */
@Composable
private fun statusColor(status: CardConnectionStatus): Color {
    val isDark = LocalSpaceColors.current.isDark
    return when (status) {
        CardConnectionStatus.OFFLINE      -> DisconnectedGray
        // Distinct from Reconnecting's orange/amber per the spec's explicit
        // "Reconnecting = orange" — Connecting reuses the accent cyan instead
        // of also being amber, so the two in-progress states read differently
        // at a glance.
        CardConnectionStatus.CONNECTING   -> PulsarCyan
        CardConnectionStatus.CONNECTED    -> PlasmaGreen
        CardConnectionStatus.RECONNECTING -> SolarFlare
        CardConnectionStatus.AUTH_REQUIRED -> QuantumBlue
        CardConnectionStatus.FAILED       -> ErrorRed
        CardConnectionStatus.SUSPENDED    -> if (isDark) SuspendedYellowDark else SuspendedYellowLight
    }
}

/** Resolves the glyph for a given status. */
private fun statusIcon(status: CardConnectionStatus) = when (status) {
    CardConnectionStatus.OFFLINE       -> Icons.Filled.Circle
    CardConnectionStatus.CONNECTING    -> Icons.Filled.Sync
    CardConnectionStatus.CONNECTED     -> Icons.Filled.CheckCircle
    CardConnectionStatus.RECONNECTING  -> Icons.Filled.SyncProblem
    CardConnectionStatus.AUTH_REQUIRED -> Icons.Filled.Lock
    CardConnectionStatus.FAILED        -> Icons.Filled.ErrorOutline
    CardConnectionStatus.SUSPENDED     -> Icons.Filled.PauseCircle
}

/** Resolves the localized tooltip/label text for a given status. */
@Composable
private fun statusLabel(status: CardConnectionStatus): String = when (status) {
    CardConnectionStatus.OFFLINE       -> stringResource(R.string.conn_status_offline)
    CardConnectionStatus.CONNECTING    -> stringResource(R.string.session_state_connecting)
    CardConnectionStatus.CONNECTED     -> stringResource(R.string.session_state_connected)
    CardConnectionStatus.RECONNECTING  -> stringResource(R.string.session_state_reconnecting)
    CardConnectionStatus.AUTH_REQUIRED -> stringResource(R.string.session_state_auth_required)
    CardConnectionStatus.FAILED        -> stringResource(R.string.conn_status_failed)
    CardConnectionStatus.SUSPENDED     -> stringResource(R.string.session_state_suspended)
}

/**
 * CONNECTION-STATUS-INDICATOR FEATURE: the small live status badge shown on
 * every connection card (~16-18dp visual size, 44dp-ish touch target via the
 * long-press affordance below).
 *
 * - Rotation animates Connecting/Reconnecting; a gentle alpha/scale pulse
 *   animates Connected. AnimatedContent cross-fades the whole badge whenever
 *   [info]'s status itself changes, so switching states never pops abruptly.
 * - A Material 3 tooltip shows the status label, plus [CardStatusInfo.reasonText]
 *   for Failed/Auth-Required when it's non-blank.
 * - [onLongPress] is the hook for opening [SessionDetailsDialog] — deliberately
 *   NOT handled with a manual pointerInput/detectDragGesturesAfterLongPress
 *   here: the parent card (RdpProfileCard/ReorderableProfileCard) already uses
 *   detectDragGesturesAfterLongPress on its own outer Box for reorder-drag vs.
 *   open-menu disambiguation (see HomeScreen.kt). combinedClickable on this
 *   badge consumes the initial pointer-down within its own bounds — the exact
 *   same mechanism that already lets the adjacent Quick-Pin/Favorite
 *   IconButtons coexist safely with that same parent gesture detector — so a
 *   long-press landing on the badge itself opens details instead of leaking
 *   through to the card's drag/menu handling underneath.
 * - RTL/LTR: no Start/End-specific manual math anywhere below; every offset
 *   this composable itself applies is symmetric (centered), and callers place
 *   it using Alignment.TopStart/TopEnd, which Compose already mirrors per
 *   LocalLayoutDirection — same as the existing Pin badge in Components.kt.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionStatusBadge(
    info: CardStatusInfo,
    modifier: Modifier = Modifier,
    // Used only to compose the accessibility description below
    // ("<connection name> — <status>"); blank shows the status alone.
    connectionName: String = "",
    onLongPress: () -> Unit = {},
) {
    val label = statusLabel(info.status)
    val a11yTemplate = stringResource(R.string.conn_status_a11y_template)
    val description = if (connectionName.isNotBlank()) {
        String.format(Locale.getDefault(), a11yTemplate, connectionName, label)
    } else {
        label
    }
    val tooltipText = if (
        (info.status == CardConnectionStatus.FAILED || info.status == CardConnectionStatus.AUTH_REQUIRED) &&
        info.reasonText.isNotBlank()
    ) {
        "$label — ${info.reasonText}"
    } else {
        label
    }

    val infiniteTransition = rememberInfiniteTransition(label = "session_status_badge")
    // Continuous rotation for Connecting/Reconnecting only.
    val rotation by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    // Gentle breathing pulse for Connected only.
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.55f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val tooltipState = rememberTooltipState()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip          = { PlainTooltip { Text(tooltipText) } },
        state            = tooltipState,
    ) {
        Box(
            modifier = modifier
                .size(17.dp)
                .semantics {
                    contentDescription = description
                    stateDescription   = label
                }
                .combinedClickable(
                    onClick     = {},
                    onLongClick = onLongPress,
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = info.status,
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(initialScale = 0.7f, animationSpec = tween(200))) togetherWith
                        (fadeOut(tween(150)) + scaleOut(targetScale = 0.7f, animationSpec = tween(150)))
                },
                label = "session_status_transition"
            ) { status ->
                val statusIconVector = statusIcon(status)
                val statusColorForFrame = statusColor(status)
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .background(statusColorForFrame.copy(alpha = 0.22f), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = statusIconVector,
                        contentDescription = null,
                        tint = statusColorForFrame.copy(
                            alpha = if (status == CardConnectionStatus.CONNECTED) pulseAlpha else 1f
                        ),
                        modifier = Modifier
                            .size(12.dp)
                            .graphicsLayer {
                                rotationZ = if (
                                    status == CardConnectionStatus.CONNECTING ||
                                    status == CardConnectionStatus.RECONNECTING
                                ) rotation else 0f
                                if (status == CardConnectionStatus.CONNECTED) {
                                    val s = 0.9f + 0.1f * pulseAlpha
                                    scaleX = s
                                    scaleY = s
                                }
                            }
                    )
                }
            }
        }
    }
}

/**
 * Small "00:15:42"-style live session-duration text — a 1-second local
 * ticker via [produceState], intentionally scoped to the caller's own
 * composition (only rendered while [CardStatusInfo.status] is CONNECTED —
 * see call sites in Components.kt) rather than running unconditionally.
 * [SessionTabManager.MAX_TABS] caps concurrent CONNECTED tabs at 5, so at
 * most 5 of these tickers can ever be running at once regardless of how many
 * (possibly hundreds of) cards are on screen.
 */
@Composable
fun SessionUptimeText(
    connectedSinceMillis: Long,
    modifier: Modifier = Modifier,
) {
    val elapsedSeconds by produceState(initialValue = 0L, connectedSinceMillis) {
        while (true) {
            value = ((System.currentTimeMillis() - connectedSinceMillis) / 1000L).coerceAtLeast(0L)
            delay(1000L)
        }
    }
    Text(
        text     = formatUptime(elapsedSeconds),
        style    = MaterialTheme.typography.labelSmall,
        color    = PlasmaGreen,
        modifier = modifier
    )
}

private fun formatUptime(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}

/**
 * CONNECTION-STATUS-INDICATOR FEATURE: long-press detail popup. Shows only
 * fields that are genuinely available from the live engine today — see the
 * doc comment in STATUS_INDICATOR_CONTINUE_PROMPT.md's "part 2" section for
 * why latency / negotiated encryption / transport / server version are
 * deliberately absent rather than filled with placeholder values: none of
 * that is instrumented anywhere in the current session pipeline for any
 * protocol client, and inventing numbers here would be actively misleading.
 */
@Composable
fun SessionDetailsDialog(
    info: CardStatusInfo,
    profile: RdpProfile,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_details_title)) },
        text = {
            Column {
                DetailRow(stringResource(R.string.session_details_protocol), profile.protocolType.name)
                DetailRow(
                    stringResource(R.string.session_details_host),
                    "${profile.host}:${profile.port}"
                )
                DetailRow(
                    stringResource(R.string.session_details_current_status),
                    statusLabel(info.status)
                )
                if (info.status == CardConnectionStatus.CONNECTED && info.connectedSinceMillis != null) {
                    val elapsed = ((System.currentTimeMillis() - info.connectedSinceMillis) / 1000L).coerceAtLeast(0L)
                    DetailRow(
                        stringResource(R.string.session_details_duration),
                        formatUptime(elapsed)
                    )
                }
                if (info.reconnectCount > 0) {
                    DetailRow(
                        stringResource(R.string.session_details_reconnect_count),
                        info.reconnectCount.toString()
                    )
                }
                if (info.reasonText.isNotBlank() &&
                    (info.status == CardConnectionStatus.FAILED || info.status == CardConnectionStatus.AUTH_REQUIRED)
                ) {
                    DetailRow(
                        stringResource(R.string.session_details_failure_reason),
                        info.reasonText
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = CometTail)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = StarDust)
    }
}
