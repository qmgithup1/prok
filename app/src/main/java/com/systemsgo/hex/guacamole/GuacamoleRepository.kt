package com.systemsgo.hex.guacamole

import android.content.Context
import com.systemsgo.hex.security.openEncryptedPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/** Everything needed to open a tunnel once a connection has been picked — see [GuacamoleTunnelClient.GuacamoleTunnelConfig]. */
data class GuacamoleSession(
    val authToken: String,
    val dataSource: String,
    val username: String,
)

/**
 * GUACAMOLE-PROTOCOL FEATURE (Part 1/N, persistence added Part 4/N).
 *
 * Thin session-state wrapper around [GuacamoleAuthClient]. Holds the
 * *current* session in memory ([session]) for
 * [listConnections]/[listConnectionGroups] to use without every caller
 * re-threading a raw token by hand, and — when constructed with an
 * [appContext] — can additionally persist the last session to
 * [com.systemsgo.hex.security.openEncryptedPrefs] (same encrypted-prefs
 * helper every other protocol's TOFU-fingerprint storage already uses) so
 * a later [tryRestoreSession] can skip a fresh REST login. [appContext] is
 * optional (not every caller — e.g. [com.systemsgo.hex.ui.components.GuacamoleConnectionPickerDialog]'s
 * throwaway repo instance — needs or wants persistence) rather than this
 * class always requiring one.
 *
 * Session TOKENS are what's persisted here, never the password — the
 * password itself already gets the app's existing at-rest protection via
 * [com.systemsgo.hex.data.model.RdpProfile.password] living in the Room
 * database, which [com.systemsgo.hex.data.db.DatabaseEncryptionMigrator]
 * encrypts as a whole (the same protection RDP/VNC/SSH passwords already
 * get) — a second, separate encrypted store for the password itself would
 * be redundant, not more secure.
 *
 * Deliberately NOT a Hilt `@Singleton`: a Guacamole "session" is scoped to
 * one server+account, and this app can have many Guacamole profiles
 * pointed at different servers/accounts — a single global instance would
 * have to juggle multiple concurrent sessions itself. One
 * [GuacamoleRepository] per login attempt (as
 * [com.systemsgo.hex.ui.screens.RdpSessionActivity.resolveGuacamoleSessionOrAbort]
 * and the connection picker both already do) keeps that scoping trivial
 * instead of reinventing it inside a shared singleton.
 */
