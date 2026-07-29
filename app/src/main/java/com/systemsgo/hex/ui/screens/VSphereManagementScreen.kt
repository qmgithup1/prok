package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.vsphere.protocol.VSphereHost
import com.systemsgo.hex.vsphere.protocol.VSpherePowerAction
import com.systemsgo.hex.vsphere.protocol.VSphereVm

/** `POWERED_ON`/`POWERED_OFF`/`SUSPENDED` per [VSphereVm.powerState]'s doc comment — kept local to the screen since nothing else needs it. */
private val VSphereVm.isRunning: Boolean get() = powerState == "POWERED_ON"
private val VSphereHost.isConnected: Boolean get() = connectionState == "CONNECTED"

/**
 * VMWARE-VSPHERE FEATURE (Part 3/N): the host-status-strip + VM-inventory
 * screen driven by [VSphereManagementViewModel] — mirrors
 * [ProxmoxManagementScreen]'s plain Material3 Scaffold shape (no bespoke
 * theme tokens) since this is a REST-polled management dashboard, not a
 * themed onboarding/marketing surface. Unlike Proxmox's node→guest tree,
 * hosts are shown as a compact read-only status row (vSphere's own
 * `GET /vcenter/vm` doesn't say which host a VM is on without a per-VM
 * detail call — see [VSphereManagementViewModel]'s state-class doc
 * comments) and VMs are a flat list below, which also matches how the
 * vSphere Client's own "Virtual Machines" tab is organized.
 */
@Composable
fun VSphereManagementScreen(
    profile: RdpProfile,
    viewModel: VSphereManagementViewModel,
    onFinish: () -> Unit,
    onOpenConsole: (profileId: String) -> Unit,
) {
    val hostsState by viewModel.hosts.collectAsStateWithLifecycle()
    val vmsState by viewModel.vms.collectAsStateWithLifecycle()
    val vmOp by viewModel.vmOp.collectAsStateWithLifecycle()

    val loading = hostsState.loading || vmsState.loading
    val fatalError = vmsState.error?.takeIf { vmsState.vms.isEmpty() }

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
                    IconButton(onClick = { viewModel.refreshAll() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading && vmsState.vms.isEmpty() && hostsState.hosts.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                fatalError != null -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Text(fatalError, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.refreshAll() }) { Text("Retry") }
                    }
                }
                else -> {
                    Column(Modifier.fillMaxSize()) {
                        HostStatusStrip(hostsState.hosts, hostsState.error)
                        Divider()
                        if (vmsState.vms.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No virtual machines visible to this account.", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(vmsState.vms, key = { it.moref }) { vm ->
                                    VmRow(
                                        vm = vm,
                                        busy = vmOp.busyMoref == vm.moref,
                                        onPowerAction = { action -> viewModel.powerAction(vm, action) },
                                        onConsole = {
                                            viewModel.openConsole(vm = vm, onReady = onOpenConsole, onError = { })
                                        },
                                    )
                                    Divider()
                                }
                            }
                        }
                    }
                }
            }
            vmOp.error?.let { message ->
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text(message) }
            }
        }
    }
}

/** Compact, always-visible strip of ESXi host connection/power state — read-only (host power/maintenance-mode actions aren't in scope here, only guest VM control). */
@Composable
private fun HostStatusStrip(hosts: List<VSphereHost>, error: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "Hosts",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(4.dp))
        when {
            error != null -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            hosts.isEmpty() -> Text("No hosts visible to this account.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(hosts, key = { it.moref }) { host -> HostChip(host) }
            }
        }
    }
}

@Composable
private fun HostChip(host: VSphereHost) {
    val dotColor = if (host.isConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
    ElevatedCard {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(dotColor, shape = androidx.compose.foundation.shape.CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(host.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${host.connectionState} · ${host.powerState}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VmRow(
    vm: VSphereVm,
    busy: Boolean,
    onPowerAction: (VSpherePowerAction) -> Unit,
    onConsole: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Computer, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(vm.name.ifBlank { vm.moref }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val subtitle = buildString {
                append(vm.powerState.lowercase().replaceFirstChar { it.uppercase() })
                if (vm.cpuCount > 0) append(" · ${vm.cpuCount} vCPU")
                if (vm.memoryMb > 0) append(" · ${vm.memoryMb / 1024}GB RAM")
                if (vm.guestFullName.isNotBlank()) append(" · ${vm.guestFullName}")
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp))
        } else {
            var menuOpen by remember { mutableStateOf(false) }
            if (vm.isRunning) {
                IconButton(onClick = onConsole) { Icon(Icons.Filled.Monitor, contentDescription = "Console") }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Power actions") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!vm.isRunning) {
                        DropdownMenuItem(text = { Text("Power On") }, onClick = { menuOpen = false; onPowerAction(VSpherePowerAction.START) })
                    } else {
                        DropdownMenuItem(text = { Text("Shut Down Guest") }, onClick = { menuOpen = false; onPowerAction(VSpherePowerAction.SHUTDOWN) })
                        DropdownMenuItem(text = { Text("Restart Guest") }, onClick = { menuOpen = false; onPowerAction(VSpherePowerAction.RESET) })
                        DropdownMenuItem(text = { Text("Suspend") }, onClick = { menuOpen = false; onPowerAction(VSpherePowerAction.SUSPEND) })
                        DropdownMenuItem(text = { Text("Power Off (hard)") }, onClick = { menuOpen = false; onPowerAction(VSpherePowerAction.STOP) })
                    }
                }
            }
        }
    }
}
