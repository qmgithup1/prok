package com.systemsgo.hex.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.systemsgo.hex.R
import com.systemsgo.hex.amt.protocol.AmtAuditLogEntry
import com.systemsgo.hex.amt.protocol.AmtRedirectionAccessLogEntry
import com.systemsgo.hex.amt.protocol.AmtBootDevice
import com.systemsgo.hex.amt.protocol.AmtClient
import com.systemsgo.hex.amt.protocol.AmtRedirectionTransport
import com.systemsgo.hex.amt.protocol.CiraRelayTransport
import com.systemsgo.hex.amt.protocol.AmtException
import com.systemsgo.hex.amt.protocol.AmtGeneralInfo
import com.systemsgo.hex.amt.protocol.AmtIderMediaType
import com.systemsgo.hex.amt.protocol.AmtIderSession
import com.systemsgo.hex.amt.protocol.AmtIderSessionState
import com.systemsgo.hex.amt.protocol.AmtKvmRect
import com.systemsgo.hex.amt.protocol.AmtKvmSession
import com.systemsgo.hex.amt.protocol.AmtPowerAction
import com.systemsgo.hex.amt.protocol.AmtPowerStatus
import com.systemsgo.hex.amt.protocol.AmtSolSession
import com.systemsgo.hex.data.model.ConnectionLog
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.ConnectionLogRepository
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.ipmi.protocol.IpmiChassisStatus
import com.systemsgo.hex.ipmi.protocol.IpmiClient
import com.systemsgo.hex.ipmi.protocol.IpmiDeviceId
import com.systemsgo.hex.ipmi.protocol.IpmiException
import com.systemsgo.hex.ipmi.protocol.IpmiFruInfo
import com.systemsgo.hex.ipmi.protocol.IpmiLanConfig
import com.systemsgo.hex.ipmi.protocol.IpmiPefStatus
import com.systemsgo.hex.ipmi.protocol.IpmiPowerAction
import com.systemsgo.hex.ipmi.protocol.IpmiSelEntry
import com.systemsgo.hex.ipmi.protocol.IpmiSensor
import com.systemsgo.hex.ipmi.protocol.IpmiSolChannel
import com.systemsgo.hex.ipmi.protocol.IpmiUserAccount
import com.systemsgo.hex.ipmi.protocol.IpmiWatchdogAction
import com.systemsgo.hex.ipmi.protocol.IpmiWatchdogConfig
import com.systemsgo.hex.ipmi.protocol.IpmiWatchdogUse
import com.systemsgo.hex.redfish.protocol.RedfishClient
import com.systemsgo.hex.redfish.protocol.RedfishException
import com.systemsgo.hex.redfish.protocol.RedfishLogEntry
import com.systemsgo.hex.redfish.protocol.RedfishResetType
import com.systemsgo.hex.redfish.protocol.RedfishSensorReading
import com.systemsgo.hex.redfish.protocol.RedfishSystemSummary
import com.systemsgo.hex.redfish.protocol.RedfishVirtualMedia
import com.systemsgo.hex.ui.theme.SystemsGoTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * REDFISH-IPMI FEATURE (extended by AMT-VPRO FEATURE): session Activity for
 * [ProtocolType.REDFISH], [ProtocolType.IPMI], and [ProtocolType.AMT]
 * profiles — the BMC/out-of-band-management counterpart to
 * [WebPortalActivity]/[RdpSessionActivity]. Separate Activity for the same
 * reason WEB got its own: none of the three is a
 * [com.systemsgo.hex.remote.RemoteSessionClient] (no framebuffer), and
 * unlike WEB this isn't a WebView either — it's structured data (power
 * state, sensors, event log) rendered as native Compose UI, driven by
 * [RedfishClient], [IpmiClient], or [AmtClient] depending on
 * [RdpProfile.protocolType].
 *
 * Launched only via [com.systemsgo.hex.remote.SessionLauncher] with a
 * "profile_id" extra, same trust boundary as every other session Activity.
 */
@AndroidEntryPoint
class BmcManagementActivity : AppCompatActivity() {

    private val viewModel: BmcManagementViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileId = intent.getStringExtra("profile_id") ?: run { finish(); return }
        viewModel.load(profileId)

        // APP-LOCK-EXPORT FIX: this Activity is android:exported="true" (so pinned
        // home-screen shortcuts can start it directly from the Launcher process —
        // see ShortcutHelper.kt), which also means any other app on the device can
        // send an explicit Intent carrying a "profile_id" extra and land here
        // directly, skipping MainActivity's own App Lock screen entirely. This used
        // to connect immediately in every case, handing over this profile's saved
        // BMC/Redfish/IPMI/AMT credentials with no PIN/biometric prompt at all. Now
        // gated with the same lockRequired/isUnlocked/AppLockScreen pattern already
        // used by RdpSessionActivity and WebPortalActivity: a normal in-app tap
        // already passed through MainActivity's own lock screen, so only the
        // shortcut/external-intent path re-prompts here.
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
                        // Still loading the profile, or waiting on App Lock — either
                        // way nothing sensitive (power state, sensors, credentials)
                        // is visible underneath yet.
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    } else {
                        BmcManagementScreen(profile = p, viewModel = viewModel, onFinish = { finish() })
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
        viewModel.disconnect()
    }
}

data class BmcConnectionState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
)

/** SOL-FEATURE: result of [BmcManagementViewModel.exportSolLog], surfaced as a
 *  dismissible inline message the same way [dismissAmtBootMessage] already
 *  works for the Boot tab. */
sealed class SolExportEvent {
    data object Success : SolExportEvent()
    data class Error(val message: String) : SolExportEvent()
}

