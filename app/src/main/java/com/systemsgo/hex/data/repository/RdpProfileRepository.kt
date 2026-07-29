package com.systemsgo.hex.data.repository

import android.content.Context
import com.systemsgo.hex.data.db.RdpProfileDao
import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.security.CryptoHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RdpProfileRepository @Inject constructor(
    private val dao: RdpProfileDao,
    // QUICK-SETTINGS-TILE FEATURE (Part 1/2): only used to poke
    // QsTileUpdater.requestUpdate() — see updateConnectionState() below.
    @ApplicationContext private val context: Context,
) {
    // ── helpers ──────────────────────────────────────────────────────────────

    // ROOT-HARDENING FIX: each field's AAD is "<profileId>:<fieldName>". Without
    // this, a root-privileged attacker editing the SQLCipher file directly could
    // copy the ciphertext from one profile's `password` column into another
    // profile's `password` column (or into a different field entirely, like
    // `sshTunnelPassword`), and it would decrypt as if it were legitimate —
    // GCM's tag only proves the bytes weren't altered, not that they belong in
    // that row/column. Binding the profile id + field name makes that kind of
    // splice fail decryption instead of silently handing the attacker (or a
    // corrupted app state) someone else's credential.
    private fun aad(profileId: String, field: String) = "$profileId:$field"

    @Suppress("DEPRECATION")
    private fun RdpProfile.withEncryptedSecrets(): RdpProfile = copy(
        password               = CryptoHelper.encrypt(password, aad(id, "password")),
        gatewayPassword        = CryptoHelper.encrypt(gatewayPassword, aad(id, "gatewayPassword")),
        sshPrivateKey          = CryptoHelper.encrypt(sshPrivateKey, aad(id, "sshPrivateKey")),
        sshPrivateKeyPassphrase = CryptoHelper.encrypt(sshPrivateKeyPassphrase, aad(id, "sshPrivateKeyPassphrase")),
        // FIX S1: SSH Tunnel credentials were stored in plaintext — encrypt them too
        sshTunnelPassword             = CryptoHelper.encrypt(sshTunnelPassword, aad(id, "sshTunnelPassword")),
        sshTunnelPrivateKey           = CryptoHelper.encrypt(sshTunnelPrivateKey, aad(id, "sshTunnelPrivateKey")),
        sshTunnelPrivateKeyPassphrase = CryptoHelper.encrypt(sshTunnelPrivateKeyPassphrase, aad(id, "sshTunnelPrivateKeyPassphrase")),
        // SSH-PROXYJUMP-CHAIN FEATURE: same treatment, one hop at a time.
        // Each hop's own stable [SshJumpHop.id] is folded into the AAD
        // (rather than its position in the list) so a chain can be
        // reordered/have hops inserted or removed without invalidating the
        // AAD binding of hops that didn't change — and, per the same
        // ROOT-HARDENING reasoning as the field-level AAD above, so a
        // root-privileged attacker can't splice one hop's encrypted secret
        // into another hop (of this profile or a different one) and have it
        // decrypt successfully.
        sshTunnelHops = sshTunnelHops.map { hop ->
            hop.copy(
                password = CryptoHelper.encrypt(hop.password, aad(id, "sshTunnelHops:${hop.id}:password")),
                privateKey = CryptoHelper.encrypt(hop.privateKey, aad(id, "sshTunnelHops:${hop.id}:privateKey")),
                privateKeyPassphrase = CryptoHelper.encrypt(
                    hop.privateKeyPassphrase, aad(id, "sshTunnelHops:${hop.id}:privateKeyPassphrase")
                ),
            )
        },
    )

    @Suppress("DEPRECATION")
    private fun RdpProfile.withDecryptedSecrets(): RdpProfile = try {
        copy(
            password               = CryptoHelper.decrypt(password, aad(id, "password")),
            gatewayPassword        = CryptoHelper.decrypt(gatewayPassword, aad(id, "gatewayPassword")),
            sshPrivateKey          = CryptoHelper.decrypt(sshPrivateKey, aad(id, "sshPrivateKey")),
            sshPrivateKeyPassphrase = CryptoHelper.decrypt(sshPrivateKeyPassphrase, aad(id, "sshPrivateKeyPassphrase")),
            // FIX S1: Decrypt SSH Tunnel credentials on read
            sshTunnelPassword             = CryptoHelper.decrypt(sshTunnelPassword, aad(id, "sshTunnelPassword")),
            sshTunnelPrivateKey           = CryptoHelper.decrypt(sshTunnelPrivateKey, aad(id, "sshTunnelPrivateKey")),
            sshTunnelPrivateKeyPassphrase = CryptoHelper.decrypt(sshTunnelPrivateKeyPassphrase, aad(id, "sshTunnelPrivateKeyPassphrase")),
            // SSH-PROXYJUMP-CHAIN FEATURE: decrypt each hop's secrets on read.
            // effectiveSshTunnelHops (RdpProfile) reads this already-decrypted
            // list, so no separate decryption step is needed for the legacy
            // single-hop fallback it also computes from the (already
            // decrypted, right above) legacy fields.
            sshTunnelHops = sshTunnelHops.map { hop ->
                hop.copy(
                    password = CryptoHelper.decrypt(hop.password, aad(id, "sshTunnelHops:${hop.id}:password")),
                    privateKey = CryptoHelper.decrypt(hop.privateKey, aad(id, "sshTunnelHops:${hop.id}:privateKey")),
                    privateKeyPassphrase = CryptoHelper.decrypt(
                        hop.privateKeyPassphrase, aad(id, "sshTunnelHops:${hop.id}:privateKeyPassphrase")
                    ),
                )
            },
        )
    } catch (e: SecurityException) {
        // BUG-DECRYPT FIX: CryptoHelper.decrypt() now throws SecurityException when the
        // Keystore key is lost (after reinstall / backup restore / factory reset) rather
        // than returning "" silently. Re-throw with context so the ViewModel can catch it
        // and show a "Please re-enter your password" prompt instead of a generic auth error.
        android.util.Log.e("RdpProfileRepository", "Failed to decrypt secrets for profile ${this.id}", e)
        // MED-1 FIX: Profile names may contain sensitive infrastructure details (client name,
        // server role, company name). Logging or surfacing them in exception messages leaks
        // that information to logcat and crash-reporting tools. Use the opaque UUID instead.
        throw SecurityException("Credentials for profile ID ${this.id} could not be decrypted. " +
            "Please edit the profile and re-enter your password.", e)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun getAllProfiles(): Flow<List<RdpProfile>> =
        dao.getAllProfiles().map { list ->
            // BUG-ALLPROFILES FIX: withDecryptedSecrets() throws SecurityException when the
            // Android Keystore key is lost (after reinstall / backup restore / factory reset).
            // Previously this exception escaped the Flow into viewModelScope.launch with no
            // handler, crashing the app whenever the home screen opened.
            // Fix: catch per-profile and return a sanitised copy (empty password) instead of
            // crashing. The profile remains visible in the list with a broken-lock indicator
            // so the user can re-enter credentials via the edit dialog.
            list.mapNotNull { profile ->
                try {
                    profile.withDecryptedSecrets()
                } catch (e: SecurityException) {
                    android.util.Log.e("RdpProfileRepository",
                        "getAllProfiles: could not decrypt profile ID ${profile.id}", e)
                    // Return a sanitised copy so the card is still shown; empty secrets prevent
                    // an accidental connect attempt with garbled credentials.
                    @Suppress("DEPRECATION")
                    profile.copy(
                        password                      = "",
                        gatewayPassword               = "",
                        sshPrivateKey                 = "",
                        sshPrivateKeyPassphrase       = "",
                        sshTunnelPassword             = "",
                        sshTunnelPrivateKey           = "",
                        sshTunnelPrivateKeyPassphrase = "",
                        // SSH-PROXYJUMP-CHAIN FEATURE: same sanitization as the
                        // legacy single-hop fields above — clear every hop's
                        // secrets rather than surfacing garbled ciphertext.
                        sshTunnelHops = profile.sshTunnelHops.map { hop ->
                            hop.copy(password = "", privateKey = "", privateKeyPassphrase = "")
                        },
                    )
                }
            }
        }

    suspend fun getProfileById(id: String): RdpProfile? {
        // BUG-GETBYID FIX: getAllProfiles() already catches SecurityException per-profile
        // (Keystore key lost after reinstall / backup restore), but getProfileById() let
        // the exception propagate uncaught. RdpSessionActivity calls this before every
        // connection attempt, so a lost Keystore key crashed the app on the session
        // screen with no user-visible explanation.
        // Fix: mirror the same try/catch pattern from getAllProfiles() — return null so
        // the caller's existing "profile == null → show error_profile_not_found" path
        // fires, which is a cleaner UX than a raw crash. The SecurityException is logged
        // so developers can diagnose the Keystore state from Logcat.
        return try {
            dao.getProfileById(id)?.withDecryptedSecrets()
        } catch (e: SecurityException) {
            android.util.Log.e("RdpProfileRepository",
                "getProfileById: could not decrypt profile id=$id (Keystore key lost?)", e)
            null
        }
    }

    suspend fun saveProfile(profile: RdpProfile) =
        dao.insertProfile(profile.withEncryptedSecrets())

    suspend fun updateProfile(profile: RdpProfile) =
        dao.updateProfile(profile.withEncryptedSecrets())

    suspend fun deleteProfile(profile: RdpProfile) = dao.deleteProfile(profile)

    suspend fun updateLastConnected(id: String) =
        dao.updateLastConnected(id, System.currentTimeMillis())

    // BUG-9 FIX: the DAO method was renamed to updateScreenshotFilename; the old
    // name (updateScreenshot / updateScreenshotPath) no longer exists and caused
    // an "Unresolved reference" compile error. Parameter renamed path → filename
    // to match the DAO signature and the DB column (lastScreenshotFilename).
    suspend fun updateScreenshot(id: String, filename: String) =
        dao.updateScreenshotFilename(id, filename)

    // QUICK-SETTINGS-TILE FEATURE (Part 1/2): a connect/disconnect anywhere
    // in the app (including a session started from the tile itself) needs
    // to be reflected on the tile's STATE_ACTIVE/STATE_INACTIVE the next
    // time it's visible, even though onStartListening's own Flow collection
    // already picks up the underlying DB row change on its own while the
    // panel is open — this just wakes up a *currently invisible* tile via
    // requestListeningState so it isn't showing stale state next time the
    // panel opens. Harmless no-op if the tile was never added to any panel.
    suspend fun updateConnectionState(id: String, connected: Boolean) {
        dao.updateConnectionState(id, connected)
        com.systemsgo.hex.tile.QsTileUpdater.requestUpdate(context)
        // HOME-SCREEN-WIDGET FEATURE (Part 2/2): same "wake up anything
        // currently invisible" reasoning as the QsTileUpdater poke above —
        // a SINGLE_CONNECTION widget instance's status dot
        // (WidgetViewBinder.bind's isConnected branch) and every row's dot
        // in a CONNECTION_LIST instance both need to flip the moment a
        // session connects/disconnects, not just whenever the widget next
        // happens to redraw for an unrelated reason. This is not redundant
        // with MainViewModel.observeData()'s own getAllProfiles()-driven
        // widget refresh: a session can connect/disconnect from
        // RdpSessionActivity/RdpSessionService while MainViewModel (and its
        // ViewModelScope) isn't even alive, e.g. the app backgrounded after
        // launching straight from this same widget. WidgetUpdater.requestUpdateAll
        // is a cheap no-op if the user has never placed the widget.
        com.systemsgo.hex.widget.WidgetUpdater.requestUpdateAll(context)
    }

    // UX-03: Persist new sort order after drag-to-reorder
    suspend fun reorderProfiles(profiles: List<RdpProfile>) {
        profiles.forEachIndexed { index, profile ->
            dao.updateSortOrder(profile.id, index)
        }
    }

    // FIX B3: تُستدعى عند بدء التطبيق لإزالة علامات isConnected المتبقية من جلسات سابقة
    suspend fun resetAllConnectionStates() = dao.resetAllConnectionStates()

    // FAVORITES FEATURE: toggles/sets the favorite flag for one profile. This
    // is a plain, unencrypted boolean column (like isConnected/sortOrder
    // above), so it goes straight to the DAO with no secrets to encrypt.
    suspend fun setFavorite(id: String, isFavorite: Boolean) =
        dao.updateFavorite(id, isFavorite)

    // ── PIN-CONNECTION FEATURE ──────────────────────────────────────────────
    // Also plain unencrypted columns (like isFavorite above), so these go
    // straight to the DAO with no secrets to encrypt.

    /** Current highest pinnedOrder among pinned rows, or null if none are pinned. */
    suspend fun getMaxPinnedOrder(): Long? = dao.getMaxPinnedOrder()

    /**
     * Pins [id] at [pinnedOrder] (appended after every currently-pinned
     * connection — see MainViewModel.togglePin, which computes that value),
     * or unpins it when [isPinned] is false (pinnedOrder is then ignored by
     * every reader, so any value is fine — MainViewModel always passes 0L).
     */
    suspend fun setPinned(id: String, isPinned: Boolean, pinnedOrder: Long = 0L) =
        dao.updatePinned(id, isPinned, pinnedOrder)

    /**
     * BULK-PIN FEATURE: pins/unpins every id in [ids] in one call (see
     * MainViewModel.bulkPinSelected/bulkUnpinSelected). When pinning, ids
     * are appended after the current pinned set in the order given, so a
     * multi-selected batch keeps a stable, predictable relative order
     * instead of all landing on the same pinnedOrder value.
     */
    suspend fun setPinnedBulk(ids: List<String>, isPinned: Boolean) {
        if (ids.isEmpty()) return
        if (!isPinned) {
            ids.forEach { dao.updatePinned(it, false, 0L) }
            return
        }
        var nextOrder = (dao.getMaxPinnedOrder() ?: -1L) + 1
        ids.forEach { id ->
            dao.updatePinned(id, true, nextOrder)
            nextOrder++
        }
    }

    // UX-03-style drag-to-reorder, scoped to the pinned section only — never
    // touches sortOrder (which keeps driving the unpinned list exactly as
    // before) or isPinned itself.
    suspend fun reorderPinnedProfiles(pinnedProfiles: List<RdpProfile>) {
        pinnedProfiles.forEachIndexed { index, profile ->
            dao.updatePinnedOrder(profile.id, index.toLong())
        }
    }
}
