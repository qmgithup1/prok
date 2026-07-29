package com.systemsgo.hex.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import com.systemsgo.hex.BuildConfig // BUG-4 FIX: needed for dynamic VERSION_NAME
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset as UiOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.systemsgo.hex.R
import com.systemsgo.hex.ui.components.*
import com.systemsgo.hex.ui.components.buildCursorBitmap
import com.systemsgo.hex.ui.MainViewModel
import com.systemsgo.hex.ui.theme.*
import com.systemsgo.hex.util.normalizeDigits

// ═════════════════════════════════════════════════════════════════════════════
//  UI-FIX (settings reorganization): Settings used to be one long scrolling
//  page mixing ~25 unrelated toggles/sliders/dropdowns (appearance, cursor,
//  connection, session, data, security, developer info — all stacked on top
//  of each other). That's overwhelming and hard to scan.
//
//  It's now a small hub of clearly-labeled categories (this file's
//  `SettingsScreen`), each opening its own focused screen with just the
//  options that belong together. Every sub-screen reuses the same building
//  blocks (SettingsSection/Toggle/Choice/Slider/Item) so behavior and visual
//  language are unchanged — only the organization is.
// ═════════════════════════════════════════════════════════════════════════════

private data class SettingsCategory(
    val route:    String,
    val icon:     ImageVector,
    val title:    String,
    val subtitle: String,
    val accent:   Color,
)

@Composable
private fun rememberSettingsCategories(): List<SettingsCategory> {
    val colors = LocalSpaceColors.current
    return listOf(
        SettingsCategory(
            route    = "settings/appearance",
            icon     = Icons.Outlined.Palette,
            title    = stringResource(R.string.appearance),
            subtitle = stringResource(R.string.settings_appearance_desc),
            accent   = colors.accent,
        ),
        SettingsCategory(
            route    = "settings/cursor_input",
            icon     = Icons.Outlined.Mouse,
            title    = stringResource(R.string.cursor_input),
            subtitle = stringResource(R.string.settings_cursor_input_desc),
            accent   = colors.accentSecondary,
        ),
        SettingsCategory(
            route    = "settings/connection",
            icon     = Icons.Outlined.Wifi,
            title    = stringResource(R.string.connection),
            subtitle = stringResource(R.string.settings_connection_desc),
            accent   = colors.accentTertiary,
        ),
        SettingsCategory(
            route    = "settings/session_controls",
            icon     = Icons.Outlined.Gamepad,
            title    = stringResource(R.string.session_controls),
            subtitle = stringResource(R.string.settings_session_controls_desc),
            accent   = colors.accent,
        ),
        SettingsCategory(
            route    = "settings/data",
            icon     = Icons.Outlined.Storage,
            title    = stringResource(R.string.data_section),
            subtitle = stringResource(R.string.settings_data_desc),
            accent   = colors.accentSecondary,
        ),
        SettingsCategory(
            route    = "settings/security",
            icon     = Icons.Outlined.Security,
            title    = stringResource(R.string.security),
            subtitle = stringResource(R.string.settings_security_desc),
            accent   = colors.danger,
        ),
        // CLOUD-SYNC FEATURE (Part 3-b)
        SettingsCategory(
            route    = "settings/cloud_sync",
            icon     = Icons.Outlined.Cloud,
            title    = stringResource(R.string.cloud_sync),
            subtitle = stringResource(R.string.settings_cloud_sync_desc),
            accent   = colors.accentSecondary,
        ),
        // USB-REDIRECT FEATURE (Part 1/3)
        SettingsCategory(
            route    = "settings/usb_redirection",
            icon     = Icons.Outlined.Usb,
            title    = stringResource(R.string.usb_redirection_title),
            subtitle = stringResource(R.string.settings_usb_redirection_desc),
            accent   = colors.accentTertiary,
        ),
        // QUICK-SETTINGS-TILE FEATURE (Part 1/2)
        SettingsCategory(
            route    = "settings/quick_tile",
            icon     = Icons.Outlined.Bolt,
            title    = stringResource(R.string.qs_tile_settings_title),
            subtitle = stringResource(R.string.settings_quick_tile_desc),
            accent   = colors.accent,
        ),
        // HOME-SCREEN-WIDGET FEATURE (Part 2/2): purely explanatory entry —
        // see HomeScreenWidgetInfoScreen's own doc comment for why this
        // opens a "how to add it" screen rather than WidgetConfigureActivity
        // directly (placing a widget is the Launcher's job, not something
        // any app, including this one, can trigger via an Intent).
        SettingsCategory(
            route    = "settings/home_widget",
            icon     = Icons.Outlined.Widgets,
            title    = stringResource(R.string.home_widget_settings_title),
            subtitle = stringResource(R.string.settings_home_widget_desc),
            accent   = colors.accentSecondary,
        ),
        // PROFILE-SWITCHER FEATURE: dedicated hub entry so switching to the
        // isolated Guest Profile is directly reachable at any time — not only
        // via the "Forgot PIN?" recovery flow on the lock screen. Only ever
        // navigable from here while Primary is active: Settings itself is
        // blocked while Guest Mode is active (see HomeScreen's onSettingsClick).
        SettingsCategory(
            route    = "settings/profiles",
            icon     = Icons.Outlined.Person,
            title    = stringResource(R.string.profiles),
            subtitle = stringResource(R.string.settings_profiles_desc),
            accent   = colors.accentSecondary,
        ),
        SettingsCategory(
            route    = "settings/about",
            icon     = Icons.Outlined.Info,
            title    = stringResource(R.string.about),
            subtitle = stringResource(R.string.settings_about_desc),
            accent   = colors.accentTertiary,
        ),
    )
}

// ── Settings Hub ────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val categories = rememberSettingsCategories()
    val spaceColors = LocalSpaceColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(spaceColors.backgroundGradient))
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.settings),
                            style = MaterialTheme.typography.titleLarge,
                            color = StarDust, fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = PulsarCyan)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                categories.forEach { category ->
                    SettingsCategoryCard(
                        icon     = category.icon,
                        title    = category.title,
                        subtitle = category.subtitle,
                        accent   = category.accent,
                        onClick  = { navController.navigate(category.route) }
                    )
                }
            }
        }
    }
}

// ── Settings Category Card (hub entry) ────────────────────────────────────────
@Composable
private fun SettingsCategoryCard(
    icon:     ImageVector,
    title:    String,
    subtitle: String,
    accent:   Color,
    onClick:  () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(listOf(GradientCardStart, GradientCardEnd)),
                shape = RoundedCornerShape(16.dp)
            )
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .pressScale(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = StarDust, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CometTail)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = CometTail, modifier = Modifier.size(22.dp))
        }
    }
}

// ── Shared scaffold for every settings sub-screen ─────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubScaffold(
    title:          String,
    navController:  NavController,
    snackbarHostState: SnackbarHostState? = null,
    content:        @Composable ColumnScope.() -> Unit,
) {
    val spaceColors = LocalSpaceColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(spaceColors.backgroundGradient))
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost   = { snackbarHostState?.let { SnackbarHost(it) } },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = StarDust, fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = PulsarCyan)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
}

// ── 1. Appearance ──────────────────────────────────────────────────────────
@Composable
fun SettingsAppearanceScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
) {
    // PERF-FIX: this sub-screen only ever reads `settings` — it used to collect
    // the *whole* shared HomeUiState (profiles + settings + network + loading),
    // so it recomposed on every profile-list change or network-quality tick even
    // though it doesn't display any of that. Collecting the narrower
    // `settingsState` flow means this screen only recomposes when settings
    // actually change.
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()

    SettingsSubScaffold(title = stringResource(R.string.appearance), navController = navController) {
        SettingsToggle(
            icon      = if (settings.isDarkMode) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
            title     = stringResource(R.string.dark_mode),
            subtitle  = if (settings.isDarkMode) stringResource(R.string.dark) else stringResource(R.string.light),
            checked   = settings.isDarkMode,
            onCheckedChange = viewModel::updateDarkMode
        )

        SettingsThemeChoice(
            title     = stringResource(R.string.theme),
            options   = listOf(
                "space"  to stringResource(R.string.theme_space),
                "nebula" to stringResource(R.string.theme_nebula),
                "aurora" to stringResource(R.string.theme_aurora)
            ),
            selected  = settings.themeVariant,
            darkTheme = settings.isDarkMode,
            onSelect  = viewModel::updateTheme
        )

        SettingsLanguageChoice(
            title    = stringResource(R.string.language),
            options  = listOf(
                "system" to stringResource(R.string.lang_system),
                "en"     to "English",
                "ar"     to "العربية"
            ),
            selected = settings.language,
            onSelect = viewModel::updateLanguage
        )

        Spacer(Modifier.height(8.dp))
    }
}

// ── 2. Cursor & Input ──────────────────────────────────────────────────────
@Composable
fun SettingsCursorInputScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
) {
    // PERF-FIX: this sub-screen only ever reads `settings` — it used to collect
    // the *whole* shared HomeUiState (profiles + settings + network + loading),
    // so it recomposed on every profile-list change or network-quality tick even
    // though it doesn't display any of that. Collecting the narrower
    // `settingsState` flow means this screen only recomposes when settings
    // actually change.
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()

    SettingsSubScaffold(title = stringResource(R.string.cursor_input), navController = navController) {
        // UI-FIX: everything that controls the pointer — the live screen, the
        // style gallery, the size and speed sliders — now lives inside ONE
        // frame/panel instead of a mockup floating above separate settings rows.
        CursorControlPanel(
            cursorStyle         = settings.cursorStyle,
            cursorSize          = settings.cursorSize,
            touchpadSensitivity = settings.touchpadSensitivity,
            accent              = LocalSpaceColors.current.cursorColor,
            styleOptions        = listOf(
                "default"   to stringResource(R.string.cursor_default),
                "classic"   to stringResource(R.string.cursor_classic),
                "crosshair" to stringResource(R.string.cursor_crosshair),
                "dot"       to stringResource(R.string.cursor_dot),
                "circle"    to stringResource(R.string.cursor_circle)
            ),
            onStyleSelect       = viewModel::updateCursorStyle,
            onSizeChange        = { viewModel.updateCursorSize(it) },
            onSensitivityChange = viewModel::updateTouchpadSensitivity
        )

        SettingsSlider(
            icon          = Icons.Outlined.SwapVert,
            title         = stringResource(R.string.scroll_sensitivity),
            value         = settings.scrollSensitivity,
            valueRange    = 0.3f..3.0f,
            onValueChange = viewModel::updateScrollSensitivity,
            valueLabel    = { "%.1f×".format(it) }
        )

        // FIX B2: تبديل "إظهار المؤشر عند اللمس" — كان الإعداد موجوداً في DataStore
        // لكن لم يكن للمستخدم أي طريقة لتغييره من الواجهة.
        SettingsToggle(
            icon            = Icons.Outlined.Visibility,
            title           = stringResource(R.string.show_cursor_on_touch),
            subtitle        = stringResource(R.string.show_cursor_on_touch_desc),
            checked         = settings.showCursorOnTouch,
            onCheckedChange = viewModel::updateShowCursorOnTouch
        )

        SettingsToggle(
            icon            = Icons.Outlined.TouchApp,
            title           = stringResource(R.string.right_click_long_press),
            subtitle        = stringResource(R.string.right_click_long_press_desc),
            checked         = settings.rightClickLongPress,
            onCheckedChange = viewModel::updateRightClickLongPress
        )

        SettingsToggle(
            icon             = Icons.Outlined.Vibration,
            title            = stringResource(R.string.haptic_feedback),
            checked          = settings.hapticFeedback,
            onCheckedChange  = viewModel::updateHapticFeedback,
            isHapticSelfTest = true
        )

        SettingsToggle(
            icon            = Icons.AutoMirrored.Outlined.VolumeUp,
            title           = stringResource(R.string.sound_effects),
            subtitle        = stringResource(R.string.sound_effects_desc),
            checked         = settings.soundEnabled,
            onCheckedChange = viewModel::updateSoundEnabled,
            isSoundSelfTest = true
        )

        SettingsToggle(
            icon            = Icons.Outlined.ScreenLockLandscape,
            title           = stringResource(R.string.keep_screen_on),
            subtitle        = stringResource(R.string.keep_screen_on_desc),
            checked         = settings.keepScreenOn,
            onCheckedChange = viewModel::updateKeepScreenOn
        )

        Spacer(Modifier.height(8.dp))
    }
}

