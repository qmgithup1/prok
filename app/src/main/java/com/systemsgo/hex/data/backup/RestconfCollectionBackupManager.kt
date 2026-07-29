package com.systemsgo.hex.data.backup

import android.content.Context
import android.net.Uri
import com.systemsgo.hex.BuildConfig
import com.systemsgo.hex.data.model.RestconfEnvironment
import com.systemsgo.hex.data.model.RestconfSavedRequest
import com.systemsgo.hex.data.repository.RestconfExplorerRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RESTCONF FEATURE (Part 5): Import/Export — the API Explorer's "Export
 * collection" / "Import collection" buttons (see [RestconfApiExplorerSheet]).
 * Scoped to one profile at a time, same as every other table
 * [RestconfExplorerRepository] backs: exporting bundles that profile's
 * Collections, Saved Requests, and Environments into one portable JSON file;
 * importing merges a file back in, skipping anything that already exists by
 * name so re-running an import is always safe — same dedupe-on-re-import
 * shape as [ConnectionBackupManager], just without the AES-256-GCM
 * encryption step (this isn't a device credentials backup, and RESTCONF
 * saved-request headers/bodies are exactly the kind of thing a user *wants*
 * to hand to a colleague or paste into version control unencrypted, the same
 * way a Postman collection export is plain JSON).
 *
 * SECURITY NOTE surfaced in the UI (not just here): a saved request's
 * headers/body can legitimately contain a bearer token or API key someone
 * typed into the Auth tab. Export is plain-text JSON — the Activity's export
 * confirmation should say so before the file picker opens.
 *
 * Import also accepts a plain Postman v2.1 collection export
 * (`{"info": {...}, "item": [...]}`) — see [importPostmanCollection] — since
 * "someone shared a Postman collection for this device's API" is at least as
 * common a starting point as an export from this app itself. Only requests
 * are recognized from a Postman file (no environments/folders-as-collections
 * mapping attempted); every imported request lands in the target profile's
 * uncategorized (collectionId = null) bucket.
 */
