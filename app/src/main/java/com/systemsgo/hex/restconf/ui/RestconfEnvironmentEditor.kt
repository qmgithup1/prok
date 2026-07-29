package com.systemsgo.hex.restconf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RestconfEnvironment
import com.systemsgo.hex.data.repository.parseVariableLines
import com.systemsgo.hex.ui.theme.PlasmaGreen

/**
 * RESTCONF FEATURE (Part 5): the "Environments" tab of the API Explorer
 * sheet — list of [RestconfEnvironment]s for the current profile, one of
 * which can be the active one (its variables get auto-substituted into
 * every `{{name}}` placeholder on send, see
 * [com.systemsgo.hex.restconf.protocol.RestconfTemplateEngine]). Tapping a
 * row's radio selects/deselects it as active; the pencil opens
 * [RestconfEnvironmentVariablesDialog] to edit its variable list; "New
 * environment" creates an empty one and opens straight into that same
 * dialog so the very next thing the user does is add its first variable.
 */
@Composable
fun RestconfEnvironmentList(
    environments: List<RestconfEnvironment>,
    onCreate: (String) -> Unit,
    onSetActive: (RestconfEnvironment?) -> Unit,
    onSave: (RestconfEnvironment) -> Unit,
    onDelete: (RestconfEnvironment) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNewDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var editingTarget by remember { mutableStateOf<RestconfEnvironment?>(null) }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showNewDialog = true }) { Text(stringResource(R.string.restconf_new_environment)) }
        }

        if (environments.isEmpty()) {
            Text(
                stringResource(R.string.restconf_no_environments_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(environments, key = { it.id }) { env ->
                    val variableCount = remember(env.variables) { parseVariableLines(env.variables).size }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { onSetActive(if (env.isActive) null else env) }) {
                            Icon(
                                if (env.isActive) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = if (env.isActive) stringResource(R.string.restconf_active_environment) else stringResource(R.string.restconf_set_as_active),
                                tint = if (env.isActive) PlasmaGreen else LocalContentColor.current,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(env.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                pluralStringResource(R.plurals.restconf_variable_count, variableCount, variableCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { editingTarget = env }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.restconf_edit_variables))
                        }
                        IconButton(onClick = { onDelete(env) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.restconf_delete_environment))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text(stringResource(R.string.restconf_new_environment)) },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.restconf_env_name_hint)) }, singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) onCreate(newName)
                    newName = ""
                    showNewDialog = false
                }) { Text(stringResource(R.string.restconf_create)) }
            },
            dismissButton = { TextButton(onClick = { showNewDialog = false; newName = "" }) { Text(stringResource(R.string.restconf_cancel)) } },
        )
    }

    editingTarget?.let { env ->
        RestconfEnvironmentVariablesDialog(
            environment = env,
            onDismiss = { editingTarget = null },
            onSave = { updated -> onSave(updated); editingTarget = null },
        )
    }
}

/**
 * Edits one environment's variables as a list of key/value rows — same
 * [RestconfKeyValueRow] shape the Request Builder's Params/Headers tabs
 * already use, encoded back to "key: value" per line on Save via
 * [parseVariableLines]'s inverse (done inline here since it's a one-liner
 * and nowhere else needs a "rows -> encoded string" helper for environments).
 */
@Composable
private fun RestconfEnvironmentVariablesDialog(
    environment: RestconfEnvironment,
    onDismiss: () -> Unit,
    onSave: (RestconfEnvironment) -> Unit,
) {
    var name by remember(environment.id) { mutableStateOf(environment.name) }
    var rows by remember(environment.id) {
        mutableStateOf(
            parseVariableLines(environment.variables).map { (k, v) -> RestconfKeyValueRow(k, v) }
                .ifEmpty { listOf(RestconfKeyValueRow("", "")) }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restconf_edit_environment)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.restconf_name_label)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                RestconfKeyValueEditor(
                    rows = rows, onRowsChange = { rows = it },
                    keyLabel = stringResource(R.string.restconf_variable_label), valueLabel = stringResource(R.string.restconf_header_value),
                )
                Text(
                    stringResource(R.string.restconf_env_variable_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val encoded = rows.filter { it.key.isNotBlank() }.joinToString("\n") { "${it.key}: ${it.value}" }
                onSave(environment.copy(name = name.ifBlank { environment.name }, variables = encoded))
            }) { Text(stringResource(R.string.restconf_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.restconf_cancel)) } },
    )
}