// ── 3. Connection ───────────────────────────────────────────────────────────
@Composable
fun SettingsConnectionScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
) {
    // PERF-FIX: this sub-screen only ever reads `settings` — it used to collect
    // the *whole* shared HomeUiState (profiles + settings + network + loading),
    // so it recomposed on every profile-list change or network-quality tick even
    // though it doesn't display any of that. Collecting the narrower
    // `settingsState` flow means this screen only recomposes when settings
    // actually change.
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val context  = LocalContext.current

    // CUSTOM-RESOLUTION FIX (item #10): the fixed preset list below (HD/FHD/QHD/4K)
    // was the only way to set a display resolution from the UI — anything else
    // (e.g. 1600x900, or a non-standard size a specific server expects) was only
    // reachable by importing a .rdp file with a matching "desktopwidth"/
    // "desktopheight" line, even though RdpSessionActivity.loadAndConnect()
    // already happily parses *any* "WxH" string out of settings.defaultResolution
    // (see its `defaultRes.split("x")` branch) — the missing piece was purely a
    // UI to let the user type one in. showCustomResDialog / the two text fields
    // below add exactly that, with the same bounds nativeResize() enforces native-
    // side (1..8192 per dimension) so an accepted value can never be silently
    // rejected at connect time.
    var showCustomResDialog by remember { mutableStateOf(false) }
    var customWidthInput  by remember { mutableStateOf("") }
    var customHeightInput by remember { mutableStateOf("") }
    var customResError    by remember { mutableStateOf<String?>(null) }

    val resolutionPresets = listOf(
        "auto"       to stringResource(R.string.resolution_auto),
        "1280x720"   to "1280 × 720  HD",
        "1920x1080"  to "1920 × 1080  FHD",
        "2560x1440"  to "2560 × 1440  QHD",
        "3840x2160"  to "3840 × 2160  4K"
    )
    // If the stored value is already a custom (non-preset, non-auto) "WxH",
    // surface it as its own selected row instead of just falling back to the
    // raw key as a label with no radio button matching it.
    val currentIsCustom = settings.defaultResolution != "auto" &&
        resolutionPresets.none { it.first == settings.defaultResolution }
    val resolutionOptions = resolutionPresets +
        (if (currentIsCustom)
            listOf(settings.defaultResolution to stringResource(
                R.string.resolution_custom_current, settings.defaultResolution))
         else emptyList()) +
        listOf("__custom__" to stringResource(R.string.resolution_custom))

    SettingsSubScaffold(title = stringResource(R.string.connection), navController = navController) {
        // WARP-STATUS FEATURE: see WarpStatusChecker's class doc for what
        // "Connected" actually guarantees (and doesn't). Re-checked on
        // resume — same pattern SettingsGeneralScreen's battery-optimization
        // row above uses — since the user can install/connect WARP, then
        // come straight back to this screen without it ever recomposing
        // otherwise.
        var warpState by remember {
            mutableStateOf(com.systemsgo.hex.util.WarpStatusChecker.currentState(context))
        }
        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    warpState = com.systemsgo.hex.util.WarpStatusChecker.currentState(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val warpSubtitle = when (warpState) {
            com.systemsgo.hex.util.WarpStatusChecker.WarpState.CONNECTED -> stringResource(R.string.warp_connected)
            com.systemsgo.hex.util.WarpStatusChecker.WarpState.INSTALLED_DISCONNECTED -> stringResource(R.string.warp_installed_disconnected)
            com.systemsgo.hex.util.WarpStatusChecker.WarpState.NOT_INSTALLED -> stringResource(R.string.warp_not_installed)
        }
        val warpTint = when (warpState) {
            com.systemsgo.hex.util.WarpStatusChecker.WarpState.CONNECTED -> ConnectedGreen
            com.systemsgo.hex.util.WarpStatusChecker.WarpState.INSTALLED_DISCONNECTED -> ConnectingAmber
            com.systemsgo.hex.util.WarpStatusChecker.WarpState.NOT_INSTALLED -> CometTail
        }
        SettingsItem(
            icon     = Icons.Outlined.Shield,
            title    = stringResource(R.string.warp_status_title),
            subtitle = warpSubtitle,
            tint     = warpTint,
            onClick  = {
                val pkg = com.systemsgo.hex.util.WarpStatusChecker.WARP_PACKAGE_NAME
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                } else {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        )
        Text(
            text  = stringResource(R.string.warp_status_heuristic_notice),
            color = CometTail,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(8.dp))

        SettingsChoice(
            icon     = Icons.Outlined.AspectRatio,
            title    = stringResource(R.string.default_resolution),
            options  = resolutionOptions,
            selected = settings.defaultResolution,
            onSelect = { key ->
                if (key == "__custom__") {
                    // Prefill from the current value when re-opening to tweak it.
                    if (currentIsCustom) {
                        val parts = settings.defaultResolution.split("x")
                        customWidthInput  = parts.getOrNull(0).orEmpty()
                        customHeightInput = parts.getOrNull(1).orEmpty()
                    } else {
                        customWidthInput  = ""
                        customHeightInput = ""
                    }
                    customResError = null
                    showCustomResDialog = true
                } else {
                    viewModel.updateDefaultResolution(key)
                }
            }
        )

        if (showCustomResDialog) {
            AlertDialog(
                onDismissRequest = { showCustomResDialog = false },
                title = { Text(stringResource(R.string.resolution_custom)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = customWidthInput,
                            onValueChange = { customWidthInput = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.width)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customHeightInput,
                            onValueChange = { customHeightInput = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.height)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        customResError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error,
                                 style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val w = customWidthInput.toIntOrNull()
                        val h = customHeightInput.toIntOrNull()
                        // Same bounds nativeResize() enforces (app/src/main/cpp/systemsgo_jni.c):
                        // 1..8192 per dimension. Rejecting out-of-range values here — rather
                        // than letting them through to fail silently at connect time — is the
                        // "مقبول منطقيا" (logically acceptable) part of this fix.
                        if (w == null || h == null || w <= 0 || h <= 0 || w > 8192 || h > 8192) {
                            customResError = context.getString(R.string.resolution_custom_invalid)
                        } else {
                            viewModel.updateDefaultResolution("${w}x${h}")
                            showCustomResDialog = false
                        }
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomResDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // SETTINGS-CONSOLIDATE FIX: color depth used to live only in the
        // per-connection "Add Connection" form. It's now global, alongside
        // Default Resolution, so every connection shares one set of quality
        // controls instead of needing it set per-profile.
        // AUTO-COLOR-DEPTH FEATURE: adds an "Auto" choice (sentinel value 0,
        // RdpPerformance.COLOR_DEPTH_AUTO) alongside the fixed 16/24/32-bit
        // options — resolved to a concrete depth from the live network
        // signal right before each connect/reconnect (see
        // NetworkQualityDetector.resolveColorDepth), the same pattern the
        // Display Quality slider below already uses for performanceLevel.
        SettingsChoice(
            icon     = Icons.Outlined.Palette,
            title    = stringResource(R.string.color_depth),
            options  = listOf(
                "0"  to stringResource(R.string.color_depth_auto),
                "16" to stringResource(R.string.color_depth_16),
                "24" to stringResource(R.string.color_depth_24),
                "32" to stringResource(R.string.color_depth_32)
            ),
            selected = settings.colorDepth.toString(),
            onSelect = { viewModel.updateColorDepth(it.toIntOrNull() ?: 32) }
        )

        // QUALITY-UNIFY FIX: "Frame Compression Quality" (a raw 0-100 codec
        // dial) and "Performance" (a 2-option High/Low effects switch) used
        // to be two separate controls that both claimed to govern quality —
        // confusing, since a weak connection needed both turned down and
        // there was no single place that made that obvious. They're now one
        // slider with five clear network-strength levels; each level moves
        // both the codec quality and the visual-effects trade-off together
        // (see RdpPerformance.flagsFor / codecQualityFor).
        // NETWORK-QUALITY-LABEL FIX: level 4 (RdpPerformance.AUTO) used to
        // be labelled "Very Strong" here — as if it were simply the top end
        // of the strength ladder — even though it isn't a fixed strength at
        // all: NetworkQualityDetector.resolve() re-evaluates it against the
        // *live* network every time a session (re)connects. That mislabeling
        // is exactly why the auto option was hard to find — it looked like
        // "very strong" quality, not like an adaptive mode. It now reads as
        // "Auto" so it's clearly the network-adaptive choice, distinct from
        // the four fixed levels.
        SettingsSlider(
            icon         = Icons.Outlined.HighQuality,
            title        = stringResource(R.string.display_quality),
            value        = settings.performanceLevel.toFloat(),
            valueRange   = 0f..4f,
            steps        = 3,   // 5 stops total: 0,1,2,3,4
            onValueChange = { viewModel.updatePerformanceLevel(it.toInt().coerceIn(0, 4)) },
            valueLabel   = {
                when (it.toInt().coerceIn(0, 4)) {
                    0 -> stringResource(R.string.network_quality_very_weak)
                    1 -> stringResource(R.string.network_quality_weak)
                    2 -> stringResource(R.string.network_quality_medium)
                    3 -> stringResource(R.string.network_quality_strong)
                    else -> stringResource(R.string.network_quality_auto)
                }
            }
        )

        // UDP-TRANSPORT FEATURE: MS-RDPEMT — request that RDP sessions try to
        // move bulk graphics traffic onto UDP alongside the classic TCP
        // channel, when both this client and the server support it. Global,
        // next to Display Quality/Color Depth above, same "how should any
        // RDP session behave on this network" grouping. Off by default —
        // see AppSettings.udpTransportEnabled's doc comment for why.
        SettingsToggle(
            icon            = Icons.Outlined.NetworkCheck,
            title           = stringResource(R.string.udp_transport),
            subtitle        = stringResource(R.string.udp_transport_desc),
            checked         = settings.udpTransportEnabled,
            onCheckedChange = viewModel::updateUdpTransportEnabled
        )

        // SMART-SIZING FEATURE: classic RDP-client "Smart Sizing" — see
        // AppSettings.smartSizingEnabled's doc comment for the full
        // rationale/mechanism. Grouped with Display Quality/UDP Transport
        // above, same "how a session behaves on this device" section.
        SettingsToggle(
            icon            = Icons.Outlined.AspectRatio,
            title           = stringResource(R.string.smart_sizing),
            subtitle        = stringResource(R.string.smart_sizing_desc),
            checked         = settings.smartSizingEnabled,
            onCheckedChange = viewModel::updateSmartSizingEnabled
        )

        // DATA-SAVER FEATURE: an explicit "minimize data no matter how fast
        // the network looks" override — forces every session down to the
        // lightest codec/color settings regardless of Display Quality/Color
        // Depth (even a fixed, non-Auto choice). Grouped in the same
        // "how a session behaves on this network" section as Display
        // Quality/Color Depth/UDP Transport above, since it's the same kind
        // of network-behavior dial — see AppSettings.dataSaverEnabled's doc
        // comment for why this is a different axis from AUTO quality.
        SettingsToggle(
            icon            = Icons.Outlined.DataSaverOn,
            title           = stringResource(R.string.data_saver),
            subtitle        = stringResource(R.string.data_saver_desc),
            checked         = settings.dataSaverEnabled,
            onCheckedChange = viewModel::updateDataSaverEnabled
        )

        // AUTO-RECONNECT-ALWAYS-ON FIX: the "Auto Reconnect" toggle (and its
        // paired attempt-limit) is removed. A session now always keeps
        // retrying/loading on a dropped or errored connection — with a
        // growing back-off between attempts — until either the user
        // intentionally disconnects or the server itself returns a real,
        // definitive error (e.g. AUTH_FAILED — rejected credentials). The
        // app itself no longer gives up on the user's behalf after an
        // arbitrary attempt count. See attachStateCollector() and
        // loadAndConnectQuick() in RdpSessionActivity.kt.

        // VPN-AWARE-CONNECTIVITY: lets the user pin new RDP/VNC/SSH
        // connections to a specific network (most commonly "VPN only", so a
        // session never silently falls back to a network the target host
        // isn't reachable from) or leave it on "Any available network" (the
        // previous, unrestricted behaviour — kept as the default). Applies
        // process-wide via VpnConnectivityManager.applyBinding right before
        // each connection attempt, so it covers RDP, VNC, and SSH uniformly
        // with no protocol-specific configuration.
        SettingsChoice(
            icon     = Icons.Outlined.VpnLock,
            title    = stringResource(R.string.network_binding),
            options  = listOf(
                "ANY"            to stringResource(R.string.network_binding_any),
                "VPN_ONLY"       to stringResource(R.string.network_binding_vpn_only),
                "WIFI_ONLY"      to stringResource(R.string.network_binding_wifi_only),
                "CELLULAR_ONLY"  to stringResource(R.string.network_binding_cellular_only),
            ),
            selected = settings.networkBinding,
            onSelect = viewModel::updateNetworkBinding
        )

        // UX-SIMPLIFY FIX: the "Max Reconnect Attempts" slider was removed —
        // users just want Auto Reconnect on or off without tuning a retry
        // count. Reconnection now uses a sensible built-in default and the
        // in-session reconnect overlay offers a Cancel button instead of
        // surfacing an attempt counter (see ConnectingOverlay).

        SettingsToggle(
            icon            = Icons.Outlined.PhoneAndroid,
            title           = stringResource(R.string.run_in_background),
            subtitle        = stringResource(R.string.run_in_background_desc),
            checked         = settings.runInBackground,
            onCheckedChange = viewModel::updateRunInBackground
        )

        // POWER FIX: previously the background service always held a
        // partial wake lock for up to 6h while a session was running,
        // blocking Doze entirely regardless of the chosen performance
        // level. This opt-in toggle lets battery-conscious users skip
        // the wake lock and rely on TcpKeepAlive instead — acceptable
        // since a backgrounded session isn't visible, so the occasional
        // Doze-induced delay before the next frame arrives isn't felt.
        AnimatedVisibility(visible = settings.runInBackground) {
            SettingsToggle(
                icon            = Icons.Outlined.BatterySaver,
                title           = stringResource(R.string.background_power_saving),
                subtitle        = stringResource(R.string.background_power_saving_desc),
                checked         = settings.backgroundPowerSaving,
                onCheckedChange = viewModel::updateBackgroundPowerSaving
            )
        }

        // OEM-COMPAT FIX: background sessions on Xiaomi/Honor/Huawei/Oppo/Vivo
        // and similar OEM skins die even with runInBackground=true unless the
        // user also exempts the app from battery optimisation (and, on the
        // worst offenders, enables an OEM-specific "Autostart"/"Protected apps"
        // switch). Surface both actions directly in Settings so users aren't
        // left guessing why background sessions get killed.
        AnimatedVisibility(visible = settings.runInBackground) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var batteryExempt by remember {
                    mutableStateOf(com.systemsgo.hex.util.PowerOptimizationHelper.isIgnoringBatteryOptimizations(context))
                }
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            batteryExempt = com.systemsgo.hex.util.PowerOptimizationHelper.isIgnoringBatteryOptimizations(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                SettingsItem(
                    icon     = Icons.Outlined.BatteryChargingFull,
                    title    = stringResource(R.string.battery_optimization_title),
                    subtitle = stringResource(
                        if (batteryExempt) R.string.battery_optimization_desc_enabled
                        else R.string.battery_optimization_desc_disabled
                    ),
                    tint = if (batteryExempt) ConnectedGreen else ConnectingAmber,
                    onClick = {
                        if (!batteryExempt) {
                            com.systemsgo.hex.util.PowerOptimizationHelper.launchSafely(
                                context,
                                com.systemsgo.hex.util.PowerOptimizationHelper.ignoreBatteryOptimizationsIntent(context)
                            )
                        }
                    }
                )

                if (com.systemsgo.hex.util.PowerOptimizationHelper.autostartIntent(context) != null) {
                    SettingsItem(
                        icon     = Icons.Outlined.RocketLaunch,
                        title    = stringResource(R.string.autostart_settings_title),
                        subtitle = stringResource(
                            R.string.autostart_settings_desc,
                            android.os.Build.MANUFACTURER
                        ),
                        tint = ConnectingAmber,
                        onClick = {
                            com.systemsgo.hex.util.PowerOptimizationHelper.autostartIntent(context)?.let {
                                com.systemsgo.hex.util.PowerOptimizationHelper.launchSafely(context, it)
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ── 4. In-Session Controls ───────────────────────────────────────────────────
@Composable
fun SettingsSessionScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
) {
    // PERF-FIX: this sub-screen only ever reads `settings` — it used to collect
    // the *whole* shared HomeUiState (profiles + settings + network + loading),
    // so it recomposed on every profile-list change or network-quality tick even
    // though it doesn't display any of that. Collecting the narrower
    // `settingsState` flow means this screen only recomposes when settings
    // actually change.
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()

    SettingsSubScaffold(title = stringResource(R.string.session_controls), navController = navController) {
        SettingsToggle(
            icon            = Icons.Outlined.ViewStream,
            title           = stringResource(R.string.show_toolbar_by_default),
            checked         = settings.sessionToolbarVisible,
            onCheckedChange = viewModel::updateSessionToolbarVisible
        )

        SettingsToggle(
            icon            = Icons.Outlined.Keyboard,
            title           = stringResource(R.string.show_extra_keys_by_default),
            checked         = settings.sessionExtraKeysVisible,
            onCheckedChange = viewModel::updateSessionExtraKeysVisible
        )

        // FIX B1: عداد FPS — الإعداد كان موجوداً في DataStore لكن لم يكن
        // هناك تبديل لتفعيله، ولم يكن يُعرض في شاشة الجلسة. تم إصلاح كلا الجانبين.
        SettingsToggle(
            icon            = Icons.Outlined.Speed,
            title           = stringResource(R.string.show_fps_counter),
            subtitle        = stringResource(R.string.show_fps_counter_desc),
            checked         = settings.showFpsCounter,
            onCheckedChange = viewModel::updateShowFps
        )

        // TOOLBOX FEATURE (Stage 7): "سرعة الاستجابة" (latency) used to be
        // tied to the same showFpsCounter switch above — it now has its own
        // independent setting/toggle, mirroring the FPS one exactly, plus a
        // matching tool in the in-session Toolbox (see RdpSessionActivity).
        SettingsToggle(
            icon            = Icons.Outlined.NetworkCheck,
            title           = stringResource(R.string.show_latency_counter),
            subtitle        = stringResource(R.string.show_latency_counter_desc),
            checked         = settings.showLatencyCounter,
            onCheckedChange = viewModel::updateShowLatency
        )

        // PLACEMENT FIX: "Max Reconnect Attempts" moved to Settings → Connection,
        // directly under the "Auto Reconnect" toggle it belongs to.

        Spacer(Modifier.height(8.dp))
    }
}

// ── 5. Data ────────────────────────────────────────────────────────────────
@Composable
fun SettingsDataScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // SECURITY FIX: Export ("Export backups containing sensitive information")
    // and Import ("Import or overwrite existing data" / "Restore backups" —
    // importConnections() decrypts a backup and merges it straight into the
    // live connection database) are both sensitive actions. Neither used to
    // require re-authentication even when App Lock was enabled — anyone with
    // the phone unlocked could exfiltrate every saved credential to a file,
    // or silently merge in an attacker-supplied backup. Both entry points now
    // route through the same [SecurityConfirmDialog] gate already used to
    // disable App Lock elsewhere in Settings, before any file picker or
    // password prompt is shown. A single pending-action slot is enough since
    // only one of these dialogs can be open at a time.
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    var pendingSecureAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // ── Export All Connections ──────────────────────────────────────────────
    // Flow: tap → re-auth (if App Lock is on) → ask for (and confirm) a backup
    // password → let the user pick where to save the file →
    // ConnectionBackupManager does the actual encrypt-and-write on a
    // background thread (see MainViewModel.exportConnections).
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    // Held only for the brief moment between "password confirmed" and the
    // system file picker returning a destination Uri — never persisted.
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri != null && password != null) {
            viewModel.exportConnections(uri, password)
        }
    }

    // ── Import Connections ───────────────────────────────────────────────────
    // Flow: tap → re-auth (if App Lock is on) → pick an existing backup file →
    // ask for its password → ConnectionBackupManager decrypts + merges it
    // (see MainViewModel.importConnections).
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportPasswordDialog = true
        }
    }

    val backupEvent by viewModel.backupEvent.collectAsStateWithLifecycle()
    val exportSuccessFmt = stringResource(R.string.export_connections_success)
    val importSuccessFmt = stringResource(R.string.import_connections_success)
    LaunchedEffect(backupEvent) {
        when (val event = backupEvent) {
            is MainViewModel.BackupUiEvent.ExportSuccess -> {
                snackbarHostState.showSnackbar(String.format(exportSuccessFmt, event.profileCount))
                viewModel.clearBackupEvent()
            }
            is MainViewModel.BackupUiEvent.ExportError -> {
                snackbarHostState.showSnackbar(event.message)
                viewModel.clearBackupEvent()
            }
            is MainViewModel.BackupUiEvent.ImportSuccess -> {
                snackbarHostState.showSnackbar(
                    String.format(importSuccessFmt, event.importedProfiles, event.skippedProfiles)
                )
                viewModel.clearBackupEvent()
            }
            is MainViewModel.BackupUiEvent.ImportError -> {
                snackbarHostState.showSnackbar(event.message)
                viewModel.clearBackupEvent()
            }
            else -> {}
        }
    }

    SettingsSubScaffold(
        title = stringResource(R.string.data_section),
        navController = navController,
        snackbarHostState = snackbarHostState
    ) {
        SettingsItem(
            icon     = Icons.Outlined.History,
            title    = stringResource(R.string.connection_history),
            subtitle = stringResource(R.string.connection_history_in_settings),
            onClick  = { navController.navigate("connection_history") }
        )

        Spacer(Modifier.height(4.dp))

        SettingsItem(
            icon     = Icons.Outlined.CloudUpload,
            title    = stringResource(R.string.export_all_connections),
            subtitle = stringResource(R.string.export_all_connections_desc),
            onClick  = {
                // Re-authenticate first (no-op if App Lock isn't configured —
                // see SecurityConfirmDialog), only then reveal the backup
                // password prompt.
                pendingSecureAction = { showExportPasswordDialog = true }
            }
        )

        SettingsItem(
            icon     = Icons.Outlined.CloudDownload,
            title    = stringResource(R.string.import_connections),
            subtitle = stringResource(R.string.import_connections_desc),
            onClick  = {
                // Re-authenticate first — importing overwrites/merges live
                // connection data, so it's gated exactly like export above.
                pendingSecureAction = {
                    importBackupLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                }
            }
        )

        Spacer(Modifier.height(4.dp))

        // CLEAR-DATA REDESIGN: this item used to trigger an immediate,
        // irreversible wipe the moment it was tapped — with no
        // re-authentication and no chance to review what would actually be
        // deleted (see git history). It now behaves like Export/Import
        // above: tapping it only ever *requests* the sensitive screen via
        // pendingSecureAction. The actual navigation into
        // DataManagementScreen happens exclusively from
        // SecurityConfirmDialog's onConfirmed callback further down — so if
        // App Lock is configured, a successful PIN/biometric check is
        // mandatory before that screen is ever shown, and no deletion of any
        // kind happens on this screen itself. This is deliberately separate
        // from the "Forgot PIN?" 24h-delayed reset on the lock screen (see
        // AppLockScreen.kt / DataResetManager) — that recovery path must
        // stay reachable *without* authentication, since it exists
        // specifically for users who can no longer authenticate at all.
        SettingsItem(
            icon     = Icons.Outlined.DeleteSweep,
            title    = stringResource(R.string.clear_data),
            subtitle = stringResource(R.string.clear_data_desc),
            onClick  = {
                pendingSecureAction = { navController.navigate("settings/data/manage") }
            }
        )

        val isBusy = backupEvent is MainViewModel.BackupUiEvent.Exporting ||
            backupEvent is MainViewModel.BackupUiEvent.Importing
        if (isBusy) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier   = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color      = PulsarCyan
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showExportPasswordDialog) {
        BackupPasswordDialog(
            title                = stringResource(R.string.export_all_connections),
            description          = stringResource(R.string.export_password_desc),
            requireConfirmation  = true,
            onConfirm            = { password ->
                showExportPasswordDialog = false
                pendingExportPassword = password
                val timestamp = android.text.format.DateFormat.format(
                    "yyyyMMdd_HHmmss", System.currentTimeMillis()
                )
                exportLauncher.launch("systemsgo_backup_$timestamp.hexbak")
            },
            onDismiss = { showExportPasswordDialog = false }
        )
    }

    if (showImportPasswordDialog) {
        BackupPasswordDialog(
            title               = stringResource(R.string.import_connections),
            description         = stringResource(R.string.import_password_desc),
            requireConfirmation = false,
            onConfirm           = { password ->
                showImportPasswordDialog = false
                val uri = pendingImportUri
                pendingImportUri = null
                if (uri != null) viewModel.importConnections(uri, password)
            },
            onDismiss = {
                showImportPasswordDialog = false
                pendingImportUri = null
            }
        )
    }

    // SECURITY FIX: the actual re-authentication gate for both Export and
    // Import above. Reuses the exact same PIN/biometric dialog as the rest
    // of the app (see SecurityConfirmDialog.kt) — auto-confirms with no UI
    // shown if App Lock isn't configured, otherwise requires a successful
    // PIN or biometric check before the pending action runs. A cancelled or
    // failed attempt clears the pending action, so nothing proceeds.
    pendingSecureAction?.let { action ->
        SecurityConfirmDialog(
            pinLockEnabled       = settings.pinLockEnabled,
            biometricLockEnabled = settings.biometricLockEnabled,
            encryptedPin         = settings.pinCode,
            onConfirmed = {
                pendingSecureAction = null
                action()
            },
            onDismiss = { pendingSecureAction = null }
        )
    }
}

// ── Backup password prompt ───────────────────────────────────────────────────
// Shared by "Export All Connections" (password + confirmation, since there's
// no way to recover a forgotten backup password later) and "Import
// Connections" (single password field) above.
@Composable
private fun BackupPasswordDialog(
    title: String,
    description: String,
    requireConfirmation: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // SECURITY FIX: contains a backup password field — see security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val emptyPasswordError = stringResource(R.string.error_backup_password_empty)
    val mismatchError = stringResource(R.string.error_backup_password_mismatch)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = NebulaSurface) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    title,
                    color      = StarDust,
                    fontWeight = FontWeight.Bold,
                    style      = MaterialTheme.typography.titleMedium
                )
                Text(description, color = CometTail, style = MaterialTheme.typography.bodySmall)

                OutlinedTextField(
                    value           = password,
                    onValueChange   = { password = it; errorText = null },
                    label           = { Text(stringResource(R.string.backup_password)) },
                    singleLine      = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction    = if (requireConfirmation) ImeAction.Next else ImeAction.Done
                    ),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                // A11Y FIX: was contentDescription = null — icon-only toggle,
                                // unreadable by TalkBack/screen readers without this.
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.cd_hide_password else R.string.cd_show_password
                                ),
                                tint = CometTail
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (requireConfirmation) {
                    OutlinedTextField(
                        value           = confirmPassword,
                        onValueChange   = { confirmPassword = it; errorText = null },
                        label           = { Text(stringResource(R.string.confirm_backup_password)) },
                        singleLine      = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction    = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (errorText != null) {
                    Text(
                        errorText!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        when {
                            password.isEmpty() -> errorText = emptyPasswordError
                            requireConfirmation && password != confirmPassword ->
                                errorText = mismatchError
                            else -> onConfirm(password)
                        }
                    }) { Text(stringResource(R.string.confirm_action)) }
                }
            }
        }
    }
}

// ── 6. Security ───────────────────────────────────────────────────────────
@Composable
fun SettingsSecurityScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
) {
    // PERF-FIX: this sub-screen only ever reads `settings` — it used to collect
    // the *whole* shared HomeUiState (profiles + settings + network + loading),
    // so it recomposed on every profile-list change or network-quality tick even
    // though it doesn't display any of that. Collecting the narrower
    // `settingsState` flow means this screen only recomposes when settings
    // actually change.
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val context  = LocalContext.current

    // FIX 3: snackbar scope for non-composable lambdas (e.g. biometric check)
    val scope              = rememberCoroutineScope()
    val snackbarHostState   = remember { SnackbarHostState() }
    val pinLockError by viewModel.pinLockError.collectAsStateWithLifecycle()
    LaunchedEffect(pinLockError) {
        pinLockError?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearPinLockError()
        }
    }

    SettingsSubScaffold(
        title = stringResource(R.string.security),
        navController = navController,
        snackbarHostState = snackbarHostState
    ) {
        // SECURITY FIX: disabling either lock previously took effect
        // immediately from onCheckedChange, with no re-authentication —
        // anyone holding an already-unlocked phone could switch both
        // protections off in two taps. Both toggles now route their
        // "turn OFF" path through SecurityConfirmDialog, which requires
        // re-entering the current PIN (or a biometric prompt, if no PIN
        // is set) before the change is applied. See SecurityConfirmDialog.kt.
        var pendingDisableBiometric by remember { mutableStateOf(false) }
        var pendingDisablePin       by remember { mutableStateOf(false) }

        SettingsToggle(
            icon            = Icons.Outlined.Fingerprint,
            title           = stringResource(R.string.biometric_lock),
            subtitle        = stringResource(R.string.biometric_lock_desc),
            checked         = settings.biometricLockEnabled,
            onCheckedChange = { enabled ->
                // BUG 3 FIX: Check biometric availability BEFORE saving
                // the setting. Previously, the toggle saved 'true' regardless
                // of hardware/enrollment state. When the user later reopened
                // the app they hit AppLockScreen: biometricEnabled=true but
                // launchBiometric() returned silently (canAuthenticate ≠ SUCCESS),
                // and if PIN was also off there was no unlock path at all —
                // the user was locked out of their own app.
                if (enabled) {
                    val bm = BiometricManager.from(context)
                    val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    } else {
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                    }
                    if (bm.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
                        // Hardware absent, no fingerprints enrolled, or temporarily locked.
                        // Show feedback and abort — do NOT persist the setting.
                        val msg = context.getString(R.string.biometric_not_available)
                        scope.launch { snackbarHostState.showSnackbar(msg) } // FIX 3
                        return@SettingsToggle
                    }
                    viewModel.updateBiometricLock(true)
                } else {
                    // Require re-authentication before turning protection OFF.
                    pendingDisableBiometric = true
                }
            }
        )

        if (pendingDisableBiometric) {
            SecurityConfirmDialog(
                pinLockEnabled       = settings.pinLockEnabled,
                biometricLockEnabled = settings.biometricLockEnabled,
                encryptedPin         = settings.pinCode,
                onConfirmed = {
                    viewModel.updateBiometricLock(false)
                    pendingDisableBiometric = false
                },
                onDismiss = { pendingDisableBiometric = false }
            )
        }

        // قفل PIN
        var showPinDialog by remember { mutableStateOf(false) }
        SettingsToggle(
            icon            = Icons.Outlined.Pin,
            title           = stringResource(R.string.pin_lock),
            subtitle        = if (settings.pinLockEnabled)
                stringResource(R.string.pin_lock_set)
            else
                stringResource(R.string.pin_lock_desc),
            checked         = settings.pinLockEnabled,
            onCheckedChange = { enabled ->
                if (enabled) showPinDialog = true
                // Require re-authentication before turning protection OFF.
                else pendingDisablePin = true
            }
        )

        if (pendingDisablePin) {
            SecurityConfirmDialog(
                pinLockEnabled       = settings.pinLockEnabled,
                biometricLockEnabled = settings.biometricLockEnabled,
                encryptedPin         = settings.pinCode,
                onConfirmed = {
                    viewModel.updatePinLock(false)
                    pendingDisablePin = false
                },
                onDismiss = { pendingDisablePin = false }
            )
        }

        if (showPinDialog) {
            PinSetupDialog(
                onConfirm = { pin ->
                    viewModel.updatePinLock(true, pin)
                    showPinDialog = false
                },
                onDismiss = { showPinDialog = false }
            )
        }

        // FEATURE-AUTO-LOCK: how long the app may stay in the background
        // before biometric/PIN re-authentication is required again. Only
        // takes effect once Biometric Lock or PIN Lock (above) is enabled.
        SettingsChoice(
            icon     = Icons.Outlined.Timer,
            title    = stringResource(R.string.auto_lock),
            options  = listOf(
                "-1"      to stringResource(R.string.auto_lock_disabled),
                "30000"   to stringResource(R.string.auto_lock_30s),
                "60000"   to stringResource(R.string.auto_lock_1m),
                "300000"  to stringResource(R.string.auto_lock_5m),
                "900000"  to stringResource(R.string.auto_lock_15m),
                "1800000" to stringResource(R.string.auto_lock_30m),
                "0"       to stringResource(R.string.auto_lock_immediate),
            ),
            selected = settings.autoLockTimeoutMs.toString(),
            onSelect = { key -> viewModel.updateAutoLockTimeout(key.toLongOrNull() ?: 0L) }
        )

        Spacer(Modifier.height(8.dp))
    }
}