@Singleton
class RestconfCollectionBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RestconfExplorerRepository,
) {
    private val gson: Gson = GsonBuilder().create()

    data class ExportResult(val requestCount: Int, val collectionCount: Int, val environmentCount: Int)

    data class ImportResult(
        val importedRequests: Int,
        val skippedRequests: Int,
        val importedCollections: Int,
        val importedEnvironments: Int,
    )

    class InvalidFileException(message: String) : Exception(message)

    // ── On-disk shape (own format) ────────────────────────────────────────
    private data class ExportedRequest(
        val name: String,
        val collectionName: String?,
        val method: String,
        val path: String,
        val queryParams: String,
        val headers: String,
        val body: String,
        val dataFormat: String,
        val isFavorite: Boolean,
    )

    private data class ExportedEnvironment(
        val name: String,
        val variables: String,
        val isActive: Boolean,
    )

    private data class ExportPayload(
        val formatVersion: Int = FORMAT_VERSION,
        val exportedAt: Long = System.currentTimeMillis(),
        val appVersion: String = "",
        val collections: List<String> = emptyList(), // collection names only — ids are meaningless across profiles/installs
        val requests: List<ExportedRequest> = emptyList(),
        val environments: List<ExportedEnvironment> = emptyList(),
    ) {
        companion object {
            const val FORMAT_VERSION = 1
        }
    }

    suspend fun exportTo(uri: Uri, profileId: String): ExportResult = withContext(Dispatchers.IO) {
        val collections = repository.getCollections(profileId).first()
        val requests = repository.getSavedRequests(profileId).first()
        val environments = repository.getEnvironments(profileId).first()
        val collectionNameById = collections.associate { it.id to it.name }

        val payload = ExportPayload(
            appVersion = BuildConfig.VERSION_NAME,
            collections = collections.map { it.name },
            requests = requests.map { req ->
                ExportedRequest(
                    name = req.name,
                    collectionName = req.collectionId?.let { collectionNameById[it] },
                    method = req.method, path = req.path,
                    queryParams = req.queryParams, headers = req.headers, body = req.body,
                    dataFormat = req.dataFormat, isFavorite = req.isFavorite,
                )
            },
            environments = environments.map { env ->
                ExportedEnvironment(name = env.name, variables = env.variables, isActive = env.isActive)
            },
        )

        val json = gson.toJson(payload)
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Could not open the destination file for writing.")
        stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

        ExportResult(requestCount = requests.size, collectionCount = collections.size, environmentCount = environments.size)
    }

    /** Reads [uri], detects whether it's this app's own export format or a Postman v2.1 collection, and merges its contents into [profileId]. */
    suspend fun importFrom(uri: Uri, profileId: String): ImportResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8)
            ?: throw IOException("Could not open the file for reading.")

        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw InvalidFileException("This file isn't valid JSON.")
        }

        if (root.has("info") && root.has("item")) {
            importPostmanCollection(root, profileId)
        } else if (root.has("requests") || root.has("formatVersion")) {
            importOwnFormat(text, profileId)
        } else {
            throw InvalidFileException("This doesn't look like a RESTCONF collection export or a Postman collection.")
        }
    }

    private suspend fun importOwnFormat(text: String, profileId: String): ImportResult {
        val payload = try {
            gson.fromJson(text, ExportPayload::class.java) ?: throw InvalidFileException("The file is empty or unreadable.")
        } catch (e: InvalidFileException) {
            throw e
        } catch (e: Exception) {
            throw InvalidFileException("The file could not be parsed.")
        }

        val existingCollections = repository.getCollections(profileId).first().toMutableList()
        val collectionIdByName = existingCollections.associateTo(mutableMapOf()) { it.name.trim().lowercase() to it.id }
        var importedCollections = 0
        for (name in payload.collections) {
            val key = name.trim().lowercase()
            if (key.isBlank() || collectionIdByName.containsKey(key)) continue
            val created = repository.createCollection(profileId, name)
            collectionIdByName[key] = created.id
            importedCollections++
        }

        val existingRequests = repository.getSavedRequests(profileId).first()
        val existingRequestKeys = existingRequests.mapTo(mutableSetOf()) { requestDedupeKey(it.name, it.method, it.path) }
        var importedRequests = 0
        var skippedRequests = 0
        for (req in payload.requests) {
            val key = requestDedupeKey(req.name, req.method, req.path)
            if (key in existingRequestKeys) {
                skippedRequests++
                continue
            }
            repository.saveRequest(
                RestconfSavedRequest(
                    profileId = profileId,
                    collectionId = req.collectionName?.let { collectionIdByName[it.trim().lowercase()] },
                    name = req.name, method = req.method, path = req.path,
                    queryParams = req.queryParams, headers = req.headers, body = req.body,
                    dataFormat = req.dataFormat, isFavorite = req.isFavorite,
                )
            )
            existingRequestKeys.add(key)
            importedRequests++
        }

        val existingEnvironments = repository.getEnvironments(profileId).first()
        val existingEnvNames = existingEnvironments.mapTo(mutableSetOf()) { it.name.trim().lowercase() }
        var importedEnvironments = 0
        for (env in payload.environments) {
            if (env.name.trim().lowercase() in existingEnvNames) continue
            repository.saveEnvironment(
                RestconfEnvironment(profileId = profileId, name = env.name, variables = env.variables, isActive = false)
            )
            existingEnvNames.add(env.name.trim().lowercase())
            importedEnvironments++
        }

        return ImportResult(importedRequests, skippedRequests, importedCollections, importedEnvironments)
    }

    /**
     * Best-effort Postman v2.1 collection import: flattens `item` (one level
     * of nested folders is walked, deeper nesting is flattened too — Postman
     * folders are themselves just `item` arrays with a `name` and no
     * `request`, so recursing is enough) into flat [RestconfSavedRequest]
     * rows. `url.raw` is preferred when present; otherwise `url.path` is
     * joined with `/`. Headers come from the `header` array
     * (`{"key":..., "value":...}`); body from `body.raw` when
     * `body.mode == "raw"` (form-data/urlencoded/binary Postman bodies have
     * no RESTCONF equivalent and are skipped, leaving an empty body rather
     * than guessing).
     */
    private suspend fun importPostmanCollection(root: JSONObject, profileId: String): ImportResult {
        val flat = mutableListOf<ExportedRequest>()
        fun walk(items: JSONArray) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val nested = item.optJSONArray("item")
                if (nested != null) {
                    walk(nested)
                    continue
                }
                val req = item.optJSONObject("request") ?: continue
                val name = item.optString("name", "Imported request")
                val method = req.optString("method", "GET").uppercase()

                val urlField = req.opt("url")
                val path = when (urlField) {
                    is JSONObject -> urlField.optString("raw", "").ifBlank {
                        val segments = urlField.optJSONArray("path")
                        if (segments != null) "/" + (0 until segments.length()).joinToString("/") { segments.optString(it) } else ""
                    }
                    is String -> urlField
                    else -> ""
                }

                val headerLines = StringBuilder()
                req.optJSONArray("header")?.let { headers ->
                    for (h in 0 until headers.length()) {
                        val header = headers.optJSONObject(h) ?: continue
                        if (header.optBoolean("disabled", false)) continue
                        headerLines.append(header.optString("key")).append(": ").append(header.optString("value")).append("\n")
                    }
                }

                val bodyObj = req.optJSONObject("body")
                val body = if (bodyObj?.optString("mode") == "raw") bodyObj.optString("raw", "") else ""

                flat += ExportedRequest(
                    name = name, collectionName = null, method = method, path = path,
                    queryParams = "", headers = headerLines.toString().trim(), body = body,
                    dataFormat = if (body.trimStart().startsWith("<")) "XML" else "JSON", isFavorite = false,
                )
            }
        }
        walk(root.optJSONArray("item") ?: JSONArray())

        val existingRequests = repository.getSavedRequests(profileId).first()
        val existingKeys = existingRequests.mapTo(mutableSetOf()) { requestDedupeKey(it.name, it.method, it.path) }
        var imported = 0
        var skipped = 0
        for (req in flat) {
            val key = requestDedupeKey(req.name, req.method, req.path)
            if (key in existingKeys) {
                skipped++
                continue
            }
            repository.saveRequest(
                RestconfSavedRequest(
                    profileId = profileId, collectionId = null,
                    name = req.name, method = req.method, path = req.path,
                    queryParams = req.queryParams, headers = req.headers, body = req.body,
                    dataFormat = req.dataFormat, isFavorite = false,
                )
            )
            existingKeys.add(key)
            imported++
        }

        return ImportResult(imported, skipped, importedCollections = 0, importedEnvironments = 0)
    }

    private fun requestDedupeKey(name: String, method: String, path: String): String =
        "${name.trim().lowercase()}|${method.trim().uppercase()}|${path.trim()}"
}
