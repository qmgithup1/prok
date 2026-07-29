package com.systemsgo.hex.web

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ConnectionLog
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.ConnectionLogRepository
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.ui.theme.SystemsGoTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WEB-PORTAL FEATURE: embedded-browser session for a [ProtocolType.WEB]
 * profile — RD Web Access (webfeed.aspx / RDWeb), or any other HTTPS
 * management portal (Guacamole, ESXi/vCenter, iDRAC/iLO, Proxmox, pfSense,
 * ...). Deliberately separate from [com.systemsgo.hex.ui.screens.RdpSessionActivity]:
 * a web portal has no framebuffer and no [com.systemsgo.hex.remote.RemoteSessionClient]
 * behind it, just a WebView, so it gets its own small, self-contained
 * Activity instead of shoehorning a fifth branch into that 5000+ line one.
 *
 * Launched only via [com.systemsgo.hex.remote.SessionLauncher.intentFor] with
 * a "profile_id" extra pointing at an existing, locally-saved [RdpProfile]
 * (same trust boundary as RdpSessionActivity's pinned-shortcut / .rdp
 * ACTION_VIEW intents — no URL or credential is ever accepted from outside
 * this app's own database).
 */
@AndroidEntryPoint
class WebPortalActivity : AppCompatActivity() {

    private val viewModel: WebPortalViewModel by viewModels()

    private var webViewRef: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profileId = intent.getStringExtra("profile_id") ?: run { finish(); return }
        viewModel.load(profileId)

        // HOME-SCREEN-SHORTCUTS FEATURE: same gate RdpSessionActivity applies —
        // a pinned Web-portal shortcut must not be a way to reach a saved
        // portal (and whatever's logged into it) without passing App Lock.
        // A normal in-app "Connect" tap already passed through MainActivity's
        // own lock screen, so this only re-prompts for the shortcut path.
        val fromShortcut = intent.getBooleanExtra("from_shortcut", false)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val lockRequired = fromShortcut && (settings.biometricLockEnabled || settings.pinLockEnabled)
            var isUnlocked by remember { mutableStateOf(false) }
            LaunchedEffect(lockRequired) {
                if (!lockRequired) isUnlocked = true
            }

            SystemsGoTheme(darkTheme = settings.isDarkMode, themeVariant = settings.themeVariant) {
                val profile by viewModel.profile.collectAsStateWithLifecycle()
                val p = profile
                Box(Modifier.fillMaxSize()) {
                    if (p == null || !isUnlocked) {
                        // Still loading the profile, or waiting on App Lock —
                        // either way, nothing sensitive (URL, credentials) is
                        // visible underneath yet, same as RdpSessionActivity's
                        // equivalent overlay.
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    } else {
                        WebPortalScreen(
                            profile   = p,
                            onFinish  = { finish() },
                            onWebViewReady = { webViewRef = it },
                        )
                    }
                    if (lockRequired) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible  = !isUnlocked,
                            enter    = androidx.compose.animation.fadeIn(),
                            exit     = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            com.systemsgo.hex.ui.screens.AppLockScreen(
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

    // Let the system (hardware) back button/gesture navigate the WebView's
    // own history before leaving the Activity — same expectation a user has
    // of any browser. BackHandler inside WebPortalScreen handles the
    // Compose-side predictive-back case; this covers the raw KeyEvent path
    // some devices/launchers still deliver directly to the Activity.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            webViewRef?.let { if (it.canGoBack()) { it.goBack(); return true } }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        // Persist cookies (session cookies for the portal login, RDWeb feed
        // auth, etc.) across process death / app restarts, the same
        // guarantee a real browser gives a bookmarked portal.
        CookieManager.getInstance().flush()
    }
}

/**
 * Loads the [RdpProfile] behind this session and owns the one-row
 * [ConnectionLog] entry for it, mirroring RdpSessionViewModel's logging
 * shape (start on first successful load, finish on ViewModel clear) so Web
 * sessions show up in Connection History exactly like RDP/VNC/SSH/Telnet
 * ones do.
 */
@HiltViewModel
class WebPortalViewModel @Inject constructor(
    private val profileRepository: RdpProfileRepository,
    private val logRepository: ConnectionLogRepository,
    private val settingsRepository: com.systemsgo.hex.data.repository.AppSettingsRepository,
) : ViewModel() {

    private val _profile = MutableStateFlow<RdpProfile?>(null)
    val profile: StateFlow<RdpProfile?> = _profile.asStateFlow()

    // HOME-SCREEN-SHORTCUTS FEATURE: same App Lock gate RdpSessionActivity
    // applies for a pinned-shortcut launch — see its settings/lockRequired
    // doc comment. Seeded synchronously with the real persisted snapshot for
    // the same reason (FLASH-FIX): no loading-state flicker on first frame.
    val settings: StateFlow<com.systemsgo.hex.data.repository.AppSettings> =
        settingsRepository.settingsFlow.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, settingsRepository.currentSettingsSnapshot()
        )

    private var logId: String? = null
    private var loggedResult = false

    fun load(profileId: String) {
        viewModelScope.launch {
            val loaded = profileRepository.getProfileById(profileId)
            _profile.value = loaded
            if (loaded != null) {
                logId = logRepository.start(
                    ConnectionLog(
                        profileId    = loaded.id,
                        profileName  = loaded.name,
                        host         = loaded.host.ifBlank { Uri.parse(loaded.webUrl.ifBlank { "https://" }).host ?: "" },
                        port         = loaded.port.takeIf { it > 0 } ?: ProtocolType.WEB.defaultPort,
                        protocolType = ProtocolType.WEB,
                    )
                )
            }
        }
    }

    /** Called by the WebView's own load callbacks — see WebPortalScreen. */
    fun reportResult(success: Boolean, reason: String? = null) {
        if (loggedResult) return
        loggedResult = success || reason != null
        val id = logId ?: return
        viewModelScope.launch { logRepository.finish(id, reason, success) }
    }

    override fun onCleared() {
        super.onCleared()
        val id = logId ?: return
        if (!loggedResult) {
            // Activity finished (user backed out) without the WebView ever
            // reporting a definite success/failure — log it as a clean,
            // user-initiated disconnect rather than leaving an orphaned
            // "still active" row for closeOrphanedLogs() to clean up later.
            // viewModelScope is already cancelled by the time onCleared runs,
            // so this one closing write uses a short-lived scope of its own
            // (same one-shot, fire-and-forget shape RdpSessionService uses
            // for its own end-of-life bookkeeping).
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                logRepository.finish(id, "", wasSuccessful = true)
            }
        }
    }
}

private enum class LoadError { NONE, HOST_NOT_FOUND, TIMEOUT, CONNECTION_REFUSED, SSL, GENERIC }

@Composable
private fun WebPortalScreen(
    profile: RdpProfile,
    onFinish: () -> Unit,
    onWebViewReady: (WebView) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: WebPortalViewModel = viewModel()

    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var pageTitle by remember { mutableStateOf(profile.name) }
    var loadError by remember { mutableStateOf(LoadError.NONE) }
    var pendingCertDecision by remember { mutableStateOf<PendingCertDecision?>(null) }
    var pendingAuthRequest by remember { mutableStateOf<PendingAuthRequest?>(null) }

    // SECURITY FIX: the portal's own configured host — credential autofill
    // (JS injection in onPageFinished) and automatic HTTP-Basic/Digest
    // replies (onReceivedHttpAuthRequest) must never fire for any host
    // other than this one. Without this check, a redirect, an on-page link,
    // or an embedded iframe pointing at a different host (whether via an
    // open redirect on the portal, a compromised portal, or just a "help"
    // link the portal author added) would silently receive this profile's
    // saved username/password, since navigation inside this WebView is not
    // otherwise restricted to the original origin.
    val portalHost = remember(profile.webUrl) { Uri.parse(profile.webUrl).host }
    fun isPortalHostName(candidateHost: String?): Boolean =
        portalHost != null && candidateHost != null && candidateHost.equals(portalHost, ignoreCase = true)
    fun isPortalUrl(candidateUrl: String?): Boolean =
        isPortalHostName(candidateUrl?.let { runCatching { Uri.parse(it).host }.getOrNull() })
    var webViewRefState by remember { mutableStateOf<WebView?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    BackHandler(enabled = canGoBack) { webViewRefState?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(pageTitle, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            profile.webUrl,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onFinish) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { webViewRefState?.goForward() }, enabled = canGoForward) {
                        Icon(Icons.Outlined.ArrowForward, contentDescription = stringResource(R.string.cd_web_forward))
                    }
                    IconButton(onClick = { webViewRefState?.reload(); loadError = LoadError.NONE }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.cd_web_refresh))
                    }
                    IconButton(onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webViewRefState?.url ?: profile.webUrl))
                        context.startActivity(browserIntent)
                    }) {
                        Icon(Icons.Outlined.OpenInBrowser, contentDescription = stringResource(R.string.web_open_in_browser))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (loadError == LoadError.NONE) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.saveFormData = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webChromeClient = object : android.webkit.WebChromeClient() {
                                // WEB-PORTAL-FAVICON FEATURE: cache the
                                // portal's own favicon for the connection
                                // card — see WebPortalFaviconCache's doc
                                // comment. Fire-and-forget on the ViewModel's
                                // scope (outlives this composition's own
                                // recomposition but not the Activity), same
                                // "best-effort, never blocks the WebView"
                                // shape as the cache's own save().
                                override fun onReceivedIcon(view: WebView, icon: android.graphics.Bitmap?) {
                                    if (icon == null) return
                                    viewModel.viewModelScope.launch {
                                        com.systemsgo.hex.web.WebPortalFaviconCache.save(context.applicationContext, profile.id, icon)
                                    }
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    isLoading = true
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    isLoading = false
                                    canGoBack = view.canGoBack()
                                    canGoForward = view.canGoForward()
                                    pageTitle = view.title?.takeIf { it.isNotBlank() } ?: profile.name
                                    viewModel.reportResult(success = true)

                                    // WEB-PORTAL-SMART-AUTOFILL FEATURE: see
                                    // WebPortalLoginAutofill's doc comment for
                                    // why this only fills, never submits, and
                                    // is safe to fire on every page load
                                    // (idempotent past the first successful
                                    // fill, no-ops on unrecognized markup).
                                    if (profile.webAutoFillLoginForm && profile.username.isNotBlank() &&
                                        isPortalUrl(url)
                                    ) {
                                        view.evaluateJavascript(
                                            WebPortalLoginAutofill.buildScript(profile.username, profile.password),
                                            null,
                                        )
                                    }
                                }

                                // ERROR-TRANSLATION FEATURE: map WebView's raw
                                // error codes to the same plain-language,
                                // user-facing categories the gap analysis
                                // flags as missing for RDP/VNC/SSH — never
                                // surface a bare WebResourceError/net::ERR_*
                                // string to the user.
                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    if (!request.isForMainFrame) return
                                    loadError = when (error.errorCode) {
                                        ERROR_HOST_LOOKUP -> LoadError.HOST_NOT_FOUND
                                        ERROR_TIMEOUT -> LoadError.TIMEOUT
                                        ERROR_CONNECT, ERROR_UNKNOWN -> LoadError.CONNECTION_REFUSED
                                        ERROR_FAILED_SSL_HANDSHAKE -> LoadError.SSL
                                        else -> LoadError.GENERIC
                                    }
                                    viewModel.reportResult(success = false, reason = error.description?.toString())
                                }

                                // UNTRUSTED-CERT FEATURE: mirrors the
                                // CertificateChallenge flow RDP/VNC/SSH/Telnet
                                // already use — never silently accept an
                                // untrusted certificate. profile.webTrustSelfSignedCertificate
                                // (BUG-3-style, off-by-default opt-in) skips
                                // the prompt for portals the user has already
                                // told this profile to trust; otherwise the
                                // user sees the certificate host and decides,
                                // once, for this load.
                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    if (profile.webTrustSelfSignedCertificate) {
                                        handler.proceed()
                                        return
                                    }
                                    pendingCertDecision = PendingCertDecision(handler, error.url ?: profile.webUrl)
                                }

                                override fun onReceivedHttpAuthRequest(
                                    view: WebView,
                                    handler: HttpAuthHandler,
                                    host: String?,
                                    realm: String?,
                                ) {
                                    if (profile.webAutoFillHttpAuth &&
                                        profile.username.isNotBlank() &&
                                        !handler.useHttpAuthUsernamePassword() &&
                                        isPortalHostName(host)
                                    ) {
                                        handler.proceed(profile.username, profile.password)
                                        return
                                    }
                                    pendingAuthRequest = PendingAuthRequest(handler, host ?: profile.webUrl)
                                }
                            }

                            loadUrl(profile.webUrl)
                            webViewRefState = this
                            onWebViewReady(this)
                        }
                    },
                    update = { /* no-op: navigation happens via webViewRefState, not recomposition */ },
                    key = reloadKey,
                )

                if (isLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.web_portal_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                WebPortalErrorView(
                    error = loadError,
                    onRetry = {
                        loadError = LoadError.NONE
                        reloadKey++
                    },
                )
            }
        }
    }

    pendingCertDecision?.let { pending ->
        AlertDialog(
            onDismissRequest = { pending.handler.cancel(); pendingCertDecision = null },
            icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            title = { Text(stringResource(R.string.web_cert_untrusted_title)) },
            text = { Text(stringResource(R.string.web_cert_untrusted_message, Uri.parse(pending.url).host ?: pending.url)) },
            confirmButton = {
                TextButton(onClick = { pending.handler.proceed(); pendingCertDecision = null }) {
                    Text(stringResource(R.string.web_cert_trust_once))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending.handler.cancel(); pendingCertDecision = null }) {
                    Text(stringResource(R.string.web_cert_reject))
                }
            },
        )
    }

    pendingAuthRequest?.let { pending ->
        WebPortalAuthDialog(
            host = pending.host,
            initialUsername = profile.username,
            onSubmit = { user, pass -> pending.handler.proceed(user, pass); pendingAuthRequest = null },
            onDismiss = { pending.handler.cancel(); pendingAuthRequest = null },
        )
    }
}

private class PendingCertDecision(val handler: SslErrorHandler, val url: String)
private class PendingAuthRequest(val handler: HttpAuthHandler, val host: String)

@Composable
private fun WebPortalAuthDialog(
    host: String,
    initialUsername: String,
    onSubmit: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    // SECURITY FIX: contains a password field — see security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()
    var user by remember { mutableStateOf(initialUsername) }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_http_auth_title, host)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.web_http_auth_username)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.web_http_auth_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(user, pass) }) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun WebPortalErrorView(error: LoadError, onRetry: () -> Unit) {
    val messageRes = when (error) {
        LoadError.HOST_NOT_FOUND -> R.string.web_error_host_not_found
        LoadError.TIMEOUT -> R.string.web_error_timeout
        LoadError.CONNECTION_REFUSED -> R.string.web_error_connection_refused
        LoadError.SSL -> R.string.web_error_ssl
        else -> R.string.web_error_generic
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.web_error_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(messageRes), style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.web_error_retry)) }
    }
}
