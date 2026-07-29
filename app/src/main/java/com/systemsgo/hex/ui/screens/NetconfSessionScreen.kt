package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.systemsgo.hex.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.netconf.protocol.NetconfDatastore
import com.systemsgo.hex.netconf.protocol.NetconfSessionState
import com.systemsgo.hex.netconf.protocol.NetconfWireMessage
import com.systemsgo.hex.ui.components.MouseContextMenuArea
import com.systemsgo.hex.ui.components.NetconfKeyboardShortcuts
import com.systemsgo.hex.ui.components.netconfKeyboardShortcuts
import com.systemsgo.hex.util.DeviceFormFactor

/**
 * NETCONF FEATURE: the session UI for [NetconfSessionActivity]. Same
 * "status card + tab row" shape as [BmcManagementScreen] (a NETCONF session
 * is structured-data interaction, not a framebuffer), with tabs for the
 * datastore browser, the RPC Builder/Saved-RPC-Library tools, and the raw
 * XML wire log / notification stream.
 */
@Composable
fun NetconfSessionScreen(
    profile: RdpProfile,
    viewModel: NetconfSessionViewModel,
    onFinish: () -> Unit,
) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val hello by viewModel.hello.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    val tabs = listOf(
        stringResource(R.string.netconf_tab_status), stringResource(R.string.netconf_datastore),
        stringResource(R.string.netconf_tab_rpc_tools), stringResource(R.string.netconf_tab_yang),
        stringResource(R.string.netconf_tab_diff), stringResource(R.string.netconf_tab_wire_log),
        stringResource(R.string.netconf_tab_notifications),
    )
    val tabIcons = listOf(
        Icons.Outlined.Info, Icons.Outlined.Storage, Icons.Outlined.Build,
        Icons.Outlined.AccountTree, Icons.Outlined.Compare, Icons.Outlined.List,
        Icons.Outlined.Notifications,
    )
    var selectedTab by remember { mutableIntStateOf(0) }

    // DEX / FOLDABLE FEATURE: on a desktop-class surface (Samsung DeX,
    // Android Desktop Mode, an unfolded foldable, or any tablet-sized/
    // freeform window — see DeviceFormFactor's doc comment), the phone's
    // "one tab visible at a time" TabRow wastes the extra width. Reading
    // LocalConfiguration.current (rather than context.resources.configuration
    // directly) makes this recompute on every configuration change — an
    // unfold, a DeX dock/undock, or a freeform window resize — not just at
    // first composition.
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isDesktopClass by remember(configuration) {
        derivedStateOf { DeviceFormFactor.supportsDesktopFeatures(context) }
    }

    // NETCONF-KEYBOARD-MOUSE FEATURE: a hardware keyboard's Ctrl+Enter/
    // Ctrl+S/Ctrl+F need to reach whichever tab-scoped composable actually
    // owns that action's state (the RPC Builder's text, the YANG search
    // field) — since those live in child composables, not here, a trigger
    // counter is the simplest way to signal "fire your action now" down
    // through props without hoisting all of that per-tab state up to this
    // shared root just for shortcut wiring.
    var sendTrigger by remember { mutableIntStateOf(0) }
    var saveTrigger by remember { mutableIntStateOf(0) }
    var findTrigger by remember { mutableIntStateOf(0) }
    var escapeTrigger by remember { mutableIntStateOf(0) }

    val shortcuts = remember {
        NetconfKeyboardShortcuts(
            onSwitchTab = { index -> if (index in tabs.indices) selectedTab = index },
            onReconnect = { viewModel.reconnect() },
            onSend = { sendTrigger++ },
            onSave = { saveTrigger++ },
            onFind = { findTrigger++ },
            onEscape = { escapeTrigger++ },
        )
    }

    Scaffold(
        modifier = Modifier.netconfKeyboardShortcuts(shortcuts),
        topBar = {
            TopAppBar(
                title = { Text(profile.name.ifBlank { profile.host }) },
                navigationIcon = {
                    IconButton(onClick = onFinish) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    NetconfStateBadge(sessionState)
                    IconButton(onClick = { viewModel.reconnect() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.netconf_reconnect))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            toast?.let { msg ->
                NetconfBanner(msg) { viewModel.dismissToast() }
            }

            val tabContent: @Composable () -> Unit = {
                when (selectedTab) {
                    0 -> NetconfStatusTab(profile, sessionState, hello, stats)
                    1 -> NetconfDatastoreTab(viewModel)
                    2 -> NetconfRpcToolsTab(viewModel, sendTrigger = sendTrigger, saveTrigger = saveTrigger)
                    3 -> NetconfYangTab(viewModel, findTrigger = findTrigger, escapeTrigger = escapeTrigger)
                    4 -> NetconfDiffTab(viewModel)
                    5 -> NetconfWireLogTab(viewModel)
                    6 -> NetconfNotificationsTab(viewModel)
                }
            }

            if (isDesktopClass) {
                // DEX / FOLDABLE FEATURE: a permanent NavigationRail (every
                // tab visible and one tap away, instead of a scrolling
                // TabRow sized for thumb reach) plus an always-visible
                // connection-glance sidebar — the same "master info panel +
                // detail pane" shape SessionsScreen already uses on large
                // screens, applied here to a session that isn't a
                // framebuffer. The Status *tab* is left in the rail too
                // (jumping to it still shows the fuller scrollable version),
                // since the sidebar is a compact glance, not a replacement.
                Row(Modifier.fillMaxSize()) {
                    NavigationRail {
                        tabs.forEachIndexed { i, label ->
                            NavigationRailItem(
                                selected = selectedTab == i,
                                onClick = { selectedTab = i },
                                icon = { Icon(tabIcons[i], contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                    NetconfStatusSidebar(
                        profile = profile,
                        sessionState = sessionState,
                        hello = hello,
                        stats = stats,
                        modifier = Modifier.width(260.dp).fillMaxHeight(),
                    )
                    VerticalDivider()
                    Box(Modifier.weight(1f).fillMaxHeight()) { tabContent() }
                }
            } else {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { i, label ->
                        Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(label) })
                    }
                }
                Box(Modifier.fillMaxSize()) { tabContent() }
            }
        }
    }
}

/**
 * DEX / FOLDABLE FEATURE: compact, always-visible counterpart to
 * [NetconfStatusTab] for the desktop-class two-pane layout — session state,
 * uptime, and datastore/capability counts at a glance while a different tab
 * (RPC Tools, YANG, Wire Log…) occupies the main pane, so switching tabs to
 * check "is it still connected?" isn't necessary on a wide window.
 */
@Composable
private fun NetconfStatusSidebar(
    profile: RdpProfile,
    sessionState: NetconfSessionState,
    hello: com.systemsgo.hex.netconf.protocol.NetconfHelloInfo?,
    stats: com.systemsgo.hex.netconf.protocol.NetconfConnectionStats,
    modifier: Modifier = Modifier,
) {
    val uptimeSec = stats.connectedSinceMs?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(profile.name.ifBlank { profile.host }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        NetconfStateBadge(sessionState)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        InfoRow(stringResource(R.string.netconf_label_host), "${profile.host}:${profile.port}")
        InfoRow(stringResource(R.string.netconf_label_session_id), hello?.sessionId?.toString() ?: "—")
        InfoRow(stringResource(R.string.netconf_label_uptime), formatDuration(uptimeSec))
        InfoRow(stringResource(R.string.netconf_label_latency), "${stats.lastLatencyMs} ms")
        InfoRow(stringResource(R.string.netconf_label_rpcs), stats.rpcCount.toString())
        InfoRow(stringResource(R.string.netconf_label_notifications), stats.notificationCount.toString())
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.netconf_datastores), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        val datastores = hello?.supportedDatastores.orEmpty()
        if (datastores.isEmpty()) {
            Text(stringResource(R.string.netconf_not_yet_known), style = MaterialTheme.typography.bodySmall)
        } else {
            datastores.forEach { Text(stringResource(R.string.netconf_bullet_item, it), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun NetconfStateBadge(state: NetconfSessionState) {
    val (label, color) = when (state) {
        NetconfSessionState.CONNECTED -> stringResource(R.string.netconf_state_connected) to MaterialTheme.colorScheme.primary
        NetconfSessionState.CONNECTING -> stringResource(R.string.netconf_state_connecting) to MaterialTheme.colorScheme.tertiary
        NetconfSessionState.RECONNECTING -> stringResource(R.string.netconf_state_reconnecting) to MaterialTheme.colorScheme.tertiary
        NetconfSessionState.AUTH_FAILED -> stringResource(R.string.netconf_state_auth_failed) to MaterialTheme.colorScheme.error
        NetconfSessionState.ERROR -> stringResource(R.string.netconf_state_error) to MaterialTheme.colorScheme.error
        NetconfSessionState.DISCONNECTED -> stringResource(R.string.netconf_state_disconnected) to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        Box(Modifier.size(8.dp).background(color, shape = androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun NetconfBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.netconf_dismiss)) }
        }
    }
}

// ── Status tab: session details, capabilities, datastores, stats ──

@Composable
private fun NetconfStatusTab(
    profile: RdpProfile,
    sessionState: NetconfSessionState,
    hello: com.systemsgo.hex.netconf.protocol.NetconfHelloInfo?,
    stats: com.systemsgo.hex.netconf.protocol.NetconfConnectionStats,
) {
    val uptimeSec = stats.connectedSinceMs?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.netconf_connection), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                InfoRow(stringResource(R.string.netconf_label_host), "${profile.host}:${profile.port}")
                InfoRow(stringResource(R.string.netconf_label_username), profile.username)
                InfoRow(stringResource(R.string.netconf_label_session_state), sessionState.name)
                InfoRow(stringResource(R.string.netconf_label_session_id), hello?.sessionId?.toString() ?: "—")
                InfoRow(stringResource(R.string.netconf_label_uptime), formatDuration(uptimeSec))
                InfoRow(stringResource(R.string.netconf_label_last_rpc_latency), "${stats.lastLatencyMs} ms")
                InfoRow(stringResource(R.string.netconf_label_reconnects), stats.reconnectCount.toString())
            }
        }
        Spacer(Modifier.height(12.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.netconf_statistics), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                InfoRow(stringResource(R.string.netconf_label_rpc_count), stats.rpcCount.toString())
                InfoRow(stringResource(R.string.netconf_label_notification_count), stats.notificationCount.toString())
                InfoRow(stringResource(R.string.netconf_label_bytes_sent), formatBytes(stats.bytesSent))
                InfoRow(stringResource(R.string.netconf_label_bytes_received), formatBytes(stats.bytesReceived))
            }
        }
        Spacer(Modifier.height(12.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.netconf_datastores), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val datastores = hello?.supportedDatastores.orEmpty()
                if (datastores.isEmpty()) {
                    Text(stringResource(R.string.netconf_not_yet_known), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        datastores.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.netconf_capabilities_count, hello?.capabilities?.size ?: 0), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                hello?.capabilities.orEmpty().forEach { cap ->
                    Text(cap.uri, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

// ── Datastore tab: pick a datastore, run get-config, lock/unlock/commit/validate ──

@Composable
private fun NetconfDatastoreTab(viewModel: NetconfSessionViewModel) {
    val selected by viewModel.selectedDatastore.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.netconf_datastore), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NetconfDatastore.entries.forEach { ds ->
                FilterChip(
                    selected = selected == ds,
                    onClick = { viewModel.selectDatastore(ds) },
                    label = { Text(ds.elementName) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.runGetConfig() }) { Text(stringResource(R.string.netconf_get_config)) }
            OutlinedButton(onClick = { viewModel.runLock() }) { Text(stringResource(R.string.netconf_lock)) }
            OutlinedButton(onClick = { viewModel.runUnlock() }) { Text(stringResource(R.string.netconf_unlock)) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.runValidate() }) { Text(stringResource(R.string.netconf_validate)) }
            OutlinedButton(onClick = { viewModel.runCommit() }) { Text(stringResource(R.string.netconf_commit)) }
            OutlinedButton(onClick = { viewModel.runDiscardChanges() }) { Text(stringResource(R.string.netconf_discard)) }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.netconf_last_result), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        NetconfXmlOutput(lastResult?.replyXml ?: lastResult?.error ?: stringResource(R.string.netconf_no_rpc_run_yet))
    }
}

@Composable
private fun NetconfXmlOutput(xml: String) {
    ElevatedCard(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        SelectionContainer {
            Text(
                text = prettyPrintXml(xml),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}

/**
 * XML-EDITOR FEATURE (pretty-formatting slice): lightweight, dependency-free
 * indenter for displaying rpc-reply/get-config XML readably in the output
 * panels above.
 */
internal fun prettyPrintXml(xml: String): String {
    if (xml.isBlank()) return xml
    return try {
        val trimmed = xml.trim()
        val sb = StringBuilder()
        var indent = 0
        var i = 0
        while (i < trimmed.length) {
            val ltIdx = trimmed.indexOf('<', i)
            if (ltIdx == -1) { sb.append(trimmed.substring(i)); break }
            if (ltIdx > i) sb.append(trimmed.substring(i, ltIdx).trim())
            val gtIdx = trimmed.indexOf('>', ltIdx)
            if (gtIdx == -1) break
            val tag = trimmed.substring(ltIdx, gtIdx + 1)
            val isClosing = tag.startsWith("</")
            val isSelfClosing = tag.endsWith("/>") || tag.startsWith("<?") || tag.startsWith("<!--")
            if (isClosing) indent = (indent - 1).coerceAtLeast(0)
            sb.append("\n").append("  ".repeat(indent)).append(tag)
            if (!isClosing && !isSelfClosing) indent++
            i = gtIdx + 1
        }
        sb.toString().trim()
    } catch (_: Exception) {
        xml
    }
}

// ── RPC Tools tab: RPC Builder + Saved RPC Library ──

@Composable
private fun NetconfRpcToolsTab(
    viewModel: NetconfSessionViewModel,
    sendTrigger: Int = 0,
    saveTrigger: Int = 0,
) {
    val savedRpcs by viewModel.savedRpcs.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()
    val knownTagNames by viewModel.yangKnownTagNames.collectAsStateWithLifecycle()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var rpcBody by remember { mutableStateOf("<get><filter type=\"subtree\"/></get>") }
    var loadKey by remember { mutableIntStateOf(0) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    fun loadIntoBuilder(xml: String) {
        rpcBody = xml
        loadKey++
    }

    // NETCONF-KEYBOARD-MOUSE FEATURE: Ctrl+Enter/Ctrl+S from the session
    // screen's root shortcut handler land here as trigger-counter bumps —
    // see NetconfKeyboardMouse.kt's doc comment for why a counter instead
    // of hoisting rpcBody itself up to the parent.
    LaunchedEffect(sendTrigger) { if (sendTrigger > 0) viewModel.runCustomRpc("custom", rpcBody) }
    LaunchedEffect(saveTrigger) { if (saveTrigger > 0) showSaveDialog = true }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.netconf_rpc_builder), style = MaterialTheme.typography.titleMedium)
            Row {
                IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(rpcBody)) }) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.netconf_export_xml_clipboard))
                }
                IconButton(onClick = {
                    val pasted = clipboard.getText()?.text
                    if (!pasted.isNullOrBlank()) loadIntoBuilder(pasted)
                }) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.netconf_import_xml_clipboard))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        key(loadKey) {
            XmlCodeEditor(
                initialValue = rpcBody,
                onValueChange = { rpcBody = it },
                knownTagNames = knownTagNames,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.runCustomRpc("custom", rpcBody) }) { Text(stringResource(R.string.netconf_send)) }
            OutlinedButton(onClick = { showSaveDialog = true }) { Text(stringResource(R.string.save)) }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.netconf_saved_rpc_library), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(savedRpcs, key = { it.id }) { saved ->
                MouseContextMenuArea(
                    items = listOf(
                        stringResource(R.string.netconf_load_into_builder) to { loadIntoBuilder(saved.bodyXml) },
                        stringResource(R.string.netconf_run) to { viewModel.runCustomRpc(saved.name, saved.bodyXml) },
                        stringResource(R.string.netconf_copy_xml) to { clipboard.setText(androidx.compose.ui.text.AnnotatedString(saved.bodyXml)) },
                        stringResource(R.string.netconf_toggle_favorite) to { viewModel.toggleFavoriteRpc(saved.id) },
                        stringResource(R.string.delete) to { viewModel.deleteSavedRpc(saved.id) },
                    ),
                ) {
                    ListItem(
                        headlineContent = { Text(saved.name) },
                        supportingContent = { Text(saved.bodyXml, maxLines = 1) },
                        leadingContent = {
                            Icon(
                                if (saved.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = stringResource(R.string.netconf_favorite),
                                modifier = Modifier.clickable { viewModel.toggleFavoriteRpc(saved.id) },
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { loadIntoBuilder(saved.bodyXml) }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.netconf_load_into_builder))
                                }
                                IconButton(onClick = { viewModel.runCustomRpc(saved.name, saved.bodyXml) }) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.netconf_run))
                                }
                                IconButton(onClick = { viewModel.deleteSavedRpc(saved.id) }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                                }
                            }
                        },
                    )
                }
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.netconf_result), style = MaterialTheme.typography.titleSmall)
        NetconfXmlOutput(lastResult?.replyXml ?: lastResult?.error ?: stringResource(R.string.netconf_no_rpc_run_yet))
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.netconf_save_rpc)) },
            text = {
                OutlinedTextField(value = saveName, onValueChange = { saveName = it }, label = { Text(stringResource(R.string.netconf_name)) })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (saveName.isNotBlank()) viewModel.saveRpc(saveName, rpcBody)
                    saveName = ""
                    showSaveDialog = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ── YANG tab: module discovery, namespace explorer, schema tree, XPath builder ──

@Composable
private fun NetconfYangTab(
    viewModel: NetconfSessionViewModel,
    findTrigger: Int = 0,
    escapeTrigger: Int = 0,
) {
    val modules by viewModel.yangFilteredModules.collectAsStateWithLifecycle()
    val loading by viewModel.yangLoading.collectAsStateWithLifecycle()
    val selected by viewModel.selectedYangModule.collectAsStateWithLifecycle()
    val tree by viewModel.yangTree.collectAsStateWithLifecycle()
    val query by viewModel.yangSearchQuery.collectAsStateWithLifecycle()
    val namespaces by viewModel.yangNamespaces.collectAsStateWithLifecycle()
    val xpath by viewModel.yangXPathBuilder.collectAsStateWithLifecycle()
    var showNamespaces by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (modules.isEmpty()) viewModel.loadYangModules()
    }
    // NETCONF-KEYBOARD-MOUSE FEATURE: Ctrl+F focuses module search (only
    // meaningful in list view); Escape backs out of a module's detail view.
    LaunchedEffect(findTrigger) {
        if (findTrigger > 0 && selected == null) {
            showNamespaces = false
            runCatching { searchFocusRequester.requestFocus() }
        }
    }
    LaunchedEffect(escapeTrigger) {
        if (escapeTrigger > 0 && selected != null) viewModel.backToYangModuleList()
    }

    if (selected == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.netconf_yang_modules_count, modules.size), style = MaterialTheme.typography.titleMedium)
                Row {
                    IconButton(onClick = { showNamespaces = !showNamespaces }) {
                        Icon(Icons.Outlined.AccountTree, contentDescription = stringResource(R.string.netconf_namespace_explorer_icon))
                    }
                    IconButton(onClick = { viewModel.loadYangModules() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.netconf_rediscover))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setYangSearchQuery(it) },
                label = { Text(stringResource(R.string.netconf_search_modules)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            if (showNamespaces) {
                Text(stringResource(R.string.netconf_namespace_explorer), style = MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.weight(1f)) {
                    items(namespaces) { (namespace, moduleName) ->
                        ListItem(
                            headlineContent = { Text(moduleName) },
                            supportingContent = { Text(namespace, style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { Icon(Icons.Outlined.AccountTree, contentDescription = null) },
                        )
                        HorizontalDivider()
                    }
                }
            } else if (modules.isEmpty() && !loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.netconf_no_yang_modules))
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(modules, key = { it.name + (it.revision ?: "") }) { m ->
                        ListItem(
                            modifier = Modifier.clickable { viewModel.selectYangModule(m) },
                            headlineContent = { Text(m.name) },
                            supportingContent = {
                                Column {
                                    Text(m.namespace, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    if (m.revision != null) Text(stringResource(R.string.netconf_revision, m.revision ?: ""), style = MaterialTheme.typography.labelSmall)
                                    if (m.features.isNotEmpty()) Text(stringResource(R.string.netconf_features, m.features.joinToString(", ")), style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            leadingContent = { Icon(Icons.Outlined.Description, contentDescription = null) },
                            trailingContent = {
                                if (!m.schemaFetchable) {
                                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.netconf_metadata_only_no_schema))
                                } else {
                                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
        return
    }

    // ── Selected module: tree view + XPath builder ──
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.backToYangModuleList() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.netconf_back_to_module_list))
                }
                Column {
                    Text(selected!!.name, style = MaterialTheme.typography.titleMedium)
                    Text(selected!!.namespace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = xpath,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.netconf_xpath_label)) },
            trailingIcon = {
                IconButton(onClick = { viewModel.clearXPathBuilder() }) {
                    Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.clear))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (tree == null) {
            Text(stringResource(R.string.netconf_no_schema_source), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(flattenYangTree(tree!!)) { (node, depth) ->
                    YangTreeRow(node, depth) { viewModel.appendToXPathBuilder(node.name) }
                }
            }
        }
    }
}

/** Flattens the recursive [com.systemsgo.hex.netconf.protocol.YangTreeNode] tree into (node, depth) pairs — LazyColumn needs a flat list, not recursive composables, to stay performant on large schemas. */
private fun flattenYangTree(
    root: com.systemsgo.hex.netconf.protocol.YangTreeNode,
    depth: Int = 0,
): List<Pair<com.systemsgo.hex.netconf.protocol.YangTreeNode, Int>> =
    root.children.flatMap { child -> listOf(child to depth) + flattenYangTree(child, depth + 1) }

@Composable
private fun YangTreeRow(
    node: com.systemsgo.hex.netconf.protocol.YangTreeNode,
    depth: Int,
    onClick: () -> Unit,
) {
    val icon = when (node.kind) {
        "container" -> Icons.Outlined.Folder
        "list" -> Icons.Outlined.ViewList
        "leaf" -> Icons.Outlined.Label
        "leaf-list" -> Icons.Outlined.FormatListBulleted
        "choice" -> Icons.Outlined.CallSplit
        "case" -> Icons.Outlined.SubdirectoryArrowRight
        "rpc" -> Icons.Outlined.PlayArrow
        "notification" -> Icons.Outlined.NotificationsActive
        "uses" -> Icons.Outlined.Link
        "augment" -> Icons.Outlined.AddCircleOutline
        else -> Icons.Outlined.Circle
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (depth * 16).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = node.kind, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(node.name, style = MaterialTheme.typography.bodyMedium)
        node.dataType?.let {
            Spacer(Modifier.width(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!node.isConfig) {
            Spacer(Modifier.width(6.dp))
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.netconf_ro_flag), style = MaterialTheme.typography.labelSmall) })
        }
    }
}


@Composable
private fun NetconfWireLogTab(viewModel: NetconfSessionViewModel) {
    val log by viewModel.wireLog.collectAsStateWithLifecycle()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(log.asReversed()) { msg: NetconfWireMessage ->
            MouseContextMenuArea(
                items = listOf(
                    stringResource(R.string.netconf_copy_xml) to { clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.xml)) },
                ),
            ) {
            ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (msg.outbound) "→ sent" else "← received",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (msg.outbound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date(msg.timestampMs)),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        prettyPrintXml(msg.xml).take(2000),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
            }
        }
    }
}

// ── Notifications tab: live NETCONF event stream ──

@Composable
private fun NetconfNotificationsTab(viewModel: NetconfSessionViewModel) {
    val notifications by viewModel.notificationLog.collectAsStateWithLifecycle()
    if (notifications.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.netconf_no_notifications))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        items(notifications.asReversed()) { n: String ->
            ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    prettyPrintXml(n),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}
