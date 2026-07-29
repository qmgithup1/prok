package com.systemsgo.hex.ui.screens

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemsgo.hex.cloudsync.CloudLinkResult
import com.systemsgo.hex.cloudsync.CloudProvider
import com.systemsgo.hex.cloudsync.CloudSyncError
import com.systemsgo.hex.cloudsync.CloudSyncManager
import com.systemsgo.hex.cloudsync.CloudSyncProvider
import com.systemsgo.hex.cloudsync.DropboxSyncProvider
import com.systemsgo.hex.cloudsync.GoogleDriveSyncProvider
import com.systemsgo.hex.data.repository.CloudSyncPreferences
import com.systemsgo.hex.data.repository.CloudSyncSettings
import com.systemsgo.hex.security.SyncPassphraseStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CLOUD-SYNC FEATURE (Part 3-b).
 *
 * One-shot outcome of an action this ViewModel just performed — consumed by
 * the screen as a Snackbar and then cleared via [CloudSyncViewModel.consumeSyncEvent].
 * Deliberately NOT folded into [CloudSyncSettings] itself: the settings flow
 * already carries a durable "last error message" for display on next open
 * (see [CloudSyncSettings.lastSyncErrorMessage]), but a *successful* sync's
 * "uploaded" vs "downloaded (N imported)" distinction, and a link attempt's
 * own failure, aren't part of that persisted state and only matter for the
 * single moment right after the user tapped a button.
 */
sealed class CloudSyncEvent {
    object Uploaded : CloudSyncEvent()
    data class Downloaded(val importedProfiles: Int, val skippedProfiles: Int) : CloudSyncEvent()
    data class SyncFailed(val error: CloudSyncError) : CloudSyncEvent()
    data class LinkFailed(val message: String) : CloudSyncEvent()
}

