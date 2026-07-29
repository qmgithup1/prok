package com.systemsgo.hex.ui.screens.addconnection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ProtocolCatalogEntry
import com.systemsgo.hex.ui.theme.CardBorderColor
import com.systemsgo.hex.ui.theme.CometTail
import com.systemsgo.hex.ui.theme.DeepSpace
import com.systemsgo.hex.ui.theme.InputBorder
import com.systemsgo.hex.ui.theme.NebulaSurface
import com.systemsgo.hex.ui.theme.PlasmaGreen
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.SolarFlare
import com.systemsgo.hex.ui.theme.StarDust

/**
 * ADD-CONNECTION PROTOCOL PICKER — FIRST-TIME EXPERIENCE panel.
 *
 * Shown once per protocol (see [AddConnectionProtocolViewModel.hasSeenIntro] /
 * [AddConnectionProtocolViewModel.markIntroSeen]), and re-openable on demand
 * from a "What is this protocol?" affordance inside the connection editor —
 * same composable, same call site shape, called from wherever that link ends
 * up living.
 *
 * The "Preview" section is the dedicated slot for a real per-protocol
 * screenshot/illustration ([ProtocolIntroContent.previewImageRes]). Until
 * that artwork exists, [ProtocolPreviewSlot] renders a themed placeholder
 * with the exact same 20dp rounding, aspect ratio, and elevation the real
 * image will have — drop a drawable into the catalog entry and it swaps in
 * with no layout change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolIntroPanel(
    entry: ProtocolCatalogEntry,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    // i18n: protocolIntroContentFor is @Composable (it resolves every string via
    // stringResource), so it's called directly here rather than wrapped in
    // remember(entry.id) { ... } — remember's calculation lambda explicitly
    // disallows composable calls. Recomputing this small object on every
    // recomposition is cheap.
    val content = protocolIntroContentFor(entry)
    val accent = protocolAccentFor(entry.category)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DeepSpace,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(NebulaSurface),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cd_close), tint = StarDust, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                ProtocolIconMedallion(icon = protocolIconFor(entry.category), accent = accent)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(text = entry.name, color = StarDust, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(text = content.headline.substringBefore('.'), color = CometTail, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = content.headline, color = CometTail.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 20.sp)

            if (entry.isPopular) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(PlasmaGreen.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = PlasmaGreen, modifier = Modifier.size(15.dp))
                    Text(text = stringResource(R.string.protocol_intro_most_popular), color = PlasmaGreen, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = InputBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(20.dp))

            Text(text = stringResource(R.string.protocol_intro_key_features), color = StarDust, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))
            KeyFeaturesList(features = content.keyFeatures, accent = accent)

            Spacer(modifier = Modifier.height(24.dp))
            Text(text = stringResource(R.string.protocol_intro_preview_title), color = StarDust, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))
            ProtocolPreviewSlot(
                previewImageRes = content.previewImageRes,
                accent = accent,
                placeholderIcon = protocolIconFor(entry.category),
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text(text = stringResource(R.string.protocol_intro_use_cases_title), color = StarDust, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))
            content.useCases.forEach { useCase -> UseCaseRow(useCase, accent) }

            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp) // DYNAMIC-FONT FIX: was a fixed height(52.dp), which could
                    // clip "Continue" at large system font-scale settings — heightIn(min=) lets the
                    // row grow instead, same pattern as SpaceButton in Components.kt.
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(PulsarCyan, PlasmaGreen)))
                    .clickable(onClick = onContinue),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = stringResource(R.string.protocol_intro_continue), color = DeepSpace, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    // RTL FIX: was a literal "→" glyph, which doesn't mirror for RTL layouts.
                    // AutoMirrored.Filled.ArrowForward flips automatically with LocalLayoutDirection.
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = DeepSpace, modifier = Modifier.size(18.dp))
                }
            }

            if (content.learnMoreUrl != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = stringResource(R.string.protocol_intro_learn_more_fmt, entry.name), color = PulsarCyan, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = PulsarCyan, modifier = Modifier.size(14.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ProtocolIconMedallion(icon: androidx.compose.ui.graphics.vector.ImageVector, accent: androidx.compose.ui.graphics.Color) {
    Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .border(BorderStroke(1.dp, accent.copy(alpha = 0.25f)), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun KeyFeaturesList(features: List<ProtocolFeature>, accent: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NebulaSurface.copy(alpha = 0.6f))
            .border(BorderStroke(1.dp, CardBorderColor.copy(alpha = 0.2f)), RoundedCornerShape(16.dp)),
    ) {
        features.forEachIndexed { index, feature ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(feature.icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = feature.title, color = StarDust, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(text = feature.subtitle, color = CometTail, fontSize = 12.sp)
                }
            }
            if (index != features.lastIndex) {
                HorizontalDivider(color = InputBorder.copy(alpha = 0.5f), thickness = 1.dp, modifier = Modifier.padding(start = 62.dp))
            }
        }
    }
}

/**
 * The dedicated image slot for "Preview". Renders [previewImageRes] with 20dp
 * rounded corners at a 16:10 aspect ratio when one is supplied; otherwise
 * shows a themed placeholder at the exact same size/rounding so a real
 * screenshot can be dropped in later with zero layout changes.
 */
@Composable
private fun ProtocolPreviewSlot(previewImageRes: Int?, accent: androidx.compose.ui.graphics.Color, placeholderIcon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(NebulaSurface, DeepSpace)),
            )
            .border(BorderStroke(1.dp, CardBorderColor.copy(alpha = 0.25f)), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (previewImageRes != null) {
            Image(
                painter = painterResource(id = previewImageRes),
                contentDescription = stringResource(R.string.protocol_intro_preview_title),
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            // Placeholder: swap for a real per-protocol screenshot via
            // ProtocolIntroContent.previewImageRes — this box keeps the
            // exact rounding/aspect ratio the real artwork will use.
            Icon(
                imageVector = placeholderIcon,
                contentDescription = null,
                tint = accent.copy(alpha = 0.25f),
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun UseCaseRow(text: String, accent: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = text, color = CometTail, fontSize = 13.sp)
    }
}
