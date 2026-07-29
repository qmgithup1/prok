package com.systemsgo.hex.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.systemsgo.hex.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.systemsgo.hex.amt.protocol.AmtBootDevice
import com.systemsgo.hex.amt.protocol.AmtIderMediaType
import com.systemsgo.hex.amt.protocol.AmtIderSessionState
import com.systemsgo.hex.amt.protocol.AmtPowerAction
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.ipmi.protocol.IpmiClient
import com.systemsgo.hex.ipmi.protocol.IpmiPowerAction
import com.systemsgo.hex.ipmi.protocol.IpmiWatchdogAction
import com.systemsgo.hex.ipmi.protocol.IpmiWatchdogUse
import com.systemsgo.hex.redfish.protocol.RedfishResetType
import com.systemsgo.hex.redfish.protocol.RedfishVirtualMedia
import com.systemsgo.hex.ssh.protocol.SshKeyMap
import com.systemsgo.hex.ui.screens.terminal.rememberIncrementalAnsiText

/**
 * REDFISH-IPMI FEATURE: the session UI for [BmcManagementActivity]. Layout
 * is deliberately simple (a scrollable status card + a tab row) rather than
 * a framebuffer/terminal like every other protocol's session screen — a BMC
 * session is fundamentally "look at structured state, occasionally push a
 * button", not a continuous stream.
 */
@Composable
fun BmcManagementScreen(
    profile: RdpProfile,
    viewModel: BmcManagementViewModel,
    onFinish: () -> Unit,
) {
    val connState by viewModel.connState.collectAsStateWithLifecycle()
    val isIpmi = profile.protocolType == ProtocolType.IPMI
    val isAmt = profile.protocolType == ProtocolType.AMT
    val tabPower = stringResource(R.string.bmc_tab_power)
    val tabSensors = stringResource(R.string.bmc_tab_sensors)
    val tabEventLog = stringResource(R.string.bmc_tab_event_log)
    val tabInfo = stringResource(R.string.bmc_tab_info)
    val tabAdmin = stringResource(R.string.bmc_tab_admin)
    val tabConsole = stringResource(R.string.bmc_tab_console)
    val tabBoot = stringResource(R.string.bmc_tab_boot)
    val tabAuditLog = stringResource(R.string.bmc_tab_audit_log)
    val tabKvm = stringResource(R.string.bmc_tab_kvm)
    val tabMedia = stringResource(R.string.bmc_tab_media)
    val tabVirtualMedia = stringResource(R.string.bmc_tab_virtual_media)
    val tabs = when {
        isIpmi -> listOf(tabPower, tabSensors, tabEventLog, tabInfo, tabAdmin, tabConsole)
        isAmt -> listOf(tabPower, tabInfo, tabBoot, tabAuditLog, tabConsole, tabKvm, tabMedia)
        else -> listOf(tabPower, tabSensors, tabEventLog, tabVirtualMedia)
    }
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(profile.name.ifBlank { profile.host }) },
                navigationIcon = {
                    IconButton(onClick = onFinish) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bmc_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        when {
                            isIpmi -> viewModel.ipmiRefresh()
                            isAmt -> viewModel.amtRefresh()
                            else -> viewModel.redfishRefresh()
                        }
                    }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.bmc_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                connState.connecting -> ConnectingState(profile)
                connState.error != null && !connState.connected -> ConnectionErrorState(connState.error!!, onFinish)
                else -> {
                    connState.error?.let { err ->
                        ErrorBanner(err) { viewModel.dismissError() }
                    }
                    TabRow(selectedTabIndex = selectedTab) {
                        tabs.forEachIndexed { i, label ->
                            Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(label) })
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        when {
                            isIpmi -> when (selectedTab) {
                                0 -> IpmiPowerTab(viewModel)
                                1 -> IpmiSensorsTab(viewModel)
                                2 -> IpmiEventLogTab(viewModel)
                                3 -> IpmiInfoTab(viewModel)
                                4 -> IpmiAdminTab(viewModel)
                                5 -> IpmiConsoleTab(viewModel)
                            }
                            isAmt -> when (selectedTab) {
                                0 -> AmtPowerTab(viewModel)
                                1 -> AmtInfoTab(viewModel)
                                2 -> AmtBootTab(viewModel)
                                3 -> AmtAuditLogTab(viewModel)
                                4 -> AmtConsoleTab(viewModel)
                                5 -> AmtKvmTab(viewModel)
                                6 -> AmtMediaTab(viewModel)
                            }
                            else -> when (selectedTab) {
                                0 -> RedfishPowerTab(viewModel)
                                1 -> RedfishSensorsTab(viewModel)
                                2 -> RedfishEventLogTab(viewModel)
                                3 -> RedfishVirtualMediaTab(viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectingState(profile: RdpProfile) {
    Column(
        Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.bmc_connecting_to, profile.host, profile.port))
    }
}

@Composable
private fun ConnectionErrorState(error: String, onFinish: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onFinish) { Text(stringResource(R.string.close)) }
    }
}

@Composable
private fun ErrorBanner(error: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.bmc_dismiss)) }
        }
    }
}

// ── Redfish tabs ─────────────────────────────────────────────────────

@Composable
private fun RedfishPowerTab(viewModel: BmcManagementViewModel) {
    val system by viewModel.system.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val sys = system
        if (sys == null) {
            Text(stringResource(R.string.bmc_no_computer_system))
            return@Column
        }
        StatusCard(
            title = sys.name,
            lines = listOfNotNull(
                stringResource(R.string.bmc_label_power, sys.powerState),
                sys.health?.let { stringResource(R.string.bmc_label_health, it) },
                sys.manufacturer?.let { m -> sys.model?.let { mo -> "$m $mo" } ?: m },
                sys.serialNumber?.let { stringResource(R.string.bmc_label_serial, it) },
                sys.biosVersion?.let { stringResource(R.string.bmc_label_bios, it) },
                sys.processorSummary,
                sys.memorySummaryGiB?.let { stringResource(R.string.bmc_label_memory_gib, "%.0f".format(it)) },
            ),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.bmc_power_actions), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        FlowRowButtons(RedfishResetType.entries.toList()) { action ->
            OutlinedButton(onClick = { viewModel.redfishReset(action) }) { Text(action.label) }
        }
    }
}

