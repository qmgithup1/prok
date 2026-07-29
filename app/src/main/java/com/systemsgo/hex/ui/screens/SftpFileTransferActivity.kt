package com.systemsgo.hex.ui.screens

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.systemsgo.hex.data.model.ConnectionLog
import com.systemsgo.hex.data.repository.ConnectionLogRepository
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.ui.theme.SystemsGoTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SFTP-STANDALONE FEATURE: session Activity for a [com.systemsgo.hex.data.model.ProtocolType.SFTP]
 * profile — opens straight into [FileTransferScreen] (the exact same composable
 * RdpSessionActivity's in-session "Files" panel already uses for SSH profiles,
 * see [FileTransferScreen]'s `isSsh` flag), with no terminal/framebuffer session
 * behind it at all. Deliberately its own small Activity rather than a sixth
 * branch inside RdpSessionActivity — same reasoning as [com.systemsgo.hex.web.WebPortalActivity]
 * for [com.systemsgo.hex.data.model.ProtocolType.WEB]: an SFTP connection has no
 * framebuffer/terminal and no [com.systemsgo.hex.remote.RemoteSessionClient]
 * behind it, just a file browser, so RemoteSessionFactory/RdpSessionActivity are
 * never consulted for it (see RemoteSessionFactory's SFTP branch and
 * SessionLauncher.intentFor's routing).
 *
 * Launched only via [com.systemsgo.hex.remote.SessionLauncher.intentFor] with a
 * "profile_id" extra pointing at an existing, locally-saved [com.systemsgo.hex.data.model.RdpProfile]
 * — same trust boundary as every other session Activity here (WebPortalActivity,
 * BmcManagementActivity, ...): no host/credential is ever accepted from outside
 * this app's own database.
 */
@AndroidEntryPoint
class SftpFileTransferActivity : AppCompatActivity() {

    private val viewModel: SftpFileTransferViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profileId = intent.getStringExtra("profile_id") ?: run { finish(); return }
        viewModel.load(profileId)

        // HOME-SCREEN-SHORTCUTS FEATURE: same App Lock gate every other
        // session Activity applies for a pinned-shortcut launch (see
        // WebPortalActivity/RdpSessionActivity's identical `fromShortcut`
        // handling) — a normal in-app "Connect" tap already passed through
        // MainActivity's own lock screen, so this only re-prompts for the
        // shortcut path.
        val fromShortcut = intent.getBooleanExtra("from_shortcut", false)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val lockRequired = fromShortcut && (settings.biometricLockEnabled || settings.pinLockEnabled)
            var isUnlocked by remember { mutableStateOf(false) }
            LaunchedEffect(lockRequired) {
                if (!lockRequired) isUnlocked = true
            }

            SystemsGoTheme(darkTheme = settings.isDarkMode, themeVariant = settings.themeVariant) {
                val profile by viewModel.profile.collectAsStateWithLifecycle()
                val p = profile
                Box(Modifier.fillMaxSize()) {
                    if (p == null || !isUnlocked) {
                        // Still loading the profile, or waiting on App Lock —
                        // nothing sensitive (host, credentials) is visible
                        // underneath yet, same as WebPortalActivity/
                        // RdpSessionActivity's equivalent loading overlay.
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    } else {
                        // FileTransferScreen renders full-screen fine here even
                        // though its other call site (RdpSessionActivity's
                        // "Files" button mid-session) wraps it in a Dialog —
                        // this Activity's whole purpose IS that screen, so it's
                        // the root content directly instead.
                        FileTransferScreen(profile = p, onDismiss = { finish() })
                    }
                    if (lockRequired) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible  = !isUnlocked,
                            enter    = androidx.compose.animation.fadeIn(),
                            exit     = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            AppLockScreen(
                                biometricEnabled = settings.biometricLockEnabled,
                                pinEnabled       = settings.pinLockEnabled,
                                encryptedPin     = settings.pinCode,
                                isUnlocked       = isUnlocked,
                                onUnlocked       = { isUnlocked = true }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Loads the [com.systemsgo.hex.data.model.RdpProfile] behind this session and
 * owns its one-row [ConnectionLog] entry, mirroring WebPortalViewModel's shape
 * so standalone SFTP connections show up in Connection History exactly like
 * every other protocol does.
 */
@HiltViewModel
class SftpFileTransferViewModel @Inject constructor(
    private val profileRepository: RdpProfileRepository,
    private val logRepository: ConnectionLogRepository,
    private val settingsRepository: com.systemsgo.hex.data.repository.AppSettingsRepository,
) : ViewModel() {

    private val _profile = MutableStateFlow<com.systemsgo.hex.data.model.RdpProfile?>(null)
    val profile: StateFlow<com.systemsgo.hex.data.model.RdpProfile?> = _profile.asStateFlow()

    val settings: StateFlow<com.systemsgo.hex.data.repository.AppSettings> =
        settingsRepository.settingsFlow.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, settingsRepository.currentSettingsSnapshot()
        )

    private var logId: String? = null

    fun load(profileId: String) {
        viewModelScope.launch {
            val loaded = profileRepository.getProfileById(profileId)
            _profile.value = loaded
            if (loaded != null) {
                logId = logRepository.start(
                    ConnectionLog(
                        profileId    = loaded.id,
                        profileName  = loaded.name,
                        host         = loaded.host,
                        port         = loaded.port,
                        protocolType = loaded.protocolType,
                    )
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        val id = logId ?: return
        // ViewModel scope is already cancelling by the time onCleared runs —
        // same short-lived fire-and-forget scope WebPortalViewModel's
        // onCleared uses for its own closing write.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            logRepository.finish(id, disconnectReason = "", wasSuccessful = true)
        }
    }
}
