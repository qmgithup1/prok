package com.systemsgo.hex.ui.screens

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.rememberCoroutineScope
import com.systemsgo.hex.R
import com.systemsgo.hex.ui.theme.CometTail
import com.systemsgo.hex.ui.theme.HorizonGray
import com.systemsgo.hex.ui.theme.LocalSpaceColors
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.StarDust
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// Onboarding pager — 3 screens shown on first launch:
//   1. Welcome            2. What Systems Go does       3. Data / security
// Replaces the old single-dialog WelcomeOverlay with a full-bleed space
// background (gradient + starfield + nebula glow) and the animated mascot
// (img_mascot_wave). Kept as its own file so WelcomeOverlay.kt is untouched
// and can still be referenced/reverted to if needed.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    Dialog(
        onDismissRequest = { /* must go through Skip / Get Started */ },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        OnboardingContent(onFinish)
    }
}

@Composable
private fun OnboardingContent(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        SpaceBackground(modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.fillMaxSize()) {
            // Skip — top-right, absorbs touches so it doesn't leak to the pager below
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, end = 20.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                val isLastPage = pagerState.currentPage == 3
                if (!isLastPage) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        color = CometTail,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onFinish,
                            )
                            .padding(8.dp),
                    )
                } else {
                    Spacer(Modifier.height(1.dp))
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> WhatIsAppPage()
                    2 -> DataAndSecurityPage()
                    else -> ProFeaturesPage()
                }
            }

            PagerDots(pagerState = pagerState, modifier = Modifier.padding(vertical = 18.dp))

            PrimaryButton(
                text = if (pagerState.currentPage == 3)
                    stringResource(R.string.welcome_get_started)
                else
                    stringResource(R.string.onboarding_next),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                onClick = {
                    if (pagerState.currentPage == 3) {
                        onFinish()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
            )
        }
    }
}

// ── Page 1 — Welcome ──────────────────────────────────────────────────────────
@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FloatingMascot(size = 220.dp)
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_prefix),
            color = CometTail,
            style = MaterialTheme.typography.titleMedium,
        )
        // RTL-FIX: "Systems GO" is a brand name, not translated copy — it must
        // always read in that order. Without forcing LTR here, this Row gets
        // mirrored under an RTL locale (e.g. Arabic) and renders as "GO Systems".
        // Same pattern already used elsewhere for non-mirroring content, see
        // AppLockScreen.kt / TerminalScreen.kt.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row {
                Text(
                    text = "Systems ",
                    color = StarDust,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = "GO",
                    color = PulsarCyan,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            color = CometTail,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

// ── Page 2 — What the app does ────────────────────────────────────────────────
@Composable
private fun WhatIsAppPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FloatingHeroImage(
            painter = painterResource(R.drawable.img_page2_protocols),
            width = 200.dp,
            height = 300.dp, // matches the asset's 1024:1536 (2:3) aspect ratio
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.onboarding_page2_title_line1),
            color = StarDust,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.onboarding_page2_title_line2),
            color = StarDust,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.onboarding_page2_subtitle),
            color = CometTail,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Page 3 — Data, backup & security ──────────────────────────────────────────
// Uses the cloud-sync / locked-device hero graphic (img_page3_data_control),
// the same way WhatIsAppPage uses img_page2_protocols and ProFeaturesPage
// uses img_page4_pro_features — keeps all onboarding pages visually consistent.
@Composable
private fun DataAndSecurityPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FloatingHeroImage(
            painter = painterResource(R.drawable.img_page3_data_control),
            width = 150.dp,
            height = 352.dp, // matches the asset's 382:897 aspect ratio
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.onboarding_page3_title),
            color = StarDust,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.onboarding_page3_subtitle),
            color = CometTail,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Page 4 — Split screen, Samsung DeX, and other pro features ───────────────
// Uses the AI-generated hero image (split-screen / DeX / pro-badge diagram),
// the same way WhatIsAppPage uses img_page2_protocols.
@Composable
private fun ProFeaturesPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FloatingHeroImage(
            painter = painterResource(R.drawable.img_page4_pro_features),
            width = 200.dp,
            height = 300.dp, // matches the asset's 1024:1536 (2:3) aspect ratio
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.onboarding_page4_title),
            color = StarDust,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.onboarding_page4_subtitle),
            color = CometTail,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Shared: floating mascot with a soft cyan glow behind it ──────────────────
@Composable
private fun FloatingMascot(size: Dp) {
    FloatingHeroImage(
        painter = painterResource(R.drawable.img_mascot_wave),
        width = size,
        height = size,
    )
}

// ── Shared: any onboarding hero graphic (mascot or diagram), floating gently
// with a soft glow behind it — width/height set independently since some
// assets (like the protocols diagram) aren't square.
@Composable
private fun FloatingHeroImage(painter: Painter, width: Dp, height: Dp) {
    val infinite = rememberInfiniteTransition(label = "hero_float")
    val floatOffset by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "float_y",
    )
    val glowPulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow_pulse",
    )
    val glowSize = maxOf(width, height) * 0.9f

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(width * 1.15f, height * 1.15f)) {
        // Soft radial glow behind the graphic — fades to fully transparent,
        // so it reads as a gentle light source rather than a hard circle.
        Box(
            modifier = Modifier
                .size(glowSize)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            PulsarCyan.copy(alpha = 0.35f * glowPulse),
                            PulsarCyan.copy(alpha = 0.10f * glowPulse),
                            Color.Transparent,
                        ),
                    ),
                    shape = CircleShape,
                ),
        )
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier
                .size(width, height)
                .offset { IntOffset(0, (floatOffset * 8.dp.toPx()).roundToInt()) },
            contentScale = ContentScale.Fit,
        )
    }
}

// ── Shared: page dots ─────────────────────────────────────────────────────────
@Composable
private fun PagerDots(pagerState: PagerState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(pagerState.pageCount) { index ->
            val active = pagerState.currentPage == index
            Box(
                modifier = Modifier
                    .size(if (active) 9.dp else 7.dp)
                    .background(
                        if (active) PulsarCyan else HorizonGray,
                        CircleShape,
                    ),
            )
        }
    }
}

// ── Shared: primary CTA button ────────────────────────────────────────────────
@Composable
private fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(PulsarCyan)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color(0xFF02060F),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ── Shared: full-bleed space background — gradient + starfield + nebula glow ─
@Composable
private fun SpaceBackground(modifier: Modifier = Modifier) {
    val colors = LocalSpaceColors.current
    val stars = remember {
        val rnd = Random(42)
        List(90) {
            Triple(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
        }
    }

    Box(
        modifier = modifier.background(
            Brush.verticalGradient(colors.backgroundGradient),
        ),
    ) {
        // Two soft corner nebula glows for depth, matching the reference design
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(260.dp)
                .offset((-80).dp, (-60).dp)
                .background(
                    Brush.radialGradient(
                        listOf(colors.accentTertiary.copy(alpha = 0.16f), Color.Transparent),
                    ),
                    CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(300.dp)
                .offset(90.dp, 80.dp)
                .background(
                    Brush.radialGradient(
                        listOf(colors.accentSecondary.copy(alpha = 0.14f), Color.Transparent),
                    ),
                    CircleShape,
                ),
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            stars.forEach { (xf, yf, rf) ->
                val radius = 0.6f + rf * 1.6f
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f + rf * 0.55f),
                    radius = radius,
                    center = androidx.compose.ui.geometry.Offset(xf * size.width, yf * size.height),
                )
            }
        }
    }
}
