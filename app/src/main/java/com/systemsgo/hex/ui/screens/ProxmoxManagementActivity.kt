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
import com.systemsgo.hex.proxmox.ProxmoxVncBridge
import com.systemsgo.hex.proxmox.toProxmoxClient
import com.systemsgo.hex.proxmox.protocol.ProxmoxApiClient
import com.systemsgo.hex.proxmox.protocol.ProxmoxException
import com.systemsgo.hex.proxmox.protocol.ProxmoxGuest
import com.systemsgo.hex.proxmox.protocol.ProxmoxNode
import com.systemsgo.hex.proxmox.protocol.ProxmoxPowerAction
import com.systemsgo.hex.remote.SessionLauncher
import com.systemsgo.hex.ui.theme.SystemsGoTheme
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
 * PROXMOX-API FEATURE (Part 3/N): session Activity for [ProtocolType.PROXMOX]
 * profiles — node/guest (VM + LXC container) inventory, power controls, and
 * a VNC console launch for running QEMU VMs, driven by [ProxmoxApiClient]
 * (Part 1) and [ProxmoxVncBridge] (Part 2). Not a
 * [com.systemsgo.hex.remote.RemoteSessionClient] (no framebuffer/terminal of
 * its own), so it's routed here directly by
 * [com.systemsgo.hex.remote.SessionLauncher] rather than through
 * RdpSessionActivity/RemoteSessionFactory, exactly like
 * REDFISH/IPMI/AMT/SNMP/NETCONF/RESTCONF/MODBUS_TCP. Same
 * shape/trust-boundary as those (exported="true" + "profile_id"-only extra).
 */
@AndroidEntryPoint
class ProxmoxManagementActivity : AppCompatActivity() {

