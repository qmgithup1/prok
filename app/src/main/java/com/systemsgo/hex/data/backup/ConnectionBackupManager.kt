package com.systemsgo.hex.data.backup

import android.content.Context
import android.net.Uri
import com.systemsgo.hex.BuildConfig
import com.systemsgo.hex.data.model.ConnectionFolder
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.repository.ConnectionFolderRepository
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.security.BackupCrypto
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk (pre-encryption) contents of a connections backup file.
 *
 * NOTE ON SECRETS: [RdpProfileRepository.getAllProfiles] already returns
 * profiles with every credential field (password, gatewayPassword,
 * sshPrivateKey, sshTunnelPassword, ...) decrypted from the on-device
 * Android-Keystore-backed [com.systemsgo.hex.security.CryptoHelper] encryption
 * into plain strings — that per-device key can never be exported or restored
 * on another device, so credentials would otherwise be unrecoverable outside
 * this install. Those plain strings only ever exist in memory and inside
 * this payload *before* [BackupCrypto.encrypt] wraps the whole JSON blob in
 * AES-256-GCM under a key derived from the user's backup password — the
 * bytes that actually reach disk (and everything in them, credentials
 * included) are always ciphertext. On import, [RdpProfileRepository.saveProfile]
 * re-encrypts every secret with *this* device's own Keystore key before it
 * ever touches the database, exactly as if the user had typed it in by hand.
 */