// ── Profiles (PROFILE-SWITCHER FEATURE) ──────────────────────────────────────
// A lightweight, always-reachable profile switcher. This screen is only ever
// shown while the Primary profile is active — HomeScreen already blocks
// navigating into Settings (and therefore here) whenever Guest Mode is on
// (see onSettingsClick in HomeScreen.kt) — so "Primary" below is always the
// currently-active profile, never something that needs its own state.
//
// Switching TO Guest needs no re-authentication (Guest is empty/isolated by
// design, so there's nothing sensitive to protect on the way in) but does ask
// for an explicit confirmation tap first, so a stray tap can't silently swap
// the whole visible workspace out from under the user. Switching BACK to
// Primary is handled elsewhere (GuestModeBanner + SecurityConfirmDialog in
// HomeScreen) and already requires successful PIN/biometric re-authentication
// — reused as-is here, not duplicated.
@Composable
fun SettingsProfilesScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
) {
    var showSwitchConfirm by remember { mutableStateOf(false) }

    SettingsSubScaffold(title = stringResource(R.string.profiles), navController = navController) {
        SettingsSection(icon = Icons.Outlined.Lock, title = stringResource(R.string.profile_primary_title))
        ProfileCard(
            icon        = Icons.Outlined.Lock,
            title       = stringResource(R.string.profile_primary_title),
            description = stringResource(R.string.profile_primary_desc),
            trailing    = {
                Box(
                    modifier = Modifier
                        .background(PlasmaGreen.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        stringResource(R.string.profile_active_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = PlasmaGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        SettingsSection(icon = Icons.Outlined.Person, title = stringResource(R.string.profile_guest_title))
        ProfileCard(
            icon        = Icons.Outlined.Person,
            title       = stringResource(R.string.profile_guest_title),
            description = stringResource(R.string.profile_guest_desc),
            trailing    = {
                TextButton(onClick = { showSwitchConfirm = true }) {
                    Text(stringResource(R.string.profile_switch_to_guest), color = PulsarCyan)
                }
            }
        )

        Spacer(Modifier.height(8.dp))
    }

    if (showSwitchConfirm) {
        AlertDialog(
            onDismissRequest = { showSwitchConfirm = false },
            containerColor   = NebulaSurface,
            icon             = { Icon(Icons.Outlined.Person, contentDescription = null, tint = PulsarCyan) },
            title = {
                Text(
                    stringResource(R.string.profile_switch_to_guest_confirm_title),
                    color      = StarDust,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    stringResource(R.string.profile_switch_to_guest_confirm_message),
                    color = CometTail,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSwitchConfirm = false
                    viewModel.enterGuestMode()
                    // Settings exposes only primary-only data and is blocked while
                    // Guest Mode is active — leave it immediately and land on Home,
                    // which already renders the GuestModeBanner + guest-scoped state.
                    navController.popBackStack("home", inclusive = false)
                }) {
                    Text(stringResource(R.string.profile_switch_to_guest_confirm_button), color = SolarFlare)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSwitchConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            }
        )
    }
}

@Composable
private fun ProfileCard(
    icon:        ImageVector,
    title:       String,
    description: String,
    trailing:    @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(PulsarCyan.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style      = MaterialTheme.typography.titleSmall,
                    color      = StarDust,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.weight(1f)
                )
                trailing()
            }
            Spacer(Modifier.height(8.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = CometTail)
        }
    }
}

// ── 7. About / Developer ─────────────────────────────────────────────────────
// DESIGN-OVERHAUL: the old "About" screen was two generic list rows (Telegram,
// YouTube) followed by a single info card — no rating/sharing entry points, no
// Privacy Policy / Terms of Service (both expected by users and by app-store
// review guidelines), and icons that didn't clearly read as "developer contact"
// vs "legal" vs "support the app". Rebuilt as a proper About hub: an animated
// hero identity card, then three purpose-grouped sections (Connect, Support,
// Legal) plus a compact App Info block, closing with a small footer — the
// pattern used by most polished Play Store apps.
@Composable
fun SettingsAboutScreen(
    navController: NavController,
) {
    val context = LocalContext.current

    SettingsSubScaffold(title = stringResource(R.string.about), navController = navController) {
        AboutHero()

        Spacer(Modifier.height(4.dp))
        SettingsSection(Icons.Outlined.Forum, stringResource(R.string.about_section_connect))
        SettingsItem(
            icon     = Icons.AutoMirrored.Outlined.Send,
            title    = "Telegram",
            subtitle = stringResource(R.string.developer_telegram),
            onClick  = { com.systemsgo.hex.ui.components.safeOpenUrl(context, "https://t.me/GoToHEX") },
            tint     = Color(0xFF2CA5E0L)
        )
        SettingsItem(
            icon     = Icons.Outlined.SmartDisplay,
            title    = "YouTube",
            subtitle = stringResource(R.string.developer_youtube),
            onClick  = { com.systemsgo.hex.ui.components.safeOpenUrl(context, "https://youtube.com/@dev-hex404") },
            tint     = Color(0xFFFF0000L)
        )
        SettingsItem(
            icon     = Icons.Outlined.Email,
            title    = stringResource(R.string.contact_email),
            subtitle = stringResource(R.string.contact_email_subtitle),
            onClick  = {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:support@gotohex.dev")
                    putExtra(Intent.EXTRA_SUBJECT, "Systems Go — ${context.getString(R.string.app_name)}")
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, context.getString(R.string.error_no_app_to_open_link), Toast.LENGTH_SHORT).show()
                }
            },
            tint = QuantumBlue
        )

        SettingsSection(Icons.Outlined.Favorite, stringResource(R.string.about_section_support))
        SettingsItem(
            icon     = Icons.Outlined.StarRate,
            title    = stringResource(R.string.rate_app),
            subtitle = stringResource(R.string.rate_app_subtitle),
            onClick  = { rateAppOnStore(context) },
            tint     = Color(0xFFFFC107L)
        )
        SettingsItem(
            icon     = Icons.Outlined.Share,
            title    = stringResource(R.string.share_app),
            subtitle = stringResource(R.string.share_app_subtitle),
            onClick  = { shareApp(context) },
            tint     = PlasmaGreen
        )

        SettingsSection(Icons.Outlined.Gavel, stringResource(R.string.about_section_legal))
        SettingsItem(
            icon     = Icons.Outlined.PrivacyTip,
            title    = stringResource(R.string.privacy_policy),
            subtitle = stringResource(R.string.privacy_policy_subtitle),
            onClick  = { navController.navigate("settings/about/privacy") },
            tint     = VoidPurple
        )
        SettingsItem(
            icon     = Icons.Outlined.Article,
            title    = stringResource(R.string.terms_of_service),
            subtitle = stringResource(R.string.terms_of_service_subtitle),
            onClick  = { navController.navigate("settings/about/terms") },
            tint     = VoidPurple
        )

        SettingsSection(Icons.Outlined.Info, stringResource(R.string.about_section_info))
        AboutInfoCard()

        Spacer(Modifier.height(8.dp))
        AboutFooter()
        Spacer(Modifier.height(16.dp))
    }
}

// ── About Hero ────────────────────────────────────────────────────────────────
// Animated identity card: a slow-rotating conic glow ring behind the launcher
// icon (reuses the same "orbit" language as the rest of the space theme)
// instead of the old static rocket glyph, plus a version pill instead of a
// plain text line.
@Composable
private fun AboutHero() {
    val accent    = PulsarCyan
    val secondary = QuantumBlue
    val tertiary  = VoidPurple

    val infiniteTransition = rememberInfiniteTransition(label = "about_hero_orbit")
    val rotation by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "about_hero_rotation"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(listOf(accent.copy(0.10f), secondary.copy(0.07f), tertiary.copy(0.08f))),
                shape = RoundedCornerShape(20.dp)
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(CardBorderColor, tertiary.copy(0.2f))),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(vertical = 24.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = rotation }
                        .background(
                            brush = Brush.sweepGradient(
                                listOf(accent, secondary, tertiary, accent.copy(alpha = 0f), accent)
                            ),
                            shape = CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(NebulaSurface)
                        .border(2.dp, StarfieldSurface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    // ic_launcher_foreground.xml is a self-contained 108x108 vector
                    // (space gradient + nebula glow + hex ring already baked in), not
                    // a transparent adaptive-icon layer — safe to draw directly with
                    // painterResource, unlike the mipmap/anydpi-v26 adaptive icon
                    // (AdaptiveIconDrawable doesn't render reliably via painterResource).
                    Image(
                        painter            = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.app_name),
                style      = MaterialTheme.typography.titleLarge,
                color      = StarDust,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.about_hero_tagline),
                style     = MaterialTheme.typography.bodySmall,
                color     = CometTail,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.NewReleases, null, tint = accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${stringResource(R.string.app_version_label)} ${BuildConfig.VERSION_NAME}",
                    style      = MaterialTheme.typography.labelMedium,
                    color      = accent,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── About Info Card (version / build / diagnostics) ────────────────────────────
@Composable
private fun AboutInfoCard() {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.debug_info_copied)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            AboutInfoRow(stringResource(R.string.app_version_label), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            HorizontalDivider(color = HorizonGray.copy(alpha = 0.15f))
            AboutInfoRow(stringResource(R.string.by_developer), "GoToHEX")
            HorizontalDivider(color = HorizonGray.copy(alpha = 0.15f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(onClick = {
                        val info = "Systems Go ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
                            "${Build.MANUFACTURER} ${Build.MODEL}"
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(info))
                        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                    })
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.copy_debug_info), style = MaterialTheme.typography.bodyMedium, color = StarDust)
                    Text(stringResource(R.string.copy_debug_info_subtitle), style = MaterialTheme.typography.bodySmall, color = CometTail)
                }
                Icon(Icons.Outlined.ContentCopy, null, tint = CometTail, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = StarDust)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = CometTail)
    }
}

