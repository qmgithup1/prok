package com.systemsgo.hex.ui.screens

// ─────────────────────────────────────────────────────────────────────────────
// WEBDAV-FEATURE: Standalone WebDAV quick-connect file browser.
// Same architectural choice as FtpTransferScreen/SmbTransferScreen — a
// self-contained screen independent of RdpProfile/ProtocolType (see the
// comment at the top of FtpTransferScreen.kt for the full reasoning).
// ─────────────────────────────────────────────────────────────────────────────

import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.media.MediaScannerConnection
import com.systemsgo.hex.R
import com.systemsgo.hex.transfer.*
import com.systemsgo.hex.ui.theme.*
import kotlinx.coroutines.*
import java.io.File

private sealed class WebDavConnState {
    object Form : WebDavConnState()
    object Connecting : WebDavConnState()
    object Connected : WebDavConnState()
    data class Error(val message: String) : WebDavConnState()
}

@Composable
fun WebDavTransferDialog(onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        WebDavTransferScreen(onDismiss = onDismiss)
    }
}

@Composable
fun WebDavTransferScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── Connection form state ────────────────────────────────────────────────
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    var connState by remember { mutableStateOf<WebDavConnState>(WebDavConnState.Form) }
    var webdav by remember { mutableStateOf<WebDavFileBrowser?>(null) }

    // ── Phone-side state ──────────────────────────────────────────────────────
    var phonePath    by remember { mutableStateOf(Environment.getExternalStorageDirectory().absolutePath) }
    var phoneFiles   by remember { mutableStateOf<List<HexFile>>(emptyList()) }
    var phoneSpace   by remember { mutableStateOf(PhoneFileBrowser.phoneStorageSpace()) }
    var phoneLoading by remember { mutableStateOf(false) }
    var phoneSelected by remember { mutableStateOf<HexFile?>(null) }
    var hasStoragePermission by remember { mutableStateOf(checkStoragePermission(context)) }

    // ── Remote-side (WebDAV) state — path is relative to the server URL, "" = root
    var remotePath     by remember { mutableStateOf("") }
    var remoteFiles    by remember { mutableStateOf<List<HexFile>>(emptyList()) }
    var remoteSelected by remember { mutableStateOf<HexFile?>(null) }
    var remoteLoading  by remember { mutableStateOf(false) }
    var remoteError    by remember { mutableStateOf<String?>(null) }

    var transferProgress by remember { mutableStateOf<TransferProgress>(TransferProgress.Idle) }

    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasStoragePermission = results.values.any { it }
        if (hasStoragePermission) loadPhoneFiles(scope, phonePath) { phoneFiles = it; phoneLoading = false }
    }

    fun reloadPhone() {
        if (!hasStoragePermission) return
        phoneLoading = true
        phoneSpace   = PhoneFileBrowser.phoneStorageSpace()
        loadPhoneFiles(scope, phonePath) { phoneFiles = it; phoneLoading = false }
    }
    LaunchedEffect(phonePath, hasStoragePermission) { reloadPhone() }

    fun reloadRemote() {
        val client = webdav ?: return
        remoteLoading = true
        remoteError   = null
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    remoteFiles = client.listDir(remotePath)
                } catch (e: Exception) {
                    remoteError = context.getString(R.string.ft_error_list_dir)
                }
            }
            remoteLoading = false
        }
    }
    LaunchedEffect(remotePath, connState) { if (connState is WebDavConnState.Connected) reloadRemote() }

    DisposableEffect(Unit) {
        onDispose {
            val ref = webdav
            if (ref != null) Thread { try { ref.disconnect() } catch (e: Exception) { android.util.Log.d("WebDavTransferScreen", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") } }.start()
        }
    }

    fun doConnect() {
        connState = WebDavConnState.Connecting
        scope.launch {
            val client = WebDavFileBrowser(
                WebDavConfig(baseUrl = serverUrl.trim(), username = username, password = password)
            )
            try {
                withContext(Dispatchers.IO) { client.connect() }
                webdav = client
                remotePath = client.homeDir()
                connState = WebDavConnState.Connected
            } catch (e: Exception) {
                connState = WebDavConnState.Error(context.getString(R.string.ft_error_sftp_connect))
            }
            password = ""
        }
    }

    fun uploadSelected() {
        val sel = phoneSelected ?: return
        val client = webdav ?: return
        transferProgress = TransferProgress.Running(sel.name, 0L, sel.size, true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val target = if (remotePath.isEmpty()) sel.name else "$remotePath/${sel.name}"
                    client.uploadFile(sel.path, target) { p -> transferProgress = p }
                }
                reloadRemote()
            } catch (e: Exception) {
                transferProgress = TransferProgress.Failure(context.getString(R.string.ft_error_upload))
            }
            phoneSelected = null
        }
    }

    fun downloadSelected() {
        val sel = remoteSelected ?: return
        val client = webdav ?: return
        transferProgress = TransferProgress.Running(sel.name, 0L, sel.size, false)
        scope.launch {
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                ).absolutePath
                withContext(Dispatchers.IO) {
                    client.downloadFile(sel.path, downloadDir) { p -> transferProgress = p }
                }
                val savedFile = File(downloadDir, sel.name)
                MediaScannerConnection.scanFile(context, arrayOf(savedFile.absolutePath), null, null)
                reloadPhone()
            } catch (e: Exception) {
                transferProgress = TransferProgress.Failure(context.getString(R.string.ft_error_download))
            }
            remoteSelected = null
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .background(DeepSpace)
    ) {
        FtHeader(
            protocolLabel = "WebDAV",
            profileName   = serverUrl.ifBlank { stringResource(R.string.webdav_title) },
            onClose       = onDismiss
        )

        when (val state = connState) {
            is WebDavConnState.Form, is WebDavConnState.Connecting -> {
                WebDavConnectionForm(
                    serverUrl = serverUrl, onServerUrlChange = { serverUrl = it },
                    username = username, onUsernameChange = { username = it },
                    password = password, onPasswordChange = { password = it },
                    connecting = connState is WebDavConnState.Connecting,
                    onConnect = ::doConnect
                )
            }
            is WebDavConnState.Error -> {
                ErrorPanel(message = state.message) { connState = WebDavConnState.Form }
            }
            is WebDavConnState.Connected -> {
                AnimatedVisibility(
                    visible = transferProgress !is TransferProgress.Idle,
                    enter   = expandVertically() + fadeIn(),
                    exit    = shrinkVertically() + fadeOut()
                ) {
                    TransferProgressBanner(
                        progress  = transferProgress,
                        onDismiss = { transferProgress = TransferProgress.Idle }
                    )
                }

                var activeTab by remember { mutableStateOf(0) }
                TabRow(selectedTabIndex = activeTab, containerColor = NebulaSurface) {
                    Tab(selected = activeTab == 0, onClick = { activeTab = 0 },
                        text = { Text(stringResource(R.string.ft_phone_files)) })
                    Tab(selected = activeTab == 1, onClick = { activeTab = 1 },
                        text = { Text(stringResource(R.string.ft_remote_files)) })
                }

                Column(Modifier.weight(1f).fillMaxWidth()) {
                    if (activeTab == 0) {
                        FilePanelHeader(
                            title       = stringResource(R.string.ft_phone_files),
                            path        = phonePath,
                            rootPath    = Environment.getExternalStorageDirectory().absolutePath,
                            space       = phoneSpace,
                            icon        = Icons.Default.PhoneAndroid,
                            accentColor = PulsarCyan
                        )
                        if (phoneLoading) LoadingIndicator() else FileList(
                            files          = phoneFiles,
                            selectedFile   = phoneSelected,
                            isPhoneSide    = true,
                            onNavigate     = { f -> if (f.isDirectory) phonePath = f.path else phoneSelected = if (phoneSelected == f) null else f },
                            onSelectToggle = { f -> phoneSelected = if (phoneSelected == f) null else f }
                        )
                    } else {
                        FilePanelHeader(
                            title       = stringResource(R.string.ft_remote_files),
                            path        = remotePath,
                            rootPath    = "",
                            space       = null,
                            icon        = Icons.Default.Cloud,
                            accentColor = NovaPink
                        )
                        when {
                            remoteLoading -> LoadingIndicator()
                            remoteError != null -> remoteError?.let { ErrorPanel(it) { reloadRemote() } }
                            else -> FileList(
                                files          = remoteFiles,
                                selectedFile   = remoteSelected,
                                isPhoneSide    = false,
                                onNavigate     = { f -> if (f.isDirectory) remotePath = f.path else remoteSelected = if (remoteSelected == f) null else f },
                                onSelectToggle = { f -> remoteSelected = if (remoteSelected == f) null else f }
                            )
                        }
                    }
                }

                BottomPathBar(
                    phonePath  = phonePath,
                    remotePath = remotePath.ifEmpty { "/" },
                    onPhoneUp  = { File(phonePath).parent?.let { phonePath = it } },
                    onRemoteUp = {
                        remotePath = if ('/' in remotePath) remotePath.substringBeforeLast('/') else ""
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = ::uploadSelected, enabled = phoneSelected != null, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Upload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ft_upload_tip))
                    }
                    Button(onClick = ::downloadSelected, enabled = remoteSelected != null, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ft_download_tip))
                    }
                }
            }
        }
    }
}

@Composable
private fun WebDavConnectionForm(
    serverUrl: String, onServerUrlChange: (String) -> Unit,
    username: String, onUsernameChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    connecting: Boolean,
    onConnect: () -> Unit,
) {
    // SECURITY FIX: contains a password field — see security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = serverUrl, onValueChange = onServerUrlChange,
            label = { Text(stringResource(R.string.webdav_server_url)) },
            placeholder = { Text(stringResource(R.string.webdav_server_url_placeholder)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = username, onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.ftp_username)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password, onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.ftp_password)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick  = onConnect,
            enabled  = serverUrl.isNotBlank() && !connecting,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (connecting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Icon(Icons.Default.Cloud, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ftp_connect))
            }
        }
    }
}
