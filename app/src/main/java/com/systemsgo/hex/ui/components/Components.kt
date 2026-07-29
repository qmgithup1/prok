package com.systemsgo.hex.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ConnectionFolder
import com.systemsgo.hex.data.model.FolderColor
import com.systemsgo.hex.data.model.FolderIcon
import com.systemsgo.hex.data.model.CodecPreference
import com.systemsgo.hex.data.model.ProtocolCatalog
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.MoshPredictionMode
import com.systemsgo.hex.data.model.ProxmoxAuthMode
import com.systemsgo.hex.data.model.ProxyType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.session.CardConnectionStatus
import com.systemsgo.hex.session.CardStatusInfo
import com.systemsgo.hex.data.model.RemoteAppDisplayMode
import com.systemsgo.hex.data.model.SshAuthType
import com.systemsgo.hex.data.model.SshJumpHop
import com.systemsgo.hex.data.model.VncRepeaterMode
import com.systemsgo.hex.data.model.VrdeAuthType
import com.systemsgo.hex.data.model.VSphereApiMode
import com.systemsgo.hex.rdp.transport.RdpTransportMode
import com.systemsgo.hex.rdp.transport.RdpWebSocketConfig
import com.systemsgo.hex.rdp.transport.RdpWebSocketConfigCodec
import com.systemsgo.hex.ui.components.transport.WebSocketTransportSettings
// ADD-CONNECTION PROTOCOL PICKER (Part 2/2): reuses the exact same panel the
// picker shows on first use — see ProtocolIntroPanel's class doc, which
// explicitly documents this "What is this protocol?" call site.
import com.systemsgo.hex.ui.screens.addconnection.ProtocolIntroPanel
import com.systemsgo.hex.ui.theme.*
import com.systemsgo.hex.util.normalizeDigits
import kotlin.math.*
import kotlin.random.Random
import androidx.compose.foundation.BorderStroke
import com.systemsgo.hex.security.openEncryptedPrefs
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import com.systemsgo.hex.ssh.protocol.GeneratedSshKeyPair
import com.systemsgo.hex.ssh.protocol.SshKeyAlgorithm
import com.systemsgo.hex.ssh.protocol.SshKeyGenerator
import com.systemsgo.hex.ssh.protocol.X11AuthCookie

// ── Sound Manager CompositionLocal ────────────────────────────────────────────
val LocalSoundManager = staticCompositionLocalOf<com.systemsgo.hex.audio.SoundManager?> { null }

// ── Safe external-link launcher ───────────────────────────────────────────────
// BUGFIX-CRASH: every place in the app that opened an external link (developer
// Telegram/YouTube buttons, etc.) called context.startActivity(Intent(ACTION_VIEW, …))
// directly. On any device/emulator with no browser or no app registered to handle
// the URI (very common on clean emulators, some custom ROMs, or restricted work
// profiles) this throws an uncaught android.content.ActivityNotFoundException and
// takes down the whole app — exactly the "any button crashes the app" symptom.
// Wrapping every external Intent launch through this helper guarantees we never
// crash: on failure we just show a toast instead of losing the whole session.
fun safeOpenUrl(context: android.content.Context, url: String) {
    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0)
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, context.getString(R.string.error_no_app_to_open_link), android.widget.Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, context.getString(R.string.error_no_app_to_open_link), android.widget.Toast.LENGTH_SHORT).show()
    }
}

// ── Press Scale Modifier ──────────────────────────────────────────────────────
@Composable
fun Modifier.pressScale(
    enabled:    Boolean = true,
    scaleDown:  Float   = 0.96f,
    playSound:  Boolean = true,
    onClick:    () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue   = if (isPressed) scaleDown else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
        label         = "press_scale"
    )
    val soundManager = LocalSoundManager.current
    return this
        .scale(scale)
        .clickable(
            enabled           = enabled,
            interactionSource = interactionSource,
            indication        = null,
            onClick           = {
                if (playSound) soundManager?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.35f)
                onClick()
            }
        )
}

// ── Cursor Bitmap Builder ─────────────────────────────────────────────────────
fun buildCursorBitmap(
    cursorStyle: String,
    cursorSize:  Int,
    accentColor: Color
): android.graphics.Bitmap {
    val px     = (cursorSize * 2).coerceIn(20, 64)
    val cx     = px / 2f
    val cy     = px / 2f
    val bmp    = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val cvs    = android.graphics.Canvas(bmp)
    val accent = accentColor.toArgb()

    val fillPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color       = accent
        style       = android.graphics.Paint.Style.FILL
    }
    val strokePaint = android.graphics.Paint().apply {
        isAntiAlias  = true
        color        = android.graphics.Color.argb(200, 0, 0, 0)
        style        = android.graphics.Paint.Style.STROKE
        strokeWidth  = 2.5f
        strokeCap    = android.graphics.Paint.Cap.ROUND
        strokeJoin   = android.graphics.Paint.Join.ROUND
    }
    val glowPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color       = android.graphics.Color.argb(80,
            android.graphics.Color.red(accent),
            android.graphics.Color.green(accent),
            android.graphics.Color.blue(accent)
        )
        style       = android.graphics.Paint.Style.FILL
    }

    when (cursorStyle) {
        "classic" -> {
            // A literal, true-to-life system pointer: solid white body, solid black
            // outline, no accent tint — for people who just want "the real one".
            val s = px / 15f
            fun x(v: Float) = v * s
            fun y(v: Float) = v * s
            val path = android.graphics.Path().apply {
                moveTo(x(3.29227f), y(0.048984f))
                cubicTo(x(3.47033f), y(-0.032338f), x(3.67946f), y(-0.00228214f), x(3.8274f), y(0.125891f))
                lineTo(x(12.8587f), y(7.95026f))
                cubicTo(x(13.0134f), y(8.08432f), x(13.0708f), y(8.29916f), x(13.0035f), y(8.49251f))
                cubicTo(x(12.9362f), y(8.68586f), x(12.7578f), y(8.81866f), x(12.5533f), y(8.82768f))
                lineTo(x(9.21887f), y(8.97474f))
                lineTo(x(11.1504f), y(13.2187f))
                cubicTo(x(11.2648f), y(13.47f), x(11.1538f), y(13.7664f), x(10.9026f), y(13.8808f))
                lineTo(x(8.75024f), y(14.8613f))
                cubicTo(x(8.499f), y(14.9758f), x(8.20255f), y(14.8649f), x(8.08802f), y(14.6137f))
                lineTo(x(6.15339f), y(10.3703f))
                lineTo(x(3.86279f), y(12.7855f))
                cubicTo(x(3.72196f), y(12.934f), x(3.50487f), y(12.9817f), x(3.31479f), y(12.9059f))
                cubicTo(x(3.1247f), y(12.8301f), x(3f), y(12.6461f), x(3f), y(12.4414f))
                lineTo(x(3f), y(0.503792f))
                cubicTo(x(3f), y(0.308048f), x(3.11422f), y(0.130306f), x(3.29227f), y(0.048984f))
                close()
            }
            val shadowP = android.graphics.Paint().apply {
                isAntiAlias = true; color = android.graphics.Color.argb(90, 0, 0, 0)
                style = android.graphics.Paint.Style.FILL
            }
            val whiteFill = android.graphics.Paint().apply {
                isAntiAlias = true; color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.FILL
            }
            val blackOutline = android.graphics.Paint().apply {
                isAntiAlias = true; color = android.graphics.Color.BLACK
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = px * 0.05f
                strokeJoin  = android.graphics.Paint.Join.ROUND
            }
            cvs.save(); cvs.translate(px * 0.035f, px * 0.05f); cvs.drawPath(path, shadowP); cvs.restore()
            cvs.drawPath(path, whiteFill)
            cvs.drawPath(path, blackOutline)
        }
        "crosshair" -> {
            // Sci-fi crosshair with gap in center and corner brackets
            val r    = px * 0.38f
            val gap  = px * 0.12f
            val brkL = px * 0.18f  // bracket length

            // Glow
            val glowP = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 6f
                color = android.graphics.Color.argb(50,
                    android.graphics.Color.red(accent),
                    android.graphics.Color.green(accent),
                    android.graphics.Color.blue(accent))
            }
            cvs.drawLine(cx, cy - r, cx, cy - gap, glowP)
            cvs.drawLine(cx, cy + gap, cx, cy + r, glowP)
            cvs.drawLine(cx - r, cy, cx - gap, cy, glowP)
            cvs.drawLine(cx + gap, cy, cx + r, cy, glowP)

            val lineP = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f; color = accent; strokeCap = android.graphics.Paint.Cap.ROUND
            }
            cvs.drawLine(cx, cy - r, cx, cy - gap, lineP)
            cvs.drawLine(cx, cy + gap, cx, cy + r, lineP)
            cvs.drawLine(cx - r, cy, cx - gap, cy, lineP)
            cvs.drawLine(cx + gap, cy, cx + r, cy, lineP)

            // Corner brackets
            val brk = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2.5f; color = accent; strokeCap = android.graphics.Paint.Cap.SQUARE
            }
            val m = px * 0.08f
            cvs.drawLine(m, m, m + brkL, m, brk)
            cvs.drawLine(m, m, m, m + brkL, brk)
            cvs.drawLine(px - m, m, px - m - brkL, m, brk)
            cvs.drawLine(px - m, m, px - m, m + brkL, brk)
            cvs.drawLine(m, px - m, m + brkL, px - m, brk)
            cvs.drawLine(m, px - m, m, px - m - brkL, brk)
            cvs.drawLine(px - m, px - m, px - m - brkL, px - m, brk)
            cvs.drawLine(px - m, px - m, px - m, px - m - brkL, brk)

            // Center dot
            cvs.drawCircle(cx, cy, 2.5f, glowPaint)
            cvs.drawCircle(cx, cy, 2.5f, fillPaint)
        }
        "dot" -> {
            val r = cx * 0.7f
            cvs.drawCircle(cx, cy, r + 3f, glowPaint)
            cvs.drawCircle(cx, cy, r, fillPaint)
            val innerPaint = android.graphics.Paint().apply {
                isAntiAlias = true; color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.FILL
            }
            cvs.drawCircle(cx * 0.75f + cx * 0.1f, cy * 0.75f + cy * 0.1f, r * 0.25f, innerPaint)
            cvs.drawCircle(cx, cy, r, strokePaint)
        }
        "circle" -> {
            val r       = cx - 4f
            val ringP   = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f; color = accent
            }
            val ringGlow = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 7f
                color = android.graphics.Color.argb(60,
                    android.graphics.Color.red(accent),
                    android.graphics.Color.green(accent),
                    android.graphics.Color.blue(accent))
            }
            cvs.drawCircle(cx, cy, r, ringGlow)
            cvs.drawCircle(cx, cy, r, ringP)
            // Tick marks at cardinal points
            val tLen = r * 0.22f
            val tP = android.graphics.Paint().apply {
                isAntiAlias = true; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f; color = accent
            }
            cvs.drawLine(cx, 2f, cx, 2f + tLen, tP)
            cvs.drawLine(cx, px - 2f, cx, px - 2f - tLen, tP)
            cvs.drawLine(2f, cy, 2f + tLen, cy, tP)
            cvs.drawLine(px - 2f, cy, px - 2f - tLen, cy, tP)
            cvs.drawCircle(cx, cy, 2.5f, glowPaint)
            cvs.drawCircle(cx, cy, 2.5f, fillPaint)
        }
        else -> { // "default" — real-proportioned OS pointer arrow.
            // FIX (cursor shape correction): the old hand-picked polygon read as an
            // arbitrary shard, not a recognizable pointer. This path is traced from
            // the standard "cursor-arrow" select-tool glyph used across desktop OSes
            // and UI kits (tall arrowhead, straight left edge, notched tail flag),
            // reproduced here as plain geometry — not copied artwork — scaled into
            // our px canvas. Native viewBox is 15×15 with the tip near (3, 0).
            val s = px / 15f
            fun x(v: Float) = v * s
            fun y(v: Float) = v * s
            val path = android.graphics.Path().apply {
                moveTo(x(3.29227f), y(0.048984f))
                cubicTo(x(3.47033f), y(-0.032338f), x(3.67946f), y(-0.00228214f), x(3.8274f), y(0.125891f))
                lineTo(x(12.8587f), y(7.95026f))
                cubicTo(x(13.0134f), y(8.08432f), x(13.0708f), y(8.29916f), x(13.0035f), y(8.49251f))
                cubicTo(x(12.9362f), y(8.68586f), x(12.7578f), y(8.81866f), x(12.5533f), y(8.82768f))
                lineTo(x(9.21887f), y(8.97474f))
                lineTo(x(11.1504f), y(13.2187f))
                cubicTo(x(11.2648f), y(13.47f), x(11.1538f), y(13.7664f), x(10.9026f), y(13.8808f))
                lineTo(x(8.75024f), y(14.8613f))
                cubicTo(x(8.499f), y(14.9758f), x(8.20255f), y(14.8649f), x(8.08802f), y(14.6137f))
                lineTo(x(6.15339f), y(10.3703f))
                lineTo(x(3.86279f), y(12.7855f))
                cubicTo(x(3.72196f), y(12.934f), x(3.50487f), y(12.9817f), x(3.31479f), y(12.9059f))
                cubicTo(x(3.1247f), y(12.8301f), x(3f), y(12.6461f), x(3f), y(12.4414f))
                lineTo(x(3f), y(0.503792f))
                cubicTo(x(3f), y(0.308048f), x(3.11422f), y(0.130306f), x(3.29227f), y(0.048984f))
                close()
            }
            val shadowP = android.graphics.Paint().apply {
                isAntiAlias = true; color = android.graphics.Color.argb(110, 0, 0, 0)
                style = android.graphics.Paint.Style.FILL
            }
            val whiteFill = android.graphics.Paint().apply {
                isAntiAlias = true; color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.FILL
            }
            val darkOutline = android.graphics.Paint().apply {
                isAntiAlias = true; color = android.graphics.Color.argb(230, 15, 15, 20)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = px * 0.045f
                strokeJoin  = android.graphics.Paint.Join.ROUND
            }
            val accentStroke = android.graphics.Paint().apply {
                isAntiAlias = true; color = accent; style = android.graphics.Paint.Style.STROKE
                strokeWidth = px * 0.028f; strokeJoin = android.graphics.Paint.Join.ROUND
            }
            // Soft glow behind the whole silhouette so it reads clearly over any wallpaper
            cvs.drawPath(path, glowPaint)
            // Drop shadow, offset down-right like a real rendered pointer
            cvs.save(); cvs.translate(px * 0.035f, px * 0.05f); cvs.drawPath(path, shadowP); cvs.restore()
            cvs.drawPath(path, whiteFill)
            cvs.drawPath(path, darkOutline)   // true-to-life dark outline for contrast on any background
            cvs.drawPath(path, accentStroke)  // thin accent hairline on top for brand identity
        }
    }
    return bmp
}

// ── Starfield Background ──────────────────────────────────────────────────────
@Composable
fun StarfieldBackground(
    modifier:   Modifier = Modifier,
    starCount:  Int      = 100,
    isDark:     Boolean? = null,
    content:    @Composable BoxScope.() -> Unit
) {
    val spaceColors    = LocalSpaceColors.current
    val dark           = isDark ?: spaceColors.isDark
    val gradientColors = spaceColors.backgroundGradient
    val accentColor    = spaceColors.accent
    val accentSecondary = spaceColors.accentSecondary

    // BUG-13 FIX: pause all infinite animations when app is backgrounded to save battery
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // PERF-FIX: twinkle/nebula used to be driven by Animatable.animateTo(tween(...)),
    // which resamples — and forces a Canvas redraw — on *every* display frame (up to
    // 120 times/second on high-refresh-rate phones) for a decorative effect that only
    // needs to look smooth over several seconds. This competes for frame budget with
    // anything else happening on screen (scrolling the connection list, navigating
    // away, typing in a dialog on top). Driving both phases manually at a fixed
    // ~20 updates/second keeps the same visual motion while cutting the number of
    // Canvas redraws (and the per-star brightness/position math that goes with each
    // one) by roughly 3-6x depending on the device's refresh rate.
    var twinkle by remember { mutableFloatStateOf(0f) }
    var nebulaShift by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isResumed) {
        if (isResumed) {
            val stepMs = 50L                // ~20 updates/second
            val twinkleCycleMs = 8000f       // full 0 -> 1 -> 0 cycle
            val nebulaCycleMs = 12000f       // full 0 -> 1 cycle
            var elapsedMs = 0f
            while (true) {
                delay(stepMs)
                elapsedMs += stepMs
                val twinklePhase = (elapsedMs % twinkleCycleMs) / twinkleCycleMs
                twinkle = if (twinklePhase < 0.5f) twinklePhase * 2f else (1f - twinklePhase) * 2f
                nebulaShift = (elapsedMs % nebulaCycleMs) / nebulaCycleMs
            }
        }
    }

    // PERF-FIX: fewer stars means fewer per-frame trig calls and draw calls with
    // barely any visible difference in density (100 -> 72, 8 -> 6 feature stars).
    val stars = remember(starCount) {
        val count = (starCount * 0.72f).toInt().coerceAtLeast(1)
        List(count) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) }
    }
    // Larger, brighter "feature" stars
    val bigStars = remember { List(6) { Triple(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Background gradient
            drawRect(
                brush = if (gradientColors.size > 1)
                    Brush.verticalGradient(gradientColors)
                else
                    Brush.verticalGradient(listOf(gradientColors[0], gradientColors[0]))
            )

            if (dark) {
                // ── Dark mode: stars + nebula glow orbs ──────────────────────
                // Nebula glow orbs — animated drift
                val driftX = sin(nebulaShift * 2 * PI.toFloat()) * size.width * 0.05f
                val driftY = cos(nebulaShift * 2 * PI.toFloat()) * size.height * 0.03f
                drawCircle(
                    brush  = Brush.radialGradient(
                        listOf(accentColor.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.75f + driftX, size.height * 0.15f + driftY),
                        radius = size.width * 0.45f
                    ),
                    radius = size.width * 0.45f,
                    center = Offset(size.width * 0.75f + driftX, size.height * 0.15f + driftY)
                )
                drawCircle(
                    brush  = Brush.radialGradient(
                        listOf(accentSecondary.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(size.width * 0.15f - driftX, size.height * 0.72f - driftY),
                        radius = size.width * 0.38f
                    ),
                    radius = size.width * 0.38f,
                    center = Offset(size.width * 0.15f - driftX, size.height * 0.72f - driftY)
                )
                // Small stars
                stars.forEach { (xFrac, yFrac, factor) ->
                    val brightness = 0.35f + factor * 0.65f *
                            (0.7f + 0.3f * sin(twinkle * PI.toFloat() * 2 + factor * 13))
                    drawCircle(
                        color  = Color.White.copy(alpha = brightness),
                        radius = 0.8f + factor * 1.8f,
                        center = Offset(xFrac * size.width, yFrac * size.height)
                    )
                }
                // Big "feature" stars with diffraction spikes
                bigStars.forEach { (xFrac, yFrac, factor) ->
                    val pulse = 0.6f + 0.4f * sin(twinkle * PI.toFloat() * 1.5f + factor * 7)
                    val cx = xFrac * size.width
                    val cy = yFrac * size.height
                    val r  = 2.5f + factor * 2f
                    drawCircle(color = Color.White.copy(alpha = 0.9f * pulse), radius = r, center = Offset(cx, cy))
                    drawCircle(
                        color = accentColor.copy(alpha = 0.3f * pulse),
                        radius = r * 3, center = Offset(cx, cy)
                    )
                    val spikeLen = r * 4 * pulse
                    val spikePaint = Stroke(width = 0.8f)
                    drawLine(
                        Color.White.copy(alpha = 0.5f * pulse),
                        Offset(cx - spikeLen, cy), Offset(cx + spikeLen, cy), strokeWidth = 1f
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.5f * pulse),
                        Offset(cx, cy - spikeLen), Offset(cx, cy + spikeLen), strokeWidth = 1f
                    )
                }
            } else {
                // ── Light mode: subtle geometric "star-map" dots + accent gradient ──
                val driftX = kotlin.math.sin(nebulaShift * 2 * kotlin.math.PI.toFloat()) * size.width * 0.04f
                val driftY = kotlin.math.cos(nebulaShift * 2 * kotlin.math.PI.toFloat()) * size.height * 0.025f
                // Soft accent glow pools
                drawCircle(
                    brush  = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(accentColor.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(size.width * 0.80f + driftX, size.height * 0.12f + driftY),
                        radius = size.width * 0.50f
                    ),
                    radius = size.width * 0.50f,
                    center = Offset(size.width * 0.80f + driftX, size.height * 0.12f + driftY)
                )
                drawCircle(
                    brush  = androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(accentSecondary.copy(alpha = 0.05f), Color.Transparent),
                        center = Offset(size.width * 0.10f - driftX, size.height * 0.80f - driftY),
                        radius = size.width * 0.40f
                    ),
                    radius = size.width * 0.40f,
                    center = Offset(size.width * 0.10f - driftX, size.height * 0.80f - driftY)
                )
                // Subtle dot grid — a "star map" at very low opacity
                stars.forEach { (xFrac, yFrac, factor) ->
                    if (factor > 0.60f) {  // only ~40% of dots show
                        drawCircle(
                            color  = accentColor.copy(alpha = 0.05f + factor * 0.06f),
                            radius = 1.2f + factor * 1.5f,
                            center = Offset(xFrac * size.width, yFrac * size.height)
                        )
                    }
                }
            }
        }
        content()
    }
}

// ── RDP Profile Card ──────────────────────────────────────────────────────────
@Composable
// LAST-CONNECTED-ON-CARD FEATURE: profile.lastConnected has been stored in
// Room since the very first version (it drives Favorites/widget sort order —
// see MainViewModel.kt / SystemsGoAppWidgetProvider.kt) but was never
// rendered anywhere on the card itself, only inside the full History screen.
// android.text.format.DateUtils.getRelativeTimeSpanString gives a localized
// "3 hours ago"/"قبل 3 ساعات"-style string for free (it already ships
// Arabic plurals/phrasing, matching this app's bilingual string policy)
// without hand-rolling relative-time formatting or a new string resource
// per unit of time.
@Composable
private fun LastConnectedLabel(
    lastConnectedMillis: Long,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall,
    textAlign: TextAlign? = null,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val relative = remember(lastConnectedMillis) {
        android.text.format.DateUtils.getRelativeTimeSpanString(
            lastConnectedMillis,
            System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }
    Text(
        text = stringResource(R.string.last_connected_label, relative),
        style = style,
        color = CometTail.copy(alpha = 0.75f),
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier
    )
}

// ── Share via QR (QR-SHARE FEATURE) ───────────────────────────────────────
// Missing counterpart to QrScannerActivity/QrConnectionParser: turns a saved
// profile back into a scannable QR code so it can be handed to a colleague
// instead of them re-typing every field by hand. Fully-qualified zxing/
// android.graphics references below (no top-level imports) since this file
// already has its own Compose `Color`/`Image` in scope and zxing's own
// `BarcodeFormat`/`Bitmap` types would otherwise need aliasing.

/**
 * Renders [content] as a black-on-white QR [ImageBitmap], or null if it can't be encoded
 * (e.g. payload too long for a QR).
 *
 * FILE-TRANSFER-QR FEATURE: made internal (was private) so [com.systemsgo.hex.ui.screens.FileTransferScreen]
 * can reuse the same encoder for the HTTP file-server share link, instead of duplicating
 * the zxing/Bitmap boilerplate in a second file.
 */
internal fun generateQrImageBitmap(content: String, sizePx: Int = 512): ImageBitmap? {
    return try {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.MARGIN to 1,
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
        )
        val matrix: com.google.zxing.common.BitMatrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

@Composable
fun ShareConnectionQrDialog(
    profile: RdpProfile,
    onDismiss: () -> Unit,
) {
    // SECURITY: defaults to *excluding* username/password from the encoded
    // payload — same reasoning CryptoHelper/SQLCipher encryption elsewhere in
    // this app follows for credentials at rest: a QR code is trivially
    // photographed, screen-recorded, or seen by anyone standing nearby, so
    // plaintext creds shouldn't ride along unless the person explicitly
    // opts in and has been shown the warning below.
    var includeCredentials by rememberSaveable(profile.id) { mutableStateOf(false) }
    val hasCredentials = profile.username.isNotBlank() || profile.password.isNotBlank()

    val payload = remember(profile.id, profile.host, profile.port, profile.username,
        profile.password, profile.protocolType, profile.webUrl, includeCredentials) {
        QrConnectionEncoder.encode(profile, includeCredentials = includeCredentials)
    }
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(payload) {
        qrBitmap = withContext(Dispatchers.Default) { generateQrImageBitmap(payload) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(NebulaSurface)
                .border(1.dp, CardBorderColor, RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.share_via_qr_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = StarDust
                )
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = CometTail
                )
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = qrBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = stringResource(R.string.share_via_qr_title),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        )
                    } else {
                        CircularProgressIndicator(color = PulsarCyan, strokeWidth = 2.dp)
                    }
                }
                if (hasCredentials) {
                    SpaceSwitch(
                        label = stringResource(R.string.share_via_qr_include_credentials),
                        checked = includeCredentials,
                        onCheckedChange = { includeCredentials = it },
                    )
                    if (includeCredentials) {
                        Text(
                            text = stringResource(R.string.share_via_qr_credentials_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = ConnectingAmber,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                SpaceButton(
                    text = stringResource(R.string.close),
                    onClick = onDismiss,
                    variant = ButtonVariant.GHOST,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun RdpProfileCard(
    profile:      RdpProfile,
    onConnect:    () -> Unit,
    onEdit:       () -> Unit,
    onDelete:     () -> Unit,
    onWakeOnLan:  (() -> Unit)? = null,
    // WAKE-CONNECT FEATURE: sends the Magic Packet, waits for the host to come
    // online, then connects automatically — a single tap that replaces
    // "Wake" followed by a manual "Connect" once the machine has booted.
    // Optional (like onWakeOnLan) so existing call sites keep compiling; null
    // hides the menu item.
    onWakeAndConnect: (() -> Unit)? = null,
    // PINNED-SHORTCUT FEATURE: optional so existing call sites keep compiling
    // unchanged; null hides the menu item entirely (e.g. if a future caller
    // doesn't want to offer it).
    onPinShortcut: (() -> Unit)? = null,
    // TAG-FILTER-UX: tapping a tag chip on the card jumps straight to filtering
    // the list by that tag (mirrors selectFolder/selectTag already existing on
    // MainViewModel — this was the missing UI wiring). Null keeps existing
    // call sites unaffected and the chips non-interactive.
    onTagClick:    ((String) -> Unit)? = null,
    // FAVORITES FEATURE: toggles profile.isFavorite. Null (default) hides the
    // star button entirely, so any other existing call site of this shared
    // composable keeps compiling and rendering unchanged.
    onToggleFavorite: (() -> Unit)? = null,
    // PIN-CONNECTION FEATURE: toggles profile.isPinned (MainViewModel.togglePin).
    // Null (default) hides both the quick-pin icon and the "Pin/Unpin" dropdown
    // row entirely — same optional pattern as onToggleFavorite/onPinShortcut
    // above, so every existing call site keeps compiling and rendering exactly
    // as before until it's explicitly wired up.
    onTogglePin: (() -> Unit)? = null,
    // DUPLICATE-CONNECTION FEATURE: same optional-callback pattern as every
    // other row here — only shows up once the caller wires onDuplicate.
    // Sits between the pin/select rows and the destructive Delete row,
    // never adjacent to Delete, so a slip of the thumb can't turn "clone
    // this" into "remove this".
    onDuplicate: (() -> Unit)? = null,
    // QR-SHARE FEATURE: same optional-callback pattern as onDuplicate right
    // above — only shows up once the caller wires onShareQr. Opens
    // ShareConnectionQrDialog for this profile at the call site (HomeScreen),
    // since that dialog needs the full RdpProfile this card already has in
    // its own closure but this composable's other callbacks deliberately
    // don't pass around.
    onShareQr: (() -> Unit)? = null,
    // CONNECTION-STATUS-INDICATOR FEATURE: live per-card status badge data
    // from MainViewModel.cardStatuses[profile.id]. Optional/null (same
    // pattern as onTogglePin/onToggleFavorite above) so any existing call
    // site keeps compiling and rendering unchanged — null hides the badge
    // entirely rather than showing a misleading explicit Offline state for
    // a card that was never wired up to the feature at all.
    statusInfo: CardStatusInfo? = null,
    // PIN-CONNECTION FEATURE — multi-selection: while isSelectionMode is true,
    // the whole-card "tap to connect" layer becomes "tap to toggle selection"
    // and a checkbox fades in at the start of the header row instead of the
    // protocol badge. onToggleSelect is expected to be non-null whenever
    // isSelectionMode is true (the caller drives both from the same
    // MainViewModel.isSelectionMode/selectedProfileIds pair). Both default to
    // false/null so no existing call site is affected.
    isSelectionMode: Boolean = false,
    isSelected:      Boolean = false,
    onToggleSelect:  (() -> Unit)? = null,
    modifier:     Modifier = Modifier,
    hapticEnabled: Boolean = true,   // FIX #1: تمرير إعداد hapticFeedback للـ SwipeableCard
    actionPending: Boolean = false,  // BUGFIX-UI-1: تمرير حالة الـ Dialog المفتوح للـ SwipeableCard
    // CARD-DECLUTTER: the ⋮ overflow button that used to open this menu is
    // gone (see below) — Edit/Delete/etc. now open via a long-press on the
    // card instead (wired in ReorderableProfileCard). That means the open/
    // closed state has to be hoisted out here as a controlled value rather
    // than kept as private `remember` state, so the long-press caller can
    // drive it directly; onMenuExpandedChange doubles as that setter.
    menuExpanded:         Boolean = false,
    onMenuExpandedChange: (Boolean) -> Unit = {},  // BUGFIX-UI-9
    // SNMP FEATURE: opens SnmpManagementActivity for this profile's SNMP
    // monitoring add-on (see RdpProfile.snmpMonitoringEnabled). Same optional
    // pattern as onWakeOnLan/onPinShortcut above — null hides the menu row
    // entirely, and the row only shows at all once snmpMonitoringEnabled is
    // also true (an SNMP-protocol profile already has its own primary
    // "Connect" action for this, so this row is for every *other* protocol).
    onOpenSnmpDashboard: (() -> Unit)? = null,
) {
    // Last-frame thumbnail
    val context  = androidx.compose.ui.platform.LocalContext.current
    val lastFrame by produceState<ImageBitmap?>(initialValue = null, profile.id) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.systemsgo.hex.util.LastFrameStore.load(context, profile.id)?.asImageBitmap()
        }
    }

    val accent    = PulsarCyan
    val secondary = QuantumBlue

    // NESTED-CLICK FIX: `.pressScale(onClick = onConnect)` used to sit on the outer
    // card Box, so its click region covered the *entire* card — including the MoreVert
    // menu button, the "Wake on LAN" button, and the "Connect" button nested inside it.
    // A tap on "Wake on LAN" (a genuinely different, non-connecting action) landed
    // inside two click regions at once, same as the SettingsToggle/SettingsCard/tab-close
    // issues fixed elsewhere. Worst case here isn't just a visual flicker: it risked
    // launching an actual connection attempt at the same time the user only meant to
    // send a Wake-on-LAN packet or open the ⋮ menu.
    // Fix: the whole-card "tap anywhere to connect" behavior now lives on its own
    // full-size, invisible layer placed *underneath* the header/menu/buttons in the Box's
    // z-order. Plain layout composables (Column, Row, Text, Icon) never intercept touch
    // in Compose, so a tap passes straight through them to this layer — except where a
    // real interactive child (MoreVert IconButton, Wake button, Connect button) is
    // stacked on top and claims the touch for itself first. That makes the two kinds of
    // click structurally unable to overlap, regardless of any single widget's own
    // consume-vs-propagate behavior.
    val connectInteractionSource = remember { MutableInteractionSource() }
    val isConnectPressed by connectInteractionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue   = if (isConnectPressed) 0.96f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
        label         = "card_press_scale"
    )
    val soundManager = LocalSoundManager.current

    // PIN-CONNECTION FEATURE: accessibility strings shared by the badge, the
    // quick-pin button and the selection checkbox below.
    val pinnedBadgeDescription = stringResource(R.string.pinned_badge_desc)
    val pinActionLabel   = stringResource(if (profile.isPinned) R.string.unpin_connection else R.string.pin_connection)
    val quickPinLabel     = stringResource(if (profile.isPinned) R.string.quick_unpin_action else R.string.quick_pin_action)
    val selectLabel        = stringResource(if (isSelected) R.string.deselect_connection else R.string.select_connection)

    // CONNECTION-STATUS-INDICATOR FEATURE: long-press on the status badge
    // opens this dialog; hoisted here (rather than inside SessionStatusBadge
    // itself) since the dialog needs the full RdpProfile, which the badge
    // composable deliberately doesn't take (see SessionStatusIndicator.kt).
    var showStatusDetails by remember { mutableStateOf(false) }

    SwipeableProfileCard(
        onDelete       = onDelete,
        onEdit         = onEdit,
        modifier       = modifier,
        hapticEnabled  = hapticEnabled,   // FIX #1
        actionPending  = actionPending,   // BUGFIX-UI-1
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(cardScale)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(GradientCardStart, GradientCardEnd)))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(listOf(accent.copy(0.4f), secondary.copy(0.15f))),
                    shape = RoundedCornerShape(18.dp)
                )
        ) {
            // Whole-card "tap to connect" layer — see NESTED-CLICK FIX note above.
            // Declared first so it sits at the bottom of the Box's z-order; any real
            // button declared later below will sit on top of it and win hit-testing
            // for its own bounds.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = connectInteractionSource,
                        indication        = null,
                        // PIN-CONNECTION FEATURE — selection mode: while selecting,
                        // a tap anywhere on the card toggles that card's selection
                        // instead of connecting, exactly like tapping its checkbox.
                        // This mirrors the long-press-to-select pattern from other
                        // multi-select lists (Gmail, Photos, etc.) — once selection
                        // mode is active, taps stay "safe" (never launch a session)
                        // until the user explicitly cancels or acts on the selection.
                        onClick = {
                            if (isSelectionMode) {
                                onToggleSelect?.invoke()
                            } else {
                                soundManager?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.35f)
                                onConnect()
                            }
                        }
                    )
            )
            // Corner glow accent
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset((-20).dp, (-20).dp)
                    .background(
                        brush = Brush.radialGradient(listOf(accent.copy(0.15f), Color.Transparent)),
                        shape = RoundedCornerShape(50)
                    )
            )

            // PIN-CONNECTION FEATURE: small pinned badge in the top-start corner
            // (top-end in RTL — handled automatically by Alignment.TopStart,
            // which Compose mirrors per LocalLayoutDirection same as every other
            // start/end-based alignment in this file). AnimatedVisibility gives
            // a soft scale+fade in/out exactly on pin/unpin instead of an abrupt
            // pop, matching the Material 3 motion used elsewhere in the app.
            androidx.compose.animation.AnimatedVisibility(
                visible  = profile.isPinned,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                enter = androidx.compose.animation.scaleIn(
                    animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)
                ) + androidx.compose.animation.fadeIn(),
                exit  = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(PulsarCyan.copy(alpha = 0.22f))
                        .border(1.dp, PulsarCyan.copy(alpha = 0.5f), RoundedCornerShape(7.dp))
                        .semantics { contentDescription = pinnedBadgeDescription },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Filled.PushPin,
                        contentDescription = null,
                        tint               = PulsarCyan,
                        modifier           = Modifier.size(13.dp)
                    )
                }
            }

            // Last-frame backdrop
            lastFrame?.let { img ->
                androidx.compose.foundation.Image(
                    bitmap             = img,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(18.dp))
                        .alpha(0.18f)
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(listOf(
                                GradientCardStart.copy(0.88f),
                                GradientCardStart.copy(0.35f),
                                GradientCardEnd.copy(0.9f)
                            ))
                        )
                )
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                // Header row
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier          = Modifier.weight(1f)
                    ) {
                        // PIN-CONNECTION FEATURE — multi-selection: a checkbox
                        // slides/fades in ahead of the protocol badge whenever
                        // selection mode is active, so the badge never has to
                        // reflow abruptly. 44.dp minimum touch target for
                        // accessibility even though the visual box is smaller.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isSelectionMode,
                            enter   = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(),
                            exit    = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onToggleSelect?.invoke() },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .semantics {
                                            contentDescription = selectLabel
                                            role = Role.Checkbox
                                        },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor   = PulsarCyan,
                                        uncheckedColor = CometTail
                                    )
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                        Box {
                            ProtocolIconBadge(profile.protocolType, profileId = profile.id)
                            // CONNECTION-STATUS-INDICATOR FEATURE: anchored to
                            // the protocol icon's own corner rather than a new
                            // standalone ring, so the badge reads as "this
                            // protocol icon, right now" — also naturally gives
                            // the optional "colored ring by protocol" polish
                            // for free, since ProtocolIconBadge's own border
                            // is already tinted by protocol color.
                            if (statusInfo != null) {
                                SessionStatusBadge(
                                    info           = statusInfo,
                                    connectionName = profile.name,
                                    onLongPress    = { showStatusDetails = true },
                                    modifier       = Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(3.dp, 3.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                profile.name,
                                style      = MaterialTheme.typography.titleMedium,
                                color      = StarDust,
                                fontWeight = FontWeight.Bold,
                                maxLines   = 1,
                                overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                "${profile.host}:${profile.port}",
                                style    = MaterialTheme.typography.bodySmall,
                                color    = CometTail,
                                maxLines = 1,
                                // BUGFIX-UI: was missing overflow handling — unlike the
                                // profile name right above it, a long hostname would be
                                // abruptly clipped mid-character instead of showing "…".
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            // LAST-CONNECTED-ON-CARD FEATURE: profile.lastConnected was
                            // already stored (used to sort Favorites/widgets) but never
                            // surfaced on the card itself, so there was no way to tell
                            // "when did I last connect to this server" without opening
                            // History. 0L means "never connected" (see MainViewModel's
                            // RdpProfile() default) so it's hidden rather than showing a
                            // misleading 1970 date.
                            if (profile.lastConnected > 0L) {
                                LastConnectedLabel(profile.lastConnected)
                            }
                            // CONNECTION-STATUS-INDICATOR FEATURE: live "00:15:42"
                            // session-duration text — only exists in composition
                            // while actually CONNECTED, see SessionUptimeText's doc
                            // comment for why that keeps the concurrent-ticker count
                            // bounded regardless of how many cards are rendered.
                            val connectedSince = statusInfo?.connectedSinceMillis
                            if (statusInfo?.status == CardConnectionStatus.CONNECTED && connectedSince != null) {
                                SessionUptimeText(connectedSinceMillis = connectedSince)
                            }
                        }
                    }

                    // Status dot + accessible overflow menu (alternative to swipe
                    // gestures, which conflict with TalkBack's horizontal swipe
                    // navigation and are otherwise undiscoverable for new users)
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // UI FIX: removed the standalone PulsingDot status indicator —
                        // it duplicated the protocol/connection info already shown in the
                        // header and cluttered the card with an extra floating mark.
                        // FAVORITES FEATURE: filled star = favorite, outlined star =
                        // not favorite. Sits to the left of the ⋮ menu, matching its
                        // 44.dp touch target so both stay easy to tap without
                        // crowding the header row.
                        // CARD-DECLUTTER: the ⋮ "more options" chip (with its circular
                        // gradient/sweep-border background) has been removed entirely.
                        // The star now sits alone in its old spot — a plain tap
                        // toggles favorite; Edit/Delete/etc. open via a long-press
                        // anywhere on the card instead (see ReorderableProfileCard,
                        // which drives `menuExpanded` from a held-without-moving
                        // long-press rather than a dedicated button). The Box below
                        // still anchors the DropdownMenu to this same corner.
                        //
                        // PIN-CONNECTION FEATURE: this whole cluster (quick-pin,
                        // favorite star, overflow menu) is hidden while selection
                        // mode is active. It's a real interactive IconButton stacked
                        // on top of the whole-card tap-to-select layer (same z-order
                        // trick as NESTED-CLICK FIX above), so it would otherwise
                        // steal the tap and toggle favorite/pin instead of selecting
                        // — hiding it keeps every tap on the card unambiguous.
                        if (!isSelectionMode) {
                        Box {
                            if (onTogglePin != null) {
                                IconButton(
                                    onClick  = onTogglePin,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .semantics { contentDescription = quickPinLabel }
                                ) {
                                    Icon(
                                        imageVector = if (profile.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                        contentDescription = null,
                                        tint     = if (profile.isPinned) PulsarCyan else CometTail,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            if (onToggleFavorite != null) {
                                IconButton(
                                    onClick  = onToggleFavorite,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        imageVector = if (profile.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = stringResource(
                                            if (profile.isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites
                                        ),
                                        tint     = if (profile.isFavorite) SolarFlare else CometTail,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            // UI-FIX: the dropdown itself was a stock, borderless Material
                            // surface — flat against the glowing, bordered cards around it.
                            // Give it the same glowing-border + shape treatment as dialogs
                            // and cards elsewhere, and give each row's icon its own tinted
                            // badge (matching AddOptionRow / ProtocolIconBadge) instead of a
                            // bare glyph sitting directly on the menu background.
                            DropdownMenu(
                                expanded         = menuExpanded,
                                onDismissRequest = { onMenuExpandedChange(false) },
                                shape            = RoundedCornerShape(16.dp),
                                containerColor   = NebulaSurface,
                                tonalElevation   = 0.dp,
                                shadowElevation  = 10.dp,
                                border           = BorderStroke(1.dp, CardBorderColor),
                                modifier         = Modifier.padding(vertical = 4.dp)
                            ) {
                                MenuActionItem(
                                    text    = stringResource(R.string.edit),
                                    icon    = Icons.Outlined.Edit,
                                    tint    = PulsarCyan,
                                    onClick = { onMenuExpandedChange(false); onEdit() }
                                )
                                // PIN-CONNECTION FEATURE: same optional-callback pattern
                                // as every other row here — only shows up once the
                                // caller wires onTogglePin. Label/icon flip based on
                                // the connection's current pinned state.
                                if (onTogglePin != null) {
                                    MenuActionItem(
                                        text    = pinActionLabel,
                                        icon    = if (profile.isPinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                                        tint    = PulsarCyan,
                                        onClick = { onMenuExpandedChange(false); onTogglePin() }
                                    )
                                }
                                // PIN-CONNECTION FEATURE — multi-selection entry point: a
                                // dedicated "Select" row in the existing long-press-opened
                                // menu is the entry point into isSelectionMode, chosen over
                                // repurposing the long-press gesture itself, since long-press
                                // here already means either "open this menu" or "start a
                                // reorder drag" (see LONG-PRESS DISAMBIGUATION in
                                // ReorderableProfileCard) — overloading it a third way would
                                // make all three ambiguous. Only shown once the caller wires
                                // onToggleSelect (same optional pattern as every row above).
                                if (onToggleSelect != null) {
                                    MenuActionItem(
                                        text    = selectLabel,
                                        icon    = if (isSelected) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                                        tint    = PulsarCyan,
                                        onClick = { onMenuExpandedChange(false); onToggleSelect() }
                                    )
                                }
                                if (profile.wolEnabled && onWakeOnLan != null) {
                                    MenuActionItem(
                                        text    = stringResource(R.string.wol_wake),
                                        icon    = Icons.Outlined.PowerSettingsNew,
                                        tint    = ConnectingAmber,
                                        onClick = { onMenuExpandedChange(false); onWakeOnLan() }
                                    )
                                }
                                if (profile.wolEnabled && onWakeAndConnect != null) {
                                    MenuActionItem(
                                        text    = stringResource(R.string.wol_wake_and_connect),
                                        icon    = Icons.Outlined.FlashOn,
                                        tint    = ConnectingAmber,
                                        onClick = { onMenuExpandedChange(false); onWakeAndConnect() }
                                    )
                                }
                                if (profile.snmpMonitoringEnabled && onOpenSnmpDashboard != null) {
                                    MenuActionItem(
                                        text    = stringResource(R.string.snmp_dashboard_menu_item),
                                        icon    = Icons.Outlined.NetworkCheck,
                                        tint    = SolarFlare,
                                        onClick = { onMenuExpandedChange(false); onOpenSnmpDashboard() }
                                    )
                                }
                                if (onPinShortcut != null) {
                                    MenuActionItem(
                                        text    = stringResource(R.string.pin_shortcut),
                                        icon    = Icons.Outlined.AddToHomeScreen,
                                        tint    = PulsarCyan,
                                        onClick = { onMenuExpandedChange(false); onPinShortcut() }
                                    )
                                }
                                if (onDuplicate != null) {
                                    MenuActionItem(
                                        text    = stringResource(R.string.duplicate_connection),
                                        icon    = Icons.Outlined.ContentCopy,
                                        tint    = PulsarCyan,
                                        onClick = { onMenuExpandedChange(false); onDuplicate() }
                                    )
                                }
                                if (onShareQr != null) {
                                    MenuActionItem(
                                        text    = stringResource(R.string.share_via_qr),
                                        icon    = Icons.Outlined.QrCode,
                                        tint    = PulsarCyan,
                                        onClick = { onMenuExpandedChange(false); onShareQr() }
                                    )
                                }
                                MenuActionItem(
                                    text      = stringResource(R.string.delete),
                                    icon      = Icons.Outlined.Delete,
                                    tint      = ErrorRed,
                                    textColor = ErrorRed,
                                    onClick   = { onMenuExpandedChange(false); onDelete() }
                                )
                            }
                        }
                        } // end if (!isSelectionMode)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Info chips
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (profile.protocolType) {
                        ProtocolType.RDP -> {
                            InfoChip(Icons.Outlined.Person,   profile.username.ifEmpty { "—" })
                            InfoChip(Icons.Outlined.Security, if (profile.useNla) "NLA" else "RDP")
                            if (profile.gatewayEnabled)
                                InfoChip(Icons.Outlined.Hub, stringResource(R.string.chip_gateway))
                        }
                        ProtocolType.VNC -> {
                            InfoChip(Icons.Outlined.Monitor, "VNC")
                            if (profile.vncViewOnly)
                                IconBadge(Icons.Outlined.Visibility, contentDescription = stringResource(R.string.chip_view_only))
                            if (profile.vncRepeaterEnabled)
                                InfoChip(Icons.Outlined.Router, stringResource(R.string.vnc_repeater))
                            if (profile.vncListenModeEnabled)
                                InfoChip(Icons.Outlined.Router, stringResource(R.string.vnc_listen_mode))
                        }
                        ProtocolType.SSH -> {
                            InfoChip(Icons.Outlined.Person, profile.username.ifEmpty { "—" })
                            InfoChip(
                                Icons.Outlined.Key,
                                if (profile.sshAuthType == SshAuthType.PRIVATE_KEY) stringResource(R.string.chip_key) else stringResource(R.string.ssh_auth_password)
                            )
                        }
                        ProtocolType.TELNET -> {
                            InfoChip(Icons.Outlined.SettingsEthernet, "Telnet")
                            if (profile.telnetUseTls)
                                InfoChip(Icons.Outlined.Security, "TLS")
                        }
                        ProtocolType.RLOGIN -> {
                            InfoChip(Icons.Outlined.SettingsEthernet, "Rlogin")
                            InfoChip(Icons.Outlined.Person, profile.username.ifEmpty { "—" })
                        }
                        // MOSH FEATURE: same shape as RLOGIN above — username
                        // chip plus a key/password indicator like SSH, since
                        // Mosh's bootstrap phase is plain SSH auth.
                        ProtocolType.MOSH -> {
                            InfoChip(Icons.Outlined.SettingsEthernet, "Mosh")
                            InfoChip(Icons.Outlined.Person, profile.username.ifEmpty { "—" })
                            InfoChip(
                                Icons.Outlined.Key,
                                if (profile.sshAuthType == SshAuthType.PRIVATE_KEY) stringResource(R.string.chip_key) else stringResource(R.string.ssh_auth_password)
                            )
                        }
                        // SPICE-PROTOCOL FEATURE (Part 1/N): minimal placeholder chip —
                        // no SPICE-specific profile fields (TLS port, etc.) exist yet.
                        ProtocolType.SPICE -> {
                            InfoChip(Icons.Outlined.DesktopWindows, "SPICE")
                        }
                        // RTSP FEATURE: stream path + transport chips, mirroring
                        // how SSH/Telnet above show their own protocol-specific
                        // detail rather than just the generic host/port already
                        // shown elsewhere on the card.
                        ProtocolType.RTSP -> {
                            InfoChip(Icons.Outlined.Videocam, profile.rtspStreamPath.ifEmpty { "/" })
                            InfoChip(Icons.Outlined.SettingsEthernet, profile.rtspTransportMode)
                        }
                        ProtocolType.WEB -> {
                            InfoChip(Icons.Outlined.Web, stringResource(R.string.web_protocol_label))
                            if (profile.webTrustSelfSignedCertificate)
                                InfoChip(Icons.Outlined.Security, "TLS")
                        }
                        // REDFISH-IPMI FEATURE: same info-chip shape as every
                        // other protocol above.
                        ProtocolType.REDFISH -> {
                            InfoChip(Icons.Outlined.Dns, profile.username.ifEmpty { "—" })
                            if (profile.acceptSelfSignedCertificate)
                                InfoChip(Icons.Outlined.Security, "TLS")
                        }
                        ProtocolType.IPMI -> {
                            InfoChip(Icons.Outlined.Dns, profile.username.ifEmpty { "—" })
                            InfoChip(Icons.Outlined.AdminPanelSettings, profile.ipmiPrivilegeLevel)
                        }
                        // AMT-VPRO FEATURE: same info-chip shape as REDFISH
                        // above — username plus a TLS chip when amtUseTls is on.
                        // Phase 6 (CIRA setup UI): a relay-addressed profile
                        // shows a "CIRA" chip instead of the TLS chip, since
                        // amtUseTls is meaningless for it (see
                        // RdpProfile.ciraEnabled's doc comment — the two
                        // addressing modes are mutually exclusive).
                        ProtocolType.AMT -> {
                            InfoChip(Icons.Outlined.Memory, profile.username.ifEmpty { "—" })
                            if (profile.ciraEnabled)
                                InfoChip(Icons.Outlined.Hub, "CIRA")
                            else if (profile.amtUseTls)
                                InfoChip(Icons.Outlined.Security, "TLS")
                        }
                        // SERIAL-CONSOLE FEATURE: shows the transport mode
                        // and, for RFC 2217, the baud rate — the two things
                        // that most distinguish one Serial Console profile
                        // from another at a glance.
                        ProtocolType.SERIAL_CONSOLE -> {
                            InfoChip(Icons.Outlined.SettingsEthernet, profile.serialConsoleTransport.label)
                            if (profile.serialConsoleTransport == SerialRedirectMode.RFC_2217)
                                InfoChip(Icons.Outlined.Speed, "${profile.serialConsoleBaudRate} baud")
                        }
                        // RESTCONF FEATURE (Part 1/4): same info-chip shape as
                        // REDFISH above — auth method plus a TLS chip.
                        ProtocolType.RESTCONF -> {
                            InfoChip(Icons.Outlined.Api, profile.restconfAuthType)
                            if (profile.restconfUseHttps)
                                InfoChip(Icons.Outlined.Security, "TLS")
                        }
                        // NETCONF FEATURE: username plus the default datastore
                        // this profile opens on — the two things that most
                        // distinguish one NETCONF profile from another.
                        ProtocolType.NETCONF -> {
                            InfoChip(Icons.Outlined.Person, profile.username.ifEmpty { "—" })
                            InfoChip(Icons.Outlined.Storage, profile.netconfDefaultDatastore)
                        }
                        // MODBUS-TCP FEATURE (Part 2/2): unit id is the one
                        // thing that most distinguishes one Modbus profile
                        // from another sharing the same gateway/host.
                        ProtocolType.MODBUS_TCP -> {
                            InfoChip(Icons.Outlined.NetworkCheck, "Unit ${profile.modbusUnitId}")
                        }
                        // VIRTUALBOX-VRDE FEATURE (Part 1/N): same info-chip
                        // shape as every other protocol above — shows the
                        // configured VRDE auth-type hint (see
                        // RdpProfile.vrdeAuthType's doc comment).
                        ProtocolType.VIRTUALBOX_VRDE -> {
                            InfoChip(Icons.Outlined.DesktopWindows, profile.vrdeAuthType)
                        }
                        // VMWARE-VSPHERE FEATURE (Part 1/N): same info-chip
                        // shape as REDFISH/PROXMOX above — username plus which
                        // API family (REST/SOAP) this profile is set to use.
                        ProtocolType.VMWARE_VSPHERE -> {
                            InfoChip(Icons.Outlined.Dns, profile.username.ifEmpty { "—" })
                            InfoChip(Icons.Outlined.Api, profile.vsphereApiMode)
                        }
                    }
                    // WoL chip — shown for any protocol when Wake-on-LAN is configured
                    if (profile.wolEnabled && profile.wolMacAddress.isNotBlank()) {
                        // i18n FIX: was hardcoded "WoL" — use string resource.
                        InfoChip(Icons.Outlined.PowerSettingsNew, stringResource(R.string.chip_wol))
                    }
                }

                // Tag chips — feature: tags for saved connections. Capped at 4
                // visible tags so a heavily-tagged connection can't push the
                // card's height around; the rest are simply not shown here
                // (still filterable/searchable, just not all individually chipped).
                if (profile.tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        profile.tags.take(4).forEach { tag ->
                            InfoChip(
                                icon    = Icons.AutoMirrored.Outlined.Label,
                                text    = tag,
                                onClick = if (onTagClick != null) ({ onTagClick(tag) }) else null
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Connect row — optionally paired with Wake / Wake & Connect buttons
                if (profile.wolEnabled && onWakeOnLan != null) {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Wake button (amber accent, narrower) — sends the packet only
                        SpaceButton(
                            text     = stringResource(R.string.wol_wake),
                            onClick  = onWakeOnLan,
                            variant  = ButtonVariant.GHOST,
                            modifier = Modifier.weight(if (onWakeAndConnect != null) 0.32f else 0.42f)
                        )
                        // Wake & Connect button — one tap: wake, wait, then connect
                        if (onWakeAndConnect != null) {
                            SpaceButton(
                                text     = stringResource(R.string.wol_wake_and_connect_short),
                                onClick  = onWakeAndConnect,
                                variant  = ButtonVariant.GHOST,
                                modifier = Modifier.weight(0.32f)
                            )
                        }
                        // Connect button
                        SpaceButton(
                            text     = stringResource(R.string.connect),
                            onClick  = onConnect,
                            modifier = Modifier.weight(if (onWakeAndConnect != null) 0.36f else 0.58f)
                        )
                    }
                } else {
                    // Connect button
                    SpaceButton(
                        text    = stringResource(R.string.connect),
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // CONNECTION-STATUS-INDICATOR FEATURE: long-press detail popup, outside
    // SwipeableProfileCard's content lambda (it's a dialog/popup, not part of
    // the card's own layout) but still inside RdpProfileCard so it has direct
    // access to `profile` and the `statusInfo` this card was given.
    if (showStatusDetails && statusInfo != null) {
        SessionDetailsDialog(
            info      = statusInfo,
            profile   = profile,
            onDismiss = { showStatusDetails = false }
        )
    }
}

// ── Card overflow-menu row ─────────────────────────────────────────────────────
// UI-FIX (design feedback): DropdownMenuItem's default leadingIcon renders a bare
// glyph straight on the menu background. Every other icon in the app (protocol
// badges, AddOptionRow, dialog headers) sits inside a small tinted, rounded chip —
// wrapping the glyph the same way here keeps the "تعديل / حذف" row consistent
// with the rest of the space theme instead of looking like a stock Material menu.
@Composable
internal fun MenuActionItem(
    text:      String,
    icon:      androidx.compose.ui.graphics.vector.ImageVector,
    tint:      Color,
    textColor: Color = StarDust,
    onClick:   () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text, color = textColor) },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
            }
        },
        onClick = onClick
    )
}


// ── Protocol Icon Badge ────────────────────────────────────────────────────────
// UI-FIX (design feedback): the previous badge drew a full SVG "monitor" outline
// for every protocol, with only a tiny inner glyph differing (a grid for RDP, an
// eye for VNC, terminal dots for SSH). Icon() tints the whole vector a single
// flat color, so at 20dp all three collapsed into the same indistinct "screen"
// silhouette — hard to tell apart at a glance.
//
// Fixed by reusing the same clear, distinct icon set the app already uses for
// the protocol filter's empty states (DesktopWindows / Monitor / Terminal) —
// real, tidy glyphs instead of a custom hand-drawn shape, and consistent with
// the rest of the app instead of introducing a fourth representation.
@Composable
fun ProtocolIconBadge(type: ProtocolType, profileId: String? = null) {
    val color = when (type) {
        ProtocolType.RDP -> QuantumBlue
        ProtocolType.VNC -> VoidPurple
        ProtocolType.SSH -> PlasmaGreen
        ProtocolType.TELNET -> SolarFlare
        ProtocolType.RLOGIN -> SolarFlare
        ProtocolType.SPICE -> QuantumBlue
        ProtocolType.RTSP -> VoidPurple
        ProtocolType.WEB -> PulsarCyan
        ProtocolType.REDFISH -> PulsarCyan
        ProtocolType.IPMI -> SolarFlare
        ProtocolType.AMT -> PulsarCyan
        ProtocolType.SERIAL_CONSOLE -> SolarFlare
        ProtocolType.RESTCONF -> PulsarCyan
        ProtocolType.SNMP -> SolarFlare
        // NETCONF FEATURE: same "network device management" family as
        // REDFISH/IPMI/AMT color-wise.
        ProtocolType.NETCONF -> PulsarCyan
        // GUACAMOLE-PROTOCOL FEATURE: same "framebuffer desktop" family as RDP/SPICE.
        ProtocolType.GUACAMOLE -> QuantumBlue
        // MOSH FEATURE: same accent as TELNET/RLOGIN/SERIAL_CONSOLE.
        ProtocolType.MOSH -> SolarFlare
        // PROXMOX-API FEATURE: same "management API" family as RESTCONF/REDFISH.
        ProtocolType.PROXMOX -> PulsarCyan
        // MODBUS-TCP FEATURE (Part 2/2): same "device management" family as SNMP/NETCONF.
        ProtocolType.MODBUS_TCP -> SolarFlare
        ProtocolType.VIRTUALBOX_VRDE -> QuantumBlue
        ProtocolType.VMWARE_VSPHERE -> PulsarCyan
        ProtocolType.WAKE_ON_LAN -> SolarFlare
        ProtocolType.SFTP -> PlasmaGreen
        // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: same file-transfer family as SFTP above.
        ProtocolType.FTP -> PlasmaGreen
        ProtocolType.FTPS -> PlasmaGreen
        ProtocolType.WEBDAV -> PlasmaGreen
        ProtocolType.SMB -> PlasmaGreen
        ProtocolType.NFS -> PlasmaGreen
    }
    val icon = when (type) {
        ProtocolType.RDP -> Icons.Outlined.DesktopWindows
        ProtocolType.VNC -> Icons.Outlined.Monitor
        ProtocolType.SSH -> Icons.Outlined.Terminal
        ProtocolType.TELNET -> Icons.Outlined.SettingsEthernet
        ProtocolType.RLOGIN -> Icons.Outlined.SettingsEthernet
        ProtocolType.SPICE -> Icons.Outlined.DesktopWindows
        ProtocolType.RTSP -> Icons.Outlined.Videocam
        ProtocolType.WEB -> Icons.Outlined.Web
        ProtocolType.REDFISH -> Icons.Outlined.Dns
        ProtocolType.IPMI -> Icons.Outlined.SettingsRemote
        ProtocolType.AMT -> Icons.Outlined.Memory
        ProtocolType.SERIAL_CONSOLE -> Icons.Outlined.SettingsEthernet
        ProtocolType.RESTCONF -> Icons.Outlined.Api
        ProtocolType.SNMP -> Icons.Outlined.NetworkCheck
        ProtocolType.NETCONF -> Icons.Outlined.SettingsRemote
        // GUACAMOLE-PROTOCOL FEATURE: no dedicated artwork yet — reuse the desktop icon.
        ProtocolType.GUACAMOLE -> Icons.Outlined.DesktopWindows
        // MOSH FEATURE: same icon as TELNET/RLOGIN/SERIAL_CONSOLE.
        ProtocolType.MOSH -> Icons.Outlined.SettingsEthernet
        // PROXMOX-API FEATURE: same "management API" icon as RESTCONF/REDFISH.
        ProtocolType.PROXMOX -> Icons.Outlined.Dns
        // MODBUS-TCP FEATURE (Part 2/2): same icon as SNMP.
        ProtocolType.MODBUS_TCP -> Icons.Outlined.NetworkCheck
        ProtocolType.VIRTUALBOX_VRDE -> Icons.Outlined.DesktopWindows
        ProtocolType.VMWARE_VSPHERE -> Icons.Outlined.Dns
        ProtocolType.WAKE_ON_LAN -> Icons.Outlined.Wifi
        ProtocolType.SFTP -> Icons.Outlined.Terminal
        // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: same file-transfer family as SFTP above.
        ProtocolType.FTP -> Icons.Outlined.Terminal
        ProtocolType.FTPS -> Icons.Outlined.Terminal
        ProtocolType.WEBDAV -> Icons.Outlined.Terminal
        ProtocolType.SMB -> Icons.Outlined.Terminal
        ProtocolType.NFS -> Icons.Outlined.Terminal
    }

    // WEB-PORTAL-FAVICON FEATURE: if this badge is for a specific Web-portal
    // profile and a favicon has already been captured for it (see
    // WebPortalFaviconCache), show that instead of the generic globe/browser
    // glyph above — same "real site icon over generic protocol icon" idea a
    // browser's own tab strip uses. Re-checks whenever faviconVersion ticks
    // (a new icon was just captured somewhere) so a freshly-visited portal's
    // favicon appears next time this card recomposes, with no extra plumbing
    // needed from any of this composable's callers.
    var favicon by remember(type, profileId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    if (type == ProtocolType.WEB && profileId != null) {
        val context = LocalContext.current
        val version by com.systemsgo.hex.web.WebPortalFaviconCache.faviconVersion.collectAsStateWithLifecycle()
        LaunchedEffect(profileId, version) {
            favicon = com.systemsgo.hex.web.WebPortalFaviconCache.get(context, profileId)
        }
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color.copy(alpha = if (LocalSpaceColors.current.isDark) 0.18f else 0.12f), RoundedCornerShape(10.dp))
            .border(1.5.dp, color.copy(alpha = if (LocalSpaceColors.current.isDark) 0.4f else 0.5f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        val cachedFavicon = favicon
        if (cachedFavicon != null) {
            androidx.compose.foundation.Image(
                bitmap = cachedFavicon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(21.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint     = color,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

// ── Grid Profile Card (multi-column "cards" view) ───────────────────────────
// GRID-VIEW FEATURE: compact tile used when Home is showing more than one
// column at once (pinch-to-zoom / the grid-size button in the toolbar).
// Deliberately a *different* layout from RdpProfileCard's horizontal row —
// at 2-4 cards per row there isn't enough width for icon + star + two lines
// of text to sit side-by-side without crowding, so this uses a vertical
// "app tile" arrangement instead. Every element (icon size, font size, which
// lines are even shown) scales down as columnCount grows, which is what
// keeps the name/host text from ever overlapping or clipping mid-character
// at the smallest (3-4 column) size.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridProfileCard(
    profile:          RdpProfile,
    columnCount:      Int,
    onConnect:        () -> Unit,
    onEdit:           () -> Unit,
    onDelete:         () -> Unit,
    onWakeOnLan:      (() -> Unit)? = null,
    onWakeAndConnect: (() -> Unit)? = null,
    onPinShortcut:    (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    // PIN-CONNECTION FEATURE — same optional-callback / selection-mode contract
    // as RdpProfileCard above; see the comments there for the full rationale.
    onTogglePin:      (() -> Unit)? = null,
    // DUPLICATE-CONNECTION FEATURE: same optional pattern as RdpProfileCard.
    onDuplicate:      (() -> Unit)? = null,
    // QR-SHARE FEATURE: same optional pattern as onDuplicate above.
    onShareQr:        (() -> Unit)? = null,
    isSelectionMode:  Boolean = false,
    isSelected:       Boolean = false,
    onToggleSelect:   (() -> Unit)? = null,
    hapticEnabled:    Boolean = true,
    // CONNECTION-STATUS-INDICATOR FEATURE: same optional/null-hides-badge
    // contract as RdpProfileCard's own statusInfo param above.
    statusInfo:       CardStatusInfo? = null,
) {
    val accent    = PulsarCyan
    val secondary = QuantumBlue
    var menuExpanded by remember { mutableStateOf(false) }
    // CONNECTION-STATUS-INDICATOR FEATURE: see RdpProfileCard's identical
    // field for the full rationale (dialog needs `profile`, badge doesn't
    // take it).
    var showStatusDetails by remember { mutableStateOf(false) }
    val haptics      = androidx.compose.ui.platform.LocalHapticFeedback.current
    val soundManager = LocalSoundManager.current
    val pinnedBadgeDescription = stringResource(R.string.pinned_badge_desc)
    val pinActionLabel = stringResource(if (profile.isPinned) R.string.unpin_connection else R.string.pin_connection)
    val selectLabel      = stringResource(if (isSelected) R.string.deselect_connection else R.string.select_connection)

    // Scale every dimension down as more columns are packed onto one row.
    val iconSize   = when { columnCount <= 2 -> 40.dp; columnCount == 3 -> 32.dp; else -> 26.dp }
    val iconGlyph  = when { columnCount <= 2 -> 21.dp; columnCount == 3 -> 17.dp; else -> 14.dp }
    val titleStyle = when {
        columnCount <= 2 -> MaterialTheme.typography.titleSmall
        columnCount == 3 -> MaterialTheme.typography.labelLarge
        else             -> MaterialTheme.typography.labelMedium
    }
    // Past 3 columns there's no safe room left for a second line of text at
    // all (that's exactly the overlap the size control has to avoid), so the
    // host address and the inline star both move into the long-press menu.
    val showHost    = columnCount <= 3
    val showStar    = columnCount <= 3
    val cardPadding = when { columnCount <= 2 -> 12.dp; columnCount == 3 -> 9.dp; else -> 7.dp }

    val protocolColor = when (profile.protocolType) {
        ProtocolType.RDP, ProtocolType.SPICE -> QuantumBlue
        ProtocolType.RTSP -> VoidPurple
        ProtocolType.VNC -> VoidPurple
        ProtocolType.SSH -> PlasmaGreen
        ProtocolType.TELNET, ProtocolType.RLOGIN, ProtocolType.IPMI, ProtocolType.SERIAL_CONSOLE, ProtocolType.MOSH -> SolarFlare
        ProtocolType.WEB, ProtocolType.REDFISH, ProtocolType.AMT, ProtocolType.RESTCONF, ProtocolType.NETCONF -> PulsarCyan
        ProtocolType.SNMP -> SolarFlare
        // GUACAMOLE-PROTOCOL FEATURE: groups with RDP/SPICE — same
        // "framebuffer desktop" family from a color-coding point of view,
        // regardless of what guacd is actually driving underneath.
        ProtocolType.GUACAMOLE -> QuantumBlue
        // PROXMOX-API FEATURE: same "management API" family as RESTCONF/REDFISH.
        ProtocolType.PROXMOX -> PulsarCyan
        // MODBUS-TCP FEATURE (Part 2/2): groups with SNMP/IPMI — a device-
        // management protocol, not a framebuffer.
        ProtocolType.MODBUS_TCP -> SolarFlare
        ProtocolType.VIRTUALBOX_VRDE -> QuantumBlue
        ProtocolType.VMWARE_VSPHERE -> PulsarCyan
        ProtocolType.WAKE_ON_LAN -> SolarFlare
        ProtocolType.SFTP -> PlasmaGreen
        // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: same file-transfer family as SFTP above.
        ProtocolType.FTP -> PlasmaGreen
        ProtocolType.FTPS -> PlasmaGreen
        ProtocolType.WEBDAV -> PlasmaGreen
        ProtocolType.SMB -> PlasmaGreen
        ProtocolType.NFS -> PlasmaGreen
    }
    val protocolIcon = when (profile.protocolType) {
        ProtocolType.RDP, ProtocolType.SPICE -> Icons.Outlined.DesktopWindows
        ProtocolType.RTSP -> Icons.Outlined.Videocam
        ProtocolType.VNC -> Icons.Outlined.Monitor
        ProtocolType.SSH -> Icons.Outlined.Terminal
        ProtocolType.TELNET, ProtocolType.RLOGIN, ProtocolType.SERIAL_CONSOLE, ProtocolType.MOSH -> Icons.Outlined.SettingsEthernet
        ProtocolType.WEB -> Icons.Outlined.Web
        ProtocolType.REDFISH -> Icons.Outlined.Dns
        ProtocolType.IPMI -> Icons.Outlined.SettingsRemote
        ProtocolType.AMT -> Icons.Outlined.Memory
        ProtocolType.RESTCONF -> Icons.Outlined.Api
        ProtocolType.SNMP -> Icons.Outlined.NetworkCheck
        ProtocolType.NETCONF -> Icons.Outlined.SettingsRemote
        // GUACAMOLE-PROTOCOL FEATURE: no dedicated Guacamole artwork yet —
        // reuse the desktop icon, same "no dedicated art yet" reasoning
        // ShortcutHelper.iconResFor already documents for REDFISH/IPMI/AMT.
        ProtocolType.GUACAMOLE -> Icons.Outlined.DesktopWindows
        // PROXMOX-API FEATURE: same "management API" icon as RESTCONF/REDFISH.
        ProtocolType.PROXMOX -> Icons.Outlined.Dns
        ProtocolType.MODBUS_TCP -> Icons.Outlined.NetworkCheck
        ProtocolType.VIRTUALBOX_VRDE -> Icons.Outlined.DesktopWindows
        ProtocolType.VMWARE_VSPHERE -> Icons.Outlined.Dns
        ProtocolType.WAKE_ON_LAN -> Icons.Outlined.Wifi
        ProtocolType.SFTP -> Icons.Outlined.Terminal
        // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: same file-transfer family as SFTP above.
        ProtocolType.FTP -> Icons.Outlined.Terminal
        ProtocolType.FTPS -> Icons.Outlined.Terminal
        ProtocolType.WEBDAV -> Icons.Outlined.Terminal
        ProtocolType.SMB -> Icons.Outlined.Terminal
        ProtocolType.NFS -> Icons.Outlined.Terminal
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (columnCount <= 2) 1.35f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(GradientCardStart, GradientCardEnd)))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(accent.copy(0.4f), secondary.copy(0.15f))),
                shape = RoundedCornerShape(16.dp)
            )
            .combinedClickable(
                onClick = {
                    // PIN-CONNECTION FEATURE — selection mode: tapping the tile
                    // toggles selection instead of connecting (mirrors
                    // RdpProfileCard's list-mode behavior above).
                    if (isSelectionMode) {
                        onToggleSelect?.invoke()
                    } else {
                        soundManager?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.35f)
                        onConnect()
                    }
                },
                onLongClick = {
                    if (hapticEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    // While already selecting, a long-press just extends the
                    // selection instead of opening the long-press context menu.
                    if (isSelectionMode) onToggleSelect?.invoke() else menuExpanded = true
                }
            )
    ) {
        // PIN-CONNECTION FEATURE: top-start corner shows a checkbox while
        // selecting, or the pinned badge otherwise — never both, so the two
        // never fight for the same corner. AnimatedContent gives a soft
        // cross-fade when selection mode toggles on/off.
        androidx.compose.animation.AnimatedContent(
            targetState = isSelectionMode,
            modifier    = Modifier.align(Alignment.TopStart).padding(6.dp),
            label       = "grid_card_corner_indicator"
        ) { selecting ->
            if (selecting) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect?.invoke() },
                    modifier = Modifier
                        .size(if (columnCount == 2) 32.dp else 28.dp)
                        .semantics {
                            contentDescription = selectLabel
                            role = Role.Checkbox
                        },
                    colors = CheckboxDefaults.colors(checkedColor = PulsarCyan, uncheckedColor = CometTail)
                )
            } else if (profile.isPinned) {
                Box(
                    modifier = Modifier
                        .size(if (columnCount == 2) 22.dp else 18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(PulsarCyan.copy(alpha = 0.22f))
                        .border(1.dp, PulsarCyan.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .semantics { contentDescription = pinnedBadgeDescription },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Filled.PushPin,
                        contentDescription = null,
                        tint               = PulsarCyan,
                        modifier           = Modifier.size(if (columnCount == 2) 13.dp else 11.dp)
                    )
                }
            } else {
                Spacer(Modifier.size(1.dp))
            }
        }

        if (showStar && !isSelectionMode && onToggleFavorite != null) {
            IconButton(
                onClick  = onToggleFavorite,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(if (columnCount == 2) 32.dp else 28.dp)
            ) {
                Icon(
                    imageVector = if (profile.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(
                        if (profile.isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites
                    ),
                    tint     = if (profile.isFavorite) SolarFlare else CometTail,
                    modifier = Modifier.size(if (columnCount == 2) 16.dp else 14.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(cardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .size(iconSize)
                        .background(protocolColor.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                        .border(1.dp, protocolColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(protocolIcon, null, tint = protocolColor, modifier = Modifier.size(iconGlyph))
                }
                // CONNECTION-STATUS-INDICATOR FEATURE: same corner-anchor
                // idea as RdpProfileCard's list-mode badge — see the comment
                // there for why this doubles as the "colored ring by
                // protocol" optional polish for free.
                if (statusInfo != null) {
                    SessionStatusBadge(
                        info           = statusInfo,
                        connectionName = profile.name,
                        onLongPress    = { showStatusDetails = true },
                        modifier       = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(2.dp, 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(if (columnCount <= 2) 8.dp else 5.dp))
            Text(
                profile.name,
                style      = titleStyle,
                color      = StarDust,
                fontWeight = FontWeight.Bold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth()
            )
            if (showHost) {
                Text(
                    profile.host,
                    style     = MaterialTheme.typography.labelSmall,
                    color     = CometTail,
                    maxLines  = 1,
                    overflow  = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth()
                )
                // CONNECTION-STATUS-INDICATOR FEATURE: see RdpProfileCard's
                // identical guard for why this is safely bounded to at most
                // MAX_TABS=5 concurrent tickers.
                val connectedSince = statusInfo?.connectedSinceMillis
                if (statusInfo?.status == CardConnectionStatus.CONNECTED && connectedSince != null) {
                    SessionUptimeText(connectedSinceMillis = connectedSince)
                } else if (profile.lastConnected > 0L) {
                    // LAST-CONNECTED-ON-CARD FEATURE: same reasoning as
                    // RdpProfileCard — only shown when not actively
                    // connected (SessionUptimeText already covers that case)
                    // and only once the tile has room to show the host at all.
                    LastConnectedLabel(profile.lastConnected, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        DropdownMenu(
            expanded         = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            shape            = RoundedCornerShape(16.dp),
            containerColor   = NebulaSurface,
            tonalElevation   = 0.dp,
            shadowElevation  = 10.dp,
            border           = BorderStroke(1.dp, CardBorderColor),
            modifier         = Modifier.padding(vertical = 4.dp)
        ) {
            MenuActionItem(
                text    = stringResource(R.string.edit),
                icon    = Icons.Outlined.Edit,
                tint    = PulsarCyan,
                onClick = { menuExpanded = false; onEdit() }
            )
            if (onTogglePin != null) {
                MenuActionItem(
                    text    = pinActionLabel,
                    icon    = if (profile.isPinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                    tint    = PulsarCyan,
                    onClick = { menuExpanded = false; onTogglePin() }
                )
            }
            // PIN-CONNECTION FEATURE — multi-selection entry point (same rationale
            // as the List-mode dropdown above): a "Select" row here is what starts
            // isSelectionMode, since long-press is already spoken for (open this
            // menu) and the checkbox itself doesn't exist until selection mode is
            // already on.
            if (onToggleSelect != null) {
                MenuActionItem(
                    text    = selectLabel,
                    icon    = if (isSelected) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    tint    = PulsarCyan,
                    onClick = { menuExpanded = false; onToggleSelect() }
                )
            }
            if (profile.wolEnabled && onWakeOnLan != null) {
                MenuActionItem(
                    text    = stringResource(R.string.wol_wake),
                    icon    = Icons.Outlined.PowerSettingsNew,
                    tint    = ConnectingAmber,
                    onClick = { menuExpanded = false; onWakeOnLan() }
                )
            }
            if (profile.wolEnabled && onWakeAndConnect != null) {
                MenuActionItem(
                    text    = stringResource(R.string.wol_wake_and_connect),
                    icon    = Icons.Outlined.FlashOn,
                    tint    = ConnectingAmber,
                    onClick = { menuExpanded = false; onWakeAndConnect() }
                )
            }
            if (onPinShortcut != null) {
                MenuActionItem(
                    text    = stringResource(R.string.pin_shortcut),
                    icon    = Icons.Outlined.AddToHomeScreen,
                    tint    = PulsarCyan,
                    onClick = { menuExpanded = false; onPinShortcut() }
                )
            }
            if (!showStar && onToggleFavorite != null) {
                MenuActionItem(
                    text    = stringResource(
                        if (profile.isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites
                    ),
                    icon    = if (profile.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    tint    = SolarFlare,
                    onClick = { menuExpanded = false; onToggleFavorite() }
                )
            }
            if (onDuplicate != null) {
                MenuActionItem(
                    text    = stringResource(R.string.duplicate_connection),
                    icon    = Icons.Outlined.ContentCopy,
                    tint    = PulsarCyan,
                    onClick = { menuExpanded = false; onDuplicate() }
                )
            }
            if (onShareQr != null) {
                MenuActionItem(
                    text    = stringResource(R.string.share_via_qr),
                    icon    = Icons.Outlined.QrCode,
                    tint    = PulsarCyan,
                    onClick = { menuExpanded = false; onShareQr() }
                )
            }
            MenuActionItem(
                text      = stringResource(R.string.delete),
                icon      = Icons.Outlined.Delete,
                tint      = ErrorRed,
                textColor = ErrorRed,
                onClick   = { menuExpanded = false; onDelete() }
            )
        }
    }

    // CONNECTION-STATUS-INDICATOR FEATURE: see RdpProfileCard's identical
    // dialog invocation for the full rationale.
    if (showStatusDetails && statusInfo != null) {
        SessionDetailsDialog(
            info      = statusInfo,
            profile   = profile,
            onDismiss = { showStatusDetails = false }
        )
    }
}

@Composable
fun ProtocolBadge(type: ProtocolType) {
    val color = when (type) {
        ProtocolType.RDP -> QuantumBlue
        ProtocolType.VNC -> VoidPurple
        ProtocolType.SSH -> PlasmaGreen
        ProtocolType.TELNET -> SolarFlare
        ProtocolType.RLOGIN -> SolarFlare
        ProtocolType.SPICE -> QuantumBlue
        ProtocolType.RTSP -> VoidPurple
        ProtocolType.WEB -> PulsarCyan
        ProtocolType.REDFISH -> PulsarCyan
        ProtocolType.IPMI -> SolarFlare
        ProtocolType.AMT -> PulsarCyan
        ProtocolType.SERIAL_CONSOLE -> SolarFlare
        ProtocolType.RESTCONF -> PulsarCyan
        ProtocolType.SNMP -> SolarFlare
        ProtocolType.NETCONF -> PulsarCyan
        ProtocolType.GUACAMOLE -> QuantumBlue
        ProtocolType.MOSH -> SolarFlare
        ProtocolType.PROXMOX -> PulsarCyan
        ProtocolType.MODBUS_TCP -> SolarFlare
        ProtocolType.VIRTUALBOX_VRDE -> QuantumBlue
        ProtocolType.VMWARE_VSPHERE -> PulsarCyan
        ProtocolType.WAKE_ON_LAN -> SolarFlare
        ProtocolType.SFTP -> PlasmaGreen
        // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: same file-transfer family as SFTP above.
        ProtocolType.FTP -> PlasmaGreen
        ProtocolType.FTPS -> PlasmaGreen
        ProtocolType.WEBDAV -> PlasmaGreen
        ProtocolType.SMB -> PlasmaGreen
        ProtocolType.NFS -> PlasmaGreen
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Icon(protocolIcon(type), null, tint = color, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
        Text(type.label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
    }
}

// ── Pulsing Status Dot ────────────────────────────────────────────────────────
// PERF: previously every card ran its own infiniteRepeatable animation regardless
// of status, so a list of 20 saved connections meant 20 concurrent infinite
// animations recomposing forever — a real cost on mid-range devices. A
// "connected"/"disconnected" dot conveys no extra meaning by pulsing, so we only
// animate for the transient "connecting" (amber) state, where it actually
// communicates something. Idle dots are drawn once and never invalidate.
@Composable
fun PulsingDot(color: Color, size: Dp = 10.dp) {
    if (color == ConnectingAmber) {
        val infiniteT = rememberInfiniteTransition(label = "dot_pulse")
        val pulse by infiniteT.animateFloat(
            initialValue  = 0.5f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "dot_scale"
        )
        Box(
            Modifier
                .size(size)
                .drawBehind {
                    drawCircle(color = color.copy(alpha = 0.25f * pulse), radius = this.size.minDimension / 2 * 2.2f)
                    drawCircle(color = color.copy(alpha = 0.5f * pulse),  radius = this.size.minDimension / 2 * 1.4f)
                    drawCircle(color = color, radius = this.size.minDimension / 2)
                }
        )
    } else {
        Box(
            Modifier
                .size(size)
                .drawBehind {
                    drawCircle(color = color.copy(alpha = 0.25f), radius = this.size.minDimension / 2 * 1.6f)
                    drawCircle(color = color, radius = this.size.minDimension / 2)
                }
        )
    }
}

// ── Icon Badge ────────────────────────────────────────────────────────────────
// UI-FIX (view-only indicator): the VNC "view only" marker used to be a full
// InfoChip (icon + "View Only" text), which took noticeably more horizontal
// space than the other chips next to it just to convey a single boolean
// state. An icon alone reads just as clearly here — the eye icon is a
// well-understood "view only / read-only" convention — so this trims it down
// to an icon-only badge while keeping the same chip styling and a
// contentDescription so screen readers still announce it.
@Composable
fun IconBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(ChipBg, RoundedCornerShape(8.dp))
            .border(1.dp, HorizonGray.copy(0.3f), RoundedCornerShape(8.dp))
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = PulsarCyan,
            modifier = Modifier.size(13.dp)
        )
    }
}

// ── Info Chip ─────────────────────────────────────────────────────────────────
@Composable
fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    // TAG-FILTER-UX: optional so every existing call site (protocol/auth/WoL
    // chips) keeps behaving exactly as before — only the tag chips below pass
    // a real callback, turning them into a quick way to jump straight to
    // "show me everything else with this tag".
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(ChipBg, RoundedCornerShape(8.dp))
            .border(1.dp, HorizonGray.copy(0.3f), RoundedCornerShape(8.dp))
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ) else Modifier
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text     = text,
            style    = MaterialTheme.typography.labelSmall,
            color    = CometTail,
            maxLines = 1,                                          // BUG-10 FIX: prevent long usernames overflowing Chip
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

// ── Space Button ──────────────────────────────────────────────────────────────
enum class ButtonVariant { PRIMARY, DANGER, GHOST }

@Composable
fun SpaceButton(
    text:     String,
    onClick:  () -> Unit,
    variant:  ButtonVariant = ButtonVariant.PRIMARY,
    modifier: Modifier = Modifier,
    enabled:  Boolean  = true
) {
    val accent     = PulsarCyan
    val secondary  = QuantumBlue
    val danger1    = NovaPink
    val danger2    = SolarFlare

    val gradient = when (variant) {
        ButtonVariant.PRIMARY -> Brush.horizontalGradient(listOf(secondary, accent))
        ButtonVariant.DANGER  -> Brush.horizontalGradient(listOf(danger1, danger2))
        ButtonVariant.GHOST   -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val spaceColors = LocalSpaceColors.current
    val textColor = when (variant) {
        ButtonVariant.PRIMARY -> if (spaceColors.isDark) Color(0xFF020816) else Color.White
        ButtonVariant.DANGER  -> Color.White
        ButtonVariant.GHOST   -> accent
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue   = if (isPressed) 0.95f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
        label = "btn_scale"
    )
    val soundManager = LocalSoundManager.current

    Box(
        modifier = modifier
            .scale(scale)
            .heightIn(min = 46.dp) // BUG FIX: was fixed height(46.dp) with no min-width/padding,
            // which let the Box shrink-wrap tightly around the label, producing cramped,
            // inconsistently-sized pills (see Cancel/Clear in Clear History dialog).
            .defaultMinSize(minWidth = 96.dp)
            .clip(RoundedCornerShape(12.dp))
            .alpha(if (enabled) 1f else 0.38f) // BUG-5 FIX: dim entire button (gradient + text) when disabled
            .background(brush = gradient)
            .then(
                if (variant == ButtonVariant.GHOST)
                    Modifier.border(1.dp, accent.copy(0.6f), RoundedCornerShape(12.dp))
                else Modifier
            )
            .clickable(
                enabled           = enabled,
                interactionSource = interactionSource,
                indication        = null,
                onClick = {
                    soundManager?.play(com.systemsgo.hex.audio.SoundManager.Sound.TAP, 0.4f)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = text,
            style      = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color      = textColor, // BUG-5 FIX: alpha now applied to whole Box above
            textAlign  = TextAlign.Center,
            modifier   = Modifier.padding(horizontal = 24.dp, vertical = 10.dp) // BUG FIX: proper button padding so label isn't glued to the pill edges
        )
    }
}

// ── Network Quality Badge ─────────────────────────────────────────────────────
@Composable
fun NetworkQualityBadge(quality: com.systemsgo.hex.ui.NetworkQuality) {
    val (color, bars) = when (quality) {
        com.systemsgo.hex.ui.NetworkQuality.POOR      -> Pair(ErrorRed,        1)
        com.systemsgo.hex.ui.NetworkQuality.FAIR      -> Pair(ConnectingAmber, 2)
        com.systemsgo.hex.ui.NetworkQuality.GOOD      -> Pair(PlasmaGreen,     3)
        com.systemsgo.hex.ui.NetworkQuality.EXCELLENT -> Pair(PulsarCyan,      4)
        else                                         -> Pair(DisconnectedGray, 0)
    }
    Row(
        verticalAlignment     = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier              = Modifier.height(18.dp)
    ) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(4.dp)
                    .height((4 + i * 3).dp)
                    .background(
                        color = if (i <= bars) color else color.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

// ── Subscribe Dialog ─────────────────────────────────────────────────────────
// BUG-11 FIX: SubscribeDialog removed (dead code since UX-05).
// ViewModel state (showSubscribeDialog) retained for back-compat; UI call site was removed.

// ── Profile Form Dialog ───────────────────────────────────────────────────────
// PERF-FIX (recomposition scope): shared port-range check used by ProfileFormDialog
// and its extracted sections below. Kept as a plain top-level function (not a
// composable) so each section can validate its own field locally instead of
// reading a value computed higher up — the local read keeps the invalidation
// scoped to that one section, instead of forcing a parent scope to re-execute.
private fun isPortInRange(value: String): Boolean {
    // I18N-FIX: normalize Arabic-Indic/Extended Arabic-Indic digits (e.g. a
    // port pasted or typed via an Arabic-locale IME) to ASCII before parsing,
    // so port validation behaves identically regardless of digit style.
    val n = value.normalizeDigits().toIntOrNull() ?: return false
    return n in 1..65535
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileFormDialog(
    profile:   RdpProfile? = null,
    // BUGFIX #4: when adding a brand-new connection from a protocol-filtered tab
    // (e.g. the user is on the SSH tab and taps "Add"), the form used to always
    // open on RDP regardless of which tab was active. Callers now pass the
    // currently-active filter's protocol here so the form opens on the correct
    // tab; it's ignored whenever an existing `profile` is supplied (editing/
    // import always keeps the profile's own protocol).
    initialProtocolType: ProtocolType? = null,
    // Folders (categories) available to file this connection under. Defaults
    // to empty so existing call sites that haven't been updated to pass the
    // current folder list yet keep compiling — the "Organize" section below
    // simply has nothing but "No folder" to offer in that case.
    folders: List<ConnectionFolder> = emptyList(),
    onDismiss: () -> Unit,
    onSave:    (RdpProfile) -> Unit,
    // ENTRA-ID-AUTH FEATURE: this dialog stays a plain stateless composable
    // (no Hilt/ViewModel access, same as every other param here), so the
    // actual MSAL interactive sign-in call — which needs a live Activity —
    // is the caller's responsibility. Wire these to
    // entraIdAuthManager.signIn(activity)/.signOut() (+ EntraSignInLinkStore
    // update) from whichever screen hosts this dialog (see the handoff
    // prompt for the exact call). Defaults are no-ops so existing callers
    // that haven't been updated yet keep compiling with the sign-in button
    // simply doing nothing.
    onSignInWithMicrosoft: () -> Unit = {},
    onSignOutMicrosoft:    () -> Unit = {},
    entraSignInPending:    Boolean = false,
    // ENTRA-ID-AUTH FEATURE: id to give a brand-new (profile == null)
    // profile if it's saved from this dialog instance. Without this, the
    // Save button below would mint its own fresh UUID at click time (see
    // `val base = profile ?: RdpProfile(...)`) — a *different* id than
    // whatever the caller's onSignInWithMicrosoft closure already linked an
    // Entra account to via EntraSignInLinkStore, silently orphaning that
    // link the moment the profile is actually saved. Callers creating a new
    // profile must generate this once (e.g. `rememberSaveable { UUID
    // .randomUUID().toString() }`, regenerated whenever the "new profile"
    // dialog is reopened) and pass the *same* value here and into their
    // onSignInWithMicrosoft closure. Ignored (Room already has a stable id)
    // when `profile` is non-null — editing an existing profile.
    pendingProfileId: String? = null,
) {
    // BUGFIX-UI: this dialog's fields used to be plain `remember`, so all
    // in-progress edits were lost if Android killed the app process in the
    // background (e.g. low memory while the user briefly switched apps) —
    // there was no rememberSaveable anywhere in the project. Ordinary fields
    // below are now rememberSaveable so they survive that. Fields carrying
    // actual secret material (password, private key, passphrase — marked
    // "SECURITY" below) are deliberately left on plain `remember` instead:
    // rememberSaveable persists its value in the Activity's savedInstanceState
    // Bundle, which is a broader, less controlled surface than this app's own
    // encrypted storage (the same reason PIN fields elsewhere in the app are
    // never persisted outside EncryptedSharedPreferences). Losing an in-progress
    // password on process death is an acceptable, safe trade-off; persisting
    // plaintext secrets in a system Bundle across process boundaries is not.
    // ADD-CONNECTION PROTOCOL PICKER (Part 2/2): "What is this protocol?" —
    // reopens ProtocolIntroPanel for whichever protocol is currently
    // selected, on demand, every time it's tapped (unlike the picker screen,
    // this never checks/sets AddConnectionProtocolViewModel.hasSeenIntro —
    // the whole point here is "show me again", not a one-time gate).
    var showProtocolIntro by rememberSaveable { mutableStateOf(false) }
    var protocolType    by rememberSaveable { mutableStateOf(profile?.protocolType ?: initialProtocolType ?: ProtocolType.RDP) }
    var name            by rememberSaveable { mutableStateOf(profile?.name     ?: "") }
    var host            by rememberSaveable { mutableStateOf(profile?.host     ?: "") }
    var port            by rememberSaveable { mutableStateOf(profile?.port?.toString() ?: ProtocolType.RDP.defaultPort.toString()) }
    var username        by rememberSaveable { mutableStateOf(profile?.username ?: "") }
    // GUACAMOLE-PROTOCOL FEATURE: manual-entry fields, or filled by
    // GuacamoleConnectionPickerDialog via the "Browse Connections" button
    // in ProtocolOptionsSection's GUACAMOLE branch.
    var guacServerUrl          by rememberSaveable { mutableStateOf(profile?.guacServerUrl ?: "") }
    var guacDataSource         by rememberSaveable { mutableStateOf(profile?.guacDataSource ?: "") }
    var guacConnectionIdentifier by rememberSaveable { mutableStateOf(profile?.guacConnectionIdentifier ?: "") }
    var guacConnectionName     by rememberSaveable { mutableStateOf(profile?.guacConnectionName ?: "") }
    var guacConnectionProtocol by rememberSaveable { mutableStateOf(profile?.guacConnectionProtocol ?: "") }
    var guacRememberSession by rememberSaveable { mutableStateOf(profile?.guacRememberSession ?: false) }
    var showGuacamolePicker by remember { mutableStateOf(false) }
    // RTSP FEATURE: see RdpProfile.rtspStreamPath/rtspTransportMode/
    // rtspUseTls's doc comments.
    var rtspStreamPath by rememberSaveable { mutableStateOf(profile?.rtspStreamPath ?: "") }
    var rtspTransportMode by rememberSaveable { mutableStateOf(profile?.rtspTransportMode ?: com.systemsgo.hex.rtsp.protocol.RtspTransportMode.TCP_INTERLEAVED.name) }
    var rtspUseTls by rememberSaveable { mutableStateOf(profile?.rtspUseTls ?: false) }
    var password        by remember { mutableStateOf(profile?.password ?: "") }  // SECURITY: kept as plain remember, not rememberSaveable — see note below
    var domain          by rememberSaveable { mutableStateOf(profile?.domain   ?: "") }
    var useNla          by rememberSaveable { mutableStateOf(profile?.useNla   ?: true) }
    // BUG-3 FIX: expose per-profile self-signed cert acceptance so users with
    // home/office RDP servers can connect without a full PKI certificate chain.
    var acceptSelfSignedCertificate by rememberSaveable { mutableStateOf(profile?.acceptSelfSignedCertificate ?: false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    var gatewayEnabled  by rememberSaveable { mutableStateOf(profile?.gatewayEnabled ?: false) }
    var gatewayHost     by rememberSaveable { mutableStateOf(profile?.gatewayHost ?: "") }
    var gatewayPort     by rememberSaveable { mutableStateOf(profile?.gatewayPort?.toString() ?: "443") }
    var gatewayUsername by rememberSaveable { mutableStateOf(profile?.gatewayUsername ?: "") }
    var gatewayPassword by remember { mutableStateOf(profile?.gatewayPassword ?: "") }  // SECURITY: see note below
    var gatewayDomain   by rememberSaveable { mutableStateOf(profile?.gatewayDomain ?: "") }
    var gatewayPasswordVisible by rememberSaveable { mutableStateOf(false) }
    // ENTRA-ID-AUTH FEATURE: see GatewayAuthMode's doc comment. entraLinkedUpn
    // is display-only (updated by EntraSignInLinkStore right after a
    // successful sign-in elsewhere, e.g. on the session-connect screen) —
    // this editor form never lets the user type it directly.
    var gatewayAuthMode by rememberSaveable {
        mutableStateOf(com.systemsgo.hex.data.model.GatewayAuthMode.fromName(profile?.gatewayAuthMode ?: com.systemsgo.hex.data.model.GatewayAuthMode.PASSWORD.name))
    }
    val entraLinkedUpn = profile?.entraLinkedUpn.orEmpty()
    // ENTRA-ID-AUTH FEATURE: see RdpProfile.gatewayScopeUri's doc comment.
    // Editable (unlike entraLinkedUpn above) — the user types this in
    // themselves, from their org's Azure Portal.
    var gatewayScopeUri by rememberSaveable { mutableStateOf(profile?.gatewayScopeUri ?: "") }

    // OUTBOUND-PROXY FEATURE: same shape as the gateway* state vars above —
    // see com.systemsgo.hex.data.model.ProxyType's doc comment and
    // AFreeRdpBridge.connect()'s proxyEnabled param for what this actually
    // does (routes the client's own outbound TCP connection through a
    // SOCKS/HTTP proxy, distinct from RD Gateway just above).
    var proxyEnabled  by rememberSaveable { mutableStateOf(profile?.proxyEnabled ?: false) }
    var proxyType     by rememberSaveable { mutableStateOf(profile?.proxyType ?: ProxyType.SOCKS) }
    var proxyHost     by rememberSaveable { mutableStateOf(profile?.proxyHost ?: "") }
    var proxyPort     by rememberSaveable { mutableStateOf(profile?.proxyPort?.toString() ?: "1080") }
    var proxyUsername by rememberSaveable { mutableStateOf(profile?.proxyUsername ?: "") }
    var proxyPassword by remember { mutableStateOf(profile?.proxyPassword ?: "") }  // SECURITY: same as gatewayPassword above
    var proxyPasswordVisible by rememberSaveable { mutableStateOf(false) }

    // PAC-SUPPORT FEATURE (Part 3/n — UI): URL of a Proxy Auto-Config
    // script, offered inside the same "Outbound Proxy" card as an
    // alternative to typing proxyHost/proxyPort/... by hand. See
    // RdpProfile.pacUrl's doc comment for the pacUrl-vs-static-fields
    // priority applied at actual connect time.
    var pacUrl by rememberSaveable { mutableStateOf(profile?.pacUrl ?: "") }

    // RDP-OVER-WEBSOCKET FEATURE: see RdpProfile.transportMode/webSocketConfig's
    // doc comments. transportMode is a plain enum (Serializable, like
    // GatewayAuthMode above) so rememberSaveable works directly. webSocketConfig
    // is a nested data class that can carry secrets (bearerToken,
    // authorizationHeader, cookie) — kept as plain `remember`, same reasoning
    // as gatewayPassword/proxyPassword just above (never lands in the saved-
    // instance-state Bundle).
    var transportMode by rememberSaveable {
        mutableStateOf(RdpTransportMode.fromName(profile?.transportMode ?: RdpTransportMode.TCP.name))
    }
    var webSocketConfig by remember {
        mutableStateOf(profile?.webSocketConfig?.let { RdpWebSocketConfigCodec.decode(it) } ?: RdpWebSocketConfig())
    }

    // REMOTEAPP: RAIL single-published-app mode (RDP only).
    var remoteAppEnabled     by rememberSaveable { mutableStateOf(profile?.remoteAppEnabled ?: false) }
    var remoteAppProgram     by rememberSaveable { mutableStateOf(profile?.remoteAppProgram ?: "") }
    var remoteAppWorkingDir  by rememberSaveable { mutableStateOf(profile?.remoteAppWorkingDir ?: "") }
    var remoteAppCmdLine     by rememberSaveable { mutableStateOf(profile?.remoteAppCmdLine ?: "") }
    var remoteAppDisplayMode by rememberSaveable { mutableStateOf(profile?.remoteAppDisplayMode ?: RemoteAppDisplayMode.SINGLE_WINDOW) }

    // CODEC-NEGOTIATION FEATURE: see the doc comment on
    // com.systemsgo.hex.data.model.CodecPreference / AFreeRdpBridge.CodecPreference
    // for what each value does. Defaults to AUTO, same as every other
    // codec-related default in this feature, so a brand-new profile keeps
    // fully-automatic negotiation until the user opens Advanced Settings
    // and explicitly changes it.
    var codecPreference by rememberSaveable { mutableStateOf(profile?.codecPreference ?: CodecPreference.AUTO) }

    var vncViewOnly     by rememberSaveable { mutableStateOf(profile?.vncViewOnly ?: false) }
    // ULTRAVNC-REPEATER FEATURE: see RdpProfile.vncRepeaterEnabled's doc comment.
    var vncRepeaterEnabled by rememberSaveable { mutableStateOf(profile?.vncRepeaterEnabled ?: false) }
    var vncRepeaterId      by rememberSaveable { mutableStateOf(profile?.vncRepeaterId ?: "") }
    // ULTRAVNC-REPEATER FEATURE (Mode I/II): see VncRepeaterMode's doc comment.
    var vncRepeaterMode    by rememberSaveable { mutableStateOf(profile?.vncRepeaterMode ?: VncRepeaterMode.MODE_II) }
    // LISTEN-MODE FEATURE: see RdpProfile.vncListenModeEnabled's doc comment.
    // Mutually exclusive with vncRepeaterEnabled — turning one on turns the
    // other off (enforced where each toggle's onCheckedChange is wired up
    // below), since a repeater and an accepted incoming connection can't
    // both describe the same socket.
    var vncListenModeEnabled by rememberSaveable { mutableStateOf(profile?.vncListenModeEnabled ?: false) }
    var vncListenPort        by rememberSaveable { mutableStateOf((profile?.vncListenPort ?: 5500).toString()) }
    var sshAuthType     by rememberSaveable { mutableStateOf(profile?.sshAuthType ?: SshAuthType.PASSWORD) }

    // SETTINGS-CONSOLIDATE FIX: color depth, resolution and the performance/
    // quality trade-off used to be set per-connection here. They're now
    // global (Settings → Connection) so every connection shares one
    // consistent, easy-to-find set of quality controls instead of the user
    // having to configure — or accidentally leave stale — a value on each
    // profile individually. enableSound is kept: whether a given remote
    // server should receive redirected audio genuinely varies per server.
    var enableSound     by rememberSaveable { mutableStateOf(profile?.enableSound ?: false) }
    // MIC-REDIRECT FEATURE: input-direction counterpart to enableSound above
    // (local mic → remote, MS-RDPEAI "audin", vs. remote → local speaker).
    var enableMicRedirect by rememberSaveable { mutableStateOf(profile?.enableMicRedirect ?: false) }
    // PRINTER-REDIRECT FEATURE: whether a given remote server should be able
    // to see this device as a printer and send it jobs — genuinely varies
    // per server/profile, same reasoning as enableSound just above.
    var enablePrinterRedirect by rememberSaveable { mutableStateOf(profile?.enablePrinterRedirect ?: false) }
    // WEBCAM-REDIRECT FEATURE: whether a given remote server should be able
    // to use this device's camera as a redirected webcam — same reasoning
    // as enablePrinterRedirect just above.
    var enableWebcamRedirect by rememberSaveable { mutableStateOf(profile?.enableWebcamRedirect ?: false) }
    // SMARTCARD-REDIRECT FEATURE: whether a given remote server should be
    // able to see a PC/SC smart-card reader backed by this device — same
    // reasoning as enablePrinterRedirect/enableWebcamRedirect just above.
    var enableSmartcardRedirect by rememberSaveable { mutableStateOf(profile?.enableSmartcardRedirect ?: false) }
    // PARALLEL-REDIRECT FEATURE: whether a given remote server should see a
    // parallel port backed by a local device node on this device — same
    // reasoning as enablePrinterRedirect/enableSmartcardRedirect just above.
    // Unlike those, it also needs a user-supplied path (parallelPortPath),
    // since Android has no built-in default for a "parallel port" the way
    // drive redirect has this app's own storage directory.
    var enableParallelRedirect by rememberSaveable { mutableStateOf(profile?.enableParallelRedirect ?: false) }
    var parallelPortPath by rememberSaveable { mutableStateOf(profile?.parallelPortPath ?: "") }
    // SERIAL-REDIRECT FEATURE: mirrors enableParallelRedirect/parallelPortPath
    // immediately above, just a "serial" rdpdr device instead of "parallel".
    var enableSerialRedirect by rememberSaveable { mutableStateOf(profile?.enableSerialRedirect ?: false) }
    var serialPortPath by rememberSaveable { mutableStateOf(profile?.serialPortPath ?: "") }
    // SERIAL-OVER-NETWORK FEATURE: mirrors enableSerialRedirect/serialPortPath
    // immediately above — serialRedirectMode picks whether serialPortPath is
    // actually used (LOCAL_DEVICE) or serialNetworkHost/Port are (RAW_TCP /
    // RFC_2217). See RdpProfile.serialRedirectMode's doc comment.
    var serialRedirectMode by rememberSaveable { mutableStateOf(profile?.serialRedirectMode ?: com.systemsgo.hex.data.model.SerialRedirectMode.LOCAL_DEVICE) }
    var serialNetworkHost by rememberSaveable { mutableStateOf(profile?.serialNetworkHost ?: "") }
    var serialNetworkPort by rememberSaveable { mutableStateOf(profile?.serialNetworkPort?.toString() ?: "2217") }
    var sshPrivateKey   by remember { mutableStateOf(profile?.sshPrivateKey ?: "") }  // SECURITY: see note below
    var sshKeyPassphrase by remember { mutableStateOf(profile?.sshPrivateKeyPassphrase ?: "") }  // SECURITY: see note below
    var sshAgentForwardingEnabled by rememberSaveable { mutableStateOf(profile?.sshAgentForwardingEnabled ?: false) }

    // DYN-PROXY: SOCKS5 dynamic proxy state (SSH profiles only) — the
    // equivalent of `ssh -D <port>`, started alongside the terminal session.
    var socksProxyEnabled by rememberSaveable { mutableStateOf(profile?.socksProxyEnabled ?: false) }
    var socksProxyPort    by rememberSaveable { mutableStateOf(profile?.socksProxyPort?.toString() ?: "1080") }

    // X11 FORWARDING FEATURE: `ssh -X`/`-Y` equivalent (SSH profiles only) —
    // see RdpProfile.x11ForwardingEnabled's doc comment.
    var x11ForwardingEnabled by rememberSaveable { mutableStateOf(profile?.x11ForwardingEnabled ?: false) }
    var x11DisplayHost    by rememberSaveable { mutableStateOf(profile?.x11DisplayHost ?: "127.0.0.1") }
    var x11DisplayNumber  by rememberSaveable { mutableStateOf(profile?.x11DisplayNumber?.toString() ?: "0") }
    var x11AuthCookie     by remember { mutableStateOf(profile?.x11AuthCookie ?: "") }  // SECURITY: not rememberSaveable, mirrors sshPrivateKey above

    // SSH-PORT-FORWARD FEATURE: user-defined static -L/-R forwards (SSH
    // profiles only) — see RdpProfile.sshPortForwards's doc comment. Plain
    // `remember`, not `rememberSaveable`: List<SshPortForwardRule> has no
    // registered Bundle Saver (same reasoning would apply to `tags`, which
    // this form also doesn't attempt to rememberSaveable), so this list is
    // rebuilt from `profile` on process death instead of round-tripping
    // through the saved-instance-state Bundle. Harmless here since it's
    // rebuilt from the persisted profile the moment the form is reopened.
    var sshPortForwards by remember { mutableStateOf(profile?.sshPortForwards ?: emptyList()) }

    // TELNET-TLS FEATURE: see RdpProfile.telnetUseTls's doc comment.
    var telnetUseTls by rememberSaveable { mutableStateOf(profile?.telnetUseTls ?: false) }

    // RLOGIN FEATURE: see RdpProfile.rloginRemoteUsername/rloginTerminalType's
    // doc comments.
    var rloginRemoteUsername by rememberSaveable { mutableStateOf(profile?.rloginRemoteUsername ?: "") }
    var rloginTerminalType by rememberSaveable { mutableStateOf(profile?.rloginTerminalType ?: "xterm/38400") }

    // MOSH FEATURE: see RdpProfile.moshRemoteServerCommand/moshUdpPortRange/
    // moshRemoteLocale/moshColorMode/moshPredictionMode's doc comments.
    // Auth itself reuses username/password/sshAuthType/sshPrivateKey/
    // sshPrivateKeyPassphrase from the generic/SSH sections above — the
    // bootstrap phase is plain SSH exec, same as an SSH profile.
    var moshRemoteServerCommand by rememberSaveable { mutableStateOf(profile?.moshRemoteServerCommand ?: "mosh-server") }
    var moshUdpPortRange by rememberSaveable { mutableStateOf(profile?.moshUdpPortRange ?: "") }
    var moshRemoteLocale by rememberSaveable { mutableStateOf(profile?.moshRemoteLocale ?: "") }
    var moshColorMode by rememberSaveable { mutableStateOf(profile?.moshColorMode ?: 256) }
    var moshPredictionMode by rememberSaveable { mutableStateOf(profile?.moshPredictionMode ?: MoshPredictionMode.ADAPTIVE.name) }

    // PROXMOX-API FEATURE (Part 1/N): host/port/username/password above are
    // reused as-is (username as user@realm, e.g. root@pam, for PASSWORD
    // mode). See ProtocolOptionsSection's ProtocolType.PROXMOX branch for
    // the actual input fields — not wired up yet, these three exist so the
    // validation `when` a few hundred lines down and the save-mapping below
    // both have something to read/write; the visible form fields land in a
    // follow-up pass.
    var proxmoxAuthMode by rememberSaveable { mutableStateOf(profile?.proxmoxAuthMode ?: ProxmoxAuthMode.TOKEN.name) }
    var proxmoxTokenId by rememberSaveable { mutableStateOf(profile?.proxmoxTokenId ?: "") }
    var proxmoxTokenSecret by rememberSaveable { mutableStateOf(profile?.proxmoxTokenSecret ?: "") }
    var proxmoxAcceptSelfSignedCertificate by rememberSaveable { mutableStateOf(profile?.proxmoxAcceptSelfSignedCertificate ?: true) }

    // MODBUS-TCP FEATURE (Part 2/2): see RdpProfile.modbus*'s doc comments.
    // Reuses the generic host/port fields above (port defaults to 502 for
    // this protocol via ProtocolType.MODBUS_TCP.defaultPort) — only what's
    // genuinely Modbus-specific gets its own state here. modbusPoints (the
    // dashboard's saved register map) isn't editable from this generic
    // form — it's managed from ModbusManagementScreen's Points tab once
    // the profile is saved.
    var modbusUnitId by rememberSaveable { mutableStateOf((profile?.modbusUnitId ?: 1).toString()) }
    var modbusConnectTimeoutMs by rememberSaveable { mutableStateOf((profile?.modbusConnectTimeoutMs ?: 5000).toString()) }
    var modbusResponseTimeoutMs by rememberSaveable { mutableStateOf((profile?.modbusResponseTimeoutMs ?: 3000).toString()) }
    var modbusRetries by rememberSaveable { mutableStateOf((profile?.modbusRetries ?: 1).toString()) }
    var modbusPollIntervalMs by rememberSaveable { mutableStateOf((profile?.modbusPollIntervalMs ?: 1000).toString()) }

    // VIRTUALBOX-VRDE FEATURE (Part 1/N → Part 2/N): host/port/username/
    // password/domain/acceptSelfSignedCertificate above are reused as-is —
    // see ProtocolType.VIRTUALBOX_VRDE's doc comment. vrdeAuthType/
    // vrdeMultiConnectionAllowed are purely informational (see
    // VrdeAuthType's doc comment) — this form doesn't send them anywhere,
    // it just lets the user record what the host VM's own VRDE expects.
    var vrdeAuthType by rememberSaveable { mutableStateOf(profile?.vrdeAuthType ?: VrdeAuthType.NULL_AUTH.name) }
    var vrdeMultiConnectionAllowed by rememberSaveable { mutableStateOf(profile?.vrdeMultiConnectionAllowed ?: false) }

    // VMWARE-VSPHERE FEATURE (Part 1/N → Part 2/N): host/port above are the
    // vCenter/ESXi API endpoint, username/password the vSphere account —
    // see ProtocolType.VMWARE_VSPHERE's doc comment.
    var vsphereApiMode by rememberSaveable { mutableStateOf(profile?.vsphereApiMode ?: VSphereApiMode.REST.name) }
    var vsphereAcceptSelfSignedCertificate by rememberSaveable { mutableStateOf(profile?.vsphereAcceptSelfSignedCertificate ?: true) }
    var vsphereDatacenter by rememberSaveable { mutableStateOf(profile?.vsphereDatacenter ?: "") }

    // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: the nine saved-profile
    // fields for these five protocols — see each's doc comment on
    // RdpProfile.kt (ftpSecurity/ftpPassiveMode, smbShare/smbDomain,
    // webdavBaseUrl, nfsExportPath/nfsUid/nfsGid/nfsMountdPort). nfsUid/
    // nfsGid/nfsMountdPort are kept as String state here (same convention
    // modbusUnitId/snmpPort/etc. above use for numeric text fields) and
    // parsed back to Int only in the Save button's RdpProfile(...) call.
    var ftpSecurity by rememberSaveable { mutableStateOf(profile?.ftpSecurity?.name ?: com.systemsgo.hex.transfer.FtpSecurity.PLAIN.name) }
    var ftpPassiveMode by rememberSaveable { mutableStateOf(profile?.ftpPassiveMode ?: true) }
    var smbShare by rememberSaveable { mutableStateOf(profile?.smbShare ?: "") }
    var smbDomain by rememberSaveable { mutableStateOf(profile?.smbDomain ?: "") }
    var webdavBaseUrl by rememberSaveable { mutableStateOf(profile?.webdavBaseUrl ?: "") }
    var nfsExportPath by rememberSaveable { mutableStateOf(profile?.nfsExportPath ?: "") }
    var nfsUid by rememberSaveable { mutableStateOf((profile?.nfsUid ?: 0).toString()) }
    var nfsGid by rememberSaveable { mutableStateOf((profile?.nfsGid ?: 0).toString()) }
    var nfsMountdPort by rememberSaveable { mutableStateOf(profile?.nfsMountdPort?.takeIf { it != 0 }?.toString() ?: "") }

    // WEB-PORTAL FEATURE: see RdpProfile.webUrl/webTrustSelfSignedCertificate/
    // webAutoFillHttpAuth's doc comments.
    var webUrl by rememberSaveable { mutableStateOf(profile?.webUrl ?: "") }
    var webTrustSelfSignedCertificate by rememberSaveable { mutableStateOf(profile?.webTrustSelfSignedCertificate ?: false) }
    var webAutoFillHttpAuth by rememberSaveable { mutableStateOf(profile?.webAutoFillHttpAuth ?: true) }
    // WEB-PORTAL-SMART-AUTOFILL FEATURE: see RdpProfile.webAutoFillLoginForm's doc comment.
    var webAutoFillLoginForm by rememberSaveable { mutableStateOf(profile?.webAutoFillLoginForm ?: true) }

    // REDFISH-IPMI FEATURE: see RdpProfile.ipmiPrivilegeLevel's doc comment.
    // REDFISH has no protocol-specific field of its own — it reuses
    // acceptSelfSignedCertificate (below) and the generic host/port/
    // username/password fields every profile already has.
    var ipmiPrivilegeLevel by rememberSaveable { mutableStateOf(profile?.ipmiPrivilegeLevel ?: "ADMINISTRATOR") }
    // IPMI-KG-FEATURE: see RdpProfile.ipmiKgKey's doc comment. Blank means
    // one-key login (no BMC key configured) — the common case.
    var ipmiKgKey by rememberSaveable { mutableStateOf(profile?.ipmiKgKey ?: "") }

    // AMT-VPRO FEATURE: see RdpProfile.amtUseTls's doc comment. AMT has no
    // other protocol-specific field — like REDFISH it reuses
    // acceptSelfSignedCertificate (below) and the generic host/port/
    // username/password fields every profile already has.
    var amtUseTls by rememberSaveable { mutableStateOf(profile?.amtUseTls ?: false) }
    // AMT-VPRO FEATURE — Phase 6 (CIRA setup UI): see RdpProfile.ciraEnabled's
    // doc comment. Mutually exclusive with the generic host/port fields
    // (see isCiraAmt below), not layered on top of them.
    var ciraEnabled by rememberSaveable { mutableStateOf(profile?.ciraEnabled ?: false) }
    var ciraRelayHost by rememberSaveable { mutableStateOf(profile?.ciraRelayHost ?: "") }
    var ciraRelayPort by rememberSaveable { mutableStateOf((profile?.ciraRelayPort ?: 8081).toString()) }
    var ciraRelayUsername by rememberSaveable { mutableStateOf(profile?.ciraRelayUsername ?: "") }
    var ciraRelayPassword by rememberSaveable { mutableStateOf(profile?.ciraRelayPassword ?: "") }
    var ciraDeviceId by rememberSaveable { mutableStateOf(profile?.ciraDeviceId ?: "") }
    // AMT-VPRO FEATURE — Phase 6, Part 3: see RdpProfile.ciraRelayUseTls's
    // doc comment. Only meaningful when ciraEnabled is also true.
    var ciraRelayUseTls by rememberSaveable { mutableStateOf(profile?.ciraRelayUseTls ?: false) }

    // SNMP FEATURE: see RdpProfile.snmp*'s doc comments (the "SNMP FEATURE"
    // block in RdpProfile.kt). snmpPort is separate from the generic [port]
    // field above since SNMP can also run as a monitoring add-on layered on
    // top of a non-SNMP profile (RDP/SSH/...) whose own port shouldn't be
    // overwritten.
    var snmpVersion by rememberSaveable { mutableStateOf(profile?.snmpVersion ?: "V2C") }
    var snmpCommunity by rememberSaveable { mutableStateOf(profile?.snmpCommunity ?: "public") }
    var snmpV3Username by rememberSaveable { mutableStateOf(profile?.snmpV3Username ?: "") }
    var snmpV3SecurityLevel by rememberSaveable { mutableStateOf(profile?.snmpV3SecurityLevel ?: "AUTH_PRIV") }
    var snmpV3AuthProtocol by rememberSaveable { mutableStateOf(profile?.snmpV3AuthProtocol ?: "SHA1") }
    var snmpV3AuthPassphrase by rememberSaveable { mutableStateOf(profile?.snmpV3AuthPassphrase ?: "") }
    var snmpV3PrivProtocol by rememberSaveable { mutableStateOf(profile?.snmpV3PrivProtocol ?: "AES128") }
    var snmpV3PrivPassphrase by rememberSaveable { mutableStateOf(profile?.snmpV3PrivPassphrase ?: "") }
    var snmpV3ContextName by rememberSaveable { mutableStateOf(profile?.snmpV3ContextName ?: "") }
    var snmpPort by rememberSaveable { mutableStateOf((profile?.snmpPort ?: 161).toString()) }
    // Monitoring-add-on mode: lets a non-SNMP profile (RDP/SSH/...) also
    // carry its own SNMP credentials, so its detail screen can offer an
    // "Open SNMP dashboard" action alongside the primary connection — see
    // HomeScreen/ConnectionDetail's follow-up wiring for that entry point.
    var snmpMonitoringEnabled by rememberSaveable { mutableStateOf(profile?.snmpMonitoringEnabled ?: false) }

    // NETCONF FEATURE: see RdpProfile.netconf*'s doc comments. Reuses
    // sshAuthType/sshPrivateKey/sshKeyPassphrase (already hoisted above) for
    // its own auth — only what's genuinely NETCONF-specific gets its own
    // state here.
    var netconfDefaultDatastore by rememberSaveable { mutableStateOf(profile?.netconfDefaultDatastore ?: "running") }
    var netconfExtraCapabilities by rememberSaveable { mutableStateOf(profile?.netconfExtraCapabilities ?: "") }
    var netconfKeepAliveMs by rememberSaveable { mutableStateOf((profile?.netconfKeepAliveMs ?: 15_000).toString()) }
    var netconfConnectTimeoutMs by rememberSaveable { mutableStateOf((profile?.netconfConnectTimeoutMs ?: 15_000).toString()) }
    var netconfCompressionEnabled by rememberSaveable { mutableStateOf(profile?.netconfCompressionEnabled ?: false) }
    var netconfOpenSshCertificate by remember { mutableStateOf(profile?.netconfOpenSshCertificate ?: "") } // SECURITY: key-adjacent material, not rememberSaveable — see sshPrivateKey's note above
    var netconfAutoReconnect by rememberSaveable { mutableStateOf(profile?.netconfAutoReconnect ?: true) }
    // CALL-HOME FEATURE (RFC 8071, Part 12): see RdpProfile.netconfCallHome*'s doc comments.
    var netconfCallHomeEnabled by rememberSaveable { mutableStateOf(profile?.netconfCallHomeEnabled ?: false) }
    var netconfCallHomeListenPort by rememberSaveable { mutableStateOf((profile?.netconfCallHomeListenPort ?: 4334).toString()) }
    var netconfCallHomeAllowedSourceHost by rememberSaveable { mutableStateOf(profile?.netconfCallHomeAllowedSourceHost ?: "") }
    // CALL-HOME-TLS FEATURE (RFC 8071's netconf-ch-tls variant): see RdpProfile.netconfCallHomeTransport/netconfCallHomeTlsClientCertificatePem's doc comments.
    var netconfCallHomeTransport by rememberSaveable { mutableStateOf(profile?.netconfCallHomeTransport?.ifBlank { "SSH" } ?: "SSH") }
    var netconfCallHomeTlsClientCertificatePem by remember { mutableStateOf(profile?.netconfCallHomeTlsClientCertificatePem ?: "") } // SECURITY: key-adjacent material, not rememberSaveable — see sshPrivateKey's note above

    // SERIAL-CONSOLE FEATURE (Part 1/N): see RdpProfile.serialConsoleTransport/
    // serialConsoleBaudRate/serialConsoleDataBits/serialConsoleParity/
    // serialConsoleStopBits/serialConsoleDevicePath/
    // serialConsoleHardwareFlowControl's doc comments. Reuses
    // the generic host/port fields above for the endpoint.
    var serialConsoleTransport by rememberSaveable { mutableStateOf(profile?.serialConsoleTransport ?: com.systemsgo.hex.data.model.SerialRedirectMode.RFC_2217) }
    var serialConsoleBaudRate by rememberSaveable { mutableStateOf((profile?.serialConsoleBaudRate ?: 9600).toString()) }
    var serialConsoleDataBits by rememberSaveable { mutableStateOf(profile?.serialConsoleDataBits ?: 8) }
    var serialConsoleParity by rememberSaveable { mutableStateOf(profile?.serialConsoleParity ?: com.systemsgo.hex.data.model.SerialParity.NONE) }
    var serialConsoleStopBits by rememberSaveable { mutableStateOf(profile?.serialConsoleStopBits ?: com.systemsgo.hex.data.model.SerialStopBits.ONE) }
    var serialConsoleDevicePath by rememberSaveable { mutableStateOf(profile?.serialConsoleDevicePath ?: "") }
    var serialConsoleHardwareFlowControl by rememberSaveable { mutableStateOf(profile?.serialConsoleHardwareFlowControl ?: false) }

    // SSH Tunnel state
    var sshTunnelEnabled     by rememberSaveable { mutableStateOf(profile?.sshTunnelEnabled ?: false) }
    // SSH-PROXYJUMP-CHAIN FEATURE (UI): the old single Jump Host fields
    // (sshTunnelHost/Port/Username/AuthType/Password/PrivateKey/
    // PrivateKeyPassphrase) are replaced by this editable ordered chain —
    // see SshTunnelHopChainEditor below. Seeded from
    // RdpProfile.effectiveSshTunnelHops so a profile saved by an older
    // build (only the legacy single-hop fields populated, sshTunnelHops
    // empty) opens already migrated into a one-entry chain, exactly like
    // every other effectiveSshTunnelHops reader (RemoteSessionFactory,
    // etc.) — see that property's doc comment. Plain `remember` (matches
    // sshPortForwards just above): List<SshJumpHop> has no Bundle Saver,
    // so this is rebuilt from `profile` on process death instead.
    var sshTunnelHops by remember { mutableStateOf(profile?.effectiveSshTunnelHops ?: emptyList()) }

    var portTouchedByUser by rememberSaveable { mutableStateOf(profile != null) }

    // ── Folders & Tags ───────────────────────────────────────────────────────
    var folderId by rememberSaveable { mutableStateOf(profile?.folderId) }
    // Edited as a single comma-separated string for simplicity; split/trimmed/
    // de-duplicated back into RdpProfile.tags on save (see below).
    var tagsInput by rememberSaveable {
        mutableStateOf(profile?.tags?.joinToString(", ") ?: "")
    }

    // Wake-on-LAN state
    var wolEnabled         by rememberSaveable { mutableStateOf(profile?.wolEnabled ?: false) }
    var wolMacAddress      by rememberSaveable { mutableStateOf(profile?.wolMacAddress ?: "") }
    var wolBroadcastAddress by rememberSaveable { mutableStateOf(profile?.wolBroadcastAddress ?: "255.255.255.255") }
    // WAKE-CONNECT FEATURE: Magic Packet UDP port + the timing knobs for the
    // "Wake & Connect" reachability poll. Edited as strings (like every other
    // numeric field in this form) and parsed back to Int on save.
    var wolPort by rememberSaveable {
        mutableStateOf((profile?.wolPort ?: com.systemsgo.hex.util.WakeOnLanManager.DEFAULT_WOL_PORT).toString())
    }
    var wolConnectTimeoutSeconds by rememberSaveable { mutableStateOf((profile?.wolConnectTimeoutSeconds ?: 60).toString()) }
    var wolRetryIntervalSeconds  by rememberSaveable { mutableStateOf((profile?.wolRetryIntervalSeconds ?: 3).toString()) }
    var wolMaxRetries            by rememberSaveable { mutableStateOf((profile?.wolMaxRetries ?: 20).toString()) }

    // ── UX REDESIGN: every optional section is now its own self-contained,
    // collapsible card (icon + title + live status + chevron) instead of a
    // long flat list of mixed switches and fields. Required/essential fields
    // stay visible up top ("Quick Connect"); everything situational lives
    // under "Optional Settings", collapsed by default but auto-opens the
    // moment its switch is turned on so the user always sees what they just
    // enabled. This keeps the screen calm for the common case (4–5 fields)
    // while still surfacing every capability for power users.
    var expandSecurity by rememberSaveable { mutableStateOf(profile != null && (domain.isNotBlank() || acceptSelfSignedCertificate)) }
    var expandGateway by rememberSaveable { mutableStateOf(gatewayEnabled) }
    var expandProxy by rememberSaveable { mutableStateOf(proxyEnabled) }
    // RDP-OVER-WEBSOCKET FEATURE: same shape as expandProxy above, but keyed
    // off transportMode.isWebSocket rather than a Boolean toggle — expanded
    // by default whenever the profile is already using WS/WSS.
    var expandTransport by rememberSaveable { mutableStateOf(transportMode.isWebSocket) }
    var expandRemoteApp by rememberSaveable { mutableStateOf(remoteAppEnabled) }
    // CODEC-NEGOTIATION FEATURE: mirrors expandTrust below — collapsed by
    // default even for an existing profile, since AUTO (the common case)
    // needs no attention; only auto-expanded if the saved profile already
    // picked something other than AUTO, so a user revisiting a
    // non-default choice sees it immediately instead of having to know to
    // open the card.
    var expandCodec by rememberSaveable { mutableStateOf(codecPreference != CodecPreference.AUTO) }
    var expandTunnel  by rememberSaveable { mutableStateOf(sshTunnelEnabled) }
    var expandWol     by rememberSaveable { mutableStateOf(wolEnabled) }
    var expandTrust   by rememberSaveable { mutableStateOf(false) }
    // SNMP FEATURE: collapsed by default unless already turned on for this profile.
    var expandSnmpMonitoring by rememberSaveable { mutableStateOf(profile?.snmpMonitoringEnabled ?: false) }
    // COLLAPSIBLE-OPTIONAL-GROUP: the whole "Optional Settings" block (every
    // card below — security, gateway, tunnel, WOL, trust) stays hidden until
    // the person taps the group header, so a brand-new profile form shows only
    // Quick Connect. If any of these were already configured on an existing
    // profile, start the group open so editing doesn't hide settings that are
    // already in effect.
    var showOptionalSettings by remember {
        // RDP-OVER-WEBSOCKET FEATURE: expandTransport added to this check —
        // without it, a profile opened with transportMode already WS/WSS
        // (e.g. the "RDP over WebSocket" catalog preset) had expandTransport
        // = true but the surrounding "Advanced (optional)" group itself
        // stayed collapsed, hiding the very section that was supposed to be
        // pre-opened. Same bug shape expandSecurity/expandGateway/
        // expandTunnel/expandWol already guard against for their own
        // sections.
        mutableStateOf(expandSecurity || expandGateway || expandTunnel || expandWol || expandTransport)
    }

    fun selectProtocol(newType: ProtocolType) {
        if (newType == protocolType) return
        protocolType = newType
        if (!portTouchedByUser) port = newType.defaultPort.toString()
    }

    // UI-SPLIT (user request): Host/IP and Port are two separate fields again,
    // laid out side-by-side in one row (not stacked) so the port can be edited
    // directly without having to place the cursor inside a combined "ip:port"
    // string. `portTouchedByUser` still tracks manual edits so switching
    // protocol tabs keeps updating the port to that protocol's default until
    // the user actually types one themselves.

    // PERF-FIX (recomposition scope): this used to be a plain `val isValid = ...`
    // computed directly in ProfileFormDialog's own top-level body, reading
    // name/host/port/username/gatewayPort/sshTunnelPort/... together. Because
    // that read happened in the outermost composition scope (not inside a
    // nested lambda), Compose had to re-walk this whole ~650-line composable —
    // including the Dialog/TopBar/BottomBar wrapper — on every single keystroke
    // in *any* field, even one totally unrelated to save-eligibility, just to
    // re-evaluate this one boolean. `derivedStateOf` keeps the same dependency
    // reads but only notifies readers (the Save button below) when the *result*
    // actually flips — e.g. host going from blank to non-blank — not on every
    // character typed while the value stays valid (or stays invalid).
    val canSave by remember {
        derivedStateOf {
            // LISTEN-MODE FEATURE: in listen mode the app never dials
            // host/port at all (see Connection.useListenMode's doc
            // comment) — it only opens vncListenPort and waits — so
            // neither field should block Save for a VNC profile with this
            // toggle on.
            val vncListening = protocolType == ProtocolType.VNC && vncListenModeEnabled
            // WEB-PORTAL FEATURE: a Web/HTTPS profile is identified by its
            // portal URL, not host/port (host/port are still populated from
            // the URL's authority purely for search/export consistency — see
            // RdpProfile.webUrl's doc comment — but they're not what the user
            // actually typed in, so they shouldn't gate Save here the way
            // they do for every other protocol).
            val isWeb = protocolType == ProtocolType.WEB
            val webUrlValid = webUrl.isNotBlank() && (webUrl.startsWith("https://") || webUrl.startsWith("http://"))
            // WAKE-ON-LAN-STANDALONE FEATURE: a WAKE_ON_LAN profile is identified
            // by its MAC address (validated separately, below, via wolValid —
            // always required for this protocol regardless of the wolEnabled
            // toggle, since the toggle itself is meaningless/hidden for a
            // protocol that *is* Wake-on-LAN), not host/port — same "this
            // protocol doesn't dial host:port the normal way" reasoning as
            // isWeb/vncListening above.
            val isWol = protocolType == ProtocolType.WAKE_ON_LAN
            // WEBDAV-STANDALONE FEATURE: a WebDAV profile is identified by
            // its webdavBaseUrl (validated below), not host/port — same
            // "this protocol doesn't dial host:port the normal way"
            // reasoning as isWeb/isWol above (host/port are still populated
            // for display/export consistency by ProtocolCatalog, just not
            // what the user actually typed here).
            val isWebdav = protocolType == ProtocolType.WEBDAV
            // AMT-VPRO FEATURE — Phase 6 (CIRA setup UI): a CIRA-enabled AMT
            // profile is identified by the relay's address + a device ID,
            // not the AMT device's own host/port (which it has no
            // direct/dialable one of, from this app's perspective) — same
            // "this protocol doesn't dial host:port the normal way"
            // reasoning as isWeb/isWol/isWebdav above.
            val isCiraAmt = protocolType == ProtocolType.AMT && ciraEnabled
            val ciraFieldsValid = ciraRelayHost.isNotBlank() && isPortInRange(ciraRelayPort) && ciraDeviceId.isNotBlank()
            val fieldsValid = name.isNotBlank() && (host.isNotBlank() || vncListening || isWeb || isWol || isWebdav || isCiraAmt) &&
                (isPortInRange(port) || vncListening || isWeb || isWol || isWebdav || isCiraAmt) &&
                (!isCiraAmt || ciraFieldsValid) &&
                (!isWeb || webUrlValid) &&
                (gatewayPort.isBlank() || isPortInRange(gatewayPort)) &&
                (proxyPort.isBlank() || isPortInRange(proxyPort)) &&
                // SSH-PROXYJUMP-CHAIN FEATURE: when the tunnel is on (and
                // actually applicable — it's RDP/VNC/Telnet only, never SSH),
                // every hop in the chain needs a complete host/port/username
                // (and a private key, if that's its auth type) before Save is
                // allowed — mirrors SshJumpHop.isValid, plus the key-required
                // check SshJumpHop.isValid doesn't cover on its own.
                (protocolType == ProtocolType.SSH || !sshTunnelEnabled || (
                    sshTunnelHops.isNotEmpty() && sshTunnelHops.all { hop ->
                        hop.isValid && (hop.authType == SshAuthType.PASSWORD || hop.privateKey.isNotBlank())
                    }
                )) &&
                (!socksProxyEnabled || isPortInRange(socksProxyPort)) &&
                (!x11ForwardingEnabled || (
                    x11DisplayHost.isNotBlank() &&
                    x11DisplayNumber.toIntOrNull()?.let { it in 0..99 } == true &&
                    (x11AuthCookie.isBlank() || X11AuthCookie.validate(x11AuthCookie) != null)
                )) &&
                when (protocolType) {
                    ProtocolType.RDP -> username.isNotBlank()
                    ProtocolType.SSH -> username.isNotBlank() &&
                        (sshAuthType == SshAuthType.PASSWORD || sshPrivateKey.isNotBlank())
                    // Telnet has no protocol-level auth field (login, if any,
                    // happens as plain terminal text once connected) — only
                    // host/port (already validated above) are required.
                    ProtocolType.TELNET -> true
                    // RLOGIN FEATURE: unlike Telnet, the RFC 1282 handshake
                    // itself carries a username (this app reuses [username]
                    // as the handshake's "client user name" field — see
                    // RloginClient/RemoteSessionFactory) so, unlike Telnet,
                    // a blank username would send an empty handshake field.
                    ProtocolType.RLOGIN -> username.isNotBlank()
                    // MOSH FEATURE: its bootstrap phase is plain SSH exec of
                    // mosh-server (see RemoteSessionFactory's MOSH branch), so
                    // the same auth requirement as SSH above applies —
                    // username, plus either a password or a private key.
                    ProtocolType.MOSH -> username.isNotBlank() &&
                        (sshAuthType == SshAuthType.PASSWORD || sshPrivateKey.isNotBlank())
                    // SPICE-PROTOCOL FEATURE (Part 1/N): no dedicated
                    // required field yet (no separate SPICE password/TLS
                    // fields exist in the form) — host/port validated
                    // generically above is enough for now.
                    ProtocolType.SPICE -> true
                    // RTSP FEATURE: no dedicated required field of its own —
                    // host/port validated generically above is enough; the
                    // stream path may legitimately be blank (root stream).
                    ProtocolType.RTSP -> true
                    // BUGFIX-UI-8 REVERTED: this used to be hard-coded `false` because
                    // com.undatech.opaque.RfbConnectable was a stub that always threw
                    // VncNotImplementedException — saving a VNC profile could never lead
                    // to a working connection, so the save button was disabled outright.
                    // RfbConnectable now contains a real RFB/VNC client (handshake,
                    // DES auth, Raw/CopyRect/ZRLE framebuffer decoding — see its class
                    // doc), so VNC profiles work like RDP/SSH ones. Classic VNC auth
                    // needs no username (only name + host, already required above, and
                    // an optional password for "VNC Authentication" security type —
                    // "None" security type servers have no password at all).
                    // ULTRAVNC-REPEATER FEATURE: Mode II needs a non-blank ID
                    // (the repeater has nothing to match otherwise); Mode I
                    // needs nothing extra — the repeater's own config already
                    // maps host/port to one target server.
                    ProtocolType.VNC -> (!vncRepeaterEnabled ||
                        vncRepeaterMode == VncRepeaterMode.MODE_I ||
                        vncRepeaterId.isNotBlank()) &&
                        // LISTEN-MODE FEATURE: needs a valid port to bind;
                        // mutually exclusive with the repeater toggle above
                        // (enforced at the onCheckedChange call sites), so
                        // both conditions can be ANDed safely here.
                        (!vncListenModeEnabled || isPortInRange(vncListenPort))
                    // No protocol-level auth field — the portal itself (an
                    // in-page login form, or an HTTP auth prompt handled live
                    // by WebPortalActivity) owns authentication. Only a
                    // well-formed URL (already validated above) is required.
                    ProtocolType.WEB -> true
                    // REDFISH-IPMI FEATURE (AMT-VPRO FEATURE reuses the same
                    // rule): all three need host/port (already validated
                    // generically above, same as RDP/VNC/SSH/Telnet) plus a
                    // username — every BMC/AMT account is username+password,
                    // there's no anonymous/keyless mode worth surfacing in
                    // this form.
                    ProtocolType.REDFISH -> username.isNotBlank()
                    ProtocolType.IPMI -> username.isNotBlank()
                    ProtocolType.AMT -> username.isNotBlank()
                    // SERIAL-CONSOLE FEATURE: same reasoning as Telnet above —
                    // a serial-device server (ser2net etc.) has no
                    // protocol-level auth field of its own; only host/port
                    // (already validated generically above) are required.
                    ProtocolType.SERIAL_CONSOLE -> true
                    // RESTCONF FEATURE (Part 1/4): this generic form only
                    // exposes host/port/username/password today (the
                    // auth-type picker, bearer/API-key/OAuth2/cert fields
                    // land with Part 4's dedicated RESTCONF Configuration
                    // Screen) — default restconfAuthType is BASIC, so
                    // require a username exactly like REDFISH/IPMI/AMT above.
                    ProtocolType.RESTCONF -> username.isNotBlank()
                    // SNMP FEATURE: v1/v2c need a non-empty community; v3
                    // needs a username, and — per RFC 3414 §11.2 — an auth
                    // passphrase of at least 8 characters whenever the
                    // selected security level uses authentication (USM
                    // requires this minimum; shorter passphrases are
                    // rejected by every real agent, not just an app-side
                    // preference here), plus a privacy passphrase too under
                    // authPriv specifically.
                    // SNMP-ROADMAP FIX: snmpPort was digit-filtered on entry
                    // but never actually range-checked here, so e.g. "0" or a
                    // 5-digit value above 65535 could reach Save — unlike
                    // every other port field in this form (gatewayPort,
                    // proxyPort, socksProxyPort, vncListenPort, wolPort all
                    // gate on isPortInRange). Now consistent with those.
                    ProtocolType.SNMP -> isPortInRange(snmpPort) && when (snmpVersion) {
                        "V3" -> snmpV3Username.isNotBlank() &&
                            (snmpV3SecurityLevel == "NO_AUTH_NO_PRIV" || snmpV3AuthPassphrase.length >= 8) &&
                            (snmpV3SecurityLevel != "AUTH_PRIV" || snmpV3PrivPassphrase.length >= 8)
                        else -> snmpCommunity.isNotBlank()
                    }
                    // NETCONF FEATURE: SSH-transported, same requirement as
                    // ProtocolType.SSH above — a username plus either a
                    // password or a private key.
                    ProtocolType.NETCONF -> username.isNotBlank() &&
                        (sshAuthType == SshAuthType.PASSWORD || sshPrivateKey.isNotBlank())
                    // GUACAMOLE-PROTOCOL FEATURE: needs a server URL
                    // (validated for well-formedness elsewhere, same spot
                    // host/port normally is — see GuacamoleProfile's doc for
                    // why this isn't the generic host/port check), a
                    // username (Guacamole's REST login always needs one),
                    // and a picked connection identifier from
                    // GuacamoleConnectionPickerDialog (or typed in directly).
                    ProtocolType.GUACAMOLE -> guacServerUrl.isNotBlank() &&
                        username.isNotBlank() &&
                        guacConnectionIdentifier.isNotBlank()
                    // PROXMOX-API FEATURE: TOKEN mode needs a full token id
                    // (user@realm!tokenid) plus its secret; PASSWORD mode
                    // reuses the generic username/password fields above
                    // (username entered as user@realm, e.g. root@pam).
                    ProtocolType.PROXMOX -> if (proxmoxAuthMode == ProxmoxAuthMode.TOKEN.name)
                        proxmoxTokenId.isNotBlank() && proxmoxTokenSecret.isNotBlank()
                    else
                        username.isNotBlank() && password.isNotBlank()
                    // MODBUS-TCP FEATURE (Part 2/2): no protocol-level auth —
                    // a Modbus/TCP master just needs a reachable host/port
                    // (already validated generically above) and a unit id
                    // in the valid 0-255 range (RTU-heritage single byte).
                    ProtocolType.MODBUS_TCP -> modbusUnitId.toIntOrNull()?.let { it in 0..255 } ?: false
                    // VIRTUALBOX-VRDE FEATURE (Part 1/N): VRDE credentials
                    // are effectively optional — a NULL_AUTH-mode VM (the
                    // default) accepts any/no username at the RDP layer, so
                    // unlike ProtocolType.RDP above this doesn't require a
                    // username; only host/port (already validated generically
                    // above) is required.
                    ProtocolType.VIRTUALBOX_VRDE -> true
                    // VMWARE-VSPHERE FEATURE (Part 1/N): same reasoning as
                    // REDFISH/IPMI/AMT/PROXMOX's PASSWORD-mode branch above —
                    // every vSphere account is username+password, no
                    // anonymous mode worth surfacing here.
                    ProtocolType.VMWARE_VSPHERE -> username.isNotBlank()
                    // WAKE-ON-LAN-STANDALONE FEATURE: no protocol-level auth and no
                    // host/port of its own (bypassed above via isWol) — the actual
                    // required field is the MAC address, enforced unconditionally
                    // for this protocol by wolValid below (note the `|| isWol`
                    // there — a WAKE_ON_LAN profile must pass that check even
                    // though its own wolEnabled toggle is forced on and hidden,
                    // never surfaced for the user to turn off).
                    ProtocolType.WAKE_ON_LAN -> true
                    // SFTP-STANDALONE FEATURE: same auth requirement as
                    // ProtocolType.SSH above (username plus either a password or
                    // a private key) — it's the same wire protocol, just without
                    // the terminal-session-only options (tunnel/SOCKS/X11/port
                    // forwards) SSH's branch also gates on protocolType ==
                    // ProtocolType.SSH specifically elsewhere in this file. Host/
                    // port ARE required here (unlike WAKE_ON_LAN/WEB above) —
                    // an SFTP connection dials a real host exactly like SSH does,
                    // so no isWol/isWeb-style bypass is needed; the generic
                    // fieldsValid check above already covers it.
                    ProtocolType.SFTP -> username.isNotBlank() &&
                        (sshAuthType == SshAuthType.PASSWORD || sshPrivateKey.isNotBlank())
                    // FTP/FTPS-STANDALONE FEATURE: host/port (generic check
                    // above) is all that's strictly required — ftpSecurity
                    // always has a valid default (PLAIN) and ftpPassiveMode
                    // is a plain toggle, neither can be "invalid".
                    ProtocolType.FTP -> true
                    ProtocolType.FTPS -> true
                    // SMB-STANDALONE FEATURE: SMB has no "browse the whole
                    // server" mode the way FTP/SFTP do — a share name is
                    // mandatory (see RdpProfile.smbShare's doc comment);
                    // smbDomain stays optional (blank = no domain/workgroup).
                    ProtocolType.SMB -> smbShare.isNotBlank()
                    // WEBDAV-STANDALONE FEATURE: identified by its base URL
                    // (host/port bypassed above via isWebdav), so require a
                    // non-blank, http(s)-prefixed webdavBaseUrl instead —
                    // same shape as isWeb's webUrlValid above.
                    ProtocolType.WEBDAV -> webdavBaseUrl.isNotBlank() &&
                        (webdavBaseUrl.startsWith("https://") || webdavBaseUrl.startsWith("http://"))
                    // NFS-STANDALONE FEATURE: still dials a real host (no
                    // bypass needed, unlike WEBDAV), but the export path is
                    // just as mandatory as an SMB share name — see
                    // RdpProfile.nfsExportPath's doc comment. uid/gid/
                    // mountdPort all have safe defaults (0 / auto-detect)
                    // so they never block Save.
                    ProtocolType.NFS -> nfsExportPath.isNotBlank()
                }
            val wolValid = (!wolEnabled && !isWol) ||
                (wolMacAddress.isNotBlank() && com.systemsgo.hex.util.WakeOnLanManager.isValidMac(wolMacAddress) &&
                    isPortInRange(wolPort) &&
                    (wolConnectTimeoutSeconds.toIntOrNull()?.let { it > 0 } == true) &&
                    (wolRetryIntervalSeconds.toIntOrNull()?.let { it > 0 } == true) &&
                    (wolMaxRetries.toIntOrNull()?.let { it > 0 } == true))
            fieldsValid && wolValid
        }
    }

    // FIX-MED-R3-4: TOFU fingerprint clear — only available when editing an existing profile
    val context = androidx.compose.ui.platform.LocalContext.current
    var showClearTofuSshDialog    by remember { mutableStateOf(false) }
    var showClearTofuTunnelDialog by remember { mutableStateOf(false) }
    // RESET-TRUSTED-CERT FIX: RDP and VNC each pin their own TOFU fingerprint
    // (PREFS_TOFU_RDP / PREFS_TOFU_VNC — see RdpRemoteAdapter.kt / VncClient.kt)
    // exactly like SSH does, but until now only SSH/SSH-Tunnel had a UI path
    // to clear a stale one. Without this, a legitimately renewed RDP/VNC
    // server certificate permanently refused the connection (see
    // verifyServerCertificate()'s hard-reject comment) with no way out short
    // of wiping the whole app's data — losing every other saved connection
    // too. Mirrors the SSH dialogs below exactly, just against the RDP/VNC
    // prefs file and default port.
    var showClearTofuRdpDialog    by remember { mutableStateOf(false) }
    var showClearTofuVncDialog    by remember { mutableStateOf(false) }
    // TELNET-TLS FEATURE: same reasoning as showClearTofuRdpDialog/
    // showClearTofuVncDialog above, against TelnetClient's own
    // "systemsgo_tofu_telnet_tls" prefs file and default port 23.
    var showClearTofuTelnetDialog by remember { mutableStateOf(false) }
    var tofuClearedMessage        by remember { mutableStateOf("") }

    // Confirmation dialog — SSH fingerprint
    if (showClearTofuSshDialog) {
        AlertDialog(
            onDismissRequest = { showClearTofuSshDialog = false },
            containerColor   = StarfieldSurface,
            shape            = RoundedCornerShape(20.dp),
            icon  = { Icon(Icons.Outlined.Security, null, tint = SolarFlare, modifier = Modifier.size(36.dp)) },
            title = { Text(stringResource(R.string.tofu_clear_confirm_title), color = StarDust, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.tofu_clear_confirm_message), color = CometTail) },
            confirmButton = {
                SpaceButton(
                    text    = stringResource(R.string.clear),
                    onClick = {
                        val hostKey = "${host.trim()}:${port.toIntOrNull() ?: 22}"
                        // LIVE-HIGH-1 FIX: commit() for atomic TOFU removal.
                        context.openEncryptedPrefs("systemsgo_tofu_ssh")
                            .edit().remove(hostKey).commit()
                        tofuClearedMessage = context.getString(R.string.tofu_cleared)
                        showClearTofuSshDialog = false
                    },
                    variant  = ButtonVariant.DANGER,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearTofuSshDialog = false }) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            }
        )
    }

    // Confirmation dialog — SSH Tunnel fingerprint
    if (showClearTofuTunnelDialog) {
        AlertDialog(
            onDismissRequest = { showClearTofuTunnelDialog = false },
            containerColor   = StarfieldSurface,
            shape            = RoundedCornerShape(20.dp),
            icon  = { Icon(Icons.Outlined.Security, null, tint = SolarFlare, modifier = Modifier.size(36.dp)) },
            title = { Text(stringResource(R.string.tofu_clear_confirm_title), color = StarDust, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.tofu_clear_confirm_message), color = CometTail) },
            confirmButton = {
                SpaceButton(
                    text    = stringResource(R.string.clear),
                    onClick = {
                        // SSH-PROXYJUMP-CHAIN FEATURE: each hop pins its own
                        // host-key entry in this prefs file, keyed the same
                        // way SshTunnelManager.TofuHostKeyRepository.mapKey()
                        // does ("host:port") — clear every hop in the current
                        // chain, not just a single jump host.
                        val editor = context.openEncryptedPrefs("systemsgo_tofu_tunnel").edit()
                        sshTunnelHops.forEach { hop ->
                            editor.remove("${hop.host.trim()}:${hop.port}")
                        }
                        // LIVE-HIGH-1 FIX: commit() for atomic TOFU removal.
                        editor.commit()
                        tofuClearedMessage = context.getString(R.string.tofu_cleared)
                        showClearTofuTunnelDialog = false
                    },
                    variant  = ButtonVariant.DANGER,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearTofuTunnelDialog = false }) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            }
        )
    }

    // Confirmation dialog — RDP fingerprint
    if (showClearTofuRdpDialog) {
        AlertDialog(
            onDismissRequest = { showClearTofuRdpDialog = false },
            containerColor   = StarfieldSurface,
            shape            = RoundedCornerShape(20.dp),
            icon  = { Icon(Icons.Outlined.Security, null, tint = SolarFlare, modifier = Modifier.size(36.dp)) },
            title = { Text(stringResource(R.string.tofu_clear_confirm_title), color = StarDust, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.tofu_clear_confirm_message), color = CometTail) },
            confirmButton = {
                SpaceButton(
                    text    = stringResource(R.string.clear),
                    onClick = {
                        // Same "host:port" key RdpRemoteAdapter.verifyServerCertificate() builds.
                        val hostKey = "${host.trim()}:${port.toIntOrNull() ?: 3389}"
                        context.openEncryptedPrefs("systemsgo_tofu_rdp")
                            .edit().remove(hostKey).commit()
                        tofuClearedMessage = context.getString(R.string.tofu_cleared)
                        showClearTofuRdpDialog = false
                    },
                    variant  = ButtonVariant.DANGER,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearTofuRdpDialog = false }) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            }
        )
    }

    // Confirmation dialog — VNC fingerprint
    if (showClearTofuVncDialog) {
        AlertDialog(
            onDismissRequest = { showClearTofuVncDialog = false },
            containerColor   = StarfieldSurface,
            shape            = RoundedCornerShape(20.dp),
            icon  = { Icon(Icons.Outlined.Security, null, tint = SolarFlare, modifier = Modifier.size(36.dp)) },
            title = { Text(stringResource(R.string.tofu_clear_confirm_title), color = StarDust, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.tofu_clear_confirm_message), color = CometTail) },
            confirmButton = {
                SpaceButton(
                    text    = stringResource(R.string.clear),
                    onClick = {
                        // Same "host:port" key VncClient.VncTofuVerifier.prefKey builds.
                        val hostKey = "${host.trim()}:${port.toIntOrNull() ?: 5900}"
                        context.openEncryptedPrefs("systemsgo_tofu_vnc")
                            .edit().remove(hostKey).commit()
                        tofuClearedMessage = context.getString(R.string.tofu_cleared)
                        showClearTofuVncDialog = false
                    },
                    variant  = ButtonVariant.DANGER,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearTofuVncDialog = false }) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            }
        )
    }

    // Confirmation dialog — Telnet TLS certificate fingerprint
    if (showClearTofuTelnetDialog) {
        AlertDialog(
            onDismissRequest = { showClearTofuTelnetDialog = false },
            containerColor   = StarfieldSurface,
            shape            = RoundedCornerShape(20.dp),
            icon  = { Icon(Icons.Outlined.Security, null, tint = SolarFlare, modifier = Modifier.size(36.dp)) },
            title = { Text(stringResource(R.string.tofu_clear_confirm_title), color = StarDust, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.tofu_clear_confirm_message), color = CometTail) },
            confirmButton = {
                SpaceButton(
                    text    = stringResource(R.string.clear),
                    onClick = {
                        // Same "host:port" key TelnetClient.verifyServerCertificate() builds.
                        val hostKey = "${host.trim()}:${port.toIntOrNull() ?: 23}"
                        context.openEncryptedPrefs("systemsgo_tofu_telnet_tls")
                            .edit().remove(hostKey).commit()
                        tofuClearedMessage = context.getString(R.string.tofu_cleared)
                        showClearTofuTelnetDialog = false
                    },
                    variant  = ButtonVariant.DANGER,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showClearTofuTelnetDialog = false }) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            }
        )
    }

    // UI-REDESIGN: the connection-setup form has grown to include RDP/VNC/SSH
    // fields, gateway, SSH tunnel, Wake-on-LAN, and advanced display settings —
    // far too much content for a constrained AlertDialog box (which clipped to
    // 85% height and squeezed everything into a tiny scrollable area). It now
    // opens as a proper full-screen surface: a top app bar with a close button
    // replaces the dialog title, the form fills the available height, and a
    // sticky bottom bar holds Cancel/Save — the same pattern used by Settings
    // and every other full screen in the app, for a consistent, professional layout.
    // UI-FIX (transitions): the "Add/Edit Connection" form previously popped
    // open/closed instantly using the platform Dialog's default window
    // animation — cheap and generic. It now plays the same "hyperspace"
    // scale + drift + fade motion (SpaceMotion) used by every screen
    // transition in the app. Opening plays automatically via the
    // `visible = true` LaunchedEffect below; closing (back arrow, Cancel,
    // Save, or a system back-press/outside tap via onDismissRequest) all
    // route through `closeThen()`, which plays the exit animation and only
    // invokes the real dismiss/save callback once it finishes — so the form
    // never just vanishes.
        var visible by remember { mutableStateOf(false) }
        var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        fun closeThen(action: (() -> Unit)? = null) {
            pendingAction = action
            visible = false
        }
        LaunchedEffect(Unit) { visible = true }
        LaunchedEffect(visible) {
            if (!visible) {
                delay(SpaceMotion.DIALOG_EXIT_MS)
                pendingAction?.invoke() ?: onDismiss()
            }
        }

    androidx.compose.ui.window.Dialog(
        // Back-press and outside-tap both call this directly; routing it
        // through closeThen() keeps every dismissal path animated, instead
        // of only the explicit close button/Cancel/Save.
        onDismissRequest = { closeThen() },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows  = false
        )
    ) {
        AnimatedVisibility(
            visible = visible,
            enter   = SpaceMotion.dialogEnter,
            exit    = SpaceMotion.dialogExit
        ) {
        // PERF-FIX: StarfieldBackground runs a continuous, never-stopping Canvas
        // animation (twinkling stars + drifting nebula glow, redrawn every frame)
        // designed for the home screen where nothing else demands frame budget.
        // Using it behind a data-entry form full of text fields made every
        // keystroke and every label-float animation compete with that constant
        // background redraw — this is what made typing and field-focus
        // transitions feel slow/choppy. A form screen doesn't need decorative
        // motion, so it gets a static gradient instead: same space aesthetic,
        // zero per-frame redraw cost.
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProtocolIconBadge(protocolType)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        // LIVE-TITLE: reflects what the user is typing into the
                                        // "connection name" field character-by-character; falls
                                        // back to the generic title only while that field is empty.
                                        name.ifBlank {
                                            if (profile != null) stringResource(R.string.edit_profile)
                                            else stringResource(R.string.new_connection)
                                        },
                                        style = MaterialTheme.typography.titleLarge, color = StarDust,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        protocolType.label,
                                        style = MaterialTheme.typography.labelSmall, color = PulsarCyan
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { closeThen() }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_back),
                                    tint = PulsarCyan
                                )
                            }
                        },
                        // ADD-CONNECTION PROTOCOL PICKER (Part 2/2): "What is this
                        // protocol?" — only shown when the currently-selected
                        // protocolType actually has a catalog entry (true for all
                        // of them today; the null-check just keeps this safe if a
                        // future ProtocolType is added here before its catalog
                        // entry).
                        actions = {
                            if (ProtocolCatalog.byId[protocolType.name] != null) {
                                IconButton(onClick = { showProtocolIntro = true }) {
                                    Icon(
                                        Icons.Outlined.Info,
                                        contentDescription = stringResource(R.string.cd_what_is_this_protocol),
                                        tint = PulsarCyan
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                },
                bottomBar = {
                    Surface(color = StarfieldSurface.copy(alpha = 0.92f)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SpaceButton(
                                text     = stringResource(R.string.cancel),
                                onClick  = { closeThen() },
                                variant  = ButtonVariant.GHOST,
                                modifier = Modifier.weight(1f)
                            )
                            SpaceButton(
                                text    = stringResource(R.string.save),
                                onClick = {
                                    val base = profile ?: RdpProfile(
                                        // ENTRA-ID-AUTH FEATURE: keep this id in sync with
                                        // whatever onSignInWithMicrosoft already linked — see
                                        // pendingProfileId's doc comment above.
                                        id = pendingProfileId ?: java.util.UUID.randomUUID().toString(),
                                        name = "", host = "", username = "", password = "",
                                    )
                                    val saved = base.copy(
                                        name = name.trim(), protocolType = protocolType,
                                        // WEB-PORTAL FEATURE: host/port aren't user-typed fields for
                                        // a Web/HTTPS profile (webUrl is) — derive them from the URL's
                                        // authority purely so this profile still sorts/searches/exports
                                        // the same way every other profile does. See RdpProfile.webUrl's
                                        // doc comment.
                                        host = if (protocolType == ProtocolType.WEB)
                                            (android.net.Uri.parse(webUrl.trim()).host ?: "") else host.trim(),
                                        port = if (protocolType == ProtocolType.WEB)
                                            (android.net.Uri.parse(webUrl.trim()).port.takeIf { it > 0 } ?: ProtocolType.WEB.defaultPort)
                                        else (port.toIntOrNull() ?: protocolType.defaultPort),
                                        username = username.trim(), password = password,
                                        domain = domain.trim(), useNla = useNla,
                                        acceptSelfSignedCertificate = acceptSelfSignedCertificate &&
                                            (protocolType == ProtocolType.RDP || protocolType == ProtocolType.REDFISH ||
                                                protocolType == ProtocolType.RESTCONF ||
                                                (protocolType == ProtocolType.AMT && amtUseTls)),  // BUG-3 FIX + REDFISH-IPMI FEATURE + AMT-VPRO FEATURE + RESTCONF FEATURE
                                        // REDFISH-IPMI FEATURE: irrelevant to every other protocol,
                                        // but harmless to always write — RdpProfile.ipmiPrivilegeLevel
                                        // is simply ignored unless protocolType == IPMI.
                                        ipmiPrivilegeLevel = ipmiPrivilegeLevel,
                                        // IPMI-KG-FEATURE: same "harmless to always write, ignored
                                        // unless protocolType == IPMI" reasoning as ipmiPrivilegeLevel.
                                        ipmiKgKey = ipmiKgKey.trim(),
                                        // AMT-VPRO FEATURE: same reasoning as ipmiPrivilegeLevel above —
                                        // ignored unless protocolType == AMT.
                                        amtUseTls = amtUseTls,
                                        // AMT-VPRO FEATURE — Phase 6 (CIRA setup UI): harmless to always
                                        // write, same "ignored at read time unless relevant" reasoning
                                        // as amtUseTls above — only consulted when protocolType == AMT
                                        // and ciraEnabled is true.
                                        ciraEnabled = ciraEnabled,
                                        ciraRelayHost = ciraRelayHost.trim(),
                                        ciraRelayPort = ciraRelayPort.toIntOrNull() ?: 8081,
                                        ciraRelayUsername = ciraRelayUsername.trim(),
                                        ciraRelayPassword = ciraRelayPassword,
                                        ciraDeviceId = ciraDeviceId.trim(),
                                        ciraRelayUseTls = ciraRelayUseTls,
                                        // SNMP FEATURE: harmless to always write, same reasoning as
                                        // ipmiPrivilegeLevel/amtUseTls above — ignored at read time
                                        // unless protocolType == SNMP or snmpMonitoringEnabled is on.
                                        snmpVersion = snmpVersion,
                                        snmpCommunity = snmpCommunity.trim(),
                                        snmpV3Username = snmpV3Username.trim(),
                                        snmpV3SecurityLevel = snmpV3SecurityLevel,
                                        snmpV3AuthProtocol = snmpV3AuthProtocol,
                                        snmpV3AuthPassphrase = snmpV3AuthPassphrase,
                                        snmpV3PrivProtocol = snmpV3PrivProtocol,
                                        snmpV3PrivPassphrase = snmpV3PrivPassphrase,
                                        snmpV3ContextName = snmpV3ContextName.trim(),
                                        snmpPort = snmpPort.toIntOrNull() ?: 161,
                                        snmpMonitoringEnabled = snmpMonitoringEnabled && protocolType != ProtocolType.SNMP,
                                        gatewayEnabled = gatewayEnabled && protocolType == ProtocolType.RDP,
                                        gatewayHost = gatewayHost.trim(), gatewayPort = gatewayPort.toIntOrNull() ?: 443,
                                        gatewayUsername = gatewayUsername.trim(), gatewayPassword = gatewayPassword,
                                        gatewayDomain = gatewayDomain.trim(),
                                        // ENTRA-ID-AUTH FEATURE: entraLinkedUpn is intentionally
                                        // NOT set here — it's display-only and kept in sync by
                                        // EntraSignInLinkStore.setLinkedUpn() right after a
                                        // successful sign-in, never by this form.
                                        gatewayAuthMode = gatewayAuthMode.name,
                                        // ENTRA-ID-AUTH FEATURE: see RdpProfile.gatewayScopeUri's
                                        // doc comment — user-entered, RDP only, same force-off-
                                        // when-irrelevant reasoning as the other protocol-specific
                                        // fields around it (e.g. remoteAppEnabled below).
                                        gatewayScopeUri = if (protocolType == ProtocolType.RDP) gatewayScopeUri.trim() else "",
                                        // OUTBOUND-PROXY FEATURE: RDP only — same force-off
                                        // reasoning as gatewayEnabled just above.
                                        proxyEnabled = proxyEnabled && protocolType == ProtocolType.RDP,
                                        proxyType = proxyType,
                                        proxyHost = proxyHost.trim(), proxyPort = proxyPort.toIntOrNull() ?: 1080,
                                        proxyUsername = proxyUsername.trim(), proxyPassword = proxyPassword,
                                        // PAC-SUPPORT FEATURE (Part 3/n — UI): unlike proxyEnabled
                                        // just above, this is NOT force-cleared for non-RDP
                                        // protocols — RdpProfile.pacUrl's doc comment says it also
                                        // applies to SSH-based outbound connections (direct SSH/
                                        // Telnet-over-tunnel, or an RDP/VNC tunnel's first hop),
                                        // so clearing it here would silently break that even though
                                        // this form's own PAC URL field is only shown/editable
                                        // inside the RDP-only "Outbound Proxy" card today.
                                        pacUrl = pacUrl.trim(),
                                        // RDP-OVER-WEBSOCKET FEATURE: RDP only —
                                        // force back to TCP/blank otherwise so a
                                        // stale WS/WSS choice can never persist on
                                        // a VNC/SSH profile (same reasoning as the
                                        // other RDP-only fields here, e.g.
                                        // gatewayEnabled/remoteAppEnabled).
                                        transportMode = if (protocolType == ProtocolType.RDP) transportMode.name else RdpTransportMode.TCP.name,
                                        webSocketConfig = if (protocolType == ProtocolType.RDP && transportMode.isWebSocket)
                                            RdpWebSocketConfigCodec.encode(webSocketConfig) else "",
                                        // REMOTEAPP: RDP only — force-off otherwise so a stale
                                        // switch state can never persist on a VNC/SSH profile
                                        // (same reasoning as the SSH-only fields above).
                                        remoteAppEnabled = remoteAppEnabled && protocolType == ProtocolType.RDP,
                                        remoteAppProgram = remoteAppProgram.trim(),
                                        remoteAppWorkingDir = remoteAppWorkingDir.trim(),
                                        remoteAppCmdLine = remoteAppCmdLine.trim(),
                                        remoteAppDisplayMode = remoteAppDisplayMode,
                                        // CODEC-NEGOTIATION FEATURE: RDP only — force back to AUTO
                                        // otherwise so a stale non-default codec choice can never
                                        // persist on a VNC/SSH profile (same reasoning as the
                                        // RDP-only fields above, e.g. remoteAppEnabled).
                                        codecPreference = if (protocolType == ProtocolType.RDP) codecPreference else CodecPreference.AUTO,
                                        vncViewOnly = vncViewOnly,
                                        // ULTRAVNC-REPEATER FEATURE: VNC only — force-off
                                        // otherwise so a stale switch state can never persist
                                        // on an RDP/SSH profile (same reasoning as the
                                        // protocol-specific fields above, e.g. remoteAppEnabled).
                                        vncRepeaterEnabled = vncRepeaterEnabled && protocolType == ProtocolType.VNC,
                                        vncRepeaterId = vncRepeaterId.trim(),
                                        vncRepeaterMode = vncRepeaterMode,
                                        // LISTEN-MODE FEATURE: VNC only — force-off otherwise,
                                        // same reasoning as vncRepeaterEnabled directly above.
                                        vncListenModeEnabled = vncListenModeEnabled && protocolType == ProtocolType.VNC,
                                        vncListenPort = vncListenPort.toIntOrNull() ?: 5500,
                                        sshAuthType = sshAuthType, sshPrivateKey = sshPrivateKey,
                                        sshPrivateKeyPassphrase = sshKeyPassphrase,
                                        // AGENT-FWD: only meaningful for an SSH profile authenticating
                                        // with a private key — force-off otherwise so a stale switch
                                        // state (e.g. left on from a prior PRIVATE_KEY edit, then
                                        // switched back to PASSWORD) can never persist as enabled.
                                        sshAgentForwardingEnabled = sshAgentForwardingEnabled &&
                                            protocolType == ProtocolType.SSH && sshAuthType == SshAuthType.PRIVATE_KEY,
                                        // DYN-PROXY: only meaningful for an SSH profile — force-off
                                        // otherwise so a stale switch state can never persist as
                                        // enabled on an RDP/VNC profile (same reasoning as agent
                                        // forwarding above).
                                        socksProxyEnabled = socksProxyEnabled && protocolType == ProtocolType.SSH,
                                        socksProxyPort    = socksProxyPort.toIntOrNull() ?: 1080,
                                        // X11 FORWARDING FEATURE: only meaningful for an SSH profile —
                                        // force-off otherwise, same reasoning as socksProxyEnabled above.
                                        x11ForwardingEnabled = x11ForwardingEnabled && protocolType == ProtocolType.SSH,
                                        x11DisplayHost    = x11DisplayHost.trim().ifBlank { "127.0.0.1" },
                                        x11DisplayNumber  = x11DisplayNumber.toIntOrNull() ?: 0,
                                        x11AuthCookie     = x11AuthCookie.trim(),
                                        // SSH-PORT-FORWARD FEATURE: only meaningful for an SSH
                                        // profile — force-empty otherwise, same reasoning as
                                        // socksProxyEnabled/x11ForwardingEnabled above.
                                        sshPortForwards = if (protocolType == ProtocolType.SSH) sshPortForwards else emptyList(),
                                        // TELNET-TLS FEATURE: only meaningful for a Telnet profile —
                                        // force-off otherwise, same reasoning as socksProxyEnabled
                                        // above so a stale switch state never persists cross-protocol.
                                        telnetUseTls      = telnetUseTls && protocolType == ProtocolType.TELNET,
                                        // RLOGIN FEATURE: only meaningful for a Rlogin profile —
                                        // force back to defaults otherwise, same reasoning as
                                        // telnetUseTls above so a stale value never persists
                                        // cross-protocol.
                                        rloginRemoteUsername = if (protocolType == ProtocolType.RLOGIN) rloginRemoteUsername.trim() else "",
                                        rloginTerminalType    = if (protocolType == ProtocolType.RLOGIN) rloginTerminalType.trim().ifBlank { "xterm/38400" } else "xterm/38400",
                                        // MOSH FEATURE: only meaningful for a Mosh profile — force
                                        // back to defaults otherwise, same reasoning as
                                        // rloginRemoteUsername/rloginTerminalType above.
                                        moshRemoteServerCommand = if (protocolType == ProtocolType.MOSH) moshRemoteServerCommand.trim().ifBlank { "mosh-server" } else "mosh-server",
                                        moshUdpPortRange        = if (protocolType == ProtocolType.MOSH) moshUdpPortRange.trim() else "",
                                        moshRemoteLocale        = if (protocolType == ProtocolType.MOSH) moshRemoteLocale.trim() else "",
                                        moshColorMode           = if (protocolType == ProtocolType.MOSH) moshColorMode else 256,
                                        moshPredictionMode      = if (protocolType == ProtocolType.MOSH) moshPredictionMode else MoshPredictionMode.ADAPTIVE.name,
                                        // PROXMOX-API FEATURE: only meaningful for a Proxmox profile —
                                        // force back to defaults otherwise, same reasoning as the
                                        // Mosh/Rlogin fields immediately above.
                                        proxmoxAuthMode         = if (protocolType == ProtocolType.PROXMOX) proxmoxAuthMode else ProxmoxAuthMode.TOKEN.name,
                                        proxmoxTokenId          = if (protocolType == ProtocolType.PROXMOX) proxmoxTokenId.trim() else "",
                                        proxmoxTokenSecret      = if (protocolType == ProtocolType.PROXMOX) proxmoxTokenSecret.trim() else "",
                                        proxmoxAcceptSelfSignedCertificate = if (protocolType == ProtocolType.PROXMOX) proxmoxAcceptSelfSignedCertificate else true,
                                        // MODBUS-TCP FEATURE (Part 2/2): only meaningful for a
                                        // Modbus profile — force back to defaults otherwise,
                                        // same reasoning as proxmox*/guac* above so a stale
                                        // value never persists cross-protocol. modbusPoints is
                                        // intentionally left untouched here (managed from
                                        // ModbusManagementScreen's Points tab, not this form).
                                        modbusUnitId = if (protocolType == ProtocolType.MODBUS_TCP) (modbusUnitId.toIntOrNull() ?: 1).coerceIn(0, 255) else 1,
                                        modbusConnectTimeoutMs = if (protocolType == ProtocolType.MODBUS_TCP) (modbusConnectTimeoutMs.toIntOrNull() ?: 5000) else 5000,
                                        modbusResponseTimeoutMs = if (protocolType == ProtocolType.MODBUS_TCP) (modbusResponseTimeoutMs.toIntOrNull() ?: 3000) else 3000,
                                        modbusRetries = if (protocolType == ProtocolType.MODBUS_TCP) (modbusRetries.toIntOrNull() ?: 1).coerceIn(0, 10) else 1,
                                        modbusPollIntervalMs = if (protocolType == ProtocolType.MODBUS_TCP) (modbusPollIntervalMs.toIntOrNull() ?: 1000).coerceAtLeast(200) else 1000,
                                        // VIRTUALBOX-VRDE FEATURE: only meaningful for a VRDE
                                        // profile — force back to defaults otherwise, same
                                        // reasoning as proxmox*/modbus* above so a stale value
                                        // never persists cross-protocol.
                                        vrdeAuthType = if (protocolType == ProtocolType.VIRTUALBOX_VRDE) vrdeAuthType else VrdeAuthType.NULL_AUTH.name,
                                        vrdeMultiConnectionAllowed = vrdeMultiConnectionAllowed && protocolType == ProtocolType.VIRTUALBOX_VRDE,
                                        // VMWARE-VSPHERE FEATURE: only meaningful for a vSphere
                                        // profile — same "force back to defaults otherwise"
                                        // reasoning as vrde* immediately above.
                                        vsphereApiMode = if (protocolType == ProtocolType.VMWARE_VSPHERE) vsphereApiMode else VSphereApiMode.REST.name,
                                        vsphereAcceptSelfSignedCertificate = if (protocolType == ProtocolType.VMWARE_VSPHERE) vsphereAcceptSelfSignedCertificate else true,
                                        vsphereDatacenter = if (protocolType == ProtocolType.VMWARE_VSPHERE) vsphereDatacenter.trim() else "",
                                        // SERIAL-CONSOLE FEATURE (Part 1/N): only meaningful for a
                                        // Serial Console profile — force back to defaults otherwise,
                                        // same reasoning as rloginRemoteUsername/rloginTerminalType
                                        // above so a stale value never persists cross-protocol.
                                        serialConsoleTransport = if (protocolType == ProtocolType.SERIAL_CONSOLE) serialConsoleTransport else com.systemsgo.hex.data.model.SerialRedirectMode.RFC_2217,
                                        serialConsoleBaudRate = if (protocolType == ProtocolType.SERIAL_CONSOLE) (serialConsoleBaudRate.toIntOrNull() ?: 9600) else 9600,
                                        serialConsoleDataBits = if (protocolType == ProtocolType.SERIAL_CONSOLE) serialConsoleDataBits else 8,
                                        serialConsoleParity = if (protocolType == ProtocolType.SERIAL_CONSOLE) serialConsoleParity else com.systemsgo.hex.data.model.SerialParity.NONE,
                                        serialConsoleStopBits = if (protocolType == ProtocolType.SERIAL_CONSOLE) serialConsoleStopBits else com.systemsgo.hex.data.model.SerialStopBits.ONE,
                                        serialConsoleDevicePath = if (protocolType == ProtocolType.SERIAL_CONSOLE) serialConsoleDevicePath.trim() else "",
                                        serialConsoleHardwareFlowControl = if (protocolType == ProtocolType.SERIAL_CONSOLE) serialConsoleHardwareFlowControl else false,
                                        // WEB-PORTAL FEATURE: only meaningful for a Web/HTTPS profile —
                                        // force back to defaults otherwise, same reasoning as
                                        // telnetUseTls above so a stale value never persists
                                        // cross-protocol.
                                        webUrl = if (protocolType == ProtocolType.WEB) webUrl.trim() else "",
                                        webTrustSelfSignedCertificate = webTrustSelfSignedCertificate && protocolType == ProtocolType.WEB,
                                        webAutoFillHttpAuth = if (protocolType == ProtocolType.WEB) webAutoFillHttpAuth else true,
                                        webAutoFillLoginForm = if (protocolType == ProtocolType.WEB) webAutoFillLoginForm else true,
                                        // NETCONF FEATURE: only meaningful for a NETCONF profile —
                                        // force back to defaults otherwise, same reasoning as
                                        // webUrl/webAutoFillLoginForm above so a stale value never
                                        // persists cross-protocol.
                                        netconfDefaultDatastore = if (protocolType == ProtocolType.NETCONF) netconfDefaultDatastore.trim().ifBlank { "running" } else "running",
                                        netconfExtraCapabilities = if (protocolType == ProtocolType.NETCONF) netconfExtraCapabilities else "",
                                        netconfKeepAliveMs = if (protocolType == ProtocolType.NETCONF) (netconfKeepAliveMs.toIntOrNull() ?: 15_000) else 15_000,
                                        netconfConnectTimeoutMs = if (protocolType == ProtocolType.NETCONF) (netconfConnectTimeoutMs.toIntOrNull() ?: 15_000) else 15_000,
                                        netconfCompressionEnabled = netconfCompressionEnabled && protocolType == ProtocolType.NETCONF,
                                        netconfOpenSshCertificate = if (protocolType == ProtocolType.NETCONF) netconfOpenSshCertificate else "",
                                        netconfAutoReconnect = if (protocolType == ProtocolType.NETCONF) netconfAutoReconnect else true,
                                        // CALL-HOME FEATURE: same stale-value protection as every
                                        // other netconf* field above.
                                        netconfCallHomeEnabled = netconfCallHomeEnabled && protocolType == ProtocolType.NETCONF,
                                        netconfCallHomeListenPort = if (protocolType == ProtocolType.NETCONF) (netconfCallHomeListenPort.toIntOrNull() ?: 4334) else 4334,
                                        netconfCallHomeAllowedSourceHost = if (protocolType == ProtocolType.NETCONF) netconfCallHomeAllowedSourceHost.trim() else "",
                                        // CALL-HOME-TLS FEATURE: same stale-value protection.
                                        netconfCallHomeTransport = if (protocolType == ProtocolType.NETCONF) netconfCallHomeTransport else "SSH",
                                        netconfCallHomeTlsClientCertificatePem = if (protocolType == ProtocolType.NETCONF) netconfCallHomeTlsClientCertificatePem else "",
                                        // GUACAMOLE-PROTOCOL FEATURE: only meaningful for a
                                        // Guacamole profile — force back to defaults otherwise,
                                        // same reasoning as netconf*/webUrl above so a stale
                                        // value never persists cross-protocol.
                                        guacServerUrl = if (protocolType == ProtocolType.GUACAMOLE) guacServerUrl.trim() else "",
                                        guacDataSource = if (protocolType == ProtocolType.GUACAMOLE) guacDataSource.trim() else "",
                                        guacConnectionIdentifier = if (protocolType == ProtocolType.GUACAMOLE) guacConnectionIdentifier.trim() else "",
                                        guacConnectionName = if (protocolType == ProtocolType.GUACAMOLE) guacConnectionName.trim() else "",
                                        guacConnectionProtocol = if (protocolType == ProtocolType.GUACAMOLE) guacConnectionProtocol.trim() else "",
                                        guacRememberSession = guacRememberSession && protocolType == ProtocolType.GUACAMOLE,
                                        // RTSP FEATURE: only meaningful for an RTSP profile —
                                        // same "force back to defaults otherwise" reasoning as
                                        // guac* above.
                                        rtspStreamPath = if (protocolType == ProtocolType.RTSP) rtspStreamPath.trim().removePrefix("/") else "",
                                        rtspTransportMode = if (protocolType == ProtocolType.RTSP) rtspTransportMode else com.systemsgo.hex.rtsp.protocol.RtspTransportMode.TCP_INTERLEAVED.name,
                                        rtspUseTls = protocolType == ProtocolType.RTSP && rtspUseTls,
                                        // FTP/FTPS-STANDALONE FEATURE: only meaningful for an
                                        // FTP/FTPS profile — force back to defaults otherwise,
                                        // same "force back to defaults otherwise" reasoning as
                                        // rtsp*/guac* above so a stale value never persists
                                        // cross-protocol.
                                        ftpSecurity = if (protocolType == ProtocolType.FTP || protocolType == ProtocolType.FTPS)
                                            com.systemsgo.hex.transfer.FtpSecurity.valueOf(ftpSecurity)
                                        else com.systemsgo.hex.transfer.FtpSecurity.PLAIN,
                                        ftpPassiveMode = if (protocolType == ProtocolType.FTP || protocolType == ProtocolType.FTPS) ftpPassiveMode else true,
                                        // SMB-STANDALONE FEATURE: only meaningful for an SMB
                                        // profile — same stale-value protection.
                                        smbShare = if (protocolType == ProtocolType.SMB) smbShare.trim() else "",
                                        smbDomain = if (protocolType == ProtocolType.SMB) smbDomain.trim() else "",
                                        // WEBDAV-STANDALONE FEATURE: only meaningful for a
                                        // WebDAV profile — same stale-value protection.
                                        webdavBaseUrl = if (protocolType == ProtocolType.WEBDAV) webdavBaseUrl.trim() else "",
                                        // NFS-STANDALONE FEATURE: only meaningful for an NFS
                                        // profile — same stale-value protection. uid/gid default
                                        // to 0 (root) and mountdPort to 0 (auto-detect via the
                                        // portmapper — see RdpProfile.nfsMountdPort's doc
                                        // comment) exactly like this dialog's own initial state
                                        // for a brand-new NFS profile.
                                        nfsExportPath = if (protocolType == ProtocolType.NFS) nfsExportPath.trim() else "",
                                        nfsUid = if (protocolType == ProtocolType.NFS) (nfsUid.toIntOrNull() ?: 0) else 0,
                                        nfsGid = if (protocolType == ProtocolType.NFS) (nfsGid.toIntOrNull() ?: 0) else 0,
                                        nfsMountdPort = if (protocolType == ProtocolType.NFS) (nfsMountdPort.toIntOrNull() ?: 0) else 0,
                                        // SSH Tunnel fields (applies to RDP and VNC only)
                                        sshTunnelEnabled  = sshTunnelEnabled && protocolType != ProtocolType.SSH,
                                        // SSH-PROXYJUMP-CHAIN FEATURE: this editor writes the
                                        // ordered chain only — the deprecated single-hop fields
                                        // (sshTunnelHost/Port/Username/AuthType/Password/
                                        // PrivateKey/PrivateKeyPassphrase) are left blank/default
                                        // per RdpProfile.sshTunnelHops's doc comment ("New
                                        // profiles created by the (future) multi-hop editor UI
                                        // leave these blank/false and populate sshTunnelHops
                                        // only"). Any legacy values already on `base` (an old
                                        // single-hop profile being edited here for the first
                                        // time) are harmless leftovers once cleared — every
                                        // reader goes through effectiveSshTunnelHops, which
                                        // always prefers a non-empty sshTunnelHops.
                                        sshTunnelHost     = "",
                                        sshTunnelPort     = 22,
                                        sshTunnelUsername = "",
                                        sshTunnelAuthType = SshAuthType.PASSWORD,
                                        sshTunnelPassword = "",
                                        sshTunnelPrivateKey = "",
                                        sshTunnelPrivateKeyPassphrase = "",
                                        sshTunnelHops = if (protocolType != ProtocolType.SSH)
                                            sshTunnelHops.map { it.copy(host = it.host.trim(), username = it.username.trim()) }
                                        else emptyList(),
                                        // SETTINGS-CONSOLIDATE FIX: colorDepth/width/height/
                                        // performanceFlags are no longer editable per-connection
                                        // (see Settings → Connection), so they're left untouched
                                        // here — every session reads the global values instead.
                                        enableSound      = if (protocolType == ProtocolType.RDP) enableSound else false,
                                        enableMicRedirect = if (protocolType == ProtocolType.RDP) enableMicRedirect else false,
                                        enablePrinterRedirect = if (protocolType == ProtocolType.RDP) enablePrinterRedirect else false,
                                        enableWebcamRedirect = if (protocolType == ProtocolType.RDP) enableWebcamRedirect else false,
                                        enableSmartcardRedirect = if (protocolType == ProtocolType.RDP) enableSmartcardRedirect else false,
                                        enableParallelRedirect = if (protocolType == ProtocolType.RDP) enableParallelRedirect else false,
                                        parallelPortPath = parallelPortPath.trim(),
                                        enableSerialRedirect = if (protocolType == ProtocolType.RDP) enableSerialRedirect else false,
                                        serialPortPath = serialPortPath.trim(),
                                        serialRedirectMode = serialRedirectMode,
                                        serialNetworkHost = serialNetworkHost.trim(),
                                        serialNetworkPort = serialNetworkPort.toIntOrNull() ?: 2217,
                                        // Wake-on-LAN fields
                                        wolEnabled          = wolEnabled,
                                        wolMacAddress       = wolMacAddress.trim(),
                                        wolBroadcastAddress = wolBroadcastAddress.trim().ifBlank { "255.255.255.255" },
                                        wolPort             = wolPort.toIntOrNull()
                                            ?: com.systemsgo.hex.util.WakeOnLanManager.DEFAULT_WOL_PORT,
                                        wolConnectTimeoutSeconds = wolConnectTimeoutSeconds.toIntOrNull() ?: 60,
                                        wolRetryIntervalSeconds  = wolRetryIntervalSeconds.toIntOrNull() ?: 3,
                                        wolMaxRetries            = wolMaxRetries.toIntOrNull() ?: 20,
                                        // Folders & Tags
                                        folderId = folderId,
                                        tags = tagsInput.split(",")
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                            .distinct(),
                                    )
                                    closeThen { onSave(saved) }
                                },
                                enabled  = canSave,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ProtocolSelector(selected = protocolType, onSelect = ::selectProtocol, isEditing = profile != null)

                FormGroupHeader(
                    icon     = Icons.Outlined.RocketLaunch,
                    title    = stringResource(R.string.quick_connect),
                    subtitle = stringResource(R.string.quick_connect_desc)
                )
                // PERF-FIX (recomposition scope): Quick Connect fields and the
                // protocol-specific options block used to be inlined directly in
                // this Column, which meant every keystroke in *any* one of them
                // (or in gatewayHost/wolMac/etc. further down) forced this whole
                // ~300-line lambda to be re-walked, since the plain field reads
                // sat directly in its body. Pulling each group into its own
                // @Composable call means a change to, say, `gatewayHost` no
                // longer needs this scope to evaluate the Quick Connect / SSH
                // auth statements at all — Compose skips those calls outright
                // since their own arguments didn't change.
                QuickConnectSection(
                    name = name, onNameChange = { name = it },
                    host = host,
                    onHostChange = { text ->
                        // If the user pastes a full "host:port" string into
                        // this field (a common paste scenario), split it
                        // automatically instead of leaving ":port" stuck
                        // inside the host text. Plain typing never contains
                        // ':' so this never interferes with normal editing.
                        // I18N-FIX: normalize Arabic-Indic/Extended Arabic-Indic
                        // digits first so a pasted "192.168.1.1:٣٣٨٩" (or a port
                        // typed via an Arabic-locale IME) is recognized the same
                        // as its ASCII equivalent.
                        val normalized = text.normalizeDigits()
                        val idx = normalized.lastIndexOf(':')
                        if (idx >= 0) {
                            host = normalized.substring(0, idx)
                            val portPart = normalized.substring(idx + 1)
                            if (portPart.isNotEmpty() && portPart.length <= 5 && portPart.all(Char::isDigit)) {
                                port = portPart
                                portTouchedByUser = true
                            }
                        } else {
                            host = normalized
                        }
                    },
                    port = port,
                    onPortChange = { newPort ->
                        val normalized = newPort.normalizeDigits()
                        if (normalized.length <= 5 && normalized.all(Char::isDigit)) {
                            port = normalized
                            portTouchedByUser = true
                        }
                    },
                    // ARD FIX: VNC profiles now show the username field too —
                    // classic VNC auth ignores it (harmless if left blank),
                    // but Apple Screen Sharing/ARD's RA2 security type (see
                    // RfbConnectable.negotiateAppleRA2) requires a username
                    // alongside the password, and there was previously no
                    // way to enter one for a VNC profile at all.
                    showUsername = true,
                    username = username, onUsernameChange = { username = it },
                    usernameLabel = if (protocolType == ProtocolType.VNC)
                        stringResource(R.string.vnc_username) else stringResource(R.string.username),
                    passwordLabel = if (protocolType == ProtocolType.VNC)
                        stringResource(R.string.vnc_password) else stringResource(R.string.password),
                    password = password, onPasswordChange = { password = it },
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible }
                )

                // NOTE: the folder/tags "Organize" picker used to live here as its
                // own FormGroupHeader + OrganizeSection block. Removed from the
                // connection form per UI cleanup request — folder and tags aren't
                // needed at connection-creation time. `folderId`/`tagsInput` state
                // above is left untouched (still saved on the profile as-is) so
                // nothing downstream (folder filtering, existing tagged profiles)
                // breaks; there's just no picker UI for them in this dialog anymore.
                ProtocolOptionsSection(
                    protocolType = protocolType,
                    useNla = useNla, onUseNlaChange = { useNla = it },
                    enableSound = enableSound, onEnableSoundChange = { enableSound = it },
                    // AUDIO-BACKEND FIX: see the doc comment on
                    // AFreeRdpBridge.isAudioBackendAvailable — currently
                    // always false, since the FreeRDP prebuilt this app links
                    // against is built without an Android audio backend
                    // (no WITH_OPENSLES in .github/workflows/main.yml).
                    audioBackendAvailable = com.systemsgo.hex.rdp.native.AFreeRdpBridge.isAudioBackendAvailable,
                    enableMicRedirect = enableMicRedirect, onEnableMicRedirectChange = { enableMicRedirect = it },
                    enablePrinterRedirect = enablePrinterRedirect,
                    onEnablePrinterRedirectChange = { enablePrinterRedirect = it },
                    // PRINTER-REDIRECT FEATURE: see the doc comment on
                    // AFreeRdpBridge.isPrinterBackendAvailable — currently
                    // always false, since this build's FreeRDP prebuilt has
                    // WITH_CUPS=OFF (no printer backend compiled in).
                    printerBackendAvailable = com.systemsgo.hex.rdp.native.AFreeRdpBridge.isPrinterBackendAvailable,
                    enableWebcamRedirect = enableWebcamRedirect,
                    onEnableWebcamRedirectChange = { enableWebcamRedirect = it },
                    // WEBCAM-REDIRECT FEATURE: see the doc comment on
                    // AFreeRdpBridge.isWebcamBackendAvailable — defaults
                    // true now that this build's FreeRDP prebuilt is pinned
                    // to 3.27.1 with -DCHANNEL_RDPECAM_CLIENT=ON.
                    webcamBackendAvailable = com.systemsgo.hex.rdp.native.AFreeRdpBridge.isWebcamBackendAvailable,
                    enableSmartcardRedirect = enableSmartcardRedirect,
                    onEnableSmartcardRedirectChange = { enableSmartcardRedirect = it },
                    // SMARTCARD-REDIRECT FEATURE: see the doc comment on
                    // AFreeRdpBridge.isSmartcardBackendAvailable — reflects
                    // whether the rdpdr smartcard *channel* compiled in
                    // (WITH_PCSC=ON), not whether a physical card is
                    // actually readable yet (no on-device PC/SC resource
                    // manager bridge exists — see that property's doc).
                    smartcardBackendAvailable = com.systemsgo.hex.rdp.native.AFreeRdpBridge.isSmartcardBackendAvailable,
                    enableParallelRedirect = enableParallelRedirect,
                    onEnableParallelRedirectChange = { enableParallelRedirect = it },
                    parallelPortPath = parallelPortPath,
                    onParallelPortPathChange = { parallelPortPath = it },
                    enableSerialRedirect = enableSerialRedirect,
                    onEnableSerialRedirectChange = { enableSerialRedirect = it },
                    serialPortPath = serialPortPath,
                    onSerialPortPathChange = { serialPortPath = it },
                    serialRedirectMode = serialRedirectMode,
                    onSerialRedirectModeChange = { serialRedirectMode = it },
                    serialNetworkHost = serialNetworkHost,
                    onSerialNetworkHostChange = { serialNetworkHost = it },
                    serialNetworkPort = serialNetworkPort,
                    onSerialNetworkPortChange = { serialNetworkPort = it },
                    vncViewOnly = vncViewOnly, onVncViewOnlyChange = { vncViewOnly = it },
                    // LISTEN-MODE FEATURE: mutually exclusive with the repeater
                    // toggle — an accepted incoming connection can't also be
                    // routed through a repeater this app dials out through.
                    vncRepeaterEnabled = vncRepeaterEnabled,
                    onVncRepeaterEnabledChange = { vncRepeaterEnabled = it; if (it) vncListenModeEnabled = false },
                    vncRepeaterMode = vncRepeaterMode, onVncRepeaterModeChange = { vncRepeaterMode = it },
                    vncRepeaterId = vncRepeaterId, onVncRepeaterIdChange = { vncRepeaterId = it },
                    isVncRepeaterIdError = vncRepeaterEnabled && vncRepeaterMode == VncRepeaterMode.MODE_II && vncRepeaterId.isBlank(),
                    vncListenModeEnabled = vncListenModeEnabled,
                    onVncListenModeEnabledChange = { vncListenModeEnabled = it; if (it) vncRepeaterEnabled = false },
                    vncListenPort = vncListenPort, onVncListenPortChange = { vncListenPort = it },
                    isVncListenPortError = vncListenModeEnabled && !isPortInRange(vncListenPort),
                    sshAuthType = sshAuthType, onSshAuthTypeChange = { sshAuthType = it },
                    sshPrivateKey = sshPrivateKey, onSshPrivateKeyChange = { sshPrivateKey = it },
                    sshKeyPassphrase = sshKeyPassphrase, onSshKeyPassphraseChange = { sshKeyPassphrase = it },
                    sshAgentForwardingEnabled = sshAgentForwardingEnabled,
                    onSshAgentForwardingChange = { sshAgentForwardingEnabled = it },
                    socksProxyEnabled = socksProxyEnabled,
                    onSocksProxyEnabledChange = { socksProxyEnabled = it },
                    socksProxyPort = socksProxyPort,
                    onSocksProxyPortChange = { socksProxyPort = it },
                    isSocksProxyPortError = socksProxyEnabled && socksProxyPort.isNotBlank() && !isPortInRange(socksProxyPort),
                    x11ForwardingEnabled = x11ForwardingEnabled,
                    onX11ForwardingEnabledChange = { x11ForwardingEnabled = it },
                    x11DisplayHost = x11DisplayHost, onX11DisplayHostChange = { x11DisplayHost = it },
                    x11DisplayNumber = x11DisplayNumber, onX11DisplayNumberChange = { x11DisplayNumber = it },
                    x11AuthCookie = x11AuthCookie, onX11AuthCookieChange = { x11AuthCookie = it },
                    isX11DisplayNumberError = x11ForwardingEnabled &&
                        (x11DisplayNumber.toIntOrNull()?.let { it !in 0..99 } ?: true),
                    isX11AuthCookieError = x11ForwardingEnabled && x11AuthCookie.isNotBlank() &&
                        X11AuthCookie.validate(x11AuthCookie) == null,
                    sshPortForwards = sshPortForwards,
                    onSshPortForwardsChange = { sshPortForwards = it },
                    telnetUseTls = telnetUseTls, onTelnetUseTlsChange = { telnetUseTls = it },
                    rloginRemoteUsername = rloginRemoteUsername, onRloginRemoteUsernameChange = { rloginRemoteUsername = it },
                    rloginTerminalType = rloginTerminalType, onRloginTerminalTypeChange = { rloginTerminalType = it },
                    moshRemoteServerCommand = moshRemoteServerCommand, onMoshRemoteServerCommandChange = { moshRemoteServerCommand = it },
                    moshUdpPortRange = moshUdpPortRange, onMoshUdpPortRangeChange = { moshUdpPortRange = it },
                    moshRemoteLocale = moshRemoteLocale, onMoshRemoteLocaleChange = { moshRemoteLocale = it },
                    moshColorMode = moshColorMode, onMoshColorModeChange = { moshColorMode = it },
                    moshPredictionMode = moshPredictionMode, onMoshPredictionModeChange = { moshPredictionMode = it },
                    serialConsoleTransport = serialConsoleTransport, onSerialConsoleTransportChange = { serialConsoleTransport = it },
                    serialConsoleBaudRate = serialConsoleBaudRate, onSerialConsoleBaudRateChange = { serialConsoleBaudRate = it },
                    serialConsoleDataBits = serialConsoleDataBits, onSerialConsoleDataBitsChange = { serialConsoleDataBits = it },
                    serialConsoleParity = serialConsoleParity, onSerialConsoleParityChange = { serialConsoleParity = it },
                    serialConsoleStopBits = serialConsoleStopBits, onSerialConsoleStopBitsChange = { serialConsoleStopBits = it },
                    serialConsoleDevicePath = serialConsoleDevicePath, onSerialConsoleDevicePathChange = { serialConsoleDevicePath = it },
                    serialConsoleHardwareFlowControl = serialConsoleHardwareFlowControl, onSerialConsoleHardwareFlowControlChange = { serialConsoleHardwareFlowControl = it },
                    webUrl = webUrl, onWebUrlChange = { webUrl = it },
                    webTrustSelfSignedCertificate = webTrustSelfSignedCertificate,
                    onWebTrustSelfSignedCertificateChange = { webTrustSelfSignedCertificate = it },
                    webAutoFillHttpAuth = webAutoFillHttpAuth,
                    onWebAutoFillHttpAuthChange = { webAutoFillHttpAuth = it },
                    webAutoFillLoginForm = webAutoFillLoginForm,
                    onWebAutoFillLoginFormChange = { webAutoFillLoginForm = it },
                    snmpVersion = snmpVersion, onSnmpVersionChange = { snmpVersion = it },
                    snmpCommunity = snmpCommunity, onSnmpCommunityChange = { snmpCommunity = it },
                    snmpPort = snmpPort, onSnmpPortChange = { snmpPort = it },
                    snmpV3Username = snmpV3Username, onSnmpV3UsernameChange = { snmpV3Username = it },
                    snmpV3SecurityLevel = snmpV3SecurityLevel, onSnmpV3SecurityLevelChange = { snmpV3SecurityLevel = it },
                    snmpV3AuthProtocol = snmpV3AuthProtocol, onSnmpV3AuthProtocolChange = { snmpV3AuthProtocol = it },
                    snmpV3AuthPassphrase = snmpV3AuthPassphrase, onSnmpV3AuthPassphraseChange = { snmpV3AuthPassphrase = it },
                    snmpV3PrivProtocol = snmpV3PrivProtocol, onSnmpV3PrivProtocolChange = { snmpV3PrivProtocol = it },
                    snmpV3PrivPassphrase = snmpV3PrivPassphrase, onSnmpV3PrivPassphraseChange = { snmpV3PrivPassphrase = it },
                    snmpV3ContextName = snmpV3ContextName, onSnmpV3ContextNameChange = { snmpV3ContextName = it },
                    netconfDefaultDatastore = netconfDefaultDatastore, onNetconfDefaultDatastoreChange = { netconfDefaultDatastore = it },
                    netconfExtraCapabilities = netconfExtraCapabilities, onNetconfExtraCapabilitiesChange = { netconfExtraCapabilities = it },
                    netconfKeepAliveMs = netconfKeepAliveMs, onNetconfKeepAliveMsChange = { netconfKeepAliveMs = it },
                    netconfConnectTimeoutMs = netconfConnectTimeoutMs, onNetconfConnectTimeoutMsChange = { netconfConnectTimeoutMs = it },
                    netconfCompressionEnabled = netconfCompressionEnabled, onNetconfCompressionEnabledChange = { netconfCompressionEnabled = it },
                    netconfOpenSshCertificate = netconfOpenSshCertificate, onNetconfOpenSshCertificateChange = { netconfOpenSshCertificate = it },
                    netconfAutoReconnect = netconfAutoReconnect, onNetconfAutoReconnectChange = { netconfAutoReconnect = it },
                    netconfCallHomeEnabled = netconfCallHomeEnabled, onNetconfCallHomeEnabledChange = { netconfCallHomeEnabled = it },
                    netconfCallHomeListenPort = netconfCallHomeListenPort, onNetconfCallHomeListenPortChange = { netconfCallHomeListenPort = it },
                    netconfCallHomeAllowedSourceHost = netconfCallHomeAllowedSourceHost, onNetconfCallHomeAllowedSourceHostChange = { netconfCallHomeAllowedSourceHost = it },
                    netconfCallHomeTransport = netconfCallHomeTransport,
                    onNetconfCallHomeTransportChange = { newTransport ->
                        // CALL-HOME-TLS FEATURE: swap the listen port to the
                        // new transport's IANA default only if the field
                        // still holds the *old* transport's default — a
                        // user-customized port (their device dials a
                        // non-default port) is left untouched.
                        val oldDefault = if (netconfCallHomeTransport == "TLS") "4335" else "4334"
                        val newDefault = if (newTransport == "TLS") "4335" else "4334"
                        if (netconfCallHomeListenPort == oldDefault) netconfCallHomeListenPort = newDefault
                        netconfCallHomeTransport = newTransport
                    },
                    netconfCallHomeTlsClientCertificatePem = netconfCallHomeTlsClientCertificatePem,
                    onNetconfCallHomeTlsClientCertificatePemChange = { netconfCallHomeTlsClientCertificatePem = it },
                    guacServerUrl = guacServerUrl, onGuacServerUrlChange = { guacServerUrl = it },
                    guacDataSource = guacDataSource, onGuacDataSourceChange = { guacDataSource = it },
                    guacConnectionIdentifier = guacConnectionIdentifier,
                    onGuacConnectionIdentifierChange = { guacConnectionIdentifier = it },
                    guacConnectionName = guacConnectionName, onGuacConnectionNameChange = { guacConnectionName = it },
                    onBrowseConnectionsClick = { showGuacamolePicker = true },
                    guacRememberSession = guacRememberSession,
                    onGuacRememberSessionChange = { guacRememberSession = it },
                    // RTSP FEATURE: see RdpProfile.rtspStreamPath/rtspTransportMode/rtspUseTls's doc comments.
                    rtspStreamPath = rtspStreamPath, onRtspStreamPathChange = { rtspStreamPath = it },
                    rtspTransportMode = rtspTransportMode, onRtspTransportModeChange = { rtspTransportMode = it },
                    rtspUseTls = rtspUseTls, onRtspUseTlsChange = { rtspUseTls = it },
                    modbusUnitId = modbusUnitId, onModbusUnitIdChange = { modbusUnitId = it },
                    modbusConnectTimeoutMs = modbusConnectTimeoutMs, onModbusConnectTimeoutMsChange = { modbusConnectTimeoutMs = it },
                    modbusResponseTimeoutMs = modbusResponseTimeoutMs, onModbusResponseTimeoutMsChange = { modbusResponseTimeoutMs = it },
                    modbusRetries = modbusRetries, onModbusRetriesChange = { modbusRetries = it },
                    modbusPollIntervalMs = modbusPollIntervalMs, onModbusPollIntervalMsChange = { modbusPollIntervalMs = it },
                    // COMPILE-BREAK FIX: wiring the four params ProtocolOptionsSection
                    // was missing (see that function's doc comment on them) to this
                    // dialog's own existing state.
                    acceptSelfSignedCertificate = acceptSelfSignedCertificate,
                    onAcceptSelfSignedCertificateChange = { acceptSelfSignedCertificate = it },
                    ipmiPrivilegeLevel = ipmiPrivilegeLevel, onIpmiPrivilegeLevelChange = { ipmiPrivilegeLevel = it },
                    ipmiKgKey = ipmiKgKey, onIpmiKgKeyChange = { ipmiKgKey = it },
                    amtUseTls = amtUseTls, onAmtUseTlsChange = { amtUseTls = it },
                    ciraEnabled = ciraEnabled, onCiraEnabledChange = { ciraEnabled = it },
                    ciraRelayHost = ciraRelayHost, onCiraRelayHostChange = { ciraRelayHost = it },
                    ciraRelayPort = ciraRelayPort, onCiraRelayPortChange = { ciraRelayPort = it },
                    ciraRelayUsername = ciraRelayUsername, onCiraRelayUsernameChange = { ciraRelayUsername = it },
                    ciraRelayPassword = ciraRelayPassword, onCiraRelayPasswordChange = { ciraRelayPassword = it },
                    ciraDeviceId = ciraDeviceId, onCiraDeviceIdChange = { ciraDeviceId = it },
                    ciraRelayUseTls = ciraRelayUseTls, onCiraRelayUseTlsChange = { ciraRelayUseTls = it },
                    // VIRTUALBOX-VRDE / VMWARE-VSPHERE FEATURES (Part 2/N)
                    vrdeAuthType = vrdeAuthType, onVrdeAuthTypeChange = { vrdeAuthType = it },
                    vrdeMultiConnectionAllowed = vrdeMultiConnectionAllowed,
                    onVrdeMultiConnectionAllowedChange = { vrdeMultiConnectionAllowed = it },
                    vsphereApiMode = vsphereApiMode, onVsphereApiModeChange = { vsphereApiMode = it },
                    vsphereAcceptSelfSignedCertificate = vsphereAcceptSelfSignedCertificate,
                    onVsphereAcceptSelfSignedCertificateChange = { vsphereAcceptSelfSignedCertificate = it },
                    vsphereDatacenter = vsphereDatacenter, onVsphereDatacenterChange = { vsphereDatacenter = it },
                    // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE
                    ftpSecurity = ftpSecurity, onFtpSecurityChange = { ftpSecurity = it },
                    ftpPassiveMode = ftpPassiveMode, onFtpPassiveModeChange = { ftpPassiveMode = it },
                    smbShare = smbShare, onSmbShareChange = { smbShare = it },
                    smbDomain = smbDomain, onSmbDomainChange = { smbDomain = it },
                    webdavBaseUrl = webdavBaseUrl, onWebdavBaseUrlChange = { webdavBaseUrl = it },
                    nfsExportPath = nfsExportPath, onNfsExportPathChange = { nfsExportPath = it },
                    nfsUid = nfsUid, onNfsUidChange = { nfsUid = it },
                    nfsGid = nfsGid, onNfsGidChange = { nfsGid = it },
                    nfsMountdPort = nfsMountdPort, onNfsMountdPortChange = { nfsMountdPort = it },
                )
                if (showGuacamolePicker) {
                    GuacamoleConnectionPickerDialog(
                        serverUrl = guacServerUrl,
                        username = username,
                        password = password,
                        dataSourceHint = guacDataSource,
                        acceptSelfSignedCertificate = acceptSelfSignedCertificate,
                        onDismiss = { showGuacamolePicker = false },
                        onConnectionPicked = { connection, dataSourceUsed ->
                            guacConnectionIdentifier = connection.identifier
                            guacConnectionName = connection.name
                            guacConnectionProtocol = connection.protocol ?: ""
                            guacDataSource = dataSourceUsed
                            showGuacamolePicker = false
                        },
                    )
                }
                // Everything situational lives here as its own self-contained card:
                // icon + name + one-line description + live status, collapsed by
                // default. Nothing is hidden — every capability still has a visible
                // entry point — but nothing demands attention unless the person
                // opens it or switches it on. This replaces the previous mix of
                // bare switches, inline field stacks and section dividers with one
                // predictable interaction pattern across the whole form.
                FormGroupHeader(
                    icon             = Icons.Outlined.Tune,
                    title            = stringResource(R.string.advanced_optional),
                    subtitle         = stringResource(R.string.advanced_optional_desc),
                    collapsible      = true,
                    expanded         = showOptionalSettings,
                    onExpandedChange = { showOptionalSettings = it }
                )

                AnimatedVisibility(
                    visible = showOptionalSettings,
                    enter   = expandVertically() + fadeIn(),
                    exit    = shrinkVertically() + fadeOut()
                ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                if (protocolType == ProtocolType.RDP) {
                    SettingsCard(
                        icon         = Icons.Outlined.Security,
                        accentColor  = QuantumBlue,
                        title        = stringResource(R.string.section_security_auth),
                        subtitle     = stringResource(R.string.section_security_auth_desc),
                        expanded     = expandSecurity,
                        onExpandedChange = { expandSecurity = it }
                    ) {
                        SecuritySection(
                            domain = domain, onDomainChange = { domain = it },
                        )
                    }

                    SettingsCard(
                        icon         = Icons.Outlined.Hub,
                        accentColor  = PulsarCyan,
                        title        = stringResource(R.string.rd_gateway),
                        subtitle     = stringResource(R.string.card_gateway_desc) + " · " +
                            stringResource(if (gatewayEnabled) R.string.status_on else R.string.status_off),
                        hasToggle    = true,
                        toggleChecked = gatewayEnabled,
                        onToggleChange = { gatewayEnabled = it },
                        expanded     = expandGateway,
                        onExpandedChange = { expandGateway = it }
                    ) {
                        GatewaySection(
                            gatewayHost = gatewayHost, onGatewayHostChange = { gatewayHost = it.normalizeDigits() },
                            gatewayPort = gatewayPort, onGatewayPortChange = { gatewayPort = it.normalizeDigits().filter(Char::isDigit) },
                            gatewayUsername = gatewayUsername, onGatewayUsernameChange = { gatewayUsername = it },
                            gatewayPassword = gatewayPassword, onGatewayPasswordChange = { gatewayPassword = it },
                            gatewayPasswordVisible = gatewayPasswordVisible,
                            onToggleGatewayPasswordVisible = { gatewayPasswordVisible = !gatewayPasswordVisible },
                            gatewayDomain = gatewayDomain, onGatewayDomainChange = { gatewayDomain = it },
                            // ENTRA-ID-AUTH FEATURE
                            gatewayAuthMode = gatewayAuthMode,
                            onGatewayAuthModeChange = { gatewayAuthMode = it },
                            entraLinkedUpn = entraLinkedUpn,
                            gatewayScopeUri = gatewayScopeUri, onGatewayScopeUriChange = { gatewayScopeUri = it },
                            onSignInWithMicrosoft = onSignInWithMicrosoft,
                            onSignOutMicrosoft = onSignOutMicrosoft,
                            entraSignInPending = entraSignInPending,
                        )
                    }

                    SettingsCard(
                        icon         = Icons.Outlined.SettingsEthernet,
                        accentColor  = PulsarCyan,
                        title        = stringResource(R.string.outbound_proxy),
                        subtitle     = stringResource(R.string.card_proxy_desc) + " · " +
                            stringResource(if (proxyEnabled) R.string.status_on else R.string.status_off),
                        hasToggle    = true,
                        toggleChecked = proxyEnabled,
                        onToggleChange = { proxyEnabled = it },
                        expanded     = expandProxy,
                        onExpandedChange = { expandProxy = it }
                    ) {
                        ProxySection(
                            proxyType = proxyType, onProxyTypeChange = { proxyType = it },
                            proxyHost = proxyHost, onProxyHostChange = { proxyHost = it.normalizeDigits() },
                            proxyPort = proxyPort, onProxyPortChange = { proxyPort = it.normalizeDigits().filter(Char::isDigit) },
                            proxyUsername = proxyUsername, onProxyUsernameChange = { proxyUsername = it },
                            proxyPassword = proxyPassword, onProxyPasswordChange = { proxyPassword = it },
                            proxyPasswordVisible = proxyPasswordVisible,
                            onToggleProxyPasswordVisible = { proxyPasswordVisible = !proxyPasswordVisible },
                            // PAC-SUPPORT FEATURE (Part 3/n — UI)
                            pacUrl = pacUrl, onPacUrlChange = { pacUrl = it },
                            targetHost = host, targetPort = port,
                        )
                    }

                    // RDP-OVER-WEBSOCKET FEATURE: same collapsible-card shape as
                    // Outbound Proxy just above, but toggled by transportMode
                    // rather than a plain Boolean — the card's own "on" state
                    // (for the subtitle/expand-by-default heuristic) is simply
                    // "not TCP". Title/subtitle now come from strings.xml
                    // (en + ar), matching every other SettingsCard here.
                    SettingsCard(
                        icon         = Icons.Outlined.SettingsEthernet,
                        accentColor  = PulsarCyan,
                        title        = stringResource(R.string.ws_transport_title),
                        subtitle     = stringResource(R.string.ws_transport_subtitle) + " · " +
                            stringResource(if (transportMode.isWebSocket) R.string.status_on else R.string.status_off),
                        hasToggle    = true,
                        toggleChecked = transportMode.isWebSocket,
                        onToggleChange = { checked ->
                            transportMode = if (checked) {
                                RdpTransportMode.autoDetect(webSocketConfig.url.ifBlank { webSocketConfig.host })
                            } else {
                                RdpTransportMode.TCP
                            }
                        },
                        expanded     = expandTransport,
                        onExpandedChange = { expandTransport = it }
                    ) {
                        WebSocketTransportSettings(
                            mode = transportMode,
                            config = webSocketConfig,
                            onModeChange = { transportMode = it },
                            onConfigChange = { webSocketConfig = it },
                        )
                    }

                    SettingsCard(
                        icon         = Icons.Outlined.OpenInNew,
                        accentColor  = NovaPink,
                        title        = stringResource(R.string.remote_app),
                        subtitle     = stringResource(R.string.card_remote_app_desc) + " · " +
                            stringResource(if (remoteAppEnabled) R.string.status_on else R.string.status_off),
                        hasToggle    = true,
                        toggleChecked = remoteAppEnabled,
                        onToggleChange = { remoteAppEnabled = it },
                        expanded     = expandRemoteApp,
                        onExpandedChange = { expandRemoteApp = it }
                    ) {
                        RemoteAppSection(
                            remoteAppProgram = remoteAppProgram, onRemoteAppProgramChange = { remoteAppProgram = it },
                            remoteAppWorkingDir = remoteAppWorkingDir, onRemoteAppWorkingDirChange = { remoteAppWorkingDir = it },
                            remoteAppCmdLine = remoteAppCmdLine, onRemoteAppCmdLineChange = { remoteAppCmdLine = it },
                            remoteAppDisplayMode = remoteAppDisplayMode, onRemoteAppDisplayModeChange = { remoteAppDisplayMode = it },
                        )
                    }

                    // CODEC-NEGOTIATION FEATURE: its own collapsed-by-default
                    // card, same shape as Security/RD Gateway/RemoteApp above.
                    // No `hasToggle` — unlike RD Gateway/RemoteApp (which are
                    // wholesale on/off features), codec preference is always
                    // "on" in the sense that some choice always applies
                    // (AUTO by default); there's nothing to toggle, only a
                    // choice to make, so the subtitle shows the current
                    // choice's label instead of an On/Off state.
                    SettingsCard(
                        icon         = Icons.Outlined.HighQuality,
                        accentColor  = VoidPurple,
                        title        = stringResource(R.string.advanced_settings),
                        subtitle     = stringResource(R.string.advanced_settings_desc) + " · " +
                            stringResource(codecPreference.labelRes()),
                        expanded     = expandCodec,
                        onExpandedChange = { expandCodec = it }
                    ) {
                        CodecPreferenceSection(
                            selected = codecPreference,
                            onSelect = { codecPreference = it },
                            // CODEC-NEGOTIATION FEATURE: see AFreeRdpBridge.isH264BackendAvailable/
                            // isAv1BackendAvailable's doc comments — both currently false in this
                            // project's default FreeRDP prebuilt (WITH_OPENH264/WITH_FFMPEG/
                            // WITH_AV1 all off, see SETUP.md), which is why "Prefer AV1"/
                            // "Prefer H.264" show as unavailable-in-this-build out of the box.
                            h264BackendAvailable = com.systemsgo.hex.rdp.native.AFreeRdpBridge.isH264BackendAvailable,
                            av1BackendAvailable = com.systemsgo.hex.rdp.native.AFreeRdpBridge.isAv1BackendAvailable,
                            // CODEC-NEGOTIATION FEATURE (hardware-decode signal): purely
                            // informational — see HardwareDecoderCapabilities' class doc
                            // for why this never disables an option, only labels it.
                            h264HardwareDecoder = com.systemsgo.hex.rdp.codec.HardwareDecoderCapabilities.isH264HardwareDecoderAvailable,
                            av1HardwareDecoder = com.systemsgo.hex.rdp.codec.HardwareDecoderCapabilities.isAv1HardwareDecoderAvailable,
                        )
                    }
                }

                // SNMP FEATURE: lets any non-SNMP profile (RDP/SSH/...) also
                // carry SNMP credentials for the same device, so its session
                // screen can offer an "Open SNMP dashboard" shortcut without
                // needing a second, separate SNMP-only profile. Irrelevant for
                // a profile that's already protocolType == SNMP (that profile
                // is unconditionally SNMP already — see snmpMonitoringEnabled's
                // save-time `&& protocolType != ProtocolType.SNMP` guard).
                if (protocolType != ProtocolType.SNMP) {
                    SettingsCard(
                        icon         = Icons.Outlined.NetworkCheck,
                        accentColor  = SolarFlare,
                        title        = stringResource(R.string.snmp_monitoring_title),
                        subtitle     = stringResource(
                            R.string.snmp_monitoring_subtitle,
                            if (snmpMonitoringEnabled) stringResource(R.string.status_on) else stringResource(R.string.status_off)
                        ),
                        hasToggle    = true,
                        toggleChecked = snmpMonitoringEnabled,
                        onToggleChange = { snmpMonitoringEnabled = it },
                        expanded     = expandSnmpMonitoring,
                        onExpandedChange = { expandSnmpMonitoring = it },
                    ) {
                        SnmpCredentialFields(
                            snmpVersion = snmpVersion, onSnmpVersionChange = { snmpVersion = it },
                            snmpPort = snmpPort, onSnmpPortChange = { snmpPort = it },
                            snmpCommunity = snmpCommunity, onSnmpCommunityChange = { snmpCommunity = it },
                            snmpV3Username = snmpV3Username, onSnmpV3UsernameChange = { snmpV3Username = it },
                            snmpV3SecurityLevel = snmpV3SecurityLevel, onSnmpV3SecurityLevelChange = { snmpV3SecurityLevel = it },
                            snmpV3AuthProtocol = snmpV3AuthProtocol, onSnmpV3AuthProtocolChange = { snmpV3AuthProtocol = it },
                            snmpV3AuthPassphrase = snmpV3AuthPassphrase, onSnmpV3AuthPassphraseChange = { snmpV3AuthPassphrase = it },
                            snmpV3PrivProtocol = snmpV3PrivProtocol, onSnmpV3PrivProtocolChange = { snmpV3PrivProtocol = it },
                            snmpV3PrivPassphrase = snmpV3PrivPassphrase, onSnmpV3PrivPassphraseChange = { snmpV3PrivPassphrase = it },
                            snmpV3ContextName = snmpV3ContextName, onSnmpV3ContextNameChange = { snmpV3ContextName = it },
                        )
                    }
                }

                if (protocolType != ProtocolType.SSH) {
                    SettingsCard(
                        icon         = Icons.Outlined.Terminal,
                        accentColor  = PlasmaGreen,
                        title        = stringResource(R.string.ssh_tunnel),
                        subtitle     = stringResource(R.string.card_tunnel_desc) + " · " +
                            stringResource(if (sshTunnelEnabled) R.string.status_on else R.string.status_off),
                        hasToggle    = true,
                        toggleChecked = sshTunnelEnabled,
                        onToggleChange = { sshTunnelEnabled = it },
                        expanded     = expandTunnel,
                        onExpandedChange = { expandTunnel = it }
                    ) {
                        SshTunnelHopChainEditor(
                            hops         = sshTunnelHops,
                            onHopsChange = { sshTunnelHops = it },
                        )
                    }
                }

                SettingsCard(
                    icon         = Icons.Outlined.Wifi,
                    accentColor  = SolarFlare,
                    title        = stringResource(R.string.wol_enable),
                    subtitle     = stringResource(R.string.card_wol_desc) + " · " +
                        stringResource(if (wolEnabled) R.string.status_on else R.string.status_off),
                    // WAKE-ON-LAN-STANDALONE FEATURE: for a WAKE_ON_LAN profile,
                    // Wake-on-LAN isn't an optional add-on the user can turn off —
                    // it *is* the protocol — so the toggle is hidden (hasToggle =
                    // false) and the card stays permanently expanded, instead of
                    // showing a switch that would be confusing to turn off (see
                    // wolValid's `|| isWol` — turning it off wouldn't even be
                    // allowed to Save anyway).
                    hasToggle    = protocolType != ProtocolType.WAKE_ON_LAN,
                    toggleChecked = wolEnabled,
                    onToggleChange = { wolEnabled = it },
                    expanded     = expandWol || protocolType == ProtocolType.WAKE_ON_LAN,
                    onExpandedChange = { expandWol = it }
                ) {
                    WolSection(
                        wolMacAddress = wolMacAddress, onWolMacChange = { wolMacAddress = it.normalizeDigits() },
                        wolBroadcastAddress = wolBroadcastAddress,
                        onWolBroadcastChange = { wolBroadcastAddress = it.normalizeDigits() },
                        wolPort = wolPort, onWolPortChange = { wolPort = it.normalizeDigits().filter(Char::isDigit) },
                        wolConnectTimeoutSeconds = wolConnectTimeoutSeconds,
                        onWolConnectTimeoutChange = { wolConnectTimeoutSeconds = it.normalizeDigits().filter(Char::isDigit) },
                        wolRetryIntervalSeconds = wolRetryIntervalSeconds,
                        onWolRetryIntervalChange = { wolRetryIntervalSeconds = it.normalizeDigits().filter(Char::isDigit) },
                        wolMaxRetries = wolMaxRetries,
                        onWolMaxRetriesChange = { wolMaxRetries = it.normalizeDigits().filter(Char::isDigit) },
                    )
                }

                // ── Server Trust — TOFU fingerprint management ────────────────
                // FIX-MED-R3-4: only shown when editing an existing profile so there
                // is an actual stored fingerprint that might need clearing (e.g. after
                // the server certificate is renewed or the host is rebuilt).
                if (profile != null) {
                    // SFTP-STANDALONE FEATURE: an SFTP profile pins a TOFU host
                    // key through the exact same openSshSession()/JSch
                    // "StrictHostKeyChecking = accept-new" path SSH uses (see
                    // FileTransferManager.kt) — same trusted-key management
                    // applies here as for ProtocolType.SSH.
                    val showSshTrust    = protocolType == ProtocolType.SSH || protocolType == ProtocolType.SFTP
                    val showTunnelTrust = protocolType != ProtocolType.SSH && sshTunnelEnabled &&
                        sshTunnelHops.isNotEmpty()
                    // RESET-TRUSTED-CERT FIX: see the showClearTofuRdpDialog/
                    // showClearTofuVncDialog state declarations above.
                    val showRdpTrust = protocolType == ProtocolType.RDP
                    val showVncTrust = protocolType == ProtocolType.VNC
                    // TELNET-TLS FEATURE: only meaningful when this Telnet profile
                    // actually has TLS turned on — plain Telnet has no certificate
                    // to pin at all.
                    val showTelnetTrust = protocolType == ProtocolType.TELNET && telnetUseTls
                    if (showSshTrust || showTunnelTrust || showRdpTrust || showVncTrust || showTelnetTrust) {
                        SettingsCard(
                            icon         = Icons.Outlined.Fingerprint,
                            accentColor  = NovaPink,
                            title        = stringResource(R.string.tofu_clear_section),
                            subtitle     = stringResource(R.string.card_trust_desc),
                            expanded     = expandTrust,
                            onExpandedChange = { expandTrust = it }
                        ) {
                            ServerTrustSection(
                                tofuClearedMessage = tofuClearedMessage,
                                showSshTrust = showSshTrust,
                                showTunnelTrust = showTunnelTrust,
                                showRdpTrust = showRdpTrust,
                                showVncTrust = showVncTrust,
                                showTelnetTrust = showTelnetTrust,
                                onClearSshTofu = { showClearTofuSshDialog = true },
                                onClearTunnelTofu = { showClearTofuTunnelDialog = true },
                                onClearRdpTofu = { showClearTofuRdpDialog = true },
                                onClearVncTofu = { showClearTofuVncDialog = true },
                                onClearTelnetTofu = { showClearTofuTelnetDialog = true }
                            )
                        }
                    }
                }
                } // end optional-settings Column
                } // end optional-settings AnimatedVisibility
            }
        }
    }
    }
    }

    // ADD-CONNECTION PROTOCOL PICKER (Part 2/2): "What is this protocol?" —
    // see the actions button above and ProtocolIntroPanel's class doc. Own
    // top-level Dialog window (ModalBottomSheet), so it sits fine as a
    // sibling of the editor's own Dialog rather than nested inside it.
    if (showProtocolIntro) {
        ProtocolCatalog.byId[protocolType.name]?.let { entry ->
            ProtocolIntroPanel(
                entry = entry,
                onDismiss = { showProtocolIntro = false },
                onContinue = { showProtocolIntro = false },
            )
        }
    }
}

// ── ProfileFormDialog — extracted sections ────────────────────────────────────
// PERF-FIX: each section below owns only the state it actually displays and
// takes it as plain parameters/callbacks. A keystroke in one section (e.g.
// gatewayHost) now only invalidates *that* section's own call — sibling
// sections (WOL, SSH tunnel, Quick Connect...) are skipped by Compose entirely
// since their own parameters didn't change, instead of the previous single
// ~650-line composable being re-walked on every keystroke in any field.

// ── Test Connection (TEST-CONNECTION FEATURE) ─────────────────────────────
// A raw TCP handshake against host:port, run off the main thread, so the
// person gets a fast reachable/unreachable answer without needing to attempt
// a full RDP/VNC/SSH/etc. session first. This deliberately checks nothing
// protocol-specific (no RDP negotiation, no SSH banner, no auth) — it only
// answers "is something listening on this address", which is exactly the
// class of mistake (wrong IP, wrong port, server down, blocked by firewall)
// that used to only surface after tapping Connect and waiting for the full
// session to time out.
private sealed class ConnectionTestState {
    object Idle : ConnectionTestState()
    object Testing : ConnectionTestState()
    data class Success(val latencyMs: Long) : ConnectionTestState()
    data class Failure(val reason: String) : ConnectionTestState()
}

private const val TEST_CONNECTION_TIMEOUT_MS = 5000

/** Attempts a plain TCP connect to [host]:[port]; never throws. */
private fun performTcpConnectionTest(host: String, port: Int): ConnectionTestState {
    return try {
        val start = System.currentTimeMillis()
        java.net.Socket().use { socket ->
            socket.connect(
                java.net.InetSocketAddress(host, port),
                TEST_CONNECTION_TIMEOUT_MS
            )
        }
        ConnectionTestState.Success(System.currentTimeMillis() - start)
    } catch (e: java.net.UnknownHostException) {
        ConnectionTestState.Failure(e.message ?: "unknown host")
    } catch (e: java.net.SocketTimeoutException) {
        ConnectionTestState.Failure("timeout")
    } catch (e: Exception) {
        ConnectionTestState.Failure(e.message ?: e.javaClass.simpleName)
    }
}

@Composable
private fun TestConnectionRow(
    host: String,
    port: String,
    portValid: Boolean,
    modifier: Modifier = Modifier,
) {
    // Keyed on host+port: editing either field after a result was shown
    // resets straight back to Idle instead of leaving a stale "Reachable"
    // badge pointing at an address that's no longer what's in the fields.
    var state by remember(host, port) { mutableStateOf<ConnectionTestState>(ConnectionTestState.Idle) }
    val scope = rememberCoroutineScope()
    val canTest = host.isNotBlank() && portValid && state !is ConnectionTestState.Testing

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(10.dp))
                .alpha(if (canTest) 1f else 0.4f)
                .border(1.dp, PulsarCyan.copy(0.5f), RoundedCornerShape(10.dp))
                .clickable(enabled = canTest) {
                    val targetHost = host.trim()
                    val targetPort = port.normalizeDigits().toIntOrNull()
                    if (targetPort == null) return@clickable
                    state = ConnectionTestState.Testing
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            performTcpConnectionTest(targetHost, targetPort)
                        }
                        state = result
                    }
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state is ConnectionTestState.Testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = PulsarCyan
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.NetworkCheck,
                        contentDescription = null,
                        tint = PulsarCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.test_connection),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = PulsarCyan
                )
            }
        }

        when (val s = state) {
            is ConnectionTestState.Success -> Text(
                text = stringResource(R.string.test_connection_success, s.latencyMs),
                style = MaterialTheme.typography.bodySmall,
                color = PlasmaGreen
            )
            is ConnectionTestState.Failure -> Text(
                text = stringResource(R.string.test_connection_failure, s.reason),
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            else -> {}
        }
    }
}

@Composable
private fun QuickConnectSection(
    name:                    String, onNameChange: (String) -> Unit,
    host:                    String, onHostChange: (String) -> Unit,
    port:                    String, onPortChange: (String) -> Unit,
    showUsername:            Boolean,
    username:                String, onUsernameChange: (String) -> Unit,
    // ARD FIX: optional override so VNC profiles can clarify that the
    // username field is for Apple Screen Sharing/ARD auth specifically
    // (defaults to the generic "Username" label for every other protocol,
    // unchanged from before).
    usernameLabel:           String = stringResource(R.string.username),
    passwordLabel:           String,
    password:                String, onPasswordChange: (String) -> Unit,
    passwordVisible:         Boolean, onTogglePasswordVisible: () -> Unit,
) {
    val portValid = isPortInRange(port)

    SpaceTextField(name, onNameChange, stringResource(R.string.connection_name), Icons.AutoMirrored.Outlined.Label)

    // Host/IP + Port side by side in one row: wide field for the host, narrow
    // field for the port, so the port can be edited directly instead of
    // hunting for it inside a combined string.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SpaceTextField(
            value          = host,
            onValueChange  = onHostChange,
            label          = stringResource(R.string.host_ip_short),
            icon           = Icons.Outlined.Language,
            modifier       = Modifier.weight(0.66f)
        )
        SpaceTextField(
            value          = port,
            onValueChange  = onPortChange,
            label          = stringResource(R.string.port),
            icon           = Icons.Outlined.SettingsEthernet,
            keyboardType   = KeyboardType.Number,
            isError        = port.isNotBlank() && !portValid,
            modifier       = Modifier.weight(0.34f)
        )
    }

    // TEST-CONNECTION FEATURE: lets the person verify host/port reachability
    // (a plain TCP handshake) before saving the profile or launching a full
    // session — previously the only way to discover a wrong IP/port was to
    // attempt an actual RDP/VNC/SSH handshake. Keyed on host+port so any
    // edit to either field resets a stale result instead of showing a
    // "Reachable" from a since-changed address.
    TestConnectionRow(host = host, port = port, portValid = portValid)

    if (showUsername) {
        SpaceTextField(username, onUsernameChange, usernameLabel, Icons.Outlined.Person)
    }

    SpaceTextField(
        value            = password,
        onValueChange    = onPasswordChange,
        label            = passwordLabel,
        icon             = Icons.Outlined.Lock,
        isPassword       = true,
        passwordVisible  = passwordVisible,
        onTogglePassword = onTogglePasswordVisible
    )
}

// NOTE: OrganizeSection (folder picker + tags input) previously lived here.
// Removed along with its call site in ProfileFormDialog — folder/tag
// assignment is no longer exposed in the connection form. The underlying
// `folderId`/`tags` fields on RdpProfile are untouched; folders themselves
// are still manageable elsewhere (NewFolderDialog/RenameFolderDialog/
// DeleteFolderDialog further down in this file), this just removes the
// per-connection picker that used to clutter the add/edit form.

// SNMP FEATURE: the SNMP credential/version field set, factored out of
// ProtocolOptionsSection's ProtocolType.SNMP branch so the exact same UI can
// be reused inside the "SNMP monitoring add-on" SettingsCard for non-SNMP
// profiles (see its call site further down) without duplicating ~70 lines
// of form code in two places.
@Composable
private fun SnmpCredentialFields(
    snmpVersion: String, onSnmpVersionChange: (String) -> Unit,
    snmpPort: String, onSnmpPortChange: (String) -> Unit,
    snmpCommunity: String, onSnmpCommunityChange: (String) -> Unit,
    snmpV3Username: String, onSnmpV3UsernameChange: (String) -> Unit,
    snmpV3SecurityLevel: String, onSnmpV3SecurityLevelChange: (String) -> Unit,
    snmpV3AuthProtocol: String, onSnmpV3AuthProtocolChange: (String) -> Unit,
    snmpV3AuthPassphrase: String, onSnmpV3AuthPassphraseChange: (String) -> Unit,
    snmpV3PrivProtocol: String, onSnmpV3PrivProtocolChange: (String) -> Unit,
    snmpV3PrivPassphrase: String, onSnmpV3PrivPassphraseChange: (String) -> Unit,
    snmpV3ContextName: String, onSnmpV3ContextNameChange: (String) -> Unit,
) {
    Text(stringResource(R.string.snmp_version_label), color = CometTail, style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("V1" to "v1", "V2C" to "v2c", "V3" to stringResource(R.string.snmp_v3_recommended)).forEach { (value, label) ->
            AuthTypeChip(label = label, selected = snmpVersion == value, onClick = { onSnmpVersionChange(value) })
        }
    }
    SpaceTextField(
        value = snmpPort,
        onValueChange = { v -> onSnmpPortChange(v.filter { it.isDigit() }.take(5)) },
        label = stringResource(R.string.snmp_port_label),
        icon = Icons.Outlined.SettingsEthernet,
        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        // SNMP-ROADMAP FIX: was digit-filtered only, no range check — a port
        // like "70000" or "0" passed straight through to canSave. Mirrors the
        // isError pattern already used for gatewayPort/proxyPort/wolPort
        // elsewhere in this file.
        isError = snmpPort.isNotBlank() && !isPortInRange(snmpPort),
    )
    if (snmpVersion == "V1" || snmpVersion == "V2C") {
        SpaceTextField(
            value = snmpCommunity,
            onValueChange = onSnmpCommunityChange,
            label = stringResource(R.string.snmp_community),
            icon = Icons.Outlined.Key,
        )
        Text(
            text = stringResource(R.string.snmp_community_plaintext_warning),
            color = CometTail, style = MaterialTheme.typography.bodySmall,
        )
    } else {
        SpaceTextField(value = snmpV3Username, onValueChange = onSnmpV3UsernameChange, label = stringResource(R.string.snmp_username), icon = Icons.Outlined.Person)
        Text(stringResource(R.string.snmp_security_level), color = CometTail, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "NO_AUTH_NO_PRIV" to stringResource(R.string.snmp_security_level_none),
                "AUTH_NO_PRIV" to stringResource(R.string.snmp_security_level_auth_only),
                "AUTH_PRIV" to stringResource(R.string.snmp_security_level_auth_privacy),
            ).forEach { (value, label) ->
                AuthTypeChip(label = label, selected = snmpV3SecurityLevel == value, onClick = { onSnmpV3SecurityLevelChange(value) })
            }
        }
        if (snmpV3SecurityLevel != "NO_AUTH_NO_PRIV") {
            Text(stringResource(R.string.snmp_authentication_protocol), color = CometTail, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("MD5", "SHA1", "SHA224", "SHA256", "SHA384", "SHA512").forEach { proto ->
                    AuthTypeChip(label = proto, selected = snmpV3AuthProtocol == proto, onClick = { onSnmpV3AuthProtocolChange(proto) })
                }
            }
            SpaceTextField(
                value = snmpV3AuthPassphrase, onValueChange = onSnmpV3AuthPassphraseChange,
                label = stringResource(R.string.snmp_auth_passphrase), icon = Icons.Outlined.Lock, isPassword = true,
                // SNMP-ROADMAP FIX: red border once the user has typed
                // something too short, instead of only the static hint text
                // below — same isError-on-touched pattern as the port field.
                isError = snmpV3AuthPassphrase.isNotEmpty() && snmpV3AuthPassphrase.length < 8,
            )
            Text(
                stringResource(R.string.snmp_passphrase_min_length_warning),
                color = if (snmpV3AuthPassphrase.isNotEmpty() && snmpV3AuthPassphrase.length < 8)
                    MaterialTheme.colorScheme.error else CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (snmpV3SecurityLevel == "AUTH_PRIV") {
            Text(stringResource(R.string.snmp_privacy_protocol), color = CometTail, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("DES", "AES128", "AES192", "AES256").forEach { proto ->
                    AuthTypeChip(label = proto, selected = snmpV3PrivProtocol == proto, onClick = { onSnmpV3PrivProtocolChange(proto) })
                }
            }
            SpaceTextField(
                value = snmpV3PrivPassphrase, onValueChange = onSnmpV3PrivPassphraseChange,
                label = stringResource(R.string.snmp_privacy_passphrase), icon = Icons.Outlined.Lock, isPassword = true,
                // SNMP-ROADMAP FIX: same 8-char minimum as the auth passphrase
                // above (RFC 3414 §11.2 applies to both keys), previously
                // enforced only by canSave with no visible field-level cue.
                isError = snmpV3PrivPassphrase.isNotEmpty() && snmpV3PrivPassphrase.length < 8,
            )
        }
        SpaceTextField(
            value = snmpV3ContextName, onValueChange = onSnmpV3ContextNameChange,
            label = stringResource(R.string.snmp_context_name_optional), icon = Icons.Outlined.Label,
        )
    }
}

@Composable
private fun ProtocolOptionsSection(
    protocolType:            ProtocolType,
    useNla:                  Boolean, onUseNlaChange: (Boolean) -> Unit,
    enableSound:             Boolean, onEnableSoundChange: (Boolean) -> Unit,
    // AUDIO-BACKEND FIX: whether this build's native library actually has a
    // working audio backend (see AFreeRdpBridge.isAudioBackendAvailable).
    // Defaults to true so existing callers that don't pass it keep the
    // previous always-enabled toggle behavior.
    audioBackendAvailable:   Boolean = true,
    enableMicRedirect:       Boolean = false, onEnableMicRedirectChange: (Boolean) -> Unit = {},
    // PRINTER-REDIRECT FEATURE: mirrors enableSound/audioBackendAvailable
    // immediately above — see AFreeRdpBridge.isPrinterBackendAvailable.
    enablePrinterRedirect:   Boolean = false, onEnablePrinterRedirectChange: (Boolean) -> Unit = {},
    printerBackendAvailable: Boolean = true,
    // WEBCAM-REDIRECT FEATURE: mirrors enablePrinterRedirect/printerBackendAvailable
    // immediately above — see AFreeRdpBridge.isWebcamBackendAvailable.
    enableWebcamRedirect:    Boolean = false, onEnableWebcamRedirectChange: (Boolean) -> Unit = {},
    webcamBackendAvailable:  Boolean = true,
    // SMARTCARD-REDIRECT FEATURE: mirrors enableWebcamRedirect/webcamBackendAvailable
    // immediately above — see AFreeRdpBridge.isSmartcardBackendAvailable.
    enableSmartcardRedirect: Boolean = false, onEnableSmartcardRedirectChange: (Boolean) -> Unit = {},
    smartcardBackendAvailable: Boolean = true,
    // PARALLEL-REDIRECT FEATURE: mirrors enableSmartcardRedirect immediately
    // above, plus the user-supplied local device path (no *_BackendAvailable
    // flag — see systemsgo_jni.c's parallel block for why this one is
    // unconditional).
    enableParallelRedirect: Boolean = false, onEnableParallelRedirectChange: (Boolean) -> Unit = {},
    parallelPortPath: String = "", onParallelPortPathChange: (String) -> Unit = {},
    // SERIAL-REDIRECT FEATURE: mirrors enableParallelRedirect/parallelPortPath
    // immediately above, just a "serial" rdpdr device instead of "parallel".
    enableSerialRedirect: Boolean = false, onEnableSerialRedirectChange: (Boolean) -> Unit = {},
    serialPortPath: String = "", onSerialPortPathChange: (String) -> Unit = {},
    // SERIAL-OVER-NETWORK FEATURE: mirrors enableSerialRedirect/serialPortPath
    // immediately above — mode picks whether serialPortPath (LOCAL_DEVICE) or
    // serialNetworkHost/Port (RAW_TCP / RFC_2217) is actually used. See
    // RdpProfile.serialRedirectMode's doc comment.
    serialRedirectMode: com.systemsgo.hex.data.model.SerialRedirectMode = com.systemsgo.hex.data.model.SerialRedirectMode.LOCAL_DEVICE,
    onSerialRedirectModeChange: (com.systemsgo.hex.data.model.SerialRedirectMode) -> Unit = {},
    serialNetworkHost: String = "", onSerialNetworkHostChange: (String) -> Unit = {},
    serialNetworkPort: String = "2217", onSerialNetworkPortChange: (String) -> Unit = {},
    vncViewOnly:             Boolean, onVncViewOnlyChange: (Boolean) -> Unit,
    // ULTRAVNC-REPEATER FEATURE: mirrors vncViewOnly immediately above.
    vncRepeaterEnabled: Boolean = false, onVncRepeaterEnabledChange: (Boolean) -> Unit = {},
    vncRepeaterMode: VncRepeaterMode = VncRepeaterMode.MODE_II, onVncRepeaterModeChange: (VncRepeaterMode) -> Unit = {},
    vncRepeaterId: String = "", onVncRepeaterIdChange: (String) -> Unit = {},
    isVncRepeaterIdError: Boolean = false,
    // LISTEN-MODE FEATURE: mirrors the repeater params immediately above.
    vncListenModeEnabled: Boolean = false, onVncListenModeEnabledChange: (Boolean) -> Unit = {},
    vncListenPort: String = "5500", onVncListenPortChange: (String) -> Unit = {},
    isVncListenPortError: Boolean = false,
    sshAuthType:             SshAuthType, onSshAuthTypeChange: (SshAuthType) -> Unit,
    sshPrivateKey:           String, onSshPrivateKeyChange: (String) -> Unit,
    sshKeyPassphrase:        String, onSshKeyPassphraseChange: (String) -> Unit,
    sshAgentForwardingEnabled: Boolean = false, onSshAgentForwardingChange: (Boolean) -> Unit = {},
    socksProxyEnabled: Boolean = false, onSocksProxyEnabledChange: (Boolean) -> Unit = {},
    socksProxyPort: String = "1080", onSocksProxyPortChange: (String) -> Unit = {},
    isSocksProxyPortError: Boolean = false,
    // X11 FORWARDING FEATURE: `ssh -X`/`-Y` equivalent — see
    // RdpProfile.x11ForwardingEnabled's doc comment.
    x11ForwardingEnabled: Boolean = false, onX11ForwardingEnabledChange: (Boolean) -> Unit = {},
    x11DisplayHost: String = "127.0.0.1", onX11DisplayHostChange: (String) -> Unit = {},
    x11DisplayNumber: String = "0", onX11DisplayNumberChange: (String) -> Unit = {},
    x11AuthCookie: String = "", onX11AuthCookieChange: (String) -> Unit = {},
    isX11DisplayNumberError: Boolean = false,
    isX11AuthCookieError: Boolean = false,
    // SSH-PORT-FORWARD FEATURE: user-defined static -L/-R forwards — see
    // RdpProfile.sshPortForwards's doc comment.
    sshPortForwards: List<com.systemsgo.hex.data.model.SshPortForwardRule> = emptyList(),
    onSshPortForwardsChange: (List<com.systemsgo.hex.data.model.SshPortForwardRule>) -> Unit = {},
    // TELNET-TLS FEATURE: see RdpProfile.telnetUseTls's doc comment.
    telnetUseTls: Boolean = false, onTelnetUseTlsChange: (Boolean) -> Unit = {},
    // RLOGIN FEATURE: see RdpProfile.rloginRemoteUsername/rloginTerminalType's
    // doc comments.
    rloginRemoteUsername: String = "", onRloginRemoteUsernameChange: (String) -> Unit = {},
    rloginTerminalType: String = "xterm/38400", onRloginTerminalTypeChange: (String) -> Unit = {},
    // MOSH FEATURE: see RdpProfile's MOSH-specific fields doc comment.
    moshRemoteServerCommand: String = "mosh-server", onMoshRemoteServerCommandChange: (String) -> Unit = {},
    moshUdpPortRange: String = "", onMoshUdpPortRangeChange: (String) -> Unit = {},
    moshRemoteLocale: String = "", onMoshRemoteLocaleChange: (String) -> Unit = {},
    moshColorMode: Int = 256, onMoshColorModeChange: (Int) -> Unit = {},
    moshPredictionMode: String = MoshPredictionMode.ADAPTIVE.name, onMoshPredictionModeChange: (String) -> Unit = {},
    // SERIAL-CONSOLE FEATURE (Part 1/N): see RdpProfile.serialConsoleTransport/
    // serialConsoleBaudRate/serialConsoleDataBits/serialConsoleParity/
    // serialConsoleStopBits/serialConsoleDevicePath/
    // serialConsoleHardwareFlowControl's doc comments.
    serialConsoleTransport: com.systemsgo.hex.data.model.SerialRedirectMode = com.systemsgo.hex.data.model.SerialRedirectMode.RFC_2217,
    onSerialConsoleTransportChange: (com.systemsgo.hex.data.model.SerialRedirectMode) -> Unit = {},
    serialConsoleBaudRate: String = "9600", onSerialConsoleBaudRateChange: (String) -> Unit = {},
    serialConsoleDataBits: Int = 8, onSerialConsoleDataBitsChange: (Int) -> Unit = {},
    serialConsoleParity: com.systemsgo.hex.data.model.SerialParity = com.systemsgo.hex.data.model.SerialParity.NONE,
    onSerialConsoleParityChange: (com.systemsgo.hex.data.model.SerialParity) -> Unit = {},
    serialConsoleStopBits: com.systemsgo.hex.data.model.SerialStopBits = com.systemsgo.hex.data.model.SerialStopBits.ONE,
    onSerialConsoleStopBitsChange: (com.systemsgo.hex.data.model.SerialStopBits) -> Unit = {},
    serialConsoleDevicePath: String = "", onSerialConsoleDevicePathChange: (String) -> Unit = {},
    serialConsoleHardwareFlowControl: Boolean = false, onSerialConsoleHardwareFlowControlChange: (Boolean) -> Unit = {},
    // WEB-PORTAL FEATURE: see RdpProfile.webUrl/webTrustSelfSignedCertificate/
    // webAutoFillHttpAuth's doc comments.
    webUrl: String = "", onWebUrlChange: (String) -> Unit = {},
    webTrustSelfSignedCertificate: Boolean = false, onWebTrustSelfSignedCertificateChange: (Boolean) -> Unit = {},
    webAutoFillHttpAuth: Boolean = true, onWebAutoFillHttpAuthChange: (Boolean) -> Unit = {},
    // WEB-PORTAL-SMART-AUTOFILL FEATURE: see RdpProfile.webAutoFillLoginForm's doc comment.
    webAutoFillLoginForm: Boolean = true, onWebAutoFillLoginFormChange: (Boolean) -> Unit = {},
    // SNMP FEATURE: see RdpProfile.snmp*'s doc comments.
    snmpVersion: String = "V2C", onSnmpVersionChange: (String) -> Unit = {},
    snmpCommunity: String = "public", onSnmpCommunityChange: (String) -> Unit = {},
    snmpPort: String = "161", onSnmpPortChange: (String) -> Unit = {},
    snmpV3Username: String = "", onSnmpV3UsernameChange: (String) -> Unit = {},
    snmpV3SecurityLevel: String = "AUTH_PRIV", onSnmpV3SecurityLevelChange: (String) -> Unit = {},
    snmpV3AuthProtocol: String = "SHA1", onSnmpV3AuthProtocolChange: (String) -> Unit = {},
    snmpV3AuthPassphrase: String = "", onSnmpV3AuthPassphraseChange: (String) -> Unit = {},
    snmpV3PrivProtocol: String = "AES128", onSnmpV3PrivProtocolChange: (String) -> Unit = {},
    snmpV3PrivPassphrase: String = "", onSnmpV3PrivPassphraseChange: (String) -> Unit = {},
    snmpV3ContextName: String = "", onSnmpV3ContextNameChange: (String) -> Unit = {},
    // NETCONF FEATURE: see RdpProfile.netconf*'s doc comments. Auth reuses
    // sshAuthType/sshPrivateKey/sshKeyPassphrase already declared above.
    netconfDefaultDatastore: String = "running", onNetconfDefaultDatastoreChange: (String) -> Unit = {},
    netconfExtraCapabilities: String = "", onNetconfExtraCapabilitiesChange: (String) -> Unit = {},
    netconfKeepAliveMs: String = "15000", onNetconfKeepAliveMsChange: (String) -> Unit = {},
    netconfConnectTimeoutMs: String = "15000", onNetconfConnectTimeoutMsChange: (String) -> Unit = {},
    netconfCompressionEnabled: Boolean = false, onNetconfCompressionEnabledChange: (Boolean) -> Unit = {},
    netconfOpenSshCertificate: String = "", onNetconfOpenSshCertificateChange: (String) -> Unit = {},
    netconfAutoReconnect: Boolean = true, onNetconfAutoReconnectChange: (Boolean) -> Unit = {},
    // CALL-HOME FEATURE (RFC 8071, Part 12): see RdpProfile.netconfCallHome*'s doc comments.
    netconfCallHomeEnabled: Boolean = false, onNetconfCallHomeEnabledChange: (Boolean) -> Unit = {},
    netconfCallHomeListenPort: String = "4334", onNetconfCallHomeListenPortChange: (String) -> Unit = {},
    netconfCallHomeAllowedSourceHost: String = "", onNetconfCallHomeAllowedSourceHostChange: (String) -> Unit = {},
    // CALL-HOME-TLS FEATURE (RFC 8071's netconf-ch-tls variant): see RdpProfile.netconfCallHomeTransport/netconfCallHomeTlsClientCertificatePem's doc comments.
    netconfCallHomeTransport: String = "SSH", onNetconfCallHomeTransportChange: (String) -> Unit = {},
    netconfCallHomeTlsClientCertificatePem: String = "", onNetconfCallHomeTlsClientCertificatePemChange: (String) -> Unit = {},
    // GUACAMOLE-PROTOCOL FEATURE: manual-entry fields, or filled by
    // GuacamoleConnectionPickerDialog via onBrowseConnectionsClick.
    guacServerUrl: String = "", onGuacServerUrlChange: (String) -> Unit = {},
    guacDataSource: String = "", onGuacDataSourceChange: (String) -> Unit = {},
    guacConnectionIdentifier: String = "", onGuacConnectionIdentifierChange: (String) -> Unit = {},
    guacConnectionName: String = "", onGuacConnectionNameChange: (String) -> Unit = {},
    onBrowseConnectionsClick: () -> Unit = {},
    guacRememberSession: Boolean = false, onGuacRememberSessionChange: (Boolean) -> Unit = {},
    // RTSP FEATURE: see RdpProfile.rtspStreamPath/rtspTransportMode/
    // rtspUseTls's doc comments. Defaulted so every existing call site keeps
    // compiling unchanged, same convention as every other protocol's params
    // above.
    rtspStreamPath: String = "", onRtspStreamPathChange: (String) -> Unit = {},
    rtspTransportMode: String = com.systemsgo.hex.rtsp.protocol.RtspTransportMode.TCP_INTERLEAVED.name,
    onRtspTransportModeChange: (String) -> Unit = {},
    rtspUseTls: Boolean = false, onRtspUseTlsChange: (Boolean) -> Unit = {},
    // MODBUS-TCP FEATURE (Part 2/2): host/port are the existing generic
    // fields above; only what's genuinely Modbus-specific needs its own
    // params here.
    modbusUnitId: String = "1", onModbusUnitIdChange: (String) -> Unit = {},
    modbusConnectTimeoutMs: String = "5000", onModbusConnectTimeoutMsChange: (String) -> Unit = {},
    modbusResponseTimeoutMs: String = "3000", onModbusResponseTimeoutMsChange: (String) -> Unit = {},
    modbusRetries: String = "1", onModbusRetriesChange: (String) -> Unit = {},
    modbusPollIntervalMs: String = "1000", onModbusPollIntervalMsChange: (String) -> Unit = {},
    // COMPILE-BREAK FIX: these four params were entirely missing — the
    // REDFISH/IPMI/AMT branches below referenced acceptSelfSignedCertificate/
    // ipmiPrivilegeLevel/ipmiKgKey/amtUseTls as if they were in scope, but
    // those only exist as ProfileFormDialog's own rememberSaveable state
    // (a different, enclosing composable) — unresolved references, so this
    // file could not have compiled as delivered. Added now as ordinary
    // hoisted-state params, same value+onChange convention as every other
    // field in this function.
    acceptSelfSignedCertificate: Boolean = false, onAcceptSelfSignedCertificateChange: (Boolean) -> Unit = {},
    ipmiPrivilegeLevel: String = "ADMINISTRATOR", onIpmiPrivilegeLevelChange: (String) -> Unit = {},
    ipmiKgKey: String = "", onIpmiKgKeyChange: (String) -> Unit = {},
    amtUseTls: Boolean = false, onAmtUseTlsChange: (Boolean) -> Unit = {},
    // AMT-VPRO FEATURE — Phase 6 (CIRA setup UI): see RdpProfile.ciraEnabled's
    // doc comment. Same hoisted-state convention as every other field here.
    ciraEnabled: Boolean = false, onCiraEnabledChange: (Boolean) -> Unit = {},
    ciraRelayHost: String = "", onCiraRelayHostChange: (String) -> Unit = {},
    ciraRelayPort: String = "8081", onCiraRelayPortChange: (String) -> Unit = {},
    ciraRelayUsername: String = "", onCiraRelayUsernameChange: (String) -> Unit = {},
    ciraRelayPassword: String = "", onCiraRelayPasswordChange: (String) -> Unit = {},
    ciraDeviceId: String = "", onCiraDeviceIdChange: (String) -> Unit = {},
    ciraRelayUseTls: Boolean = false, onCiraRelayUseTlsChange: (Boolean) -> Unit = {},
    // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: see RdpProfile.ftpSecurity/
    // ftpPassiveMode/smbShare/smbDomain/webdavBaseUrl/nfsExportPath/nfsUid/
    // nfsGid/nfsMountdPort's doc comments. nfsUid/nfsGid/nfsMountdPort are
    // plain String params here (same numeric-text-field convention as
    // modbusUnitId/snmpPort above) — parsed to Int only when the profile is
    // actually saved.
    ftpSecurity: String = com.systemsgo.hex.transfer.FtpSecurity.PLAIN.name, onFtpSecurityChange: (String) -> Unit = {},
    ftpPassiveMode: Boolean = true, onFtpPassiveModeChange: (Boolean) -> Unit = {},
    smbShare: String = "", onSmbShareChange: (String) -> Unit = {},
    smbDomain: String = "", onSmbDomainChange: (String) -> Unit = {},
    webdavBaseUrl: String = "", onWebdavBaseUrlChange: (String) -> Unit = {},
    nfsExportPath: String = "", onNfsExportPathChange: (String) -> Unit = {},
    nfsUid: String = "0", onNfsUidChange: (String) -> Unit = {},
    nfsGid: String = "0", onNfsGidChange: (String) -> Unit = {},
    nfsMountdPort: String = "", onNfsMountdPortChange: (String) -> Unit = {},
    // VIRTUALBOX-VRDE FEATURE (Part 1/N): see RdpProfile.vrdeAuthType/
    // vrdeMultiConnectionAllowed's doc comments — informational host-side
    // VRDE settings, surfaced here so the editor's picker/switch has
    // something to read/write (closes the "no dedicated editor fields yet"
    // gap this branch used to document).
    vrdeAuthType: String = VrdeAuthType.NULL_AUTH.name, onVrdeAuthTypeChange: (String) -> Unit = {},
    vrdeMultiConnectionAllowed: Boolean = false, onVrdeMultiConnectionAllowedChange: (Boolean) -> Unit = {},
    // VMWARE-VSPHERE FEATURE (Part 1/N): see RdpProfile.vsphereApiMode/
    // vsphereAcceptSelfSignedCertificate/vsphereDatacenter's doc comments.
    vsphereApiMode: String = VSphereApiMode.REST.name, onVsphereApiModeChange: (String) -> Unit = {},
    vsphereAcceptSelfSignedCertificate: Boolean = true, onVsphereAcceptSelfSignedCertificateChange: (Boolean) -> Unit = {},
    vsphereDatacenter: String = "", onVsphereDatacenterChange: (String) -> Unit = {},
) {
    when (protocolType) {
        ProtocolType.RDP -> {
            SpaceSwitch(
                label   = stringResource(R.string.use_nla),
                checked = useNla,
                onCheckedChange = onUseNlaChange
            )
            // REDESIGN: sound / mic-redirect / printer-redirect used to be three
            // separate label+switch rows (a lot of repeated text for three simple
            // on/off toggles). Replaced with one row of tappable icon toggles —
            // tap an icon to flip it on/off, the active ones light up in
            // PulsarCyan. Unsupported-backend ones (see audioBackendAvailable /
            // printerBackendAvailable below) stay visible but dimmed/disabled
            // rather than disappearing, same as before, so it's clear the
            // capability exists but isn't wired up in this build.
            IconToggleRow(
                items = listOf(
                    IconToggleData(
                        icon = Icons.Outlined.VolumeUp,
                        label = stringResource(R.string.enable_sound),
                        checked = enableSound && audioBackendAvailable,
                        onCheckedChange = onEnableSoundChange,
                        enabled = audioBackendAvailable,
                    ),
                    IconToggleData(
                        icon = Icons.Outlined.Mic,
                        label = stringResource(R.string.enable_mic_redirect),
                        checked = enableMicRedirect,
                        onCheckedChange = onEnableMicRedirectChange,
                        enabled = true,
                    ),
                    IconToggleData(
                        icon = Icons.Outlined.Print,
                        label = stringResource(R.string.enable_printer_redirect),
                        checked = enablePrinterRedirect && printerBackendAvailable,
                        onCheckedChange = onEnablePrinterRedirectChange,
                        enabled = printerBackendAvailable,
                    ),
                    IconToggleData(
                        icon = Icons.Outlined.Videocam,
                        label = stringResource(R.string.enable_webcam_redirect),
                        checked = enableWebcamRedirect && webcamBackendAvailable,
                        onCheckedChange = onEnableWebcamRedirectChange,
                        enabled = webcamBackendAvailable,
                    ),
                    IconToggleData(
                        icon = Icons.Outlined.CreditCard,
                        label = stringResource(R.string.enable_smartcard_redirect),
                        checked = enableSmartcardRedirect && smartcardBackendAvailable,
                        onCheckedChange = onEnableSmartcardRedirectChange,
                        enabled = smartcardBackendAvailable,
                    ),
                    IconToggleData(
                        icon = Icons.Outlined.Cable,
                        label = stringResource(R.string.enable_parallel_redirect),
                        checked = enableParallelRedirect,
                        onCheckedChange = onEnableParallelRedirectChange,
                        enabled = true,
                    ),
                    IconToggleData(
                        icon = Icons.Outlined.SettingsInputComponent,
                        label = stringResource(R.string.enable_serial_redirect),
                        checked = enableSerialRedirect,
                        onCheckedChange = onEnableSerialRedirectChange,
                        enabled = true,
                    ),
                )
            )
            if (!audioBackendAvailable) {
                Text(stringResource(R.string.enable_sound_unsupported), color = CometTail.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
            }
            if (!printerBackendAvailable) {
                Text(stringResource(R.string.enable_printer_redirect_unsupported), color = CometTail.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
            }
            if (!webcamBackendAvailable) {
                Text(stringResource(R.string.enable_webcam_redirect_unsupported), color = CometTail.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
            }
            if (!smartcardBackendAvailable) {
                Text(stringResource(R.string.enable_smartcard_redirect_unsupported), color = CometTail.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
            } else if (enableSmartcardRedirect) {
                // SMARTCARD-REDIRECT FEATURE: even when the channel backend is
                // available, no on-device PC/SC resource manager exists yet —
                // see AFreeRdpBridge.isSmartcardBackendAvailable's doc comment.
                // Shown only once the user actually turns the toggle on, so it
                // doesn't clutter the form for people who never touch it.
                Text(stringResource(R.string.enable_smartcard_redirect_experimental), color = CometTail.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
            }
            if (enableParallelRedirect) {
                // PARALLEL-REDIRECT FEATURE: there is no Android-side default
                // for a "parallel port" (unlike drive redirect, which always
                // points at this app's own storage), so the toggle alone
                // does nothing until the user fills in a real local device
                // path here — see systemsgo_jni.c's parallel block, which skips
                // registration entirely when this is blank.
                OutlinedTextField(
                    value = parallelPortPath,
                    onValueChange = onParallelPortPathChange,
                    label = { Text(stringResource(R.string.parallel_port_path_label)) },
                    placeholder = { Text("/dev/ttyUSB0") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.enable_parallel_redirect_hint), color = CometTail.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
            }
            if (enableSerialRedirect) {
                // SERIAL-REDIRECT FEATURE: mirrors the enableParallelRedirect
                // path field immediately above — no Android-side default,
                // so the toggle does nothing until a real device node is
                // entered here (LOCAL_DEVICE mode) or a network endpoint is
                // filled in below (RAW_TCP / RFC_2217 — see
                // SERIAL-OVER-NETWORK FEATURE, RdpProfile.serialRedirectMode's
                // doc comment).
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = serialRedirectMode == com.systemsgo.hex.data.model.SerialRedirectMode.LOCAL_DEVICE,
                        onClick  = { onSerialRedirectModeChange(com.systemsgo.hex.data.model.SerialRedirectMode.LOCAL_DEVICE) },
                        label    = { Text(com.systemsgo.hex.data.model.SerialRedirectMode.LOCAL_DEVICE.label) },
                    )
                    FilterChip(
                        selected = serialRedirectMode == com.systemsgo.hex.data.model.SerialRedirectMode.RAW_TCP,
                        onClick  = { onSerialRedirectModeChange(com.systemsgo.hex.data.model.SerialRedirectMode.RAW_TCP) },
                        label    = { Text(com.systemsgo.hex.data.model.SerialRedirectMode.RAW_TCP.label) },
                    )
                    FilterChip(
                        selected = serialRedirectMode == com.systemsgo.hex.data.model.SerialRedirectMode.RFC_2217,
                        onClick  = { onSerialRedirectModeChange(com.systemsgo.hex.data.model.SerialRedirectMode.RFC_2217) },
                        label    = { Text(com.systemsgo.hex.data.model.SerialRedirectMode.RFC_2217.label) },
                    )
                }
                if (serialRedirectMode == com.systemsgo.hex.data.model.SerialRedirectMode.LOCAL_DEVICE) {
                    OutlinedTextField(
                        value = serialPortPath,
                        onValueChange = onSerialPortPathChange,
                        label = { Text(stringResource(R.string.serial_port_path_label)) },
                        placeholder = { Text("/dev/ttyUSB0") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = serialNetworkHost,
                        onValueChange = onSerialNetworkHostChange,
                        label = { Text(stringResource(R.string.components_host_label)) },
                        placeholder = { Text("192.168.1.50") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = serialNetworkPort,
                        onValueChange = { v -> onSerialNetworkPortChange(v.filter { it.isDigit() }.take(5)) },
                        label = { Text(stringResource(R.string.components_port_label)) },
                        placeholder = { Text("2217") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(R.string.enable_serial_redirect_hint), color = CometTail.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
            }
        }
        ProtocolType.VNC -> {
            SpaceSwitch(
                label   = stringResource(R.string.vnc_view_only),
                checked = vncViewOnly,
                onCheckedChange = onVncViewOnlyChange
            )
            // VENCRYPT FIX (prep only — not implemented): this is where a
            // "Require TLS (VeNCrypt)" toggle would go, once RfbConnectable
            // actually supports it (see RfbConnectable.negotiateVeNCrypt()).
            // Follow the enableSound/audioBackendAvailable pattern just
            // above for the disabled-with-explanation state in the
            // meantime — a SpaceSwitch with `enabled = false` and a
            // subtitle string — rather than adding a switch that silently
            // does nothing when checked.

            // CLEARTEXT-WARNING FIX: until the VeNCrypt toggle above exists,
            // every VNC session this app makes is unencrypted at the RFB
            // layer — the only thing standing between an eavesdropper and
            // the screen/keyboard/VNC password is however the network path
            // itself is secured. Surfaced here (not just in a code comment)
            // so the person filling in this form actually sees it before
            // connecting, same reasoning as the Telnet TLS-off warning and
            // the Rlogin section below.
            Text(
                text  = stringResource(R.string.vnc_cleartext_warning),
                color = SolarFlare,
                style = MaterialTheme.typography.bodySmall
            )

            // ULTRAVNC-REPEATER FEATURE: routing through an UltraVNC
            // Repeater, either Mode I (port-mapped) or Mode II (ID-based) —
            // see VncRepeaterMode's doc comment / RfbConnectable's class doc
            // for the wire protocol. When enabled, "host"/"port" above
            // (already entered at the top of this form) are the
            // *repeater's* address, not the real server's.
            SectionDivider(stringResource(R.string.vnc_repeater))
            SpaceSwitch(
                label   = stringResource(R.string.vnc_repeater),
                checked = vncRepeaterEnabled,
                onCheckedChange = onVncRepeaterEnabledChange
            )
            Text(
                text  = stringResource(R.string.vnc_repeater_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            AnimatedVisibility(visible = vncRepeaterEnabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuthTypeChip(stringResource(R.string.vnc_repeater_mode_i), vncRepeaterMode == VncRepeaterMode.MODE_I, { onVncRepeaterModeChange(VncRepeaterMode.MODE_I) }, Modifier.weight(1f))
                        AuthTypeChip(stringResource(R.string.vnc_repeater_mode_ii), vncRepeaterMode == VncRepeaterMode.MODE_II, { onVncRepeaterModeChange(VncRepeaterMode.MODE_II) }, Modifier.weight(1f))
                    }
                    Text(
                        text  = stringResource(
                            if (vncRepeaterMode == VncRepeaterMode.MODE_I)
                                R.string.vnc_repeater_mode_i_desc
                            else
                                R.string.vnc_repeater_mode_ii_desc
                        ),
                        color = CometTail,
                        style = MaterialTheme.typography.bodySmall
                    )
                    // Mode I needs no ID — the repeater's own config already
                    // maps host/port to one target server.
                    AnimatedVisibility(visible = vncRepeaterMode == VncRepeaterMode.MODE_II, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        SpaceTextField(
                            value          = vncRepeaterId,
                            onValueChange  = onVncRepeaterIdChange,
                            label          = stringResource(R.string.vnc_repeater_id),
                            icon           = Icons.Outlined.Router,
                            isError        = isVncRepeaterIdError,
                        )
                    }
                }
            }

            // LISTEN-MODE FEATURE (reverse VNC): standard RFB "listening
            // viewer" mode — this app opens vncListenPort and waits for the
            // remote VNC *server* to dial in, instead of dialing out to
            // host/port itself. Mutually exclusive with the repeater above
            // (enforced in onVncListenModeEnabledChange/onVncRepeaterEnabledChange
            // at the call site), since an accepted incoming socket and a
            // repeater this app dials out through can't both apply to the
            // same session.
            SectionDivider(stringResource(R.string.vnc_listen_mode))
            SpaceSwitch(
                label   = stringResource(R.string.vnc_listen_mode),
                checked = vncListenModeEnabled,
                onCheckedChange = onVncListenModeEnabledChange
            )
            Text(
                text  = stringResource(R.string.vnc_listen_mode_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            AnimatedVisibility(visible = vncListenModeEnabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SpaceTextField(
                    value          = vncListenPort,
                    onValueChange  = { onVncListenPortChange(it.normalizeDigits().filter(Char::isDigit)) },
                    label          = stringResource(R.string.vnc_listen_port),
                    icon           = Icons.Outlined.Router,
                    isError        = isVncListenPortError,
                    keyboardType   = KeyboardType.Number,
                )
            }
        }
        ProtocolType.SSH -> {
            SectionDivider(stringResource(R.string.ssh_authentication))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthTypeChip(stringResource(R.string.ssh_auth_password), sshAuthType == SshAuthType.PASSWORD, { onSshAuthTypeChange(SshAuthType.PASSWORD) }, Modifier.weight(1f))
                AuthTypeChip(stringResource(R.string.ssh_auth_key), sshAuthType == SshAuthType.PRIVATE_KEY, { onSshAuthTypeChange(SshAuthType.PRIVATE_KEY) }, Modifier.weight(1f))
            }
            AnimatedVisibility(visible = sshAuthType == SshAuthType.PRIVATE_KEY, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = sshPrivateKey, onValueChange = onSshPrivateKeyChange,
                        label = { Text(stringResource(R.string.ssh_private_key), color = CometTail) },
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----", color = CometTail.copy(alpha = 0.6f)) },
                        minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PulsarCyan, unfocusedBorderColor = InputBorder,
                            focusedLabelColor = PulsarCyan, cursorColor = PulsarCyan,
                            focusedTextColor = StarDust, unfocusedTextColor = StarDust,
                            focusedContainerColor = InputBg, unfocusedContainerColor = InputBg,
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    SpaceTextField(sshKeyPassphrase, onSshKeyPassphraseChange, stringResource(R.string.ssh_key_passphrase), Icons.Outlined.Key)
                    // NEW: local key generation, alongside the existing paste-a-PEM-you-
                    // already-have flow above. Fills sshPrivateKey directly on success.
                    GenerateKeyPairButton(
                        passphrase     = sshKeyPassphrase,
                        onKeyGenerated = onSshPrivateKeyChange,
                    )
                    // AGENT-FWD: only offered for PRIVATE_KEY auth — there is no local
                    // identity to forward under PASSWORD auth (see SshCredentials).
                    SpaceSwitch(
                        label   = stringResource(R.string.ssh_agent_forwarding),
                        checked = sshAgentForwardingEnabled,
                        onCheckedChange = onSshAgentForwardingChange
                    )
                    Text(
                        text  = stringResource(R.string.ssh_agent_forwarding_desc),
                        color = CometTail,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // DYN-PROXY: offered regardless of auth mode (unlike agent forwarding
            // above, which only makes sense for PRIVATE_KEY) — a dynamic SOCKS
            // proxy just needs an authenticated session, however it was reached.
            SectionDivider(stringResource(R.string.ssh_socks_proxy))
            SpaceSwitch(
                label   = stringResource(R.string.ssh_socks_proxy),
                checked = socksProxyEnabled,
                onCheckedChange = onSocksProxyEnabledChange
            )
            Text(
                text  = stringResource(R.string.ssh_socks_proxy_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            AnimatedVisibility(visible = socksProxyEnabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SpaceTextField(
                    value          = socksProxyPort,
                    onValueChange  = { onSocksProxyPortChange(it.normalizeDigits().filter(Char::isDigit)) },
                    label          = stringResource(R.string.ssh_socks_proxy_port),
                    icon           = Icons.Outlined.Router,
                    isError        = isSocksProxyPortError,
                    keyboardType   = KeyboardType.Number,
                )
            }

            // X11 FORWARDING FEATURE: `ssh -X`/`-Y` equivalent — likewise offered
            // regardless of auth mode, same reasoning as the SOCKS proxy above.
            SectionDivider(stringResource(R.string.ssh_x11_forwarding))
            SpaceSwitch(
                label   = stringResource(R.string.ssh_x11_forwarding),
                checked = x11ForwardingEnabled,
                onCheckedChange = onX11ForwardingEnabledChange
            )
            Text(
                text  = stringResource(R.string.ssh_x11_forwarding_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            AnimatedVisibility(visible = x11ForwardingEnabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SpaceTextField(
                        value          = x11DisplayHost,
                        onValueChange  = onX11DisplayHostChange,
                        label          = stringResource(R.string.ssh_x11_display_host),
                        icon           = Icons.Outlined.Monitor,
                    )
                    SpaceTextField(
                        value          = x11DisplayNumber,
                        onValueChange  = { onX11DisplayNumberChange(it.normalizeDigits().filter(Char::isDigit)) },
                        label          = stringResource(R.string.ssh_x11_display_number),
                        icon           = Icons.Outlined.Pin,
                        isError        = isX11DisplayNumberError,
                        keyboardType   = KeyboardType.Number,
                    )
                    SpaceTextField(
                        value          = x11AuthCookie,
                        onValueChange  = onX11AuthCookieChange,
                        label          = stringResource(R.string.ssh_x11_auth_cookie),
                        icon           = Icons.Outlined.Key,
                        isError        = isX11AuthCookieError,
                    )
                    Text(
                        text  = stringResource(R.string.ssh_x11_auth_cookie_desc),
                        color = CometTail,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // SSH-PORT-FORWARD FEATURE: user-defined static -L/-R forwards —
            // offered regardless of auth mode, same reasoning as the SOCKS
            // proxy and X11 blocks above.
            SectionDivider(stringResource(R.string.ssh_port_forwarding))
            Text(
                text  = stringResource(R.string.ssh_port_forwarding_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            SshPortForwardListEditor(
                rules = sshPortForwards,
                onRulesChange = onSshPortForwardsChange,
            )
        }
        // SFTP-STANDALONE FEATURE: same Authentication sub-section as
        // ProtocolType.SSH above (password vs. private key, same
        // sshAuthType/sshPrivateKey/sshKeyPassphrase state) — but deliberately
        // stops there. Agent forwarding, the dynamic SOCKS proxy, X11
        // forwarding, and static port forwards are all shell/terminal-session
        // features (see ProtocolType.SFTP's doc comment in RdpProfile.kt);
        // FileTransferManager's SftpConfig has no use for any of them, and
        // ProfileFormDialog's `canSave` SFTP branch doesn't require them either.
        ProtocolType.SFTP -> {
            SectionDivider(stringResource(R.string.ssh_authentication))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthTypeChip(stringResource(R.string.ssh_auth_password), sshAuthType == SshAuthType.PASSWORD, { onSshAuthTypeChange(SshAuthType.PASSWORD) }, Modifier.weight(1f))
                AuthTypeChip(stringResource(R.string.ssh_auth_key), sshAuthType == SshAuthType.PRIVATE_KEY, { onSshAuthTypeChange(SshAuthType.PRIVATE_KEY) }, Modifier.weight(1f))
            }
            AnimatedVisibility(visible = sshAuthType == SshAuthType.PRIVATE_KEY, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = sshPrivateKey, onValueChange = onSshPrivateKeyChange,
                        label = { Text(stringResource(R.string.ssh_private_key), color = CometTail) },
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----", color = CometTail.copy(alpha = 0.6f)) },
                        minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PulsarCyan, unfocusedBorderColor = InputBorder,
                            focusedLabelColor = PulsarCyan, cursorColor = PulsarCyan,
                            focusedTextColor = StarDust, unfocusedTextColor = StarDust,
                            focusedContainerColor = InputBg, unfocusedContainerColor = InputBg,
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    SpaceTextField(sshKeyPassphrase, onSshKeyPassphraseChange, stringResource(R.string.ssh_key_passphrase), Icons.Outlined.Key)
                    GenerateKeyPairButton(
                        passphrase     = sshKeyPassphrase,
                        onKeyGenerated = onSshPrivateKeyChange,
                    )
                }
            }
        }
        // FTP/FTPS-STANDALONE FEATURE: security mode (Plain / FTPS explicit
        // AUTH TLS / FTPS implicit port 990 — see RdpProfile.ftpSecurity's
        // doc comment) plus the passive-mode toggle (almost always wanted,
        // hence defaulting on — see RdpProfile.ftpPassiveMode's doc
        // comment). Host/port/username/password above still apply
        // unchanged, same "generic fields section covers the rest" pattern
        // SPICE/RTSP use.
        ProtocolType.FTP, ProtocolType.FTPS -> {
            SectionDivider(stringResource(R.string.ftp_security))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthTypeChip(
                    stringResource(R.string.ftp_security_plain),
                    ftpSecurity == com.systemsgo.hex.transfer.FtpSecurity.PLAIN.name,
                    { onFtpSecurityChange(com.systemsgo.hex.transfer.FtpSecurity.PLAIN.name) },
                    Modifier.weight(1f),
                )
                AuthTypeChip(
                    stringResource(R.string.ftp_security_explicit),
                    ftpSecurity == com.systemsgo.hex.transfer.FtpSecurity.FTPS_EXPLICIT.name,
                    { onFtpSecurityChange(com.systemsgo.hex.transfer.FtpSecurity.FTPS_EXPLICIT.name) },
                    Modifier.weight(1f),
                )
                AuthTypeChip(
                    stringResource(R.string.ftp_security_implicit),
                    ftpSecurity == com.systemsgo.hex.transfer.FtpSecurity.FTPS_IMPLICIT.name,
                    { onFtpSecurityChange(com.systemsgo.hex.transfer.FtpSecurity.FTPS_IMPLICIT.name) },
                    Modifier.weight(1f),
                )
            }
            SpaceSwitch(
                label   = stringResource(R.string.ftp_passive_mode),
                checked = ftpPassiveMode,
                onCheckedChange = onFtpPassiveModeChange,
            )
        }
        // SMB-STANDALONE FEATURE: share name (required — see
        // RdpProfile.smbShare's doc comment, SMB has no "browse the whole
        // server" mode) and optional domain/workgroup. Host/port/username/
        // password above still apply unchanged.
        ProtocolType.SMB -> {
            SectionDivider(stringResource(R.string.smb_title))
            SpaceTextField(smbShare, onSmbShareChange, stringResource(R.string.smb_share), Icons.Outlined.Folder)
            SpaceTextField(smbDomain, onSmbDomainChange, stringResource(R.string.smb_domain), Icons.Outlined.Domain)
        }
        // WEBDAV-STANDALONE FEATURE: identified by its base URL, not
        // host/port (see RdpProfile.webdavBaseUrl's doc comment — host/port
        // are bypassed in canSave above via isWebdav, same idea as isWeb's
        // webUrl). Username/password above still apply (HTTP Basic/Digest
        // auth against the DAV server).
        ProtocolType.WEBDAV -> {
            SectionDivider(stringResource(R.string.webdav_title))
            SpaceTextField(
                value         = webdavBaseUrl,
                onValueChange = onWebdavBaseUrlChange,
                label         = stringResource(R.string.webdav_server_url),
                icon          = Icons.Outlined.Language,
            )
            Text(
                text  = stringResource(R.string.webdav_server_url_placeholder),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // NFS-STANDALONE FEATURE: export path (required — see
        // RdpProfile.nfsExportPath's doc comment), numeric uid/gid this
        // client presents to the server's AUTH_SYS check, and the optional
        // mountd port override (blank = auto-detect via the portmapper —
        // see RdpProfile.nfsMountdPort's doc comment). NFSv3/AUTH_SYS has no
        // username/password of its own — the generic Authentication fields
        // above still render, but they're simply unused for this protocol.
        ProtocolType.NFS -> {
            SectionDivider(stringResource(R.string.nfs_title))
            SpaceTextField(
                value         = nfsExportPath,
                onValueChange = onNfsExportPathChange,
                label         = stringResource(R.string.nfs_export_path),
                icon          = Icons.Outlined.Folder,
            )
            Text(
                text  = stringResource(R.string.nfs_export_path_hint),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpaceTextField(
                    value         = nfsUid,
                    onValueChange = { onNfsUidChange(it.filter(Char::isDigit)) },
                    label         = stringResource(R.string.nfs_uid),
                    icon          = Icons.Outlined.Person,
                    keyboardType  = KeyboardType.Number,
                    modifier      = Modifier.weight(1f),
                )
                SpaceTextField(
                    value         = nfsGid,
                    onValueChange = { onNfsGidChange(it.filter(Char::isDigit)) },
                    label         = stringResource(R.string.nfs_gid),
                    icon          = Icons.Outlined.Person,
                    keyboardType  = KeyboardType.Number,
                    modifier      = Modifier.weight(1f),
                )
            }
            SpaceTextField(
                value         = nfsMountdPort,
                onValueChange = { onNfsMountdPortChange(it.filter(Char::isDigit)) },
                label         = stringResource(R.string.nfs_mountd_port),
                icon          = Icons.Outlined.SettingsEthernet,
                keyboardType  = KeyboardType.Number,
            )
            Text(
                text  = stringResource(R.string.nfs_mountd_port_hint),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text  = stringResource(R.string.nfs_auth_notice),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ProtocolType.TELNET -> {
            SectionDivider(stringResource(R.string.telnet_security))
            SpaceSwitch(
                label   = stringResource(R.string.telnet_use_tls),
                checked = telnetUseTls,
                onCheckedChange = onTelnetUseTlsChange
            )
            Text(
                text  = stringResource(R.string.telnet_use_tls_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            // CLEARTEXT-WARNING FIX: telnetUseTls defaults to false and
            // plenty of real Telnet servers (network gear, lab equipment)
            // only ever speak plain Telnet, so this is the common case, not
            // an edge case — worth a visible warning rather than leaving it
            // implied by telnet_use_tls_desc's wording alone. Only shown
            // while TLS is off; once the switch above is on, the warning
            // above already covers it.
            AnimatedVisibility(visible = !telnetUseTls, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(
                    text  = stringResource(R.string.telnet_cleartext_warning),
                    color = SolarFlare,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        // RLOGIN FEATURE: the two handshake fields RFC 1282 needs beyond the
        // generic host/port/username section above — see
        // RdpProfile.rloginRemoteUsername/rloginTerminalType's doc comments.
        ProtocolType.RLOGIN -> {
            SectionDivider(stringResource(R.string.rlogin_section_title))
            // CLEARTEXT-WARNING FIX: unlike Telnet, Rlogin has no TLS option
            // at all in this app (or in RFC 1282 itself) — it is always
            // plain text, including the trust-based auto-login username
            // exchange below. Shown unconditionally, not behind any toggle.
            Text(
                text  = stringResource(R.string.rlogin_cleartext_warning),
                color = SolarFlare,
                style = MaterialTheme.typography.bodySmall
            )
            SpaceTextField(
                value          = rloginRemoteUsername,
                onValueChange  = onRloginRemoteUsernameChange,
                label          = stringResource(R.string.rlogin_remote_username),
                icon           = Icons.Outlined.Person,
            )
            Text(
                text  = stringResource(R.string.rlogin_remote_username_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            SpaceTextField(
                value          = rloginTerminalType,
                onValueChange  = onRloginTerminalTypeChange,
                label          = stringResource(R.string.rlogin_terminal_type),
                icon           = Icons.Outlined.SettingsEthernet,
            )
            // COMPILE-BREAK FIX: this Text() was truncated mid-edit — open
            // paren with no arguments and no closing paren, so the branch's
            // closing `}` below was actually being parsed as this call's
            // argument list. Restored using the matching
            // rlogin_terminal_type_desc string (already present in
            // strings.xml/values-ar, just never wired up), same
            // label+field+description triplet pattern every other field in
            // this section uses.
            Text(
                text  = stringResource(R.string.rlogin_terminal_type_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // MOSH FEATURE: same auth-type picker as SSH (the bootstrap phase is
        // plain SSH exec of mosh-server — see RemoteSessionFactory's MOSH
        // branch and MoshSessionManager), plus the handful of mosh-server
        // flags with no SSH/RDP/VNC equivalent. See RdpProfile's
        // MOSH-specific fields doc comment for what each one maps to.
        ProtocolType.MOSH -> {
            SectionDivider(stringResource(R.string.ssh_authentication))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthTypeChip(stringResource(R.string.ssh_auth_password), sshAuthType == SshAuthType.PASSWORD, { onSshAuthTypeChange(SshAuthType.PASSWORD) }, Modifier.weight(1f))
                AuthTypeChip(stringResource(R.string.ssh_auth_key), sshAuthType == SshAuthType.PRIVATE_KEY, { onSshAuthTypeChange(SshAuthType.PRIVATE_KEY) }, Modifier.weight(1f))
            }
            AnimatedVisibility(visible = sshAuthType == SshAuthType.PRIVATE_KEY, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = sshPrivateKey, onValueChange = onSshPrivateKeyChange,
                        label = { Text(stringResource(R.string.ssh_private_key), color = CometTail) },
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----", color = CometTail.copy(alpha = 0.6f)) },
                        minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PulsarCyan, unfocusedBorderColor = InputBorder,
                            focusedLabelColor = PulsarCyan, cursorColor = PulsarCyan,
                            focusedTextColor = StarDust, unfocusedTextColor = StarDust,
                            focusedContainerColor = InputBg, unfocusedContainerColor = InputBg,
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    SpaceTextField(sshKeyPassphrase, onSshKeyPassphraseChange, stringResource(R.string.ssh_key_passphrase), Icons.Outlined.Key)
                }
            }

            SectionDivider(stringResource(R.string.mosh_settings_title))
            SpaceTextField(
                value          = moshRemoteServerCommand,
                onValueChange  = onMoshRemoteServerCommandChange,
                label          = stringResource(R.string.mosh_remote_server_command),
                icon           = Icons.Outlined.Terminal,
            )
            Text(
                text  = stringResource(R.string.mosh_remote_server_command_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            SpaceTextField(
                value          = moshUdpPortRange,
                onValueChange  = onMoshUdpPortRangeChange,
                label          = stringResource(R.string.mosh_udp_port_range),
                icon           = Icons.Outlined.Router,
            )
            Text(
                text  = stringResource(R.string.mosh_udp_port_range_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            SpaceTextField(
                value          = moshRemoteLocale,
                onValueChange  = onMoshRemoteLocaleChange,
                label          = stringResource(R.string.mosh_remote_locale),
                icon           = Icons.Outlined.Language,
            )
            Text(
                text  = stringResource(R.string.mosh_remote_locale_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text  = stringResource(R.string.mosh_color_mode),
                color = StarDust,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(8, 16, 88, 256).forEach { mode ->
                    AuthTypeChip(mode.toString(), moshColorMode == mode, { onMoshColorModeChange(mode) }, Modifier.weight(1f))
                }
            }

            Text(
                text  = stringResource(R.string.mosh_prediction_mode),
                color = StarDust,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthTypeChip(stringResource(R.string.mosh_prediction_adaptive), moshPredictionMode == MoshPredictionMode.ADAPTIVE.name, { onMoshPredictionModeChange(MoshPredictionMode.ADAPTIVE.name) }, Modifier.weight(1f))
                AuthTypeChip(stringResource(R.string.mosh_prediction_always), moshPredictionMode == MoshPredictionMode.ALWAYS.name, { onMoshPredictionModeChange(MoshPredictionMode.ALWAYS.name) }, Modifier.weight(1f))
                AuthTypeChip(stringResource(R.string.mosh_prediction_never), moshPredictionMode == MoshPredictionMode.NEVER.name, { onMoshPredictionModeChange(MoshPredictionMode.NEVER.name) }, Modifier.weight(1f))
            }
            Text(
                text  = stringResource(R.string.mosh_prediction_mode_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // SPICE-PROTOCOL FEATURE (Part 1/N): no dedicated form fields yet —
        // host/port/password from the generic section above are all this
        // protocol uses for now (no TLS port, no separate SPICE password
        // field). Intentionally empty until Part 4/N adds SpiceSessionClient
        // and any SPICE-specific profile columns it needs.
        ProtocolType.SPICE -> {}
        // VIRTUALBOX-VRDE FEATURE (Part 1/N → Part 2/N): host/port/username/
        // password/domain/acceptSelfSignedCertificate above still apply
        // (reused as-is from the generic RDP-shaped fields — see
        // ProtocolType.VIRTUALBOX_VRDE's doc comment). vrdeAuthType/
        // vrdeMultiConnectionAllowed are purely informational (see
        // VrdeAuthType's doc comment and
        // com.systemsgo.hex.virtualbox.VirtualBoxVrdeCredentials) — they
        // describe how the *host* VM's VRDE is configured so the user isn't
        // guessing, they don't change what this client actually sends.
        ProtocolType.VIRTUALBOX_VRDE -> {
            SectionDivider(stringResource(R.string.vrde_section_title))
            Text(
                text  = stringResource(R.string.vrde_auth_type_label),
                color = StarDust,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthTypeChip(stringResource(R.string.vrde_auth_type_null), vrdeAuthType == VrdeAuthType.NULL_AUTH.name, { onVrdeAuthTypeChange(VrdeAuthType.NULL_AUTH.name) }, Modifier.weight(1f))
                AuthTypeChip(stringResource(R.string.vrde_auth_type_external), vrdeAuthType == VrdeAuthType.EXTERNAL.name, { onVrdeAuthTypeChange(VrdeAuthType.EXTERNAL.name) }, Modifier.weight(1f))
                AuthTypeChip(stringResource(R.string.vrde_auth_type_guest), vrdeAuthType == VrdeAuthType.GUEST.name, { onVrdeAuthTypeChange(VrdeAuthType.GUEST.name) }, Modifier.weight(1f))
            }
            Text(
                text  = stringResource(R.string.vrde_auth_type_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
            SpaceSwitch(
                label   = stringResource(R.string.vrde_multi_connection_label),
                checked = vrdeMultiConnectionAllowed,
                onCheckedChange = onVrdeMultiConnectionAllowedChange,
                subtitle = stringResource(R.string.vrde_multi_connection_desc),
            )
        }
        // VMWARE-VSPHERE FEATURE (Part 1/N → Part 2/N): host/port above are
        // the vCenter/ESXi API endpoint, username/password the vSphere
        // account — both from the generic fields section (see
        // ProtocolType.VMWARE_VSPHERE's doc comment). vsphereApiMode picks
        // REST vs SOAP (see that enum's doc comment); the self-signed
        // toggle mirrors proxmoxAcceptSelfSignedCertificate/
        // acceptSelfSignedCertificate elsewhere on this screen since
        // vCenter/ESXi is overwhelmingly self-signed out of the box;
        // vsphereDatacenter is optional (blank = every datacenter the
        // account can see).
        ProtocolType.VMWARE_VSPHERE -> {
            SectionDivider(stringResource(R.string.vsphere_section_title))
            Text(
                text  = stringResource(R.string.vsphere_api_mode_label),
                color = StarDust,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthTypeChip(stringResource(R.string.vsphere_api_mode_rest), vsphereApiMode == VSphereApiMode.REST.name, { onVsphereApiModeChange(VSphereApiMode.REST.name) }, Modifier.weight(1f))
                AuthTypeChip(stringResource(R.string.vsphere_api_mode_soap), vsphereApiMode == VSphereApiMode.SOAP.name, { onVsphereApiModeChange(VSphereApiMode.SOAP.name) }, Modifier.weight(1f))
            }
            Text(
                text  = stringResource(R.string.vsphere_api_mode_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
            SpaceTextField(
                value         = vsphereDatacenter,
                onValueChange = onVsphereDatacenterChange,
                label         = stringResource(R.string.vsphere_datacenter_label),
                icon          = Icons.Outlined.Domain,
            )
            Text(
                text  = stringResource(R.string.vsphere_datacenter_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
            SpaceSwitch(
                label   = stringResource(R.string.web_trust_self_signed),
                checked = vsphereAcceptSelfSignedCertificate,
                onCheckedChange = onVsphereAcceptSelfSignedCertificateChange,
                subtitle = stringResource(R.string.vsphere_trust_self_signed_desc),
            )
        }
        // WAKE-ON-LAN-STANDALONE FEATURE: no protocol-specific fields here —
        // unlike every other branch above, a WAKE_ON_LAN profile's actual
        // configuration (MAC address, broadcast address, port, Wake & Connect
        // timing) lives in the always-expanded WolSection card rendered
        // separately in ProfileFormDialog (see the `hasToggle = protocolType
        // != ProtocolType.WAKE_ON_LAN` SettingsCard just above where this
        // composable is called), not in this generic per-protocol options
        // section — same "intentionally empty" shape as ProtocolType.SPICE
        // above.
        ProtocolType.WAKE_ON_LAN -> {}
        // RTSP FEATURE: stream path, transport mode, and TLS toggle —
        // the three RtspCredentials-facing fields RdpProfile carries for
        // this protocol (see rtspStreamPath/rtspTransportMode/rtspUseTls's
        // doc comments). Host/port/username/password above still apply,
        // same "generic fields section covers the rest" pattern SPICE/WEB
        // use.
        ProtocolType.RTSP -> {
            SectionDivider(stringResource(R.string.rtsp_section_title))
            SpaceTextField(
                value         = rtspStreamPath,
                onValueChange = onRtspStreamPathChange,
                label         = stringResource(R.string.rtsp_stream_path_label),
                icon          = Icons.Outlined.Videocam,
            )
            Text(
                text  = stringResource(R.string.rtsp_stream_path_hint),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthTypeChip(
                    stringResource(R.string.rtsp_transport_tcp),
                    rtspTransportMode == com.systemsgo.hex.rtsp.protocol.RtspTransportMode.TCP_INTERLEAVED.name,
                    { onRtspTransportModeChange(com.systemsgo.hex.rtsp.protocol.RtspTransportMode.TCP_INTERLEAVED.name) },
                    Modifier.weight(1f),
                )
                AuthTypeChip(
                    stringResource(R.string.rtsp_transport_udp),
                    rtspTransportMode == com.systemsgo.hex.rtsp.protocol.RtspTransportMode.UDP.name,
                    { onRtspTransportModeChange(com.systemsgo.hex.rtsp.protocol.RtspTransportMode.UDP.name) },
                    Modifier.weight(1f),
                )
            }
            SpaceSwitch(
                label = stringResource(R.string.rtsp_use_tls),
                checked = rtspUseTls,
                onCheckedChange = onRtspUseTlsChange,
                subtitle = stringResource(R.string.rtsp_use_tls_subtitle),
            )
        }
        // MODBUS-TCP FEATURE (Part 2/2): unit id / timeouts / retries /
        // poll interval — the register map itself (modbusPoints) is edited
        // later, from ModbusManagementScreen's Points tab, not here (see
        // this function's modbusUnitId param doc above).
        ProtocolType.MODBUS_TCP -> {
            SectionDivider("Modbus TCP")
            SpaceTextField(
                value         = modbusUnitId,
                onValueChange = { onModbusUnitIdChange(it.filter(Char::isDigit)) },
                label         = "Unit ID (0-255)",
                icon          = Icons.Outlined.Numbers,
                keyboardType  = KeyboardType.Number,
            )
            Text(
                text  = "The slave/device address a gateway forwards to. Talking to a native Modbus/TCP device directly usually ignores this — leave at 1 if unsure.",
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
            SpaceTextField(
                value         = modbusConnectTimeoutMs,
                onValueChange = { onModbusConnectTimeoutMsChange(it.filter(Char::isDigit)) },
                label         = "Connect timeout (ms)",
                icon          = Icons.Outlined.Timer,
                keyboardType  = KeyboardType.Number,
            )
            SpaceTextField(
                value         = modbusResponseTimeoutMs,
                onValueChange = { onModbusResponseTimeoutMsChange(it.filter(Char::isDigit)) },
                label         = "Response timeout (ms)",
                icon          = Icons.Outlined.Timer,
                keyboardType  = KeyboardType.Number,
            )
            SpaceTextField(
                value         = modbusRetries,
                onValueChange = { onModbusRetriesChange(it.filter(Char::isDigit)) },
                label         = "Retries",
                icon          = Icons.Outlined.Replay,
                keyboardType  = KeyboardType.Number,
            )
            SpaceTextField(
                value         = modbusPollIntervalMs,
                onValueChange = { onModbusPollIntervalMsChange(it.filter(Char::isDigit)) },
                label         = "Dashboard poll interval (ms)",
                icon          = Icons.Outlined.Sync,
                keyboardType  = KeyboardType.Number,
            )
            Text(
                text  = "Register points for the live dashboard are added from the Modbus session screen once this connection is saved.",
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // WEB-PORTAL FEATURE: the only protocol-specific fields a Web/HTTPS
        // profile needs — the portal URL itself, plus the safe-by-default
        // opt-ins covering TLS trust and the two separate autofill surfaces
        // (see RdpProfile.webTrustSelfSignedCertificate/webAutoFillHttpAuth/
        // webAutoFillLoginForm's doc comments). Host/port/username/password
        // above still apply (the generic fields section) — username/password
        // only matter here if one of the two autofill toggles is on.
        ProtocolType.WEB -> {
            SectionDivider(stringResource(R.string.web_protocol_label))
            SpaceTextField(
                value         = webUrl,
                onValueChange = onWebUrlChange,
                label         = stringResource(R.string.web_url_label),
                icon          = Icons.Outlined.Web,
            )
            SpaceSwitch(
                label   = stringResource(R.string.web_autofill_http_auth),
                checked = webAutoFillHttpAuth,
                onCheckedChange = onWebAutoFillHttpAuthChange
            )
            Text(
                text  = stringResource(R.string.web_autofill_http_auth_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            SpaceSwitch(
                label   = stringResource(R.string.web_autofill_login_form),
                checked = webAutoFillLoginForm,
                onCheckedChange = onWebAutoFillLoginFormChange
            )
            Text(
                text  = stringResource(R.string.web_autofill_login_form_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            SpaceSwitch(
                label   = stringResource(R.string.web_trust_self_signed),
                checked = webTrustSelfSignedCertificate,
                onCheckedChange = onWebTrustSelfSignedCertificateChange
            )
            Text(
                text  = stringResource(R.string.web_trust_self_signed_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // REDFISH-IPMI FEATURE: Redfish is plain HTTPS REST, so it reuses
        // the same self-signed-certificate trust toggle RDP has — BMC web
        // UIs/APIs are overwhelmingly self-signed out of the box.
        ProtocolType.REDFISH -> {
            SectionDivider("Redfish")
            SpaceSwitch(
                label   = stringResource(R.string.web_trust_self_signed),
                checked = acceptSelfSignedCertificate,
                onCheckedChange = onAcceptSelfSignedCertificateChange
            )
            Text(
                text  = stringResource(R.string.redfish_trust_self_signed_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // IPMI has no certificate to trust (RMCP+/RAKP has its own
        // password-derived session keys — see IpmiClient's kdoc) but does
        // need the requested privilege level, since Chassis Control (power)
        // requires Administrator on most BMCs.
        ProtocolType.IPMI -> {
            // SECURITY FIX: the Kg key field below is a password-like secret —
            // see security/SecureScreen.kt.
            com.systemsgo.hex.security.SecureScreen()
            SectionDivider("IPMI")
            Text(
                text  = stringResource(R.string.bmc_ipmi_privilege_level_label),
                color = CometTail,
                style = MaterialTheme.typography.labelMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("USER", "OPERATOR", "ADMINISTRATOR").forEach { level ->
                    AuthTypeChip(
                        label    = level.lowercase().replaceFirstChar { it.uppercase() },
                        selected = ipmiPrivilegeLevel == level,
                        onClick  = { onIpmiPrivilegeLevelChange(level) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ipmiKgKey, onValueChange = onIpmiKgKeyChange,
                label = { Text(stringResource(R.string.bmc_kg_key_optional)) },
                placeholder = { Text(stringResource(R.string.bmc_kg_key_hint)) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text  = stringResource(R.string.bmc_kg_key_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // AMT-VPRO FEATURE: unlike REDFISH/IPMI, AMT's plain-vs-TLS choice
        // isn't implied by the port the way HTTP/HTTPS-on-80/443 is (both
        // 16992 and 16993 are already nonstandard), so it needs its own
        // switch — see RdpProfile.amtUseTls's doc comment. The self-signed
        // toggle only matters once TLS is actually on, same conditional
        // shape [ProtocolType.WEB]'s webTrustSelfSignedCertificate uses.
        //
        // AUDIT FIX: this branch was previously missing entirely, so the
        // amtUseTls switch declared above was never actually rendered —
        // there was no way to turn TLS on from this screen. Also adds the
        // explicit CIRA disclosure called for by the audit report: this
        // client only reaches AMT/vPro over a direct LAN connection, not
        // through a CIRA/MPS relay, so a device behind NAT/off the local
        // network won't be reachable — better to say so here than let the
        // user discover it as a silent connection failure.
        ProtocolType.AMT -> {
            SectionDivider("Intel AMT / vPro")
            // AMT-VPRO FEATURE — Phase 6 (CIRA setup UI): this switch is the
            // one that used to be permanently unavailable per
            // amt_cira_limitation_desc below — see RdpProfile.ciraEnabled's
            // doc comment for what turning it on actually changes (relay
            // address + device ID replace this device's own host/port as
            // the profile's addressing). Shown above amtUseTls/self-signed
            // since those two only apply to a *direct* connection.
            SpaceSwitch(
                label   = stringResource(R.string.amt_cira_enabled),
                checked = ciraEnabled,
                onCheckedChange = onCiraEnabledChange
            )
            Text(
                text  = stringResource(R.string.amt_cira_enabled_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            if (ciraEnabled) {
                Spacer(Modifier.height(8.dp))
                SpaceTextField(
                    value = ciraRelayHost, onValueChange = onCiraRelayHostChange,
                    label = stringResource(R.string.amt_cira_relay_host),
                    icon  = Icons.Outlined.Hub,
                )
                SpaceTextField(
                    value = ciraRelayPort, onValueChange = onCiraRelayPortChange,
                    label = stringResource(R.string.amt_cira_relay_port),
                    icon  = Icons.Outlined.SettingsEthernet,
                    keyboardType = KeyboardType.Number,
                )
                SpaceTextField(
                    value = ciraDeviceId, onValueChange = onCiraDeviceIdChange,
                    label = stringResource(R.string.amt_cira_device_id),
                    icon  = Icons.Outlined.Devices,
                )
                Text(
                    text  = stringResource(R.string.amt_cira_device_id_hint),
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall
                )
                SpaceTextField(
                    value = ciraRelayUsername, onValueChange = onCiraRelayUsernameChange,
                    label = stringResource(R.string.amt_cira_relay_username),
                    icon  = Icons.Outlined.Person,
                )
                OutlinedTextField(
                    value = ciraRelayPassword, onValueChange = onCiraRelayPasswordChange,
                    label = { Text(stringResource(R.string.amt_cira_relay_password)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                // AMT-VPRO FEATURE — Phase 6, Part 3: see
                // RdpProfile.ciraRelayUseTls's doc comment. Placed with the
                // rest of the relay-address fields above (host/port/device
                // id/credentials) since it's a property of *this* app's
                // connection to the relay, same as those — not of the
                // relay<->device APF hop, which is unaffected by it.
                SpaceSwitch(
                    label   = stringResource(R.string.amt_cira_relay_use_tls),
                    checked = ciraRelayUseTls,
                    onCheckedChange = onCiraRelayUseTlsChange
                )
                Text(
                    text  = stringResource(R.string.amt_cira_relay_use_tls_desc),
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall
                )
                // AMT-VPRO FEATURE — Phase 6, Part 4: general WS-Man
                // device management (identity, power control, boot device,
                // audit/access logs) now works over CIRA the same as
                // SOL/KVM/IDE-R redirection does — see AMT_VPRO_ROADMAP.md's
                // Phase 6 Part 4 section. The one remaining gap is TLS: if
                // "Use TLS" below is on for this profile, WS-Man/redirection
                // over CIRA isn't supported yet (whether a CIRA-forwarded
                // channel needs a second TLS handshake on top of the relay
                // connection isn't confirmed) — surface that as a note here
                // too, not just as a connect-time error.
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = stringResource(R.string.amt_cira_status_note),
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall
                )
                if (amtUseTls) {
                    Text(
                        text  = stringResource(R.string.amt_cira_tls_not_supported),
                        color = ConnectingAmber,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                SpaceSwitch(
                    label   = stringResource(R.string.amt_use_tls),
                    checked = amtUseTls,
                    onCheckedChange = onAmtUseTlsChange
                )
                if (amtUseTls) {
                    SpaceSwitch(
                        label   = stringResource(R.string.web_trust_self_signed),
                        checked = acceptSelfSignedCertificate,
                        onCheckedChange = onAcceptSelfSignedCertificateChange
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = stringResource(R.string.amt_cira_limitation_desc),
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        // SERIAL-CONSOLE FEATURE (Part 1/N): transport-mode picker (mirrors
        // the RDP-redirect feature's own LOCAL_DEVICE/RAW_TCP/RFC_2217 chips
        // further up this file) plus, for RFC_2217 only, the serial-line
        // parameters this client sends at connect time — see
        // SerialConsoleClient's class doc for why RAW_TCP has no equivalent
        // fields (nothing on this end to negotiate). LOCAL_DEVICE is fully
        // implemented (see SerialConsoleClient.connectLocalDevice and
        // UsbSerialProbe) and selectable here like the other two modes —
        // it is not a stub/disabled option.
        ProtocolType.SERIAL_CONSOLE -> {
            SectionDivider(stringResource(R.string.serial_console_section_title))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthTypeChip(
                    label    = com.systemsgo.hex.data.model.SerialRedirectMode.RFC_2217.label,
                    selected = serialConsoleTransport == com.systemsgo.hex.data.model.SerialRedirectMode.RFC_2217,
                    onClick  = { onSerialConsoleTransportChange(com.systemsgo.hex.data.model.SerialRedirectMode.RFC_2217) }
                )
                AuthTypeChip(
                    label    = com.systemsgo.hex.data.model.SerialRedirectMode.RAW_TCP.label,
                    selected = serialConsoleTransport == com.systemsgo.hex.data.model.SerialRedirectMode.RAW_TCP,
                    onClick  = { onSerialConsoleTransportChange(com.systemsgo.hex.data.model.SerialRedirectMode.RAW_TCP) }
                )
                AuthTypeChip(
                    label    = stringResource(R.string.serial_console_usb_device),
                    selected = serialConsoleTransport == com.systemsgo.hex.data.model.SerialRedirectMode.LOCAL_DEVICE,
                    onClick  = { onSerialConsoleTransportChange(com.systemsgo.hex.data.model.SerialRedirectMode.LOCAL_DEVICE) }
                )
            }
            when (serialConsoleTransport) {
                com.systemsgo.hex.data.model.SerialRedirectMode.RFC_2217 -> {
                    Text(
                        text  = stringResource(R.string.serial_console_rfc2217_desc),
                        color = CometTail,
                        style = MaterialTheme.typography.bodySmall
                    )
                    SpaceTextField(
                        value          = serialConsoleBaudRate,
                        onValueChange  = { v -> onSerialConsoleBaudRateChange(v.filter { it.isDigit() }.take(7)) },
                        label          = stringResource(R.string.serial_console_baud_rate),
                        icon           = Icons.Outlined.Speed,
                        keyboardType   = androidx.compose.ui.text.input.KeyboardType.Number,
                    )
                    Text(stringResource(R.string.serial_console_data_bits), color = CometTail, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 6, 7, 8).forEach { bits ->
                            AuthTypeChip(
                                label    = bits.toString(),
                                selected = serialConsoleDataBits == bits,
                                onClick  = { onSerialConsoleDataBitsChange(bits) }
                            )
                        }
                    }
                    Text(stringResource(R.string.serial_console_parity), color = CometTail, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.systemsgo.hex.data.model.SerialParity.entries.forEach { p ->
                            AuthTypeChip(
                                label    = p.label,
                                selected = serialConsoleParity == p,
                                onClick  = { onSerialConsoleParityChange(p) }
                            )
                        }
                    }
                    Text(stringResource(R.string.serial_console_stop_bits), color = CometTail, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.systemsgo.hex.data.model.SerialStopBits.entries.forEach { s ->
                            AuthTypeChip(
                                label    = s.label,
                                selected = serialConsoleStopBits == s,
                                onClick  = { onSerialConsoleStopBitsChange(s) }
                            )
                        }
                    }
                }
                com.systemsgo.hex.data.model.SerialRedirectMode.RAW_TCP -> {
                    Text(
                        text  = stringResource(R.string.serial_console_raw_tcp_desc),
                        color = CometTail,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                com.systemsgo.hex.data.model.SerialRedirectMode.LOCAL_DEVICE -> {
                    Text(
                        text  = stringResource(R.string.serial_console_local_device_desc),
                        color = CometTail,
                        style = MaterialTheme.typography.bodySmall
                    )
                    SpaceTextField(
                        value          = serialConsoleDevicePath,
                        onValueChange  = onSerialConsoleDevicePathChange,
                        label          = stringResource(R.string.serial_console_device_path),
                        icon           = Icons.Outlined.Usb,
                    )
                    SpaceSwitch(
                        label   = stringResource(R.string.serial_console_hardware_flow_control),
                        checked = serialConsoleHardwareFlowControl,
                        onCheckedChange = onSerialConsoleHardwareFlowControlChange,
                        subtitle = stringResource(R.string.serial_console_hardware_flow_control_desc),
                    )
                }
            }
        }
        // SNMP FEATURE: version picker (v1/v2c share the same plaintext
        // "community" auth model; v3 replaces it with USM — username +
        // security level + auth/priv protocol+passphrase). See
        // SnmpCredentials in com.systemsgo.hex.snmp.protocol.SnmpModels.kt
        // for exactly how each of these maps onto the wire.
        ProtocolType.SNMP -> {
            SectionDivider("SNMP")
            SnmpCredentialFields(
                snmpVersion = snmpVersion, onSnmpVersionChange = onSnmpVersionChange,
                snmpPort = snmpPort, onSnmpPortChange = onSnmpPortChange,
                snmpCommunity = snmpCommunity, onSnmpCommunityChange = onSnmpCommunityChange,
                snmpV3Username = snmpV3Username, onSnmpV3UsernameChange = onSnmpV3UsernameChange,
                snmpV3SecurityLevel = snmpV3SecurityLevel, onSnmpV3SecurityLevelChange = onSnmpV3SecurityLevelChange,
                snmpV3AuthProtocol = snmpV3AuthProtocol, onSnmpV3AuthProtocolChange = onSnmpV3AuthProtocolChange,
                snmpV3AuthPassphrase = snmpV3AuthPassphrase, onSnmpV3AuthPassphraseChange = onSnmpV3AuthPassphraseChange,
                snmpV3PrivProtocol = snmpV3PrivProtocol, onSnmpV3PrivProtocolChange = onSnmpV3PrivProtocolChange,
                snmpV3PrivPassphrase = snmpV3PrivPassphrase, onSnmpV3PrivPassphraseChange = onSnmpV3PrivPassphraseChange,
                snmpV3ContextName = snmpV3ContextName, onSnmpV3ContextNameChange = onSnmpV3ContextNameChange,
            )
        }

        // NETCONF FEATURE: the full Configuration Screen for a NETCONF
        // profile. Connection identity/host/port/username and auth
        // (password/private key/passphrase — the sshAuthType/sshPrivateKey/
        // sshKeyPassphrase fields further up this same when-expression's
        // ProtocolType.SSH branch's neighborhood) are shared, generic
        // controls already rendered above this section for every protocol —
        // this branch is only the NETCONF-specific extras: default
        // datastore, extra capabilities, keep-alive/timeout, compression,
        // and the security/advanced group.
        ProtocolType.NETCONF -> {
            SectionDivider("NETCONF")

            Text(
                text  = stringResource(R.string.netconf_default_datastore_label),
                color = CometTail,
                style = MaterialTheme.typography.labelMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("running", "candidate", "startup").forEach { ds ->
                    AuthTypeChip(
                        label    = ds.replaceFirstChar { it.uppercase() },
                        selected = netconfDefaultDatastore == ds,
                        onClick  = { onNetconfDefaultDatastoreChange(ds) }
                    )
                }
            }
            Text(
                text  = stringResource(R.string.netconf_default_datastore_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(4.dp))
            SpaceTextField(
                value          = netconfExtraCapabilities,
                onValueChange  = onNetconfExtraCapabilitiesChange,
                label          = stringResource(R.string.netconf_extra_capabilities),
                icon           = Icons.Outlined.Extension,
            )
            Text(
                text  = stringResource(R.string.netconf_extra_capabilities_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpaceTextField(
                    value          = netconfKeepAliveMs,
                    onValueChange  = { onNetconfKeepAliveMsChange(it.normalizeDigits().filter(Char::isDigit)) },
                    label          = stringResource(R.string.netconf_keep_alive_ms),
                    icon           = Icons.Outlined.Timer,
                    keyboardType   = KeyboardType.Number,
                    modifier       = Modifier.weight(1f),
                )
                SpaceTextField(
                    value          = netconfConnectTimeoutMs,
                    onValueChange  = { onNetconfConnectTimeoutMsChange(it.normalizeDigits().filter(Char::isDigit)) },
                    label          = stringResource(R.string.netconf_connect_timeout_ms),
                    icon           = Icons.Outlined.HourglassEmpty,
                    keyboardType   = KeyboardType.Number,
                    modifier       = Modifier.weight(1f),
                )
            }

            SpaceSwitch(
                label   = stringResource(R.string.netconf_enable_ssh_compression),
                checked = netconfCompressionEnabled,
                onCheckedChange = onNetconfCompressionEnabledChange
            )
            SpaceSwitch(
                label   = stringResource(R.string.netconf_automatic_reconnect),
                checked = netconfAutoReconnect,
                onCheckedChange = onNetconfAutoReconnectChange
            )

            // SECURITY / ADVANCED group — certificate auth (an OpenSSH
            // certificate blob, used alongside a private key when
            // sshAuthType == PRIVATE_KEY; see NetconfAuthMode's doc comment
            // for why this rides the same publickey auth path as a plain key).
            SectionDivider(stringResource(R.string.netconf_security_advanced_title))
            SpaceTextField(
                value          = netconfOpenSshCertificate,
                onValueChange  = onNetconfOpenSshCertificateChange,
                label          = stringResource(R.string.netconf_openssh_certificate_optional),
                icon           = Icons.Outlined.VerifiedUser,
            )
            Text(
                text  = stringResource(R.string.netconf_openssh_cert_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text  = stringResource(R.string.netconf_hostkey_verification_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )

            // CALL-HOME FEATURE (RFC 8071, Part 12): reverses which side
            // dials — see RdpProfile.netconfCallHomeEnabled's doc comment.
            SectionDivider(stringResource(R.string.netconf_call_home_title))
            SpaceSwitch(
                label   = stringResource(R.string.netconf_call_home_accept),
                checked = netconfCallHomeEnabled,
                onCheckedChange = onNetconfCallHomeEnabledChange
            )
            Text(
                text  = stringResource(R.string.netconf_call_home_accept_desc),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall
            )
            if (netconfCallHomeEnabled) {
                Spacer(Modifier.height(4.dp))
                // CALL-HOME-TLS FEATURE: RFC 8071 defines Call Home over
                // either SSH (netconf-ch-ssh, RFC 6242 framing over an SSH
                // subsystem channel) or TLS (netconf-ch-tls, RFC 7589
                // framing directly on the TLS record layer). Same chip
                // pattern as the "Default datastore" picker above.
                Text(
                    text  = stringResource(R.string.netconf_call_home_transport_label),
                    color = CometTail,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthTypeChip(label = "SSH", selected = netconfCallHomeTransport == "SSH", onClick = { onNetconfCallHomeTransportChange("SSH") })
                    AuthTypeChip(label = "TLS", selected = netconfCallHomeTransport == "TLS", onClick = { onNetconfCallHomeTransportChange("TLS") })
                }
                Spacer(Modifier.height(4.dp))
                SpaceTextField(
                    value          = netconfCallHomeListenPort,
                    onValueChange  = { onNetconfCallHomeListenPortChange(it.normalizeDigits().filter(Char::isDigit)) },
                    label          = stringResource(R.string.netconf_call_home_listen_port),
                    icon           = Icons.Outlined.SettingsInputAntenna,
                    keyboardType   = KeyboardType.Number,
                )
                Text(
                    text  = if (netconfCallHomeTransport == "TLS")
                        stringResource(R.string.netconf_call_home_port_desc_tls)
                    else
                        stringResource(R.string.netconf_call_home_port_desc_ssh),
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(4.dp))
                SpaceTextField(
                    value          = netconfCallHomeAllowedSourceHost,
                    onValueChange  = onNetconfCallHomeAllowedSourceHostChange,
                    label          = stringResource(R.string.netconf_call_home_expected_source_ip),
                    icon           = Icons.Outlined.Shield,
                )
                Text(
                    text  = stringResource(R.string.netconf_call_home_source_ip_desc),
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall
                )
                if (netconfCallHomeTransport == "TLS") {
                    Spacer(Modifier.height(4.dp))
                    SpaceTextField(
                        value          = netconfCallHomeTlsClientCertificatePem,
                        onValueChange  = onNetconfCallHomeTlsClientCertificatePemChange,
                        label          = stringResource(R.string.netconf_call_home_tls_client_certificate),
                        icon           = Icons.Outlined.VerifiedUser,
                    )
                    Text(
                        text  = stringResource(R.string.netconf_call_home_tls_cert_desc),
                        color = CometTail,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        // GUACAMOLE-PROTOCOL FEATURE: manual-entry fields, or filled by
        // GuacamoleConnectionPickerDialog via the Browse Connections button.
        // guacConnectionIdentifier is exactly what
        // GuacamoleConnection.identifier returns from
        // GuacamoleRepository.listConnections() — the picker fills these
        // same four fields instead of the user typing them.
        ProtocolType.GUACAMOLE -> {
            SectionDivider(stringResource(R.string.guacamole_section_title))
            SpaceTextField(
                value         = guacServerUrl,
                onValueChange = onGuacServerUrlChange,
                label         = stringResource(R.string.guacamole_server_url),
                icon          = Icons.Outlined.Link,
            )
            SpaceTextField(
                value         = guacDataSource,
                onValueChange = onGuacDataSourceChange,
                label         = stringResource(R.string.guacamole_data_source),
                icon          = Icons.Outlined.Storage,
            )
            OutlinedButton(
                onClick = onBrowseConnectionsClick,
                enabled = guacServerUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Cable, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.guacamole_browse_connections))
            }
            SpaceTextField(
                value         = guacConnectionIdentifier,
                onValueChange = onGuacConnectionIdentifierChange,
                label         = stringResource(R.string.guacamole_connection_identifier),
                icon          = Icons.Outlined.Cable,
            )
            SpaceTextField(
                value         = guacConnectionName,
                onValueChange = onGuacConnectionNameChange,
                label         = stringResource(R.string.guacamole_connection_name),
                icon          = Icons.Outlined.Label,
            )
            SpaceSwitch(
                label = stringResource(R.string.guacamole_remember_session),
                checked = guacRememberSession,
                onCheckedChange = onGuacRememberSessionChange,
                subtitle = stringResource(R.string.guacamole_remember_session_subtitle),
            )
            Text(
                text  = stringResource(R.string.guacamole_picker_not_yet_available),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
// List editor for RdpProfile.sshPortForwards — the equivalent of OpenSSH's
// `ssh -L`/`-R` static forwards. See SshPortForwardRule/SshPortForwardType
// and SshClient.connect() for the JSch wiring this configures.

@Composable
private fun SshPortForwardListEditor(
    rules: List<com.systemsgo.hex.data.model.SshPortForwardRule>,
    onRulesChange: (List<com.systemsgo.hex.data.model.SshPortForwardRule>) -> Unit,
) {
    var editingRule by remember { mutableStateOf<com.systemsgo.hex.data.model.SshPortForwardRule?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (rules.isEmpty()) {
            Text(
                text  = stringResource(R.string.ssh_port_forwarding_no_rules),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            rules.forEach { rule ->
                key(rule.id) {
                    SshPortForwardRuleRow(
                        rule     = rule,
                        onEdit   = { editingRule = rule },
                        onDelete = { onRulesChange(rules.filterNot { it.id == rule.id }) },
                    )
                }
            }
        }

        SpaceButton(
            text     = stringResource(R.string.ssh_port_forwarding_add_rule),
            onClick  = { showAddDialog = true },
            variant  = ButtonVariant.GHOST,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showAddDialog) {
        SshPortForwardRuleDialog(
            initial   = null,
            onDismiss = { showAddDialog = false },
            onSave    = { newRule ->
                onRulesChange(rules + newRule)
                showAddDialog = false
            },
        )
    }

    editingRule?.let { rule ->
        SshPortForwardRuleDialog(
            initial   = rule,
            onDismiss = { editingRule = null },
            onSave    = { updated ->
                onRulesChange(rules.map { if (it.id == rule.id) updated else it })
                editingRule = null
            },
        )
    }
}

@Composable
private fun SshPortForwardRuleRow(
    rule: com.systemsgo.hex.data.model.SshPortForwardRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isLocal = rule.type == com.systemsgo.hex.data.model.SshPortForwardType.LOCAL
    val summary = if (isLocal) {
        stringResource(R.string.ssh_port_forward_summary_local, rule.bindAddress, rule.listenPort, rule.destHost, rule.destPort)
    } else {
        stringResource(R.string.ssh_port_forward_summary_remote, rule.listenPort, rule.destHost, rule.destPort)
    }
    Surface(
        shape    = RoundedCornerShape(14.dp),
        color    = NebulaSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector        = if (isLocal) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                contentDescription = null,
                tint               = PulsarCyan,
                modifier           = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = if (isLocal) stringResource(R.string.ssh_port_forward_type_local)
                                 else stringResource(R.string.ssh_port_forward_type_remote),
                    color      = StarDust,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text  = summary,
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.ssh_port_forward_remove_rule),
                    tint = CometTail,
                )
            }
        }
    }
}

@Composable
private fun SshPortForwardRuleDialog(
    initial: com.systemsgo.hex.data.model.SshPortForwardRule?,
    onDismiss: () -> Unit,
    onSave: (com.systemsgo.hex.data.model.SshPortForwardRule) -> Unit,
) {
    var type by remember { mutableStateOf(initial?.type ?: com.systemsgo.hex.data.model.SshPortForwardType.LOCAL) }
    var bindAddress by remember { mutableStateOf(initial?.bindAddress ?: "127.0.0.1") }
    var listenPort by remember { mutableStateOf(initial?.listenPort?.takeIf { it != 0 }?.toString() ?: "") }
    var destHost by remember { mutableStateOf(initial?.destHost ?: "") }
    var destPort by remember { mutableStateOf(initial?.destPort?.takeIf { it != 0 }?.toString() ?: "") }

    val listenPortValid = isPortInRange(listenPort)
    val destPortValid = isPortInRange(destPort)
    val canSave = listenPortValid && destPortValid && destHost.isNotBlank() && bindAddress.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = StarfieldSurface,
        shape            = RoundedCornerShape(20.dp),
        icon  = { Icon(Icons.Outlined.SettingsEthernet, null, tint = PulsarCyan, modifier = Modifier.size(40.dp)) },
        title = { Text(stringResource(R.string.ssh_port_forward_edit_rule), color = StarDust, fontWeight = FontWeight.Bold) },
        text  = {
            Column(
                modifier             = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement  = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == com.systemsgo.hex.data.model.SshPortForwardType.LOCAL,
                        onClick  = { type = com.systemsgo.hex.data.model.SshPortForwardType.LOCAL },
                        label    = { Text(stringResource(R.string.ssh_port_forward_type_local)) },
                    )
                    FilterChip(
                        selected = type == com.systemsgo.hex.data.model.SshPortForwardType.REMOTE,
                        onClick  = { type = com.systemsgo.hex.data.model.SshPortForwardType.REMOTE },
                        label    = { Text(stringResource(R.string.ssh_port_forward_type_remote)) },
                    )
                }
                SpaceTextField(
                    value         = bindAddress,
                    onValueChange = { bindAddress = it },
                    label         = stringResource(R.string.ssh_port_forward_bind_address),
                    icon          = Icons.Outlined.Router,
                )
                Text(
                    text  = if (type == com.systemsgo.hex.data.model.SshPortForwardType.LOCAL)
                                stringResource(R.string.ssh_port_forward_bind_address_local_desc)
                            else stringResource(R.string.ssh_port_forward_bind_address_remote_desc),
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall,
                )
                SpaceTextField(
                    value         = listenPort,
                    onValueChange = { listenPort = it.normalizeDigits().filter(Char::isDigit) },
                    label         = stringResource(R.string.ssh_port_forward_listen_port),
                    icon          = Icons.Outlined.Pin,
                    keyboardType  = KeyboardType.Number,
                    isError       = listenPort.isNotBlank() && !listenPortValid,
                )
                Text(
                    text  = if (type == com.systemsgo.hex.data.model.SshPortForwardType.LOCAL)
                                stringResource(R.string.ssh_port_forward_listen_port_local_desc)
                            else stringResource(R.string.ssh_port_forward_listen_port_remote_desc),
                    color = CometTail,
                    style = MaterialTheme.typography.bodySmall,
                )
                SpaceTextField(
                    value         = destHost,
                    onValueChange = { destHost = it },
                    label         = stringResource(R.string.ssh_port_forward_dest_host),
                    icon          = Icons.Outlined.Dns,
                )
                SpaceTextField(
                    value         = destPort,
                    onValueChange = { destPort = it.normalizeDigits().filter(Char::isDigit) },
                    label         = stringResource(R.string.ssh_port_forward_dest_port),
                    icon          = Icons.Outlined.SettingsEthernet,
                    keyboardType  = KeyboardType.Number,
                    isError       = destPort.isNotBlank() && !destPortValid,
                )
            }
        },
        confirmButton = {
            SpaceButton(
                text     = stringResource(R.string.ssh_port_forward_save_rule),
                onClick  = {
                    onSave(
                        com.systemsgo.hex.data.model.SshPortForwardRule(
                            id          = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            type        = type,
                            bindAddress = bindAddress.trim(),
                            listenPort  = listenPort.toIntOrNull() ?: 0,
                            destHost    = destHost.trim(),
                            destPort    = destPort.toIntOrNull() ?: 0,
                        )
                    )
                },
                enabled  = canSave,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CometTail) }
        }
    )
}

@Composable
private fun SecuritySection(
    domain:                        String, onDomainChange: (String) -> Unit,
) {
    SpaceTextField(domain, onDomainChange, stringResource(R.string.domain), Icons.Outlined.Domain)
    // NOTE: the "Accept self-signed certificate (unsafe)" pre-connection
    // toggle that used to live here has been removed. Trust is now decided
    // at connect time instead, via a dialog that shows the actual
    // certificate details (see CertificateTrustDialog in RdpSessionActivity
    // and RdpRemoteAdapter.verifyServerCertificate) — the same pattern a
    // browser uses for an untrusted HTTPS certificate, including an
    // "always trust this certificate" option, rather than a blanket opt-in
    // decided in advance with no visibility into what's being trusted.
}

@Composable
private fun GatewaySection(
    gatewayHost:                     String, onGatewayHostChange: (String) -> Unit,
    gatewayPort:                     String, onGatewayPortChange: (String) -> Unit,
    gatewayUsername:                 String, onGatewayUsernameChange: (String) -> Unit,
    gatewayPassword:                 String, onGatewayPasswordChange: (String) -> Unit,
    gatewayPasswordVisible:          Boolean, onToggleGatewayPasswordVisible: () -> Unit,
    gatewayDomain:                   String, onGatewayDomainChange: (String) -> Unit,
    // ENTRA-ID-AUTH FEATURE: see GatewayAuthMode's doc comment for what each
    // mode actually does at connect time.
    gatewayAuthMode: com.systemsgo.hex.data.model.GatewayAuthMode = com.systemsgo.hex.data.model.GatewayAuthMode.PASSWORD,
    onGatewayAuthModeChange: (com.systemsgo.hex.data.model.GatewayAuthMode) -> Unit = {},
    entraLinkedUpn: String = "",
    // ENTRA-ID-AUTH FEATURE: the per-profile Application ID URI scope — see
    // RdpProfile.gatewayScopeUri's doc comment. Only shown/editable while
    // gatewayAuthMode is ENTRA_ID (EntraGatewaySignInSection below).
    gatewayScopeUri: String = "",
    onGatewayScopeUriChange: (String) -> Unit = {},
    onSignInWithMicrosoft: () -> Unit = {},
    onSignOutMicrosoft: () -> Unit = {},
    entraSignInPending: Boolean = false,
) {
    val gatewayPortValid = gatewayPort.isBlank() || isPortInRange(gatewayPort)

    SpaceTextField(gatewayHost, onGatewayHostChange, stringResource(R.string.gateway_host), Icons.Outlined.Hub)
    SpaceTextField(
        value         = gatewayPort,
        onValueChange = onGatewayPortChange,
        label         = stringResource(R.string.gateway_port),
        icon          = Icons.Outlined.SettingsEthernet,
        keyboardType  = KeyboardType.Number,
        isError       = gatewayPort.isNotBlank() && !gatewayPortValid
    )

    // ENTRA-ID-AUTH FEATURE: a simple two-way segmented toggle between the
    // pre-existing "Username & password / NLA-Kerberos" auth (unchanged
    // below) and Azure AD Application Proxy + MSAL sign-in. Mirrors the
    // shape of ProxySection's HTTP/SOCKS picker just below in this file —
    // two SpaceButton-style chips, not a dropdown, since there are only ever
    // two options and both should be visible at a glance.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AuthModeChip(
            label = stringResource(R.string.gateway_auth_mode_password),
            selected = gatewayAuthMode == com.systemsgo.hex.data.model.GatewayAuthMode.PASSWORD,
            onClick = { onGatewayAuthModeChange(com.systemsgo.hex.data.model.GatewayAuthMode.PASSWORD) },
            modifier = Modifier.weight(1f),
        )
        AuthModeChip(
            label = stringResource(R.string.gateway_auth_mode_entra_id),
            selected = gatewayAuthMode == com.systemsgo.hex.data.model.GatewayAuthMode.ENTRA_ID,
            onClick = { onGatewayAuthModeChange(com.systemsgo.hex.data.model.GatewayAuthMode.ENTRA_ID) },
            modifier = Modifier.weight(1f),
        )
    }

    if (gatewayAuthMode == com.systemsgo.hex.data.model.GatewayAuthMode.PASSWORD) {
        SpaceTextField(gatewayUsername, onGatewayUsernameChange, stringResource(R.string.gateway_username), Icons.Outlined.Person)
        SpaceTextField(
            gatewayPassword, onGatewayPasswordChange, stringResource(R.string.gateway_password), Icons.Outlined.Lock,
            isPassword = true, passwordVisible = gatewayPasswordVisible, onTogglePassword = onToggleGatewayPasswordVisible
        )
        SpaceTextField(gatewayDomain, onGatewayDomainChange, stringResource(R.string.gateway_domain), Icons.Outlined.Domain)
    } else {
        EntraGatewaySignInSection(
            linkedUpn = entraLinkedUpn,
            pending = entraSignInPending,
            onSignInClick = onSignInWithMicrosoft,
            onSignOutClick = onSignOutMicrosoft,
            scopeUri = gatewayScopeUri,
            onScopeUriChange = onGatewayScopeUriChange,
        )
    }
}

/** Small selected/unselected chip used by GatewaySection's auth-mode picker
 *  above. Kept local to this file rather than reusing SpaceButton, since
 *  SpaceButton is styled for a single primary action, not a 2-way toggle. */
@Composable
private fun AuthModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .then(Modifier),
        color = if (selected) PulsarCyan.copy(alpha = 0.18f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (selected) PulsarCyan else Color.White.copy(alpha = 0.15f)
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        onClick = onClick,
    ) {
        androidx.compose.material3.Text(
            text = label,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp).fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = if (selected) PulsarCyan else Color.White.copy(alpha = 0.7f),
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * ENTRA-ID-AUTH FEATURE: the "Sign in with Microsoft" card shown in place
 * of gateway username/password fields when GatewayAuthMode.ENTRA_ID is
 * selected. Purely presentational — [onSignInClick]/[onSignOutClick] are
 * supplied by the screen hosting ProfileFormDialog, which is the layer that
 * actually holds a live Activity + EntraIdAuthManager/GatewayTokenProvider
 * (see the handoff prompt for the exact wiring).
 */
@Composable
private fun EntraGatewaySignInSection(
    linkedUpn: String,
    pending: Boolean,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    // ENTRA-ID-AUTH FEATURE: see RdpProfile.gatewayScopeUri's doc comment.
    // Placed above the sign-in button (rather than after) so the user fills
    // it in before attempting to sign in, matching the actual order of
    // operations they need to follow in Azure Portal first.
    scopeUri: String = "",
    onScopeUriChange: (String) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        androidx.compose.material3.Text(
            text = stringResource(R.string.gateway_entra_id_explainer),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SpaceTextField(
            value = scopeUri,
            onValueChange = onScopeUriChange,
            label = stringResource(R.string.gateway_entra_scope_uri_label),
            icon = Icons.Outlined.Hub,
        )
        androidx.compose.material3.Text(
            text = stringResource(R.string.gateway_entra_scope_uri_helper),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (linkedUpn.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = PulsarCyan)
                androidx.compose.material3.Text(
                    text = stringResource(R.string.gateway_entra_signed_in_as, linkedUpn),
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    color = Color.White,
                )
                androidx.compose.material3.TextButton(onClick = onSignOutClick, enabled = !pending) {
                    androidx.compose.material3.Text(stringResource(R.string.gateway_entra_sign_out))
                }
            }
        } else {
            SpaceButton(
                text = if (pending) stringResource(R.string.gateway_entra_signing_in) else stringResource(R.string.gateway_entra_sign_in),
                onClick = onSignInClick,
                enabled = !pending,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// PAC-SUPPORT FEATURE (Part 3/n — UI): reconstructs a canonical
// "KEYWORD host:port" (or "DIRECT") string for a parsed directive, for the
// raw-directive line in the Test-button preview below. PacFileParser only
// keeps the original raw text for an Unrecognized entry (see
// PacProxyDirective's doc comment) — Direct/Proxy/Socks are re-serialized
// here instead of round-tripped, so whitespace/casing may differ slightly
// from what the .pac script literally returned; the *meaning* is identical.
private fun com.systemsgo.hex.util.PacProxyDirective.toRawString(): String = when (this) {
    is com.systemsgo.hex.util.PacProxyDirective.Direct -> "DIRECT"
    is com.systemsgo.hex.util.PacProxyDirective.Proxy -> "PROXY $host:$port"
    is com.systemsgo.hex.util.PacProxyDirective.Socks -> "SOCKS $host:$port"
    is com.systemsgo.hex.util.PacProxyDirective.Unrecognized -> raw
}

// PAC-SUPPORT FEATURE (Part 3/n — UI): the PAC URL field + "Test" preview,
// shown at the top of the "Outbound Proxy" card as an alternative to the
// manual proxyHost/proxyPort/... fields below it (see PacProxyResolver's
// doc comment for how the two interact at actual connect time — this form
// doesn't force them to be mutually exclusive).
//
// The Test button deliberately calls PacFileParser directly instead of
// going through PacProxyResolver.resolve(): that resolver's entire job is
// to silently degrade to a fallback proxy/DIRECT so a real connect attempt
// is never blocked by a broken PAC file, which is exactly the information
// this preview needs to SHOW the user (invalid URL, 404, script error,
// timeout) rather than hide.
//
// TESTABILITY FIX: the fetch/validate/evaluate branching used to live
// entirely inline in this Composable's onClick, which meant the only way
// to check "what does an invalid URL / a 404 / a timeout / a broken
// script actually show the user" was a full Compose UI test. That logic
// now lives in PacUrlTester (plain Kotlin, see its own doc comment) —
// PacUrlTesterTest.kt covers every branch directly. This Composable's job
// is reduced to: call PacUrlTester.test(), map the returned
// PacUrlTestOutcome to a localized string, and own the loading/result UI
// state — nothing here needs a Compose UI test to verify anymore.
@Composable
private fun PacUrlTestBlock(
    pacUrl:      String, onPacUrlChange: (String) -> Unit,
    targetHost:  String, targetPort:     String,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // PacFileParser takes no constructor dependencies (its @Singleton
    // annotation only matters to Hilt's graph) — safe to instantiate
    // directly here since ProfileFormDialog is a plain stateless composable
    // with no Hilt/ViewModel access (see its own doc comment for why).
    val pacFileParser = remember { com.systemsgo.hex.util.PacFileParser() }
    val pacUrlTester = remember { com.systemsgo.hex.util.PacUrlTester(pacFileParser) }
    // "LAST SUCCESSFUL TEST" FIX: SharedPreferences-backed, not a Room
    // column — see PacUrlLastTestStore's own doc comment for why.
    val lastTestStore = remember { com.systemsgo.hex.util.PacUrlLastTestStore(context) }

    var testLoading by remember { mutableStateOf(false) }
    // Success carries (friendly summary, full raw directive list) so the
    // preview can show both, per the "الاثنين" (both) requirement.
    var testSuccess by remember { mutableStateOf<Pair<String, String>?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    // The exact (trimmed) pacUrl a currently-shown testSuccess/testError
    // was tested against — used below to detect a stale result instead of
    // clearing it the instant the user types, which used to make the
    // result disappear silently.
    var testedPacUrl by remember { mutableStateOf<String?>(null) }
    var lastSuccessAt by remember { mutableStateOf<Long?>(null) }

    Text(
        stringResource(R.string.pac_url_section_title),
        color = StarDust, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
    Text(
        stringResource(R.string.pac_url_hint),
        color = CometTail, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    SpaceTextField(
        value = pacUrl,
        onValueChange = onPacUrlChange,
        label = stringResource(R.string.pac_url_label),
        icon = Icons.Outlined.Link,
        keyboardType = KeyboardType.Uri,
        imeAction = ImeAction.Done,
    )

    val trimmedPacUrl = pacUrl.trim()
    val trimmedTargetHost = targetHost.trim()
    val canTest = trimmedPacUrl.isNotEmpty() && !testLoading
    // DEBOUNCE-ON-EDIT FIX: a previous result is no longer cleared the
    // instant the URL changes — it's kept on screen but flagged stale, so
    // the user sees "you changed this, re-test to confirm" instead of the
    // result just vanishing.
    val resultIsStale = testedPacUrl != null && testedPacUrl != trimmedPacUrl &&
        (testSuccess != null || testError != null)

    // "LAST SUCCESSFUL TEST" FIX: look up (and re-look-up as the URL is
    // edited) whether this exact PAC URL was ever verified before —
    // including on first composition, so opening a profile that already
    // has a pacUrl saved shows this immediately without the user pressing
    // Test again just to confirm it still works.
    LaunchedEffect(trimmedPacUrl) {
        lastSuccessAt = lastTestStore.lastSuccessAt(trimmedPacUrl)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            enabled = canTest,
            onClick = {
                testLoading = true
                testSuccess = null
                testError = null
                testedPacUrl = trimmedPacUrl
                coroutineScope.launch {
                    when (val outcome = pacUrlTester.test(pacUrl, targetHost, targetPort)) {
                        is com.systemsgo.hex.util.PacUrlTestOutcome.MissingHost ->
                            testError = context.getString(R.string.pac_test_error_missing_host)
                        is com.systemsgo.hex.util.PacUrlTestOutcome.InvalidUrl ->
                            testError = context.getString(R.string.pac_test_error_invalid_url)
                        is com.systemsgo.hex.util.PacUrlTestOutcome.NotFound ->
                            testError = context.getString(R.string.pac_test_error_not_found)
                        is com.systemsgo.hex.util.PacUrlTestOutcome.HttpError ->
                            testError = context.getString(R.string.pac_test_error_http, outcome.code)
                        is com.systemsgo.hex.util.PacUrlTestOutcome.Timeout ->
                            testError = context.getString(R.string.pac_test_error_timeout)
                        is com.systemsgo.hex.util.PacUrlTestOutcome.NetworkError ->
                            testError = context.getString(R.string.pac_test_error_network, outcome.message)
                        is com.systemsgo.hex.util.PacUrlTestOutcome.ScriptError ->
                            testError = context.getString(R.string.pac_test_error_script, outcome.message)
                        is com.systemsgo.hex.util.PacUrlTestOutcome.NoUsableDirective ->
                            testError = context.getString(R.string.pac_test_error_no_usable_directive)
                        is com.systemsgo.hex.util.PacUrlTestOutcome.Success -> {
                            val rawDirectiveList = outcome.directives.joinToString("; ") { it.toRawString() }
                            val summary = when (val resolution = outcome.resolution) {
                                is com.systemsgo.hex.util.PacUrlTestOutcome.Resolution.Direct ->
                                    context.getString(R.string.pac_test_result_direct)
                                is com.systemsgo.hex.util.PacUrlTestOutcome.Resolution.Proxy ->
                                    context.getString(R.string.pac_test_result_proxy, resolution.host, resolution.port)
                                is com.systemsgo.hex.util.PacUrlTestOutcome.Resolution.Socks ->
                                    context.getString(R.string.pac_test_result_socks, resolution.host, resolution.port)
                            }
                            testSuccess = summary to rawDirectiveList
                            lastTestStore.recordSuccess(trimmedPacUrl)
                            lastSuccessAt = System.currentTimeMillis()
                        }
                    }
                    testLoading = false
                }
            }
        ) {
            if (testLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PulsarCyan)
            } else {
                Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.pac_test_button))
        }

        // Inline hint instead of a silent disabled button when the only
        // thing blocking Test is a missing Host — points the user at
        // exactly what to fill in first.
        if (trimmedPacUrl.isNotEmpty() && trimmedTargetHost.isEmpty() && !testLoading) {
            Text(
                stringResource(R.string.pac_test_needs_host_hint),
                color = SolarFlare, style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    // "LAST SUCCESSFUL TEST" FIX: shown only when there's no current-run
    // result on screen (fresh success/error from this session always take
    // priority) and nothing is in flight.
    if (testSuccess == null && testError == null && !testLoading && lastSuccessAt != null) {
        Text(
            stringResource(
                R.string.pac_test_last_success,
                android.text.format.DateUtils.getRelativeTimeSpanString(
                    lastSuccessAt!!, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
                )
            ),
            color = CometTail, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp)
        )
    }

    testSuccess?.let { (summary, rawDirectiveList) ->
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = RoundedCornerShape(10.dp),
            color = PlasmaGreen.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, PlasmaGreen.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = PlasmaGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(summary, color = StarDust, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    stringResource(R.string.pac_test_raw_directive, rawDirectiveList),
                    color = CometTail, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp, start = 22.dp)
                )
                if (resultIsStale) {
                    Text(
                        stringResource(R.string.pac_test_url_changed_hint),
                        color = SolarFlare, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp, start = 22.dp)
                    )
                }
            }
        }
    }

    testError?.let { error ->
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = RoundedCornerShape(10.dp),
            color = ErrorRed.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(error, color = StarDust, style = MaterialTheme.typography.labelMedium)
                }
                if (resultIsStale) {
                    Text(
                        stringResource(R.string.pac_test_url_changed_hint),
                        color = SolarFlare, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp, start = 22.dp)
                    )
                }
            }
        }
    }
}

// OUTBOUND-PROXY FEATURE: same shape as GatewaySection immediately above,
// plus a 3-way HTTP/HTTPS/SOCKS type picker (ProxyType.NONE is never offered
// here — the enclosing SettingsCard's own toggle already covers "no proxy").
// HTTPS-PROXY FEATURE: HTTPS only works once the CI-applied FreeRDP patch
// (the "Patch FreeRDP proxy for HTTPS support" CI step in .github/workflows/main.yml) is in the prebuilt
// this binary links against — see AFreeRdpBridge.ProxyType's doc comment.
@Composable
private fun ProxySection(
    proxyType:                       ProxyType, onProxyTypeChange: (ProxyType) -> Unit,
    proxyHost:                       String, onProxyHostChange: (String) -> Unit,
    proxyPort:                       String, onProxyPortChange: (String) -> Unit,
    proxyUsername:                   String, onProxyUsernameChange: (String) -> Unit,
    proxyPassword:                   String, onProxyPasswordChange: (String) -> Unit,
    proxyPasswordVisible:            Boolean, onToggleProxyPasswordVisible: () -> Unit,
    // PAC-SUPPORT FEATURE (Part 3/n — UI): see RdpProfile.pacUrl's doc
    // comment for the pacUrl-vs-static-fields priority applied at actual
    // connect time by PacProxyResolver. targetHost/targetPort are the
    // Quick Connect section's current host/port — passed in (rather than
    // read from outer state) so this section stays self-contained, same
    // as every other *Section composable in this file.
    pacUrl:                          String, onPacUrlChange: (String) -> Unit,
    targetHost:                      String, targetPort:      String,
) {
    val proxyPortValid = proxyPort.isBlank() || isPortInRange(proxyPort)

    PacUrlTestBlock(
        pacUrl = pacUrl, onPacUrlChange = onPacUrlChange,
        targetHost = targetHost, targetPort = targetPort,
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = HorizonGray.copy(alpha = 0.35f))
    Text(
        stringResource(R.string.proxy_manual_section_title),
        color = CometTail, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(ProxyType.SOCKS, ProxyType.HTTP, ProxyType.HTTPS).forEach { option ->
            val sel = option == proxyType
            Surface(
                modifier = Modifier.weight(1f).clickable { if (!sel) onProxyTypeChange(option) },
                shape  = RoundedCornerShape(12.dp),
                color  = if (sel) VoidPurple.copy(alpha = 0.18f) else NebulaSurface,
                border = BorderStroke(1.dp, if (sel) VoidPurple else HorizonGray)
            ) {
                Text(
                    text = option.name,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                    color = if (sel) StarDust else CometTail,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
    // HTTPS-PROXY FEATURE: the port field carries no per-type default — SOCKS'
    // conventional 1080 stays pre-filled even when HTTPS is picked, same as
    // it already did for HTTP before this feature (both share proxyPort's
    // single default). Users connecting to an HTTPS-only forward proxy
    // typically need to type the real port (often 443 or 8443) themselves;
    // this intentionally does not silently overwrite a value the user may
    // have already typed by switching the segmented control.
    if (proxyType == ProxyType.HTTPS) {
        Text(
            stringResource(R.string.proxy_https_hint),
            color = CometTail, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
        )
    }
    Spacer(Modifier.height(4.dp))
    SpaceTextField(proxyHost, onProxyHostChange, stringResource(R.string.proxy_host), Icons.Outlined.Hub)
    SpaceTextField(
        value         = proxyPort,
        onValueChange = onProxyPortChange,
        label         = stringResource(R.string.proxy_port),
        icon          = Icons.Outlined.SettingsEthernet,
        keyboardType  = KeyboardType.Number,
        isError       = proxyPort.isNotBlank() && !proxyPortValid
    )
    SpaceTextField(proxyUsername, onProxyUsernameChange, stringResource(R.string.proxy_username), Icons.Outlined.Person)
    SpaceTextField(
        proxyPassword, onProxyPasswordChange, stringResource(R.string.proxy_password), Icons.Outlined.Lock,
        isPassword = true, passwordVisible = proxyPasswordVisible, onTogglePassword = onToggleProxyPasswordVisible
    )
}

@Composable
private fun RemoteAppSection(
    remoteAppProgram:        String, onRemoteAppProgramChange: (String) -> Unit,
    remoteAppWorkingDir:     String, onRemoteAppWorkingDirChange: (String) -> Unit,
    remoteAppCmdLine:        String, onRemoteAppCmdLineChange: (String) -> Unit,
    remoteAppDisplayMode:    RemoteAppDisplayMode, onRemoteAppDisplayModeChange: (RemoteAppDisplayMode) -> Unit,
) {
    SpaceTextField(
        remoteAppProgram, onRemoteAppProgramChange,
        stringResource(R.string.remote_app_program), Icons.Outlined.OpenInNew
    )
    Text(
        stringResource(R.string.remote_app_program_hint),
        color = CometTail, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
    SpaceTextField(remoteAppWorkingDir, onRemoteAppWorkingDirChange, stringResource(R.string.remote_app_working_dir), Icons.Outlined.Folder)
    SpaceTextField(remoteAppCmdLine, onRemoteAppCmdLineChange, stringResource(R.string.remote_app_cmdline), Icons.Outlined.Terminal)
    Spacer(Modifier.height(8.dp))
    RemoteAppDisplayModePicker(remoteAppDisplayMode, onRemoteAppDisplayModeChange)
}

/**
 * REMOTEAPP-WINDOWS FEATURE: single-vs-multi window presentation picker,
 * shown as a horizontal row of two icon tiles (mirrors [ProtocolSelector]
 * immediately below, same tile shape/selection styling) rather than a plain
 * switch, so the two modes' *effect* — one full-screen window vs. several
 * switchable ones — is visually obvious at a glance instead of needing a
 * label to explain what "on"/"off" means for a mode picker.
 *
 * Purely a per-profile default (see RdpProfile.remoteAppDisplayMode's doc
 * comment) — the in-session choice, once a live switcher exists in
 * RdpSessionActivity, can still override it for that session without coming
 * back here.
 */
@Composable
private fun RemoteAppDisplayModePicker(
    selected: RemoteAppDisplayMode,
    onSelect: (RemoteAppDisplayMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.remote_app_display_mode), color = CometTail, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteAppDisplayMode.entries.forEach { mode ->
                val sel = mode == selected
                val (icon, label, hint) = when (mode) {
                    RemoteAppDisplayMode.SINGLE_WINDOW -> Triple(
                        Icons.Outlined.Fullscreen,
                        stringResource(R.string.remote_app_display_mode_single),
                        stringResource(R.string.remote_app_display_mode_single_hint),
                    )
                    RemoteAppDisplayMode.MULTI_WINDOW -> Triple(
                        Icons.Outlined.ViewCarousel,
                        stringResource(R.string.remote_app_display_mode_multi),
                        stringResource(R.string.remote_app_display_mode_multi_hint),
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f).clickable { if (!sel) onSelect(mode) },
                    shape  = RoundedCornerShape(12.dp),
                    color  = if (sel) NovaPink.copy(alpha = 0.18f) else NebulaSurface,
                    border = BorderStroke(1.dp, if (sel) NovaPink else HorizonGray)
                ) {
                    Column(
                        modifier            = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(icon, null, tint = if (sel) NovaPink else CometTail, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            label, color = if (sel) NovaPink else CometTail,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            hint, color = CometTail, style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * CODEC-NEGOTIATION FEATURE: maps a [CodecPreference] to its short,
 * user-facing label — used both for the "Advanced Settings" SettingsCard's
 * subtitle (in the RDP branch of the optional-settings Column above) and as
 * this picker's own per-option label below, so the two always stay in sync
 * by construction instead of two separate `when` blocks drifting apart over
 * time.
 */
private fun CodecPreference.labelRes(): Int = when (this) {
    CodecPreference.AUTO -> R.string.codec_preference_auto
    CodecPreference.PREFER_AV1 -> R.string.codec_preference_prefer_av1
    CodecPreference.PREFER_H264 -> R.string.codec_preference_prefer_h264
    CodecPreference.DISABLE_MODERN_CODECS -> R.string.codec_preference_disable_modern
}

/**
 * CODEC-NEGOTIATION FEATURE: the four-way RDPGFX codec-preference picker —
 * see [CodecPreference]'s doc comment for what each option actually changes
 * and AFreeRdpBridge.CodecPreference for the exhaustive native-layer
 * mapping. Laid out as a vertical list of selectable rows (unlike
 * [RemoteAppDisplayModePicker]'s two-tile row above) since these four
 * options need a full-width hint line each — "Not available in this build"
 * / "Experimental" / a hardware-decode badge — that wouldn't fit legibly in
 * a narrow tile.
 *
 * [h264BackendAvailable]/[av1BackendAvailable] gate selectability exactly
 * like [ProtocolOptionsSection]'s printerBackendAvailable/webcamBackendAvailable
 * pattern: an unavailable option stays visible (so the capability is
 * discoverable) but dimmed and unclickable, with an explanatory subtitle,
 * rather than disappearing outright. [h264HardwareDecoder]/[av1HardwareDecoder]
 * never disable anything — see HardwareDecoderCapabilities' class doc for
 * why that one is purely an informational hint.
 */
@Composable
private fun CodecPreferenceSection(
    selected: CodecPreference,
    onSelect: (CodecPreference) -> Unit,
    h264BackendAvailable: Boolean,
    av1BackendAvailable: Boolean,
    h264HardwareDecoder: Boolean,
    av1HardwareDecoder: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CodecPreference.entries.forEach { option ->
            // AUTO and DISABLE_MODERN_CODECS never depend on a specific
            // codec backend being compiled in — AUTO just offers whatever
            // is available (possibly nothing modern, falling back to
            // RemoteFX/NSCodec) and DISABLE_MODERN_CODECS skips RDPGFX
            // entirely. Only the two single-codec choices can be genuinely
            // unavailable in this build.
            val available = when (option) {
                CodecPreference.PREFER_AV1 -> av1BackendAvailable
                CodecPreference.PREFER_H264 -> h264BackendAvailable
                CodecPreference.AUTO, CodecPreference.DISABLE_MODERN_CODECS -> true
            }
            val sel = option == selected
            val hwHint = when (option) {
                CodecPreference.PREFER_AV1 -> av1HardwareDecoder
                CodecPreference.PREFER_H264 -> h264HardwareDecoder
                else -> false
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = available) { if (!sel) onSelect(option) },
                shape  = RoundedCornerShape(12.dp),
                color  = if (sel) VoidPurple.copy(alpha = 0.18f) else NebulaSurface,
                border = BorderStroke(1.dp, if (sel) VoidPurple else HorizonGray)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = sel,
                        onClick = { if (!sel) onSelect(option) },
                        enabled = available,
                        colors = RadioButtonDefaults.colors(selectedColor = VoidPurple, unselectedColor = HorizonGray),
                    )
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            stringResource(option.labelRes()),
                            color = if (!available) CometTail.copy(alpha = 0.5f) else if (sel) StarDust else CometTail,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        )
                        val subtitle = when {
                            !available -> stringResource(R.string.codec_preference_unavailable_in_build)
                            option == CodecPreference.PREFER_AV1 -> stringResource(R.string.codec_preference_av1_experimental)
                            hwHint -> stringResource(R.string.codec_preference_hardware_decode_hint)
                            else -> null
                        }
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                color = CometTail.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WolSection(
    wolMacAddress:           String, onWolMacChange: (String) -> Unit,
    wolBroadcastAddress:     String, onWolBroadcastChange: (String) -> Unit,
    wolPort:                 String, onWolPortChange: (String) -> Unit,
    wolConnectTimeoutSeconds: String, onWolConnectTimeoutChange: (String) -> Unit,
    wolRetryIntervalSeconds:  String, onWolRetryIntervalChange: (String) -> Unit,
    wolMaxRetries:            String, onWolMaxRetriesChange: (String) -> Unit,
) {
    val wolMacValid = wolMacAddress.isBlank() || com.systemsgo.hex.util.WakeOnLanManager.isValidMac(wolMacAddress)

    SpaceTextField(
        value         = wolMacAddress,
        onValueChange = onWolMacChange,
        label         = stringResource(R.string.wol_mac_address),
        icon          = Icons.Outlined.Wifi,
        isError       = wolMacAddress.isNotBlank() && !wolMacValid
    )
    if (wolMacAddress.isNotBlank() && !wolMacValid) {
        Text(
            stringResource(R.string.wol_mac_invalid),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
    SpaceTextField(wolBroadcastAddress, onWolBroadcastChange, stringResource(R.string.wol_broadcast), Icons.Outlined.Router)
    Text(stringResource(R.string.wol_hint), color = CometTail, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))

    // Magic Packet UDP port — conventionally 7 or 9, but configurable in case
    // the target's NIC/BIOS is set up to listen elsewhere.
    SpaceTextField(
        value         = wolPort,
        onValueChange = onWolPortChange,
        label         = stringResource(R.string.wol_port),
        icon          = Icons.Outlined.SettingsEthernet,
        keyboardType  = KeyboardType.Number,
        isError       = wolPort.isNotBlank() && !isPortInRange(wolPort)
    )

    // "Wake & Connect" timing — how long to wait overall, how often to probe,
    // and the hard cap on probe attempts. Only affects the combined action;
    // manually sending the Magic Packet ("Wake") ignores these.
    Text(
        stringResource(R.string.wol_wake_and_connect_section),
        color = CometTail, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SpaceTextField(
            value         = wolConnectTimeoutSeconds,
            onValueChange = onWolConnectTimeoutChange,
            label         = stringResource(R.string.wol_timeout),
            icon          = Icons.Outlined.Timer,
            keyboardType  = KeyboardType.Number,
            isError       = wolConnectTimeoutSeconds.toIntOrNull()?.let { it <= 0 } ?: true,
            modifier      = Modifier.weight(1f)
        )
        SpaceTextField(
            value         = wolRetryIntervalSeconds,
            onValueChange = onWolRetryIntervalChange,
            label         = stringResource(R.string.wol_retry_interval),
            icon          = Icons.Outlined.Replay,
            keyboardType  = KeyboardType.Number,
            isError       = wolRetryIntervalSeconds.toIntOrNull()?.let { it <= 0 } ?: true,
            modifier      = Modifier.weight(1f)
        )
    }
    SpaceTextField(
        value         = wolMaxRetries,
        onValueChange = onWolMaxRetriesChange,
        label         = stringResource(R.string.wol_max_retries),
        icon          = Icons.Outlined.Repeat,
        keyboardType  = KeyboardType.Number,
        isError       = wolMaxRetries.toIntOrNull()?.let { it <= 0 } ?: true
    )
}

@Composable
private fun ServerTrustSection(
    tofuClearedMessage: String,
    showSshTrust:       Boolean,
    showTunnelTrust:    Boolean,
    showRdpTrust:       Boolean,
    showVncTrust:       Boolean,
    showTelnetTrust:    Boolean = false,
    onClearSshTofu:     () -> Unit,
    onClearTunnelTofu:  () -> Unit,
    onClearRdpTofu:     () -> Unit,
    onClearVncTofu:     () -> Unit,
    onClearTelnetTofu:  () -> Unit = {},
) {
    if (tofuClearedMessage.isNotEmpty()) {
        Text(tofuClearedMessage, style = MaterialTheme.typography.bodySmall, color = PulsarCyan)
    }
    if (showSshTrust) {
        SpaceButton(
            text    = stringResource(R.string.tofu_clear_button_ssh),
            onClick = onClearSshTofu,
            variant = ButtonVariant.GHOST,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (showTunnelTrust) {
        SpaceButton(
            text    = stringResource(R.string.tofu_clear_button_tunnel),
            onClick = onClearTunnelTofu,
            variant = ButtonVariant.GHOST,
            modifier = Modifier.fillMaxWidth()
        )
    }
    // RESET-TRUSTED-CERT FIX: same pattern as SSH/Tunnel above, for RDP's and
    // VNC's own independently-pinned TOFU fingerprints.
    if (showRdpTrust) {
        SpaceButton(
            text    = stringResource(R.string.tofu_clear_button_rdp),
            onClick = onClearRdpTofu,
            variant = ButtonVariant.GHOST,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (showVncTrust) {
        SpaceButton(
            text    = stringResource(R.string.tofu_clear_button_vnc),
            onClick = onClearVncTofu,
            variant = ButtonVariant.GHOST,
            modifier = Modifier.fillMaxWidth()
        )
    }
    // TELNET-TLS FEATURE: same pattern as RDP/VNC above, for TelnetClient's
    // own pinned TLS-certificate fingerprint (see PREFS_TOFU_TELNET).
    if (showTelnetTrust) {
        SpaceButton(
            text    = stringResource(R.string.tofu_clear_button_telnet),
            onClick = onClearTelnetTofu,
            variant = ButtonVariant.GHOST,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SpaceSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    // AUDIO-BACKEND FIX: lets a caller show a toggle as unavailable (greyed
    // out, forced off) rather than pretending it works — e.g. enableSound
    // when this build has no native audio backend. Existing callers are
    // unaffected: both default to the prior always-enabled, no-subtitle
    // behavior.
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = if (enabled) CometTail else CometTail.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(subtitle, color = CometTail.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(
            checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DeepSpace, checkedTrackColor = PulsarCyan,
                uncheckedThumbColor = CometTail, uncheckedTrackColor = HorizonGray
            )
        )
    }
}

// ── Icon toggle row — replaces label+switch rows for simple on/off device
// redirection options (sound / mic / printer). Each item is an icon with a
// short label underneath; tapping it flips the toggle and the active ones
// light up (filled cyan background + cyan icon) instead of showing a
// separate switch control next to a line of text.
private data class IconToggleData(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
    val enabled: Boolean = true,
)

@Composable
private fun IconToggleRow(items: List<IconToggleData>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            IconToggleButton(item = item, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun IconToggleButton(item: IconToggleData, modifier: Modifier = Modifier) {
    val active = item.checked && item.enabled
    val tint = if (active) PulsarCyan else CometTail
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) PulsarCyan.copy(alpha = 0.18f) else NebulaSurface)
            .border(
                width = 1.dp,
                color = if (active) PulsarCyan else HorizonGray,
                shape = RoundedCornerShape(14.dp)
            )
            .then(
                if (item.enabled) Modifier.clickable { item.onCheckedChange(!item.checked) }
                else Modifier
            )
            .alpha(if (item.enabled) 1f else 0.4f)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(item.icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            item.label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun ProtocolSelector(selected: ProtocolType, onSelect: (ProtocolType) -> Unit, isEditing: Boolean = false) {
    // UX-11: Protocol can now be changed in edit mode; we show a warning instead of locking.
    var showProtocolChangeWarning by remember { mutableStateOf(false) }
    var pendingProtocol by remember { mutableStateOf<ProtocolType?>(null) }

    if (showProtocolChangeWarning && pendingProtocol != null) {
        AlertDialog(
            onDismissRequest = { showProtocolChangeWarning = false; pendingProtocol = null },
            containerColor   = StarfieldSurface,
            shape            = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.protocol_change_title), color = StarDust) },
            text  = { Text(stringResource(R.string.protocol_change_warning), color = CometTail, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    pendingProtocol?.let { onSelect(it) }
                    showProtocolChangeWarning = false
                    pendingProtocol = null
                }) { Text(stringResource(R.string.protocol_change_confirm), color = NovaPink) }
            },
            dismissButton = {
                TextButton(onClick = { showProtocolChangeWarning = false; pendingProtocol = null }) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.protocol), color = CometTail, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProtocolType.entries.forEach { type ->
                val sel = type == selected
                val color = when (type) {
                    ProtocolType.RDP -> QuantumBlue
                    ProtocolType.VNC -> VoidPurple
                    ProtocolType.SSH -> PlasmaGreen
                    ProtocolType.TELNET -> SolarFlare
                    ProtocolType.RLOGIN -> SolarFlare
                    ProtocolType.SPICE -> QuantumBlue
                    ProtocolType.RTSP -> VoidPurple
                    ProtocolType.WEB -> PulsarCyan
                    ProtocolType.REDFISH -> PulsarCyan
                    ProtocolType.IPMI -> SolarFlare
                    ProtocolType.AMT -> PulsarCyan
                    ProtocolType.SERIAL_CONSOLE -> SolarFlare
                    ProtocolType.RESTCONF -> PulsarCyan
                    ProtocolType.SNMP -> SolarFlare
                    ProtocolType.NETCONF -> PulsarCyan
                    ProtocolType.GUACAMOLE -> QuantumBlue
                    ProtocolType.PROXMOX -> PulsarCyan
                    ProtocolType.MODBUS_TCP -> SolarFlare
                    ProtocolType.VIRTUALBOX_VRDE -> QuantumBlue
                    ProtocolType.VMWARE_VSPHERE -> PulsarCyan
                    ProtocolType.WAKE_ON_LAN -> SolarFlare
                    ProtocolType.SFTP -> PlasmaGreen
                    // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: same file-transfer family as SFTP above.
                    ProtocolType.FTP -> PlasmaGreen
                    ProtocolType.FTPS -> PlasmaGreen
                    ProtocolType.WEBDAV -> PlasmaGreen
                    ProtocolType.SMB -> PlasmaGreen
                    ProtocolType.NFS -> PlasmaGreen
                }
                Surface(
                    modifier = Modifier.weight(1f).clickable {
                        if (!sel) {
                            if (isEditing) {
                                pendingProtocol = type
                                showProtocolChangeWarning = true
                            } else {
                                onSelect(type)
                            }
                        }
                    },
                    shape  = RoundedCornerShape(12.dp),
                    color  = if (sel) color.copy(alpha = 0.18f) else NebulaSurface,
                    border = BorderStroke(1.dp, if (sel) color else HorizonGray)
                ) {
                    Column(
                        modifier              = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment   = Alignment.CenterHorizontally
                    ) {
                        Icon(protocolIcon(type), null, tint = if (sel) color else CometTail, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(type.label, color = if (sel) color else CometTail, style = MaterialTheme.typography.labelLarge, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

fun protocolIcon(type: ProtocolType): androidx.compose.ui.graphics.vector.ImageVector = when (type) {
    ProtocolType.RDP -> Icons.Outlined.DesktopWindows
    ProtocolType.VNC -> Icons.Outlined.Monitor
    ProtocolType.SSH -> Icons.Outlined.Terminal
    ProtocolType.TELNET -> Icons.Outlined.SettingsEthernet
    ProtocolType.RLOGIN -> Icons.Outlined.SettingsEthernet
    ProtocolType.SPICE -> Icons.Outlined.DesktopWindows
    ProtocolType.RTSP -> Icons.Outlined.Videocam
    ProtocolType.WEB -> Icons.Outlined.Web
    ProtocolType.REDFISH -> Icons.Outlined.Dns
    ProtocolType.IPMI -> Icons.Outlined.SettingsRemote
    ProtocolType.AMT -> Icons.Outlined.Memory
    ProtocolType.SERIAL_CONSOLE -> Icons.Outlined.SettingsEthernet
    ProtocolType.RESTCONF -> Icons.Outlined.Api
    ProtocolType.SNMP -> Icons.Outlined.NetworkCheck
    ProtocolType.NETCONF -> Icons.Outlined.SettingsRemote
    ProtocolType.GUACAMOLE -> Icons.Outlined.DesktopWindows
    // MOSH FEATURE: same icon as TELNET/RLOGIN/SERIAL_CONSOLE above.
    ProtocolType.MOSH -> Icons.Outlined.SettingsEthernet
    // PROXMOX-API FEATURE: same "management API" icon as RESTCONF/REDFISH.
    ProtocolType.PROXMOX -> Icons.Outlined.Dns
    // MODBUS-TCP FEATURE (Part 2/2): same icon as SNMP above.
    ProtocolType.MODBUS_TCP -> Icons.Outlined.NetworkCheck
    ProtocolType.VIRTUALBOX_VRDE -> Icons.Outlined.DesktopWindows
    ProtocolType.VMWARE_VSPHERE -> Icons.Outlined.Dns
    ProtocolType.WAKE_ON_LAN -> Icons.Outlined.Wifi
    ProtocolType.SFTP -> Icons.Outlined.Terminal
    // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: same file-transfer family as SFTP above.
    ProtocolType.FTP -> Icons.Outlined.Terminal
    ProtocolType.FTPS -> Icons.Outlined.Terminal
    ProtocolType.WEBDAV -> Icons.Outlined.Terminal
    ProtocolType.SMB -> Icons.Outlined.Terminal
    ProtocolType.NFS -> Icons.Outlined.Terminal
}

@Composable
fun protocolColor(type: ProtocolType): Color = when (type) {
    ProtocolType.RDP -> QuantumBlue
    ProtocolType.VNC -> VoidPurple
    ProtocolType.SSH -> PlasmaGreen
    ProtocolType.TELNET -> SolarFlare
    ProtocolType.RLOGIN -> SolarFlare
    ProtocolType.SPICE -> QuantumBlue
    ProtocolType.RTSP -> VoidPurple
    ProtocolType.WEB -> PulsarCyan
    ProtocolType.REDFISH -> PulsarCyan
    ProtocolType.IPMI -> SolarFlare
    ProtocolType.AMT -> PulsarCyan
    ProtocolType.SERIAL_CONSOLE -> SolarFlare
    ProtocolType.RESTCONF -> PulsarCyan
    ProtocolType.SNMP -> SolarFlare
    ProtocolType.NETCONF -> PulsarCyan
    ProtocolType.GUACAMOLE -> QuantumBlue
    // MOSH FEATURE: same accent as TELNET/RLOGIN/SERIAL_CONSOLE above.
    ProtocolType.MOSH -> SolarFlare
    // PROXMOX-API FEATURE: same "management API" family as RESTCONF/REDFISH.
    ProtocolType.PROXMOX -> PulsarCyan
    // MODBUS-TCP FEATURE (Part 2/2): same accent as SNMP above.
    ProtocolType.MODBUS_TCP -> SolarFlare
    ProtocolType.VIRTUALBOX_VRDE -> QuantumBlue
    ProtocolType.VMWARE_VSPHERE -> PulsarCyan
    ProtocolType.WAKE_ON_LAN -> SolarFlare
    ProtocolType.SFTP -> PlasmaGreen
    // FTP/FTPS/WEBDAV/SMB/NFS-STANDALONE FEATURE: same file-transfer family as SFTP above.
    ProtocolType.FTP -> PlasmaGreen
    ProtocolType.FTPS -> PlasmaGreen
    ProtocolType.WEBDAV -> PlasmaGreen
    ProtocolType.SMB -> PlasmaGreen
    ProtocolType.NFS -> PlasmaGreen
}

// PRE-EXISTING-BUG FIX: this composable's own header (`@Composable` +
// `private fun AuthTypeChip(`) was missing from the file as uploaded — its
// body had been left dangling directly off protocolColor's closing brace
// above (`}(label: String, ...) { ... }`), which cannot compile regardless
// of anything Mosh-related. Every call site above (SNMP, VNC repeater mode,
// SSH auth type, and now Mosh's color-mode/prediction-mode pickers) already
// assumed this exact signature, so the header is restored to match them —
// nothing about the body below was changed.
@Composable
private fun AuthTypeChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = PulsarCyan
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape  = RoundedCornerShape(10.dp),
        color  = if (selected) accent.copy(alpha = 0.15f) else NebulaSurface,
        border = BorderStroke(1.dp, if (selected) accent else HorizonGray)
    ) {
        Text(
            label,
            color = if (selected) accent else CometTail,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun SectionDivider(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = HorizonGray.copy(alpha = 0.35f))
        Text(label, color = PulsarCyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), color = HorizonGray.copy(alpha = 0.35f))
    }
}

// ── Form Group Header ─────────────────────────────────────────────────────────
// Friendlier, more spacious stand-in for a bare SectionDivider when a group of
// fields benefits from a short one-line explanation of *why* it exists (used to
// frame "Quick Connect" vs "Optional Settings" in the redesigned connection form).
@Composable
fun FormGroupHeader(
    icon:             androidx.compose.ui.graphics.vector.ImageVector,
    title:            String,
    subtitle:         String,
    // COLLAPSIBLE-GROUP: when a header fronts a whole block of situational
    // cards (e.g. "Optional Settings"), the block itself shouldn't render
    // until the person actually asks for it — otherwise every card's
    // collapsed-but-visible header still adds up to a wall of UI before
    // anyone has touched anything. Passing collapsible=true turns the header
    // into a single tap target with a chevron; the caller is responsible
    // for wrapping the section's content in AnimatedVisibility(expanded).
    // Non-collapsible callers (e.g. "Quick Connect") are unaffected —
    // expanded/onExpandedChange default to a fixed, always-open state.
    collapsible:      Boolean = false,
    expanded:         Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null
) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(
            modifier = if (collapsible && onExpandedChange != null) {
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onExpandedChange(!expanded) }
            } else Modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = PulsarCyan, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = StarDust, fontWeight = FontWeight.Bold)
                // BUGFIX-CONTRAST: HorizonGray is the *border* color (near-identical to the
                // dark background), not a text color — using it here made the subtitle text
                // ("فقط الأساسيات — املأها وأنت جاهز للاتصال") effectively invisible against
                // the dialog background. CometTail is the theme's dedicated secondary-text
                // color and keeps proper contrast in both dark and light variants.
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = CometTail,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            if (collapsible && onExpandedChange != null) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    tint     = CometTail,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp), color = HorizonGray.copy(alpha = 0.25f))
    }
}

// ── Settings Card ──────────────────────────────────────────────────────────────
// The single, predictable building block for every optional section of the
// connection form. One visual shape, one interaction pattern: an icon, a name,
// a one-line status that's readable without opening the card, an optional
// on/off switch for true toggles, and a chevron that expands the details.
// Turning the switch on auto-expands the card so the person immediately sees
// what they just enabled — never a hidden state.
@Composable
fun SettingsCard(
    icon:             androidx.compose.ui.graphics.vector.ImageVector,
    accentColor:      Color,
    title:            String,
    subtitle:         String,
    hasToggle:        Boolean = false,
    toggleChecked:    Boolean = false,
    onToggleChange:   ((Boolean) -> Unit)? = null,
    expanded:         Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content:          @Composable ColumnScope.() -> Unit
) {
    val isActive = hasToggle && toggleChecked
    Surface(
        shape    = RoundedCornerShape(16.dp),
        color    = if (isActive) accentColor.copy(alpha = 0.07f) else NebulaSurface.copy(alpha = 0.55f),
        border   = BorderStroke(1.dp, if (isActive) accentColor.copy(alpha = 0.55f) else HorizonGray.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // NESTED-CLICK FIX: the Switch used to live inside the same Row as
            // `.clickable { onExpandedChange(!expanded) }`, so a tap on the Switch's hit
            // area sat inside two click regions at once — the expand/collapse click and
            // the Switch's own toggle. The Switch's handler already calls
            // onExpandedChange() itself (to auto-expand/collapse), so if the Row's
            // clickable also fired on the same tap, the two expand calls could race and
            // leave the card flickering open/closed. Only the [icon + title/subtitle]
            // region is wrapped in the expand-click now; the Switch is a sibling outside
            // it, so a tap on the Switch can only ever hit the Switch.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onExpandedChange(!expanded) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(accentColor.copy(alpha = 0.16f), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, null, tint = accentColor, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyMedium, color = StarDust, fontWeight = FontWeight.SemiBold)
                        Text(
                            subtitle,
                            style    = MaterialTheme.typography.labelSmall,
                            // BUGFIX-CONTRAST: HorizonGray هو لون الـ border (يطابق الخلفية
                            // الداكنة تقريباً)، وليس لون نص — نفس الخطأ الذي أُصلح في
                            // FormGroupHeader. CometTail هو لون النص الثانوي الصحيح.
                            color    = if (isActive) accentColor else CometTail,
                            // BUGFIX-UI-13: maxLines=1 كان يقصّ النصوص العربية التوضيحية
                            // الأطول من نظيراتها الإنجليزية (مثل "الاتصال عبر RDP Gateway ·
                            // متوقف") دون أي وسيلة لرؤية النص كاملاً. السماح بسطرين يمنح
                            // مساحة كافية في الغالبية العظمى من الحالات؛ Ellipsis يبقى كشبكة
                            // أمان للنصوص الاستثنائية الطول.
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                if (hasToggle && onToggleChange != null) {
                    Switch(
                        checked = toggleChecked,
                        onCheckedChange = { checked ->
                            onToggleChange(checked)
                            // BUGFIX #7: turning the switch ON already auto-expanded the
                            // section, but turning it OFF left the fields visible until the
                            // user manually tapped the chevron — confusing, since the toggle
                            // looked "off" while its inputs stayed on screen. Now the section
                            // auto-collapses the moment the switch is turned off, mirroring
                            // the auto-expand behavior on turn-on.
                            if (checked && !expanded) onExpandedChange(true)
                            else if (!checked && expanded) onExpandedChange(false)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DeepSpace, checkedTrackColor = accentColor,
                            uncheckedThumbColor = CometTail, uncheckedTrackColor = HorizonGray
                        ),
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    tint     = CometTail,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication        = null
                        ) { onExpandedChange(!expanded) }
                        .padding(4.dp)
                        .size(20.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp, top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
fun SpaceTextField(
    value:           String,
    onValueChange:   (String) -> Unit,
    label:           String,
    icon:            androidx.compose.ui.graphics.vector.ImageVector,
    isPassword:      Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    isError:         Boolean = false,
    imeAction:       ImeAction = ImeAction.Next,
    onImeAction:     (() -> Unit)? = null,
    keyboardType:    KeyboardType = KeyboardType.Text,
    modifier:        Modifier = Modifier
) {
    // SECURITY FIX: this one field backs every password/passphrase entry in
    // the app (RDP, VNC, SSH, gateway, SSH key passphrase, SSH tunnel, FTP,
    // SMB, WebDAV, ARD, RESTCONF, SNMP, cloud sync, web portal login...), so
    // hooking FLAG_SECURE protection here — rather than at each of those ~30
    // call sites — covers all of them at once and stays correct for future
    // ones automatically. Only engages for isPassword fields, and only for
    // as long as this field is actually on screen (see security/SecureScreen
    // .kt); never affects non-sensitive fields or RDP/VNC/SSH *session*
    // screens, which still need to remain screenshot/recordable.
    if (isPassword) {
        com.systemsgo.hex.security.SecureScreen()
    }
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label, color = CometTail) },
        leadingIcon   = { Icon(icon, null, tint = if (isError) MaterialTheme.colorScheme.error else PulsarCyan, modifier = Modifier.size(20.dp)) },
        trailingIcon  = if (isPassword && onTogglePassword != null) ({
            IconButton(onClick = onTogglePassword) {
                Icon(
                    if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    // A11Y FIX: was contentDescription = null — an icon-only toggle with
                    // no adjacent text label, so TalkBack/screen readers announced it as
                    // just "button" with no indication of what it does. This field is
                    // reused for every password/passphrase in the app (RDP, VNC, SSH,
                    // gateway, SSH key passphrase, SSH tunnel...), so this one fix covers
                    // all of them at once.
                    contentDescription = stringResource(
                        if (passwordVisible) R.string.cd_hide_password else R.string.cd_show_password
                    ),
                    tint = CometTail
                )
            }
        }) else null,
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        isError       = isError,
        singleLine    = true,
        keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = keyboardType),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction?.invoke() },
            onDone = { onImeAction?.invoke() },
            onGo   = { onImeAction?.invoke() }
        ),
        modifier      = modifier.fillMaxWidth(),
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor      = PulsarCyan,
            unfocusedBorderColor    = InputBorder,
            focusedLabelColor       = PulsarCyan,
            cursorColor             = PulsarCyan,
            focusedTextColor        = StarDust,
            unfocusedTextColor      = StarDust,
            focusedContainerColor   = InputBg,
            unfocusedContainerColor = InputBg,
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

// ── Delete Confirm Dialog ─────────────────────────────────────────────────────
@Composable
fun DeleteConfirmDialog(
    profileName: String,
    onConfirm:   () -> Unit,
    onDismiss:   () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = StarfieldSurface,
        shape            = RoundedCornerShape(20.dp),
        icon  = { Icon(Icons.Outlined.Warning, null, tint = SolarFlare, modifier = Modifier.size(40.dp)) },
        title = { Text(stringResource(R.string.delete_confirm_title), color = StarDust, fontWeight = FontWeight.Bold) },
        text  = { Text(stringResource(R.string.delete_confirm_message, profileName), color = CometTail) },
        confirmButton = {
            SpaceButton(stringResource(R.string.delete), onConfirm, ButtonVariant.DANGER, Modifier.fillMaxWidth())
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CometTail) }
        }
    )
}

// ── Folder Dialogs (FOLDERS-UI feature) ───────────────────────────────────────
// The folder CRUD backend (ConnectionFolderRepository, MainViewModel.create/
// rename/deleteFolder) already existed with no UI ever calling it — these are
// the three small dialogs that were missing to actually let a user manage
// folders from the app.

// ── Folder Color/Icon Pickers (FOLDER-APPEARANCE feature) ─────────────────────
// Small, fixed swatch/glyph rows shared by NewFolderDialog and
// RenameFolderDialog below — a closed FolderColor/FolderIcon palette (see
// their doc comments) rather than a full color wheel or icon browser, kept
// deliberately lightweight so it doesn't turn a two-field "name a folder"
// dialog into its own multi-step flow. Both pickers include a leading
// "none" swatch/glyph so a user can always go back to the original neutral
// look.

@Composable
private fun folderColorLabel(color: FolderColor): String = when (color) {
    FolderColor.CYAN   -> stringResource(R.string.folder_color_cyan)
    FolderColor.BLUE   -> stringResource(R.string.folder_color_blue)
    FolderColor.VIOLET -> stringResource(R.string.folder_color_violet)
    FolderColor.PINK   -> stringResource(R.string.folder_color_pink)
    FolderColor.RED    -> stringResource(R.string.folder_color_red)
    FolderColor.ORANGE -> stringResource(R.string.folder_color_orange)
    FolderColor.YELLOW -> stringResource(R.string.folder_color_yellow)
    FolderColor.GREEN  -> stringResource(R.string.folder_color_green)
}

@Composable
private fun FolderColorPicker(
    selected:  FolderColor?,
    onSelect:  (FolderColor?) -> Unit,
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // "No color" swatch — an outlined ring, distinct from every real color.
        val noneLabel = stringResource(R.string.folder_color_none)
        val noneSelected = selected == null
        val noneDescription = if (noneSelected) stringResource(R.string.cd_color_swatch_selected, noneLabel) else noneLabel
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(NebulaSurface)
                .border(
                    width = if (noneSelected) 2.dp else 1.dp,
                    color = if (noneSelected) StarDust else HorizonGray.copy(alpha = 0.6f),
                    shape = CircleShape
                )
                // A11Y FIX: was an icon-only clickable swatch with no label —
                // set the description on the swatch itself so TalkBack
                // announces the color choice, not the decorative glyph.
                .semantics { contentDescription = noneDescription }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onSelect(null) }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Block, contentDescription = null, tint = CometTail, modifier = Modifier.size(14.dp))
        }
        FolderColor.entries.forEach { swatch ->
            val swatchColor = Color(swatch.argb)
            val swatchLabel = folderColorLabel(swatch)
            val isSelected = selected == swatch
            val swatchDescription = if (isSelected) stringResource(R.string.cd_color_swatch_selected, swatchLabel) else swatchLabel
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(swatchColor)
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = StarDust,
                        shape = CircleShape
                    )
                    // A11Y FIX: was an icon-only clickable swatch with no
                    // label; the checkmark shown only when selected isn't
                    // enough on its own for TalkBack to identify the color.
                    .semantics { contentDescription = swatchDescription }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(swatch) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun FolderIconPicker(
    selected:  FolderIcon?,
    onSelect:  (FolderIcon?) -> Unit,
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FolderIcon.entries.forEach { entry ->
            val isSelected = selected == entry
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) PulsarCyan.copy(alpha = 0.18f) else NebulaSurface)
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) PulsarCyan else HorizonGray.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        // Tapping the already-selected glyph clears it back to
                        // the default plain-folder look, same "tap again to
                        // clear" pattern already used by tag filter chips.
                        onClick = { onSelect(if (isSelected) null else entry) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    FolderIconMap[entry] ?: Icons.Outlined.Folder,
                    null,
                    tint     = if (isSelected) PulsarCyan else CometTail,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun NewFolderDialog(
    // FOLDER-APPEARANCE FEATURE: onConfirm now also reports the chosen
    // color/icon (as FolderColor/FolderIcon enum names, or "" if left
    // unset) alongside the name — see ConnectionFolder.color/icon's doc
    // comment. HomeScreen wires this straight into
    // MainViewModel.createFolder(name, color, icon).
    onConfirm: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf<FolderColor?>(null) }
    var icon by remember { mutableStateOf<FolderIcon?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = StarfieldSurface,
        shape            = RoundedCornerShape(20.dp),
        icon  = { Icon(Icons.Outlined.CreateNewFolder, null, tint = PulsarCyan, modifier = Modifier.size(40.dp)) },
        title = { Text(stringResource(R.string.new_folder), color = StarDust, fontWeight = FontWeight.Bold) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SpaceTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = stringResource(R.string.folder_name_hint),
                    icon          = Icons.Outlined.Folder,
                    imeAction     = ImeAction.Done,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.folder_color), color = CometTail, style = MaterialTheme.typography.labelMedium)
                    FolderColorPicker(selected = color, onSelect = { color = it })
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.folder_icon), color = CometTail, style = MaterialTheme.typography.labelMedium)
                    FolderIconPicker(selected = icon, onSelect = { icon = it })
                }
            }
        },
        confirmButton = {
            SpaceButton(
                text     = stringResource(R.string.create),
                onClick  = { onConfirm(name, color?.name ?: "", icon?.name ?: "") },
                enabled  = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CometTail) }
        }
    )
}

@Composable
fun RenameFolderDialog(
    folder:    ConnectionFolder,
    // FOLDER-APPEARANCE FEATURE: reports name + color + icon together —
    // HomeScreen wires this into MainViewModel.updateFolderAppearance().
    onConfirm: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(folder.name) }
    var color by remember { mutableStateOf(FolderColor.fromName(folder.color)) }
    var icon by remember { mutableStateOf(FolderIcon.fromName(folder.icon)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = StarfieldSurface,
        shape            = RoundedCornerShape(20.dp),
        icon  = { Icon(Icons.Outlined.DriveFileRenameOutline, null, tint = PulsarCyan, modifier = Modifier.size(40.dp)) },
        title = { Text(stringResource(R.string.rename_folder), color = StarDust, fontWeight = FontWeight.Bold) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SpaceTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = stringResource(R.string.folder_name_hint),
                    icon          = Icons.Outlined.Folder,
                    imeAction     = ImeAction.Done,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.folder_color), color = CometTail, style = MaterialTheme.typography.labelMedium)
                    FolderColorPicker(selected = color, onSelect = { color = it })
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.folder_icon), color = CometTail, style = MaterialTheme.typography.labelMedium)
                    FolderIconPicker(selected = icon, onSelect = { icon = it })
                }
            }
        },
        confirmButton = {
            SpaceButton(
                text     = stringResource(R.string.save),
                onClick  = { onConfirm(name, color?.name ?: "", icon?.name ?: "") },
                enabled  = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CometTail) }
        }
    )
}

@Composable
fun DeleteFolderDialog(
    folder:    ConnectionFolder,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = StarfieldSurface,
        shape            = RoundedCornerShape(20.dp),
        icon  = { Icon(Icons.Outlined.Warning, null, tint = SolarFlare, modifier = Modifier.size(40.dp)) },
        title = { Text(stringResource(R.string.delete_folder_title), color = StarDust, fontWeight = FontWeight.Bold) },
        text  = { Text(stringResource(R.string.delete_folder_message, folder.name), color = CometTail) },
        confirmButton = {
            SpaceButton(stringResource(R.string.delete), onConfirm, ButtonVariant.DANGER, Modifier.fillMaxWidth())
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CometTail) }
        }
    )
}

// ── Home Screen Shortcut Dialog (HOME-SCREEN-SHORTCUTS feature) ──────────────
/**
 * Lets the user customize the label before a home-screen shortcut is pinned
 * for [profile] — see ShortcutHelper.requestPinShortcut(). Pre-fills the
 * field with the connection's own name, mirroring RenameFolderDialog's
 * pattern immediately above.
 */
@Composable
fun CreateShortcutDialog(
    profile:   RdpProfile,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(profile.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = StarfieldSurface,
        shape            = RoundedCornerShape(20.dp),
        icon  = { Icon(Icons.Outlined.AddToHomeScreen, null, tint = PulsarCyan, modifier = Modifier.size(40.dp)) },
        title = { Text(stringResource(R.string.create_shortcut_title), color = StarDust, fontWeight = FontWeight.Bold) },
        text  = {
            Column {
                Text(
                    stringResource(R.string.create_shortcut_desc),
                    color = CometTail,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                SpaceTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = stringResource(R.string.shortcut_name_label),
                    icon          = Icons.Outlined.AddToHomeScreen,
                    imeAction     = ImeAction.Done,
                )
            }
        },
        confirmButton = {
            SpaceButton(
                text     = stringResource(R.string.create_shortcut_action),
                onClick  = { onConfirm(name) },
                enabled  = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CometTail) }
        }
    )
}

// ── SSH ProxyJump Chain Editor (SSH-PROXYJUMP-CHAIN FEATURE — UI) ──────────
/**
 * Editable, ordered list of [SshJumpHop] shown inside the "SSH Tunnel"
 * SettingsCard, replacing the single Jump Host field set that used to live
 * there directly. Supports the same add/remove UX as
 * [SshPortForwardListEditor] (RdpProfile.sshPortForwards) plus up/down
 * reordering — unlike port-forward rules, hop *order* changes which server
 * is dialed from where, so (unlike that list) position matters here. See
 * [SshJumpHop]'s doc comment for the ordering contract this editor has to
 * preserve (index 0 = first hop reachable from the device).
 *
 * Each hop reuses [SshTunnelSection] verbatim for its own
 * host/port/username/auth fields, so behavior (password masking, PEM key
 * generation, digit normalization, etc.) is identical to the old
 * single-hop UI — only now there can be more than one of it, stacked in a
 * card per hop with its own reorder/delete controls (the
 * MobaXterm/Termius-style chain-editing experience the up/down arrows
 * below are standing in for; full drag-and-drop isn't attempted here since
 * arrows already cover the same reordering with far less gesture-conflict
 * risk inside a scrollable form).
 *
 * This only ever edits [RdpProfile.sshTunnelHops] — never
 * [SshTunnelManager] or the data model itself, per this feature's UI-only
 * scope.
 */
@Composable
fun SshTunnelHopChainEditor(
    hops: List<SshJumpHop>,
    onHopsChange: (List<SshJumpHop>) -> Unit,
) {
    // Per-hop, display-only UI state (password visibility), keyed by
    // SshJumpHop.id so it survives reordering (the row for a given hop
    // keeps its own visibility state when it moves up/down) without ever
    // being persisted — same scope [SshTunnelSection]'s old single
    // `sshTunnelPasswordVisible` flag had, just one per hop now.
    val passwordVisibility = remember { mutableStateMapOf<String, Boolean>() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text  = stringResource(R.string.ssh_tunnel_chain_desc),
            color = CometTail,
            style = MaterialTheme.typography.bodySmall,
        )

        if (hops.isEmpty()) {
            Text(
                text  = stringResource(R.string.ssh_tunnel_chain_no_hops),
                color = CometTail,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            hops.forEachIndexed { index, hop ->
                key(hop.id) {
                    SshTunnelHopCard(
                        hop              = hop,
                        hopNumber        = index + 1,
                        hopCount         = hops.size,
                        isFirst          = index == 0,
                        isLast           = index == hops.lastIndex,
                        passwordVisible  = passwordVisibility[hop.id] ?: false,
                        onTogglePassword = {
                            passwordVisibility[hop.id] = !(passwordVisibility[hop.id] ?: false)
                        },
                        onChange = { updated ->
                            onHopsChange(hops.map { if (it.id == hop.id) updated else it })
                        },
                        onMoveUp = {
                            if (index > 0) {
                                onHopsChange(hops.toMutableList().apply { add(index - 1, removeAt(index)) })
                            }
                        },
                        onMoveDown = {
                            if (index < hops.lastIndex) {
                                onHopsChange(hops.toMutableList().apply { add(index + 1, removeAt(index)) })
                            }
                        },
                        onDelete = {
                            passwordVisibility.remove(hop.id)
                            onHopsChange(hops.filterNot { it.id == hop.id })
                        },
                    )
                }
            }
        }

        SpaceButton(
            text     = stringResource(R.string.ssh_tunnel_chain_add_hop),
            onClick  = { onHopsChange(hops + SshJumpHop()) },
            variant  = ButtonVariant.GHOST,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A single hop's card in [SshTunnelHopChainEditor]: a numbered header (with
 * reorder/delete controls) above that hop's own [SshTunnelSection] fields.
 * The port is edited through a local text buffer ([portText]) rather than
 * straight off `hop.port.toString()` — same reasoning as every other
 * numeric field in this form (e.g. the top-level `sshTunnelPort` this
 * replaces used to be a `String`): rendering directly off a parsed Int
 * fights the user while they're mid-edit (clearing the field would jump
 * back to a digit instead of going blank). `remember(hop.id)` keyed the
 * same as the `key(hop.id)` wrapper around this card in the caller, so the
 * buffer follows its own hop across a reorder instead of getting mixed up
 * with whichever hop now sits at the same list position.
 */
@Composable
private fun SshTunnelHopCard(
    hop: SshJumpHop,
    hopNumber: Int,
    hopCount: Int,
    isFirst: Boolean,
    isLast: Boolean,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    onChange: (SshJumpHop) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var portText by remember(hop.id) { mutableStateOf(hop.port.toString()) }
    // VALIDATION (item 3): a hop that's missing a required field — or picked
    // PRIVATE_KEY auth but hasn't pasted a key yet — gets a warning-tinted
    // border so it's obvious *which* hop is blocking Save (canSave in
    // ProfileFormDialog already requires every hop to satisfy this same
    // check before the Save button enables).
    val incomplete = !hop.isValid || (hop.authType == SshAuthType.PRIVATE_KEY && hop.privateKey.isBlank())

    Surface(
        shape    = RoundedCornerShape(16.dp),
        color    = NebulaSurface.copy(alpha = 0.55f),
        border   = BorderStroke(1.dp, if (incomplete) SolarFlare.copy(alpha = 0.45f) else HorizonGray.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier             = Modifier.padding(12.dp),
            verticalArrangement  = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(PulsarCyan.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$hopNumber",
                        color      = PulsarCyan,
                        style      = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text       = stringResource(R.string.ssh_tunnel_chain_hop_label, hopNumber, hopCount),
                    color      = StarDust,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.weight(1f),
                )
                IconButton(onClick = onMoveUp, enabled = !isFirst, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.ssh_tunnel_chain_move_up),
                        tint = if (isFirst) CometTail.copy(alpha = 0.3f) else CometTail,
                    )
                }
                IconButton(onClick = onMoveDown, enabled = !isLast, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.ssh_tunnel_chain_move_down),
                        tint = if (isLast) CometTail.copy(alpha = 0.3f) else CometTail,
                    )
                }
                IconButton(onClick = onDelete, enabled = hopCount > 1, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.ssh_tunnel_chain_remove_hop),
                        tint = if (hopCount > 1) NovaPink else CometTail.copy(alpha = 0.3f),
                    )
                }
            }

            if (incomplete) {
                Text(
                    text  = stringResource(R.string.ssh_tunnel_chain_incomplete),
                    color = SolarFlare,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            SshTunnelSection(
                host                  = hop.host,
                onHostChange          = { onChange(hop.copy(host = it.normalizeDigits())) },
                port                  = portText,
                onPortChange          = { newText ->
                    val filtered = newText.normalizeDigits().filter(Char::isDigit)
                    portText = filtered
                    onChange(hop.copy(port = filtered.toIntOrNull() ?: 0))
                },
                isPortError           = portText.isNotBlank() && !isPortInRange(portText),
                username              = hop.username,
                onUsernameChange      = { onChange(hop.copy(username = it)) },
                authType              = hop.authType,
                onAuthTypeChange      = { onChange(hop.copy(authType = it)) },
                password              = hop.password,
                onPasswordChange      = { onChange(hop.copy(password = it)) },
                passwordVisible       = passwordVisible,
                onTogglePassword      = onTogglePassword,
                privateKey            = hop.privateKey,
                onPrivateKeyChange    = { onChange(hop.copy(privateKey = it)) },
                keyPassphrase         = hop.privateKeyPassphrase,
                onKeyPassphraseChange = { onChange(hop.copy(privateKeyPassphrase = it)) },
            )
        }
    }
}

// ── SSH Tunnel Section ────────────────────────────────────────────────────────
/**
 * Reusable SSH Tunnel jump-host fields — one hop's worth — used by
 * [SshTunnelHopChainEditor] above to render every hop in the chain
 * (previously this rendered the single, only jump host directly inside the
 * "SSH Tunnel" SettingsCard in ProfileFormDialog). The on/off switch and
 * expand/collapse state live on the outer card header, so this composable
 * only ever renders one hop's fields.
 */
@Composable
fun SshTunnelSection(
    host:                  String,
    onHostChange:          (String) -> Unit,
    port:                  String,
    onPortChange:          (String) -> Unit,
    isPortError:           Boolean = false,
    username:              String,
    onUsernameChange:      (String) -> Unit,
    authType:              SshAuthType,
    onAuthTypeChange:      (SshAuthType) -> Unit,
    password:              String,
    onPasswordChange:      (String) -> Unit,
    passwordVisible:       Boolean,
    onTogglePassword:      () -> Unit,
    privateKey:            String,
    onPrivateKeyChange:    (String) -> Unit,
    keyPassphrase:         String,
    onKeyPassphraseChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // Jump-host connection fields
            SpaceTextField(
                value         = host,
                onValueChange = onHostChange,
                label         = stringResource(R.string.ssh_tunnel_host),
                icon          = Icons.Outlined.Hub,
            )
            SpaceTextField(
                value         = port,
                onValueChange = onPortChange,
                label         = stringResource(R.string.ssh_tunnel_port),
                icon          = Icons.Outlined.SettingsEthernet,
                keyboardType  = KeyboardType.Number,
                isError       = isPortError,
            )
            SpaceTextField(
                value         = username,
                onValueChange = onUsernameChange,
                label         = stringResource(R.string.ssh_tunnel_username),
                icon          = Icons.Outlined.Person,
            )

            // Auth type selector
            Text(
                text  = stringResource(R.string.ssh_tunnel_auth),
                style = MaterialTheme.typography.labelMedium,
                color = CometTail,
            )
            Row(
                modifier             = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AuthTypeChip(
                    label    = stringResource(R.string.ssh_auth_password),
                    selected = authType == SshAuthType.PASSWORD,
                    onClick  = { onAuthTypeChange(SshAuthType.PASSWORD) },
                    modifier = Modifier.weight(1f),
                )
                AuthTypeChip(
                    label    = stringResource(R.string.ssh_auth_key),
                    selected = authType == SshAuthType.PRIVATE_KEY,
                    onClick  = { onAuthTypeChange(SshAuthType.PRIVATE_KEY) },
                    modifier = Modifier.weight(1f),
                )
            }

            // Password auth
            AnimatedVisibility(
                visible = authType == SshAuthType.PASSWORD,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut(),
            ) {
                SpaceTextField(
                    value            = password,
                    onValueChange    = onPasswordChange,
                    label            = stringResource(R.string.ssh_tunnel_password),
                    icon             = Icons.Outlined.Lock,
                    isPassword       = true,
                    passwordVisible  = passwordVisible,
                    onTogglePassword = onTogglePassword,
                )
            }

            // Private-key auth
            AnimatedVisibility(
                visible = authType == SshAuthType.PRIVATE_KEY,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value         = privateKey,
                        onValueChange = onPrivateKeyChange,
                        label         = { Text(stringResource(R.string.ssh_tunnel_private_key), color = CometTail) },
                        placeholder   = { Text("-----BEGIN OPENSSH PRIVATE KEY-----", color = CometTail.copy(alpha = 0.6f)) },
                        minLines      = 4,
                        maxLines      = 8,
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor      = PulsarCyan,
                            unfocusedBorderColor    = InputBorder,
                            focusedLabelColor       = PulsarCyan,
                            cursorColor             = PulsarCyan,
                            focusedTextColor        = StarDust,
                            unfocusedTextColor      = StarDust,
                            focusedContainerColor   = InputBg,
                            unfocusedContainerColor = InputBg,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    )
                    SpaceTextField(
                        value         = keyPassphrase,
                        onValueChange = onKeyPassphraseChange,
                        label         = stringResource(R.string.ssh_tunnel_key_passphrase),
                        icon          = Icons.Outlined.Key,
                    )
                    GenerateKeyPairButton(
                        passphrase     = keyPassphrase,
                        onKeyGenerated = onPrivateKeyChange,
                    )
                }
            }
        }
}

/**
 * "Generate New Key Pair" entry point, shared by the direct-SSH profile auth
 * section and the SSH-tunnel auth section. Lets a user create a brand-new local
 * identity instead of having to bring one in via paste, complementing (not
 * replacing) the existing PEM-import text field this sits below.
 *
 * [passphrase] is whatever the user has already typed into the adjacent
 * passphrase field at the moment they tap Generate — if non-empty, the freshly
 * generated private key is encrypted with it, so the two fields end up
 * consistent with each other without extra prompting.
 */
@Composable
private fun GenerateKeyPairButton(
    passphrase:     String,
    onKeyGenerated: (privateKeyPem: String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var generatedKey by remember { mutableStateOf<GeneratedSshKeyPair?>(null) }
    var genError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runGeneration(algorithm: SshKeyAlgorithm) {
        isGenerating = true
        genError = false
        scope.launch(Dispatchers.Default) {
            val result = runCatching { SshKeyGenerator.generate(algorithm, passphrase) }
            withContext(Dispatchers.Main) {
                isGenerating = false
                result.onSuccess { generated ->
                    onKeyGenerated(generated.privateKeyPem)
                    generatedKey = generated
                    showPicker = false
                }.onFailure {
                    genError = true
                }
            }
        }
    }

    OutlinedButton(
        onClick  = { showPicker = true },
        modifier = Modifier.fillMaxWidth(),
        colors   = ButtonDefaults.outlinedButtonColors(contentColor = PulsarCyan),
        border   = BorderStroke(1.dp, PulsarCyan.copy(alpha = 0.5f)),
    ) {
        Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ssh_generate_key_pair))
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { if (!isGenerating) showPicker = false },
            containerColor   = StarfieldSurface,
            shape            = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.ssh_generate_key_title), color = StarDust) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.ssh_generate_key_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = CometTail,
                    )
                    if (genError) {
                        Text(
                            stringResource(R.string.ssh_generate_key_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = SolarFlare,
                        )
                    }
                    if (isGenerating) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier   = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color      = PulsarCyan,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(R.string.ssh_generating_key),
                                color = StarDust,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Button(
                            onClick  = { runGeneration(SshKeyAlgorithm.ED25519) },
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.buttonColors(containerColor = PulsarCyan),
                        ) { Text(stringResource(R.string.ssh_generate_ed25519)) }
                        OutlinedButton(
                            onClick  = { runGeneration(SshKeyAlgorithm.RSA_4096) },
                            modifier = Modifier.fillMaxWidth(),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = StarDust),
                            border   = BorderStroke(1.dp, InputBorder),
                        ) { Text(stringResource(R.string.ssh_generate_rsa)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (!isGenerating) showPicker = false }) {
                    Text(stringResource(R.string.cancel), color = CometTail)
                }
            },
        )
    }

    generatedKey?.let { key ->
        PublicKeyResultDialog(keyPair = key, onDismiss = { generatedKey = null })
    }
}

/**
 * Shown once immediately after a successful generation. The public key line is
 * the only artifact the user needs to move off-device (into the server's
 * ~/.ssh/authorized_keys) — the private key has already been placed into the
 * profile's own field by the caller and never appears here.
 */
@Composable
private fun PublicKeyResultDialog(
    keyPair:   GeneratedSshKeyPair,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var justCopied by remember { mutableStateOf(false) }

    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(1500)
            justCopied = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = StarfieldSurface,
        shape            = RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.ssh_public_key_title), color = StarDust) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.ssh_public_key_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = CometTail,
                )
                SelectionContainer {
                    Text(
                        keyPair.publicKeyLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = StarDust,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(InputBg, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    )
                }
                Text(
                    stringResource(R.string.ssh_public_key_fingerprint, keyPair.fingerprintSha256),
                    style = MaterialTheme.typography.labelSmall,
                    color = CometTail,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(keyPair.publicKeyLine))
                justCopied = true
            }) {
                Icon(
                    if (justCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = PulsarCyan,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(if (justCopied) R.string.ssh_public_key_copied else R.string.ssh_copy_public_key),
                    color = PulsarCyan,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = CometTail)
            }
        },
    )
}
