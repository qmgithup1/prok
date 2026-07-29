package com.systemsgo.hex.cloudsync

import android.app.Activity
import android.content.Context
import com.dropbox.core.DbxException
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.InvalidAccessTokenException
import com.dropbox.core.NetworkIOException
import com.dropbox.core.RetryException
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.DownloadErrorException
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.GetMetadataErrorException
import com.dropbox.core.v2.files.UploadErrorException
import com.dropbox.core.v2.files.WriteMode
import com.systemsgo.hex.data.repository.CloudSyncPreferences
import com.systemsgo.hex.security.CryptoHelper
import com.systemsgo.hex.security.openEncryptedPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CLOUD-SYNC FEATURE (Part 2/3).
 *
 * [CloudSyncProvider] backed by Dropbox, scoped to this app's **App
 * folder** (`/Apps/SystemsGo/...`) rather than the user's whole Dropbox — see
 * [CloudSyncProvider]'s own doc. Unlike Drive's `appDataFolder`, this
 * restriction is NOT expressed via an OAuth scope parameter in code — it's
 * configured once on the Dropbox App Console (Permissions → "App folder"
 * access type) and enforced server-side for every token this app's App Key
 * ever issues; see [APP_KEY] below for where that's wired up (now sourced
 * from BuildConfig — see its CLOUD-SYNC-CONFIG FIX comment).
 *
 * Uses PKCE (`Auth.startOAuth2PKCE`) specifically because it's the one
 * Dropbox Android SDK flow that requires no client secret to ship in the
 * APK (see `dropbox-android-sdk` vs `dropbox-core-sdk` note in
 * `gradle/libs.versions.toml`).
 *
 * Auth model note: unlike [GoogleDriveSyncProvider] (which never persists a
 * token — [com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential]
 * re-derives one from the Android account + Play services on demand),
 * Dropbox's PKCE flow hands this app a real [DbxCredential] (refresh token +
 * short-lived access token) directly — there is no Android
 * AccountManager-equivalent for Dropbox to re-derive it from, so it has to
 * be persisted locally. It gets the exact same at-rest protection as every
 * other credential this app stores: [CryptoHelper]-wrapped (Keystore-backed
 * AES-256-GCM, dedicated AAD) inside `openEncryptedPrefs`, same pattern as
 * [com.systemsgo.hex.security.SyncPassphraseStore] and
 * [com.systemsgo.hex.data.db.DatabaseKeyProvider].
 */
