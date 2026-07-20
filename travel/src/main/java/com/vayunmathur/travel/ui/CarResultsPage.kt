package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.Favorite
import com.vayunmathur.travel.data.Vertical
import com.vayunmathur.travel.network.CarDto
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.openBooking

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarResultsPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.CarResults,
) {
    val context = LocalContext.current
    val state by viewModel.cars.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    LaunchedEffect(route) {
        viewModel.searchCars(route.location, route.pickup, route.dropoff)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(route.location) },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            item {
                OutlinedCard(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        "Car-rental price coverage is limited. Tap \"Book\" to see live prices " +
                            "and availability on the provider's site.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(state.results) { car ->
                val fav = favorites.any { it.bookingUrl == car.bookingUrl }
                ResultCard(
                    title = car.name,
                    subtitle = car.provider,
                    price = car.price,
                    currency = car.currency,
                    isFavorite = fav,
                    onFavorite = { viewModel.toggleFavorite(car.toFavorite()) },
                    onBook = { openBooking(context, car.bookingUrl) },
                )
            }
            if (state.loading || state.error != null || (state.hasSearched && state.results.isEmpty())) {
                item {
                    StatusBox(
                        loading = state.loading,
                        error = state.error,
                        isEmpty = state.hasSearched && state.results.isEmpty(),
                    )
                }
            }
        }
    }
}

private fun CarDto.toFavorite() = Favorite(
    bookingUrl = bookingUrl,
    vertical = Vertical.CARS.name,
    title = name,
    subtitle = provider,
    price = price,
    currency = currency,
)
