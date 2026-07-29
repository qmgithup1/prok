package com.systemsgo.hex.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.systemsgo.hex.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.guacamole.GuacamoleApiException
import com.systemsgo.hex.guacamole.GuacamoleConnection
import com.systemsgo.hex.guacamole.GuacamoleConnectionGroup
import com.systemsgo.hex.guacamole.GuacamoleRepository
import com.systemsgo.hex.guacamole.GuacamoleServerConfig
import java.security.MessageDigest

/**
 * GUACAMOLE-PROTOCOL FEATURE (Part 3/N, group filtering added Part 4/N,
 * favorites/recents added Part 6/N).
 *
 * Replaces manually typing [com.systemsgo.hex.data.model.RdpProfile.guacConnectionIdentifier]
 * — logs in with the URL/credentials already entered in the connection
 * editor, lists the account's real connections via
 * [GuacamoleRepository.listConnections]/[GuacamoleRepository.listConnectionGroups],
 * and lets the user search, filter by group, and star favorites before
 * picking one.
 *
 * Group filtering is a flat "chip per group, tap to filter" row rather than
 * a full breadcrumb tree — reg.txt's "Filter by group" is satisfied by
 * either shape, and a flat row is simpler for the common case (most
 * Guacamole deployments have a shallow, one-or-two-level group structure).
 *
 * Favorites/recents are tracked CLIENT-SIDE only, in plain (non-encrypted —
 * a connection name/identifier isn't sensitive the way a credential is)
 * `SharedPreferences`, keyed by a hash of server URL + username. Guacamole's
 * own REST API has no standard favorites/recency endpoint (unlike, say,
 * "recently used" being a first-class server concept) — client-side
 * tracking is the only option here, same as how a browser tracks its own
 * "frequently visited" list independent of the site itself. Starred
 * connections sort first, then recently-picked ones, then everything else.
 *
 * Login happens fresh every time this dialog opens — no session is reused
 * from [com.systemsgo.hex.ui.screens.RdpSessionActivity]'s own per-connect
 * login, and this picker's own [GuacamoleRepository] instance is
 * deliberately constructed without persistence (no `appContext`) — see
 * that class's "Remember session" doc — since a one-off browse-and-pick
 * dialog has nothing worth remembering a token for.
 */