@Singleton
class DropboxSyncProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudSyncPreferences: CloudSyncPreferences,
) : CloudSyncProvider {

    override val provider: CloudProvider = CloudProvider.DROPBOX

    companion object {
        /** Path is relative to the App folder root — resolves to `/Apps/SystemsGo/connections_backup.enc`. */
        private const val BACKUP_FILE_PATH = "/connections_backup.enc"

        // CLOUD-SYNC-CONFIG FIX: this used to be a literal placeholder string
        // hardcoded here, duplicated by hand into AndroidManifest.xml's
        // redirect scheme. It now comes from BuildConfig.DROPBOX_APP_KEY
        // (see app/build.gradle.kts's "CLOUD-SYNC-CONFIG FIX" comment),
        // itself read from the DROPBOX_APP_KEY env var (CI) or the
        // `dropbox.app.key` Gradle property (local
        // ~/.gradle/gradle.properties, never committed) — falling back to
        // the same "TODO_..." placeholder if neither is set. The exact same
        // value is used to derive AndroidManifest.xml's
        // `${dropboxRedirectScheme}` placeholder in build.gradle.kts, so the
        // two can no longer drift apart, and filling this in later is a
        // one-line Gradle-property/env-var change — no source or manifest
        // edit needed.
        //
        // What to paste there: the Dropbox App Key (Dropbox App Console →
        // your app → Settings tab → "App key"). This is a public identifier,
        // safe to ship in the APK — PKCE is exactly what means no App
        // *Secret* is ever needed on an Android client (see the class doc).
        // Also set "Permission type" to "App folder" (not "Full Dropbox")
        // under the App Console's Permissions tab — see the class doc for
        // why that, not a code-side scope, is what actually confines this
        // app to /Apps/SystemsGo/....
        private val APP_KEY: String = com.systemsgo.hex.BuildConfig.DROPBOX_APP_KEY

        /** UI-CONFIG-GUARD FIX: mirrors [com.systemsgo.hex.cloudsync.GoogleDriveSyncProvider.isConfigured] —
         *  true once [APP_KEY] above has actually been replaced with a real Dropbox
         *  App Key (and the matching AndroidManifest.xml redirect scheme updated to
         *  match). Lets [CloudSyncSettingsScreen] disable the "Connect Dropbox"
         *  button with an explanatory subtitle instead of letting the user hit
         *  Auth.startOAuth2PKCE with a placeholder key and get a confusing failure. */
        val isConfigured: Boolean = !APP_KEY.startsWith("TODO_")
    }

    private val requestConfig: DbxRequestConfig by lazy {
        DbxRequestConfig.newBuilder("SystemsGo-Android/CloudSync").build()
    }

    /**
     * Same [CryptoHelper] + `openEncryptedPrefs` idiom as
     * [com.systemsgo.hex.security.SyncPassphraseStore] — kept private/nested
     * here rather than promoted to its own `security/` class, since (unlike
     * the sync passphrase) nothing outside this provider ever needs to read
     * or write a Dropbox credential.
     */
    private object CredentialStore {
        private const val PREFS = "systemsgo_cloud_sync_secure"
        private const val KEY = "dropbox_credential_enc_v1"
        private const val AAD_CONTEXT = "cloud_sync_dropbox_credential"

        fun read(context: Context): DbxCredential? {
            val sp = context.openEncryptedPrefs(PREFS)
            val stored = sp.getString(KEY, null) ?: return null
            return try {
                val json = CryptoHelper.decrypt(stored, AAD_CONTEXT)
                DbxCredential.Reader.readFully(json)
            } catch (e: Exception) {
                android.util.Log.w("DropboxSyncProvider", "Stored Dropbox credential unreadable — treating as unlinked", e)
                null
            }
        }

        fun write(context: Context, credential: DbxCredential) {
            val sp = context.openEncryptedPrefs(PREFS)
            val enc = CryptoHelper.encrypt(credential.toString(), AAD_CONTEXT)
            sp.edit().putString(KEY, enc).commit()
        }

        fun clear(context: Context) {
            context.openEncryptedPrefs(PREFS).edit().remove(KEY).commit()
        }
    }

    // ── Linking (Part 3's "Connect Dropbox" button calls this pair) ────────

    /**
     * Starts the PKCE browser/Custom-Tab flow. Unlike
     * [GoogleDriveSyncProvider.link] this does NOT return the result
     * synchronously — Dropbox's flow redirects the user out to a browser
     * and back, so it can only complete on a later app resume. Call
     * [completeLinkIfPending] from the linking [Activity]'s `onResume()`.
     */
    fun startLink(activity: Activity) {
        Auth.startOAuth2PKCE(activity, APP_KEY, requestConfig)
    }

    /**
     * Checks whether the SDK has a freshly-completed auth result waiting
     * (`Auth.getDbxCredential()` only ever returns non-null once, right
     * after a successful redirect back into this app following
     * [startLink]). Returns null when there is nothing pending (e.g. this
     * `onResume()` wasn't preceded by a link attempt) — callers should treat
     * null as "no-op", not as a failure.
     */
    suspend fun completeLinkIfPending(): CloudLinkResult? = withContext(Dispatchers.IO) {
        val credential = Auth.getDbxCredential() ?: return@withContext null
        try {
            CredentialStore.write(context, credential)
            val client = DbxClientV2(requestConfig, credential)
            val email = client.users().currentAccount.email
            cloudSyncPreferences.setLinkedProvider(CloudProvider.DROPBOX, email)
            CloudLinkResult.Success(email)
        } catch (e: DbxException) {
            CloudLinkResult.Failure(mapExceptionToError(e))
        }
    }

    // ── CloudSyncProvider ────────────────────────────────────────────────

    override suspend fun isLinked(): Boolean = withContext(Dispatchers.IO) {
        CredentialStore.read(context) != null
    }

    override suspend fun accountLabel(): String? = withContext(Dispatchers.IO) {
        val settings = cloudSyncPreferences.currentSettingsSnapshot()
        if (settings.linkedProvider == CloudProvider.DROPBOX) settings.linkedAccountLabel else null
    }

    override suspend fun upload(bytes: ByteArray): CloudUploadOutcome = withContext(Dispatchers.IO) {
        try {
            val client = dbxClient() ?: return@withContext CloudUploadOutcome.Failure(CloudSyncError.NotLinked)
            val metadata = client.files().uploadBuilder(BACKUP_FILE_PATH)
                .withMode(WriteMode.OVERWRITE)
                .uploadAndFinish(ByteArrayInputStream(bytes))
            CloudUploadOutcome.Success(metadata.toCloudMetadata())
        } catch (e: Exception) {
            CloudUploadOutcome.Failure(mapExceptionToError(e))
        }
    }

    override suspend fun download(): CloudDownloadOutcome = withContext(Dispatchers.IO) {
        try {
            val client = dbxClient() ?: return@withContext CloudDownloadOutcome.Failure(CloudSyncError.NotLinked)
            val output = ByteArrayOutputStream()
            val metadata = try {
                client.files().download(BACKUP_FILE_PATH).download(output)
            } catch (e: DownloadErrorException) {
                if (e.errorValue?.isPath == true && e.errorValue.pathValue?.isNotFound == true) {
                    return@withContext CloudDownloadOutcome.NotFound
                }
                throw e
            }
            CloudDownloadOutcome.Success(output.toByteArray(), metadata.toCloudMetadata())
        } catch (e: Exception) {
            CloudDownloadOutcome.Failure(mapExceptionToError(e))
        }
    }

    override suspend fun remoteMetadata(): CloudFileMetadata? = withContext(Dispatchers.IO) {
        try {
            val client = dbxClient() ?: return@withContext null
            (client.files().getMetadata(BACKUP_FILE_PATH) as? FileMetadata)?.toCloudMetadata()
        } catch (e: GetMetadataErrorException) {
            // Includes "not found" — CloudSyncManager (Part 2) treats a null
            // remoteMetadata() as "nothing to compare against yet" and
            // uploads, same handling as Drive's equivalent catch (see
            // GoogleDriveSyncProvider.remoteMetadata()'s doc comment).
            null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun unlink() = withContext(Dispatchers.IO) {
        CredentialStore.clear(context)
        if (cloudSyncPreferences.currentSettingsSnapshot().linkedProvider == CloudProvider.DROPBOX) {
            cloudSyncPreferences.clearLink()
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun dbxClient(): DbxClientV2? {
        val credential = CredentialStore.read(context) ?: return null
        return DbxClientV2(requestConfig, credential)
    }

    private fun FileMetadata.toCloudMetadata(): CloudFileMetadata = CloudFileMetadata(
        remoteModifiedAtEpochMs = (clientModified ?: serverModified)?.time ?: 0L,
        sizeBytes = size,
    )

    private fun mapExceptionToError(e: Exception): CloudSyncError = when (e) {
        is InvalidAccessTokenException -> CloudSyncError.AuthExpired
        is UploadErrorException -> {
            val reason = e.errorValue?.toString().orEmpty()
            if (reason.contains("insufficient_space", ignoreCase = true)) {
                CloudSyncError.QuotaExceeded
            } else {
                CloudSyncError.Unknown(e.message ?: "Dropbox upload error")
            }
        }
        is RetryException, is NetworkIOException -> CloudSyncError.NetworkError
        is DbxException -> CloudSyncError.Unknown(e.message ?: "Dropbox error")
        else -> CloudSyncError.Unknown(e.message ?: "Unknown Dropbox error")
    }
}
