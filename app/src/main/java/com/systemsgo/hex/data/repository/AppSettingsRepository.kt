package com.systemsgo.hex.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.systemsgo.hex.data.model.TerminalSnippet
import com.systemsgo.hex.security.openEncryptedPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val isDarkMode: Boolean = true,
    val language: String = "system",
    val themeVariant: String = "space",
    val cursorStyle: String = "default",
    val cursorSize: Int = 24,
    val showCursorOnTouch: Boolean = true,
    val touchpadSensitivity: Float = 1.0f,
    val scrollSensitivity: Float = 1.0f,
    val hasShownFirstLaunch: Boolean = false,
    val hapticFeedback: Boolean = true,
    val keepScreenOn: Boolean = true,
    // ALWAYS-RECONNECT FIX: the "Auto Reconnect" toggle and its paired
    // attempt-limit (autoReconnectAttempts) were removed — a session now
    // always keeps retrying a dropped/errored connection until the user
    // intentionally disconnects or the server returns a real, definitive
    // error (e.g. rejected credentials). See RdpSessionActivity's
    // attachStateCollector()/loadAndConnectQuick() ERROR/DISCONNECTED
    // branches, which no longer read a toggle or a stored attempt cap.
    val showFpsCounter: Boolean = false,
    // TOOLBOX FEATURE (Stage 7): "سرعة الاستجابة" (latency) was previously
    // shown/hidden as one unit together with the FPS number, both gated by
    // the single showFpsCounter flag above (see the old FIX-B1/BUG-M3
    // combined fpsText in RdpSessionActivity). The plan requires the two to
    // become fully independent tools, so latency now has its own setting —
    // showFpsCounter is kept exactly as-is (FPS only, existing installs see
    // no behaviour change) and this new flag controls the latency overlay
    // on its own.
    val showLatencyCounter: Boolean = false,
    // TOOLBOX FEATURE (Stage 7 follow-up): the user can now drag the FPS/
    // latency overlay to any spot on screen (one finger) and pinch it
    // bigger/smaller (two fingers) — independent of the Toolbox's own
    // position. Stored as 0f..1f fractions of the available width/height,
    // same re-anchoring rationale as toolboxPosXFraction/-Y above. -1f is a
    // sentinel meaning "user never dragged this yet" — until they do, a
    // sensible RTL-aware default corner is used instead (see
    // CounterOverlayState.kt) rather than persisting a guessed value that
    // might not even make sense on a different device/orientation.
    val counterPosXFraction: Float = -1f,
    val counterPosYFraction: Float = -1f,
    val counterScale: Float = 1.0f,
    val defaultResolution: String = "auto",
    // QUALITY-UNIFY FIX: colorDepth and performanceLevel used to be two
    // separate problems. First, they were per-connection-only fields (set in
    // the Add/Edit Connection form) while Default Resolution lived globally
    // in Settings — one concept split across two screens. Second,
    // performanceLevel itself used to compete with a raw 0-100 "Frame
    // Compression Quality" slider that also claimed to control quality, in
    // different units, so a user with a bad connection had to know to turn
    // down *both* to get a responsive session.
    // Both issues are fixed the same way: colorDepth and performanceLevel are
    // now global (Settings → Connection, alongside Default Resolution), and
    // performanceLevel is the *only* quality dial — a single 5-level
    // network-strength slider. RdpPerformance.flagsFor() and
    // RdpPerformance.codecQualityFor() both derive from it, so visual effects
    // and codec quality always move together instead of two sliders a user
    // has to keep in sync by hand. RdpProfile still carries its own
    // colorDepth/performanceFlags/width/height columns (kept only so existing
    // Room rows don't need a migration), but none of them are read at connect
    // time any more — see RdpSessionActivity / RemoteSessionFactory, which
    // source colorDepth, performanceLevel and the derived codec quality from
    // here for every session (profile-based and Quick Connect alike).
    val colorDepth: Int = com.systemsgo.hex.data.model.RdpPerformance.COLOR_DEPTH_AUTO,
    val performanceLevel: Int = com.systemsgo.hex.data.model.RdpPerformance.AUTO,
    // UDP-TRANSPORT FEATURE: MS-RDPEMT — request that RDP sessions try to
    // move bulk graphics traffic onto UDP alongside the classic TCP channel,
    // when both this client and the server support it. Global (like
    // colorDepth/performanceLevel above) rather than per-profile, since it's
    // the same kind of "how should any RDP session behave on this device's
    // network" dial. Off by default: most RD Gateways/corporate firewalls
    // block the extra UDP ports, and even when they don't, FreeRDP falls
    // back to TCP transparently, so turning this on can only help — but it
    // stays opt-in so a user on a locked-down network isn't stalled waiting
    // on a UDP handshake that will never complete. See
    // AFreeRdpBridge.connect()'s enableUdpTransport doc and
    // systemsgo_jni.c's FreeRDP_SupportMultitransport block for the native
    // side of this.
    val udpTransportEnabled: Boolean = false,
    // SMART-SIZING FEATURE: classic RDP-client "Smart Sizing" — keep the
    // remote session at a FIXED resolution (whatever it connected at)
    // instead of sending a live MS-RDPEDISP resize every time the local
    // viewport changes (rotation, split-screen resize, external-monitor
    // hot-plug, DeX window resize). The rendered frame is then scaled to
    // fit the current viewport purely on the client side.
    // This needs no new rendering code: RdpCanvas's letterboxSize() already
    // computes an aspect-correct fit-to-viewport size for the frame on
    // every draw (see its doc comment) — it was just always a near-no-op
    // before, since screenWidth/screenHeight (the remote resolution) was
    // kept equal to the viewport by the *other* path (live resize, the
    // existing default/off behavior). Turning this on simply stops
    // RdpSessionViewModel.updateDisplayMetrics() from pushing that live
    // resize, so screenWidth/screenHeight stay fixed at their connect-time
    // value and the pre-existing letterbox scaling in RdpCanvas takes over
    // doing the actual visual fit — useful for a server that renders more
    // reliably at one resolution (e.g. a fixed-DPI RemoteApp/kiosk session)
    // or that doesn't handle frequent resize requests well. Off by default,
    // matching this session's other opt-in toggles — existing behavior
    // (dynamic resolution matching the viewport) is unchanged until a user
    // turns this on.
    val smartSizingEnabled: Boolean = false,
    val sessionToolbarVisible: Boolean = true,
    val sessionExtraKeysVisible: Boolean = true,
    val runInBackground: Boolean = true,
    val soundEnabled: Boolean = true,
    val biometricLockEnabled: Boolean = false,
    val pinLockEnabled: Boolean = false,
    val pinCode: String = "",                    // مُشفَّر
    // FEATURE-AUTO-LOCK: how long the app may sit in the background before the
    // lock screen (AppLockScreen) is re-shown on return. Only meaningful when
    // biometricLockEnabled or pinLockEnabled is on. Encoded as a single Long so
    // no extra storage key/migration is needed for the "kind" of value:
    //   AUTO_LOCK_DISABLED (-1)  -> never re-lock due to backgrounding
    //   AUTO_LOCK_IMMEDIATE (0)  -> re-lock the instant the app backgrounds
    //                               (matches the app's original, pre-feature
    //                               behaviour — kept as the default so existing
    //                               users see no change unless they opt in)
    //   > 0                      -> milliseconds the app may stay backgrounded
    //                               before the next foreground re-locks it
    val autoLockTimeoutMs: Long = AUTO_LOCK_IMMEDIATE,
    val rightClickLongPress: Boolean = true,
    val hasShownGestureHints: Boolean = false,
    // UI-POLISH: number of times the home screen has been opened — used to show the
    // full "swipe to edit/delete" text hint only for the first few visits, then fall
    // back to the small persistent icon-only hint. Avoids permanent text-noise on
    // every card once the user already knows the gesture.
    val homeScreenOpenCount: Int = 0,
    // POWER FIX: when true, RdpSessionService relies solely on the RDP/VNC/SSH
    // socket's TcpKeepAlive to keep the connection alive in the background and
    // does NOT hold a partial wake lock. Off by default to preserve existing
    // "never drop a background session" behaviour; users on a battery budget
    // (or who don't care about frame latency while the app isn't visible) can
    // opt in from Settings.
    val backgroundPowerSaving: Boolean = false,
    // FEATURE-TERM-FONT: SSH terminal text size, in sp. Previously hardcoded at
    // 13.sp in TerminalScreen with no way for the user to change it — a real
    // problem on high-DPI phones (too small to read) and on small screens in
    // landscape (too large, forcing constant horizontal scrolling). Persisted
    // globally (like cursorSize) so it's remembered across sessions/reconnects.
    val terminalFontSize: Int = DEFAULT_TERMINAL_FONT_SIZE,
    // FEATURE-TERM-SNIPPETS: user-saved commands the SSH terminal can re-run
    // with a single tap (e.g. "ls -la", "sudo systemctl restart nginx")
    // instead of retyping/pasting them every session. Stored app-wide (not
    // per-profile) since the same handful of commands are typically useful
    // across every server a user connects to.
    val terminalSnippets: List<TerminalSnippet> = emptyList(),
    // VPN-AWARE-CONNECTIVITY: which network new remote-session connections are
    // allowed to use, as the name of a
    // com.systemsgo.hex.util.VpnConnectivityManager.NetworkBindingPreference
    // ("ANY", "VPN_ONLY", "WIFI_ONLY", "CELLULAR_ONLY"). Stored as a plain
    // string (like themeVariant/cursorStyle above) rather than the enum type
    // itself, matching every other setting in this class — the enum lives in
    // the util package, not in this data/repository module. Global (not
    // per-profile) since it reflects a device-level connectivity choice
    // ("only ever use my VPN for remote sessions") the user makes once,
    // applying equally to RDP/VNC/SSH and to both saved profiles and Quick
    // Connect. Defaults to "ANY" so existing installs see no behaviour change
    // until a user opts into a stricter binding.
    val networkBinding: String = "ANY",
    // TOOLBOX FEATURE (Stage 0): SessionToolbox replaces the old fixed
    // SessionToolbar with a draggable, customizable container. Ordered list
    // of tool ids pinned to the Quick Bar, comma-separated (a JSON list is
    // overkill for a handful of short ids). Empty means "use the default
    // set" — see SessionToolboxState.DEFAULT_QUICK_TOOL_IDS.
    val toolboxQuickToolIds: String = "",
    // Last floating position of the Toolbox container, stored as a fraction
    // (0f..1f) of the available width/height so it re-anchors sensibly on
    // any screen size/orientation instead of a fixed dp offset that could
    // land off-screen after a rotation or on a different device.
    val toolboxPosXFraction: Float = 1f,
    val toolboxPosYFraction: Float = 0f,
    // Which screen edge the Quick Bar is docked to: "top", "bottom",
    // "start", or "end" (RTL-aware — "start"/"end" instead of left/right).
    val toolboxDockEdge: String = "top",
    // TOOLBOX FEATURE (Stage 5): "قلب الشاشة" — purely local/visual mirroring
    // of the remote frame ("normal", "horizontal", "vertical", "rotate_180" —
    // see ScreenFlipMode.toSetting()/fromSetting()). Decision documented here
    // per the stage's requirement to state the chosen behaviour clearly:
    // this is stored GLOBALLY (not per-profile) and is APPLIED AUTOMATICALLY
    // to every future session rather than reset each session — the same
    // pattern already used for every other session-display preference in
    // this class (cursorStyle, cursorSize, touchpadSensitivity...). A user
    // who mirrors their display (e.g. because of how their remote monitor is
    // physically mounted, or a projector/mirror setup) almost always wants
    // that mirroring to persist across sessions, exactly like their chosen
    // cursor style does — resetting it on every reconnect would just make
    // them re-select it constantly.
    val screenFlipMode: String = "normal",
    // TOOLBOX FEATURE (Stage 6a): "وضع الماوس/تاتش باد" — "touchpad" (relative,
    // pre-existing behaviour driven by touchpadSensitivity) or "direct"
    // (absolute — the finger's on-screen position maps straight to the
    // matching remote pixel, see MouseInputMode.kt doc comment). Stored
    // GLOBALLY and auto-applied to future sessions, same reasoning as
    // screenFlipMode above: whichever input style a user's hand is used to
    // is a device-level preference, not something worth re-choosing every
    // reconnect.
    val mouseInputMode: String = "touchpad",
    // DATA-SAVER FEATURE: a single global override that forces every
    // session — regardless of what Display Quality/Color Depth are
    // otherwise set to, AUTO or a fixed level — down to the lightest
    // possible codec/color settings (RdpPerformance.LOW_BANDWIDTH, 16-bit
    // color). Distinct from performanceLevel == AUTO: AUTO still *measures*
    // the live network and can land on a high-quality level on a fast
    // connection; this is an explicit "minimize data no matter how fast the
    // network looks" switch for a metered/capped connection (e.g. mobile
    // hotspot, roaming) where link speed and data cost are unrelated. See
    // NetworkQualityDetector.resolve()/resolveColorDepth()'s dataSaverEnabled
    // parameter for where this is actually applied. Off by default —
    // existing installs see no behaviour change until a user opts in.
    val dataSaverEnabled: Boolean = false,
) {
    companion object {
        const val MIN_TERMINAL_FONT_SIZE = 10
        const val MAX_TERMINAL_FONT_SIZE = 24
        const val DEFAULT_TERMINAL_FONT_SIZE = 14

        // FEATURE-AUTO-LOCK sentinel values for autoLockTimeoutMs.
        const val AUTO_LOCK_DISABLED: Long = -1L
        const val AUTO_LOCK_IMMEDIATE: Long = 0L
    }
}