// ── About Footer ─────────────────────────────────────────────────────────────
@Composable
private fun AboutFooter() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.about_footer_made_with), style = MaterialTheme.typography.labelSmall, color = CometTail.copy(alpha = 0.8f))
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.about_footer_copyright), style = MaterialTheme.typography.labelSmall, color = CometTail.copy(alpha = 0.6f))
    }
}

// ── Rate / Share helpers ────────────────────────────────────────────────────────
// PLACEHOLDER: com.systemsgo.hex is the real applicationId already, but the
// Play Store listing itself doesn't exist yet — once published, no code change
// is needed here since both intents already target BuildConfig.APPLICATION_ID.
private fun rateAppOnStore(context: android.content.Context) {
    val packageName = context.packageName
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0)
            }
        )
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0)
                }
            )
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.error_no_store_app), Toast.LENGTH_SHORT).show()
        }
    }
}

private fun shareApp(context: android.content.Context) {
    val packageName = context.packageName
    val message = context.getString(R.string.share_app_message, packageName)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_app_chooser_title)))
}

// ── Legal document viewer (Privacy Policy / Terms of Service) ──────────────────
// No hosted URL exists yet for either document, so both render from local
// string resources instead of opening a browser — this also means they work
// offline. Once real hosted pages exist, swap the SelectionContainer/Text body
// below for safeOpenUrl(context, realUrl) without touching the calling routes.
@Composable
fun SettingsLegalScreen(
    navController: NavController,
    isPrivacyPolicy: Boolean,
) {
    val title   = stringResource(if (isPrivacyPolicy) R.string.privacy_policy else R.string.terms_of_service)
    val content = stringResource(if (isPrivacyPolicy) R.string.privacy_policy_content else R.string.terms_of_service_content)
    val icon    = if (isPrivacyPolicy) Icons.Outlined.PrivacyTip else Icons.Outlined.Article

    SettingsSubScaffold(title = title, navController = navController) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = VoidPurple, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = StarDust, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color    = StarfieldSurface,
            shape    = RoundedCornerShape(12.dp)
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    content,
                    modifier = Modifier.padding(18.dp),
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = CometTail,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

// ── Settings Section Header ───────────────────────────────────────────────────
@Composable
fun SettingsSection(icon: ImageVector, title: String) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            title.uppercase(),
            style  = MaterialTheme.typography.labelMedium,
            color  = PulsarCyan,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = PulsarCyan.copy(alpha = 0.2f)
        )
    }
}

