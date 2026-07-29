package com.systemsgo.hex.cloudsync

/**
 * CLOUD-SYNC FEATURE (Part 1/3).
 *
 * The two cloud backends this feature can sync the encrypted connections
 * backup to. Adding a third provider later (e.g. OneDrive) means adding one
 * more entry here + one more [CloudSyncProvider] implementation — nothing
 * else in this file needs to change, since every other cloud-sync type in
 * this package (settings, results, errors) is written against the
 * [CloudSyncProvider] interface, not against "Drive" or "Dropbox" by name.
 */
enum class CloudProvider(
    /** Stored as-is in [com.systemsgo.hex.data.repository.CloudSyncPreferences] — never rename these. */
    val storageKey: String,
) {
    GOOGLE_DRIVE("google_drive"),
    DROPBOX("dropbox"),
    ;

    companion object {
        fun fromStorageKeyOrNull(key: String?): CloudProvider? =
            entries.firstOrNull { it.storageKey == key }
    }
}