@HiltViewModel
class CloudSyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudSyncManager: CloudSyncManager,
    private val cloudSyncPreferences: CloudSyncPreferences,
    // Multibound map — used only for the provider-agnostic unlink() path
    // (CloudSyncProvider.unlink() already clears CloudSyncPreferences'
    // link internally when it's the one currently linked; see both
    // implementations' unlink() doc comments).
    private val providers: Map<CloudProvider, @JvmSuppressWildcards CloudSyncProvider>,
    // Injected directly (not through the map above) because linking itself
    // — link()/startLink()/completeLinkIfPending() — is a provider-specific
    // UI interaction that isn't part of the common CloudSyncProvider surface;
    // see CloudUploadOutcome/CloudLinkResult's doc comment in CloudSyncResult.kt.
    private val googleDriveSyncProvider: GoogleDriveSyncProvider,
    private val dropboxSyncProvider: DropboxSyncProvider,
) : ViewModel() {

    val settings: StateFlow<CloudSyncSettings> = cloudSyncPreferences.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), cloudSyncPreferences.currentSettingsSnapshot())

    private val _hasPassphrase = MutableStateFlow(SyncPassphraseStore.hasPassphrase(context))
    val hasPassphrase: StateFlow<Boolean> = _hasPassphrase.asStateFlow()

    private val _isLinking = MutableStateFlow(false)
    val isLinking: StateFlow<Boolean> = _isLinking.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _event = MutableStateFlow<CloudSyncEvent?>(null)
    /** One-shot — the screen shows it once (Snackbar) and calls [consumeSyncEvent]. */
    val event: StateFlow<CloudSyncEvent?> = _event.asStateFlow()

    /** Screen calls this once it has shown [event]. */
    fun consumeSyncEvent() {
        _event.value = null
    }

    // ── Passphrase (separate from the manual export/import password) ───────

    fun setPassphrase(passphrase: String) {
        SyncPassphraseStore.setPassphrase(context, passphrase)
        _hasPassphrase.value = true
    }

    // ── Linking ──────────────────────────────────────────────────────────

    /** Google Drive's Credential Manager picker returns synchronously — no onResume step needed. */
    fun linkGoogleDrive(activity: Activity, preferAuthorizedAccounts: Boolean = true) = viewModelScope.launch {
        if (_isLinking.value) return@launch
        _isLinking.value = true
        try {
            when (val result = googleDriveSyncProvider.link(activity, preferAuthorizedAccounts)) {
                is CloudLinkResult.Success -> { /* CloudSyncPreferences already updated inside link() */ }
                is CloudLinkResult.Failure -> _event.value = CloudSyncEvent.LinkFailed(result.error.userMessage)
                CloudLinkResult.Cancelled -> { /* user backed out — nothing to report */ }
            }
        } finally {
            _isLinking.value = false
        }
    }

    /** Starts Dropbox's PKCE browser redirect. Call [completeDropboxLinkIfPending] from onResume. */
    fun startDropboxLink(activity: Activity) {
        dropboxSyncProvider.startLink(activity)
    }

    /** Call from the hosting Activity's onResume — a no-op unless a [startDropboxLink] redirect just completed. */
    fun completeDropboxLinkIfPending() = viewModelScope.launch {
        when (val result = dropboxSyncProvider.completeLinkIfPending()) {
            is CloudLinkResult.Failure -> _event.value = CloudSyncEvent.LinkFailed(result.error.userMessage)
            is CloudLinkResult.Success, CloudLinkResult.Cancelled, null -> { /* nothing to report */ }
        }
    }

    /**
     * Re-runs the link flow for whichever provider is currently linked —
     * used for the [CloudSyncError.AuthExpired] "Reconnect" action. A fresh
     * successful link re-establishes usable access the same way the
     * original "Connect" button did.
     */
    fun reconnect(activity: Activity) {
        when (settings.value.linkedProvider) {
            CloudProvider.GOOGLE_DRIVE -> linkGoogleDrive(activity)
            CloudProvider.DROPBOX -> startDropboxLink(activity)
            null -> { /* nothing linked to reconnect */ }
        }
    }

    /**
     * Lets the user pick a *different* account for the currently linked
     * provider without a separate unlink step first. Both providers' link
     * flows already surface a full account picker every time (Drive: Credential
     * Manager's [GetGoogleIdOption] is built with `setAutoSelectEnabled(false)`;
     * Dropbox: `Auth.startOAuth2PKCE` always opens the browser's own account
     * chooser), so simply re-running the same flow — and letting
     * [GoogleDriveSyncProvider.link]/[DropboxSyncProvider] overwrite the
     * previously stored [CloudSyncPreferences] link on success — is enough
     * to "switch" accounts; there is nothing else that needs to be torn down
     * first for either backend.
     */
    fun switchAccount(activity: Activity) {
        when (settings.value.linkedProvider) {
            CloudProvider.GOOGLE_DRIVE -> linkGoogleDrive(activity, preferAuthorizedAccounts = false)
            CloudProvider.DROPBOX -> startDropboxLink(activity)
            null -> { /* nothing linked to switch away from */ }
        }
    }

    // ── Sync ─────────────────────────────────────────────────────────────

    fun syncNow() = viewModelScope.launch {
        if (_isSyncing.value) return@launch
        _isSyncing.value = true
        try {
            _event.value = when (val outcome = cloudSyncManager.syncNow()) {
                is CloudSyncManager.SyncOutcome.Uploaded -> CloudSyncEvent.Uploaded
                is CloudSyncManager.SyncOutcome.Downloaded -> CloudSyncEvent.Downloaded(
                    importedProfiles = outcome.importResult.importedProfiles,
                    skippedProfiles = outcome.importResult.skippedProfiles,
                )
                is CloudSyncManager.SyncOutcome.Failure -> CloudSyncEvent.SyncFailed(outcome.error)
            }
        } finally {
            _isSyncing.value = false
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────

    fun updateAutoSyncEnabled(enabled: Boolean) = viewModelScope.launch {
        cloudSyncPreferences.updateAutoSyncEnabled(enabled)
        // CloudSyncScheduler.applySettings() reacts to this via SystemsGoApp's
        // settingsFlow collector — nothing else to do here (see this
        // ViewModel's file doc / the Part 3-b prompt: never call the
        // scheduler or WorkManager directly).
    }

    fun updateAutoSyncIntervalMinutes(minutes: Int) = viewModelScope.launch {
        cloudSyncPreferences.updateAutoSyncIntervalMinutes(minutes)
    }

    // ── Disconnect ───────────────────────────────────────────────────────

    /**
     * Unlinks the currently linked provider (which clears
     * [CloudSyncPreferences]'s link internally — see [CloudSyncProvider.unlink]'s
     * doc) and forgets the locally stored sync passphrase, since it's no
     * longer useful without a linked destination to sync it to.
     */
    fun disconnect() = viewModelScope.launch {
        val linked = settings.value.linkedProvider
        linked?.let { providers[it]?.unlink() }
        SyncPassphraseStore.clear(context)
        _hasPassphrase.value = false
    }
}
