package com.vayunmathur.maps.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.PoiSection

/**
 * Everything the place panel (sheet header + sheet content) reads, in one value.
 *
 * A screen takes this plus [PlacePanelActions] rather than the ViewModels
 * themselves, so a `@Preview` can render the panel from literal state — the
 * same contract [SearchUiState]/[SearchActions] already keeps for search.
 * The ViewModel-backed callers bind it with [rememberPlacePanelState]; nothing
 * else touches a ViewModel.
 */
data class PlacePanelState(
    val poi: GooglePoiInfo? = null,
    val poiSection: PoiSection = PoiSection.DETAILS,
    val saved: List<SavedPlace> = emptyList(),
    val userPosition: com.vayunmathur.library.map.GeoPoint? = null,
) {
    /** True when [feature] is already in the flat starred list. */
    fun isSaved(feature: SpecificFeature.RoutableFeature): Boolean =
        saved.any { it.matches(feature) }

    /** The starred entry matching [feature], for removal. Null when not saved. */
    fun savedMatch(feature: SpecificFeature.RoutableFeature): SavedPlace? =
        saved.firstOrNull { it.matches(feature) }
}

/**
 * Place-panel callbacks. Every method has a no-op default so a preview can
 * render the panel without supplying behaviour — [Noop] is the whole
 * implementation a preview needs.
 */
interface PlacePanelActions {
    fun setPoiSection(section: PoiSection) {}
    fun addSaved() {}
    fun removeSaved(place: SavedPlace) {}
    fun openNearestStop(lat: Double, lon: Double) {}

    companion object {
        val Noop: PlacePanelActions = object : PlacePanelActions {}
    }
}

/**
 * Bind the three ViewModels the place panel reads into a [PlacePanelState].
 *
 * One binder so the header and the content cannot disagree: the tab row lives
 * in the header while the panel it switches lives in the content, and both
 * resolve through this same value.
 */
@Composable
fun rememberPlacePanelState(
    viewModel: SelectedFeatureViewModel,
    savedPlacesViewModel: SavedPlacesViewModel,
): PlacePanelState {
    val poi by viewModel.currentPoiInfo.collectAsState()
    val poiSection by viewModel.poiSection.collectAsState()
    val saved by savedPlacesViewModel.saved.collectAsState()
    val userPosition by viewModel.userPosition.collectAsState()
    return PlacePanelState(
        poi = poi,
        poiSection = poiSection,
        saved = saved,
        userPosition = userPosition,
    )
}
