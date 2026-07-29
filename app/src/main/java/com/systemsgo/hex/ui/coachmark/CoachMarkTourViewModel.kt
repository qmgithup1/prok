package com.systemsgo.hex.ui.coachmark

import androidx.lifecycle.ViewModel
import com.systemsgo.hex.data.repository.CoachMarkPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Thin, screen-agnostic wrapper around [CoachMarkPreferences] for screens
 * that want to gate a `CoachMarkState.start(...)` call behind "has this
 * device already seen this tour?" without adding that plumbing to their own
 * (sometimes already very large) feature ViewModel — e.g. RdpSessionScreen,
 * which owns RdpSessionViewModel and shouldn't need to grow just to host one
 * boolean flag. Grab it with `hiltViewModel()` right where the tour starts.
 */
@HiltViewModel
class CoachMarkTourViewModel @Inject constructor(
    private val preferences: CoachMarkPreferences,
) : ViewModel() {
    fun shouldShow(tourId: String): Boolean = !preferences.hasSeenTour(tourId)
    fun markSeen(tourId: String) = preferences.markTourSeen(tourId)
}
