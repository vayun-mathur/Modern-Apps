package com.vayunmathur.travel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.util.ConnectivityMonitor
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.R
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.ExploreDestinationDto
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.searchExplore
import com.vayunmathur.travel.util.selectOffer

/**
 * Explore map: destination suggestions from `find_destination` as price pins.
 * Tapping a pin opens [ExploreBottomSheet] with live flights to that place;
 * tapping a live offer reports it via the normal review flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreMapPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.Explore,
) {
    val state by viewModel.explore.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ExploreDestinationDto?>(null) }

    LaunchedEffect(route) {
        viewModel.searchExplore(
            origin = route.origin,
            tripType = route.tripType,
            depart = route.depart,
            returnDate = route.returnDate,
            cabin = route.cabin,
            adults = route.adults,
            children = route.children,
            infants = route.infants,
            maxConnections = route.maxConnections,
        )
    }

    val camera = rememberCameraState(
        CameraPosition(target = GeoPoint(0.0, 20.0), zoom = 1.5)
    )
    // Fit the camera on the pins once loaded.
    LaunchedEffect(state.destinations) {
        val pins = state.destinations.mapNotNull { d ->
            val lat = d.latitude
            val lng = d.longitude
            if (lat == null || lng == null) null else GeoPoint(lng, lat)
        }
        if (pins.isNotEmpty()) {
            val avgLng = pins.map { it.longitude }.average()
            val avgLat = pins.map { it.latitude }.average()
            camera.animateTo(CameraPosition(target = GeoPoint(avgLng, avgLat), zoom = 2.5))
        }
    }

    val request = remember(route) {
        ExploreRequest(
            origin = route.origin,
            tripType = route.tripType,
            depart = route.depart,
            returnDate = route.returnDate,
            cabin = route.cabin,
            adults = route.adults,
            children = route.children,
            infants = route.infants,
            maxConnections = route.maxConnections,
        )
    }

    AppScaffold(
        title = stringResource(R.string.explore_title, route.origin),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading || state.error != null || (state.hasSearched && state.destinations.isEmpty())) {
                StatusBox(
                    loading = state.loading,
                    error = state.error,
                    isEmpty = state.hasSearched && state.destinations.isEmpty(),
                    emptyMessage = stringResource(R.string.explore_empty),
                )
            } else {
                VectorMap(cameraState = camera, online = ConnectivityMonitor.isOnline) {
                    state.destinations.forEach { dest ->
                        val lat = dest.latitude ?: return@forEach
                        val lng = dest.longitude ?: return@forEach
                        MapMarker(position = GeoPoint(lng, lat)) {
                            ExplorePricePill(
                                amount = dest.lowestAmount,
                                currency = dest.lowestCurrency,
                                modifier = Modifier.clickable { selected = dest },
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { dest ->
        ExploreBottomSheet(
            destination = dest,
            request = request,
            viewModel = viewModel,
            onDismiss = { selected = null },
            onOffer = { offer ->
                viewModel.selectOffer(offer)
                selected = null
                backStack.add(Route.OfferReview(offer.offerId))
            },
        )
    }
}
