package com.systemsgo.hex.display

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.systemsgo.hex.ui.screens.RdpCanvas
import com.systemsgo.hex.ui.screens.RdpSessionViewModel
import com.systemsgo.hex.ui.screens.SessionUiState
import com.systemsgo.hex.ui.theme.SystemsGoTheme

/**
 * EXTERNAL-DISPLAY FEATURE
 *
 * A [Presentation] is Android's dedicated API for showing content on a
 * *secondary* display from inside the current process — it is not a new
 * Activity/Task and does not spin up a second [RdpSessionViewModel] or a
 * second [com.systemsgo.hex.remote.RemoteSessionClient]. It simply renders a
 * second [ComposeView] that observes the exact same view model instance
 * already running the session in [com.systemsgo.hex.ui.screens.RdpSessionActivity].
 * That is what makes this whole feature safe:
 *   - The remote session is never touched, paused, or reconnected — the
 *     Presentation just adds a second place the same live frames/state are
 *     drawn.
 *   - Disconnecting the external display only tears down this window
 *     ([dismiss] is called by the DisplayManager listener in the owning
 *     Activity when the display disappears); the session itself is
 *     completely unaffected and keeps running for the phone screen to fall
 *     back to.
 *
 * A [Presentation] is a [android.app.Dialog] subclass, so out of the box its
 * window has no [LifecycleOwner]/[ViewModelStoreOwner]/[SavedStateRegistryOwner]
 * for Compose to hook into. This class supplies minimal ones itself (the
 * standard pattern for hosting Compose outside of an Activity/Fragment),
 * scoped to exactly this window's lifetime.
 */
class RdpPresentation(
    outerContext: Context,
    display: Display,
    private val viewModel: RdpSessionViewModel
) : Presentation(outerContext, display),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        savedStateRegistryController.performRestore(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onCreate(savedInstanceState)

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent { PresentationContent() }
        }

        window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        setContentView(composeView)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * Only this Presentation window is torn down here — never the session.
     * Safe to call multiple times ([android.app.Dialog.dismiss] already is).
     */
    override fun dismiss() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.dismiss()
    }

    @androidx.compose.runtime.Composable
    private fun PresentationContent() {
        val settings by viewModel.settings.collectAsStateWithLifecycle()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val resolution by viewModel.resolution.collectAsStateWithLifecycle()
        val screenWidth = resolution.first
        val screenHeight = resolution.second

        SystemsGoTheme(darkTheme = settings.isDarkMode, themeVariant = settings.themeVariant) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (state is SessionUiState.Connected) {
                    // Same composable, same callbacks the phone screen uses —
                    // this is genuinely the live session, just drawn a second
                    // time on the external display.
                    RdpCanvas(
                        bitmapFlow          = viewModel.frameBitmap,
                        screenWidth         = screenWidth,
                        screenHeight        = screenHeight,
                        cursorStyle         = settings.cursorStyle,
                        cursorSize          = settings.cursorSize,
                        showCursor          = settings.showCursorOnTouch,
                        touchpadSensitivity = settings.touchpadSensitivity,
                        scrollSensitivity   = settings.scrollSensitivity,
                        rightClickLongPress = settings.rightClickLongPress,
                        // TOOLBOX FEATURE (Stage 5): same global "قلب الشاشة" preference
                        // used on the phone screen, so the mirrored external display
                        // matches what the user chose.
                        flipMode            = com.systemsgo.hex.ui.screens.ScreenFlipMode.fromSetting(settings.screenFlipMode),
                        onMouseMove  = { x, y       -> viewModel.sendMouseMove(x, y) },
                        onMouseClick = { x, y, b, d -> viewModel.sendMouseClick(x, y, b, d) },
                        onScroll     = { x, y, d    -> viewModel.sendMouseScroll(x, y, d) },
                        onViewportSettled = { viewModel.requestFrameRefresh() },
                        // EXTERNAL-DISPLAY / DEX FEATURE: physical keyboard attached to the
                        // dock/monitor (or the phone itself) types directly into the session.
                        onHardwareKeyEvent = { sc, down, ext -> viewModel.sendKeyEvent(sc, down, ext) },
                        modifier     = Modifier.fillMaxSize()
                    )
                } else {
                    // The session dropped or is (re)connecting while this
                    // window is up — show a minimal status instead of a
                    // stale/blank frame; the phone screen still has the full
                    // Connecting/Error/Disconnected overlays for details.
                    Text("…", color = Color.White)
                }
            }
        }
    }
}
