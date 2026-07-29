package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.systemsgo.hex.R
import com.systemsgo.hex.ui.theme.*

/**
 * HOME-SCREEN-WIDGET FEATURE (Part 2/2).
 *
 * Settings → Home Screen Widget: a purely explanatory screen, unlike
 * [QuickTileSettingsScreen] or [WidgetConfigureScreen] it reads/writes
 * nothing itself — there is no in-app "which connection is bound" state to
 * show here, because that binding is per *placed instance*
 * ([com.systemsgo.hex.data.repository.WidgetPreferences] is keyed by
 * `appWidgetId`, not by profile), and an instance only exists once the user
 * has actually dragged it onto their home screen via the Launcher's own
 * widget picker. This screen exists solely so "how do I add the widget?" has
 * somewhere to point a curious user, the same way a phone's own Settings app
 * usually can't add a home-screen widget for you either — placing widgets is
 * exclusively the Launcher's job (`AppWidgetHost`), not something any app,
 * including this one, can trigger via an Intent. If the user already has an
 * instance placed, [WidgetConfigureActivity] is reachable by long-pressing
 * that instance directly (or, for a SINGLE_CONNECTION instance whose bound
 * connection was deleted, its own "tap to set up" recovery card) — never
 * from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenWidgetInfoScreen(navController: NavController) {
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
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = PulsarCyan)
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Widgets, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.home_widget_settings_title), color = StarDust, fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.home_widget_settings_intro),
                    color = CometTail,
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(Modifier.height(4.dp))

                HomeWidgetStepRow(
                    icon = Icons.Outlined.TouchApp,
                    step = 1,
                    title = stringResource(R.string.home_widget_step1_title),
                    body = stringResource(R.string.home_widget_step1_body),
                )
                HomeWidgetStepRow(
                    icon = Icons.Outlined.Widgets,
                    step = 2,
                    title = stringResource(R.string.home_widget_step2_title),
                    body = stringResource(R.string.home_widget_step2_body),
                )
                HomeWidgetStepRow(
                    icon = Icons.Outlined.Tune,
                    step = 3,
                    title = stringResource(R.string.home_widget_step3_title),
                    // The widget's own display name (widget_label) is what actually
                    // appears in every launcher's picker — interpolating it here
                    // rather than hardcoding "SystemsGo Connections" a second time
                    // keeps the two in sync if that name is ever renamed.
                    body = stringResource(R.string.home_widget_step3_body, stringResource(R.string.widget_label)),
                )
                HomeWidgetStepRow(
                    icon = Icons.Outlined.Bolt,
                    step = 4,
                    title = stringResource(R.string.home_widget_step4_title),
                    body = stringResource(R.string.home_widget_step4_body),
                )

                Spacer(Modifier.height(8.dp))
                Surface(
                    color = NebulaSurface,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        stringResource(R.string.home_widget_settings_note),
                        color = CometTail,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HomeWidgetStepRow(
    icon: ImageVector,
    step: Int,
    title: String,
    body: String,
) {
    Surface(
        color = NebulaSurface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(PulsarCyan.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${step}. $title",
                    color = StarDust,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(body, color = CometTail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
