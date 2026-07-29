package com.systemsgo.hex.shadow

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.rdp.native.AFreeRdpServerBridge
import com.systemsgo.hex.ui.theme.SystemsGoTheme

/**
 * SHADOW-SERVER FEATURE: the one piece of this feature that MUST live in an
 * Activity rather than [ShadowScreenCaptureService] — only an Activity can
 * launch the system's `MediaProjectionManager.createScreenCaptureIntent()`
 * consent dialog and receive its result. Once granted, everything else
 * (capture, encode, push) happens in the service; this screen just starts/
 * stops it and shows current status.
 *
 * Not wired into any nav graph/entry point yet on purpose, same
 * "prove the pipeline, wire up the entry point next" pattern the RDP
 * Server API milestone-1 doc already used for AFreeRdpServerBridge itself
 * — launch it directly (e.g. `adb shell am start -n
 * com.systemsgo.hex/.shadow.ShadowServerActivity`) or add a single entry
 * point for it (a Settings-screen row, a Home-screen action) as a small
 * follow-up once this has been smoke-tested against a real RDP viewer.
 */
class ShadowServerActivity : ComponentActivity() {

    private var portState: MutableState<String>? = null
    private var usernameState: MutableState<String>? = null
    private var passwordState: MutableState<String>? = null
    private var runningState: MutableState<Boolean>? = null
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var pendingDensity = 0

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, getString(R.string.shadow_server_permission_denied), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val port = portState?.value?.toIntOrNull() ?: 3389
        // NLA-SERVER FEATURE: blank username/password disables the check
        // and NLA entirely, same as before this feature existed — see
        // ShadowScreenCaptureService.startCapture()'s doc for what
        // non-null values actually turn on.
        val username = usernameState?.value?.takeIf { it.isNotBlank() }
        val password = passwordState?.value?.takeIf { username != null }
        ShadowScreenCaptureService.start(
            this, result.resultCode, data, port, pendingWidth, pendingHeight, pendingDensity,
            username, password,
        )
        runningState?.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val metrics = resources.displayMetrics
        pendingWidth = metrics.widthPixels
        pendingHeight = metrics.heightPixels
        pendingDensity = metrics.densityDpi

        setContent {
            SystemsGoTheme {
                // SECURITY FIX: this whole screen exists to collect a
                // username/password — see security/SecureScreen.kt.
                com.systemsgo.hex.security.SecureScreen()
                val port = remember { mutableStateOf("3389") }
                portState = port
                val username = remember { mutableStateOf("") }
                usernameState = username
                val password = remember { mutableStateOf("") }
                passwordState = password
                val runningMutableState = remember { mutableStateOf(ShadowScreenCaptureService.isRunning) }
                runningState = runningMutableState
                var running by runningMutableState

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.shadow_server_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(stringResource(R.string.shadow_server_desc), style = MaterialTheme.typography.bodyMedium)

                        OutlinedTextField(
                            value = port.value,
                            onValueChange = { port.value = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.shadow_server_port_label)) },
                            enabled = !running,
                            singleLine = true,
                        )

                        OutlinedTextField(
                            value = username.value,
                            onValueChange = { username.value = it },
                            label = { Text(stringResource(R.string.shadow_server_username_label)) },
                            enabled = !running,
                            singleLine = true,
                        )

                        OutlinedTextField(
                            value = password.value,
                            onValueChange = { password.value = it },
                            label = { Text(stringResource(R.string.shadow_server_password_label)) },
                            enabled = !running,
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        )

                        Text(
                            stringResource(R.string.shadow_server_nla_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Text(
                            if (running) {
                                stringResource(R.string.shadow_server_status_running).format(port.value.toIntOrNull() ?: 3389)
                            } else {
                                stringResource(R.string.shadow_server_status_stopped)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        if (!AFreeRdpServerBridge.isAvailable) {
                            Text(
                                stringResource(R.string.shadow_server_unavailable),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                enabled = AFreeRdpServerBridge.isAvailable,
                                onClick = {
                                    if (running) {
                                        ShadowScreenCaptureService.stop(this@ShadowServerActivity)
                                        running = false
                                    } else {
                                        val projectionManager = getSystemService(MediaProjectionManager::class.java)
                                        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                                    }
                                },
                            ) {
                                Text(
                                    stringResource(
                                        if (running) R.string.shadow_server_stop else R.string.shadow_server_start,
                                    ),
                                )
                            }
                        }

                        HorizontalDivider()

                        Text(stringResource(R.string.shadow_server_accessibility_hint), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) {
                            Text(stringResource(R.string.shadow_server_accessibility_open_settings))
                        }
                    }
                }
            }
        }
    }

}
