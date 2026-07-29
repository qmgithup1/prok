package com.systemsgo.hex.ui.screens

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.security.KeyChain
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.systemsgo.hex.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.util.DeviceFormFactor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.model.RestconfAuthType
import com.systemsgo.hex.data.model.RestconfDataFormat
import com.systemsgo.hex.data.repository.ConnectionLogRepository
import com.systemsgo.hex.data.model.ConnectionLog
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.restconf.protocol.RestconfClient
import com.systemsgo.hex.restconf.protocol.RestconfConnectionConfig
import com.systemsgo.hex.restconf.protocol.RestconfConnectionState
import com.systemsgo.hex.restconf.protocol.RestconfException
import com.systemsgo.hex.restconf.protocol.RestconfMethod
import com.systemsgo.hex.restconf.protocol.RestconfResponse
import com.systemsgo.hex.restconf.protocol.RestconfSessionStats
import com.systemsgo.hex.restconf.protocol.RestconfTemplateEngine
import com.systemsgo.hex.ui.theme.SystemsGoTheme
import com.systemsgo.hex.ui.theme.NovaPink
import com.systemsgo.hex.ui.theme.PlasmaGreen
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.SolarFlare
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
 * RESTCONF FEATURE (Part 1/4): session Activity for [com.systemsgo.hex.data.model.ProtocolType.RESTCONF]
 * profiles — same "own standalone Activity, not a RemoteSessionClient tab"
 * shape as [BmcManagementActivity]/[WebPortalActivity], see
 * [com.systemsgo.hex.remote.SessionLauncher]'s routing.
 *
 * This is deliberately a minimal-but-real request/response explorer for
 * Part 1 (method + path + Send, raw response viewer, quick capability/
 * module-list probes, live connection-state/stats) — [RestconfClient]
 * itself is the fully-built protocol layer; the polished Request Builder
 * (syntax-highlighted body editor, saved requests/collections/history) and
 * Response Viewer (tree view, XML/JSON pretty-printing) land in Part 2, and
 * the dedicated YANG Browser/Schema Explorer tab lands in Part 3. Every
 * action here already goes over the real wire — nothing in this screen is
 * mocked.
 */
@AndroidEntryPoint
class RestconfExplorerActivity : AppCompatActivity() {

