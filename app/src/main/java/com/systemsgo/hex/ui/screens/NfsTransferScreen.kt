package com.systemsgo.hex.ui.screens

// ─────────────────────────────────────────────────────────────────────────────
// NFS-FEATURE: Standalone NFSv3 quick-connect file browser.
// Same architectural choice as SmbTransferScreen/FtpTransferScreen — a
// self-contained screen independent of RdpProfile/ProtocolType. Reuses the
// same shared UI helpers (FtHeader, FilePanelHeader, FileList,
// TransferProgressBanner, LoadingIndicator, ErrorPanel, BottomPathBar,
// loadPhoneFiles, checkStoragePermission, storagePermissions) that
// FileTransferScreen.kt exposes as `internal`.
//
// Form fields intentionally differ from SMB/FTP: there's no username/password
// (NFSv3/AUTH_SYS has none — see NfsConfig's doc comment in
// FileTransferManager.kt). mountd/nfsd ports are auto-discovered via the
// portmapper with a documented 2049 fallback — see Portmapper.kt — so there's
// no *required* port field, but an optional "Mountd port" field is offered
// (collapsed by default) for servers where neither the portmapper query nor
// the 2049 fallback lands on the real mountd port; it maps straight to
// NfsConfig.mountdPort. The form also asks for the export path and, since
// AUTH_SYS's uid/gid *is* effectively the connection's only "identity", an
// explicit UID/GID pair with the auth-model notice shown up front rather
// than buried in an error message later.
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.media.MediaScannerConnection
import com.systemsgo.hex.R
import com.systemsgo.hex.transfer.*
import com.systemsgo.hex.ui.theme.*
import kotlinx.coroutines.*
import java.io.File

private sealed class NfsConnState {
    object Form : NfsConnState()
    object Connecting : NfsConnState()
    object Connected : NfsConnState()
    data class Error(val message: String) : NfsConnState()
}

@Composable
fun NfsTransferDialog(onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        NfsTransferScreen(onDismiss = onDismiss)
    }
}

