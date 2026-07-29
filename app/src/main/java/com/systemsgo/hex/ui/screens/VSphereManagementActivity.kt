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
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.remote.SessionLauncher
import com.systemsgo.hex.ui.theme.SystemsGoTheme
import com.systemsgo.hex.vsphere.VSphereVncBridge
import com.systemsgo.hex.vsphere.toVSphereClient
import com.systemsgo.hex.vsphere.protocol.VSphereApiClient
import com.systemsgo.hex.vsphere.protocol.VSphereException
import com.systemsgo.hex.vsphere.protocol.VSphereHost
import com.systemsgo.hex.vsphere.protocol.VSpherePowerAction
import com.systemsgo.hex.vsphere.protocol.VSphereVm
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * VMWARE-VSPHERE FEATURE (Part 3/N): session Activity for
 * [ProtocolType.VMWARE_VSPHERE] profiles — the VM/host inventory, power
 * controls, and WEBMKS console launch screen that
 * [com.systemsgo.hex.vsphere.protocol.VSphereApiClient]'s (Part 1) and
 * [VSphereVncBridge]'s (Part 2) doc comments both flagged as "not built
 * yet". Same shape as [ProxmoxManagementActivity] — not a
 * [com.systemsgo.hex.remote.RemoteSessionClient] (no framebuffer/terminal of
 * its own), so it's routed here directly by
 * [com.systemsgo.hex.remote.SessionLauncher] rather than through
 * RdpSessionActivity/RemoteSessionFactory, exactly like PROXMOX/REDFISH/
 * IPMI/AMT/SNMP/NETCONF/RESTCONF/MODBUS_TCP. Same shape/trust-boundary as
 * those (exported="true" + "profile_id"-only extra).
 */
@AndroidEntryPoint
class VSphereManagementActivity : AppCompatActivity() {

