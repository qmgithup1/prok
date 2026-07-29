package com.systemsgo.hex.ui.screens

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.systemsgo.hex.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.systemsgo.hex.ui.theme.SystemsGoTheme
import com.systemsgo.hex.vnc.ard.ArdAdminClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ARD FEATURE (manual-test screen — not wired into the profile system).
 *
 * A minimal, standalone form for exercising [ArdAdminClient.addComputer] /
 * [ArdAdminClient.sendUnixCommand] against a real Mac: host + admin
 * username/password on top, target user + shell command below, a Run
 * button, and a scrolling output pane.
 *
 * Deliberately *not* a [com.systemsgo.hex.data.model.RdpProfile]-backed
 * session Activity like [BmcManagementActivity] — there is no
 * `ProtocolType.ARD`, no saved-profile form, and no
 * [com.systemsgo.hex.remote.SessionLauncher] routing for it yet. See
 * [ArdAdminClient]'s class doc: the handshake's first packet is an
 * unverified best guess, so this screen exists to let that be tested
 * end-to-end against a real Mac (System Settings \u2192 General \u2192
 * Sharing \u2192 Remote Management) before investing in the heavier
 * profile/DB/SessionLauncher integration that AMT/IPMI/Redfish have.
 *
 * Not declared `exported` in the manifest — launch it from within the app
 * (e.g. a temporary entry point in a debug/settings screen) via
 * `Intent(context, ArdCommandActivity::class.java)`.
 *
 * Each tap of Run calls [ArdAdminClient.addComputer] again from scratch
 * and closes the session afterward, rather than keeping one session alive
 * across multiple commands — simplest thing that works for a manual-test
 * screen; a real integration would cache the session.
 */
class ArdCommandActivity : ComponentActivity() {

    private val viewModel: ArdCommandViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SystemsGoTheme {
                val state by viewModel.state.collectAsState()
                ArdCommandScreen(
                    state = state,
                    onBack = { finish() },
                    onHostChange = viewModel::updateHost,
                    onAdminUserChange = viewModel::updateAdminUsername,
                    onAdminPassChange = viewModel::updateAdminPassword,
                    onTargetUserChange = viewModel::updateTargetUser,
                    onCommandChange = viewModel::updateCommand,
                    onRun = viewModel::run
                )
            }
        }
    }
}

data class ArdCommandUiState(
    val host: String = "",
    val adminUsername: String = "",
    val adminPassword: String = "",
    val targetUser: String = "",
    val command: String = "",
    val isRunning: Boolean = false,
    val output: String = "",
    val exitStatus: Int? = null,
    val error: String? = null
)

class ArdCommandViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ArdCommandUiState())
    val state: StateFlow<ArdCommandUiState> = _state.asStateFlow()

    fun updateHost(v: String) = _state.update { it.copy(host = v) }
    fun updateAdminUsername(v: String) = _state.update { it.copy(adminUsername = v) }
    fun updateAdminPassword(v: String) = _state.update { it.copy(adminPassword = v) }
    fun updateTargetUser(v: String) = _state.update { it.copy(targetUser = v) }
    fun updateCommand(v: String) = _state.update { it.copy(command = v) }

    /**
     * Runs [ArdAdminClient.addComputer] followed by
     * [ArdAdminClient.sendUnixCommand] against the currently-entered form
     * values, off the main thread ([ArdAdminClient] is fully blocking I/O
     * — java.net.DatagramSocket — so this must never run on Dispatchers.Main).
     */
    fun run() {
        val s = _state.value
        if (s.isRunning) return
        if (s.host.isBlank() || s.adminUsername.isBlank() || s.command.isBlank()) {
            _state.update { it.copy(error = getApplication<Application>().getString(R.string.ard_required_fields_error)) }
            return
        }
        _state.update { it.copy(isRunning = true, error = null, output = "", exitStatus = null) }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var session: ArdAdminClient.ArdAdminSession? = null
                try {
                    session = ArdAdminClient.addComputer(
                        host = s.host.trim(),
                        adminUsername = s.adminUsername,
                        adminPassword = s.adminPassword
                    )
                    val cmdResult = ArdAdminClient.sendUnixCommand(
                        session = session,
                        user = s.targetUser.ifBlank { s.adminUsername },
                        command = s.command
                    )
                    Result.success(cmdResult)
                } catch (e: Exception) {
                    Result.failure(e)
                } finally {
                    session?.close()
                }
            }

            result.fold(
                onSuccess = { cmdResult ->
                    _state.update {
                        it.copy(
                            isRunning = false,
                            output = cmdResult.output,
                            exitStatus = cmdResult.exitStatus,
                            error = null
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            isRunning = false,
                            error = e.message ?: e.javaClass.simpleName
                        )
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArdCommandScreen(
    state: ArdCommandUiState,
    onBack: () -> Unit,
    onHostChange: (String) -> Unit,
    onAdminUserChange: (String) -> Unit,
    onAdminPassChange: (String) -> Unit,
    onTargetUserChange: (String) -> Unit,
    onCommandChange: (String) -> Unit,
    onRun: () -> Unit
) {
    // SECURITY FIX: contains an admin password field — see security/SecureScreen.kt.
    com.systemsgo.hex.security.SecureScreen()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ard_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ard_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.ard_remote_management_hint),
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = state.host,
                onValueChange = onHostChange,
                label = { Text(stringResource(R.string.ard_host_or_ip)) },
                singleLine = true,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.adminUsername,
                onValueChange = onAdminUserChange,
                label = { Text(stringResource(R.string.ard_admin_username)) },
                singleLine = true,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.adminPassword,
                onValueChange = onAdminPassChange,
                label = { Text(stringResource(R.string.ard_admin_password)) },
                singleLine = true,
                enabled = !state.isRunning,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            OutlinedTextField(
                value = state.targetUser,
                onValueChange = onTargetUserChange,
                label = { Text(stringResource(R.string.ard_run_command_as)) },
                singleLine = true,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.command,
                onValueChange = onCommandChange,
                label = { Text(stringResource(R.string.ard_shell_command)) },
                singleLine = true,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = onRun,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(stringResource(R.string.ard_running))
                    }
                } else {
                    Text(stringResource(R.string.ard_run))
                }
            }

            state.error?.let { err ->
                Text(
                    "Error: $err",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            state.exitStatus?.let { status ->
                Text(stringResource(R.string.ard_exit_status, status), style = MaterialTheme.typography.labelLarge)
            }

            if (state.output.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.ard_output), style = MaterialTheme.typography.labelLarge)
                Text(
                    state.output,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