@Composable
private fun RedfishSensorsTab(viewModel: BmcManagementViewModel) {
    val sensors by viewModel.sensors.collectAsStateWithLifecycle()
    if (sensors.isEmpty()) {
        EmptyState(stringResource(R.string.bmc_no_sensor_data))
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(sensors) { s ->
            ListItem(
                headlineContent = { Text(s.name) },
                supportingContent = { s.health?.let { Text(it) } },
                trailingContent = { Text(s.reading?.let { "%.1f %s".format(it, s.units ?: "") } ?: "—") },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun RedfishEventLogTab(viewModel: BmcManagementViewModel) {
    val entries by viewModel.redfishLog.collectAsStateWithLifecycle()
    if (entries.isEmpty()) {
        EmptyState(stringResource(R.string.bmc_no_log_entries))
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(entries) { e ->
            ListItem(
                headlineContent = { Text(e.message ?: e.id) },
                supportingContent = { Text(listOfNotNull(e.created, e.severity).joinToString(" · ")) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun RedfishVirtualMediaTab(viewModel: BmcManagementViewModel) {
    val media by viewModel.virtualMedia.collectAsStateWithLifecycle()
    if (media.isEmpty()) {
        EmptyState(stringResource(R.string.bmc_no_virtual_media_slots))
        return
    }
    var imageUrl by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<RedfishVirtualMedia?>(null) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            items(media) { vm ->
                ListItem(
                    headlineContent = { Text(vm.name) },
                    supportingContent = { Text(if (vm.inserted) stringResource(R.string.bmc_media_inserted, vm.image ?: "") else stringResource(R.string.bmc_media_empty, vm.mediaTypes.joinToString())) },
                    trailingContent = {
                        if (vm.inserted) {
                            TextButton(onClick = { viewModel.redfishEjectMedia(vm) }) { Text(stringResource(R.string.bmc_eject)) }
                        } else {
                            TextButton(onClick = { target = vm }) { Text(stringResource(R.string.bmc_mount)) }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
        if (target != null) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedTextField(
                    value = imageUrl, onValueChange = { imageUrl = it },
                    label = { Text(stringResource(R.string.bmc_iso_url_label)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { target = null }) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        target?.let { viewModel.redfishInsertMedia(it, imageUrl) }
                        target = null; imageUrl = ""
                    }, enabled = imageUrl.isNotBlank()) { Text(stringResource(R.string.bmc_mount)) }
                }
            }
        }
    }
}

// ── IPMI tabs ────────────────────────────────────────────────────────

@Composable
private fun IpmiPowerTab(viewModel: BmcManagementViewModel) {
    val status by viewModel.chassisStatus.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val st = status
        if (st == null) {
            Text(stringResource(R.string.bmc_no_chassis_status))
        } else {
            StatusCard(
                title = if (st.powerIsOn) stringResource(R.string.bmc_powered_on) else stringResource(R.string.bmc_powered_off),
                lines = listOfNotNull(
                    "Last power-on cause: ${st.lastPowerOnCause}",
                    if (st.overload) "⚠ Power overload" else null,
                    if (st.fault) "⚠ Power fault" else null,
                    if (st.interlock) "⚠ Chassis interlock open" else null,
                ),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.bmc_power_actions), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        FlowRowButtons(IpmiPowerAction.entries.toList()) { action ->
            OutlinedButton(onClick = { viewModel.ipmiPowerControl(action) }) { Text(action.label) }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.bmc_chassis_identify_led), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Blinks the chassis identify LED — useful for finding one server in a rack full of them.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.ipmiIdentify(15) }) { Text(stringResource(R.string.bmc_identify_15s)) }
            OutlinedButton(onClick = { viewModel.ipmiIdentify(0) }) { Text(stringResource(R.string.bmc_stop)) }
        }
    }
}

@Composable
private fun IpmiSensorsTab(viewModel: BmcManagementViewModel) {
    val sensors by viewModel.ipmiSensors.collectAsStateWithLifecycle()
    val loading by viewModel.ipmiSensorsLoading.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!started) { started = true; viewModel.ipmiLoadSensors() }
    }
    if (loading && sensors.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (sensors.isEmpty()) {
        EmptyState(stringResource(R.string.bmc_no_sensors_found_sdr))
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { viewModel.ipmiLoadSensors() }) { Text(if (loading) stringResource(R.string.bmc_refreshing) else stringResource(R.string.bmc_refresh_sensors)) }
            }
        }
        items(sensors) { sensor ->
            val valueText = when {
                sensor.readingUnavailable -> "unavailable"
                sensor.value != null -> "%.2f %s".format(sensor.value, sensor.unit).trim()
                else -> "raw ${sensor.rawReading}${if (sensor.unit.isNotBlank()) " " + sensor.unit else ""}"
            }
            ListItem(
                headlineContent = { Text(sensor.name) },
                supportingContent = { Text(sensor.sensorTypeLabel) },
                trailingContent = {
                    Text(
                        valueText,
                        color = if (sensor.stateAsserted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun IpmiEventLogTab(viewModel: BmcManagementViewModel) {
    val entries by viewModel.selEntries.collectAsStateWithLifecycle()
    if (entries.isEmpty()) {
        EmptyState(stringResource(R.string.bmc_event_log_empty))
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(entries) { e ->
            ListItem(
                headlineContent = { Text(e.eventDescription) },
                supportingContent = { Text(stringResource(R.string.bmc_record_number, e.recordId)) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun IpmiInfoTab(viewModel: BmcManagementViewModel) {
    val deviceId by viewModel.ipmiDeviceId.collectAsStateWithLifecycle()
    val fru by viewModel.ipmiFru.collectAsStateWithLifecycle()
    if (deviceId == null && fru == null) {
        EmptyState(stringResource(R.string.bmc_no_device_fru_info))
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        deviceId?.let { d ->
            StatusCard(
                title = stringResource(R.string.bmc_firmware),
                lines = listOf(
                    stringResource(R.string.bmc_label_firmware_version, d.firmwareVersion),
                    stringResource(R.string.bmc_label_ipmi_version, d.ipmiVersion),
                    stringResource(R.string.bmc_label_manufacturer_id, d.manufacturerId),
                    stringResource(R.string.bmc_label_product_id, d.productId),
                ),
            )
            Spacer(Modifier.height(16.dp))
        }
        fru?.let { f ->
            StatusCard(
                title = f.productName ?: f.boardProduct ?: stringResource(R.string.bmc_fru_inventory),
                lines = listOfNotNull(
                    f.productManufacturer?.let { stringResource(R.string.bmc_label_manufacturer, it) } ?: f.boardManufacturer?.let { stringResource(R.string.bmc_label_board_manufacturer, it) },
                    f.productPartNumber?.let { stringResource(R.string.bmc_label_part_number, it) } ?: f.boardPartNumber?.let { stringResource(R.string.bmc_label_board_part_number, it) },
                    f.productSerial?.let { stringResource(R.string.bmc_label_serial, it) } ?: f.boardSerial?.let { stringResource(R.string.bmc_label_board_serial, it) },
                    f.productAssetTag?.let { stringResource(R.string.bmc_label_asset_tag, it) },
                    f.chassisType?.let { stringResource(R.string.bmc_label_chassis_type, it) },
                    f.chassisSerial?.let { stringResource(R.string.bmc_label_chassis_serial, it) },
                ),
            )
        }
    }
}

@Composable
private fun IpmiAdminTab(viewModel: BmcManagementViewModel) {
    val message by viewModel.ipmiAdminMessage.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            stringResource(R.string.bmc_admin_settings_warning),
            style = MaterialTheme.typography.bodySmall,
        )
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message ?: "", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    IconButton(onClick = { viewModel.dismissIpmiAdminMessage() }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.bmc_dismiss)) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        IpmiLanConfigSection(viewModel)
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
        IpmiUsersSection(viewModel)
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
        IpmiPefSection(viewModel)
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
        IpmiWatchdogSection(viewModel)
    }
}

@Composable
private fun IpmiLanConfigSection(viewModel: BmcManagementViewModel) {
    val config by viewModel.ipmiLanConfig.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf("") }
    var subnet by remember { mutableStateOf("") }
    var gateway by remember { mutableStateOf("") }

    SectionHeader(stringResource(R.string.bmc_lan_configuration)) { viewModel.ipmiLoadLanConfig() }
    val c = config
    if (c == null) {
        Text(stringResource(R.string.bmc_tap_refresh_to_load), style = MaterialTheme.typography.bodySmall)
        return
    }
    StatusCard(
        title = c.ipAddress,
        lines = listOfNotNull(
            stringResource(R.string.bmc_label_source, c.ipSource),
            stringResource(R.string.bmc_label_subnet_mask, c.subnetMask),
            stringResource(R.string.bmc_label_gateway, c.defaultGateway),
            stringResource(R.string.bmc_label_mac, c.macAddress),
            if (c.vlanEnabled) stringResource(R.string.bmc_label_vlan, c.vlanId) else null,
        ),
    )
    Spacer(Modifier.height(8.dp))
    if (!editing) {
        TextButton(onClick = { editing = true; ip = c.ipAddress; subnet = c.subnetMask; gateway = c.defaultGateway }) {
            Text(stringResource(R.string.bmc_change_static_ip))
        }
    } else {
        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text(stringResource(R.string.bmc_ip_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(value = subnet, onValueChange = { subnet = it }, label = { Text(stringResource(R.string.bmc_subnet_mask)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(value = gateway, onValueChange = { gateway = it }, label = { Text(stringResource(R.string.bmc_default_gateway)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { viewModel.ipmiSetLanStatic(ip, subnet, gateway); editing = false }) { Text(stringResource(R.string.bmc_apply)) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "⚠ Changing the BMC's IP can disconnect this session — reconnect at the new address afterward.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun IpmiUsersSection(viewModel: BmcManagementViewModel) {
    val users by viewModel.ipmiUsers.collectAsStateWithLifecycle()
    val loading by viewModel.ipmiUsersLoading.collectAsStateWithLifecycle()
    var passwordTarget by remember { mutableStateOf<Int?>(null) }
    var passwordInput by remember { mutableStateOf("") }

    SectionHeader(stringResource(R.string.bmc_users_header)) { viewModel.ipmiLoadUsers() }
    if (loading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.bmc_loading_users), style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    if (users.isEmpty()) {
        Text(stringResource(R.string.bmc_tap_refresh_to_load), style = MaterialTheme.typography.bodySmall)
        return
    }
    users.forEach { u ->
        ListItem(
            headlineContent = { Text(u.name.ifBlank { "(user #${u.userId})" }) },
            supportingContent = { Text(stringResource(if (u.enabled) R.string.bmc_privilege_enabled else R.string.bmc_privilege_disabled, u.privilege)) },
            trailingContent = {
                Row {
                    IconButton(onClick = { viewModel.ipmiSetUserEnabled(u.userId, !u.enabled) }) {
                        Icon(
                            if (u.enabled) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                            contentDescription = if (u.enabled) stringResource(R.string.bmc_disable) else stringResource(R.string.bmc_enable),
                        )
                    }
                    IconButton(onClick = { passwordTarget = u.userId; passwordInput = "" }) {
                        Icon(Icons.Outlined.Key, contentDescription = stringResource(R.string.bmc_set_password_desc))
                    }
                }
            },
        )
        FlowRowButtons(IpmiClient.IpmiPrivilege.entries.toList()) { priv ->
            OutlinedButton(
                onClick = { viewModel.ipmiSetUserPrivilege(u, priv) },
                enabled = u.privilege != priv.name.lowercase().replaceFirstChar { it.uppercase() },
            ) { Text(priv.name.lowercase().replaceFirstChar { it.uppercase() }) }
        }
        HorizontalDivider()
    }
    if (passwordTarget != null) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = passwordInput, onValueChange = { passwordInput = it },
            label = { Text(stringResource(R.string.bmc_new_password_for_user, passwordTarget ?: 0)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = { passwordTarget = null }) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.ipmiSetUserPassword(passwordTarget!!, passwordInput); passwordTarget = null },
                enabled = passwordInput.isNotBlank(),
            ) { Text(stringResource(R.string.bmc_set_password)) }
        }
    }
}

@Composable
private fun IpmiPefSection(viewModel: BmcManagementViewModel) {
    val status by viewModel.ipmiPefStatus.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.bmc_pef_section_title)) { viewModel.ipmiLoadPefStatus() }
    val s = status
    if (s == null) {
        Text(stringResource(R.string.bmc_tap_refresh_to_load), style = MaterialTheme.typography.bodySmall)
        return
    }
    if (!s.supported) {
        Text(stringResource(R.string.bmc_pef_not_supported), style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.bmc_pef_enabled_version, s.version), modifier = Modifier.weight(1f))
        Switch(checked = s.pefEnabled, onCheckedChange = { viewModel.ipmiSetPefEnabled(it) })
    }
}

@Composable
private fun IpmiWatchdogSection(viewModel: BmcManagementViewModel) {
    val wd by viewModel.ipmiWatchdog.collectAsStateWithLifecycle()
    var seconds by remember { mutableStateOf("300") }
    var selectedAction by remember { mutableStateOf(IpmiWatchdogAction.HARD_RESET) }

    SectionHeader(stringResource(R.string.bmc_watchdog_timer)) { viewModel.ipmiLoadWatchdog() }
    val w = wd
    if (w == null) {
        Text(stringResource(R.string.bmc_tap_refresh_to_load), style = MaterialTheme.typography.bodySmall)
        return
    }
    StatusCard(
        title = if (w.running) stringResource(R.string.bmc_watchdog_running) else stringResource(R.string.bmc_watchdog_stopped),
        lines = listOf(
            stringResource(R.string.bmc_watchdog_use, w.use.label),
            stringResource(R.string.bmc_watchdog_action_expiration_value, w.action.label),
            stringResource(R.string.bmc_watchdog_countdown_remaining, "%.1f".format(w.presentCountdownSeconds), "%.1f".format(w.initialCountdownSeconds)),
        ),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = seconds, onValueChange = { seconds = it.filter { c -> c.isDigit() } },
        label = { Text(stringResource(R.string.bmc_countdown_seconds)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.bmc_action_on_expiration), style = MaterialTheme.typography.bodySmall)
    FlowRowButtons(IpmiWatchdogAction.entries.toList()) { action ->
        OutlinedButton(onClick = { selectedAction = action }, enabled = selectedAction != action) { Text(action.label) }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val secs = seconds.toIntOrNull() ?: 300
            viewModel.ipmiSetWatchdog(IpmiWatchdogUse.SMS_OS, selectedAction, secs, start = true)
        }) { Text(stringResource(R.string.bmc_configure_start)) }
        OutlinedButton(onClick = { viewModel.ipmiLoadWatchdog() }) { Text(stringResource(R.string.bmc_refresh)) }
    }
}

@Composable
private fun SectionHeader(title: String, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.bmc_load_refresh)) }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun IpmiConsoleTab(viewModel: BmcManagementViewModel) {
    val output by viewModel.solOutput.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val exportEvent by viewModel.solExportEvent.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!started) { started = true; viewModel.openSolConsole() }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.closeSolConsole() }
    }

    SolConsoleBody(
        description = "Serial-over-LAN — BIOS/OS console output. Not all BMCs enable SOL by default.",
        output = output,
        fontSize = settings.terminalFontSize,
        onFontSizeChange = { viewModel.setTerminalFontSize(it) },
        onSendLine = { viewModel.sendSolInput(it) },
        onSendRaw = { viewModel.sendSolRaw(it.toByteArray(Charsets.US_ASCII)) },
        onSendControlByte = { viewModel.sendSolRaw(byteArrayOf(it.toByte())) },
        onSendBreak = { viewModel.sendSolBreak() }, // native IPMI SOL feature — see IpmiSolChannel.sendBreak
        exportFileNamePrefix = "sol_ipmi",
        exportEvent = exportEvent,
        onExportDone = { viewModel.exportSolLog(it) },
        onDismissExportEvent = { viewModel.dismissSolExportEvent() },
    )
}

/**
 * SOL-FEATURE: shared body for [IpmiConsoleTab] and [AmtConsoleTab] — a real
 * ANSI/VT100-rendered terminal (reusing [rememberIncrementalAnsiText], the same
 * incremental parser [com.systemsgo.hex.ui.screens.terminal.TerminalScreen] uses
 * for SSH) instead of a plain scrolling [Text], plus a control-key row for keys
 * with no plain-text representation (reusing [SshKeyMap]'s scan-code→ANSI-escape
 * table — a BIOS's serial console redirection is itself an ANSI/VT100 terminal
 * emulator, so the same escape sequences SSH uses apply here), an optional BREAK
 * button (IPMI SOL only — see [IpmiClient]'s `IpmiSolChannel.sendBreak` doc for
 * why AMT has none), and a save-to-file export of the full session transcript.
 *
 * [onSendBreak] is nullable rather than a no-op lambda specifically so the BREAK
 * chip can be omitted (not just disabled) for AMT, where the underlying protocol
 * genuinely has no such signal — a disabled-but-visible button would misleadingly
 * suggest it's just temporarily unavailable.
 */
@Composable
private fun SolConsoleBody(
    description: String,
    output: String,
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    onSendLine: (String) -> Unit,
    onSendRaw: (String) -> Unit,
    onSendControlByte: (Int) -> Unit,
    onSendBreak: (() -> Unit)?,
    exportFileNamePrefix: String,
    exportEvent: SolExportEvent?,
    onExportDone: (android.net.Uri) -> Unit,
    onDismissExportEvent: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val minFontSize = com.systemsgo.hex.data.repository.AppSettings.MIN_TERMINAL_FONT_SIZE
    val maxFontSize = com.systemsgo.hex.data.repository.AppSettings.MAX_TERMINAL_FONT_SIZE

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) onExportDone(uri) }

    LaunchedEffect(output) {
        // Same "stick to bottom unless the user scrolled up" behaviour as
        // TerminalScreen's SSH console.
        val distanceFromBottom = scrollState.maxValue - scrollState.value
        if (distanceFromBottom < 300 || scrollState.maxValue == 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { onFontSizeChange((fontSize - 1).coerceAtLeast(minFontSize)) },
                enabled = fontSize > minFontSize,
                modifier = Modifier.size(32.dp),
            ) { Icon(Icons.Default.TextDecrease, contentDescription = stringResource(R.string.bmc_smaller_text), modifier = Modifier.size(16.dp)) }
            IconButton(
                onClick = { onFontSizeChange((fontSize + 1).coerceAtMost(maxFontSize)) },
                enabled = fontSize < maxFontSize,
                modifier = Modifier.size(32.dp),
            ) { Icon(Icons.Default.TextIncrease, contentDescription = stringResource(R.string.bmc_larger_text), modifier = Modifier.size(16.dp)) }
            IconButton(
                onClick = {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                    exportLauncher.launch("${exportFileNamePrefix}_$stamp.log")
                },
                modifier = Modifier.size(32.dp),
            ) { Icon(Icons.Default.Download, contentDescription = stringResource(R.string.bmc_save_console_log), modifier = Modifier.size(16.dp)) }
        }
        if (exportEvent != null) {
            Spacer(Modifier.height(4.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val text = when (exportEvent) {
                        is SolExportEvent.Success -> stringResource(R.string.bmc_console_log_saved)
                        is SolExportEvent.Error -> stringResource(R.string.bmc_console_log_save_failed, exportEvent.message)
                    }
                    Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = onDismissExportEvent, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.bmc_dismiss), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            val waitingText = "(waiting for console output…)"
            val ansiText: androidx.compose.ui.text.AnnotatedString = if (output.isBlank()) {
                remember(waitingText) { androidx.compose.ui.text.AnnotatedString(waitingText) }
            } else {
                rememberIncrementalAnsiText(output)
            }
            SelectionContainer {
                Text(
                    text = ansiText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize + 4).sp,
                    modifier = Modifier.padding(8.dp).fillMaxSize().verticalScroll(scrollState),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Control-key row — same reasoning as TerminalScreen's: forced LTR so the
        // arrow keys' fixed physical order doesn't get reshuffled under Arabic/RTL.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { SolKeyChip(stringResource(R.string.bmc_key_esc)) { onSendRaw("\u001B") } }
                item { SolKeyChip(stringResource(R.string.bmc_key_tab)) { onSendRaw("\t") } }
                item { SolKeyChip("Ctrl+C") { onSendControlByte(SshKeyMap.CTRL_C) } }
                item { SolKeyChip("Ctrl+D") { onSendControlByte(SshKeyMap.CTRL_D) } }
                item { SolKeyChip("Ctrl+Z") { onSendControlByte(SshKeyMap.CTRL_Z) } }
                item { SolKeyChip("Ctrl+L") { onSendControlByte(SshKeyMap.CTRL_L) } }
                item { SolKeyChip("↑") { onSendRaw("\u001B[A") } }
                item { SolKeyChip("↓") { onSendRaw("\u001B[B") } }
                item { SolKeyChip("←") { onSendRaw("\u001B[D") } }
                item { SolKeyChip("→") { onSendRaw("\u001B[C") } }
                item { SolKeyChip(stringResource(R.string.bmc_key_home)) { onSendRaw("\u001B[H") } }
                item { SolKeyChip(stringResource(R.string.bmc_key_end)) { onSendRaw("\u001B[F") } }
                items((1..12).toList()) { n -> SolKeyChip("F$n") { onSendRaw(functionKeyAnsiSequence(n)) } }
                if (onSendBreak != null) {
                    item {
                        SolKeyChip(stringResource(R.string.bmc_key_break), accent = true) { onSendBreak() }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text(stringResource(R.string.bmc_type_and_press_enter)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    onSendLine(input); input = ""
                }),
            )
            IconButton(onClick = { onSendLine(input); input = "" }) {
                Icon(Icons.Outlined.Send, contentDescription = stringResource(R.string.bmc_send))
            }
        }
    }
}

/** Maps F1–F12 to the same ANSI/VT100 escape sequences [SshKeyMap] uses for its
 *  scan-code table — duplicated as a direct number→sequence lookup here since
 *  the SOL key row isn't driven by Android key events/scan codes like
 *  [com.systemsgo.hex.ui.screens.ExtraKeysBar] is. */
private fun functionKeyAnsiSequence(n: Int): String = when (n) {
    1 -> "\u001BOP"; 2 -> "\u001BOQ"; 3 -> "\u001BOR"; 4 -> "\u001BOS"
    5 -> "\u001B[15~"; 6 -> "\u001B[17~"; 7 -> "\u001B[18~"; 8 -> "\u001B[19~"
    9 -> "\u001B[20~"; 10 -> "\u001B[21~"; 11 -> "\u001B[23~"; 12 -> "\u001B[24~"
    else -> ""
}

@Composable
private fun SolKeyChip(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = if (accent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = if (accent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ── AMT tabs ──────────────────────────────────────────────────────────
// AMT-VPRO FEATURE: phase 1 UI — power control + a read-only identity
// card. Phase 3 adds AmtConsoleTab (SOL) below, reusing the same output
// flow/layout shape as IpmiConsoleTab. KVM is still a follow-up phase
// (see AMT_VPRO_ROADMAP.md) — a framebuffer view is a different enough UI
// shape from this tab-of-structured-state screen that it'll want its own
// session Activity, the same way every other framebuffer protocol here
// does, rather than a tab in this one.

@Composable
private fun AmtPowerTab(viewModel: BmcManagementViewModel) {
    val status by viewModel.amtPowerStatus.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val st = status
        StatusCard(
            title = st?.label ?: stringResource(R.string.bmc_power_state_unknown),
            lines = listOfNotNull(st?.let { stringResource(R.string.bmc_raw_cim_power_state, it.stateValue) }),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.bmc_power_actions), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.bmc_amt_power_actions_note),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        FlowRowButtons(AmtPowerAction.entries.toList()) { action ->
            OutlinedButton(onClick = { viewModel.amtPowerControl(action) }) { Text(action.label) }
        }
    }
}

@Composable
private fun AmtInfoTab(viewModel: BmcManagementViewModel) {
    val info by viewModel.amtGeneralInfo.collectAsStateWithLifecycle()
    val i = info
    if (i == null) {
        EmptyState(stringResource(R.string.bmc_no_amt_general_settings))
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        StatusCard(
            title = i.hostName ?: stringResource(R.string.bmc_intel_amt),
            lines = listOfNotNull(
                i.amtVersion?.let { stringResource(R.string.bmc_label_firmware, it) },
                stringResource(R.string.bmc_label_network_interface, if (i.networkInterfaceEnabled) stringResource(R.string.bmc_status_enabled) else stringResource(R.string.bmc_status_disabled)),
                i.digestRealm?.let { stringResource(R.string.bmc_label_digest_realm, it) },
            ),
        )
    }
}

@Composable
private fun AmtBootTab(viewModel: BmcManagementViewModel) {
    val message by viewModel.amtBootMessage.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.bmc_one_shot_boot), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Sets the boot device for the next reset only. This doesn't reset the host — use " +
                "the Power tab (Power Cycle / Hard Reset) afterward to apply it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        FlowRowButtons(AmtBootDevice.entries.toList()) { device ->
            OutlinedButton(onClick = { viewModel.amtSetOneShotBoot(device) }) { Text(device.label) }
        }
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message ?: "",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    IconButton(onClick = { viewModel.dismissAmtBootMessage() }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.bmc_dismiss))
                    }
                }
            }
        }
    }
}

