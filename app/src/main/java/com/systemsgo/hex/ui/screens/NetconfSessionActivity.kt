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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.systemsgo.hex.data.model.ConnectionLog
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.ConnectionLogRepository
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.netconf.protocol.CommitOptions
import com.systemsgo.hex.netconf.protocol.NetconfClient
import com.systemsgo.hex.netconf.protocol.NetconfConnectionStats
import com.systemsgo.hex.netconf.protocol.NetconfDatastore
import com.systemsgo.hex.netconf.protocol.NetconfFilter
import com.systemsgo.hex.netconf.protocol.NetconfHelloInfo
import com.systemsgo.hex.netconf.protocol.NetconfOperations
import com.systemsgo.hex.netconf.protocol.NetconfProfileMapper
import com.systemsgo.hex.netconf.protocol.NetconfRpcException
import com.systemsgo.hex.netconf.protocol.NetconfSessionState
import com.systemsgo.hex.netconf.protocol.NetconfWireMessage
import com.systemsgo.hex.R
import com.systemsgo.hex.ui.theme.SystemsGoTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * NETCONF FEATURE: session Activity for [com.systemsgo.hex.data.model.
 * ProtocolType.NETCONF] profiles — the structured-RPC counterpart to
 * [BmcManagementActivity] (same reasoning: [NetconfClient] is not a
 * [com.systemsgo.hex.remote.RemoteSessionClient], so this never goes through
 * [RdpSessionActivity]/[com.systemsgo.hex.remote.RemoteSessionFactory] — see
 * [com.systemsgo.hex.remote.SessionLauncher] for the dispatch).
 */
@AndroidEntryPoint
class NetconfSessionActivity : AppCompatActivity() {

    private val viewModel: NetconfSessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profileId = intent.getStringExtra("profile_id") ?: run { finish(); return }
        // CALL-HOME FEATURE (RFC 8071, Part 12): present only when this
        // Activity was launched from NetconfCallHomeService's "device called
        // home" notification — see NetconfSessionViewModel.load's doc
        // comment for why this branches to *attaching* an already-connected
        // NetconfClient instead of building+dialing a new one.
        val callHomeToken = intent.getStringExtra("call_home_token")
        viewModel.load(profileId, callHomeToken)