@Singleton
class AppSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // CRIT-5 FIX: Replace plain Preferences DataStore with EncryptedSharedPreferences.
    //
    // Plain DataStore writes a protobuf file with no authentication or encryption.
    // On a rooted device any process can:
    //   • Read PIN_LOCK_ENABLED and set it to false → bypass app lock entirely.
    //   • Read the encrypted PIN ciphertext and replace it with a ciphertext of a
    //     known PIN (under the same Keystore key) → unlock the app with an attacker-
    //     controlled PIN.
    // Neither attack is detectable because plain DataStore has no tamper-detection tag.
    //
    // EncryptedSharedPreferences uses AES-256-GCM authenticated encryption for all
    // values (and AES-256-SIV for keys), backed by an Android Keystore key.  Any
    // modification of the on-disk file is detected via the GCM authentication tag and
    // causes decryption to fail — matching the tamper-detection already in place for
    // TOFU data and the database key (both already using EncryptedSharedPreferences).
    //
    // Migration note: the DataStore file (systemsgo_settings.preferences_pb) is a
    // different on-disk format from SharedPreferences XML.  On first launch after this
    // change all settings reset to defaults — an accepted one-time UX trade-off.
    private val prefs: SharedPreferences by lazy {
        // HIGH-R2 FIX: Delete the stale plain-text DataStore file left behind by the
        // pre-CRIT-5 build.  The file is no longer read (EncryptedSharedPreferences is
        // now the sole source of truth), but it remains on disk and can expose settings
        // — including the encrypted PIN ciphertext — to any process on a rooted device
        // and to Android Backup (prior to the backup_rules.xml exclusion being added).
        // Deletion is best-effort: if it fails the app continues normally, since the
        // old file is harmless once nothing reads from it.
        try {
            val dsFile = java.io.File(context.filesDir, "datastore/systemsgo_settings.preferences_pb")
            if (dsFile.exists()) dsFile.delete()
        } catch (e: Exception) { android.util.Log.d("AppSettingsRepository", "non-fatal cleanup/best-effort exception ignored: ${e.message}") }
        context.openEncryptedPrefs("systemsgo_settings")
    }

    // Key constants (match the old DataStore key strings exactly for forward-compat if
    // the DataStore file is still present — the two stores are different files so there
    // is no conflict, but consistent naming aids debugging).
    private object Keys {
        const val IS_DARK_MODE            = "is_dark_mode"
        const val LANGUAGE                = "language"
        const val THEME_VARIANT           = "theme_variant"
        const val CURSOR_STYLE            = "cursor_style"
        const val CURSOR_SIZE             = "cursor_size"
        const val SHOW_CURSOR_ON_TOUCH    = "show_cursor_on_touch"
        const val TOUCHPAD_SENSITIVITY    = "touchpad_sensitivity"
        const val SCROLL_SENSITIVITY      = "scroll_sensitivity"
        const val HAS_SHOWN_FIRST_LAUNCH  = "has_shown_first_launch"
        const val HAPTIC_FEEDBACK         = "haptic_feedback"
        const val KEEP_SCREEN_ON          = "keep_screen_on"
        const val SHOW_FPS_COUNTER        = "show_fps_counter"
        // TOOLBOX FEATURE (Stage 7)
        const val SHOW_LATENCY_COUNTER    = "show_latency_counter"
        // TOOLBOX FEATURE (Stage 7 follow-up)
        const val COUNTER_POS_X_FRACTION  = "counter_pos_x_fraction"
        const val COUNTER_POS_Y_FRACTION  = "counter_pos_y_fraction"
        const val COUNTER_SCALE           = "counter_scale"
        const val DEFAULT_RESOLUTION      = "default_resolution"
        const val COLOR_DEPTH             = "color_depth"
        const val PERFORMANCE_LEVEL       = "performance_level"
        const val UDP_TRANSPORT_ENABLED   = "udp_transport_enabled"
        const val SMART_SIZING_ENABLED    = "smart_sizing_enabled"
        const val SESSION_TOOLBAR_VISIBLE     = "session_toolbar_visible"
        const val SESSION_EXTRA_KEYS_VISIBLE  = "session_extra_keys_visible"
        const val RUN_IN_BACKGROUND       = "run_in_background"
        const val SOUND_ENABLED           = "sound_enabled"
        const val BIOMETRIC_LOCK_ENABLED  = "biometric_lock_enabled"
        const val PIN_LOCK_ENABLED        = "pin_lock_enabled"
        const val PIN_CODE                = "pin_code"
        const val RIGHT_CLICK_LONG_PRESS  = "right_click_long_press"
        const val HAS_SHOWN_GESTURE_HINTS = "has_shown_gesture_hints"
        const val HOME_SCREEN_OPEN_COUNT  = "home_screen_open_count"
        const val BACKGROUND_POWER_SAVING = "background_power_saving"
        const val TERMINAL_FONT_SIZE      = "terminal_font_size"
        const val TERMINAL_SNIPPETS       = "terminal_snippets"
        const val AUTO_LOCK_TIMEOUT_MS    = "auto_lock_timeout_ms"
        const val NETWORK_BINDING         = "network_binding"
        // TOOLBOX FEATURE (Stage 0)
        const val TOOLBOX_QUICK_TOOL_IDS  = "toolbox_quick_tool_ids"
        const val TOOLBOX_POS_X_FRACTION  = "toolbox_pos_x_fraction"
        const val TOOLBOX_POS_Y_FRACTION  = "toolbox_pos_y_fraction"
        const val TOOLBOX_DOCK_EDGE       = "toolbox_dock_edge"
        // TOOLBOX FEATURE (Stage 5)
        const val SCREEN_FLIP_MODE        = "screen_flip_mode"
        // TOOLBOX FEATURE (Stage 6a)
        const val MOUSE_INPUT_MODE        = "mouse_input_mode"
        // DATA-SAVER FEATURE
        const val DATA_SAVER_ENABLED      = "data_saver_enabled"
    }

    private val gson = Gson()

    // FLASH-FIX: readSettings() is a plain SharedPreferences read (microseconds,
    // no coroutine/IO hop needed) — safe to call synchronously. Exposed so
    // MainViewModel can seed its initial state with the *real* persisted
    // settings instead of AppSettings() defaults, eliminating the one-frame
    // flash (e.g. dark-mode toggle briefly showing "on" before correcting to
    // "off") that showed up whenever the Activity was recreated — most
    // commonly right after a language change, which the OS recreates for.
    fun currentSettingsSnapshot(): AppSettings = readSettings()

    private fun readSettings(): AppSettings = prefs.run {
        AppSettings(
            isDarkMode              = getBoolean(Keys.IS_DARK_MODE, true),
            language                = getString(Keys.LANGUAGE, "system") ?: "system",
            themeVariant            = getString(Keys.THEME_VARIANT, "space") ?: "space",
            cursorStyle             = getString(Keys.CURSOR_STYLE, "default") ?: "default",
            cursorSize              = getInt(Keys.CURSOR_SIZE, 24),
            showCursorOnTouch       = getBoolean(Keys.SHOW_CURSOR_ON_TOUCH, true),
            touchpadSensitivity     = getFloat(Keys.TOUCHPAD_SENSITIVITY, 1.0f),
            scrollSensitivity       = getFloat(Keys.SCROLL_SENSITIVITY, 1.0f),
            hasShownFirstLaunch     = getBoolean(Keys.HAS_SHOWN_FIRST_LAUNCH, false),
            hapticFeedback          = getBoolean(Keys.HAPTIC_FEEDBACK, true),
            keepScreenOn            = getBoolean(Keys.KEEP_SCREEN_ON, true),
            showFpsCounter          = getBoolean(Keys.SHOW_FPS_COUNTER, false),
            showLatencyCounter      = getBoolean(Keys.SHOW_LATENCY_COUNTER, false),
            counterPosXFraction     = getFloat(Keys.COUNTER_POS_X_FRACTION, -1f),
            counterPosYFraction     = getFloat(Keys.COUNTER_POS_Y_FRACTION, -1f),
            counterScale            = getFloat(Keys.COUNTER_SCALE, 1.0f),
            defaultResolution       = getString(Keys.DEFAULT_RESOLUTION, "auto") ?: "auto",
            colorDepth              = getInt(Keys.COLOR_DEPTH, com.systemsgo.hex.data.model.RdpPerformance.COLOR_DEPTH_AUTO),
            performanceLevel        = getInt(Keys.PERFORMANCE_LEVEL, com.systemsgo.hex.data.model.RdpPerformance.AUTO),
            udpTransportEnabled     = getBoolean(Keys.UDP_TRANSPORT_ENABLED, false),
            smartSizingEnabled      = getBoolean(Keys.SMART_SIZING_ENABLED, false),
            sessionToolbarVisible   = getBoolean(Keys.SESSION_TOOLBAR_VISIBLE, true),
            sessionExtraKeysVisible = getBoolean(Keys.SESSION_EXTRA_KEYS_VISIBLE, true),
            runInBackground         = getBoolean(Keys.RUN_IN_BACKGROUND, true),
            soundEnabled            = getBoolean(Keys.SOUND_ENABLED, true),
            biometricLockEnabled    = getBoolean(Keys.BIOMETRIC_LOCK_ENABLED, false),
            pinLockEnabled          = getBoolean(Keys.PIN_LOCK_ENABLED, false),
            pinCode                 = getString(Keys.PIN_CODE, "") ?: "",
            autoLockTimeoutMs       = getLong(Keys.AUTO_LOCK_TIMEOUT_MS, AppSettings.AUTO_LOCK_IMMEDIATE),
            rightClickLongPress     = getBoolean(Keys.RIGHT_CLICK_LONG_PRESS, true),
            hasShownGestureHints    = getBoolean(Keys.HAS_SHOWN_GESTURE_HINTS, false),
            homeScreenOpenCount     = getInt(Keys.HOME_SCREEN_OPEN_COUNT, 0),
            backgroundPowerSaving   = getBoolean(Keys.BACKGROUND_POWER_SAVING, false),
            terminalFontSize        = getInt(Keys.TERMINAL_FONT_SIZE, AppSettings.DEFAULT_TERMINAL_FONT_SIZE),
            terminalSnippets        = readTerminalSnippets(),
            networkBinding          = getString(Keys.NETWORK_BINDING, "ANY") ?: "ANY",
            toolboxQuickToolIds     = getString(Keys.TOOLBOX_QUICK_TOOL_IDS, "") ?: "",
            toolboxPosXFraction     = getFloat(Keys.TOOLBOX_POS_X_FRACTION, 1f),
            toolboxPosYFraction     = getFloat(Keys.TOOLBOX_POS_Y_FRACTION, 0f),
            toolboxDockEdge         = getString(Keys.TOOLBOX_DOCK_EDGE, "top") ?: "top",
            screenFlipMode          = getString(Keys.SCREEN_FLIP_MODE, "normal") ?: "normal",
            mouseInputMode          = getString(Keys.MOUSE_INPUT_MODE, "touchpad") ?: "touchpad",
            dataSaverEnabled        = getBoolean(Keys.DATA_SAVER_ENABLED, false),
        )
    }

    private fun readTerminalSnippets(): List<TerminalSnippet> {
        val raw = prefs.getString(Keys.TERMINAL_SNIPPETS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TerminalSnippet>>() {}.type
            gson.fromJson<List<TerminalSnippet>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Reactive Flow via SharedPreferences listener — mirrors the DataStore Flow API.
    // distinctUntilChanged avoids redundant re-compositions for unchanged settings.
    val settingsFlow: Flow<AppSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(readSettings())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(readSettings())   // emit current state immediately on collection start
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    // ── Update helpers ────────────────────────────────────────────────────────
    // SharedPreferences.edit().apply() commits to disk asynchronously on a background
    // thread — equivalent to DataStore's suspend-based edit().  Keeping suspend
    // signatures lets all existing ViewModel call-sites remain unchanged.

    private fun put(block: SharedPreferences.Editor.() -> Unit) =
        prefs.edit().apply(block).apply()

    suspend fun updateDarkMode(v: Boolean)              = put { putBoolean(Keys.IS_DARK_MODE, v) }
    suspend fun updateLanguage(v: String)               = put { putString(Keys.LANGUAGE, v) }
    suspend fun updateThemeVariant(v: String)           = put { putString(Keys.THEME_VARIANT, v) }
    suspend fun updateCursorStyle(v: String)            = put { putString(Keys.CURSOR_STYLE, v) }
    suspend fun updateCursorSize(v: Int)                = put { putInt(Keys.CURSOR_SIZE, v) }
    suspend fun updateTouchpadSensitivity(v: Float)     = put { putFloat(Keys.TOUCHPAD_SENSITIVITY, v) }
    suspend fun updateScrollSensitivity(v: Float)       = put { putFloat(Keys.SCROLL_SENSITIVITY, v) }
    suspend fun updateHapticFeedback(v: Boolean)        = put { putBoolean(Keys.HAPTIC_FEEDBACK, v) }
    suspend fun updateKeepScreenOn(v: Boolean)          = put { putBoolean(Keys.KEEP_SCREEN_ON, v) }
    suspend fun updateShowFps(v: Boolean)               = put { putBoolean(Keys.SHOW_FPS_COUNTER, v) }
    // TOOLBOX FEATURE (Stage 7): independent from updateShowFps above.
    suspend fun updateShowLatency(v: Boolean)           = put { putBoolean(Keys.SHOW_LATENCY_COUNTER, v) }

    // TOOLBOX FEATURE (Stage 7 follow-up): persists the free-dragged
    // position and pinch-scale of the FPS/latency overlay. Position is
    // committed by the caller only once the user stops touching it
    // (debounced — see CounterOverlay.kt), not on every drag/pinch frame.
    suspend fun updateCounterPosition(xFraction: Float, yFraction: Float) = put {
        putFloat(Keys.COUNTER_POS_X_FRACTION, xFraction.coerceIn(0f, 1f))
        putFloat(Keys.COUNTER_POS_Y_FRACTION, yFraction.coerceIn(0f, 1f))
    }
    suspend fun updateCounterScale(v: Float) = put {
        // Data layer shouldn't depend on the UI-layer CounterOverlayState class,
        // so the clamp range is duplicated here as plain constants — keep in
        // sync with CounterOverlayState.MIN_SCALE/MAX_SCALE if either changes.
        putFloat(Keys.COUNTER_SCALE, v.coerceIn(0.6f, 2.2f))
    }
    suspend fun updateShowCursorOnTouch(v: Boolean)     = put { putBoolean(Keys.SHOW_CURSOR_ON_TOUCH, v) }
    suspend fun updateDefaultResolution(v: String)      = put { putString(Keys.DEFAULT_RESOLUTION, v) }
    suspend fun updateColorDepth(v: Int)                = put { putInt(Keys.COLOR_DEPTH, v) }
    suspend fun updatePerformanceLevel(v: Int)          = put { putInt(Keys.PERFORMANCE_LEVEL, v) }
    suspend fun updateUdpTransportEnabled(v: Boolean)   = put { putBoolean(Keys.UDP_TRANSPORT_ENABLED, v) }
    suspend fun updateSmartSizingEnabled(v: Boolean)    = put { putBoolean(Keys.SMART_SIZING_ENABLED, v) }
    suspend fun updateSessionToolbarVisible(v: Boolean) = put { putBoolean(Keys.SESSION_TOOLBAR_VISIBLE, v) }
    suspend fun updateSessionExtraKeysVisible(v: Boolean) = put { putBoolean(Keys.SESSION_EXTRA_KEYS_VISIBLE, v) }
    suspend fun updateRunInBackground(v: Boolean)       = put { putBoolean(Keys.RUN_IN_BACKGROUND, v) }
    suspend fun updateSoundEnabled(v: Boolean)          = put { putBoolean(Keys.SOUND_ENABLED, v) }
    suspend fun updateBiometricLock(v: Boolean)         = put { putBoolean(Keys.BIOMETRIC_LOCK_ENABLED, v) }

    suspend fun updatePinLock(enabled: Boolean, pin: String = "") {
        // NEW-BUG-2 FIX: Use isNotEmpty() instead of isNotBlank().
        // isNotBlank() returns false for whitespace-only PINs (e.g. " "), causing
        // encryptedPin to be null while PIN_LOCK_ENABLED is still written as true.
        // Result: the lock is "enabled" with no stored PIN code → permanent lockout
        // with no valid PIN that can ever open it.
        // isNotEmpty() only skips truly empty strings (the default when no PIN is
        // provided), which is the correct semantic here. Consistent with the
        // isBlank()→isEmpty() fix already applied to CryptoHelper.encrypt().
        val encryptedPin: String? = if (enabled && pin.isNotEmpty()) {
            val payload = com.systemsgo.hex.security.PinHasher.hash(pin)
            // ROOT-HARDENING FIX: dedicated AAD context so this ciphertext can
            // never be swapped for a different stored value (e.g. a credential
            // field) that happens to decrypt under the app's Keystore key.
            com.systemsgo.hex.security.CryptoHelper.encrypt(payload, "pin_code")
        } else null

        put {
            putBoolean(Keys.PIN_LOCK_ENABLED, enabled)
            when {
                encryptedPin != null -> putString(Keys.PIN_CODE, encryptedPin)
                !enabled -> {
                    // BUG 2 FIX (SECURITY): Wipe stored PIN when lock is disabled.
                    putString(Keys.PIN_CODE, "")
                }
            }
        }
    }

    // FEATURE-AUTO-LOCK: persists the selected background-timeout for the app
    // lock screen (see AppSettings.autoLockTimeoutMs for the sentinel values).
    suspend fun updateAutoLockTimeout(v: Long) = put { putLong(Keys.AUTO_LOCK_TIMEOUT_MS, v) }

    suspend fun updateRightClickLongPress(v: Boolean)   = put { putBoolean(Keys.RIGHT_CLICK_LONG_PRESS, v) }
    suspend fun markGestureHintsShown()                 = put { putBoolean(Keys.HAS_SHOWN_GESTURE_HINTS, true) }
    // UI-POLISH: increments on every HomeScreen composition entry, capped at a small
    // number — used only to decide whether to show the full text swipe-hint banner.
    suspend fun markHomeScreenOpened(currentCount: Int) = put {
        putInt(Keys.HOME_SCREEN_OPEN_COUNT, (currentCount + 1).coerceAtMost(99))
    }
    suspend fun markFirstLaunchShown()                  = put { putBoolean(Keys.HAS_SHOWN_FIRST_LAUNCH, true) }
    suspend fun updateBackgroundPowerSaving(v: Boolean) = put { putBoolean(Keys.BACKGROUND_POWER_SAVING, v) }

    // TOOLBOX FEATURE (Stage 0): persists Quick Bar contents/order and the
    // floating container's last position/dock edge.
    suspend fun updateToolboxQuickToolIds(ids: List<String>) = put {
        putString(Keys.TOOLBOX_QUICK_TOOL_IDS, ids.joinToString(","))
    }
    suspend fun updateToolboxPosition(xFraction: Float, yFraction: Float) = put {
        putFloat(Keys.TOOLBOX_POS_X_FRACTION, xFraction.coerceIn(0f, 1f))
        putFloat(Keys.TOOLBOX_POS_Y_FRACTION, yFraction.coerceIn(0f, 1f))
    }
    suspend fun updateToolboxDockEdge(edge: String) = put { putString(Keys.TOOLBOX_DOCK_EDGE, edge) }

    // TOOLBOX FEATURE (Stage 5): persists the chosen ScreenFlipMode (by its
    // toSetting() string — see AppSettings.screenFlipMode doc comment above
    // for why this is global and auto-applied to future sessions).
    suspend fun updateScreenFlipMode(v: String) = put { putString(Keys.SCREEN_FLIP_MODE, v) }

    // TOOLBOX FEATURE (Stage 6a): persists the chosen MouseInputMode (by its
    // toSetting() string — see AppSettings.mouseInputMode doc comment above).
    suspend fun updateMouseInputMode(v: String) = put { putString(Keys.MOUSE_INPUT_MODE, v) }

    // VPN-AWARE-CONNECTIVITY: persists the chosen NetworkBindingPreference
    // (by enum name — see AppSettings.networkBinding doc comment above).
    suspend fun updateNetworkBinding(v: String) = put { putString(Keys.NETWORK_BINDING, v) }

    // DATA-SAVER FEATURE: persists the global data saver override — see
    // AppSettings.dataSaverEnabled's doc comment above.
    suspend fun updateDataSaverEnabled(v: Boolean) = put { putBoolean(Keys.DATA_SAVER_ENABLED, v) }

    /** Synchronous read for call sites (e.g. a foreground [android.app.Service]'s
     * onStartCommand) that cannot suspend to collect [settingsFlow]. */
    fun isBackgroundPowerSavingEnabled(): Boolean =
        prefs.getBoolean(Keys.BACKGROUND_POWER_SAVING, false)

    // FEATURE-TERM-FONT: clamp defensively here (not just in the UI) so a bad
    // value can never get persisted, regardless of which call site writes it.
    suspend fun updateTerminalFontSize(v: Int) = put {
        putInt(Keys.TERMINAL_FONT_SIZE, v.coerceIn(AppSettings.MIN_TERMINAL_FONT_SIZE, AppSettings.MAX_TERMINAL_FONT_SIZE))
    }

    // FEATURE-TERM-SNIPPETS ────────────────────────────────────────────────────
    // Read-modify-write helpers built on top of readTerminalSnippets()/put{} so
    // every call site (Add dialog, delete icon) mutates the single persisted
    // list rather than juggling its own copy that could race with another write.

    suspend fun addTerminalSnippet(label: String, command: String) {
        val trimmedLabel = label.trim()
        val trimmedCommand = command.trim()
        if (trimmedLabel.isEmpty() || trimmedCommand.isEmpty()) return
        val updated = readTerminalSnippets() + TerminalSnippet(label = trimmedLabel, command = trimmedCommand)
        put { putString(Keys.TERMINAL_SNIPPETS, gson.toJson(updated)) }
    }

    suspend fun removeTerminalSnippet(id: String) {
        val updated = readTerminalSnippets().filterNot { it.id == id }
        put { putString(Keys.TERMINAL_SNIPPETS, gson.toJson(updated)) }
    }

    // ── USB-REDIRECT FEATURE (Part 1/3) ─────────────────────────────────────
    // Deliberately its own small settings object/Flow rather than folding
    // into AppSettings above: it has its own read-modify-write shape for
    // approvedDeviceKeys (a Set<String>, same "gson-serialized JSON blob in
    // one pref key" trick as readTerminalSnippets()) and is consumed by a
    // narrower set of call sites (UsbRedirectionManager,
    // UsbRedirectionSettingsViewModel) — adding five more fields to the
    // already-large top-level AppSettings would force every one of its many
    // unrelated collectors to recompose on a USB-only settings change.

    private object UsbKeys {
        const val ENABLED = "usb_redirection_enabled"
        const val AUTO_REDIRECT_NEW = "usb_redirection_auto_redirect_new"
        const val ASK_BEFORE = "usb_redirection_ask_before"
        const val RECONNECT_AUTOMATICALLY = "usb_redirection_reconnect_automatically"
        const val DEBUG_LOGGING = "usb_redirection_debug_logging"
        const val APPROVED_DEVICE_KEYS = "usb_redirection_approved_device_keys"
    }

    private fun readUsbRedirectionSettings(): com.systemsgo.hex.usb.UsbRedirectionSettings = prefs.run {
        com.systemsgo.hex.usb.UsbRedirectionSettings(
            enabled = getBoolean(UsbKeys.ENABLED, false),
            autoRedirectNewDevices = getBoolean(UsbKeys.AUTO_REDIRECT_NEW, false),
            askBeforeRedirecting = getBoolean(UsbKeys.ASK_BEFORE, true),
            reconnectAutomatically = getBoolean(UsbKeys.RECONNECT_AUTOMATICALLY, true),
            debugLogging = getBoolean(UsbKeys.DEBUG_LOGGING, false),
            approvedDeviceKeys = readApprovedDeviceKeys(),
        )
    }

    private fun readApprovedDeviceKeys(): Set<String> {
        val raw = prefs.getString(UsbKeys.APPROVED_DEVICE_KEYS, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(raw, type) ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun currentUsbRedirectionSettingsSnapshot(): com.systemsgo.hex.usb.UsbRedirectionSettings = readUsbRedirectionSettings()

    val usbRedirectionSettingsFlow: Flow<com.systemsgo.hex.usb.UsbRedirectionSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("usb_redirection_")) trySend(readUsbRedirectionSettings())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(readUsbRedirectionSettings())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    suspend fun updateUsbRedirectionEnabled(v: Boolean) = put { putBoolean(UsbKeys.ENABLED, v) }
    suspend fun updateUsbAutoRedirectNewDevices(v: Boolean) = put { putBoolean(UsbKeys.AUTO_REDIRECT_NEW, v) }
    suspend fun updateUsbAskBeforeRedirecting(v: Boolean) = put { putBoolean(UsbKeys.ASK_BEFORE, v) }
    suspend fun updateUsbReconnectAutomatically(v: Boolean) = put { putBoolean(UsbKeys.RECONNECT_AUTOMATICALLY, v) }
    suspend fun updateUsbDebugLogging(v: Boolean) = put { putBoolean(UsbKeys.DEBUG_LOGGING, v) }
    suspend fun updateUsbApprovedDevices(keys: Set<String>) = put { putString(UsbKeys.APPROVED_DEVICE_KEYS, gson.toJson(keys)) }
}