/**
 * AMT-VPRO FEATURE phase 5: IDE-R virtual media — mounts a local `.iso`/
 * `.img` on the managed box via [BmcManagementViewModel.amtMountIderMedia],
 * which handles the WS-Man enable, the channel handshake, and the
 * mount-and-serve loop. "Test IDE-R Channel" stays as an optional
 * connectivity-only check ahead of picking a file — mounting opens its own
 * channel on demand if one isn't already open.
 */
@Composable
private fun AmtMediaTab(viewModel: BmcManagementViewModel) {
    val state by viewModel.amtIderState.collectAsStateWithLifecycle()
    val message by viewModel.amtIderMessage.collectAsStateWithLifecycle()
    val mountedFileName by viewModel.amtIderMountedFileName.collectAsStateWithLifecycle()
    val preparing by viewModel.amtIderPreparing.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val defaultMediaName = stringResource(R.string.bmc_ider_default_media_name)

    // Remembers the file just picked (before the person confirms which
    // media type to mount it as) — cleared once amtMountIderMedia is called
    // or the picker is dismissed without a selection.
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var pendingMediaType by remember { mutableStateOf(AmtIderMediaType.CD_ROM) }
    // IDE-R write support (AMT_VPRO_ROADMAP.md phase 5 follow-up): opt-in,
    // and only offered for Floppy — CD/DVD-ROM stays read-only regardless
    // (see AmtIderDiskEmulator's doc comment), so this resets to false
    // whenever the media type switches away from Floppy rather than
    // silently carrying a stale "writable" choice into a CD-ROM mount that
    // would just ignore it.
    var pendingWritable by remember { mutableStateOf(false) }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = run {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                } ?: uri.lastPathSegment ?: defaultMediaName
        }
        pendingUri = uri
        pendingName = name
        pendingMediaType = if (name.substringAfterLast('.', "").lowercase() == "img") {
            AmtIderMediaType.FLOPPY
        } else {
            AmtIderMediaType.CD_ROM
        }
        pendingWritable = false
    }

    val mounting = state == AmtIderSessionState.MEDIA_ACTIVE || mountedFileName != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.bmc_ider_virtual_media), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.bmc_ider_mount_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))

        when {
            mounting -> {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.bmc_label_mounted, mountedFileName ?: stringResource(R.string.bmc_generic_image)),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { viewModel.amtUnmountIderMedia() }) { Text(stringResource(R.string.bmc_unmount)) }
                    }
                }
            }
            preparing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bmc_preparing_image))
                }
            }
            pendingUri != null -> {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(pendingName, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AmtIderMediaType.entries.forEach { type ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(end = 12.dp),
                                ) {
                                    RadioButton(
                                        selected = pendingMediaType == type,
                                        onClick = {
                                            pendingMediaType = type
                                            // CD/DVD-ROM is always read-only
                                            // (see AmtIderDiskEmulator's doc
                                            // comment) — switching to it
                                            // clears any writable choice
                                            // made while Floppy was selected
                                            // rather than leaving a toggle
                                            // set that this mount will just
                                            // ignore.
                                            if (type != AmtIderMediaType.FLOPPY) pendingWritable = false
                                        },
                                    )
                                    Text(type.label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        // IDE-R write support: opt-in, and only meaningful
                        // for Floppy — real optical media (CD/DVD-ROM) was
                        // never writable to begin with, so the toggle isn't
                        // shown at all once a CD-ROM mount is selected
                        // rather than showing it disabled/misleading.
                        if (pendingMediaType == AmtIderMediaType.FLOPPY) {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = pendingWritable, onCheckedChange = { pendingWritable = it })
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.bmc_ider_writable), style = MaterialTheme.typography.bodySmall)
                            }
                            if (pendingWritable) {
                                Text(
                                    stringResource(R.string.bmc_ider_writable_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = {
                                val uri = pendingUri ?: return@Button
                                viewModel.amtMountIderMedia(uri, pendingName, pendingMediaType, pendingWritable)
                                pendingUri = null
                            }) { Text(stringResource(R.string.bmc_mount)) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { pendingUri = null }) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                }
            }
            else -> {
                Button(onClick = { pickImageLauncher.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.bmc_choose_image))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row {
            Button(
                onClick = { viewModel.amtOpenIderDiagnostic() },
                enabled = state != AmtIderSessionState.CONNECTING && !mounting,
            ) { Text(stringResource(R.string.bmc_test_ider_channel)) }
            Spacer(Modifier.width(8.dp))
            if (state == AmtIderSessionState.CHANNEL_OPEN) {
                OutlinedButton(onClick = { viewModel.amtCloseIderDiagnostic() }) { Text(stringResource(R.string.close)) }
            }
        }
        when (state) {
            AmtIderSessionState.CONNECTING -> {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bmc_opening_ider_channel))
                }
            }
            AmtIderSessionState.CHANNEL_OPEN -> {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.bmc_channel_open), color = MaterialTheme.colorScheme.primary)
            }
            else -> Unit
        }
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(message ?: "", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        AmtAccessLogSection(viewModel)
    }
}

