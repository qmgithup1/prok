package com.systemsgo.hex.restconf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.model.RestconfAuthType
import com.systemsgo.hex.data.model.RestconfDataFormat
import com.systemsgo.hex.restconf.protocol.RestconfConnectionState
import com.systemsgo.hex.ui.theme.NovaPink
import com.systemsgo.hex.ui.theme.PlasmaGreen
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.SolarFlare

/**
 * RESTCONF FEATURE (Part 4/4): the dedicated Configuration Screen the
 * Request Builder's Auth tab doc comment points to — everything editable
 * there is a *per-request override* that never touches the saved
 * [RdpProfile]; everything here is the actual connection-level
 * configuration, and saving it here persists to the profile and triggers a
 * real reconnect (see `RestconfExplorerViewModel.saveConfiguration`).
 *
 * Laid out as a set of collapsible, categorized sections (Connection /
 * Authentication / TLS & Security / Custom Headers / Performance) rather
 * than one long flat form — the RESTCONF profile surface is large (17
 * dedicated columns on [RdpProfile] alone, see its "RESTCONF FEATURE (Part
 * 1/4)" block), and a flat list of that many fields is exactly the kind of
 * screen a "professional" settings surface is supposed to not be. Reuses
 * [RestconfAuthEditor] and [RestconfKeyValueEditor] as-is (Part 2/4) rather
 * than re-implementing per-auth-type fields or the header list editor a
 * second time.
 *
 * Every field here is staged in local Compose state and only written back
 * via [onSave] — nothing is persisted keystroke-by-keystroke, so navigating
 * away without saving (back arrow, system back) discards edits exactly like
 * a cancel would, without needing a separate "Discard changes?" dialog for
 * the common case of an accidental back-press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestconfConfigurationScreen(
    profile: RdpProfile,
    connectionState: RestconfConnectionState,
    isLargeScreen: Boolean,
    onBack: () -> Unit,
    onSave: (RdpProfile) -> Unit,
    onPickClientCert: (onAliasPicked: (String) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    // SECURITY FIX: contains a password field — see security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()
    // ── Connection ───────────────────────────────────────────────────────
    var host by remember(profile.id) { mutableStateOf(profile.host) }
    var portText by remember(profile.id) { mutableStateOf(profile.port.toString()) }
    var username by remember(profile.id) { mutableStateOf(profile.username) }
    var password by remember(profile.id) { mutableStateOf(profile.password) }
    var passwordVisible by remember { mutableStateOf(false) }
    var useHttps by remember(profile.id) { mutableStateOf(profile.restconfUseHttps) }
    var acceptSelfSigned by remember(profile.id) { mutableStateOf(profile.acceptSelfSignedCertificate) }
    var dataFormat by remember(profile.id) { mutableStateOf(RestconfDataFormat.fromName(profile.restconfDataFormat)) }

    // ── Authentication (reuses RestconfAuthEditor's own field set) ─────────
    var authType by remember(profile.id) { mutableStateOf(RestconfAuthType.fromName(profile.restconfAuthType)) }
    var bearerToken by remember(profile.id) { mutableStateOf(profile.restconfBearerToken) }
    var jwtToken by remember(profile.id) { mutableStateOf(profile.restconfJwtToken) }
    var apiKeyHeaderName by remember(profile.id) { mutableStateOf(profile.restconfApiKeyHeaderName) }
    var apiKeyValue by remember(profile.id) { mutableStateOf(profile.restconfApiKeyValue) }
    var oauth2TokenUrl by remember(profile.id) { mutableStateOf(profile.restconfOAuth2TokenUrl) }
    var oauth2ClientId by remember(profile.id) { mutableStateOf(profile.restconfOAuth2ClientId) }
    var oauth2ClientSecret by remember(profile.id) { mutableStateOf(profile.restconfOAuth2ClientSecret) }
    var oauth2Scope by remember(profile.id) { mutableStateOf(profile.restconfOAuth2Scope) }
    var clientCertAlias by remember(profile.id) { mutableStateOf(profile.restconfClientCertAlias) }

    // ── TLS & Security ──────────────────────────────────────────────────
    var mutualTlsEnabled by remember(profile.id) { mutableStateOf(profile.restconfMutualTlsEnabled) }
    var certificatePins by remember(profile.id) { mutableStateOf(profile.restconfCertificatePins) }

    // ── Custom Headers (same "Name: value" per line encoding as the model) ─
    var headerRows by remember(profile.id) {
        mutableStateOf(parseConfigHeaderLines(profile.restconfCustomHeaders))
    }

    // ── Performance ──────────────────────────────────────────────────────
    var http2Enabled by remember(profile.id) { mutableStateOf(profile.restconfHttp2Enabled) }
    var compressionEnabled by remember(profile.id) { mutableStateOf(profile.restconfCompressionEnabled) }
    var keepAliveSeconds by remember(profile.id) { mutableFloatStateOf(profile.restconfKeepAliveSeconds.coerceIn(0, 300).toFloat()) }

    val portValue = portText.toIntOrNull()
    val portValid = portValue != null && portValue in 1..65535
    val pinCount = remember(certificatePins) { certificatePins.split(",").map { it.trim() }.count { it.isNotBlank() } }

    fun buildUpdatedProfile(): RdpProfile = profile.copy(
        host = host.trim(),
        port = portValue ?: profile.port,
        username = username,
        password = password,
        acceptSelfSignedCertificate = acceptSelfSigned,
        restconfUseHttps = useHttps,
        restconfDataFormat = dataFormat.name,
        restconfAuthType = authType.name,
        restconfBearerToken = bearerToken,
        restconfJwtToken = jwtToken,
        restconfApiKeyHeaderName = apiKeyHeaderName,
        restconfApiKeyValue = apiKeyValue,
        restconfOAuth2TokenUrl = oauth2TokenUrl,
        restconfOAuth2ClientId = oauth2ClientId,
        restconfOAuth2ClientSecret = oauth2ClientSecret,
        restconfOAuth2Scope = oauth2Scope,
        restconfClientCertAlias = clientCertAlias,
        restconfMutualTlsEnabled = mutualTlsEnabled,
        restconfCertificatePins = certificatePins,
        restconfCustomHeaders = toConfigHeaderLines(headerRows),
        restconfHttp2Enabled = http2Enabled,
        restconfCompressionEnabled = compressionEnabled,
        restconfKeepAliveSeconds = keepAliveSeconds.toInt(),
    )

    val isDirty = remember(
        host, portText, username, password, useHttps, acceptSelfSigned, dataFormat, authType,
        bearerToken, jwtToken, apiKeyHeaderName, apiKeyValue, oauth2TokenUrl, oauth2ClientId,
        oauth2ClientSecret, oauth2Scope, clientCertAlias, mutualTlsEnabled, certificatePins,
        headerRows, http2Enabled, compressionEnabled, keepAliveSeconds,
    ) { buildUpdatedProfile() != profile }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.restconf_config_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.restconf_back)) }
                },
                actions = {
                    ConnectionStatusChip(connectionState)
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { onSave(buildUpdatedProfile()) },
                        enabled = isDirty && portValid,
                    ) { Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.restconf_save_and_reconnect)) }
                    Spacer(Modifier.width(4.dp))
                },
            )
        },
    ) { padding ->
        val sections: List<@Composable () -> Unit> = listOf(
            {
                ConfigSection(title = stringResource(R.string.restconf_section_connection), icon = Icons.Outlined.Router, initiallyExpanded = true) {
                    OutlinedTextField(
                        value = host, onValueChange = { host = it },
                        label = { Text(stringResource(R.string.restconf_host)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = portText, onValueChange = { portText = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.restconf_port)) }, singleLine = true,
                        isError = !portValid,
                        supportingText = { if (!portValid) Text(stringResource(R.string.restconf_port_error), color = NovaPink) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username, onValueChange = { username = it },
                        label = { Text(stringResource(R.string.restconf_username)) }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text(stringResource(R.string.restconf_password)) }, singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (passwordVisible) stringResource(R.string.restconf_hide_password) else stringResource(R.string.restconf_show_password),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    ConfigToggleRow(stringResource(R.string.restconf_use_https), useHttps) { useHttps = it }
                    ConfigToggleRow(stringResource(R.string.restconf_accept_self_signed), acceptSelfSigned) { acceptSelfSigned = it }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.restconf_preferred_data_format), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RestconfDataFormat.entries.forEach { fmt ->
                            FilterChip(
                                selected = dataFormat == fmt,
                                onClick = { dataFormat = fmt },
                                label = { Text(fmt.name) },
                            )
                        }
                    }
                }
            },
            {
                ConfigSection(title = stringResource(R.string.restconf_section_authentication), icon = Icons.Outlined.Key, initiallyExpanded = true) {
                    RestconfAuthEditor(
                        authType = authType, onAuthTypeChange = { authType = it },
                        username = username, onUsernameChange = { username = it },
                        password = password, onPasswordChange = { password = it },
                        bearerToken = bearerToken, onBearerTokenChange = { bearerToken = it },
                        jwtToken = jwtToken, onJwtTokenChange = { jwtToken = it },
                        apiKeyHeaderName = apiKeyHeaderName, onApiKeyHeaderNameChange = { apiKeyHeaderName = it },
                        apiKeyValue = apiKeyValue, onApiKeyValueChange = { apiKeyValue = it },
                        oauth2TokenUrl = oauth2TokenUrl, onOAuth2TokenUrlChange = { oauth2TokenUrl = it },
                        oauth2ClientId = oauth2ClientId, onOAuth2ClientIdChange = { oauth2ClientId = it },
                        oauth2ClientSecret = oauth2ClientSecret, onOAuth2ClientSecretChange = { oauth2ClientSecret = it },
                        oauth2Scope = oauth2Scope, onOAuth2ScopeChange = { oauth2Scope = it },
                        clientCertAlias = clientCertAlias,
                        onPickClientCert = { onPickClientCert { alias -> clientCertAlias = alias } },
                    )
                }
            },
            {
                ConfigSection(title = stringResource(R.string.restconf_section_tls_security), icon = Icons.Outlined.Security) {
                    ConfigToggleRow(stringResource(R.string.restconf_require_mutual_tls), mutualTlsEnabled) { mutualTlsEnabled = it }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = certificatePins, onValueChange = { certificatePins = it },
                        label = { Text(stringResource(R.string.restconf_certificate_pins)) },
                        supportingText = {
                            Text(
                                if (certificatePins.isBlank()) stringResource(R.string.restconf_no_pinning)
                                else pluralStringResource(R.plurals.restconf_pins_configured, pinCount, pinCount),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            {
                ConfigSection(title = stringResource(R.string.restconf_section_custom_headers), icon = Icons.Outlined.Key) {
                    RestconfKeyValueEditor(
                        rows = headerRows, onRowsChange = { headerRows = it },
                        keyLabel = stringResource(R.string.restconf_header_name), valueLabel = stringResource(R.string.restconf_header_value),
                    )
                }
            },
            {
                ConfigSection(title = stringResource(R.string.restconf_section_performance), icon = Icons.Outlined.Speed) {
                    ConfigToggleRow(stringResource(R.string.restconf_http2), http2Enabled) { http2Enabled = it }
                    ConfigToggleRow(stringResource(R.string.restconf_response_compression), compressionEnabled) { compressionEnabled = it }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.restconf_keep_alive), style = MaterialTheme.typography.labelLarge)
                        Text("${keepAliveSeconds.toInt()}s", style = MaterialTheme.typography.labelLarge, color = PulsarCyan)
                    }
                    Slider(
                        value = keepAliveSeconds,
                        onValueChange = { keepAliveSeconds = it },
                        valueRange = 0f..300f,
                        steps = 29,
                    )
                }
            },
        )

        Box(Modifier.padding(padding).fillMaxWidth()) {
            if (isLargeScreen) {
                // Tablet/foldable/DeX/Desktop-Mode: two independently-scrolling
                // columns instead of one long single-column form — same
                // DeviceFormFactor signal RestconfExplorerScreen's request/
                // response split and SplitScreen already gate on.
                val left = sections.filterIndexed { i, _ -> i % 2 == 0 }
                val right = sections.filterIndexed { i, _ -> i % 2 == 1 }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { left.forEach { it() } }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { right.forEach { it() } }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { sections.forEach { it() } }
            }
        }
    }
}

/** Small live connection-state pill shown next to the Save action, so a mid-edit reconnect (from a Save on a previous visit, or a background retry) is always visible without switching back to the Request tab. */
@Composable
private fun ConnectionStatusChip(state: RestconfConnectionState) {
    val (label, color) = when (state) {
        RestconfConnectionState.CONNECTED -> stringResource(R.string.restconf_status_connected) to PlasmaGreen
        RestconfConnectionState.CONNECTING, RestconfConnectionState.RECONNECTING -> "…" to SolarFlare
        RestconfConnectionState.ERROR -> stringResource(R.string.restconf_status_error) to NovaPink
        RestconfConnectionState.DISCONNECTED -> stringResource(R.string.restconf_status_off) to PulsarCyan
    }
    Surface(color = color.copy(alpha = 0.15f), contentColor = color, shape = MaterialTheme.shapes.small) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** One collapsible, card-framed group of fields — the building block every category (Connection/Authentication/TLS & Security/Custom Headers/Performance) above is made of. */
@Composable
private fun ConfigSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    // Tap-to-toggle without the ripple's implied "this is a button" affordance
    // a whole section header shouldn't fully commit to (the chevron icon
    // still gets a normal click target via its own semantics).
    val headerInteractionSource = remember { MutableInteractionSource() }
    Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().clickable(
                    indication = null,
                    interactionSource = headerInteractionSource,
                ) { expanded = !expanded },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = PulsarCyan, modifier = Modifier.width(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (expanded) stringResource(R.string.restconf_collapse) else stringResource(R.string.restconf_expand))
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(Modifier.padding(top = 12.dp)) { content() }
            }
        }
    }
}

/** Label + Switch row shared by every boolean setting in this screen. */
@Composable
private fun ConfigToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun parseConfigHeaderLines(raw: String): List<RestconfKeyValueRow> =
    raw.lines().mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) null else RestconfKeyValueRow(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
    }

private fun toConfigHeaderLines(rows: List<RestconfKeyValueRow>): String =
    rows.filter { it.key.isNotBlank() }.joinToString("\n") { "${it.key}: ${it.value}" }