    private val viewModel: RestconfExplorerViewModel by viewModels()

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
        // RESTCONF credentials (Basic auth, bearer token, client cert alias, ...)
        // with no PIN/biometric prompt at all. Now gated with the same
        // lockRequired/isUnlocked/AppLockScreen pattern already used by
        // RdpSessionActivity and WebPortalActivity: a normal in-app tap already
        // passed through MainActivity's own lock screen, so only the
        // shortcut/external-intent path re-prompts here.
        val fromShortcut = intent.getBooleanExtra("from_shortcut", false)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            var isUnlocked by remember { mutableStateOf(false) }
            val lockRequired = fromShortcut && (settings.biometricLockEnabled || settings.pinLockEnabled)
            LaunchedEffect(lockRequired) {
                if (!lockRequired) isUnlocked = true
            }
            SystemsGoTheme {
                val profile by viewModel.profile.collectAsStateWithLifecycle()
                val p = profile
                Box(Modifier.fillMaxSize()) {
                    if (p == null || !isUnlocked) {
                        // Still loading the profile, or waiting on App Lock — either
                        // way nothing sensitive (auth headers, responses, credentials)
                        // is visible underneath yet.
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    } else {
                        RestconfExplorerScreen(profile = p, viewModel = viewModel, onFinish = { finish() })
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

@HiltViewModel
class RestconfExplorerViewModel @Inject constructor(
    private val profileRepository: RdpProfileRepository,
    private val logRepository: ConnectionLogRepository,
    private val explorerRepository: com.systemsgo.hex.data.repository.RestconfExplorerRepository,
    private val collectionBackupManager: com.systemsgo.hex.data.backup.RestconfCollectionBackupManager,
    // APP-LOCK-EXPORT FIX: needed so the Activity can gate its exported,
    // profile_id-driven launch behind App Lock — see BmcManagementViewModel /
    // SnmpManagementViewModel / NetconfSessionViewModel's identical field.
    private val settingsRepository: com.systemsgo.hex.data.repository.AppSettingsRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _profile = MutableStateFlow<RdpProfile?>(null)
    val profile: StateFlow<RdpProfile?> = _profile.asStateFlow()

    val settings: StateFlow<com.systemsgo.hex.data.repository.AppSettings> =
        settingsRepository.settingsFlow.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, settingsRepository.currentSettingsSnapshot()
        )

    private var client: RestconfClient? = null
    private var logId: String? = null

    val connectionState: StateFlow<RestconfConnectionState>
        get() = client?.connectionState ?: MutableStateFlow(RestconfConnectionState.DISCONNECTED)

    val stats: StateFlow<RestconfSessionStats>
        get() = client?.stats ?: MutableStateFlow(RestconfSessionStats())

    private val _lastResponse = MutableStateFlow<RestconfResponse?>(null)
    val lastResponse: StateFlow<RestconfResponse?> = _lastResponse.asStateFlow()

    // ── RESTCONF FEATURE (Part 4/4): Diff tab state — previousResponse is a
    // plain rolling "one back" (in-memory only, session-scoped: nothing here
    // needs to survive a process death the way saved requests/history do)
    // updated every time send() gets a new response; baselineResponse is the
    // same shape but only ever changes when the user explicitly pins/unpins
    // one via the Response Viewer's pin icon, so it can sit still while the
    // user sends more requests to diff each new response against a fixed
    // reference point (e.g. "what changed since I set this config").
    private val _previousResponse = MutableStateFlow<RestconfResponse?>(null)
    val previousResponse: StateFlow<RestconfResponse?> = _previousResponse.asStateFlow()

    private val _baselineResponse = MutableStateFlow<RestconfResponse?>(null)
    val baselineResponse: StateFlow<RestconfResponse?> = _baselineResponse.asStateFlow()

    fun pinBaseline() {
        _baselineResponse.value = _lastResponse.value
    }

    fun clearBaseline() {
        _baselineResponse.value = null
    }

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    // ── RESTCONF FEATURE (Part 3/4): YANG Browser state ─────────────────
    private val _yangModules = MutableStateFlow<List<com.systemsgo.hex.restconf.protocol.YangModule>>(emptyList())
    val yangModules: StateFlow<List<com.systemsgo.hex.restconf.protocol.YangModule>> = _yangModules.asStateFlow()

    private val _isLoadingModules = MutableStateFlow(false)
    val isLoadingModules: StateFlow<Boolean> = _isLoadingModules.asStateFlow()

    private val _datastoreBody = MutableStateFlow<String?>(null)
    val datastoreBody: StateFlow<String?> = _datastoreBody.asStateFlow()

    // ── RESTCONF FEATURE (Part 3/4): API Explorer state — populated by
    // collecting explorerRepository's per-profile Flows once load() resolves
    // the profile id (see load() below); each is a plain StateFlow here so
    // the Compose layer never needs to know these are backed by Room.
    private val _savedRequests = MutableStateFlow<List<com.systemsgo.hex.data.model.RestconfSavedRequest>>(emptyList())
    val savedRequests: StateFlow<List<com.systemsgo.hex.data.model.RestconfSavedRequest>> = _savedRequests.asStateFlow()

    private val _favoriteRequests = MutableStateFlow<List<com.systemsgo.hex.data.model.RestconfSavedRequest>>(emptyList())
    val favoriteRequests: StateFlow<List<com.systemsgo.hex.data.model.RestconfSavedRequest>> = _favoriteRequests.asStateFlow()

    private val _history = MutableStateFlow<List<com.systemsgo.hex.data.model.RestconfHistoryEntry>>(emptyList())
    val history: StateFlow<List<com.systemsgo.hex.data.model.RestconfHistoryEntry>> = _history.asStateFlow()

    private val _collections = MutableStateFlow<List<com.systemsgo.hex.data.model.RestconfCollection>>(emptyList())
    val collections: StateFlow<List<com.systemsgo.hex.data.model.RestconfCollection>> = _collections.asStateFlow()

    // ── RESTCONF FEATURE (Part 5): Environment Variables state — same
    // Flow-mirrored-into-StateFlow shape as the four fields above.
    // environmentVariables derives from environments rather than its own
    // repository collection, so it's always in sync with whichever row has
    // isActive = true without a second Room query.
    private val _environments = MutableStateFlow<List<com.systemsgo.hex.data.model.RestconfEnvironment>>(emptyList())
    val environments: StateFlow<List<com.systemsgo.hex.data.model.RestconfEnvironment>> = _environments.asStateFlow()

    val environmentVariables: StateFlow<Map<String, String>> = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap()).apply {
        viewModelScope.launch {
            _environments.collect { list ->
                value = list.firstOrNull { it.isActive }?.let { com.systemsgo.hex.data.repository.parseVariableLines(it.variables) } ?: emptyMap()
            }
        }
    }.asStateFlow()

    // RESTCONF FEATURE (Part 5): Import/Export result surfaced as a
    // one-shot event (same "collect + null it back out" shape used for
    // errors elsewhere in this ViewModel) so the Screen can show a Toast/snackbar
    // without the message replaying on every recomposition.
    private val _collectionBackupEvent = MutableStateFlow<String?>(null)
    val collectionBackupEvent: StateFlow<String?> = _collectionBackupEvent.asStateFlow()

    fun clearCollectionBackupEvent() {
        _collectionBackupEvent.value = null
    }

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

            // RESTCONF FEATURE (Part 3/4): now that the profile id is known,
            // mirror the repository's per-profile Flows into plain StateFlows
            // (see the four _saved.../_history/_collections fields above).
            launch { explorerRepository.getSavedRequests(loaded.id).collect { _savedRequests.value = it } }
            launch { explorerRepository.getFavoriteRequests(loaded.id).collect { _favoriteRequests.value = it } }
            launch { explorerRepository.getHistory(loaded.id).collect { _history.value = it } }
            launch { explorerRepository.getCollections(loaded.id).collect { _collections.value = it } }
            launch { explorerRepository.getEnvironments(loaded.id).collect { _environments.value = it } }
        }
    }

