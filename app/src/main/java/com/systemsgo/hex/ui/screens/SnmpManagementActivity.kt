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
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.snmp.snmpFavoriteOidList
import com.systemsgo.hex.snmp.toSnmpClient
import com.systemsgo.hex.snmp.toSnmpFavoriteOidsColumn
import com.systemsgo.hex.snmp.protocol.Oid
import com.systemsgo.hex.snmp.protocol.SnmpClient
import com.systemsgo.hex.snmp.protocol.SnmpCredentials
import com.systemsgo.hex.snmp.protocol.SnmpException
import com.systemsgo.hex.snmp.protocol.SnmpTrapEvent
import com.systemsgo.hex.snmp.protocol.SnmpTrapListener
import com.systemsgo.hex.snmp.protocol.SnmpValue
import com.systemsgo.hex.snmp.protocol.VarBind
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
 * SNMP FEATURE: session Activity for [com.systemsgo.hex.data.model.ProtocolType.SNMP]
 * profiles (and the entry point used when opening the SNMP monitoring
 * add-on for any other profile) — the SNMP counterpart to
 * [BmcManagementActivity]. Not a [com.systemsgo.hex.remote.RemoteSessionClient]
 * (no framebuffer/terminal), so it's routed here directly by
 * [com.systemsgo.hex.remote.SessionLauncher] rather than through
 * RdpSessionActivity/RemoteSessionFactory, exactly like REDFISH/IPMI/AMT.
 */
@AndroidEntryPoint
class SnmpManagementActivity : AppCompatActivity() {