/** `AMT_RedirectionService.AccessLog` — a real, verified WS-Man Get (see
 *  [com.systemsgo.hex.amt.protocol.AmtClient.getRedirectionAccessLog]'s
 *  doc comment) listing every past IDE-R/SOL session's date/time/IP:Port.
 *  Lives on the Media tab since it's specifically the *redirection*
 *  session log, not the general [AmtAuditLogTab] security audit log. */
@Composable
private fun AmtAccessLogSection(viewModel: BmcManagementViewModel) {
    val entries by viewModel.amtAccessLog.collectAsStateWithLifecycle()
    val loading by viewModel.amtAccessLogLoading.collectAsStateWithLifecycle()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.bmc_ider_sol_access_log), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp))
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Every past Storage Redirection or SOL session AMT has logged, with the connecting console's date, time, and IP:Port.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { viewModel.amtLoadAccessLog() }) { Text(stringResource(R.string.bmc_load_access_log)) }
    Spacer(Modifier.height(8.dp))
    if (!loading && entries.isEmpty()) {
        Text(stringResource(R.string.bmc_no_access_log_entries), style = MaterialTheme.typography.bodySmall)
    }
    entries.forEach { e ->
        ListItem(
            headlineContent = { Text(e.ipPort ?: e.raw) },
            supportingContent = {
                Text(listOfNotNull(e.date, e.time).joinToString(" · ").ifEmpty { e.raw })
            },
        )
        HorizontalDivider()
    }
}