@HiltViewModel
class BmcManagementViewModel @Inject constructor(
    private val profileRepository: RdpProfileRepository,
    private val logRepository: ConnectionLogRepository,
    private val settingsRepository: com.systemsgo.hex.data.repository.AppSettingsRepository,
    // SOL-FEATURE: only needed for exportSolLog()'s contentResolver.openOutputStream(uri) —
    // the SAF write itself needs an application Context, same as every other
    // Uri-based export in the app (see MainViewModel.exportConnections' ConnectionBackupManager).
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _profile = MutableStateFlow<RdpProfile?>(null)
    val profile: StateFlow<RdpProfile?> = _profile.asStateFlow()

    val settings: StateFlow<com.systemsgo.hex.data.repository.AppSettings> =
        settingsRepository.settingsFlow.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, settingsRepository.currentSettingsSnapshot()
        )

    private val _connState = MutableStateFlow(BmcConnectionState())
    val connState: StateFlow<BmcConnectionState> = _connState.asStateFlow()

    private val _chassisStatus = MutableStateFlow<IpmiChassisStatus?>(null)
    val chassisStatus: StateFlow<IpmiChassisStatus?> = _chassisStatus.asStateFlow()

    private val _system = MutableStateFlow<RedfishSystemSummary?>(null)
    val system: StateFlow<RedfishSystemSummary?> = _system.asStateFlow()

    private val _sensors = MutableStateFlow<List<RedfishSensorReading>>(emptyList())
    val sensors: StateFlow<List<RedfishSensorReading>> = _sensors.asStateFlow()

    private val _redfishLog = MutableStateFlow<List<RedfishLogEntry>>(emptyList())
    val redfishLog: StateFlow<List<RedfishLogEntry>> = _redfishLog.asStateFlow()

    private val _selEntries = MutableStateFlow<List<IpmiSelEntry>>(emptyList())
    val selEntries: StateFlow<List<IpmiSelEntry>> = _selEntries.asStateFlow()

    private val _ipmiDeviceId = MutableStateFlow<IpmiDeviceId?>(null)
    val ipmiDeviceId: StateFlow<IpmiDeviceId?> = _ipmiDeviceId.asStateFlow()

    private val _ipmiFru = MutableStateFlow<IpmiFruInfo?>(null)
    val ipmiFru: StateFlow<IpmiFruInfo?> = _ipmiFru.asStateFlow()

    private val _ipmiSensors = MutableStateFlow<List<IpmiSensor>>(emptyList())
    val ipmiSensors: StateFlow<List<IpmiSensor>> = _ipmiSensors.asStateFlow()

    private val _ipmiSensorsLoading = MutableStateFlow(false)
    val ipmiSensorsLoading: StateFlow<Boolean> = _ipmiSensorsLoading.asStateFlow()

    private val _ipmiLanConfig = MutableStateFlow<IpmiLanConfig?>(null)
    val ipmiLanConfig: StateFlow<IpmiLanConfig?> = _ipmiLanConfig.asStateFlow()

    private val _ipmiUsers = MutableStateFlow<List<IpmiUserAccount>>(emptyList())
    val ipmiUsers: StateFlow<List<IpmiUserAccount>> = _ipmiUsers.asStateFlow()

    private val _ipmiUsersLoading = MutableStateFlow(false)
    val ipmiUsersLoading: StateFlow<Boolean> = _ipmiUsersLoading.asStateFlow()

    private val _ipmiPefStatus = MutableStateFlow<IpmiPefStatus?>(null)
    val ipmiPefStatus: StateFlow<IpmiPefStatus?> = _ipmiPefStatus.asStateFlow()

    private val _ipmiWatchdog = MutableStateFlow<IpmiWatchdogConfig?>(null)
    val ipmiWatchdog: StateFlow<IpmiWatchdogConfig?> = _ipmiWatchdog.asStateFlow()

    private val _ipmiAdminMessage = MutableStateFlow<String?>(null)
    val ipmiAdminMessage: StateFlow<String?> = _ipmiAdminMessage.asStateFlow()

    private val _virtualMedia = MutableStateFlow<List<RedfishVirtualMedia>>(emptyList())
    val virtualMedia: StateFlow<List<RedfishVirtualMedia>> = _virtualMedia.asStateFlow()

    private val _solOutput = MutableStateFlow("")
    val solOutput: StateFlow<String> = _solOutput.asStateFlow()

    // SOL-FEATURE (recording/export): [_solOutput] above is deliberately capped to the
    // last 8 000 chars for UI performance (see its write sites) — too short to be a
    // useful session transcript. [solLogBuffer] mirrors every chunk written to
    // [_solOutput] into a separate, much larger buffer (capped at ~4 MB, generous for
    // even a very long BIOS/OS console session) that only [exportSolLog] reads, so it
    // never touches recomposition. Reset each time a console is (re)opened.
    private val solLogBuffer = StringBuilder()

    private val _solExportEvent = MutableStateFlow<SolExportEvent?>(null)
    val solExportEvent: StateFlow<SolExportEvent?> = _solExportEvent.asStateFlow()
    fun dismissSolExportEvent() { _solExportEvent.value = null }

    private val _amtGeneralInfo = MutableStateFlow<AmtGeneralInfo?>(null)
    val amtGeneralInfo: StateFlow<AmtGeneralInfo?> = _amtGeneralInfo.asStateFlow()

    private val _amtPowerStatus = MutableStateFlow<AmtPowerStatus?>(null)
    val amtPowerStatus: StateFlow<AmtPowerStatus?> = _amtPowerStatus.asStateFlow()

    private val _amtBootMessage = MutableStateFlow<String?>(null)
    val amtBootMessage: StateFlow<String?> = _amtBootMessage.asStateFlow()

    private val _amtAuditLog = MutableStateFlow<List<AmtAuditLogEntry>>(emptyList())
    val amtAuditLog: StateFlow<List<AmtAuditLogEntry>> = _amtAuditLog.asStateFlow()

    private val _amtAuditLogLoading = MutableStateFlow(false)
    val amtAuditLogLoading: StateFlow<Boolean> = _amtAuditLogLoading.asStateFlow()

    private val _amtAccessLog = MutableStateFlow<List<AmtRedirectionAccessLogEntry>>(emptyList())
    val amtAccessLog: StateFlow<List<AmtRedirectionAccessLogEntry>> = _amtAccessLog.asStateFlow()

    private val _amtAccessLogLoading = MutableStateFlow(false)
    val amtAccessLogLoading: StateFlow<Boolean> = _amtAccessLogLoading.asStateFlow()

    // AMT-VPRO FEATURE phase 4: KVM. [amtKvmFrame] holds the latest full
    // framebuffer as a Bitmap — assembled here (not in [AmtKvmSession]
    // itself, which stays platform-agnostic) from the raw
    // [AmtKvmRect] rectangles each poll produces, same division of labor
    // as bVNC's Bitmap-owned-by-the-caller shape in [VncClient].
    private val _amtKvmFrame = MutableStateFlow<Bitmap?>(null)
    val amtKvmFrame: StateFlow<Bitmap?> = _amtKvmFrame.asStateFlow()

    private val _amtKvmConnecting = MutableStateFlow(false)
    val amtKvmConnecting: StateFlow<Boolean> = _amtKvmConnecting.asStateFlow()

    // AMT-VPRO FEATURE phase 5: IDE-R. [_amtIderState] mirrors
    // [AmtIderSession.state] — [AmtIderSessionState.MEDIA_ACTIVE] once
    // [amtMountIderMedia] has a [mountAndServe] loop running, same as the
    // plain connectivity-check path below.
    private val _amtIderState = MutableStateFlow<AmtIderSessionState?>(null)
    val amtIderState: StateFlow<AmtIderSessionState?> = _amtIderState.asStateFlow()

    private val _amtIderMessage = MutableStateFlow<String?>(null)
    val amtIderMessage: StateFlow<String?> = _amtIderMessage.asStateFlow()

    /** Display name of the image currently mounted via [amtMountIderMedia],
     *  null when nothing is mounted — lets the UI show what's mounted
     *  without holding onto the (potentially large) temp [java.io.File]
     *  itself. */
    private val _amtIderMountedFileName = MutableStateFlow<String?>(null)
    val amtIderMountedFileName: StateFlow<String?> = _amtIderMountedFileName.asStateFlow()

    /** True while the picked document is still being copied into a cache
     *  file (before [AmtIderSession.mountAndServe] starts) — distinct from
     *  [AmtIderSessionState.MEDIA_ACTIVE] so the UI can show "Preparing
     *  image…" instead of implying AMT is already serving it. */
    private val _amtIderPreparing = MutableStateFlow(false)
    val amtIderPreparing: StateFlow<Boolean> = _amtIderPreparing.asStateFlow()

    private var redfishClient: RedfishClient? = null
    private var ipmiClient: IpmiClient? = null
    private var amtClient: AmtClient? = null
    private var solChannel: IpmiSolChannel? = null
    // AMT-VPRO FEATURE phase 3: separate from [solChannel] since it's a
    // different wire protocol/class (AmtSolSession, not IpmiSolChannel) —
    // both write into the shared [_solOutput]/[solOutput] flow below since
    // only one protocol's client is ever active per session Activity.
    private var amtSolSession: AmtSolSession? = null
    private var amtKvmSession: AmtKvmSession? = null
    private var amtIderSession: AmtIderSession? = null
    // AMT-VPRO FEATURE phase 5: the coroutine running [AmtIderSession.mountAndServe]
    // (blocking, on Dispatchers.IO) for the current mount, if any — tracked
    // separately from [amtIderSession] itself so [amtUnmountIderMedia] and
    // [amtCloseIderDiagnostic] can tell "mounting" apart from "channel open, idle".
    private var amtIderMountJob: kotlinx.coroutines.Job? = null
    private var amtIderCacheFile: java.io.File? = null
    // Mutable pixel backing store for [_amtKvmFrame] — kept here (not
    // recreated per rectangle) so ENCODING_COPY_RECT rectangles, which name
    // a *source point already in this buffer*, have something to copy from.
    private var amtKvmPixels: IntArray? = null
    private var amtKvmWidth = 0
    private var amtKvmHeight = 0
    private var systemOdataId: String? = null
    private var chassisOdataId: String? = null
    private var managerOdataId: String? = null

    private var logId: String? = null

    fun load(profileId: String) {
        viewModelScope.launch {
            val loaded = profileRepository.getProfileById(profileId) ?: return@launch
            _profile.value = loaded
            logId = logRepository.start(
                ConnectionLog(
                    profileId = loaded.id,
                    profileName = loaded.name,
                    host = loaded.host,
                    port = loaded.port,
                    protocolType = loaded.protocolType,
                )
            )
            connect(loaded)
        }
    }

    private fun connect(p: RdpProfile) {
        _connState.value = BmcConnectionState(connecting = true)
        viewModelScope.launch {
            try {
                when (p.protocolType) {
                    ProtocolType.IPMI -> {
                        val client = IpmiClient(
                            host = p.host, port = p.port, username = p.username, password = p.password,
                            privilegeLevel = runCatching { IpmiClient.IpmiPrivilege.valueOf(p.ipmiPrivilegeLevel) }
                                .getOrDefault(IpmiClient.IpmiPrivilege.ADMINISTRATOR),
                            bmcKey = p.ipmiKgKey,
                        )
                        client.connect()
                        ipmiClient = client
                        refreshIpmi()
                    }
                    ProtocolType.AMT -> {
                        // AMT-VPRO FEATURE — Phase 6 (CIRA), WS-Man-over-CIRA
                        // follow-up (closes the gap AMT_VPRO_ROADMAP.md's
                        // "Not yet started" section flagged): a ciraEnabled
                        // profile's host/port were never a directly-dialable
                        // address (see RdpProfile.ciraEnabled's doc
                        // comment), so AmtClient's own WS-Man calls
                        // (identity check, power control, boot device, ...)
                        // now go through buildWsmanTransportFactory(p) — a
                        // CiraRelayTransport channel to the WS-Man port —
                        // instead of dialing p.host/p.port directly. This
                        // is a *separate* relay channel from the ones
                        // amtOpenSolConsole/amtOpenKvmConsole/
                        // amtOpenIderDiagnostic open for the redirection
                        // port, exactly like a direct connection also uses
                        // two independent sockets for WS-Man vs. redirection.
                        //
                        // TLS-over-CIRA for the WS-Man port is now resolved
                        // — see TlsLayeredTransport's doc comment for the
                        // "the port decides, not the tunnel" answer this
                        // was blocked on. buildWsmanTransportFactory(p)
                        // below picks 16992/16993 from p.amtUseTls and
                        // layers TlsLayeredTransport on top when needed, so
                        // no fail-fast guard is required here anymore.
                        val client = AmtClient(
                            host = p.host, port = p.port, username = p.username, password = p.password,
                            useTls = p.amtUseTls, acceptSelfSignedCertificate = p.acceptSelfSignedCertificate,
                            appContext = appContext,
                            externalWsmanTransportFactory = if (p.ciraEnabled) buildWsmanTransportFactory(p) else null,
                        )
                        client.connect()
                        amtClient = client
                        refreshAmt()
                    }
                    else -> {
                        val scheme = if (p.port == 80) "http" else "https"
                        val client = RedfishClient(
                            baseUrl = "$scheme://${p.host}:${p.port}",
                            username = p.username, password = p.password,
                            acceptSelfSignedCertificate = p.acceptSelfSignedCertificate,
                            appContext = appContext,
                        )
                        client.connect()
                        redfishClient = client
                        refreshRedfish()
                    }
                }
                _connState.value = BmcConnectionState(connected = true)
                logRepository.finish(logId ?: "", null, true)
            } catch (e: Exception) {
                val msg = e.message ?: appContext.getString(R.string.bmc_connection_failed)
                _connState.value = BmcConnectionState(error = msg)
                logRepository.finish(logId ?: "", msg, false)
            }
        }
    }

    // ── Redfish actions ──────────────────────────────────────────────

    private suspend fun refreshRedfish() {
        val client = redfishClient ?: return
        val systems = client.getSystems()
        val sys = systems.firstOrNull()
        _system.value = sys
        systemOdataId = sys?.odataId
        val chassisList = client.getChassis()
        chassisOdataId = chassisList.firstOrNull()?.odataId
        val managers = client.getManagers()
        managerOdataId = managers.firstOrNull()?.odataId
        chassisOdataId?.let { runCatching { _sensors.value = client.getSensors(it) } }
        (systemOdataId ?: managerOdataId)?.let { runCatching { _redfishLog.value = client.getEventLog(it) } }
        managerOdataId?.let { runCatching { _virtualMedia.value = client.getVirtualMedia(it) } }
    }

    fun redfishReset(resetType: RedfishResetType) = viewModelScope.launch {
        val client = redfishClient ?: return@launch
        val sysId = systemOdataId ?: return@launch
        try {
            client.resetSystem(sysId, resetType)
            kotlinx.coroutines.delay(2000)
            _system.value = client.getSystem(sysId)
        } catch (e: RedfishException) {
            _connState.value = _connState.value.copy(error = e.message)
        }
    }

    fun redfishEjectMedia(vm: RedfishVirtualMedia) = viewModelScope.launch {
        val client = redfishClient ?: return@launch
        runCatching { client.ejectVirtualMedia(vm.odataId) }
        managerOdataId?.let { runCatching { _virtualMedia.value = client.getVirtualMedia(it) } }
    }

    fun redfishInsertMedia(vm: RedfishVirtualMedia, imageUrl: String) = viewModelScope.launch {
        val client = redfishClient ?: return@launch
        runCatching { client.insertVirtualMedia(vm.odataId, imageUrl) }
        managerOdataId?.let { runCatching { _virtualMedia.value = client.getVirtualMedia(it) } }
    }

    fun redfishRefresh() = viewModelScope.launch { runCatching { refreshRedfish() } }

    // ── IPMI actions ─────────────────────────────────────────────────

    private suspend fun refreshIpmi() {
        val client = ipmiClient ?: return
        _chassisStatus.value = client.getChassisStatus()
        runCatching { _selEntries.value = client.getSelEntries() }
        runCatching { _ipmiDeviceId.value = client.getDeviceId() }
        runCatching { _ipmiFru.value = client.getFruInventory() }
    }

    fun ipmiPowerControl(action: IpmiPowerAction) = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        try {
            client.powerControl(action)
            kotlinx.coroutines.delay(1500)
            _chassisStatus.value = client.getChassisStatus()
        } catch (e: IpmiException) {
            _connState.value = _connState.value.copy(error = e.message)
        }
    }

    fun ipmiIdentify(seconds: Int = 15) = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        try {
            client.setChassisIdentify(seconds)
        } catch (e: IpmiException) {
            _connState.value = _connState.value.copy(error = "Identify: ${e.message}")
        }
    }

    /** Walks the SDR repository for real sensor names/units — separate from
     *  [refreshIpmi] since it's a heavier multi-round-trip operation (one
     *  Get SDR + one Get Sensor Reading per sensor), so it only runs when
     *  the Sensors tab is actually opened rather than on every refresh. */
    fun ipmiLoadSensors() = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        _ipmiSensorsLoading.value = true
        try {
            _ipmiSensors.value = client.getSensors()
        } catch (e: IpmiException) {
            _connState.value = _connState.value.copy(error = "Sensors: ${e.message}")
        } finally {
            _ipmiSensorsLoading.value = false
        }
    }

    fun ipmiRefresh() = viewModelScope.launch { runCatching { refreshIpmi() } }

    // ── IPMI admin: LAN / Users / PEF / Watchdog ────────────────────────
    // These are heavier, rarely-needed operations (several round trips each)
    // so — like ipmiLoadSensors — they load on demand (when the Admin tab's
    // relevant section is opened) rather than as part of every refresh.

    fun ipmiLoadLanConfig() = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        try {
            _ipmiLanConfig.value = client.getLanConfig()
        } catch (e: IpmiException) {
            _ipmiAdminMessage.value = appContext.getString(R.string.bmc_error_lan_config, e.message ?: "")
        }
    }

    fun ipmiSetLanStatic(ip: String, subnet: String, gateway: String) = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        try {
            client.setLanStaticConfig(ipAddress = ip, subnetMask = subnet, defaultGateway = gateway)
            _ipmiAdminMessage.value = appContext.getString(R.string.bmc_lan_config_updated)
            _ipmiLanConfig.value = client.getLanConfig()
        } catch (e: IpmiException) {
            _ipmiAdminMessage.value = appContext.getString(R.string.bmc_error_set_lan_config, e.message ?: "")
        } catch (e: IllegalArgumentException) {
            _ipmiAdminMessage.value = e.message
        }
    }

    fun ipmiLoadUsers() = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        _ipmiUsersLoading.value = true
        try {
            _ipmiUsers.value = client.getUsers()
        } catch (e: IpmiException) {
            _ipmiAdminMessage.value = appContext.getString(R.string.bmc_error_users, e.message ?: "")
        } finally {
            _ipmiUsersLoading.value = false
        }
    }

    fun ipmiSetUserPrivilege(account: IpmiUserAccount, privilege: IpmiClient.IpmiPrivilege) = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        try {
            client.setUserAccess(
                userId = account.userId, privilege = privilege,
                ipmiMessagingEnabled = account.ipmiMessagingEnabled, linkAuthEnabled = account.linkAuthEnabled,
                callInRestricted = !account.callInEnabled,
            )
            ipmiLoadUsers()
        } catch (e: IpmiException) {
            _ipmiAdminMessage.value = appContext.getString(R.string.bmc_error_set_privilege, e.message ?: "")
        }
    }

    fun ipmiSetUserEnabled(userId: Int, enabled: Boolean) = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        try {
            client.setUserEnabled(userId, enabled)
            ipmiLoadUsers()
        } catch (e: IpmiException) {
            _ipmiAdminMessage.value = appContext.getString(R.string.bmc_error_enable_disable_user, e.message ?: "")
        }
    }

    fun ipmiSetUserPassword(userId: Int, password: String) = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        try {
            client.setUserPassword(userId, password)
            _ipmiAdminMessage.value = appContext.getString(R.string.bmc_password_updated_for_user, userId.toString())
        } catch (e: IpmiException) {
            _ipmiAdminMessage.value = appContext.getString(R.string.bmc_error_set_password, e.message ?: "")
        }
    }

    fun ipmiLoadPefStatus() = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        try {
            _ipmiPefStatus.value = client.getPefStatus()
        } catch (e: IpmiException) {
            _ipmiAdminMessage.value = appContext.getString(R.string.bmc_error_pef, e.message ?: "")
        }
    }

    fun ipmiSetPefEnabled(enabled: Boolean) = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        try {
            client.setPefEnabled(enabled)
            _ipmiPefStatus.value = client.getPefStatus()
        } catch (e: IpmiException) {
            _ipmiAdminMessage.value = appContext.getString(R.string.bmc_error_pef, e.message ?: "")
        }
    }

    fun ipmiLoadWatchdog() = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        try {
            _ipmiWatchdog.value = client.getWatchdogConfig()
        } catch (e: IpmiException) {
            _ipmiAdminMessage.value = appContext.getString(R.string.bmc_error_watchdog, e.message ?: "")
        }
    }

    fun ipmiSetWatchdog(use: IpmiWatchdogUse, action: IpmiWatchdogAction, countdownSeconds: Int, start: Boolean) = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        try {
            client.setWatchdogConfig(use, action, countdownSeconds)
            if (start) client.resetWatchdog()
            _ipmiWatchdog.value = client.getWatchdogConfig()
        } catch (e: IpmiException) {
            _ipmiAdminMessage.value = appContext.getString(R.string.bmc_error_watchdog, e.message ?: "")
        }
    }

    fun dismissIpmiAdminMessage() { _ipmiAdminMessage.value = null }

    /** SOL-FEATURE: appends a decoded output chunk to [solLogBuffer], capping it at
     *  ~4 MB by dropping the oldest quarter of the buffer if it grows past that —
     *  a defensive bound for an unattended session left open a long time, not a
     *  normal operating limit for interactive use. */
    private fun appendToSolLog(chunk: String) {
        solLogBuffer.append(chunk)
        val cap = 4 * 1024 * 1024
        if (solLogBuffer.length > cap) solLogBuffer.delete(0, solLogBuffer.length / 4)
    }

    /** SOL-FEATURE: writes the full SOL transcript captured so far to [uri] (picked
     *  via `ActivityResultContracts.CreateDocument` — see [BmcManagementScreen]'s
     *  Console tabs) as plain UTF-8 text. Same SAF-write shape as
     *  `MainViewModel.exportConnections`, just without the encryption step since
     *  this is a console transcript, not a credentials backup. */
    fun exportSolLog(uri: Uri) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val text = solLogBuffer.toString()
            appContext.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw java.io.IOException("openOutputStream returned null")
            _solExportEvent.value = SolExportEvent.Success
        } catch (e: Exception) {
            android.util.Log.e("BmcManagementViewModel", "exportSolLog failed", e)
            _solExportEvent.value = SolExportEvent.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** SOL-FEATURE: shares [settingsRepository]'s app-wide terminal font size
     *  (already used by SSH's [com.systemsgo.hex.ui.screens.terminal.TerminalScreen])
     *  with the SOL console tabs, rather than introducing a second, separate
     *  font-size setting for what is visually the same kind of monospace output. */
    fun setTerminalFontSize(v: Int) = viewModelScope.launch { settingsRepository.updateTerminalFontSize(v) }

    fun openSolConsole() = viewModelScope.launch {
        val client = ipmiClient ?: return@launch
        solLogBuffer.setLength(0) // SOL-FEATURE: fresh transcript per console open
        try {
            val channel = client.openSolChannel()
            solChannel = channel
            while (solChannel === channel) {
                val data = channel.receive()
                if (data != null && data.isNotEmpty()) {
                    val chunk = String(data, Charsets.US_ASCII)
                    _solOutput.value = (_solOutput.value + chunk).takeLast(8000)
                    appendToSolLog(chunk)
                }
            }
        } catch (e: IpmiException) {
            _connState.value = _connState.value.copy(error = "SOL: ${e.message}")
        }
    }

    fun sendSolInput(text: String) = viewModelScope.launch {
        solChannel?.send((text + "\r").toByteArray(Charsets.US_ASCII))
    }

    /** SOL-FEATURE: sends raw bytes with no trailing CR appended — used by the
     *  terminal key row (arrows, Esc, Tab, Ctrl+letter, F-keys) whose ANSI escape
     *  sequences would be corrupted by [sendSolInput]'s Enter-key framing. */
    fun sendSolRaw(data: ByteArray) = viewModelScope.launch {
        solChannel?.send(data)
    }

    /** SOL-FEATURE: native IPMI SOL BREAK — see [IpmiSolChannel.sendBreak]'s doc
     *  comment for what it does and why there's no AMT equivalent. */
    fun sendSolBreak() = viewModelScope.launch {
        try {
            solChannel?.sendBreak()
        } catch (e: IpmiException) {
            _connState.value = _connState.value.copy(error = "SOL BREAK: ${e.message}")
        }
    }

    fun closeSolConsole() = viewModelScope.launch {
        val channel = solChannel ?: return@launch
        solChannel = null
        channel.close()
    }

    // ── AMT / vPro actions ────────────────────────────────────────────
    // AMT-VPRO FEATURE: phase 1 covers identity + power control, the same
    // "always supported regardless of OS state" subset IPMI's Chassis
    // Control offers above. Phase 3 (below) adds the SOL console — its own
    // wire protocol (AmtSolSession/APF), not more WS-Man calls. KVM
    // redirection and IDE-R are still tracked as follow-up phases — see
    // AMT_VPRO_ROADMAP.md.

    private suspend fun refreshAmt() {
        val client = amtClient ?: return
        _amtGeneralInfo.value = client.getGeneralInfo()
        runCatching { _amtPowerStatus.value = client.getPowerStatus() }
    }

    // AMT-VPRO FEATURE phase 6 (CIRA): shared by amtOpenSolConsole/
    // amtOpenKvmConsole/amtOpenIderDiagnostic/amtMountIderMedia below —
    // each opens its own CiraRelayTransport per session (not shared/reused
    // across SOL/KVM/IDE-R, matching how a direct connection also opens an
    // independent socket per session) when the active profile has CIRA
    // enabled, and passes null (falling back to AmtClient's own
    // DirectSocketTransport) otherwise.

    /** The AMT redirection port a CIRA-forwarded SOL/KVM/IDE-R channel
     *  should target — mirrors [AmtClient]'s own private `port -> port+2`
     *  mapping, now switching on [RdpProfile.amtUseTls] the same way the
     *  direct-connection path already does, instead of always forcing the
     *  plain port. See [TlsLayeredTransport]'s doc comment for why a
     *  CIRA-forwarded channel to 16995 needing its own inner TLS handshake
     *  (independent of the outer relay/APF encryption) is now a resolved,
     *  implemented case rather than a flagged unknown. */
    private fun ciraRedirectionPort(profile: RdpProfile): Int = if (profile.amtUseTls) 16995 else 16994

    // AMT-VPRO FEATURE phase 6 (CIRA), WS-Man-over-CIRA follow-up.

    /** The AMT WS-Man port a CIRA-forwarded channel should target — the
     *  WS-Man counterpart to [ciraRedirectionPort], switching on
     *  [RdpProfile.amtUseTls] the same way [AmtClient]'s own direct-connect
     *  `port` constructor parameter does between 16992/16993. */
    private fun ciraWsmanPort(profile: RdpProfile): Int = if (profile.amtUseTls) 16993 else 16992

    /** Wraps [inner] in [TlsLayeredTransport] when [profile] calls for TLS
     *  on this CIRA-forwarded channel, otherwise returns it unchanged —
     *  the one seam both [buildWsmanTransportFactory] and
     *  [openCiraTransport] share so the "should this channel be
     *  TLS-wrapped" decision lives in exactly one place. [portLabel] (e.g.
     *  `"wsman"`, `"redir"`) keeps the two channels' [TofuTrustManager]
     *  pins from colliding when both are open for the same device at once. */
    private fun maybeWrapTls(
        inner: AmtRedirectionTransport,
        profile: RdpProfile,
        portLabel: String,
        targetPort: Int,
    ): AmtRedirectionTransport {
        if (!profile.amtUseTls) return inner
        return TlsLayeredTransport.wrap(
            inner = inner,
            identity = "${profile.ciraDeviceId}:$portLabel:$targetPort",
            acceptSelfSignedCertificate = profile.acceptSelfSignedCertificate,
            appContext = appContext,
        )
    }

    /** Builds the `externalWsmanTransportFactory` [AmtClient] takes —
     *  called lazily by [AmtClient] itself (see that parameter's doc
     *  comment) whenever it needs to open or reopen the WS-Man CIRA
     *  channel, not eagerly here. Deliberately a plain blocking lambda, not
     *  `suspend`: [CiraRelayTransport.open] and [TlsLayeredTransport.wrap]
     *  are themselves blocking calls (see [openCiraTransport]'s identical
     *  reasoning above), and [AmtClient] only ever invokes this factory
     *  from inside its own `withContext(Dispatchers.IO)`-wrapped suspend
     *  functions, so no further dispatch is needed here. */
    private fun buildWsmanTransportFactory(profile: RdpProfile): () -> AmtRedirectionTransport = {
        val targetPort = ciraWsmanPort(profile)
        val relayChannel = CiraRelayTransport.open(
            relayHost = profile.ciraRelayHost,
            relayPort = profile.ciraRelayPort,
            relayUsername = profile.ciraRelayUsername,
            relayPassword = profile.ciraRelayPassword,
            deviceId = profile.ciraDeviceId,
            targetPort = targetPort,
            useTls = profile.ciraRelayUseTls,
            appContext = appContext,
        )
        maybeWrapTls(relayChannel, profile, "wsman", targetPort)
    }

    /** Opens a fresh [CiraRelayTransport] for [profile]'s CIRA relay
     *  fields, on [kotlinx.coroutines.Dispatchers.IO] since
     *  [CiraRelayTransport.open]/[TlsLayeredTransport.wrap] block — see
     *  those functions' doc comments. Throws [AmtException] on any
     *  relay/channel-open/handshake failure, same contract
     *  [AmtSolSession.open] etc. already have for a failed direct connect. */
    private suspend fun openCiraTransport(profile: RdpProfile): AmtRedirectionTransport =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val targetPort = ciraRedirectionPort(profile)
            try {
                val relayChannel = CiraRelayTransport.open(
                    relayHost = profile.ciraRelayHost,
                    relayPort = profile.ciraRelayPort,
                    relayUsername = profile.ciraRelayUsername,
                    relayPassword = profile.ciraRelayPassword,
                    deviceId = profile.ciraDeviceId,
                    targetPort = targetPort,
                    useTls = profile.ciraRelayUseTls,
                    appContext = appContext,
                )
                maybeWrapTls(relayChannel, profile, "redir", targetPort)
            } catch (e: IllegalArgumentException) {
                // CiraRelayTransport.open's require() checks (blank relay
                // host/device id) — surfaced the same way a genuine
                // connect failure would be, rather than as an unhandled
                // exception, since both are "this profile isn't usable
                // right now" from the caller's point of view.
                throw AmtException(e.message ?: "Invalid CIRA relay configuration")
            } catch (e: java.io.IOException) {
                // TlsLayeredTransport.wrap's handshake failure — same
                // "surface as AmtException, not a raw IOException" contract
                // as the require()-check branch above.
                throw AmtException(e.message ?: "CIRA TLS handshake failed")
            }
        }

    fun amtPowerControl(action: AmtPowerAction) = viewModelScope.launch {
        val client = amtClient ?: return@launch
        // AMT-VPRO FEATURE phase 6 (CIRA): power control is a WS-Man call —
        // now routed through the relay too (see connect()'s AMT branch,
        // which builds `client` with `externalWsmanTransportFactory` for a
        // CIRA-enabled profile), so no CIRA-specific handling is needed
        // here anymore; `client.powerControl` below already goes over
        // whichever transport `client` was built with.
        try {
            client.powerControl(action)
            kotlinx.coroutines.delay(1500)
            runCatching { _amtPowerStatus.value = client.getPowerStatus() }
        } catch (e: AmtException) {
            _connState.value = _connState.value.copy(error = e.message)
        }
    }

    fun amtRefresh() = viewModelScope.launch {
        // AMT-VPRO FEATURE phase 6 (CIRA): see amtPowerControl's identical
        // note — no CIRA-specific handling needed anymore.
        runCatching { refreshAmt() }
    }

    /** Stages [device] for the next boot/reset (AMT_VPRO FEATURE phase 2 —
     *  see AmtClient.setOneShotBoot's doc). Doesn't itself power-cycle the
     *  box — [amtBootMessage] tells the person that explicitly so they
     *  know a manual reset/power action is still needed. */
    fun amtSetOneShotBoot(device: AmtBootDevice) = viewModelScope.launch {
        val client = amtClient ?: return@launch
        // AMT-VPRO FEATURE phase 6 (CIRA): see amtPowerControl's identical
        // note — no CIRA-specific handling needed anymore.
        try {
            client.setOneShotBoot(device)
            _amtBootMessage.value = appContext.getString(R.string.bmc_next_boot_set_to, device.label)
        } catch (e: AmtException) {
            _connState.value = _connState.value.copy(error = e.message)
        }
    }

    fun dismissAmtBootMessage() { _amtBootMessage.value = null }

    fun amtLoadAuditLog() = viewModelScope.launch {
        val client = amtClient ?: return@launch
        _amtAuditLogLoading.value = true
        try {
            _amtAuditLog.value = client.getAuditLog()
        } catch (e: AmtException) {
            _connState.value = _connState.value.copy(error = "Audit log: ${e.message}")
        } finally {
            _amtAuditLogLoading.value = false
        }
    }

    /** `AMT_RedirectionService.AccessLog` — the IDE-R/SOL session log,
     *  distinct from [amtLoadAuditLog]'s general security audit log. See
     *  [AmtClient.getRedirectionAccessLog]'s doc comment. */
    fun amtLoadAccessLog() = viewModelScope.launch {
        val client = amtClient ?: return@launch
        _amtAccessLogLoading.value = true
        try {
            _amtAccessLog.value = client.getRedirectionAccessLog()
        } catch (e: AmtException) {
            _connState.value = _connState.value.copy(error = "Access log: ${e.message}")
        } finally {
            _amtAccessLogLoading.value = false
        }
    }

    /** AMT_VPRO FEATURE phase 3: opens the SOL console and streams output
     *  into the same [solOutput] flow IPMI's [openSolConsole] uses — mirrors
     *  its shape exactly (loop-while-still-the-active-session, so a
     *  subsequent [amtCloseSolConsole] naturally ends this loop rather than
     *  needing a separate cancellation flag). */
    fun amtOpenSolConsole() = viewModelScope.launch {
        val client = amtClient ?: return@launch
        _solOutput.value = ""
        solLogBuffer.setLength(0) // SOL-FEATURE: fresh transcript per console open
        try {
            // AMT-VPRO FEATURE phase 6 (CIRA): route through the relay when
            // this profile is CIRA-enabled — see openCiraTransport's doc
            // comment and connect()'s AMT branch for why there's nothing
            // directly reachable to fall back to otherwise.
            val profile = _profile.value
            val transport = if (profile?.ciraEnabled == true) openCiraTransport(profile) else null
            val session = client.openSolSession(externalTransport = transport)
            amtSolSession = session
            while (amtSolSession === session) {
                val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { session.receive() }
                if (data != null && data.isNotEmpty()) {
                    val chunk = String(data, Charsets.US_ASCII)
                    _solOutput.value = (_solOutput.value + chunk).takeLast(8000)
                    appendToSolLog(chunk)
                } else if (!session.established) {
                    break // AMT closed the channel — stop polling rather than spin
                }
            }
        } catch (e: AmtException) {
            _connState.value = _connState.value.copy(error = "SOL: ${e.message}")
        }
    }

    fun amtSendSolInput(text: String) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { amtSolSession?.send((text + "\r").toByteArray(Charsets.US_ASCII)) }
    }

    /** SOL-FEATURE: raw-byte counterpart to [amtSendSolInput] for the terminal key
     *  row — see [sendSolRaw]'s doc comment. There's no `amtSendSolBreak`: AMT's
     *  APF channel (see [AmtSolSession]) is a plain byte stream with no
     *  out-of-band control bit, unlike IPMI SOL's Operation/Status byte. */
    fun amtSendSolRaw(data: ByteArray) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { amtSolSession?.send(data) }
    }

    fun amtCloseSolConsole() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val session = amtSolSession ?: return@launch
        amtSolSession = null
        runCatching { session.close() }
    }

    /** AMT-VPRO FEATURE phase 4: opens the KVM session and polls it into
     *  [amtKvmFrame], mirroring [amtOpenSolConsole]'s
     *  "loop-while-still-the-active-session" shape. [kvmPassword] defaults
     *  to the profile's own AMT password — see [AmtClient.openKvmSession]'s
     *  doc comment for why that's the common case, not a certainty. */
    fun amtOpenKvmConsole(kvmPassword: String? = null) = viewModelScope.launch {
        val client = amtClient ?: return@launch
        val pass = kvmPassword ?: _profile.value?.password ?: ""
        _amtKvmConnecting.value = true
        amtKvmPixels = null; amtKvmWidth = 0; amtKvmHeight = 0; _amtKvmFrame.value = null
        try {
            // AMT-VPRO FEATURE phase 6 (CIRA): see amtOpenSolConsole's
            // identical note.
            val profile = _profile.value
            val transport = if (profile?.ciraEnabled == true) openCiraTransport(profile) else null
            val session = client.openKvmSession(pass, externalTransport = transport)
            amtKvmSession = session
            amtKvmWidth = session.framebufferWidth
            amtKvmHeight = session.framebufferHeight
            amtKvmPixels = IntArray(amtKvmWidth * amtKvmHeight)
            _amtKvmConnecting.value = false
            while (amtKvmSession === session) {
                val rects = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { session.receiveFrame() }
                if (rects.isNotEmpty()) {
                    applyKvmRects(rects)
                    publishKvmFrame()
                } else if (!session.established) {
                    break // AMT closed the connection — stop polling rather than spin
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { session.requestFrame(incremental = true) }
            }
        } catch (e: AmtException) {
            _amtKvmConnecting.value = false
            _connState.value = _connState.value.copy(error = "KVM: ${e.message}")
        }
    }

    /** Applies decoded rectangles onto [amtKvmPixels] in place — Raw
     *  rectangles overwrite their region directly, CopyRect rectangles
     *  blit from an already-drawn region of the same buffer (which is why
     *  this buffer is owned here rather than inside [AmtKvmSession] — see
     *  that class's doc comment on [AmtKvmRect]). */
    private fun applyKvmRects(rects: List<AmtKvmRect>) {
        val pixels = amtKvmPixels ?: return
        val w = amtKvmWidth
        for (rect in rects) {
            if (rect.pixels != null) {
                for (row in 0 until rect.h) {
                    val destOffset = (rect.y + row) * w + rect.x
                    val srcOffset = row * rect.w
                    if (destOffset < 0 || destOffset + rect.w > pixels.size) continue
                    System.arraycopy(rect.pixels, srcOffset, pixels, destOffset, rect.w)
                }
            } else {
                // CopyRect — copy row-by-row, bottom-to-top when the source
                // is below the destination so an overlapping copy doesn't
                // read pixels this same loop already overwrote.
                val topToBottom = rect.copySrcY >= rect.y
                val rows = if (topToBottom) 0 until rect.h else (rect.h - 1) downTo 0
                for (row in rows) {
                    val destOffset = (rect.y + row) * w + rect.x
                    val srcOffset = (rect.copySrcY + row) * w + rect.copySrcX
                    if (destOffset < 0 || srcOffset < 0 || destOffset + rect.w > pixels.size || srcOffset + rect.w > pixels.size) continue
                    System.arraycopy(pixels, srcOffset, pixels, destOffset, rect.w)
                }
            }
        }
    }

    private fun publishKvmFrame() {
        val pixels = amtKvmPixels ?: return
        if (amtKvmWidth <= 0 || amtKvmHeight <= 0) return
        val bitmap = Bitmap.createBitmap(pixels, amtKvmWidth, amtKvmHeight, Bitmap.Config.ARGB_8888)
        _amtKvmFrame.value = bitmap
    }

    fun amtSendKvmPointer(x: Int, y: Int, buttonMask: Int) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { amtKvmSession?.sendPointerEvent(x, y, buttonMask) }
    }

    fun amtSendKvmKey(keysym: Int, down: Boolean) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { amtKvmSession?.sendKeyEvent(keysym, down) }
    }

    fun amtCloseKvmConsole() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val session = amtKvmSession ?: return@launch
        amtKvmSession = null
        runCatching { session.close() }
    }

    /** AMT-VPRO FEATURE phase 5: opens (and immediately reports the result
     *  of) an IDE-R channel — a connectivity check, ahead of an actual
     *  mount via [amtMountIderMedia]. [amtMountIderMedia] opens its own
     *  channel on demand, so this is only needed for a person who wants to
     *  confirm auth/connectivity before picking an image.
     *  Unrelated to write support — see [amtMountIderMedia]. */
    fun amtOpenIderDiagnostic() = viewModelScope.launch {
        val client = amtClient ?: return@launch
        _amtIderState.value = AmtIderSessionState.CONNECTING
        _amtIderMessage.value = null
        try {
            // AMT-VPRO FEATURE phase 6 (CIRA): see amtOpenSolConsole's
            // identical note.
            val profile = _profile.value
            val transport = if (profile?.ciraEnabled == true) openCiraTransport(profile) else null
            val session = client.openIderSession(externalTransport = transport)
            amtIderSession = session
            _amtIderState.value = session.state
            _amtIderMessage.value = appContext.getString(R.string.bmc_ider_channel_accepted)
        } catch (e: AmtException) {
            _amtIderState.value = null
            _amtIderMessage.value = appContext.getString(R.string.bmc_error_ider, e.message ?: "")
        }
    }

    fun amtCloseIderDiagnostic() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        amtIderMountJob?.let { job ->
            amtIderSession?.requestStop() // let mountAndServe's poll loop notice and return before we yank the socket
            job.cancel()
        }
        val session = amtIderSession ?: return@launch
        amtIderSession = null
        runCatching { session.close() }
        _amtIderState.value = AmtIderSessionState.CLOSED
        _amtIderMountedFileName.value = null
        amtIderCacheFile?.let { runCatching { it.delete() } }
        amtIderCacheFile = null
    }

    /** AMT-VPRO FEATURE phase 5: the actual media mount — copies [uri] (a
     *  picked `.iso`/`.img`, via `ActivityResultContracts.OpenDocument` in
     *  [BmcManagementScreen]) into a cache file (AMT's virtual controller
     *  needs to seek within it — see [AmtIderDiskEmulator]/
     *  [AmtIderFloppyEmulator] — so a real [java.io.File] path is required,
     *  not just a `content://` stream), then runs
     *  [AmtIderSession.mountAndServe] until [amtUnmountIderMedia] is
     *  called, AMT closes the channel, or the connection drops. Reuses an
     *  already-open [amtIderSession] (e.g. from [amtOpenIderDiagnostic]) if
     *  one exists and is still open, otherwise opens a fresh one — a person
     *  shouldn't have to test connectivity separately before mounting.
     *
     *  [writable] is IDE-R write support's opt-in switch (AMT_VPRO_ROADMAP.md
     *  phase 5 follow-up) — passed straight through to
     *  [AmtIderSession.mountAndServe]/[com.systemsgo.hex.amt.protocol.AmtIderFloppyEmulator].
     *  Defaults to `false` (read-only), matching every mount before this
     *  parameter existed. Only meaningful for [AmtIderMediaType.FLOPPY]:
     *  [AmtIderMediaType.CD_ROM] always mounts read-only regardless, since a
     *  real CD/DVD-ROM was never writable to begin with (see
     *  [com.systemsgo.hex.amt.protocol.AmtIderDiskEmulator]'s doc comment) —
     *  [BmcManagementScreen]'s media tab only offers the writable toggle
     *  when Floppy is the selected media type for exactly that reason. */
    fun amtMountIderMedia(uri: Uri, fileName: String, mediaType: AmtIderMediaType, writable: Boolean = false) {
        val client = amtClient ?: return
        if (amtIderMountJob?.isActive == true) return // one mount at a time, matching real IDE-R usage
        amtIderMountJob = viewModelScope.launch {
            _amtIderMessage.value = null
            _amtIderPreparing.value = true
            var cacheFile: java.io.File? = null
            try {
                val session = amtIderSession?.takeIf { it.established }
                    ?: run {
                        _amtIderState.value = AmtIderSessionState.CONNECTING
                        // AMT-VPRO FEATURE phase 6 (CIRA): see
                        // amtOpenSolConsole's identical note.
                        val profile = _profile.value
                        val transport = if (profile?.ciraEnabled == true) openCiraTransport(profile) else null
                        client.openIderSession(externalTransport = transport).also { amtIderSession = it }
                    }
                _amtIderState.value = session.state

                val preparedFile: java.io.File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    copyUriToIderCacheFile(uri, fileName)
                }
                cacheFile = preparedFile
                amtIderCacheFile = preparedFile
                _amtIderPreparing.value = false
                _amtIderMountedFileName.value = fileName

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    session.mountAndServe(preparedFile, mediaType, writable = writable)
                }
                // mountAndServe only returns once stopped/closed/dropped.
            } catch (e: Exception) {
                _amtIderMessage.value = appContext.getString(R.string.bmc_error_ider_mount, e.message ?: "")
            } finally {
                _amtIderPreparing.value = false
                _amtIderMountedFileName.value = null
                _amtIderState.value = amtIderSession?.state ?: AmtIderSessionState.CLOSED
                cacheFile?.let { runCatching { it.delete() } }
                if (amtIderCacheFile === cacheFile) amtIderCacheFile = null
            }
        }
    }

    /** Asks a running [amtMountIderMedia] mount to stop — the channel
     *  itself stays open (matching [AmtIderSession.requestStop]'s doc
     *  comment) so another image can be mounted without re-authenticating. */
    fun amtUnmountIderMedia() {
        amtIderSession?.requestStop()
    }

    /** Streams [uri] into a fresh file under [appContext]'s cache dir —
     *  same chunked-copy shape as [com.systemsgo.hex.transfer.FileTransferManager]'s
     *  upload handling, needed here because [AmtIderSession.mountAndServe]
     *  seeks within the file to answer individual ATA/ATAPI read commands,
     *  which a `content://` [Uri]'s stream can't do. Deleted again in
     *  [amtMountIderMedia]'s `finally` once the mount ends. */
    private fun copyUriToIderCacheFile(uri: Uri, fileName: String): java.io.File {
        val suffix = fileName.substringAfterLast('.', "").let { if (it.isNotEmpty()) ".$it" else ".img" }
        val dest = java.io.File.createTempFile("systemsgo_ider_", suffix, appContext.cacheDir)
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(dest).use { output ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                }
            }
        } ?: run {
            dest.delete()
            throw java.io.IOException("Couldn't open '$fileName' — the picked file may no longer be accessible")
        }
        return dest
    }

    fun dismissError() { _connState.value = _connState.value.copy(error = null) }

    fun disconnect() {
        val id = logId
        viewModelScope.launch {
            runCatching { solChannel?.close() }
            runCatching { amtSolSession?.close() }
            runCatching { amtKvmSession?.close() }
            amtIderSession?.requestStop()
            amtIderMountJob?.cancel()
            runCatching { amtIderSession?.close() }
            amtIderCacheFile?.let { runCatching { it.delete() } }
            amtIderCacheFile = null
            runCatching { ipmiClient?.disconnect() }
            runCatching { redfishClient?.disconnect() }
            runCatching { amtClient?.disconnect() }
            if (id != null) runCatching { logRepository.finish(id, "", true) }
        }
    }
}
