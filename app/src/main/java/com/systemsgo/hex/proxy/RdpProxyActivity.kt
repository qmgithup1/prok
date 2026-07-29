package com.systemsgo.hex.proxy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.systemsgo.hex.R
import com.systemsgo.hex.rdp.native.AFreeRdpServerBridge
import com.systemsgo.hex.ui.theme.SystemsGoTheme

/**
 * PROXY-RELAY FEATURE: config/start/stop screen for [RdpProxyRelay] (run
 * inside [RdpProxyService]). Same "not wired into any nav graph yet, prove
 * it end-to-end first" scope as [com.systemsgo.hex.shadow.ShadowServerActivity]
 * — launch directly (e.g. `adb shell am start -n
 * com.systemsgo.hex/.proxy.RdpProxyActivity`) or add a single entry point
 * as a follow-up once this has been smoke-tested against a real RDP client
 * and a real target RDP server.
 *
 * Unlike ShadowServerActivity, no MediaProjection consent dialog is
 * involved, so start/stop here is a direct service call — no
 * ActivityResult round-trip needed.
 */
class RdpProxyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SystemsGoTheme {
                // SECURITY FIX: this screen collects target/inbound
                // usernames and passwords — see security/SecureScreen.kt.
                com.systemsgo.hex.security.SecureScreen()
                var listenPort by remember { mutableStateOf("3390") }
                var targetHost by remember { mutableStateOf("") }
                var targetPort by remember { mutableStateOf("3389") }
                var targetUsername by remember { mutableStateOf("") }
                var targetPassword by remember { mutableStateOf("") }
                var targetDomain by remember { mutableStateOf("") }
                var inboundUsername by remember { mutableStateOf("") }
                var inboundPassword by remember { mutableStateOf("") }
                var running by remember { mutableStateOf(RdpProxyService.isRunning) }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.rdp_proxy_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(stringResource(R.string.rdp_proxy_desc), style = MaterialTheme.typography.bodyMedium)

                        OutlinedTextField(
                            value = listenPort,
                            onValueChange = { listenPort = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.rdp_proxy_listen_port_label)) },
                            enabled = !running,
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        )

                        HorizontalDivider()
                        Text(stringResource(R.string.rdp_proxy_target_section), style = MaterialTheme.typography.titleSmall)

                        OutlinedTextField(
                            value = targetHost,
                            onValueChange = { targetHost = it },
                            label = { Text(stringResource(R.string.rdp_proxy_target_host_label)) },
                            enabled = !running,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = targetPort,
                            onValueChange = { targetPort = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.rdp_proxy_target_port_label)) },
                            enabled = !running,
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = targetUsername,
                            onValueChange = { targetUsername = it },
                            label = { Text(stringResource(R.string.rdp_proxy_target_username_label)) },
                            enabled = !running,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = targetPassword,
                            onValueChange = { targetPassword = it },
                            label = { Text(stringResource(R.string.rdp_proxy_target_password_label)) },
                            enabled = !running,
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        )
                        OutlinedTextField(
                            value = targetDomain,
                            onValueChange = { targetDomain = it },
                            label = { Text(stringResource(R.string.rdp_proxy_target_domain_label)) },
                            enabled = !running,
                            singleLine = true,
                        )

                        HorizontalDivider()
                        Text(stringResource(R.string.rdp_proxy_inbound_section), style = MaterialTheme.typography.titleSmall)

                        OutlinedTextField(
                            value = inboundUsername,
                            onValueChange = { inboundUsername = it },
                            label = { Text(stringResource(R.string.rdp_proxy_inbound_username_label)) },
                            enabled = !running,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = inboundPassword,
                            onValueChange = { inboundPassword = it },
                            label = { Text(stringResource(R.string.rdp_proxy_inbound_password_label)) },
                            enabled = !running,
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        )

                        Text(
                            if (running) {
                                stringResource(R.string.rdp_proxy_status_running)
                                    .format(listenPort.toIntOrNull() ?: 3390, targetHost, targetPort.toIntOrNull() ?: 3389)
                            } else {
                                stringResource(R.string.rdp_proxy_status_stopped)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        if (!AFreeRdpServerBridge.isAvailable) {
                            Text(
                                stringResource(R.string.rdp_proxy_unavailable),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        // SECURITY NOTE: see RdpProxyRelay's class doc — same
                        // "LAN/VPN only" warning as Shadow Server, surfaced
                        // here so it's visible at the point of use, not just
                        // in source comments.
                        Text(
                            stringResource(R.string.rdp_proxy_security_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        Button(
                            enabled = AFreeRdpServerBridge.isAvailable &&
                                (running || (targetHost.isNotBlank() && listenPort.toIntOrNull() != null && targetPort.toIntOrNull() != null)),
                            onClick = {
                                if (running) {
                                    RdpProxyService.stop(this@RdpProxyActivity)
                                    running = false
                                } else {
                                    RdpProxyService.start(
                                        this@RdpProxyActivity,
                                        listenPort = listenPort.toIntOrNull() ?: 3390,
                                        targetHost = targetHost.trim(),
                                        targetPort = targetPort.toIntOrNull() ?: 3389,
                                        targetUsername = targetUsername.trim(),
                                        targetPassword = targetPassword,
                                        targetDomain = targetDomain.trim(),
                                        inboundUsername = inboundUsername.trim().takeIf { it.isNotBlank() },
                                        inboundPassword = inboundPassword.takeIf { inboundUsername.isNotBlank() },
                                    )
                                    running = true
                                }
                            },
                        ) {
                            Text(stringResource(if (running) R.string.rdp_proxy_stop else R.string.rdp_proxy_start))
                        }
                    }
                }
            }
        }
    }
}