@Composable
private fun AmtAuditLogTab(viewModel: BmcManagementViewModel) {
    val entries by viewModel.amtAuditLog.collectAsStateWithLifecycle()
    val loading by viewModel.amtAuditLogLoading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.amtLoadAuditLog() }

    if (loading && entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (entries.isEmpty()) {
        EmptyState(stringResource(R.string.bmc_no_audit_log_entries))
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(entries) { e ->
            ListItem(
                headlineContent = { Text(stringResource(R.string.bmc_audit_app_event, e.auditAppName, e.eventId)) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            e.timestampEpochSeconds?.let {
                                java.text.DateFormat.getDateTimeInstance()
                                    .format(java.util.Date(it * 1000))
                            },
                            e.initiator,
                            e.netAddress,
                        ).joinToString(" · "),
                    )
                },
            )
            HorizontalDivider()
        }
    }
}

/** AMT_VPRO FEATURE phase 3: SOL console — the same UI shape as
 *  [IpmiConsoleTab] (a scroll-back output pane + single-line input), just
 *  driven by [BmcManagementViewModel.amtOpenSolConsole]/`amtSendSolInput`
 *  instead of the IPMI equivalents, since it's backed by a different wire
 *  protocol ([com.systemsgo.hex.amt.protocol.AmtSolSession]) under the hood. */
