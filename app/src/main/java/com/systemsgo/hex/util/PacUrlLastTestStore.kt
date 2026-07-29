package com.systemsgo.hex.util

import android.content.Context

private const val PREFS_NAME = "pac_url_last_test"

/**
 * PAC-SUPPORT FEATURE (Part 3/n follow-up — "last successful test"): tiny
 * SharedPreferences-backed cache of when a given PAC URL's Test button
 * last resolved to a usable proxy, keyed by the (trimmed) URL string
 * itself — so reopening a profile that already has a `pacUrl` saved can
 * show "last verified: ..." immediately instead of the user having to
 * press Test again just to find out the URL is still good.
 *
 * Deliberately NOT a new Room column on RdpProfile: this is a disposable
 * UI hint, not connection data. A value this low-stakes doesn't justify a
 * fresh migration in SystemsGoDatabase.kt's chain for every existing
 * profile — plain (unencrypted) SharedPreferences is enough, same
 * reasoning as EncryptedPrefsHelper's doc comment draws the opposite way
 * for TOFU fingerprints/credentials, which actually are sensitive. Losing
 * this cache (reinstall, clearing app data) is harmless: the UI simply
 * falls back to not showing a "last verified" line until Test is pressed
 * again. Keying by the URL string itself (rather than a profile id) is
 * also intentional: if the same PAC URL is reused across multiple
 * profiles, testing it once from any of them usefully informs all of
 * them.
 */
class PacUrlLastTestStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records "now" as the last successful test time for [pacUrl]. */
    fun recordSuccess(pacUrl: String) {
        val key = pacUrl.trim()
        if (key.isEmpty()) return
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
    }

    /** Epoch millis of the last successful test for [pacUrl], or null if it was never tested successfully. */
    fun lastSuccessAt(pacUrl: String): Long? {
        val key = pacUrl.trim()
        if (key.isEmpty()) return null
        val value = prefs.getLong(key, -1L)
        return if (value < 0) null else value
    }
}
