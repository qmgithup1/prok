package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.proxmox.protocol.ProxmoxGuest
import com.systemsgo.hex.proxmox.protocol.ProxmoxGuestType
import com.systemsgo.hex.proxmox.protocol.ProxmoxPowerAction

/**
 * PROXMOX-API FEATURE (Part 3/N): the node/guest inventory screen driven by
 * [ProxmoxManagementViewModel] — mirrors ModbusManagementScreen/
 * SnmpManagementScreen's plain Material3 Scaffold shape (no bespoke theme
 * tokens) since this is a REST-polled management dashboard, not a themed
 * onboarding/marketing surface.
 */
@Composable
fun ProxmoxManagementScreen(
    profile: RdpProfile,
    viewModel: ProxmoxManagementViewModel,
    onFinish: () -> Unit,
    onOpenConsole: (profileId: String) -> Unit,
) {
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val loadingNodes by viewModel.loadingNodes.collectAsStateWithLifecycle()
    val nodesError by viewModel.nodesError.collectAsStateWithLifecycle()
    val guestOp by viewModel.guestOp.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(profile.name.ifBlank { profile.host }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onFinish) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { viewModel.refreshNodes() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loadingNodes && nodes.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                nodesError != null && nodes.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Text(nodesError ?: "", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.refreshNodes() }) { Text("Retry") }
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(nodes, key = { it.node.node }) { nodeState ->
                            NodeRow(
                                nodeState = nodeState,
                                busyVmid = guestOp.busyVmid,
                                onToggle = { viewModel.toggleNode(nodeState.node.node) },
                                onPowerAction = { guest, action -> viewModel.powerAction(guest, action) },
                                onConsole = { guest ->
                                    viewModel.openConsole(
                                        guest = guest,
                                        onReady = onOpenConsole,
                                        onError = { },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            guestOp.error?.let { message ->
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text(message) }
            }
        }
    }
}

@Composable
private fun NodeRow(
    nodeState: ProxmoxNodeState,
    busyVmid: Int?,
    onToggle: () -> Unit,
    onPowerAction: (ProxmoxGuest, ProxmoxPowerAction) -> Unit,
    onConsole: (ProxmoxGuest) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Dns, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(nodeState.node.node, fontWeight = FontWeight.Bold)
                Text(
                    "${nodeState.node.status} · ${(nodeState.node.cpuFraction * 100).toInt()}% CPU · " +
                        "${nodeState.node.memUsedBytes / (1024 * 1024)}MB / ${nodeState.node.memMaxBytes / (1024 * 1024)}MB",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(if (nodeState.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (nodeState.expanded) {
            when {
                nodeState.loadingGuests -> Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
                nodeState.error != null -> Text(nodeState.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                nodeState.guests.isEmpty() -> Text("No VMs or containers on this node.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                else -> nodeState.guests.forEach { guest ->
                    GuestRow(
                        guest = guest,
                        busy = busyVmid == guest.vmid,
                        onPowerAction = { action -> onPowerAction(guest, action) },
                        onConsole = { onConsole(guest) },
                    )
                }
            }
        }
        Divider()
    }
}

@Composable
private fun GuestRow(
    guest: ProxmoxGuest,
    busy: Boolean,
    onPowerAction: (ProxmoxPowerAction) -> Unit,
    onConsole: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (guest.type == ProxmoxGuestType.QEMU) Icons.Filled.Computer else Icons.Filled.ViewInAr,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${guest.name.ifBlank { "#${guest.vmid}" }} (${guest.vmid})", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${guest.status}" + if (guest.template) " · template" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (guest.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp))
        } else {
            var menuOpen by remember { mutableStateOf(false) }
            if (guest.isRunning && guest.type == ProxmoxGuestType.QEMU) {
                IconButton(onClick = onConsole) { Icon(Icons.Filled.Monitor, contentDescription = "Console") }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Power actions") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!guest.isRunning) {
                        DropdownMenuItem(text = { Text("Start") }, onClick = { menuOpen = false; onPowerAction(ProxmoxPowerAction.START) })
                    } else {
                        DropdownMenuItem(text = { Text("Shutdown") }, onClick = { menuOpen = false; onPowerAction(ProxmoxPowerAction.SHUTDOWN) })
                        DropdownMenuItem(text = { Text("Reboot") }, onClick = { menuOpen = false; onPowerAction(ProxmoxPowerAction.REBOOT) })
                        DropdownMenuItem(text = { Text("Stop (force)") }, onClick = { menuOpen = false; onPowerAction(ProxmoxPowerAction.STOP) })
                        if (guest.type == ProxmoxGuestType.QEMU) {
                            DropdownMenuItem(text = { Text("Suspend") }, onClick = { menuOpen = false; onPowerAction(ProxmoxPowerAction.SUSPEND) })
                        }
                    }
                }
            }
        }
    }
}
