package com.systemsgo.hex.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.util.Rational
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.draw.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import com.systemsgo.hex.R
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.AppSettingsRepository
import com.systemsgo.hex.data.repository.AppSettings
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.remote.*
import com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter
import com.systemsgo.hex.ssh.protocol.SshClient
import com.systemsgo.hex.ssh.protocol.SshInteractivePrompt
import com.systemsgo.hex.ssh.protocol.SshKeyMap
import com.systemsgo.hex.ui.components.ButtonVariant
import com.systemsgo.hex.ui.components.hardwareKeyboardInput
import com.systemsgo.hex.ui.components.LocalSoundManager
import com.systemsgo.hex.ui.components.SpaceButton
import com.systemsgo.hex.ui.screens.terminal.TerminalScreen
import com.systemsgo.hex.audio.SoundManager
import com.systemsgo.hex.ui.theme.*
import com.systemsgo.hex.util.normalizeDigits
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import androidx.compose.runtime.CompositionLocalProvider

// ─────────────────────────────────────────────────────────────────────────────
// Activity
// ─────────────────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class RdpSessionActivity : AppCompatActivity() {

    private val viewModel: RdpSessionViewModel by viewModels()

    // CRASH BUG-C FIX: LocalSoundManager was never provided in RdpSessionActivity's
    // composition tree (unlike MainActivity which wraps in CompositionLocalProvider).
    // Any Composable calling LocalSoundManager.current got null; future code without
    // null-safe ?. would crash. Injecting here and providing below solves this.
    @Inject lateinit var soundManager: SoundManager

    // EXTERNAL-DISPLAY FEATURE: detects Samsung DeX / Android Desktop Mode and
    // any connected secondary display (HDMI-USB-C dock, wireless display,
    // etc.) so the toolbar can offer "move session to display". See
    // com.systemsgo.hex.display.ExternalDisplayManager for details.
    @Inject lateinit var externalDisplayManager: com.systemsgo.hex.display.ExternalDisplayManager

    // "How to use the session toolbar" spotlight tour — shown once, the
    // first time a session actually connects, pointing at the floating
    // toolbox. See com.systemsgo.hex.ui.coachmark.CoachMark.kt.
    @Inject lateinit var coachMarkPreferences: com.systemsgo.hex.data.repository.CoachMarkPreferences

    // EXTERNAL-DISPLAY FEATURE: the live Presentation window currently
    // showing the session on an external display, or null if the session is
    // only shown on this (phone/tablet) screen. Not Compose state on purpose —
    // it's imperative Android window plumbing, not something a recomposition
    // should ever recreate; sessionOnExternalDisplayId (below) is the
    // Compose-visible mirror of "is one currently active".
    private var externalPresentation: com.systemsgo.hex.display.RdpPresentation? = null

    // EXTERNAL-DISPLAY FEATURE: id of the display currently showing the
    // session via [externalPresentation], or null. Read from Compose so the
    // phone screen can swap its own canvas for a lightweight "Session moved
    // to <display>" placeholder instead of wastefully rendering the same
    // frames twice.
    private val sessionOnExternalDisplayId = mutableStateOf<Int?>(null)

    // PIP FEATURE: whether this Activity is currently shown as a floating
    // Picture-in-Picture window. Read from Compose (see setContent below) to
    // hide interactive chrome (toolbar / extra-keys / dialogs) while in that
    // mode — only the remote framebuffer should be visible in the small
    // window. Backed by a Compose State so RdpSessionScreen recomposes the
    // moment onPictureInPictureModeChanged fires below.
    private val isInPipMode = mutableStateOf(false)

    // PIP FEATURE (completeness fix): PiP is only meaningful if the device/OS
    // combo actually supports it — some OEMs disable it via device policy or
    // low-RAM configs even above minSdk. Gates the toolbar button so it isn't
    // shown as a dead affordance; enterPipMode()/armPipAutoEnter() already had
    // a try/catch for the runtime failure case, this additionally avoids
    // offering the button at all when it's known ahead of time to be a no-op.
    private val isPipSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    // PIP FEATURE (completeness fix): while shrunk into the floating PiP
    // window, none of this Activity's own UI is reachable — Android routes
    // all touches on that tiny window to the system, not to our Compose tree.
    // Without a RemoteAction, a user who entered PiP has no way to end the
    // session short of expanding back to full-screen first. This receiver
    // backs a "Disconnect" action rendered by the system directly on top of
    // the PiP window (see buildPipParams()), giving PiP full functional
    // parity with the in-app toolbar's disconnect button.
    private val pipDisconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PIP_DISCONNECT) {
                viewModel.disconnect()
                RdpSessionService.stop(this@RdpSessionActivity)
                finish()
            }
        }
    }

    companion object {
        private const val ACTION_PIP_DISCONNECT = "com.systemsgo.hex.PIP_DISCONNECT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // BUGFIX #5: FLAG_SECURE was blocking screenshots and screen recording of the
        // remote desktop session. Removed at the user's request so the session view
        // can be captured/recorded like the rest of the app.

        // BUG 4 FIX: POST_NOTIFICATIONS must be requested at runtime on Android 13+
        // (API 33+). Without this, the foreground service notification is silently
        // suppressed and may throw SecurityException on some devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        // MIC-REDIRECT FEATURE: RECORD_AUDIO is requested here unconditionally
        // (same fire-and-forget pattern as POST_NOTIFICATIONS above) rather
        // than only for profiles with enableMicRedirect=true, because the
        // profile itself is loaded asynchronously by the ViewModel and isn't
        // available yet at this point in onCreate. This is harmless for
        // profiles that never enable the toggle: systemsgo_jni.c only sets
        // FreeRDP_AudioCapture (and therefore only loads the "audin" channel)
        // when that per-profile flag is on, regardless of whether this
        // permission was granted.
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 0)

        // WEBCAM-REDIRECT FEATURE: CAMERA requested here for the same reason
        // and with the same fire-and-forget pattern as RECORD_AUDIO just
        // above — the profile isn't loaded yet at this point in onCreate, so
        // this is requested unconditionally. Harmless for profiles that
        // never enable the toggle: systemsgo_jni.c only registers the
        // "rdpecam" dynamic channel when enableWebcamRedirect is on,
        // regardless of whether this permission was granted.
        requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 0)

        // SMARTCARD-REDIRECT FEATURE: same fire-and-forget reasoning as
        // RECORD_AUDIO/CAMERA above (the profile isn't loaded yet here), but
        // shaped differently because USB device access isn't a manifest
        // runtime permission — there's nothing to request unless a matching
        // (CCID, interface class 0x0B) device is actually plugged in right
        // now. ensureShimInitialized() is cheap (just loads a small .so) and
        // always safe to call. discoverReader() only prompts Android's "Allow
        // app to access this USB device?" dialog if a CCID reader happens to
        // be attached at this exact moment — for the overwhelming majority of
        // sessions (no reader plugged in) it's a silent no-op. It does mean a
        // reader plugged in for an unrelated profile with
        // enableSmartcardRedirect=false could still trigger that one dialog;
        // accepted as a reasonable tradeoff for not needing to wait on the
        // async profile load here — same tradeoff CAMERA/RECORD_AUDIO above
        // already make (requested regardless of the corresponding toggle).
        // If nothing ever calls SCard* on it, an opened reader is harmless.
        com.systemsgo.hex.smartcard.PcscUsbBridge.ensureShimInitialized()
        com.systemsgo.hex.smartcard.PcscUsbBridge.discoverReader(this)
        // NFC-READER FEATURE: same fire-and-forget reasoning as the USB
        // discoverReader() call immediately above — the profile isn't
        // loaded yet here, and enableNfcReaderMode() is a no-op on any
        // device with no NFC radio. Actual enable/disable is paired with
        // onResume/onPause below (NFC reader mode is only deliverable to
        // the foreground Activity, unlike the USB path which stays valid
        // across the whole Activity lifetime once permission is granted).

        val profileId = intent.getStringExtra("profile_id") ?: run { finish(); return }

        // HOME-SCREEN-SHORTCUTS FEATURE: pinned shortcuts (see util/ShortcutHelper.kt)
        // launch this Activity directly from the Launcher, bypassing MainActivity's
        // own App Lock screen entirely. When this extra is set, the composition
        // below shows the same AppLockScreen before it will proceed to connect —
        // see the lockRequired/isUnlocked state just inside setContent().
        val fromShortcut = intent.getBooleanExtra("from_shortcut", false)

        // Feature-05: if this Activity is being brought back to the front via
        // FLAG_ACTIVITY_REORDER_TO_FRONT with a close_tab signal, just disconnect.
        if (intent.getBooleanExtra("close_tab", false)) {
            handleCloseTabIntent(intent)
            finish()
            return
        }

        val metrics     = resources.displayMetrics
        val deviceWidth  = metrics.widthPixels
        val deviceHeight = metrics.heightPixels

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val state    by viewModel.state.collectAsStateWithLifecycle()

            // HOME-SCREEN-SHORTCUTS FEATURE: only gate on App Lock when this
            // launch actually came from a pinned shortcut — a normal in-app
            // "Connect" tap already passed through MainActivity's own lock
            // screen, so re-prompting here would be a pure regression for
            // that (far more common) path. settings is seeded synchronously
            // with the real persisted values (see RdpSessionViewModel.settings
            // above), so this is correct from the very first composition —
            // no loading-state flicker to account for, unlike MainActivity.
            val lockRequired = fromShortcut && (settings.biometricLockEnabled || settings.pinLockEnabled)
            var isUnlocked by remember { mutableStateOf(false) }
            LaunchedEffect(lockRequired) {
                if (!lockRequired) isUnlocked = true
            }

            // CRASH BUG-A FIX: loadAndConnect() was called *after* setContent() returned,
            // which starts coroutines that push state updates (_state emissions) before
            // Compose has registered its first collector. On fast devices the CONNECTED
            // state arrived before the first recomposition, causing the UI to miss it
            // entirely and display a blank/frozen screen. Moving the call into a
            // LaunchedEffect guarantees Compose is set up and collecting before the
            // connection coroutine begins.
            // HOME-SCREEN-SHORTCUTS FEATURE: keyed on isUnlocked (was Unit) so that,
            // for a shortcut launch behind App Lock, connecting is deferred until
            // AppLockScreen below calls onUnlocked. For every other launch (the
            // overwhelming majority) isUnlocked flips to true on the very first
            // composition via the LaunchedEffect above, so this fires immediately
            // exactly as it always did.
            LaunchedEffect(isUnlocked) {
                if (!isUnlocked) return@LaunchedEffect
                if (profileId == "__quick__") {
                    val token = intent.getStringExtra("quick_token") ?: ""
                    val creds = com.systemsgo.hex.security.QuickConnectCache.take(token)
                    // HIGH-R3 FIX: Extract ALL fields from creds BEFORE calling clear().
                    // The previous code read host/port/username inside the loadAndConnectQuick()
                    // call after clear() had already been invoked. clear() currently zeros only
                    // the password CharArray, so host/port/username happened to be safe — but
                    // this is fragile: if QuickConnectParams.clear() is ever extended to null/zero
                    // other fields the connection silently uses empty values with no error or log.
                    // Fix: snapshot every field atomically first, then clear the credential object.
                    val host     = creds?.host     ?: ""
                    val port     = creds?.port     ?: 3389
                    val username = creds?.username ?: ""
                    val quickPwd = creds?.let { String(it.password) } ?: ""
                    creds?.clear()   // zero password CharArray — all fields already captured above
                    viewModel.loadAndConnectQuick(
                        host         = host,
                        port         = port,
                        username     = username,
                        password     = quickPwd,
                        deviceWidth  = deviceWidth,
                        deviceHeight = deviceHeight,
                    )
                } else {
                    viewModel.loadAndConnect(profileId, deviceWidth, deviceHeight)
                }
            }

            // ✅ keepScreenOn يُطبَّق بشكل حي من الإعداد
            LaunchedEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            LaunchedEffect(state, settings.runInBackground) {
                val profileName = (state as? SessionUiState.Connected)?.profile?.name
                if (state is SessionUiState.Connected && settings.runInBackground) {
                    RdpSessionService.start(this@RdpSessionActivity, profileName ?: "RDP")
                } else if (state is SessionUiState.Disconnected || state is SessionUiState.Error) {
                    RdpSessionService.stop(this@RdpSessionActivity)
                }
            }

            // PIP FEATURE: (re)arm auto-enter-PiP params on Android 12+ once
            // connected, and again if the remote resolution changes (affects
            // the PiP window's aspect ratio). See armPipAutoEnter() for why
            // this must happen ahead of time rather than only inside
            // enterPipMode()/onUserLeaveHint.
            val resolution by viewModel.resolution.collectAsStateWithLifecycle()
            LaunchedEffect(state is SessionUiState.Connected, resolution) {
                if (state is SessionUiState.Connected) {
                    armPipAutoEnter()
                }
            }

            // THEME-STATIC-FIX: this used to hardcode darkTheme = true, so the entire
            // session UI (toolbar, connecting/error/disconnected overlays, everything
            // in RdpSessionScreen) never respected the user's light-mode preference —
            // it was permanently stuck in dark mode regardless of the Settings toggle.
            // That workaround existed to dodge a flicker caused by `settings` starting
            // from AppSettings() defaults before the real value loaded from disk. Now
            // that RdpSessionViewModel.settings is seeded synchronously with the real
            // persisted settings (see its definition above), that flicker is gone and
            // darkTheme can safely follow settings.isDarkMode like everywhere else.
            SystemsGoTheme(darkTheme = settings.isDarkMode, themeVariant = settings.themeVariant) {
                // CRASH BUG-C FIX: CompositionLocalProvider was missing in RdpSessionActivity
                // (present in MainActivity but not here). Every Composable calling
                // LocalSoundManager.current was receiving null; any future caller without
                // null-safe ?. would crash. The soundManager field is @Inject-ed above.
                CompositionLocalProvider(LocalSoundManager provides soundManager) {
                    val inPip by isInPipMode
                    // EXTERNAL-DISPLAY FEATURE
                    val externalDisplays by externalDisplayManager.externalDisplays.collectAsStateWithLifecycle()
                    val movedToDisplayId by sessionOnExternalDisplayId
                    Box(Modifier.fillMaxSize()) {
                        RdpSessionScreen(
                            viewModel = viewModel,
                            onClose   = {
                                RdpSessionService.stop(this@RdpSessionActivity)
                                finish()
                            },
                            isInPip   = inPip,
                            pipSupported = isPipSupported,
                            onEnterPip = { enterPipMode() },
                            externalDisplays = externalDisplays,
                            sessionOnExternalDisplayId = movedToDisplayId,
                            onMoveToExternalDisplay = { info ->
                                externalDisplayManager.findDisplay(info.displayId)?.let {
                                    moveSessionToExternalDisplay(it)
                                }
                            },
                            onBringSessionBack = { bringSessionBackToPhone() },
                            shouldShowToolboxSpotlight = {
                                !coachMarkPreferences.hasSeenTour(
                                    com.systemsgo.hex.data.repository.CoachMarkTourIds.RDP_SESSION_TOOLBAR,
                                )
                            },
                            onToolboxSpotlightFinished = {
                                coachMarkPreferences.markTourSeen(
                                    com.systemsgo.hex.data.repository.CoachMarkTourIds.RDP_SESSION_TOOLBAR,
                                )
                            },
                        )

                        // HOME-SCREEN-SHORTCUTS FEATURE: drawn on top of the session
                        // screen (which is already busy connecting/connected underneath)
                        // rather than replacing it, same "opaque overlay" approach
                        // MainActivity uses for its own AppLockScreen — nothing sensitive
                        // is visible underneath since AppLockScreen's StarfieldBackground
                        // fully paints over it, and the connect LaunchedEffect above
                        // hasn't even started yet at this point (isUnlocked is still
                        // false), so there's no live session to hide in the first place.
                        AnimatedVisibility(
                            visible  = !isUnlocked,
                            enter    = fadeIn(),
                            exit     = fadeOut(tween(300)),
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
        // CRASH BUG-A FIX: loadAndConnect() calls removed from here; moved into
        // LaunchedEffect inside setContent() above.
    }

    /** Feature-05: called when FLAG_ACTIVITY_REORDER_TO_FRONT brings us back. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("close_tab", false)) {
            handleCloseTabIntent(intent)
            finish()
        }
        // SMARTCARD-REDIRECT FEATURE: a CCID reader plugged in *while* this
        // Activity is already in front arrives here (see AndroidManifest.xml's
        // USB_DEVICE_ATTACHED <intent-filter>/<meta-data> on this Activity,
        // scoped to CCID devices via usb_device_filter.xml), rather than
        // through onCreate's one-shot discoverReader() call above, which only
        // covers a reader already plugged in *before* this Activity started.
        if (intent.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            com.systemsgo.hex.smartcard.PcscUsbBridge.ensureShimInitialized()
            com.systemsgo.hex.smartcard.PcscUsbBridge.discoverReader(this)
        }
    }

    /**
     * BUG FIX (session tab close): the previous implementation always called
     * viewModel.disconnect(), which tears down whatever session is *currently
     * loaded in this Activity instance* (tracked by viewModel.currentTabId) —
     * not necessarily the tab the user actually tapped × on. Because
     * RdpSessionActivity is launchMode="singleTop", this Activity instance can
     * be reused across different tabs, so the two could silently diverge and
     * the × button would appear to do nothing (or close the wrong session).
     *
     * Fix: read the tab_id the caller intended to close.
     *  - If it matches the tab actually loaded here, disconnect for real
     *    (tears down the live connection + foreground service + tab entry).
     *  - Otherwise this Activity has nothing live for that tab (it was never
     *    given its own connection to begin with), so just make sure it is
     *    removed from SessionTabManager directly — this guarantees the chip
     *    always disappears from the tab bar even in that edge case.
     */
    private fun handleCloseTabIntent(intent: android.content.Intent) {
        val targetTabId = intent.getStringExtra("tab_id")
        if (targetTabId == null || targetTabId == viewModel.currentTabId) {
            viewModel.disconnect()
        } else {
            viewModel.closeTab(targetTabId)
        }
    }

    // LIVE-RESIZE FIX: onConfigurationChanged (below) reliably fires for a
    // device *rotation*, but connecting/disconnecting an external monitor
    // (HDMI / USB-C DisplayPort-alt-mode) does not always change this
    // Activity's own Configuration if the app keeps rendering on the
    // internal panel — the external display is a distinct android.view.Display
    // that DisplayManager tracks independently. Registering a
    // DisplayListener catches that case too, in addition to rotation.
    private val displayManager: android.hardware.display.DisplayManager by lazy {
        getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
    }
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int)   = pushCurrentDisplayMetrics()
        override fun onDisplayRemoved(displayId: Int) {
            pushCurrentDisplayMetrics()
            // EXTERNAL-DISPLAY FEATURE: if the monitor currently showing the
            // session (via externalPresentation) was just unplugged/switched
            // off, fall back to the phone screen instead of leaking a
            // Presentation bound to a now-gone Display. The session itself
            // (remoteClient inside the ViewModel) is completely unaffected —
            // only which window is drawing it changes.
            if (sessionOnExternalDisplayId.value == displayId) {
                bringSessionBackToPhone()
            }
        }
        override fun onDisplayChanged(displayId: Int) = pushCurrentDisplayMetrics()
    }

    private fun pushCurrentDisplayMetrics() {
        val metrics = resources.displayMetrics
        viewModel.updateDisplayMetrics(metrics.widthPixels, metrics.heightPixels)
    }

    // LIFECYCLE-THROTTLE FIX: each tab is its own Activity instance, so onStart/onStop
    // accurately reflect whether this specific tab is the one currently visible to the
    // user (another tab's Activity covering this one triggers onStop here). Forward
    // that to the ViewModel so its frame-update collectors can skip the expensive
    // Bitmap draw for tabs that aren't on screen — see _isForeground for details.
    // The underlying connection is left untouched; only the drawing is throttled.
    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
        // LIVE-RESIZE FIX: (re)register on every onStart rather than once in
        // onCreate — Activities using configChanges are never re-created, so
        // onCreate only ever runs once per tab, but the listener must not
        // keep firing (and touching the ViewModel) while this specific tab's
        // Activity is stopped/backgrounded behind another tab.
        displayManager.registerDisplayListener(displayListener, null)
        // Also catch a display change that happened while this tab's
        // Activity instance was stopped (e.g. an external monitor connected
        // while the user was on a different tab).
        pushCurrentDisplayMetrics()
        // EXTERNAL-DISPLAY FEATURE: (re)start the shared detector alongside
        // the tab's own DisplayManager registration above.
        externalDisplayManager.startListening()

        // PIP FEATURE (completeness fix): armed for as long as the Activity is
        // started, mirroring the displayListener above — the receiver only
        // needs to be alive while this specific tab's Activity instance could
        // plausibly be in PiP, not for the process's entire lifetime.
        if (isPipSupported) {
            val filter = IntentFilter(ACTION_PIP_DISCONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipDisconnectReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(pipDisconnectReceiver, filter)
            }
        }
    }

    /**
     * NFC-READER FEATURE: [android.nfc.NfcAdapter.enableReaderMode] only
     * ever delivers tag-discovered callbacks to the current *foreground*
     * Activity, and Android's own guidance is to enable/disable it exactly
     * around onResume/onPause (not onStart/onStop — a backgrounded-but-
     * visible state, e.g. a system dialog on top, should stop claiming the
     * NFC radio away from whatever else might want it). This Activity had
     * no onResume/onPause overrides before this feature; both are new.
     * enableNfcReaderMode() itself is a no-op on any device with no NFC
     * radio (see NfcCcidReader.enableReaderMode's doc comment).
     */
    override fun onResume() {
        super.onResume()
        com.systemsgo.hex.smartcard.PcscUsbBridge.enableNfcReaderMode(this)
    }

    override fun onPause() {
        com.systemsgo.hex.smartcard.PcscUsbBridge.disableNfcReaderMode(this)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        viewModel.setForeground(false)
        displayManager.unregisterDisplayListener(displayListener)
        externalDisplayManager.stopListening()
        // NOTE: externalPresentation is intentionally left showing here — the
        // whole point of "move session to external display" is that the
        // monitor keeps showing the live session while the user puts the
        // phone app in the background or switches to another tab. It is only
        // ever torn down in onDestroy() below, or when the display itself
        // disconnects (see displayListener.onDisplayRemoved above).
        if (isPipSupported) {
            try {
                unregisterReceiver(pipDisconnectReceiver)
            } catch (_: IllegalArgumentException) {
                // Not registered (e.g. onStart's registerReceiver call itself
                // failed) — safe to ignore.
            }
        }
    }

    /**
     * BUG 6 FIX: configChanges="orientation|screenSize" prevents Activity recreation
     * on rotation, so onCreate (where dimensions were originally read) is never called
     * again.
     *
     * LIVE-RESIZE FIX: this used to only feed [RdpSessionViewModel.updateDisplayMetrics]
     * for a *future* reconnect's benefit. That ViewModel method now also pushes a
     * live resize to the active session (see its doc) — this callback (plus the
     * DisplayListener in onStart/onStop, which additionally covers external-monitor
     * hot-plug that doesn't always change this Activity's own Configuration) is what
     * triggers it for an in-progress rotation.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        pushCurrentDisplayMetrics()
    }

    override fun onDestroy() {
        super.onDestroy()
        // BUG-ONDESTROY FIX: The previous `if (isFinishing)` guard meant the service
        // was only stopped when the user explicitly closed the Activity. If the system
        // destroyed the Activity for any other reason (low memory, process kill, or a
        // config change not listed in android:configChanges such as uiMode/dark-mode),
        // isFinishing == false and the foreground service — along with its persistent
        // notification — would remain alive indefinitely.
        // Fix: always stop the service in onDestroy(). If the Activity is recreated
        // (config change), the LaunchedEffect in setContent() restarts the service
        // automatically once the new Activity reaches the CONNECTED state.
        RdpSessionService.stop(this)
        // EXTERNAL-DISPLAY FEATURE: only torn down here (true destruction), not
        // in onStop() — see the NOTE in onStop() above for why it must survive
        // ordinary backgrounding/tab-switching.
        externalPresentation?.dismiss()
        externalPresentation = null
        // SMARTCARD-REDIRECT FEATURE: release the USB reader (if one was
        // opened) so another app — or a later session — can claim it. If
        // this Activity restarts due to an unlisted config change, onCreate
        // re-opens it via discoverReader(); since USB permission is already
        // granted at that point, that re-open is silent (no dialog).
        com.systemsgo.hex.smartcard.PcscUsbBridge.releaseReader()
    }

    /**
     * EXTERNAL-DISPLAY FEATURE: shows the live session on [display] via a
     * [com.systemsgo.hex.display.RdpPresentation], replacing any previously
     * chosen display. Only ever called while CONNECTED (see the toolbar
     * button below) — there's nothing meaningful to show otherwise. Uses the
     * exact same [viewModel] instance already running the connection, so
     * nothing reconnects and no state is lost; see [RdpPresentation]'s doc.
     */
    private fun moveSessionToExternalDisplay(display: android.view.Display) {
        externalPresentation?.dismiss()
        externalPresentation = com.systemsgo.hex.display.RdpPresentation(this, display, viewModel).also {
            it.show()
        }
        sessionOnExternalDisplayId.value = display.displayId
    }

    /**
     * EXTERNAL-DISPLAY FEATURE: dismisses the external Presentation (if any)
     * and hands the session view back to the phone/tablet screen. Called
     * both from the user-facing "Bring back" action and automatically if the
     * external display disconnects (see displayListener.onDisplayRemoved).
     */
    private fun bringSessionBackToPhone() {
        externalPresentation?.dismiss()
        externalPresentation = null
        sessionOnExternalDisplayId.value = null
    }

    /**
     * PIP FEATURE: fired both when we enter and when we exit Picture-in-Picture
     * (e.g. the user taps the small floating window, which Android expands back
     * to full-screen automatically). We just mirror the flag into Compose state;
     * RdpSessionScreen reacts by hiding/restoring the toolbar, extra-keys bar and
     * dialogs. The underlying session/connection is never touched here — it runs
     * the whole time (see the LIFECYCLE-THROTTLE FIX note on onStart/onStop),
     * so nothing needs to be "resumed" beyond the UI chrome itself.
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode.value = isInPictureInPictureMode
    }

    /**
     * PIP FEATURE: shrinks this Activity into a floating PiP window. Only
     * called while a session is connected (see the toolbar button and
     * onUserLeaveHint below) — there's nothing useful to show in PiP otherwise.
     * Entering PiP only changes how the window is presented; it does not stop
     * or recreate the Activity (configChanges already covers the resulting
     * screenSize/screenLayout change), so the remote session keeps running
     * exactly as it would in the background with "Run in background" enabled.
     */
    private fun buildPipParams(): PictureInPictureParams {
        val (w, h) = viewModel.resolution.value
        // PiP aspect ratios must fall between 1:2.39 and 2.39:1.
        val aspect = if (w > 0 && h > 0) {
            val ratio = (w.toFloat() / h.toFloat()).coerceIn(1f / 2.39f, 2.39f)
            Rational((ratio * 10_000).toInt(), 10_000)
        } else {
            Rational(16, 9)
        }
        val builder = PictureInPictureParams.Builder().setAspectRatio(aspect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: let the system auto-enter PiP when the user leaves
            // (Home / recents / another app) while connected, on top of the
            // explicit toolbar button used (and the onUserLeaveHint fallback
            // on older versions) below.
            builder.setAutoEnterEnabled(true)
        }
        // PIP FEATURE (completeness fix): without this, the floating PiP
        // window has zero controls of its own — touches on it don't reach
        // our Compose UI at all (they're consumed by the system to
        // move/resize/expand the window). A RemoteAction is the standard way
        // PiP-supporting apps (video players, maps navigation) expose a
        // control directly on the system's PiP chrome. "Disconnect" is the
        // one action that's actually useful without expanding back to
        // full-screen first.
        val disconnectIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_PIP_DISCONNECT).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnectAction = RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.disconnect),
            getString(R.string.disconnect),
            disconnectIntent
        )
        builder.setActions(listOf(disconnectAction))
        return builder.build()
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            enterPictureInPictureMode(buildPipParams())
        } catch (_: IllegalStateException) {
            // Launcher/device doesn't support PiP right now — ignore, the
            // session just stays full-screen.
        }
    }

    /**
     * PIP FEATURE (Android 12+ auto-enter): setAutoEnterEnabled only takes
     * effect for params that are active *before* the user leaves — calling
     * enterPipMode() itself is too late for that path, since it only runs once
     * the user has already tapped the toolbar button. Re-arming these params
     * whenever the session becomes connected (see the LaunchedEffect in
     * setContent) means simply pressing Home also auto-shrinks into PiP on
     * API 31+, matching the explicit-button behavior used on older versions.
     */
    private fun armPipAutoEnter() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            setPictureInPictureParams(buildPipParams())
        } catch (_: IllegalStateException) {
            // Not allowed to set PiP params right now — safe to ignore, the
            // manual toolbar button still works once the Activity is resumed.
        }
    }

    /**
     * PIP FEATURE (pre-Android 12 fallback): setAutoEnterEnabled only exists on
     * API 31+. On older supported versions (minSdk 26), onUserLeaveHint() —
     * called right before the app is hidden by Home/recents/switching apps,
     * but not for transient overlays like our own dialogs — is the standard
     * way apps (e.g. video players) auto-enter PiP instead of being stopped.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            viewModel.state.value is SessionUiState.Connected
        ) {
            enterPipMode()
        }
    }

    // ✅ إخفاء شريط الحالة وشريط التنقل في وضع الجلسة الكاملة
    // API 30+ (Android 11+): WindowInsetsController الحديث
    // API 26-29 (Android 8-10): systemUiVisibility المتوافق مع الإصدارات القديمة
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ (API 30+)
                val controller = window.insetsController
                if (controller != null) {
                    controller.hide(
                        android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                    )
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                // Android 8.0 - 10 (API 26-29): استخدام الطريقة المتوافقة
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class RdpSessionViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repository: RdpProfileRepository,
    private val settingsRepository: com.systemsgo.hex.data.repository.AppSettingsRepository,
    private val connectionLogRepository: com.systemsgo.hex.data.repository.ConnectionLogRepository,
    private val sessionTabManager: com.systemsgo.hex.session.SessionTabManager,
    // ENTRA-ID-AUTH FEATURE: resolves a fresh Gateway bearer token right
    // before every RemoteSessionFactory.create() call below — see that
    // class's doc comment.
    private val gatewayTokenProvider: com.systemsgo.hex.auth.GatewayTokenProvider,
    // PAC-SUPPORT FEATURE: resolves a profile's effective outbound proxy
    // (PAC-or-static — see that class's doc comment for the priority) right
    // before every RemoteSessionFactory.create() call below, same shape as
    // gatewayTokenProvider just above.
    private val pacProxyResolver: com.systemsgo.hex.proxy.PacProxyResolver,
) : ViewModel() {

    private val _state       = MutableStateFlow<SessionUiState>(SessionUiState.Idle)
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val _frameBitmap = MutableStateFlow<Bitmap?>(null)
    val frameBitmap: StateFlow<Bitmap?> = _frameBitmap.asStateFlow()

    private val _latency = MutableStateFlow(0L)
    val latency: StateFlow<Long> = _latency.asStateFlow()

    // BUG-M3 FIX: separate StateFlow for the actual frame-to-frame interval (ms).
    // Previously the FPS counter used 1000/latency where latency = connection setup
    // time (constant), giving a meaningless FPS value for the entire session lifetime.
    private val _frameRateMs = MutableStateFlow(0L)
    val frameRateMs: StateFlow<Long> = _frameRateMs.asStateFlow()
    private var lastFrameTimeMs = 0L

    private val _resolution = MutableStateFlow(1280 to 720)
    val resolution: StateFlow<Pair<Int, Int>> = _resolution.asStateFlow()

    private val _terminalText = MutableStateFlow("")
    val terminalText: StateFlow<String> = _terminalText.asStateFlow()

    // MOSH-PREDICT-FEATURE: null for every non-Mosh terminal protocol (SSH,
    // IPMI-SOL, serial, telnet, rlogin) and for every non-terminal protocol —
    // only ever non-null while the active client is a MoshSessionClient. See
    // predictionOverlayCollectorJob below for how it's kept in sync as the
    // active client changes, and TerminalScreen's predictedText/predictedVisible/
    // predictedUnderlined params for how the UI consumes it without depending
    // on the mosh.protocol package directly.
    private val _predictionOverlay =
        MutableStateFlow<com.systemsgo.hex.mosh.protocol.MoshPredictionEngine.Overlay?>(null)
    val predictionOverlay: StateFlow<com.systemsgo.hex.mosh.protocol.MoshPredictionEngine.Overlay?> =
        _predictionOverlay.asStateFlow()
    // MOSH-PREDICT-FEATURE: tracks the (at most one) live collector on
    // client.predictionOverlay so it can be cancelled the moment the active
    // session stops being Mosh (client swap or disconnect) — otherwise this
    // Job would keep running under sessionScope for the lifetime of the
    // ViewModel, collecting from an abandoned client forever.
    private var predictionOverlayCollectorJob: kotlinx.coroutines.Job? = null

    // KBD-INT FIX: bridges SshClient.authPrompt / SshTunneledClient.authPrompt (a
    // real server-side keyboard-interactive question — TOTP code, PAM challenge,
    // etc.) up to the Compose UI. Without this the JSch callback had a StateFlow
    // to write to but nothing ever collected it, so the connect() thread just sat
    // blocked on authPromptQueue.take() forever with no dialog ever appearing —
    // the same "no path to the user" problem, one layer higher. Can fire during
    // SessionUiState.Connecting (auth happens before CONNECTED is reached), so the
    // UI must render this regardless of the current session state.
    private val _authPrompt = MutableStateFlow<SshInteractivePrompt?>(null)
    val authPrompt: StateFlow<SshInteractivePrompt?> = _authPrompt.asStateFlow()

    // FIX #3 (Performance): StringBuilder replaces the split+join approach that
    // previously allocated three full copies of the ~200 KB terminal buffer on
    // every incoming SSH byte (combined string, split list, joined string).
    // The buffer is protected by the Dispatchers.IO coroutine that calls it.
    private val terminalBuffer = StringBuilder()
    // Track newline count separately to avoid an O(n) scan on every chunk.
    private var terminalLineCount = 0

    // FLASH-FIX: seeded with a synchronous snapshot of the real persisted
    // settings (same fix as MainViewModel) instead of AppSettings() defaults,
    // so the very first frame of the session screen — including the theme
    // below — is already correct.
    val settings: StateFlow<com.systemsgo.hex.data.repository.AppSettings> =
        settingsRepository.settingsFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, settingsRepository.currentSettingsSnapshot()
        )

    private var remoteClient: RemoteSessionClient? = null
    // BUG-1 FIX: @Volatile added — screenBitmap is written on Dispatchers.IO inside
    // currentSessionJob and read on Main in onCleared(). Without @Volatile the JVM
    // may cache the old null value in the Main thread's register and the snapshot
    // save in onCleared() silently skips the last rendered frame.
    @Volatile private var screenBitmap: Bitmap? = null
    // FIX #5: Double-buffer display bitmaps — we alternate between A and B so
    // Compose always gets a different object reference (triggering StateFlow
    // recomposition) without allocating a new Bitmap on every 16 ms frame.
    @Volatile private var displayBitmapA: Bitmap? = null
    @Volatile private var displayBitmapB: Bitmap? = null
    // AtomicBoolean ensures the buffer-flip (read + write) is a single
    // atomic operation, preventing two concurrent frame callbacks from
    // writing to the same buffer simultaneously (BUG-05).
    private val useDisplayBitmapA = java.util.concurrent.atomic.AtomicBoolean(true)
    private var screenWidth  = 1280
    private var screenHeight = 720
    private var currentProfileId: String? = null
    @Volatile private var reconnectAttempts = 0  // BUG-N4 FIX: @Volatile prevents IO thread reading stale cache value
    // RECONNECT-GUARD FIX: tracks "has this session reached CONNECTED at least
    // once" independently of _state.value. Real FreeRDP/[MS-RDPBCGR] auto-reconnect
    // (client_auto_reconnect_ex — "Attempting reconnect (N of 20)") drives its retry
    // loop off its own persistent attempt counter, never off "is the client's
    // transient state currently X" — because that transient state is exactly what a
    // reconnect attempt itself is busy changing (Connecting) while it's still in
    // flight. Our DISCONNECTED handlers used to gate the *next* retry on
    // `_state.value is SessionUiState.Connected`, but the very first retry attempt
    // already flips _state.value to Connecting before it even runs — so if that one
    // retry failed (e.g. the network genuinely needed more than a few seconds to
    // recover), the guard read false and the whole auto-reconnect loop silently gave
    // up after a single try. This flag is set true only on a real CONNECTED event and
    // only cleared by an intentional disconnect() or a fresh loadAndConnect*() call
    // that never got established, so it stays true across every Connecting/
    // Reconnecting flicker in between — matching how FreeRDP's own counter survives
    // across its intermediate states.
    @Volatile private var hasConnectedThisSession = false
    // FIX #7: Use an explicit flag instead of the unsafe Int.MAX_VALUE sentinel
    // which risks integer overflow bugs in any arithmetic that touches the counter.
    // BUGFIX-UI-6: كانت هذه Volatile var داخلية فقط، والـ UI لا يستطيع معرفة
    // ما إذا كان الانقطاع متعمداً أم لا، فيُشغّل صوت SUCCESS حتى عند انقطاع
    // شبكة مفاجئ. تحويلها لـ property مدعومة بـ MutableStateFlow يجعلها قابلة
    // للمراقبة من الـ UI مع الحفاظ على نفس صيغة القراءة/الكتابة (intentionalDisconnect
    // = true/false) في باقي الكود دون أي تعديل إضافي؛ StateFlow.value أصلاً atomic
    // فلم تعد الحاجة لـ @Volatile.
    private val _intentionalDisconnect = MutableStateFlow(false)
    val intentionalDisconnectFlow: StateFlow<Boolean> = _intentionalDisconnect.asStateFlow()
    private var intentionalDisconnect: Boolean
        get() = _intentionalDisconnect.value
        set(value) { _intentionalDisconnect.value = value }
    private var savedDeviceWidth  = 0
    private var savedDeviceHeight = 0
    // RETRY FEATURE: loadAndConnectQuick() only ever receives plaintext
    // credentials once, via a single-use QuickConnectCache token that's
    // already consumed by the time RdpSessionActivity.onCreate calls it (see
    // that call site) — so a later retry can't just re-read the token, it's
    // gone. Caching the params here (in-memory only, same lifetime/exposure
    // as the RdpProfile.password field already held in `remoteClient` for
    // the life of the attempt — no new persistence, nothing written to disk)
    // lets retryConnect() below replay a failed Quick Connect exactly like a
    // failed saved-profile connect replays via currentProfileId. Cleared the
    // moment it's no longer needed (see retryConnect()'s CONNECTED branch and
    // the clearQuickRetryParams() calls at disconnect()/onCleared()).
    private data class QuickRetryParams(
        val host: String, val port: Int, val username: String, val password: String
    )
    private var lastQuickConnectParams: QuickRetryParams? = null
    private fun clearQuickRetryParams() { lastQuickConnectParams = null }
    // FIX #8: Track the Job for the current session so we can cancel all its
    // child coroutines before starting a new session on reconnect.
    private var currentSessionJob: kotlinx.coroutines.Job? = null
    // BUG-reconnect FIX: Track the pending delayed-reconnect Job so disconnect()
    // can cancel it immediately instead of waiting for the delay to expire.
    // Without this, calling disconnect() during "Reconnecting in Ns…" would set
    // intentionalDisconnect=true but the orphan coroutine would still call
    // loadAndConnect() once the countdown finished.
    private var reconnectJob: kotlinx.coroutines.Job? = null

    // TOOLBOX FEATURE (Stage 8): handles for the two per-client child
    // collectors (frame rendering + connection-state/reconnect handling),
    // extracted out of loadAndConnect() into attachFrameCollector() /
    // attachStateCollector() so changeSessionQuality() can re-attach them to
    // a freshly-connected replacement client after a mid-session quality
    // swap — see changeSessionQuality() doc comment for the full rationale.
    private var frameCollectorJob: kotlinx.coroutines.Job? = null
    private var stateCollectorJob: kotlinx.coroutines.Job? = null
    private val _qualityChangeInProgress = MutableStateFlow(false)
    /** TOOLBOX FEATURE (Stage 8): true while a background quality-swap connection is in flight. */
    val qualityChangeInProgress: StateFlow<Boolean> = _qualityChangeInProgress.asStateFlow()
    private val _qualityChangeResult = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    /** TOOLBOX FEATURE (Stage 8): emits true/false once a changeSessionQuality() attempt settles. */
    val qualityChangeResult = _qualityChangeResult.asSharedFlow()

    // TOOLBOX FEATURE (Stage 9): mirrors the *current* remoteClient's
    // RemoteSessionClient.clipboardSyncState (null = unsupported/not started,
    // true/false = supported + current on/off state). Kept as its own
    // ViewModel-level StateFlow (rather than exposing remoteClient's flow
    // directly) because remoteClient itself is swapped out from under the UI
    // during a live quality change (see changeSessionQuality()) — this flow
    // stays the same stable reference across that swap, same reasoning as
    // frameCollectorJob/stateCollectorJob being re-attached rather than the
    // Compose layer reaching into remoteClient directly.
    private val _clipboardSyncState = MutableStateFlow<Boolean?>(null)
    val clipboardSyncState: StateFlow<Boolean?> = _clipboardSyncState.asStateFlow()
    private var clipboardSyncCollectorJob: kotlinx.coroutines.Job? = null

    /** TOOLBOX FEATURE (Stage 9): re-attached to whichever client is current — see [clipboardSyncState] doc comment. */
    private fun attachClipboardSyncCollector(client: RemoteSessionClient): kotlinx.coroutines.Job =
        sessionScope.launch {
            client.clipboardSyncState.collect { _clipboardSyncState.value = it }
        }

    // UNTRUSTED-CERT DIALOG FEATURE: mirrors clipboardSyncState immediately
    // above — the same "own ViewModel-level flow, re-attached across a
    // remoteClient swap" pattern, since the CertificateChallenge itself is
    // created on RdpRemoteAdapter's background connect thread and needs a
    // stable path up to the Compose UI regardless of which concrete client
    // is currently active.
    private val _certificateChallenge = MutableStateFlow<com.systemsgo.hex.remote.CertificateChallenge?>(null)
    val certificateChallenge: StateFlow<com.systemsgo.hex.remote.CertificateChallenge?> = _certificateChallenge.asStateFlow()
    private var certificateChallengeCollectorJob: kotlinx.coroutines.Job? = null

    private fun attachCertificateChallengeCollector(client: RemoteSessionClient): kotlinx.coroutines.Job =
        sessionScope.launch {
            client.certificateChallenge.collect { _certificateChallenge.value = it }
        }

    /**
     * MOSH-PREDICT-FEATURE: mirrors clipboardSyncState/certificateChallenge's
     * "own ViewModel-level StateFlow, re-attached across a client swap"
     * pattern, but unlike those two this one is conditional on the client's
     * *type* rather than every RemoteSessionClient exposing the same flow —
     * predictionOverlay only exists on MoshSessionClient, so:
     *   - Mosh active: collect client.predictionOverlay into _predictionOverlay
     *     for the lifetime of this Job (cancelled by the caller on the next
     *     swap/disconnect, same as every other attach*Collector here).
     *   - anything else (SSH/IPMI-SOL/serial/telnet/rlogin, or a non-terminal
     *     protocol): reset _predictionOverlay to null and return a no-op,
     *     already-completed Job so callers can treat the return value
     *     uniformly regardless of protocol.
     */
    private fun attachPredictionOverlayCollector(client: RemoteSessionClient): kotlinx.coroutines.Job {
        return if (client is com.systemsgo.hex.mosh.protocol.MoshSessionClient) {
            sessionScope.launch {
                client.predictionOverlay.collect { _predictionOverlay.value = it }
            }
        } else {
            _predictionOverlay.value = null
            sessionScope.launch { /* no-op: not a Mosh session */ }
        }
    }

    // LIVE-CHANNEL-STATUS FEATURE: mirrors clipboardSyncState/certificateChallenge
    // immediately above — own ViewModel-level StateFlow, re-attached across a
    // remoteClient swap, so the Toolbox status indicators never reach into
    // remoteClient directly. Default (all false) already matches
    // RemoteSessionClient.channelStatus's own default, so SSH/VNC sessions
    // just never light these up — no protocol-specific branching needed here.
    private val _channelStatus = MutableStateFlow(com.systemsgo.hex.remote.RemoteChannelStatus())
    val channelStatus: StateFlow<com.systemsgo.hex.remote.RemoteChannelStatus> = _channelStatus.asStateFlow()

    private fun attachChannelStatusCollector(client: RemoteSessionClient): kotlinx.coroutines.Job =
        sessionScope.launch {
            client.channelStatus.collect { _channelStatus.value = it }
        }
    private var channelStatusCollectorJob: kotlinx.coroutines.Job? = null

    // CODEC-NEGOTIATION FEATURE (part 3): mirrors channelStatus immediately
    // above — own ViewModel-level StateFlow, re-attached across a
    // remoteClient swap, so the session diagnostics text never reaches into
    // remoteClient directly. Default (null) already matches
    // RemoteSessionClient.negotiatedCodec's own default, so SSH/VNC sessions
    // (and an RDP session still on the classic non-GFX path) just never
    // populate this — no protocol-specific branching needed here.
    private val _negotiatedCodec = MutableStateFlow<String?>(null)
    val negotiatedCodec: StateFlow<String?> = _negotiatedCodec.asStateFlow()

    private fun attachNegotiatedCodecCollector(client: RemoteSessionClient): kotlinx.coroutines.Job =
        sessionScope.launch {
            client.negotiatedCodec.collect { _negotiatedCodec.value = it }
        }
    private var negotiatedCodecCollectorJob: kotlinx.coroutines.Job? = null

    // XRDP-CAPABILITY-DETECTION FEATURE: mirrors negotiatedCodec immediately
    // above — see RemoteSessionClient.negotiatedSecurityProtocol doc.
    private val _negotiatedSecurityProtocol = MutableStateFlow<String?>(null)
    val negotiatedSecurityProtocol: StateFlow<String?> = _negotiatedSecurityProtocol.asStateFlow()

    private fun attachNegotiatedSecurityProtocolCollector(client: RemoteSessionClient): kotlinx.coroutines.Job =
        sessionScope.launch {
            client.negotiatedSecurityProtocol.collect { _negotiatedSecurityProtocol.value = it }
        }
    private var negotiatedSecurityProtocolCollectorJob: kotlinx.coroutines.Job? = null

    // REMOTEAPP-WINDOWS FEATURE: mirrors clipboardSyncState/certificateChallenge
    // immediately above — own ViewModel-level StateFlows, re-attached across a
    // remoteClient swap, so RdpSessionScreen never reaches into remoteClient
    // (or does an `as? RdpRemoteAdapter` cast of its own) directly.
    //
    // [com.systemsgo.hex.session.RemoteAppWindowManager] only exists on
    // [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter] (not on the common
    // RemoteSessionClient surface — see that class's railWindowManager doc
    // comment: this is deliberately RDP/RAIL-only, unlike e.g. `monitors`),
    // so [attachRailWindowCollector] below is a no-op Job for SSH/VNC clients.
    private val _railWindows = MutableStateFlow<List<com.systemsgo.hex.data.model.RailWindow>>(emptyList())
    val railWindows: StateFlow<List<com.systemsgo.hex.data.model.RailWindow>> = _railWindows.asStateFlow()

    private val _railDisplayMode = MutableStateFlow(com.systemsgo.hex.data.model.RemoteAppDisplayMode.SINGLE_WINDOW)
    val railDisplayMode: StateFlow<com.systemsgo.hex.data.model.RemoteAppDisplayMode> = _railDisplayMode.asStateFlow()

    private val _railActiveWindowId = MutableStateFlow<Int?>(null)
    val railActiveWindowId: StateFlow<Int?> = _railActiveWindowId.asStateFlow()

    private var railWindowCollectorJob: kotlinx.coroutines.Job? = null

    /**
     * REMOTEAPP-WINDOWS FEATURE: re-attached to whichever client is current —
     * same pattern as [attachClipboardSyncCollector]. Unlike that one,
     * [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter.railWindowManager] isn't
     * assigned until that adapter's own `connect()` actually runs (it's created
     * at the top of that suspend function, not at construction time), and this
     * collector is attached right after `remoteClient = client` — *before*
     * `client.connect()` is awaited — at every call site (loadAndConnect,
     * loadAndConnectQuick, changeSessionQuality's swap), matching where
     * attachClipboardSyncCollector/attachCertificateChallengeCollector are
     * already attached there. So briefly poll for it instead of assuming it's
     * already there; the loop exits immediately once connect() actually
     * assigns it, and gives up after ~5s (matching this app's other connect
     * timeouts) if the client never turns out to be an RdpRemoteAdapter at all
     * (SSH/VNC) or connect() fails before ever reaching that line.
     */
    private fun attachRailWindowCollector(client: RemoteSessionClient): kotlinx.coroutines.Job =
        sessionScope.launch {
            val adapter = client as? com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter ?: return@launch
            var manager = adapter.railWindowManager
            var attempts = 0
            while (manager == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                manager = adapter.railWindowManager
                attempts++
            }
            val mgr = manager ?: return@launch
            launch { mgr.windows.collect { _railWindows.value = it } }
            launch { mgr.displayMode.collect { _railDisplayMode.value = it } }
            launch { mgr.activeWindowId.collect { _railActiveWindowId.value = it } }
        }

    /** SessionTool ("remote_app_display_mode") action — see RdpSessionScreen's toolboxTools. */
    fun setRemoteAppDisplayMode(mode: com.systemsgo.hex.data.model.RemoteAppDisplayMode) {
        (remoteClient as? com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter)?.railWindowManager?.setDisplayMode(mode)
    }

    /** Called when the user taps a window's icon in the MULTI_WINDOW switcher (part 2). */
    fun activateRailWindow(windowId: Int) {
        (remoteClient as? com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter)?.railWindowManager?.activateWindow(windowId)
    }

    /** REMOTEAPP-WINDOWS FEATURE (part 3): called once a drag/resize gesture
     *  on [RemoteAppFreeformDesktop]'s title bar / resize handle ends, with
     *  the window's new on-screen rect (already clamped to the desktop
     *  bounds by that composable). Delegates straight to
     *  RemoteAppWindowManager.moveWindow, which both updates the local
     *  optimistic state and sends the WindowMove request to the server —
     *  same "no-op if this isn't an active RDP/RAIL session" shape as
     *  [activateRailWindow] just above. */
    fun moveRailWindow(windowId: Int, rect: android.graphics.Rect) {
        (remoteClient as? com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter)?.railWindowManager?.moveWindow(windowId, rect)
    }

    /** Called from CertificateTrustDialog once the user taps Connect anyway / Cancel. */
    fun respondToCertificateChallenge(decision: com.systemsgo.hex.remote.CertificateChallenge.Decision) {
        _certificateChallenge.value?.respond(decision)
        _certificateChallenge.value = null
    }

    /** TOOLBOX FEATURE (Stage 9): "مزامنة الحافظة" tap-to-toggle tool action. No-op on a client where clipboard sync isn't wired up. */
    fun setClipboardSyncEnabled(enabled: Boolean) {
        remoteClient?.setClipboardSyncEnabled(enabled)
    }

    // ── Feature-05: multi-session tab support ─────────────────────────────────
    /** Tab ID assigned by [SessionTabManager] for this Activity instance. */
    var currentTabId: String? = null
        private set

    /** Expose the live tab list so the UI can render the tab bar. */
    val sessionTabs = sessionTabManager.tabs
    val activeTabId = sessionTabManager.activeTabId

    // ── LIFECYCLE-THROTTLE FIX ──────────────────────────────────────────────
    // This ViewModel (and the underlying RemoteSessionClient it owns) keeps
    // receiving/decoding frames from the network even while its Activity/tab
    // is not visible — that part is intentional, since a background tab must
    // still track the server's real framebuffer so it's up to date the moment
    // the user switches back to it, and RdpSessionService keeps the socket
    // alive regardless of tab visibility.
    //
    // What previously was NOT gated on visibility is the *expensive* part:
    // applyFrameUpdate() allocates/locks the display Bitmap and does a
    // synchronized Canvas draw for every single incoming frame, for every
    // open tab (up to MAX_TABS), even for tabs the user cannot currently see.
    // The Activity calls [setForeground] from onStart()/onStop() so the
    // frame-update collectors below can skip that Bitmap work while
    // backgrounded and only resume drawing once the tab is visible again.
    private val _isForeground = kotlinx.coroutines.flow.MutableStateFlow(true)

    /** Called by [RdpSessionActivity].onStart()/onStop(). */
    fun setForeground(foreground: Boolean) {
        _isForeground.value = foreground
    }

    // ── Feature-06: connection history logging ────────────────────────────────
    // BUG-AA3 FIX: both fields are written from Dispatchers.IO (inside currentSessionJob
    // or the state collector) and read from a different IO coroutine. Without @Volatile,
    // a CPU with its own register cache can see a stale value — same root cause that was
    // already fixed for reconnectAttempts and intentionalDisconnect (BUG-N4).
    /** ID of the [ConnectionLog] row for the current session (null = not started yet). */
    @Volatile private var currentLogId: String? = null
    /** True once the remote session has reached CONNECTED at least once. */
    @Volatile private var sessionWasSuccessful = false

    private companion object {
        // FIX #6: Maximum terminal lines kept in memory to prevent OOM
        // during long SSH sessions (top, journalctl, etc.) on ARMv7 devices.
        const val MAX_TERMINAL_LINES = 5_000
    }

    /** The protocol of the currently loaded profile, used by the UI to decide
     *  whether to render the framebuffer canvas or the terminal screen. */
    private val _protocolType = MutableStateFlow(ProtocolType.RDP)
    val protocolType: StateFlow<ProtocolType> = _protocolType.asStateFlow()

    /**
     * Dedicated scope for everything related to the active session
     * (connection, receive loop collectors, frame decoding). Any exception
     * that escapes here is caught and surfaced as a normal "Error" state
     * instead of crashing the process — this is the final safety net behind
     * the per-frame try/catch in [loadAndConnect] (issue #3 — the app used to
     * close immediately after a connection attempt because an uncaught
     * exception from a `viewModelScope.launch` child propagates and kills
     * the app by design).
     */
    private val sessionScope = viewModelScope + CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("RdpSession", "Unhandled session error", throwable)
        // BUGFIX-UI: the previous "i18n FIX" only localized the case where
        // throwable.message was null — but that's the rare case. The common
        // case (a real exception from bVNC/JSch/FreeRDP) always HAS a message,
        // and that message is always raw English, so it was still shown
        // verbatim to Arabic users. localizeConnectionError() now handles both
        // cases: known patterns get a real translation, anything else still
        // gets a localized surrounding sentence with the raw detail kept for
        // diagnosis.
        _state.value = SessionUiState.Error(
            localizeConnectionError(appContext, throwable.message)
        )
    }

    // ── BUG-N3 FIX: TrimMemoryBus registration ───────────────────────────────
    // The bus was sending trim events (from SystemsGoApp.onTrimMemory) but no
    // ViewModel ever registered a listener, so OOM protection was completely dead.
    // We keep a named reference so we can unregister it exactly in onCleared().
    private val trimMemoryListener: (Int) -> Unit = { _ ->
        // Under memory pressure, recycle whichever display buffer is currently
        // idle (the one NOT being composed on screen). This frees ~16 MB of
        // native heap per session without disturbing the active rendered frame.
        val useA = useDisplayBitmapA.get()
        if (!useA) {
            displayBitmapA?.recycle()
            displayBitmapA = null
        } else {
            displayBitmapB?.recycle()
            displayBitmapB = null
        }
    }

    init {
        com.systemsgo.hex.TrimMemoryBus.register(trimMemoryListener)
    }

    /**
     * VPN-AWARE-CONNECTIVITY: applies the user's global network-binding
     * preference (Settings → Connection → Network) to this process before a
     * new session opens any socket. Binding is process-wide (see
     * VpnConnectivityManager.applyBinding), so this transparently covers the
     * native FreeRDP bridge, JSch (SSH), and bVNC (VNC) alike with no
     * protocol-specific code.
     *
     * @return true if the connection attempt should proceed; false if it was
     *         aborted (an [SessionUiState.Error] has already been emitted)
     *         because the requested network isn't currently available.
     */
    private suspend fun applyNetworkBindingOrAbort(): Boolean {
        val pref = com.systemsgo.hex.util.VpnConnectivityManager.NetworkBindingPreference
            .fromSettingValue(settings.value.networkBinding)
        val bound = com.systemsgo.hex.util.VpnConnectivityManager.applyBinding(appContext, pref)
        if (!bound) {
            val messageRes = when (pref) {
                com.systemsgo.hex.util.VpnConnectivityManager.NetworkBindingPreference.VPN_ONLY ->
                    R.string.error_network_binding_vpn_unavailable
                com.systemsgo.hex.util.VpnConnectivityManager.NetworkBindingPreference.WIFI_ONLY ->
                    R.string.error_network_binding_wifi_unavailable
                com.systemsgo.hex.util.VpnConnectivityManager.NetworkBindingPreference.CELLULAR_ONLY ->
                    R.string.error_network_binding_cellular_unavailable
                com.systemsgo.hex.util.VpnConnectivityManager.NetworkBindingPreference.ANY ->
                    R.string.error_network_binding_unavailable
            }
            _state.emit(SessionUiState.Error(appContext.getString(messageRes)))
        }
        return bound
    }

    /**
     * ENTRA-ID-AUTH FEATURE: same "resolve a precondition or emit
     * SessionUiState.Error and let the caller `return@launch`" shape as
     * [applyNetworkBindingOrAbort] just above. Returns the bearer token to
     * pass as RemoteSessionFactory.create(gatewayBearerToken = ...) — always
     * "" (a no-op) for a profile not using GatewayAuthMode.ENTRA_ID, so
     * every call site can call this unconditionally.
     *
     * On [GatewayTokenProvider.Result.SignInRequired] this surfaces a
     * specific error message rather than a generic connect failure, since
     * the fix is different (sign in from the profile editor's Gateway
     * section) from a normal network/credential failure. Likewise
     * [GatewayTokenProvider.Result.MissingScope] surfaces a distinct
     * message pointing at the Application ID URI field, since that fix
     * (fill in gatewayScopeUri) is different again from either of those.
     */
    private suspend fun resolveGatewayBearerTokenOrAbort(profile: RdpProfile): String? {
        return when (val result = gatewayTokenProvider.resolve(profile)) {
            is com.systemsgo.hex.auth.GatewayTokenProvider.Result.Token -> result.bearerToken
            is com.systemsgo.hex.auth.GatewayTokenProvider.Result.SignInRequired -> {
                _state.emit(SessionUiState.Error(appContext.getString(R.string.error_entra_sign_in_required)))
                null
            }
            is com.systemsgo.hex.auth.GatewayTokenProvider.Result.MissingScope -> {
                _state.emit(SessionUiState.Error(appContext.getString(R.string.error_entra_gateway_scope_missing)))
                null
            }
            is com.systemsgo.hex.auth.GatewayTokenProvider.Result.Failure -> {
                _state.emit(SessionUiState.Error(appContext.getString(R.string.error_entra_token_failed, result.message)))
                null
            }
        }
    }

    /**
     * GUACAMOLE-PROTOCOL FEATURE: same "resolve the suspend precondition,
     * abort with a [SessionUiState.Error] on failure" shape as
     * [resolveGatewayBearerTokenOrAbort] — logs in against [profile]'s
     * Guacamole server and returns the resulting session for
     * `RemoteSessionFactory.create(guacamoleSession = ...)`. Only ever
     * called for [ProtocolType.GUACAMOLE] profiles — call sites pass `null`
     * directly for every other protocol rather than calling this, since
     * (unlike the gateway token, which is legitimately absent-but-fine for
     * most RDP profiles) a null return here always means "failed, abort".
     * "Remember session": when the profile has it enabled, tries the
     * persisted token first (skipping a fresh REST login), validates it
     * with one cheap `listConnections()` call, and transparently falls back
     * to a real login if that comes back `AuthenticationFailed` — an
     * expired/revoked token is never treated as a hard error, since
     * Guacamole gives no advance warning before a token goes stale.
     */
    private suspend fun resolveGuacamoleSessionOrAbort(
        profile: RdpProfile
    ): com.systemsgo.hex.guacamole.GuacamoleSession? {
        val repo = com.systemsgo.hex.guacamole.GuacamoleRepository(
            com.systemsgo.hex.guacamole.GuacamoleServerConfig(
                baseUrl = profile.guacServerUrl.trimEnd('/'),
                acceptSelfSignedCertificate = profile.acceptSelfSignedCertificate,
            ),
            appContext = appContext,
        )
        if (profile.guacRememberSession) {
            val restored = repo.tryRestoreSession(profile.username)
            if (restored != null) {
                try {
                    repo.listConnections()
                    return restored
                } catch (_: com.systemsgo.hex.guacamole.GuacamoleApiException.AuthenticationFailed) {
                    // Expired/invalid — fall through to a real login below.
                }
            }
        }
        return try {
            repo.login(profile.username, profile.password, rememberSession = profile.guacRememberSession)
        } catch (e: com.systemsgo.hex.guacamole.GuacamoleApiException.AuthenticationFailed) {
            _state.emit(SessionUiState.Error(e.message ?: appContext.getString(R.string.error_guacamole_authentication_failed)))
            null
        } catch (e: com.systemsgo.hex.guacamole.GuacamoleApiException.Unreachable) {
            _state.emit(SessionUiState.Error(appContext.getString(R.string.error_server_unreachable)))
            null
        } catch (e: com.systemsgo.hex.guacamole.GuacamoleApiException.RequestFailed) {
            _state.emit(SessionUiState.Error(e.message ?: "Guacamole request failed (HTTP ${e.httpCode})"))
            null
        }
    }

    /**
     * PAC-SUPPORT FEATURE: resolves [profile]'s effective outbound proxy —
     * see [com.systemsgo.hex.proxy.PacProxyResolver]'s doc comment for the
     * full pacUrl-vs-static-proxy-fields priority and fetch/eval-failure
     * fallback rules. Unlike [resolveGatewayBearerTokenOrAbort] this never
     * aborts the connection attempt and never emits a [SessionUiState.Error]
     * — a broken/unreachable PAC file is deliberately non-fatal (falls back
     * to the static proxy fields, or to DIRECT — see that class's doc
     * comment for why), so every call site can call this unconditionally
     * and pass the result straight into
     * `RemoteSessionFactory.create(resolvedProxy = ...)`.
     */
    private suspend fun resolvePacProxy(
        profile: RdpProfile
    ): com.systemsgo.hex.proxy.PacProxyResolver.Resolved {
        val (dialHost, dialPort) = pacProxyResolver.outboundDialTarget(profile)
        return pacProxyResolver.resolve(profile, dialHost, dialPort)
    }

    /**
     * VPN-AWARE-RECONNECT: waits [baseDelayMs] as the existing reconnect
     * back-off already did, then — only when the active binding preference
     * is VPN_ONLY — additionally waits (bounded) for the VPN to actually come
     * back up before letting the caller retry. Without this, a dropped VPN
     * tunnel would burn through the whole auto-reconnect attempt budget on
     * doomed retries while the tunnel itself is still down (e.g. Tailscale/
     * WireGuard renegotiating), instead of recovering gracefully the moment
     * it reconnects.
     */
    private suspend fun awaitReconnectWindow(baseDelayMs: Long) {
        delay(baseDelayMs)
        val pref = com.systemsgo.hex.util.VpnConnectivityManager.NetworkBindingPreference
            .fromSettingValue(settings.value.networkBinding)
        if (pref == com.systemsgo.hex.util.VpnConnectivityManager.NetworkBindingPreference.VPN_ONLY) {
            withTimeoutOrNull(20_000L) {
                com.systemsgo.hex.util.VpnConnectivityManager.vpnAvailabilityEvents(appContext)
                    .filter { it }
                    .first()
            }
        }
    }

    /**
     * @param deviceWidth/deviceHeight the device's current display size, used when
     *        the profile and the global default resolution are both "auto" (issue #5).
     */
    fun loadAndConnect(profileId: String, deviceWidth: Int, deviceHeight: Int) {
        // ✅ حفظ المعاملات لإعادة الاتصال التلقائي
        savedDeviceWidth  = deviceWidth
        savedDeviceHeight = deviceHeight

        // BUG-Y3 FIX: intentionalDisconnect is set to true by disconnect() and was only
        // cleared inside the CONNECTED state handler (i.e. after a successful connection).
        // If the user disconnects manually and then immediately reconnects, the flag stays
        // true throughout the CONNECTING phase. Any ERROR or DISCONNECTED that occurs
        // before CONNECTED is reached (e.g. wrong password on first attempt) is treated
        // as "intentional" → auto-reconnect is permanently suppressed for the new session.
        // Fix: reset the flag here at the very start of each new connection attempt so
        // the new session always begins with a clean auto-reconnect state.
        intentionalDisconnect = false

        // FIX #8: Cancel all coroutines from the previous session before
        // starting a new one. Without this, every reconnect accumulates new
        // flow-collectors alongside the still-running ones from prior sessions,
        // leading to duplicated events and unpredictable behaviour.
        currentSessionJob?.cancel()

        // FIX #7: Disconnect the old client before replacing its reference.
        // Previously the old RemoteSessionClient was simply abandoned, leaving
        // its network connections and IO threads open indefinitely (resource leak).
        remoteClient?.disconnect()
        remoteClient = null
        // KBD-INT FIX: clear any leftover prompt from a previous attempt/session —
        // _authPrompt is a plain value, not solely derived from the (now-cancelled)
        // collector, so it would otherwise keep showing a stale dialog.
        _authPrompt.value = null

        currentSessionJob = sessionScope.launch(Dispatchers.IO) { // BUG 9 FIX: was using Main dispatcher, causing ANR risk for heavy network/IO ops
            _state.emit(SessionUiState.Connecting(""))
            val profile = try {
                repository.getProfileById(profileId)
            } catch (e: SecurityException) {
                // BUG-DECRYPT FIX: CryptoHelper.decrypt() now throws SecurityException
                // when the Keystore key is lost (reinstall / backup restore). Surface a
                // meaningful message so the user knows to re-enter their credentials
                // rather than seeing a generic "Auth failed" with no explanation.
                android.util.Log.e("RdpSession", "Failed to decrypt profile credentials", e)
                _state.emit(SessionUiState.Error(
                    appContext.getString(R.string.error_credentials_lost)
                ))
                return@launch
            }
            if (profile == null) {
                // i18n FIX: was hardcoded "Profile not found" — use string resource.
                _state.emit(SessionUiState.Error(appContext.getString(R.string.error_profile_not_found)))
                return@launch
            }
            // WAKE-ON-LAN-STANDALONE FEATURE: safety net for entry points that
            // reach this Activity directly, bypassing HomeScreen's launchConnect
            // fast-path (see that fun's doc comment) — pinned home-screen
            // shortcuts (ShortcutHelper), the Quick Settings tile
            // (QuickConnectTileService), and home-screen widgets
            // (SystemsGoAppWidgetProvider/RdpProfileWidgetService) all build
            // their launch Intent from SessionLauncher.intentFor(), which always
            // targets this Activity for a WAKE_ON_LAN profile (see that fun's
            // WAKE_ON_LAN branch). Without this check, RemoteSessionFactory.create()
            // below would hit its WAKE_ON_LAN -> error(...) branch and crash the
            // whole Activity. Instead: send the Magic Packet right here (same
            // WakeOnLanManager call, same wol* profile columns MainViewModel.
            // sendWakeOnLan() uses) and surface the result through the existing
            // ErrorOverlay (repurposed to show a plain result message, not
            // necessarily a failure — same "Error state as generic terminal
            // message" pattern this file already uses for e.g.
            // error_profile_not_found above), instead of ever reaching
            // RemoteSessionFactory/the framebuffer UI for a protocol that has
            // no session to render.
            if (profile.protocolType == ProtocolType.WAKE_ON_LAN) {
                val sent = try {
                    com.systemsgo.hex.util.WakeOnLanManager.sendMagicPacket(
                        context = appContext,
                        macAddress = profile.wolMacAddress,
                        broadcastAddress = profile.wolBroadcastAddress,
                        port = profile.wolPort,
                    )
                    true
                } catch (e: Exception) {
                    android.util.Log.e("RdpSession", "Wake-on-LAN send failed", e)
                    false
                }
                _state.emit(SessionUiState.Error(
                    appContext.getString(if (sent) R.string.wol_sent else R.string.wol_error)
                ))
                return@launch
            }
            _state.emit(SessionUiState.Connecting(profile.name))
            currentProfileId = profile.id
            _protocolType.value = profile.protocolType

            // ── Feature-05: open / reuse tab ─────────────────────────────────
            // BUGFIX · duplicate & stale active sessions: openTab() now returns a
            // sealed OpenTabResult instead of unconditionally creating a tab —
            // see SessionTabManager.openTab() for the dedup logic.
            if (currentTabId == null) {
                when (val openResult = sessionTabManager.openTab(profile)) {
                    is com.systemsgo.hex.session.OpenTabResult.SessionLimitReached -> {
                        // FIX MAX_TABS: reached when the session cap is hit. In that
                        // case the Activity was still launched from the UI (HomeScreen
                        // now checks isFull first, but guard here too as a safety net
                        // for any future callers). Emit an error and abort so we do
                        // NOT allocate the 3× ~16 MB Bitmaps below, which would bypass
                        // OOM protection entirely.
                        // i18n FIX: was a hardcoded English multi-line string.
                        // error_max_sessions already exists in strings.xml with %1$d for the limit.
                        _state.emit(
                            SessionUiState.Error(
                                appContext.getString(
                                    R.string.error_max_sessions,
                                    com.systemsgo.hex.session.SessionTabManager.MAX_TABS
                                )
                            )
                        )
                        return@launch
                    }
                    is com.systemsgo.hex.session.OpenTabResult.Reused -> {
                        // BUGFIX: an identical connection (same protocol/host/port/
                        // username/domain) is already CONNECTING/RECONNECTING/CONNECTED
                        // in another tab. Do NOT start a second, redundant connection —
                        // just point the tab bar at the existing one and stop here.
                        // currentTabId is intentionally left null: this ViewModel does
                        // not own that tab's connection, so it must never update its
                        // state or close it.
                        sessionTabManager.switchTo(openResult.tabId)
                        _state.emit(
                            SessionUiState.Error(appContext.getString(R.string.error_session_already_active))
                        )
                        return@launch
                    }
                    is com.systemsgo.hex.session.OpenTabResult.Created -> {
                        currentTabId = openResult.tabId
                        sessionTabManager.updateState(
                            openResult.tabId, com.systemsgo.hex.data.model.ConnectionState.CONNECTING,
                            appContext.getString(R.string.session_tab_connecting)
                        )
                    }
                }
            } else {
                // RETRY FEATURE: currentTabId already points at this tab from
                // a previous (now-failed) attempt on this same ViewModel —
                // openTab() above is skipped entirely in that case, so this
                // tab's last-known status would otherwise stay stuck on
                // ERROR/DISCONNECTED in the tab bar even though a fresh
                // connection attempt is starting right now. Mirror the
                // Created branch's own CONNECTING update here so the tab bar
                // never disagrees with what loadAndConnect() is actually doing.
                sessionTabManager.updateState(
                    currentTabId!!, com.systemsgo.hex.data.model.ConnectionState.CONNECTING,
                    appContext.getString(R.string.session_tab_connecting)
                )
            }

            // ── Feature-06: start a new history log entry ─────────────────────
            sessionWasSuccessful = false
            val logEntry = com.systemsgo.hex.data.model.ConnectionLog(
                profileId    = if (profileId == "__quick__") null else profileId,
                profileName  = profile.name,
                host         = profile.host,
                port         = profile.port,
                protocolType = profile.protocolType
            )
            currentLogId = connectionLogRepository.start(logEntry)

            val defaultRes = settings.value.defaultResolution
            val (resW, resH) = when {
                profile.width > 0 && profile.height > 0 -> profile.width to profile.height
                defaultRes != "auto" && defaultRes.contains("x") -> {
                    val parts = defaultRes.split("x")
                    (parts.getOrNull(0)?.toIntOrNull() ?: deviceWidth) to (parts.getOrNull(1)?.toIntOrNull() ?: deviceHeight)
                }
                deviceWidth > 0 && deviceHeight > 0 -> deviceWidth to deviceHeight
                else -> 1280 to 720
            }
            screenWidth  = resW
            screenHeight = resH
            _resolution.value = screenWidth to screenHeight

            if (!profile.protocolType.isTerminal) {
                // FIX #9: Recycle the old bitmaps before allocating new ones.
                // Previously screenBitmap was silently replaced, leaving the old
                // Bitmap alive until the next GC cycle — an extra 3–7 MB leak on
                // every reconnect on ARMv7 devices.
                screenBitmap?.recycle()
                displayBitmapA?.recycle()
                displayBitmapB?.recycle()
                // BUG-M3 FIX: reset frame-rate tracker so a stale interval from the
                // previous session doesn't contaminate the new session's FPS counter.
                lastFrameTimeMs = 0L
                _frameRateMs.value = 0L
                // FIX #5: Allocate the double-buffer display bitmaps once here.
                screenBitmap = android.graphics.Bitmap.createBitmap(
                    screenWidth, screenHeight, android.graphics.Bitmap.Config.ARGB_8888
                )
                displayBitmapA = android.graphics.Bitmap.createBitmap(
                    screenWidth, screenHeight, android.graphics.Bitmap.Config.ARGB_8888
                )
                displayBitmapB = android.graphics.Bitmap.createBitmap(
                    screenWidth, screenHeight, android.graphics.Bitmap.Config.ARGB_8888
                )
                useDisplayBitmapA.set(true)
            }

            // BUG-AUTO-QUALITY FIX: resolve AUTO to a concrete, live network-based level
            // *before* deriving codec quality / performance flags, so "Auto" genuinely
            // adapts instead of behaving as a hardcoded max-quality alias
            // (codecQualityFor(AUTO) used to always return 100). Non-AUTO levels are
            // returned unchanged. Resolved once so both arguments below agree.
            // VPN-AWARE-CONNECTIVITY: apply the user's chosen network binding
            // (Settings → Connection → Network) before opening any session
            // socket. Aborts with a clear error if a specific network
            // (VPN/Wi-Fi/Cellular) was requested but isn't currently
            // available, rather than silently connecting over whichever
            // network the OS would otherwise pick.
            if (!applyNetworkBindingOrAbort()) return@launch

            val effectivePerformanceLevel = com.systemsgo.hex.util.NetworkQualityDetector.resolve(
                appContext, settings.value.performanceLevel, settings.value.dataSaverEnabled
            )
            // AUTO-COLOR-DEPTH FEATURE: same live-resolve-at-connect-time pattern
            // as effectivePerformanceLevel above — see NetworkQualityDetector.resolveColorDepth().
            val effectiveColorDepth = com.systemsgo.hex.util.NetworkQualityDetector.resolveColorDepth(
                appContext, settings.value.colorDepth, settings.value.dataSaverEnabled
            )
            val client = RemoteSessionFactory.create(
                profile,
                screenWidth,
                screenHeight,
                com.systemsgo.hex.data.model.RdpPerformance.codecQualityFor(effectivePerformanceLevel),  // FIX #4 / QUALITY-UNIFY
                colorDepth = effectiveColorDepth,
                performanceLevel = effectivePerformanceLevel,
                udpTransportEnabled = settings.value.udpTransportEnabled,  // UDP-TRANSPORT FEATURE
                appContext = appContext,   // BUG-2 FIX: was missing → compile error (appContext has no default)
                // ENTRA-ID-AUTH FEATURE: aborts with a clear
                // "sign in first" error (see resolveGatewayBearerTokenOrAbort)
                // instead of attempting — and failing opaquely at — a
                // Gateway connection with no credentials at all.
                gatewayBearerToken = resolveGatewayBearerTokenOrAbort(profile) ?: return@launch,
                // PAC-SUPPORT FEATURE: see resolvePacProxy's doc comment —
                // never aborts (a broken PAC file falls back to the static
                // proxy fields or DIRECT rather than failing the connection).
                resolvedProxy = resolvePacProxy(profile),
                // GUACAMOLE-PROTOCOL FEATURE: see resolveGuacamoleSessionOrAbort's
                // doc comment for why this is only called (and only its
                // null-means-abort contract applies) for GUACAMOLE profiles.
                guacamoleSession = if (profile.protocolType == ProtocolType.GUACAMOLE)
                    resolveGuacamoleSessionOrAbort(profile) ?: return@launch else null,
            )
            remoteClient = client
            // TOOLBOX FEATURE (Stage 9): start reflecting this client's clipboard
            // sync state immediately — see attachClipboardSyncCollector() doc comment.
            clipboardSyncCollectorJob?.cancel()
            clipboardSyncCollectorJob = attachClipboardSyncCollector(client)
            certificateChallengeCollectorJob?.cancel()
            certificateChallengeCollectorJob = attachCertificateChallengeCollector(client)
            channelStatusCollectorJob?.cancel()
            channelStatusCollectorJob = attachChannelStatusCollector(client)
            // CODEC-NEGOTIATION FEATURE (part 3): same wiring as channelStatus
            // immediately above.
            negotiatedCodecCollectorJob?.cancel()
            negotiatedCodecCollectorJob = attachNegotiatedCodecCollector(client)
            // XRDP-CAPABILITY-DETECTION FEATURE: same wiring as negotiatedCodec
            // immediately above.
            negotiatedSecurityProtocolCollectorJob?.cancel()
            negotiatedSecurityProtocolCollectorJob = attachNegotiatedSecurityProtocolCollector(client)
            // REMOTEAPP-WINDOWS FEATURE: see attachRailWindowCollector's doc
            // comment for why this is safe to attach before client.connect() runs.
            railWindowCollectorJob?.cancel()
            railWindowCollectorJob = attachRailWindowCollector(client)

            // KBD-INT FIX: forward whichever client actually talks JSch (the direct
            // SSH terminal client, or the SSH-tunnel decorator wrapping RDP/VNC) so a
            // real keyboard-interactive challenge (TOTP/PAM) from either the terminal
            // host or a jump host reaches the UI instead of blocking silently.
            launch {
                val prompts = when (client) {
                    is SshClient         -> client.authPrompt
                    is SshTunneledClient -> client.authPrompt
                    // MOSH FEATURE: same JSch-backed SSH bootstrap as SshClient,
                    // so it can hit the exact same keyboard-interactive
                    // challenge — see MoshSessionManager's InteractiveUserInfo.
                    is com.systemsgo.hex.mosh.protocol.MoshSessionClient -> client.authPrompt
                    else                 -> null
                }
                prompts?.collect { _authPrompt.value = it }
            }

            // TOOLBOX FEATURE (Stage 8): moved into attachStateCollector() so
            // changeSessionQuality() can reuse the exact same handling on a
            // replacement client after a mid-session quality swap.
            stateCollectorJob = attachStateCollector(client, profile)
            // BUG-07: Removed redundant `lastError` collector — the `combine`
            // block above (line ~306) already collects `client.error` on the
            // same SharedFlow. A second concurrent collector caused unsynchronised
            // writes to `lastError` from two coroutines and double-consumption
            // of error events. Error messages are now read directly from the
            // combine block's `lastError` destructured value.

            // MOSH-PREDICT-FEATURE: attached here, before the isTerminal branch,
            // so it runs exactly once per new active client regardless of
            // protocol. Cancel any collector left over from the previous
            // session first — otherwise switching sessions (Mosh → anything,
            // or anything → Mosh) would leak the old Job, which would keep
            // pushing stale overlay updates into _predictionOverlay forever.
            // attachPredictionOverlayCollector() itself resets _predictionOverlay
            // to null and returns a no-op Job for every non-Mosh client (which
            // includes every non-terminal RemoteSessionClient), so this single
            // call is correct whichever branch below is taken.
            predictionOverlayCollectorJob?.cancel()
            predictionOverlayCollectorJob = attachPredictionOverlayCollector(client)

            if (!profile.protocolType.isTerminal) {
                // Periodically persist a thumbnail of the current screen (issue
                // #11 — "show the last point the system was at" in the profile
                // list, blended into the card). Throttled to once every 15s.
                launch {
                    while (isActive) {
                        delay(15_000)
                        if (_state.value is SessionUiState.Connected) {
                            saveLastFrameThumbnail()
                        }
                    }
                }

                // TOOLBOX FEATURE (Stage 8): moved into attachFrameCollector() so
                // changeSessionQuality() can reuse the exact same handling on a
                // replacement client after a mid-session quality swap.
                frameCollectorJob = attachFrameCollector(client)
            } else {
                // FIX #2 (Logic): Clear terminal state from any previous SSH session.
                // Without this reset, stale output from the last session remains visible
                // until the first new chunk arrives from the new connection.
                terminalBuffer.clear()
                terminalLineCount = 0
                _terminalText.value = ""

                launch {
                    client.terminalOutput.collect { chunk ->
                        // FIX #3 (Performance): Process only the incoming chunk — O(chunk_size)
                        // — instead of splitting the entire accumulated text on every byte.
                        // The old split('\n') + joinToString() created three full copies of
                        // the ~200 KB buffer per chunk, causing GC pressure and ANR risk on
                        // commands like `dmesg` or `tail -f`.
                        val text = chunk.text
                        terminalBuffer.append(text)
                        terminalLineCount += text.count { it == '\n' }

                        // Trim oldest lines from the front when we exceed MAX_TERMINAL_LINES.
                        while (terminalLineCount > MAX_TERMINAL_LINES) {
                            val nl = terminalBuffer.indexOf('\n')
                            if (nl < 0) break
                            terminalBuffer.delete(0, nl + 1)
                            terminalLineCount--
                        }

                        // One string copy (toString) vs the previous three — ~3x fewer
                        // allocations per incoming chunk.
                        _terminalText.value = terminalBuffer.toString()
                        _latency.value = client.latencyMs
                    }
                }
            }

            val success = client.connect()
            if (!success && _state.value !is SessionUiState.Error) {
                // lastError was removed (BUG-07). The combine collector above
                // already surfaces error messages via SessionUiState.Error.
                // This path only fires if connect() returns false *before* any
                // error event is emitted, so a generic message is appropriate.
                _state.emit(SessionUiState.Error(appContext.getString(R.string.error_connect_failed_host, profile.host, profile.port)))
            }
        }
    }

    /**
     * FIX C1: Quick Connect — builds a temporary in-memory [RdpProfile] from
     * the supplied credentials and connects without saving anything to the DB.
     */
    fun loadAndConnectQuick(
        host: String,
        port: Int,
        username: String,
        password: String,
        deviceWidth: Int,
        deviceHeight: Int,
    ) {
        if (host.isBlank()) {
            _state.value = SessionUiState.Error(appContext.getString(R.string.error_quick_connect_host_required))
            return
        }
        // RETRY FEATURE: see lastQuickConnectParams' doc comment above.
        lastQuickConnectParams = QuickRetryParams(host, port, username, password)
        val tempProfile = com.systemsgo.hex.data.model.RdpProfile(
            id       = "__quick__",
            name     = host,
            host     = host,
            port     = port,
            username = username,
            password = password,
        )
        savedDeviceWidth  = deviceWidth
        savedDeviceHeight = deviceHeight

        // BUG-Z3 FIX: intentionalDisconnect is set to true by disconnect() and was not
        // being reset here, mirroring the same bug fixed in loadAndConnect() (BUG-Y3).
        // Without this reset, auto-reconnect is silently disabled for any Quick Connect
        // session that follows a manual disconnect().
        intentionalDisconnect = false
        currentSessionJob?.cancel()
        remoteClient?.disconnect()
        remoteClient = null

        currentSessionJob = sessionScope.launch(Dispatchers.IO) { // BUG 9 FIX
            _state.emit(SessionUiState.Connecting(host))
            currentProfileId = "__quick__"
            _protocolType.value = com.systemsgo.hex.data.model.ProtocolType.RDP

            if (currentTabId == null) {
                // BUGFIX · duplicate & stale active sessions: same dedup-aware
                // openTab() as loadAndConnect — see SessionTabManager.openTab().
                when (val openResult = sessionTabManager.openTab(tempProfile)) {
                    is com.systemsgo.hex.session.OpenTabResult.SessionLimitReached -> {
                        // FIX MAX_TABS: same guard as loadAndConnect — abort before
                        // allocating large Bitmaps if we're at the session cap.
                        // i18n FIX: same fix as loadAndConnect above.
                        _state.emit(
                            SessionUiState.Error(
                                appContext.getString(
                                    R.string.error_max_sessions,
                                    com.systemsgo.hex.session.SessionTabManager.MAX_TABS
                                )
                            )
                        )
                        return@launch
                    }
                    is com.systemsgo.hex.session.OpenTabResult.Reused -> {
                        // BUGFIX: identical host/port/username already
                        // connecting/connected elsewhere — reuse it instead of
                        // starting a second Quick Connect session.
                        sessionTabManager.switchTo(openResult.tabId)
                        _state.emit(
                            SessionUiState.Error(appContext.getString(R.string.error_session_already_active))
                        )
                        return@launch
                    }
                    is com.systemsgo.hex.session.OpenTabResult.Created -> {
                        currentTabId = openResult.tabId
                        sessionTabManager.updateState(
                            openResult.tabId, com.systemsgo.hex.data.model.ConnectionState.CONNECTING,
                            appContext.getString(R.string.session_tab_connecting)
                        )
                    }
                }
            } else {
                // RETRY FEATURE: see the identical else-branch in loadAndConnect() above.
                sessionTabManager.updateState(
                    currentTabId!!, com.systemsgo.hex.data.model.ConnectionState.CONNECTING,
                    appContext.getString(R.string.session_tab_connecting)
                )
            }

            screenBitmap?.recycle()
            displayBitmapA?.recycle()
            displayBitmapB?.recycle()
            // BUG-M3 FIX: reset frame-rate tracker on new quick-connect session.
            lastFrameTimeMs = 0L
            _frameRateMs.value = 0L
            val (resW, resH) = if (deviceWidth > 0 && deviceHeight > 0) deviceWidth to deviceHeight else 1280 to 720
            screenWidth  = resW
            screenHeight = resH
            _resolution.value = resW to resH
            screenBitmap   = android.graphics.Bitmap.createBitmap(resW, resH, android.graphics.Bitmap.Config.ARGB_8888)
            displayBitmapA = android.graphics.Bitmap.createBitmap(resW, resH, android.graphics.Bitmap.Config.ARGB_8888)
            displayBitmapB = android.graphics.Bitmap.createBitmap(resW, resH, android.graphics.Bitmap.Config.ARGB_8888)
            useDisplayBitmapA.set(true)

            // VPN-AWARE-CONNECTIVITY: same binding step as loadAndConnect() —
            // see applyNetworkBindingOrAbort() doc comment.
            if (!applyNetworkBindingOrAbort()) return@launch

            // BUG-AUTO-QUALITY FIX: same live resolution as the main connect path above —
            // see RemoteSessionFactory.create() call in loadAndConnect() for details.
            val effectivePerformanceLevel = com.systemsgo.hex.util.NetworkQualityDetector.resolve(
                appContext, settings.value.performanceLevel, settings.value.dataSaverEnabled
            )
            // AUTO-COLOR-DEPTH FEATURE: same live-resolve-at-connect-time pattern
            // as effectivePerformanceLevel above — see NetworkQualityDetector.resolveColorDepth().
            val effectiveColorDepth = com.systemsgo.hex.util.NetworkQualityDetector.resolveColorDepth(
                appContext, settings.value.colorDepth, settings.value.dataSaverEnabled
            )
            val client = RemoteSessionFactory.create(
                tempProfile, resW, resH,
                compressionQuality = com.systemsgo.hex.data.model.RdpPerformance.codecQualityFor(effectivePerformanceLevel),  // FIX-QC1 / QUALITY-UNIFY
                colorDepth = effectiveColorDepth,
                performanceLevel = effectivePerformanceLevel,
                udpTransportEnabled = settings.value.udpTransportEnabled,  // UDP-TRANSPORT FEATURE
                appContext = appContext,
                // ENTRA-ID-AUTH FEATURE: see resolveGatewayBearerTokenOrAbort's
                // doc comment. tempProfile.gatewayAuthMode defaults to
                // PASSWORD (Quick Connect profiles have no editor UI to set
                // it), so this resolves to "" and is a no-op for that path.
                gatewayBearerToken = resolveGatewayBearerTokenOrAbort(tempProfile) ?: return@launch,
                // PAC-SUPPORT FEATURE: see resolvePacProxy's doc comment.
                // tempProfile.pacUrl defaults to "" (Quick Connect profiles
                // have no editor UI to set it either), so this resolves to
                // the static proxy fields (also unset) — a no-op, same as
                // gatewayBearerToken just above.
                resolvedProxy = resolvePacProxy(tempProfile),
                // GUACAMOLE-PROTOCOL FEATURE: Quick Connect has no editor UI
                // for the guac* fields either, so this resolves to an abort
                // for a (currently unreachable) GUACAMOLE tempProfile, and a
                // no-op null for every real Quick Connect protocol.
                guacamoleSession = if (tempProfile.protocolType == ProtocolType.GUACAMOLE)
                    resolveGuacamoleSessionOrAbort(tempProfile) ?: return@launch else null,
            )  // BUG-B/H FIX
            remoteClient = client
            // TOOLBOX FEATURE (Stage 9): same wiring as loadAndConnect() — Quick
            // Connect profiles carry RdpProfile.enableClipboard defaults too, so
            // the clipboard_sync tool works here exactly the same way, unlike
            // connection_quality which explicitly excludes Quick Connect.
            clipboardSyncCollectorJob?.cancel()
            clipboardSyncCollectorJob = attachClipboardSyncCollector(client)
            certificateChallengeCollectorJob?.cancel()
            certificateChallengeCollectorJob = attachCertificateChallengeCollector(client)
            channelStatusCollectorJob?.cancel()
            channelStatusCollectorJob = attachChannelStatusCollector(client)
            // CODEC-NEGOTIATION FEATURE (part 3): same wiring as loadAndConnect().
            negotiatedCodecCollectorJob?.cancel()
            negotiatedCodecCollectorJob = attachNegotiatedCodecCollector(client)
            // XRDP-CAPABILITY-DETECTION FEATURE: same wiring as loadAndConnect().
            negotiatedSecurityProtocolCollectorJob?.cancel()
            negotiatedSecurityProtocolCollectorJob = attachNegotiatedSecurityProtocolCollector(client)
            // REMOTEAPP-WINDOWS FEATURE: same wiring as loadAndConnect().
            railWindowCollectorJob?.cancel()
            railWindowCollectorJob = attachRailWindowCollector(client)

            // FIX-QC2: Log Quick Connect sessions in connection history just like regular profiles.
            sessionWasSuccessful = false
            val logEntry = com.systemsgo.hex.data.model.ConnectionLog(
                profileId    = null,  // __quick__ is not a real DB row
                profileName  = host,
                host         = host,
                port         = port,
                protocolType = com.systemsgo.hex.data.model.ProtocolType.RDP
            )
            currentLogId = connectionLogRepository.start(logEntry)

            launch {
                kotlinx.coroutines.flow.combine(client.sessionState, client.error.onStart { emit("") }) { remoteState, msg ->
                    remoteState to msg.ifBlank { null }
                }.collect { (remoteState, lastError) ->
                    when (remoteState) {
                        RemoteSessionState.CONNECTED -> {
                            reconnectAttempts = 0
                            hasConnectedThisSession = true  // RECONNECT-GUARD FIX
                            intentionalDisconnect = false
                            sessionWasSuccessful = true
                            currentTabId?.let {
                                sessionTabManager.updateState(
                                    it, com.systemsgo.hex.data.model.ConnectionState.CONNECTED, ""
                                )
                            }
                            _state.emit(SessionUiState.Connected(tempProfile))
                        }
                        RemoteSessionState.AUTH_FAILED -> {
                            currentLogId?.let { logId ->
                                connectionLogRepository.finish(logId, appContext.getString(R.string.disconnect_reason_auth), sessionWasSuccessful)
                            }
                            currentLogId = null  // BUG-Z2 FIX: prevent double-finish overwriting error reason
                            currentTabId?.let {
                                sessionTabManager.updateState(
                                    it, com.systemsgo.hex.data.model.ConnectionState.ERROR, appContext.getString(R.string.session_tab_auth_failed)
                                )
                            }
                            // BUGFIX · stale active sessions: see the matching comment in
                            // loadAndConnect's AUTH_FAILED branch.
                            if (!sessionWasSuccessful) {
                                currentTabId?.let { sessionTabManager.closeTab(it) }
                                currentTabId = null
                            }
                            // BUGFIX-UI: this branch already knows the failure is an
                            // authentication failure (from the state machine itself), so
                            // we no longer guess from the raw lastError text — always show
                            // the fully localized auth message instead of occasionally
                            // leaking a raw English string from the native bridge.
                            _state.emit(SessionUiState.Error(appContext.getString(R.string.error_auth_failed_credentials)))
                        }
                        RemoteSessionState.ERROR -> {
                            // FIX-QC3: reconnect for Quick Connect, mirroring loadAndConnect logic.
                            // ALWAYS-RECONNECT FIX: no more "Auto Reconnect" toggle and no more
                            // app-decided attempt cap — a dropped/errored connection keeps
                            // retrying (with a growing, capped back-off) until the user
                            // intentionally disconnects or the server itself returns a real,
                            // definitive error (AUTH_FAILED is handled in its own branch above
                            // and never reaches here).
                            if (!intentionalDisconnect) {
                                reconnectAttempts++
                                val waitSec = (reconnectAttempts * 3).coerceAtMost(30)
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.systemsgo.hex.data.model.ConnectionState.RECONNECTING,
                                        appContext.getString(R.string.session_tab_retry)
                                    )
                                }
                                _state.emit(SessionUiState.Connecting(
                                    appContext.getString(R.string.session_connecting_countdown, waitSec),
                                    isReconnecting = true,
                                    lastErrorHint  = lastError?.takeIf { it.isNotBlank() }
                                ))
                                val savedW = savedDeviceWidth
                                val savedH = savedDeviceHeight
                                // BUG-AA2 FIX: close the current log before the reconnect job
                                // overwrites currentLogId with a new entry.
                                val errMsgForLog = lastError ?: appContext.getString(R.string.disconnect_reason_error)
                                currentLogId?.let { logId ->
                                    connectionLogRepository.finish(logId, errMsgForLog, sessionWasSuccessful)
                                }
                                currentLogId = null
                                reconnectJob = sessionScope.launch(Dispatchers.IO) {
                                    awaitReconnectWindow(waitSec * 1000L)
                                    if (!intentionalDisconnect) {
                                        loadAndConnectQuick(host, port, username, password, savedW, savedH)
                                    }
                                }
                                return@collect
                            } else {
                                val errMsg = lastError ?: appContext.getString(R.string.disconnect_reason_error)
                                currentLogId?.let { logId ->
                                    connectionLogRepository.finish(logId, errMsg, sessionWasSuccessful)
                                }
                                currentLogId = null  // BUG-Z2 FIX: prevent DISCONNECTED branch from overwriting the error reason
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.systemsgo.hex.data.model.ConnectionState.ERROR, appContext.getString(R.string.session_tab_error)
                                    )
                                }
                                // BUGFIX · stale active sessions: see the matching comment in
                                // loadAndConnect's final-ERROR branch.
                                if (!sessionWasSuccessful) {
                                    currentTabId?.let { sessionTabManager.closeTab(it) }
                                    currentTabId = null
                                }
                                // BUGFIX-UI: errMsg (raw lastError) is kept as-is for the
                                // connection-history log above (intentional — full technical
                                // detail there aids diagnosis), but the live overlay the user
                                // sees right now gets the localized version instead of a raw,
                                // possibly-English string from the native bridge.
                                _state.emit(SessionUiState.Error(localizeConnectionError(appContext, errMsg)))
                            }
                        }
                        RemoteSessionState.DISCONNECTED -> {
                            if (reconnectJob?.isActive == true) return@collect
                            // ALWAYS-RECONNECT FIX: see the matching comment in the ERROR
                            // branch above — no toggle, no attempt cap, retries indefinitely
                            // unless the user intentionally disconnected.
                            // RECONNECT-GUARD FIX: was `_state.value is SessionUiState.Connected`.
                            // That read the *current* transient UI state, but a few lines below
                            // this same branch overwrites _state.value to Connecting before the
                            // retry runs — so if that one retry attempt itself failed (a plain
                            // failed connect() surfaces as DISCONNECTED here too, see
                            // RdpRemoteAdapter's native state-code mapping, not a distinct ERROR),
                            // this guard read false on the very next pass and the whole
                            // auto-reconnect loop silently stopped after a single try. Real
                            // FreeRDP/[MS-RDPBCGR] auto-reconnect drives its retry loop off its
                            // own persistent attempt counter instead — hasConnectedThisSession is
                            // that same kind of persistent signal here (see its declaration doc).
                            if (!intentionalDisconnect && hasConnectedThisSession) {
                                reconnectAttempts++
                                // BACKOFF-CONSISTENCY FIX: was a hardcoded awaitReconnectWindow(3_000)
                                // that never grew no matter how many times this branch retried —
                                // unlike the ERROR branch's escalating waitSec just above. A network
                                // outage that outlasts a few of these fixed 3s attempts would just
                                // hammer the server every 3s forever instead of backing off. Reuse
                                // the exact same escalating formula as ERROR (3s, 6s, 9s ... capped
                                // at 30s) so both paths behave identically, matching FreeRDP's own
                                // "retry N of MAX, delaying <growing>ms" pattern.
                                val waitSec = (reconnectAttempts * 3).coerceAtMost(30)
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.systemsgo.hex.data.model.ConnectionState.RECONNECTING,
                                        appContext.getString(R.string.session_tab_retry)
                                    )
                                }
                                _state.emit(SessionUiState.Connecting(
                                    appContext.getString(R.string.session_connecting_countdown, waitSec),
                                    isReconnecting = true,
                                    lastErrorHint  = lastError?.takeIf { it.isNotBlank() }
                                ))
                                val savedW = savedDeviceWidth
                                val savedH = savedDeviceHeight
                                // LOG-DIAGNOSIS FIX: capture the real lastError instead of
                                // discarding it as null, so Connection History shows why each
                                // Quick Connect retry actually failed.
                                currentLogId?.let { logId ->
                                    val dropReason = lastError?.takeIf { it.isNotBlank() }
                                        ?: appContext.getString(R.string.disconnect_reason_dropped)
                                    connectionLogRepository.finish(logId, dropReason, sessionWasSuccessful)
                                }
                                currentLogId = null
                                reconnectJob = sessionScope.launch(Dispatchers.IO) {
                                    awaitReconnectWindow(waitSec * 1000L)
                                    if (!intentionalDisconnect) {
                                        loadAndConnectQuick(host, port, username, password, savedW, savedH)
                                    }
                                }
                                return@collect
                            } else {
                                currentLogId?.let { logId ->
                                    val dropReason = if (sessionWasSuccessful && intentionalDisconnect)
                                        null // graceful, user-initiated disconnect — no error to show
                                    else
                                        lastError?.takeIf { it.isNotBlank() }
                                            ?: appContext.getString(R.string.disconnect_reason_dropped)
                                    connectionLogRepository.finish(logId, dropReason, sessionWasSuccessful)
                                }
                                currentLogId = null  // BUG-Z2 FIX: prevent any further finish() from re-writing over a null reason
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.systemsgo.hex.data.model.ConnectionState.DISCONNECTED, ""
                                    )
                                }
                                // BUGFIX · stale active sessions: see the matching comment in
                                // loadAndConnect's final-DISCONNECTED branch.
                                if (!sessionWasSuccessful) {
                                    currentTabId?.let { sessionTabManager.closeTab(it) }
                                    currentTabId = null
                                }
                                _state.emit(SessionUiState.Disconnected)
                            }
                        }
                        else -> {}
                    }
                }
            }
            launch {
                client.frameUpdates.collect { frame ->
                    // LIFECYCLE-THROTTLE FIX: see comment on _isForeground above.
                    if (_isForeground.value) {
                        try { applyFrameUpdate(frame) } catch (e: Exception) { android.util.Log.d("RdpSessionActivity", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") }
                    }
                    _latency.value = client.latencyMs
                }
            }
            // BUG-4 FIX: Periodic thumbnail loop was present in loadAndConnect() but missing
            // here. Quick Connect sessions never persisted a card thumbnail, so the profile
            // list always showed the placeholder image even after a successful connection.
            // Note: currentProfileId == "__quick__", so saveLastFrameThumbnail() already
            // guards against writing an orphan file via its internal "__quick__" check.
            launch {
                while (isActive) {
                    delay(15_000)
                    if (_state.value is SessionUiState.Connected) {
                        saveLastFrameThumbnail()
                    }
                }
            }
            val success = client.connect()
            if (!success && _state.value !is SessionUiState.Error) {
                _state.emit(SessionUiState.Error(appContext.getString(R.string.error_connect_failed_host, host, port)))
            }
        }
    }

    /**
     * Saves a small thumbnail of the current [screenBitmap] for the active
     * profile (issue #11), used by [com.systemsgo.hex.ui.components.RdpProfileCard]
     * to show "what the system looked like last time" blended into the card.
     * Runs on a background thread and never throws.
     */
    private fun saveLastFrameThumbnail() {
        val bitmap = screenBitmap ?: return
        val profileId = currentProfileId ?: return
        // BUG-R1 FIX: The bitmap copy used to happen INSIDE the viewModelScope coroutine.
        // That coroutine is NOT cancelled by currentSessionJob?.cancel(), so when
        // loadAndConnect() ran screenBitmap?.recycle() inside the new session job, the
        // copy coroutine might still be alive — bitmap.copy() on a recycled bitmap throws
        // IllegalStateException("can't copy a recycled bitmap").
        // Fix: take the snapshot synchronously HERE (before the coroutine launch) so the
        // result is already an independent Bitmap, unaffected by any later recycle().
        val snapshot = try {
            synchronized(bitmap) {
                if (bitmap.isRecycled) return
                bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            }
        } catch (_: Exception) { return }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // BUG-AA1 FIX: guard BOTH LastFrameStore.save() and updateScreenshot()
                // behind the same "__quick__" check. The previous BUG-X2 fix only protected
                // the DB update; LastFrameStore.save() was still called unconditionally,
                // writing an orphan "__quick__.jpg" into cacheDir/last_frames/ whenever
                // disconnect() was called on a Quick Connect session.
                if (profileId != "__quick__") {
                    com.systemsgo.hex.util.LastFrameStore.save(appContext, profileId, snapshot)
                    // BUG-5 FIX: persist filename to DB so the card thumbnail survives app restarts.
                    // LastFrameStore saves the file as "$profileId.jpg" in cacheDir/last_frames/.
                    repository.updateScreenshot(profileId, "$profileId.jpg")
                }
            } finally {
                snapshot.recycle()
            }
        }
    }

    private fun applyFrameUpdate(frame: RemoteFrameUpdate) {
        if (frame.width <= 0 || frame.height <= 0) return

        // LIVE-RESIZE FIX: a fullScreen frame whose dimensions don't match our
        // current display buffers means the remote desktop was just resized —
        // either because our own resize() request (from updateDisplayMetrics)
        // was honored, or because the server clamped it to a different
        // supported resolution than the one we asked for, or (rarely) the
        // server changed its own resolution independently. Reallocate the
        // buffers to match instead of clipping the new frame into stale,
        // wrongly-sized bitmaps.
        if (frame.fullScreen && frame.x == 0 && frame.y == 0 &&
            (frame.width != screenBitmap?.width || frame.height != screenBitmap?.height)
        ) {
            screenBitmap?.recycle()
            displayBitmapA?.recycle()
            displayBitmapB?.recycle()
            screenBitmap = android.graphics.Bitmap.createBitmap(
                frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888
            )
            displayBitmapA = android.graphics.Bitmap.createBitmap(
                frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888
            )
            displayBitmapB = android.graphics.Bitmap.createBitmap(
                frame.width, frame.height, android.graphics.Bitmap.Config.ARGB_8888
            )
            useDisplayBitmapA.set(true)
            screenWidth  = frame.width
            screenHeight = frame.height
            _resolution.value = frame.width to frame.height
        }

        val bitmap = screenBitmap ?: return

        // Defensive validation — never trust pixel array size or rectangle
        // bounds blindly. A mismatch here previously threw an uncaught
        // ArrayIndexOutOfBoundsException (or a native Canvas crash from
        // Bitmap.createBitmap/drawBitmap with out-of-range rects) on the very
        // first frame after the session reports CONNECTED, which is exactly
        // the "app closes suddenly when the connection starts" symptom
        // (issue #3).
        val expectedPixelCount = frame.width.toLong() * frame.height.toLong()
        if (frame.pixels.size.toLong() < expectedPixelCount) return

        // Clamp the destination rectangle to the screen bitmap's bounds.
        val dstX = frame.x.coerceIn(0, bitmap.width)
        val dstY = frame.y.coerceIn(0, bitmap.height)
        val drawW = frame.width.coerceAtMost(bitmap.width - dstX)
        val drawH = frame.height.coerceAtMost(bitmap.height - dstY)
        if (drawW <= 0 || drawH <= 0) return

        synchronized(bitmap) {
            if (frame.fullScreen && dstX == 0 && dstY == 0 &&
                drawW == bitmap.width && drawH == bitmap.height &&
                drawW == frame.width && drawH == frame.height
            ) {
                bitmap.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            } else {
                // BUG-E FIX: Avoid creating a temporary Bitmap per dirty rectangle.
                // The old pattern allocated a full Bitmap(w×h) then immediately recycled it
                // on every partial update — causing continuous GC pressure.
                // Canvas.drawBitmap(int[], offset, stride, x, y, width, height, hasAlpha, paint)
                // reads directly from the IntArray with zero heap allocation.
                // srcRect is always (0, 0, drawW, drawH), so offset = 0 and stride = frame.width.
                val androidCanvas = android.graphics.Canvas(bitmap)
                @Suppress("DEPRECATION")
                androidCanvas.drawBitmap(
                    frame.pixels,
                    0,              // offset: dirty rect starts at pixel[0]
                    frame.width,    // stride: full row width of the source frame
                    dstX.toFloat(),
                    dstY.toFloat(),
                    drawW,
                    drawH,
                    false,          // hasAlpha: no alpha blending needed for remote desktop
                    null            // paint
                )
            }
        }
        // FIX #5: Double-buffer — copy the freshly-written screenBitmap into
        // the inactive display buffer (no new allocation), then swap so Compose
        // always receives a different object reference (StateFlow equality check)
        // while the other buffer is safely idle. Eliminates the per-frame Bitmap
        // allocation that caused continuous GC pressure on ARMv7 (512 MB–1 GB RAM).
        // FIX #5: CAS-based atomic flip — getAndSet(!get()) was a two-step
        // read-then-write that let two concurrent frame callbacks land on the
        // same display buffer. compareAndSet retries until it wins the race,
        // guaranteeing exactly one winner per flip.
        var wasA: Boolean
        do {
            wasA = useDisplayBitmapA.get()
        } while (!useDisplayBitmapA.compareAndSet(wasA, !wasA))
        val displayTarget = if (wasA) displayBitmapA else displayBitmapB
        if (displayTarget != null) {
            // BUG-RACE FIX: bitmap is read by drawBitmap() below. Previously it was only
            // protected inside the synchronized(bitmap) block above, but between that block
            // and synchronized(displayTarget) a concurrent loadAndConnect() could call
            // screenBitmap?.recycle(), causing drawBitmap() to throw IllegalStateException.
            // Solution: hold synchronized(bitmap) across the copy so recycle() must wait.
            synchronized(bitmap) {
                if (!bitmap.isRecycled) {
                    synchronized(displayTarget) {
                        android.graphics.Canvas(displayTarget).drawBitmap(bitmap, 0f, 0f, null)
                    }
                }
            }
            _frameBitmap.tryEmit(displayTarget)
            // BUG-M3 FIX: record frame interval for accurate FPS display.
            // The old FPS counter used 1000/latency (connection setup time → constant),
            // producing a meaningless value. We track the real inter-frame gap here.
            val now = System.currentTimeMillis()
            if (lastFrameTimeMs > 0L) _frameRateMs.value = now - lastFrameTimeMs
            lastFrameTimeMs = now
        }
    }

    fun sendMouseMove(x: Int, y: Int)                                            = remoteClient?.sendMouseMove(x, y)
    fun sendMouseClick(x: Int, y: Int, button: RemoteMouseButton, down: Boolean) = remoteClient?.sendMouseClick(x, y, button, down)
    fun sendMouseScroll(x: Int, y: Int, delta: Int)                              = remoteClient?.sendMouseScroll(x, y, delta)
    fun sendKeyEvent(scanCode: Int, down: Boolean, extended: Boolean = false)    = remoteClient?.sendKeyEvent(scanCode, down, extended)
    fun sendCtrlAltDel()                                                         = remoteClient?.sendCtrlAltDel()

    // MULTITOUCH FEATURE: passthrough to whichever RemoteSessionClient is
    // active — see RemoteSessionClient.sendTouchFrame doc for the
    // per-protocol best-effort contract (real MS-RDPEI forwarding for RDP,
    // silent no-op for SSH/VNC).
    fun sendTouchFrame(contacts: List<com.systemsgo.hex.ui.screens.RemoteTouchContact>) = remoteClient?.sendTouchFrame(contacts)

    // MULTITOUCH FEATURE: whether the active session can carry real
    // multi-contact touch right now — see RemoteSessionClient.multiTouchSupported.
    val multiTouchSupported: StateFlow<Boolean> get() = remoteClient?.multiTouchSupported ?: RemoteSessionClient.FALSE_MULTITOUCH_SUPPORTED

    // TOOLBOX FEATURE (Stage 3): sticky modifier keys (Ctrl/Alt/Shift/Win) —
    // ExtraKeysBar's Ctrl/Alt/Win buttons used to send a scancode down on
    // touch-down and up on touch-up (i.e. only "held" for as long as a
    // finger stayed on the button), which makes combining them with any
    // *other* key (typed on the virtual keyboard, or another ExtraKeysBar
    // button) impossible with one hand. Sticky mode instead *toggles*: tap
    // once to send the down event and hold it held remotely (highlighted in
    // the UI); tap again (or send any other key while active — see
    // maybeAutoReleaseModifiers) to release it. `activeModifierScancodes`
    // tracks the down info needed to release each one later.
    private data class HeldModifier(val scanCode: Int, val extended: Boolean)
    private val heldModifiers = mutableMapOf<String, HeldModifier>()
    private val _activeModifiers = MutableStateFlow<Set<String>>(emptySet())
    val activeModifiers: StateFlow<Set<String>> = _activeModifiers.asStateFlow()

    fun toggleStickyModifier(id: String, scanCode: Int, extended: Boolean = false) {
        if (heldModifiers.containsKey(id)) {
            sendKeyEvent(scanCode, down = false, extended = extended)
            heldModifiers.remove(id)
        } else {
            sendKeyEvent(scanCode, down = true, extended = extended)
            heldModifiers[id] = HeldModifier(scanCode, extended)
        }
        _activeModifiers.value = heldModifiers.keys.toSet()
    }

    /**
     * Releases every currently-held sticky modifier (sends the matching
     * up-event for each). Called after any ordinary key goes through while
     * modifiers are active — mirrors how a physical keyboard's Ctrl/Alt only
     * apply to the *next* keystroke in most on-screen-keyboard UX — and as a
     * safety net on disconnect/cleanup so a held Ctrl/Alt can never leak
     * into the next session.
     */
    fun releaseAllStickyModifiers() {
        if (heldModifiers.isEmpty()) return
        heldModifiers.values.forEach { sendKeyEvent(it.scanCode, down = false, extended = it.extended) }
        heldModifiers.clear()
        _activeModifiers.value = emptySet()
    }

    /** Fires a single tap-key (down+up) as a scancode, combining with any currently-held sticky modifier, then auto-releases them — used by the virtual keyboard's modifier-combo mode (Stage 3). */
    fun sendComboKeyTap(scanCode: Int, extended: Boolean = false) {
        sendKeyEvent(scanCode, down = true, extended = extended)
        sendKeyEvent(scanCode, down = false, extended = extended)
        releaseAllStickyModifiers()
    }

    /** SSH terminal text input — typed characters or pasted text. */
    fun sendTerminalText(text: String) = remoteClient?.sendText(text)

    /** Terminal control byte, e.g. Ctrl+C — SSH, Telnet, and Mosh sessions only. */
    fun sendTerminalControlByte(byte: Int) {
        when (val c = remoteClient) {
            is SshClient    -> c.sendControlByte(byte)
            is com.systemsgo.hex.telnet.protocol.TelnetClient -> c.sendControlByte(byte)
            // MOSH FEATURE: sendControlByte already existed on MoshSessionClient
            // (same surface as SshClient's, per its own doc comment) but this
            // dispatch was never updated to route to it — without this, the
            // terminal toolbar's Ctrl+C/Ctrl+D/etc. keys silently did nothing
            // in a Mosh session.
            is com.systemsgo.hex.mosh.protocol.MoshSessionClient -> c.sendControlByte(byte)
        }
    }

    /**
     * TERM-RESIZE FIX: dispatches a real terminal-grid resize (cols/rows —
     * NOT the pixel resize() used by RDP/VNC in updateDisplayMetrics()
     * above) to whichever protocol client is active. [cols]/[rows] come
     * from TerminalScreen's onTerminalSizeChanged, which measures the
     * actual Compose-laid-out terminal viewport against the current font's
     * real glyph metrics — see that composable's doc comment for every
     * trigger this responds to (initial connect, rotation, split-screen,
     * keyboard show/hide, font-size change).
     *
     * Before this fix, SshClient.resizeTerminal()/MoshSessionClient.resizeTerminal()
     * existed and worked at the client layer but were never called from
     * anywhere in the UI — every session stayed pinned to the constructor's
     * fixed default PTY size (100 cols × 32 rows) for its entire lifetime,
     * regardless of the device's actual screen size or the user's chosen
     * font size. Server-side programs that query the terminal size (many
     * TUIs, `less`, shells doing line-wrapping) rendered incorrectly as a
     * result.
     *
     * Same dispatch shape as [sendTerminalControlByte] above by design —
     * SSH, Mosh, and Telnet (NAWS — see TelnetClient.resizeTerminal's doc):
     *  - Rlogin (RloginClient) only *parses/discards* the server's own
     *    window-size control message; the rlogin wire protocol has no
     *    equivalent client → server window-size message to send in the
     *    first place, so there is nothing to wire here.
     *  - Serial Console (SerialConsoleClient) has no PTY/window-size concept
     *    at all — it is a fixed byte-oriented wire protocol per its own
     *    class doc, so a cols/rows resize is not meaningful for it.
     *
     * TERM-RESIZE FIX (عربي): يوجّه طلب تغيير حجم شبكة الطرفية (أعمدة/صفوف)
     * إلى العميل النشط، بنفس نمط sendTerminalControlByte أعلاه. يشمل SSH
     * وMosh وTelnet (عبر NAWS — راجع توثيق TelnetClient.resizeTerminal).
     * Rlogin ما عنده رسالة client→server لحجم النافذة أصلاً، وSerial Console
     * بروتوكول أسلاك ثابت بدون مفهوم PTY.
     */
    fun resizeTerminal(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        when (val c = remoteClient) {
            is SshClient -> c.resizeTerminal(cols, rows)
            is com.systemsgo.hex.mosh.protocol.MoshSessionClient -> c.resizeTerminal(cols, rows)
            // NAWS-FEATURE follow-up: TelnetClient.resizeTerminal() now
            // sends a real RFC 1073 NAWS subnegotiation (previously
            // OPT_NAWS was defined but always declined — see that class's
            // updated respondToNegotiation doc comment).
            is com.systemsgo.hex.telnet.protocol.TelnetClient -> c.resizeTerminal(cols, rows)
        }
    }

    /**
     * KBD-INT FIX: called by the UI when the user answers a keyboard-interactive
     * prompt (e.g. types a TOTP code and taps Submit). Routed to whichever
     * client actually owns the blocked JSch callback thread.
     */
    fun submitAuthPromptResponse(responses: List<String>) {
        when (val c = remoteClient) {
            is SshClient         -> c.submitAuthPromptResponse(responses)
            is SshTunneledClient -> c.submitAuthPromptResponse(responses)
            is com.systemsgo.hex.mosh.protocol.MoshSessionClient -> c.submitAuthPromptResponse(responses)
        }
    }

    /** KBD-INT FIX: called when the user dismisses/cancels the prompt (e.g. back). */
    fun cancelAuthPrompt() {
        when (val c = remoteClient) {
            is SshClient         -> c.cancelAuthPrompt()
            is SshTunneledClient -> c.cancelAuthPrompt()
            is com.systemsgo.hex.mosh.protocol.MoshSessionClient -> c.cancelAuthPrompt()
        }
    }

    fun setSessionToolbarVisible(visible: Boolean) = viewModelScope.launch {
        settingsRepository.updateSessionToolbarVisible(visible)
    }

    fun setSessionExtraKeysVisible(visible: Boolean) = viewModelScope.launch {
        settingsRepository.updateSessionExtraKeysVisible(visible)
    }

    // TOOLBOX FEATURE (Stage 0): persistence hooks used by
    // rememberSessionToolboxState (SessionToolboxState.kt) — kept here so the
    // Toolbox composables never touch settingsRepository/viewModelScope
    // directly, matching every other persisted session-UI setting above.
    fun setToolboxQuickTools(ids: List<String>) = viewModelScope.launch {
        settingsRepository.updateToolboxQuickToolIds(ids)
    }

    fun setToolboxPosition(xFraction: Float, yFraction: Float) = viewModelScope.launch {
        settingsRepository.updateToolboxPosition(xFraction, yFraction)
    }

    fun setToolboxDockEdge(edge: String) = viewModelScope.launch {
        settingsRepository.updateToolboxDockEdge(edge)
    }

    // TOOLBOX FEATURE (Stage 5): persists the chosen "قلب الشاشة" mode (see
    // AppSettings.screenFlipMode doc comment for the global/auto-apply
    // persistence decision).
    fun setScreenFlipMode(mode: String) = viewModelScope.launch {
        settingsRepository.updateScreenFlipMode(mode)
    }

    // TOOLBOX FEATURE (Stage 6a): persists the chosen "وضع الماوس/تاتش باد"
    // (see AppSettings.mouseInputMode doc comment for the global/auto-apply
    // persistence decision).
    fun setMouseInputMode(mode: String) = viewModelScope.launch {
        settingsRepository.updateMouseInputMode(mode)
    }

    // TOOLBOX FEATURE (Stage 6b): "شكل المؤشر" popup writes straight through
    // to the same global cursorStyle/cursorSize settings SettingsScreen's
    // SettingsCursorChoice/SettingsSlider already expose via MainViewModel —
    // this is a second, session-scoped door to the identical persisted
    // values (not a separate preference), so both screens always agree.
    fun setCursorStyle(style: String) = viewModelScope.launch {
        settingsRepository.updateCursorStyle(style)
    }

    fun setCursorSize(size: Int) = viewModelScope.launch {
        settingsRepository.updateCursorSize(size)
    }

    // TOOLBOX FEATURE (Stage 7): "FPS" and "سرعة الاستجابة" toggles — each
    // writes to its OWN setting (showFpsCounter / showLatencyCounter), fully
    // independent of one another, matching every other stateful toggle tool
    // above (extra_keys_toggle, virtual_keyboard, mouse_mode...). The old
    // FIX-B1/BUG-M3 combined overlay only had one flag for both counters;
    // that flag (showFpsCounter) now controls the FPS number ONLY.
    fun setShowFpsCounter(v: Boolean) = viewModelScope.launch {
        settingsRepository.updateShowFps(v)
    }

    fun setShowLatencyCounter(v: Boolean) = viewModelScope.launch {
        settingsRepository.updateShowLatency(v)
    }

    // TOOLBOX FEATURE (Stage 7 follow-up): persists the free-dragged
    // position / pinch-scale of the FPS/latency overlay (see CounterOverlay.kt).
    fun setCounterPosition(xFraction: Float, yFraction: Float) = viewModelScope.launch {
        settingsRepository.updateCounterPosition(xFraction, yFraction)
    }

    fun setCounterScale(scale: Float) = viewModelScope.launch {
        settingsRepository.updateCounterScale(scale)
    }

    // FEATURE-TERM-FONT: persisted globally (like cursorSize) so the chosen
    // size is remembered the next time any SSH session is opened.
    fun setTerminalFontSize(sizeSp: Int) = viewModelScope.launch {
        settingsRepository.updateTerminalFontSize(sizeSp)
    }

    // FEATURE-TERM-SNIPPETS: add/remove go straight through the repository's
    // read-modify-write helpers — the resulting list flows back through
    // [settings] automatically, no local cache to keep in sync here.
    fun addTerminalSnippet(label: String, command: String) = viewModelScope.launch {
        settingsRepository.addTerminalSnippet(label, command)
    }

    fun removeTerminalSnippet(id: String) = viewModelScope.launch {
        settingsRepository.removeTerminalSnippet(id)
    }

    // UX-07: mark gesture hints as shown so overlay never appears again
    fun markGestureHintsShown() = viewModelScope.launch {
        settingsRepository.markGestureHintsShown()
    }

    // UX-09: Save the current remote frame as a PNG to the app-private screenshots directory.
    //
    // NEW-CRIT-1 FIX: Previous implementation wrote to MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    // (DIRECTORY_PICTURES/SystemsGo on API 29+, or getExternalStoragePublicDirectory on API 26-28).
    // Both are world-readable public locations:
    //   • Any app holding READ_MEDIA_IMAGES can access every screenshot silently.
    //   • Google Photos, Samsung Cloud, and other gallery apps auto-upload them to the cloud.
    //   • RDP screenshots routinely contain corporate data, credentials, and confidential docs.
    // FLAG_SECURE prevents Android's system screenshot mechanism, but does NOT stop the app
    // itself from reading its own framebuffer and writing it anywhere — as was the case here.
    //
    // Fix: save to filesDir/screenshots/ (app-private, mode 0700, inaccessible to other apps)
    // and exclude the path from backup via backup_rules.xml (already present: NEW-CRIT-2 FIX).
    // To share a screenshot the user must explicitly tap "Share" and choose a destination —
    // implement that with FileProvider + ACTION_SEND at the UI layer when needed.
    //
    // SEC-SCREENSHOT-RETENTION FIX: The previous implementation created a new timestamped file
    // for every capture with no cleanup policy. Screenshots contain confidential remote-desktop
    // content (corporate data, credentials, documents) and accumulate indefinitely, causing:
    //   1. Storage exhaustion — each PNG can be several MB; no upper bound existed.
    //   2. Growing attack surface — sensitive data accumulates in filesDir/screenshots/
    //      and stays there forever (until the user uninstalls).
    // Fix: enforce a dual retention policy enforced on every save:
    //   • MAX_SCREENSHOTS (20): oldest file deleted when the count exceeds the limit.
    //   • MAX_SCREENSHOT_DIR_BYTES (200 MB): oldest files pruned until under the cap.
    // Both limits are applied BEFORE writing the new file so the directory never briefly
    // exceeds the limit. The newest files are always preserved.
    private val MAX_SCREENSHOTS = 20
    private val MAX_SCREENSHOT_DIR_BYTES = 200L * 1024 * 1024  // 200 MB

    /**
     * Prunes old screenshots from [dir] to enforce the retention policy.
     * Files are sorted oldest-first (by lastModified); the newest ones survive.
     * Called on Dispatchers.IO before every new screenshot is written.
     */
    private fun pruneScreenshots(dir: java.io.File) {
        val files = (dir.listFiles() ?: return)
            .filter { it.isFile && it.name.endsWith(".png") }
            .sortedBy { it.lastModified() }   // oldest first

        // Enforce count limit
        var toDelete = files.size - MAX_SCREENSHOTS + 1   // +1 to leave room for the new file
        if (toDelete > 0) {
            files.take(toDelete).forEach { it.delete() }
        }

        // Enforce size limit (re-read after count pruning)
        val remaining = (dir.listFiles() ?: return)
            .filter { it.isFile && it.name.endsWith(".png") }
            .sortedBy { it.lastModified() }
        var totalBytes = remaining.sumOf { it.length() }
        for (f in remaining) {
            if (totalBytes <= MAX_SCREENSHOT_DIR_BYTES) break
            totalBytes -= f.length()
            f.delete()
        }
    }

    fun takeScreenshot(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            // BUG #3 FIX: Race Condition — applyFrameUpdate() writes to the display
            // bitmaps (displayBitmapA/B) concurrently using synchronized(displayTarget).
            // bitmap.compress() is NOT synchronized, so compressing the live display
            // bitmap while a frame update writes to it produces torn/corrupt screenshots
            // and can crash Bitmap.compress() with an IllegalStateException on some devices.
            // Fix: make a thread-safe copy first, then compress the copy.
            val safeCopy: Bitmap = synchronized(bitmap) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            try {
                val screenshotsDir = java.io.File(appContext.filesDir, "screenshots").also { it.mkdirs() }
                // SEC-SCREENSHOT-RETENTION FIX: Prune old files BEFORE writing the new one.
                pruneScreenshots(screenshotsDir)
                val file = java.io.File(screenshotsDir, "SystemsGo_${System.currentTimeMillis()}.png")
                file.outputStream().use { out ->
                    safeCopy.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                _screenshotSaved.emit(true)
            } catch (_: Exception) {
                _screenshotSaved.emit(false)
            } finally {
                safeCopy.recycle()
            }
        }
    }

    private val _screenshotSaved = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val screenshotSaved = _screenshotSaved.asSharedFlow()

    // TOOLBOX FEATURE (Stage 1) — "تصوير الجلسة": video half of the capture
    // tool. Photo capture above reuses the existing takeScreenshot(); this
    // reuses the exact same frameBitmap source for video, via SessionRecorder.
    private val sessionRecorder = SessionRecorder(appContext)
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    private val _recordingSaved = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val recordingSaved = _recordingSaved.asSharedFlow()

    fun startRecording(quality: SessionRecorder.Quality) {
        if (_isRecording.value) return
        val file = sessionRecorder.start(
            scope = viewModelScope,
            remoteWidth = screenWidth,
            remoteHeight = screenHeight,
            quality = quality,
            // Same clean, overlay-free bitmap takeScreenshot() reads —
            // guarantees the recorded video can never contain Toolbox/FPS/
            // any other UI chrome, matching the plan's Stage 1 requirement.
            frameProvider = { _frameBitmap.value },
        )
        _isRecording.value = file != null
        if (file == null) viewModelScope.launch { _recordingSaved.emit(false) }
    }

    fun stopRecording() = viewModelScope.launch {
        val file = sessionRecorder.stop()
        _isRecording.value = false
        _recordingSaved.emit(file != null)
    }

    // LIVE-RESIZE FIX: a multi-step rotation animation (Android can fire
    // onConfigurationChanged / the DisplayListener several times in quick
    // succession while settling into the final orientation) or an external
    // monitor hot-plug shouldn't spam the remote protocol with several resize
    // requests a few milliseconds apart — only the last size within this
    // window is actually sent.
    private var resizeJob: kotlinx.coroutines.Job? = null

    /**
     * Called from Activity.onConfigurationChanged() / its DisplayListener
     * whenever the local display size changes (rotation, or an external
     * monitor connected/disconnected).
     *
     * BUG 6 FIX (kept): always refreshes savedDeviceWidth/Height so that if
     * this session later reconnects (auto-reconnect, or the user manually
     * reconnects), it negotiates the *current* size instead of the
     * dimensions read back in onCreate().
     *
     * LIVE-RESIZE FIX (new): previously that was the only thing this did —
     * an active, already-CONNECTED session kept rendering at its original
     * resolution (scaled/cropped to fit) until the user manually
     * disconnected and reconnected. Now, if a session is actually connected,
     * we also push a live resize down to the protocol layer
     * (RemoteSessionClient.resize — real RDPEDISP/disp-channel or RFB
     * SetDesktopSize request) and update our own rendering buffers to match.
     */
    fun updateDisplayMetrics(width: Int, height: Int) {
        savedDeviceWidth  = width
        savedDeviceHeight = height

        if (width <= 0 || height <= 0) return
        // SMART-SIZING FEATURE: when on, the remote resolution is never
        // live-resized to match the viewport — it stays fixed at whatever
        // it connected with, and RdpCanvas's existing letterboxSize() scales
        // the frame to fit whatever the viewport is instead (see
        // AppSettings.smartSizingEnabled's doc comment for the full
        // rationale). savedDeviceWidth/Height are still updated above so a
        // *future reconnect* still negotiates the current device size —
        // this only skips the *live*, same-session resize request.
        if (settings.value.smartSizingEnabled) return
        if (width == screenWidth && height == screenHeight) return
        // Only a live, connected session has anything to resize; SSH is a
        // text terminal (resize() is a harmless no-op there anyway) and a
        // session still CONNECTING will simply pick up the new dimensions
        // the next time it (re)connects via savedDeviceWidth/Height above.
        if (_state.value !is SessionUiState.Connected) return

        resizeJob?.cancel()
        resizeJob = sessionScope.launch(Dispatchers.IO) {
            delay(400L) // let the rotation animation / hot-plug settle before touching the wire
            remoteClient?.resize(width, height)
            // Track the newly requested size regardless of whether the
            // server actually honors it — applyFrameUpdate() reconciles this
            // against whatever the server's *next real frame* reports, so a
            // server that ignores the request simply keeps rendering at its
            // old size without any visual glitch.
            screenWidth  = width
            screenHeight = height
            _resolution.value = width to height
        }
    }

    // HIRES-ZOOM FEATURE: best-effort request for a fresh, non-incremental
    // frame from the remote protocol, called once a pinch/pan gesture in
    // RdpCanvas settles. Lets the now-still viewport show a guaranteed-
    // complete framebuffer instead of whatever partial incremental updates
    // happened to arrive mid-gesture. A no-op for protocols that don't
    // implement RemoteSessionClient.refresh() (see its default there), so
    // this is always safe to call regardless of session type.
    fun requestFrameRefresh() {
        if (_state.value !is SessionUiState.Connected) return
        sessionScope.launch(Dispatchers.IO) {
            remoteClient?.refresh()
        }
    }

    /**
     * TOOLBOX FEATURE (Stage 8) — change picture quality / responsiveness
     * mid-session, without a visible reconnect.
     *
     * ── Required Stage 8 investigation, documented before any UI code ──
     * FreeRDP's PerformanceFlags (sent once in the Client Info PDU) and
     * RemoteFxCodecMode (negotiated once during capability exchange) are
     * only written into `rdpSettings` inside nativeConnect() — before
     * freerdp_connect() blocks on the handshake (see systemsgo_jni.c around
     * the BUG-QUALITY / jCompressionQuality comment). Neither libfreerdp's
     * client API nor our JNI bridge (AFreeRdpBridge) exposes any function
     * to push new values into those fields on an *already-connected*
     * session and have the server honor them — a genuine live change would
     * require implementing an MS-RDPBCGR §1.3.1.3 Deactivate-All /
     * Reactivate sequence, which this client build does not initiate on
     * demand (only certain server-driven events trigger it today, e.g. the
     * "disp" channel resize in nativeResize()/resize()). VNC/RFB has no
     * equivalent quality concept at all in this codebase — VncCredentials
     * takes no compressionQuality parameter, and the RFB protocol used here
     * has no analogous negotiable field.
     *
     * Conclusion: a true protocol-level live change is **not supported** by
     * the current architecture. Per the Stage 8 plan, this is documented
     * here instead of shipping a UI-only tool with no real effect.
     *
     * ── The solution actually implemented ──
     * Rather than changing the live protocol session, this opens a *second*
     * RemoteSessionClient with the new quality settings in the background
     * while the current client keeps rendering the visible frame, and
     * atomically swaps to it only once the new one reaches CONNECTED. The
     * user never sees a "Connecting…" screen, a blank/black frame, or a tab
     * state flicker — which is what the Stage 8 acceptance criterion
     * ("without any disconnect or visible reconnect") actually asks for.
     * Cost: for roughly the connection-handshake duration (typically well
     * under a second on a healthy network, bounded to 15s by the timeout
     * below) two sockets are briefly open; input during that window still
     * goes to the OLD client until the swap completes, so nothing is
     * silently dropped or misrouted.
     *
     * Scope note: only wired up for profile-based sessions (not Quick
     * Connect, and not SSH — SSH is a text terminal with no framebuffer
     * quality concept). Quick Connect can be added the same way once it
     * also tracks [frameCollectorJob]/[stateCollectorJob] the way
     * loadAndConnect() now does — left for the Part 2 UI pass.
     */
    fun changeSessionQuality(newLevel: Int) {
        if (_state.value !is SessionUiState.Connected) return
        if (currentProtocol().isTerminal) return
        val profileId = currentProfileId
        if (profileId == null || profileId == "__quick__") return
        if (_qualityChangeInProgress.value) return // debounce rapid taps in the Toolbox tool

        val previousLevel = settings.value.performanceLevel
        if (previousLevel == newLevel) return // nothing to do

        sessionScope.launch(Dispatchers.IO) {
            _qualityChangeInProgress.value = true
            var succeeded = false
            try {
                // Single source of truth (Stage 8 requirement #3): update the
                // same AppSettings.performanceLevel used by Settings → Connection,
                // optimistically, before the new client even connects — reverted
                // below if the swap fails.
                settingsRepository.updatePerformanceLevel(newLevel)

                val profile = try {
                    repository.getProfileById(profileId)
                } catch (e: SecurityException) {
                    null
                }
                if (profile == null) return@launch

                val effectiveLevel = com.systemsgo.hex.util.NetworkQualityDetector.resolve(appContext, newLevel, settings.value.dataSaverEnabled)
                // AUTO-COLOR-DEPTH FEATURE: same live-resolve-at-connect-time pattern
                // as effectiveLevel above — see NetworkQualityDetector.resolveColorDepth().
                val effectiveColorDepth = com.systemsgo.hex.util.NetworkQualityDetector.resolveColorDepth(
                    appContext, settings.value.colorDepth, settings.value.dataSaverEnabled
                )
                val newClient = RemoteSessionFactory.create(
                    profile, screenWidth, screenHeight,
                    com.systemsgo.hex.data.model.RdpPerformance.codecQualityFor(effectiveLevel),
                    colorDepth = effectiveColorDepth,
                    performanceLevel = effectiveLevel,
                    udpTransportEnabled = settings.value.udpTransportEnabled,  // UDP-TRANSPORT FEATURE
                    appContext = appContext,
                    // ENTRA-ID-AUTH FEATURE: re-resolves silently (no UI
                    // prompt) since a session is already connected and this
                    // is just a live quality swap — see
                    // resolveGatewayBearerTokenOrAbort's doc comment. A
                    // silent-refresh failure here (e.g. token expired mid
                    // session) simply aborts the quality change and keeps
                    // the existing connection, same as any other failure
                    // path in this function.
                    gatewayBearerToken = resolveGatewayBearerTokenOrAbort(profile) ?: return@launch,
                    // PAC-SUPPORT FEATURE: see resolvePacProxy's doc comment.
                    resolvedProxy = resolvePacProxy(profile),
                    // GUACAMOLE-PROTOCOL FEATURE: same silent-re-resolve
                    // reasoning as gatewayBearerToken just above.
                    guacamoleSession = if (profile.protocolType == ProtocolType.GUACAMOLE)
                        resolveGuacamoleSessionOrAbort(profile) ?: return@launch else null,
                )

                val connected = try {
                    withTimeoutOrNull(15_000) { newClient.connect() } == true
                } catch (e: Exception) {
                    false
                }

                if (!connected) {
                    newClient.disconnect()
                    return@launch
                }

                // Swap: stop the OLD client's frame/state collectors, point the
                // renderer at the NEW client, then start collecting from it using
                // the exact same shared logic a fresh loadAndConnect() would use.
                // The old client keeps rendering right up until this point, so
                // there is no gap where the screen shows nothing.
                frameCollectorJob?.cancel()
                stateCollectorJob?.cancel()
                clipboardSyncCollectorJob?.cancel()
                certificateChallengeCollectorJob?.cancel()
                railWindowCollectorJob?.cancel()
                val oldClient = remoteClient
                remoteClient = newClient
                oldClient?.disconnect()

                frameCollectorJob = attachFrameCollector(newClient)
                stateCollectorJob = attachStateCollector(newClient, profile)
                // TOOLBOX FEATURE (Stage 9): the replacement client re-runs its own
                // enableClipboard branch inside connect() above, so this just
                // re-points the UI-facing flow at it — same "no per-session
                // clipboard preference to carry across the swap" simplicity as
                // the rest of changeSessionQuality().
                clipboardSyncCollectorJob = attachClipboardSyncCollector(newClient)
                certificateChallengeCollectorJob = attachCertificateChallengeCollector(newClient)
                // REMOTEAPP-WINDOWS FEATURE: newClient.connect() already
                // succeeded above (connected == true), so railWindowManager is
                // guaranteed non-null immediately — no polling delay here, same
                // as any other post-connect-success collector attach.
                railWindowCollectorJob = attachRailWindowCollector(newClient)
                succeeded = true
            } finally {
                if (!succeeded) {
                    // Roll back the optimistic settings write so the UI's
                    // "current level" indicator doesn't lie about what's
                    // actually running.
                    settingsRepository.updatePerformanceLevel(previousLevel)
                }
                _qualityChangeInProgress.value = false
                _qualityChangeResult.emit(succeeded)
            }
        }
    }

    /**
     * TOOLBOX FEATURE (Stage 8): shared per-client connection-state collector.
     *
     * Handles CONNECTED / AUTH_FAILED / ERROR / DISCONNECTED for [client] —
     * auto-reconnect scheduling, connection-history logging, and tab-state
     * updates — exactly as loadAndConnect() always has. Extracted out of
     * loadAndConnect() (rather than duplicated) so changeSessionQuality()
     * can re-attach the identical handling to a freshly-connected
     * replacement client after a mid-session quality swap, without two
     * copies of ~200 lines of reconnect/logging logic drifting apart over
     * time.
     *
     * Launched on [sessionScope] (which outlives a single loadAndConnect()
     * call) rather than as a child of [currentSessionJob], because
     * changeSessionQuality() does not cancel/replace currentSessionJob —
     * only [frameCollectorJob] and [stateCollectorJob] get swapped.
     */
            // FIX: previously two independent flows (state + error) were
            // collected by separate `launch {}` coroutines that could run in
            // either order, so the ERROR/AUTH_FAILED branch could fire before
            // the matching error string had been recorded. combine()
            // guarantees the state and the latest error are always read
            // together from a single downstream collector.
    private fun attachStateCollector(
        client: RemoteSessionClient,
        profile: com.systemsgo.hex.data.model.RdpProfile
    ): kotlinx.coroutines.Job = sessionScope.launch(Dispatchers.IO) {
        kotlinx.coroutines.flow.combine(client.sessionState, client.error.onStart { emit("") }) { remoteState, msg ->
                    remoteState to msg.ifBlank { null }
                }.collect { (remoteState, lastError) ->
                    when (remoteState) {
                        RemoteSessionState.CONNECTED -> {
                            reconnectAttempts = 0   // ✅ إعادة العداد عند الاتصال الناجح
                            hasConnectedThisSession = true  // RECONNECT-GUARD FIX
                            intentionalDisconnect = false  // FIX #7: clear flag on successful reconnect
                            sessionWasSuccessful = true
                            // FIX B: تفعيل النقطة الخضراء (isConnected) والنقطة الكهرمانية (lastConnected).
                            // كانت updateConnectionState و updateLastConnected معرّفتين في Repository و DAO
                            // لكن لم يستدعهما أحد — البطاقات ظلّت رمادية للأبد.
                            // "__quick__" ليس صفاً حقيقياً في DB، نتخطاه لتفادي تحديث لا طائل منه.
                            if (profile.id != "__quick__") {
                                repository.updateConnectionState(profile.id, true)
                                repository.updateLastConnected(profile.id)
                            }
                            currentTabId?.let {
                                sessionTabManager.updateState(
                                    it, com.systemsgo.hex.data.model.ConnectionState.CONNECTED, ""
                                )
                            }
                            _state.emit(SessionUiState.Connected(profile))
                        }
                        RemoteSessionState.AUTH_FAILED -> {
                            currentLogId?.let { logId ->
                                connectionLogRepository.finish(logId, appContext.getString(R.string.disconnect_reason_auth), sessionWasSuccessful)
                            }
                            currentLogId = null  // BUG-Z2 FIX: prevent double-finish overwriting error reason
                            currentTabId?.let {
                                sessionTabManager.updateState(
                                    it, com.systemsgo.hex.data.model.ConnectionState.ERROR, appContext.getString(R.string.session_tab_auth_failed)
                                )
                            }
                            // FIX B: فشل المصادقة = لا اتصال → أعد النقطة إلى الرمادي
                            if (profile.id != "__quick__") {
                                repository.updateConnectionState(profile.id, false)
                            }
                            // BUGFIX · stale active sessions: AUTH_FAILED never retries, so this
                            // attempt is finished. If it never reached CONNECTED, it was only ever
                            // a *pending* session — remove it from Active Sessions instead of
                            // leaving a permanent ERROR entry behind. A session that had already
                            // connected once (sessionWasSuccessful) is left visible so the user can
                            // still see why it dropped.
                            if (!sessionWasSuccessful) {
                                currentTabId?.let { sessionTabManager.closeTab(it) }
                                currentTabId = null
                            }
                            // BUGFIX-UI: this branch already knows the failure is an
                            // authentication failure (from the state machine itself), so
                            // we no longer guess from the raw lastError text — always show
                            // the fully localized auth message instead of occasionally
                            // leaking a raw English string from the native bridge.
                            _state.emit(SessionUiState.Error(appContext.getString(R.string.error_auth_failed_credentials)))
                        }
                        RemoteSessionState.ERROR -> {
                            // ✅ إعادة الاتصال عند خطأ (ليس AUTH_FAILED)
                            // FIX #7: guard with intentionalDisconnect so a user-initiated
                            // disconnect() is never followed by a reconnect attempt.
                            // ALWAYS-RECONNECT FIX: no more "Auto Reconnect" toggle and no
                            // more app-decided attempt cap — keeps retrying (growing, capped
                            // back-off) until the user intentionally disconnects or the
                            // server itself returns a real, definitive error (AUTH_FAILED,
                            // handled in its own branch above, never reaches here).
                            if (!intentionalDisconnect) {
                                reconnectAttempts++
                                val waitSec = (reconnectAttempts * 3).coerceAtMost(30)
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.systemsgo.hex.data.model.ConnectionState.RECONNECTING,
                                        appContext.getString(R.string.session_tab_retry)
                                    )
                                }
                                _state.emit(SessionUiState.Connecting(
                                    appContext.getString(R.string.session_connecting_countdown, waitSec),
                                    isReconnecting = true,
                                    lastErrorHint  = lastError?.takeIf { it.isNotBlank() }
                                ))
                                // BUG-AA2 FIX: close the current log before launching the reconnect
                                // job. Previously currentLogId was overwritten by the next
                                // loadAndConnect() call without calling finish() → orphan entry
                                // with disconnectedAt=0 for every auto-reconnect attempt.
                                val errMsgForLog = lastError ?: appContext.getString(R.string.disconnect_reason_error)
                                currentLogId?.let { logId ->
                                    connectionLogRepository.finish(logId, errMsgForLog, sessionWasSuccessful)
                                }
                                currentLogId = null
                                /* BUG-7 FIX: calling loadAndConnect() directly here would cancel
                                 * currentSessionJob (which IS this coroutine) → self-cancellation
                                 * race condition causing duplicate collectors and duplicate log entries.
                                 * Launch a sibling coroutine that outlives the current collector. */
                                val savedProfileId = profile.id
                                val savedW = savedDeviceWidth
                                val savedH = savedDeviceHeight
                                // BUG-reconnect FIX: save the Job so disconnect() can cancel
                                // it immediately. Also guard after the delay — in case
                                // intentionalDisconnect was set while we were waiting.
                                reconnectJob = sessionScope.launch(Dispatchers.IO) {
                                    awaitReconnectWindow(waitSec * 1000L)
                                    if (!intentionalDisconnect) {
                                        loadAndConnect(savedProfileId, savedW, savedH)
                                    }
                                }
                                return@collect
                            } else {
                                val errMsg = lastError ?: appContext.getString(R.string.disconnect_reason_error)
                                currentLogId?.let { logId ->
                                    connectionLogRepository.finish(logId, errMsg, sessionWasSuccessful)
                                }
                                currentLogId = null  // BUG-Z2 FIX: prevent DISCONNECTED branch from overwriting the error reason
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.systemsgo.hex.data.model.ConnectionState.ERROR, appContext.getString(R.string.session_tab_error)
                                    )
                                }
                                // FIX B: انتهت محاولات إعادة الاتصال → أعد النقطة إلى الرمادي
                                if (profile.id != "__quick__") {
                                    repository.updateConnectionState(profile.id, false)
                                }
                                // BUGFIX · stale active sessions: retries are exhausted, so this
                                // attempt is finished. Remove it from Active Sessions if it never
                                // successfully connected — see the matching comment in the
                                // AUTH_FAILED branch above.
                                if (!sessionWasSuccessful) {
                                    currentTabId?.let { sessionTabManager.closeTab(it) }
                                    currentTabId = null
                                }
                                // BUGFIX-UI: errMsg (raw lastError) is kept as-is for the
                                // connection-history log above (intentional — full technical
                                // detail there aids diagnosis), but the live overlay the user
                                // sees right now gets the localized version instead of a raw,
                                // possibly-English string from the native bridge.
                                _state.emit(SessionUiState.Error(localizeConnectionError(appContext, errMsg)))
                            }
                        }
                        RemoteSessionState.DISCONNECTED -> {
                            // ✅ إعادة الاتصال عند الانقطاع المفاجئ (ليس عند disconnect() المتعمّد)
                            // BUG-3 FIX: FreeRDP fires both ERROR then DISCONNECTED on the same
                            // network drop. If the ERROR branch already launched a reconnect job,
                            // skip DISCONNECTED to prevent doubling reconnectAttempts.
                            if (reconnectJob?.isActive == true) return@collect
                            // FIX #7: intentionalDisconnect prevents reconnect after user-initiated
                            // disconnect().
                            // ALWAYS-RECONNECT FIX: no more toggle/attempt-cap gating — see the
                            // matching comment in the ERROR branch above.
                            // RECONNECT-GUARD FIX: was `_state.value is SessionUiState.Connected`
                            // — see the identical fix + full explanation in the mirrored
                            // Quick-Connect DISCONNECTED branch above (hasConnectedThisSession's
                            // declaration doc has the complete reasoning). Short version: that
                            // guard broke after exactly one failed retry, because the retry
                            // itself overwrites _state.value to Connecting before it even runs.
                            if (!intentionalDisconnect && hasConnectedThisSession) {
                                reconnectAttempts++
                                // BACKOFF-CONSISTENCY FIX: escalating wait (3s, 6s, 9s ... capped at
                                // 30s), same formula as the ERROR branch and the mirrored Quick
                                // Connect fix above — was a hardcoded, never-growing 3s wait that
                                // would hammer the server indefinitely on an outage longer than 3s.
                                val waitSec = (reconnectAttempts * 3).coerceAtMost(30)
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.systemsgo.hex.data.model.ConnectionState.RECONNECTING,
                                        appContext.getString(R.string.session_tab_retry)
                                    )
                                }
                                _state.emit(SessionUiState.Connecting(
                                    appContext.getString(R.string.session_connecting_countdown, waitSec),
                                    isReconnecting = true,
                                    lastErrorHint  = lastError?.takeIf { it.isNotBlank() }
                                ))
                                /* BUG-7 FIX: same self-cancellation issue as in the ERROR branch above. */
                                val savedProfileId = profile.id
                                val savedW = savedDeviceWidth
                                val savedH = savedDeviceHeight
                                // LOG-DIAGNOSIS FIX: this branch used to close the log with a
                                // hardcoded null reason, silently discarding whatever native
                                // error (lastError) had just come through — so Connection
                                // History showed endless "Reconnecting" rows with no clue why.
                                // Now the real error text (if any) is saved so the user can see
                                // exactly why each attempt failed.
                                currentLogId?.let { logId ->
                                    val dropReason = lastError?.takeIf { it.isNotBlank() }
                                        ?: appContext.getString(R.string.disconnect_reason_dropped)
                                    connectionLogRepository.finish(logId, dropReason, sessionWasSuccessful)
                                }
                                currentLogId = null
                                // BUG-reconnect FIX: save the Job so disconnect() can cancel
                                // it immediately. Also guard after the delay — in case
                                // intentionalDisconnect was set while we were waiting.
                                reconnectJob = sessionScope.launch(Dispatchers.IO) {
                                    awaitReconnectWindow(waitSec * 1000L)
                                    if (!intentionalDisconnect) {
                                        loadAndConnect(savedProfileId, savedW, savedH)
                                    }
                                }
                                return@collect
                            } else {
                                currentLogId?.let { logId ->
                                    val dropReason = if (sessionWasSuccessful && intentionalDisconnect)
                                        null // graceful, user-initiated disconnect — no error to show
                                    else
                                        lastError?.takeIf { it.isNotBlank() }
                                            ?: appContext.getString(R.string.disconnect_reason_dropped)
                                    connectionLogRepository.finish(logId, dropReason, sessionWasSuccessful)
                                }
                                currentLogId = null  // BUG-Z2 FIX: prevent any further finish() from re-writing over a null reason
                                currentTabId?.let {
                                    sessionTabManager.updateState(
                                        it, com.systemsgo.hex.data.model.ConnectionState.DISCONNECTED, ""
                                    )
                                }
                                // FIX B: قطع الاتصال النهائي → أعد النقطة إلى الرمادي
                                if (profile.id != "__quick__") {
                                    repository.updateConnectionState(profile.id, false)
                                }
                                // BUGFIX · stale active sessions: covers the (normally rare) case
                                // where DISCONNECTED is reached without the session ever having
                                // connected — e.g. a network drop / cancellation during the very
                                // first handshake. See the matching comment on the AUTH_FAILED
                                // branch above.
                                if (!sessionWasSuccessful) {
                                    currentTabId?.let { sessionTabManager.closeTab(it) }
                                    currentTabId = null
                                }
                                _state.emit(SessionUiState.Disconnected)
                            }
                        }
                        else -> {}
                    }
                    if (remoteState != RemoteSessionState.CONNECTED && !profile.protocolType.isTerminal) {
                        saveLastFrameThumbnail()
                    }
                }
            }

    /**
     * TOOLBOX FEATURE (Stage 8): shared per-client frame-rendering collector
     * for framebuffer protocols (RDP/VNC — never called for SSH). Extracted
     * out of loadAndConnect() for the same reason as [attachStateCollector]:
     * so changeSessionQuality() can re-attach identical frame handling to a
     * replacement client after a mid-session quality swap.
     */
    private fun attachFrameCollector(client: RemoteSessionClient): kotlinx.coroutines.Job =
        sessionScope.launch(Dispatchers.IO) {
            client.frameUpdates.collect { frame ->
                // LIFECYCLE-THROTTLE: skip the expensive Bitmap allocation/Canvas
                // draw while this tab isn't visible — see original comment at the
                // loadAndConnect() call site this was extracted from.
                if (_isForeground.value) {
                    try {
                        applyFrameUpdate(frame)
                    } catch (e: Exception) {
                        // A single malformed/oversized frame must never crash the
                        // whole app (issue #3). Log and skip this frame only.
                        android.util.Log.w("RdpSession", "Dropping malformed frame: ${e.message}")
                    }
                }
                _latency.value = client.latencyMs
            }
        }

    /**
     * RETRY FEATURE: replays the last connection attempt — the same saved
     * profile (or, for Quick Connect, the cached host/port/username/password
     * — see [lastQuickConnectParams]) at the same device resolution — so the
     * user can retry straight from the error screen instead of backing out
     * to HomeScreen and reconnecting from scratch.
     *
     * Never causes an overlapping/duplicate connection: [loadAndConnect] and
     * [loadAndConnectQuick] both cancel the previous attempt's coroutine and
     * disconnect any leftover client as the very first thing they do (see
     * FIX #7/#8 in loadAndConnect), so this is safe to call even if a prior
     * attempt hasn't fully finished tearing down yet — that teardown always
     * happens first. The tab-bar status is also kept in sync (see the
     * "RETRY FEATURE" else-branches added inside loadAndConnect/
     * loadAndConnectQuick's tab-open blocks) instead of being left showing
     * the old error while a new attempt is silently underway.
     *
     * No-op if there is nothing to retry yet (called before any connection
     * was ever attempted on this ViewModel) — not reachable from the UI
     * today since ErrorOverlay's Retry button only exists after a real
     * attempt has already run.
     */
    fun retryConnect() {
        val profileId = currentProfileId ?: return
        if (profileId == "__quick__") {
            val params = lastQuickConnectParams ?: return
            loadAndConnectQuick(
                host         = params.host,
                port         = params.port,
                username     = params.username,
                password     = params.password,
                deviceWidth  = savedDeviceWidth,
                deviceHeight = savedDeviceHeight,
            )
        } else {
            loadAndConnect(profileId, savedDeviceWidth, savedDeviceHeight)
        }
    }

    fun disconnect() {
        intentionalDisconnect = true   // FIX #7: explicit flag replaces unsafe Int.MAX_VALUE sentinel
        // RETRY FEATURE: no further retry makes sense once the user has
        // explicitly disconnected — drop the cached plaintext Quick Connect
        // password rather than let it linger for the rest of the Activity's life.
        clearQuickRetryParams()
        // TOOLBOX FEATURE (Stage 3): never let a sticky Ctrl/Alt/Shift/Win survive
        // into whatever the remote session's next user does — the up-events are
        // best-effort (the channel may already be gone), but the local
        // activeModifiers state is always cleared either way.
        releaseAllStickyModifiers()
        // FIX-reconnect-reset: reset the attempt counter so the next manual connection
        // starts fresh. Without this, a session that exhausted some retries before the
        // user disconnected would leave a non-zero counter, giving fewer auto-reconnect
        // attempts in the subsequent session.
        reconnectAttempts = 0
        hasConnectedThisSession = false  // RECONNECT-GUARD FIX: mirrors reconnectAttempts reset above
        // BUG-reconnect FIX: cancel any pending delayed-reconnect coroutine so the
        // countdown stops immediately. Without this, a user clicking "Disconnect"
        // during "Reconnecting in Ns…" would see the connection re-open the moment
        // the timer expired, violating their explicit intent.
        reconnectJob?.cancel()
        reconnectJob = null
        // TOOLBOX FEATURE (Stage 9): don't leave a stale "on"/"off" tint showing
        // once the session is gone — the next loadAndConnect()/loadAndConnectQuick()
        // re-attaches a fresh collector regardless.
        clipboardSyncCollectorJob?.cancel()
        clipboardSyncCollectorJob = null
        _clipboardSyncState.value = null
        certificateChallengeCollectorJob?.cancel()
        certificateChallengeCollectorJob = null
        _certificateChallenge.value = null
        // REMOTEAPP-WINDOWS FEATURE: same "don't leave stale state showing"
        // reasoning as clipboardSyncState above. Deliberately NOT resetting
        // _railDisplayMode — that's a user preference for this session that
        // should survive a reconnect, same as RemoteAppWindowManager itself
        // not resetting it on a fresh handleWindowState/activateWindow.
        railWindowCollectorJob?.cancel()
        railWindowCollectorJob = null
        _railWindows.value = emptyList()
        _railActiveWindowId.value = null
        // MOSH-PREDICT-FEATURE: same "don't leave stale state showing, next
        // loadAndConnect() re-attaches fresh" reasoning as clipboardSyncState
        // above — otherwise a disconnected Mosh session's last predicted
        // (unconfirmed) text could remain visible/underlined indefinitely.
        predictionOverlayCollectorJob?.cancel()
        predictionOverlayCollectorJob = null
        _predictionOverlay.value = null
        if (!currentProtocol().isTerminal) saveLastFrameThumbnail()
        remoteClient?.disconnect()
        // Feature-06: finalise the history log as a clean user-initiated disconnect
        viewModelScope.launch {
            currentLogId?.let { logId ->
                connectionLogRepository.finish(logId, null, sessionWasSuccessful)
            }
            currentLogId = null  // BUG-Z2 FIX: prevent any trailing state event from calling finish() again
            _state.emit(SessionUiState.Disconnected)
        }
        // Feature-05: remove this tab from the tab bar
        currentTabId?.let { sessionTabManager.closeTab(it) }

        // VPN-AWARE-CONNECTIVITY: release the process-wide network binding
        // once no other tab still needs it — bindProcessToNetwork only
        // affects *new* sockets, so this never disturbs any other tab's
        // already-open connection, and the next loadAndConnect()/
        // loadAndConnectQuick() call re-applies the current preference fresh
        // regardless.
        if (sessionTabManager.tabs.value.none {
                it.state == com.systemsgo.hex.data.model.ConnectionState.CONNECTED ||
                    it.state == com.systemsgo.hex.data.model.ConnectionState.CONNECTING ||
                    it.state == com.systemsgo.hex.data.model.ConnectionState.RECONNECTING
            }
        ) {
            com.systemsgo.hex.util.VpnConnectivityManager.clearBinding(appContext)
        }
    }

    private fun currentProtocol(): ProtocolType = _protocolType.value

    /** Feature-05: switch the foreground tab (called from tab bar clicks). */
    fun switchToTab(tabId: String) = sessionTabManager.switchTo(tabId)

    /** Feature-05: close a tab by id (e.g. from the × button in the tab bar). */
    fun closeTab(tabId: String) = sessionTabManager.closeTab(tabId)

    override fun onCleared() {
        super.onCleared()
        // RETRY FEATURE: same rationale as disconnect() above.
        clearQuickRetryParams()
        // TOOLBOX FEATURE (Stage 1): best-effort stop so a leftover encoder/
        // muxer never keeps running (and keeps a file handle open) after the
        // session Activity/ViewModel is torn down.
        if (_isRecording.value) sessionRecorder.cancel()
        // TOOLBOX FEATURE (Stage 3): safety net — see disconnect()'s call for why.
        releaseAllStickyModifiers()
        // BUG-N3 FIX: unregister so the listener lambda is not held alive after the ViewModel is gone
        com.systemsgo.hex.TrimMemoryBus.unregister(trimMemoryListener)

        // VPN-AWARE-CONNECTIVITY: best-effort safety net mirroring disconnect()'s
        // cleanup — only clears the binding if no other tab still needs it.
        if (sessionTabManager.tabs.value.none {
                it.state == com.systemsgo.hex.data.model.ConnectionState.CONNECTED ||
                    it.state == com.systemsgo.hex.data.model.ConnectionState.CONNECTING ||
                    it.state == com.systemsgo.hex.data.model.ConnectionState.RECONNECTING
            }
        ) {
            com.systemsgo.hex.util.VpnConnectivityManager.clearBinding(appContext)
        }

        // BUG-5 FIX: When the app is killed during an active session (process death, OOM,
        // swipe-to-kill), onCleared() is called but the normal disconnect/state-machine path
        // is never reached — leaving the connection_logs row with disconnectedAt=0 forever.
        // viewModelScope is already cancelled by the time onCleared() runs, so we cannot
        // use launch{}. Use runBlocking on a plain background thread instead, mirroring
        // the same pattern used for the thumbnail save below.
        val logId = currentLogId
        val wasOk = sessionWasSuccessful
        if (logId != null) {
            currentLogId = null  // prevent any concurrent access from double-finishing
            Thread {
                try {
                    kotlinx.coroutines.runBlocking {
                        connectionLogRepository.finish(logId, appContext.getString(R.string.disconnect_reason_app_closed), wasOk)
                    }
                } catch (_: Exception) { /* best-effort; never crash onCleared */ }
            }.start()
        }

        // viewModelScope is already cancelled by this point, so save directly
        // on a plain thread rather than relying on saveLastFrameThumbnail's
        // viewModelScope.launch (issue #11).
        val bitmap = screenBitmap
        val profileId = currentProfileId
        remoteClient?.disconnect()
        if (bitmap != null && profileId != null) {
            Thread {
                // BUG-N1 FIX: the previous code had no isRecycled guard and no try-catch
                // inside the Thread. If bitmap was recycled by a concurrent call (race with
                // saveLastFrameThumbnail or loadAndConnect), bitmap.copy() would throw an
                // IllegalStateException that the Thread swallowed silently — leaving the
                // snapshot un-recycled and causing a native heap leak. Fix:
                // 1. Check isRecycled inside the synchronized block before copy().
                // 2. Wrap everything in try-catch so any remaining edge-case exception
                //    still triggers cleanup via finally.
                var snapshot: android.graphics.Bitmap? = null
                try {
                    snapshot = synchronized(bitmap) {
                        if (bitmap.isRecycled) null
                        else bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    }
                    // BUG-Z1 FIX: "__quick__" is not a real profile; skip saving to avoid
                    // an orphan "__quick__.jpg" accumulating in cacheDir/last_frames/.
                    if (snapshot != null && profileId != "__quick__") {
                        com.systemsgo.hex.util.LastFrameStore.save(appContext, profileId, snapshot)
                    }
                } catch (_: Exception) {
                    // bitmap was recycled between the isRecycled check and copy() — ignore
                } finally {
                    snapshot?.recycle()
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }.start()
        } else {
            screenBitmap?.recycle()
        }
        // FIX #5 / #9: Also recycle the double-buffer display bitmaps.
        displayBitmapA?.recycle(); displayBitmapA = null
        displayBitmapB?.recycle(); displayBitmapB = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BUGFIX-UI: Connection-error localization
// ─────────────────────────────────────────────────────────────────────────────
// The VNC/SSH connection paths already map their failures to fully localized
// strings (error_vnc_timeout, error_ssh_connect_failed, ...). The RDP path and
// the generic CoroutineExceptionHandler, however, fell straight back to
// `lastError`/`throwable.message` — raw text from the native FreeRDP bridge or
// a bare exception, which is always in English regardless of the app's
// language. This maps the most common raw substrings (including FreeRDP's
// raw ERRCONNECT_* enum names from freerdp_get_last_error_name(), e.g.
// "ERRCONNECT_CONNECT_TRANSPORT_FAILED") to a fully localized message.
//
// UI-AUDIT FIX: the previous fallback branch interpolated the *raw* string
// straight into error_connection_generic_detail ("Connection error: %1$s"),
// so any FreeRDP code that didn't match one of the keyword branches below —
// e.g. ERRCONNECT_SECURITY_NEGO_CONNECT_FAILED, ERRCONNECT_CONNECT_CANCELLED,
// ERRCONNECT_INSUFFICIENT_PRIVILEGES — was shown to the user verbatim as an
// opaque English enum name. Two changes fix that: (1) many more ERRCONNECT_*
// keywords are matched below, and (2) isRawTechnicalCode() detects anything
// that still looks like an unmatched SCREAMING_SNAKE_CASE/hex code and swaps
// it for a fully generic localized message instead of interpolating it — the
// raw string is still passed to Log.e() by callers for diagnosis, it's just
// no longer shown on-screen.
private fun isRawTechnicalCode(text: String): Boolean {
    // Matches things like "ERRCONNECT_CONNECT_TRANSPORT_FAILED", "0x00020014",
    // or bare exception class names — i.e. strings with no spaces that don't
    // read like a normal human sentence.
    if (' ' !in text) return true
    if (Regex("^0x[0-9A-Fa-f]+$").matches(text)) return true
    return false
}

fun localizeConnectionError(context: android.content.Context, raw: String?): String {
    val text = raw?.trim()
    if (text.isNullOrBlank()) return context.getString(R.string.error_unexpected)
    val lower = text.lowercase()
    return when {
        "timed out" in lower || "timeout" in lower ->
            context.getString(R.string.error_timeout_generic)
        "cancelled" in lower || "canceled" in lower ->
            context.getString(R.string.error_cancelled_generic)
        "denied" in lower || "insufficient_privileges" in lower || "insufficient privileges" in lower ->
            context.getString(R.string.error_denied_generic)
        "refused" in lower ->
            context.getString(R.string.error_refused_generic)
        "unreachable" in lower || "no route to host" in lower || "network is down" in lower ||
            "transport_failed" in lower || "transport failed" in lower ->
            context.getString(R.string.error_unreachable_generic)
        "unknownhost" in lower || "unable to resolve host" in lower ||
            "name or service not known" in lower || "dns" in lower ->
            context.getString(R.string.error_dns_generic)
        "certificate" in lower || "ssl" in lower || "tls" in lower || "handshake" in lower ||
            "security_nego" in lower || "nla" in lower ->
            context.getString(R.string.error_certificate_generic)
        "password_expired" in lower || "password certainly expired" in lower || "must_change" in lower ->
            context.getString(R.string.error_password_expired_generic)
        "account_disabled" in lower || "account_locked" in lower || "account_expired" in lower ||
            "account_restriction" in lower ->
            context.getString(R.string.error_account_generic)
        "auth" in lower || "credential" in lower || "login" in lower || "logon" in lower || "password" in lower ->
            context.getString(R.string.error_auth_failed_credentials)
        isRawTechnicalCode(text) ->
            // Unmatched FreeRDP enum name / hex code / bare exception class —
            // never show this verbatim, it means nothing to the user.
            context.getString(R.string.error_connection_generic)
        else ->
            // A genuine human-readable sentence (e.g. from the VNC/SSH
            // libraries) that just didn't match a specific keyword above —
            // safe to show as supporting detail.
            context.getString(R.string.error_connection_generic_detail, text)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI State
// ─────────────────────────────────────────────────────────────────────────────

sealed class SessionUiState {
    object Idle : SessionUiState()
    // CANCEL-RECONNECT FIX: isReconnecting distinguishes an initial connect
    // from an auto-reconnect retry so ConnectingOverlay can show a Cancel
    // button only when it's actually useful (stopping a retry loop), and can
    // show plain status text instead of an attempt counter.
    // LOG-DIAGNOSIS FIX: lastErrorHint carries the actual native/error text
    // that triggered this retry (if any) so the user sees *why* it's
    // reconnecting right on the overlay, not just that it is.
    data class Connecting(
        val name: String,
        val isReconnecting: Boolean = false,
        val lastErrorHint: String? = null
    ) : SessionUiState()
    data class Connected(val profile: RdpProfile) : SessionUiState()
    data class Error(val message: String)         : SessionUiState()
    object Disconnected                           : SessionUiState()
}

// ─────────────────────────────────────────────────────────────────────────────
// Session Screen Root
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RdpSessionScreen(
    viewModel: RdpSessionViewModel,
    onClose: () -> Unit,
    // PIP FEATURE: while true, this Activity is a small floating PiP window —
    // only the remote framebuffer should be shown, with all interactive chrome
    // (toolbar, extra-keys, hint overlays, dialogs) hidden. Defaults keep every
    // existing call site (and any future one that doesn't care about PiP)
    // compiling unchanged.
    isInPip: Boolean = false,
    // PIP FEATURE (completeness fix): whether the current device/OS actually
    // supports PiP at all. Lets the toolbar hide its PiP button instead of
    // offering a control that would silently do nothing when tapped.
    pipSupported: Boolean = true,
    onEnterPip: () -> Unit = {},
    // EXTERNAL-DISPLAY FEATURE: displays other than the phone/tablet's own
    // screen currently available (Samsung DeX monitor, HDMI-USB-C dock,
    // wireless display...), the id of the one the session is currently
    // mirrored to (null = only shown here), and the two actions to move it
    // there / bring it back. Defaults keep every existing call site
    // compiling unchanged.
    externalDisplays: List<com.systemsgo.hex.display.ExternalDisplayInfo> = emptyList(),
    sessionOnExternalDisplayId: Int? = null,
    onMoveToExternalDisplay: (com.systemsgo.hex.display.ExternalDisplayInfo) -> Unit = {},
    onBringSessionBack: () -> Unit = {},
    // "How to use the session toolbar" spotlight tour — defaults keep every
    // existing call site (previews, tests) compiling unchanged; the real
    // Activity call site above wires these to CoachMarkPreferences.
    shouldShowToolboxSpotlight: () -> Boolean = { false },
    onToolboxSpotlightFinished: () -> Unit = {},
    // SPLIT-SCREEN FEATURE: when two RdpSessionScreen instances are embedded
    // side-by-side (see SplitScreenActivity), only one BackHandler should be
    // registered for the system back gesture/button at a time — otherwise
    // the two panes fight over the same back callback and the wrong pane's
    // disconnect-confirmation dialog can appear. SplitScreenActivity owns
    // its own back handling and passes false here for both embedded panes;
    // every existing call site is unaffected (defaults to true, unchanged
    // behavior).
    enableBackHandler: Boolean = true
) {
    val state        by viewModel.state.collectAsStateWithLifecycle()
    // PERF FIX (smoothness pass): frameBitmap used to be collected here, at
    // RdpSessionScreen's own top level, which invalidated this whole
    // ~2000-line composable (toolbar, dialogs, keyboard, toolbox...) on
    // every remote frame (15-60×/sec). It's now collected only inside
    // RdpCanvas (which receives viewModel.frameBitmap directly) and read via
    // viewModel.frameBitmap.value in the two places that just need the
    // latest frame at click time (screenshot buttons). See RdpCanvas's
    // `bitmapFlow` parameter doc comment for the full explanation.
    val soundManager = com.systemsgo.hex.ui.components.LocalSoundManager.current
    var prevState by remember { mutableStateOf<SessionUiState>(SessionUiState.Idle) }

    // BUG 11 FIX: enableOnBackInvokedCallback="true" is now set in the Manifest for
    // RdpSessionActivity. Without a BackHandler, pressing back during an active session
    // would exit the app immediately with no warning, leaving the session running.
    // Intercept back when connected and show a confirmation dialog instead.
    var showDisconnectDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = enableBackHandler && state is SessionUiState.Connected) {
        showDisconnectDialog = true
    }
    // PIP FEATURE: dialogs must not be shown while floating in PiP — the system
    // never dispatches touch input to a PiP window's content, so a dialog left
    // open here would strand the user with no way to dismiss it until they
    // expand back to full screen. Matches the same !isInPip gating already
    // applied to the toolbar/extra-keys/gesture-hints overlays below.
    if (showDisconnectDialog && !isInPip) {
        // UI-FIX (design feedback): this was the only dialog in the whole app still
        // using the stock, unthemed AlertDialog — a plain light Material dialog
        // popping up over the dark space UI, and one of the most commonly seen
        // dialogs since it appears on every back-press during a live session.
        // Restyled to match the rest of the app's dialogs (dark surface, rounded
        // corners, StarDust/CometTail text, glowing accent border, SpaceButton for
        // the destructive action).
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            containerColor   = NebulaSurface,
            shape            = RoundedCornerShape(20.dp),
            modifier = Modifier.border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(NovaPink.copy(alpha = 0.30f), Color.Transparent, NovaPink.copy(alpha = 0.12f))
                ),
                shape = RoundedCornerShape(20.dp)
            ),
            icon  = { Icon(Icons.Outlined.PowerSettingsNew, null, tint = NovaPink, modifier = Modifier.size(32.dp)) },
            title = {
                Text(
                    stringResource(R.string.disconnect_dialog_title),
                    color      = StarDust,
                    fontWeight = FontWeight.Bold
                )
            },
            text  = { Text(stringResource(R.string.disconnect_dialog_message), color = CometTail) },
            confirmButton = {
                SpaceButton(
                    text    = stringResource(R.string.disconnect),
                    onClick = {
                        showDisconnectDialog = false
                        viewModel.disconnect()
                        onClose()
                    },
                    variant  = ButtonVariant.DANGER,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            }
        )
    }

    // UNTRUSTED-CERT DIALOG FEATURE: replaces the old "Accept self-signed
    // certificate" pre-connection toggle — trust is now decided here, at
    // connect time, with the real certificate details in front of the user.
    // Same !isInPip gating as showDisconnectDialog above: a PiP window never
    // receives touch input, so a dialog stranded open there would block the
    // connection (RdpRemoteAdapter is blocked on a background thread waiting
    // for this decision) with no way for the user to respond.
    val certificateChallenge by viewModel.certificateChallenge.collectAsStateWithLifecycle()
    if (certificateChallenge != null && !isInPip) {
        CertificateTrustDialog(
            challenge = certificateChallenge!!,
            onRespond = viewModel::respondToCertificateChallenge,
        )
    }

    val intentionalDisconnect by viewModel.intentionalDisconnectFlow.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state is SessionUiState.Connected && prevState !is SessionUiState.Connected) {
            soundManager?.play(com.systemsgo.hex.audio.SoundManager.Sound.CONNECT, 0.85f)
        } else if (state is SessionUiState.Error) {
            soundManager?.play(com.systemsgo.hex.audio.SoundManager.Sound.ERROR, 0.7f)
        } else if (state is SessionUiState.Disconnected && prevState is SessionUiState.Connected) {
            // BUGFIX-UI-6: صوت SUCCESS كان يُشغَّل حتى عند انقطاع شبكة مفاجئ أو
            // timeout — الآن نميّز بين القطع المتعمّد (زر Disconnect) وغير
            // المتعمّد (انقطاع/خطأ) عبر intentionalDisconnectFlow.
            if (intentionalDisconnect) {
                soundManager?.play(com.systemsgo.hex.audio.SoundManager.Sound.SUCCESS, 0.5f)
            } else {
                soundManager?.play(com.systemsgo.hex.audio.SoundManager.Sound.ERROR, 0.5f)
            }
        }
        prevState = state
    }
    val latency      by viewModel.latency.collectAsStateWithLifecycle()
    val channelStatus by viewModel.channelStatus.collectAsStateWithLifecycle()
    // CODEC-NEGOTIATION FEATURE (part 3): see viewModel.negotiatedCodec's doc
    // comment — null until the first RDPGFX surface command arrives, or for
    // the lifetime of a session that never leaves the classic (non-GFX) path.
    val negotiatedCodec by viewModel.negotiatedCodec.collectAsStateWithLifecycle()
    // XRDP-CAPABILITY-DETECTION FEATURE: see viewModel.negotiatedSecurityProtocol
    // doc comment — "NLA"/"TLS"/"RDP" once known, null before that/for
    // non-RDP protocols.
    val negotiatedSecurityProtocol by viewModel.negotiatedSecurityProtocol.collectAsStateWithLifecycle()
    val settings     by viewModel.settings.collectAsStateWithLifecycle()
    val protocolType by viewModel.protocolType.collectAsStateWithLifecycle()
    val terminalText by viewModel.terminalText.collectAsStateWithLifecycle()
    // MOSH-PREDICT-FEATURE: null for every protocol except an active Mosh
    // session — see RdpSessionViewModel.predictionOverlay's doc comment.
    val predictionOverlay by viewModel.predictionOverlay.collectAsStateWithLifecycle()
    val (screenWidth, screenHeight) = viewModel.resolution.collectAsStateWithLifecycle().value

    // REMOTEAPP-WINDOWS FEATURE: see RdpRemoteAdapter.railWindowManager /
    // RemoteAppWindowManager's class doc for what these mean — collected here
    // (not inside RdpCanvas) since the display-mode toggle tool and the
    // MULTI_WINDOW switcher (part 2) both need them at this level too, not
    // just the canvas.
    val railWindows       by viewModel.railWindows.collectAsStateWithLifecycle()
    val railDisplayMode   by viewModel.railDisplayMode.collectAsStateWithLifecycle()
    val railActiveWindowId by viewModel.railActiveWindowId.collectAsStateWithLifecycle()
    val railActiveWindow = railActiveWindowId?.let { id -> railWindows.firstOrNull { it.windowId == id } }
    // SINGLE_WINDOW only actually crops once a real window rect has arrived
    // (railActiveWindow != null) — before that (native "rail" channel hasn't
    // reported anything yet, or this isn't a RemoteApp session at all) we
    // fall back to the normal full-desktop render exactly as before.
    val railCropRect = if (railDisplayMode == com.systemsgo.hex.data.model.RemoteAppDisplayMode.SINGLE_WINDOW)
        railActiveWindow?.rect else null

    // UX-09: Show toast when screenshot is saved/failed
    val context = androidx.compose.ui.platform.LocalContext.current

    // NEW-CRIT-1 FIX: screenshotPermLauncher removed — saving to filesDir/screenshots/
    // requires no runtime permission (app-private storage is always accessible to the app).
    // The previous WRITE_EXTERNAL_STORAGE launcher was only needed for the public gallery path.

    LaunchedEffect(Unit) {
        viewModel.screenshotSaved.collect { success ->
            val msg = if (success) context.getString(R.string.screenshot_saved)
                      else context.getString(R.string.screenshot_failed)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // TOOLBOX FEATURE (Stage 1): mirrors the screenshotSaved toast above for
    // video recordings.
    LaunchedEffect(Unit) {
        viewModel.recordingSaved.collect { success ->
            val msg = if (success) context.getString(R.string.recording_saved)
                      else context.getString(R.string.recording_failed)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // TOOLBOX FEATURE (Stage 8, Part 2): mirrors the screenshotSaved/
    // recordingSaved toasts above for the connection_quality tool. No
    // technical detail on failure (requirement #3) — the user only ever
    // sees "couldn't change quality, try again", never the underlying
    // connect timeout/exception, which changeSessionQuality() already
    // swallows internally.
    LaunchedEffect(Unit) {
        viewModel.qualityChangeResult.collect { success ->
            val msg = if (success) context.getString(R.string.quality_change_succeeded)
                      else context.getString(R.string.quality_change_failed)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Extra-keys visibility default from Settings, but can be toggled freely
    // by the user during the session (issue #8 — full show/hide control).
    // The default is persisted back to Settings so it's remembered next time
    // (issue #9). TOOLBOX FEATURE (Stage 0): the equivalent toolbar-visible
    // flag is gone — SessionToolbox owns its own collapsed/expanded state,
    // reset fresh on every (re)connect automatically since it's `remember`ed
    // inside the Connected branch of the `when` below, matching the same
    // BUGFIX-UI-7 intent for showExtraKeys just below.
    var showExtraKeys    by remember { mutableStateOf(settings.sessionExtraKeysVisible) }
    var showFileTransfer by remember { mutableStateOf(false) }
    // TOOLBOX FEATURE (Stage 2): the real Arabic/English virtual keyboard —
    // session-only state (not persisted), same as isCollapsed in
    // SessionToolboxState: every session starts with it closed, the user
    // opens it from the Toolbox when they actually need to type.
    var showVirtualKeyboard by remember { mutableStateOf(false) }
    var keyboardLayout by remember {
        mutableStateOf(
            if (java.util.Locale.getDefault().language == "ar") VirtualKeyboardLayout.ARABIC
            else VirtualKeyboardLayout.ENGLISH
        )
    }
    // TOOLBOX FEATURE (Stage 4) — "شاشة خالية" (blank screen): hides every
    // overlay drawn on top of the remote frame (the whole Toolbox, the extra
    // keys bar, the virtual keyboard, the FPS/latency readout, the recording
    // indicator, the view-only banner...) while leaving touch/mouse/keyboard
    // control of the session completely unaffected. Session-only, same as
    // showVirtualKeyboard above — every fresh connection starts with a clean
    // (non-blank) screen so the user always sees the Toolbox on first connect.
    var isBlankScreen by remember { mutableStateOf(false) }
    // A short-lived on-screen hint ("long-press here to return") shown for a
    // few seconds right after the user turns blank screen on, so the return
    // gesture stays discoverable without permanently cluttering the clean
    // view the feature exists to provide.
    var showBlankScreenHint by remember { mutableStateOf(false) }
    LaunchedEffect(isBlankScreen) {
        if (isBlankScreen) {
            showBlankScreenHint = true
            delay(3000)
            showBlankScreenHint = false
        }
    }
    // EXTERNAL-DISPLAY FEATURE: only used when more than one external display
    // is connected at once and we need to ask which one to use.
    var showDisplayChooser by remember { mutableStateOf(false) }

    // BUGFIX-UI-7: showExtraKeys كانت تُهيّأ مرة واحدة فقط عند أول تكوين
    // للـ Composable. إن أخفى المستخدم الشريط يدوياً ثم انقطع الاتصال وأُعيد
    // تلقائياً (autoReconnect)، يستمر نفس الـ Composable حياً فتبقى القيمة
    // مخفية في الجلسة الجديدة دون أي وسيلة ظاهرة لإعادتها. نراقب انتقال الحالة
    // إلى Connected (قادمة من حالة غير متصلة) ونعيد ضبط الرؤية على القيمة
    // المحفوظة في الإعدادات في كل مرة تبدأ فيها جلسة (اتصال أولي أو إعادة اتصال).
    var wasConnectedBefore by remember { mutableStateOf(false) }
    val toolboxCoachMarkState = com.systemsgo.hex.ui.coachmark.rememberCoachMarkState()
    val toolboxSpotlightTitle = stringResource(R.string.rdp_toolbox_spotlight_title)
    val toolboxSpotlightBody = stringResource(R.string.rdp_toolbox_spotlight_body)
    LaunchedEffect(state) {
        val isConnectedNow = state is SessionUiState.Connected
        if (isConnectedNow && !wasConnectedBefore) {
            showExtraKeys = settings.sessionExtraKeysVisible
            // TOOLBOX FEATURE (Stage 3): a fresh connection means a fresh remote
            // keyboard state — any modifier left "stuck down" from a previous
            // session/reconnect (state was cleared locally by disconnect()
            // already, but this covers the case the ViewModel outlived a
            // silent auto-reconnect without going through disconnect()).
            viewModel.releaseAllStickyModifiers()
            // "How to use the session toolbar" spotlight — first real
            // connection only (same transition guard as the reset above), and
            // only ever runs once for this device (see shouldShowToolboxSpotlight).
            if (shouldShowToolboxSpotlight()) {
                toolboxCoachMarkState.start(
                    listOf(
                        com.systemsgo.hex.ui.coachmark.CoachMarkStep(
                            targetKey = "rdp_toolbox",
                            title = toolboxSpotlightTitle,
                            description = toolboxSpotlightBody,
                            shape = com.systemsgo.hex.ui.coachmark.CoachMarkShape.RoundedRect(cornerRadius = 28.dp),
                        ),
                    ),
                )
            }
        }
        wasConnectedBefore = isConnectedNow
    }

    val backgroundColor = DeepSpace

    Box(Modifier.fillMaxSize().background(backgroundColor)) {
        // Smooth crossfade between connecting / connected / error / disconnected
        // states instead of an abrupt switch (issue #6 — professional animation).
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(animationSpec = tween(350)) + scaleIn(initialScale = 0.97f, animationSpec = tween(350))) togetherWith
                    fadeOut(animationSpec = tween(200))
            },
            label = "session_state"
        ) { s ->
            when (s) {
                is SessionUiState.Connecting   -> ConnectingOverlay(
                    name           = s.name,
                    isReconnecting = s.isReconnecting,
                    errorHint      = s.lastErrorHint,
                    onCancel       = { viewModel.disconnect(); onClose() }
                )
                is SessionUiState.Error        -> ErrorOverlay(s.message, onClose, onRetry = { viewModel.retryConnect() })
                is SessionUiState.Disconnected -> DisconnectedOverlay(onClose)
                is SessionUiState.Connected -> if (protocolType.isTerminal) {
                    // SSH is a text terminal, not a framebuffer — a completely
                    // different (and much simpler) UI than the RDP/VNC canvas.
                    TerminalScreen(
                        profileName  = s.profile.name,
                        terminalText = terminalText,
                        latency      = latency,
                        fontSize     = settings.terminalFontSize,
                        onFontSizeChange = { viewModel.setTerminalFontSize(it) },
                        // TERM-RESIZE FIX: real cols/rows from the measured
                        // Compose viewport — see TerminalScreen's
                        // onTerminalSizeChanged doc and
                        // RdpSessionViewModel.resizeTerminal() for the full
                        // rationale/trigger list.
                        onTerminalSizeChanged = { cols, rows -> viewModel.resizeTerminal(cols, rows) },
                        snippets     = settings.terminalSnippets,
                        onAddSnippet = { label, command -> viewModel.addTerminalSnippet(label, command) },
                        onDeleteSnippet = { viewModel.removeTerminalSnippet(it) },
                        onSendText   = { viewModel.sendTerminalText(it) },
                        onSendControlByte = { viewModel.sendTerminalControlByte(it) },
                        onDisconnect = { viewModel.disconnect(); onClose() },
                        // MOSH-PREDICT-FEATURE: predictionOverlay is null for
                        // every protocol other than an active Mosh session, so
                        // these collapse to their defaults ("", false, false)
                        // for SSH/IPMI-SOL/serial/telnet/rlogin — identical to
                        // TerminalScreen's behavior before this feature existed.
                        predictedText = predictionOverlay?.pendingText ?: "",
                        predictedVisible = predictionOverlay?.visible == true,
                        predictedUnderlined = predictionOverlay?.underlined == true,
                    )
                } else {
                    Box(Modifier.fillMaxSize()) {
                        // TOOLBOX FEATURE (Stage 5): resolved once per settings change, shared
                        // by RdpCanvas (draw + input math) and the toolboxTools list below (icon
                        // tint / current-selection highlight in the popup). Declared at this
                        // scope (not inside the `else` below) so both call sites can see it.
                        val screenFlipMode = remember(settings.screenFlipMode) {
                            ScreenFlipMode.fromSetting(settings.screenFlipMode)
                        }
                        // TOOLBOX FEATURE (Stage 6a): same resolve-once-per-settings-change
                        // pattern as screenFlipMode above — shared by RdpCanvas (input
                        // math + cursor visibility) and the toolboxTools list below (icon
                        // tint / label reflecting the active mode).
                        val mouseInputMode = remember(settings.mouseInputMode) {
                            MouseInputMode.fromSetting(settings.mouseInputMode)
                        }
                        // MULTITOUCH FEATURE: live "can this session actually carry
                        // real multi-contact touch right now" signal — see
                        // RemoteSessionClient.multiTouchSupported doc. Read by the
                        // mouse_mode toolbox tool's cycle logic below to decide whether
                        // MULTITOUCH is even offered as a next state.
                        val multiTouchSupported by viewModel.multiTouchSupported.collectAsStateWithLifecycle()
                        // EXTERNAL-DISPLAY FEATURE: once the session is being shown on an
                        // external display (see RdpPresentation), rendering the exact same
                        // live frames a second time here would be pure waste (decode cost,
                        // battery) for a view the user isn't looking at — show a small
                        // status card with a one-tap way back instead. The underlying
                        // ViewModel/remoteClient are completely untouched either way.
                        if (sessionOnExternalDisplayId != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(DeepSpace),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement  = Arrangement.Center
                            ) {
                                Icon(Icons.Default.CastConnected, null, tint = PlasmaGreen, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.external_display_banner_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = StarDust
                                )
                                Text(
                                    stringResource(R.string.external_display_banner_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CometTail
                                )
                                Spacer(Modifier.height(16.dp))
                                androidx.compose.material3.Button(onClick = onBringSessionBack) {
                                    Text(stringResource(R.string.cd_bring_back_to_phone))
                                }
                            }
                        } else if (railDisplayMode == com.systemsgo.hex.data.model.RemoteAppDisplayMode.MULTI_WINDOW &&
                                   railWindows.isNotEmpty()) {
                            // REMOTEAPP-WINDOWS FEATURE (part 3): MULTI_WINDOW's actual
                            // freeform surface — see RemoteAppFreeformDesktop's class doc
                            // comment for the full design. Replaces the plain RdpCanvas
                            // call (which used to just render the whole composited
                            // desktop uncropped in this mode, per its own now-stale
                            // "there is no single active window surface to crop"
                            // comment above the switcher bar) with one draggable/
                            // resizable tile per open window. The switcher bar below
                            // still overlays on top as a quick-jump list — useful for
                            // windows minimized (isVisible == false) or dragged off the
                            // visible desktop bounds.
                            RemoteAppFreeformDesktop(
                                bitmapFlow     = viewModel.frameBitmap,
                                windows        = railWindows,
                                desktopWidth   = screenWidth,
                                desktopHeight  = screenHeight,
                                activeWindowId = railActiveWindowId,
                                onActivate     = { windowId -> viewModel.activateRailWindow(windowId) },
                                onMove         = { windowId, rect -> viewModel.moveRailWindow(windowId, rect) },
                                onMouseClick   = { windowId, localX, localY, button, down ->
                                    val w = railWindows.firstOrNull { it.windowId == windowId }
                                    viewModel.sendMouseClick(localX + (w?.rect?.left ?: 0), localY + (w?.rect?.top ?: 0), button, down)
                                },
                                modifier       = Modifier.fillMaxSize(),
                            )
                        } else {
                        RdpCanvas(
                            // PERF FIX (smoothness pass): pass the StateFlow itself so the
                            // per-frame read happens inside RdpCanvas, not here — see the
                            // doc comment on RdpCanvas's `bitmapFlow` parameter.
                            bitmapFlow           = viewModel.frameBitmap,
                            // REMOTEAPP-WINDOWS FEATURE: RdpCanvas's own gesture/zoom/cursor
                            // math treats (screenWidth, screenHeight) as the full logical
                            // screen — while SINGLE_WINDOW mode has a real active window,
                            // that "screen" is the cropped window's rect, not the whole
                            // composited RAIL desktop. See RdpCanvas's cropRect doc comment
                            // for the full coordinate contract this depends on.
                            screenWidth          = railCropRect?.width()  ?: screenWidth,
                            screenHeight         = railCropRect?.height() ?: screenHeight,
                            cropRect             = railCropRect,
                            cursorStyle          = settings.cursorStyle,
                            cursorSize           = settings.cursorSize,
                            showCursor           = settings.showCursorOnTouch,
                            touchpadSensitivity  = settings.touchpadSensitivity,   // ✅
                            scrollSensitivity    = settings.scrollSensitivity,     // ✅
                            rightClickLongPress  = settings.rightClickLongPress,   // ✅
                            flipMode             = screenFlipMode,                 // ✅ TOOLBOX (Stage 5)
                            mouseInputMode       = mouseInputMode,                  // ✅ TOOLBOX (Stage 6a)
                            // REMOTEAPP-WINDOWS FEATURE: RdpCanvas reports x/y in the
                            // cropped window's own [0, rect.width()]×[0, rect.height()]
                            // space whenever cropRect is set (see its coordinate contract
                            // doc comment) — add the window's offset back on before this
                            // reaches the remote session, which only ever understands
                            // full-desktop coordinates. A no-op (+0, +0) outside
                            // SINGLE_WINDOW / before any window rect has arrived.
                            onMouseMove  = { x, y       -> viewModel.sendMouseMove(x + (railCropRect?.left ?: 0), y + (railCropRect?.top ?: 0)) },
                            onMouseClick = { x, y, b, d -> viewModel.sendMouseClick(x + (railCropRect?.left ?: 0), y + (railCropRect?.top ?: 0), b, d) },
                            onScroll     = { x, y, d    -> viewModel.sendMouseScroll(x + (railCropRect?.left ?: 0), y + (railCropRect?.top ?: 0), d) },
                            // MULTITOUCH FEATURE: same railCropRect offset-back
                            // contract as onMouseMove/onMouseClick/onScroll above
                            // (see RdpCanvas's cropRect coordinate-contract doc) —
                            // each contact's x/y is in the cropped window's own
                            // space whenever a RAIL window is active.
                            onMultiTouchFrame = { contacts ->
                                val dx = railCropRect?.left ?: 0
                                val dy = railCropRect?.top ?: 0
                                viewModel.sendTouchFrame(
                                    if (dx == 0 && dy == 0) contacts
                                    else contacts.map { it.copy(x = it.x + dx, y = it.y + dy) }
                                )
                            },
                            // HIRES-ZOOM FEATURE: once a pinch/pan gesture settles, ask
                            // the protocol layer for a fresh full frame (VNC only —
                            // no-op elsewhere) so the now-still viewport is guaranteed
                            // to reflect a complete, up-to-date framebuffer.
                            onViewportSettled = { viewModel.requestFrameRefresh() },
                            // EXTERNAL-DISPLAY / DEX FEATURE: same scan-code sink ExtraKeysBar uses.
                            onHardwareKeyEvent = { sc, down, ext -> viewModel.sendKeyEvent(sc, down, ext) },
                            modifier     = Modifier.fillMaxSize()
                        )
                        }

                        // TOOLBOX FEATURE (Stage 0): SessionToolbar's fixed
                        // top strip is replaced by SessionToolbox — a
                        // draggable, collapsible, customizable container.
                        // Every button that used to be hardcoded inside
                        // SessionToolbar is now a SessionTool in this list;
                        // later stages only ever add to it.
                        val toolboxState = rememberSessionToolboxState(
                            settings = settings,
                            onQuickToolsChanged = { viewModel.setToolboxQuickTools(it) },
                            onPositionChanged = { x, y -> viewModel.setToolboxPosition(x, y) },
                            onDockEdgeChanged = { viewModel.setToolboxDockEdge(it) },
                        )
                        // TOOLBOX FEATURE (Stage 1)
                        val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
                        // TOOLBOX FEATURE (Stage 8, Part 2): mirrors qualityChangeInProgress
                        // (StateFlow<Boolean>, per changeSessionQuality()'s doc comment) so
                        // the connection_quality tool's badge/list rebuild the moment a
                        // background quality swap starts or settles.
                        val qualityChangeInProgress by viewModel.qualityChangeInProgress.collectAsStateWithLifecycle()
                        // TOOLBOX FEATURE (Stage 9): null = clipboard sync unsupported/not
                        // started for this session (SSH, or profile disabled clipboard
                        // redirection) → hide the tool entirely; true/false = supported,
                        // tool shown tinted according to current on/off state.
                        val clipboardSyncState by viewModel.clipboardSyncState.collectAsStateWithLifecycle()
                        // Theme colors are @Composable-derived (Theme.kt/Color.kt) and
                        // must be resolved here, in composable context, before being
                        // captured by the plain `remember { buildList { ... } }` lambda
                        // below (which is NOT itself @Composable).
                        val toolPlasmaGreen = PlasmaGreen
                        val toolPulsarCyan  = PulsarCyan
                        val toolNovaPink    = NovaPink
                        // TOOLBOX FEATURE (Stage 6b): same theme-derived cursor accent
                        // RdpCanvas uses to actually render the cursor (LocalSpaceColors
                        // .current.cursorColor) — resolved here too so the "شكل المؤشر"
                        // popup's live preview swatches match the real on-screen cursor.
                        val cursorAccent = LocalSpaceColors.current.cursorColor
                        // PERF FIX (smoothness pass): `frameBitmap` was previously a remember()
                        // key here, so this entire tool list (icons, labels, lambdas, popups)
                        // was rebuilt from scratch on every single remote frame — 15-60 times a
                        // second — for a list whose only actual dependency on the frame is
                        // reading its *current* value at click time inside two onClick
                        // handlers below. Those now read viewModel.frameBitmap.value directly
                        // (fresher, since it's read at the moment of the tap, not from
                        // whatever frame happened to be current when this list was last
                        // rebuilt), so frameBitmap is no longer needed as a key at all.
                        val toolboxTools = remember(
                            latency, pipSupported, externalDisplays, sessionOnExternalDisplayId,
                            showExtraKeys, toolPlasmaGreen, toolPulsarCyan, toolNovaPink,
                            isRecording, showVirtualKeyboard, isBlankScreen, screenFlipMode, mouseInputMode,
                            cursorAccent,
                            // TOOLBOX FEATURE (Stage 7): rebuild the tool list whenever either
                            // independent counter setting changes, so the fps_counter/
                            // latency_counter tint reflects the current state immediately.
                            settings.showFpsCounter, settings.showLatencyCounter,
                            // TOOLBOX FEATURE (Stage 8, Part 2): rebuild when a quality swap
                            // starts/settles (badge) or the protocol/profile identity of this
                            // session is what it is (visibility — SSH / Quick Connect hide the
                            // tool entirely; both are fixed for the lifetime of a given `s`,
                            // but included for the same defensive-rebuild reasons as above).
                            qualityChangeInProgress, protocolType, s.profile.id,
                            // TOOLBOX FEATURE (Stage 9): rebuild when clipboard sync
                            // becomes (un)available or its on/off state changes.
                            clipboardSyncState,
                            // REMOTEAPP-WINDOWS FEATURE: rebuild when the in-session
                            // single/multi toggle changes (checkmark in the popup) or
                            // this profile's RemoteApp visibility gate changes (fixed
                            // for the lifetime of a given `s`, included for the same
                            // defensive-rebuild reasons as protocolType/s.profile.id above).
                            railDisplayMode, s.profile.remoteAppEnabled,
                        ) {
                            buildList {
                                add(SessionTool(
                                    id = "screenshot",
                                    icon = Icons.Default.CameraAlt,
                                    label = context.getString(R.string.take_screenshot),
                                    tint = toolPlasmaGreen,
                                    // NEW-CRIT-1 FIX: no runtime permission needed — screenshots
                                    // are saved to app-private filesDir/screenshots/.
                                    onClick = { viewModel.frameBitmap.value?.let { viewModel.takeScreenshot(it) } },
                                ))
                                // TOOLBOX FEATURE (Stage 1): "تصوير الجلسة" — photo + video,
                                // remote-frame-only, via a small popup (quality picker for video).
                                add(SessionTool(
                                    id = "session_capture",
                                    icon = Icons.Default.Videocam,
                                    label = context.getString(R.string.capture_session_tool),
                                    tint = if (isRecording) toolNovaPink else toolPulsarCyan,
                                    popupContent = { dismiss ->
                                        CaptureToolPopup(
                                            isRecording = isRecording,
                                            onTakePhoto = { viewModel.frameBitmap.value?.let { viewModel.takeScreenshot(it) } },
                                            onStartRecording = { quality -> viewModel.startRecording(quality) },
                                            onStopRecording = { viewModel.stopRecording() },
                                            dismiss = dismiss,
                                        )
                                    },
                                ))
                                if (isRecording) {
                                    // Stage 1 requirement: a dedicated stop button appears
                                    // automatically in the Quick Bar for the recording's duration,
                                    // regardless of what the user has pinned (forceVisible).
                                    add(SessionTool(
                                        id = "stop_recording",
                                        icon = Icons.Default.Stop,
                                        label = context.getString(R.string.capture_video_stop),
                                        tint = toolNovaPink,
                                        forceVisible = true,
                                        onClick = { viewModel.stopRecording() },
                                    ))
                                }
                                add(SessionTool(
                                    id = "ctrl_alt_del",
                                    icon = Icons.Outlined.Lock,
                                    label = context.getString(R.string.cd_ctrl_alt_del),
                                    onClick = { viewModel.sendCtrlAltDel() },
                                ))
                                add(SessionTool(
                                    id = "file_transfer",
                                    icon = Icons.Default.FolderOpen,
                                    label = context.getString(R.string.ft_toolbar_button),
                                    tint = toolPulsarCyan,
                                    onClick = { showFileTransfer = true },
                                ))
                                if (pipSupported) {
                                    add(SessionTool(
                                        id = "pip",
                                        icon = Icons.Default.PictureInPictureAlt,
                                        label = context.getString(R.string.cd_enter_pip),
                                        onClick = onEnterPip,
                                    ))
                                }
                                if (externalDisplays.isNotEmpty() || sessionOnExternalDisplayId != null) {
                                    val onExternal = sessionOnExternalDisplayId != null
                                    add(SessionTool(
                                        id = "external_display",
                                        icon = if (onExternal) Icons.Default.CastConnected else Icons.Default.Cast,
                                        label = context.getString(
                                            if (onExternal) R.string.cd_bring_back_to_phone else R.string.cd_move_to_display
                                        ),
                                        tint = if (onExternal) toolPlasmaGreen else toolPulsarCyan,
                                        onClick = {
                                            when {
                                                onExternal -> onBringSessionBack()
                                                externalDisplays.size == 1 -> onMoveToExternalDisplay(externalDisplays.first())
                                                else -> showDisplayChooser = true
                                            }
                                        },
                                    ))
                                }
                                add(SessionTool(
                                    id = "extra_keys_toggle",
                                    icon = Icons.Default.Keyboard,
                                    label = context.getString(R.string.cd_toggle_toolbar),
                                    onClick = {
                                        showExtraKeys = !showExtraKeys
                                        viewModel.setSessionExtraKeysVisible(showExtraKeys)
                                    },
                                ))
                                // TOOLBOX FEATURE (Stage 2): the real Arabic/English virtual
                                // keyboard — distinct from extra_keys_toggle above (which only
                                // shows modifier/function keys like Ctrl/Alt/Tab/arrows).
                                add(SessionTool(
                                    id = "virtual_keyboard",
                                    icon = Icons.Default.Translate,
                                    label = context.getString(R.string.virtual_keyboard_tool),
                                    tint = if (showVirtualKeyboard) toolPulsarCyan else null,
                                    onClick = { showVirtualKeyboard = !showVirtualKeyboard },
                                ))
                                // TOOLBOX FEATURE (Stage 4): "شاشة خالية" — hides every overlay
                                // (Toolbox included) so only the remote frame is visible, while
                                // touch/mouse/keyboard control keeps working normally. Exiting
                                // is a long-press on a small hotspot (see below), since the
                                // Toolbox itself is gone the moment this is active.
                                add(SessionTool(
                                    id = "blank_screen",
                                    icon = Icons.Outlined.Visibility,
                                    label = context.getString(R.string.blank_screen_tool),
                                    tint = if (isBlankScreen) toolPulsarCyan else null,
                                    onClick = { isBlankScreen = true },
                                ))
                                // TOOLBOX FEATURE (Stage 5): "قلب الشاشة" — local-only mirroring
                                // of the remote frame (see ScreenFlipMode / RdpCanvas.flipMode).
                                // Tapping opens a small popup listing all four states (a
                                // "dropdown"), same anchored-popup pattern as session_capture
                                // above, with a checkmark on the currently active one.
                                add(SessionTool(
                                    id = "screen_flip",
                                    icon = Icons.Default.Flip,
                                    label = context.getString(R.string.screen_flip_tool),
                                    tint = if (screenFlipMode != ScreenFlipMode.NORMAL) toolPulsarCyan else null,
                                    popupContent = { dismiss ->
                                        ScreenFlipPopup(
                                            currentMode = screenFlipMode,
                                            onSelect = { viewModel.setScreenFlipMode(it.toSetting()) },
                                            dismiss = dismiss,
                                        )
                                    },
                                ))
                                // REMOTEAPP-WINDOWS FEATURE: only meaningful for a
                                // profile that actually launches a published RemoteApp
                                // (Quick Connect and every plain full-desktop RDP/VNC/SSH
                                // profile never populate railWindows, so the toggle would
                                // have nothing to switch between — same visibility gate
                                // as pipSupported/externalDisplays above, fixed for the
                                // lifetime of a given `s`). This is the *in-session*
                                // override RemoteAppDisplayModePicker's doc comment (in
                                // Components.kt's profile editor) refers to — it changes
                                // railDisplayMode for the rest of this connection only,
                                // never touching the saved per-profile default.
                                if (s.profile.remoteAppEnabled) {
                                    add(SessionTool(
                                        id = "remote_app_display_mode",
                                        icon = if (railDisplayMode == com.systemsgo.hex.data.model.RemoteAppDisplayMode.SINGLE_WINDOW)
                                            Icons.Outlined.Fullscreen else Icons.Outlined.ViewCarousel,
                                        label = context.getString(R.string.remote_app_display_mode),
                                        tint = toolPulsarCyan,
                                        popupContent = { dismiss ->
                                            RemoteAppDisplayModePopup(
                                                currentMode = railDisplayMode,
                                                onSelect = { viewModel.setRemoteAppDisplayMode(it) },
                                                dismiss = dismiss,
                                            )
                                        },
                                    ))
                                }
                                // TOOLBOX FEATURE (Stage 6a): "وضع الماوس/تاتش باد" — toggles
                                // between the pre-existing relative touchpad behaviour and
                                // absolute/direct mode (see MouseInputMode.kt doc comment).
                                // A single tap flips the mode, same pattern as
                                // extra_keys_toggle/virtual_keyboard above (no popup needed for
                                // a plain two-state toggle) — tint highlights when DIRECT is
                                // active, matching every other stateful tool in this list.
                                // TOOLBOX FEATURE (Stage 6a): "وضع الماوس/تاتش باد" — toggles
                                // between the pre-existing relative touchpad behaviour and
                                // absolute/direct mode (see MouseInputMode.kt doc comment).
                                // MULTITOUCH FEATURE: extended from a 2-state toggle to a
                                // TOUCHPAD → DIRECT → MULTITOUCH → TOUCHPAD cycle, but only
                                // when multiTouchSupported is true for this session (RDP with
                                // the "rdpei" channel actually connected — see
                                // RemoteSessionClient.multiTouchSupported doc); otherwise the
                                // cycle stays the original 2-state TOUCHPAD/DIRECT toggle, so a
                                // VNC/SSH session (or an RDP server without MS-RDPEI) never
                                // exposes a mode it can't actually carry.
                                add(SessionTool(
                                    id = "mouse_mode",
                                    icon = Icons.Default.Mouse,
                                    label = context.getString(
                                        when (mouseInputMode) {
                                            MouseInputMode.DIRECT     -> R.string.mouse_mode_direct_tool
                                            MouseInputMode.MULTITOUCH -> R.string.mouse_mode_multitouch_tool
                                            MouseInputMode.TOUCHPAD   -> R.string.mouse_mode_touchpad_tool
                                        }
                                    ),
                                    tint = if (mouseInputMode != MouseInputMode.TOUCHPAD) toolPulsarCyan else null,
                                    onClick = {
                                        val next = when (mouseInputMode) {
                                            MouseInputMode.TOUCHPAD -> MouseInputMode.DIRECT
                                            MouseInputMode.DIRECT ->
                                                if (multiTouchSupported) MouseInputMode.MULTITOUCH else MouseInputMode.TOUCHPAD
                                            MouseInputMode.MULTITOUCH -> MouseInputMode.TOUCHPAD
                                        }
                                        viewModel.setMouseInputMode(next.toSetting())
                                    },
                                ))
                                // TOOLBOX FEATURE (Stage 6b): "شكل المؤشر" — only meaningful
                                // once the local cursor overlay is actually visible, which
                                // (per the Stage 6a design decision on RdpCanvas.showCursor)
                                // is exclusively MouseInputMode.DIRECT — so the tool is
                                // dimmed/inert in TOUCHPAD (enabled=false), rather than
                                // hidden outright, so the user can still discover it exists
                                // and see why it's greyed out (it lives right next to
                                // mouse_mode above).
                                add(SessionTool(
                                    id = "cursor_shape",
                                    icon = Icons.Default.NearMe,
                                    label = context.getString(R.string.cursor_shape_tool),
                                    tint = toolPulsarCyan,
                                    enabled = mouseInputMode == MouseInputMode.DIRECT,
                                    popupContent = { dismiss ->
                                        CursorShapePopup(
                                            currentStyle = settings.cursorStyle,
                                            currentSize = settings.cursorSize,
                                            accent = cursorAccent,
                                            onStyleSelect = { viewModel.setCursorStyle(it) },
                                            onSizeChange = { viewModel.setCursorSize(it) },
                                            dismiss = dismiss,
                                        )
                                    },
                                ))
                                // TOOLBOX FEATURE (Stage 7): "FPS" and "سرعة الاستجابة" — used to
                                // be a single combined overlay (see FIX-B1/BUG-M3 comments below,
                                // near the two Boxes that render them) gated by one setting.
                                // Now two independent tools, each toggling its own persisted
                                // flag (settings.showFpsCounter / settings.showLatencyCounter),
                                // same tap-to-toggle + tint-when-active pattern as
                                // virtual_keyboard/mouse_mode above.
                                add(SessionTool(
                                    id = "fps_counter",
                                    icon = Icons.Outlined.Speed,
                                    label = context.getString(R.string.fps_counter_tool),
                                    tint = if (settings.showFpsCounter) toolPulsarCyan else null,
                                    onClick = { viewModel.setShowFpsCounter(!settings.showFpsCounter) },
                                ))
                                add(SessionTool(
                                    id = "latency_counter",
                                    icon = Icons.Outlined.NetworkCheck,
                                    label = context.getString(R.string.latency_counter_tool),
                                    tint = if (settings.showLatencyCounter) toolPulsarCyan else null,
                                    onClick = { viewModel.setShowLatencyCounter(!settings.showLatencyCounter) },
                                ))
                                // TOOLBOX FEATURE (Stage 8, Part 2): "جودة الاتصال" — live
                                // quality switch for the *current* session via
                                // viewModel.changeSessionQuality(), wired to the seamless
                                // background-reconnect backend built in Part 1 (see
                                // changeSessionQuality()'s doc comment and
                                // docs/stage8-quality-live-change-investigation.md — a true
                                // protocol-level live change isn't supported by aFreeRDP/VNC
                                // here, so this opens a second client at the new quality in
                                // the background and swaps to it only once CONNECTED).
                                //
                                // Hidden entirely (not just disabled) rather than shown-and-
                                // failing-silently, per requirement #4:
                                //  - SSH has no framebuffer/quality concept at all (this branch
                                //    of the `when` never even reaches SessionToolbox for SSH —
                                //    see the `is SessionUiState.Connected ->` split above — but
                                //    the protocolType check is kept anyway, matching the
                                //    defensive style already used throughout this file, e.g.
                                //    around line 2138's `currentProtocol() == ProtocolType.SSH`
                                //    guard inside changeSessionQuality() itself).
                                //  - Quick Connect (s.profile.id == "__quick__") isn't wired up
                                //    yet: changeSessionQuality() re-looks-up the profile from
                                //    RdpProfileRepository by id to build the replacement
                                //    client, which only works for a saved profile row. Adding
                                //    Quick Connect support is a real (and welcome) follow-up,
                                //    but first requires routing loadAndConnectQuick()'s
                                //    frame/state collectors through attachFrameCollector()/
                                //    attachStateCollector() the same way loadAndConnect() does
                                //    today — loadAndConnectQuick() doesn't track
                                //    frameCollectorJob/stateCollectorJob yet, so swapping them
                                //    mid-session would have nothing valid to cancel/replace.
                                if (!protocolType.isTerminal && s.profile.id != "__quick__") {
                                    add(SessionTool(
                                        id = "connection_quality",
                                        icon = Icons.Outlined.HighQuality,
                                        label = context.getString(R.string.connection_quality_tool),
                                        tint = toolPulsarCyan,
                                        // Small, non-blocking "in progress" indicator (requirement
                                        // #2): overlays the Quick Bar icon itself — not a full
                                        // "Connecting…" screen — for as long as
                                        // qualityChangeInProgress is true, even after the popup
                                        // below has been dismissed.
                                        badge = if (qualityChangeInProgress) {
                                            {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(30.dp),
                                                    strokeWidth = 2.dp,
                                                    color = toolPulsarCyan,
                                                )
                                            }
                                        } else null,
                                        popupContent = { dismiss ->
                                            QualityToolPopup(
                                                // Same source settings.value.performanceLevel
                                                // Settings → Connection reads/writes — single
                                                // source of truth (requirement #1 / #3): this
                                                // always reflects what's actually running, since
                                                // changeSessionQuality() reverts it on failure.
                                                currentLevel = settings.performanceLevel,
                                                inProgress = qualityChangeInProgress,
                                                onSelect = { level -> viewModel.changeSessionQuality(level) },
                                                dismiss = dismiss,
                                            )
                                        },
                                    ))
                                }
                                // TOOLBOX FEATURE (Stage 9): "مزامنة الحافظة" — the
                                // clipboard-sync backend (ClipboardSyncManager,
                                // wired into RdpRemoteAdapter/VncClient) already
                                // exists and runs by default whenever the profile's
                                // enableClipboard is true; this tool only exposes a
                                // runtime on/off toggle for the *current* session on
                                // top of that. Hidden entirely (clipboardSyncState ==
                                // null) rather than shown-and-disabled for SSH (no
                                // clipboard channel) or a profile that disabled
                                // clipboard redirection outright — same "hide, don't
                                // fail silently" style as connection_quality above.
                                if (clipboardSyncState != null) {
                                    val syncOn = clipboardSyncState == true
                                    add(SessionTool(
                                        id = "clipboard_sync",
                                        icon = Icons.Default.ContentPaste,
                                        label = context.getString(
                                            if (syncOn) R.string.clipboard_sync_on_tool
                                            else R.string.clipboard_sync_off_tool
                                        ),
                                        tint = if (syncOn) toolPulsarCyan else null,
                                        onClick = { viewModel.setClipboardSyncEnabled(!syncOn) },
                                    ))
                                }
                                add(SessionTool(
                                    id = "disconnect",
                                    icon = Icons.Default.Close,
                                    label = context.getString(R.string.disconnect),
                                    tint = toolNovaPink,
                                    onClick = { viewModel.disconnect(); onClose() },
                                ))
                            }
                        }
                        SessionToolbox(
                            tools = toolboxTools,
                            state = toolboxState,
                            visible = !isInPip && !isBlankScreen,
                            coachMarkState = toolboxCoachMarkState,
                            statusContent = {
                                Column {
                                    Text(
                                        s.profile.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = StarDust,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "${latency}ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            latency < 100 -> PlasmaGreen
                                            latency < 300 -> ConnectingAmber
                                            else -> ErrorRed
                                        },
                                    )
                                    // CODEC-NEGOTIATION FEATURE (part 3): the RDPGFX codec
                                    // actually in use for this session (e.g. "H.264 AVC444"),
                                    // shown only once one has actually been reported — see
                                    // viewModel.negotiatedCodec's doc comment for why this
                                    // stays null (and this Text simply never appears) for
                                    // SSH/VNC and for an RDP session still on FreeRDP's
                                    // classic (non-GFX) path, so no separate protocolType
                                    // check is needed here.
                                    negotiatedCodec?.let { codec ->
                                        Text(
                                            codec,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StarDust,
                                        )
                                    }
                                    // XRDP-CAPABILITY-DETECTION FEATURE: which security
                                    // protocol (NLA/TLS/RDP) the server actually negotiated
                                    // for this connection — see
                                    // viewModel.negotiatedSecurityProtocol's doc comment.
                                    // Same "just don't show it" null-handling as
                                    // negotiatedCodec immediately above; no separate
                                    // protocolType check needed here either.
                                    negotiatedSecurityProtocol?.let { protocol ->
                                        Text(
                                            protocol,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StarDust,
                                        )
                                    }
                                    // LIVE-CHANNEL-STATUS FEATURE: small live dots for the
                                    // redirected device channels that actually have a native
                                    // ChannelConnected signal today (printer, audio playback,
                                    // mic capture) — see RemoteSessionClient.channelStatus doc
                                    // for why webcam/smartcard aren't included yet. Only drawn
                                    // when at least one of the underlying features was
                                    // requested for this profile, so a session with everything
                                    // disabled doesn't grow three permanently-dim icons under
                                    // the latency text for no reason.
                                    if (s.profile.enablePrinterRedirect || s.profile.enableSound || s.profile.enableMicRedirect ||
                                        s.profile.enableWebcamRedirect || s.profile.enableSmartcardRedirect) {
                                        ChannelStatusRow(
                                            channelStatus = channelStatus,
                                            showPrinter = s.profile.enablePrinterRedirect,
                                            showAudio = s.profile.enableSound,
                                            showMic = s.profile.enableMicRedirect,
                                            showWebcam = s.profile.enableWebcamRedirect,
                                            showSmartcard = s.profile.enableSmartcardRedirect,
                                        )
                                    }
                                }
                            },
                        )

                        // REMOTEAPP-WINDOWS FEATURE (part 2 + 3): MULTI_WINDOW mode's window
                        // switcher — one tile per open RAIL window, tapping one calls
                        // viewModel.activateRailWindow (already wired, see the ViewModel
                        // section above). This overlays on TOP of RemoteAppFreeformDesktop
                        // (see that composable's class doc comment for the actual freeform
                        // drag/resize surface, wired in the branch just above) as a
                        // quick-jump list — most useful for windows minimized
                        // (isVisible == false) or currently dragged outside the visible
                        // desktop bounds, where tapping here is faster than hunting for the
                        // tile. Dimming isVisible == false tiles was considered instead of
                        // changing what the freeform desktop draws, and IS done below (in
                        // the switcher tiles only) — a purely-additive way to surface that
                        // state without touching the freeform desktop's own dimming logic.
                        //
                        // Anchored TopCenter (statusBars inset), not BottomCenter: the
                        // virtual keyboard and ExtraKeysBar below both already claim
                        // BottomCenter (+ imePadding/navigationBarsPadding) and are shown
                        // together often enough in a RemoteApp session that stacking a
                        // third thing there would guarantee an overlap, not just risk one.
                        // TopCenter's only real neighbor is the VNC view-only banner further
                        // below, which can't co-occur with RAIL windows (VNC has no "rail"
                        // channel). It can still land under the floating SessionToolbox if a
                        // user manually drags that from its default top-*right* corner
                        // (posXFraction=1f/posYFraction=0f/dockEdge=TOP, see
                        // rememberSessionToolboxState) into top-center — same kind of
                        // pragmatic, user-fixable tradeoff as the FPS/latency overlay
                        // comment above already accepts for the fully free-floating Toolbox.
                        AnimatedVisibility(
                            visible = railDisplayMode == com.systemsgo.hex.data.model.RemoteAppDisplayMode.MULTI_WINDOW &&
                                      railWindows.isNotEmpty() && !isInPip && !isBlankScreen,
                            enter    = slideInVertically(animationSpec = tween(250)) { -it } + fadeIn(tween(250)),
                            exit     = slideOutVertically(animationSpec = tween(200)) { -it } + fadeOut(tween(200)),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .padding(top = 8.dp),
                        ) {
                            RemoteAppWindowSwitcherBar(
                                windows        = railWindows,
                                activeWindowId = railActiveWindowId,
                                onActivate     = { windowId -> viewModel.activateRailWindow(windowId) },
                            )
                        }

                        // TOOLBOX FEATURE (Stage 1): small red "● تسجيل" indicator while a
                        // video recording is in progress. Placed in the screen quadrant
                        // *opposite* the Toolbox's current floating position (rather than a
                        // fixed corner) so it doesn't sit under/behind the draggable
                        // container — a lightweight heuristic that avoids full collision
                        // geometry between two independently-positioned floating elements.
                        if (isRecording && !isInPip && !isBlankScreen) {
                            val indicatorAtTop = toolboxState.posYFraction > 0.5f
                            val indicatorAtStart = toolboxState.posXFraction > 0.5f
                            val indicatorAlignment = when {
                                indicatorAtTop && indicatorAtStart -> Alignment.TopStart
                                indicatorAtTop && !indicatorAtStart -> Alignment.TopEnd
                                !indicatorAtTop && indicatorAtStart -> Alignment.BottomStart
                                else -> Alignment.BottomEnd
                            }
                            Row(
                                modifier = Modifier
                                    .align(indicatorAlignment)
                                    .windowInsetsPadding(WindowInsets.systemBars)
                                    .padding(12.dp)
                                    .background(NovaPink.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                                    .border(1.dp, NovaPink, RoundedCornerShape(20.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(Modifier.size(8.dp).background(NovaPink, CircleShape))
                                Text(
                                    stringResource(R.string.recording_indicator),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NovaPink,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        // TOOLBOX FEATURE (Stage 3): shared across both bars below —
                        // sticky modifiers toggled from ExtraKeysBar must also be visible
                        // to (and combinable from) the virtual keyboard, and vice versa.
                        val activeModifiers by viewModel.activeModifiers.collectAsStateWithLifecycle()
                        AnimatedVisibility(
                            visible  = showVirtualKeyboard && !isInPip && !isBlankScreen,
                            enter    = slideInVertically(animationSpec = tween(250)) { it } + fadeIn(tween(250)),
                            exit     = slideOutVertically(animationSpec = tween(200)) { it } + fadeOut(tween(200)),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .imePadding()
                                .navigationBarsPadding()
                        ) {
                            SessionVirtualKeyboard(
                                layout = keyboardLayout,
                                onLayoutChange = { keyboardLayout = it },
                                onChar = { viewModel.sendTerminalText(it) },
                                onBackspace = { viewModel.sendComboKeyTap(0x0E) },
                                onEnter = { viewModel.sendComboKeyTap(0x1C) },
                                onHide = { showVirtualKeyboard = false },
                                activeModifiers = activeModifiers,
                                // Stage 12: extended is now passed through for the
                                // arrows/Del/Home/.../F-key cluster merged into the
                                // keyboard itself (all extended 0xE0 scancodes).
                                onScancodeTap = { sc, extended -> viewModel.sendComboKeyTap(sc, extended) },
                                // Stage 12: Ctrl/Alt/Shift/Win are now toggleable
                                // directly on the real keyboard, sharing the same
                                // sticky-modifier state ExtraKeysBar's row uses.
                                onToggleModifier = { id, sc, ext -> viewModel.toggleStickyModifier(id, sc, ext) },
                            )
                        }
                        // TOOLBOX FEATURE (Stage 7 follow-up): the FPS/latency overlay no
                        // longer needs ExtraKeysBar's measured height to avoid overlapping
                        // it — now that the overlay is user-draggable (CounterOverlay.kt),
                        // the user simply moves it themselves if it's ever in the way.
                        // TOOLBOX FEATURE (Stage 3 completion): ExtraKeysBar and the real
                        // virtual keyboard (Stage 2) both anchor to BottomCenter and would
                        // otherwise render on top of one another if a user had ExtraKeysBar
                        // open and then opened the virtual keyboard (or vice versa). The plan
                        // asks for ExtraKeysBar to show only alongside Android's own on-screen
                        // keyboard and disappear automatically once the real keyboard takes
                        // over — this app never actually invokes Android's system IME during a
                        // session (all input goes through scancodes/Unicode-text calls, never a
                        // focused TextField), so there is no separate "system keyboard active"
                        // signal to react to; the one condition that both matters and is
                        // knowable here is "is the real keyboard currently up", so that's what
                        // this enforces. showExtraKeys itself is untouched by this — it still
                        // reflects the user's own manual show/hide choice (and is what gets
                        // persisted/restored), so reopening it after closing the virtual
                        // keyboard needs no extra bookkeeping.
                        val extraKeysBarVisible = showExtraKeys && !isInPip && !isBlankScreen && !showVirtualKeyboard
                        AnimatedVisibility(
                            visible  = extraKeysBarVisible,
                            enter    = slideInVertically(animationSpec = tween(250)) { it } + fadeIn(tween(250)),
                            exit     = slideOutVertically(animationSpec = tween(200)) { it } + fadeOut(tween(200)),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .imePadding()
                                .navigationBarsPadding()
                        ) {
                            ExtraKeysBar(
                                onHide = {
                                    showExtraKeys = false
                                    viewModel.setSessionExtraKeysVisible(false)
                                },
                                activeModifiers = activeModifiers,
                                onToggleModifier = { id, sc, ext -> viewModel.toggleStickyModifier(id, sc, ext) },
                                onMomentaryKeyReleased = { viewModel.releaseAllStickyModifiers() },
                                onKeyEvent = { sc, dn, ext -> viewModel.sendKeyEvent(sc, dn, ext) },
                            )
                        }

                        // UX-07: View-only badge — persistent indicator when VNC
                        // is in view-only mode so users know why input is blocked.
                        // UI-FIX: was an icon+text pill; trimmed to an icon-only
                        // badge (eye icon reads clearly as "view only" on its own)
                        // so it takes up less of the screen during the session.
                        // contentDescription keeps it announced for accessibility.
                        if (protocolType == ProtocolType.VNC && s.profile.vncViewOnly && !isInPip && !isBlankScreen) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    // TOOLBOX FEATURE (Stage 0): SessionToolbox now floats and
                                    // can dock to any edge, so a fixed "toolbar is up top"
                                    // assumption no longer holds — use a constant safe offset.
                                    .padding(top = 8.dp)
                                    .background(
                                        color = SolarFlare.copy(alpha = 0.88f),
                                        shape = CircleShape
                                    )
                                    .padding(6.dp)
                            ) {
                                Icon(
                                    imageVector         = Icons.Outlined.Visibility,
                                    contentDescription  = stringResource(R.string.view_only_banner),
                                    tint                = DeepSpace,
                                    modifier            = Modifier.size(16.dp)
                                )
                            }
                        }

                        // TOOLBOX FEATURE (Stage 0): the old "small re-show handle" for
                        // SessionToolbar is gone — SessionToolbox shows its own always-visible
                        // CollapsedHandle when the user collapses it, so there's nothing to
                        // re-show separately here anymore.
                        if (!showExtraKeys && !isInPip && !isBlankScreen) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .imePadding()
                                    .navigationBarsPadding()
                                    .padding(8.dp)
                            ) {
                                SmallShowButton(
                                    icon = Icons.Default.KeyboardArrowUp,
                                    onClick = {
                                        showExtraKeys = true
                                        viewModel.setSessionExtraKeysVisible(true)
                                    }
                                )
                            }
                        }

                        // TOOLBOX FEATURE (Stage 7 follow-up): FPS and سرعة الاستجابة
                        // (latency) render through CounterOverlay.kt now — same
                        // independent show/hide per settings.showFpsCounter /
                        // showLatencyCounter as before, but the chip itself can be
                        // dragged anywhere on screen (one finger) and pinch-resized
                        // (two fingers), instead of being pinned to a fixed corner.
                        val counterOverlayState = rememberCounterOverlayState(
                            settings = settings,
                            onPositionChanged = { x, y -> viewModel.setCounterPosition(x, y) },
                            onScaleChanged = { viewModel.setCounterScale(it) },
                        )
                        if (!isInPip && !isBlankScreen) {
                            CounterOverlay(
                                state = counterOverlayState,
                                showFps = settings.showFpsCounter,
                                showLatency = settings.showLatencyCounter,
                                // PERF FIX (smoothness pass): pass the StateFlow itself —
                                // see CounterOverlay's `frameRateFlow` doc comment.
                                frameRateFlow = viewModel.frameRateMs,
                                latencyMs = latency,
                            )
                        }

                        // UX-07: Gesture hints overlay — shown once on first connection.
                        // FIX B5: تم حذف الـ LaunchedEffect الميت الذي كان يشغّل delay(500)
                        // دون أن يغير أي حالة، مما جعل التعليق "brief pause" مضللاً.
                        // الـ AnimatedVisibility + fadeIn(tween(400)) تعطي تأخيراً بصرياً كافياً.
                        var gestureHintsDismissed by remember { mutableStateOf(false) }
                        val shouldShowHints = !settings.hasShownGestureHints && !gestureHintsDismissed && !isInPip && !isBlankScreen
                        AnimatedVisibility(
                            visible  = shouldShowHints,
                            enter    = fadeIn(tween(400)),
                            exit     = fadeOut(tween(300)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            GestureHintsOverlay(
                                onDismiss = {
                                    gestureHintsDismissed = true
                                    viewModel.markGestureHintsShown()
                                }
                            )
                        }

                        // TOOLBOX FEATURE (Stage 4): the only way back from "شاشة خالية" once
                        // the Toolbox itself is hidden — a small top-corner hotspot (TopEnd is
                        // direction-aware: the phone's top-right in LTR, top-left under the
                        // app's RTL Arabic layout) that a long-press exits blank mode from.
                        // A long-press (rather than a plain tap) is used specifically so an
                        // ordinary tap/click anywhere, including this corner, still reaches the
                        // remote session exactly as it does everywhere else on the frame —
                        // only a deliberate hold interrupts remote control, and only within this
                        // small region. Suppressed in PiP: there's no Toolbox state to return to
                        // there (everything is already hidden by isInPip regardless), and a tiny
                        // floating PiP window shouldn't carry an invisible hit target of its own.
                        if (isBlankScreen && !isInPip) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .size(64.dp)
                                    .pointerInput(Unit) {
                                        detectTapGestures(onLongPress = { isBlankScreen = false })
                                    }
                            )
                            AnimatedVisibility(
                                visible = showBlankScreenHint,
                                enter = fadeIn(tween(300)),
                                exit = fadeOut(tween(400)),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .padding(top = 8.dp, end = 8.dp)
                            ) {
                                Surface(
                                    color = DeepSpace.copy(alpha = 0.85f),
                                    border = BorderStroke(1.dp, HorizonGray),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.blank_screen_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StarDust,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }

                        // ── File Transfer Dialog ───────────────────────────
                        // PIP FEATURE: hidden while floating in PiP — see the
                        // !isInPip note above showDisconnectDialog.
                        if (showFileTransfer && !isInPip) {
                            FileTransferDialog(
                                profile  = s.profile,
                                onDismiss = { showFileTransfer = false }
                            )
                        }
                    }
                }
                else -> Unit
            }
        }

        // KBD-INT FIX: rendered outside/after the AnimatedContent's `when` so it
        // overlays regardless of session state — a real keyboard-interactive
        // challenge is asked *during* SessionUiState.Connecting (auth happens
        // before CONNECTED is ever reached), not once a terminal is showing.
        val authPrompt by viewModel.authPrompt.collectAsStateWithLifecycle()
        // PIP FEATURE: suppressed while floating in PiP for the same reason as
        // showDisconnectDialog above — this only hides the dialog's Compose
        // node, it never touches viewModel.authPrompt itself, so the prompt
        // reappears instantly once the user expands back to full screen and
        // nothing about the pending auth challenge is lost.
        authPrompt?.takeIf { !isInPip }?.let { prompt ->
            SshAuthPromptDialog(
                prompt   = prompt,
                onSubmit = { viewModel.submitAuthPromptResponse(it) },
                onCancel = { viewModel.cancelAuthPrompt() },
            )
        }

        // EXTERNAL-DISPLAY FEATURE: only ever shown when 2+ displays are
        // connected at once (single-display case moves immediately without
        // asking). Suppressed in PiP for the same reason as the other
        // dialogs above.
        if (showDisplayChooser && !isInPip) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDisplayChooser = false },
                title = { Text(stringResource(R.string.external_display_choose_title)) },
                text = {
                    Column {
                        externalDisplays.forEach { info ->
                            Text(
                                info.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onMoveToExternalDisplay(info)
                                        showDisplayChooser = false
                                    }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showDisplayChooser = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // "How to use the session toolbar" spotlight — last so it draws on
        // top of everything else in this Box, including the dialogs above.
        com.systemsgo.hex.ui.coachmark.CoachMarkOverlay(
            state = toolboxCoachMarkState,
            nextLabel = stringResource(R.string.coach_mark_next),
            doneLabel = stringResource(R.string.coach_mark_done),
            skipLabel = stringResource(R.string.coach_mark_skip),
            onFinished = onToolboxSpotlightFinished,
        )
    }
}

/**
 * UNTRUSTED-CERT DIALOG FEATURE: shown whenever
 * [RdpSessionViewModel.certificateChallenge] flips non-null — i.e. the
 * server presented a certificate FreeRDP could not automatically verify
 * (self-signed, unknown CA, hostname mismatch, ...) and there is no
 * previously-pinned fingerprint for this host:port yet. The connect thread
 * (on Dispatchers.IO, not Main) is blocked on
 * [com.systemsgo.hex.remote.CertificateChallenge.awaitDecision] the entire
 * time this is shown.
 *
 * Replaces the old "Accept self-signed certificate" toggle in the
 * connection form: instead of deciding trust ahead of time with no
 * visibility into what's being trusted, the user sees the actual
 * certificate fields here and decides in the moment — the same pattern a
 * browser uses for an untrusted HTTPS certificate. "Always trust this
 * certificate" pins the fingerprint (so future connections to the same
 * host:port skip this dialog, same as before); leaving it unchecked trusts
 * this one connection only and asks again next time.
 */
@Composable
private fun CertificateTrustDialog(
    challenge: com.systemsgo.hex.remote.CertificateChallenge,
    onRespond: (com.systemsgo.hex.remote.CertificateChallenge.Decision) -> Unit,
) {
    var alwaysTrust by remember(challenge) { mutableStateOf(false) }
    val accent = NovaPink

    AlertDialog(
        onDismissRequest = { onRespond(com.systemsgo.hex.remote.CertificateChallenge.Decision.REJECT) },
        containerColor   = NebulaSurface,
        shape            = RoundedCornerShape(20.dp),
        modifier = Modifier.border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(accent.copy(alpha = 0.30f), Color.Transparent, accent.copy(alpha = 0.12f))
            ),
            shape = RoundedCornerShape(20.dp)
        ),
        icon  = { Icon(Icons.Outlined.GppMaybe, null, tint = accent, modifier = Modifier.size(32.dp)) },
        title = {
            Text(
                stringResource(R.string.cert_dialog_title),
                color      = StarDust,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.cert_dialog_message, "${challenge.host}:${challenge.port}"),
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = CometTail,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                CertDetailRow(stringResource(R.string.cert_dialog_common_name), challenge.commonName)
                CertDetailRow(stringResource(R.string.cert_dialog_issuer), challenge.issuer)
                CertDetailRow(stringResource(R.string.cert_dialog_fingerprint), challenge.fingerprint)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .clickable { alwaysTrust = !alwaysTrust }
                ) {
                    Checkbox(
                        checked = alwaysTrust,
                        onCheckedChange = { alwaysTrust = it },
                        colors = CheckboxDefaults.colors(checkedColor = PulsarCyan, uncheckedColor = HorizonGray)
                    )
                    Text(stringResource(R.string.cert_dialog_always_trust), color = CometTail, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            SpaceButton(
                text     = stringResource(R.string.cert_dialog_accept),
                onClick  = {
                    onRespond(
                        if (alwaysTrust) com.systemsgo.hex.remote.CertificateChallenge.Decision.ACCEPT_ALWAYS
                        else com.systemsgo.hex.remote.CertificateChallenge.Decision.ACCEPT_ONCE
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            TextButton(onClick = { onRespond(com.systemsgo.hex.remote.CertificateChallenge.Decision.REJECT) }) {
                Text(stringResource(R.string.cert_dialog_reject), color = CometTail)
            }
        }
    )
}

@Composable
private fun CertDetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, color = CometTail.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = StarDust, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * KBD-INT FIX: dialog shown when the SSH server (or an SSH jump host) asks a
 * real keyboard-interactive question — a TOTP code, a PAM challenge, etc. —
 * via [com.systemsgo.hex.ssh.protocol.SshClient.authPrompt] /
 * [com.systemsgo.hex.remote.SshTunneledClient.authPrompt].
 *
 * One text field is rendered per [SshInteractivePrompt.prompts] entry, in the
 * server's requested order; fields with `echo=false` (e.g. a verification
 * code) are masked the same way a password field is, while `echo=true` fields
 * (e.g. a numbered menu choice) are shown in plain text.
 */
@Composable
private fun SshAuthPromptDialog(
    prompt: SshInteractivePrompt,
    onSubmit: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    // SECURITY FIX: this dialog collects SSH passwords/passphrases/OTP codes —
    // unlike the surrounding session view (which stays recordable on purpose),
    // this prompt itself is protected. See security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()
    // Keyed on the prompt instance so a brand-new challenge (e.g. the server
    // re-prompts after a wrong code) starts with empty fields rather than
    // whatever was left over from the previous attempt.
    val answers = remember(prompt) {
        androidx.compose.runtime.mutableStateListOf(*Array(prompt.prompts.size) { "" })
    }
    // UI-FIX (design feedback): this was still the stock, unthemed AlertDialog —
    // a plain light Material dialog over the dark space UI, asking for a
    // password/OTP no less, which made it stand out for the wrong reason.
    // Restyled to match the other themed dialogs in the app (dark surface,
    // rounded corners, StarDust/CometTail text, accent-tinted text fields).
    val accent = SolarFlare
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor   = NebulaSurface,
        shape            = RoundedCornerShape(20.dp),
        modifier = Modifier.border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(accent.copy(alpha = 0.30f), Color.Transparent, accent.copy(alpha = 0.12f))
            ),
            shape = RoundedCornerShape(20.dp)
        ),
        icon  = { Icon(Icons.Outlined.Security, null, tint = accent, modifier = Modifier.size(32.dp)) },
        title = {
            Text(
                prompt.name.ifBlank { stringResource(R.string.ssh_auth_prompt_default_title) },
                color      = StarDust,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                // MULTIHOP FIX (UI): when this challenge came from a hop partway
                // through a ProxyJump chain (hopCount > 1 — see
                // SshInteractivePrompt's doc comment), show which hop is asking
                // before the server's own instruction text, e.g. "Hop 2 of 3 —
                // user@10.0.0.5". Omitted entirely for the ordinary single-hop/
                // direct-SSH case (hopCount == 1) so nothing changes there.
                if (prompt.hopCount > 1) {
                    Text(
                        stringResource(
                            R.string.ssh_auth_prompt_hop_indicator,
                            prompt.hopIndex,
                            prompt.hopCount,
                            prompt.hopLabel
                        ),
                        style      = MaterialTheme.typography.labelMedium,
                        color      = SolarFlare,
                        fontWeight = FontWeight.Bold,
                        modifier   = Modifier.padding(bottom = 6.dp)
                    )
                }
                if (prompt.instruction.isNotBlank()) {
                    Text(
                        prompt.instruction,
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = CometTail,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                prompt.prompts.forEachIndexed { index, field ->
                    OutlinedTextField(
                        value = answers.getOrElse(index) { "" },
                        onValueChange = { answers[index] = it },
                        label = {
                            Text(field.text.ifBlank { stringResource(R.string.ssh_auth_prompt_default_title) }, color = CometTail)
                        },
                        singleLine = true,
                        visualTransformation = if (!field.echo) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        keyboardOptions = if (!field.echo)
                            KeyboardOptions(keyboardType = KeyboardType.Password)
                        else
                            KeyboardOptions.Default,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = accent,
                            unfocusedBorderColor = HorizonGray.copy(alpha = 0.4f),
                            focusedTextColor     = StarDust,
                            unfocusedTextColor   = StarDust,
                            cursorColor          = accent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (index > 0) 8.dp else 0.dp)
                    )
                }
            }
        },
        confirmButton = {
            SpaceButton(
                text     = stringResource(R.string.ssh_auth_prompt_submit),
                // I18N-FIX: normalize Arabic-Indic/Extended Arabic-Indic digits
                // to ASCII before submitting. A verification-code prompt (TOTP/
                // OTP) is almost always validated by the server as ASCII
                // digits, so a code typed via an Arabic-locale IME needs this
                // conversion; other characters (e.g. a PAM menu answer) are
                // left untouched.
                onClick  = { onSubmit(answers.map { it.normalizeDigits() }) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = CometTail) }
        }
    )
}

@Composable
private fun SmallShowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val bg = DeepSpace
    val accent = PulsarCyan
    Surface(
        color    = bg.copy(alpha = 0.8f),
        shape    = RoundedCornerShape(8.dp),
        // BUGFIX-UI-5: الـ Surface بدون حجم محدد كان يتقلص لحجم محتواه —
        // أيقونة 28dp ناقص padding 4dp = ~20dp منطقة لمس فعلية فقط، وهو ما
        // يجعل إعادة إظهار الـ Toolbar صعبة. size(40.dp) يضمن منطقة لمس مريحة.
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick)
    ) {
        Icon(
            icon,
            contentDescription = stringResource(R.string.cd_toggle_toolbar),
            tint     = accent,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RDP Canvas — uses Compose Canvas + android.graphics for bitmap drawing
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RdpCanvas(
    // PERF FIX (smoothness pass): this used to be a plain `bitmap: Bitmap?`
    // parameter, whose value was read by the *caller* (RdpSessionScreen) via
    // `val frameBitmap by viewModel.frameBitmap.collectAsStateWithLifecycle()`
    // at that giant composable's own top-level scope. Since a live remote
    // session emits a new frame 15-60 times per second, that read invalidated
    // RdpSessionScreen's entire ~2000-line recompose scope every single
    // frame — toolbar, dialogs, keyboard, toolbox tool list and all — even
    // though only this Canvas actually needs the new pixels. Accepting the
    // StateFlow itself and collecting it *inside* RdpCanvas moves that
    // high-frequency state read into this composable's own (much smaller)
    // recompose scope, so a new frame now only re-runs this function instead
    // of the whole session screen. Call sites pass `viewModel.frameBitmap`
    // directly instead of a pre-collected value.
    bitmapFlow: StateFlow<Bitmap?>,
    screenWidth: Int,
    screenHeight: Int,
    cursorStyle: String = "default",
    cursorSize: Int = 24,
    showCursor: Boolean = true,
    touchpadSensitivity: Float = 1f,    // ✅ حساسية التوباد
    scrollSensitivity: Float = 1f,      // ✅ حساسية التمرير
    rightClickLongPress: Boolean = true, // ✅ كليك أيمن بالضغط المطوّل
    // TOOLBOX FEATURE (Stage 5): "قلب الشاشة" — purely local mirroring of the
    // drawn frame (see ScreenFlipMode). All touch/mouse delta math below is
    // adjusted by flipMode.flipX/flipY so the coordinates sent to the remote
    // device stay correct in every one of the four states.
    flipMode: ScreenFlipMode = ScreenFlipMode.NORMAL,
    // TOOLBOX FEATURE (Stage 6a): "وضع الماوس/تاتش باد" — TOUCHPAD keeps the
    // pre-existing relative/delta behaviour below untouched; DIRECT maps the
    // finger's raw position straight to the corresponding remote pixel (see
    // localPointToRemote below) on every Press/Move, like a real touchscreen.
    mouseInputMode: MouseInputMode = MouseInputMode.TOUCHPAD,
    onMouseMove:  (Int, Int) -> Unit,
    onMouseClick: (Int, Int, RemoteMouseButton, Boolean) -> Unit,
    onScroll:     (Int, Int, Int) -> Unit,
    // MULTITOUCH FEATURE: fired once per Compose pointer-event batch while
    // mouseInputMode == MULTITOUCH, with every currently down/changed
    // finger's own contact (already mapped to remote-pixel space via
    // localPointToRemote) — see RemoteTouchContact's doc comment. Default no-op
    // keeps every existing call site (TOUCHPAD/DIRECT sessions, and any
    // protocol whose bridge doesn't wire this up) compiling unchanged,
    // matching onViewportSettled/onHardwareKeyEvent's pattern above.
    onMultiTouchFrame: (List<RemoteTouchContact>) -> Unit = {},
    // HIRES-ZOOM FEATURE: fired a short moment after the user finishes a
    // pinch/pan gesture (all fingers lifted). Wired to a best-effort request
    // for a fresh, non-incremental frame from the remote protocol so the
    // now-settled viewport renders from a guaranteed-complete framebuffer
    // instead of whatever partial incremental updates arrived mid-gesture.
    // Default is a no-op so existing call sites keep compiling unchanged.
    onViewportSettled: () -> Unit = {},
    // EXTERNAL-DISPLAY / DEX FEATURE: physical/Bluetooth keyboard support —
    // forwarded as PC scan codes via HardwareKeyMap, same sink as
    // ExtraKeysBar's on-screen buttons. Default no-op keeps every existing
    // call site (which has no keyboard to offer) compiling unchanged.
    onHardwareKeyEvent: (scanCode: Int, down: Boolean, extended: Boolean) -> Unit = { _, _, _ -> },
    // REMOTEAPP-WINDOWS FEATURE: when non-null, draws only this sub-region of
    // `bitmapFlow`'s composited-desktop bitmap, scaled to fill the canvas —
    // RemoteAppDisplayMode.SINGLE_WINDOW's "full-screen crop of the active
    // window, no local title bar, no desktop background" (see
    // RailWindow.rect's doc comment for why that crop is meaningful: the
    // frame buffer is one flat bitmap of the whole RAIL desktop with no
    // per-window boundaries of its own). Null (the default) draws the whole
    // bitmap exactly as before — every existing call site is unaffected.
    //
    // COORDINATE CONTRACT: this composable's own gesture/zoom/pan math is
    // completely unaware of the crop — `screenWidth`/`screenHeight` are
    // expected to already equal `cropRect`'s width/height (not the full
    // remote desktop's) whenever `cropRect` is set, so cursor position and
    // gesture-to-remote-point mapping stay correct in this cropped "logical
    // screen" space. It is the caller's job (RdpSessionScreen) to then add
    // cropRect.left/cropRect.top back onto the x/y this composable reports
    // through onMouseMove/onMouseClick/onScroll before forwarding them to the
    // remote session — those callbacks are otherwise untouched here.
    cropRect: android.graphics.Rect? = null,
    modifier: Modifier = Modifier
) {
    // PERF FIX (smoothness pass): collected here, not by the caller — see the
    // doc comment on `bitmapFlow` above. This is the only per-frame state
    // read in the whole call chain now; it recomposes just this composable.
    val bitmap by bitmapFlow.collectAsStateWithLifecycle()
    // REMOTEAPP-WINDOWS FIX: keyed on (screenWidth, screenHeight) — see
    // cropRect's doc comment above — so cursorX/cursorY reset to center
    // whenever the "logical screen" size changes: toggling SINGLE_WINDOW ↔
    // MULTI_WINDOW, or switching which window is active in SINGLE_WINDOW,
    // both change it via cropRect. Previously this `remember` had no key,
    // so the cursor could briefly render at an off-center/stale position
    // (left over from the old logical-screen size) until the next real
    // move event corrected it — this key makes the reset immediate instead
    // of waiting on that next event. Doesn't intersect with the
    // MULTI_WINDOW switcher bar (part 2) — the switcher only calls
    // activateRailWindow, it doesn't touch cropRect/screenWidth/
    // screenHeight itself in MULTI_WINDOW mode (railCropRect stays null in
    // that mode), so this key simply never fires there.
    var cursorX      by remember(screenWidth, screenHeight) { mutableStateOf(screenWidth / 2f) }
    var cursorY      by remember(screenWidth, screenHeight) { mutableStateOf(screenHeight / 2f) }
    // HIRES-ZOOM FEATURE: rememberSaveable (not plain remember) so the zoom
    // level and pan offset survive device rotation / foldable state changes
    // / Samsung DeX window resizing — anything that goes through
    // Activity.onConfigurationChanged rather than a full recreate — without
    // needing any extra plumbing through the ViewModel. This directly
    // addresses "preserve zoom level and viewport during screen updates and
    // device rotation" and avoids the jarring reset a plain `remember` would
    // cause once the composable is torn down and rebuilt.
    var scale        by rememberSaveable { mutableStateOf(1f) }
    var offsetX      by rememberSaveable { mutableStateOf(0f) }
    var offsetY      by rememberSaveable { mutableStateOf(0f) }
    var lastPtrCount by remember { mutableStateOf(0) }
    // FIX #4: Track whether the finger has dragged past a threshold since
    // Press so that Release does not fire a spurious LEFT click after a pan.
    var hasDragged   by remember { mutableStateOf(false) }
    // EXTERNAL-DISPLAY / DEX FEATURE: set when a physical mouse's right/middle
    // button was the one pressed, so the Release handler below (which fires a
    // LEFT click whenever no pointer remains "pressed") doesn't also send a
    // spurious extra LEFT click for the same physical button-up.
    var suppressNextLeftClick by remember { mutableStateOf(false) }
    var dragAccumX   by remember { mutableStateOf(0f) }
    var dragAccumY   by remember { mutableStateOf(0f) }
    val DRAG_THRESHOLD_PX = 8f * LocalDensity.current.density  // ~8 dp

    val backgroundColor  = DeepSpace
    val cursorThemeColor = LocalSpaceColors.current.cursorColor

    // BUG-M4 FIX: capture mutable parameters as updatable State so the
    // pointerInput(Unit) coroutine (which never restarts) always reads the
    // latest values without needing its key list to change.
    val scrollSensitivityRef = rememberUpdatedState(scrollSensitivity)
    val onScrollRef          = rememberUpdatedState(onScroll)
    val onViewportSettledRef = rememberUpdatedState(onViewportSettled)
    // TOOLBOX FEATURE (Stage 5): read inside the pointerInput(Unit) gesture
    // detector below (which never restarts), same rememberUpdatedState
    // pattern as the refs above (BUG-M4 FIX), so a live flip-mode change
    // takes effect immediately without needing to lift a finger first.
    val flipModeRef          = rememberUpdatedState(flipMode)
    // MULTITOUCH FEATURE: same rationale — read inside the detectTransformGestures
    // block further down, which lives in the pointerInput(Unit) modifier
    // (never restarts), so a live mode switch must go through a ref too.
    val mouseInputModeRef     = rememberUpdatedState(mouseInputMode)

    val cursorBitmap = remember(cursorStyle, cursorSize, cursorThemeColor) {
        com.systemsgo.hex.ui.components.buildCursorBitmap(cursorStyle, cursorSize, cursorThemeColor).asImageBitmap()
    }
    val cursorPxSize = cursorBitmap.width.toFloat()

    // HIRES-ZOOM FEATURE: how far the user may zoom in scales with the
    // remote desktop's own resolution. A 4K/2K session has enough real
    // source detail to stay legible far past the old fixed 4x ceiling,
    // while a low-res session gains nothing from extra zoom beyond making
    // already-blocky pixels blockier — so it keeps the original limit.
    // This is purely a client-side viewport limit; it never changes what
    // resolution is requested from the remote host.
    val maxScale = remember(screenWidth, screenHeight) {
        when (maxOf(screenWidth, screenHeight)) {
            in 3840..Int.MAX_VALUE -> 8f   // 4K and above
            in 2560..3839          -> 6f   // 2K / 1440p
            in 1920..2559          -> 5f   // Full HD
            else                   -> 4f
        }
    }
    val minScale = 0.5f

    // Size (in px) of the Box hosting the canvas — needed outside of the
    // draw phase so pan clamping (below) can be computed from the same
    // gesture-handling code that updates offsetX/offsetY, without waiting
    // for a draw pass.
    var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }
    val contentAspect = remember(screenWidth, screenHeight) {
        if (screenWidth > 0 && screenHeight > 0) screenWidth.toFloat() / screenHeight.toFloat() else 1f
    }

    // Aspect-correct letterbox size the frame is drawn at when scale == 1,
    // shared by the draw phase and the pan-clamping logic below so both
    // agree on where the content's true edges are.
    fun letterboxSize(canvasW: Float, canvasH: Float): androidx.compose.ui.geometry.Size {
        if (canvasW <= 0f || canvasH <= 0f) return androidx.compose.ui.geometry.Size.Zero
        val canvasAspect = canvasW / canvasH
        return if (contentAspect > canvasAspect) {
            androidx.compose.ui.geometry.Size(canvasW, canvasW / contentAspect)
        } else {
            androidx.compose.ui.geometry.Size(canvasH * contentAspect, canvasH)
        }
    }

    // HIRES-ZOOM FEATURE: keeps panning inside sane bounds so a pinch/drag
    // can never fling the whole remote screen out of view — the "accidental
    // jumps or viewport resets" this feature is required to avoid. Once the
    // scaled content is smaller than the viewport on an axis, that axis is
    // pinned to 0 (centered) rather than allowed to drift.
    fun clampPan(x: Float, y: Float, atScale: Float): Pair<Float, Float> {
        val cw = canvasSizePx.width.toFloat()
        val ch = canvasSizePx.height.toFloat()
        if (cw <= 0f || ch <= 0f) return 0f to 0f
        val letterbox = letterboxSize(cw, ch)
        val scaledW = letterbox.width * atScale
        val scaledH = letterbox.height * atScale
        val maxX = ((scaledW - cw) / 2f).coerceAtLeast(0f)
        val maxY = ((scaledH - ch) / 2f).coerceAtLeast(0f)
        return x.coerceIn(-maxX, maxX) to y.coerceIn(-maxY, maxY)
    }

    // TOOLBOX FEATURE (Stage 6a): "ماوس مباشر" (absolute/direct mode) — maps
    // a touch's raw position (this Box's own screen-pixel space, exactly
    // what pointerInput reports) to the matching remote pixel, by algebraically
    // inverting the same transform the draw phase below uses to *place* the
    // frame: clipRect → translate(offsetX, offsetY) → scale(scale*flipX/Y,
    // pivot=canvas center), followed by the letterboxed dst rect from
    // letterboxSize(). Deriving it as the exact inverse (rather than a
    // separate approximation) is what keeps a direct-mode tap landing under
    // the finger at any zoom level, pan offset, or ScreenFlipMode — the
    // stage's "لا يصير النقر بمكان خاطئ" requirement.
    fun localPointToRemote(touch: Offset): Offset {
        val cw = canvasSizePx.width.toFloat()
        val ch = canvasSizePx.height.toFloat()
        if (cw <= 0f || ch <= 0f || screenWidth <= 0 || screenHeight <= 0) {
            return Offset(cursorX, cursorY)
        }
        val pivotX = cw / 2f
        val pivotY = ch / 2f
        // Undo translate(offsetX, offsetY) then scale(scale*flip, pivot=center).
        val localX = pivotX + (touch.x - offsetX - pivotX) / (scale * flipMode.flipX)
        val localY = pivotY + (touch.y - offsetY - pivotY) / (scale * flipMode.flipY)
        // Undo the letterbox placement to land in the remote frame's own
        // 0..screenWidth / 0..screenHeight space.
        val letterbox = letterboxSize(cw, ch)
        if (letterbox.width <= 0f || letterbox.height <= 0f) return Offset(cursorX, cursorY)
        val dstX = (cw - letterbox.width) / 2f
        val dstY = (ch - letterbox.height) / 2f
        val remoteX = (localX - dstX) / letterbox.width * screenWidth
        val remoteY = (localY - dstY) / letterbox.height * screenHeight
        return Offset(
            remoteX.coerceIn(0f, screenWidth.toFloat()),
            remoteY.coerceIn(0f, screenHeight.toFloat())
        )
    }

    // Re-validate the current zoom/pan whenever the remote resolution or the
    // local viewport size changes (rotation, DeX window resize, external
    // display attach/detach, orientation-driven aspect change, etc.). This
    // only *clamps* the existing values back into range — it never resets
    // scale to 1 or offset to 0 — so the user's chosen zoom/viewport is
    // preserved across these events whenever it's still valid.
    LaunchedEffect(screenWidth, screenHeight, canvasSizePx) {
        val clampedScale = scale.coerceIn(minScale, maxScale)
        val (cx, cy) = clampPan(offsetX, offsetY, clampedScale)
        scale = clampedScale
        offsetX = cx
        offsetY = cy
    }

    // HIRES-ZOOM FEATURE: true while a 2+ finger transform gesture is in
    // progress, used to know (on Release) whether a fresh remote frame
    // should be requested once the viewport settles.
    var hadMultiTouch by remember { mutableStateOf(false) }
    var settleTrigger by remember { mutableStateOf(0) }

    // Debounced settle notification: every new gesture-end bumps
    // settleTrigger, which cancels any pending wait from a previous
    // gesture-end and restarts it — so rapid successive pinches only fire
    // onViewportSettled once, shortly after the user actually stops.
    LaunchedEffect(settleTrigger) {
        if (settleTrigger > 0) {
            delay(120L)
            onViewportSettledRef.value()
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { canvasSizePx = it }
            .hardwareKeyboardInput { sc, down, ext ->
                onHardwareKeyEvent(sc, down, ext)
            }
            .pointerInput(Unit) {
                // BUG-M4 FIX: previously both this handler (via pan update) AND the
                // manual awaitPointerEventScope below (via onScroll) fired simultaneously
                // on every 2-finger event — the local canvas panned AND a remote scroll
                // was sent at the same time, causing erratic behavior.
                // Fix: distinguish intent by zoom magnitude.
                //  • Significant spread/pinch (|zoom−1| > 1.5%) → pan the local view.
                //  • Pure 2-finger swipe (zoom ≈ 1) → send remote scroll only.
                // The manual handler's "2 ->" scroll case is removed to prevent the double-fire.
                detectTransformGestures { _, pan, zoom, _ ->
                    // MULTITOUCH FEATURE: in MULTITOUCH mode every finger is
                    // forwarded to the remote as its own raw RDPEI contact
                    // (see the dedicated awaitPointerEventScope block below)
                    // instead of being consumed here to pinch/pan *this*
                    // local viewport — a remote drawing app's own pinch
                    // gesture needs to reach it as two real touch points,
                    // not get eaten by local zoom.
                    if (mouseInputModeRef.value == MouseInputMode.MULTITOUCH) return@detectTransformGestures
                    if (lastPtrCount >= 2) {
                        hadMultiTouch = true
                        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                        if (kotlin.math.abs(zoom - 1f) > 0.015f) {
                            // Pinch-zoom: pan the local canvas view, clamped so the
                            // content can never be dragged fully out of the viewport.
                            val (cx, cy) = clampPan(offsetX + pan.x, offsetY + pan.y, newScale)
                            offsetX = cx
                            offsetY = cy
                        } else if (pan.y != 0f) {
                            // Pure 2-finger swipe → forward as remote scroll.
                            // TOOLBOX FEATURE (Stage 5): scaled by flipY so a
                            // vertical mirror/180° flip doesn't invert the
                            // gesture without the rest of the coordinate math
                            // (the local pan.y direction is reversed relative
                            // to the mirrored frame, so the remote scroll
                            // direction must be reversed to match).
                            val flipY = flipModeRef.value.flipY
                            val steps = (pan.y / 8f * scrollSensitivityRef.value * flipY).toInt()
                            val delta = when {
                                steps != 0        -> (-steps).coerceIn(-5, 5)
                                pan.y * flipY > 0  -> -1
                                else               -> 1
                            }
                            onScrollRef.value(cursorX.toInt(), cursorY.toInt(), delta)
                        }
                        scale = newScale
                    }
                }
            }
            // ✅ كليك أيمن بالضغط المطوّل
            .pointerInput(rightClickLongPress) {
                if (rightClickLongPress) {
                    detectTapGestures(
                        onLongPress = { _ ->
                            onMouseClick(cursorX.toInt(), cursorY.toInt(), RemoteMouseButton.RIGHT, true)
                            onMouseClick(cursorX.toInt(), cursorY.toInt(), RemoteMouseButton.RIGHT, false)
                        }
                    )
                }
            }
            // TOOLBOX FEATURE (Stage 5): flipMode added to the key list so
            // this block (and its captured flipX/flipY below) restarts with
            // fresh values the moment the user changes the flip mode.
            // TOOLBOX FEATURE (Stage 6a): mouseInputMode added for the same
            // reason — switching touchpad/direct mid-session must take
            // effect on the very next touch, not the next finger-up.
            .pointerInput(touchpadSensitivity, scrollSensitivity, flipMode, mouseInputMode) {
                // TOOLBOX FEATURE (Stage 5): local coordinate deltas must be
                // reversed on the mirrored axis/axes before they're applied
                // to cursorX/cursorY (which are always in *remote* screen
                // space) — otherwise a flipped display would send the mouse
                // in the wrong direction relative to what the user sees.
                val flipX = flipMode.flipX
                val flipY = flipMode.flipY
                awaitPointerEventScope {
                    while (true) {
                        val event    = awaitPointerEvent()
                        val active   = event.changes.filter { it.pressed }
                        lastPtrCount = active.size

                        // MULTITOUCH FEATURE: raw passthrough branch — every
                        // finger's own contact (not just one synthesized
                        // pointer) is mapped to remote-pixel space and handed
                        // to the caller as a single frame, then this event is
                        // fully consumed and skipped by the rest of the
                        // TOUCHPAD/DIRECT single-pointer logic below (which
                        // only makes sense for those two modes).
                        if (mouseInputMode == MouseInputMode.MULTITOUCH) {
                            val contacts = event.changes.mapNotNull { change ->
                                val phase = when {
                                    change.pressed && !change.previousPressed  -> TouchPhase.DOWN
                                    change.pressed &&  change.previousPressed  -> TouchPhase.MOVE
                                    !change.pressed &&  change.previousPressed -> TouchPhase.UP
                                    else -> return@mapNotNull null
                                }
                                val mapped = localPointToRemote(change.position)
                                RemoteTouchContact(
                                    id = change.id.value.toInt(),
                                    x = mapped.x.toInt(),
                                    y = mapped.y.toInt(),
                                    phase = phase
                                )
                            }
                            if (contacts.isNotEmpty()) onMultiTouchFrame(contacts)
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        when (event.type) {
                            PointerEventType.Press -> {
                                // FIX #4: Reset drag tracking on every new touch-down
                                hasDragged = false
                                dragAccumX = 0f
                                dragAccumY = 0f
                                // TOOLBOX FEATURE (Stage 6a): "ماوس مباشر" — snap the remote
                                // cursor to the tap position immediately on touch-down. A
                                // plain tap with no movement never produces a Move event, so
                                // without this a direct-mode tap would click wherever the
                                // cursor happened to already be, not where the finger landed.
                                if (mouseInputMode == MouseInputMode.DIRECT) {
                                    event.changes.firstOrNull()?.let { pressChange ->
                                        val mapped = localPointToRemote(pressChange.position)
                                        cursorX = mapped.x
                                        cursorY = mapped.y
                                        onMouseMove(cursorX.toInt(), cursorY.toInt())
                                    }
                                }
                                // EXTERNAL-DISPLAY / DEX FEATURE: a physical mouse's right/middle
                                // button reports here as event.buttons rather than through the
                                // long-press gesture used for touch (rightClickLongPress above) —
                                // handle it directly so a real mouse's right-click just works.
                                val change = event.changes.firstOrNull()
                                if (change != null && change.type == PointerType.Mouse) {
                                    when {
                                        event.buttons.isSecondaryPressed -> {
                                            suppressNextLeftClick = true
                                            onMouseClick(cursorX.toInt(), cursorY.toInt(), RemoteMouseButton.RIGHT, true)
                                            onMouseClick(cursorX.toInt(), cursorY.toInt(), RemoteMouseButton.RIGHT, false)
                                        }
                                        event.buttons.isTertiaryPressed -> {
                                            suppressNextLeftClick = true
                                            onMouseClick(cursorX.toInt(), cursorY.toInt(), RemoteMouseButton.MIDDLE, true)
                                            onMouseClick(cursorX.toInt(), cursorY.toInt(), RemoteMouseButton.MIDDLE, false)
                                        }
                                    }
                                }
                            }
                            PointerEventType.Move -> {
                                when (active.size) {
                                    1 -> {
                                        if (mouseInputMode == MouseInputMode.DIRECT) {
                                            // TOOLBOX FEATURE (Stage 6a): "ماوس مباشر" — the cursor
                                            // tracks the finger's mapped position 1:1 (absolute),
                                            // not a relative delta. touchpadSensitivity does not
                                            // apply here — a physical touchscreen has no concept
                                            // of "sensitivity", the finger IS the pointer.
                                            val mapped = localPointToRemote(active[0].position)
                                            cursorX = mapped.x
                                            cursorY = mapped.y
                                            onMouseMove(cursorX.toInt(), cursorY.toInt())
                                        } else {
                                            // ✅ حساسية التوباد مطبَّقة
                                            // TOOLBOX FEATURE (Stage 5): * flipX / flipY reverses the
                                            // delta on the mirrored axis/axes (see comment above).
                                            val dx = (active[0].position.x - active[0].previousPosition.x) * 2f * touchpadSensitivity * flipX
                                            val dy = (active[0].position.y - active[0].previousPosition.y) * 2f * touchpadSensitivity * flipY
                                            cursorX = (cursorX + dx).coerceIn(0f, screenWidth.toFloat())
                                            cursorY = (cursorY + dy).coerceIn(0f, screenHeight.toFloat())
                                            onMouseMove(cursorX.toInt(), cursorY.toInt())
                                        }
                                        // FIX #4: accumulate raw displacement to detect intentional drags
                                        dragAccumX += kotlin.math.abs(active[0].position.x - active[0].previousPosition.x)
                                        dragAccumY += kotlin.math.abs(active[0].position.y - active[0].previousPosition.y)
                                        if (dragAccumX > DRAG_THRESHOLD_PX || dragAccumY > DRAG_THRESHOLD_PX) {
                                            hasDragged = true
                                        }
                                    }
                                    // BUG-M4 FIX: 2-finger scroll removed from here.
                                    // detectTransformGestures above now handles it exclusively,
                                    // distinguishing scroll (zoom≈1) from pinch-zoom (|zoom-1|>1.5%).
                                    // Having both fire on the same event caused local-view pan AND
                                    // remote scroll to trigger simultaneously.
                                    0 -> {
                                        // EXTERNAL-DISPLAY / DEX FEATURE: a physical mouse reports hover
                                        // Move events with no finger/button "pressed" at all (touch can't
                                        // do this — there's no contact). Reuse the exact same
                                        // relative-displacement formula as the 1-finger case above so a
                                        // connected mouse (DeX dock, USB/Bluetooth mouse, external
                                        // display setup) moves the remote cursor smoothly on hover,
                                        // without needing to press any button first.
                                        val change = event.changes.firstOrNull()
                                        if (change != null && change.type == PointerType.Mouse) {
                                            // TOOLBOX FEATURE (Stage 5): same flipX/flipY reversal as
                                            // the single-finger touchpad case above.
                                            val dx = (change.position.x - change.previousPosition.x) * 2f * touchpadSensitivity * flipX
                                            val dy = (change.position.y - change.previousPosition.y) * 2f * touchpadSensitivity * flipY
                                            if (dx != 0f || dy != 0f) {
                                                cursorX = (cursorX + dx).coerceIn(0f, screenWidth.toFloat())
                                                cursorY = (cursorY + dy).coerceIn(0f, screenHeight.toFloat())
                                                onMouseMove(cursorX.toInt(), cursorY.toInt())
                                            }
                                        }
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                            // EXTERNAL-DISPLAY / DEX FEATURE: scroll wheel on a physical mouse —
                            // touch gestures never produce this event type (their 2-finger scroll
                            // is handled by detectTransformGestures above instead).
                            PointerEventType.Scroll -> {
                                // TOOLBOX FEATURE (Stage 5): * flipY so a physical mouse wheel
                                // scrolls the expected direction relative to the mirrored frame.
                                val rawDelta = (event.changes.firstOrNull()?.scrollDelta?.y ?: 0f) * flipY
                                if (rawDelta != 0f) {
                                    val steps = (-rawDelta * scrollSensitivity).toInt().let {
                                        if (it == 0) (if (rawDelta > 0) -1 else 1) else it
                                    }
                                    onScroll(cursorX.toInt(), cursorY.toInt(), steps.coerceIn(-5, 5))
                                }
                                event.changes.forEach { it.consume() }
                            }
                            PointerEventType.Release -> {
                                // FIX #4: Only fire LEFT click if the finger did NOT drag past
                                // the threshold. Before this fix, any pan followed by lifting
                                // the finger would generate an unintended click on the remote.
                                if (active.isEmpty() && !hasDragged && !suppressNextLeftClick) {
                                    onMouseClick(cursorX.toInt(), cursorY.toInt(), RemoteMouseButton.LEFT, true)
                                    onMouseClick(cursorX.toInt(), cursorY.toInt(), RemoteMouseButton.LEFT, false)
                                }
                                suppressNextLeftClick = false
                                // HIRES-ZOOM FEATURE: all fingers up after a pinch/pan —
                                // (debounced) ask the caller to request a fresh remote frame
                                // so the settled viewport shows a fully up-to-date image.
                                if (active.isEmpty() && hadMultiTouch) {
                                    hadMultiTouch = false
                                    settleTrigger++
                                }
                                hasDragged = false
                                dragAccumX = 0f
                                dragAccumY = 0f
                            }
                            else -> {}
                        }
                    }
                }
            }
    ) {
        // HIRES-ZOOM FEATURE / GPU-ZOOM FIX (item #12): the pinch/pan transform
        // is now applied via Modifier.graphicsLayer — a RenderNode-backed layer
        // the GPU compositor can rescale/translate on its own, without Compose
        // re-running this draw lambda (and therefore without re-blitting the
        // decoded bitmap) on every gesture frame. This replaces the previous
        // DrawScope.withTransform approach, which re-executed the full
        // clipRect/translate/scale/drawImage sequence — i.e. a full CPU-side
        // redraw — on every scale/offsetX/offsetY change, showing up as lower
        // FPS and higher battery draw while pinch-zooming, especially on large
        // (2K/4K) remote frames. graphicsLayer's parameter overload (rather
        // than the lambda-block `graphicsLayer { ... }` form) is used here —
        // see the BUILD-FIX history below for why the lambda form was avoided
        // in this file.
        //
        // Transform equivalence with the old code: the old withTransform block
        // did translate(offsetX, offsetY) THEN scale(pivot = canvas center) —
        // DrawScope transforms compose so the pivot is evaluated in the
        // already-translated space, giving
        //   p_screen = center + scale·(p_content − center) + (offsetX, offsetY).
        // graphicsLayer applies scaleX/scaleY around transformOrigin (default:
        // the layer's own center) and then adds translationX/Y in the parent's
        // (untransformed) coordinate space — the exact same formula. So
        // translationX = offsetX / translationY = offsetY reproduces the old
        // visual result exactly; clip = true replaces the old clipRect() call.
        //
        // BUILD-FIX NOTE (superseded): graphicsLayer was tried once before via
        // its lambda-block overload and failed to resolve at compile time in
        // CI, which is why withTransform was used as a fallback. This uses the
        // plain-parameter overload instead (scaleX =, translationX =, etc.),
        // which needs no new import (androidx.compose.ui.draw.* already
        // covers it) and is the long-stable, non-lambda form — if this
        // specific overload ever regresses in CI again, withTransform above
        // is the known-safe fallback to revert to.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX       = scale * flipMode.flipX,
                    scaleY       = scale * flipMode.flipY,
                    translationX = offsetX,
                    translationY = offsetY,
                    clip         = true
                )
        ) {
            // BUGFIX-UI: previously drew the frame with
            // dstSize = IntSize(size.width, size.height), stretching it to
            // exactly fill the canvas regardless of the remote screen's real
            // aspect ratio — visibly distorting text and UI elements whenever
            // the session resolution's ratio didn't match the phone canvas's
            // ratio (e.g. a 4:3 or 16:10 remote desktop on a ~20:9 phone).
            // We now compute a letterboxed destination rect that preserves the
            // source aspect ratio, same as every other remote-desktop client.
            val dstW: Float
            val dstH: Float
            val dstX: Float
            val dstY: Float
            // REMOTEAPP-WINDOWS FEATURE: source rect defaults to the whole
            // bitmap (pre-existing behavior). When cropRect is set, clamp it
            // against the bitmap's actual bounds first — a rail window's
            // reported rect can momentarily be stale relative to whatever
            // frame has actually arrived (e.g. a server-side resize order lands
            // a moment before/after the frame reflecting it), and an
            // out-of-bounds srcOffset/srcSize would throw in drawImage.
            val srcW = bitmap?.let { bmp -> cropRect?.width()?.coerceIn(1, bmp.width) ?: bmp.width } ?: 0
            val srcH = bitmap?.let { bmp -> cropRect?.height()?.coerceIn(1, bmp.height) ?: bmp.height } ?: 0
            val srcX = bitmap?.let { bmp -> cropRect?.left?.coerceIn(0, (bmp.width - srcW).coerceAtLeast(0)) ?: 0 } ?: 0
            val srcY = bitmap?.let { bmp -> cropRect?.top?.coerceIn(0, (bmp.height - srcH).coerceAtLeast(0)) ?: 0 } ?: 0
            if (bitmap != null && srcW > 0 && srcH > 0) {
                val bitmapAspect = srcW.toFloat() / srcH.toFloat()
                val canvasAspect = size.width / size.height
                if (bitmapAspect > canvasAspect) {
                    dstW = size.width
                    dstH = size.width / bitmapAspect
                } else {
                    dstH = size.height
                    dstW = size.height * bitmapAspect
                }
                dstX = (size.width - dstW) / 2f
                dstY = (size.height - dstH) / 2f
                // HIRES-ZOOM FEATURE: explicit High filter quality keeps the
                // remote image looking sharp while zoomed rather than
                // blurring past the source resolution — meaningful at Full
                // HD/2K/4K where there's real detail worth preserving.
                drawImage(
                    image         = bitmap.asImageBitmap(),
                    srcOffset     = androidx.compose.ui.unit.IntOffset(srcX, srcY),
                    srcSize       = androidx.compose.ui.unit.IntSize(srcW, srcH),
                    dstOffset     = androidx.compose.ui.unit.IntOffset(dstX.toInt(), dstY.toInt()),
                    dstSize       = androidx.compose.ui.unit.IntSize(dstW.toInt(), dstH.toInt()),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High
                )
            } else {
                drawRect(color = backgroundColor)
                // No frame yet — fall back to the full canvas for cursor mapping below.
                dstW = size.width; dstH = size.height; dstX = 0f; dstY = 0f
            }

            // Draw cursor (issue #4 — now reflects the chosen style/size/visibility)
            // BUGFIX-UI: the cursor is now mapped against the same letterboxed
            // rect as the image above (dstW/dstH/dstX/dstY), so it stays
            // visually aligned with the now-undistorted remote screen instead
            // of the old mapping against the full, stretched canvas size.
            // TOOLBOX FEATURE (Stage 6a) — DESIGN DECISION: per the plan, the
            // local cursor overlay is required in DIRECT mode (a tap with no
            // visible cursor is disorienting on a touchscreen) and must
            // auto-hide in TOUCHPAD mode (where the cursor's drawn position
            // is unrelated to the finger's position, so showing it would
            // mislead rather than help — it only reflects the *last remote*
            // position, not anything the finger is doing right now). The
            // pre-existing `showCursor`/settings.showCursorOnTouch toggle is
            // kept as an additional master override (if the user explicitly
            // turned the cursor off in Settings, direct mode still honours
            // that) rather than being fully superseded — see the Stage 6a
            // hand-off notes for whether that old Settings switch should be
            // relabelled/removed now that its main use case is direct mode.
            val effectiveShowCursor = showCursor && mouseInputMode == MouseInputMode.DIRECT
            if (effectiveShowCursor) {
                val cx = cursorX / screenWidth  * dstW + dstX - cursorPxSize / 2f
                val cy = cursorY / screenHeight * dstH + dstY - cursorPxSize / 2f
                drawImage(cursorBitmap, topLeft = Offset(cx, cy))
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// Extra Keys Bar
// ─────────────────────────────────────────────────────────────────────────────

private data class SpecialKey(val label: String, val scanCode: Int, val extended: Boolean = false)

// TOOLBOX FEATURE (Stage 3): split out from the old single EXTRA_KEYS list —
// modifiers are now sticky-toggle (see ExtraKeysBar/RdpSessionViewModel.
// toggleStickyModifier), everything else stays a simple momentary
// press-and-release button exactly as before. Shift is new — the original
// list never exposed it, even though Shift+F-keys/Shift+Tab and
// Shift-combos on the virtual keyboard (Stage 2) are common.
private val MODIFIER_KEYS = listOf(
    SpecialKey("Ctrl",  0x1D),
    SpecialKey("Alt",   0x38),
    SpecialKey("Shift", 0x2A),
    SpecialKey("Win",   0x5B, true),
)

private val MOMENTARY_KEYS = listOf(
    SpecialKey("Esc",   0x01), SpecialKey("Tab",   0x0F),
    SpecialKey("F1",  0x3B), SpecialKey("F2",  0x3C), SpecialKey("F3",  0x3D),
    SpecialKey("F4",  0x3E), SpecialKey("F5",  0x3F), SpecialKey("F6",  0x40),
    SpecialKey("F7",  0x41), SpecialKey("F8",  0x42), SpecialKey("F9",  0x43),
    SpecialKey("F10", 0x44), SpecialKey("F11", 0x57), SpecialKey("F12", 0x58),
    SpecialKey("Del",   0x53, true), SpecialKey("Home",  0x47, true),
    SpecialKey("End",   0x4F, true), SpecialKey("PgUp",  0x49, true),
    SpecialKey("PgDn",  0x51, true), SpecialKey("Ins",   0x52, true),
    SpecialKey("PrtSc", 0x37, true),
    // TOOLBOX FEATURE (Stage 3 completion): CapsLock is a single tap-toggle
    // key on a real keyboard (the *remote* OS flips its own caps-lock state
    // when it sees this scancode go down+up) — unlike Ctrl/Alt/Shift/Win
    // above, it is never held down while another key is pressed, so it
    // belongs here as an ordinary momentary press, not in MODIFIER_KEYS'
    // sticky-hold list.
    SpecialKey("Caps",  0x3A),
    // Enter and the four arrow keys were the most commonly missing keys the
    // plan called out by name; all four arrows are extended (0xE0-prefixed)
    // scancodes exactly like Del/Home/End/PgUp/PgDn/Ins/PrtSc above.
    SpecialKey("Enter", 0x1C),
    SpecialKey("↑", 0x48, true), SpecialKey("↓", 0x50, true),
    SpecialKey("←", 0x4B, true), SpecialKey("→", 0x4D, true),
)

// TOOLBOX FEATURE (Stage 3 completion): one-tap combo shortcuts. Unlike the
// sticky MODIFIER_KEYS above (meant to combine with *whatever* the user taps
// or types next, for as long as they choose), these are pre-baked, complete
// combinations that a real keyboard user reaches for constantly — the plan
// names Ctrl+C / Ctrl+V / Alt+Tab specifically. Firing one sends the whole
// modifier-down → key-down → key-up → modifier-up sequence itself in one
// tap, rather than requiring two separate taps (sticky modifier, then the
// letter) the way the sticky system already supports for less common
// combinations.
private data class ComboShortcut(
    val label: String,
    val modifierScanCode: Int,
    val modifierExtended: Boolean,
    val keyScanCode: Int,
    val keyExtended: Boolean = false,
)

private val COMBO_SHORTCUTS = listOf(
    ComboShortcut("Ctrl+C",  0x1D, false, 0x2E, false), // C
    ComboShortcut("Ctrl+V",  0x1D, false, 0x2F, false), // V
    ComboShortcut("Alt+Tab", 0x38, false, 0x0F, false), // Tab
)

/**
 * REMOTEAPP-WINDOWS FEATURE (part 2): MULTI_WINDOW mode's window switcher —
 * one tile per entry in [windows], laid out in a horizontally scrollable row
 * (same LazyRow-in-a-bordered-Surface shape as [ExtraKeysBar] just below).
 * The tile whose [RailWindow.windowId][com.systemsgo.hex.data.model.RailWindow.windowId]
 * matches [activeWindowId] is highlighted using the exact selected/border
 * treatment [RemoteAppDisplayModePopup] (SessionToolbox.kt) and
 * `RemoteAppDisplayModePicker` (Components.kt) both already use — PulsarCyan
 * border/tint over NebulaSurface/HorizonGray for the rest — so the same
 * "selected" visual language holds across all three RemoteApp-related UI
 * surfaces. Tapping any tile invokes [onActivate] with that window's id.
 *
 * Windows are shown highest-[RailWindow.zOrder][com.systemsgo.hex.data.model.RailWindow.zOrder]
 * first (most-recently-activated tends to sort first — see that property's
 * own doc comment), and a window that exists but isn't currently
 * [RailWindow.isVisible][com.systemsgo.hex.data.model.RailWindow.isVisible]
 * (minimized by the remote app itself) is shown dimmed rather than hidden,
 * so the user can still see it exists and tap it to bring it back — this
 * dimming is local-only chrome on the switcher tile, it does not touch how
 * RdpCanvas renders the shared desktop bitmap.
 *
 * ICON NOTE: [RailWindow.icon] is always null today — the native-side
 * Window Icon / Cached Icon order decoding isn't wired yet (see
 * systemsgo_jni.c's WindowIcon/WindowCachedIcon and RailWindow.icon's own doc
 * comment) — so every tile currently falls back to a plain colored initial
 * (the window title's first character). The bitmap branch below is written
 * and ready for the moment that native work lands; nothing here needs to
 * change when it does.
 */
@Composable
fun RemoteAppWindowSwitcherBar(
    windows: List<com.systemsgo.hex.data.model.RailWindow>,
    activeWindowId: Int?,
    onActivate: (Int) -> Unit,
) {
    val ordered = remember(windows) { windows.sortedByDescending { it.zOrder } }
    Surface(
        color    = DeepSpace.copy(alpha = 0.95f),
        border   = BorderStroke(1.dp, HorizonGray),
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier.widthIn(max = 340.dp),
    ) {
        LazyRow(
            contentPadding        = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ordered, key = { it.windowId }) { window ->
                val selected = window.windowId == activeWindowId
                // Minimized-but-still-open windows (isVisible == false) are shown
                // dimmed rather than excluded — see the function doc comment above.
                val tileAlpha = if (window.isVisible) 1f else 0.45f
                Surface(
                    modifier = Modifier
                        .size(width = 72.dp, height = 64.dp)
                        .alpha(tileAlpha)
                        .clickable { onActivate(window.windowId) },
                    shape  = RoundedCornerShape(10.dp),
                    color  = if (selected) PulsarCyan.copy(alpha = 0.18f) else NebulaSurface,
                    border = BorderStroke(1.dp, if (selected) PulsarCyan else HorizonGray),
                ) {
                    Column(
                        modifier            = Modifier.fillMaxSize().padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement  = Arrangement.Center,
                    ) {
                        if (window.icon != null) {
                            androidx.compose.foundation.Image(
                                bitmap             = window.icon.asImageBitmap(),
                                contentDescription = null,
                                modifier           = Modifier.size(22.dp),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(
                                        if (selected) PulsarCyan.copy(alpha = 0.3f) else HorizonGray.copy(alpha = 0.4f),
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = window.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) PulsarCyan else StarDust,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = window.title.ifBlank { stringResource(R.string.remote_app_window_untitled) },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) PulsarCyan else CometTail,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExtraKeysBar(
    onHide: () -> Unit = {},
    onKeyEvent: (Int, Boolean, Boolean) -> Unit,
    activeModifiers: Set<String> = emptySet(),
    onToggleModifier: (id: String, scanCode: Int, extended: Boolean) -> Unit = { _, _, _ -> },
    onMomentaryKeyReleased: () -> Unit = {},
) {
    Surface(
        color    = DeepSpace.copy(alpha = 0.95f),
        border   = BorderStroke(1.dp, HorizonGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        val comboScope = rememberCoroutineScope()
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier              = Modifier.weight(1f),
                contentPadding        = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(MODIFIER_KEYS.size) { i ->
                    val key = MODIFIER_KEYS[i]
                    val active = activeModifiers.contains(key.label)
                    Surface(
                        color    = if (active) PulsarCyan.copy(alpha = 0.25f) else NebulaSurface,
                        shape    = RoundedCornerShape(8.dp),
                        border   = BorderStroke(1.dp, if (active) PulsarCyan else HorizonGray),
                        modifier = Modifier
                            .sizeIn(minWidth = 40.dp, minHeight = 44.dp)
                            // Sticky: a single tap toggles it on/off — no
                            // press-and-hold needed, so it stays "down" on
                            // the remote side while the user's finger taps
                            // other buttons or types on the virtual keyboard.
                            .clickable { onToggleModifier(key.label, key.scanCode, key.extended) }
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                key.label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style    = MaterialTheme.typography.labelSmall,
                                color    = if (active) PulsarCyan else StarDust,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                items(MOMENTARY_KEYS.size) { i ->
                    val key     = MOMENTARY_KEYS[i]
                    var pressed by remember { mutableStateOf(false) }
                    Surface(
                        color    = if (pressed) PulsarCyan.copy(alpha = 0.2f) else NebulaSurface,
                        shape    = RoundedCornerShape(8.dp),
                        border   = BorderStroke(1.dp, if (pressed) PulsarCyan else HorizonGray),
                        // BUGFIX-UI: the touch target used to be only ~28.dp tall
                        // (padding alone around the text), well under the 48.dp
                        // accessibility minimum — risky given these keys (Ctrl,
                        // Alt, F-keys...) are tapped repeatedly during an active
                        // remote session where a mis-tap sends the wrong key.
                        // sizeIn() guarantees a 44.dp-tall / 40.dp-wide minimum
                        // without changing the visible pill for longer labels
                        // like "PrtSc", which already exceed it naturally.
                        modifier = Modifier
                            .sizeIn(minWidth = 40.dp, minHeight = 44.dp)
                            .pointerInput(key.label) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        when (ev.type) {
                                            PointerEventType.Press   -> { pressed = true;  onKeyEvent(key.scanCode, true,  key.extended) }
                                            PointerEventType.Release -> {
                                                pressed = false
                                                onKeyEvent(key.scanCode, false, key.extended)
                                                // Stage 3: combining momentary keys (Del, F-keys...)
                                                // with a sticky modifier (e.g. Ctrl+Del) is exactly
                                                // the point of sticky mode — but like a physical
                                                // keyboard's "next key releases the modifier" UX,
                                                // release it right after so it doesn't silently stay
                                                // held for whatever the user taps next by mistake.
                                                onMomentaryKeyReleased()
                                            }
                                            else -> {}
                                        }
                                    }
                                }
                            }
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                key.label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style    = MaterialTheme.typography.labelSmall,
                                color    = if (pressed) PulsarCyan else StarDust
                            )
                        }
                    }
                }
                items(COMBO_SHORTCUTS.size) { i ->
                    val combo = COMBO_SHORTCUTS[i]
                    Surface(
                        color    = NebulaSurface,
                        shape    = RoundedCornerShape(8.dp),
                        border   = BorderStroke(1.dp, PulsarCyan.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .sizeIn(minWidth = 40.dp, minHeight = 44.dp)
                            .clickable {
                                // Release any sticky modifier first so this
                                // fires as a clean, self-contained combo
                                // instead of layering on top of (or getting
                                // silently released by) an unrelated sticky
                                // Ctrl/Alt/Shift/Win the user left held.
                                onMomentaryKeyReleased()
                                comboScope.launch {
                                    onKeyEvent(combo.modifierScanCode, true, combo.modifierExtended)
                                    delay(30)
                                    onKeyEvent(combo.keyScanCode, true, combo.keyExtended)
                                    delay(30)
                                    onKeyEvent(combo.keyScanCode, false, combo.keyExtended)
                                    delay(30)
                                    onKeyEvent(combo.modifierScanCode, false, combo.modifierExtended)
                                }
                            }
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                combo.label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style    = MaterialTheme.typography.labelSmall,
                                color    = PulsarCyan,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onHide) {
                Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.cd_toggle_toolbar), tint = CometTail, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Overlay Composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ConnectingOverlay(
    name: String,
    isReconnecting: Boolean = false,
    errorHint: String? = null,
    onCancel: (() -> Unit)? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "connect_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    Box(Modifier.fillMaxSize().background(DeepSpace), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            CircularProgressIndicator(
                color       = PulsarCyan.copy(alpha = alpha),
                modifier    = Modifier.size(56.dp),
                strokeWidth = 3.dp
            )
            // CANCEL-RECONNECT FIX: during an auto-reconnect retry, show only
            // the plain status text (no attempt counter — that setting was
            // removed) plus a Cancel button so the user isn't stuck waiting
            // through retries they don't want.
            // LOG-DIAGNOSIS FIX: also surface the actual error that triggered
            // this retry (errorHint) so the user can see *why* right here,
            // instead of only finding out after opening Connection History.
            if (isReconnecting) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = StarDust)
                if (!errorHint.isNullOrBlank()) {
                    // BUGFIX-UI: errorHint carries the raw lastError text (native
                    // bridge / exception message), which is always English — it
                    // was being shown verbatim here even in an Arabic UI.
                    val localizedHint = localizeConnectionError(
                        androidx.compose.ui.platform.LocalContext.current, errorHint
                    )
                    Text(
                        localizedHint,
                        style     = MaterialTheme.typography.bodySmall,
                        color     = SolarFlare,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                Text(stringResource(R.string.connecting_to, name), style = MaterialTheme.typography.titleMedium, color = StarDust)
                Text(stringResource(R.string.connecting_please_wait), style = MaterialTheme.typography.bodySmall, color = CometTail)
            }
            if (isReconnecting && onCancel != null) {
                Spacer(Modifier.height(8.dp))
                SpaceButton(
                    stringResource(R.string.cancel),
                    onCancel,
                    variant  = ButtonVariant.GHOST,
                    modifier = Modifier.width(160.dp)
                )
            }
        }
    }
}

@Composable
fun ErrorOverlay(message: String, onClose: () -> Unit, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(DeepSpace), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth()
        ) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = NovaPink, modifier = Modifier.size(64.dp))
            Text(
                stringResource(R.string.error_connection_failed),
                style      = MaterialTheme.typography.headlineSmall,
                color      = StarDust,
                fontWeight = FontWeight.Bold
            )
            Text(
                message,
                style     = MaterialTheme.typography.bodyMedium,
                color     = CometTail,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )
            // RETRY FEATURE: primary action first — replays the exact same
            // attempt (see RdpSessionViewModel.retryConnect()) without
            // leaving this screen. Close remains available as a secondary,
            // less prominent action for anyone who just wants to back out
            // instead (e.g. they meant to edit the profile first).
            SpaceButton(stringResource(R.string.retry_connection), onRetry, variant = ButtonVariant.PRIMARY, modifier = Modifier.width(200.dp))
            SpaceButton(stringResource(R.string.close), onClose, variant = ButtonVariant.GHOST, modifier = Modifier.width(200.dp))
        }
    }
}

@Composable
fun DisconnectedOverlay(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(DeepSpace), Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Outlined.LinkOff, null, tint = DisconnectedGray, modifier = Modifier.size(56.dp))
            Text(stringResource(R.string.disconnected_title), style = MaterialTheme.typography.titleLarge, color = StarDust)
            SpaceButton(stringResource(R.string.close), onClose, modifier = Modifier.width(160.dp))
        }
    }
}

// ── UX-07: Gesture Hints Overlay ─────────────────────────────────────────────
@Composable
private fun GestureHintsOverlay(onDismiss: () -> Unit) {
    val hints = listOf(
        Triple(Icons.Outlined.ZoomIn,               stringResource(R.string.gesture_pinch_pan_title),  stringResource(R.string.gesture_pinch_pan_desc)),
        Triple(Icons.Outlined.TouchApp,              stringResource(R.string.gesture_long_press_title), stringResource(R.string.gesture_long_press_desc)),
        // FIX A: كانت "3-Finger Swipe" — لا يوجد كود يعالج ثلاثة أصابع في RdpCanvas.
        // الطريقة الفعلية لإظهار/إخفاء شريط الأدوات هي الضغط على الزر ↓ في أعلى اليمين.
        Triple(Icons.Default.KeyboardArrowDown,      stringResource(R.string.gesture_toolbar_title),    stringResource(R.string.gesture_toolbar_desc)),
    )

    // BUGFIX-UI-11: طوال الـ 300ms من انيميشن الإخفاء (AnimatedVisibility exit)
    // يبقى هذا الـ Composable في الشجرة وما زال .clickable نشطاً رغم أنه شبه
    // شفاف بصرياً، فيستمر باستهلاك اللمسات بدل تمريرها لـ RdpCanvas تحتها.
    // نعطّل clickable فوراً بعد أول لمسة تُغلق الـ overlay، فلا تبقى منطقة
    // لمس نشطة خلال فترة التلاشي.
    var dismissing by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .then(
                if (!dismissing)
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null,
                        onClick           = {
                            dismissing = true
                            onDismiss()
                        }
                    )
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                stringResource(R.string.gesture_guide_title),
                style    = MaterialTheme.typography.titleLarge,
                color    = StarDust,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(Modifier.height(24.dp))

            hints.forEach { (icon, title, desc) ->
                Row(
                    modifier = Modifier
                        .padding(horizontal = 40.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(PulsarCyan.copy(alpha = 0.15f), CircleShape)
                            .border(1.dp, PulsarCyan.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(26.dp))
                    }
                    Column {
                        Text(title, color = StarDust,  style = MaterialTheme.typography.bodyLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(desc,  color = CometTail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Surface(
                color  = PulsarCyan.copy(alpha = 0.15f),
                shape  = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .border(1.dp, PulsarCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 28.dp, vertical = 10.dp)
            ) {
                Text(stringResource(R.string.gesture_got_it), color = PulsarCyan,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.gesture_tap_to_dismiss), color = CometTail.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
