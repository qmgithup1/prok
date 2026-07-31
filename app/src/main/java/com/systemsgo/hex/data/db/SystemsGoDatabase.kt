package com.systemsgo.hex.data.db

import androidx.room.*
import com.systemsgo.hex.data.model.ConnectionFolder
import com.systemsgo.hex.data.model.RdpProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface RdpProfileDao {
    @Query("SELECT * FROM rdp_profiles ORDER BY sortOrder ASC, createdAt DESC")
    fun getAllProfiles(): Flow<List<RdpProfile>>

    @Query("SELECT * FROM rdp_profiles WHERE id = :id")
    suspend fun getProfileById(id: String): RdpProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: RdpProfile)

    @Update
    suspend fun updateProfile(profile: RdpProfile)

    @Delete
    suspend fun deleteProfile(profile: RdpProfile)

    @Query("UPDATE rdp_profiles SET lastConnected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: String, timestamp: Long)

    @Query("UPDATE rdp_profiles SET lastScreenshotFilename = :filename WHERE id = :id")
    suspend fun updateScreenshotFilename(id: String, filename: String)
    // BUG-9 FIX: was "SET lastScreenshotPath = :path" — that column is deprecated and
    // the modern code uses lastScreenshotFilename. The old method was also an orphan
    // (never called anywhere); renamed to updateScreenshotFilename for clarity.

    @Query("UPDATE rdp_profiles SET isConnected = :connected WHERE id = :id")
    suspend fun updateConnectionState(id: String, connected: Boolean)

    // UX-03: Persist drag-to-reorder
    @Query("UPDATE rdp_profiles SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)

    // FIX B3: إعادة تهيئة جميع البطاقات كـ "غير متصل" عند إعادة تشغيل التطبيق.
    // بدون هذا، تظل البطاقات مُعلَّمة كـ isConnected=true بعد الكراش (مضلل للمستخدم).
    @Query("UPDATE rdp_profiles SET isConnected = 0 WHERE isConnected = 1")
    suspend fun resetAllConnectionStates()

    // FAVORITES FEATURE: persists the favorite toggle from the connection
    // list / card UI. Reading it back happens through the normal
    // getAllProfiles()/getProfileById() queries above — isFavorite is just
    // another column on RdpProfile — so favorites-first sorting and the
    // favorites-only filter (both applied at the repository/ViewModel layer)
    // always reflect the latest persisted state.
    @Query("UPDATE rdp_profiles SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: String, isFavorite: Boolean)

    // ── PIN-CONNECTION FEATURE ──────────────────────────────────────────────
    // Sets isPinned + pinnedOrder together in one write, since a freshly
    // pinned row always gets both at once (see MainViewModel.togglePin /
    // RdpProfileRepository.setPinned). Also used, one id at a time, by the
    // bulk-pin/unpin paths (see RdpProfileRepository.setPinnedBulk) — Room
    // has no simple portable "assign row_number() per id" bulk statement, and
    // a real-world pinned/selected set is small enough (a UI-driven
    // multi-select, not a bulk-data import) that looping suspend calls is
    // both simpler and safer than a fragile window-function query.
    @Query("UPDATE rdp_profiles SET isPinned = :isPinned, pinnedOrder = :pinnedOrder WHERE id = :id")
    suspend fun updatePinned(id: String, isPinned: Boolean, pinnedOrder: Long)

    // Drag-reorder within the pinned section only (see
    // RdpProfileRepository.reorderPinnedProfiles) — never touches isPinned
    // or the unpinned sortOrder column.
    @Query("UPDATE rdp_profiles SET pinnedOrder = :order WHERE id = :id")
    suspend fun updatePinnedOrder(id: String, order: Long)

    // Used to append newly-pinned connection(s) after every already-pinned
    // one instead of always inserting at position 0 — see
    // MainViewModel.togglePin / RdpProfileRepository.setPinnedBulk.
    @Query("SELECT MAX(pinnedOrder) FROM rdp_profiles WHERE isPinned = 1")
    suspend fun getMaxPinnedOrder(): Long?
}

/**
 * RD-WEB-FEED FEATURE: CRUD for saved feed subscriptions — see
 * [com.systemsgo.hex.data.model.WebFeedSubscription]'s doc comment. The
 * resources a feed actually publishes are never stored here (fetched live);
 * this only persists "how to reach and log into" each feed.
 */
@Dao
interface WebFeedSubscriptionDao {
    @Query("SELECT * FROM web_feed_subscriptions ORDER BY createdAt ASC")
    fun getAllSubscriptions(): Flow<List<com.systemsgo.hex.data.model.WebFeedSubscription>>

    @Query("SELECT * FROM web_feed_subscriptions WHERE id = :id")
    suspend fun getSubscriptionById(id: String): com.systemsgo.hex.data.model.WebFeedSubscription?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: com.systemsgo.hex.data.model.WebFeedSubscription)

    @Update
    suspend fun updateSubscription(subscription: com.systemsgo.hex.data.model.WebFeedSubscription)

    @Delete
    suspend fun deleteSubscription(subscription: com.systemsgo.hex.data.model.WebFeedSubscription)

    @Query("UPDATE web_feed_subscriptions SET lastRefreshed = :timestamp, lastError = :error WHERE id = :id")
    suspend fun updateRefreshResult(id: String, timestamp: Long, error: String)
}

/**
 * Folders (categories) that saved connections can be filed under.
 * See [ConnectionFolder] and [com.systemsgo.hex.data.model.RdpProfile.folderId].
 */
@Dao
interface ConnectionFolderDao {
    @Query("SELECT * FROM connection_folders ORDER BY sortOrder ASC, createdAt ASC")
    fun getAllFolders(): Flow<List<ConnectionFolder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: ConnectionFolder)

    @Update
    suspend fun updateFolder(folder: ConnectionFolder)

    @Delete
    suspend fun deleteFolder(folder: ConnectionFolder)

    // Un-files every connection that belonged to this folder so no
    // rdp_profiles row is ever left pointing at a folder id that no longer
    // exists. Called by ConnectionFolderRepository.deleteFolder() before the
    // folder row itself is deleted.
    @Query("UPDATE rdp_profiles SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolderFromProfiles(folderId: String)
}

/**
 * RESTCONF FEATURE (Part 3/4): backs the API Explorer's Saved
 * Requests/Collections/History (Favorites and Recent are just filtered
 * views over these same rows — see
 * [com.systemsgo.hex.data.repository.RestconfExplorerRepository]).
 */
@Dao
interface RestconfExplorerDao {
    @Query("SELECT * FROM restconf_saved_requests WHERE profileId = :profileId ORDER BY lastUsedAt DESC")
    fun getSavedRequests(profileId: String): Flow<List<com.systemsgo.hex.data.model.RestconfSavedRequest>>

    @Query("SELECT * FROM restconf_saved_requests WHERE profileId = :profileId AND isFavorite = 1 ORDER BY lastUsedAt DESC")
    fun getFavoriteRequests(profileId: String): Flow<List<com.systemsgo.hex.data.model.RestconfSavedRequest>>

    @Query("SELECT * FROM restconf_saved_requests WHERE profileId = :profileId AND (name LIKE '%' || :query || '%' OR path LIKE '%' || :query || '%')")
    suspend fun searchSavedRequests(profileId: String, query: String): List<com.systemsgo.hex.data.model.RestconfSavedRequest>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSavedRequest(request: com.systemsgo.hex.data.model.RestconfSavedRequest)

    @Delete
    suspend fun deleteSavedRequest(request: com.systemsgo.hex.data.model.RestconfSavedRequest)

    @Query("UPDATE restconf_saved_requests SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE restconf_saved_requests SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun touchLastUsed(id: String, timestamp: Long)

    @Query("SELECT * FROM restconf_collections WHERE profileId = :profileId ORDER BY createdAt ASC")
    fun getCollections(profileId: String): Flow<List<com.systemsgo.hex.data.model.RestconfCollection>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollection(collection: com.systemsgo.hex.data.model.RestconfCollection)

    @Delete
    suspend fun deleteCollection(collection: com.systemsgo.hex.data.model.RestconfCollection)

    // Un-files every request that belonged to this collection rather than cascade-deleting
    // them — a deleted collection shouldn't take the user's saved requests with it,
    // same reasoning as ConnectionFolderDao.clearFolderFromProfiles above.
    @Query("UPDATE restconf_saved_requests SET collectionId = NULL WHERE collectionId = :collectionId")
    suspend fun clearCollectionFromRequests(collectionId: String)

    @Query("SELECT * FROM restconf_history WHERE profileId = :profileId ORDER BY timestamp DESC LIMIT :limit")
    fun getHistory(profileId: String, limit: Int = 200): Flow<List<com.systemsgo.hex.data.model.RestconfHistoryEntry>>

    @Insert
    suspend fun insertHistoryEntry(entry: com.systemsgo.hex.data.model.RestconfHistoryEntry)

    // Keeps only the most recent [keep] rows per profile — called after every
    // insertHistoryEntry so the table never grows unbounded (see
    // RestconfHistoryEntry's doc comment on why this one IS trimmed, unlike ConnectionLog).
    @Query("""
        DELETE FROM restconf_history WHERE id NOT IN (
            SELECT id FROM restconf_history WHERE profileId = :profileId ORDER BY timestamp DESC LIMIT :keep
        ) AND profileId = :profileId
    """)
    suspend fun trimHistory(profileId: String, keep: Int = 200)

    @Query("DELETE FROM restconf_history WHERE profileId = :profileId")
    suspend fun clearHistory(profileId: String)

    // ── RESTCONF FEATURE (Part 5): Environments ──────────────────────────
    @Query("SELECT * FROM restconf_environments WHERE profileId = :profileId ORDER BY createdAt ASC")
    fun getEnvironments(profileId: String): Flow<List<com.systemsgo.hex.data.model.RestconfEnvironment>>

    @Query("SELECT * FROM restconf_environments WHERE profileId = :profileId AND isActive = 1 LIMIT 1")
    suspend fun getActiveEnvironment(profileId: String): com.systemsgo.hex.data.model.RestconfEnvironment?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEnvironment(environment: com.systemsgo.hex.data.model.RestconfEnvironment)

    @Delete
    suspend fun deleteEnvironment(environment: com.systemsgo.hex.data.model.RestconfEnvironment)

    // Called immediately before activating a different row so only one
    // environment per profile is ever active at once — see
    // RestconfExplorerRepository.setActiveEnvironment, which calls this and
    // activateEnvironment back-to-back.
    @Query("UPDATE restconf_environments SET isActive = 0 WHERE profileId = :profileId")
    suspend fun deactivateAllEnvironments(profileId: String)

    @Query("UPDATE restconf_environments SET isActive = 1 WHERE id = :id")
    suspend fun activateEnvironment(id: String)
}

@Database(
    entities = [
        RdpProfile::class,
        com.systemsgo.hex.data.model.ConnectionLog::class,
        ConnectionFolder::class,
        com.systemsgo.hex.data.model.WebFeedSubscription::class,
        com.systemsgo.hex.data.model.RestconfSavedRequest::class,
        com.systemsgo.hex.data.model.RestconfCollection::class,
        com.systemsgo.hex.data.model.RestconfHistoryEntry::class,
        com.systemsgo.hex.data.model.RestconfEnvironment::class,
    ],
    version = 64,   // Bumped from 49 to 52 for the NETCONF feature (MIGRATION_49_50 netconf* columns, MIGRATION_50_51 Call Home, MIGRATION_51_52 Call Home over TLS), then to 53 for the Guacamole feature (MIGRATION_52_53 guac* columns), then to 54 for Serial Console hardware flow control (MIGRATION_53_54 serialConsoleHardwareFlowControl), then to 55 for IPMI Kg/two-key login support (MIGRATION_54_55 ipmiKgKey), then to 56 for the Mosh feature (MIGRATION_55_56 mosh* columns), then to 57 for the Proxmox API feature (MIGRATION_56_57 proxmox* columns), then to 58 for the Modbus TCP feature (MIGRATION_57_58 modbus* columns), then to 59 for the VirtualBox VRDE feature (MIGRATION_58_59 vrde* columns), then to 60 for the VMware vSphere API feature (MIGRATION_59_60 vsphere* columns), then to 61 for the RTSP feature's previously-unmigrated rtsp* columns (MIGRATION_60_61 — see that migration's BUGFIX doc comment), then to 62 for the FTP/FTPS/WebDAV/SMB/NFS-standalone feature (MIGRATION_61_62 ftpSecurity/ftpPassiveMode/smbShare/smbDomain/webdavBaseUrl/nfsExportPath/nfsUid/nfsGid/nfsMountdPort columns), then to 63 for the AMT/vPro CIRA setup UI (MIGRATION_62_63 ciraEnabled/ciraRelayHost/ciraRelayPort/ciraRelayUsername/ciraRelayPassword/ciraDeviceId columns — see AMT_VPRO_ROADMAP.md Phase 6), then to 64 for the CIRA relay's app-facing wss:// support, Phase 6 Part 3 (MIGRATION_63_64 ciraRelayUseTls column, now read by CiraRelayTransport.open — see that class's doc comment and AMT_VPRO_ROADMAP.md).
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class SystemsGoDatabase : RoomDatabase() {
    abstract fun rdpProfileDao(): RdpProfileDao
    abstract fun connectionLogDao(): ConnectionLogDao
    abstract fun connectionFolderDao(): ConnectionFolderDao
    abstract fun webFeedSubscriptionDao(): WebFeedSubscriptionDao
    abstract fun restconfExplorerDao(): RestconfExplorerDao

    companion object {
        const val DATABASE_NAME = "systemsgo_database"
    }
}