    private fun connect(profile: RdpProfile) {
        val scheme = if (profile.restconfUseHttps) "https" else "http"
        val config = RestconfConnectionConfig(
            baseUrl = "$scheme://${profile.host}:${profile.port}",
            username = profile.username,
            password = profile.password,
            authType = RestconfAuthType.fromName(profile.restconfAuthType),
            dataFormat = RestconfDataFormat.fromName(profile.restconfDataFormat),
            acceptSelfSignedCertificate = profile.acceptSelfSignedCertificate,
            bearerToken = profile.restconfBearerToken,
            jwtToken = profile.restconfJwtToken,
            apiKeyHeaderName = profile.restconfApiKeyHeaderName,
            apiKeyValue = profile.restconfApiKeyValue,
            customHeaders = parseHeaderLines(profile.restconfCustomHeaders),
            mutualTlsEnabled = profile.restconfMutualTlsEnabled,
            clientCertAlias = profile.restconfClientCertAlias,
            oauth2TokenUrl = profile.restconfOAuth2TokenUrl,
            oauth2ClientId = profile.restconfOAuth2ClientId,
            oauth2ClientSecret = profile.restconfOAuth2ClientSecret,
            oauth2Scope = profile.restconfOAuth2Scope,
            certificatePinsSha256 = profile.restconfCertificatePins.split(",").map { it.trim() }.filter { it.isNotBlank() },
            http2Enabled = profile.restconfHttp2Enabled,
            compressionEnabled = profile.restconfCompressionEnabled,
            keepAliveSeconds = profile.restconfKeepAliveSeconds.toLong(),
        )
        // RESTCONF FEATURE (Part 4/4): appContext is only ever *used* by the
        // client when CLIENT_CERTIFICATE/MUTUAL_TLS + a non-blank
        // clientCertAlias are both in play (see RestconfClient.wireTls) —
        // harmless to always pass for every other auth type.
        val c = RestconfClient(config, androidContext = appContext)
        client = c
        viewModelScope.launch {
            try {
                c.connect()
            } catch (e: RestconfException) {
                _lastError.value = e.message
            }
        }
    }

    /**
     * RESTCONF FEATURE (Part 4/4): called once [android.security.KeyChain.choosePrivateKeyAlias]
     * returns a real alias from the system cert picker (see
     * [RestconfExplorerScreen]'s `onPickClientCert`). Persists it on the
     * profile — same field [RestconfClient] already reads via `connect()` —
     * then tears down and re-opens the session so a MUTUAL_TLS/
     * CLIENT_CERTIFICATE connection picks the new identity up immediately
     * rather than waiting for the next cold start.
     */
    fun updateClientCertAlias(alias: String) {
        val current = _profile.value ?: return
        saveConfiguration(current.copy(restconfClientCertAlias = alias))
    }

    /**
     * RESTCONF FEATURE (Part 4/4): persists a full connection-configuration
     * edit from [RestconfConfigurationScreen] (host/port/credentials, auth
     * type + its fields, TLS/mTLS + certificate pins, custom headers,
     * HTTP/2/compression/keep-alive) and tears down + re-opens the session
     * so every change — not just the ones a reconnect happens to pick up
     * lazily — takes effect immediately. Same persist-then-reconnect shape
     * [updateClientCertAlias] used before this existed; that method now just
     * calls through to this one with a single-field copy.
     */
    fun saveConfiguration(updated: RdpProfile) {
        viewModelScope.launch {
            profileRepository.updateProfile(updated)
            _profile.value = updated
            client?.disconnect()
            connect(updated)
        }
    }

    fun send(method: RestconfMethod, path: String, body: String?, queryParams: Map<String, String> = emptyMap(), extraHeaders: Map<String, String> = emptyMap()) {
        val c = client ?: return
        viewModelScope.launch {
            _isSending.value = true
            _lastError.value = null
            try {
                val resp = c.execute(method, path, body = body?.takeIf { it.isNotBlank() }, queryParams = queryParams, extraHeaders = extraHeaders)
                _previousResponse.value = _lastResponse.value
                _lastResponse.value = resp
                _profile.value?.let { p ->
                    explorerRepository.recordHistory(
                        com.systemsgo.hex.data.model.RestconfHistoryEntry(
                            profileId = p.id, method = method.name, path = path,
                            statusCode = resp.statusCode, elapsedMillis = resp.elapsedMillis,
                        )
                    )
                }
            } catch (e: Exception) {
                _lastError.value = e.message ?: appContext.getString(R.string.restconf_error_request_failed)
            } finally {
                _isSending.value = false
            }
        }
    }

