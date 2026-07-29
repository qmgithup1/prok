package com.systemsgo.hex.data.repository

import com.systemsgo.hex.data.db.RestconfExplorerDao
import com.systemsgo.hex.data.model.RestconfCollection
import com.systemsgo.hex.data.model.RestconfEnvironment
import com.systemsgo.hex.data.model.RestconfHistoryEntry
import com.systemsgo.hex.data.model.RestconfSavedRequest
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RESTCONF FEATURE (Part 3/4): API Explorer storage — Saved Requests,
 * Collections, and History, all scoped per-profile. "Favorites" and
 * "Recent" (both named as separate requirements) are deliberately not
 * separate tables: Favorites is [getFavoriteRequests] (a filtered view over
 * saved requests) and Recent is [getHistory]'s first N rows — the same data
 * the History tab shows, just truncated in the UI, since a "recently sent"
 * request and a "history entry" are the same fact.
 */
@Singleton
class RestconfExplorerRepository @Inject constructor(
    private val dao: RestconfExplorerDao,
) {
    fun getSavedRequests(profileId: String): Flow<List<RestconfSavedRequest>> = dao.getSavedRequests(profileId)

    fun getFavoriteRequests(profileId: String): Flow<List<RestconfSavedRequest>> = dao.getFavoriteRequests(profileId)

    suspend fun searchSavedRequests(profileId: String, query: String): List<RestconfSavedRequest> =
        dao.searchSavedRequests(profileId, query)

    suspend fun saveRequest(request: RestconfSavedRequest) = dao.upsertSavedRequest(request)

    suspend fun deleteRequest(request: RestconfSavedRequest) = dao.deleteSavedRequest(request)

    suspend fun toggleFavorite(id: String, isFavorite: Boolean) = dao.setFavorite(id, isFavorite)

    suspend fun markUsed(id: String) = dao.touchLastUsed(id, System.currentTimeMillis())

    fun getCollections(profileId: String): Flow<List<RestconfCollection>> = dao.getCollections(profileId)

    suspend fun createCollection(profileId: String, name: String): RestconfCollection {
        val collection = RestconfCollection(profileId = profileId, name = name.trim())
        dao.upsertCollection(collection)
        return collection
    }

    suspend fun deleteCollection(collection: RestconfCollection) {
        dao.clearCollectionFromRequests(collection.id)
        dao.deleteCollection(collection)
    }

    fun getHistory(profileId: String, limit: Int = 200): Flow<List<RestconfHistoryEntry>> = dao.getHistory(profileId, limit)

    /** Called after every request send — records the entry, then trims the profile's history back down to [keep] rows. */
    suspend fun recordHistory(entry: RestconfHistoryEntry, keep: Int = 200) {
        dao.insertHistoryEntry(entry)
        dao.trimHistory(entry.profileId, keep)
    }

    suspend fun clearHistory(profileId: String) = dao.clearHistory(profileId)

    // ── RESTCONF FEATURE (Part 5): Environment Variables ─────────────────

    fun getEnvironments(profileId: String): Flow<List<RestconfEnvironment>> = dao.getEnvironments(profileId)

    suspend fun createEnvironment(profileId: String, name: String): RestconfEnvironment {
        val env = RestconfEnvironment(profileId = profileId, name = name.trim())
        dao.upsertEnvironment(env)
        return env
    }

    suspend fun saveEnvironment(environment: RestconfEnvironment) = dao.upsertEnvironment(environment)

    suspend fun deleteEnvironment(environment: RestconfEnvironment) = dao.deleteEnvironment(environment)

    /** Activates [id] for [profileId] and deactivates every other environment on that profile in the same breath — Room has no built-in "at most one active row" constraint, so this is enforced here rather than trusted to callers. Passing `id = null` just clears the active environment (deactivates all, activates nothing). */
    suspend fun setActiveEnvironment(profileId: String, id: String?) {
        dao.deactivateAllEnvironments(profileId)
        if (id != null) dao.activateEnvironment(id)
    }

    suspend fun getActiveEnvironment(profileId: String): RestconfEnvironment? = dao.getActiveEnvironment(profileId)

    /** The active environment's variables as a plain map (empty if none is active) — what [com.systemsgo.hex.restconf.protocol.RestconfTemplateEngine.substitute] expects for its `environment` parameter. */
    suspend fun resolveActiveVariables(profileId: String): Map<String, String> =
        getActiveEnvironment(profileId)?.let { parseVariableLines(it.variables) } ?: emptyMap()
}

/** "key: value" per line — same encoding as [RestconfSavedRequest.headers]/`queryParams`. File-level (not private to the class above) so the Environments UI can reuse it for a live variable-count/preview without going through a suspend repository call. */
fun parseVariableLines(raw: String): Map<String, String> =
    raw.lines().mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }.toMap()
