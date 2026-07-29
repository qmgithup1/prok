package com.systemsgo.hex.ui.screens

// ─────────────────────────────────────────────────────────────────────────────
// FTP-FEATURE: Standalone FTP/FTPS quick-connect file browser.
//
// Deliberately NOT tied to a saved RdpProfile / ProtocolType — FTP/FTPS is a
// pure file-transfer protocol with no remote-desktop session, and ProtocolType
// is woven through RemoteSessionFactory, RdpSessionActivity, shortcuts, QR
// import, network discovery, and several UI screens that all assume an
// exhaustive RDP/VNC/SSH/Telnet session-launch flow. Bolting FTP onto that
// enum would mean touching all of those flows blind (no local compiler here —
// CI is the only build). Instead this is a fully self-contained, complete
// feature: the user enters server details once per use, exactly like the
// existing HTTP-file-share panel already does for RDP/VNC sessions.
//
// To surface this in the app, call FtpTransferDialog(onDismiss = { ... }) from
// any existing button/menu (e.g. a new IconButton next to the profile list's
// filter menu, or a HomeScreen FAB action) — see chat notes for the exact
// one-line hookup.
// ─────────────────────────────────────────────────────────────────────────────

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.media.MediaScannerConnection
import com.systemsgo.hex.R
import com.systemsgo.hex.transfer.*
import com.systemsgo.hex.ui.theme.*
import kotlinx.coroutines.*
import java.io.File

private sealed class FtpConnState {
    object Form : FtpConnState()
    object Connecting : FtpConnState()
    object Connected : FtpConnState()
    data class Error(val message: String) : FtpConnState()
}

@Composable
fun FtpTransferDialog(onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        FtpTransferScreen(onDismiss = onDismiss)
    }
}

