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
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.Favorite
import com.vayunmathur.travel.data.Vertical
import com.vayunmathur.travel.network.HotelDto
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.openBooking

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotelResultsPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.HotelResults,
) {
    val context = LocalContext.current
    val state by viewModel.hotels.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    LaunchedEffect(route) {
        viewModel.searchHotels(route.location, route.checkin, route.checkout, route.adults)
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
            items(state.results) { hotel ->
                val fav = favorites.any { it.bookingUrl == hotel.bookingUrl }
                ResultCard(
                    title = hotel.name,
                    subtitle = hotel.location,
                    price = hotel.price,
                    currency = hotel.currency,
                    isFavorite = fav,
                    onFavorite = { viewModel.toggleFavorite(hotel.toFavorite()) },
                    onBook = { openBooking(context, hotel.bookingUrl) },
                    extra = { StarRow(hotel.stars) },
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

private fun HotelDto.toFavorite() = Favorite(
    bookingUrl = bookingUrl,
    vertical = Vertical.HOTELS.name,
    title = name,
    subtitle = location,
    price = price,
    currency = currency,
)