@Composable
fun GuacamoleConnectionPickerDialog(
    serverUrl: String,
    username: String,
    password: String,
    dataSourceHint: String,
    acceptSelfSignedCertificate: Boolean,
    onDismiss: () -> Unit,
    onConnectionPicked: (connection: GuacamoleConnection, dataSourceUsed: String) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var connections by remember { mutableStateOf<List<GuacamoleConnection>>(emptyList()) }
    var groups by remember { mutableStateOf<List<GuacamoleConnectionGroup>>(emptyList()) }
    var selectedGroupId by remember { mutableStateOf<String?>(null) } // null = "All"
    var resolvedDataSource by remember { mutableStateOf(dataSourceHint) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val prefsKey = remember(serverUrl, username) { pickerAccountKey(serverUrl, username) }
    var favorites by remember(prefsKey) { mutableStateOf(loadFavorites(context, prefsKey)) }
    var recents by remember(prefsKey) { mutableStateOf(loadRecents(context, prefsKey)) }
    val couldNotLoadConnectionsMessage = stringResource(R.string.guacamole_could_not_load_connections)

    LaunchedEffect(serverUrl, username, password, dataSourceHint, acceptSelfSignedCertificate) {
        loading = true
        errorMessage = null
        try {
            val repo = GuacamoleRepository(
                GuacamoleServerConfig(baseUrl = serverUrl.trimEnd('/'), acceptSelfSignedCertificate = acceptSelfSignedCertificate)
            )
            val session = repo.login(username, password)
            resolvedDataSource = dataSourceHint.ifBlank { session.dataSource }
            connections = repo.listConnections(resolvedDataSource)
            groups = try {
                repo.listConnectionGroups(resolvedDataSource)
            } catch (_: Exception) {
                emptyList() // Group listing failing (e.g. account lacks permission to list groups) shouldn't block the flat connection list above.
            }
        } catch (e: GuacamoleApiException) {
            errorMessage = e.message ?: couldNotLoadConnectionsMessage
        } catch (e: Exception) {
            errorMessage = e.message ?: couldNotLoadConnectionsMessage
        } finally {
            loading = false
        }
    }

    val filtered = remember(connections, query, selectedGroupId, favorites, recents) {
        connections
            .filter { selectedGroupId == null || it.parentIdentifier == selectedGroupId }
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) || it.identifier.contains(query, ignoreCase = true) }
            .sortedWith(
                compareByDescending<GuacamoleConnection> { it.identifier in favorites }
                    .thenBy { recents.indexOf(it.identifier).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            )
    }
    // Only offer groups that actually have at least one connection directly in them —
    // an empty organizational group in the chip row would just be a dead end to tap.
    val nonEmptyGroups = remember(groups, connections) {
        groups.filter { g -> connections.any { it.parentIdentifier == g.identifier } }
    }

    fun toggleFavorite(identifier: String) {
        favorites = if (identifier in favorites) favorites - identifier else favorites + identifier
        saveFavorites(context, prefsKey, favorites)
    }

    fun pick(connection: GuacamoleConnection) {
        recents = (listOf(connection.identifier) + recents.filterNot { it == connection.identifier }).take(10)
        saveRecents(context, prefsKey, recents)
        onConnectionPicked(connection, resolvedDataSource)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.guacamole_browse_connections)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp)) {
                SpaceTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.guacamole_search),
                    icon = Icons.Outlined.Search,
                )
                if (nonEmptyGroups.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = selectedGroupId == null,
                                onClick = { selectedGroupId = null },
                                label = { Text(stringResource(R.string.guacamole_all)) },
                            )
                        }
                        items(nonEmptyGroups, key = { it.identifier }) { group ->
                            FilterChip(
                                selected = selectedGroupId == group.identifier,
                                onClick = { selectedGroupId = if (selectedGroupId == group.identifier) null else group.identifier },
                                label = { Text(group.name) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    errorMessage != null -> Text(
                        errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    filtered.isEmpty() -> Text(
                        if (connections.isEmpty()) stringResource(R.string.guacamole_no_connections_available)
                        else stringResource(R.string.guacamole_no_connections_match),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> LazyColumn {
                        items(filtered, key = { it.identifier }) { connection ->
                            val isFavorite = connection.identifier in favorites
                            ListItem(
                                headlineContent = {
                                    Text(connection.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        connection.protocol?.uppercase() ?: connection.identifier,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingContent = { Icon(Icons.Outlined.Cable, contentDescription = null) },
                                trailingContent = {
                                    IconButton(onClick = { toggleFavorite(connection.identifier) }) {
                                        Icon(
                                            if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                            contentDescription = if (isFavorite) stringResource(R.string.guacamole_unfavorite) else stringResource(R.string.guacamole_favorite),
                                        )
                                    }
                                },
                                modifier = Modifier.clickable { pick(connection) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// ── Favorites/recents storage (Part 6/N) — see this file's class doc for why plain, non-encrypted prefs ──

private const val PICKER_PREFS_NAME = "guacamole_connection_picker"

private fun pickerAccountKey(serverUrl: String, username: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(serverUrl.toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(username.toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun loadFavorites(context: Context, accountKey: String): Set<String> =
    context.getSharedPreferences(PICKER_PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet("$accountKey.favorites", emptySet()).orEmpty()

private fun saveFavorites(context: Context, accountKey: String, favorites: Set<String>) {
    context.getSharedPreferences(PICKER_PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putStringSet("$accountKey.favorites", favorites)
        .apply()
}

private fun loadRecents(context: Context, accountKey: String): List<String> =
    context.getSharedPreferences(PICKER_PREFS_NAME, Context.MODE_PRIVATE)
        .getString("$accountKey.recents", null)
        ?.split("\n")
        ?.filter { it.isNotBlank() }
        .orEmpty()

private fun saveRecents(context: Context, accountKey: String, recents: List<String>) {
    context.getSharedPreferences(PICKER_PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putString("$accountKey.recents", recents.joinToString("\n"))
        .apply()
}