@Composable
fun FtpTransferScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── Connection form state ────────────────────────────────────────────────
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var security by remember { mutableStateOf(FtpSecurity.PLAIN) }
    var passiveMode by remember { mutableStateOf(true) }
    var securityMenuExpanded by remember { mutableStateOf(false) }

    var connState by remember { mutableStateOf<FtpConnState>(FtpConnState.Form) }
    var ftp by remember { mutableStateOf<FtpFileBrowser?>(null) }

    // ── Phone-side state (identical to FileTransferScreen) ───────────────────
    var phonePath    by remember { mutableStateOf(Environment.getExternalStorageDirectory().absolutePath) }
    var phoneFiles   by remember { mutableStateOf<List<HexFile>>(emptyList()) }
    var phoneSpace   by remember { mutableStateOf(PhoneFileBrowser.phoneStorageSpace()) }
    var phoneLoading by remember { mutableStateOf(false) }
    var phoneSelected by remember { mutableStateOf<HexFile?>(null) }
    var hasStoragePermission by remember { mutableStateOf(checkStoragePermission(context)) }

    // ── Remote-side (FTP) state ──────────────────────────────────────────────
    var remotePath    by remember { mutableStateOf("/") }
    var remoteFiles   by remember { mutableStateOf<List<HexFile>>(emptyList()) }
    var remoteSelected by remember { mutableStateOf<HexFile?>(null) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError   by remember { mutableStateOf<String?>(null) }

    var transferProgress by remember { mutableStateOf<TransferProgress>(TransferProgress.Idle) }

    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasStoragePermission = results.values.any { it }
        if (hasStoragePermission) loadPhoneFiles(scope, phonePath) { phoneFiles = it; phoneLoading = false }
    }
    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { hasStoragePermission = checkStoragePermission(context) }

    fun reloadPhone() {
        if (!hasStoragePermission) return
        phoneLoading = true
        phoneSpace   = PhoneFileBrowser.phoneStorageSpace()
        loadPhoneFiles(scope, phonePath) { phoneFiles = it; phoneLoading = false }
    }
    LaunchedEffect(phonePath, hasStoragePermission) { reloadPhone() }

    fun reloadRemote() {
        val client = ftp ?: return
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
    LaunchedEffect(remotePath, connState) { if (connState is FtpConnState.Connected) reloadRemote() }

    DisposableEffect(Unit) {
        onDispose {
            val ref = ftp
            if (ref != null) Thread { try { ref.disconnect() } catch (e: Exception) { android.util.Log.d("FtpTransferScreen", "non-fatal cleanup/best-effort exception ignored: ${e.javaClass.simpleName}: ${e.message}") } }.start()
        }
    }

    fun doConnect() {
        val portNum = port.toIntOrNull() ?: security.let {
            when (it) {
                FtpSecurity.FTPS_IMPLICIT -> 990
                else -> 21
            }
        }
        connState = FtpConnState.Connecting
        scope.launch {
            val client = FtpFileBrowser(
                FtpConfig(
                    host        = host.trim(),
                    port        = portNum,
                    username    = username,
                    password    = password,
                    security    = security,
                    passiveMode = passiveMode
                )
            )
            try {
                withContext(Dispatchers.IO) { client.connect() }
                ftp = client
                remotePath = client.homeDir()
                connState = FtpConnState.Connected
            } catch (e: Exception) {
                // Never surface the raw exception (may embed host/credentials) —
                // same opaque-error discipline as SftpFileBrowser/ScpTransfer.
                connState = FtpConnState.Error(context.getString(R.string.ft_error_sftp_connect))
            }
            password = "" // clear the plaintext field from Compose state once used
        }
    }

    fun uploadSelected() {
        val sel = phoneSelected ?: return
        val client = ftp ?: return
        transferProgress = TransferProgress.Running(sel.name, 0L, sel.size, true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    client.uploadFile(sel.path, "${remotePath.trimEnd('/')}/${sel.name}") { p -> transferProgress = p }
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
        val client = ftp ?: return
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
            protocolLabel = when (security) {
                FtpSecurity.PLAIN -> "FTP"
                FtpSecurity.FTPS_EXPLICIT, FtpSecurity.FTPS_IMPLICIT -> "FTPS"
            },
            profileName = host.ifBlank { stringResource(R.string.ftp_title) },
            onClose     = onDismiss
        )

        when (val state = connState) {
            is FtpConnState.Form, is FtpConnState.Connecting -> {
                FtpConnectionForm(
                    host = host, onHostChange = { host = it },
                    port = port, onPortChange = { port = it },
                    username = username, onUsernameChange = { username = it },
                    password = password, onPasswordChange = { password = it },
                    security = security, onSecurityChange = { security = it },
                    securityMenuExpanded = securityMenuExpanded,
                    onSecurityMenuExpandedChange = { securityMenuExpanded = it },
                    passiveMode = passiveMode, onPassiveModeChange = { passiveMode = it },
                    connecting = connState is FtpConnState.Connecting,
                    onConnect = ::doConnect
                )
            }
            is FtpConnState.Error -> {
                ErrorPanel(message = state.message) { connState = FtpConnState.Form }
            }
            is FtpConnState.Connected -> {
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

                var activeTab by remember { mutableStateOf(0) } // 0 = phone, 1 = FTP server
                TabRow(selectedTabIndex = activeTab, containerColor = NebulaSurface) {
                    Tab(
                        selected = activeTab == 0,
                        onClick  = { activeTab = 0 },
                        text     = { Text(stringResource(R.string.ft_phone_files)) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick  = { activeTab = 1 },
                        text     = { Text(stringResource(R.string.ft_remote_files)) }
                    )
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
                            rootPath    = "/",
                            space       = null,
                            icon        = Icons.Default.Dns,
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
                    remotePath = remotePath,
                    onPhoneUp  = { File(phonePath).parent?.let { phonePath = it } },
                    onRemoteUp = { if (remotePath != "/") remotePath = remotePath.substringBeforeLast('/').ifEmpty { "/" } }
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick  = ::uploadSelected,
                        enabled  = phoneSelected != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Upload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ft_upload_tip))
                    }
                    Button(
                        onClick  = ::downloadSelected,
                        enabled  = remoteSelected != null,
                        modifier = Modifier.weight(1f)
                    ) {
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
private fun FtpConnectionForm(
    host: String, onHostChange: (String) -> Unit,
    port: String, onPortChange: (String) -> Unit,
    username: String, onUsernameChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    security: FtpSecurity, onSecurityChange: (FtpSecurity) -> Unit,
    securityMenuExpanded: Boolean, onSecurityMenuExpandedChange: (Boolean) -> Unit,
    passiveMode: Boolean, onPassiveModeChange: (Boolean) -> Unit,
    connecting: Boolean,
    onConnect: () -> Unit,
) {
    // SECURITY FIX: contains a password field — see security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = host, onValueChange = onHostChange,
            label = { Text(stringResource(R.string.ftp_host)) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = port, onValueChange = onPortChange,
                label = { Text(stringResource(R.string.ftp_port)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(110.dp)
            )
            Box(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = when (security) {
                        FtpSecurity.PLAIN -> stringResource(R.string.ftp_security_plain)
                        FtpSecurity.FTPS_EXPLICIT -> stringResource(R.string.ftp_security_explicit)
                        FtpSecurity.FTPS_IMPLICIT -> stringResource(R.string.ftp_security_implicit)
                    },
                    onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.ftp_security)) },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().clickableNoRipple { onSecurityMenuExpandedChange(true) }
                )
                DropdownMenu(expanded = securityMenuExpanded, onDismissRequest = { onSecurityMenuExpandedChange(false) }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ftp_security_plain)) }, onClick = { onSecurityChange(FtpSecurity.PLAIN); onSecurityMenuExpandedChange(false) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ftp_security_explicit)) }, onClick = { onSecurityChange(FtpSecurity.FTPS_EXPLICIT); onSecurityMenuExpandedChange(false) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ftp_security_implicit)) }, onClick = { onSecurityChange(FtpSecurity.FTPS_IMPLICIT); onSecurityMenuExpandedChange(false) })
                }
            }
        }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = passiveMode, onCheckedChange = onPassiveModeChange)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ftp_passive_mode), color = StarDust, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick  = onConnect,
            enabled  = host.isNotBlank() && username.isNotBlank() && !connecting,
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

/** Thin wrapper around Modifier.clickable so the read-only "dropdown-as-textfield" box opens the menu on tap. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))
