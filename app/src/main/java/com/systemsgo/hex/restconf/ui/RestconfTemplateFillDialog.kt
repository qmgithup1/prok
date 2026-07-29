package com.systemsgo.hex.restconf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.RestconfSavedRequest

/**
 * RESTCONF FEATURE (Part 4/4): shown instead of loading a
 * [RestconfSavedRequest] straight into the Request Builder when
 * [com.systemsgo.hex.restconf.protocol.RestconfTemplateEngine.extractPlaceholders]
 * finds one or more `{{name}}` placeholders in it — one text field per
 * distinct placeholder (pre-filled with whatever was typed last time this
 * dialog was open for the same template within this composition, via
 * `remember(request.id)`, so re-running the same template repeatedly with
 * mostly-the-same values doesn't mean retyping everything). "Use template"
 * hands the filled-in values back to the caller, which does the actual
 * substitution via [com.systemsgo.hex.restconf.protocol.RestconfTemplateEngine.substitute]
 * and loads the result — this dialog itself never touches request state.
 */
@Composable
fun RestconfTemplateFillDialog(
    request: RestconfSavedRequest,
    placeholders: List<String>,
    onDismiss: () -> Unit,
    onApply: (Map<String, String>) -> Unit,
) {
    val values = remember(request.id) { mutableStateMapOf<String, String>().apply { placeholders.forEach { put(it, "") } } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restconf_fill_in_template, request.name)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    pluralStringResource(R.plurals.restconf_placeholder_count, placeholders.size, placeholders.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                placeholders.forEach { name ->
                    OutlinedTextField(
                        value = values[name].orEmpty(),
                        onValueChange = { values[name] = it },
                        label = { Text(name) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(values.toMap()) }) { Text(stringResource(R.string.restconf_use_template)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.restconf_cancel)) }
        },
    )
}
