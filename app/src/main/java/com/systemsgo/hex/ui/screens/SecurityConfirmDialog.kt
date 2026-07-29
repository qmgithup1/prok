package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.systemsgo.hex.R
import com.systemsgo.hex.ui.theme.*

/**
 * SECURITY FIX (critical): Previously, the "Biometric Lock" and "PIN Lock"
 * switches in SettingsScreen called viewModel.updateBiometricLock(false) /
 * viewModel.updatePinLock(false) directly from onCheckedChange — with
 * NO re-authentication of any kind.
 *
 * Impact: anyone holding an already-unlocked phone (grabbed off a table,
 * borrowed "for a second", picked up while the owner stepped away) could
 * open Settings and switch both protections off in two taps, permanently
 * removing the app lock, without ever having to know the PIN or provide a
 * fingerprint. This completely defeated the purpose of the lock feature —
 * it protected against being unlocked, but not against being *disabled*.
 *
 * Fix: turning either protection OFF now routes through this dialog first.
 * It reuses the exact same verification path as the main lock screen:
 *   - If a PIN is configured, the user must re-enter it via [PinEntryPad] —
 *     the same PBKDF2 (PinHasher) verification and exponential-backoff
 *     lockout (5 free attempts, then 30s / 2m / 10m / 30m tiers, persisted
 *     across process kills) that guards app unlock, so this can't be used
 *     as a lower-friction side door to brute-force the PIN.
 *   - If only biometric is configured (no PIN set), a biometric prompt is
 *     shown instead, since that's the only factor available to confirm.
 *
 * The setting is only ever applied by the caller's onConfirmed callback —
 * never optimistically — so a dismissed or failed dialog leaves the
 * existing protection fully intact.
 *
 * GENERIC SENSITIVE-ACTION GATE: this dialog is also reused, unmodified, as
 * the single re-authentication gate in front of every other sensitive
 * action in the app — exporting/importing connection backups, clearing
 * connection history, cancelling a pending data reset, and switching back
 * from Guest to the Primary profile. Every call site can invoke it
 * unconditionally (without first checking whether App Lock is even
 * configured) because of the check below: if the user has neither a PIN
 * nor biometric App Lock enabled, there is nothing to challenge them with —
 * showing a PIN pad in that state would either dead-end them (no PIN
 * exists to enter) or feel like meaningless friction. In that case this
 * composable renders nothing and immediately invokes [onConfirmed] via a
 * side effect, so callers get a uniform "gate, then proceed" API regardless
 * of whether App Lock happens to be on.
 */
@Composable
fun SecurityConfirmDialog(
    pinLockEnabled: Boolean,
    biometricLockEnabled: Boolean,
    encryptedPin: String,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // App Lock isn't configured at all — nothing to authenticate against.
    // Proceed immediately instead of showing a dialog with no usable factor.
    if (!pinLockEnabled && !biometricLockEnabled) {
        LaunchedEffect(Unit) { onConfirmed() }
        return
    }

    // No PIN to check against — biometric is the only factor we can use.
    val biometricOnly = biometricLockEnabled && (!pinLockEnabled || encryptedPin.isBlank())

    if (biometricOnly) {
        LaunchedEffect(Unit) {
            launchBiometric(context) { onConfirmed() }
        }
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.large, color = NebulaSurface) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(R.string.security_confirm_title),
                        color = StarDust,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.security_confirm_biometric_desc),
                        color = CometTail,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = { launchBiometric(context) { onConfirmed() } }) {
                        Text(stringResource(R.string.use_biometrics), color = PulsarCyan)
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel), color = CometTail)
                    }
                }
            }
        }
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = NebulaSurface,
            modifier = Modifier.padding(horizontal = 28.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    stringResource(R.string.security_confirm_title),
                    color = StarDust,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.security_confirm_pin_desc),
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )

                // Same PBKDF2-verified, lockout-protected pad as the app lock screen.
                PinEntryPad(
                    encryptedPin = encryptedPin,
                    onSuccess = onConfirmed
                )

                if (biometricLockEnabled) {
                    TextButton(onClick = { launchBiometric(context) { onConfirmed() } }) {
                        Text(stringResource(R.string.use_biometrics), color = PulsarCyan)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            }
        }
    }
}