        // APP-LOCK-EXPORT FIX: this Activity is android:exported="true" (so pinned
        // home-screen shortcuts, and the Call Home notification tap above, can start
        // it directly from an external process — see ShortcutHelper.kt and
        // NetconfCallHomeService), which also means any other app on the device can
        // send an explicit Intent carrying a "profile_id" extra and land here
        // directly, skipping MainActivity's own App Lock screen entirely. This used
        // to attach/connect immediately in every case, handing over this profile's
        // saved NETCONF credentials with no PIN/biometric prompt at all. Now gated
        // with the same lockRequired/isUnlocked/AppLockScreen pattern already used
        // by RdpSessionActivity and WebPortalActivity: a normal in-app tap already
        // passed through MainActivity's own lock screen, so only the
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
                        // way nothing sensitive (config data, credentials) is visible
                        // underneath yet.
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    } else {
                        NetconfSessionScreen(profile = p, viewModel = viewModel, onFinish = { finish() })
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

/** One entry in the session's RPC history / log — feeds the RPC Builder history tab and the Diff/Compare tool built on top of this ViewModel. */
data class NetconfRpcHistoryEntry(
    val label: String,
    val requestXml: String,
    val replyXml: String?,
    val error: String?,
    val timestampMs: Long = System.currentTimeMillis(),
)

/** One saved RPC template — user-defined (Saved RPC Library) or a built-in starter (XML Templates). */
data class NetconfSavedRpc(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val bodyXml: String,
    val isFavorite: Boolean = false,
)

@HiltViewModel
class NetconfSessionViewModel @Inject constructor(
    private val profileRepository: RdpProfileRepository,
    private val logRepository: ConnectionLogRepository,
    private val settingsRepository: com.systemsgo.hex.data.repository.AppSettingsRepository,
    private val pacProxyResolver: com.systemsgo.hex.proxy.PacProxyResolver,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _profile = MutableStateFlow<RdpProfile?>(null)
    val profile: StateFlow<RdpProfile?> = _profile.asStateFlow()

    val settings: StateFlow<com.systemsgo.hex.data.repository.AppSettings> =
        settingsRepository.settingsFlow.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, settingsRepository.currentSettingsSnapshot()
        )

    private var client: NetconfClient? = null
    private var ops: NetconfOperations? = null
    private var yangService: com.systemsgo.hex.netconf.protocol.NetconfYangService? = null
    private var logId: String? = null

    val sessionState: StateFlow<NetconfSessionState> get() = client?.sessionState ?: MutableStateFlow(NetconfSessionState.DISCONNECTED).asStateFlow()
    val hello: StateFlow<NetconfHelloInfo?> get() = client?.hello ?: MutableStateFlow(null).asStateFlow()
    val stats: StateFlow<NetconfConnectionStats> get() = client?.stats ?: MutableStateFlow(NetconfConnectionStats()).asStateFlow()

    private val _selectedDatastore = MutableStateFlow(NetconfDatastore.RUNNING)
    val selectedDatastore: StateFlow<NetconfDatastore> = _selectedDatastore.asStateFlow()
    fun selectDatastore(ds: NetconfDatastore) { _selectedDatastore.value = ds }

    private val _rpcHistory = MutableStateFlow<List<NetconfRpcHistoryEntry>>(emptyList())
    val rpcHistory: StateFlow<List<NetconfRpcHistoryEntry>> = _rpcHistory.asStateFlow()

    private val _savedRpcs = MutableStateFlow<List<NetconfSavedRpc>>(NetconfRpcTemplates.builtIns)
    val savedRpcs: StateFlow<List<NetconfSavedRpc>> = _savedRpcs.asStateFlow()

    private val _wireLog = MutableStateFlow<List<NetconfWireMessage>>(emptyList())
    val wireLog: StateFlow<List<NetconfWireMessage>> = _wireLog.asStateFlow()

    private val _notificationLog = MutableStateFlow<List<String>>(emptyList())
    val notificationLog: StateFlow<List<String>> = _notificationLog.asStateFlow()

    private val _lastResult = MutableStateFlow<NetconfRpcHistoryEntry?>(null)
    val lastResult: StateFlow<NetconfRpcHistoryEntry?> = _lastResult.asStateFlow()
    fun dismissLastResult() { _lastResult.value = null }

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun dismissToast() { _toast.value = null }

    /**
     * CALL-HOME FEATURE (RFC 8071, Part 12): [callHomeToken] is non-null
     * exactly when the Activity was launched from `NetconfCallHomeService`'s
     * notification for a device that already connected *in* and completed
     * its SSH handshake + `<hello>` exchange before the user ever tapped
     * anything — see [com.systemsgo.hex.netconf.protocol.
     * NetconfCallHomeSessionRegistry]. In that case [connect] must not run
     * at all (there is nothing left to dial, and [NetconfClient.connect]
     * would be a no-op/error on a client that's already CONNECTED); instead
     * [attachExisting] wires this ViewModel's flows to the client that's
     * already there.
     */
    fun load(profileId: String, callHomeToken: String? = null) {
        viewModelScope.launch {
            val loaded = profileRepository.getProfileById(profileId) ?: return@launch
            _profile.value = loaded
            _selectedDatastore.value = runCatching {
                NetconfDatastore.valueOf(loaded.netconfDefaultDatastore.uppercase())
            }.getOrDefault(NetconfDatastore.RUNNING)

            val callHomeSession = callHomeToken?.let {
                com.systemsgo.hex.netconf.protocol.NetconfCallHomeSessionRegistry.get(it)
            }
            if (callHomeSession != null) {
                isCallHomeAttached = true
                logId = logRepository.start(
                    ConnectionLog(
                        profileId = loaded.id,
                        profileName = loaded.name,
                        host = "call-home:${callHomeSession.remoteAddress}",
                        port = loaded.port,
                        protocolType = loaded.protocolType,
                    )
                )
                attachExisting(callHomeSession.client)
            } else {
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
    }

    /** True once [load] attached to an already-connected Call Home session rather than dialing a fresh [NetconfClient] — read by the Status tab to show "Call Home" instead of a dial target, and by [disconnect] to decide whether tearing down this ViewModel should also kill the underlying SSH session (see [disconnect]'s doc comment). */
    private var isCallHomeAttached = false

    /** Wires this ViewModel's flows ([sessionState]/[hello]/[wireLog]/[notificationLog]/[toast]/etc.) to a [NetconfClient] that's already connected, without calling [NetconfClient.connect] — the Call Home counterpart of [connect]'s wiring below. */
    private fun attachExisting(c: NetconfClient) {
        client = c
        ops = NetconfOperations(c)
        yangService = com.systemsgo.hex.netconf.protocol.NetconfYangService(c, ops!!)

        c.wireMessages.onEach { msg ->
            _wireLog.value = (_wireLog.value + msg).takeLast(500)
        }.launchIn(viewModelScope)

        c.notifications.onEach { n ->
            _notificationLog.value = (_notificationLog.value + n).takeLast(200)
        }.launchIn(viewModelScope)

        c.error.onEach { _toast.value = it }.launchIn(viewModelScope)

        logId?.let { id -> logRepository.finish(id, disconnectReason = null, wasSuccessful = true) }
    }

    private fun connect(p: RdpProfile) {
        viewModelScope.launch {
            val outboundProxy = runCatching {
                pacProxyResolver.resolve(p, p.host, p.port)
            }.getOrDefault(com.systemsgo.hex.proxy.PacProxyResolver.Resolved.Direct)

            val c = NetconfProfileMapper.buildClient(p, appContext, outboundProxy)
            client = c
            ops = NetconfOperations(c)
            yangService = com.systemsgo.hex.netconf.protocol.NetconfYangService(c, ops!!)

            c.wireMessages.onEach { msg ->
                _wireLog.value = (_wireLog.value + msg).takeLast(500)
            }.launchIn(viewModelScope)

            c.notifications.onEach { n ->
                _notificationLog.value = (_notificationLog.value + n).takeLast(200)
            }.launchIn(viewModelScope)

            c.error.onEach { _toast.value = it }.launchIn(viewModelScope)

            val ok = c.connect()
            logId?.let { id -> logRepository.finish(id, disconnectReason = if (ok) null else appContext.getString(R.string.netconf_disconnect_reason_failed_to_connect), wasSuccessful = ok) }
            if (!ok) _toast.value = appContext.getString(R.string.netconf_toast_failed_to_connect_to, p.host, p.port)
        }
    }

    fun reconnect() {
        val p = _profile.value ?: return
        if (isCallHomeAttached) {
            // CALL-HOME FEATURE: there is nothing to redial — this
            // ViewModel never dialed anything in the first place. A device
            // whose Call Home connection actually drops gets picked up
            // again the next time it calls home (a fresh notification with
            // a fresh token), not by this button.
            _toast.value = appContext.getString(R.string.netconf_toast_call_home_reconnect_info)
            return
        }
        viewModelScope.launch {
            client?.disconnect()
            connect(p)
        }
    }

    /**
     * CALL-HOME FEATURE: for a Call Home-attached session, this
     * deliberately does NOT call [NetconfClient.disconnect] — that
     * client's lifetime belongs to `NetconfCallHomeService`
     * (see [NetconfCallHomeSessionRegistry]'s class doc comment), which
     * keeps the device's SSH session alive in the background regardless of
     * whether this Session UI is open. Closing the Activity here is just
     * "stop looking at it", the same as minimizing a normal outbound
     * session would be if this app supported that — only a normal
     * (dialed) profile's client is actually torn down on Activity destroy.
     */
    fun disconnect() {
        if (isCallHomeAttached) return
        client?.disconnect()
    }

    // ── RPC execution, shared by every operation button/tool in the UI ──

    private fun record(label: String, requestXml: String, replyXml: String?, error: String?) {
        val entry = NetconfRpcHistoryEntry(label, requestXml, replyXml, error)
        _rpcHistory.value = (_rpcHistory.value + entry).takeLast(200)
        _lastResult.value = entry
    }

    fun runGet(filter: NetconfFilter = NetconfFilter.None) = runOp("get") { it.get(filter) }

    fun runGetConfig(source: NetconfDatastore = _selectedDatastore.value, filter: NetconfFilter = NetconfFilter.None) =
        runOp("get-config($source)") { it.getConfig(source, filter) }

    fun runEditConfig(configXml: String, target: NetconfDatastore = _selectedDatastore.value) =
        runOp("edit-config($target)") { it.editConfig(target, configXml); "<ok/>" }

    fun runLock(target: NetconfDatastore = _selectedDatastore.value) = runOp("lock($target)") { it.lock(target); "<ok/>" }
    fun runUnlock(target: NetconfDatastore = _selectedDatastore.value) = runOp("unlock($target)") { it.unlock(target); "<ok/>" }
    fun runValidate(source: NetconfDatastore = _selectedDatastore.value) = runOp("validate($source)") { it.validate(source); "<ok/>" }
    fun runCommit(options: CommitOptions = CommitOptions()) = runOp("commit") { it.commit(options); "<ok/>" }
    fun runDiscardChanges() = runOp("discard-changes") { it.discardChanges(); "<ok/>" }
    fun runCancelCommit() = runOp("cancel-commit") { it.cancelCommit(); "<ok/>" }

    /** RPC Builder entry point: send an arbitrary raw `<rpc>` body the user typed/edited. */
    fun runCustomRpc(label: String, bodyXml: String) {
        val c = client ?: run { _toast.value = appContext.getString(R.string.netconf_toast_not_connected); return }
        viewModelScope.launch {
            try {
                val reply = c.sendRawRpc(bodyXml)
                record(label, requestXml = bodyXml, replyXml = reply, error = null)
            } catch (e: Exception) {
                record(label, requestXml = bodyXml, replyXml = null, error = e.message ?: appContext.getString(R.string.netconf_error_unknown))
            }
        }
    }

    private fun runOp(label: String, block: suspend (NetconfOperations) -> String) {
        val o = ops ?: run { _toast.value = appContext.getString(R.string.netconf_toast_not_connected); return }
        viewModelScope.launch {
            try {
                val reply = block(o)
                record(label, requestXml = label, replyXml = reply, error = null)
            } catch (e: NetconfRpcException) {
                record(label, requestXml = label, replyXml = e.rpcErrorXml, error = e.message)
            } catch (e: Exception) {
                record(label, requestXml = label, replyXml = null, error = e.message ?: appContext.getString(R.string.netconf_error_unknown))
            }
        }
    }

    // ── YANG browser (module discovery / schema fetch / tree / namespace) ──

    private val _yangModules = MutableStateFlow<List<com.systemsgo.hex.netconf.protocol.NetconfYangModule>>(emptyList())
    val yangModules: StateFlow<List<com.systemsgo.hex.netconf.protocol.NetconfYangModule>> = _yangModules.asStateFlow()

    private val _yangLoading = MutableStateFlow(false)
    val yangLoading: StateFlow<Boolean> = _yangLoading.asStateFlow()

    private val _yangSearchQuery = MutableStateFlow("")
    val yangSearchQuery: StateFlow<String> = _yangSearchQuery.asStateFlow()
    fun setYangSearchQuery(q: String) { _yangSearchQuery.value = q }

    val yangFilteredModules: StateFlow<List<com.systemsgo.hex.netconf.protocol.NetconfYangModule>> =
        kotlinx.coroutines.flow.combine(_yangModules, _yangSearchQuery) { modules, query ->
            if (query.isBlank()) modules
            else modules.filter { it.name.contains(query, ignoreCase = true) || it.namespace.contains(query, ignoreCase = true) }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    /** Distinct namespace → module-name pairs — the Namespace Explorer's data source. */
    val yangNamespaces: StateFlow<List<Pair<String, String>>> = _yangModules
        .map { modules -> modules.map { it.namespace to it.name }.distinctBy { it.first } }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    private val _selectedYangModule = MutableStateFlow<com.systemsgo.hex.netconf.protocol.NetconfYangModule?>(null)
    val selectedYangModule: StateFlow<com.systemsgo.hex.netconf.protocol.NetconfYangModule?> = _selectedYangModule.asStateFlow()

    private val _yangTree = MutableStateFlow<com.systemsgo.hex.netconf.protocol.YangTreeNode?>(null)
    val yangTree: StateFlow<com.systemsgo.hex.netconf.protocol.YangTreeNode?> = _yangTree.asStateFlow()

    private val _yangRawSource = MutableStateFlow<String?>(null)
    val yangRawSource: StateFlow<String?> = _yangRawSource.asStateFlow()

    /** Built by tapping tree nodes in the YANG browser — copyable into the RPC Builder's subtree/xpath filter. */
    private val _yangXPathBuilder = MutableStateFlow("")
    val yangXPathBuilder: StateFlow<String> = _yangXPathBuilder.asStateFlow()
    fun appendToXPathBuilder(segment: String) {
        _yangXPathBuilder.value = if (_yangXPathBuilder.value.isBlank()) "/$segment" else "${_yangXPathBuilder.value}/$segment"
    }
    fun clearXPathBuilder() { _yangXPathBuilder.value = "" }

    fun loadYangModules() {
        val svc = yangService ?: run { _toast.value = appContext.getString(R.string.netconf_toast_not_connected); return }
        viewModelScope.launch {
            _yangLoading.value = true
            try {
                _yangModules.value = svc.discoverModules()
            } catch (e: Exception) {
                _toast.value = appContext.getString(R.string.netconf_toast_yang_discovery_failed, e.message ?: appContext.getString(R.string.netconf_error_unknown))
            } finally {
                _yangLoading.value = false
            }
        }
    }

    fun selectYangModule(module: com.systemsgo.hex.netconf.protocol.NetconfYangModule) {        _selectedYangModule.value = module
        _yangTree.value = null
        _yangRawSource.value = null
        val svc = yangService ?: return
        if (!module.schemaFetchable) {
            _toast.value = "${module.name}: server didn't advertise ietf-netconf-monitoring — schema source isn't fetchable, only capability metadata is shown."
            return
        }
        viewModelScope.launch {
            _yangLoading.value = true
            try {
                val source = svc.fetchSchema(module)
                _yangRawSource.value = source
                _yangTree.value = com.systemsgo.hex.netconf.protocol.YangTreeBuilder.build(source)
            } catch (e: Exception) {
                _toast.value = appContext.getString(R.string.netconf_toast_get_schema_failed, module.name, e.message ?: appContext.getString(R.string.netconf_error_unknown))
            } finally {
                _yangLoading.value = false
            }
        }
    }

    fun backToYangModuleList() {
        _selectedYangModule.value = null
        _yangTree.value = null
        _yangRawSource.value = null
        clearXPathBuilder()
    }

    /** Flattened tag-name vocabulary from the currently-loaded YANG module's tree — feeds [com.systemsgo.hex.netconf.xml.XmlAutoComplete] in the RPC Builder / Diff Tool editors. */
    val yangKnownTagNames: StateFlow<List<String>> = _yangTree
        .map { tree -> tree?.let { flattenTagNames(it) } ?: emptyList() }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    private fun flattenTagNames(node: com.systemsgo.hex.netconf.protocol.YangTreeNode): List<String> =
        (if (node.name.isNotBlank()) listOf(node.name) else emptyList()) + node.children.flatMap { flattenTagNames(it) }

    /** Diff Tool support: fetches get-config for [datastore] off the main flow, delivering the raw XML (or null on failure, with a toast already shown) to [onResult]. */
    fun fetchConfigForDiff(datastore: NetconfDatastore, onResult: (String?) -> Unit) {
        val o = ops ?: run { _toast.value = appContext.getString(R.string.netconf_toast_not_connected); onResult(null); return }
        viewModelScope.launch {
            try {
                onResult(o.getConfig(datastore))
            } catch (e: Exception) {
                _toast.value = appContext.getString(R.string.netconf_toast_get_config_failed, datastore.elementName, e.message ?: appContext.getString(R.string.netconf_error_unknown))
                onResult(null)
            }
        }
    }



    fun saveRpc(name: String, bodyXml: String) {
        _savedRpcs.value = _savedRpcs.value + NetconfSavedRpc(name = name, bodyXml = bodyXml)
    }

    fun deleteSavedRpc(id: String) {
        _savedRpcs.value = _savedRpcs.value.filterNot { it.id == id }
    }

    fun toggleFavoriteRpc(id: String) {
        _savedRpcs.value = _savedRpcs.value.map { if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it }
    }
}

/** Built-in starter XML templates (Tools → XML Templates), covering the most common day-one NETCONF requests. */
object NetconfRpcTemplates {
    val builtIns: List<NetconfSavedRpc> = listOf(
        NetconfSavedRpc(
            name = "get-config (running, no filter)",
            bodyXml = "<get-config><source><running/></source></get-config>",
        ),
        NetconfSavedRpc(
            name = "get (empty filter)",
            bodyXml = "<get><filter type=\"subtree\"/></get>",
        ),
        NetconfSavedRpc(
            name = "lock candidate",
            bodyXml = "<lock><target><candidate/></target></lock>",
        ),
        NetconfSavedRpc(
            name = "commit",
            bodyXml = "<commit/>",
        ),
        NetconfSavedRpc(
            name = "copy running to startup",
            bodyXml = "<copy-config><target><startup/></target><source><running/></source></copy-config>",
        ),
    )
}
