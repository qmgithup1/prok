package com.systemsgo.hex.restconf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RestconfCollection
import com.systemsgo.hex.data.model.RestconfEnvironment
import com.systemsgo.hex.data.model.RestconfHistoryEntry
import com.systemsgo.hex.data.model.RestconfSavedRequest
import com.systemsgo.hex.restconf.protocol.RestconfTemplateEngine
import com.systemsgo.hex.ui.theme.NovaPink
import com.systemsgo.hex.ui.theme.PlasmaGreen
import com.systemsgo.hex.ui.theme.PulsarCyan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ExplorerTab { SAVED, FAVORITES, RECENT, COLLECTIONS, ENVIRONMENTS }

private fun ExplorerTab.labelRes(): Int = when (this) {
    ExplorerTab.SAVED -> R.string.restconf_tab_saved
    ExplorerTab.FAVORITES -> R.string.restconf_tab_favorites
    ExplorerTab.RECENT -> R.string.restconf_tab_recent
    ExplorerTab.COLLECTIONS -> R.string.restconf_tab_collections
    ExplorerTab.ENVIRONMENTS -> R.string.restconf_tab_environments
}

/**
 * True if any of a [RestconfSavedRequest]'s editable fields carry a
 * `{{placeholder}}` that isn't already covered by [environment] or a
 * builtin — see [RestconfTemplateEngine.needsFillDialog]. Checked in exactly
 * one place so the "does this need the Fill dialog" definition can't drift
 * between the list badge and the load-time interception.
 *
 * RESTCONF FEATURE (Part 5): [environment] is the active
 * [RestconfEnvironment]'s resolved variables — a saved request whose only
 * placeholders are covered by it loads straight in, auto-substituted, with
 * no prompt at all (same UX as a plain saved request pre-Part-4).
 */
private fun RestconfSavedRequest.needsFillDialog(environment: Map<String, String>): Boolean =
    RestconfTemplateEngine.needsFillDialog(path, body, headers, queryParams, environment = environment)

/** True if the request has *any* placeholder at all (regardless of whether it's covered by the environment) — purely for the template badge in the list, which should still hint "this is a template" even when the active environment happens to cover every placeholder right now. */
private fun RestconfSavedRequest.isTemplate(): Boolean =
    RestconfTemplateEngine.hasPlaceholders(path, body, headers, queryParams)

/**
 * RESTCONF FEATURE (Part 3/4): the API Explorer — every request-management
 * requirement (Resource browser is the YANG Browser's Datastore tab;
 * Endpoint discovery is Modules; the rest live here) in one sheet: Saved
 * Requests, Favorites (filtered view), Recent (History, most-recent-first),
 * Collections (folders of saved requests), and a search box over Saved.
 * Backed by [com.systemsgo.hex.data.repository.RestconfExplorerRepository]
 * — every action here is a real Room write, not local-only UI state.
 *
 * RESTCONF FEATURE (Part 4/4): Request Templates — any saved request with a
 * `{{placeholder}}` in its path/body/headers/query params (see
 * [isTemplate]) routes through [RestconfTemplateFillDialog] instead of
 * loading straight into the editor; a plain saved request with no
 * placeholders is unaffected and loads exactly as it always did.
 */
