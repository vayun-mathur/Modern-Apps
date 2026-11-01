package com.vayunmathur.travel.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconFlight
import com.vayunmathur.library.ui.IconHotel
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.BottomNavBarItem
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.clearRecents
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.vayunmathur.travel.R

private enum class Product(@StringRes val label: Int, val icon: @Composable () -> Unit) {
    FLIGHTS(R.string.flights, { IconFlight() }),
    STAYS(R.string.stays, { IconHotel() }),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(backStack: NavBackStack<Route>, viewModel: TravelViewModel) {
    val recents by viewModel.recentSearches.collectAsStateWithLifecycle()
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    var product by rememberSaveable { mutableStateOf(Product.FLIGHTS) }
    // Hoisted (and saved) so both forms are still filled in when the user
    // comes back from results or switches tabs.
    var flightForm by rememberSaveable(stateSaver = FlightFormSaver) { mutableStateOf(FlightFormState()) }
    var stayForm by rememberSaveable(stateSaver = StayFormSaver) { mutableStateOf(StayFormState()) }

    AppScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            if (trips.isNotEmpty()) {
                TextButton(onClick = { backStack.add(Route.Trips) }) { Text(stringResource(R.string.my_trips)) }
            }
            IconButton(onClick = { backStack.add(Route.Settings) }) {
                IconSettings()
            }
        },
        scrollBehavior = appBarScrollBehavior(),
        bottomBar = {
            BottomNavBar {
                Product.entries.forEach { p ->
                    BottomNavBarItem(
                        selected = product == p,
                        onClick = { product = p },
                        icon = p.icon,
                        label = stringResource(p.label),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {

            when (product) {
                Product.FLIGHTS -> FlightSearchForm(
                    viewModel,
                    state = flightForm,
                    onState = { flightForm = it },
                    onSearch = { query ->
                        if (query.isRoundTrip) {
                            backStack.add(
                                Route.OutboundSelect(
                                    slices = query.slices,
                                    adults = query.adults,
                                    children = query.children,
                                    infants = query.infants,
                                    cabin = query.cabin,
                                    maxConnections = query.maxConnections,
                                )
                            )
                        } else {
                            backStack.add(
                                Route.FlightResults(
                                    slices = query.slices,
                                    adults = query.adults,
                                    children = query.children,
                                    infants = query.infants,
                                    cabin = query.cabin,
                                    maxConnections = query.maxConnections,
                                )
                            )
                        }
                    },
                )
                Product.STAYS -> StaySearchForm(viewModel, stayForm, { stayForm = it }) { place, checkIn, checkOut, rooms, adults, lat, lng ->
                    backStack.add(
                        Route.StayResults(
                            place = place,
                            checkIn = checkIn,
                            checkOut = checkOut,
                            rooms = rooms,
                            adults = adults,
                            latitude = lat ?: Double.NaN,
                            longitude = lng ?: Double.NaN,
                        )
                    )
                }
            }

            if (recents.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionHeader(stringResource(R.string.recent_searches))
                    TextButton(onClick = { viewModel.clearRecents() }) { Text(stringResource(UiR.string.clear)) }
                }
                recents.forEach { recent ->
                    RecentSearchCard(recent) { openRecent(backStack, recent) }
                }
            }

            if (trips.isNotEmpty()) {
                SectionHeader(stringResource(R.string.my_trips))
                trips.take(3).forEach { trip ->
                    TripSummaryCard(trip) { backStack.add(destinationForTrip(trip)) }
                }
                if (trips.size > 3) {
                    TextButton(
                        onClick = { backStack.add(Route.Trips) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text(pluralStringResource(R.plurals.see_all_trips, trips.size, trips.size)) }
                }
            }

            Column(Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

/** Where tapping a trip summary goes, depending on flight vs stay. */
private fun destinationForTrip(trip: BookedTrip): Route =
    if (trip.type == "stay") Route.StayConfirmation(trip.orderId) else Route.Confirmation(trip.orderId)

@Composable
private fun RecentSearchCard(recent: RecentSearch, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconFlight()
            Text(recent.label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TripSummaryCard(trip: BookedTrip, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(trip.route, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${TravelViewModel.prettyDate(trip.departDate)} · ${trip.bookingReference}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatMoney(trip.amount, trip.currency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Rebuild the results Route for a stored search (flight or stay). */
private fun openRecent(backStack: NavBackStack<Route>, recent: RecentSearch) {
    if (recent.vertical == "STAYS") {
        backStack.add(
            Route.StayResults(
                place = recent.origin.orEmpty(),
                checkIn = recent.depart.orEmpty(),
                checkOut = recent.returnDate.orEmpty(),
                rooms = 1,
                adults = recent.adults,
            )
        )
        return
    }
    val origin = recent.origin.orEmpty()
    val destination = recent.destination.orEmpty()
    val depart = recent.depart.orEmpty()
    val slices = if (!recent.returnDate.isNullOrBlank()) {
        "$origin:$destination:$depart,$destination:$origin:${recent.returnDate}"
    } else {
        "$origin:$destination:$depart"
    }
    backStack.add(
        Route.FlightResults(
            slices = slices,
            adults = recent.adults,
            children = "",
            infants = 0,
            cabin = recent.cabin,
            maxConnections = -1,
        )
    )
}