    /** SAF export — same `contentResolver.openOutputStream(uri)` pattern as [BmcManagementViewModel.exportSolLog]. */
    fun exportResponse(uri: android.net.Uri, appContext: android.content.Context) {
        val body = _lastResponse.value?.body ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(body.toByteArray())
                } ?: throw java.io.IOException("openOutputStream returned null")
            }.onFailure { e ->
                android.util.Log.e("RestconfExplorerViewModel", "exportResponse failed", e)
            }
        }
    }

    fun probeCapabilities() {
        val c = client ?: return
        viewModelScope.launch {
            _isSending.value = true
            _lastError.value = null
            try {
                val caps = c.getServerCapabilities()
                _lastResponse.value = RestconfResponse(
                    statusCode = 200, statusMessage = "OK", headers = emptyMap(),
                    body = caps.capabilities.joinToString("\n"),
                    contentType = "text/plain", requestUrl = caps.restconfRoot,
                    requestMethod = "GET", elapsedMillis = 0, sizeBytes = 0,
                )
            } catch (e: Exception) {
                _lastError.value = e.message ?: appContext.getString(R.string.restconf_error_capability_probe_failed)
            } finally {
                _isSending.value = false
            }
        }
    }

    fun probeYangModules() {
        val c = client ?: return
        viewModelScope.launch {
            _isSending.value = true
            _lastError.value = null
            try {
                val modules = c.getYangModules()
                _lastResponse.value = RestconfResponse(
                    statusCode = 200, statusMessage = "OK", headers = emptyMap(),
                    body = modules.joinToString("\n") { "${it.name}${it.revision?.let { r -> "@$r" } ?: ""}  (${it.namespace})" },
                    contentType = "text/plain", requestUrl = "yang-library",
                    requestMethod = "GET", elapsedMillis = 0, sizeBytes = 0,
                )
            } catch (e: Exception) {
                _lastError.value = e.message ?: appContext.getString(R.string.restconf_error_yang_discovery_failed)
            } finally {
                _isSending.value = false
            }
        }
    }

    // ── RESTCONF FEATURE (Part 3/4): YANG Browser actions ───────────────

    fun refreshYangModules() {
        val c = client ?: return
        viewModelScope.launch {
            _isLoadingModules.value = true
            _yangModules.value = runCatching { c.getYangModules() }.getOrElse {
                _lastError.value = it.message ?: appContext.getString(R.string.restconf_error_yang_discovery_failed)
                emptyList()
            }
            _isLoadingModules.value = false
        }
    }

    fun fetchDatastorePath(path: String) {
        val c = client ?: return
        viewModelScope.launch {
            _datastoreBody.value = runCatching { c.execute(RestconfMethod.GET, path).body }.getOrElse {
                _lastError.value = it.message ?: "Couldn't load $path"
                null
            }
        }
    }

    // ── RESTCONF FEATURE (Part 3/4): API Explorer actions — every one of
    // these is a real Room write via explorerRepository; the Flows started
    // in load() above pick the change up automatically.

    fun saveCurrentRequest(
        name: String, collectionId: String?, method: RestconfMethod, path: String,
        queryParams: Map<String, String>, headers: Map<String, String>, body: String, format: RestconfDataFormat,
    ) {
        val p = _profile.value ?: return
        viewModelScope.launch {
            explorerRepository.saveRequest(
                com.systemsgo.hex.data.model.RestconfSavedRequest(
                    profileId = p.id, collectionId = collectionId, name = name,
                    method = method.name, path = path,
                    queryParams = toHeaderLines(queryParams), headers = toHeaderLines(headers),
                    body = body, dataFormat = format.name,
                )
            )
        }
    }

    fun deleteSavedRequest(request: com.systemsgo.hex.data.model.RestconfSavedRequest) {
        viewModelScope.launch { explorerRepository.deleteRequest(request) }
    }

    fun toggleFavorite(request: com.systemsgo.hex.data.model.RestconfSavedRequest) {
        viewModelScope.launch { explorerRepository.toggleFavorite(request.id, !request.isFavorite) }
    }

    fun markRequestUsed(request: com.systemsgo.hex.data.model.RestconfSavedRequest) {
        viewModelScope.launch { explorerRepository.markUsed(request.id) }
    }

    fun createCollection(name: String) {
        val p = _profile.value ?: return
        viewModelScope.launch { explorerRepository.createCollection(p.id, name) }
    }

    fun deleteCollection(collection: com.systemsgo.hex.data.model.RestconfCollection) {
        viewModelScope.launch { explorerRepository.deleteCollection(collection) }
    }

    // ── RESTCONF FEATURE (Part 5): Environment Variables actions ────────

    fun createEnvironment(name: String) {
        val p = _profile.value ?: return
        viewModelScope.launch { explorerRepository.createEnvironment(p.id, name) }
    }

    fun saveEnvironment(environment: com.systemsgo.hex.data.model.RestconfEnvironment) {
        viewModelScope.launch { explorerRepository.saveEnvironment(environment) }
    }

    fun deleteEnvironment(environment: com.systemsgo.hex.data.model.RestconfEnvironment) {
        viewModelScope.launch { explorerRepository.deleteEnvironment(environment) }
    }

    /** [environment] = null clears the active environment; otherwise toggling the same active environment off is handled by the caller passing null (see [RestconfEnvironmentList]'s radio-tap semantics). */
    fun setActiveEnvironment(environment: com.systemsgo.hex.data.model.RestconfEnvironment?) {
        val p = _profile.value ?: return
        viewModelScope.launch { explorerRepository.setActiveEnvironment(p.id, environment?.id) }
    }

    // ── RESTCONF FEATURE (Part 5): Import/Export actions ─────────────────

    fun exportCollection(uri: android.net.Uri) {
        val p = _profile.value ?: return
        viewModelScope.launch {
            runCatching { collectionBackupManager.exportTo(uri, p.id) }
                .onSuccess { r -> _collectionBackupEvent.value = appContext.getString(R.string.restconf_export_summary, r.requestCount, r.collectionCount, r.environmentCount) }
                .onFailure { e -> _collectionBackupEvent.value = appContext.getString(R.string.restconf_error_export_failed, e.message ?: appContext.getString(R.string.restconf_error_unknown)) }
        }
    }

    fun importCollection(uri: android.net.Uri) {
        val p = _profile.value ?: return
        viewModelScope.launch {
            runCatching { collectionBackupManager.importFrom(uri, p.id) }
                .onSuccess { r ->
                    _collectionBackupEvent.value =
                        "Imported ${r.importedRequests} request(s) (${r.skippedRequests} already existed), ${r.importedCollections} collection(s), ${r.importedEnvironments} environment(s)."
                }
                .onFailure { e -> _collectionBackupEvent.value = appContext.getString(R.string.restconf_error_import_failed, e.message ?: appContext.getString(R.string.restconf_error_unknown)) }
        }
    }

    private fun toHeaderLines(map: Map<String, String>): String = map.entries.joinToString("\n") { (k, v) -> "$k: $v" }

    fun disconnect() {
        client?.shutdown()
        logId?.let { id -> viewModelScope.launch { logRepository.finish(id, disconnectReason = null, wasSuccessful = true) } }
    }
}