@Composable
fun RestconfApiExplorerSheet(
    savedRequests: List<RestconfSavedRequest>,
    favoriteRequests: List<RestconfSavedRequest>,
    history: List<RestconfHistoryEntry>,
    collections: List<RestconfCollection>,
    onLoadRequest: (RestconfSavedRequest) -> Unit,
    onDeleteRequest: (RestconfSavedRequest) -> Unit,
    onToggleFavorite: (RestconfSavedRequest) -> Unit,
    onCreateCollection: (String) -> Unit,
    onDeleteCollection: (RestconfCollection) -> Unit,
    onReplayHistoryEntry: (RestconfHistoryEntry) -> Unit,
    onSaveCurrentRequest: (name: String, collectionId: String?) -> Unit,
    // RESTCONF FEATURE (Part 5): Environment Variables — environments is the
    // full list for this profile (for the Environments tab); environmentVariables
    // is just the *active* one's resolved map, threaded into the template
    // engine everywhere a placeholder might need auto-resolving.
    environments: List<RestconfEnvironment> = emptyList(),
    environmentVariables: Map<String, String> = emptyMap(),
    onCreateEnvironment: (String) -> Unit = {},
    onSetActiveEnvironment: (RestconfEnvironment?) -> Unit = {},
    onSaveEnvironment: (RestconfEnvironment) -> Unit = {},
    onDeleteEnvironment: (RestconfEnvironment) -> Unit = {},
    // RESTCONF FEATURE (Part 5): Import/Export — both no-ops by default so
    // every existing call site keeps compiling; the real Activity wires
    // these to RestconfCollectionBackupManager via SAF launchers.
    onExportCollection: () -> Unit = {},
    onImportCollection: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(ExplorerTab.SAVED) }
    var search by remember { mutableStateOf("") }
    var showSaveDialog by remember { mutableStateOf(false) }
    var newCollectionName by remember { mutableStateOf("") }
    var showNewCollectionDialog by remember { mutableStateOf(false) }
    // RESTCONF FEATURE (Part 4/4): set when the user taps Load on a request
    // that still needs the Fill dialog after environment/builtin resolution
    // (see needsFillDialog) — the Fill dialog below reads this, and its own
    // onApply/onDismiss are what clear it back to null.
    var templateFillTarget by remember { mutableStateOf<RestconfSavedRequest?>(null) }
    val untitledRequestLabel = stringResource(R.string.restconf_untitled_request)

    val handleLoad: (RestconfSavedRequest) -> Unit = { req ->
        if (req.needsFillDialog(environmentVariables)) {
            templateFillTarget = req
        } else {
            // Nothing left to prompt for — but the request may still carry
            // placeholders the active environment (or a builtin) covers, so
            // resolve those before handing it to the caller.
            onLoadRequest(
                req.copy(
                    path = RestconfTemplateEngine.substitute(req.path, emptyMap(), environmentVariables),
                    body = RestconfTemplateEngine.substitute(req.body, emptyMap(), environmentVariables),
                    headers = RestconfTemplateEngine.substitute(req.headers, emptyMap(), environmentVariables),
                    queryParams = RestconfTemplateEngine.substitute(req.queryParams, emptyMap(), environmentVariables),
                )
            )
        }
    }

    Column(modifier.heightIn(max = 480.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.restconf_explorer_title), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onImportCollection) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.restconf_import_collection))
                }
                IconButton(onClick = onExportCollection) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = stringResource(R.string.restconf_export_collection))
                }
                TextButton(onClick = { showSaveDialog = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.restconf_save))
                }
            }
        }

        ScrollableTabRow(selectedTabIndex = tab.ordinal) {
            ExplorerTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(t.labelRes())) })
            }
        }

        when (tab) {
            ExplorerTab.SAVED -> {
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text(stringResource(R.string.restconf_search_saved_requests)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                val filtered = savedRequests.filter {
                    search.isBlank() || it.name.contains(search, true) || it.path.contains(search, true)
                }
                SavedRequestList(filtered, handleLoad, onDeleteRequest, onToggleFavorite)
            }
            ExplorerTab.FAVORITES -> SavedRequestList(favoriteRequests, handleLoad, onDeleteRequest, onToggleFavorite)
            ExplorerTab.RECENT -> HistoryList(history, onReplayHistoryEntry)
            ExplorerTab.COLLECTIONS -> {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showNewCollectionDialog = true }) { Text(stringResource(R.string.restconf_new_collection)) }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(collections, key = { it.id }) { collection ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(collection.name, style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { onDeleteCollection(collection) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.restconf_delete_collection))
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            ExplorerTab.ENVIRONMENTS -> RestconfEnvironmentList(
                environments = environments,
                onCreate = onCreateEnvironment,
                onSetActive = onSetActiveEnvironment,
                onSave = onSaveEnvironment,
                onDelete = onDeleteEnvironment,
            )
        }
    }

    if (showSaveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.restconf_save_request_title)) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.restconf_name_label)) }, singleLine = true)
                    Text(
                        stringResource(R.string.restconf_save_request_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onSaveCurrentRequest(name.ifBlank { untitledRequestLabel }, null); showSaveDialog = false },
                ) { Text(stringResource(R.string.restconf_save)) }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.restconf_cancel)) } },
        )
    }

    if (showNewCollectionDialog) {
        AlertDialog(
            onDismissRequest = { showNewCollectionDialog = false },
            title = { Text(stringResource(R.string.restconf_new_collection)) },
            text = {
                OutlinedTextField(value = newCollectionName, onValueChange = { newCollectionName = it }, label = { Text(stringResource(R.string.restconf_name_label)) }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newCollectionName.isNotBlank()) onCreateCollection(newCollectionName)
                    newCollectionName = ""
                    showNewCollectionDialog = false
                }) { Text(stringResource(R.string.restconf_create)) }
            },
            dismissButton = { TextButton(onClick = { showNewCollectionDialog = false }) { Text(stringResource(R.string.restconf_cancel)) } },
        )
    }

    templateFillTarget?.let { req ->
        val placeholders = remember(req.id, environmentVariables) {
            RestconfTemplateEngine.extractPlaceholders(req.path, req.body, req.headers, req.queryParams, environment = environmentVariables)
        }
        RestconfTemplateFillDialog(
            request = req,
            placeholders = placeholders,
            onDismiss = { templateFillTarget = null },
            onApply = { values ->
                val filled = req.copy(
                    path = RestconfTemplateEngine.substitute(req.path, values, environmentVariables),
                    body = RestconfTemplateEngine.substitute(req.body, values, environmentVariables),
                    headers = RestconfTemplateEngine.substitute(req.headers, values, environmentVariables),
                    queryParams = RestconfTemplateEngine.substitute(req.queryParams, values, environmentVariables),
                )
                onLoadRequest(filled)
                templateFillTarget = null
            },
        )
    }
}

