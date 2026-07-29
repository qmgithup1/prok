package com.systemsgo.hex.ui.screens

// ─────────────────────────────────────────────────────────────────────────────
// TFTP-FEATURE: Standalone TFTP quick-connect screen.
// Same "self-contained, independent of RdpProfile/ProtocolType" choice as
// FtpTransferScreen/SmbTransferScreen/WebDavTransferScreen (see the comment
// at the top of FtpTransferScreen.kt for the full reasoning).
//
// Structurally different from those three: TFTP (RFC 1350) has no directory
// listing command at all, so there is no "remote files" panel here — just a
// phone-file picker plus a remote-filename field, since that's genuinely all
// the protocol supports.
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.media.MediaScannerConnection
import com.systemsgo.hex.R
import com.systemsgo.hex.transfer.*
import com.systemsgo.hex.ui.theme.*
import kotlinx.coroutines.*
import java.io.File

@Composable
fun TftpTransferDialog(onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        TftpTransferScreen(onDismiss = onDismiss)
    }
}

@Composable
fun TftpTransferScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("69") }
    var binaryMode by remember { mutableStateOf(true) }
    var connected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var tftp by remember { mutableStateOf<TftpFileBrowser?>(null) }

    var remoteFileName by remember { mutableStateOf("") }

    var phonePath    by remember { mutableStateOf(Environment.getExternalStorageDirectory().absolutePath) }
    var phoneFiles   by remember { mutableStateOf<List<HexFile>>(emptyList()) }
    var phoneSpace   by remember { mutableStateOf(PhoneFileBrowser.phoneStorageSpace()) }
    var phoneLoading by remember { mutableStateOf(false) }
    var phoneSelected by remember { mutableStateOf<HexFile?>(null) }
    var hasStoragePermission by remember { mutableStateOf(checkStoragePermission(context)) }

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

    DisposableEffect(Unit) {
        onDispose {
            val ref = tftp
            if (ref != null) Thread { try { ref.disconnect() } catch (e: Exception) { android.util.Log.d("TftpTransferScreen", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") } }.start()
        }
    }

    fun doConnect() {
        connecting = true
        connectError = null
        scope.launch {
            val client = TftpFileBrowser(
                TftpConfig(host = host.trim(), port = port.toIntOrNull() ?: 69, binaryMode = binaryMode)
            )
            try {
                withContext(Dispatchers.IO) { client.connect() }
                tftp = client
                connected = true
            } catch (e: Exception) {
                connectError = context.getString(R.string.ft_error_sftp_connect)
            }
            connecting = false
        }
    }

    fun uploadNow() {
        val sel = phoneSelected ?: return
        val client = tftp ?: return
        val targetName = remoteFileName.ifBlank { sel.name }
        transferProgress = TransferProgress.Running(sel.name, 0L, sel.size, true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    client.uploadFile(sel.path, targetName) { p -> transferProgress = p }
                }
            } catch (e: Exception) {
                transferProgress = TransferProgress.Failure(context.getString(R.string.ft_error_upload))
            }
        }
    }

    fun downloadNow() {
        val client = tftp ?: return
        if (remoteFileName.isBlank()) return
        transferProgress = TransferProgress.Running(remoteFileName, 0L, -1L, false)
        scope.launch {
            try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                ).absolutePath
                withContext(Dispatchers.IO) {
                    client.downloadFile(remoteFileName, downloadDir) { p -> transferProgress = p }
                }
                val savedFile = File(downloadDir, remoteFileName.substringAfterLast('/'))
                MediaScannerConnection.scanFile(context, arrayOf(savedFile.absolutePath), null, null)
                reloadPhone()
            } catch (e: Exception) {
                transferProgress = TransferProgress.Failure(context.getString(R.string.ft_error_download))
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .background(DeepSpace)
    ) {
        FtHeader(
            protocolLabel = "TFTP",
            profileName   = host.ifBlank { stringResource(R.string.tftp_title) },
            onClose       = onDismiss
        )

        if (!connected) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.tftp_no_listing_notice),
                    color = StarDust,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = host, onValueChange = { host = it },
                        label = { Text(stringResource(R.string.ftp_host)) },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = port, onValueChange = { port = it },
                        label = { Text(stringResource(R.string.ftp_port)) },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(100.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = binaryMode, onCheckedChange = { binaryMode = it })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (binaryMode) stringResource(R.string.tftp_mode_binary)
                        else stringResource(R.string.tftp_mode_ascii),
                        color = StarDust, style = MaterialTheme.typography.bodySmall
                    )
                }
                connectError?.let {
                    Text(it, color = NovaPink, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick  = ::doConnect,
                    enabled  = host.isNotBlank() && !connecting,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    if (connecting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Default.SettingsEthernet, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ftp_connect))
                    }
                }
            }
        } else {
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

            Column(Modifier.weight(1f).fillMaxWidth()) {
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
            }

            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.tftp_no_listing_notice),
                    color = StarDust, style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = remoteFileName, onValueChange = { remoteFileName = it },
                    label = { Text(stringResource(R.string.tftp_remote_filename)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::uploadNow, enabled = phoneSelected != null, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Upload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ft_upload_tip))
                    }
                    Button(onClick = ::downloadNow, enabled = remoteFileName.isNotBlank(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ft_download_tip))
                    }
                }
            }
        }
    }
}
