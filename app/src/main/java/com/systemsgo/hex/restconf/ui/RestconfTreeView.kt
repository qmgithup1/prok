package com.systemsgo.hex.restconf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.restconf.protocol.RestconfTreeNode
import com.systemsgo.hex.restconf.protocol.RestconfTreeNodeKind
import com.systemsgo.hex.ui.theme.NovaPink
import com.systemsgo.hex.ui.theme.PlasmaGreen
import com.systemsgo.hex.ui.theme.PulsarCyan
import com.systemsgo.hex.ui.theme.QuantumBlue
import com.systemsgo.hex.ui.theme.SolarFlare

/**
 * RESTCONF FEATURE (Part 2/4): flat, lazily-composed tree — rows are
 * pre-flattened with a depth + expanded-path key rather than nested
 * Composables per level, so a large YANG datastore subtree (Part 3 reuses
 * this same component) scrolls smoothly instead of composing thousands of
 * nested Rows up front. Expansion state is a path->Boolean map keyed by the
 * dotted path to each node (stable across recompositions triggered by a new
 * response replacing the root, as long as matching paths repeat — e.g.
 * re-running the same GET).
 */
@Composable
fun RestconfTreeView(root: RestconfTreeNode, modifier: Modifier = Modifier) {
    val expanded = remember(root) { mutableStateMapOf<String, Boolean>().apply { put("", true) } }
    LazyColumn(modifier = modifier) {
        flattenNode(this, root, path = "", depth = 0, expanded = expanded)
    }
}

private fun flattenNode(
    scope: LazyListScope,
    node: RestconfTreeNode,
    path: String,
    depth: Int,
    expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
) {
    scope.item(key = path.ifEmpty { "root" }) {
        val isExpanded = expanded[path] ?: (depth == 0)
        TreeRow(
            node = node,
            depth = depth,
            isExpanded = isExpanded,
            onToggle = { expanded[path] = !isExpanded },
        )
    }
    val isExpanded = expanded[path] ?: (depth == 0)
    if (isExpanded) {
        node.children.forEachIndexed { i, child ->
            flattenNode(scope, child, path = "$path/$i:${child.name}", depth = depth + 1, expanded = expanded)
        }
    }
}

@Composable
private fun TreeRow(node: RestconfTreeNode, depth: Int, isExpanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(enabled = !node.isLeaf, onClick = onToggle)
            .padding(start = (depth * 16).dp, top = 3.dp, bottom = 3.dp, end = 8.dp),
    ) {
        if (!node.isLeaf) {
            Icon(
                if (isExpanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = LocalContentColor.current.copy(alpha = 0.6f),
                modifier = Modifier.width(18.dp),
            )
        } else {
            Spacer(Modifier.width(18.dp))
        }
        Text(
            node.name,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = QuantumBlue,
        )
        if (node.isLeaf || node.valuePreview.isNotEmpty()) {
            Text(
                if (node.isLeaf) ": ${valueDisplay(node)}" else " ${node.valuePreview}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colorFor(node.kind),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        node.attributes.forEach { (k, v) ->
            Text(
                " $k=\"$v\"",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = SolarFlare,
            )
        }
    }
}

private fun valueDisplay(node: RestconfTreeNode): String = when (node.kind) {
    RestconfTreeNodeKind.STRING -> "\"${node.valuePreview}\""
    else -> node.valuePreview
}

@Composable
private fun colorFor(kind: RestconfTreeNodeKind) = when (kind) {
    RestconfTreeNodeKind.STRING -> PlasmaGreen
    RestconfTreeNodeKind.NUMBER -> SolarFlare
    RestconfTreeNodeKind.BOOLEAN, RestconfTreeNodeKind.NULL -> PulsarCyan
    RestconfTreeNodeKind.OBJECT, RestconfTreeNodeKind.ARRAY -> NovaPink.copy(alpha = 0.7f)
}
