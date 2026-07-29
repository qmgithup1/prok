package com.systemsgo.hex.ui.screens.addconnection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemsgo.hex.data.model.ProtocolCatalog
import com.systemsgo.hex.data.model.ProtocolCatalogEntry
import com.systemsgo.hex.data.model.ProtocolCategory
import com.systemsgo.hex.data.repository.AddConnectionPreferences
import com.systemsgo.hex.data.repository.CoachMarkPreferences
import com.systemsgo.hex.data.repository.CoachMarkTourIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Which filter chip is currently selected. Mirrors [ProtocolCategory] plus the two virtual chips. */
sealed interface ProtocolFilterChip {
    data object All : ProtocolFilterChip
    data object Favorites : ProtocolFilterChip
    data object Recent : ProtocolFilterChip
    data class Category(val category: ProtocolCategory) : ProtocolFilterChip

    val label: String get() = when (this) {
        All -> "All"
        Favorites -> "Favorites"
        Recent -> "Recent"
        is Category -> category.label
    }

    companion object {
        /** Chip row order, exactly as specced. */
        val all: List<ProtocolFilterChip> = listOf(All, Favorites, Recent) +
            ProtocolCategory.sectionCategories.map { Category(it) }
    }
}

data class AddConnectionProtocolUiState(
    val searchQuery: String = "",
    val selectedFilter: ProtocolFilterChip = ProtocolFilterChip.All,
    val favoriteIds: Set<String> = emptySet(),
    val recentEntries: List<ProtocolCatalogEntry> = emptyList(),
    val popularEntries: List<ProtocolCatalogEntry> = ProtocolCatalog.popular,
    /** Result of applying both the search query and the selected filter chip. */
    val visibleEntries: List<ProtocolCatalogEntry> = ProtocolCatalog.available,
    /** [visibleEntries] grouped for the "All Protocols" section — only populated for the All chip; see Part 2. */
    val groupedEntries: Map<ProtocolCategory, List<ProtocolCatalogEntry>> = emptyMap(),
    val isSearchActive: Boolean = false,
) {
    val isEmpty: Boolean get() = visibleEntries.isEmpty()
}

@HiltViewModel
class AddConnectionProtocolViewModel @Inject constructor(
    private val preferences: AddConnectionPreferences,
    private val coachMarkPreferences: CoachMarkPreferences,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedFilter = MutableStateFlow<ProtocolFilterChip>(ProtocolFilterChip.All)

    val uiState: StateFlow<AddConnectionProtocolUiState> = combine(
        searchQuery,
        selectedFilter,
        preferences.favoriteIdsFlow,
        preferences.recentIdsFlow,
    ) { query, filter, favoriteIds, recentIds ->
        val recentEntries = recentIds.mapNotNull { ProtocolCatalog.byId[it] }
            .filter { it.protocolType != null }
        val trimmedQuery = query.trim().lowercase()

        val filterMatched: List<ProtocolCatalogEntry> = when (filter) {
            ProtocolFilterChip.All -> ProtocolCatalog.available
            ProtocolFilterChip.Favorites -> ProtocolCatalog.available.filter { it.id in favoriteIds }
            ProtocolFilterChip.Recent -> recentEntries
            is ProtocolFilterChip.Category -> ProtocolCatalog.available.filter { it.category == filter.category }
        }

        val visible = if (trimmedQuery.isBlank()) {
            filterMatched
        } else {
            filterMatched.filter { it.searchIndex.contains(trimmedQuery) }
        }

        AddConnectionProtocolUiState(
            searchQuery = query,
            selectedFilter = filter,
            favoriteIds = favoriteIds,
            recentEntries = recentEntries,
            popularEntries = ProtocolCatalog.popular,
            visibleEntries = visible,
            groupedEntries = visible.groupBy { it.category }
                .toSortedMap(compareBy { ProtocolCategory.sectionCategories.indexOf(it) }),
            isSearchActive = trimmedQuery.isNotBlank(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AddConnectionProtocolUiState(),
    )

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun onFilterSelected(filter: ProtocolFilterChip) {
        selectedFilter.value = filter
    }

    fun toggleFavorite(entry: ProtocolCatalogEntry) {
        preferences.toggleFavorite(entry.id)
    }

    fun hasSeenIntro(entry: ProtocolCatalogEntry): Boolean = preferences.hasSeenIntro(entry.id)

    fun markIntroSeen(entry: ProtocolCatalogEntry) {
        preferences.markIntroSeen(entry.id)
    }

    /** Called once the user actually proceeds to create a connection with this protocol. */
    fun recordUsed(entry: ProtocolCatalogEntry) {
        preferences.recordUsed(entry.id)
    }

    // ── "How to create a connection" spotlight tour ─────────────────────────

    /** True the very first time this screen is reached; false on every visit after. */
    fun shouldShowConnectionSpotlight(): Boolean =
        !coachMarkPreferences.hasSeenTour(CoachMarkTourIds.ADD_CONNECTION)

    fun markConnectionSpotlightSeen() {
        coachMarkPreferences.markTourSeen(CoachMarkTourIds.ADD_CONNECTION)
    }
}
