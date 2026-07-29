package com.systemsgo.hex.ui.screens.addconnection

/**
 * ADD-CONNECTION PROTOCOL PICKER (Part 2/2) — "Request a Protocol" sheet.
 *
 * Reached two ways from [AddConnectionProtocolScreen], both routed through
 * [AddConnectionRoute]:
 *   - Tapping a catalog card whose [ProtocolCatalogEntry.protocolType] is
 *     null (e.g. SFTP, SMB, Modbus TCP — see ProtocolCatalog's class doc for
 *     why these exist in the catalog without a client yet) — [entry] is that
 *     specific protocol.
 *   - The picker's own bottom "Can't find what you need?" CTA — [entry] is
 *     null (generic request, not tied to any one catalog entry).
 *
 * No in-app feedback backend exists yet, so this reuses the same
 * mailto pattern SettingsAboutScreen's "Email" contact item already uses
 * (support@gotohex.dev via ACTION_SENDTO) rather than inventing a new one —
 * just pre-filled with which protocol was requested.
 */

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ProtocolCatalogEntry
import com.systemsgo.hex.ui.theme.CometTail
import com.systemsgo.hex.ui.theme.DeepSpace
import com.systemsgo.hex.ui.theme.NebulaSurface
import com.systemsgo.hex.ui.theme.PlasmaGreen
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.StarDust

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestProtocolSheet(
    entry: ProtocolCatalogEntry?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val noAppToOpenLink = stringResource(R.string.error_no_app_to_open_link)
    // i18n: resolved up front — stringResource can't be called from inside the
    // clickable{} lambda below, which isn't a @Composable scope.
    val requestSubjectSpecific = entry?.let { stringResource(R.string.protocol_request_email_subject_specific_fmt, appName, it.name) }
    val requestSubjectGeneric = stringResource(R.string.protocol_request_email_subject_generic_fmt, appName)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DeepSpace,
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
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

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(PulsarCyan.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = PulsarCyan, modifier = Modifier.size(28.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (entry != null) stringResource(R.string.protocol_request_title_specific_fmt, entry.name) else stringResource(R.string.protocol_request_title_generic),
                color = StarDust,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (entry != null) {
                    stringResource(R.string.protocol_request_desc_specific_fmt, entry.name)
                } else {
                    stringResource(R.string.protocol_request_desc_generic)
                },
                color = CometTail,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp) // DYNAMIC-FONT FIX: was a fixed height(52.dp) — see
                    // ProtocolIntroPanel's Continue button for the same reasoning.
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(PulsarCyan, PlasmaGreen)))
                    .clickable {
                        val subject = requestSubjectSpecific ?: requestSubjectGeneric
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:support@gotohex.dev")
                            putExtra(Intent.EXTRA_SUBJECT, subject)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, noAppToOpenLink, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = DeepSpace, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.protocol_request_email_button), color = DeepSpace, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
