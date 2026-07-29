package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.systemsgo.hex.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.systemsgo.hex.netconf.protocol.NetconfDatastore
import com.systemsgo.hex.netconf.xml.XmlAutoComplete
import com.systemsgo.hex.netconf.xml.XmlDiff
import com.systemsgo.hex.netconf.xml.XmlHighlighter
import com.systemsgo.hex.netconf.xml.XmlValidator

/**
 * XML-EDITOR FEATURE: a syntax-highlighted, validated, auto-completing XML
 * text editor — the professional editor the RPC Builder (and, via
 * [initialValue]/`key(...)` at the call site, the Saved RPC Library "load
 * into builder" action) uses instead of a plain [OutlinedTextField].
 *
 * Owns its [TextFieldValue] (cursor/selection included, which the
 * auto-complete popup needs) rather than being a fully controlled
 * component — the call site forces a remount via `key(...)` when it needs
 * to replace the whole document programmatically (loading a template),
 * which is the standard Compose idiom for "uncontrolled-but-resettable".
 */
@Composable
fun XmlCodeEditor(
    initialValue: String,
    onValueChange: (String) -> Unit,
    knownTagNames: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    minHeight: androidx.compose.ui.unit.Dp = 160.dp,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(initialValue)) }
    val validation = remember(tfv.text) { XmlValidator.validate(tfv.text) }
    val suggestions = remember(tfv.text, tfv.selection) {
        if (tfv.selection.collapsed) {
            XmlAutoComplete.suggest(tfv.text.substring(0, tfv.selection.start.coerceIn(0, tfv.text.length)), knownTagNames)
        } else emptyList()
    }

    Column(modifier) {
        BasicTextField(
            value = tfv,
            onValueChange = { new -> tfv = new; onValueChange(new.text) },
            visualTransformation = { text -> TransformedText(XmlHighlighter.highlight(text.text), OffsetMapping.Identity) },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFD4D4D4)),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .background(Color(0xFF1E1E1E), MaterialTheme.shapes.small)
                .padding(10.dp),
        )
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(suggestions) { s ->
                    AssistChip(
                        onClick = {
                            val (newText, newCursor) = XmlAutoComplete.applySuggestion(tfv.text, tfv.selection.start, s)
                            tfv = TextFieldValue(newText, TextRange(newCursor))
                            onValueChange(newText)
                        },
                        label = { Text(s, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (!validation.isWellFormed) {
            Text(
                "⚠ ${validation.errorMessage ?: stringResource(R.string.netconf_xml_invalid)}" + (validation.errorLine?.let { " (line $it)" } ?: ""),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            Text(stringResource(R.string.netconf_xml_well_formed), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ── Diff Tool tab: manual paste-and-compare + "Compare Running vs Candidate" ──

@Composable
fun NetconfDiffTab(viewModel: NetconfSessionViewModel) {
    val clipboard = LocalClipboardManager.current
    var leftXml by remember { mutableStateOf("") }
    var rightXml by remember { mutableStateOf("") }
    val defaultLeftLabel = stringResource(R.string.netconf_diff_left_label)
    val defaultRightLabel = stringResource(R.string.netconf_diff_right_label)
    var leftLabel by remember { mutableStateOf(defaultLeftLabel) }
    var rightLabel by remember { mutableStateOf(defaultRightLabel) }
    var loadKey by remember { mutableIntStateOf(0) }
    var diffLines by remember { mutableStateOf<List<XmlDiff.DiffLine>?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun loadDatastore(target: NetconfDatastore, isLeft: Boolean) {
        loading = true
        viewModel.fetchConfigForDiff(target) { result ->
            loading = false
            if (result != null) {
                if (isLeft) { leftXml = result; leftLabel = target.elementName } else { rightXml = result; rightLabel = target.elementName }
                loadKey++
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.netconf_xml_diff_tool), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Compare any two XML documents line-by-line, or quickly diff Running vs Candidate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                loadDatastore(NetconfDatastore.RUNNING, isLeft = true)
                loadDatastore(NetconfDatastore.CANDIDATE, isLeft = false)
            }) {
                Icon(Icons.Outlined.CompareArrows, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.netconf_xml_running_vs_candidate))
            }
            OutlinedButton(onClick = { loadDatastore(NetconfDatastore.RUNNING, isLeft = true) }) { Text(stringResource(R.string.netconf_xml_load_running_a)) }
            OutlinedButton(onClick = { loadDatastore(NetconfDatastore.STARTUP, isLeft = false) }) { Text(stringResource(R.string.netconf_xml_load_startup_b)) }
        }
        Spacer(Modifier.height(8.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(leftLabel, style = MaterialTheme.typography.labelMedium)
                key("left-$loadKey") {
                    XmlCodeEditor(initialValue = leftXml, onValueChange = { leftXml = it }, minHeight = 120.dp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(rightLabel, style = MaterialTheme.typography.labelMedium)
                key("right-$loadKey") {
                    XmlCodeEditor(initialValue = rightXml, onValueChange = { rightXml = it }, minHeight = 120.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { diffLines = XmlDiff.diff(leftXml, rightXml) }) { Text(stringResource(R.string.netconf_xml_compare)) }
            OutlinedButton(onClick = {
                val text = diffLines?.joinToString("\n") { l ->
                    val marker = when (l.type) { XmlDiff.LineChangeType.ADDED -> "+"; XmlDiff.LineChangeType.REMOVED -> "-"; else -> " " }
                    "$marker ${l.text}"
                } ?: return@OutlinedButton
                clipboard.setText(AnnotatedString(text))
            }) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.netconf_xml_copy_diff))
            }
        }
        Spacer(Modifier.height(8.dp))

        diffLines?.let { lines ->
            val stats = XmlDiff.stats(lines)
            Text(
                "+${stats.added}  −${stats.removed}  ${stats.unchanged} unchanged",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(lines) { line ->
                    val (bg, prefix) = when (line.type) {
                        XmlDiff.LineChangeType.ADDED -> Color(0x3320C020) to "+"
                        XmlDiff.LineChangeType.REMOVED -> Color(0x33D02020) to "−"
                        XmlDiff.LineChangeType.UNCHANGED -> Color.Transparent to " "
                    }
                    Row(Modifier.fillMaxWidth().background(bg).padding(vertical = 1.dp)) {
                        Text(
                            "$prefix ${line.text}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