@Composable
private fun AmtConsoleTab(viewModel: BmcManagementViewModel) {
    val output by viewModel.solOutput.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val exportEvent by viewModel.solExportEvent.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!started) { started = true; viewModel.amtOpenSolConsole() }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.amtCloseSolConsole() }
    }

    SolConsoleBody(
        description = "Serial-over-LAN — BIOS/OS console output. Requires SOL enabled on the AMT device (MEBx or AMT_RedirectionService) and this user to have Redirection-realm access.",
        output = output,
        fontSize = settings.terminalFontSize,
        onFontSizeChange = { viewModel.setTerminalFontSize(it) },
        onSendLine = { viewModel.amtSendSolInput(it) },
        onSendRaw = { viewModel.amtSendSolRaw(it.toByteArray(Charsets.US_ASCII)) },
        onSendControlByte = { viewModel.amtSendSolRaw(byteArrayOf(it.toByte())) },
        onSendBreak = null, // AMT's APF SOL channel has no BREAK control bit — see AmtSolSession's doc comment
        exportFileNamePrefix = "sol_amt",
        exportEvent = exportEvent,
        onExportDone = { viewModel.exportSolLog(it) },
        onDismissExportEvent = { viewModel.dismissSolExportEvent() },
    )
}

/** AMT_VPRO FEATURE phase 4 (KVM UI polish): KVM — renders the live
 *  framebuffer ([BmcManagementViewModel.amtKvmFrame], assembled from
 *  [com.systemsgo.hex.amt.protocol.AmtKvmSession]'s decoded rectangles) and
 *  maps touch input to [BmcManagementViewModel.amtSendKvmPointer]/
 *  [BmcManagementViewModel.amtSendKvmKey] events.
 *
 *  Originally tap-to-click only (see AMT_VPRO_ROADMAP's phase 4 entry); this
 *  now also supports click-drag, two-finger scroll, and an on-screen
 *  keyboard — the input-*sending* side (`AmtKvmSession.sendPointerEvent`/
 *  `sendKeyEvent`) was already complete, this was purely a UI gap. Decode
 *  side is unchanged (still Raw + CopyRect only, per that same roadmap
 *  entry — a separate, unrelated scope boundary). */
