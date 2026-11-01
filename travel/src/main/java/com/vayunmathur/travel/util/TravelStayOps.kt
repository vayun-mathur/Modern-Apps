package com.vayunmathur.travel.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.data.AirportCatalog
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.TRIP_STATUS_PENDING_PAYMENT
import com.vayunmathur.travel.data.TRIP_TYPE_STAY
import com.vayunmathur.travel.network.StayBookingRequestDto
import com.vayunmathur.travel.network.StayBookingResultDto
import com.vayunmathur.travel.network.StayGuestInputDto
import com.vayunmathur.travel.network.StayRateDto
import com.vayunmathur.travel.network.StaySuggestionDto
import com.vayunmathur.travel.network.StaysApi
import com.vayunmathur.travel.network.TravelApi
import kotlinx.coroutines.launch

fun TravelViewModel.searchStays(
    place: String,
    checkIn: String,
    checkOut: String,
    rooms: Int,
    adults: Int,
    latitude: Double? = null,
    longitude: Double? = null,
) {
    selectedCheckIn = checkIn
    selectedCheckOut = checkOut
    recordRecent(
        RecentSearch(
            vertical = "STAYS",
            label = "$place · ${TravelViewModel.prettyDate(checkIn)}–${TravelViewModel.prettyDate(checkOut)}",
            origin = place,
            depart = checkIn,
            returnDate = checkOut,
            adults = adults,
        )
    )
    stayResultsMutable.value = StaySearchState(loading = true, hasSearched = true)
    viewModelScope.launch {
        runCatching {
            StaysApi.search(
                place,
                checkIn,
                checkOut,
                rooms,
                adults,
                latitude = latitude,
                longitude = longitude
            )
        }
            .onSuccess { stayResultsMutable.value = StaySearchState(results = it, hasSearched = true) }
            .onFailure { stayResultsMutable.value = StaySearchState(error = errorMessage(it), hasSearched = true) }
    }
}

/** Accommodation/location suggestions for the stays search box (offline catalog). */
suspend fun TravelViewModel.staySuggestions(query: String): List<StaySuggestionDto> {
    AirportCatalog.ensureLoaded(getApplication())
    return runCatching { AirportCatalog.staySuggestions(query) }.getOrDefault(emptyList())
}

fun TravelViewModel.loadStayRates(searchResultId: String, accommodationName: String) {
    selectedStayName = accommodationName
    stayRatesMutable.value = StayRatesState(loading = true)
    viewModelScope.launch {
        runCatching { StaysApi.rates(searchResultId) }
            .onSuccess {
                if (it.name.isNotBlank()) selectedStayName = it.name
                stayRatesMutable.value = StayRatesState(rates = it)
            }
            .onFailure { stayRatesMutable.value = StayRatesState(error = errorMessage(it)) }
    }
}

/** Choose a rate and quote it (confirming price) before collecting guests. */
fun TravelViewModel.selectStayRate(rate: StayRateDto) {
    selectedRate = rate
    stayQuote = null
    stayBookingMutable.value = StayBookingState.Idle
    viewModelScope.launch {
        runCatching { StaysApi.quote(rate.id) }
            .onSuccess { stayQuote = it }
            .onFailure { /* fall back to the rate price at book time */ }
    }
}

fun TravelViewModel.selectedStayRate(): StayRateDto? = selectedRate

/** The confirmed quote total, falling back to the selected rate's price. */
fun TravelViewModel.stayTotal(): Pair<String, String> {
    val q = stayQuote
    val r = selectedRate
    return when {
        q != null -> q.totalAmount to q.totalCurrency
        r != null -> r.totalAmount to r.totalCurrency
        else -> "0" to "USD"
    }
}

/**
 * Book a stay: records guests, gets the payment intent, persists a pending
 * `stay` trip immediately, and hands the intent to the UI for the in-app card
 * sheet. After payment the trip is confirmed and polled via the shared trip
 * endpoints.
 */