private data class BackupPayload(
    val formatVersion: Int = FORMAT_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val folders: List<ConnectionFolder> = emptyList(),
    val profiles: List<RdpProfile> = emptyList(),
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

/**
 * Handles "Export All Connections" and "Import Connections" (Settings → Data).
 *
 * Export bundles every saved [RdpProfile] (including every field — RDP/VNC/SSH
 * settings, RD Gateway, RemoteApp, SSH tunnel, Wake-on-LAN, folder assignment,
 * tags — with secrets decrypted to plain values) plus every [ConnectionFolder],
 * serializes them to JSON, and encrypts the whole thing with
 * [BackupCrypto] under a password the user supplies.
 *
 * Import decrypts that file with a supplied password and merges its contents
 * into the local database, skipping anything that already exists so running
 * an import twice (or importing an old backup after already recreating some
 * of the same connections) never creates duplicates or overwrites local data.
 */
@Singleton
class ConnectionBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: RdpProfileRepository,
    private val folderRepository: ConnectionFolderRepository,
) {
    private val gson: Gson = GsonBuilder().create()

    data class ExportResult(val profileCount: Int, val folderCount: Int)

    data class ImportResult(
        val importedProfiles: Int,
        val skippedProfiles: Int,
        val importedFolders: Int,
        val skippedFolders: Int,
    )

    /** Thrown for problems specific to reading/decrypting a backup file. */
    sealed class BackupException(message: String) : Exception(message) {
        class InvalidPassword :
            BackupException("Incorrect password, or the backup file is corrupted.")
        class CorruptFile(message: String) : BackupException(message)
    }

    /**
     * Writes every saved connection + folder to [uri] as one AES-256-GCM
     * encrypted file, protected by [password].
     *
     * Thin wrapper around [buildBackupBytes] — kept so existing call sites
     * (Settings → Data → "Export All Connections", which hands the user a
     * Storage-Access-Framework [Uri] to write to) don't need to change.
     */
    suspend fun exportTo(uri: Uri, password: String): ExportResult = withContext(Dispatchers.IO) {
        val (encrypted, result) = buildBackupBytes(password)

        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Could not open the destination file for writing.")
        stream.use { it.write(encrypted) }

        result
    }

    /**
     * CLOUD-SYNC FEATURE (Part 1/3): builds the exact same AES-256-GCM
     * encrypted backup payload as [exportTo], but returns it as an in-memory
     * [ByteArray] instead of writing it to a [Uri].
     *
     * This is what a future `CloudSyncManager` (Part 2) will upload to
     * Drive/Dropbox — a cloud provider's upload call takes raw bytes, not a
     * `content://` Uri, and there is no "destination file" on this device to
     * open a stream to. Extracting this out of [exportTo] means the on-disk
     * export and the cloud-upload path share one implementation and can never
     * silently drift apart (e.g. one of them forgetting to strip
     * [com.systemsgo.hex.data.model.RdpProfile.lastScreenshotPath]).
     */
    suspend fun buildBackupBytes(password: String): Pair<ByteArray, ExportResult> = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "Backup password must not be empty" }

        // getAllProfiles() already decrypts every secret field via CryptoHelper
        // (and defensively sanitizes any single profile whose Keystore-backed
        // secrets can't be decrypted, rather than throwing) — see
        // RdpProfileRepository.getAllProfiles().
        val profiles = profileRepository.getAllProfiles().first()
        val folders = folderRepository.getAllFolders().first()

        // Strip device-local state that would be meaningless (or actively
        // misleading) after restoring to a different install/device: a
        // "connected" flag left over from this session, and a screenshot
        // filename pointing at a JPEG that only exists in this app's local
        // files directory.
        val exportableProfiles = profiles.map { profile ->
            profile.copy(
                isConnected = false,
                lastScreenshotFilename = null,
                lastScreenshotPath = null,
            )
        }

        val payload = BackupPayload(
            appVersion = BuildConfig.VERSION_NAME,
            folders = folders,
            profiles = exportableProfiles,
        )
        val json = gson.toJson(payload)
        val encrypted = BackupCrypto.encrypt(json.toByteArray(Charsets.UTF_8), password)

        encrypted to ExportResult(profileCount = exportableProfiles.size, folderCount = folders.size)
    }

    /**
     * Reads an encrypted backup from [uri], decrypts it with [password], and
     * restores its connections/folders into the local database.
     *
     * Thin wrapper around [restoreFromBytes] — kept so existing call sites
     * (Settings → Data → "Import Connections") don't need to change.
     */
    suspend fun importFrom(uri: Uri, password: String): ImportResult = withContext(Dispatchers.IO) {
        val encrypted = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not open the backup file for reading.")

        restoreFromBytes(encrypted, password)
    }

    /**
     * CLOUD-SYNC FEATURE (Part 1/3): the [ByteArray] counterpart of
     * [importFrom] — decrypts [encrypted] with [password] and merges its
     * contents into the local database, using the exact same
     * dedupe-by-identity logic as a local restore (see [profileDedupeKey]).
     *
     * This is what a future `CloudSyncManager` (Part 2) will call after
     * downloading the backup blob from Drive/Dropbox — the downloaded bytes
     * never touch disk as a plaintext file, and there is no [Uri] to read
     * from (the cloud SDKs hand back an [InputStream] or [ByteArray]
     * directly), so [importFrom]'s Uri-reading step is skipped entirely
     * here while everything after "decrypt the bytes" stays identical.
     */
    suspend fun restoreFromBytes(encrypted: ByteArray, password: String): ImportResult = withContext(Dispatchers.IO) {
        val decrypted = try {
            BackupCrypto.decrypt(encrypted, password)
        } catch (e: BackupCrypto.InvalidPasswordException) {
            throw BackupException.InvalidPassword()
        } catch (e: BackupCrypto.CorruptBackupException) {
            throw BackupException.CorruptFile(e.message ?: "This backup file is not valid.")
        }

        val payload = try {
            gson.fromJson(String(decrypted, Charsets.UTF_8), BackupPayload::class.java)
                ?: throw BackupException.CorruptFile("The backup file is empty or unreadable.")
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException.CorruptFile("The backup file could not be parsed.")
        }

        // ── Folders first, so profiles below can be re-pointed at the local
        //    (possibly pre-existing) folder that corresponds to each one. ──
        val existingFolders = folderRepository.getAllFolders().first().toMutableList()
        val folderIdRemap = mutableMapOf<String, String?>() // backup folder id -> local folder id
        var importedFolders = 0
        var skippedFolders = 0

        for (folder in payload.folders) {
            val match = existingFolders.firstOrNull {
                it.id == folder.id || it.name.trim().equals(folder.name.trim(), ignoreCase = true)
            }
            if (match != null) {
                folderIdRemap[folder.id] = match.id
                skippedFolders++
            } else {
                val created = folderRepository.createFolder(folder.name, folder.color, folder.icon)
                existingFolders.add(created)
                folderIdRemap[folder.id] = created.id
                importedFolders++
            }
        }

        // ── Profiles: skip anything that already exists (by id, or by the
        //    same protocol+host+port+username+name signature) so re-running
        //    an import — accidentally or on purpose — is always safe. ──
        val existingProfiles = profileRepository.getAllProfiles().first()
        val existingIds = existingProfiles.mapTo(mutableSetOf()) { it.id }
        val existingKeys = existingProfiles.mapTo(mutableSetOf()) { profileDedupeKey(it) }

        var importedProfiles = 0
        var skippedProfiles = 0

        for (backupProfile in payload.profiles) {
            val key = profileDedupeKey(backupProfile)
            if (existingIds.contains(backupProfile.id) || existingKeys.contains(key)) {
                skippedProfiles++
                continue
            }

            val toInsert = backupProfile.copy(
                folderId = backupProfile.folderId?.let { folderIdRemap[it] },
                isConnected = false,
                lastScreenshotFilename = null,
                lastScreenshotPath = null,
            )

            try {
                // saveProfile() re-encrypts every secret with THIS device's
                // Keystore key (see RdpProfileRepository.withEncryptedSecrets()) —
                // the plaintext values from the backup never reach the database.
                profileRepository.saveProfile(toInsert)
                existingIds.add(toInsert.id)
                existingKeys.add(key)
                importedProfiles++
            } catch (e: SecurityException) {
                // Keystore unavailable for this device right now — skip this
                // one profile rather than aborting the whole import.
                android.util.Log.e(
                    "ConnectionBackupManager",
                    "Failed to save imported profile ${toInsert.id} (Keystore unavailable)", e
                )
                skippedProfiles++
            }
        }

        ImportResult(
            importedProfiles = importedProfiles,
            skippedProfiles = skippedProfiles,
            importedFolders = importedFolders,
            skippedFolders = skippedFolders,
        )
    }

    /**
     * Identity used to recognize "the same connection" across a backup/restore
     * cycle even if its UUID changed (e.g. it was re-created by hand after a
     * lost backup). Deliberately ignores fields that don't identify the
     * *target* being connected to (color depth, gateway, tags, etc.).
     */
    private fun profileDedupeKey(profile: RdpProfile): String = listOf(
        profile.protocolType.name,
        profile.host.trim().lowercase(),
        profile.port.toString(),
        profile.username.trim().lowercase(),
        profile.name.trim().lowercase(),
    ).joinToString("|")
}
