package com.systemsgo.hex.restconf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RestconfDataFormat
import com.systemsgo.hex.restconf.protocol.RestconfFormatting
import com.systemsgo.hex.restconf.protocol.RestconfXPathHelper
import com.systemsgo.hex.restconf.protocol.YangModule

private enum class YangBrowserTab { MODULES, DATASTORE, XPATH }

private fun YangBrowserTab.labelRes(): Int = when (this) {
    YangBrowserTab.MODULES -> R.string.restconf_yang_tab_modules
    YangBrowserTab.DATASTORE -> R.string.restconf_yang_tab_datastore
    YangBrowserTab.XPATH -> R.string.restconf_yang_tab_xpath
}

/**
 * RESTCONF FEATURE (Part 3/4): the YANG Browser — Module Discovery/
 * Namespace Browser (Modules tab, backed by [com.systemsgo.hex.restconf.protocol.RestconfClient.getYangModules]),
 * Schema Explorer/Tree Navigation (Datastore tab, fetches a real resource
 * and renders it with [RestconfTreeView] — reused as-is from the Response
 * Viewer since a datastore subtree and a response body are the same shape),
 * and an XPath Helper (pure text conversion via [RestconfXPathHelper], no
 * network call).
 */
@Composable
fun RestconfYangBrowser(
    modules: List<YangModule>,
    isLoadingModules: Boolean,
    onRefreshModules: () -> Unit,
    onFetchDatastorePath: (String) -> Unit,
    datastoreResponseBody: String?,
    datastoreFormat: RestconfDataFormat,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(YangBrowserTab.MODULES) }
    var moduleSearch by remember { mutableStateOf("") }
    var datastorePath by remember { mutableStateOf("/data") }
    var xpathInput by remember { mutableStateOf("") }
    var xpathIsResourcePath by remember { mutableStateOf(true) }

    Column(modifier) {
        TabRow(selectedTabIndex = tab.ordinal) {
            YangBrowserTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(t.labelRes())) })
            }
        }
        when (tab) {
            YangBrowserTab.MODULES -> Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = moduleSearch, onValueChange = { moduleSearch = it },
                        label = { Text(stringResource(R.string.restconf_search_modules)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRefreshModules) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.restconf_refresh)) }
                }
                if (isLoadingModules) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                val filtered = modules.filter {
                    moduleSearch.isBlank() || it.name.contains(moduleSearch, ignoreCase = true) || it.namespace.contains(moduleSearch, ignoreCase = true)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered) { m ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("${m.name}${m.revision?.let { "@$it" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
                            Text(m.namespace, style = MaterialTheme.typography.labelSmall)
                        }
                        HorizontalDivider()
                    }
                    if (filtered.isEmpty() && !isLoadingModules) {
                        item {
                            Text(
                                stringResource(R.string.restconf_no_modules_loaded),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
            YangBrowserTab.DATASTORE -> Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = datastorePath, onValueChange = { datastorePath = it },
                        label = { Text(stringResource(R.string.restconf_resource_path)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onFetchDatastorePath(datastorePath) }) { Text(stringResource(R.string.restconf_load)) }
                }
                Spacer(Modifier.height(8.dp))
                val tree = remember(datastoreResponseBody, datastoreFormat) {
                    datastoreResponseBody?.let { RestconfFormatting.buildTree(it, datastoreFormat) }
                }
                if (tree == null) {
                    Text(
                        stringResource(R.string.restconf_load_resource_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    RestconfTreeView(tree, modifier = Modifier.fillMaxSize())
                }
            }
            YangBrowserTab.XPATH -> Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                Row {
                    FilterChip(selected = xpathIsResourcePath, onClick = { xpathIsResourcePath = true }, label = { Text(stringResource(R.string.restconf_path_to_xpath)) })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !xpathIsResourcePath, onClick = { xpathIsResourcePath = false }, label = { Text(stringResource(R.string.restconf_xpath_to_path)) })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = xpathInput, onValueChange = { xpathInput = it },
                    label = { Text(if (xpathIsResourcePath) stringResource(R.string.restconf_resource_path_field) else stringResource(R.string.restconf_xpath_field)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val conversionResult = remember(xpathInput, xpathIsResourcePath) {
                    if (xpathInput.isBlank()) Result.success("") else runCatching {
                        if (xpathIsResourcePath) RestconfXPathHelper.resourcePathToXPath(xpathInput)
                        else RestconfXPathHelper.xPathToResourcePath(xpathInput)
                    }
                }
                val converted = conversionResult.getOrElse {
                    stringResource(R.string.restconf_conversion_failed, it.message ?: "")
                }
                Text(
                    converted,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
                Text(
                    stringResource(R.string.restconf_xpath_key_hint),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
