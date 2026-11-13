package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.CompassCalibrationHint
import com.vayunmathur.library.ui.FreeHeightSheetState
import com.vayunmathur.library.ui.OverlayAction
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.TopAppBarOverlay
import com.vayunmathur.maps.R as MapsR
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.ui.map.LayerToggles
import com.vayunmathur.maps.ui.map.MapChromeState
import com.vayunmathur.maps.ui.map.MapFabStack
import com.vayunmathur.maps.ui.map.MapOverlay
import com.vayunmathur.maps.ui.map.MapOverlays
import com.vayunmathur.maps.ui.map.MapSearchBar
import com.vayunmathur.maps.ui.map.MapSurface
import com.vayunmathur.maps.ui.map.NavigationCameraFollow
import com.vayunmathur.maps.ui.map.WaypointList
import com.vayunmathur.maps.ui.map.rememberMapChromeState
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchUiState
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TransitStopsViewModel
import com.vayunmathur.maps.util.rememberPlacePanelState
import com.vayunmathur.maps.util.visibleBoundsOrWorld
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The wide-layout host: map pane plus side panel instead of bottom sheets.
 *
 * Expanded: the map stays full-bleed full-height in its pane while the
 * place/search content renders beside it instead of in sheets, so the
 * sheet lift has nothing to clear and the map box is shared untouched.
 * No insets at this level: the map box insets its own chrome and the
 * panel insets itself, so wrapping them here would inset twice.
 *
 * Takes the already-built [MapPageScope] (with null sheet states — nothing
 * may suspend on an unmeasured scaffold) plus the handful of wide-only
 * inputs, so MapPage stays a single `if (wide)` branch with an early return.
 */
@Composable
internal fun MapPageScope.MapWideHost(
    searchActions: SearchActions?,
    searchState: SearchUiState,
    panelSearch: Boolean,
    panelPlace: Boolean,
) {
    val panelState = rememberPlacePanelState(viewModel, savedPlacesViewModel)
    val panelActions: PlacePanelActions = remember(selectedFeature) {
        object : PlacePanelActions {
            override fun setPoiSection(section: PoiSection) {
                viewModel.setPoiSection(section)
            }
            override fun addSaved() {
                (selectedFeature as? SpecificFeature.RoutableFeature)?.let {
                    savedPlacesViewModel.addSaved(it)
                }
            }
            override fun removeSaved(place: SavedPlace) {
                savedPlacesViewModel.removeSaved(place)
            }
            override fun openNearestStop(lat: Double, lon: Double) {
                transitViewModel.openNearestStop(lat, lon)
            }
        }
    }
    val panelContent: (@Composable (Modifier) -> Unit)? = when {
        panelSearch && searchActions != null -> { modifier ->
            MapSidePanel(
                searchOpen = true,
                searchState = searchState,
                searchActions = searchActions,
                panelState = panelState,
                panelActions = panelActions,
                selectedFeature = selectedFeature,
                onSelectFeature = { viewModel.set(it) },
                route = route,
                selectedRouteType = chrome.selectedRouteType,
                onSelectedRouteType = { chrome.selectedRouteType = it },
                inactiveNavigation = inactiveNavigation,
                navState = navState,
                onClosePanel = { viewModel.set(null) },
                modifier = modifier,
            )
        }
        panelPlace -> { modifier ->
            MapSidePanel(
                searchOpen = false,
                searchState = searchState,
                searchActions = searchActions ?: SearchActions.Noop,
                panelState = panelState,
                panelActions = panelActions,
                selectedFeature = selectedFeature,
                onSelectFeature = { viewModel.set(it) },
                route = route,
                selectedRouteType = chrome.selectedRouteType,
                onSelectedRouteType = { chrome.selectedRouteType = it },
                inactiveNavigation = inactiveNavigation,
                navState = navState,
                onClosePanel = {
                    viewModel.set(null)
                    viewModel.setInactiveNavigation(null)
                },
                modifier = modifier,
            )
        }
        else -> null
    }
    MapWideLayout(
        mapContent = { modifier -> MapContentBox(modifier) },
        panelContent = panelContent,
    )
}
