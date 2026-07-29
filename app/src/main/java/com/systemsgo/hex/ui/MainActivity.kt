package com.systemsgo.hex.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
// AUTO-LOCK-FIX: ProcessLifecycleOwner reports onStart/onStop for the whole
// app process, not per-Activity — it does NOT fire when navigating between
// same-task Activities (e.g. MainActivity -> RdpSessionActivity), only when
// the app as a whole actually goes to/returns from the background. This is
// exactly what's needed to distinguish "user opened a session" from "user
// left the app", which Activity.onStop()/onStart() cannot distinguish.
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.systemsgo.hex.R
import com.systemsgo.hex.security.openEncryptedPrefs
import com.systemsgo.hex.audio.SoundManager
import com.systemsgo.hex.ui.components.LocalSoundManager
import com.systemsgo.hex.ui.screens.AppLockScreen
import com.systemsgo.hex.ui.screens.ConnectionHistoryScreen
import com.systemsgo.hex.ui.screens.DeviceDiscoveryScreen
import com.systemsgo.hex.ui.screens.RdWebFeedListScreen
import com.systemsgo.hex.ui.screens.RdWebFeedResourcesScreen
import com.systemsgo.hex.ui.screens.HomeScreen
import com.systemsgo.hex.ui.screens.SessionsScreen
import com.systemsgo.hex.ui.screens.SplitScreenPickerScreen
import com.systemsgo.hex.ui.screens.SettingsScreen
import com.systemsgo.hex.ui.screens.SettingsAppearanceScreen
import com.systemsgo.hex.ui.screens.SettingsCursorInputScreen
import com.systemsgo.hex.ui.screens.SettingsConnectionScreen
import com.systemsgo.hex.ui.screens.SettingsSessionScreen
import com.systemsgo.hex.ui.screens.SettingsDataScreen
import com.systemsgo.hex.ui.screens.DataManagementScreen
import com.systemsgo.hex.ui.screens.SettingsSecurityScreen
import com.systemsgo.hex.ui.screens.SettingsProfilesScreen
import com.systemsgo.hex.ui.screens.SettingsAboutScreen
import com.systemsgo.hex.ui.theme.SystemsGoTheme
import com.systemsgo.hex.ui.theme.SpaceMotion
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        // MANAGE-SPACE FEATURE: set by ManageSpaceActivity (the target of the
        // manifest's android:manageSpaceActivity) when the user reached this
        // Activity via the OS's "Manage space" button in
        // Settings → Apps → SystemsGo → Storage, instead of tapping the normal
        // in-app Settings → Data → "Clear Data" entry. Consumed once — see
        // the LaunchedEffect below — to auto-navigate straight to
        // DataManagementScreen right after the user clears the very same App
        // Lock gate (AppLockScreen) that already guards the rest of the app,
        // rather than depositing them on Home first.
        const val EXTRA_OPEN_DATA_MANAGEMENT = "com.systemsgo.hex.EXTRA_OPEN_DATA_MANAGEMENT"

        // QUICK-SETTINGS-TILE FEATURE (Part 1/2): set when the tile is
        // tapped with no connection bound to it yet — see
        // QuickConnectTileService.onClick()'s "unconfigured" branch. Same
        // "consume once, gated on isUnlocked" handling as
        // EXTRA_OPEN_DATA_MANAGEMENT above, navigating to
        // "settings/quick_tile" instead of the data-management screen.
        const val EXTRA_OPEN_QUICK_TILE_SETUP = "com.systemsgo.hex.EXTRA_OPEN_QUICK_TILE_SETUP"

        /** Launch intent used by [com.systemsgo.hex.tile.QuickConnectTileService] for its "tap to set up" tap. */
        fun quickTileSetupIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_QUICK_TILE_SETUP, true)
    }

    @Inject
    lateinit var soundManager: SoundManager

    // FIX #6: Track the current intent as a Compose state so that
    // onNewIntent() (fired when the app is already open and the user taps an
    // .rdp file) triggers a recomposition and re-runs the LaunchedEffect.
    private val _intentState = mutableStateOf<android.content.Intent?>(null)

    // FIX B6: حالة القفل مرفوعة إلى مستوى Activity حتى يمكن لـ onStop()
    // إعادة القفل عند ذهاب التطبيق للخلفية.
    private val _isUnlocked = mutableStateOf(false)

    // BUG #2 FIX: نحتاج لمعرفة إعدادات القفل في onStop() (خارج Compose)
    // نحفظ آخر قيمة معروفة هنا حتى يمكن فحصها بدون DataStore.
    @Volatile private var _lockEnabled = false

    // PERF-FIX (startup loading): true once MainViewModel's first real
    // profiles/settings/folders emission has landed (HomeUiState.isLoading
    // == false — see MainViewModel line ~359). Read from the native splash
    // screen's keepOnScreenCondition below, which the system polls on the
    // main thread on every frame attempt until it returns false, so it must
    // be a plain @Volatile field rather than Compose state (that condition
    // runs outside the Compose tree, before/without a composition existing
    // yet on a cold start). Kept the system's own splash icon/branding on
    // screen for those extra tens-of-ms instead of drawing an empty or
    // partially-populated Home screen (e.g. an empty profile list that then
    // pops in a moment later) underneath it.
    @Volatile private var _initialDataReady = false

    // FEATURE-AUTO-LOCK: last-known Auto Lock timeout (mirrors _lockEnabled's
    // pattern — kept in sync from Compose via LaunchedEffect so onStop()/
    // onStart() can read it without collecting a Flow outside Compose).
    @Volatile private var _autoLockTimeoutMs = com.systemsgo.hex.data.repository.AppSettings.AUTO_LOCK_IMMEDIATE

    // FEATURE-AUTO-LOCK: monotonic timestamp (SystemClock.elapsedRealtime(),
    // NOT wall-clock time) recorded when the app truly goes to the background
    // and a non-immediate timeout is active, so the process-lifecycle "start"
    // callback below can measure actual elapsed background time — this is
    // what makes the timeout "count while the app is in the background"
    // without needing a running timer/service.
    // BUGFIX (wall-clock -> elapsed-realtime): this used to be recorded with
    // System.currentTimeMillis(), which follows the device's wall clock. If
    // the user (or the system, e.g. via NTP sync) changed the clock while the
    // app was backgrounded, the "elapsed" calculation could come out negative
    // or wildly too large, silently skipping or forcing a re-lock regardless
    // of the real background duration.
    // SystemClock.elapsedRealtime() is monotonic and unaffected by clock/
    // timezone changes (it still includes sleep time), so it's the correct
    // clock for measuring a duration like this.
    // 0L means "not currently tracking a background period".
    @Volatile private var _backgroundedAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // BUGFIX-THEME-FLASH: pick the correct native theme *before* installSplashScreen()
        // and super.onCreate() so the splash/window background matches the user's saved
        // dark/light preference instead of always showing the dark theme (see comment on
        // Theme.SystemsGo.Light in themes.xml). This must be a fast, synchronous read — the
        // settings repository's EncryptedSharedPreferences-backed store works here (unlike
        // the old plain DataStore, which was async and too slow for this point in the
        // lifecycle). Falls back to the dark theme (the existing default) on any error,
        // e.g. a corrupt Keystore-backed store on first run.
        val isDarkMode = try {
            openEncryptedPrefs("systemsgo_settings").getBoolean("is_dark_mode", true)
        } catch (e: Exception) {
            true
        }
        if (!isDarkMode) {
            setTheme(R.style.Theme_SystemsGo_Light)
        }
        // BUG-COMPAT-2 FIX: installSplashScreen() MUST be called before super.onCreate().
        val splashScreen = installSplashScreen()
        // PERF-FIX (startup loading): keep the native splash (icon on the
        // themed background — zero Compose/layout cost, already drawn by the
        // system before our window even exists) up until the first real data
        // load finishes, instead of letting it disappear on the first drawn
        // frame (the default) and revealing a Home screen that's still
        // empty/loading underneath — e.g. while the SQLCipher-encrypted
        // database is still being opened/decrypted on a background thread
        // (see SystemsGoApp's Lazy<Repository> warm-up). This turns what used
        // to be a visible "blank screen -> content pops in" flash into a
        // single continuous splash -> ready transition, using the system's
        // own (already-hardware-accelerated) splash exit animation.
        splashScreen.setKeepOnScreenCondition { !_initialDataReady }
        super.onCreate(savedInstanceState)

        // AUTO-LOCK-FIX: register the process-level (not Activity-level)
        // foreground/background observer used for Auto Lock — see the
        // comment on processLifecycleObserver above for why this replaced
        // the old Activity.onStop()/onStart() based approach.
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

        // BUGFIX #5 (updated): FLAG_SECURE used to be set unconditionally on this
        // Activity's window, which blocked screenshots/screen-recording of the Home
        // screen and Settings too, not just the PIN lock screen — removed at the
        // user's request so normal use of the app can be screenshotted/recorded.
        // SECURITY FIX: that left the PIN lock screen and password fields exposed
        // to capture (e.g. via MediaProjection), so FLAG_SECURE is no longer just
        // "off" here — it's re-applied narrowly and automatically by SecureScreen()
        // (see security/SecureScreen.kt) only while the lock screen or a
        // password/passphrase field is actually in composition.
        _intentState.value = intent
        enableEdgeToEdge()

        setContent {
            val viewModel: MainViewModel = hiltViewModel()

            // FIX #6: keyed on _intentState so it re-runs when onNewIntent fires.
            val currentIntent by _intentState
            LaunchedEffect(currentIntent?.data) {
                if (currentIntent?.action == android.content.Intent.ACTION_VIEW) {
                    currentIntent?.data?.let { uri -> viewModel.parseRdpUri(uri, contentResolver) }
                }
            }

            val uiState  by viewModel.uiState.collectAsStateWithLifecycle()
            val settings = uiState.settings

            // PERF-FIX (startup loading): release the native splash screen
            // (see setKeepOnScreenCondition above) exactly once, the first
            // moment real data is in. A plain assignment (not a
            // LaunchedEffect keyed only once) so it's idempotent and safe to
            // evaluate on every recomposition without side effects.
            if (!uiState.isLoading) {
                _initialDataReady = true
            }

            // ── قفل التطبيق ─────────────────────────────────────────────────
            // BUG #5 FIX: نبدأ مقفلاً (false) ريثما تُحمَّل الإعدادات من DataStore.
            // FIX B6: الحالة مُعرَّفة على مستوى Activity حتى يمكن إعادة القفل من onStop().
            var isUnlocked by _isUnlocked
            // BUG #2 FIX: نحدّث _lockEnabled في كل مرة تتغير إعدادات القفل
            // حتى تستطيع onStop() معرفة هل يجب إعادة القفل أم لا.
            LaunchedEffect(settings.biometricLockEnabled, settings.pinLockEnabled) {
                _lockEnabled = settings.biometricLockEnabled || settings.pinLockEnabled
            }
            // FEATURE-AUTO-LOCK: keep the Activity-level copy in sync so
            // onStop()/onStart() (outside Compose) always see the latest choice.
            LaunchedEffect(settings.autoLockTimeoutMs) {
                _autoLockTimeoutMs = settings.autoLockTimeoutMs
            }
            LaunchedEffect(uiState.isLoading, settings.biometricLockEnabled, settings.pinLockEnabled) {
                if (!uiState.isLoading && !settings.biometricLockEnabled && !settings.pinLockEnabled) {
                    // الإعدادات حُمِّلت ولا يوجد قفل مفعَّل — افتح مباشرة
                    isUnlocked = true
                }
            }

            SystemsGoTheme(
                darkTheme    = settings.isDarkMode,
                themeVariant = settings.themeVariant
            ) {
                CompositionLocalProvider(LocalSoundManager provides soundManager) {
                    LaunchedEffect(settings.soundEnabled) {
                        soundManager.setEnabled(settings.soundEnabled)
                    }

                    // FIX #2: إعداد اللغة كان محفوظًا في DataStore لكن لم يكن يُطبَّق أبدًا.
                    // نستخدم AppCompatDelegate.setApplicationLocales() (يعمل على API 26+)
                    // لتغيير لغة التطبيق فعليًا عند كل تغيير في الإعداد.
                    // على Android 13+ يُعيد النظام تشغيل الـ Activity تلقائيًا.
                    LaunchedEffect(settings.language) {
                        val locales = when (settings.language) {
                            "system" -> LocaleListCompat.getEmptyLocaleList()
                            else     -> LocaleListCompat.forLanguageTags(settings.language)
                        }
                        AppCompatDelegate.setApplicationLocales(locales)
                    }

                    // BUGFIX-UI (critical): the lock screen and the main app content
                    // used to be two SEPARATE, mutually-exclusive AnimatedVisibility
                    // blocks, with rememberNavController() created *inside* the main
                    // content's block. AnimatedVisibility fully removes its content
                    // from composition once `visible` goes false — so every time the
                    // app re-locked (including just backgrounding it for a second to
                    // check a notification, per onStop() above), the entire NavHost
                    // subtree was disposed. Unlocking again then created a *brand new*
                    // NavController from scratch, silently resetting navigation back to
                    // "home" no matter which screen the user had actually been on.
                    //
                    // Fix: the NavHost (and its NavController) now lives outside the
                    // lock/unlock branching entirely, so it is created exactly once and
                    // its back stack survives any number of lock/unlock cycles. The lock
                    // screen is instead drawn as an opaque overlay on top of it — safe to
                    // do since AppLockScreen's StarfieldBackground fully paints over the
                    // whole screen, so nothing from the content underneath is visible
                    // while locked.
                    Box(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()

                        // MANAGE-SPACE FEATURE: consumed once. currentIntent carries
                        // EXTRA_OPEN_DATA_MANAGEMENT when we were reached through
                        // ManageSpaceActivity (the OS "Manage space" button) rather
                        // than a normal launch. We only remember the *request*
                        // here — the actual navigation is gated on isUnlocked below,
                        // so it still waits for the same PIN/biometric check
                        // (AppLockScreen, drawn further down) that every other route
                        // in this NavHost is already protected by.
                        var pendingManageSpaceRequest by remember { mutableStateOf(false) }
                        LaunchedEffect(currentIntent) {
                            if (currentIntent?.getBooleanExtra(EXTRA_OPEN_DATA_MANAGEMENT, false) == true) {
                                pendingManageSpaceRequest = true
                            }
                        }
                        LaunchedEffect(isUnlocked, pendingManageSpaceRequest) {
                            // SECURITY FIX (guest-mode data-clear bypass): isUnlocked also
                            // becomes true via "Continue as Guest" (no real PIN/biometric
                            // check) — see DataResetScreen/AppLockScreen's onGuestMode. Only
                            // auto-navigate here for a genuine unlock, so a guest who reached
                            // this trampoline is left on the ordinary (empty) Home screen
                            // instead of being routed to the primary account's data-clear
                            // screen. DataManagementScreen and its ViewModel functions each
                            // also independently refuse to act for a guest — this just avoids
                            // even the redirect flicker for that case.
                            if (isUnlocked && pendingManageSpaceRequest && !viewModel.isGuestMode.value) {
                                pendingManageSpaceRequest = false
                                navController.navigate("settings/data/manage")
                            } else if (isUnlocked && pendingManageSpaceRequest) {
                                pendingManageSpaceRequest = false
                            }
                        }

                        // QUICK-SETTINGS-TILE FEATURE (Part 1/2): identical trampoline
                        // shape to pendingManageSpaceRequest just above, for the tile's
                        // "tap to set up" case (see MainActivity.quickTileSetupIntent()
                        // and QuickConnectTileService.onClick()'s unconfigured branch).
                        // Settings is unreachable in Guest Mode for the same reason
                        // "settings/profiles" already is (see rememberSettingsCategories's
                        // PROFILE-SWITCHER comment) — a guest session must never be able
                        // to reach the primary account's Settings hub this way either.
                        var pendingQuickTileSetupRequest by remember { mutableStateOf(false) }
                        LaunchedEffect(currentIntent) {
                            if (currentIntent?.getBooleanExtra(EXTRA_OPEN_QUICK_TILE_SETUP, false) == true) {
                                pendingQuickTileSetupRequest = true
                            }
                        }
                        LaunchedEffect(isUnlocked, pendingQuickTileSetupRequest) {
                            if (isUnlocked && pendingQuickTileSetupRequest && !viewModel.isGuestMode.value) {
                                pendingQuickTileSetupRequest = false
                                navController.navigate("settings/quick_tile")
                            } else if (isUnlocked && pendingQuickTileSetupRequest) {
                                pendingQuickTileSetupRequest = false
                            }
                        }

                        // UI-FIX (transitions): every screen transition — including
                        // Settings and all of its new sub-sections — now shares the
                        // same "hyperspace" motion defined in SpaceMotion, instead of
                        // the old generic horizontal slide.
                        NavHost(
                            navController       = navController,
                            startDestination    = "home",
                            modifier            = Modifier.fillMaxSize(),
                            enterTransition     = SpaceMotion.enterTransition,
                            exitTransition      = SpaceMotion.exitTransition,
                            popEnterTransition  = SpaceMotion.popEnterTransition,
                            popExitTransition   = SpaceMotion.popExitTransition,
                        ) {
                            composable("home")               { HomeScreen(navController = navController) }
                            // ADD-CONNECTION PROTOCOL PICKER (Part 2/2): HomeScreen's
                            // onNewConnection now lands here first instead of opening
                            // ProfileFormDialog directly — see AddConnectionRoute's class doc.
                            composable("add_connection_protocol") {
                                com.systemsgo.hex.ui.screens.addconnection.AddConnectionRoute(navController = navController)
                            }
                            composable("discover_devices")   { DeviceDiscoveryScreen(navController = navController) }
                            composable("webfeed")            { RdWebFeedListScreen(navController = navController) }
                            composable("webfeed/{feedId}") { backStackEntry ->
                                val feedId = backStackEntry.arguments?.getString("feedId").orEmpty()
                                RdWebFeedResourcesScreen(navController = navController, feedId = feedId)
                            }
                            composable("connection_history") { ConnectionHistoryScreen(navController = navController) }
                            composable("sessions")           { SessionsScreen(navController = navController) }
                            // SPLIT-SCREEN FEATURE: picker to choose two saved
                            // connections (any RDP/VNC/SSH combination) and
                            // launch them together in SplitScreenActivity.
                            composable("split_screen_picker") { SplitScreenPickerScreen(navController = navController) }

                            // UI-FIX (settings reorganization): the settings screen was
                            // a single long page mixing ~25 unrelated options. It's now
                            // a hub of a few clearly-labeled categories, each opening its
                            // own focused screen.
                            composable("settings")                     { SettingsScreen(navController = navController) }
                            composable("settings/appearance")          { SettingsAppearanceScreen(navController = navController) }
                            composable("settings/cursor_input")        { SettingsCursorInputScreen(navController = navController) }
                            composable("settings/connection")          { SettingsConnectionScreen(navController = navController) }
                            composable("settings/session_controls")    { SettingsSessionScreen(navController = navController) }
                            composable("settings/data")                { SettingsDataScreen(navController = navController) }
                            // SETTINGS "Clear Data" REDESIGN: reached only after
                            // SettingsDataScreen's "Clear Data" item has already
                            // passed the SecurityConfirmDialog re-auth gate — see
                            // DataManagementScreen.kt for why this route performs
                            // no destructive action by itself on entry.
                            composable("settings/data/manage")         { DataManagementScreen(navController = navController) }
                            composable("settings/security")            { SettingsSecurityScreen(navController = navController) }
                            // CLOUD-SYNC FEATURE (Part 3-b)
                            composable("settings/cloud_sync")          { com.systemsgo.hex.ui.screens.CloudSyncSettingsScreen(navController = navController) }
                            // USB-REDIRECT FEATURE (Part 1/3)
                            composable("settings/usb_redirection")     { com.systemsgo.hex.ui.screens.UsbRedirectionSettingsScreen(onBack = { navController.popBackStack() }) }
                            // QUICK-SETTINGS-TILE FEATURE (Part 1/2)
                            composable("settings/quick_tile")          { com.systemsgo.hex.ui.screens.QuickTileSettingsScreen(navController = navController) }
                            // HOME-SCREEN-WIDGET FEATURE (Part 2/2): purely explanatory —
                            // see HomeScreenWidgetInfoScreen's own doc comment.
                            composable("settings/home_widget")         { com.systemsgo.hex.ui.screens.HomeScreenWidgetInfoScreen(navController = navController) }
                            // PROFILE-SWITCHER FEATURE: direct entry point to switch to the
                            // isolated Guest Profile, without needing the "Forgot PIN?" flow.
                            composable("settings/profiles")            { SettingsProfilesScreen(navController = navController) }
                            composable("settings/about")               { SettingsAboutScreen(navController = navController) }
                            // ABOUT-REDESIGN: Privacy Policy / Terms of Service render from
                            // local string resources (no hosted page exists yet) via the
                            // same SettingsLegalScreen, parameterized by which document to show.
                            composable("settings/about/privacy")       { com.systemsgo.hex.ui.screens.SettingsLegalScreen(navController = navController, isPrivacyPolicy = true) }
                            composable("settings/about/terms")         { com.systemsgo.hex.ui.screens.SettingsLegalScreen(navController = navController, isPrivacyPolicy = false) }
                        }

                        // FULL-SCREEN REDESIGN (REQ-4): polled once here and shared by
                        // both branches below instead of each one polling independently.
                        val dataResetRemainingMs by com.systemsgo.hex.ui.screens.rememberDataResetRemainingMillis()

                        // ── شاشة القفل / شاشة إلغاء المسح المجدول (تُرسم فوق المحتوى دون التخلص منه) ──
                        AnimatedVisibility(
                            visible  = !isUnlocked,
                            enter    = fadeIn(),
                            exit     = fadeOut(tween(300)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // SECURITY FIX: re-scoped FLAG_SECURE (see SecureScreen.kt) — the
                            // PIN pad's dot-order and the numpad key a user taps both leak the
                            // PIN through screen recording (MediaProjection) or the Recents
                            // live thumbnail even with digits masked as dots, so this window is
                            // blanked out of both while the lock/reset screen is in composition.
                            // Cleared automatically the instant this block leaves composition
                            // (unlock, or the reset screen's own exit), so it never affects the
                            // Home/Settings/RDP-session screens behind it.
                            com.systemsgo.hex.security.SecureScreen()
                            val pendingResetMs = dataResetRemainingMs
                            if (pendingResetMs != null) {
                                // FULL-SCREEN REDESIGN (REQ-4): while the app is locked AND a
                                // wipe is pending — i.e. exactly the "forgot PIN, scheduled a
                                // reset" situation — show the dedicated full-screen countdown
                                // instead of the plain PIN pad. It offers the same two paths
                                // forward the old small banner + lock screen combo did, just
                                // consolidated onto one screen: cancel (PIN/biometric via
                                // SecurityConfirmDialog) or continue as Guest.
                                com.systemsgo.hex.ui.screens.DataResetScreen(
                                    remainingMs           = pendingResetMs,
                                    pinLockEnabled         = settings.pinLockEnabled,
                                    biometricLockEnabled   = settings.biometricLockEnabled,
                                    encryptedPin           = settings.pinCode,
                                    onCancelled            = { isUnlocked = true },
                                    onGuestMode            = {
                                        viewModel.enterGuestMode()
                                        isUnlocked = true
                                    }
                                )
                            } else {
                                AppLockScreen(
                                    biometricEnabled = settings.biometricLockEnabled,
                                    pinEnabled       = settings.pinLockEnabled,
                                    encryptedPin     = settings.pinCode,
                                    isUnlocked       = isUnlocked,
                                    onUnlocked       = { isUnlocked = true },
                                    // GUEST-MODE FEATURE: "Continue as Guest" both enters the
                                    // isolated Guest profile on the ViewModel and unlocks the
                                    // screen, so the user lands straight on an empty Home screen.
                                    onGuestMode      = {
                                        viewModel.enterGuestMode()
                                        isUnlocked = true
                                    }
                                )
                            }
                        }

                        // DELAYED-RESET FEATURE: compact, non-blocking reminder for the
                        // remaining case — a reset is pending but the app is already
                        // unlocked (typically: the user picked "Continue as Guest" on
                        // DataResetScreen above and is now actively using the app).
                        // The full-screen takeover above only applies while locked; see
                        // DataResetBanner's own doc for why we don't force it here too.
                        if (isUnlocked && dataResetRemainingMs != null) {
                            com.systemsgo.hex.ui.screens.DataResetBanner(
                                pinLockEnabled       = settings.pinLockEnabled,
                                biometricLockEnabled = settings.biometricLockEnabled,
                                encryptedPin         = settings.pinCode,
                                modifier             = Modifier.align(Alignment.TopCenter)
                            )
                        }
                    }
                }
            }
        }
    }

    // BUG-Y1 FIX: SoundManager is @Singleton — it lives for the entire process lifetime.
    // The previous code called release() unconditionally in onDestroy(), which fires on
    // BOTH screen rotation AND true finish. On rotation isFinishing=false, so the singleton
    // was permanently poisoned (released=true) and all sounds died silently for the rest
    // of the session, even though the same instance was still injected into the new Activity.
    // Fix: only release when the Activity is truly finishing (user navigates away / back-press).
    override fun onDestroy() {
        // AUTO-LOCK-FIX: unregister the process-level observer registered in
        // onCreate() to avoid leaking this Activity instance.
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        super.onDestroy()
        if (isFinishing) {
            soundManager.release()
        }
    }

    // FIX B6 + BUG #2 FIX: إعادة القفل التلقائي عند ذهاب التطبيق للخلفية،
    // لكن فقط إذا كان قفل البصمة أو PIN مفعَّلاً فعلاً.
    // الخلل الأصلي: _isUnlocked.value = false تُستدعى دائماً بغضّ النظر
    // عن إعدادات القفل، مما يجعل المستخدمين بدون قفل محاصرين في شاشة
    // لا تحتوي على طريقة للفتح (لا بصمة ولا PIN).
    //
    // FEATURE-AUTO-LOCK: this now branches on the Auto Lock timeout setting
    // instead of always locking the instant the app backgrounds:
    //   - AUTO_LOCK_DISABLED: never re-lock due to backgrounding at all.
    //   - AUTO_LOCK_IMMEDIATE (default, matches original behaviour): lock now.
    //   - any positive timeout: don't lock yet — just record the timestamp.
    //     the "app foregrounded" callback below compares elapsed real time
    //     against this timeout the next time the app returns to the
    //     foreground and locks then if it was exceeded. Using a timestamp
    //     (rather than a running timer/service) means the elapsed time is
    //     measured correctly no matter how long the process sits
    //     backgrounded, with no extra battery/lifecycle cost — i.e. the
    //     timeout still "counts" the whole time the app is backgrounded,
    //     it's just evaluated lazily on return.
    //
    // BUGFIX (AUTO-LOCK-FIX): this logic used to live in Activity.onStop()/
    // onStart(). Those fire on *every* Activity transition within the same
    // task — including MainActivity -> RdpSessionActivity, which launches in
    // the same task with no NEW_TASK flag (see RdpSessionActivity/HomeScreen).
    // That meant opening any RDP/VNC/SSH session called MainActivity.onStop(),
    // and returning from it called onStart() — re-locking (or re-checking the
    // timeout) on every single session open/close even though the user never
    // actually left the app. Users with AUTO_LOCK_IMMEDIATE (the default) and
    // a PIN/biometric enabled were forced to re-authenticate every time they
    // returned from a session to Home.
    //
    // Fix: use ProcessLifecycleOwner instead, which only reports onStart/
    // onStop for the app process as a whole — it does not fire when one
    // Activity in the same task is replaced by another, only when the last
    // visible Activity actually stops with nothing new starting (i.e. the
    // user truly backgrounded the app: Home button, app switcher, screen off,
    // etc).
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            // نُعيد القفل فقط إذا كان أحد آليات القفل مفعَّلاً.
            // _lockEnabled يُحدَّث من LaunchedEffect أعلاه مع كل تغيير للإعدادات.
            if (_lockEnabled) {
                val timeout = _autoLockTimeoutMs
                when {
                    timeout == com.systemsgo.hex.data.repository.AppSettings.AUTO_LOCK_DISABLED -> {
                        // Auto Lock turned off — leave the app unlocked.
                    }
                    timeout <= com.systemsgo.hex.data.repository.AppSettings.AUTO_LOCK_IMMEDIATE -> {
                        _isUnlocked.value = false
                    }
                    else -> {
                        // BUGFIX: SystemClock.elapsedRealtime() instead of
                        // System.currentTimeMillis() — monotonic, unaffected
                        // by the user or system changing the wall clock while
                        // the app is backgrounded.
                        _backgroundedAtMs = SystemClock.elapsedRealtime()
                    }
                }
            }
        }

        // Runs every time the app process actually returns to the foreground.
        // If a background period was being tracked (onStop above) and it
        // lasted at least as long as the configured timeout, re-lock now.
        override fun onStart(owner: LifecycleOwner) {
            if (_lockEnabled && _backgroundedAtMs != 0L) {
                val elapsed = SystemClock.elapsedRealtime() - _backgroundedAtMs
                if (elapsed >= _autoLockTimeoutMs) {
                    _isUnlocked.value = false
                }
                _backgroundedAtMs = 0L
            }
        }
    }

    // FIX #6: Handle .rdp file intents when the app is already in the foreground.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        _intentState.value = intent
    }
}