// ── Settings Toggle ───────────────────────────────────────────────────────────
@Composable
fun SettingsToggle(
    icon:            ImageVector,
    title:           String,
    subtitle:        String?  = null,
    checked:         Boolean,
    onCheckedChange: (Boolean) -> Unit,
    // SELF-TEST FIX: the "Haptic feedback" and "Sound effects" toggles used to
    // behave exactly like every other switch on this screen — flipping the
    // persisted boolean with no actual physical confirmation. That's backwards
    // for these two specifically: the whole point of tapping them is to find
    // out whether vibration/sound currently works, so they need to *always*
    // produce the real thing right when tapped, regardless of the setting's
    // old/new value or of SoundManager's currently-effective `enabled` flag
    // (which is what silently swallowed the very TOGGLE click sound that
    // should have confirmed "sound is now on").
    isHapticSelfTest: Boolean = false,
    isSoundSelfTest:  Boolean = false,
) {
    val sound   = LocalSoundManager.current
    val haptics = LocalHapticFeedback.current
    // FIX #SETTINGS-ROW: Make the entire row tappable, not just the Switch widget.
    // Previously, tapping the icon/label area did nothing. Now the whole Surface
    // toggles the switch, matching the standard Android settings UX pattern.
    //
    // DOUBLE-TOGGLE FIX: The row used to have its own `clickable { onCheckedChange(!checked) }`
    // *and* the Switch below had its own `onCheckedChange = { onCheckedChange(it) }`. Both
    // handlers wrote to the same state, and a tap landing on the Switch's hit target could
    // fire both: the Switch's handler flips the value, then the Row's handler — closing over
    // the *pre-recomposition* `checked` — flips it back to the opposite of what it just became.
    // Net effect: the value (and, for Dark Mode, the whole screen's colors) visibly flashes to
    // the new state and immediately snaps back, while the persisted setting never actually
    // changes. This affected every SettingsToggle in the app; it was most visible on Dark Mode
    // because that one recolors the entire screen instead of just the switch.
    //
    // Fix: a single `Modifier.toggleable` on the Row is now the one and only place that reads
    // `checked` and calls `onCheckedChange`. The Switch is purely visual (`onCheckedChange =
    // null`), which also correctly merges accessibility semantics (TalkBack announces the row
    // once, not twice) — this is the standard Compose pattern for a clickable row + Switch.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value             = checked,
                    role              = Role.Switch,
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                    onValueChange = { newValue ->
                        // SELF-TEST FIX: real vibration pulse — always fires for the
                        // Haptic Feedback row itself (turning it off still gives one
                        // last confirming buzz so the user knows the tap registered;
                        // this is independent of the `hapticFeedback` setting because
                        // it's what's actually being tested here, not gated by it).
                        if (isHapticSelfTest) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        // SELF-TEST FIX: force = true bypasses SoundManager's internal
                        // `enabled` flag so the Sound Effects row is always audible when
                        // tapped, even the exact moment it's being switched from off to on.
                        sound?.play(
                            com.systemsgo.hex.audio.SoundManager.Sound.TOGGLE,
                            if (isSoundSelfTest) 0.6f else 0.4f,
                            force = isSoundSelfTest
                        )
                        onCheckedChange(newValue)
                    }
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(PulsarCyan.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CometTail)
                }
            }
            Switch(
                checked = checked,
                // null: clicks are handled solely by the row's toggleable() above. Passing a
                // handler here too is what caused the double-toggle bug — see comment above.
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor   = DeepSpace,
                    checkedTrackColor   = PulsarCyan,
                    uncheckedThumbColor = CometTail,
                    uncheckedTrackColor = HorizonGray
                )
            )
        }
    }
}

