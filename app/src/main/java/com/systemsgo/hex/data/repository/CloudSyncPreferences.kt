package com.systemsgo.hex.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.systemsgo.hex.cloudsync.CloudProvider
import com.systemsgo.hex.security.openEncryptedPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.channels.awaitClose
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CLOUD-SYNC FEATURE (Part 1/3).
 *
 * Non-secret state for "Sync connections to Google Drive / Dropbox"
 * (Settings → Cloud Sync, built in Part 3). The actual account credentials
 * live wherever each [com.systemsgo.hex.cloudsync.CloudSyncProvider]
 * implementation's own SDK stores them (AccountManager/Credential Manager
 * for Drive, the Dropbox SDK's own token store for Dropbox) — this class
 * only remembers *which* provider is linked and the sync bookkeeping the UI
 * needs to display (last sync time/result, auto-sync toggle). The backup
 * passphrase itself is separate again — see [com.systemsgo.hex.security.SyncPassphraseStore]
 * — since it's sensitive in a way "last synced 2 hours ago" is not.
 *
 * Follows the exact same EncryptedSharedPreferences + callbackFlow pattern
 * as [AppSettingsRepository] (see that class's CRIT-5 FIX comment for why
 * plain SharedPreferences/DataStore isn't used) rather than introducing a
 * second, different persistence mechanism into the codebase.
 */
data class CloudSyncSettings(
    /** Null = no provider linked; cloud sync is fully off. */
    val linkedProvider: CloudProvider? = null,
    /** Human-readable account label (usually an email) for the linked provider, if any. */
    val linkedAccountLabel: String? = null,
    /** Best-effort display name (e.g. "Ahmad Ali") for the account card avatar header, if the provider gave one. */
    val linkedAccountDisplayName: String? = null,
    /** Best-effort profile photo URL for the account card avatar, if the provider gave one (Google Drive only). */
    val linkedAccountPhotoUrl: String? = null,
    val autoSyncEnabled: Boolean = false,
    /** Only meaningful when [autoSyncEnabled] is true. */
    val autoSyncIntervalMinutes: Int = DEFAULT_AUTO_SYNC_INTERVAL_MINUTES,
    /** Epoch millis of the last sync attempt that reached a provider (success OR failure), 0 = never. */
    val lastSyncAttemptAtEpochMs: Long = 0L,
    /** Epoch millis of the last sync that actually completed successfully, 0 = never. */
    val lastSyncSuccessAtEpochMs: Long = 0L,
    /** Null when the last attempt succeeded (or none has happened yet); otherwise a short user-facing message. */
    val lastSyncErrorMessage: String? = null,
) {
    companion object {
        const val MIN_AUTO_SYNC_INTERVAL_MINUTES = 15
        const val DEFAULT_AUTO_SYNC_INTERVAL_MINUTES = 60
    }
}

@Singleton
class CloudSyncPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.openEncryptedPrefs("systemsgo_cloud_sync_settings")
    }

    private object Keys {
        const val LINKED_PROVIDER = "linked_provider"
        const val LINKED_ACCOUNT_LABEL = "linked_account_label"
        const val LINKED_ACCOUNT_DISPLAY_NAME = "linked_account_display_name"
        const val LINKED_ACCOUNT_PHOTO_URL = "linked_account_photo_url"
        const val AUTO_SYNC_ENABLED = "auto_sync_enabled"
        const val AUTO_SYNC_INTERVAL_MINUTES = "auto_sync_interval_minutes"
        const val LAST_SYNC_ATTEMPT_AT = "last_sync_attempt_at"
        const val LAST_SYNC_SUCCESS_AT = "last_sync_success_at"
        const val LAST_SYNC_ERROR_MESSAGE = "last_sync_error_message"
    }

    fun currentSettingsSnapshot(): CloudSyncSettings = readSettings()

    private fun readSettings(): CloudSyncSettings = prefs.run {
        CloudSyncSettings(
            linkedProvider = CloudProvider.fromStorageKeyOrNull(getString(Keys.LINKED_PROVIDER, null)),
            linkedAccountLabel = getString(Keys.LINKED_ACCOUNT_LABEL, null),
            linkedAccountDisplayName = getString(Keys.LINKED_ACCOUNT_DISPLAY_NAME, null),
            linkedAccountPhotoUrl = getString(Keys.LINKED_ACCOUNT_PHOTO_URL, null),
            autoSyncEnabled = getBoolean(Keys.AUTO_SYNC_ENABLED, false),
            autoSyncIntervalMinutes = getInt(
                Keys.AUTO_SYNC_INTERVAL_MINUTES,
                CloudSyncSettings.DEFAULT_AUTO_SYNC_INTERVAL_MINUTES
            ),
            lastSyncAttemptAtEpochMs = getLong(Keys.LAST_SYNC_ATTEMPT_AT, 0L),
            lastSyncSuccessAtEpochMs = getLong(Keys.LAST_SYNC_SUCCESS_AT, 0L),
            lastSyncErrorMessage = getString(Keys.LAST_SYNC_ERROR_MESSAGE, null),
        )
    }

    val settingsFlow: Flow<CloudSyncSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(readSettings())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(readSettings())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private fun put(block: SharedPreferences.Editor.() -> Unit) =
        prefs.edit().apply(block).apply()

    /** Called once linking a provider succeeds (Part 2's OAuth flow completing). */
    suspend fun setLinkedProvider(
        provider: CloudProvider,
        accountLabel: String?,
        displayName: String? = null,
        photoUrl: String? = null,
    ) = put {
        putString(Keys.LINKED_PROVIDER, provider.storageKey)
        putString(Keys.LINKED_ACCOUNT_LABEL, accountLabel)
        putString(Keys.LINKED_ACCOUNT_DISPLAY_NAME, displayName)
        putString(Keys.LINKED_ACCOUNT_PHOTO_URL, photoUrl)
        // A fresh link has no sync history yet — clear any stale state left
        // over from a previously linked (and since unlinked) provider so the
        // UI doesn't show an old error/timestamp that no longer applies.
        remove(Keys.LAST_SYNC_ATTEMPT_AT)
        remove(Keys.LAST_SYNC_SUCCESS_AT)
        remove(Keys.LAST_SYNC_ERROR_MESSAGE)
    }

    /** Called when the user unlinks cloud sync entirely (Settings → Cloud Sync → Disconnect). */
    suspend fun clearLink() = put {
        remove(Keys.LINKED_PROVIDER)
        remove(Keys.LINKED_ACCOUNT_LABEL)
        remove(Keys.LINKED_ACCOUNT_DISPLAY_NAME)
        remove(Keys.LINKED_ACCOUNT_PHOTO_URL)
        remove(Keys.AUTO_SYNC_ENABLED)
        remove(Keys.LAST_SYNC_ATTEMPT_AT)
        remove(Keys.LAST_SYNC_SUCCESS_AT)
        remove(Keys.LAST_SYNC_ERROR_MESSAGE)
    }

    suspend fun updateAutoSyncEnabled(enabled: Boolean) = put {
        putBoolean(Keys.AUTO_SYNC_ENABLED, enabled)
    }

    suspend fun updateAutoSyncIntervalMinutes(minutes: Int) = put {
        putInt(
            Keys.AUTO_SYNC_INTERVAL_MINUTES,
            minutes.coerceAtLeast(CloudSyncSettings.MIN_AUTO_SYNC_INTERVAL_MINUTES)
        )
    }

    /** Records a successful sync — updates both the attempt and success timestamps and clears any error. */
    suspend fun recordSyncSuccess(atEpochMs: Long = System.currentTimeMillis()) = put {
        putLong(Keys.LAST_SYNC_ATTEMPT_AT, atEpochMs)
        putLong(Keys.LAST_SYNC_SUCCESS_AT, atEpochMs)
        remove(Keys.LAST_SYNC_ERROR_MESSAGE)
    }

    /** Records a failed sync attempt — updates only the attempt timestamp, leaving the last success as-is. */
    suspend fun recordSyncError(message: String, atEpochMs: Long = System.currentTimeMillis()) = put {
        putLong(Keys.LAST_SYNC_ATTEMPT_AT, atEpochMs)
        putString(Keys.LAST_SYNC_ERROR_MESSAGE, message)
    }
}
