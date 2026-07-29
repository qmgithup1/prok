package com.systemsgo.hex.restconf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RestconfDataFormat
import com.systemsgo.hex.restconf.protocol.RestconfFormatting
import com.systemsgo.hex.restconf.protocol.RestconfResponse
import com.systemsgo.hex.ui.theme.NovaPink
import com.systemsgo.hex.ui.theme.PlasmaGreen
import com.systemsgo.hex.ui.theme.SolarFlare

private enum class ResponseTab { PRETTY, TREE, RAW, HEADERS, DIFF }

private fun ResponseTab.labelRes(): Int = when (this) {
    ResponseTab.PRETTY -> R.string.restconf_tab_pretty
    ResponseTab.TREE -> R.string.restconf_tab_tree
    ResponseTab.RAW -> R.string.restconf_tab_raw
    ResponseTab.HEADERS -> R.string.restconf_tab_headers
    ResponseTab.DIFF -> R.string.restconf_tab_diff
}

/**
 * RESTCONF FEATURE (Part 2/4, Diff tab added Part 4/4): renders one
 * [RestconfResponse] across five views that all read the same underlying
 * body/headers — Pretty (format-detected + syntax-highlighted), Tree (via
 * [RestconfTreeView]), Raw (unmodified bytes as received), Headers
 * (name/value list), and Diff (line-diff against the previous response in
 * this session, or a pinned baseline — see [RestconfDiffViewer]).
 * `onExport` is wired by the caller to `ActivityResultContracts.CreateDocument`,
 * same SAF pattern [com.systemsgo.hex.ui.screens.BmcManagementScreen] already
 * uses for the SOL log export — kept as a callback here so this composable
 * has no Activity/launcher dependency of its own.
 */
@Composable
fun RestconfResponseViewer(
    response: RestconfResponse,
    onExport: () -> Unit,
    previousResponse: RestconfResponse? = null,
    baselineResponse: RestconfResponse? = null,
    onPinBaseline: () -> Unit = {},
    onClearBaseline: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var tab by remember(response) { mutableStateOf(ResponseTab.PRETTY) }
    val clipboard = LocalClipboardManager.current
    val format = response.detectedFormat
    val isPinnedAsBaseline = baselineResponse != null && baselineResponse == response

    Column(modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "${response.statusCode} ${response.statusMessage}",
                color = if (response.isSuccess) PlasmaGreen else NovaPink,
                style = MaterialTheme.typography.labelLarge,
            )
            Text("${response.elapsedMillis} ms", style = MaterialTheme.typography.labelMedium)
            Text(formatSize(response.sizeBytes), style = MaterialTheme.typography.labelMedium)
            response.protocol?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { if (isPinnedAsBaseline) onClearBaseline() else onPinBaseline() }) {
                Icon(
                    if (isPinnedAsBaseline) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (isPinnedAsBaseline) stringResource(R.string.restconf_unpin_baseline) else stringResource(R.string.restconf_pin_as_baseline),
                    tint = if (isPinnedAsBaseline) SolarFlare else LocalContentColor.current,
                )
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(response.body.orEmpty())) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.restconf_copy_response))
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.restconf_export_response))
            }
        }

        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
            ResponseTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(t.labelRes())) })
            }
        }

        Box(Modifier.fillMaxSize().padding(top = 4.dp)) {
            when (tab) {
                ResponseTab.PRETTY -> PrettyView(response.body.orEmpty(), format)
                ResponseTab.TREE -> TreeViewOrEmpty(response.body.orEmpty(), format)
                ResponseTab.RAW -> RawView(response.body.orEmpty())
                ResponseTab.HEADERS -> HeadersView(response.headers)
                ResponseTab.DIFF -> DiffTab(response, previousResponse, baselineResponse)
            }
        }
    }
}

@Composable
private fun DiffTab(current: RestconfResponse, previous: RestconfResponse?, baseline: RestconfResponse?) {
    val previousLabel = stringResource(R.string.restconf_diff_previous)
    val baselineLabel = stringResource(R.string.restconf_diff_baseline)
    val options = buildList {
        if (previous != null) add(previousLabel to previous)
        if (baseline != null) add(baselineLabel to baseline)
    }
    if (options.isEmpty()) {
        Text(
            stringResource(R.string.restconf_diff_nothing_to_compare),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp),
        )
        return
    }
    var selected by remember(options) { mutableStateOf(0) }
    val (label, against) = options[selected]
    val format = current.detectedFormat

    Column(Modifier.fillMaxSize()) {
        if (options.size > 1) {
            Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { i, (optLabel, _) ->
                    FilterChip(selected = i == selected, onClick = { selected = i }, label = { Text(stringResource(R.string.restconf_diff_vs, optLabel)) })
                }
            }
        }
        RestconfDiffViewer(
            oldLabel = label,
            oldBody = RestconfFormatting.prettyPrint(against.body.orEmpty(), format),
            newLabel = stringResource(R.string.restconf_diff_current),
            newBody = RestconfFormatting.prettyPrint(current.body.orEmpty(), format),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PrettyView(body: String, format: RestconfDataFormat) {
    val pretty = remember(body, format) { RestconfFormatting.prettyPrint(body, format) }
    SelectionContainer {
        Text(
            pretty,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun TreeViewOrEmpty(body: String, format: RestconfDataFormat) {
    val tree = remember(body, format) { RestconfFormatting.buildTree(body, format) }
    if (tree == null) {
        Text(
            stringResource(R.string.restconf_body_invalid_showing_raw, format.toString()),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp),
        )
        RawView(body)
    } else {
        com.systemsgo.hex.restconf.ui.RestconfTreeView(tree, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun RawView(body: String) {
    SelectionContainer {
        Text(
            body.ifEmpty { stringResource(R.string.restconf_empty_body) },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun HeadersView(headers: Map<String, List<String>>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(headers.entries.toList()) { (name, values) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.4f))
                Text(values.joinToString(", "), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f))
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
