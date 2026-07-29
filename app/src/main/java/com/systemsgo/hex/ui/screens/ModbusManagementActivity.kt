package com.systemsgo.hex.ui.screens

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.modbus.isReadOnly
import com.systemsgo.hex.modbus.modbusPointList
import com.systemsgo.hex.modbus.toModbusClient
import com.systemsgo.hex.modbus.toModbusPointsColumn
import com.systemsgo.hex.modbus.protocol.ModbusDataFormat
import com.systemsgo.hex.modbus.protocol.ModbusException
import com.systemsgo.hex.modbus.protocol.ModbusPoint
import com.systemsgo.hex.modbus.protocol.ModbusRegisterType
import com.systemsgo.hex.modbus.protocol.ModbusTcpClient
import com.systemsgo.hex.modbus.protocol.decode
import com.systemsgo.hex.ui.theme.SystemsGoTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MODBUS TCP FEATURE (Part 2/2): session Activity for
 * [com.systemsgo.hex.data.model.ProtocolType.MODBUS_TCP] profiles — the
 * Modbus counterpart to [SnmpManagementActivity]/[ProxmoxManagementActivity].
 * Not a [com.systemsgo.hex.remote.RemoteSessionClient] (no framebuffer/
 * terminal), so it's routed here directly by
 * [com.systemsgo.hex.remote.SessionLauncher] rather than through
 * RdpSessionActivity/RemoteSessionFactory, exactly like
 * REDFISH/IPMI/AMT/SNMP/NETCONF/RESTCONF/PROXMOX. Built entirely on the
 * Part-1 protocol engine ([ModbusTcpClient]/`ModbusModels.kt`) — nothing in
 * the `modbus.protocol` package changes here.
 */
@AndroidEntryPoint
class ModbusManagementActivity : AppCompatActivity() {

    private val viewModel: ModbusManagementViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileId = intent.getStringExtra("profile_id") ?: run { finish(); return }
        viewModel.load(profileId)

