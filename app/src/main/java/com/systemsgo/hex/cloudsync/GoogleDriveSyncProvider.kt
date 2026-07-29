package com.systemsgo.hex.cloudsync

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.systemsgo.hex.data.repository.CloudSyncPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CLOUD-SYNC FEATURE (Part 2/3).
 *
 * [CloudSyncProvider] backed by Google Drive's `appDataFolder` special
 * folder — see [CloudSyncProvider]'s own doc for why `appDataFolder` (not
 * `drive.file`/`drive`) was chosen.
 *
 * Two separate Google-side concerns are deliberately kept apart here:
 *  1. **Identity** — "which Google account is this?" — via Credential
 *     Manager's [GetGoogleIdOption] ([link]). This is what proves to *this
 *     app* who the user is and gives a human-readable email for the
 *     Settings UI, exactly like [com.systemsgo.hex.auth.EntraIdAuthManager]
 *     does for Entra ID (see that class for the sibling pattern).
 *  2. **API authorization** — "is this app allowed to read/write this
 *     account's Drive appdata?" — via [GoogleAccountCredential], Android's
 *     own AccountManager-integrated OAuth2 helper (`google-api-client-android`).
 *     It is deliberately NOT derived from the ID token in (1): an ID token
 *     only proves identity, it is not an access token scoped to
 *     `drive.appdata` and Google does not intend it to be used as one.
 *     [GoogleAccountCredential] instead silently obtains/refreshes a real
 *     OAuth2 access token for [DriveScopes.DRIVE_APPDATA] through Google
 *     Play services, using the Android account selected in (1) — the first
 *     time this actually happens it throws
 *     [UserRecoverableAuthIOException], which the linking UI (Part 3) must
 *     catch once and resolve via `startActivityForResult(e.intent, ...)` to
 *     show the one-time Drive-appdata consent screen. After that, refresh
 *     is fully automatic — this class does not persist any token itself.
 *
 * Only the account's email (a non-secret identifier) is persisted locally,
 * in [CloudSyncPreferences.linkedAccountLabel] — there is no OAuth token to
 * store because [GoogleAccountCredential] re-derives one on demand from the
 * device's Google account + Play services' own encrypted token cache.
 */
