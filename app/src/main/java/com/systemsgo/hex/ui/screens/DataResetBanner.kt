package com.systemsgo.hex.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.security.DataResetManager
import com.systemsgo.hex.ui.components.StarfieldBackground
import com.systemsgo.hex.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * DELAYED-RESET FEATURE: shared "how much time is left" ticker used by both
 * [DataResetScreen] and [DataResetBanner] below, so MainActivity only needs
 * to poll [DataResetManager.remainingMillis] once and decide which of the
 * two to show, instead of each composable polling independently.
 *
 * Deliberately uses DataResetManager.remainingMillis() (anchored to
 * SystemClock.elapsedRealtime, boot-relative) rather than diffing against
 * System.currentTimeMillis() — see DataResetManager for why.
 */
@Composable
fun rememberDataResetRemainingMillis(): State<Long?> {
    val context = LocalContext.current
    return produceState<Long?>(initialValue = DataResetManager.remainingMillis(context)) {
        while (true) {
            value = DataResetManager.remainingMillis(context)
            delay(1000)
        }
    }
}

/**
 * FULL-SCREEN REDESIGN (REQ-4): a pending "Reset Application Data" wipe used
 * to be surfaced only via a small top banner (see [DataResetBanner] below)
 * layered over whatever else was on screen — easy to miss, and cramped for
 * something this consequential (a full, irreversible data wipe). This is
 * that same information — countdown, cancel — as a dedicated, full-screen
 * destination instead, shown by MainActivity in place of [AppLockScreen]
 * whenever a reset is pending and the app is locked, i.e. exactly the moment
 * a user who forgot their PIN is most likely to land here.
 *
 * Two ways forward, both surfaced directly on this screen rather than
 * requiring the user to first fight through the ordinary lock screen:
 *  - "Cancel Reset" — re-authenticates via [SecurityConfirmDialog], which
 *    reuses the exact same PIN/biometric verification path as the ordinary
 *    lock screen (see that file's own doc comment). Succeeding both cancels
 *    the scheduled wipe and unlocks the app — there is no separate,
 *    lower-friction way to stop the countdown.
 *  - "Continue as Guest" — identical to [AppLockScreen]'s own guest option:
 *    enters the isolated, empty Guest profile immediately. The countdown
 *    keeps running in the background (see [DataResetBanner] once unlocked);
 *    picking Guest does not cancel the pending erase, only defers seeing
 *    this screen again until the user comes back to cancel or it fires.
 */
@Composable
fun DataResetScreen(
    remainingMs: Long,
    pinLockEnabled: Boolean,
    biometricLockEnabled: Boolean,
    encryptedPin: String,
    onCancelled: () -> Unit,
    onGuestMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showAuthConfirm by remember { mutableStateOf(false) }

    // Progress bar fraction of the 24h window already elapsed — a quick
    // at-a-glance sense of urgency beyond just reading the digits.
    val elapsedFraction = (1f - remainingMs.toFloat() / DataResetManager.DELAY_MILLIS.toFloat())
        .coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = elapsedFraction,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "dataResetProgress"
    )

    StarfieldBackground(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = SolarFlare.copy(alpha = 0.15f),
                    border = BorderStroke(1.5.dp, SolarFlare.copy(alpha = 0.6f)),
                    modifier = Modifier.size(84.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.DeleteForever,
                            contentDescription = null,
                            tint = SolarFlare,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    stringResource(R.string.data_reset_banner_title),
                    color = StarDust,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    stringResource(R.string.data_reset_screen_desc),
                    color = CometTail,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(28.dp))

                // ── Countdown card ─────────────────────────────────────────
                Surface(
                    color = NebulaSurface,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, SolarFlare.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.data_reset_screen_time_remaining),
                            color = CometTail,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            formatRemaining(remainingMs),
                            color = SolarFlare,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.displaySmall
                        )
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = SolarFlare,
                            trackColor = HorizonGray.copy(alpha = 0.4f),
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))

                // ── Option 1: cancel (requires PIN/biometric) ───────────────
                Button(
                    onClick = { showAuthConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = SolarFlare, contentColor = Color.Black),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(stringResource(R.string.data_reset_banner_cancel), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.data_reset_screen_cancel_desc),
                    color = CometTail,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = HorizonGray.copy(alpha = 0.4f))
                    Text(
                        stringResource(R.string.data_reset_screen_or_divider),
                        color = CometTail,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = HorizonGray.copy(alpha = 0.4f))
                }
                Spacer(Modifier.height(22.dp))

                // ── Option 2: guest mode (no PIN needed) ────────────────────
                OutlinedButton(
                    onClick = onGuestMode,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PulsarCyan),
                    border = BorderStroke(1.5.dp, PulsarCyan.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.data_reset_screen_guest_button), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.data_reset_screen_guest_desc),
                    color = CometTail,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (showAuthConfirm) {
        SecurityConfirmDialog(
            pinLockEnabled = pinLockEnabled,
            biometricLockEnabled = biometricLockEnabled,
            encryptedPin = encryptedPin,
            onConfirmed = {
                showAuthConfirm = false
                DataResetManager.cancelReset(context)
                onCancelled()
            },
            onDismiss = { showAuthConfirm = false }
        )
    }
}

/**
 * DELAYED-RESET FEATURE: compact, non-blocking reminder — kept (rather than
 * replaced outright by [DataResetScreen]) for the case where a reset is
 * still pending but the app is already unlocked (most commonly: the user
 * picked "Continue as Guest" from [DataResetScreen] and is now actively
 * using the app). Forcing the full-screen takeover above on top of an
 * in-progress session would be far more disruptive than useful here — this
 * keeps the countdown visible and cancellable without blocking anything.
 */
@Composable
fun DataResetBanner(
    pinLockEnabled: Boolean,
    biometricLockEnabled: Boolean,
    encryptedPin: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val remainingMs by rememberDataResetRemainingMillis()
    val remaining = remainingMs ?: return

    var showAuthConfirm by remember { mutableStateOf(false) }

    Column(modifier = modifier.padding(12.dp)) {
        Surface(
            color = NebulaSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SolarFlare.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = SolarFlare)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.data_reset_banner_title),
                        color = StarDust,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.data_reset_banner_countdown, formatRemaining(remaining)),
                        color = CometTail,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { showAuthConfirm = true }) {
                    Text(stringResource(R.string.data_reset_banner_cancel), color = PulsarCyan, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showAuthConfirm) {
        SecurityConfirmDialog(
            pinLockEnabled = pinLockEnabled,
            biometricLockEnabled = biometricLockEnabled,
            encryptedPin = encryptedPin,
            onConfirmed = {
                showAuthConfirm = false
                DataResetManager.cancelReset(context)
            },
            onDismiss = { showAuthConfirm = false }
        )
    }
}

private fun formatRemaining(remainingMs: Long): String {
    val totalSeconds = remainingMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}
