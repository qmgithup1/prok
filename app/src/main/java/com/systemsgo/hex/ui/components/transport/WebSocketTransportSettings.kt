package com.systemsgo.hex.ui.components.transport

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.rdp.transport.RdpTransportMode
import com.systemsgo.hex.rdp.transport.RdpWebSocketConfig

/**
 * Self-contained settings section for the WebSocket transport
 * (requirement #4's UI + the requirement #1/#3 transport picker).
 *
 * Wired into `Components.kt`'s profile editor's WebSocket transport
 * SettingsCard. This composable only needs hoisted state, so it slots
 * into whatever pattern that screen already uses for other per-profile
 * setting sections (each field change calls [onModeChange]/[onConfigChange]
 * with the *whole* updated value, same shape as a typical
 * `TextField(value, onValueChange)`).
 *
 * All user-facing copy is sourced from `strings.xml`/`values-ar/strings.xml`
 * (en + ar) — only the [RdpTransportMode] segmented-button labels are left
 * as raw enum names, since TCP/WS/WSS are technical protocol identifiers
 * rather than translatable prose.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WebSocketTransportSettings(
    mode: RdpTransportMode,
    config: RdpWebSocketConfig,
    onModeChange: (RdpTransportMode) -> Unit,
    onConfigChange: (RdpWebSocketConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    // SECURITY FIX: contains a password field — see security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ws_transport_label), style = MaterialTheme.typography.titleMedium)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            RdpTransportMode.entries.forEachIndexed { index, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { onModeChange(m) },
                    shape = SegmentedButtonDefaults.itemShape(index, RdpTransportMode.entries.size),
                    // Protocol identifiers (TCP/WS/WSS) are technical acronyms,
                    // not natural-language UI copy, so they're intentionally
                    // not routed through strings.xml/translated.
                    label = { Text(m.name) },
                )
            }
        }
        Text(
            stringResource(R.string.ws_transport_desc),
            style = MaterialTheme.typography.bodySmall,
        )

        if (!mode.isWebSocket) return

        HorizontalDivider()

        OutlinedTextField(
            value = config.url,
            onValueChange = { onConfigChange(config.copy(url = it)) },
            label = { Text(stringResource(R.string.ws_url_label)) },
            placeholder = { Text(stringResource(R.string.ws_url_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = config.host,
                onValueChange = { onConfigChange(config.copy(host = it)) },
                label = { Text(stringResource(R.string.ws_host_label)) },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = if (config.port == 0) "" else config.port.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { onConfigChange(config.copy(port = it)) } },
                label = { Text(stringResource(R.string.port)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = config.path,
            onValueChange = { onConfigChange(config.copy(path = it)) },
            label = { Text(stringResource(R.string.ws_path_label)) },
            placeholder = { Text(stringResource(R.string.ws_path_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()
        Text(stringResource(R.string.ws_auth_headers_title), style = MaterialTheme.typography.titleSmall)

        OutlinedTextField(
            value = config.bearerToken,
            onValueChange = { onConfigChange(config.copy(bearerToken = it)) },
            label = { Text(stringResource(R.string.ws_bearer_token_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.authorizationHeader,
            onValueChange = { onConfigChange(config.copy(authorizationHeader = it)) },
            label = { Text(stringResource(R.string.ws_authorization_header_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.origin,
            onValueChange = { onConfigChange(config.copy(origin = it)) },
            label = { Text(stringResource(R.string.ws_origin_header_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.subprotocol,
            onValueChange = { onConfigChange(config.copy(subprotocol = it)) },
            label = { Text(stringResource(R.string.ws_subprotocol_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.cookie,
            onValueChange = { onConfigChange(config.copy(cookie = it)) },
            label = { Text(stringResource(R.string.ws_cookie_header_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        CustomHeadersEditor(
            headers = config.headers,
            onHeadersChange = { onConfigChange(config.copy(headers = it)) },
        )

        HorizontalDivider()
        Text(stringResource(R.string.ws_tls_title), style = MaterialTheme.typography.titleSmall)

        SwitchRow(
            label = stringResource(R.string.ws_validate_certificate_label),
            checked = config.tls.validateCertificate,
            onCheckedChange = { onConfigChange(config.copy(tls = config.tls.copy(validateCertificate = it))) },
        )
        SwitchRow(
            label = stringResource(R.string.ws_allow_self_signed_label),
            checked = config.tls.allowSelfSigned,
            onCheckedChange = { onConfigChange(config.copy(tls = config.tls.copy(allowSelfSigned = it))) },
        )
        if (config.tls.allowSelfSigned) {
            Text(
                stringResource(R.string.ws_self_signed_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider()
        SwitchRow(
            label = stringResource(R.string.ws_auto_reconnect_label),
            checked = config.autoReconnect,
            onCheckedChange = { onConfigChange(config.copy(autoReconnect = it)) },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Minimal add/remove list editor for [RdpWebSocketConfig.headers]. */
@Composable
private fun CustomHeadersEditor(
    headers: Map<String, String>,
    onHeadersChange: (Map<String, String>) -> Unit,
) {
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.ws_custom_headers_label), style = MaterialTheme.typography.bodyMedium)
        headers.forEach { (k, v) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("$k: $v", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onHeadersChange(headers - k) }) { Text(stringResource(R.string.ws_header_remove)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = newKey, onValueChange = { newKey = it },
                label = { Text(stringResource(R.string.ws_header_name_label)) }, singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = newValue, onValueChange = { newValue = it },
                label = { Text(stringResource(R.string.ws_header_value_label)) }, singleLine = true, modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    if (newKey.isNotBlank()) {
                        onHeadersChange(headers + (newKey to newValue))
                        newKey = ""; newValue = ""
                    }
                },
            ) { Text(stringResource(R.string.add_short)) }
        }
    }
}
