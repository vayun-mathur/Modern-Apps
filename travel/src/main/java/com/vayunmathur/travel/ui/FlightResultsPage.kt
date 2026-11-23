package com.vayunmathur.travel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.R
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.platform.FlightQuery
import com.vayunmathur.travel.platform.FlightResultsActions
import com.vayunmathur.travel.platform.FlightResultsState
import com.vayunmathur.travel.platform.OfferSort
import com.vayunmathur.travel.platform.TravelViewModel
import com.vayunmathur.travel.platform.searchFlights
import com.vayunmathur.travel.platform.selectOffer
import com.vayunmathur.travel.platform.setFareBrandFilter
import com.vayunmathur.travel.platform.setMaxStopsFilter
import com.vayunmathur.travel.platform.setSort
import com.vayunmathur.travel.platform.toggleAirlineFilter
import androidx.compose.ui.res.stringResource

/** Binds [TravelViewModel] and the back stack to the stateless [FlightResultsScreen]. */
@Composable
fun FlightResultsPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.FlightResults,
) {
    val state by viewModel.flights.collectAsStateWithLifecycle()
    val query = FlightQuery(
        slices = route.slices,
        adults = route.adults,
        children = route.children,
        infants = route.infants,
        cabin = route.cabin,
        maxConnections = route.maxConnections,
    )

    LaunchedEffect(route) { viewModel.searchFlights(query) }

    val firstLeg = route.slices.substringBefore(',').split(':')
    val title = "${firstLeg.getOrNull(0).orEmpty()} → ${firstLeg.getOrNull(1).orEmpty()}"

    val actions = remember(viewModel, backStack, query) {
        object : FlightResultsActions {
            override fun setSort(sort: OfferSort) = viewModel.setSort(sort)
            override fun setMaxStopsFilter(maxStops: Int?) = viewModel.setMaxStopsFilter(maxStops)
            override fun toggleAirlineFilter(iata: String) = viewModel.toggleAirlineFilter(iata)
            override fun setFareBrandFilter(fareBrand: String?) = viewModel.setFareBrandFilter(fareBrand)
            override fun refreshOffers() = viewModel.searchFlights(query)

            override fun openOffer(offer: OfferDto) {
                viewModel.selectOffer(offer)
                backStack.add(Route.OfferReview(offer.offerId))
            }

            override fun back() = backStack.pop()
        }
    }

    FlightResultsScreen(title = title, state = state, actions = actions)
}