// ── Settings Choice (dropdown) ────────────────────────────────────────────────
@Composable
fun SettingsChoice(
    icon:     ImageVector,
    title:    String,
    options:  List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected
    val sound = LocalSoundManager.current
    val chevronRotation by animateFloatAsState(
        targetValue   = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "chevron"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(onClick = {
                        sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                        expanded = !expanded
                    })
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(PulsarCyan.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                    Text(selectedLabel, style = MaterialTheme.typography.bodySmall, color = PulsarCyan)
                }
                Icon(Icons.Default.ExpandMore, null, tint = CometTail, modifier = Modifier.size(20.dp).rotate(chevronRotation))
            }
            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically(tween(250)) + fadeIn(tween(250)),
                exit    = shrinkVertically(tween(200)) + fadeOut(tween(150))
            ) {
                Column(modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 10.dp)) {
                    // FIX #SETTINGS-DBL: Remove RadioButton's own onClick to prevent
                    // double-trigger. The outer Row's pressScale handles all selection.
                    options.forEach { (key, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressScale(onClick = { onSelect(key); expanded = false })
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = key == selected,
                                onClick  = null,   // FIX: delegated to parent Row pressScale
                                colors   = RadioButtonDefaults.colors(selectedColor = PulsarCyan)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                        }
                    }
                }
            }
        }
    }
}

// ── Unified control panel ─────────────────────────────────────────────────────
// UI-FIX: the live screen, the style gallery, and the size/speed sliders used
// to be separate stacked pieces (a floating mockup, then plain settings rows
// below it). They now live inside a single frame — one Surface, one border,
// one background — so it reads as one cohesive "pointer control panel" rather
// than a preview sitting on top of an unrelated settings list.
@Composable
fun CursorControlPanel(
    cursorStyle:         String,
    cursorSize:          Int,
    touchpadSensitivity: Float,
    accent:              Color,
    styleOptions:        List<Pair<String, String>>,
    onStyleSelect:       (String) -> Unit,
    onSizeChange:        (Int) -> Unit,
    onSensitivityChange: (Float) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = LocalSpaceColors.current.surfaceElevated,
        shape    = RoundedCornerShape(22.dp),
        border   = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            MouseSimulationStage(
                cursorStyle = cursorStyle,
                cursorSize  = cursorSize,
                accent      = accent
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = CometTail.copy(alpha = 0.15f))
            Spacer(Modifier.height(16.dp))

            CursorStyleGallery(
                options  = styleOptions,
                selected = cursorStyle,
                onSelect = onStyleSelect,
                accent   = accent
            )

            Spacer(Modifier.height(18.dp))

            CursorPanelSlider(
                icon          = Icons.Outlined.ZoomIn,
                title         = stringResource(R.string.cursor_size),
                value         = cursorSize.toFloat(),
                valueRange    = 12f..48f,
                onValueChange = { onSizeChange(it.toInt()) },
                valueLabel    = { "${it.toInt()}px" },
                accent        = accent
            )

            Spacer(Modifier.height(10.dp))

            CursorPanelSlider(
                icon          = Icons.Outlined.TouchApp,
                title         = stringResource(R.string.touchpad_sensitivity),
                value         = touchpadSensitivity,
                valueRange    = 0.3f..3.0f,
                onValueChange = onSensitivityChange,
                valueLabel    = { "%.1f×".format(it) },
                accent        = accent
            )
        }
    }
}

// A slider row with no Surface/background of its own — used inside
// CursorControlPanel so it shares the panel's frame instead of nesting a
// second card inside the first.
@Composable
private fun CursorPanelSlider(
    icon:          ImageVector,
    title:         String,
    value:         Float,
    valueRange:    ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueLabel:    @Composable (Float) -> String,
    accent:        Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust, modifier = Modifier.weight(1f))
            Text(valueLabel(value), style = MaterialTheme.typography.labelMedium, color = accent)
        }
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = valueRange,
            modifier      = Modifier.padding(top = 2.dp),
            colors = SliderDefaults.colors(
                thumbColor       = accent,
                activeTrackColor = accent
            )
        )
    }
}

// ── Live desktop simulation stage ────────────────────────────────────────────
// UI-FIX (real simulation instead of the old flat dropdown): a small "monitor"
// mockup — metal-look bezel, webcam notch, glass sheen — showing the user's
// own desktop wallpaper as the screen. A real cursor bitmap (built by
// buildCursorBitmap, at the user's actual chosen style/size) sits on top and
// can be dragged around like a real pointer, so switching styles or resizing
// is felt live on "your screen" rather than read off a label.
@Composable
fun MouseSimulationStage(
    cursorStyle: String,
    cursorSize:  Int,
    accent:      Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            val stageWidthPx  = constraints.maxWidth.toFloat()
            val stageHeightPx = constraints.maxHeight.toFloat()
            val density       = LocalDensity.current

            // Professional monitor bezel — brushed-metal gradient frame with a
            // webcam notch, instead of the wallpaper sitting in a plain square.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(18.dp, RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF2A2E39), Color(0xFF454B5C), Color(0xFF1B1E26))
                        ),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp)
                        .size(5.dp)
                        .background(Color(0xFF0A0B0E), CircleShape)
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    Image(
                        painter            = painterResource(R.drawable.desktop_wallpaper_preview),
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize()
                    )

                    // Subtle glass sheen so it reads as a lit screen, not a flat photo.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.10f),
                                        Color.Transparent,
                                        Color.Transparent
                                    ),
                                    start = UiOffset(0f, 0f),
                                    end   = UiOffset(stageWidthPx * 0.55f, stageHeightPx * 0.55f)
                                )
                            )
                    )

                    var pos by remember {
                        mutableStateOf(UiOffset(stageWidthPx * 0.52f, stageHeightPx * 0.44f))
                    }
                    LaunchedEffect(stageWidthPx, stageHeightPx) {
                        pos = UiOffset(
                            pos.x.coerceIn(0f, stageWidthPx),
                            pos.y.coerceIn(0f, stageHeightPx)
                        )
                    }

                    val cursorPx = with(density) { cursorSize.dp.toPx() * 1.7f }
                    val patchPx  = cursorPx * 2.1f

                    // Dark contrast patch behind the pointer — requested so the
                    // cursor stays clearly visible no matter what part of the
                    // wallpaper (light sky, bright city lights, etc.) sits under it.
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (pos.x - patchPx * 0.42f).roundToInt(),
                                    (pos.y - patchPx * 0.38f).roundToInt()
                                )
                            }
                            .size(with(density) { patchPx.toDp() })
                            .background(
                                Brush.radialGradient(
                                    listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent)
                                ),
                                CircleShape
                            )
                    )

                    val cursorBitmap = remember(cursorStyle, cursorSize, accent) {
                        buildCursorBitmap(cursorStyle, cursorSize.coerceIn(16, 48), accent).asImageBitmap()
                    }

                    // Crossfade the pointer artwork when the style changes, and let
                    // size changes animate smoothly — feels like a live device, not
                    // a static swap.
                    val animatedCursorPx by animateFloatAsState(
                        targetValue   = cursorPx,
                        animationSpec = tween(220),
                        label         = "cursorSize"
                    )

                    Image(
                        bitmap             = cursorBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .offset {
                                IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
                            }
                            .size(with(density) { animatedCursorPx.toDp() })
                            .pointerInput(stageWidthPx, stageHeightPx) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    pos = UiOffset(
                                        (pos.x + dragAmount.x).coerceIn(0f, stageWidthPx - cursorPx * 0.3f),
                                        (pos.y + dragAmount.y).coerceIn(0f, stageHeightPx - cursorPx * 0.3f)
                                    )
                                }
                            }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text      = stringResource(R.string.cursor_stage_hint),
            style     = MaterialTheme.typography.bodySmall,
            color     = CometTail,
            textAlign = TextAlign.Center,
            modifier  = Modifier.fillMaxWidth()
        )
    }
}

// ── Cursor style gallery — real-time swap on the stage above ─────────────────
@Composable
fun CursorStyleGallery(
    options:  List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    accent:   Color
) {
    val sound = LocalSoundManager.current
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding        = PaddingValues(vertical = 2.dp)
    ) {
        items(options, key = { it.first }) { (key, label) ->
            val isSelected = key == selected
            val borderColor by animateColorAsState(
                targetValue   = if (isSelected) accent else Color.Transparent,
                animationSpec = tween(200), label = "cursorChipBorder"
            )
            Surface(
                color    = StarfieldSurface,
                shape    = RoundedCornerShape(14.dp),
                border   = BorderStroke(if (isSelected) 2.dp else 0.dp, borderColor),
                modifier = Modifier
                    .width(84.dp)
                    .pressScale(onClick = {
                        sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f)
                        onSelect(key)
                    })
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp)
                ) {
                    CursorPreviewBox(cursorStyle = key, cursorSize = 24, accent = accent)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text       = label,
                        style      = MaterialTheme.typography.labelSmall,
                        color      = if (isSelected) accent else CometTail,
                        textAlign  = TextAlign.Center,
                        maxLines   = 2
                    )
                }
            }
        }
    }
}

