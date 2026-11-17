package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.StaySearchResultDto
import com.vayunmathur.travel.platform.StayResultsActions
import com.vayunmathur.travel.platform.TravelViewModel
import com.vayunmathur.travel.platform.searchStays
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Binds [TravelViewModel] and the back stack to the stateless [StayResultsScreen]. */
@Composable
fun StayResultsPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.StayResults,
) {
    val state by viewModel.stayResults.collectAsStateWithLifecycle()
    LaunchedEffect(route) {
        val lat = route.latitude.takeIf { !it.isNaN() }
        val lng = route.longitude.takeIf { !it.isNaN() }
        viewModel.searchStays(route.place, route.checkIn, route.checkOut, route.rooms, route.adults, lat, lng)
    }
    val actions = remember(backStack) {
        object : StayResultsActions {
            override fun openStay(result: StaySearchResultDto) =
                backStack.add(Route.StayDetail(result.id, result.name))

            override fun back() = backStack.pop()
        }
    }
    StayResultsScreen(place = route.place, state = state, actions = actions)
}
