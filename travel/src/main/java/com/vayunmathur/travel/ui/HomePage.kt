package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.Favorite
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.Vertical
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.openBooking

private enum class Tab(val label: String) {
    FLIGHTS("Flights"), HOTELS("Hotels"), CARS("Cars"), TRAINS("Trains")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(backStack: NavBackStack<Route>, viewModel: TravelViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by remember { mutableStateOf(Tab.FLIGHTS) }
    val recents by viewModel.recentSearches.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Travel") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Tab.entries.forEachIndexed { index, t ->
                    SegmentedButton(
                        selected = tab == t,
                        onClick = { tab = t },
                        shape = SegmentedButtonDefaults.itemShape(index, Tab.entries.size),
                        label = { Text(t.label) },
                    )
                }
            }

            when (tab) {
                Tab.FLIGHTS -> FlightSearchForm(viewModel) { origin, destination, depart, ret, adults ->
                    backStack.add(Route.FlightResults(origin, destination, depart, ret, adults))
                }
                Tab.HOTELS -> HotelSearchForm(viewModel) { location, checkin, checkout, adults ->
                    backStack.add(Route.HotelResults(location, checkin, checkout, adults))
                }
                Tab.CARS -> CarSearchForm(viewModel) { location, pickup, dropoff ->
                    backStack.add(Route.CarResults(location, pickup, dropoff))
                }
                Tab.TRAINS -> TrainsComingSoon()
            }

            if (recents.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionHeader("Recent searches")
                    TextButton(onClick = { viewModel.clearRecents() }) { Text("Clear") }
                }
                recents.forEach { recent ->
                    RecentSearchCard(recent) { openRecent(backStack, recent) }
                }
            }

            if (favorites.isNotEmpty()) {
                SectionHeader("Saved")
                favorites.forEach { favorite ->
                    FavoriteCard(
                        favorite = favorite,
                        onOpen = { openBooking(context, favorite.bookingUrl) },
                        onRemove = { viewModel.removeFavorite(favorite) },
                    )
                }
            }

            Column(Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

@Composable
private fun RecentSearchCard(recent: RecentSearch, onClick: () -> Unit) {
    val vertical = runCatching { Vertical.valueOf(recent.vertical) }.getOrNull()
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
            if (vertical != null) Icon(verticalIcon(vertical), contentDescription = null)
            Text(recent.label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun FavoriteCard(favorite: Favorite, onOpen: () -> Unit, onRemove: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(favorite.title, style = MaterialTheme.typography.titleMedium)
                if (favorite.subtitle.isNotBlank()) {
                    Text(
                        favorite.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                formatPrice(favorite.price, favorite.currency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Favorite, contentDescription = "Remove", tint = MaterialTheme.colorScheme.tertiary)
            }
            TextButton(onClick = onOpen) { Text("Book") }
        }
    }
}

/** Rebuild the results Route for a stored search and navigate to it. */
private fun openRecent(backStack: NavBackStack<Route>, recent: RecentSearch) {
    when (runCatching { Vertical.valueOf(recent.vertical) }.getOrNull()) {
        Vertical.FLIGHTS -> backStack.add(
            Route.FlightResults(
                origin = recent.origin.orEmpty(),
                destination = recent.destination.orEmpty(),
                depart = recent.depart.orEmpty(),
                returnDate = recent.returnDate,
                adults = recent.adults,
            )
        )
        Vertical.HOTELS -> backStack.add(
            Route.HotelResults(
                location = recent.location.orEmpty(),
                checkin = recent.checkin.orEmpty(),
                checkout = recent.checkout.orEmpty(),
                adults = recent.adults,
            )
        )
        Vertical.CARS -> backStack.add(
            Route.CarResults(
                location = recent.location.orEmpty(),
                pickup = recent.pickup.orEmpty(),
                dropoff = recent.dropoff.orEmpty(),
            )
        )
        null -> {}
    }
}
