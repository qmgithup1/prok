package com.systemsgo.hex.data.repository

import android.content.Context
import com.systemsgo.hex.security.openEncryptedPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Has the user already seen this coach-mark tour?" flags — one boolean per
 * tour id (e.g. "add_connection_spotlight", "rdp_toolbar_spotlight"). Same
 * trust boundary and pattern as [AddConnectionPreferences]: nothing here is a
 * credential, so a single small EncryptedSharedPreferences file for all
 * coach-mark tours in the app is enough; no need for a table per screen.
 *
 * Callers should check [hasSeenTour] before calling
 * `CoachMarkState.start(...)`, and call [markTourSeen] once the tour finishes
 * or is skipped (see CoachMarkOverlay's `onFinished`), so it only ever runs
 * automatically once per device.
 */
@Singleton
class CoachMarkPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.openEncryptedPrefs("systemsgo_coach_marks") }

    fun hasSeenTour(tourId: String): Boolean = prefs.getBoolean(tourId, false)

    fun markTourSeen(tourId: String) {
        prefs.edit().putBoolean(tourId, true).apply()
    }

    /** Escape hatch for a future "Replay tutorials" setting. */
    fun resetTour(tourId: String) {
        prefs.edit().remove(tourId).apply()
    }
}

/** Well-known tour ids, kept in one place so screens can't typo them out of sync with a reset UI. */
object CoachMarkTourIds {
    const val ADD_CONNECTION = "add_connection_spotlight"
    const val RDP_SESSION_TOOLBAR = "rdp_toolbar_spotlight"
}
