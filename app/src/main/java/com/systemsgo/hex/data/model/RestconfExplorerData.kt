package com.systemsgo.hex.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * RESTCONF FEATURE (Part 3/4): a named, reusable request — the "Saved
 * Requests" half of the API Explorer (RESTCONF requirements: Favorites,
 * Recent, Saved requests, Collections). Scoped to one [RdpProfile] (a saved
 * request against one RESTCONF server rarely makes sense replayed against
 * another — different YANG modules, different auth), same per-profile
 * scoping [WebFeedSubscription] uses for its resources.
 *
 * `queryParams`/`headers` use the same "One-per-line, name: value" delimited
 * string encoding as [RdpProfile.restconfCustomHeaders] — avoids adding a
 * JSON TypeConverter for what's always a small, flat map.
 */
// BUGFIX (schema/entity mismatch): MIGRATION_46_47 creates
// index_restconf_saved_requests_profileId via raw SQL, but this @Entity
// never declared it — Room's schema validator compares the entity's
// annotated indices against what's actually on disk, so an index that
// exists in the real table but isn't declared here is a mismatch. Declaring
// it explicitly keeps the entity and the migration in sync, exactly like
// every other index-bearing entity in this codebase already does.
@Entity(tableName = "restconf_saved_requests", indices = [Index("profileId")])
data class RestconfSavedRequest(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    val collectionId: String? = null,
    val name: String,
    val method: String, // RestconfMethod.name
    val path: String,
    val queryParams: String = "", // "key: value" per line
    val headers: String = "",     // "key: value" per line
    val body: String = "",
    val dataFormat: String = RestconfDataFormat.JSON.name,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
)

/** RESTCONF FEATURE (Part 3/4): a named folder of [RestconfSavedRequest]s, scoped per-profile like the requests themselves. */
// BUGFIX (schema/entity mismatch): see RestconfSavedRequest's indices comment
// above — MIGRATION_46_47 also creates index_restconf_collections_profileId.
@Entity(tableName = "restconf_collections", indices = [Index("profileId")])
data class RestconfCollection(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * RESTCONF FEATURE (Part 3/4): one executed request, kept for the API
 * Explorer's "Recent"/"History" list. Capped per-profile by
 * [com.systemsgo.hex.data.repository.RestconfExplorerRepository.trimHistory]
 * (default 200 rows) — this is a lightweight replay/reference log, not an
 * audit trail, so unbounded growth isn't warranted the way
 * [ConnectionLog] (a real connection audit record) is kept unbounded.
 */
// BUGFIX (schema/entity mismatch): see RestconfSavedRequest's indices comment
// above — MIGRATION_46_47 also creates index_restconf_history_profileId.
@Entity(tableName = "restconf_history", indices = [Index("profileId")])
data class RestconfHistoryEntry(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    val method: String,
    val path: String,
    val statusCode: Int,
    val elapsedMillis: Long,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * RESTCONF FEATURE (Part 5): a named set of `{{name}}` values — the
 * "Environments" half of Postman-parity (Environment Variables). Scoped
 * per-profile like everything else in this file; at most one row per
 * profile has [isActive] = true at a time (enforced by
 * [com.systemsgo.hex.data.repository.RestconfExplorerRepository.setActiveEnvironment],
 * which clears every other row for the profile in the same call rather than
 * relying on a DB-level constraint, since Room has no easy "at most one true"
 * check).
 *
 * `variables` reuses the exact same "One-per-line, name: value" encoding as
 * [RestconfSavedRequest.headers]/`queryParams` — same reasoning: always a
 * small, flat map, not worth a JSON TypeConverter or a child table. Values
 * here are resolved by [com.systemsgo.hex.restconf.protocol.RestconfTemplateEngine]
 * exactly like a template-fill value, just supplied automatically from the
 * active environment instead of prompted for — see
 * [RestconfTemplateEngine.substitute]'s `environment` parameter.
 */
// BUGFIX (schema/entity mismatch): MIGRATION_47_48 creates
// index_restconf_environments_profileId via raw SQL; see
// RestconfSavedRequest's indices comment above for the same fix.
@Entity(tableName = "restconf_environments", indices = [Index("profileId")])
data class RestconfEnvironment(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    val name: String,
    val variables: String = "", // "key: value" per line
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