/** "Header-Name: value" per line — same encoding as [RdpProfile.restconfCustomHeaders] and [com.systemsgo.hex.data.model.RestconfSavedRequest.headers]/`queryParams`. File-level so both the ViewModel (session headers) and the Screen composable (loading a saved request back into the editor) can reuse it. */
private fun parseHeaderLines(raw: String): Map<String, String> =
    raw.lines().mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }.toMap()

/**
 * RESTCONF FEATURE (Part 4/4): opens the real Android Keystore/KeyChain
 * client-cert picker (system UI — no cert material is ever read into this
 * app's own storage, only the chosen *alias* is kept; see
 * [RestconfClient.buildKeyChainKeyManagers]) and hands the picked alias to
 * [onPicked]. Shared by the Request Builder's quick Auth tab (which used to
 * inline this and call [RestconfExplorerViewModel.updateClientCertAlias]
 * directly) and [RestconfConfigurationScreen] (which stages the alias in
 * local state instead, applying it only on Save like every other field
 * there) — same picker, different "what happens with the result".
 */
private fun launchClientCertPicker(context: Context, profile: RdpProfile, onPicked: (String) -> Unit) {
    val activity = context as? Activity
    if (activity == null) {
        android.util.Log.w("RestconfExplorer", "No Activity in context; cannot show KeyChain cert picker")
        return
    }
    val host = java.net.URI.create(
        "${if (profile.restconfUseHttps) "https" else "http"}://${profile.host}:${profile.port}"
    ).host
    KeyChain.choosePrivateKeyAlias(
        activity,
        { alias -> if (alias != null) onPicked(alias) },
        /* keyTypes = */ null,
        /* issuers = */ null,
        /* host = */ host,
        /* port = */ profile.port,
        /* alias = */ profile.restconfClientCertAlias.ifBlank { null },
    )
}

private enum class RequestTab { PARAMS, HEADERS, AUTH, BODY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestconfExplorerScreen(
    profile: RdpProfile,
    viewModel: RestconfExplorerViewModel,
    onFinish: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // RESTCONF FEATURE (Part 4/4): tablet/foldable/DeX support — reuses the
    // same `DeviceFormFactor` signal the app already gates Split Screen on
    // (sw600dp breakpoint, so an unfolded foldable counts too, OR desktop
    // windowing mode for Samsung DeX/Android Desktop Mode). On a large or
    // desktop-class surface the request builder and response viewer sit
    // side by side instead of stacked — the same content either way, just
    // laid out differently (see requestPane/responsePane below).
    val isLargeScreen = DeviceFormFactor.supportsDesktopFeatures(context)
    val connState by viewModel.connectionState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val response by viewModel.lastResponse.collectAsStateWithLifecycle()
    val previousResponse by viewModel.previousResponse.collectAsStateWithLifecycle()
    val baselineResponse by viewModel.baselineResponse.collectAsStateWithLifecycle()
    val error by viewModel.lastError.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val yangModules by viewModel.yangModules.collectAsStateWithLifecycle()
    val isLoadingModules by viewModel.isLoadingModules.collectAsStateWithLifecycle()
    val datastoreBody by viewModel.datastoreBody.collectAsStateWithLifecycle()
    val savedRequests by viewModel.savedRequests.collectAsStateWithLifecycle()
    val favoriteRequests by viewModel.favoriteRequests.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val environments by viewModel.environments.collectAsStateWithLifecycle()
    val environmentVariables by viewModel.environmentVariables.collectAsStateWithLifecycle()
    val collectionBackupEvent by viewModel.collectionBackupEvent.collectAsStateWithLifecycle()

    // RESTCONF FEATURE (Part 5): Import/Export feedback — a plain Toast is
    // enough here (there's no SnackbarHost on this Scaffold, unlike
    // SettingsScreen's backup flow); LaunchedEffect + immediately clearing
    // the event keeps it from re-showing on rotation/recomposition.
    LaunchedEffect(collectionBackupEvent) {
        collectionBackupEvent?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearCollectionBackupEvent()
        }
    }

    var mainTab by remember { mutableStateOf(MainTab.REQUEST) }
    var showApiExplorer by remember { mutableStateOf(false) }
    // RESTCONF FEATURE (Part 4/4): the dedicated Configuration Screen — a
    // full-screen replacement (not a bottom sheet like the API Explorer;
    // there's simply too much connection-level config for that shape) shown
    // in place of the Request/YANG Scaffold below when true. See the early
    // `if (showConfiguration) { ...; return }` right after this state block.
    var showConfiguration by remember { mutableStateOf(false) }

    var method by remember { mutableStateOf(RestconfMethod.GET) }
    var path by remember { mutableStateOf("/data") }
    var bodyText by remember { mutableStateOf("") }
    var methodMenuExpanded by remember { mutableStateOf(false) }
    var requestTab by remember { mutableStateOf(RequestTab.PARAMS) }

    var paramRows by remember { mutableStateOf(listOf<com.systemsgo.hex.restconf.ui.RestconfKeyValueRow>()) }
    var headerRows by remember { mutableStateOf(listOf<com.systemsgo.hex.restconf.ui.RestconfKeyValueRow>()) }