@Composable
private fun SavedRequestList(
    requests: List<RestconfSavedRequest>,
    onLoad: (RestconfSavedRequest) -> Unit,
    onDelete: (RestconfSavedRequest) -> Unit,
    onToggleFavorite: (RestconfSavedRequest) -> Unit,
) {
    if (requests.isEmpty()) {
        Text(stringResource(R.string.restconf_nothing_here_yet), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(requests, key = { it.id }) { req ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(req.name, style = MaterialTheme.typography.bodyMedium)
                        if (req.isTemplate()) {
                            Icon(
                                Icons.Outlined.DataObject,
                                contentDescription = stringResource(R.string.restconf_template_badge_desc),
                                tint = PulsarCyan,
                                modifier = Modifier.padding(start = 4.dp).height(14.dp),
                            )
                        }
                    }
                    Text("${req.method}  ${req.path}", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { onToggleFavorite(req) }) {
                    Icon(if (req.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = stringResource(R.string.restconf_favorite_desc))
                }
                IconButton(onClick = { onLoad(req) }) { Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.restconf_load_desc)) }
                IconButton(onClick = { onDelete(req) }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.restconf_delete_desc)) }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun HistoryList(history: List<RestconfHistoryEntry>, onReplay: (RestconfHistoryEntry) -> Unit) {
    if (history.isEmpty()) {
        Text(stringResource(R.string.restconf_no_requests_sent_yet), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        return
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(history, key = { it.id }) { entry ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${entry.method}  ${entry.path}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${timeFormat.format(Date(entry.timestamp))}  •  ${entry.elapsedMillis} ms",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    entry.statusCode.toString(),
                    color = if (entry.statusCode in 200..299) PlasmaGreen else NovaPink,
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = { onReplay(entry) }) { Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.restconf_replay_desc)) }
            }
            HorizontalDivider()
        }
    }
}
