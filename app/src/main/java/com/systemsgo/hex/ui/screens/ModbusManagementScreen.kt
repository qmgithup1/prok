package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.modbus.isReadOnly
import com.systemsgo.hex.modbus.protocol.ModbusDataFormat
import com.systemsgo.hex.modbus.protocol.ModbusPoint
import com.systemsgo.hex.modbus.protocol.ModbusRegisterType

private enum class ModbusTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Dashboard),
    BROWSER("Register Tool", Icons.Filled.Build),
    POINTS("Points", Icons.Filled.List),
}

/**
 * MODBUS TCP FEATURE (Part 2/2): the three-tab management UI driven by
 * [ModbusManagementViewModel] — see that class for the
 * [com.systemsgo.hex.modbus.protocol.ModbusTcpClient] wiring. Mirrors
 * SnmpManagementScreen/ProxmoxManagementScreen's Scaffold + bottom-
 * NavigationBar shape.
 */
@Composable
fun ModbusManagementScreen(profile: RdpProfile, viewModel: ModbusManagementViewModel, onFinish: () -> Unit) {
    var tab by remember { mutableStateOf(ModbusTab.DASHBOARD) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(profile.name.ifBlank { profile.host }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${profile.host}:${profile.port} · Unit ${profile.modbusUnitId}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onFinish) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        bottomBar = {
            NavigationBar {
                ModbusTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                ModbusTab.DASHBOARD -> DashboardTab(viewModel)
                ModbusTab.BROWSER -> RegisterToolTab(viewModel)
                ModbusTab.POINTS -> PointsTab(viewModel)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String?) {
    if (message != null) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(message, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

private fun formatValue(value: Any?): String = when (value) {
    is Boolean -> if (value) "ON" else "OFF"
    is Number -> if (value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    null -> "—"
    else -> value.toString()
}

private fun pointDisplayName(point: ModbusPoint): String = point.label.ifBlank { "${point.registerType.label} ${point.address}" }

@Composable
private fun DashboardTab(viewModel: ModbusManagementViewModel) {
    val readings by viewModel.readings.collectAsStateWithLifecycle()
    val points by viewModel.points.collectAsStateWithLifecycle()
    val connectionError by viewModel.connectionError.collectAsStateWithLifecycle()
    val polling by viewModel.polling.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ErrorBanner(connectionError)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live values", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { if (polling) viewModel.stopPolling() else viewModel.resumePolling() }) {
                Text(if (polling) "Pause" else "Resume")
            }
        }
        if (points.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No points yet — add one from the Points tab to start monitoring live values.", modifier = Modifier.padding(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(readings.ifEmpty { points.map { com.systemsgo.hex.ui.screens.ModbusPointReading(it, null, null) } }) { reading ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(pointDisplayName(reading.point), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${reading.point.registerType.label} @ ${reading.point.address}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                reading.error?.let { "Error" } ?: formatValue(reading.value),
                                style = MaterialTheme.typography.titleLarge,
                                color = if (reading.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterToolTab(viewModel: ModbusManagementViewModel) {
    val results by viewModel.toolResults.collectAsStateWithLifecycle()
    val state by viewModel.toolState.collectAsStateWithLifecycle()

    var registerType by remember { mutableStateOf(ModbusRegisterType.HOLDING_REGISTER) }
    var address by remember { mutableStateOf("0") }
    var quantity by remember { mutableStateOf("1") }
    var writeValue by remember { mutableStateOf("") }
    var typeMenuExpanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Register Browser / Tool", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        ExposedDropdownMenuBox(expanded = typeMenuExpanded, onExpandedChange = { typeMenuExpanded = it }) {
            OutlinedTextField(
                value = registerType.label, onValueChange = {}, readOnly = true, label = { Text("Register type") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                ModbusRegisterType.entries.forEach { t ->
                    DropdownMenuItem(text = { Text(t.label) }, onClick = { registerType = t; typeMenuExpanded = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = address, onValueChange = { address = it.filter(Char::isDigit) }, label = { Text("Address") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = quantity, onValueChange = { quantity = it.filter(Char::isDigit) }, label = { Text("Quantity") }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val a = address.toIntOrNull() ?: 0
                val q = (quantity.toIntOrNull() ?: 1).coerceAtLeast(1)
                viewModel.readRaw(registerType, a, q)
            },
            enabled = !state.loading, modifier = Modifier.fillMaxWidth(),
        ) { Text("Read") }

        if (!registerType.isReadOnly) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Write single ${if (registerType == ModbusRegisterType.COIL) "coil" else "register"}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (registerType == ModbusRegisterType.COIL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var coilOn by remember { mutableStateOf(true) }
                    Switch(checked = coilOn, onCheckedChange = { coilOn = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (coilOn) "ON" else "OFF")
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { viewModel.writeCoil(address.toIntOrNull() ?: 0, coilOn) }, enabled = !state.loading) { Text("Write") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = writeValue, onValueChange = { writeValue = it.filter(Char::isDigit) }, label = { Text("Value (0-65535)") }, modifier = Modifier.weight(1f))
                    Button(onClick = { viewModel.writeRegister(address.toIntOrNull() ?: 0, writeValue.toIntOrNull() ?: 0) }, enabled = !state.loading) { Text("Write") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        ErrorBanner(state.error)
        if (state.loading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        results.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("[${row.address}]")
                Text(row.value, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun PointsTab(viewModel: ModbusManagementViewModel) {
    val points by viewModel.points.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Icon(Icons.Filled.Add, contentDescription = "Add point") }
        },
    ) { innerPadding ->
        if (points.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("No dashboard points yet.", modifier = Modifier.padding(32.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp)) {
                items(points) { point ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(pointDisplayName(point), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${point.registerType.label} @ ${point.address}" +
                                        if (!point.registerType.isReadOnly && !point.registerType.bitSized) " · ${point.dataFormat.name}" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.removePoint(point) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddPointDialog(onDismiss = { showAddDialog = false }, onAdd = { viewModel.addPoint(it); showAddDialog = false })
    }
}

@Composable
private fun AddPointDialog(onDismiss: () -> Unit, onAdd: (ModbusPoint) -> Unit) {
    var label by remember { mutableStateOf("") }
    var registerType by remember { mutableStateOf(ModbusRegisterType.HOLDING_REGISTER) }
    var dataFormat by remember { mutableStateOf(ModbusDataFormat.UINT16) }
    var address by remember { mutableStateOf("0") }
    var registerTypeMenu by remember { mutableStateOf(false) }
    var dataFormatMenu by remember { mutableStateOf(false) }

    val isBitSized = registerType.bitSized

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add point") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                ExposedDropdownMenuBox(expanded = registerTypeMenu, onExpandedChange = { registerTypeMenu = it }) {
                    OutlinedTextField(
                        value = registerType.label, onValueChange = {}, readOnly = true, label = { Text("Register type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = registerTypeMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = registerTypeMenu, onDismissRequest = { registerTypeMenu = false }) {
                        ModbusRegisterType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.label) }, onClick = { registerType = t; registerTypeMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = address, onValueChange = { address = it.filter(Char::isDigit) }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
                if (!isBitSized) {
                    Spacer(Modifier.height(8.dp))
                    ExposedDropdownMenuBox(expanded = dataFormatMenu, onExpandedChange = { dataFormatMenu = it }) {
                        OutlinedTextField(
                            value = dataFormat.name, onValueChange = {}, readOnly = true, label = { Text("Data format") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dataFormatMenu) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(expanded = dataFormatMenu, onDismissRequest = { dataFormatMenu = false }) {
                            ModbusDataFormat.entries.forEach { t ->
                                DropdownMenuItem(text = { Text(t.name) }, onClick = { dataFormat = t; dataFormatMenu = false })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAdd(
                    ModbusPoint(
                        registerType = registerType,
                        address = address.toIntOrNull() ?: 0,
                        label = label,
                        dataFormat = if (isBitSized) ModbusDataFormat.UINT16 else dataFormat,
                    )
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
