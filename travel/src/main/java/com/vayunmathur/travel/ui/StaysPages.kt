package com.vayunmathur.travel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.StayRateDto
import com.vayunmathur.travel.network.StaySearchResultDto
import com.vayunmathur.travel.network.StayGuestInputDto
import com.vayunmathur.travel.util.StayBookingState
import com.vayunmathur.travel.util.TravelViewModel

/**
 * Hotel search form (used from Home when the "Stays" product is selected):
 * location autocomplete, check-in/out dates, rooms and guests.
 */
@Composable
fun StaySearchForm(
    viewModel: TravelViewModel,
    modifier: Modifier = Modifier,
    onSearch: (place: String, checkIn: String, checkOut: String, rooms: Int, adults: Int) -> Unit,
) {
    var place by remember { mutableStateOf("") }
    var checkIn by remember { mutableStateOf("") }
    var checkOut by remember { mutableStateOf("") }
    var rooms by remember { mutableIntStateOf(1) }
    var adults by remember { mutableIntStateOf(2) }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlaceAutocompleteField("Destination", viewModel, onCodeChange = { place = it })
        DateField("Check-in", checkIn, onDate = { checkIn = it })
        DateField("Check-out", checkOut, onDate = { checkOut = it })
        CountStepper("Rooms", rooms, onCount = { rooms = it }, min = 1, max = 5)
        CountStepper("Guests", adults, onCount = { adults = it }, min = 1, max = 9)
        Button(
            onClick = { onSearch(place, checkIn, checkOut, rooms, adults) },
            enabled = place.isNotBlank() && checkIn.isNotBlank() && checkOut.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Search hotels") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayResultsPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.StayResults,
) {
    val state by viewModel.stayResults.collectAsStateWithLifecycle()
    LaunchedEffect(route) {
        viewModel.searchStays(route.place, route.checkIn, route.checkOut, route.rooms, route.adults)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hotels in ${route.place}") },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            items(state.results) { result ->
                StayResultCard(result) {
                    backStack.add(Route.StayDetail(result.id, result.name))
                }
            }
            if (state.loading || state.error != null || (state.hasSearched && state.results.isEmpty())) {
                item {
                    StatusBox(
                        loading = state.loading,
                        error = state.error,
                        isEmpty = state.hasSearched && state.results.isEmpty(),
                        emptyMessage = "No hotels found for those dates.",
                    )
                }
            }
        }
    }
}

@Composable
private fun StayResultCard(result: StaySearchResultDto, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onClick() },
    ) {
        Column {
            if (result.photoUrl.isNotBlank()) {
                AsyncImage(
                    model = result.photoUrl,
                    contentDescription = result.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(result.name.ifBlank { "Hotel" }, style = MaterialTheme.typography.titleMedium)
                    Text(starLabel(result.rating, result.reviewScore), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (result.address.isNotBlank()) {
                        Text(result.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("from", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatMoney(result.cheapestAmount, result.cheapestCurrency),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayDetailPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.StayDetail,
) {
    val state by viewModel.stayRates.collectAsStateWithLifecycle()
    LaunchedEffect(route.searchResultId) { viewModel.loadStayRates(route.searchResultId, route.name) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(route.name.ifBlank { "Hotel" }) },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val rates = state.rates
        if (rates == null) {
            StatusBox(loading = state.loading, error = state.error, isEmpty = !state.loading, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            rates.photos.firstOrNull()?.let { photo ->
                AsyncImage(
                    model = photo,
                    contentDescription = rates.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(rates.name.ifBlank { "Hotel" }, style = MaterialTheme.typography.headlineSmall)
                Text(starLabel(rates.rating, rates.reviewScore), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rates.address.isNotBlank()) {
                    Text(rates.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (rates.amenities.isNotEmpty()) {
                    Text(
                        rates.amenities.take(8).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            SectionHeader("Rooms")
            rates.rooms.forEach { room ->
                Text(
                    room.name.ifBlank { "Room" },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                room.rates.forEach { rate ->
                    RateRow(rate) {
                        viewModel.selectStayRate(rate)
                        backStack.add(Route.StayGuests)
                    }
                }
            }
            Column(Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

@Composable
private fun RateRow(rate: StayRateDto, onSelect: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onSelect() },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rate.boardType.ifBlank { "Room only" }.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodyLarge)
                Text(rate.cancellation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                formatMoney(rate.totalAmount, rate.totalCurrency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayGuestsPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
) {
    val booking by viewModel.stayBooking.collectAsStateWithLifecycle()
    var givenName by remember { mutableStateOf("") }
    var familyName by remember { mutableStateOf("") }
    var bornOn by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val (amount, currency) = viewModel.stayTotal()

    LaunchedEffect(booking) {
        val b = booking
        if (b is StayBookingState.Success) {
            viewModel.resetStayBooking()
            backStack.reset(Route.Home, Route.StayConfirmation(b.result.id))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Guest details") },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val loading = booking is StayBookingState.Loading
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Total", style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatMoney(amount, currency),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            OutlinedTextField(givenName, { givenName = it }, label = { Text("Given name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(familyName, { familyName = it }, label = { Text("Family name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DateField("Date of birth", bornOn, onDate = { bornOn = it }, dateFormat = "MMM d, yyyy")
            OutlinedTextField(
                email, { email = it }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                phone, { phone = it }, label = { Text("Phone (e.g. +14155550123)") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            (booking as? StayBookingState.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Sandbox booking — paid with a Duffel test balance. No card is charged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { viewModel.bookStay(StayGuestInputDto(givenName, familyName, bornOn), email, phone) },
                enabled = !loading && givenName.isNotBlank() && familyName.isNotBlank() && bornOn.isNotBlank() && email.contains("@") && phone.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Booking…")
                } else {
                    Text("Book with test balance")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayConfirmationPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.StayConfirmation,
) {
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    val trip = trips.find { it.orderId == route.bookingId }

    Scaffold(topBar = { TopAppBar(title = { Text("Booking confirmed") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp).padding(top = 8.dp),
            )
            Text("You're booked!", style = MaterialTheme.typography.headlineSmall)
            if (trip != null) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StayDetailRow("Reference", trip.bookingReference, emphasize = true)
                        HorizontalDivider()
                        StayDetailRow("Hotel", trip.route)
                        StayDetailRow("Check-in", TravelViewModel.prettyDate(trip.departDate))
                        StayDetailRow("Amount", formatMoney(trip.amount, trip.currency))
                    }
                }
            }
            Button(onClick = { backStack.reset(Route.Home) }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun StayDetailRow(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** "★★★★★ · 8.7/10" from a star rating + review score (either may be absent). */
private fun starLabel(rating: Long, reviewScore: Double): String {
    val stars = if (rating in 1..5) "★".repeat(rating.toInt()) else ""
    val score = if (reviewScore > 0) "${"%.1f".format(reviewScore)}/10" else ""
    return listOf(stars, score).filter { it.isNotBlank() }.joinToString(" · ")
}
