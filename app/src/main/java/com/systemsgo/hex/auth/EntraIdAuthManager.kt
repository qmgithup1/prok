package com.systemsgo.hex.auth

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import com.systemsgo.hex.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ENTRA-ID-AUTH FEATURE — Part 1/2.
 *
 * Wraps MSAL for Android (com.microsoft.identity.client.msal, official
 * Microsoft library) to sign the user in against Microsoft Entra ID.
 *
 * Scope of this class: interactive + silent sign-in, token acquisition,
 * sign-out, and exposing the current account as a [StateFlow] the UI layer
 * can observe (e.g. to show "Signed in as user@tenant.com" and gate the
 * "Connect" button).
 *
 * Deliberately OUT of scope here (see Part 2 continuation): actually feeding
 * the acquired token/account into [com.systemsgo.hex.rdp.native.AFreeRdpBridge]'s
 * NLA connect flow. That wiring depends on which SSO mechanism the backend
 * actually supports — see the note in the class doc comment on `signIn`
 * below and the handoff prompt provided separately.
 *
 * Configuration: fill in the real values in
 * app/src/main/res/raw/msal_config.json (client_id, tenant_id, redirect_uri —
 * the redirect_uri's signature hash comes from your release/debug signing
 * cert, see https://aka.ms/msal-config for the exact steps) before this
 * class will work against a real Entra tenant. Also requires the
 * BrowserTabActivity + AndroidManifest <queries> block described in the
 * accompanying manifest diff.
 */
@Singleton
class EntraIdAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Scopes requested at sign-in. `User.Read` is the minimal Graph scope
     * needed to prove interactive sign-in succeeded and to read the signed-in
     * user's UPN/display name for the UI. If Part 2 ends up using a
     * different downstream API (e.g. a custom RD Gateway token audience),
     * add that scope here too — MSAL will prompt for consent once and
     * silently include it in future acquireTokenSilent calls.
     */
    private val defaultScopes = arrayOf("User.Read")

    private var pca: ISingleAccountPublicClientApplication? = null

    private val _authState = MutableStateFlow<EntraAuthState>(EntraAuthState.SignedOut)
    val authState: StateFlow<EntraAuthState> = _authState.asStateFlow()

    /**
     * Must be called once (e.g. from Application.onCreate or a splash/init
     * screen) before [signIn]/[signInSilentIfPossible] are used. Loads
     * res/raw/msal_config.json and restores any previously-signed-in account
     * from MSAL's encrypted token cache.
     */
    suspend fun initialize() {
        if (pca != null) return
        pca = suspendCancellableCoroutine { cont ->
            PublicClientApplication.createSingleAccountPublicClientApplication(
                context,
                R.raw.msal_config,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        if (cont.isActive) cont.resume(application)
                    }

                    override fun onError(exception: MsalException) {
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                },
            )
        }
        signInSilentIfPossible()
    }

    /** Tries to restore a session from MSAL's cache without any UI prompt. */
    suspend fun signInSilentIfPossible() {
        val app = pca ?: return
        val account = suspendCancellableCoroutine<IAccount?> { cont ->
            app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(activeAccount: IAccount?) {
                    if (cont.isActive) cont.resume(activeAccount)
                }

                override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                    // No-op here — onAccountLoaded already fires on first load.
                }

                override fun onError(exception: MsalException) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
        _authState.value = if (account != null) {
            EntraAuthState.SignedIn(account)
        } else {
            EntraAuthState.SignedOut
        }
    }

    /**
     * Launches the interactive Entra ID sign-in (system browser / Custom
     * Tabs via MSAL's own AuthorizationActivity). Must be called with a live
     * [Activity] (e.g. from a Compose screen's onClick, passing
     * LocalContext.current as? Activity).
     */
    suspend fun signIn(activity: Activity, scopes: Array<String> = defaultScopes): EntraAuthResult {
        val app = pca ?: return EntraAuthResult.Failure(IllegalStateException("EntraIdAuthManager.initialize() not called yet"))
        return suspendCancellableCoroutine { cont ->
            app.signIn(activity, /* loginHint = */ null, scopes, object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    _authState.value = EntraAuthState.SignedIn(authenticationResult.account)
                    if (cont.isActive) cont.resume(EntraAuthResult.Success(authenticationResult))
                }

                override fun onError(exception: MsalException) {
                    _authState.value = EntraAuthState.SignedOut
                    if (cont.isActive) cont.resume(EntraAuthResult.Failure(exception))
                }

                override fun onCancel() {
                    if (cont.isActive) cont.resume(EntraAuthResult.Cancelled)
                }
            })
        }
    }

    /**
     * Acquires (or silently refreshes) an access token for [scopes] for the
     * currently signed-in account. Call this right before it's needed — MSAL
     * handles refresh-token rotation internally, so the token returned here
     * should always be valid for immediate use.
     */
    suspend fun acquireTokenSilent(scopes: Array<String> = defaultScopes): EntraAuthResult {
        val app = pca ?: return EntraAuthResult.Failure(IllegalStateException("EntraIdAuthManager.initialize() not called yet"))
        val account = (_authState.value as? EntraAuthState.SignedIn)?.account
            ?: return EntraAuthResult.Failure(IllegalStateException("No signed-in account — call signIn() first"))
        val authority = app.configuration.defaultAuthority.authorityURL.toString()
        return suspendCancellableCoroutine { cont ->
            app.acquireTokenSilentAsync(scopes, account, authority, object : SilentAuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    if (cont.isActive) cont.resume(EntraAuthResult.Success(authenticationResult))
                }

                override fun onError(exception: MsalException) {
                    if (cont.isActive) cont.resume(EntraAuthResult.Failure(exception))
                }
            })
        }
    }

    /** Signs out and clears MSAL's local token cache for the current account. */
    suspend fun signOut(): Boolean {
        val app = pca ?: return true
        val result = suspendCancellableCoroutine<Boolean> { cont ->
            app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onError(exception: MsalException) {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
        if (result) _authState.value = EntraAuthState.SignedOut
        return result
    }
}

sealed interface EntraAuthState {
    data object SignedOut : EntraAuthState
    data class SignedIn(val account: IAccount) : EntraAuthState
}

sealed interface EntraAuthResult {
    data class Success(val result: IAuthenticationResult) : EntraAuthResult
    data class Failure(val error: Exception) : EntraAuthResult
    data object Cancelled : EntraAuthResult
}