    private val viewModel: ProxmoxManagementViewModel by viewModels()

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
                        ProxmoxManagementScreen(
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
                                SessionLauncher.launch(this@ProxmoxManagementActivity, consoleProfile)
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

/** One row's worth of loaded guests, keyed by node — populated lazily as nodes are expanded. */
data class ProxmoxNodeState(
    val node: ProxmoxNode,
    val expanded: Boolean = false,
    val loadingGuests: Boolean = false,
    val guests: List<ProxmoxGuest> = emptyList(),
    val error: String? = null,
)

/** Loading/error state for a one-shot power action or console launch, keyed by guest vmid so only that row shows a spinner. */
data class ProxmoxGuestOpState(val busyVmid: Int? = null, val error: String? = null)

@HiltViewModel
class ProxmoxManagementViewModel @Inject constructor(
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

    private var client: ProxmoxApiClient? = null

    private val _loadingNodes = MutableStateFlow(false)
    val loadingNodes: StateFlow<Boolean> = _loadingNodes.asStateFlow()
    private val _nodesError = MutableStateFlow<String?>(null)
    val nodesError: StateFlow<String?> = _nodesError.asStateFlow()
    private val _nodes = MutableStateFlow<List<ProxmoxNodeState>>(emptyList())
    val nodes: StateFlow<List<ProxmoxNodeState>> = _nodes.asStateFlow()

    private val _guestOp = MutableStateFlow(ProxmoxGuestOpState())
    val guestOp: StateFlow<ProxmoxGuestOpState> = _guestOp.asStateFlow()

    private var activeBridge: ProxmoxVncBridge? = null

    fun load(profileId: String) {
        viewModelScope.launch {
            val p = profileRepository.getProfileById(profileId) ?: return@launch
            _profile.value = p
            client = p.toProxmoxClient()
            refreshNodes()
        }
    }

    fun refreshNodes() {
        val c = client ?: return
        viewModelScope.launch {
            _loadingNodes.value = true
            _nodesError.value = null
            try {
                val fetched = c.listNodes()
                _nodes.value = fetched.map { ProxmoxNodeState(node = it) }
            } catch (e: Exception) {
                _nodesError.value = describeError(e)
            } finally {
                _loadingNodes.value = false
            }
        }
    }

    fun toggleNode(nodeName: String) {
        val current = _nodes.value
        val target = current.firstOrNull { it.node.node == nodeName } ?: return
        _nodes.value = current.map { if (it.node.node == nodeName) it.copy(expanded = !it.expanded) else it }
        if (!target.expanded && target.guests.isEmpty()) loadGuests(nodeName)
    }

    private fun loadGuests(nodeName: String) {
        val c = client ?: return
        viewModelScope.launch {
            _nodes.value = _nodes.value.map { if (it.node.node == nodeName) it.copy(loadingGuests = true, error = null) else it }
            try {
                val guests = c.listGuests(nodeName)
                _nodes.value = _nodes.value.map {
                    if (it.node.node == nodeName) it.copy(loadingGuests = false, guests = guests) else it
                }
            } catch (e: Exception) {
                _nodes.value = _nodes.value.map {
                    if (it.node.node == nodeName) it.copy(loadingGuests = false, error = describeError(e)) else it
                }
            }
        }
    }

    fun powerAction(guest: ProxmoxGuest, action: ProxmoxPowerAction) {
        val c = client ?: return
        viewModelScope.launch {
            _guestOp.value = ProxmoxGuestOpState(busyVmid = guest.vmid)
            try {
                c.powerAction(guest, action)
                // Give Proxmox a beat to reflect the new state, then refresh this node's guest list.
                kotlinx.coroutines.delay(800)
                loadGuests(guest.node)
                _guestOp.value = ProxmoxGuestOpState()
            } catch (e: Exception) {
                _guestOp.value = ProxmoxGuestOpState(error = describeError(e))
            }
        }
    }

    /**
     * Opens a VNC console for a running QEMU guest: fetches a one-time
     * ticket, starts a [ProxmoxVncBridge] on an ephemeral loopback port,
     * saves a short-lived, unsaved-to-the-user's-list [RdpProfile] pointed
     * at that port (the ticket itself doubles as the RFB password — see
     * [ProxmoxApiClient.vncWebSocketUrl]'s doc comment), and hands its id to
     * [onReady] so the caller can launch [com.systemsgo.hex.ui.screens.RdpSessionActivity]
     * via [SessionLauncher]. The temporary profile is deleted a few seconds
     * later — long enough for RdpSessionActivity to have loaded it, short
     * enough it never shows up in the connection list.
     */
    fun openConsole(guest: ProxmoxGuest, onReady: (profileId: String) -> Unit, onError: (String) -> Unit) {
        val c = client ?: return
        val proxmoxProfile = _profile.value ?: return
        viewModelScope.launch {
            _guestOp.value = ProxmoxGuestOpState(busyVmid = guest.vmid)
            try {
                val ticket = c.vncProxy(guest)
                val wsUrl = c.vncWebSocketUrl(guest, ticket)
                val headers = c.consoleAuthHeaders()
                val bridge = ProxmoxVncBridge(wsUrl, headers, proxmoxProfile.proxmoxAcceptSelfSignedCertificate)
                activeBridge?.stop()
                activeBridge = bridge

                val consoleProfileId = "proxmox_console_${guest.vmid}_${System.currentTimeMillis()}"
                var launched = false
                bridge.start(object : ProxmoxVncBridge.Listener {
                    override fun onBridgeReady(localPort: Int) {
                        viewModelScope.launch {
                            profileRepository.saveProfile(
                                RdpProfile(
                                    id = consoleProfileId,
                                    name = "Proxmox Console — ${guest.name}",
                                    protocolType = ProtocolType.VNC,
                                    host = "127.0.0.1",
                                    port = localPort,
                                    username = "",
                                    password = ticket.ticket,
                                )
                            )
                            launched = true
                            _guestOp.value = ProxmoxGuestOpState()
                            onReady(consoleProfileId)
                            // Best-effort cleanup: RdpSessionActivity has read
                            // the profile by id well before this fires.
                            kotlinx.coroutines.delay(8_000)
                            runCatching { profileRepository.getProfileById(consoleProfileId)?.let { profileRepository.deleteProfile(it) } }
                        }
                    }

                    override fun onBridgeFailed(error: Throwable) {
                        if (!launched) {
                            _guestOp.value = ProxmoxGuestOpState(error = describeError(error as? Exception ?: Exception(error)))
                            onError(error.message ?: "Console connection failed.")
                        }
                    }

                    override fun onBridgeClosed() {
                        activeBridge = null
                    }
                })
            } catch (e: Exception) {
                _guestOp.value = ProxmoxGuestOpState(error = describeError(e))
                onError(describeError(e))
            }
        }
    }

    private fun describeError(e: Exception): String = when (e) {
        is ProxmoxException -> e.message ?: "Proxmox API error."
        else -> e.message ?: e.javaClass.simpleName
    }

    fun onScreenClosed() {
        activeBridge?.stop()
        activeBridge = null
        client?.close()
    }
}
