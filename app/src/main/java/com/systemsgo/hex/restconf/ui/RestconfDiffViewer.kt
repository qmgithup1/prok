package com.systemsgo.hex.restconf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.systemsgo.hex.R
import com.systemsgo.hex.restconf.protocol.RestconfDiffEngine
import com.systemsgo.hex.ui.theme.NovaPink
import com.systemsgo.hex.ui.theme.PlasmaGreen

/**
 * RESTCONF FEATURE (Part 4/4): renders a [RestconfDiffEngine.Result] as a
 * unified (GitHub-style, single-column +/-) diff — old-vs-new line numbers
 * in a narrow gutter, a +/-/space marker, then the line text, with a tinted
 * row background for added/removed lines. `oldLabel`/`newLabel` are shown in
 * the header so the caller (Response Viewer's Diff tab) can make clear
 * *which* two responses are being compared (e.g. "Previous → Current" or
 * "Baseline → Current").
 */
@Composable
fun RestconfDiffViewer(
    oldLabel: String,
    oldBody: String,
    newLabel: String,
    newBody: String,
    modifier: Modifier = Modifier,
) {
    val diff = remember(oldBody, newBody) { RestconfDiffEngine.diffLines(oldBody, newBody) }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.restconf_diff_header, oldLabel, newLabel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                Text("+${diff.addedCount}", style = MaterialTheme.typography.labelMedium, color = PlasmaGreen)
                Text("  -${diff.removedCount}", style = MaterialTheme.typography.labelMedium, color = NovaPink)
            }
        }
        Spacer(Modifier.height(4.dp))

        when {
            diff.truncated -> Text(
                stringResource(R.string.restconf_diff_too_large),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            diff.isIdentical -> {
                Text(
                    stringResource(R.string.restconf_diff_identical, oldLabel, newLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlasmaGreen,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                DiffLineList(diff.lines)
            }
            else -> DiffLineList(diff.lines)
        }
    }
}

@Composable
private fun DiffLineList(lines: List<RestconfDiffEngine.Line>) {
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize()) {
            items(lines) { line -> DiffLineRow(line) }
        }
    }
}

@Composable
private fun DiffLineRow(line: RestconfDiffEngine.Line) {
    val (bg, marker, markerColor) = when (line.op) {
        RestconfDiffEngine.Op.ADDED -> Triple(PlasmaGreen.copy(alpha = 0.12f), "+", PlasmaGreen)
        RestconfDiffEngine.Op.REMOVED -> Triple(NovaPink.copy(alpha = 0.12f), "-", NovaPink)
        RestconfDiffEngine.Op.EQUAL -> Triple(Color.Transparent, " ", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val gutter = line.oldLineNumber?.toString().orEmpty().padStart(4) + " " + (line.newLineNumber?.toString().orEmpty()).padStart(4)

    Row(
        Modifier.fillMaxWidth().background(bg).padding(vertical = 1.dp),
    ) {
        Text(
            gutter,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            marker,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = markerColor,
        )
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