@Singleton
class GoogleDriveSyncProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudSyncPreferences: CloudSyncPreferences,
) : CloudSyncProvider {

    override val provider: CloudProvider = CloudProvider.GOOGLE_DRIVE

    companion object {
        private const val BACKUP_FILE_NAME = "systemsgo_connections_backup.enc"
        private const val OCTET_STREAM = "application/octet-stream"
        private const val DRIVE_FIELDS = "id, modifiedTime, size"

        // CLOUD-SYNC-CONFIG FIX: this used to be a literal placeholder string
        // hardcoded here. It now comes from BuildConfig.GOOGLE_DRIVE_WEB_CLIENT_ID
        // (see app/build.gradle.kts's "CLOUD-SYNC-CONFIG FIX" comment), which is
        // itself read from the GOOGLE_DRIVE_WEB_CLIENT_ID env var (CI) or the
        // `google.drive.client.id` Gradle property (local
        // ~/.gradle/gradle.properties, never committed) — falling back to the
        // same "TODO_..." placeholder if neither is set. So filling this in
        // later is a one-line Gradle-property/env-var change, not a source edit.
        //
        // What to paste there: the Google Cloud OAuth 2.0 **Web client ID**
        // — Google Cloud Console → "APIs & Services" → "Credentials" →
        // "OAuth 2.0 Client IDs" → the entry of type "Web application" (NOT
        // the "Android" client ID entry, even though this is an Android app
        // — GetGoogleIdOption needs the Web client ID because that's the
        // audience the returned ID token is issued for; see "Get your
        // backend server's client ID" at
        // https://developer.android.com/identity/sign-in/credential-manager-siwg).
        // Also, on that same Google Cloud project: register this app's
        // package name (com.systemsgo.hex) + release/debug signing
        // certificate SHA-1 fingerprint under an "Android" OAuth client
        // (required for Google Play services to authorize the account, even
        // though its client ID isn't the one referenced in code), and
        // enable the "Google Drive API" under "APIs & Services" → "Library".
        private val SERVER_CLIENT_ID: String = com.systemsgo.hex.BuildConfig.GOOGLE_DRIVE_WEB_CLIENT_ID

        /** UI-CONFIG-GUARD FIX: true once [SERVER_CLIENT_ID] above has actually been
         *  replaced with a real OAuth Web client ID. Before this fix, the "Connect
         *  Google Drive" button was always shown/enabled even with the placeholder
         *  value still in place, so tapping it triggered a confusing low-level
         *  Credential Manager/OAuth failure instead of a clear "not set up yet"
         *  message. [CloudSyncSettingsScreen] now checks this to disable the button
         *  and explain why, instead of letting the user hit that failure. */
        val isConfigured: Boolean = !SERVER_CLIENT_ID.startsWith("TODO_")
    }

    // ── Linking (Part 3's "Connect Google Drive" button calls this) ────────

    /**
     * Shows the Credential Manager account picker and, on success, records
     * the chosen account's email via [CloudSyncPreferences.setLinkedProvider].
     * Must be called with a live [Activity]. Does not by itself request the
     * `drive.appdata` consent — see the class doc for why that happens
     * lazily on first API use instead.
     *
     * @param preferAuthorizedAccounts When true (the default — used by the
     * "Connect Google Drive" button), first asks Credential Manager for only
     * accounts already authorized for this app ([GetGoogleIdOption]'s own
     * default: `filterByAuthorizedAccounts = true`), which is the frictionless
     * path for a *returning* user; only if that comes back empty
     * ([NoCredentialException]) do we fall back to the full device account
     * picker (`false`) — this is Google's own documented two-step flow
     * (developer.android.com/identity/sign-in/credential-manager-siwg-implementation).
     * "Switch account" passes false directly instead, since re-trying `true`
     * first there would defeat the point — it would just silently hand back
     * the same already-authorized account instead of letting the user pick.
     */
    suspend fun link(activity: Activity, preferAuthorizedAccounts: Boolean = true): CloudLinkResult = withContext(Dispatchers.IO) {
        try {
            val credentialManager = CredentialManager.create(activity)

            // SECURITY FIX (web-research follow-up): the original request
            // built [GetGoogleIdOption] with no nonce. Android's own "Sign in
            // with Google" guide (developer.android.com/identity/sign-in/
            // credential-manager-siwg-implementation) generates a fresh,
            // securely-random nonce per request and passes it here — without
            // one, a captured ID token could in principle be replayed later.
            // We don't verify the token server-side (there's no backend for
            // this app — see class doc, "Identity" vs "API authorization"),
            // but setting the nonce costs nothing and matches Google's own
            // reference implementation instead of silently omitting it.
            suspend fun requestCredential(filterByAuthorizedAccounts: Boolean) =
                credentialManager.getCredential(
                    activity,
                    GetCredentialRequest.Builder()
                        .addCredentialOption(
                            GetGoogleIdOption.Builder()
                                .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
                                .setServerClientId(SERVER_CLIENT_ID)
                                .setAutoSelectEnabled(false)
                                .setNonce(java.util.UUID.randomUUID().toString())
                                .build()
                        )
                        .build()
                )

            val response = if (preferAuthorizedAccounts) {
                try {
                    requestCredential(filterByAuthorizedAccounts = true)
                } catch (e: NoCredentialException) {
                    // No account has authorized this app yet (first-ever link
                    // on this device) — fall back to the full account picker.
                    // If this ALSO throws NoCredentialException (no Google
                    // account on the device at all), it propagates to the
                    // outer catch below and surfaces the normal "no account
                    // available" message.
                    requestCredential(filterByAuthorizedAccounts = false)
                }
            } else {
                requestCredential(filterByAuthorizedAccounts = false)
            }

            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(response.credential.data)
            val email = googleIdTokenCredential.id
            val displayName = googleIdTokenCredential.displayName
            val photoUrl = googleIdTokenCredential.profilePictureUri?.toString()

            cloudSyncPreferences.setLinkedProvider(
                CloudProvider.GOOGLE_DRIVE,
                accountLabel = email,
                displayName = displayName,
                photoUrl = photoUrl,
            )
            CloudLinkResult.Success(accountLabel = email, displayName = displayName, photoUrl = photoUrl)
        } catch (e: GetCredentialCancellationException) {
            CloudLinkResult.Cancelled
        } catch (e: NoCredentialException) {
            CloudLinkResult.Failure(CloudSyncError.Unknown("No Google account is available on this device to link."))
        } catch (e: GetCredentialException) {
            CloudLinkResult.Failure(CloudSyncError.Unknown(e.message ?: "Could not sign in to Google."))
        } catch (e: GoogleIdTokenParsingException) {
            CloudLinkResult.Failure(CloudSyncError.Unknown(e.message ?: "Could not parse the Google ID token."))
        }
    }

    // ── CloudSyncProvider ────────────────────────────────────────────────

    override suspend fun isLinked(): Boolean = withContext(Dispatchers.IO) {
        !linkedAccountName().isNullOrBlank()
    }

    override suspend fun accountLabel(): String? = withContext(Dispatchers.IO) {
        linkedAccountName()
    }

    override suspend fun upload(bytes: ByteArray): CloudUploadOutcome = withContext(Dispatchers.IO) {
        try {
            val drive = driveService() ?: return@withContext CloudUploadOutcome.Failure(CloudSyncError.NotLinked)
            val content = ByteArrayContent(OCTET_STREAM, bytes)
            val existingId = findBackupFileId(drive)

            val resultFile = if (existingId == null) {
                val metadata = DriveFile().apply {
                    name = BACKUP_FILE_NAME
                    parents = listOf("appDataFolder")
                }
                drive.files().create(metadata, content)
                    .setFields(DRIVE_FIELDS)
                    .execute()
            } else {
                // No body metadata on update — only the file's bytes change,
                // never its name/parent, so nothing needs patching there.
                drive.files().update(existingId, null, content)
                    .setFields(DRIVE_FIELDS)
                    .execute()
            }
            CloudUploadOutcome.Success(resultFile.toCloudMetadata())
        } catch (e: Exception) {
            CloudUploadOutcome.Failure(mapExceptionToError(e))
        }
    }

    override suspend fun download(): CloudDownloadOutcome = withContext(Dispatchers.IO) {
        try {
            val drive = driveService() ?: return@withContext CloudDownloadOutcome.Failure(CloudSyncError.NotLinked)
            val fileId = findBackupFileId(drive) ?: return@withContext CloudDownloadOutcome.NotFound

            val meta = drive.files().get(fileId).setFields(DRIVE_FIELDS).execute()
            val output = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(output)

            CloudDownloadOutcome.Success(output.toByteArray(), meta.toCloudMetadata())
        } catch (e: Exception) {
            CloudDownloadOutcome.Failure(mapExceptionToError(e))
        }
    }

    override suspend fun remoteMetadata(): CloudFileMetadata? = withContext(Dispatchers.IO) {
        try {
            val drive = driveService() ?: return@withContext null
            val fileId = findBackupFileId(drive) ?: return@withContext null
            drive.files().get(fileId).setFields(DRIVE_FIELDS).execute().toCloudMetadata()
        } catch (e: Exception) {
            // remoteMetadata() is used for a "should I upload or download?"
            // check, not surfaced to the user directly — CloudSyncManager
            // (Part 2) treats a null return the same as "nothing usable
            // remotely right now" and falls back to uploading, which is the
            // safe default if e.g. the token silently expired between app
            // launches.
            null
        }
    }

    override suspend fun unlink() = withContext(Dispatchers.IO) {
        // Deliberately does NOT call GoogleAuthUtil.clearToken()/revoke the
        // OAuth grant server-side: that grant may be shared with other
        // Drive-appdata consumers under this same Google account (unlikely
        // for this narrow scope, but not this method's business either
        // way) — see CloudSyncProvider.unlink()'s doc: "does not delete the
        // remote file" extends to "does not revoke the grant", only "this
        // device forgets about the account".
        if (cloudSyncPreferences.currentSettingsSnapshot().linkedProvider == CloudProvider.GOOGLE_DRIVE) {
            cloudSyncPreferences.clearLink()
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun linkedAccountName(): String? {
        val settings = cloudSyncPreferences.currentSettingsSnapshot()
        return if (settings.linkedProvider == CloudProvider.GOOGLE_DRIVE) settings.linkedAccountLabel else null
    }

    private fun driveService(): Drive? {
        val accountName = linkedAccountName() ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_APPDATA))
        credential.selectedAccountName = accountName
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("SystemsGo")
            .build()
    }

    /** appDataFolder holds only this one file for this app, so a name filter is enough to find it. */
    private fun findBackupFileId(drive: Drive): String? {
        val result = drive.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$BACKUP_FILE_NAME' and trashed = false")
            .setFields("files(id, modifiedTime, size)")
            .execute()
        return result.files?.firstOrNull()?.id
    }

    private fun DriveFile.toCloudMetadata(): CloudFileMetadata = CloudFileMetadata(
        remoteModifiedAtEpochMs = modifiedTime?.value ?: 0L,
        sizeBytes = getSize() ?: 0L,
    )

    private fun mapExceptionToError(e: Exception): CloudSyncError = when (e) {
        is UserRecoverableAuthIOException -> CloudSyncError.AuthExpired
        is GoogleJsonResponseException -> when {
            e.statusCode == 401 -> CloudSyncError.AuthExpired
            e.statusCode == 403 && e.details?.errors.orEmpty().any {
                it.reason == "storageQuotaExceeded" || it.reason == "quotaExceeded"
            } -> CloudSyncError.QuotaExceeded
            e.statusCode == 403 -> CloudSyncError.AuthExpired
            else -> CloudSyncError.Unknown(e.details?.message ?: e.message ?: "Google Drive error")
        }
        is IOException -> CloudSyncError.NetworkError
        else -> CloudSyncError.Unknown(e.message ?: "Unknown Google Drive error")
    }
}
