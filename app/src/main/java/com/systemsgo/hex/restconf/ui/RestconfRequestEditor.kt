package com.systemsgo.hex.restconf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RestconfAuthType
import com.systemsgo.hex.data.model.RestconfDataFormat
import com.systemsgo.hex.restconf.protocol.RestconfFormatting
import com.systemsgo.hex.ui.theme.NovaPink
import com.systemsgo.hex.ui.theme.PlasmaGreen

/** One editable name/value row shared by the Params and Headers editors. */
data class RestconfKeyValueRow(val key: String, val value: String, val enabled: Boolean = true)

/**
 * RESTCONF FEATURE (Part 2/4): generic key/value list editor — backs both
 * the Query Parameters tab and the Headers tab (same shape: name, value, an
 * enabled toggle so a row can be disabled without deleting it, matching how
 * Postman-style tools let you temporarily drop a param).
 */
@Composable
fun RestconfKeyValueEditor(
    rows: List<RestconfKeyValueRow>,
    onRowsChange: (List<RestconfKeyValueRow>) -> Unit,
    keyLabel: String = stringResource(R.string.restconf_key),
    valueLabel: String = stringResource(R.string.restconf_header_value),
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        rows.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Checkbox(checked = row.enabled, onCheckedChange = { checked ->
                    onRowsChange(rows.toMutableList().also { it[index] = row.copy(enabled = checked) })
                })
                OutlinedTextField(
                    value = row.key,
                    onValueChange = { v -> onRowsChange(rows.toMutableList().also { it[index] = row.copy(key = v) }) },
                    label = { Text(keyLabel) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                )
                OutlinedTextField(
                    value = row.value,
                    onValueChange = { v -> onRowsChange(rows.toMutableList().also { it[index] = row.copy(value = v) }) },
                    label = { Text(valueLabel) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                )
                IconButton(onClick = { onRowsChange(rows.toMutableList().also { it.removeAt(index) }) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.restconf_remove_row))
                }
            }
        }
        TextButton(onClick = { onRowsChange(rows + RestconfKeyValueRow("", "")) }) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.width(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.restconf_add_row))
        }
    }
}

/**
 * RESTCONF FEATURE (Part 2/4): auth-method picker plus exactly the fields
 * each [RestconfAuthType] needs — mirrors [com.systemsgo.hex.restconf.protocol.RestconfAuth]'s
 * per-type wiring 1:1, so every field shown here is a field that type
 * actually consumes (no dead inputs).
 */
@Composable
fun RestconfAuthEditor(
    authType: RestconfAuthType,
    onAuthTypeChange: (RestconfAuthType) -> Unit,
    username: String, onUsernameChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    bearerToken: String, onBearerTokenChange: (String) -> Unit,
    jwtToken: String, onJwtTokenChange: (String) -> Unit,
    apiKeyHeaderName: String, onApiKeyHeaderNameChange: (String) -> Unit,
    apiKeyValue: String, onApiKeyValueChange: (String) -> Unit,
    oauth2TokenUrl: String, onOAuth2TokenUrlChange: (String) -> Unit,
    oauth2ClientId: String, onOAuth2ClientIdChange: (String) -> Unit,
    oauth2ClientSecret: String, onOAuth2ClientSecretChange: (String) -> Unit,
    oauth2Scope: String, onOAuth2ScopeChange: (String) -> Unit,
    clientCertAlias: String,
    onPickClientCert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Box {
            OutlinedButton(onClick = { menuExpanded = true }) { Text(authType.name.replace('_', ' ')) }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                RestconfAuthType.entries.forEach { type ->
                    DropdownMenuItem(text = { Text(type.name.replace('_', ' ')) }, onClick = { onAuthTypeChange(type); menuExpanded = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        when (authType) {
            RestconfAuthType.BASIC, RestconfAuthType.DIGEST -> {
                OutlinedTextField(username, onUsernameChange, label = { Text(stringResource(R.string.restconf_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(password, onPasswordChange, label = { Text(stringResource(R.string.restconf_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            RestconfAuthType.BEARER_TOKEN -> OutlinedTextField(bearerToken, onBearerTokenChange, label = { Text(stringResource(R.string.restconf_bearer_token)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            RestconfAuthType.JWT -> OutlinedTextField(jwtToken, onJwtTokenChange, label = { Text(stringResource(R.string.restconf_jwt)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            RestconfAuthType.API_KEY -> {
                OutlinedTextField(apiKeyHeaderName, onApiKeyHeaderNameChange, label = { Text(stringResource(R.string.restconf_header_name_field)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(apiKeyValue, onApiKeyValueChange, label = { Text(stringResource(R.string.restconf_key_value)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            RestconfAuthType.OAUTH2 -> {
                OutlinedTextField(oauth2TokenUrl, onOAuth2TokenUrlChange, label = { Text(stringResource(R.string.restconf_token_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(oauth2ClientId, onOAuth2ClientIdChange, label = { Text(stringResource(R.string.restconf_client_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(oauth2ClientSecret, onOAuth2ClientSecretChange, label = { Text(stringResource(R.string.restconf_client_secret)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(oauth2Scope, onOAuth2ScopeChange, label = { Text(stringResource(R.string.restconf_oauth2_scope)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            RestconfAuthType.CLIENT_CERTIFICATE, RestconfAuthType.MUTUAL_TLS -> {
                Text(clientCertAlias.ifBlank { stringResource(R.string.restconf_no_client_cert_selected) }, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onPickClientCert) { Text(stringResource(R.string.restconf_choose_from_keystore)) }
            }
            RestconfAuthType.CUSTOM_HEADER -> Text(
                stringResource(R.string.restconf_custom_header_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            RestconfAuthType.NONE -> Text(stringResource(R.string.restconf_no_authentication), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * RESTCONF FEATURE (Part 2/4): the body editor — syntax-highlighted via
 * [RestconfSyntaxHighlighter] through a [VisualTransformation] (so the
 * underlying [TextFieldValue] stays plain text; highlighting is purely a
 * rendering transform, never mutates what the user typed), plus a live
 * validity indicator and a one-tap "Format" action that pretty-prints via
 * [RestconfFormatting].
 */
@Composable
fun RestconfBodyEditor(
    text: String,
    onTextChange: (String) -> Unit,
    format: RestconfDataFormat,
    modifier: Modifier = Modifier,
) {
    val validation = remember(text, format) { RestconfFormatting.validate(text, format) }
    Column(modifier) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (text.isNotBlank()) {
                Text(
                    if (validation.isValid) stringResource(R.string.restconf_body_valid, format)
                    else validation.errorLine?.let { stringResource(R.string.restconf_body_invalid_at_line, format, it) }
                        ?: stringResource(R.string.restconf_body_invalid, format),
                    color = if (validation.isValid) PlasmaGreen else NovaPink,
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                Spacer(Modifier)
            }
            TextButton(
                onClick = { onTextChange(RestconfFormatting.prettyPrint(text, format)) },
                enabled = validation.isValid && text.isNotBlank(),
            ) {
                Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.restconf_format_body))
            }
        }
        val highlighted = RestconfSyntaxHighlighter.highlight(text, format)
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = LocalContentColor.current),
            cursorBrush = SolidColor(LocalContentColor.current),
            visualTransformation = { _ -> TransformedText(highlighted, OffsetMapping.Identity) },
            modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 4.dp),
        )
        if (!validation.isValid && text.isNotBlank()) {
            Text(
                validation.errorMessage ?: stringResource(R.string.restconf_body_invalid, format),
                color = NovaPink,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