@Composable
fun NfsTransferScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── Connection form state ────────────────────────────────────────────────
    var host by remember { mutableStateOf("") }
    var exportPath by remember { mutableStateOf("") }
    var uid by remember { mutableStateOf("1000") }
    var gid by remember { mutableStateOf("1000") }
    var mountdPort by remember { mutableStateOf("") }

    var connState by remember { mutableStateOf<NfsConnState>(NfsConnState.Form) }
    var nfs by remember { mutableStateOf<NfsFileBrowser?>(null) }

    // ── Phone-side state ──────────────────────────────────────────────────────
    var phonePath    by remember { mutableStateOf(Environment.getExternalStorageDirectory().absolutePath) }
    var phoneFiles   by remember { mutableStateOf<List<HexFile>>(emptyList()) }
    var phoneSpace   by remember { mutableStateOf(PhoneFileBrowser.phoneStorageSpace()) }
    var phoneLoading by remember { mutableStateOf(false) }
    var phoneSelected by remember { mutableStateOf<HexFile?>(null) }
    var hasStoragePermission by remember { mutableStateOf(checkStoragePermission(context)) }

    // ── Remote-side (NFS) state — path is relative to the export root, "" = root
    var remotePath     by remember { mutableStateOf("") }
    var remoteFiles    by remember { mutableStateOf<List<HexFile>>(emptyList()) }
    var remoteSelected by remember { mutableStateOf<HexFile?>(null) }
    var remoteLoading  by remember { mutableStateOf(false) }
    var remoteError     by remember { mutableStateOf<String?>(null) }
    var remoteSpace     by remember { mutableStateOf<StorageSpace?>(null) }

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
        val client = nfs ?: return
        remoteLoading = true
        remoteError   = null
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    remoteFiles = client.listDir(remotePath)
                    remoteSpace = client.remoteStorageSpace()
                } catch (e: Exception) {
                    remoteError = context.getString(R.string.ft_error_list_dir)
                }
            }
            remoteLoading = false
        }
    }
    LaunchedEffect(remotePath, connState) { if (connState is NfsConnState.Connected) reloadRemote() }

    DisposableEffect(Unit) {
        onDispose {
            val ref = nfs
            if (ref != null) Thread { try { ref.disconnect() } catch (e: Exception) { android.util.Log.d("NfsTransferScreen", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") } }.start()
        }
    }

    fun doConnect() {
        connState = NfsConnState.Connecting
        scope.launch {
            val client = NfsFileBrowser(
                NfsConfig(
                    host       = host.trim(),
                    exportPath = exportPath.trim(),
                    uid        = uid.toIntOrNull() ?: 1000,
                    gid        = gid.toIntOrNull() ?: 1000,
                    mountdPort = mountdPort.trim().toIntOrNull(),
                )
            )
            try {
                withContext(Dispatchers.IO) { client.connect() }
                nfs = client
                remotePath = client.homeDir()
                connState = NfsConnState.Connected
            } catch (e: Exception) {
                connState = NfsConnState.Error(context.getString(R.string.ft_error_nfs_connect))
            }
        }
    }

    fun uploadSelected() {
        val sel = phoneSelected ?: return
        val client = nfs ?: return
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
        val client = nfs ?: return
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
            protocolLabel = "NFS",
            profileName   = if (host.isBlank()) stringResource(R.string.nfs_title) else "$host:$exportPath",
            onClose       = onDismiss
        )

        when (val state = connState) {
            is NfsConnState.Form, is NfsConnState.Connecting -> {
                NfsConnectionForm(
                    host = host, onHostChange = { host = it },
                    exportPath = exportPath, onExportPathChange = { exportPath = it },
                    uid = uid, onUidChange = { uid = it },
                    gid = gid, onGidChange = { gid = it },
                    mountdPort = mountdPort, onMountdPortChange = { mountdPort = it },
                    connecting = connState is NfsConnState.Connecting,
                    onConnect = ::doConnect
                )
            }
            is NfsConnState.Error -> {
                ErrorPanel(message = state.message) { connState = NfsConnState.Form }
            }
            is NfsConnState.Connected -> {
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
                            space       = remoteSpace,
                            icon        = Icons.Default.Storage,
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
private fun NfsConnectionForm(
    host: String, onHostChange: (String) -> Unit,
    exportPath: String, onExportPathChange: (String) -> Unit,
    uid: String, onUidChange: (String) -> Unit,
    gid: String, onGidChange: (String) -> Unit,
    mountdPort: String, onMountdPortChange: (String) -> Unit,
    connecting: Boolean,
    onConnect: () -> Unit,
) {
    var advancedExpanded by remember { mutableStateOf(mountdPort.isNotBlank()) }

    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = host, onValueChange = onHostChange,
            label = { Text(stringResource(R.string.ftp_host)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = exportPath, onValueChange = onExportPathChange,
            label = { Text(stringResource(R.string.nfs_export_path)) },
            placeholder = { Text(stringResource(R.string.nfs_export_path_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = uid, onValueChange = onUidChange,
                label = { Text(stringResource(R.string.nfs_uid)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = gid, onValueChange = onGidChange,
                label = { Text(stringResource(R.string.nfs_gid)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = stringResource(R.string.nfs_auth_notice),
            style = MaterialTheme.typography.bodySmall,
            color = StarDust.copy(alpha = 0.65f)
        )

        TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
            Icon(
                if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.nfs_advanced))
        }
        AnimatedVisibility(visible = advancedExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = mountdPort, onValueChange = onMountdPortChange,
                    label = { Text(stringResource(R.string.nfs_mountd_port)) },
                    placeholder = { Text(stringResource(R.string.nfs_mountd_port_hint)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.nfs_mountd_port_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = StarDust.copy(alpha = 0.65f)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick  = onConnect,
            enabled  = host.isNotBlank() && exportPath.isNotBlank() && !connecting,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (connecting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Icon(Icons.Default.Storage, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ftp_connect))
            }
        }
    }
}