    private val viewModel: VSphereManagementViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileId = intent.getStringExtra("profile_id") ?: run { finish(); return }
        viewModel.load(profileId)

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
                        VSphereManagementScreen(
                            profile = p,
                            viewModel = viewModel,
                            onFinish = { finish() },
                            onOpenConsole = { consoleProfileId ->
                                val consoleProfile = RdpProfile(
                                    id = consoleProfileId,
                                    name = "",
                                    protocolType = ProtocolType.VNC,
                                    host = "127.0.0.1",
                                    username = "",
                                    password = "",
                                )
                                SessionLauncher.launch(this@VSphereManagementActivity, consoleProfile)
                            },
                        )
                    }
                    if (lockRequired) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !isUnlocked,
                            enter = androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            AppLockScreen(
                                biometricEnabled = settings.biometricLockEnabled,
                                pinEnabled = settings.pinLockEnabled,
                                encryptedPin = settings.pinCode,
                                isUnlocked = isUnlocked,
                                onUnlocked = { isUnlocked = true }
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

/** Loading/error state for the read-only host list (a status strip, not expandable — see VSphereManagementScreen). */
data class VSphereHostsState(
    val loading: Boolean = false,
    val hosts: List<VSphereHost> = emptyList(),
    val error: String? = null,
)

/** Loading/error state for the VM inventory — flat, unlike Proxmox's per-node grouping, since `GET /vcenter/vm` doesn't return which host each VM sits on without a separate per-VM detail call (see VSphereApiClient.listVms's doc comment). */
data class VSphereVmsState(
    val loading: Boolean = false,
    val vms: List<VSphereVm> = emptyList(),
    val error: String? = null,
)

/** Loading/error state for a one-shot power action or console launch, keyed by VM moref so only that row shows a spinner. */
data class VSphereVmOpState(val busyMoref: String? = null, val error: String? = null)

@HiltViewModel
class VSphereManagementViewModel @Inject constructor(
    private val profileRepository: RdpProfileRepository,
    private val settingsRepository: com.systemsgo.hex.data.repository.AppSettingsRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _profile = MutableStateFlow<RdpProfile?>(null)
    val profile: StateFlow<RdpProfile?> = _profile.asStateFlow()

    val settings: StateFlow<com.systemsgo.hex.data.repository.AppSettings> =
        settingsRepository.settingsFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, settingsRepository.currentSettingsSnapshot()
        )

    private var client: VSphereApiClient? = null

    private val _hosts = MutableStateFlow(VSphereHostsState())
    val hosts: StateFlow<VSphereHostsState> = _hosts.asStateFlow()

    private val _vms = MutableStateFlow(VSphereVmsState())
    val vms: StateFlow<VSphereVmsState> = _vms.asStateFlow()

    private val _vmOp = MutableStateFlow(VSphereVmOpState())
    val vmOp: StateFlow<VSphereVmOpState> = _vmOp.asStateFlow()

    private var activeBridge: VSphereVncBridge? = null

    fun load(profileId: String) {
        viewModelScope.launch {
            val p = profileRepository.getProfileById(profileId) ?: return@launch
            _profile.value = p
            client = p.toVSphereClient()
            refreshAll()
        }
    }

    /** Re-runs [VSphereApiClient.login] (session tokens can expire) then reloads both lists — used by pull-to-refresh and the retry button alike. */
    fun refreshAll() {
        val c = client ?: return
        viewModelScope.launch {
            _hosts.value = VSphereHostsState(loading = true)
            _vms.value = VSphereVmsState(loading = true)
            try {
                c.login()
            } catch (e: Exception) {
                val message = describeError(e)
                _hosts.value = VSphereHostsState(error = message)
                _vms.value = VSphereVmsState(error = message)
                return@launch
            }
            loadHosts()
            loadVms()
        }
    }

    private fun loadHosts() {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val fetched = c.listHosts()
                _hosts.value = VSphereHostsState(hosts = fetched)
            } catch (e: Exception) {
                _hosts.value = VSphereHostsState(error = describeError(e))
            }
        }
    }

    private fun loadVms() {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val fetched = c.listVms()
                _vms.value = VSphereVmsState(vms = fetched)
            } catch (e: Exception) {
                _vms.value = VSphereVmsState(error = describeError(e))
            }
        }
    }

    fun powerAction(vm: VSphereVm, action: VSpherePowerAction) {
        val c = client ?: return
        viewModelScope.launch {
            _vmOp.value = VSphereVmOpState(busyMoref = vm.moref)
            try {
                c.powerAction(vm, action)
                // Give vCenter/ESXi a beat to reflect the new state, then refresh the VM list.
                kotlinx.coroutines.delay(800)
                loadVms()
                _vmOp.value = VSphereVmOpState()
            } catch (e: Exception) {
                _vmOp.value = VSphereVmOpState(error = describeError(e))
            }
        }
    }

    /**
     * Opens a WEBMKS console for a powered-on VM: fetches a one-time
     * console ticket, starts a [VSphereVncBridge] on an ephemeral loopback
     * port, saves a short-lived, unsaved-to-the-user's-list [RdpProfile]
     * pointed at that port, and hands its id to [onReady] so the caller can
     * launch [RdpSessionActivity] via [SessionLauncher] — same flow as
     * [ProxmoxManagementViewModel.openConsole], with the ticket also
     * supplied as the RFB password on the (unconfirmed) chance the target
     * VM negotiates VNC Authentication rather than no in-band auth at all —
     * see [VSphereVncBridge]'s doc comment for why that's a best effort, not
     * a guarantee. The temporary profile is deleted a few seconds later —
     * long enough for RdpSessionActivity to have loaded it, short enough it
     * never shows up in the connection list.
     */
    fun openConsole(vm: VSphereVm, onReady: (profileId: String) -> Unit, onError: (String) -> Unit) {
        val c = client ?: return
        val vsphereProfile = _profile.value ?: return
        viewModelScope.launch {
            _vmOp.value = VSphereVmOpState(busyMoref = vm.moref)
            try {
                val ticket = c.acquireConsoleTicket(vm)
                val bridge = VSphereVncBridge(ticket.webSocketUrl, vsphereProfile.vsphereAcceptSelfSignedCertificate)
                activeBridge?.stop()
                activeBridge = bridge

                val consoleProfileId = "vsphere_console_${vm.moref}_${System.currentTimeMillis()}"
                var launched = false
                bridge.start(object : VSphereVncBridge.Listener {
                    override fun onBridgeReady(localPort: Int) {
                        viewModelScope.launch {
                            profileRepository.saveProfile(
                                RdpProfile(
                                    id = consoleProfileId,
                                    name = "vSphere Console — ${vm.name}",
                                    protocolType = ProtocolType.VNC,
                                    host = "127.0.0.1",
                                    port = localPort,
                                    username = "",
                                    password = ticket.ticket,
                                )
                            )
                            launched = true
                            _vmOp.value = VSphereVmOpState()
                            onReady(consoleProfileId)
                            // Best-effort cleanup: RdpSessionActivity has read
                            // the profile by id well before this fires.
                            kotlinx.coroutines.delay(8_000)
                            runCatching { profileRepository.getProfileById(consoleProfileId)?.let { profileRepository.deleteProfile(it) } }
                        }
                    }

                    override fun onBridgeFailed(error: Throwable) {
                        if (!launched) {
                            _vmOp.value = VSphereVmOpState(error = describeError(error as? Exception ?: Exception(error)))
                            onError(error.message ?: "Console connection failed.")
                        }
                    }

                    override fun onBridgeClosed() {
                        activeBridge = null
                    }
                })
            } catch (e: Exception) {
                _vmOp.value = VSphereVmOpState(error = describeError(e))
                onError(describeError(e))
            }
        }
    }

    private fun describeError(e: Exception): String = when (e) {
        is VSphereException -> e.message ?: "vSphere API error."
        else -> e.message ?: e.javaClass.simpleName
    }

    fun onScreenClosed() {
        activeBridge?.stop()
        activeBridge = null
        client?.close()
    }
}