// ── Cursor Choice with live preview ──────────────────────────────────────────
@Composable
fun SettingsCursorChoice(
    title:      String,
    options:    List<Pair<String, String>>,
    selected:   String,
    onSelect:   (String) -> Unit,
    cursorSize: Int,
    accent:     Color
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected
    val sound = LocalSoundManager.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220), label = "chevron"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(onClick = { sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f); expanded = !expanded })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CursorPreviewBox(cursorStyle = selected, cursorSize = cursorSize, accent = accent)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                    Text(selectedLabel, style = MaterialTheme.typography.bodySmall, color = PulsarCyan)
                }
                Icon(Icons.Default.ExpandMore, null, tint = CometTail, modifier = Modifier.size(20.dp).rotate(chevronRotation))
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(tween(250)) + fadeIn(tween(250)), exit = shrinkVertically(tween(200)) + fadeOut(tween(150))) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                    // FIX #SETTINGS-DBL: RadioButton.onClick = null to avoid double-trigger.
                    options.forEach { (key, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressScale(onClick = { onSelect(key); expanded = false })
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CursorPreviewBox(cursorStyle = key, cursorSize = cursorSize, accent = accent)
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = StarDust, modifier = Modifier.weight(1f))
                            RadioButton(
                                selected = key == selected,
                                onClick  = null,   // FIX: delegated to parent Row pressScale
                                colors   = RadioButtonDefaults.colors(selectedColor = PulsarCyan)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Theme Choice with a live color-swatch preview per option ─────────────────
// UX-05: replaces the plain RadioButton dot with a small circle that's an
// actual preview of that theme's palette — a sweep-gradient ring built from
// its three accent hues, wrapped around a disc of its own deep background
// color. It reads instantly as "this is what the theme looks like" instead
// of an anonymous selection dot, the same way Telegram/iOS theme pickers use
// colored circles rather than checkboxes for visual-style choices.
@Composable
fun SettingsThemeChoice(
    title:     String,
    options:   List<Pair<String, String>>,
    selected:  String,
    darkTheme: Boolean,
    onSelect:  (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected
    val sound = LocalSoundManager.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220), label = "chevron"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(onClick = { sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f); expanded = !expanded })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemeSwatch(themeVariant = selected, selected = false, darkTheme = darkTheme, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                    Text(selectedLabel, style = MaterialTheme.typography.bodySmall, color = PulsarCyan)
                }
                Icon(Icons.Default.ExpandMore, null, tint = CometTail, modifier = Modifier.size(20.dp).rotate(chevronRotation))
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(tween(250)) + fadeIn(tween(250)), exit = shrinkVertically(tween(200)) + fadeOut(tween(150))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    options.forEach { (key, label) ->
                        Column(
                            modifier = Modifier
                                .pressScale(onClick = { onSelect(key); expanded = false }),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ThemeSwatch(themeVariant = key, selected = key == selected, darkTheme = darkTheme, size = 52.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (key == selected) PulsarCyan else CometTail
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(
    themeVariant: String,
    selected:     Boolean,
    darkTheme:    Boolean,
    size:         Dp
) {
    val colors = spaceColorsFor(themeVariant, darkTheme)
    val ringBrush = remember(colors) {
        Brush.sweepGradient(listOf(colors.accent, colors.accentSecondary, colors.accentTertiary, colors.accent))
    }
    val ringWidth = size * 0.16f
    val scale by animateFloatAsState(if (selected) 1f else 0.92f, tween(180), label = "swatchScale")

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .then(
                if (selected)
                    Modifier.shadow(8.dp, CircleShape, ambientColor = colors.accent, spotColor = colors.accent)
                else Modifier
            )
            .clip(CircleShape)
            .background(ringBrush)
            .padding(ringWidth)
            .clip(CircleShape)
            .background(colors.background),
        contentAlignment = Alignment.Center
    ) {
        // A faint hint of the theme's card gradient inside the disc so it
        // isn't just a flat fill — still reads as "a tiny screenshot" of the app.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(colors.accent.copy(alpha = 0.35f), Color.Transparent)
                    )
                )
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint     = colors.accent,
                modifier = Modifier.size(size * 0.4f)
            )
        }
    }
}

