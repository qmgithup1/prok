package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.systemsgo.hex.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.snmp.protocol.Oid
import com.systemsgo.hex.snmp.protocol.SnmpValue
import java.time.format.DateTimeFormatter

private enum class SnmpTab(val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DASHBOARD(Icons.Filled.Dashboard),
    BROWSER(Icons.Filled.AccountTree),
    TOOLS(Icons.Filled.Build),
    TRAPS(Icons.Filled.NotificationsActive),
}

@Composable
private fun SnmpTab.displayLabel(): String = when (this) {
    SnmpTab.DASHBOARD -> stringResource(R.string.snmp_tab_dashboard)
    SnmpTab.BROWSER -> stringResource(R.string.snmp_tab_mib_browser)
    SnmpTab.TOOLS -> stringResource(R.string.snmp_tab_get_set)
    SnmpTab.TRAPS -> stringResource(R.string.snmp_tab_traps)
}

/**
 * SNMP FEATURE: the four-tab management UI driven by [SnmpManagementViewModel]
 * — see that class for the [com.systemsgo.hex.snmp.protocol.SnmpClient] /
 * [com.systemsgo.hex.snmp.protocol.SnmpTrapListener] wiring. Mirrors
 * BmcManagementScreen's Scaffold+TabRow shape.
 */
@Composable
fun SnmpManagementScreen(profile: RdpProfile, viewModel: SnmpManagementViewModel, onFinish: () -> Unit) {
    var tab by remember { mutableStateOf(SnmpTab.DASHBOARD) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(profile.name.ifBlank { profile.host }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.snmp_host_port_version, profile.host, profile.snmpPort, profile.snmpVersion), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onFinish) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.snmp_back)) } },
            )
        },
        bottomBar = {
            NavigationBar {
                SnmpTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.displayLabel()) },
                        label = { Text(t.displayLabel()) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                SnmpTab.DASHBOARD -> DashboardTab(viewModel)
                SnmpTab.BROWSER -> BrowserTab(viewModel)
                SnmpTab.TOOLS -> ToolsTab(viewModel)
                SnmpTab.TRAPS -> TrapsTab(viewModel)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String?) {
    if (message != null) {
        Card(
            Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun ResultRowItem(row: SnmpResultRow, viewModel: SnmpManagementViewModel, favorites: List<Oid>) {
    ListItem(
        headlineContent = { Text(row.name, fontFamily = FontFamily.Monospace) },
        supportingContent = {
            Column {
                Text(row.oid.toString(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(row.displayValue, style = MaterialTheme.typography.bodyMedium)
            }
        },
        trailingContent = {
            IconButton(onClick = { viewModel.toggleFavorite(row.oid) }) {
                Icon(
                    if (row.oid in favorites) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = stringResource(R.string.snmp_favorite),
                    tint = if (row.oid in favorites) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        },
    )
    HorizontalDivider()
}

@Composable
private fun DashboardTab(viewModel: SnmpManagementViewModel) {
    val rows by viewModel.dashboard.collectAsStateWithLifecycle()
    val state by viewModel.dashboardState.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.snmp_system_overview), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { viewModel.refreshDashboard() }) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.snmp_refresh))
            }
        }
        ErrorBanner(state.error)
        if (favorites.isNotEmpty()) {
            Text(stringResource(R.string.snmp_favorites), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp))
            LazyColumn(Modifier.weight(0.35f)) {
                items(rows.filter { it.oid in favorites }) { ResultRowItem(it, viewModel, favorites) }
            }
            HorizontalDivider(thickness = 2.dp)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(rows) { ResultRowItem(it, viewModel, favorites) }
        }
    }
}