@Composable
private fun AmtKvmTab(viewModel: BmcManagementViewModel) {
    val frame by viewModel.amtKvmFrame.collectAsStateWithLifecycle()
    val connecting by viewModel.amtKvmConnecting.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(false) }
    var keyboardVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!started) { started = true; viewModel.amtOpenKvmConsole() }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.amtCloseKvmConsole() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.bmc_drag_click_drag_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { keyboardVisible = !keyboardVisible }) {
                Icon(Icons.Outlined.Keyboard, contentDescription = stringResource(R.string.bmc_toggle_keyboard))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
            val bmp = frame
            when {
                bmp != null -> {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.bmc_remote_screen),
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(bmp.width, bmp.height) {
                                // Map a Compose-space offset (this Composable's
                                // on-screen size, which the Image draws scaled-
                                // to-fit) back to framebuffer pixel coordinates.
                                fun toFbCoords(offset: androidx.compose.ui.geometry.Offset): Pair<Int, Int> {
                                    val scaleX = bmp.width.toFloat() / size.width
                                    val scaleY = bmp.height.toFloat() / size.height
                                    val x = (offset.x * scaleX).toInt().coerceIn(0, bmp.width - 1)
                                    val y = (offset.y * scaleY).toInt().coerceIn(0, bmp.height - 1)
                                    return x to y
                                }
                                val touchSlop = viewConfiguration.touchSlop
                                // One awaitEachGesture loop classifies each
                                // gesture as it unfolds, rather than three
                                // competing detectXGestures() calls fighting
                                // over who consumes the initial touch slop:
                                //  - lift with no meaningful movement -> click
                                //  - single-finger move past touch slop  -> the
                                //    RFB button goes down at that point and
                                //    stays down until lift (click-drag)
                                //  - a second finger appearing at any point
                                //    -> switches to two-finger scroll mode
                                //    (natural-scroll convention: fingers move
                                //    up => wheel-down notches, fingers move
                                //    down => wheel-up notches), same wheel-bit
                                //    press-then-release shape
                                //    VncClient.sendMouseScroll uses, since
                                //    AmtKvmSession speaks the same RFB
                                //    pointer-event wire format.
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var (fx, fy) = toFbCoords(down.position)
                                    var dragging = false
                                    var scrolling = false
                                    var scrollAccum = 0f
                                    var totalMovement = 0f
                                    val scrollNotchPx = 60f // tuned for touch, not precision

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val active = event.changes.filter { it.pressed }

                                        if (active.isEmpty()) {
                                            if (dragging) {
                                                viewModel.amtSendKvmPointer(fx, fy, 0) // release the held button
                                            } else if (!scrolling) {
                                                viewModel.amtSendKvmPointer(fx, fy, 1) // plain tap = click
                                                viewModel.amtSendKvmPointer(fx, fy, 0)
                                            }
                                            break
                                        }

                                        if (active.size >= 2) {
                                            if (dragging) {
                                                // A second finger landed mid-drag — release the
                                                // held button so it's never left stuck down.
                                                viewModel.amtSendKvmPointer(fx, fy, 0)
                                                dragging = false
                                            }
                                            scrolling = true
                                            val primary = active.first()
                                            scrollAccum += primary.position.y - primary.previousPosition.y
                                            while (scrollAccum <= -scrollNotchPx) {
                                                viewModel.amtSendKvmPointer(fx, fy, 1 shl 4) // wheel down
                                                viewModel.amtSendKvmPointer(fx, fy, 0)
                                                scrollAccum += scrollNotchPx
                                            }
                                            while (scrollAccum >= scrollNotchPx) {
                                                viewModel.amtSendKvmPointer(fx, fy, 1 shl 3) // wheel up
                                                viewModel.amtSendKvmPointer(fx, fy, 0)
                                                scrollAccum -= scrollNotchPx
                                            }
                                        } else {
                                            val primary = active.first()
                                            val (nx, ny) = toFbCoords(primary.position)
                                            fx = nx; fy = ny
                                            if (!dragging && !scrolling) {
                                                val dx = primary.position.x - primary.previousPosition.x
                                                val dy = primary.position.y - primary.previousPosition.y
                                                totalMovement += kotlin.math.sqrt(dx * dx + dy * dy)
                                                if (totalMovement > touchSlop) {
                                                    dragging = true
                                                    viewModel.amtSendKvmPointer(fx, fy, 1) // button down — click-drag starts
                                                }
                                            } else if (dragging) {
                                                viewModel.amtSendKvmPointer(fx, fy, 1) // move while held
                                            }
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                            },
                    )
                }
                connecting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> EmptyState("KVM — waiting for the first frame. Requires KVM enabled on the AMT device and this user to have Redirection-realm access.")
            }
        }
        AnimatedVisibility(visible = keyboardVisible) {
            AmtKvmKeyboardBar(onKeysym = { keysym, down -> viewModel.amtSendKvmKey(keysym, down) })
        }
    }
}



