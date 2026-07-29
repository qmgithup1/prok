package com.systemsgo.hex.ui

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.provider.OpenableColumns
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemsgo.hex.audio.SoundManager
import com.systemsgo.hex.data.backup.ConnectionBackupManager
import com.systemsgo.hex.data.model.ConnectionFolder
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.AppSettingsRepository
import com.systemsgo.hex.data.repository.AppSettings
import com.systemsgo.hex.data.repository.ConnectionFolderRepository
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.R
import com.systemsgo.hex.util.RdpFileParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// Sentinel selectedFolderId value meaning "connections with no folder",
// distinct from selectedFolderId == null which means "no folder filter / All".
const val UNFILED_FOLDER_ID = "__unfiled__"

data class HomeUiState(
    val profiles: List<RdpProfile> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val showFirstLaunchDialog: Boolean = false,
    val networkQuality: NetworkQuality = NetworkQuality.UNKNOWN,
    // VPN-AWARE-CONNECTIVITY: live VPN status shown before connecting (see
    // HomeScreen's VpnStatusBanner) and used to decide whether to warn the
    // user that a saved host may only be reachable through their VPN.
    val vpnStatus: com.systemsgo.hex.util.VpnConnectivityManager.VpnStatus =
        com.systemsgo.hex.util.VpnConnectivityManager.VpnStatus.INACTIVE,
    val isLoading: Boolean = true,
    // ── Folders & Tags ───────────────────────────────────────────────────────
    val folders: List<ConnectionFolder> = emptyList(),
    // null = no folder filter applied ("All"). See UNFILED_FOLDER_ID above
    // for the "no folder assigned" case.
    val selectedFolderId: String? = null,
    // null = no tag filter applied ("All").
    val selectedTag: String? = null,
    // FAVORITES FEATURE: when true, only favorited connections are shown
    // (layered on top of the folder/tag filters above, same as the protocol
    // tab and search filters applied in HomeScreen).
    val showFavoritesOnly: Boolean = false,
    // GUEST-MODE FEATURE: true while the app is presenting the isolated,
    // temporary Guest profile instead of the real primary data — see the
    // "Forgot PIN?" flow in AppLockScreen and MainViewModel.enterGuestMode().
    // When true, `profiles`/`folders`/`settings` above are already the
    // Guest-scoped (empty/default) values, never the primary user's data.
    val isGuestMode: Boolean = false,
)