@Composable
private fun BrowserTab(viewModel: SnmpManagementViewModel) {
    var rootOid by remember { mutableStateOf("1.3.6.1.2.1.1") }
    var mibText by remember { mutableStateOf("") }
    var showLoadDialog by remember { mutableStateOf(false) }
    val results by viewModel.walkResults.collectAsStateWithLifecycle()
    val state by viewModel.walkState.collectAsStateWithLifecycle()
    val mibMessage by viewModel.mibLoadMessage.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = rootOid, onValueChange = { rootOid = it },
                label = { Text(stringResource(R.string.snmp_root_oid_label)) },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { viewModel.walk(rootOid) }) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.snmp_walk))
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { showLoadDialog = true }) { Icon(Icons.Filled.UploadFile, contentDescription = stringResource(R.string.snmp_load_mib)) }
        }
        mibMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
        }
        ErrorBanner(state.error)
        Text(
            "${results.size} object${if (results.size == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(results) { ResultRowItem(it, viewModel, favorites) }
        }
    }

    if (showLoadDialog) {
        AlertDialog(
            onDismissRequest = { showLoadDialog = false },
            title = { Text(stringResource(R.string.snmp_load_custom_mib)) },
            text = {
                Column {
                    Text(
                        "Paste the contents of a standard-format .mib/.txt file. Names it can resolve are merged into the browser's name table for this session (see MibParser for what \"resolve\" covers).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = mibText, onValueChange = { mibText = it },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        label = { Text(stringResource(R.string.snmp_mib_text)) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.loadMibText(mibText); showLoadDialog = false }) { Text(stringResource(R.string.snmp_load)) }
            },
            dismissButton = { TextButton(onClick = { showLoadDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private enum class SnmpValueType(val label: String) { STRING("OCTET STRING"), INTEGER("INTEGER"), OID("OID"), IP_ADDRESS("IpAddress") }

@Composable
private fun ToolsTab(viewModel: SnmpManagementViewModel) {
    var oidText by remember { mutableStateOf("1.3.6.1.2.1.1.5.0") }
    var setValueText by remember { mutableStateOf("") }
    var setType by remember { mutableStateOf(SnmpValueType.STRING) }
    var typeMenuExpanded by remember { mutableStateOf(false) }
    // SET-CONFIRMATION FEATURE: a SET writes live configuration to the
    // device, unlike GET/GetNext/Walk — require an explicit confirm step
    // (see the AlertDialog at the end of this composable) rather than
    // firing on the first tap.
    var pendingSet by remember { mutableStateOf<Pair<String, SnmpValue>?>(null) }
    var pendingSetError by remember { mutableStateOf<String?>(null) }
    val results by viewModel.toolResults.collectAsStateWithLifecycle()
    val state by viewModel.toolState.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = oidText, onValueChange = { oidText = it },
                label = { Text(stringResource(R.string.snmp_oid)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = { viewModel.get(oidText) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.snmp_get)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { viewModel.getNext(oidText) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.snmp_get_next)) }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.snmp_set), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { typeMenuExpanded = true }) { Text(setType.label) }
                    DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                        SnmpValueType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.label) }, onClick = { setType = t; typeMenuExpanded = false })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = setValueText, onValueChange = { setValueText = it },
                    label = { Text(stringResource(R.string.snmp_value)) }, singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val parsed = runCatching {
                        when (setType) {
                            SnmpValueType.STRING -> SnmpValue.OctetStringVal(setValueText.toByteArray(Charsets.UTF_8))
                            SnmpValueType.INTEGER -> SnmpValue.IntegerVal(setValueText.trim().toLong())
                            SnmpValueType.OID -> SnmpValue.ObjectIdVal(Oid(setValueText.trim()))
                            SnmpValueType.IP_ADDRESS -> SnmpValue.IpAddressVal(
                                setValueText.trim().split(".").map { it.toInt().toByte() }.toByteArray()
                            )
                        }
                    }
                    parsed.onSuccess { pendingSet = oidText to it }
                        .onFailure { pendingSetError = "Invalid ${setType.label} value: \"$setValueText\"" }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
            ) { Text(stringResource(R.string.snmp_set)) }
        }
        ErrorBanner(state.error)
        ErrorBanner(pendingSetError)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        results.forEach { ResultRowItem(it, viewModel, favorites) }
    }

    val toSet = pendingSet
    if (toSet != null) {
        val (targetOid, value) = toSet
        AlertDialog(
            onDismissRequest = { pendingSet = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.snmp_confirm_set)) },
            text = {
                Column {
                    Text(stringResource(R.string.snmp_set_warning))
                    Spacer(Modifier.height(8.dp))
                    Text(targetOid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.snmp_arrow_value, value), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.set(targetOid, value); pendingSet = null }) {
                    Text(stringResource(R.string.snmp_set), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingSet = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun TrapsTab(viewModel: SnmpManagementViewModel) {
    var port by remember { mutableStateOf("1620") }
    var communitiesText by remember { mutableStateOf("public") }
    var showAddV3User by remember { mutableStateOf(false) }
    val listening by viewModel.trapListening.collectAsStateWithLifecycle()
    val events by viewModel.trapEvents.collectAsStateWithLifecycle()
    val error by viewModel.trapError.collectAsStateWithLifecycle()
    val v3Users by viewModel.trapV3Users.collectAsStateWithLifecycle()
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Port 162 needs root on Android — use a router port-forward (UDP 162 → the port below) unless this device is rooted.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.snmp_listen_port)) }, singleLine = true, modifier = Modifier.width(140.dp),
                    enabled = !listening,
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = communitiesText, onValueChange = { communitiesText = it },
                    label = { Text(stringResource(R.string.snmp_accepted_communities)) }, singleLine = true,
                    modifier = Modifier.weight(1f), enabled = !listening,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.snmp_v3_users), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { showAddV3User = true }, enabled = !listening) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.snmp_add_user))
                }
            }
            Text(
                "Needed to authenticate/decrypt v3 traps or informs — a v3 packet from a username not listed here can't be read (see SnmpTrapListener's doc comment for why).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (v3Users.isEmpty()) {
                Text(stringResource(R.string.snmp_no_v3_users), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            } else {
                v3Users.forEach { u ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.snmp_username_security_level, u.username, u.securityLevel.label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.removeTrapV3User(u.username) }, enabled = !listening) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.snmp_remove), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = {
                        val portInt = port.toIntOrNull() ?: 1620
                        val communities = communitiesText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                        viewModel.startTrapListener(portInt, communities)
                    },
                    enabled = !listening, modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.snmp_start_listening)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { viewModel.stopTrapListener() }, enabled = listening, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.snmp_stop))
                }
            }
            if (listening) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Circle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.snmp_listening_on_udp, port), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        ErrorBanner(error)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.snmp_events_received, events.size), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { viewModel.clearTrapEvents() }, enabled = events.isNotEmpty()) { Text(stringResource(R.string.clear)) }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(events) { event ->
                ListItem(
                    headlineContent = { Text(event.friendlyName(), fontFamily = FontFamily.Monospace) },
                    supportingContent = {
                        Column {
                            Text(
                                "${formatter.format(event.receivedAt.atZone(java.time.ZoneId.systemDefault()))} · ${event.sourceHost} · ${event.version.label}${if (event.isInform) " (Inform)" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            SelectionContainer {
                                Column {
                                    event.varBinds.forEach { vb ->
                                        Text(
                                            "${com.systemsgo.hex.snmp.mib.MibDictionary.describe(vb.oid)} = ${vb.value}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    leadingContent = { Icon(Icons.Filled.NotificationsActive, contentDescription = null) },
                )
                HorizontalDivider()
            }
        }
    }

    if (showAddV3User) {
        AddV3UserDialog(
            onDismiss = { showAddV3User = false },
            onConfirm = { creds -> viewModel.addTrapV3User(creds); showAddV3User = false },
        )
    }
}

/** SNMP FEATURE: adds one entry to the trap receiver's v3-user registry — see TrapsTab's doc text and SnmpTrapListener's class doc for why this is needed at all. */
@Composable
private fun AddV3UserDialog(onDismiss: () -> Unit, onConfirm: (com.systemsgo.hex.snmp.protocol.SnmpCredentials.Usm) -> Unit) {
    // SECURITY FIX: contains auth/priv passphrase fields — see security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()
    var username by remember { mutableStateOf("") }
    var securityLevel by remember { mutableStateOf(com.systemsgo.hex.snmp.protocol.SnmpSecurityLevel.AUTH_PRIV) }
    var authProtocol by remember { mutableStateOf(com.systemsgo.hex.snmp.protocol.SnmpAuthProtocol.SHA1) }
    var authPassphrase by remember { mutableStateOf("") }
    var privProtocol by remember { mutableStateOf(com.systemsgo.hex.snmp.protocol.SnmpPrivProtocol.AES128) }
    var privPassphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.snmp_add_v3_trap_user)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(stringResource(R.string.snmp_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.snmp_security_level), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.systemsgo.hex.snmp.protocol.SnmpSecurityLevel.entries.forEach { level ->
                        FilterChip(selected = securityLevel == level, onClick = { securityLevel = level }, label = { Text(level.label) })
                    }
                }
                if (securityLevel != com.systemsgo.hex.snmp.protocol.SnmpSecurityLevel.NO_AUTH_NO_PRIV) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.snmp_auth_protocol), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            com.systemsgo.hex.snmp.protocol.SnmpAuthProtocol.MD5, com.systemsgo.hex.snmp.protocol.SnmpAuthProtocol.SHA1,
                            com.systemsgo.hex.snmp.protocol.SnmpAuthProtocol.SHA256,
                        ).forEach { p -> FilterChip(selected = authProtocol == p, onClick = { authProtocol = p }, label = { Text(p.label) }) }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = authPassphrase, onValueChange = { authPassphrase = it }, label = { Text(stringResource(R.string.snmp_auth_passphrase)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                }
                if (securityLevel == com.systemsgo.hex.snmp.protocol.SnmpSecurityLevel.AUTH_PRIV) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.snmp_privacy_protocol), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            com.systemsgo.hex.snmp.protocol.SnmpPrivProtocol.DES, com.systemsgo.hex.snmp.protocol.SnmpPrivProtocol.AES128,
                        ).forEach { p -> FilterChip(selected = privProtocol == p, onClick = { privProtocol = p }, label = { Text(p.label) }) }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = privPassphrase, onValueChange = { privPassphrase = it }, label = { Text(stringResource(R.string.snmp_privacy_passphrase)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank(),
                onClick = {
                    onConfirm(
                        com.systemsgo.hex.snmp.protocol.SnmpCredentials.Usm(
                            username = username.trim(),
                            securityLevel = securityLevel,
                            authProtocol = authProtocol,
                            authPassphrase = authPassphrase,
                            privProtocol = privProtocol,
                            privPassphrase = privPassphrase,
                        )
                    )
                },
            ) { Text(stringResource(R.string.snmp_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
