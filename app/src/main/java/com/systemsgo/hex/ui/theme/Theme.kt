package com.systemsgo.hex.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.systemsgo.hex.R

// ─────────────────────────────────────────────────────────────────────────────
// Fonts — Space/Sci-Fi themed:
//   • Orbitron      — geometric display font for titles (English/Latin)
//   • Rajdhani      — clean technical body text (English/Latin)
//   • Share Tech Mono — console/HUD monospace for code/labels
//   • Tajawal       — modern geometric Arabic that pairs well with Orbitron
// ─────────────────────────────────────────────────────────────────────────────

private val DisplayFontFamily = FontFamily(
    Font(R.font.orbitron_medium,   weight = FontWeight.Medium),
    Font(R.font.orbitron_semibold, weight = FontWeight.SemiBold),
    Font(R.font.orbitron_bold,     weight = FontWeight.Bold),
    Font(R.font.tajawal_bold,      weight = FontWeight.Bold),
    Font(R.font.tajawal_medium,    weight = FontWeight.Medium),
)

private val BodyFontFamily = FontFamily(
    Font(R.font.rajdhani_regular,  weight = FontWeight.Normal),
    Font(R.font.rajdhani_medium,   weight = FontWeight.Medium),
    Font(R.font.rajdhani_semibold, weight = FontWeight.SemiBold),
    Font(R.font.tajawal_regular,   weight = FontWeight.Normal),
    Font(R.font.tajawal_medium,    weight = FontWeight.Medium),
    Font(R.font.tajawal_bold,      weight = FontWeight.Bold),
)

private val MonoFontFamily = FontFamily(
    Font(R.font.share_tech_mono, weight = FontWeight.Normal),
    Font(R.font.share_tech_mono, weight = FontWeight.Medium),
    Font(R.font.tajawal_regular, weight = FontWeight.Normal),
)

val SpaceTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 52.sp, lineHeight = 60.sp, letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 42.sp, lineHeight = 50.sp, letterSpacing = (-0.25).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.15.sp
    ),
    titleMedium = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp
    ),
    labelMedium = TextStyle(
        fontFamily = MonoFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp
    ),
    labelSmall = TextStyle(
        fontFamily = MonoFontFamily, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp
    ),
)

// UI-FIX (theme switch flash): swapping the ColorScheme's ~30 colors in one
// frame (light↔dark or variant↔variant) made every surface, text, and icon
// jump colors simultaneously — a hard "flash" that reads like a camera
// flash, especially jarring at night on a dark screen. Instead of feeding
// the target ColorScheme straight into MaterialTheme, every color role is
// wrapped in animateColorAsState so Compose cross-fades each one over
// THEME_SWITCH_DURATION_MS instead of snapping instantly.
private const val THEME_SWITCH_DURATION_MS = 350

// Uses ColorScheme.copy() (it's a data class) rather than the raw constructor
// so this keeps compiling even if a future Material3 bump adds/removes color
// roles (e.g. the "fixed" color families) — any role not listed below simply
// falls back to the target scheme's value unanimated instead of breaking the
// build.
@Composable
private fun ColorScheme.animated(durationMillis: Int = THEME_SWITCH_DURATION_MS): ColorScheme {
    val spec = tween<Color>(durationMillis)
    @Composable fun anim(target: Color, label: String) = animateColorAsState(target, spec, label).value

    return copy(
        primary                   = anim(primary, "primary"),
        onPrimary                 = anim(onPrimary, "onPrimary"),
        primaryContainer          = anim(primaryContainer, "primaryContainer"),
        onPrimaryContainer        = anim(onPrimaryContainer, "onPrimaryContainer"),
        inversePrimary            = anim(inversePrimary, "inversePrimary"),
        secondary                 = anim(secondary, "secondary"),
        onSecondary               = anim(onSecondary, "onSecondary"),
        secondaryContainer        = anim(secondaryContainer, "secondaryContainer"),
        onSecondaryContainer      = anim(onSecondaryContainer, "onSecondaryContainer"),
        tertiary                  = anim(tertiary, "tertiary"),
        onTertiary                = anim(onTertiary, "onTertiary"),
        tertiaryContainer         = anim(tertiaryContainer, "tertiaryContainer"),
        onTertiaryContainer       = anim(onTertiaryContainer, "onTertiaryContainer"),
        background                = anim(background, "background"),
        onBackground              = anim(onBackground, "onBackground"),
        surface                   = anim(surface, "surface"),
        onSurface                 = anim(onSurface, "onSurface"),
        surfaceVariant            = anim(surfaceVariant, "surfaceVariant"),
        onSurfaceVariant          = anim(onSurfaceVariant, "onSurfaceVariant"),
        surfaceTint               = anim(surfaceTint, "surfaceTint"),
        inverseSurface            = anim(inverseSurface, "inverseSurface"),
        inverseOnSurface          = anim(inverseOnSurface, "inverseOnSurface"),
        error                     = anim(error, "error"),
        onError                   = anim(onError, "onError"),
        errorContainer            = anim(errorContainer, "errorContainer"),
        onErrorContainer          = anim(onErrorContainer, "onErrorContainer"),
        outline                   = anim(outline, "outline"),
        outlineVariant            = anim(outlineVariant, "outlineVariant"),
        scrim                     = anim(scrim, "scrim"),
        surfaceBright             = anim(surfaceBright, "surfaceBright"),
        surfaceDim                = anim(surfaceDim, "surfaceDim"),
        surfaceContainer          = anim(surfaceContainer, "surfaceContainer"),
        surfaceContainerHigh      = anim(surfaceContainerHigh, "surfaceContainerHigh"),
        surfaceContainerHighest   = anim(surfaceContainerHighest, "surfaceContainerHighest"),
        surfaceContainerLow       = anim(surfaceContainerLow, "surfaceContainerLow"),
        surfaceContainerLowest    = anim(surfaceContainerLowest, "surfaceContainerLowest"),
    )
}

@Composable
fun SystemsGoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeVariant: String = "space",
    content: @Composable () -> Unit
) {
    val spaceColors = spaceColorsFor(themeVariant, darkTheme)
    val colorScheme = materialColorSchemeFor(spaceColors).animated()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // BUG 1 FIX: (view.context as Activity) throws ClassCastException on
            // devices where LocalContext is wrapped (MIUI, OneUI, multi-window,
            // picture-in-picture, or any ContextWrapper chain). Use safe cast
            // and bail out silently — status-bar tint is cosmetic and should
            // never crash the app.
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars     = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalSpaceColors  provides spaceColors,
        LocalThemeVariant provides themeVariant
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = SpaceTypography,
            content     = content
        )
    }
}
