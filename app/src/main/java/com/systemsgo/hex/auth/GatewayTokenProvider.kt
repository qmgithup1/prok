package com.systemsgo.hex.auth

import com.systemsgo.hex.data.model.GatewayAuthMode
import com.systemsgo.hex.data.model.RdpProfile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ENTRA-ID-AUTH FEATURE — the glue between [EntraIdAuthManager] (generic
 * MSAL sign-in/token wrapper, Part 1 of the original feature split) and an
 * actual RDP connect attempt.
 *
 * [RemoteSessionFactory.create] is a plain synchronous `object` function —
 * it can't itself make a suspend MSAL call — so callers (RdpSessionViewModel)
 * resolve the Gateway bearer token *before* calling `create()`, exactly the
 * same shape as how `effectivePerformanceLevel`/`effectiveColorDepth` are
 * resolved just above every `RemoteSessionFactory.create(...)` call site
 * today. This class is the one place that resolution logic lives, so all
 * three call sites (initial connect, quick-reconnect, live performance-level
 * change) share identical behavior instead of three hand-rolled copies.
 */
@Singleton
class GatewayTokenProvider @Inject constructor(
    private val entraIdAuthManager: EntraIdAuthManager,
    private val entraSignInLinkStore: EntraSignInLinkStore,
) {
    sealed interface Result {
        data class Token(val bearerToken: String, val upn: String?) : Result
        /** No token available and no signed-in account — caller should
         *  prompt the user to sign in (e.g. via GatewaySignInSection's
         *  button) rather than attempting to connect. */
        data object SignInRequired : Result
        /** [RdpProfile.gatewayAuthMode] is ENTRA_ID but
         *  [RdpProfile.gatewayScopeUri] is blank — caller should prompt the
         *  user to fill in the Application ID URI field in
         *  EntraGatewaySignInSection rather than attempting to acquire a
         *  token for an unknown audience. */
        data object MissingScope : Result
        data class Failure(val message: String) : Result
    }

    private companion object {
        /**
         * MSAL scope suffix appended to [RdpProfile.gatewayScopeUri] to
         * request delegated access on that Application Proxy app —
         * "user_impersonation" is the scope name Azure AD's App Proxy blade
         * auto-creates under "Expose an API" for a newly-registered App
         * Proxy application. If a given tenant's admin renamed or added a
         * different scope there, the user needs to enter
         * "<Application ID URI>/<that scope name>" directly into
         * gatewayScopeUri instead of relying on this suffix — see
         * buildScope()'s doc comment.
         */
        const val DEFAULT_SCOPE_SUFFIX = "/user_impersonation"
    }

    /**
     * Builds the actual MSAL scope string to request for a profile's
     * [RdpProfile.gatewayScopeUri]. Accepts either just the Application ID
     * URI ("api://<app-id>", the common case — DEFAULT_SCOPE_SUFFIX is
     * appended) or the user having already typed the full
     * "<uri>/<scope-name>" themselves (left as-is, detected by an existing
     * "/" after the "://").
     */
    private fun buildScope(gatewayScopeUri: String): String {
        val uri = gatewayScopeUri.trim()
        val schemeEnd = uri.indexOf("://")
        val hasExplicitScopeName = schemeEnd >= 0 && uri.indexOf('/', schemeEnd + 3) >= 0
        return if (hasExplicitScopeName) uri else uri + DEFAULT_SCOPE_SUFFIX
    }

    /**
     * Resolves the bearer token to use for [profile]'s Gateway hop.
     * - Returns [Result.Token] immediately, with an empty token, if the
     *   profile isn't using [GatewayAuthMode.ENTRA_ID] at all — callers can
     *   pass that empty string straight to
     *   `RemoteSessionFactory.create(gatewayBearerToken = ...)` unconditionally
     *   without an `if` at every call site.
     * - Returns [Result.MissingScope] if ENTRA_ID is selected but
     *   [RdpProfile.gatewayScopeUri] hasn't been filled in yet for this
     *   profile — see that field's doc comment for where the user gets it.
     * - Otherwise tries a silent MSAL token acquisition first (no UI), and
     *   surfaces [Result.SignInRequired] if that fails because there's no
     *   cached account (first-time use, or the account was signed out) —
     *   RdpSessionViewModel/RdpSessionActivity should show
     *   GatewaySignInSection's sign-in prompt in that case rather than
     *   silently failing the whole connection attempt.
     */
    suspend fun resolve(profile: RdpProfile): Result {
        if (GatewayAuthMode.fromName(profile.gatewayAuthMode) != GatewayAuthMode.ENTRA_ID) {
            return Result.Token(bearerToken = "", upn = null)
        }
        if (profile.gatewayScopeUri.isBlank()) {
            return Result.MissingScope
        }
        val scopes = arrayOf(buildScope(profile.gatewayScopeUri))
        return when (val silent = entraIdAuthManager.acquireTokenSilent(scopes)) {
            is EntraAuthResult.Success -> {
                val upn = silent.result.account.username
                entraSignInLinkStore.setLinkedUpn(profile.id, upn)
                Result.Token(bearerToken = silent.result.accessToken, upn = upn)
            }
            is EntraAuthResult.Failure -> Result.SignInRequired
            EntraAuthResult.Cancelled -> Result.SignInRequired
        }
    }
}
