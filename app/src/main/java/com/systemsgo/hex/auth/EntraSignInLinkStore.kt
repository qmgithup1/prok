package com.systemsgo.hex.auth

import android.content.Context
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.security.openEncryptedPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ENTRA-ID-AUTH FEATURE — records which Entra ID account (by UPN, e.g.
 * "user@contoso.com") is linked to which RDP profile's Gateway hop.
 *
 * Deliberately stores only the UPN, never a token: MSAL's own cache
 * (Android Keystore-backed, see EntraIdAuthManager) is already the
 * authoritative, encrypted store for tokens and the IAccount object itself.
 * Duplicating that here would just be a second place a token could leak
 * from, for zero benefit — a fresh token is always re-acquired via
 * `acquireTokenSilent` at connect time anyway (see GatewayTokenProvider).
 *
 * Two copies of the UPN intentionally exist:
 * - Here (EncryptedSharedPreferences, keyed by profileId): the fast,
 *   synchronous-feeling source GatewayTokenProvider writes to right after
 *   every successful silent/interactive token acquisition.
 * - RdpProfile.entraLinkedUpn (Room column): what GatewaySection actually
 *   *displays* while editing a profile, since the profile editor already
 *   holds a full RdpProfile in memory / Room Flow and reading a second
 *   encrypted-prefs source there would mean juggling two async data
 *   sources for one label. [setLinkedUpn] keeps both in sync so neither
 *   ever goes stale relative to the other.
 */
@Singleton
class EntraSignInLinkStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: RdpProfileRepository,
) {
    private val prefsName = "entra_gateway_links"

    /** Records (or clears, if [upn] is null) the linked account for [profileId],
     *  in both the encrypted-prefs copy and the Room column. */
    suspend fun setLinkedUpn(profileId: String, upn: String?) = withContext(Dispatchers.IO) {
        val prefs = context.openEncryptedPrefs(prefsName)
        prefs.edit().apply {
            if (upn.isNullOrBlank()) remove(profileId) else putString(profileId, upn)
        }.apply()

        val profile = try {
            profileRepository.getProfileById(profileId)
        } catch (e: SecurityException) {
            null
        } ?: return@withContext
        if (profile.entraLinkedUpn != (upn ?: "")) {
            profileRepository.updateProfile(profile.copy(entraLinkedUpn = upn ?: ""))
        }
    }

    /** Clears the linked account for [profileId] — call this alongside
     *  EntraIdAuthManager.signOut() if a user explicitly disconnects the
     *  Entra ID account from a specific profile (as opposed to a full MSAL
     *  sign-out, which affects every Entra-linked profile at once). */
    suspend fun clearLinkedUpn(profileId: String) = setLinkedUpn(profileId, null)

    fun getLinkedUpn(profileId: String): String? =
        context.openEncryptedPrefs(prefsName).getString(profileId, null)
}
