package com.vayunmathur.travel.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.BookedTripDao
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.RecentSearchDao
import com.vayunmathur.travel.data.Vertical
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OrderRequestDto
import com.vayunmathur.travel.network.OrderResultDto
import com.vayunmathur.travel.network.PassengerInputDto
import com.vayunmathur.travel.network.PaymentInputDto
import com.vayunmathur.travel.network.PlaceDto
import com.vayunmathur.travel.network.TravelApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Search state for the flight results list. */
data class SearchUiState<T>(
    val loading: Boolean = false,
    val error: String? = null,
    val results: List<T> = emptyList(),
    val hasSearched: Boolean = false,
)

/** State for the single-offer review (re-price) screen. */
data class OfferReviewState(
    val loading: Boolean = false,
    val error: String? = null,
    val offer: OfferDto? = null,
)

/** Booking lifecycle for the payment/confirmation flow. */
sealed interface BookingState {
    data object Idle : BookingState
    data object Loading : BookingState
    data class Success(val result: OrderResultDto) : BookingState
    data class Error(val message: String) : BookingState
}

/**
 * The single hub for the Travel app: owns flight search + the
 * offer→passengers→order booking flow (calling [TravelApi] and exposing
 * loading/error state as [StateFlow]), persists recent searches, and stores
 * booked trips through the Room [RecentSearchDao] / [BookedTripDao].
 */
class TravelViewModel(
    application: Application,
    private val recentSearchDao: RecentSearchDao,
    private val bookedTripDao: BookedTripDao,
) : AndroidViewModel(application) {

    private val _flights = MutableStateFlow(SearchUiState<OfferDto>())
    val flights: StateFlow<SearchUiState<OfferDto>> = _flights.asStateFlow()

    private val _review = MutableStateFlow(OfferReviewState())
    val review: StateFlow<OfferReviewState> = _review.asStateFlow()

    private val _passengers = MutableStateFlow<List<PassengerInputDto>>(emptyList())
    val passengers: StateFlow<List<PassengerInputDto>> = _passengers.asStateFlow()

    private val _booking = MutableStateFlow<BookingState>(BookingState.Idle)
    val booking: StateFlow<BookingState> = _booking.asStateFlow()

    val recentSearches: StateFlow<List<RecentSearch>> = recentSearchDao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookedTrips: StateFlow<List<BookedTrip>> = bookedTripDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Autocomplete -----------------------------------------------------

    /** Airport/city suggestions for [query]; never throws (empty on failure). */
    suspend fun autocomplete(query: String): List<PlaceDto> =
        runCatching { TravelApi.places(query) }.getOrDefault(emptyList())

    // --- Search -----------------------------------------------------------

    fun searchFlights(
        origin: String,
        destination: String,
        depart: String,
        ret: String?,
        adults: Int,
        cabin: String = "economy",
    ) {
        recordRecent(
            RecentSearch(
                vertical = Vertical.FLIGHTS.name,
                label = "$origin → $destination · ${prettyDate(depart)}",
                origin = origin,
                destination = destination,
                depart = depart,
                returnDate = ret,
                adults = adults,
                cabin = cabin,
            )
        )
        _flights.value = SearchUiState(loading = true, hasSearched = true)
        viewModelScope.launch {
            runCatching { TravelApi.flights(origin, destination, depart, ret, adults, cabin) }
                .onSuccess { _flights.value = SearchUiState(results = it.offers, hasSearched = true) }
                .onFailure { _flights.value = SearchUiState(error = errorMessage(it), hasSearched = true) }
        }
    }

    // --- Offer review / re-price -----------------------------------------

    /** Seed the review screen with the tapped offer. */
    fun selectOffer(offer: OfferDto) {
        _review.value = OfferReviewState(offer = offer)
    }

    /** Re-price the chosen offer right before booking (offers expire). */
    fun refreshOffer(offerId: String) {
        _review.value = _review.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { TravelApi.offer(offerId) }
                .onSuccess { _review.value = OfferReviewState(offer = it) }
                .onFailure {
                    // Keep the previously selected offer so the user can still proceed.
                    _review.value = _review.value.copy(loading = false, error = errorMessage(it))
                }
        }
    }

    // --- Passengers -------------------------------------------------------

    /**
     * Initialize one blank passenger per Duffel passenger id on the offer,
     * unless a matching set is already present (so returning to the form keeps
     * entered values).
     */
    fun initPassengers(offer: OfferDto) {
        val ids = offer.passengerIds.ifEmpty { listOf("") }
        if (_passengers.value.map { it.id } == ids) return
        _passengers.value = ids.map { PassengerInputDto(id = it) }
    }

    fun updatePassenger(index: Int, passenger: PassengerInputDto) {
        _passengers.value = _passengers.value.toMutableList().also {
            if (index in it.indices) it[index] = passenger
        }
    }

    // --- Booking ----------------------------------------------------------

    /** Book the reviewed offer with the collected passengers via test balance. */
    fun createOrder() {
        val offer = _review.value.offer ?: return
        val pax = _passengers.value
        _booking.value = BookingState.Loading
        viewModelScope.launch {
            runCatching {
                TravelApi.createOrder(
                    OrderRequestDto(
                        offerId = offer.offerId,
                        passengers = pax,
                        payment = PaymentInputDto(type = "balance"),
                    )
                )
            }
                .onSuccess { result ->
                    persistTrip(offer, pax, result)
                    _booking.value = BookingState.Success(result)
                }
                .onFailure { _booking.value = BookingState.Error(errorMessage(it)) }
        }
    }

    fun resetBooking() {
        _booking.value = BookingState.Idle
    }

    fun tripById(orderId: String): BookedTrip? = bookedTrips.value.find { it.orderId == orderId }

    private suspend fun persistTrip(
        offer: OfferDto,
        passengers: List<PassengerInputDto>,
        result: OrderResultDto,
    ) {
        val first = offer.slices.firstOrNull()
        val roundTrip = offer.slices.size > 1
        val route = if (first == null) {
            ""
        } else {
            val base = "${first.origin} → ${first.destination}"
            if (roundTrip) "$base (round trip)" else base
        }
        bookedTripDao.upsert(
            BookedTrip(
                orderId = result.orderId,
                bookingReference = result.bookingReference,
                route = route,
                departDate = first?.departureAt?.take(10).orEmpty(),
                amount = result.totalAmount,
                currency = result.currency,
                passengersJson = runCatching { json.encodeToString(passengers) }.getOrDefault(""),
            )
        )
    }

    // --- Recents ----------------------------------------------------------

    fun clearRecents() {
        viewModelScope.launch { recentSearchDao.clear() }
    }

    private fun recordRecent(search: RecentSearch) {
        viewModelScope.launch {
            recentSearchDao.insert(search)
            recentSearchDao.trim()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** "2026-09-01" -> "Sep 1"; falls back to the raw string on any parse error. */
        fun prettyDate(iso: String): String = runCatching {
            val date = java.time.LocalDate.parse(iso.take(10))
            date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        }.getOrDefault(iso)

        private fun errorMessage(t: Throwable): String =
            t.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."
    }
}

class TravelViewModelFactory(
    private val application: Application,
    private val recentSearchDao: RecentSearchDao,
    private val bookedTripDao: BookedTripDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TravelViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return TravelViewModel(application, recentSearchDao, bookedTripDao) as T
    }
}