        // Same App-Lock re-prompt-on-exported-shortcut-launch gating as
        // SnmpManagementActivity/RdpSessionActivity/WebPortalActivity — see
        // SnmpManagementActivity's onCreate for the full rationale.
        val fromShortcut = intent.getBooleanExtra("from_shortcut", false)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            var isUnlocked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            val lockRequired = fromShortcut && (settings.biometricLockEnabled || settings.pinLockEnabled)
            androidx.compose.runtime.LaunchedEffect(lockRequired) {
                if (!lockRequired) isUnlocked = true
            }
            SystemsGoTheme(darkTheme = settings.isDarkMode, themeVariant = settings.themeVariant) {
                val profile by viewModel.profile.collectAsState()
                val p = profile
                Box(Modifier.fillMaxSize()) {
                    if (p == null || !isUnlocked) {
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    } else {
                        ModbusManagementScreen(profile = p, viewModel = viewModel, onFinish = { finish() })
                    }
                    if (lockRequired) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible  = !isUnlocked,
                            enter    = androidx.compose.animation.fadeIn(),
                            exit     = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            AppLockScreen(
                                biometricEnabled = settings.biometricLockEnabled,
                                pinEnabled       = settings.pinLockEnabled,
                                encryptedPin     = settings.pinCode,
                                isUnlocked       = isUnlocked,
                                onUnlocked       = { isUnlocked = true }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.onScreenClosed()
    }
}

/** Loading/error state for a one-shot Modbus operation. */
data class ModbusOpState(val loading: Boolean = false, val error: String? = null)

/** A decoded value + any error for one dashboard [ModbusPoint]. */
data class ModbusPointReading(val point: ModbusPoint, val value: Any?, val error: String?)

/** One row in the Register Tool's raw read-result table. */
data class ModbusRawResultRow(val address: Int, val value: String)

/** Human-readable summary of a [ModbusException] — this screen's counterpart to how SnmpManagementActivity/ProxmoxManagementActivity surface their own client's exceptions. */
private fun ModbusException.describe(): String = when (this) {
    is ModbusException.Protocol -> message ?: "Modbus exception"
    is ModbusException.Timeout -> message ?: "Timed out"
    is ModbusException.Connection -> message ?: "Connection failed"
    is ModbusException.Framing -> message ?: "Malformed response"
    is ModbusException.InvalidArgument -> message ?: "Invalid argument"
}

@HiltViewModel
class ModbusManagementViewModel @Inject constructor(
    private val profileRepository: RdpProfileRepository,
    private val settingsRepository: com.systemsgo.hex.data.repository.AppSettingsRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _profile = MutableStateFlow<RdpProfile?>(null)
    val profile: StateFlow<RdpProfile?> = _profile.asStateFlow()

    val settings: StateFlow<com.systemsgo.hex.data.repository.AppSettings> =
        settingsRepository.settingsFlow.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, settingsRepository.currentSettingsSnapshot()
        )

    private var client: ModbusTcpClient? = null
    private var pollJob: Job? = null

    // ── Dashboard (cyclic poll of saved points) ──
    private val _points = MutableStateFlow<List<ModbusPoint>>(emptyList())
    val points: StateFlow<List<ModbusPoint>> = _points.asStateFlow()
    private val _readings = MutableStateFlow<List<ModbusPointReading>>(emptyList())
    val readings: StateFlow<List<ModbusPointReading>> = _readings.asStateFlow()
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()
    private val _polling = MutableStateFlow(false)
    val polling: StateFlow<Boolean> = _polling.asStateFlow()

    // ── Register Tool (ad-hoc read/write) ──
    private val _toolResults = MutableStateFlow<List<ModbusRawResultRow>>(emptyList())
    val toolResults: StateFlow<List<ModbusRawResultRow>> = _toolResults.asStateFlow()
    private val _toolState = MutableStateFlow(ModbusOpState())
    val toolState: StateFlow<ModbusOpState> = _toolState.asStateFlow()

    fun load(profileId: String) {
        viewModelScope.launch {
            val p = profileRepository.getProfileById(profileId) ?: return@launch
            _profile.value = p
            _points.value = p.modbusPointList()
            client = p.toModbusClient()
            startPolling()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        val p = _profile.value ?: return
        pollJob = viewModelScope.launch {
            _polling.value = true
            while (true) {
                val c = client
                val pts = _points.value
                if (c != null && pts.isNotEmpty()) {
                    val results = pts.map { point -> readPoint(c, point) }
                    _readings.value = results
                    _connectionError.value = results.firstOrNull { it.error != null }
                        ?.takeIf { results.all { r -> r.error != null } }?.error
                }
                delay(p.modbusPollIntervalMs.coerceAtLeast(200).toLong())
            }
        }
    }

    private suspend fun readPoint(client: ModbusTcpClient, point: ModbusPoint): ModbusPointReading = try {
        val value: Any = when (point.registerType) {
            ModbusRegisterType.COIL -> client.readCoils(point.address, 1).values.first()
            ModbusRegisterType.DISCRETE_INPUT -> client.readDiscreteInputs(point.address, 1).values.first()
            ModbusRegisterType.HOLDING_REGISTER -> point.dataFormat.decode(client.readHoldingRegisters(point.address, point.dataFormat.wordCount).values)
            ModbusRegisterType.INPUT_REGISTER -> point.dataFormat.decode(client.readInputRegisters(point.address, point.dataFormat.wordCount).values)
        }
        ModbusPointReading(point, value, null)
    } catch (e: ModbusException) {
        ModbusPointReading(point, null, e.describe())
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        _polling.value = false
    }

    fun resumePolling() {
        if (pollJob == null) startPolling()
    }

    // ── Points (dashboard) management ──
    fun addPoint(point: ModbusPoint) = savePoints(_points.value + point)
    fun removePoint(point: ModbusPoint) = savePoints(_points.value.filterNot { it == point })

    private fun savePoints(updated: List<ModbusPoint>) {
        _points.value = updated
        val p = _profile.value ?: return
        viewModelScope.launch {
            val saved = p.copy(modbusPoints = updated.toModbusPointsColumn())
            profileRepository.updateProfile(saved)
            _profile.value = saved
        }
    }

    // ── Register Tool ──
    fun readRaw(registerType: ModbusRegisterType, startAddress: Int, quantity: Int) {
        val c = client ?: return
        viewModelScope.launch {
            _toolState.value = ModbusOpState(loading = true)
            try {
                val rows: List<ModbusRawResultRow> = when (registerType) {
                    ModbusRegisterType.COIL ->
                        c.readCoils(startAddress, quantity).values.mapIndexed { i, v -> ModbusRawResultRow(startAddress + i, v.toString()) }
                    ModbusRegisterType.DISCRETE_INPUT ->
                        c.readDiscreteInputs(startAddress, quantity).values.mapIndexed { i, v -> ModbusRawResultRow(startAddress + i, v.toString()) }
                    ModbusRegisterType.HOLDING_REGISTER ->
                        c.readHoldingRegisters(startAddress, quantity).values.mapIndexed { i, v -> ModbusRawResultRow(startAddress + i, "$v (0x${v.toString(16).padStart(4, '0')})") }
                    ModbusRegisterType.INPUT_REGISTER ->
                        c.readInputRegisters(startAddress, quantity).values.mapIndexed { i, v -> ModbusRawResultRow(startAddress + i, "$v (0x${v.toString(16).padStart(4, '0')})") }
                }
                _toolResults.value = rows
                _toolState.value = ModbusOpState()
            } catch (e: ModbusException) {
                _toolState.value = ModbusOpState(error = e.describe())
            }
        }
    }

    fun writeCoil(address: Int, value: Boolean) = runWrite { c -> c.writeSingleCoil(address, value) }
    fun writeRegister(address: Int, value: Int) = runWrite { c -> c.writeSingleRegister(address, value) }

    private fun runWrite(op: suspend (ModbusTcpClient) -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            _toolState.value = ModbusOpState(loading = true)
            try {
                op(c)
                _toolState.value = ModbusOpState()
            } catch (e: ModbusException) {
                _toolState.value = ModbusOpState(error = e.describe())
            } catch (e: NumberFormatException) {
                _toolState.value = ModbusOpState(error = "Invalid value for the selected register type")
            }
        }
    }

    fun onScreenClosed() {
        stopPolling()
        client?.close()
    }
}