enum class NetworkQuality { UNKNOWN, POOR, FAIR, GOOD, EXCELLENT }

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: RdpProfileRepository,
    private val settingsRepository: AppSettingsRepository,
    private val folderRepository: ConnectionFolderRepository,
    val soundManager: SoundManager,
    private val connectionLogRepository: com.systemsgo.hex.data.repository.ConnectionLogRepository,
    private val sessionTabManager: com.systemsgo.hex.session.SessionTabManager,
    private val backupManager: ConnectionBackupManager,
    // QUICK-SETTINGS-TILE FEATURE (Part 1/2): only used in deleteProfile()
    // below, mirroring ShortcutHelper.disableShortcut()'s cleanup.
    private val qsTilePreferences: com.systemsgo.hex.data.repository.QsTilePreferences,
    // HOME-SCREEN-WIDGET FEATURE (Part 2/2): same "only used in
    // deleteProfile() below" shape as qsTilePreferences immediately above —
    // see WidgetPreferences.clearIfSelected's doc comment for why a
    // SINGLE_CONNECTION widget instance needs this cleanup and a
    // CONNECTION_LIST instance doesn't.
    private val widgetPreferences: com.systemsgo.hex.data.repository.WidgetPreferences,
    // ENTRA-ID-AUTH FEATURE — Part 2/2: wired here (rather than a dedicated
    // ViewModel) so HomeScreen/DeviceDiscoveryScreen's ProfileFormDialog
    // call sites can reach sign-in through the same MainViewModel they
    // already hold, without passing a second ViewModel down into Compose
    // (see the "never pass ViewModel instances down" guidance) — only
    // `signInWithMicrosoft`/`signOutMicrosoft`/`entraSignInPending` are
    // exposed to the UI, not these two collaborators themselves.
    private val entraIdAuthManager: com.systemsgo.hex.auth.EntraIdAuthManager,
    private val entraSignInLinkStore: com.systemsgo.hex.auth.EntraSignInLinkStore,
) : ViewModel() {

    // FLASH-FIX: was MutableStateFlow(HomeUiState()) — i.e. AppSettings()
    // defaults (isDarkMode=true, language="system", etc.) with isLoading=true,
    // shown for the first frame(s) every time this ViewModel is constructed
    // fresh (notably right after a language-change-triggered Activity
    // recreate). That produced exactly the reported glitch: the dark-mode
    // toggle (or PIN screen text) would flash to the default state and then
    // "correct" itself a moment later once settingsFlow's first emission
    // landed. Seeding with a synchronous snapshot of the real persisted
    // settings means the very first frame is already correct — no flash,
    // no flip-back.
    private val _uiState = MutableStateFlow(
        HomeUiState(settings = settingsRepository.currentSettingsSnapshot(), isLoading = false)
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // PERF-FIX (recomposition scope): every Settings sub-screen used to collect
    // the *whole* HomeUiState (profiles + settings + networkQuality + isLoading)
    // just to read `.settings` — meaning each one recomposed on every profile
    // list change or network-quality tick, even though it displays neither.
    // This narrower StateFlow is derived directly from the settings repository
    // (independent of the profiles/network combine below), so screens that only
    // care about settings can collect just this and skip those unrelated
    // invalidations entirely.
    val settingsState: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.currentSettingsSnapshot())

    // ── Feature-05: live session tabs ─────────────────────────────────────────
    // GUEST-MODE ISOLATION FIX: sessionTabManager.tabs/.activeTabId used to be
    // exposed here completely unfiltered — every open RDP/VNC/SSH session
    // (including host/username in its title) stayed visible in the Active
    // Sessions bar after switching to Guest Mode, even though profiles,
    // folders, and history all correctly went empty. SessionTabManager is a
    // process-wide Singleton with no notion of "current user" by itself, so
    // filtering has to happen here, the same way connectionLogs (above) is
    // forced empty under Guest — see SessionTab.isGuest / setGuestContext.
    val sessionTabs: StateFlow<List<com.systemsgo.hex.session.SessionTab>> =
        combine(sessionTabManager.tabs, _isGuestMode) { tabs, guest ->
            tabs.filter { it.isGuest == guest }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeTabId: StateFlow<String?> =
        combine(sessionTabManager.activeTabId, sessionTabs) { activeId, visibleTabs ->
            // Never surface an active tab id that belongs to the other
            // profile context — it would make HomeScreen try to render/select
            // a tab that isn't in the (correctly filtered) list above.
            activeId?.takeIf { id -> visibleTabs.any { it.tabId == id } }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // CONNECTION-STATUS-INDICATOR FEATURE: one CardStatusInfo per profile id
    // that currently has either a live tab or a cached recent failure —
    // profiles with neither simply have no entry, which HomeScreen/the card
    // composables treat as CardStatusInfo.Offline (cheaper than materializing
    // an Offline entry for every profile in the account). Purely event-driven
    // (StateFlow.combine over sessionTabs + sessionTabManager.lastFailures,
    // both already StateFlow-backed with no polling anywhere in the chain) —
    // recomputes only when either source actually changes, and each card only
    // recomposes when its own map entry's value changes (see resolveCardStatus
    // in CardConnectionStatus.kt for the actual per-profile mapping logic).
    private val authFailureHint = context.getString(R.string.session_tab_auth_failed)
    val cardStatuses: StateFlow<Map<String, com.systemsgo.hex.session.CardStatusInfo>> =
        combine(sessionTabs, sessionTabManager.lastFailures) { tabs, failures ->
            val tabsByProfileId = tabs
                .filter { it.profile.id.isNotBlank() }
                .associateBy { it.profile.id }
            (tabsByProfileId.keys + failures.keys).associateWith { id ->
                com.systemsgo.hex.session.resolveCardStatus(tabsByProfileId[id], failures[id], authFailureHint)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * UI FIX (sessions screen / × button): removes the tab immediately from
     * [SessionTabManager] so the tab bar / sessions list updates instantly,
     * without waiting on a round-trip through RdpSessionActivity. The caller
     * is still responsible for also asking RdpSessionActivity to tear down the
     * live connection for this tab (see HomeScreen's onTabClose), but the tab
     * chip disappearing no longer depends on that round-trip succeeding.
     */
    fun removeSessionTabLocally(tabId: String) = sessionTabManager.closeTab(tabId)

    // ── Import .rdp file ──────────────────────────────────────────────────────
    // Holds a pre-parsed profile waiting for the user to review and confirm.
    private val _pendingImportProfile = MutableStateFlow<RdpProfile?>(null)
    val pendingImportProfile: StateFlow<RdpProfile?> = _pendingImportProfile.asStateFlow()

    // FIX #8: expose parse errors so the UI can show a meaningful message
    // instead of silently ignoring a file with a missing "full address".
    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    // FIX L1 / FIX-i18n: Expose WakeOnLan result as Boolean? (true=success, false=error)
    // so the UI layer can pick the correct localised string via stringResource().
    // Previously this held a hardcoded English string, breaking Arabic localisation.
    private val _wolResult = MutableStateFlow<Boolean?>(null)
    val wolResult: StateFlow<Boolean?> = _wolResult.asStateFlow()

    fun clearWolResult() { _wolResult.value = null }

    /** Parses an .rdp URI (from file picker or external intent) and stores the result. */
    fun parseRdpUri(uri: Uri, contentResolver: ContentResolver) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            try {
                // FIX-FILE-URI: Reject any URI whose scheme is not "content".
                // Although the file:// intent-filter was removed from the Manifest,
                // a malicious app on the device can still send an explicit Intent that
                // bypasses the filter and lands here. Enforcing content:// at this layer
                // ensures the OS content-permission model is always in effect regardless
                // of how the intent was constructed.
                if (uri.scheme != "content") {
                    _importError.value = context.getString(R.string.error_rdp_file_corrupted)
                    return@withContext
                }
                // IMPORT-FIX: the system file picker's EXTRA_MIME_TYPES filter is only
                // a hint — many file managers (and every picker that falls back to
                // "*/*" internally) still let the user browse into and select a file
                // of any type, including a photo or a PDF. Since real .rdp files have
                // no MIME type that's actually registered system-wide (most content
                // providers just report them as the same generic "application/octet-stream"
                // that literally any unrecognized file gets), MIME filtering alone can
                // never reliably keep non-.rdp files out. The only dependable check is
                // the real file name's extension, read via OpenableColumns.DISPLAY_NAME
                // (uri.lastPathSegment is not trustworthy for every provider — e.g. some
                // return an opaque document id with no ".rdp" suffix at all even for a
                // genuine .rdp file). We reject anything that isn't named "*.rdp" here,
                // before ever trying to parse its bytes, so picking an image or any other
                // unrelated file now shows a clear, specific error instead of either a
                // confusing generic "corrupted file" message or — worse — silently
                // creating a garbage profile from whatever text happened to be parseable.
                val displayName = queryDisplayName(uri, contentResolver) ?: uri.lastPathSegment?.substringAfterLast('/')
                if (displayName != null && !displayName.endsWith(".rdp", ignoreCase = true)) {
                    _importError.value = context.getString(R.string.error_rdp_file_wrong_type)
                    return@withContext
                }
                val fileName = displayName
                    ?.removeSuffix(".rdp")
                    ?.removeSuffix(".RDP")
                    // i18n FIX: was hardcoded "Imported" — use string resource so
                    // Arabic users see "مستورد" instead of English.
                    ?: context.getString(R.string.imported_profile_name)
                // BUG #2 FIX: openInputStream returns null when the URI is no longer
                // accessible (revoked permission, deleted file, etc.). Report it.
                val stream = contentResolver.openInputStream(uri)
                if (stream == null) {
                    _importError.value = context.getString(R.string.error_file_cannot_open)
                } else {
                    stream.use {
                        val profile = RdpFileParser.parse(it, fileName)
                        _pendingImportProfile.value = profile
                        _importError.value = null
                    }
                }
            } catch (e: IllegalArgumentException) {
                // BUG-4 FIX: was e.message (raw English hardcoded string) → now uses
                // localized R.string so Arabic users see an Arabic error message.
                _importError.value = context.getString(R.string.error_rdp_file_missing_host)
            } catch (_: Exception) {
                _importError.value = context.getString(R.string.error_rdp_file_corrupted)
            }
        }
    }

    /**
     * Resolves a content:// Uri's real file name via [OpenableColumns.DISPLAY_NAME],
     * which every well-behaved DocumentsProvider (system file picker, Google Drive,
     * Files by Google, etc.) is required to support — unlike [Uri.lastPathSegment],
     * which for many providers is just an opaque document id with no relation to the
     * original file name or extension. Returns null (never throws) if the provider
     * doesn't expose the column or the query otherwise fails, so callers can fall
     * back to [Uri.lastPathSegment] themselves.
     */
    private fun queryDisplayName(uri: Uri, contentResolver: ContentResolver): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    /** Called when the user dismisses the import review dialog. */
    fun clearPendingImport() {
        _pendingImportProfile.value = null
        _importError.value = null
    }

    // ── Scan QR Code (new connection) ───────────────────────────────────────
    // Mirrors the .rdp import flow immediately above: holds a pre-parsed
    // profile waiting for the user to review and confirm in ProfileFormDialog,
    // plus an error slot the UI shows as a Snackbar when the scanned text
    // couldn't be turned into a connection.
    private val _pendingQrProfile = MutableStateFlow<RdpProfile?>(null)
    val pendingQrProfile: StateFlow<RdpProfile?> = _pendingQrProfile.asStateFlow()

    private val _qrError = MutableStateFlow<String?>(null)
    val qrError: StateFlow<String?> = _qrError.asStateFlow()

    /** Parses raw text decoded from a scanned QR code and stores the result. */
    fun parseQrContent(rawText: String) {
        try {
            _pendingQrProfile.value =
                com.systemsgo.hex.util.QrConnectionParser.parse(
                    rawText,
                    context.getString(R.string.scanned_profile_name)
                )
            _qrError.value = null
        } catch (_: com.systemsgo.hex.util.QrConnectionParser.InvalidQrContentException) {
            _qrError.value = context.getString(R.string.error_qr_invalid)
        } catch (_: Exception) {
            _qrError.value = context.getString(R.string.error_qr_invalid)
        }
    }

    /** Called when the user dismisses the QR-scan review dialog. */
    fun clearPendingQr() {
        _pendingQrProfile.value = null
        _qrError.value = null
    }

    // ── Guest Mode (REQ-3: Forgot PIN → Continue as Guest) ─────────────────────
    // A completely isolated, temporary profile for someone who is locked out
    // of their real PIN but still needs basic use of the app. It never reads
    // from or writes to the primary user's Room database or encrypted
    // settings/history — it only ever touches the in-memory lists below,
    // which start empty every time Guest Mode is entered and vanish the
    // moment the process dies or the user switches back to Primary.
    private val _isGuestMode  = MutableStateFlow(false)
    val isGuestMode: StateFlow<Boolean> = _isGuestMode.asStateFlow()
    private val guestProfiles = MutableStateFlow<List<RdpProfile>>(emptyList())
    private val guestFolders  = MutableStateFlow<List<ConnectionFolder>>(emptyList())

    /** Enters Guest Mode with a brand-new, empty temporary profile. */
    fun enterGuestMode() {
        guestProfiles.value = emptyList()
        guestFolders.value  = emptyList()
        _isGuestMode.value  = true
        // GUEST-MODE ISOLATION FIX: any session opened from now on is tagged
        // as Guest's own (see SessionTab.isGuest), keeping it out of the
        // Primary profile's Active Sessions list too if the user switches back.
        sessionTabManager.setGuestContext(true)
    }

    /**
     * Leaves Guest Mode and returns to the Primary profile. Callers MUST only
     * invoke this after the user has successfully re-authenticated (PIN or
     * biometric) — see SecurityConfirmDialog usage in HomeScreen's
     * "Switch to Primary Profile" action. The temporary Guest data (any
     * connections added while in Guest Mode) is discarded, never merged into
     * the primary profile.
     */
    fun exitGuestMode() {
        _isGuestMode.value = false
        guestProfiles.value = emptyList()
        guestFolders.value  = emptyList()
        // GUEST-MODE ISOLATION FIX: previously this only dropped guest tabs
        // from SessionTabManager's list — the tab chip disappeared, but any
        // still-connected RDP/VNC/SSH session underneath kept running
        // indefinitely in its own RdpSessionActivity instance, invisible and
        // unreachable from anywhere in the UI. Each removed tab is now also
        // sent the exact same "close_tab" Intent SessionsScreen's own ×
        // button already uses (see RdpSessionActivity.handleCloseTabIntent),
        // so the live connection is actually torn down, not just hidden.
        sessionTabManager.closeAllGuestTabs().forEach { tab ->
            val closeIntent = android.content.Intent(context, com.systemsgo.hex.ui.screens.RdpSessionActivity::class.java)
                .putExtra("profile_id", tab.profile.id)
                .putExtra("tab_id", tab.tabId)
                .putExtra("close_tab", true)
                // NEW_TASK is required here (unlike SessionsScreen's identical call)
                // because this Context is the injected application Context, not an
                // Activity Context. RdpSessionActivity has no custom taskAffinity, so
                // this lands in SystemsGo's existing task and singleTop + REORDER_TO_FRONT
                // deliver it via onNewIntent to the already-running instance for that
                // tab, if any — see RdpSessionActivity's own onNewIntent doc.
                .addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            try {
                context.startActivity(closeIntent)
            } catch (e: Exception) {
                // Best-effort: if for any reason the system can't deliver this
                // (e.g. the process is mid-teardown), the tab entry is already
                // gone from SessionTabManager above — worst case is a session
                // that lingers until the OS reclaims the process, not a UI leak.
                android.util.Log.w("MainViewModel", "Failed to force-close guest tab ${tab.tabId}", e)
            }
        }
        sessionTabManager.setGuestContext(false)
    }

    // ── Feature-06: connection history ────────────────────────────────────────
    // GUEST-MODE FEATURE: history is primary-only data — forced empty whenever
    // the Guest profile is active, regardless of what's actually logged.
    val connectionLogs = combine(connectionLogRepository.getRecentLogs(), _isGuestMode) { logs, guest ->
        if (guest) emptyList() else logs
    }

    /**
     * SECURITY FIX (guest-mode data-clear bypass): clears the *primary*
     * connection history. Guest Mode already shows an empty history (see
     * connectionLogs above) and its own "Settings aren't available in Guest
     * Mode" message blocks the Settings button in HomeScreen — but that
     * block is UI-only. Nothing previously stopped this function itself from
     * running if DataManagementScreen was ever reached while
     * Guest Mode was active (e.g. via ManageSpaceActivity's OS-level "Manage
     * space" entry point + "Continue as Guest", which authenticates the app
     * but not the user's real PIN). A guest — someone who by definition
     * never proved they know the real PIN — must never be able to erase the
     * primary user's real history. Guarding here, at the single choke point
     * every call path funnels through, is the actual security boundary;
     * the navigation-level guard added alongside this is only UX politeness.
     */
    fun clearConnectionHistory() = viewModelScope.launch {
        if (_isGuestMode.value) return@launch
        connectionLogRepository.clearAll()
    }

    // ── CLEAR-DATA REDESIGN: Settings → Data → "Clear Data" → DataManagementScreen ──
    // Both functions below are only ever invoked from DataManagementScreen,
    // which itself is only reachable after SettingsDataScreen's
    // SecurityConfirmDialog re-auth gate has already succeeded (see
    // SettingsScreen.kt). Each is additionally gated behind its own explicit
    // AlertDialog confirmation in DataManagementScreen before this is
    // called — neither function performs any UI confirmation itself.

    /**
     * Non-destructive: clears cached thumbnails/temp files only.
     * Deliberately does NOT touch the profile database, encrypted prefs, or
     * any saved connection — unlike [eraseAllAppData] below. Safe to repeat;
     * the cache directory is recreated by Android automatically as needed.
     *
     * SECURITY FIX (guest-mode data-clear bypass): guarded the same as
     * [clearConnectionHistory] / [eraseAllAppData] — see that doc for why.
     * This one is not destructive to *primary* data either way (the cache
     * dir holds no per-profile content), but a Guest session should never be
     * able to trigger any primary-account side effect at all, on principle.
     */
    fun clearCache() = viewModelScope.launch {
        if (_isGuestMode.value) return@launch
        withContext(Dispatchers.IO) {
            fun deleteContents(dir: java.io.File) {
                dir.listFiles()?.forEach { child ->
                    if (child.isDirectory) {
                        deleteContents(child)
                        child.delete()
                    } else {
                        child.delete()
                    }
                }
            }
            deleteContents(context.cacheDir)
        }
    }

    /**
     * Destructive: the same full wipe used by the delayed "Forgot PIN?"
     * reset (see SecureWipe.wipeEverything / DataResetWorker), but run
     * immediately rather than scheduled 24h out — appropriate here because,
     * unlike the forgot-PIN recovery path, the caller has already
     * authenticated (App Lock re-check) before this screen was even
     * reachable. clearApplicationUserData() (called inside wipeEverything)
     * kills the app process once it returns, so there is intentionally no
     * state update or navigation after this call — the process restarting
     * from a clean state IS the completion signal.
     *
     * SECURITY FIX (guest-mode data-clear bypass): the "caller has already
     * authenticated" assumption above turned out not to always hold — see
     * [clearConnectionHistory]'s doc for the exact gap. This is the most
     * consequential of the three functions here (it erases literally
     * everything, irreversibly), so it gets the same guest-mode guard as a
     * hard requirement, not just a nice-to-have.
     */
    fun eraseAllAppData() = viewModelScope.launch {
        if (_isGuestMode.value) return@launch
        withContext(Dispatchers.IO) {
            com.systemsgo.hex.security.SecureWipe.wipeEverything(context)
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        observeData()
        registerNetworkCallback()   // ✅ مراقبة حية بدل قراءة لمرة واحدة
        observeVpnStatus()
    }

    // VPN-AWARE-CONNECTIVITY: mirrors registerNetworkCallback() above but for
    // VPN presence specifically, so HomeScreen can show "VPN active" before
    // the user taps Connect and MainViewModel doesn't need its own duplicate
    // ConnectivityManager plumbing (see VpnConnectivityManager.observeVpnStatus).
    private fun observeVpnStatus() {
        viewModelScope.launch {
            com.systemsgo.hex.util.VpnConnectivityManager.observeVpnStatus(context)
                .collect { status -> _uiState.update { it.copy(vpnStatus = status) } }
        }
    }

    private fun observeData() {
        viewModelScope.launch {
            val primaryData = combine(
                profileRepository.getAllProfiles(),
                settingsRepository.settingsFlow,
                folderRepository.getAllFolders()
            ) { profiles, settings, folders -> Triple(profiles, settings, folders) }

            // GUEST-MODE FEATURE: layered on top of the primary combine above
            // instead of threaded through it, so the primary data pipeline
            // (and every existing consumer of it) is untouched. Whenever
            // Guest Mode is active, the primary profiles/folders/settings are
            // swapped out for the isolated in-memory Guest equivalents —
            // guestProfiles/guestFolders (always empty on entry) and a
            // brand-new default AppSettings() (so Guest never sees, and can
            // never silently mutate, the real PIN, biometric flag, or any
            // other primary setting).
            combine(primaryData, _isGuestMode, guestProfiles, guestFolders) {
                (profiles, settings, folders), guest, gProfiles, gFolders ->
                val effectiveProfiles = if (guest) gProfiles else profiles
                val effectiveFolders  = if (guest) gFolders else folders
                val effectiveSettings = if (guest) AppSettings() else settings
                val shouldShowFirstLaunch = !guest && !settings.hasShownFirstLaunch
                soundManager.setEnabled(effectiveSettings.soundEnabled)

                // FAVORITE-SHORTCUTS FEATURE: long-press-launcher-icon menu.
                // Deliberately built from `profiles` (the real, non-Guest-Mode
                // list from primaryData above), never from `effectiveProfiles`
                // — Guest Mode's connections are meant to stay isolated and
                // temporary, so they must never leak onto a persistent,
                // device-level launcher menu. Most-recently-connected first
                // is what ShortcutHelper treats as "most used" for ranking.
                com.systemsgo.hex.util.ShortcutHelper.updateFavoriteShortcuts(
                    context,
                    profiles.filter { it.isFavorite }.sortedByDescending { it.lastConnected }
                )
                HomeUiState(
                    profiles = effectiveProfiles,
                    settings = effectiveSettings,
                    showFirstLaunchDialog = shouldShowFirstLaunch,
                    networkQuality        = _uiState.value.networkQuality,
                    vpnStatus             = _uiState.value.vpnStatus,
                    isLoading             = false,
                    folders               = effectiveFolders,
                    // Preserve whatever the user had selected across data refreshes
                    // (e.g. a profile update elsewhere shouldn't reset the filter).
                    selectedFolderId      = _uiState.value.selectedFolderId,
                    selectedTag           = _uiState.value.selectedTag,
                    showFavoritesOnly     = _uiState.value.showFavoritesOnly,
                    isGuestMode           = guest,
                )
            }.collect { _uiState.value = it }
        }

        // HOME-SCREEN-WIDGET FEATURE (Part 2/2): pokes every placed widget
        // instance whenever the *primary* (non-Guest) connection list
        // actually changes — add, edit, delete, or a favorite toggle
        // (setFavorite() writes straight to the DAO, so it surfaces here as
        // an ordinary emission like any other field change). This is what
        // makes addProfile()/updateProfile() above (and setFavorite()/
        // toggleFavorite() below) end up refreshing the widget too, without
        // each of those needing its own WidgetUpdater call: every one of
        // them writes through profileRepository, and every write there
        // shows up in this same getAllProfiles() flow.
        //
        // Deliberately a *separate* collector from the combine above rather
        // than folded into it: that combine also re-emits on every
        // settings/network/guest-mode change, none of which affect what a
        // widget shows, and WidgetUpdater.requestUpdateAll ends up doing a
        // real notifyAppWidgetViewDataChanged() + sendBroadcast() — worth
        // skipping when nothing widget-relevant changed. distinctUntilChanged()
        // relies on RdpProfile's data-class equals(), so toggling dark mode
        // or an unrelated settings change never fires this.
        //
        // No infinite-loop risk despite living inside a ViewModel that also
        // *writes* profiles: nothing in the widget code path
        // (SystemsGoAppWidgetProvider, RdpProfileRemoteViewsFactory) ever calls
        // back into profileRepository.saveProfile/updateProfile/deleteProfile
        // — it only ever reads, via getProfileById()/getAllProfiles(). A
        // widget tap launches a session the same way a shortcut or the tile
        // does; it never writes a profile row.
        //
        // Read from the repository directly (not `uiState.profiles`,
        // `visibleProfiles`, or `effectiveProfiles`) so this never fires from
        // Guest Mode's isolated in-memory list — same "primary data only"
        // isolation ShortcutHelper.updateFavoriteShortcuts already applies
        // just above, for the same reason (a widget is a persistent,
        // device-level surface, and Guest's connections must never leak onto
        // one).
        viewModelScope.launch {
            profileRepository.getAllProfiles()
                .distinctUntilChanged()
                .collect { com.systemsgo.hex.widget.WidgetUpdater.requestUpdateAll(context) }
        }
    }

    // ── Network — مراقبة حية بـ NetworkCallback ───────────────────────────────
    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // قراءة أولية فورية
        _uiState.update { it.copy(networkQuality = readNetworkQuality(cm)) }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(net: Network, caps: NetworkCapabilities) {
                _uiState.update { it.copy(networkQuality = qualityFromCaps(caps)) }
            }
            override fun onLost(net: Network) {
                _uiState.update { it.copy(networkQuality = NetworkQuality.UNKNOWN) }
            }
        }
        networkCallback = cb
        try { cm.registerNetworkCallback(request, cb) } catch (e: Exception) { android.util.Log.d("MainViewModel", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun readNetworkQuality(cm: ConnectivityManager): NetworkQuality {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkQuality.UNKNOWN
        return qualityFromCaps(caps)
    }

    // UX-10: WiFi quality is now based on actual downstream bandwidth, not
    // just transport type. A weak WiFi signal gets the same treatment as
    // slow cellular so we don't suggest LAN-level settings for a poor link.
    // BUG-AUTO-QUALITY FIX: thresholds now live in NetworkQualityDetector (a
    // single shared source of truth also used by RdpSessionViewModel to
    // resolve RdpPerformance.AUTO at connect time); this just maps its
    // Bucket enum onto the UI-facing NetworkQuality enum.
    private fun qualityFromCaps(caps: NetworkCapabilities): NetworkQuality =
        when (com.systemsgo.hex.util.NetworkQualityDetector.bucketFromCapabilities(caps)) {
            com.systemsgo.hex.util.NetworkQualityDetector.Bucket.EXCELLENT -> NetworkQuality.EXCELLENT
            com.systemsgo.hex.util.NetworkQualityDetector.Bucket.GOOD      -> NetworkQuality.GOOD
            com.systemsgo.hex.util.NetworkQualityDetector.Bucket.FAIR      -> NetworkQuality.FAIR
            com.systemsgo.hex.util.NetworkQualityDetector.Bucket.POOR      -> NetworkQuality.POOR
            com.systemsgo.hex.util.NetworkQualityDetector.Bucket.UNKNOWN   -> NetworkQuality.UNKNOWN
        }

    override fun onCleared() {
        super.onCleared()
        networkCallback?.let {
            try {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (e: Exception) { android.util.Log.d("MainViewModel", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
        }
        // BUG-Y1 FIX: soundManager.release() removed from here.
        // SoundManager is @Singleton — its lifecycle is the Application process, not
        // any individual ViewModel. Calling release() here caused two problems:
        //   1. Double-release on true finish: MainActivity.onDestroy(isFinishing=true)
        //      already calls release(); onCleared() fires moments later → pool.release() twice.
        //   2. Wrong owner: a future ViewModel recreation after process restore would get
        //      the same poisoned singleton (released=true) with no way to re-initialise it.
        // Ownership is now solely in MainActivity.onDestroy() guarded by isFinishing.
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    // DEAD-CODE FIX (item #8): this function's only caller was addProfile()'s
    // per-profile performanceFlags computation, which has been removed since
    // that value is never read at connect time (see addProfile() below for
    // the full explanation). Live network-aware quality resolution now lives
    // solely in NetworkQualityDetector, used by RdpSessionViewModel at the
    // moment a session actually connects — the only place it has any effect.

    // ── Wake-on-LAN ────────────────────────────────────────────────────────────

    /**
     * Sends a UDP Magic Packet for [profile] and returns a [Result].
     * The caller is responsible for any post-wake delay before connecting.
     */
    fun sendWakeOnLan(profile: RdpProfile) = viewModelScope.launch {
        // FIX L1: Surface WoL result (success or error) to the UI.
        // FIX-i18n: emit Boolean (true=success, false=error) so the UI picks
        // the correct localised string; avoids hardcoded English messages.
        _wolResult.value = null
        val result = runCatching {
            com.systemsgo.hex.util.WakeOnLanManager.sendMagicPacket(
                context          = context,
                macAddress       = profile.wolMacAddress,
                broadcastAddress = profile.wolBroadcastAddress,
                port             = profile.wolPort
            )
        }
        _wolResult.value = result.isSuccess
    }

    // ── Wake & Connect ──────────────────────────────────────────────────────────
    // Sends the Magic Packet, waits for the target's RDP/VNC/SSH port to answer,
    // then hands control back to the UI (via [onReady]) to actually launch the
    // session Activity — Activity/Intent launching stays in Compose, this
    // ViewModel only owns the network side and the progress state machine.

    sealed class WakeConnectState {
        object Idle : WakeConnectState()
        data class SendingPacket(val profileId: String) : WakeConnectState()
        data class WaitingForHost(val profileId: String, val attempt: Int, val maxAttempts: Int) : WakeConnectState()
        data class HostOnline(val profileId: String) : WakeConnectState()
        data class Connecting(val profileId: String) : WakeConnectState()
        data class Failed(val profileId: String, val reason: FailReason) : WakeConnectState()

        enum class FailReason { SEND_FAILED, TIMEOUT }
    }

    private val _wakeConnectState = MutableStateFlow<WakeConnectState>(WakeConnectState.Idle)
    val wakeConnectState: StateFlow<WakeConnectState> = _wakeConnectState.asStateFlow()

    private var wakeConnectJob: kotlinx.coroutines.Job? = null

    /**
     * Runs the full "Wake & Connect" sequence for [profile]:
     *  1. Send the Magic Packet.
     *  2. Poll [profile.host]:[profile.port] (the profile's own RDP/VNC/SSH port)
     *     until it accepts a TCP connection, honouring the profile's configured
     *     timeout / retry interval / max retries.
     *  3. On success, invoke [onReady] so the caller can launch the actual
     *     session (RdpSessionActivity or equivalent) exactly as a normal
     *     "Connect" tap would.
     *
     * Any previous in-flight wake attempt is cancelled first, so re-tapping
     * "Wake & Connect" (or tapping it for a different profile) always starts
     * clean rather than racing two attempts. Failures (send failure or
     * reachability timeout) land in [WakeConnectState.Failed] without ever
     * freezing the UI thread — everything network-related runs on
     * Dispatchers.IO inside WakeOnLanManager.
     */
    fun wakeAndConnect(profile: RdpProfile, onReady: () -> Unit) {
        wakeConnectJob?.cancel()
        wakeConnectJob = viewModelScope.launch {
            _wakeConnectState.value = WakeConnectState.SendingPacket(profile.id)
            val sendResult = runCatching {
                com.systemsgo.hex.util.WakeOnLanManager.sendMagicPacket(
                    context          = context,
                    macAddress       = profile.wolMacAddress,
                    broadcastAddress = profile.wolBroadcastAddress,
                    port             = profile.wolPort
                )
            }
            if (sendResult.isFailure) {
                _wakeConnectState.value = WakeConnectState.Failed(profile.id, WakeConnectState.FailReason.SEND_FAILED)
                return@launch
            }

            val maxAttempts = profile.wolMaxRetries.coerceAtLeast(1)
            _wakeConnectState.value = WakeConnectState.WaitingForHost(profile.id, 0, maxAttempts)

            val reachable = com.systemsgo.hex.util.WakeOnLanManager.waitForHostReachable(
                host             = profile.host,
                port             = profile.port,
                overallTimeoutMs = profile.wolConnectTimeoutSeconds.coerceAtLeast(1) * 1000L,
                retryIntervalMs  = profile.wolRetryIntervalSeconds.coerceAtLeast(1) * 1000L,
                maxRetries       = maxAttempts,
                onAttempt        = { attempt, max ->
                    _wakeConnectState.value = WakeConnectState.WaitingForHost(profile.id, attempt, max)
                }
            )

            if (!reachable) {
                _wakeConnectState.value = WakeConnectState.Failed(profile.id, WakeConnectState.FailReason.TIMEOUT)
                return@launch
            }

            _wakeConnectState.value = WakeConnectState.HostOnline(profile.id)
            // Brief pause so "Computer is online." is actually visible to the
            // person for a moment, rather than being replaced by "Connecting…"
            // on the very next line before a single frame renders it.
            kotlinx.coroutines.delay(500)
            _wakeConnectState.value = WakeConnectState.Connecting(profile.id)
            onReady()
            // The session Activity is now launching (or already in front). Clear
            // the progress state shortly after so, if the user later navigates
            // back to this screen, they don't find a stale "Connecting…" dialog
            // still showing.
            kotlinx.coroutines.delay(1500)
            if (_wakeConnectState.value is WakeConnectState.Connecting) {
                _wakeConnectState.value = WakeConnectState.Idle
            }
        }
    }

    /** Cancels any in-flight Wake & Connect attempt and returns the state to Idle. */
    fun cancelWakeAndConnect() {
        wakeConnectJob?.cancel()
        wakeConnectJob = null
        _wakeConnectState.value = WakeConnectState.Idle
    }

    /** Dismisses the current terminal (success/failure) state, e.g. after the user closes the progress sheet. */
    fun resetWakeConnectState() {
        if (_wakeConnectState.value !is WakeConnectState.WaitingForHost &&
            _wakeConnectState.value !is WakeConnectState.SendingPacket
        ) {
            _wakeConnectState.value = WakeConnectState.Idle
        }
    }

    // ── Profile Actions ────────────────────────────────────────────────────────

    // UX-03: Drag-to-reorder
    fun reorderProfiles(profiles: List<RdpProfile>) = viewModelScope.launch {
        if (_isGuestMode.value) {
            guestProfiles.value = profiles
            return@launch
        }
        profileRepository.reorderProfiles(profiles)
    }

    // ── Folders ──────────────────────────────────────────────────────────────
    // Folder CRUD. The connection list itself is unaffected by these beyond
    // whatever it derives from uiState.folders / visibleProfiles below —
    // search and sort keep working exactly as before, since both operate on
    // whatever list the screen chooses to display (now visibleProfiles
    // instead of the raw uiState.profiles).

    // FOLDER-APPEARANCE FEATURE: color/icon are FolderColor/FolderIcon enum
    // names (or "" for "unset") — see ConnectionFolder.color/icon's doc
    // comment. Optional here so every existing caller (and the guest-mode
    // path) keeps working unchanged.
    fun createFolder(name: String, color: String = "", icon: String = "") = viewModelScope.launch {
        if (name.isBlank()) return@launch
        // GUEST-MODE FEATURE: same isolation as profiles above — a folder
        // created while in Guest Mode lives only in memory for that session.
        if (_isGuestMode.value) {
            guestFolders.update { it + ConnectionFolder(name = name.trim(), color = color, icon = icon) }
            return@launch
        }
        folderRepository.createFolder(name, color, icon)
    }

    fun renameFolder(folder: ConnectionFolder, newName: String) = viewModelScope.launch {
        if (newName.isBlank()) return@launch
        if (_isGuestMode.value) {
            guestFolders.update { list ->
                list.map { if (it.id == folder.id) it.copy(name = newName.trim()) else it }
            }
            return@launch
        }
        folderRepository.renameFolder(folder, newName)
    }

    // FOLDER-APPEARANCE FEATURE: used by RenameFolderDialog's swatch/glyph
    // rows to save name + color + icon together — see
    // ConnectionFolderRepository.updateAppearance's doc comment for why
    // this is a separate call from plain renameFolder above.
    fun updateFolderAppearance(folder: ConnectionFolder, newName: String, color: String, icon: String) = viewModelScope.launch {
        if (newName.isBlank()) return@launch
        if (_isGuestMode.value) {
            guestFolders.update { list ->
                list.map { if (it.id == folder.id) it.copy(name = newName.trim(), color = color, icon = icon) else it }
            }
            return@launch
        }
        folderRepository.updateAppearance(folder, newName, color, icon)
    }

    fun deleteFolder(folder: ConnectionFolder) = viewModelScope.launch {
        if (_isGuestMode.value) {
            guestFolders.update { list -> list.filterNot { it.id == folder.id } }
            if (_uiState.value.selectedFolderId == folder.id) {
                _uiState.update { it.copy(selectedFolderId = null) }
            }
            return@launch
        }
        folderRepository.deleteFolder(folder)
        // Clear the filter if the folder being viewed was just deleted, so the
        // list doesn't appear to silently go empty.
        if (_uiState.value.selectedFolderId == folder.id) {
            _uiState.update { it.copy(selectedFolderId = null) }
        }
    }

    /** Selects which folder's connections to show. Pass null to clear the filter ("All"). */
    fun selectFolder(folderId: String?) {
        _uiState.update { it.copy(selectedFolderId = folderId) }
    }

    // DRAG-TO-FOLDER FEATURE: moves [profile] into [folderId] (pass null to
    // unfile it) by persisting a copy with only folderId changed. Reuses the
    // exact same save path as updateProfile() — including the Guest-mode
    // isolation and the Keystore-failure fallback — since a folder move is
    // just a special case of "update this profile's fields", not a distinct
    // operation as far as storage is concerned.
    fun moveProfileToFolder(profile: RdpProfile, folderId: String?) = viewModelScope.launch {
        if (profile.folderId == folderId) return@launch
        val updated = profile.copy(folderId = folderId)
        if (_isGuestMode.value) {
            guestProfiles.update { list -> list.map { if (it.id == updated.id) updated else it } }
            return@launch
        }
        try {
            profileRepository.updateProfile(updated)
        } catch (e: SecurityException) {
            android.util.Log.e("MainViewModel", "moveProfileToFolder: Keystore encryption failed", e)
            _profileSaveError.value = context.getString(R.string.error_profile_save_keystore)
        }
    }

    /** Selects which tag's connections to show. Pass null to clear the filter ("All"). */
    fun selectTag(tag: String?) {
        _uiState.update { it.copy(selectedTag = tag) }
    }

    // ── Favorites ────────────────────────────────────────────────────────────

    /** Toggles the favorite flag for [profile] and persists it immediately. */
    fun toggleFavorite(profile: RdpProfile) = viewModelScope.launch {
        profileRepository.setFavorite(profile.id, !profile.isFavorite)
    }

    /** Toggles the "show favorites only" filter on/off. */
    fun toggleFavoritesOnly() {
        _uiState.update { it.copy(showFavoritesOnly = !it.showFavoritesOnly) }
    }

    // ── Pin Connection ───────────────────────────────────────────────────────
    // See RdpProfile.isPinned/pinnedOrder doc comments for the full design.
    // Pin state/order lives entirely in these fields and is completely
    // independent of search text, the active protocol tab, folder/tag
    // selection, and the favorites-only filter — none of those ever read or
    // write isPinned/pinnedOrder, so switching between them never disturbs
    // which connections are pinned or their relative order. What *does*
    // change with those filters is only which cards are visible at all
    // (unrelated to pinning, same as it always has been) — a pinned card
    // that doesn't match the current folder/tag/search is hidden exactly
    // like an unpinned one would be, but the moment it's visible again it's
    // right back at the top where it was pinned.

    /**
     * Toggles [profile]'s pinned state. Pinning appends it after every
     * already-pinned connection (max existing pinnedOrder + 1); unpinning
     * just clears the flag.
     */
    fun togglePin(profile: RdpProfile) = viewModelScope.launch {
        if (_isGuestMode.value) {
            if (profile.isPinned) {
                guestProfiles.update { list ->
                    list.map { if (it.id == profile.id) it.copy(isPinned = false) else it }
                }
            } else {
                val nextOrder = (guestProfiles.value.filter { it.isPinned }
                    .maxOfOrNull { it.pinnedOrder } ?: -1L) + 1
                guestProfiles.update { list ->
                    list.map { if (it.id == profile.id) it.copy(isPinned = true, pinnedOrder = nextOrder) else it }
                }
            }
            return@launch
        }
        if (profile.isPinned) {
            profileRepository.setPinned(profile.id, false)
        } else {
            val nextOrder = (profileRepository.getMaxPinnedOrder() ?: -1L) + 1
            profileRepository.setPinned(profile.id, true, nextOrder)
        }
    }

    /** Persists a new manual order for the pinned section after drag-reorder. */
    fun reorderPinnedProfiles(pinnedProfiles: List<RdpProfile>) = viewModelScope.launch {
        if (_isGuestMode.value) {
            val orderById = pinnedProfiles.mapIndexed { index, p -> p.id to index.toLong() }.toMap()
            guestProfiles.update { list ->
                list.map { p -> orderById[p.id]?.let { p.copy(pinnedOrder = it) } ?: p }
            }
            return@launch
        }
        profileRepository.reorderPinnedProfiles(pinnedProfiles)
    }

    // ── Multi-selection (bulk pin/unpin, etc.) ─────────────────────────────
    private val _selectedProfileIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedProfileIds: StateFlow<Set<String>> = _selectedProfileIds.asStateFlow()

    /** True whenever one or more cards are selected — drives the selection-mode toolbar. */
    val isSelectionMode: StateFlow<Boolean> = _selectedProfileIds
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleProfileSelection(id: String) {
        _selectedProfileIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun selectProfiles(ids: Collection<String>) {
        _selectedProfileIds.value = ids.toSet()
    }

    fun clearSelection() {
        _selectedProfileIds.value = emptySet()
    }

    /** Pins every currently-selected connection, then exits selection mode. */
    fun bulkPinSelected() = viewModelScope.launch {
        val ids = _selectedProfileIds.value.toList()
        if (ids.isEmpty()) return@launch
        if (_isGuestMode.value) {
            var nextOrder = (guestProfiles.value.filter { it.isPinned }
                .maxOfOrNull { it.pinnedOrder } ?: -1L) + 1
            val idSet = ids.toSet()
            guestProfiles.update { list ->
                list.map { p ->
                    if (p.id in idSet && !p.isPinned) {
                        p.copy(isPinned = true, pinnedOrder = nextOrder++)
                    } else p
                }
            }
        } else {
            profileRepository.setPinnedBulk(ids, isPinned = true)
        }
        clearSelection()
    }

    /** Unpins every currently-selected connection, then exits selection mode. */
    fun bulkUnpinSelected() = viewModelScope.launch {
        val ids = _selectedProfileIds.value.toList()
        if (ids.isEmpty()) return@launch
        if (_isGuestMode.value) {
            val idSet = ids.toSet()
            guestProfiles.update { list ->
                list.map { p -> if (p.id in idSet) p.copy(isPinned = false) else p }
            }
        } else {
            profileRepository.setPinnedBulk(ids, isPinned = false)
        }
        clearSelection()
    }

    // ── Derived: folder/tag-filtered profiles ───────────────────────────────
    // Applies the current folder + tag + favorites filters on top of the raw
    // profile list, and always sorts favorites before non-favorites. This
    // narrows/orders the set the same way the existing protocol-tab filter
    // already does — search and sort (implemented in the connection-list
    // screen) continue to operate on top of whatever this exposes, so neither
    // is affected by adding folders/tags/favorites.
    //
    // NOTE ON SORTING: List.sortedByDescending() is a stable sort, so within
    // the favorite / non-favorite groups the incoming order — already
    // sortOrder ASC, createdAt DESC from RdpProfileDao.getAllProfiles() — is
    // preserved exactly. This is what lets favorites "float to the top"
    // without disturbing drag-to-reorder, folders, tags, or search.
    // PIN-CONNECTION FEATURE: pinned connections are partitioned out and
    // placed first — ordered by pinnedOrder ascending (i.e. pin order,
    // oldest pin first / most-recently-drag-reordered position) — ahead of
    // every unpinned connection, which keeps exactly the sorting it always
    // had (favorites-first, then the existing sortOrder/createdAt order from
    // RdpProfileDao.getAllProfiles()). This is "pinned order first, then the
    // existing sort mode for the rest" — partition+sort is stable, so pin
    // placement never disturbs favorites/sortOrder relative order within
    // either group.
    val visibleProfiles: StateFlow<List<RdpProfile>> = uiState
        .map { state ->
            val filtered = filterProfiles(state.profiles, state.selectedFolderId, state.selectedTag, state.showFavoritesOnly)
            val (pinned, unpinned) = filtered.partition { it.isPinned }
            pinned.sortedBy { it.pinnedOrder } + unpinned.sortedByDescending { it.isFavorite }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Every distinct tag currently in use, for populating a tag-filter chip row.
    val allTags: StateFlow<List<String>> = uiState
        .map { state -> state.profiles.flatMap { it.tags }.filter { it.isNotBlank() }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun filterProfiles(
        profiles: List<RdpProfile>,
        selectedFolderId: String?,
        selectedTag: String?,
        showFavoritesOnly: Boolean
    ): List<RdpProfile> = profiles.filter { profile ->
        val matchesFolder = when (selectedFolderId) {
            null -> true
            UNFILED_FOLDER_ID -> profile.folderId == null
            else -> profile.folderId == selectedFolderId
        }
        val matchesTag = selectedTag == null || profile.tags.contains(selectedTag)
        val matchesFavorite = !showFavoritesOnly || profile.isFavorite
        matchesFolder && matchesTag && matchesFavorite
    }

    // CRIT-1 FIX: _profileSaveError exposes Keystore encryption failures to the UI.
    // addProfile() and updateProfile() call withEncryptedSecrets() which calls
    // CryptoHelper.encrypt(). That throws SecurityException when the Android Keystore
    // is unavailable (factory reset, old ARMv7 device, OEM quirks). Without try/catch
    // the exception propagated silently inside viewModelScope.launch, crashing the app.
    private val _profileSaveError = MutableStateFlow<String?>(null)
    val profileSaveError: StateFlow<String?> = _profileSaveError.asStateFlow()
    fun clearProfileSaveError() { _profileSaveError.value = null }

    // DEAD-CODE FIX (item #8): addProfile() used to compute a network-aware
    // performanceFlags value (via getRecommendedPerformance()) and store it on
    // every new profile. That value is never read anywhere at connect time —
    // RemoteSessionFactory.create() only ever consults the single global
    // AppSettings.performanceLevel (Settings → Connection), not
    // profile.performanceFlags. The column itself is kept as-is (removing it
    // would need a destructive Room migration for no behavioural benefit,
    // since it's schema-compatible and simply unused going forward); this
    // just stops the pointless live network-quality lookup + write on every
    // profile save. Profiles (including imported .rdp files) are now saved
    // exactly as constructed, with no extra computation.
    fun addProfile(profile: RdpProfile) = viewModelScope.launch {
        // GUEST-MODE FEATURE: never let a Guest-added connection reach the
        // primary Room database — keep it in the isolated in-memory list only.
        if (_isGuestMode.value) {
            guestProfiles.update { it + profile }
            return@launch
        }
        try {
            profileRepository.saveProfile(profile)
        } catch (e: SecurityException) {
            android.util.Log.e("MainViewModel", "addProfile: Keystore encryption failed", e)
            _profileSaveError.value = context.getString(R.string.error_profile_save_keystore)
        }
    }
    // DUPLICATE-CONNECTION FEATURE: clones an existing profile as a starting
    // point for a near-identical one (e.g. same server family, different
    // host/port) instead of the user re-filling the whole form from scratch.
    // Deliberately resets identity/usage-derived fields (new id, "(copy)"
    // name suffix, unfavorited/unpinned, never-connected) so the duplicate
    // reads as a fresh, independent profile rather than a second pointer at
    // the same one — everything else (host, credentials, protocol-specific
    // settings, folder, tags) carries over unchanged via .copy(), so this
    // stays correct automatically as new fields get added to RdpProfile.
    fun duplicateProfile(profile: RdpProfile) {
        val duplicate = profile.copy(
            id            = java.util.UUID.randomUUID().toString(),
            name          = context.getString(R.string.duplicate_connection_suffix, profile.name),
            isFavorite    = false,
            isPinned      = false,
            pinnedOrder   = 0L,
            lastConnected = 0L,
            createdAt     = System.currentTimeMillis(),
        )
        addProfile(duplicate)
    }
    fun updateProfile(profile: RdpProfile) = viewModelScope.launch {
        if (_isGuestMode.value) {
            guestProfiles.update { list -> list.map { if (it.id == profile.id) profile else it } }
            return@launch
        }
        try {
            profileRepository.updateProfile(profile)
        } catch (e: SecurityException) {
            android.util.Log.e("MainViewModel", "updateProfile: Keystore encryption failed", e)
            _profileSaveError.value = context.getString(R.string.error_profile_save_keystore)
        }
    }
    fun deleteProfile(profile: RdpProfile)  = viewModelScope.launch {
        if (_isGuestMode.value) {
            guestProfiles.update { list -> list.filterNot { it.id == profile.id } }
            return@launch
        }
        profileRepository.deleteProfile(profile)
        // BUG-Y4 FIX: deleting the DB row left the thumbnail file at
        // cacheDir/last_frames/$profileId.jpg with no owner. Over time these
        // orphan files accumulate and are never evicted (the OS only clears
        // cacheDir under storage pressure, and only the whole directory at once).
        // Delete the file synchronously here — it's a tiny JPEG, never blocks.
        com.systemsgo.hex.util.LastFrameStore.delete(context, profile.id)
        // HOME-SCREEN-SHORTCUTS FEATURE: a pinned shortcut for this profile
        // would otherwise keep sitting on the user's home screen pointing at
        // a profile_id that no longer resolves to anything. Disable it so
        // the Launcher greys it out instead of silently failing on tap.
        com.systemsgo.hex.util.ShortcutHelper.disableShortcut(context, profile.id)
        // QUICK-SETTINGS-TILE FEATURE (Part 1/2): same "clean up after the
        // thing this pointed to is gone" reasoning as the shortcut line
        // above — see QsTilePreferences.clearIfSelected's doc comment.
        qsTilePreferences.clearIfSelected(profile.id)
        // HOME-SCREEN-WIDGET FEATURE (Part 2/2): same cleanup as
        // qsTilePreferences immediately above, but per-instance — a
        // SINGLE_CONNECTION widget bound to this profile needs to fall back
        // to its "unconfigured / tap to set up" card (see
        // SystemsGoAppWidgetProvider.buildSingleConnectionViews's null-profile
        // branch) instead of silently showing this connection's now-stale
        // name/host until the next unrelated redraw. clearIfSelected() only
        // touches instances actually bound to this profile id and returns
        // just those, so requestUpdate() below never broadcasts to widgets
        // that have nothing to do with this deletion. A CONNECTION_LIST
        // instance needs no push here — it always re-reads the DB fresh, and
        // the separate getAllProfiles()-driven collector in observeData()
        // (which this same delete's DAO write also feeds) covers it anyway.
        val affectedWidgetIds = widgetPreferences.clearIfSelected(
            profile.id,
            com.systemsgo.hex.widget.WidgetUpdater.allWidgetIds(context),
        )
        com.systemsgo.hex.widget.WidgetUpdater.requestUpdate(context, affectedWidgetIds)
        // WEB-PORTAL-FAVICON FEATURE: same orphan-file reasoning as
        // LastFrameStore above — a captured favicon at
        // filesDir/web_favicons/$profileId.png has no owner once the row is
        // gone, and would otherwise resurface if a profile id were ever
        // reused. Harmless no-op for every non-WEB profile (nothing was ever
        // cached for it).
        com.systemsgo.hex.web.WebPortalFaviconCache.clear(context, profile.id)
    }
    fun dismissFirstLaunchDialog()          = viewModelScope.launch {
        settingsRepository.markFirstLaunchShown()
        _uiState.update { it.copy(showFirstLaunchDialog = false) }
    }

    // ── Settings Update Functions ──────────────────────────────────────────────

    fun updateDarkMode(v: Boolean)              = viewModelScope.launch { settingsRepository.updateDarkMode(v) }
    fun updateLanguage(v: String)               = viewModelScope.launch { settingsRepository.updateLanguage(v) }
    fun updateTheme(v: String)                  = viewModelScope.launch { settingsRepository.updateThemeVariant(v) }
    fun updateCursorStyle(v: String)            = viewModelScope.launch { settingsRepository.updateCursorStyle(v) }
    fun updateCursorSize(v: Int)                = viewModelScope.launch { settingsRepository.updateCursorSize(v) }
    fun updateTouchpadSensitivity(v: Float)     = viewModelScope.launch { settingsRepository.updateTouchpadSensitivity(v) }
    fun updateScrollSensitivity(v: Float)       = viewModelScope.launch { settingsRepository.updateScrollSensitivity(v) }  // ✅
    fun updateHapticFeedback(v: Boolean)        = viewModelScope.launch { settingsRepository.updateHapticFeedback(v) }
    fun updateKeepScreenOn(v: Boolean)          = viewModelScope.launch { settingsRepository.updateKeepScreenOn(v) }       // ✅
    fun updateDefaultResolution(v: String)      = viewModelScope.launch { settingsRepository.updateDefaultResolution(v) }
    fun updateColorDepth(v: Int)                = viewModelScope.launch { settingsRepository.updateColorDepth(v) }
    fun updatePerformanceLevel(v: Int)          = viewModelScope.launch { settingsRepository.updatePerformanceLevel(v) }
    fun updateUdpTransportEnabled(v: Boolean)   = viewModelScope.launch { settingsRepository.updateUdpTransportEnabled(v) }
    fun updateSmartSizingEnabled(v: Boolean)    = viewModelScope.launch { settingsRepository.updateSmartSizingEnabled(v) }
    fun updateSessionToolbarVisible(v: Boolean) = viewModelScope.launch { settingsRepository.updateSessionToolbarVisible(v) }
    fun updateSessionExtraKeysVisible(v: Boolean) = viewModelScope.launch { settingsRepository.updateSessionExtraKeysVisible(v) }
    fun updateRunInBackground(v: Boolean)       = viewModelScope.launch { settingsRepository.updateRunInBackground(v) }
    fun updateBackgroundPowerSaving(v: Boolean) = viewModelScope.launch { settingsRepository.updateBackgroundPowerSaving(v) }
    // VPN-AWARE-CONNECTIVITY: v is a NetworkBindingPreference enum name
    // ("ANY", "VPN_ONLY", "WIFI_ONLY", "CELLULAR_ONLY") — see SettingsScreen's
    // network-binding SettingsChoice.
    fun updateNetworkBinding(v: String)         = viewModelScope.launch { settingsRepository.updateNetworkBinding(v) }
    // DATA-SAVER FEATURE
    fun updateDataSaverEnabled(v: Boolean)      = viewModelScope.launch { settingsRepository.updateDataSaverEnabled(v) }
    fun updateSoundEnabled(v: Boolean)          = viewModelScope.launch { settingsRepository.updateSoundEnabled(v) }
    fun updateBiometricLock(v: Boolean)         = viewModelScope.launch { settingsRepository.updateBiometricLock(v) }     // ✅
    fun updateAutoLockTimeout(v: Long)          = viewModelScope.launch { settingsRepository.updateAutoLockTimeout(v) }
    fun updatePinLock(enabled: Boolean, pin: String = "") = viewModelScope.launch {
        // FIX-PIN-ENCRYPT: CryptoHelper.encrypt() throws SecurityException when
        // Android Keystore is unavailable (e.g. after factory reset on some OEMs,
        // or on very old ARMv7 devices). Without a try/catch here the exception
        // propagates silently — the UI shows the PIN as enabled but it was never
        // actually saved, locking the user out with no working PIN.
        // We surface the error via _pinLockError so the UI can display a message.
        try {
            settingsRepository.updatePinLock(enabled, pin)
        } catch (e: SecurityException) {
            android.util.Log.e("MainViewModel", "PIN encryption failed — Keystore unavailable", e)
            _pinLockError.value = context.getString(R.string.error_pin_keystore_unavailable)
        }
    }
    private val _pinLockError = MutableStateFlow<String?>(null)
    val pinLockError: StateFlow<String?> = _pinLockError.asStateFlow()
    fun clearPinLockError() { _pinLockError.value = null }
    fun updateRightClickLongPress(v: Boolean)   = viewModelScope.launch { settingsRepository.updateRightClickLongPress(v) } // ✅
    fun markGestureHintsShown()                 = viewModelScope.launch { settingsRepository.markGestureHintsShown() }
    fun markHomeScreenOpened(currentCount: Int) = viewModelScope.launch { settingsRepository.markHomeScreenOpened(currentCount) }
    // FIX I1: These settings existed in AppSettings and AppSettingsRepository
    // but had no ViewModel wrapper, making them impossible to change from the UI.
    fun updateShowFps(v: Boolean)               = viewModelScope.launch { settingsRepository.updateShowFps(v) }
    // TOOLBOX FEATURE (Stage 7): latency now has its own independent setting.
    fun updateShowLatency(v: Boolean)           = viewModelScope.launch { settingsRepository.updateShowLatency(v) }
    fun updateShowCursorOnTouch(v: Boolean)     = viewModelScope.launch { settingsRepository.updateShowCursorOnTouch(v) }

    // ── Export All Connections / Import Connections (Settings → Data) ──────────
    // See ConnectionBackupManager for the actual encrypt/decrypt + merge logic.
    // This ViewModel just runs it on a background coroutine and turns the
    // result (or any failure) into a one-shot UI event the Settings screen can
    // show as a Snackbar.

    sealed class BackupUiEvent {
        object Exporting : BackupUiEvent()
        data class ExportSuccess(val profileCount: Int, val folderCount: Int) : BackupUiEvent()
        data class ExportError(val message: String) : BackupUiEvent()
        object Importing : BackupUiEvent()
        data class ImportSuccess(val importedProfiles: Int, val skippedProfiles: Int) : BackupUiEvent()
        data class ImportError(val message: String) : BackupUiEvent()
    }

    private val _backupEvent = MutableStateFlow<BackupUiEvent?>(null)
    val backupEvent: StateFlow<BackupUiEvent?> = _backupEvent.asStateFlow()
    fun clearBackupEvent() { _backupEvent.value = null }

    /** Encrypts every saved connection + folder with [password] and writes it to [uri]. */
    fun exportConnections(uri: Uri, password: String) = viewModelScope.launch {
        _backupEvent.value = BackupUiEvent.Exporting
        _backupEvent.value = try {
            val result = backupManager.exportTo(uri, password)
            BackupUiEvent.ExportSuccess(result.profileCount, result.folderCount)
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "exportConnections failed", e)
            BackupUiEvent.ExportError(context.getString(R.string.error_export_failed))
        }
    }

    /** Decrypts the backup at [uri] with [password] and restores its connections/folders. */
    fun importConnections(uri: Uri, password: String) = viewModelScope.launch {
        _backupEvent.value = BackupUiEvent.Importing
        _backupEvent.value = try {
            val result = backupManager.importFrom(uri, password)
            BackupUiEvent.ImportSuccess(result.importedProfiles, result.skippedProfiles)
        } catch (e: ConnectionBackupManager.BackupException.InvalidPassword) {
            BackupUiEvent.ImportError(context.getString(R.string.error_backup_wrong_password))
        } catch (e: ConnectionBackupManager.BackupException.CorruptFile) {
            BackupUiEvent.ImportError(context.getString(R.string.error_backup_corrupt))
        } catch (e: Exception) {
            android.util.Log.e("MainViewModel", "importConnections failed", e)
            BackupUiEvent.ImportError(context.getString(R.string.error_import_failed))
        }
    }

    // ── ENTRA-ID-AUTH FEATURE — Part 2/2 ────────────────────────────────────
    // Wiring for GatewaySection's "Sign in with Microsoft" /
    // EntraGatewaySignInSection button (see Components.kt's
    // ProfileFormDialog doc comment on onSignInWithMicrosoft). Both
    // HomeScreen.kt and DeviceDiscoveryScreen.kt call these two functions
    // and observe [entraSignInPending]; neither screen touches
    // EntraIdAuthManager/EntraSignInLinkStore directly.

    private val _entraSignInPending = MutableStateFlow(false)
    /** True while an interactive MSAL sign-in launched by [signInWithMicrosoft]
     *  is in flight — lets ProfileFormDialog show a spinner / disable the
     *  "Sign in with Microsoft" button instead of allowing a double-tap to
     *  launch two concurrent MSAL interactive flows. */
    val entraSignInPending: StateFlow<Boolean> = _entraSignInPending.asStateFlow()

    /**
     * Launches interactive Entra ID sign-in for [activity] and, on success,
     * links the resulting account's UPN to [profileId] via
     * [EntraSignInLinkStore] (both its encrypted-prefs copy and, for a
     * profile that already exists in Room, [RdpProfile.entraLinkedUpn] —
     * see that store's doc comment). [profileId] must be stable across this
     * call *and* the eventual save for a brand-new/unsaved profile — see
     * ProfileFormDialog's `pendingProfileId` param, which HomeScreen/
     * DeviceDiscoveryScreen generate up front for exactly this reason,
     * instead of letting the dialog mint a fresh random id at Save time
     * that would silently orphan whatever was linked here.
     */
    fun signInWithMicrosoft(activity: Activity, profileId: String) = viewModelScope.launch {
        if (_entraSignInPending.value) return@launch  // guard against a double-tap launching two MSAL flows
        _entraSignInPending.value = true
        try {
            when (val result = entraIdAuthManager.signIn(activity)) {
                is com.systemsgo.hex.auth.EntraAuthResult.Success -> {
                    entraSignInLinkStore.setLinkedUpn(profileId, result.result.account.username)
                }
                is com.systemsgo.hex.auth.EntraAuthResult.Failure -> {
                    android.util.Log.e("MainViewModel", "Entra ID sign-in failed", result.error)
                }
                is com.systemsgo.hex.auth.EntraAuthResult.Cancelled -> { /* user backed out — nothing to link */ }
            }
        } finally {
            _entraSignInPending.value = false
        }
    }

    /**
     * Signs out of MSAL entirely (single-account mode — this affects every
     * Entra-linked profile, not just [profileId], per
     * EntraIdAuthManager/EntraSignInLinkStore's own doc comments) and
     * clears the link for [profileId] specifically, so its own UI updates
     * immediately even though other profiles' encrypted-prefs entries are
     * now stale until they're each opened for edit again.
     */
    fun signOutMicrosoft(profileId: String) = viewModelScope.launch {
        entraIdAuthManager.signOut()
        entraSignInLinkStore.clearLinkedUpn(profileId)
    }
}
// Appended by WoL patch — keep at end
