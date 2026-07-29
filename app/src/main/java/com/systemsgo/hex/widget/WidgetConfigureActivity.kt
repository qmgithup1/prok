package com.systemsgo.hex.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.data.repository.WidgetPreferences
import com.systemsgo.hex.ui.components.ProtocolIconBadge
import com.systemsgo.hex.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * HOME-SCREEN-WIDGET FEATURE (Part 1/2).
 *
 * The system launches this whenever the user drags the widget onto their home
 * screen (`ACTION_APPWIDGET_CONFIGURE`, declared via `android:configure` in
 * systemsgo_widget_info.xml + the matching intent-filter in AndroidManifest.xml), and
 * [SystemsGoAppWidgetProvider] also launches it directly for its unconfigured
 * "tap to set up" recovery (see [reconfigureIntent] and
 * `SystemsGoAppWidgetProvider.buildSingleConnectionViews`'s null-profile branch) — the
 * same dual-purpose shape [com.systemsgo.hex.ui.MainActivity.quickTileSetupIntent]
 * gives the Quick Settings tile's own "not bound yet" tap.
 *
 * `setResult(RESULT_CANCELED)` up front, before any UI is shown, is required
 * platform behavior for a widget configuration Activity — if the user backs out
 * without saving, the Launcher needs to see `RESULT_CANCELED` so it discards the
 * half-placed widget instead of leaving a broken one on the home screen. Saving
 * flips that to `RESULT_OK` with the same `appWidgetId` echoed back, per the
 * `AppWidgetManager` "app widget host" contract.
 */
@AndroidEntryPoint
class WidgetConfigureActivity : AppCompatActivity() {

    companion object {
        /** Launches this Activity to re-bind an existing instance (not the system's own add-widget flow) — see this class's doc comment. */
        fun reconfigureIntent(context: Context, appWidgetId: Int): Intent =
            Intent(context, WidgetConfigureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            // The system never invokes this Activity without a valid id in practice —
            // this only guards against a malformed manual launch (e.g. reconfigureIntent
            // called with a stale id after a widget was already removed).
            finish()
            return
        }

        setContent {
            SystemsGoTheme {
                WidgetConfigureScreen(
                    appWidgetId = appWidgetId,
                    onSaved = { finishWithResult() },
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun finishWithResult() {
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, resultValue)
        // WidgetUpdater pushes the very first RemoteViews for this instance — without
        // this, the widget would show whatever SystemsGoAppWidgetProvider.onUpdate() drew
        // it as *before* configuration (its default layout, since WidgetPreferences had
        // no saved config yet) until the next unrelated update happened to fire.
        WidgetUpdater.requestUpdate(this, listOf(appWidgetId))
        finish()
    }
}

/**
 * State for [WidgetConfigureScreen]. Loads any existing config for [appWidgetId] once
 * (re-configure path) and otherwise starts from the same defaults
 * [WidgetPreferences.WidgetConfig]'s constructor already declares — no separate
 * "is this a fresh add" branch needed, since re-reading an unconfigured id's snapshot
 * already returns those same defaults.
 */
@HiltViewModel
class WidgetConfigureViewModel @Inject constructor(
    private val profileRepository: RdpProfileRepository,
    private val widgetPreferences: WidgetPreferences,
) : ViewModel() {

    val profiles: StateFlow<List<RdpProfile>> =
        profileRepository.getAllProfiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _mode = MutableStateFlow(WidgetPreferences.DisplayMode.CONNECTION_LIST)
    val mode: StateFlow<WidgetPreferences.DisplayMode> = _mode.asStateFlow()

    private val _selectedProfileId = MutableStateFlow<String?>(null)
    val selectedProfileId: StateFlow<String?> = _selectedProfileId.asStateFlow()

    private val _listFilter = MutableStateFlow(WidgetPreferences.ListFilter.ALL)
    val listFilter: StateFlow<WidgetPreferences.ListFilter> = _listFilter.asStateFlow()

    private var loadedForWidgetId: Int? = null

    /** Called once from a `LaunchedEffect(appWidgetId)` — see [WidgetConfigureScreen]. */
    fun loadExisting(appWidgetId: Int) {
        if (loadedForWidgetId == appWidgetId) return
        loadedForWidgetId = appWidgetId
        val existing = widgetPreferences.configSnapshot(appWidgetId)
        _mode.value = existing.mode
        _selectedProfileId.value = existing.singleProfileId
        _listFilter.value = existing.listFilter
    }

    fun setMode(mode: WidgetPreferences.DisplayMode) { _mode.value = mode }
    fun selectProfile(profileId: String) { _selectedProfileId.value = profileId }
    fun setListFilter(filter: WidgetPreferences.ListFilter) { _listFilter.value = filter }

    fun save(appWidgetId: Int) = viewModelScope.launch {
        widgetPreferences.saveConfig(
            appWidgetId,
            WidgetPreferences.WidgetConfig(
                mode = mode.value,
                singleProfileId = if (mode.value == WidgetPreferences.DisplayMode.SINGLE_CONNECTION) selectedProfileId.value else null,
                listFilter = listFilter.value,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetConfigureScreen(
    appWidgetId: Int,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    viewModel: WidgetConfigureViewModel = hiltViewModel(),
) {
    LaunchedEffect(appWidgetId) { viewModel.loadExisting(appWidgetId) }

    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val selectedProfileId by viewModel.selectedProfileId.collectAsStateWithLifecycle()
    val listFilter by viewModel.listFilter.collectAsStateWithLifecycle()
    val spaceColors = LocalSpaceColors.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(spaceColors.backgroundGradient))
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = PulsarCyan)
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Widgets, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.widget_configure_title), color = StarDust, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            },
            bottomBar = {
                val canSave = mode == WidgetPreferences.DisplayMode.CONNECTION_LIST || selectedProfileId != null
                Surface(color = Color.Transparent) {
                    Button(
                        onClick = { scope.launch { viewModel.save(appWidgetId); onSaved() } },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PulsarCyan, contentColor = Color.Black)
                    ) {
                        Text(stringResource(R.string.widget_configure_save), fontWeight = FontWeight.Bold)
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    stringResource(R.string.widget_configure_mode_label),
                    color = CometTail,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    ModeOptionCard(
                        icon = Icons.Outlined.Widgets,
                        label = stringResource(R.string.widget_configure_mode_single),
                        selected = mode == WidgetPreferences.DisplayMode.SINGLE_CONNECTION,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.setMode(WidgetPreferences.DisplayMode.SINGLE_CONNECTION) }
                    )
                    ModeOptionCard(
                        icon = Icons.Outlined.ViewList,
                        label = stringResource(R.string.widget_configure_mode_list),
                        selected = mode == WidgetPreferences.DisplayMode.CONNECTION_LIST,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.setMode(WidgetPreferences.DisplayMode.CONNECTION_LIST) }
                    )
                }

                Spacer(Modifier.height(20.dp))

                when (mode) {
                    WidgetPreferences.DisplayMode.CONNECTION_LIST -> {
                        Text(
                            stringResource(R.string.widget_configure_filter_label),
                            color = CometTail,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            FilterOptionChip(
                                label = stringResource(R.string.widget_configure_filter_all),
                                selected = listFilter == WidgetPreferences.ListFilter.ALL,
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.setListFilter(WidgetPreferences.ListFilter.ALL) }
                            )
                            FilterOptionChip(
                                label = stringResource(R.string.widget_configure_filter_favorites),
                                icon = Icons.Outlined.Star,
                                selected = listFilter == WidgetPreferences.ListFilter.FAVORITES_ONLY,
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.setListFilter(WidgetPreferences.ListFilter.FAVORITES_ONLY) }
                            )
                        }
                    }
                    WidgetPreferences.DisplayMode.SINGLE_CONNECTION -> {
                        Text(
                            stringResource(R.string.widget_configure_pick_profile_hint),
                            color = CometTail,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        if (profiles.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.widget_configure_no_profiles),
                                    color = CometTail,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(profiles, key = { it.id }) { profile ->
                                    ProfilePickRow(
                                        profile = profile,
                                        selected = profile.id == selectedProfileId,
                                        onClick = { viewModel.selectProfile(profile.id) }
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

@Composable
private fun ModeOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) PulsarCyan.copy(alpha = 0.12f) else NebulaSurface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = if (selected) PulsarCyan else CometTail)
            Spacer(Modifier.height(8.dp))
            Text(label, color = StarDust, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun FilterOptionChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) PulsarCyan.copy(alpha = 0.12f) else NebulaSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, null, tint = if (selected) PulsarCyan else CometTail, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, color = if (selected) PulsarCyan else StarDust, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ProfilePickRow(
    profile: RdpProfile,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) PulsarCyan.copy(alpha = 0.12f) else NebulaSurface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProtocolIconBadge(type = profile.protocolType)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, color = StarDust, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("${profile.host}:${profile.port}", color = CometTail, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (selected) {
                Icon(Icons.Filled.CheckCircle, null, tint = PulsarCyan)
            } else {
                Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = CometTail.copy(alpha = 0.4f))
            }
        }
    }
}