class GuacamoleRepository(
    private val serverConfig: GuacamoleServerConfig,
    private val appContext: Context? = null,
) {
    private val authClient = GuacamoleAuthClient(serverConfig, appContext = appContext)

    private val _session = MutableStateFlow<GuacamoleSession?>(null)
    val session: StateFlow<GuacamoleSession?> = _session.asStateFlow()

    /** reg.txt's Authentication → "Username / Password". Throws [GuacamoleApiException] on failure — callers map that to the reg.txt ERROR HANDLING states. */
    suspend fun login(username: String, password: String, rememberSession: Boolean = false): GuacamoleSession {
        val result = authClient.login(username, password)
        val newSession = GuacamoleSession(
            authToken = result.authToken,
            dataSource = result.dataSource,
            username = result.username,
        )
        _session.value = newSession
        if (rememberSession) persist(newSession) else clearPersisted(username)
        return newSession
    }

    /**
     * OAuth/OpenID → mirrors [login]'s remember-session bookkeeping, but for
     * a caller that already holds an ID token — see
     * [GuacamoleAuthClient.loginWithExternalToken]'s doc comment for the
     * important caveat on the exact request shape not being verified
     * against a live server, and this class's own note on why the
     * interactive browser redirect that WOULD obtain [idToken] isn't
     * implemented anywhere in this app: OpenID Connect's browser-redirect
     * step is security-sensitive (PKCE, redirect-URI validation, token
     * replay protection) and provider-specific (Keycloak/Okta/Azure AD/a
     * generic OIDC IdP all differ in exact endpoint shapes and required
     * config) — building that flow blind, without a real target IdP to
     * test the redirect/token-exchange against, risks shipping something
     * that looks complete but is subtly wrong in a way that matters for
     * authentication security. The REST-level plumbing here is real and
     * ready for whichever browser-redirect mechanism ends up wired to it.
     */
    suspend fun loginWithExternalToken(idToken: String, state: String? = null, rememberSession: Boolean = false): GuacamoleSession {
        val result = authClient.loginWithExternalToken(idToken, state)
        val newSession = GuacamoleSession(
            authToken = result.authToken,
            dataSource = result.dataSource,
            username = result.username,
        )
        _session.value = newSession
        if (rememberSession) persist(newSession) else clearPersisted(result.username)
        return newSession
    }

    /**
     * reg.txt's SESSION → "Remember session": returns whatever session was
     * last [persist]-ed for this exact server+username, without making any
     * network call — the caller (see
     * [com.systemsgo.hex.ui.screens.RdpSessionActivity.resolveGuacamoleSessionOrAbort])
     * is responsible for treating this as *optimistic*: Guacamole tokens
     * expire server-side (idle timeout, admin-forced logout, ...) with no
     * client-visible warning beforehand, so the very next REST/tunnel call
     * made with a restored token can still fail — that failure should fall
     * back to a real [login], not be treated as a hard error. Returns null
     * if this repo has no [appContext] (persistence unavailable) or nothing
     * was ever persisted for this server+username.
     */
    fun tryRestoreSession(username: String): GuacamoleSession? {
        val context = appContext ?: return null
        val prefs = context.openEncryptedPrefs(PREFS_NAME)
        val key = sessionKey(username)
        val token = prefs.getString("$key.token", null) ?: return null
        val dataSource = prefs.getString("$key.dataSource", null) ?: return null
        val restored = GuacamoleSession(authToken = token, dataSource = dataSource, username = username)
        _session.value = restored
        return restored
    }

    /** reg.txt's Authentication → "Logout". Clears local session state (and any persisted token for this account) even if the server call fails. */
    suspend fun logout() {
        val current = _session.value
        current?.let { authClient.logout(it.authToken) }
        current?.let { clearPersisted(it.username) }
        _session.value = null
    }

    /** reg.txt's Connection Management → "Available connections". Requires an active [session] (call [login] or [tryRestoreSession] first). */
    suspend fun listConnections(dataSource: String = requireSession().dataSource): List<GuacamoleConnection> =
        authClient.listConnections(dataSource, requireSession().authToken)

    /** reg.txt's Connection Management → "Connection groups", used to build the group filter/tree in the connection picker. */
    suspend fun listConnectionGroups(dataSource: String = requireSession().dataSource): List<GuacamoleConnectionGroup> =
        authClient.listConnectionGroups(dataSource, requireSession().authToken)

    private fun requireSession(): GuacamoleSession =
        _session.value ?: throw IllegalStateException("GuacamoleRepository.login()/tryRestoreSession() must succeed before this call")

    private fun persist(session: GuacamoleSession) {
        val context = appContext ?: return
        val key = sessionKey(session.username)
        context.openEncryptedPrefs(PREFS_NAME).edit()
            .putString("$key.token", session.authToken)
            .putString("$key.dataSource", session.dataSource)
            .apply()
    }

    private fun clearPersisted(username: String) {
        val context = appContext ?: return
        val key = sessionKey(username)
        context.openEncryptedPrefs(PREFS_NAME).edit()
            .remove("$key.token")
            .remove("$key.dataSource")
            .apply()
    }

    /** Server URL + username, hashed — avoids putting a raw URL/username directly into a SharedPreferences key. */
    private fun sessionKey(username: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(serverConfig.baseUrl.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(username.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PREFS_NAME = "guacamole_sessions"
    }
}

