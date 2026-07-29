package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.systemsgo.hex.R
import com.systemsgo.hex.usb.RedirectionState
import com.systemsgo.hex.usb.UsbConnectionState
import com.systemsgo.hex.usb.UsbRedirectedDevice

/**
 * USB-REDIRECT FEATURE (Part 1/3): Material 3 settings surface for USB
 * redirection — global toggles plus a live, per-device list backed by
 * [UsbRedirectionSettingsViewModel]/[com.systemsgo.hex.usb.UsbRedirectionManager.deviceListFlow].
 *
 * Deliberately built from plain M3 primitives (`Switch`/`Card`/`Text`)
 * rather than this file's siblings' private `SpaceSwitch` (declared
 * `private` in `Components.kt`, scoped to that file) — safe to restyle to
 * match the rest of Settings with a follow-up pass once that component is
 * exposed, without touching any state/logic here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbRedirectionSettingsScreen(
    onBack: () -> Unit,
    viewModel: UsbRedirectionSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val devices by viewModel.devices.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usb_redirection_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.usb_redirection_desc), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }

            item {
                ToggleRow(
                    label = stringResource(R.string.usb_redirection_enable),
                    checked = settings.enabled,
                    onCheckedChange = viewModel::setEnabled,
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.usb_redirection_auto_redirect_new),
                    checked = settings.autoRedirectNewDevices,
                    onCheckedChange = viewModel::setAutoRedirectNewDevices,
                    enabled = settings.enabled,
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.usb_redirection_ask_before),
                    checked = settings.askBeforeRedirecting,
                    onCheckedChange = viewModel::setAskBeforeRedirecting,
                    enabled = settings.enabled,
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.usb_redirection_reconnect_automatically),
                    checked = settings.reconnectAutomatically,
                    onCheckedChange = viewModel::setReconnectAutomatically,
                    enabled = settings.enabled,
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.usb_redirection_debug_logging),
                    checked = settings.debugLogging,
                    onCheckedChange = viewModel::setDebugLogging,
                    icon = Icons.Outlined.BugReport,
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.usb_redirection_devices_header),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
            }

            if (devices.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.usb_redirection_no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                items(devices, key = { it.info.deviceName }) { device ->
                    UsbDeviceCard(
                        device = device,
                        onRedirectClick = { viewModel.onRedirectClicked(device) },
                        onStopClick = { viewModel.onStopClicked(device) },
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun UsbDeviceCard(
    device: UsbRedirectedDevice,
    onRedirectClick: () -> Unit,
    onStopClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Usb, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        device.info.productName ?: device.info.idString,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    device.info.manufacturerName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            DetailRow(stringResource(R.string.usb_redirection_vendor_id), "0x%04X".format(device.info.vendorId))
            DetailRow(stringResource(R.string.usb_redirection_product_id), "0x%04X".format(device.info.productId))
            DetailRow(stringResource(R.string.usb_redirection_class), device.info.classCategory.name)
            device.info.serialNumber?.let { DetailRow(stringResource(R.string.usb_redirection_serial), it) }
            DetailRow(stringResource(R.string.usb_redirection_connection_state), connectionStateLabel(device.connectionState))
            DetailRow(stringResource(R.string.usb_redirection_redirection_state), redirectionStateLabel(device.redirectionState))

            Spacer(Modifier.height(12.dp))
            if (device.redirectionState == RedirectionState.REDIRECTED || device.redirectionState == RedirectionState.PENDING) {
                OutlinedButton(onClick = onStopClick) { Text(stringResource(R.string.usb_redirection_stop_button)) }
            } else {
                OutlinedButton(onClick = onRedirectClick) { Text(stringResource(R.string.usb_redirection_redirect_button)) }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun connectionStateLabel(state: UsbConnectionState): String = stringResource(
    when (state) {
        UsbConnectionState.DISCONNECTED -> R.string.usb_redirection_conn_disconnected
        UsbConnectionState.PERMISSION_REQUESTED -> R.string.usb_redirection_conn_permission_requested
        UsbConnectionState.PERMISSION_DENIED -> R.string.usb_redirection_conn_permission_denied
        UsbConnectionState.CONNECTED -> R.string.usb_redirection_conn_connected
        UsbConnectionState.ERROR -> R.string.usb_redirection_conn_error
    }
)

@Composable
private fun redirectionStateLabel(state: RedirectionState): String = stringResource(
    when (state) {
        RedirectionState.NOT_REDIRECTED -> R.string.usb_redirection_state_not_redirected
        RedirectionState.PENDING -> R.string.usb_redirection_state_pending
        RedirectionState.REDIRECTED -> R.string.usb_redirection_state_redirected
        RedirectionState.FAILED -> R.string.usb_redirection_state_failed
        RedirectionState.DISCONNECTED_PENDING_RESTORE -> R.string.usb_redirection_state_disconnected_pending_restore
    }
)