    // Per-request auth override (Part 2 scope): starts from the session's
    // configured auth (from the profile) so the tab shows what's actually in
    // effect; editing it here only changes the header attached to *this*
    // request (via authOverrideHeader below) — it doesn't rewrite the saved
    // profile. Persisting a changed auth type back to the profile is part of
    // the dedicated RESTCONF Configuration Screen (Part 4).
    var authType by remember { mutableStateOf(RestconfAuthType.fromName(profile.restconfAuthType)) }
    var authUsername by remember { mutableStateOf(profile.username) }
    var authPassword by remember { mutableStateOf(profile.password) }
    var authBearerToken by remember { mutableStateOf(profile.restconfBearerToken) }
    var authJwtToken by remember { mutableStateOf(profile.restconfJwtToken) }
    var authApiKeyHeaderName by remember { mutableStateOf(profile.restconfApiKeyHeaderName) }
    var authApiKeyValue by remember { mutableStateOf(profile.restconfApiKeyValue) }
    var authOAuth2TokenUrl by remember { mutableStateOf(profile.restconfOAuth2TokenUrl) }
    var authOAuth2ClientId by remember { mutableStateOf(profile.restconfOAuth2ClientId) }
    var authOAuth2ClientSecret by remember { mutableStateOf(profile.restconfOAuth2ClientSecret) }
    var authOAuth2Scope by remember { mutableStateOf(profile.restconfOAuth2Scope) }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*"),
    ) { uri -> uri?.let { viewModel.exportResponse(it, context) } }

    // RESTCONF FEATURE (Part 5): Import/Export — collection export writes
    // plain JSON (see RestconfCollectionBackupManager's doc comment on why
    // this one isn't encrypted, unlike ConnectionBackupManager); import
    // accepts either that same format or a Postman v2.1 collection.
    val exportCollectionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportCollection(it) } }
    val importCollectionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importCollection(it) } }

    // RESTCONF FEATURE (Part 4/4): hardware-keyboard support (tablet/DeX/
    // Chromebook/attached keyboard) — Ctrl+Enter sends the current request
    // from anywhere in the screen, mirroring the same shortcut in every
    // desktop REST client (Postman/Insomnia included) rather than forcing a
    // pointer tap on the Send icon every time.
    //
    // RESTCONF FEATURE (Part 5): path/body/headers/query-param VALUES are
    // resolved against the active environment right here, right before
    // send — so typing {{host}} straight into the path field (with no saved
    // request/template involved at all) still works, exactly like Postman's
    // "just type a variable and it resolves at send time" behavior.
    val sendRequest: () -> Unit = {
        val params = paramRows.filter { it.enabled && it.key.isNotBlank() }
            .associate { it.key to RestconfTemplateEngine.substitute(it.value, emptyMap(), environmentVariables) }
        val headers = headerRows.filter { it.enabled && it.key.isNotBlank() }
            .associate { it.key to RestconfTemplateEngine.substitute(it.value, emptyMap(), environmentVariables) } +
            authOverrideHeader(authType, authBearerToken, authJwtToken, authApiKeyHeaderName, authApiKeyValue)
        viewModel.send(
            method,
            RestconfTemplateEngine.substitute(path, emptyMap(), environmentVariables),
            RestconfTemplateEngine.substitute(bodyText, emptyMap(), environmentVariables),
            queryParams = params, extraHeaders = headers,
        )
    }

    // RESTCONF FEATURE (Part 4/4): full-screen swap, not an overlay — the
    // Configuration Screen owns its own Scaffold/TopAppBar/back action, so
    // showing it here replaces the Request/YANG Scaffold below entirely
    // rather than stacking on top of it. Saving persists via
    // RestconfExplorerViewModel.saveConfiguration (disconnect + reconnect
    // with the edited profile) and returns to the Request tab.
    if (showConfiguration) {
        com.systemsgo.hex.restconf.ui.RestconfConfigurationScreen(
            profile = profile,
            connectionState = connState,
            isLargeScreen = isLargeScreen,
            onBack = { showConfiguration = false },
            onSave = { updated ->
                viewModel.saveConfiguration(updated)
                showConfiguration = false
            },
            onPickClientCert = { onAliasPicked -> launchClientCertPicker(context, profile, onAliasPicked) },
        )
        return
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { event ->
            when {
                event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter && !isSending -> {
                    sendRequest(); true
                }
                event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.K -> {
                    showApiExplorer = true; true
                }
                event.type == KeyEventType.KeyDown && event.key == Key.Escape && showApiExplorer -> {
                    showApiExplorer = false; true
                }
                else -> false
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(profile.name.ifBlank { profile.host }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            connectionStateLabel(connState),
                            style = MaterialTheme.typography.labelSmall,
                            color = connectionStateColor(connState),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onFinish) { Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.restconf_back)) }
                },
                actions = {
                    IconButton(onClick = { mainTab = if (mainTab == MainTab.MONITOR) MainTab.REQUEST else MainTab.MONITOR }) {
                        Icon(Icons.Outlined.Analytics, contentDescription = stringResource(R.string.restconf_monitoring_dashboard))
                    }
                    IconButton(onClick = { mainTab = if (mainTab == MainTab.YANG) MainTab.REQUEST else MainTab.YANG }) {
                        Icon(Icons.Outlined.AccountTree, contentDescription = stringResource(R.string.restconf_yang_browser))
                    }
                    IconButton(onClick = { showApiExplorer = true }) {
                        Icon(Icons.Outlined.Folder, contentDescription = stringResource(R.string.restconf_api_explorer))
                    }
                    IconButton(onClick = { showConfiguration = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.restconf_configuration))
                    }
                },
            )
        },
    ) { padding ->
      if (mainTab == MainTab.MONITOR) {
        Box(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            com.systemsgo.hex.restconf.ui.RestconfMonitoringDashboard(
                connectionState = connState,
                stats = stats,
                history = history,
                modifier = Modifier.fillMaxSize(),
            )
        }
      } else if (mainTab == MainTab.YANG) {
        Box(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            com.systemsgo.hex.restconf.ui.RestconfYangBrowser(
                modules = yangModules,
                isLoadingModules = isLoadingModules,
                onRefreshModules = { viewModel.refreshYangModules() },
                onFetchDatastorePath = { p -> viewModel.fetchDatastorePath(p) },
                datastoreResponseBody = datastoreBody,
                datastoreFormat = RestconfDataFormat.fromName(profile.restconfDataFormat),
                modifier = Modifier.fillMaxSize(),
            )
        }
      } else {
        // RESTCONF FEATURE (Part 4/4): both panes are plain `@Composable
        // ColumnScope.() -> Unit` blocks with zero layout logic of their own
        // — every response-pane element that needs `Modifier.weight(1f)`
        // still resolves against whichever Column actually hosts it below,
        // so the same two blocks work unmodified whether they're stacked
        // (phone) or side by side (tablet/foldable/DeX).
        val requestPane: @Composable ColumnScope.() -> Unit = {
            // ── URL builder: method + path + Send ───────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    AssistChip(onClick = { methodMenuExpanded = true }, label = { Text(method.name) })
                    DropdownMenu(expanded = methodMenuExpanded, onDismissRequest = { methodMenuExpanded = false }) {
                        RestconfMethod.entries.forEach { m ->
                            DropdownMenuItem(text = { Text(m.name) }, onClick = { method = m; methodMenuExpanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = path, onValueChange = { path = it },
                    label = { Text(stringResource(R.string.restconf_resource_path)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = sendRequest,
                    enabled = !isSending,
                ) { Icon(Icons.Outlined.Send, contentDescription = stringResource(R.string.restconf_send)) }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                AssistChip(onClick = { viewModel.probeCapabilities() }, label = { Text(stringResource(R.string.restconf_capabilities)) })
                AssistChip(onClick = { viewModel.probeYangModules() }, label = { Text(stringResource(R.string.restconf_yang_modules)) })
            }

            Spacer(Modifier.height(8.dp))

            // ── Request Builder tabs: Params / Headers / Auth / Body ───
            TabRow(selectedTabIndex = requestTab.ordinal) {
                RequestTab.entries.forEach { t ->
                    Tab(selected = requestTab == t, onClick = { requestTab = t }, text = { Text(t.name) })
                }
            }
            Box(
                Modifier.fillMaxWidth()
                    .let { if (isLargeScreen) it.weight(1f) else it.heightIn(min = 60.dp, max = 220.dp) }
                    .padding(top = 4.dp)
            ) {
                when (requestTab) {
                    RequestTab.PARAMS -> com.systemsgo.hex.restconf.ui.RestconfKeyValueEditor(
                        rows = paramRows, onRowsChange = { paramRows = it },
                        keyLabel = stringResource(R.string.restconf_param_name), valueLabel = stringResource(R.string.restconf_header_value),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                    RequestTab.HEADERS -> com.systemsgo.hex.restconf.ui.RestconfKeyValueEditor(
                        rows = headerRows, onRowsChange = { headerRows = it },
                        keyLabel = stringResource(R.string.restconf_header_name), valueLabel = stringResource(R.string.restconf_header_value),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                    RequestTab.AUTH -> com.systemsgo.hex.restconf.ui.RestconfAuthEditor(
                        authType = authType, onAuthTypeChange = { authType = it },
                        username = authUsername, onUsernameChange = { authUsername = it },
                        password = authPassword, onPasswordChange = { authPassword = it },
                        bearerToken = authBearerToken, onBearerTokenChange = { authBearerToken = it },
                        jwtToken = authJwtToken, onJwtTokenChange = { authJwtToken = it },
                        apiKeyHeaderName = authApiKeyHeaderName, onApiKeyHeaderNameChange = { authApiKeyHeaderName = it },
                        apiKeyValue = authApiKeyValue, onApiKeyValueChange = { authApiKeyValue = it },
                        oauth2TokenUrl = authOAuth2TokenUrl, onOAuth2TokenUrlChange = { authOAuth2TokenUrl = it },
                        oauth2ClientId = authOAuth2ClientId, onOAuth2ClientIdChange = { authOAuth2ClientId = it },
                        oauth2ClientSecret = authOAuth2ClientSecret, onOAuth2ClientSecretChange = { authOAuth2ClientSecret = it },
                        oauth2Scope = authOAuth2Scope, onOAuth2ScopeChange = { authOAuth2Scope = it },
                        clientCertAlias = profile.restconfClientCertAlias,
                        onPickClientCert = {
                            launchClientCertPicker(context, profile) { alias -> viewModel.updateClientCertAlias(alias) }
                        },
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                    RequestTab.BODY -> com.systemsgo.hex.restconf.ui.RestconfBodyEditor(
                        text = bodyText, onTextChange = { bodyText = it },
                        format = RestconfDataFormat.fromName(profile.restconfDataFormat),
                    )
                }
            }
        }

        val responsePane: @Composable ColumnScope.() -> Unit = {
            // ── Response Viewer: Pretty / Tree / Raw / Headers, copy + export ──
            if (isSending) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            error?.let { err ->
                Text(err, color = NovaPink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            }
            response?.let { resp ->
                com.systemsgo.hex.restconf.ui.RestconfResponseViewer(
                    response = resp,
                    onExport = { exportLauncher.launch(exportFileName(resp)) },
                    previousResponse = previousResponse,
                    baselineResponse = baselineResponse,
                    onPinBaseline = { viewModel.pinBaseline() },
                    onClearBaseline = { viewModel.clearBaseline() },
                    modifier = Modifier.weight(1f),
                )
            } ?: Spacer(Modifier.weight(1f))

            // ── Monitoring footer (requirement: connection state / requests / errors / latency) ──
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.restconf_stat_requests, stats.requestCount), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.restconf_stat_errors, stats.errorCount), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.restconf_stat_avg_latency, stats.averageLatencyMillis), style = MaterialTheme.typography.labelSmall)
                stats.lastTlsVersion?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }

        if (isLargeScreen) {
            // Tablet/unfolded-foldable/DeX/Desktop-Mode layout: request
            // builder and response viewer side by side (each its own
            // independently-scrolling pane) instead of stacked — the same
            // real-time request/response loop, just using the width a
            // desktop-class surface actually has instead of wasting it on
            // a single narrow phone-width column.
            Row(Modifier.padding(padding).fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) { requestPane() }
                Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(Modifier.weight(1f).fillMaxHeight()) { responsePane() }
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
                requestPane()
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                responsePane()
            }
        }
      }
    }

    if (showApiExplorer) {
        ModalBottomSheet(onDismissRequest = { showApiExplorer = false }) {
            com.systemsgo.hex.restconf.ui.RestconfApiExplorerSheet(
                savedRequests = savedRequests,
                favoriteRequests = favoriteRequests,
                history = history,
                collections = collections,
                onLoadRequest = { req ->
                    method = RestconfMethod.valueOf(req.method)
                    path = req.path
                    bodyText = req.body
                    paramRows = parseHeaderLines(req.queryParams).map { (k, v) -> com.systemsgo.hex.restconf.ui.RestconfKeyValueRow(k, v) }
                    headerRows = parseHeaderLines(req.headers).map { (k, v) -> com.systemsgo.hex.restconf.ui.RestconfKeyValueRow(k, v) }
                    viewModel.markRequestUsed(req)
                    showApiExplorer = false
                },
                onDeleteRequest = { viewModel.deleteSavedRequest(it) },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onCreateCollection = { viewModel.createCollection(it) },
                onDeleteCollection = { viewModel.deleteCollection(it) },
                onReplayHistoryEntry = { entry ->
                    method = RestconfMethod.valueOf(entry.method)
                    path = entry.path
                    showApiExplorer = false
                },
                onSaveCurrentRequest = { name, collectionId ->
                    val params = paramRows.filter { it.enabled && it.key.isNotBlank() }.associate { it.key to it.value }
                    val headers = headerRows.filter { it.enabled && it.key.isNotBlank() }.associate { it.key to it.value }
                    viewModel.saveCurrentRequest(
                        name, collectionId, method, path, params, headers, bodyText,
                        RestconfDataFormat.fromName(profile.restconfDataFormat),
                    )
                },
                environments = environments,
                environmentVariables = environmentVariables,
                onCreateEnvironment = { viewModel.createEnvironment(it) },
                onSetActiveEnvironment = { viewModel.setActiveEnvironment(it) },
                onSaveEnvironment = { viewModel.saveEnvironment(it) },
                onDeleteEnvironment = { viewModel.deleteEnvironment(it) },
                onExportCollection = { exportCollectionLauncher.launch("restconf-collection-${profile.name.ifBlank { profile.host }}.json") },
                onImportCollection = { importCollectionLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private enum class MainTab { REQUEST, YANG, MONITOR }

/** Only BEARER_TOKEN/JWT/API_KEY/CUSTOM_HEADER are meaningfully overridable per-request without a full reconnect (Basic/Digest/OAuth2/mTLS are connection-level) — see the Auth tab's doc comment above. */
private fun authOverrideHeader(
    type: RestconfAuthType,
    bearerToken: String,
    jwtToken: String,
    apiKeyHeaderName: String,
    apiKeyValue: String,
): Map<String, String> = when (type) {
    RestconfAuthType.BEARER_TOKEN -> mapOf("Authorization" to "Bearer $bearerToken")
    RestconfAuthType.JWT -> mapOf("Authorization" to "Bearer $jwtToken")
    RestconfAuthType.API_KEY -> mapOf(apiKeyHeaderName to apiKeyValue)
    else -> emptyMap()
}

private fun exportFileName(response: RestconfResponse): String =
    if (response.detectedFormat == RestconfDataFormat.XML) "restconf-response.xml" else "restconf-response.json"

@Composable
private fun connectionStateLabel(state: RestconfConnectionState): String = when (state) {
    RestconfConnectionState.DISCONNECTED -> stringResource(R.string.restconf_state_disconnected)
    RestconfConnectionState.CONNECTING -> stringResource(R.string.restconf_state_connecting)
    RestconfConnectionState.CONNECTED -> stringResource(R.string.restconf_state_connected)
    RestconfConnectionState.RECONNECTING -> stringResource(R.string.restconf_state_reconnecting)
    RestconfConnectionState.ERROR -> stringResource(R.string.restconf_state_connection_error)
}

private fun connectionStateColor(state: RestconfConnectionState): Color = when (state) {
    RestconfConnectionState.CONNECTED -> PlasmaGreen
    RestconfConnectionState.CONNECTING, RestconfConnectionState.RECONNECTING -> SolarFlare
    RestconfConnectionState.ERROR -> NovaPink
    RestconfConnectionState.DISCONNECTED -> PulsarCyan
}
