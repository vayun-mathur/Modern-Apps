package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconWork
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TransitStopsViewModel
import com.vayunmathur.maps.util.rememberPlacePanelState

/**
 * Everything on the map's bottom sheet below the fold — what expanding it reveals.
 *
 * The fixed part above, which the sheet peeks at, is [BottomSheetHeader]. The
 * "Directions" action lives there, which is why nothing here needs to change the
 * selection any more.
 */
@Composable
fun BottomSheetContent(
    viewModel: SelectedFeatureViewModel,
    selectedFeature: SpecificFeature?,
    route: Map<RouteService.TravelMode, RouteService.RouteType?>?,
    selectedRouteType: RouteService.TravelMode,
    setSelectedRouteType: (RouteService.TravelMode) -> Unit,
    savedPlacesViewModel: SavedPlacesViewModel,
    transitViewModel: TransitStopsViewModel,
    navState: NavigationSessionManager.NavState = NavigationSessionManager.NavState.Idle,
) {
    val panelState = rememberPlacePanelState(viewModel, savedPlacesViewModel)
    val panelActions = remember(selectedFeature) {
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
    BottomSheetContent(
        panelState = panelState,
        panelActions = panelActions,
        selectedFeature = selectedFeature,
        route = route,
        selectedRouteType = selectedRouteType,
        setSelectedRouteType = setSelectedRouteType,
        navState = navState,
    )
}

/**
 * Stateless sheet body: the same content as [BottomSheetContent], driven by
 * [PlacePanelState] instead of the ViewModels, so previews can render it with
 * literal state. The ViewModel overload above is the only caller that binds
 * real state; everything else passes through untouched.
 */
@Composable
fun BottomSheetContent(
    panelState: PlacePanelState,
    panelActions: PlacePanelActions,
    selectedFeature: SpecificFeature?,
    route: Map<RouteService.TravelMode, RouteService.RouteType?>?,
    selectedRouteType: RouteService.TravelMode,
    setSelectedRouteType: (RouteService.TravelMode) -> Unit,
    navState: NavigationSessionManager.NavState = NavigationSessionManager.NavState.Idle,
) {
    when (selectedFeature) {
        is SpecificFeature.Admin0Label ->
            AdminLabelHeader(selectedFeature.name, selectedFeature.wikipedia)
        is SpecificFeature.Admin1Label ->
            AdminLabelHeader(selectedFeature.name, selectedFeature.wikipedia)
        is SpecificFeature.Admin2Label ->
            AdminLabelHeader(selectedFeature.name, selectedFeature.wikipedia)
        is SpecificFeature.Restaurant -> {
            Column {
                // Weighted so the sheet fills available space: the tab panel scrolls and
                // would otherwise take every pixel of a tall sheet.
                PlaceSheet(panelState.poi, panelState.poiSection, selectedFeature, Modifier.weight(1f, fill = false))
            }
        }
        is SpecificFeature.GenericPlace -> {
            Column {
                PlaceSheet(
                    panelState.poi,
                    panelState.poiSection,
                    selectedFeature,
                    Modifier.weight(1f, fill = false),
                    onDepartures = if (selectedFeature.poiType == 50) {
                        {
                            panelActions.openNearestStop(
                                selectedFeature.position.latitude,
                                selectedFeature.position.longitude,
                            )
                        }
                    } else null,
                )
            }
        }
        is SpecificFeature.Route -> {
            val currentRoute = route
            if (currentRoute != null) {
                RouteSheet(selectedFeature, currentRoute, selectedRouteType, setSelectedRouteType, navState, panelState.userPosition)
            } else {
                // Routes arrive asynchronously and start out null, so this is the state right
                // after asking for directions. It used to render nothing at all, inside a sheet
                // whose peek height is fixed — so the user got a blank card and no reason to
                // believe anything was happening. RouteSheet has a "generating" placeholder of
                // its own, but it was unreachable from here.
                LoadingState(message = stringResource(R.string.generating_route))
            }
        }
        // `RoutableFeature` is an intermediate sealed interface, so this cannot be made
        // exhaustive over the leaves; `else` covers null and any future subtype.
        else -> Unit
    }
}

/**
 * Header for a tapped country / state / city label: the name, with a button to its
 * Wikipedia article pinned to the trailing edge.
 *
 * The article URL is deliberately not rendered. It used to be the subtitle, where
 * it read as noise and — being plain text — could not actually be opened.
 */
@Composable
private fun AdminLabelHeader(name: String, wikipedia: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        // Only when there is an article to open. The name comes from the basemap and is always
        // there; the article is a Wikidata lookup that needs the network, so offline the sheet
        // shows the place with no button rather than not opening at all.
        if (wikipedia != null) {
            TextButton(onClick = { goto(context, wikipedia) }) {
                Text(stringResource(R.string.wikipedia))
            }
        }
    }
}
