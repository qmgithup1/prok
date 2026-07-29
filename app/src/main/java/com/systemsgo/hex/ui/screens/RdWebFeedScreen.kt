package com.systemsgo.hex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.systemsgo.hex.R
import com.systemsgo.hex.data.model.ProtocolType
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.model.WebFeedSubscription
import com.systemsgo.hex.data.repository.WebFeedRepository
import com.systemsgo.hex.ui.MainViewModel
import com.systemsgo.hex.ui.components.ProfileFormDialog
import com.systemsgo.hex.ui.theme.*
import com.systemsgo.hex.webfeed.RdWebFeedClient
import com.systemsgo.hex.webfeed.WebFeedFetchResult
import com.systemsgo.hex.webfeed.WebFeedResource
import com.systemsgo.hex.webfeed.WebFeedResourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── List screen: saved feed subscriptions ───────────────────────────────────

@HiltViewModel
class WebFeedListViewModel @Inject constructor(
    private val repository: WebFeedRepository,
) : ViewModel() {
    val subscriptions: StateFlow<List<WebFeedSubscription>> =
        repository.getAllSubscriptions()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addFeed(subscription: WebFeedSubscription) = viewModelScope.launch {
        repository.saveSubscription(subscription)
    }

    fun deleteFeed(subscription: WebFeedSubscription) = viewModelScope.launch {
        repository.deleteSubscription(subscription)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RdWebFeedListScreen(
    navController: NavController,
    viewModel: WebFeedListViewModel = hiltViewModel(),
) {
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = CometTail)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Cloud, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.webfeed_title), color = StarDust, fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, containerColor = PulsarCyan) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.webfeed_add), tint = androidx.compose.ui.graphics.Color.Black)
            }
        }
    ) { padding ->
        if (subscriptions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Cloud, null, tint = CometTail.copy(alpha = 0.5f), modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.webfeed_empty_title), color = StarDust, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.webfeed_empty_desc),
                    color = CometTail.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(subscriptions, key = { it.id }) { feed ->
                    WebFeedSubscriptionCard(
                        feed = feed,
                        onClick = { navController.navigate("webfeed/${feed.id}") },
                        onDelete = { viewModel.deleteFeed(feed) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddWebFeedDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, url, username, password, domain, acceptSelfSigned ->
                viewModel.addFeed(
                    WebFeedSubscription(
                        name = name.ifBlank { url },
                        feedUrl = RdWebFeedClient.normalizeFeedUrl(url),
                        username = username,
                        password = password,
                        domain = domain,
                        acceptSelfSignedCertificate = acceptSelfSigned,
                    )
                )
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun WebFeedSubscriptionCard(
    feed: WebFeedSubscription,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NebulaSurface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(PulsarCyan.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Cloud, null, tint = PulsarCyan, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(feed.name, color = StarDust, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(feed.feedUrl, color = CometTail.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (feed.lastError.isNotBlank()) {
                Text(feed.lastError, color = ErrorRed, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete), tint = CometTail)
        }
    }
}

@Composable
private fun AddWebFeedDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, username: String, password: String, domain: String, acceptSelfSigned: Boolean) -> Unit,
) {
    // SECURITY FIX: contains a password field — see security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var acceptSelfSigned by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.webfeed_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.webfeed_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.webfeed_field_url)) }, singleLine = true, placeholder = { Text(stringResource(R.string.webfeed_field_url_placeholder)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(stringResource(R.string.username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = domain, onValueChange = { domain = it }, label = { Text(stringResource(R.string.domain)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acceptSelfSigned, onCheckedChange = { acceptSelfSigned = it })
                    Text(stringResource(R.string.webfeed_accept_self_signed), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = { onSave(name, url, username, password, domain, acceptSelfSigned) }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

// ── Detail screen: resources published by one feed ──────────────────────────

@HiltViewModel
class WebFeedResourcesViewModel @Inject constructor(
    private val repository: WebFeedRepository,
    private val client: RdWebFeedClient,
    // WEB-PORTAL FEATURE: only used by openInBrowserProfile() below, for the
    // "Open in browser" fallback when the feed reports AuthRequired (Basic
    // auth not enabled on the server's Feed endpoint — see RdWebFeedClient's
    // class doc). Everything else in this ViewModel is unrelated to it.
    private val profileRepository: com.systemsgo.hex.data.repository.RdpProfileRepository,
) : ViewModel() {
    private val _feed = MutableStateFlow<WebFeedSubscription?>(null)
    val feed: StateFlow<WebFeedSubscription?> = _feed.asStateFlow()

    private val _resources = MutableStateFlow<List<WebFeedResource>>(emptyList())
    val resources: StateFlow<List<WebFeedResource>> = _resources.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load(feedId: String) = viewModelScope.launch {
        val subscription = repository.getSubscriptionById(feedId) ?: return@launch
        _feed.value = subscription
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val subscription = _feed.value ?: return@launch
        _isLoading.value = true
        _error.value = null
        when (val result = client.fetchFeed(
            feedUrl = subscription.feedUrl,
            username = subscription.username,
            password = subscription.password,
            domain = subscription.domain,
            acceptSelfSignedCertificate = subscription.acceptSelfSignedCertificate,
        )) {
            is WebFeedFetchResult.Success -> {
                _resources.value = result.resources
                repository.updateRefreshResult(subscription.id, "")
            }
            is WebFeedFetchResult.AuthRequired -> {
                val message = "Authentication failed — check the username/password, or ask the admin to enable Basic Authentication on the RDWeb Feed."
                _error.value = message
                repository.updateRefreshResult(subscription.id, message)
            }
            is WebFeedFetchResult.Error -> {
                _error.value = result.message
                repository.updateRefreshResult(subscription.id, result.message)
            }
        }
        _isLoading.value = false
    }

    suspend fun resolveProfile(resource: WebFeedResource): RdpProfile? {
        val subscription = _feed.value ?: return null
        return client.fetchResourceProfile(
            resource = resource,
            username = subscription.username,
            password = subscription.password,
            domain = subscription.domain,
            acceptSelfSignedCertificate = subscription.acceptSelfSignedCertificate,
        )?.copy(
            webFeedSubscriptionId = subscription.id,
            folderId = subscription.targetFolderId.ifBlank { null },
        )
    }

    // WEB-PORTAL FEATURE: fallback for WebFeedFetchResult.AuthRequired — the
    // feed's Basic-auth endpoint isn't usable (wrong creds, or the admin
    // never enabled Basic Authentication on the Feed vdir at all — see
    // RdWebFeedClient's class doc), but the ordinary browser-facing
    // "/RDWeb/Pages" forms login usually still is. Reuses (rather than
    // duplicates) a Web/HTTPS profile per subscription so retrying this more
    // than once doesn't leave a pile of near-identical saved profiles behind —
    // keyed off [RdpProfile.webFeedSubscriptionId], the same linkage
    // [resolveProfile] above already uses for imported RemoteApp profiles.
    suspend fun openInBrowserProfile(): RdpProfile? {
        val subscription = _feed.value ?: return null
        val pagesUrl = client.normalizeFeedUrl(subscription.feedUrl)
            .substringBefore("/Feed/webfeed.aspx", missingDelimiterValue = subscription.feedUrl)
            .trimEnd('/') + "/Pages"

        val existing = profileRepository.getAllProfiles().first()
            .firstOrNull { it.webFeedSubscriptionId == subscription.id && it.protocolType == ProtocolType.WEB }
        val profile = (existing ?: RdpProfile(
            name = subscription.name,
            host = "",
            username = "",
            password = "",
            protocolType = ProtocolType.WEB,
            webFeedSubscriptionId = subscription.id,
        )).copy(
            webUrl = pagesUrl,
            username = subscription.username,
            password = subscription.password,
            webTrustSelfSignedCertificate = subscription.acceptSelfSignedCertificate,
            folderId = subscription.targetFolderId.ifBlank { null },
        )
        profileRepository.saveProfile(profile)
        return profile
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RdWebFeedResourcesScreen(
    navController: NavController,
    feedId: String,
    viewModel: WebFeedResourcesViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val resources by viewModel.resources.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var pendingProfile by remember { mutableStateOf<RdpProfile?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(feedId) { viewModel.load(feedId) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = CometTail)
                    }
                },
                title = {
                    Text(feed?.name ?: stringResource(R.string.webfeed_title), color = StarDust, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.discover_devices_refresh), tint = CometTail)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isLoading && resources.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PulsarCyan)
                }
            } else if (error != null && resources.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error.orEmpty(), color = ErrorRed, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    // WEB-PORTAL FEATURE: this feed's Basic-auth endpoint isn't
                    // usable right now (wrong creds, or Basic auth was never
                    // enabled on the server's Feed vdir — see RdWebFeedClient's
                    // class doc) — but the ordinary browser-facing
                    // "/RDWeb/Pages" forms login usually still is. Offer that
                    // as a fallback instead of leaving the user stuck.
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        scope.launch {
                            val profile = viewModel.openInBrowserProfile()
                            if (profile != null) {
                                context.startActivity(com.systemsgo.hex.remote.SessionLauncher.intentFor(context, profile))
                            }
                        }
                    }) {
                        Text(stringResource(R.string.webfeed_open_in_browser))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(resources, key = { it.alias }) { resource ->
                        WebFeedResourceCard(
                            resource = resource,
                            onAdd = {
                                scope.launch {
                                    val profile = viewModel.resolveProfile(resource)
                                    if (profile != null) {
                                        pendingProfile = profile
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.webfeed_resolve_failed),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    pendingProfile?.let { stub ->
        ProfileFormDialog(
            profile = stub,
            onDismiss = { pendingProfile = null },
            onSave = { profile ->
                mainViewModel.addProfile(profile)
                pendingProfile = null
            },
        )
    }
}

@Composable
private fun WebFeedResourceCard(
    resource: WebFeedResource,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NebulaSurface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val accent = if (resource.type == WebFeedResourceType.DESKTOP) QuantumBlue else PlasmaGreen
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (resource.type == WebFeedResourceType.DESKTOP) Icons.Outlined.Computer else Icons.Outlined.Apps,
                null, tint = accent, modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(resource.title, color = StarDust, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (resource.type == WebFeedResourceType.DESKTOP) stringResource(R.string.webfeed_type_desktop) else stringResource(R.string.webfeed_type_remoteapp),
                color = CometTail.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onAdd) { Text(stringResource(R.string.add_short)) }
    }
}