/** AMT_VPRO FEATURE phase 4 (KVM UI polish): compact on-screen keyboard for
 *  [AmtKvmTab]. [com.systemsgo.hex.amt.protocol.AmtKvmSession] is plain RFB
 *  (RFC 6143) exactly like [com.systemsgo.hex.vnc.protocol.VncClient]'s own
 *  session, so it takes the exact same X11 keysym space — this reuses
 *  [VncClient]'s XK_* constants and RFB §5.4 Unicode-keysym mapping (see
 *  [VncClient.sendText]'s doc comment) rather than re-deriving them.
 *
 *  Two input paths:
 *   - a text field that exists only to summon the system IME and capture
 *     typed/composed text, forwarded a character at a time via [onKeysym]
 *     press+release pairs (so autocomplete, Arabic/CJK composition, etc.
 *     all work, not just a literal fixed key row) and then cleared — it is
 *     a capture surface, never itself a record of remote state;
 *   - a row of special keys (Esc/Tab/arrows/Enter/Backspace) and momentary-
 *     hold Ctrl/Alt toggles (tap once = held down for the next key,
 *     matching how a physical modifier key is used — tap again to
 *     release) that have no printable-character keysym and so can't go
 *     through the IME text path at all.
 */
@Composable
private fun AmtKvmKeyboardBar(onKeysym: (keysym: Int, down: Boolean) -> Unit) {
    var typed by remember { mutableStateOf("") }
    var ctrlHeld by remember { mutableStateOf(false) }
    var altHeld by remember { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    fun tapKey(keysym: Int) {
        onKeysym(keysym, true)
        onKeysym(keysym, false)
    }

    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = ctrlHeld, onClick = {
                    ctrlHeld = !ctrlHeld
                    onKeysym(com.systemsgo.hex.vnc.protocol.VncClient.XK_CONTROL_L, ctrlHeld)
                }, label = { Text(stringResource(R.string.bmc_key_ctrl)) })
                FilterChip(selected = altHeld, onClick = {
                    altHeld = !altHeld
                    onKeysym(com.systemsgo.hex.vnc.protocol.VncClient.XK_ALT_L, altHeld)
                }, label = { Text(stringResource(R.string.bmc_key_alt)) })
                AssistChip(onClick = { tapKey(com.systemsgo.hex.vnc.protocol.VncClient.XK_ESCAPE) }, label = { Text(stringResource(R.string.bmc_key_esc)) })
                AssistChip(onClick = { tapKey(com.systemsgo.hex.vnc.protocol.VncClient.XK_TAB) }, label = { Text(stringResource(R.string.bmc_key_tab)) })
                AssistChip(onClick = { tapKey(com.systemsgo.hex.vnc.protocol.VncClient.XK_BACKSPACE) }, label = { Text("⌫") })
                AssistChip(onClick = { tapKey(com.systemsgo.hex.vnc.protocol.VncClient.XK_RETURN) }, label = { Text("⏎") })
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "←" to com.systemsgo.hex.vnc.protocol.VncClient.XK_LEFT,
                    "↑" to com.systemsgo.hex.vnc.protocol.VncClient.XK_UP,
                    "↓" to com.systemsgo.hex.vnc.protocol.VncClient.XK_DOWN,
                    "→" to com.systemsgo.hex.vnc.protocol.VncClient.XK_RIGHT,
                ).forEach { (label, keysym) ->
                    AssistChip(onClick = { tapKey(keysym) }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { newValue ->
                    // Diff-based: only the newly appended/composed characters get
                    // forwarded, one RFB key event per character, same shape as
                    // VncClient.sendText. Always reset to "" right after — this
                    // field never displays remote state, only captures IME input.
                    if (newValue.length > typed.length) {
                        newValue.substring(typed.length).forEach { ch ->
                            val keysym = if (ch.code in 0x0020..0x00FF) ch.code else (0x01000000 or ch.code)
                            tapKey(keysym)
                        }
                    }
                    typed = ""
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.bmc_type_here_key_by_key)) },
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
    }
}

@Composable
private fun StatusCard(title: String, lines: List<String>) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun <T> FlowRowButtons(items: List<T>, content: @Composable (T) -> Unit) {
    // Simple 2-per-row grid instead of a real FlowRow (avoids pulling in the
    // experimental foundation.layout.FlowRow API purely for a handful of buttons).
    items.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { item -> Box(Modifier.weight(1f)) { content(item) } }
        }
    }
}