// ── Language Choice with real flag circles ────────────────────────────────────
// UX-06: same idea as the theme swatches — a plain radio dot doesn't tell you
// anything about the language, but a small circular flag does at a glance.
// Uses hand-drawn vector flag artwork (ic_flag_en / ic_flag_sa) instead of the
// Unicode regional-indicator emoji glyphs, so the flags render identically on
// every device regardless of the system's emoji font: the UK flag for English,
// the Saudi Arabia flag for Arabic — the same representative flags
// Google/Twitter/most apps use for these two languages. Each drawable fills its
// swatch with ContentScale.Crop so a non-square flag (like Saudi Arabia's) is
// center-cropped into the circle rather than squashed. "Follow system" isn't a
// country, so instead of forcing a flag onto it, it gets its own distinct
// badge: a globe icon on a three-color sweep gradient so it visually reads as
// "automatic / all of them".
@Composable
fun SettingsLanguageChoice(
    title:    String,
    options:  List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected
    val sound = LocalSoundManager.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220), label = "chevron"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(onClick = { sound?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.3f); expanded = !expanded })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LanguageSwatch(langKey = selected, selected = false, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                    Text(selectedLabel, style = MaterialTheme.typography.bodySmall, color = PulsarCyan)
                }
                Icon(Icons.Default.ExpandMore, null, tint = CometTail, modifier = Modifier.size(20.dp).rotate(chevronRotation))
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(tween(250)) + fadeIn(tween(250)), exit = shrinkVertically(tween(200)) + fadeOut(tween(150))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    options.forEach { (key, label) ->
                        Column(
                            modifier = Modifier.pressScale(onClick = { onSelect(key); expanded = false }),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LanguageSwatch(langKey = key, selected = key == selected, size = 52.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (key == selected) PulsarCyan else CometTail
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageSwatch(
    langKey:  String,
    selected: Boolean,
    size:     Dp
) {
    val scale by animateFloatAsState(if (selected) 1f else 0.92f, tween(180), label = "langScale")
    val borderColor by animateColorAsState(
        targetValue   = if (selected) PulsarCyan else HorizonGray.copy(alpha = 0.4f),
        animationSpec = tween(200), label = "langBorder"
    )

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .then(
                if (selected) Modifier.shadow(8.dp, CircleShape, ambientColor = PulsarCyan, spotColor = PulsarCyan)
                else Modifier
            )
            .clip(CircleShape)
            .background(
                if (langKey == "system")
                    Brush.sweepGradient(listOf(PulsarCyan, QuantumBlue, VoidPurple, PulsarCyan))
                else
                    Brush.linearGradient(listOf(StarfieldSurface, NebulaSurface))
            )
            .border(if (selected) 2.dp else 1.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        when (langKey) {
            "system" -> Icon(
                Icons.Outlined.Language,
                contentDescription = null,
                tint     = Color.White,
                modifier = Modifier.size(size * 0.52f)
            )
            "ar" -> Image(
                painter            = painterResource(R.drawable.ic_flag_sa),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
            ) // Saudi Arabia flag — cropped from its native 4:3 artwork into the circle
            "en" -> Image(
                painter            = painterResource(R.drawable.ic_flag_en),
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .matchParentSize()
                    .clip(CircleShape)
            ) // United Kingdom flag
            else -> Icon(Icons.Outlined.Public, contentDescription = null, tint = CometTail, modifier = Modifier.size(size * 0.5f))
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.34f)
                    .clip(CircleShape)
                    .background(PulsarCyan)
                    .border(1.5.dp, StarfieldSurface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint     = DeepSpace,
                    modifier = Modifier.size(size * 0.2f)
                )
            }
        }
    }
}

@Composable
private fun CursorPreviewBox(cursorStyle: String, cursorSize: Int, accent: Color) {
    val previewBitmap = remember(cursorStyle, cursorSize, accent) {
        buildCursorBitmap(cursorStyle, cursorSize.coerceIn(16, 32), accent).asImageBitmap()
    }
    val previewBg = LocalSpaceColors.current.backgroundGradient.first() // BUG-6 FIX: was hardcoded DeepSpace — broken in light themes
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(previewBg, RoundedCornerShape(8.dp))
            .border(1.dp, HorizonGray.copy(0.4f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Image(bitmap = previewBitmap, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

// ── Settings Slider ───────────────────────────────────────────────────────────
@Composable
fun SettingsSlider(
    icon:         ImageVector,
    title:        String,
    value:        Float,
    valueRange:   ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueLabel:   @Composable (Float) -> String = { "%.1f".format(it) },
    steps:        Int = 0   // discrete stops between the two ends, e.g. 3 → 5 total positions
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(PulsarCyan.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust, modifier = Modifier.weight(1f))
                Text(valueLabel(value), style = MaterialTheme.typography.labelMedium, color = PulsarCyan)
            }
            Slider(
                value         = value,
                onValueChange = onValueChange,
                valueRange    = valueRange,
                steps         = steps,
                modifier      = Modifier.padding(top = 4.dp),
                colors = SliderDefaults.colors(
                    thumbColor        = PulsarCyan,
                    activeTrackColor  = PulsarCyan,
                    inactiveTrackColor = HorizonGray.copy(alpha = 0.4f)
                )
            )
        }
    }
}

// ── Settings Item (tappable) ──────────────────────────────────────────────────
@Composable
fun SettingsItem(
    icon:     ImageVector,
    title:    String,
    subtitle: String? = null,
    onClick:  () -> Unit,
    tint:     Color = PulsarCyan,
    // UI-CONFIG-GUARD FIX: lets callers (e.g. CloudSyncSettingsScreen's
    // Google Drive/Dropbox rows) visually grey the row out and swallow taps
    // instead of invoking [onClick] — used when the action behind it isn't
    // actually usable yet (e.g. an unconfigured OAuth client), so the row
    // communicates "not available" instead of tapping through to a
    // confusing low-level failure.
    enabled:  Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = StarfieldSurface,
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(onClick = if (enabled) onClick else {{}})
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .alpha(contentAlpha),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(tint.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CometTail)
                }
            }
            if (enabled) {
                Icon(Icons.Filled.ChevronRight, null, tint = CometTail, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── PIN Setup Dialog ──────────────────────────────────────────────────────────

// UX FIX: the PIN-setup dialog previously had several rough edges reported
// by users:
//   1. No way to reveal what you typed — a mis-tapped digit in a masked
//      field meant setting a PIN you couldn't actually confirm you knew.
//   2. Validation only ran on "Save" — mismatches/too-short PINs were never
//      flagged until after both fields were filled in and submitted.
//   3. Nothing stopped trivially guessable PINs ("0000", "1234", "123456",
//      repeated/sequential digits) from being accepted, which is especially
//      relevant now that SecurityConfirmDialog relies on the PIN as a real
//      security gate for disabling app-lock protections.
//   4. No keyboard flow — the IME had no "Next"/"Done" action, so users had
//      to manually tap between fields and then tap Save.
// All four are fixed below; the dot-pad on the lock screen itself is left
// as-is since typed-and-masked entry (with reveal) is the right pattern for
// *setting* a PIN, while a dot pad is right for *entering* a known one.
private val WEAK_PINS = setOf(
    "0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777", "8888", "9999",
    "1234", "4321", "0123", "3210",
    "000000", "111111", "123456", "654321", "121212"
)

private fun isSequential(pin: String): Boolean {
    val digits     = pin.map { it - '0' } // List<Int> — Iterable, unlike String itself
    val ascending  = digits.zipWithNext().all { (a, b) -> b - a == 1 }
    val descending = digits.zipWithNext().all { (a, b) -> a - b == 1 }
    return ascending || descending
}

// DESIGN-UNIFY FIX: PIN setup previously used a plain AlertDialog with two
// masked OutlinedTextField boxes — a completely different visual language
// from the actual lock screen the user sees every time they unlock the app
// (starfield background, glowing lock icon, circular numpad, dot progress
// indicator — see AppLockScreen.kt). Since the whole point of setting a PIN
// is to preview/rehearse how unlocking will feel, this now reuses the same
// PinKey numpad button and the same visual chrome as AppLockScreen, in a
// two-step flow (enter → confirm) instead of two side-by-side text fields.
private enum class PinSetupStep { ENTER, CONFIRM }

@Composable
fun PinSetupDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // SECURITY FIX: same numpad/dot-order leak as AppLockScreen (tap position
    // reveals the digit even though it's masked) — blank this window out of
    // screenshots, the Recents thumbnail, and screen recording while the new
    // PIN is being entered/confirmed. See security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()

    var step         by remember { mutableStateOf(PinSetupStep.ENTER) }
    var firstPin     by remember { mutableStateOf("") }
    var entered      by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    val strTooShort = stringResource(R.string.pin_error_too_short)
    val strMismatch = stringResource(R.string.pin_error_mismatch)
    val strWeak     = stringResource(R.string.pin_error_weak)

    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    fun triggerShake(message: String) {
        errorMessage = message
        scope.launch {
            repeat(4) {
                shake.animateTo(if (it % 2 == 0) 12f else -12f, tween(60))
            }
            shake.animateTo(0f, tween(60))
        }
        entered = ""
    }

    fun onDigit(d: String) {
        if (errorMessage.isNotBlank()) errorMessage = ""
        if (entered.length < 6) entered += d
    }

    fun onBackspace() {
        if (errorMessage.isNotBlank()) errorMessage = ""
        if (entered.isNotEmpty()) entered = entered.dropLast(1)
    }

    fun onCheck() {
        when (step) {
            PinSetupStep.ENTER -> when {
                entered.length < 4                                  -> triggerShake(strTooShort)
                entered in WEAK_PINS || isSequential(entered)        -> triggerShake(strWeak)
                else -> {
                    firstPin = entered
                    entered  = ""
                    step     = PinSetupStep.CONFIRM
                }
            }
            PinSetupStep.CONFIRM -> when {
                entered != firstPin -> triggerShake(strMismatch)
                else                -> onConfirm(entered)
            }
        }
    }

    // BIDI FIX: same pattern as AppLockScreen — the numpad/dots need a
    // locale-independent Ltr layout, but title/subtitle/error sentences
    // (which embed the Latin word "PIN" inside Arabic text) must keep their
    // own natural paragraph direction or the words visually scramble.
    val naturalLayoutDirection = LocalLayoutDirection.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            StarfieldBackground(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(20.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.cd_close),
                            tint = CometTail
                        )
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        Box(
                            Modifier
                                .size(88.dp)
                                .background(PulsarCyan.copy(alpha = 0.12f), CircleShape)
                                .border(2.dp, PulsarCyan.copy(alpha = 0.7f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = PulsarCyan,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        CompositionLocalProvider(LocalLayoutDirection provides naturalLayoutDirection) {
                            Text(
                                stringResource(
                                    if (step == PinSetupStep.ENTER) R.string.pin_setup_title
                                    else R.string.pin_confirm_label
                                ),
                                style      = MaterialTheme.typography.headlineMedium,
                                color      = StarDust,
                                fontWeight = FontWeight.Bold,
                                textAlign  = TextAlign.Center
                            )
                            Text(
                                stringResource(
                                    if (step == PinSetupStep.ENTER) R.string.pin_setup_desc
                                    else R.string.pin_setup_confirm_desc
                                ),
                                style     = MaterialTheme.typography.bodyMedium,
                                color     = CometTail,
                                textAlign = TextAlign.Center
                            )
                            if (errorMessage.isNotBlank()) {
                                Text(
                                    errorMessage,
                                    style      = MaterialTheme.typography.bodySmall,
                                    color      = ErrorRed,
                                    fontWeight = FontWeight.Medium,
                                    textAlign  = TextAlign.Center
                                )
                            }
                        }

                        // ── Dots indicator — grows as digits are typed (length is
                        // chosen by the user, 4–6, so there's no fixed slot count
                        // to pre-render the way the fixed-length unlock pad does).
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier
                                .offset(x = shake.value.dp)
                                .heightIn(min = 14.dp)
                        ) {
                            repeat(6) { i ->
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .background(
                                            if (i < entered.length) PulsarCyan
                                            else HorizonGray.copy(alpha = 0.3f),
                                            CircleShape
                                        )
                                )
                            }
                        }

                        val keys = listOf(
                            listOf("1", "2", "3"),
                            listOf("4", "5", "6"),
                            listOf("7", "8", "9"),
                            listOf("check", "0", "⌫"),
                        )
                        BoxWithConstraints {
                            val availableWidth = maxWidth
                            val btnSize = ((availableWidth - 64.dp) / 3.5f).coerceIn(52.dp, 72.dp)
                            val gap     = (btnSize * 0.25f).coerceIn(10.dp, 20.dp)
                            val canCheck = entered.length in 4..6

                            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                keys.forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                        row.forEach { key ->
                                            when (key) {
                                                "check" -> {
                                                    if (canCheck) {
                                                        PinKey(label = "✓", size = btnSize, onClick = ::onCheck)
                                                    } else {
                                                        Spacer(Modifier.size(btnSize))
                                                    }
                                                }
                                                "⌫" -> PinKey(
                                                    label   = key,
                                                    size    = btnSize,
                                                    enabled = entered.isNotEmpty(),
                                                    onClick = ::onBackspace
                                                )
                                                else -> PinKey(
                                                    label   = key,
                                                    size    = btnSize,
                                                    enabled = entered.length < 6,
                                                    onClick = { onDigit(key) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
//  CLEAR-DATA REDESIGN — DataManagementScreen
//
//  Reached exclusively via SettingsDataScreen's "Clear Data" item, and only
//  after that item's pendingSecureAction → SecurityConfirmDialog re-auth gate
//  has already succeeded (no-op/auto-confirmed if App Lock isn't configured
//  at all — see SecurityConfirmDialog.kt). Landing on this route therefore
//  already satisfies "if PIN/biometric is enabled, verification must happen
//  first" — this screen itself does not re-check auth.
//
//  Nothing on this screen deletes anything as a side effect of navigation or
//  composition. Every destructive action requires its own explicit
//  AlertDialog confirmation (same pattern as "Clear History" elsewhere in
//  Settings) before the corresponding ViewModel function runs. Cache and
//  Connection History are reversible/low-stakes and are only single-confirmed;
//  "Erase Everything" is irreversible and uses a dedicated, more explicit
//  confirmation dialog that spells out exactly what will be lost.
//
//  Deliberately unrelated to, and does not modify, the separate "Forgot PIN?"
//  24h-delayed reset reachable from the lock screen (AppLockScreen.kt /
//  DataResetManager) — that recovery path intentionally stays reachable
//  without authentication, since it exists for users who can no longer
//  authenticate at all. This screen is for an already-authenticated user who
//  wants to review and remove data on their own terms.
// ═════════════════════════════════════════════════════════════════════════════
@Composable
fun DataManagementScreen(
    navController: NavController,
    viewModel: MainViewModel = hiltViewModel(),
) {
    // SECURITY FIX (guest-mode data-clear bypass): this screen's own "does
    // not re-check auth" design above assumed every path here already went
    // through SettingsDataScreen's SecurityConfirmDialog — but "Continue as
    // Guest" also sets isUnlocked=true without ever verifying the real PIN,
    // and at least one entry point (ManageSpaceActivity's OS-level "Manage
    // space" button) navigates straight here on unlock, bypassing
    // SettingsDataScreen entirely. A guest — someone who never proved they
    // know the real PIN — must never even see this screen, let alone use it.
    // MainViewModel.clearCache/clearConnectionHistory/eraseAllAppData are
    // separately guarded too (the actual security boundary); this redirect
    // is the courtesy of not dead-ending a guest on a screen that will
    // silently no-op every button for them.
    val isGuestMode by viewModel.isGuestMode.collectAsStateWithLifecycle()
    LaunchedEffect(isGuestMode) {
        if (isGuestMode) {
            navController.popBackStack()
        }
    }
    if (isGuestMode) return

    var showCacheConfirm by remember { mutableStateOf(false) }
    var showHistoryConfirm by remember { mutableStateOf(false) }
    var showEraseConfirm by remember { mutableStateOf(false) }
    var isErasing by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val cacheClearedMsg = stringResource(R.string.data_management_cache_cleared)
    val scope = rememberCoroutineScope()

    SettingsSubScaffold(
        title = stringResource(R.string.data_management_title),
        navController = navController,
        snackbarHostState = snackbarHostState
    ) {
        Text(
            stringResource(R.string.data_management_intro),
            style = MaterialTheme.typography.bodySmall,
            color = CometTail
        )

        Spacer(Modifier.height(12.dp))

        // ── Cache & Temporary Files (low-stakes, reversible) ────────────────
        SettingsSection(icon = Icons.Outlined.Storage, title = stringResource(R.string.data_management_cache_title))
        Text(
            stringResource(R.string.data_management_cache_desc),
            style = MaterialTheme.typography.bodySmall,
            color = CometTail,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        SettingsItem(
            icon     = Icons.Outlined.DeleteSweep,
            title    = stringResource(R.string.data_management_cache_clear),
            onClick  = { showCacheConfirm = true }
        )

        Spacer(Modifier.height(4.dp))

        // ── Connection History ───────────────────────────────────────────────
        SettingsSection(icon = Icons.Outlined.History, title = stringResource(R.string.data_management_history_title))
        Text(
            stringResource(R.string.data_management_history_desc),
            style = MaterialTheme.typography.bodySmall,
            color = CometTail,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        SettingsItem(
            icon     = Icons.Outlined.History,
            title    = stringResource(R.string.data_management_history_clear),
            onClick  = { showHistoryConfirm = true }
        )

        Spacer(Modifier.height(4.dp))

        // ── Erase All App Data (irreversible) ────────────────────────────────
        SettingsSection(icon = Icons.Outlined.WarningAmber, title = stringResource(R.string.data_management_erase_title))
        Text(
            stringResource(R.string.data_management_erase_desc),
            style = MaterialTheme.typography.bodySmall,
            color = CometTail,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        SettingsItem(
            icon     = Icons.Outlined.DeleteForever,
            title    = stringResource(R.string.data_management_erase_button),
            onClick  = { showEraseConfirm = true },
            tint     = ErrorRed
        )

        if (isErasing) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = ErrorRed)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.data_management_erasing), color = CometTail, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // ── Cache confirm ───────────────────────────────────────────────────────
    if (showCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showCacheConfirm = false },
            containerColor   = NebulaSurface,
            shape            = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.data_management_cache_confirm_title), color = StarDust, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.data_management_cache_confirm_message), color = CometTail) },
            confirmButton = {
                SpaceButton(
                    stringResource(R.string.data_management_cache_clear),
                    onClick = {
                        showCacheConfirm = false
                        viewModel.clearCache()
                        scope.launch { snackbarHostState.showSnackbar(cacheClearedMsg) }
                    },
                    variant  = ButtonVariant.DANGER,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            },
            dismissButton = {
                SpaceButton(
                    stringResource(R.string.cancel),
                    onClick  = { showCacheConfirm = false },
                    variant  = ButtonVariant.GHOST,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        )
    }

    // ── History confirm ──────────────────────────────────────────────────────
    if (showHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showHistoryConfirm = false },
            containerColor   = NebulaSurface,
            shape            = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.history_clear_title), color = StarDust, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.history_clear_confirm), color = CometTail) },
            confirmButton = {
                SpaceButton(
                    stringResource(R.string.clear),
                    onClick = {
                        showHistoryConfirm = false
                        viewModel.clearConnectionHistory()
                    },
                    variant  = ButtonVariant.DANGER,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            },
            dismissButton = {
                SpaceButton(
                    stringResource(R.string.cancel),
                    onClick  = { showHistoryConfirm = false },
                    variant  = ButtonVariant.GHOST,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        )
    }

    // ── Erase Everything confirm (irreversible — most explicit dialog) ──────
    if (showEraseConfirm) {
        AlertDialog(
            onDismissRequest = { showEraseConfirm = false },
            containerColor   = NebulaSurface,
            shape            = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.data_management_erase_confirm_title), color = ErrorRed, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.data_management_erase_confirm_message), color = CometTail) },
            confirmButton = {
                SpaceButton(
                    stringResource(R.string.data_management_erase_confirm_button),
                    onClick = {
                        showEraseConfirm = false
                        isErasing = true
                        viewModel.eraseAllAppData()
                        // No further navigation/state update on purpose:
                        // clearApplicationUserData() inside eraseAllAppData()
                        // kills the process once it completes — the process
                        // restart from a clean state is the completion signal.
                    },
                    variant  = ButtonVariant.DANGER,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            },
            dismissButton = {
                SpaceButton(
                    stringResource(R.string.cancel),
                    onClick  = { showEraseConfirm = false },
                    variant  = ButtonVariant.GHOST,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        )
    }
}