fun TravelViewModel.bookStay(guest: StayGuestInputDto, email: String, phone: String) {
    val quoteId = stayQuote?.id
    if (quoteId == null) {
        stayBookingMutable.value = StayBookingState.Error("This rate is no longer available. Please pick another.")
        return
    }
    stayBookingMutable.value = StayBookingState.Loading
    viewModelScope.launch {
        runCatching {
            StaysApi.book(
                StayBookingRequestDto(
                    quoteId = quoteId,
                    guests = listOf(guest),
                    email = email,
                    phoneNumber = phone,
                )
            )
        }
            .onSuccess { result ->
                persistStay(result, guest, email, phone)
                stayCheckout = com.vayunmathur.travel.network.CheckoutDto(
                    clientSecret = result.clientSecret,
                    publishableKey = result.publishableKey,
                    quoteId = result.id,
                    totalAmount = result.totalAmount,
                    currency = result.totalCurrency,
                    expiresAt = result.expiresAt,
                )
                stayBookingMutable.value = StayBookingState.Success(result)
            }
            .onFailure { stayBookingMutable.value = StayBookingState.Error(errorMessage(it)) }
    }
}

fun TravelViewModel.resetStayBooking() {
    stayBookingMutable.value = StayBookingState.Idle
}

private suspend fun TravelViewModel.persistStay(
    result: StayBookingResultDto,
    guest: StayGuestInputDto,
    email: String,
    phone: String,
) {
    val name = result.accommodationName.ifBlank { selectedStayName }
    val checkIn = result.checkInDate.ifBlank { selectedCheckIn }
    // Stay bookings don't carry a total; fall back to the confirmed quote.
    val (quoteAmount, quoteCurrency) = stayTotal()
    val amount = result.totalAmount.takeIf { it.isNotBlank() && it != "0" } ?: quoteAmount
    val currency = result.totalCurrency.takeIf { it.isNotBlank() } ?: quoteCurrency
    val passengers = runCatching {
        bookingJson.encodeToString(
            listOf(
                com.vayunmathur.travel.network.PassengerInputDto(
                    givenName = guest.givenName,
                    familyName = guest.familyName,
                    bornOn = guest.bornOn,
                    email = email,
                    phoneNumber = phone,
                )
            )
        )
    }.getOrDefault("")
    repository.upsertBookedTrip(
        BookedTrip(
            orderId = result.id,
            bookingReference = result.reference,
            route = name,
            departDate = checkIn.take(ISO_DATE_LENGTH),
            amount = amount,
            currency = currency,
            passengersJson = passengers,
            status = result.status.ifBlank { TRIP_STATUS_PENDING_PAYMENT },
            type = TRIP_TYPE_STAY,
            awaitingPayment = result.status != "confirmed",
            tripId = result.id,
            checkoutExpiresAt = result.expiresAt,
            lastName = guest.familyName,
        )
    )
    // Prime the shared poll loop with the stay trip id.
    currentTrip = com.vayunmathur.travel.network.TripDto(
        tripId = result.id,
        status = result.status.ifBlank { TRIP_STATUS_PENDING_PAYMENT },
        bookingReference = result.reference,
        totalAmount = amount,
        currency = currency,
    )
    // Refresh the canonical state from the server (same shape as flights).
    runCatching { TravelApi.trip(result.id) }
        .onSuccess { trip ->
            currentTrip = trip
            updateStoredTrip(trip)
        }
}

fun TravelViewModel.clearRecents() {
    viewModelScope.launch { repository.clearRecent() }
}

internal fun TravelViewModel.recordRecent(search: RecentSearch) {
    viewModelScope.launch {
        repository.insertRecent(search)
        repository.trimRecent()
    }
}

/** Length of an ISO `YYYY-MM-DD` date prefix. */
private const val ISO_DATE_LENGTH = 10