    private val viewModel: SnmpManagementViewModel by viewModels()

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
        // SNMP credentials with no PIN/biometric prompt at all. Now gated with the
        // same lockRequired/isUnlocked/AppLockScreen pattern already used by
        // RdpSessionActivity and WebPortalActivity for their own exported,
        // profile_id-driven launches: a normal in-app tap already passed through
        // MainActivity's own lock screen, so only the shortcut/external-intent path
        // re-prompts here.
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
                        // way nothing sensitive (OIDs, community string/credentials)
                        // is visible underneath yet.
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    } else {
                        SnmpManagementScreen(profile = p, viewModel = viewModel, onFinish = { finish() })
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

/** One row in the Get/Walk/Browser result table. */
data class SnmpResultRow(val oid: Oid, val name: String, val value: SnmpValue, val displayValue: String)

data class SnmpOpState(val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class SnmpManagementViewModel @Inject constructor(
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

    private var client: SnmpClient? = null

    // ── Dashboard ──
    private val _dashboard = MutableStateFlow<List<SnmpResultRow>>(emptyList())
    val dashboard: StateFlow<List<SnmpResultRow>> = _dashboard.asStateFlow()
    private val _dashboardState = MutableStateFlow(SnmpOpState())
    val dashboardState: StateFlow<SnmpOpState> = _dashboardState.asStateFlow()

    // ── Browser / Walk ──
    private val _walkResults = MutableStateFlow<List<SnmpResultRow>>(emptyList())
    val walkResults: StateFlow<List<SnmpResultRow>> = _walkResults.asStateFlow()
    private val _walkState = MutableStateFlow(SnmpOpState())
    val walkState: StateFlow<SnmpOpState> = _walkState.asStateFlow()
    private val _mibLoadMessage = MutableStateFlow<String?>(null)
    val mibLoadMessage: StateFlow<String?> = _mibLoadMessage.asStateFlow()

    // ── Get/Set tool ──
    private val _toolResults = MutableStateFlow<List<SnmpResultRow>>(emptyList())
    val toolResults: StateFlow<List<SnmpResultRow>> = _toolResults.asStateFlow()
    private val _toolState = MutableStateFlow(SnmpOpState())
    val toolState: StateFlow<SnmpOpState> = _toolState.asStateFlow()

    // ── Favorites ──
    private val _favorites = MutableStateFlow<List<Oid>>(emptyList())
    val favorites: StateFlow<List<Oid>> = _favorites.asStateFlow()

    // ── Trap receiver ──
    private val _trapEvents = MutableStateFlow<List<SnmpTrapEvent>>(emptyList())
    val trapEvents: StateFlow<List<SnmpTrapEvent>> = _trapEvents.asStateFlow()
    private val _trapListening = MutableStateFlow(false)
    val trapListening: StateFlow<Boolean> = _trapListening.asStateFlow()
    private val _trapError = MutableStateFlow<String?>(null)
    val trapError: StateFlow<String?> = _trapError.asStateFlow()
    private var trapListener: SnmpTrapListener? = null

    fun load(profileId: String) {
        viewModelScope.launch {
            val p = profileRepository.getProfileById(profileId) ?: return@launch
            _profile.value = p
            _favorites.value = p.snmpFavoriteOidList()
            client = p.toSnmpClient()
            refreshDashboard()
        }
    }

    private fun row(oid: Oid, value: SnmpValue): SnmpResultRow {
        val name = com.systemsgo.hex.snmp.mib.MibDictionary.describe(oid)
        return SnmpResultRow(oid, name, value, formatValue(oid, value))
    }

    private fun formatValue(oid: Oid, value: SnmpValue): String = when (value) {
        is SnmpValue.OctetStringVal -> if (value.bytes.any { it < 0x09 || (it in 0x0e..0x1f) }) value.asHex() else value.asText()
        is SnmpValue.IntegerVal -> {
            val columnOid = Oid(oid.arcs.copyOf(oid.arcs.size - 1).let { if (it.size >= 2) it else oid.arcs })
            com.systemsgo.hex.snmp.mib.MibDictionary.DISPLAY_HINTS[columnOid.toString()]?.get(value.value.toInt())
                ?: value.value.toString()
        }
        is SnmpValue.TimeTicksVal -> value.formatted()
        is SnmpValue.Counter32Val -> value.value.toString()
        is SnmpValue.Gauge32Val -> value.value.toString()
        is SnmpValue.Counter64Val -> value.asDecimalString()
        is SnmpValue.IpAddressVal -> value.asText()
        is SnmpValue.ObjectIdVal -> com.systemsgo.hex.snmp.mib.MibDictionary.describe(value.oid)
        is SnmpValue.OpaqueVal -> value.asHex()
        SnmpValue.NullVal -> ""
        SnmpValue.NoSuchObject -> "(no such object)"
        SnmpValue.NoSuchInstance -> "(no such instance)"
        SnmpValue.EndOfMibView -> "(end of MIB view)"
    }

    /** The handful of near-universal MIB-II scalars that make a useful at-a-glance dashboard without a full walk. */
    private val dashboardOids = listOf(
        "1.3.6.1.2.1.1.1.0", "1.3.6.1.2.1.1.3.0", "1.3.6.1.2.1.1.5.0",
        "1.3.6.1.2.1.1.4.0", "1.3.6.1.2.1.1.6.0", "1.3.6.1.2.1.1.2.0", "1.3.6.1.2.1.2.1.0",
    ).map { Oid(it) }

    fun refreshDashboard() {
        val c = client ?: return
        viewModelScope.launch {
            _dashboardState.value = SnmpOpState(loading = true)
            try {
                val resp = c.get(*dashboardOids.toTypedArray())
                _dashboard.value = resp.varBinds.map { row(it.oid, it.value) }
                _dashboardState.value = SnmpOpState()
            } catch (e: SnmpException) {
                _dashboardState.value = SnmpOpState(error = e.message)
            }
        }
    }

    fun walk(rootOidText: String) {
        val c = client ?: return
        val oid = runCatching { Oid(rootOidText) }.getOrNull() ?: run {
            _walkState.value = SnmpOpState(error = "Invalid OID: $rootOidText"); return
        }
        viewModelScope.launch {
            _walkState.value = SnmpOpState(loading = true)
            try {
                val results = c.walk(oid)
                _walkResults.value = results.map { row(it.oid, it.value) }
                _walkState.value = SnmpOpState()
            } catch (e: SnmpException) {
                _walkState.value = SnmpOpState(error = e.message)
            }
        }
    }

    fun loadMibText(text: String) {
        viewModelScope.launch {
            val result = com.systemsgo.hex.snmp.mib.MibParser.loadInto(text)
            _mibLoadMessage.value = "${result.resolved.size} objects loaded" +
                if (result.unresolvedNames.isNotEmpty()) ", ${result.unresolvedNames.size} unresolved (missing IMPORTS?)" else ""
        }
    }

    fun get(oidText: String) = runTool { c -> c.get(Oid(oidText)) }
    fun getNext(oidText: String) = runTool { c -> c.getNext(Oid(oidText)) }
    fun set(oidText: String, value: SnmpValue) = runTool { c -> c.set(VarBind(Oid(oidText), value)) }

    private fun runTool(op: suspend (SnmpClient) -> com.systemsgo.hex.snmp.protocol.SnmpResponse) {
        val c = client ?: return
        viewModelScope.launch {
            _toolState.value = SnmpOpState(loading = true)
            try {
                val resp = op(c)
                _toolResults.value = resp.varBinds.map { row(it.oid, it.value) }
                _toolState.value = SnmpOpState()
            } catch (e: SnmpException) {
                _toolState.value = SnmpOpState(error = e.message)
            } catch (e: NumberFormatException) {
                _toolState.value = SnmpOpState(error = appContext.getString(R.string.snmp_error_invalid_value_for_type))
            }
        }
    }

    fun toggleFavorite(oid: Oid) {
        val p = _profile.value ?: return
        val current = _favorites.value
        val updated = if (oid in current) current - oid else current + oid
        _favorites.value = updated
        viewModelScope.launch {
            val saved = p.copy(snmpFavoriteOids = updated.toSnmpFavoriteOidsColumn())
            profileRepository.updateProfile(saved)
            _profile.value = saved
        }
    }

    // ── Trap receiver: v3 user registry ──
    // SNMP FEATURE: users a manager might send v3 traps/informs as — needed
    // to authenticate/decrypt them (SnmpTrapListener has no discovery
    // responder, so it can only make sense of a v3 packet whose username it
    // already has credentials for; see SnmpTrapListener's class doc).
    private val _trapV3Users = MutableStateFlow<List<SnmpCredentials.Usm>>(emptyList())
    val trapV3Users: StateFlow<List<SnmpCredentials.Usm>> = _trapV3Users.asStateFlow()

    fun addTrapV3User(creds: SnmpCredentials.Usm) {
        _trapV3Users.value = _trapV3Users.value.filterNot { it.username == creds.username } + creds
    }

    fun removeTrapV3User(username: String) {
        _trapV3Users.value = _trapV3Users.value.filterNot { it.username == username }
    }

    fun startTrapListener(port: Int, communities: Set<String>) {
        stopTrapListener()
        val listener = SnmpTrapListener(
            port = port,
            communities = communities,
            v3Users = _trapV3Users.value.associateBy { it.username },
            onEvent = { event -> _trapEvents.value = (listOf(event) + _trapEvents.value).take(500) },
            onError = { e -> _trapError.value = e.message },
        )
        listener.start(viewModelScope)
        trapListener = listener
        _trapListening.value = true
    }

    fun stopTrapListener() {
        trapListener?.stop()
        trapListener = null
        _trapListening.value = false
    }

    fun clearTrapEvents() { _trapEvents.value = emptyList() }

    fun onScreenClosed() {
        stopTrapListener()
        client?.close()
    }
}
