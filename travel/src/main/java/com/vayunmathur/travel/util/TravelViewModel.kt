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
import com.vayunmathur.travel.network.CancellationDto
import com.vayunmathur.travel.network.ChangeOfferDto
import com.vayunmathur.travel.network.ChangeRequestInputDto
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.OrderRequestDto
import com.vayunmathur.travel.network.OrderResultDto
import com.vayunmathur.travel.network.PassengerInputDto
import com.vayunmathur.travel.network.PaymentInputDto
import com.vayunmathur.travel.network.PlaceDto
import com.vayunmathur.travel.network.SearchSliceInputDto
import com.vayunmathur.travel.network.SeatCabinDto
import com.vayunmathur.travel.network.SeatDto
import com.vayunmathur.travel.network.ServiceSelectionDto
import com.vayunmathur.travel.network.StayBookingRequestDto
import com.vayunmathur.travel.network.StayBookingResultDto
import com.vayunmathur.travel.network.StayGuestInputDto
import com.vayunmathur.travel.network.StayQuoteDto
import com.vayunmathur.travel.network.StayRateDto
import com.vayunmathur.travel.network.StayRatesDto
import com.vayunmathur.travel.network.StaySearchResultDto
import com.vayunmathur.travel.network.StaysApi
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

/** A normalized flight query, carried by the results route and re-used for re-sort. */
data class FlightQuery(
    val slices: String,
    val adults: Int = 1,
    val children: String = "",
    val infants: Int = 0,
    val cabin: String = "economy",
    val maxConnections: Int = -1,
)

/** Server-side sort options for the offer list. */
enum class OfferSort(val key: String?, val label: String) {
    BEST(null, "Best"),
    CHEAPEST("total_amount", "Cheapest"),
    FASTEST("total_duration", "Fastest"),
}

/** Client-side filters applied over the fetched offers. */
data class FlightFilters(
    val maxStops: Int? = null,
    val airlines: Set<String> = emptySet(),
)

/** State for the flight results screen: offers plus sort/filter controls. */
data class FlightResultsState(
    val loading: Boolean = false,
    val error: String? = null,
    val hasSearched: Boolean = false,
    val offerRequestId: String = "",
    val allOffers: List<OfferDto> = emptyList(),
    val sort: OfferSort = OfferSort.BEST,
    val filters: FlightFilters = FlightFilters(),
) {
    /** Offers after applying the client-side filters. */
    val visibleOffers: List<OfferDto>
        get() = allOffers.filter { offer ->
            (filters.maxStops == null || offer.slices.all { it.stops <= filters.maxStops }) &&
                (filters.airlines.isEmpty() || offer.airlineIatas.any { it in filters.airlines })
        }

    /** Distinct airline IATA codes present in the results, for the filter UI. */
    val availableAirlines: List<String>
        get() = allOffers.flatMap { it.airlineIatas }.distinct().sorted()
}

/** State for the single-offer review (re-price) screen. */
data class OfferReviewState(
    val loading: Boolean = false,
    val error: String? = null,
    val offer: OfferDto? = null,
)

/** State for the seat-map screen. */
data class SeatMapState(
    val loading: Boolean = false,
    val error: String? = null,
    val cabins: List<SeatCabinDto> = emptyList(),
)

/** Booking lifecycle for the payment/confirmation flow. */
sealed interface BookingState {
    data object Idle : BookingState
    data object Loading : BookingState
    data class Success(val result: OrderResultDto) : BookingState
    data class Error(val message: String) : BookingState
}

/** Lifecycle for the pay-later action on a hold order. */
sealed interface PaymentActionState {
    data object Idle : PaymentActionState
    data object Loading : PaymentActionState
    data object Success : PaymentActionState
    data class Error(val message: String) : PaymentActionState
}

/** State for the remote Trips sync (order list). */
data class RemoteOrdersState(
    val loading: Boolean = false,
    val error: String? = null,
    val orders: List<OrderDetailDto> = emptyList(),
)

/** State for a single remote order-detail screen. */
data class OrderDetailState(
    val loading: Boolean = false,
    val error: String? = null,
    val order: OrderDetailDto? = null,
)

/** State for the cancellation flow (quote → confirm). */
data class CancellationState(
    val loading: Boolean = false,
    val error: String? = null,
    val quote: CancellationDto? = null,
    val confirming: Boolean = false,
    val done: Boolean = false,
)

/** State for the change flow (request → offers → confirm). */
data class ChangeState(
    val loading: Boolean = false,
    val error: String? = null,
    val requested: Boolean = false,
    val offers: List<ChangeOfferDto> = emptyList(),
    val confirming: Boolean = false,
    val done: Boolean = false,
)

/** State for the stays search results. */
data class StaySearchState(
    val loading: Boolean = false,
    val error: String? = null,
    val results: List<StaySearchResultDto> = emptyList(),
    val hasSearched: Boolean = false,
)

/** State for the accommodation rates screen. */
data class StayRatesState(
    val loading: Boolean = false,
    val error: String? = null,
    val rates: StayRatesDto? = null,
)

/** Booking lifecycle for a stay. */
sealed interface StayBookingState {
    data object Idle : StayBookingState
    data object Loading : StayBookingState
    data class Success(val result: StayBookingResultDto) : StayBookingState
    data class Error(val message: String) : StayBookingState
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

    private val _flights = MutableStateFlow(FlightResultsState())
    val flights: StateFlow<FlightResultsState> = _flights.asStateFlow()

    /** The query backing the current results, kept so re-sort can re-fetch. */
    private var currentQuery: FlightQuery? = null

    private val _review = MutableStateFlow(OfferReviewState())
    val review: StateFlow<OfferReviewState> = _review.asStateFlow()

    private val _passengers = MutableStateFlow<List<PassengerInputDto>>(emptyList())
    val passengers: StateFlow<List<PassengerInputDto>> = _passengers.asStateFlow()

    private val _seatMap = MutableStateFlow(SeatMapState())
    val seatMap: StateFlow<SeatMapState> = _seatMap.asStateFlow()

    /** Selected extra-baggage services: service id → quantity. */
    private val _selectedBaggage = MutableStateFlow<Map<String, Long>>(emptyMap())
    val selectedBaggage: StateFlow<Map<String, Long>> = _selectedBaggage.asStateFlow()

    /** Selected seats keyed by `"segmentId|designator"`. */
    private val _selectedSeats = MutableStateFlow<Map<String, SeatDto>>(emptyMap())
    val selectedSeats: StateFlow<Map<String, SeatDto>> = _selectedSeats.asStateFlow()

    private val _booking = MutableStateFlow<BookingState>(BookingState.Idle)
    val booking: StateFlow<BookingState> = _booking.asStateFlow()

    private val _payment = MutableStateFlow<PaymentActionState>(PaymentActionState.Idle)
    val payment: StateFlow<PaymentActionState> = _payment.asStateFlow()

    private val _remoteOrders = MutableStateFlow(RemoteOrdersState())
    val remoteOrders: StateFlow<RemoteOrdersState> = _remoteOrders.asStateFlow()

    private val _orderDetail = MutableStateFlow(OrderDetailState())
    val orderDetail: StateFlow<OrderDetailState> = _orderDetail.asStateFlow()

    private val _cancellation = MutableStateFlow(CancellationState())
    val cancellation: StateFlow<CancellationState> = _cancellation.asStateFlow()

    private val _change = MutableStateFlow(ChangeState())
    val change: StateFlow<ChangeState> = _change.asStateFlow()

    private val _stayResults = MutableStateFlow(StaySearchState())
    val stayResults: StateFlow<StaySearchState> = _stayResults.asStateFlow()

    private val _stayRates = MutableStateFlow(StayRatesState())
    val stayRates: StateFlow<StayRatesState> = _stayRates.asStateFlow()

    private val _stayBooking = MutableStateFlow<StayBookingState>(StayBookingState.Idle)
    val stayBooking: StateFlow<StayBookingState> = _stayBooking.asStateFlow()

    /** The rate the user chose to book, plus context for persistence. */
    private var selectedRate: StayRateDto? = null
    private var selectedStayName: String = ""
    private var selectedCheckIn: String = ""
    private var selectedCheckOut: String = ""
    private var stayQuote: StayQuoteDto? = null

    val recentSearches: StateFlow<List<RecentSearch>> = recentSearchDao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookedTrips: StateFlow<List<BookedTrip>> = bookedTripDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Autocomplete -----------------------------------------------------

    /** Airport/city suggestions for [query]; never throws (empty on failure). */
    suspend fun autocomplete(query: String): List<PlaceDto> =
        runCatching { TravelApi.places(query) }.getOrDefault(emptyList())

    // --- Search -----------------------------------------------------------

    fun searchFlights(query: FlightQuery) {
        currentQuery = query
        val firstSlice = query.slices.substringBefore(',')
        val parts = firstSlice.split(':')
        val origin = parts.getOrNull(0).orEmpty()
        val destination = parts.getOrNull(1).orEmpty()
        val depart = parts.getOrNull(2).orEmpty()
        val multiCity = query.slices.contains(',') && query.slices.substringAfter(',').isNotBlank()
        recordRecent(
            RecentSearch(
                vertical = Vertical.FLIGHTS.name,
                label = if (multiCity) {
                    "$origin → … · multi-city"
                } else {
                    "$origin → $destination · ${prettyDate(depart)}"
                },
                origin = origin,
                destination = destination,
                depart = depart,
                returnDate = null,
                adults = query.adults,
                cabin = query.cabin,
            )
        )
        _flights.value = FlightResultsState(loading = true, hasSearched = true)
        viewModelScope.launch {
            runCatching {
                TravelApi.flights(
                    slices = query.slices,
                    adults = query.adults,
                    children = query.children,
                    infants = query.infants,
                    cabin = query.cabin,
                    maxConnections = query.maxConnections,
                )
            }
                .onSuccess {
                    _flights.value = FlightResultsState(
                        hasSearched = true,
                        offerRequestId = it.offerRequestId,
                        allOffers = it.offers,
                    )
                }
                .onFailure {
                    _flights.value = FlightResultsState(error = errorMessage(it), hasSearched = true)
                }
        }
    }

    /** Change the server-side sort, re-fetching the offer list for the request. */
    fun setSort(sort: OfferSort) {
        val state = _flights.value
        if (state.sort == sort || state.offerRequestId.isBlank()) {
            _flights.value = state.copy(sort = sort)
            return
        }
        _flights.value = state.copy(sort = sort, loading = true, error = null)
        val requestId = state.offerRequestId
        val maxConnections = currentQuery?.maxConnections ?: -1
        viewModelScope.launch {
            runCatching { TravelApi.offers(requestId, sort.key, maxConnections) }
                .onSuccess { offers ->
                    _flights.value = _flights.value.copy(loading = false, allOffers = offers)
                }
                .onFailure {
                    _flights.value = _flights.value.copy(loading = false, error = errorMessage(it))
                }
        }
    }

    fun setMaxStopsFilter(maxStops: Int?) {
        _flights.value = _flights.value.copy(
            filters = _flights.value.filters.copy(maxStops = maxStops),
        )
    }

    fun toggleAirlineFilter(iata: String) {
        val current = _flights.value.filters.airlines
        val next = if (iata in current) current - iata else current + iata
        _flights.value = _flights.value.copy(filters = _flights.value.filters.copy(airlines = next))
    }

    fun clearFilters() {
        _flights.value = _flights.value.copy(filters = FlightFilters())
    }

    // --- Offer review / re-price -----------------------------------------

    /** Seed the review screen with the tapped offer; clear any prior ancillaries. */
    fun selectOffer(offer: OfferDto) {
        _review.value = OfferReviewState(offer = offer)
        _selectedBaggage.value = emptyMap()
        _selectedSeats.value = emptyMap()
        _seatMap.value = SeatMapState()
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

    // --- Ancillaries (baggage + seats) -----------------------------------

    /** Set the selected quantity for an extra-baggage service (0 removes it). */
    fun setBaggageQuantity(serviceId: String, quantity: Long) {
        _selectedBaggage.value = _selectedBaggage.value.toMutableMap().also {
            if (quantity <= 0) it.remove(serviceId) else it[serviceId] = quantity
        }
    }

    /** Fetch seat maps for the given offer. */
    fun loadSeatMaps(offerId: String) {
        _seatMap.value = SeatMapState(loading = true)
        viewModelScope.launch {
            runCatching { TravelApi.seatMap(offerId) }
                .onSuccess { _seatMap.value = SeatMapState(cabins = it) }
                .onFailure { _seatMap.value = SeatMapState(error = errorMessage(it)) }
        }
    }

    /** Toggle selection of a (selectable) seat on a segment. */
    fun toggleSeat(segmentId: String, seat: SeatDto) {
        if (!seat.available || seat.serviceId == null) return
        val key = "$segmentId|${seat.designator}"
        _selectedSeats.value = _selectedSeats.value.toMutableMap().also {
            if (it.containsKey(key)) it.remove(key) else it[key] = seat
        }
    }

    /** Combined selected services (baggage + seats) for the order body. */
    private fun selectedServices(): List<ServiceSelectionDto> {
        val bags = _selectedBaggage.value.map { (id, qty) -> ServiceSelectionDto(id = id, quantity = qty) }
        val seats = _selectedSeats.value.values.mapNotNull { seat ->
            seat.serviceId?.let { ServiceSelectionDto(id = it, quantity = 1) }
        }
        return bags + seats
    }

    // --- Booking ----------------------------------------------------------

    /** Book the reviewed offer with the collected passengers via test balance. */
    fun createOrder(hold: Boolean = false) {
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
                        services = selectedServices(),
                        hold = hold,
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

    /** Settle a hold order later; updates the stored trip on success. */
    fun payOrder(orderId: String) {
        _payment.value = PaymentActionState.Loading
        viewModelScope.launch {
            runCatching { TravelApi.payOrder(orderId) }
                .onSuccess { result ->
                    bookedTripDao.byId(orderId)?.let { trip ->
                        bookedTripDao.upsert(
                            trip.copy(
                                awaitingPayment = result.awaitingPayment,
                                paymentRequiredBy = result.paymentRequiredBy.orEmpty(),
                                amount = result.totalAmount,
                                currency = result.currency,
                            )
                        )
                    }
                    _payment.value = PaymentActionState.Success
                }
                .onFailure { _payment.value = PaymentActionState.Error(errorMessage(it)) }
        }
    }

    fun resetPaymentAction() {
        _payment.value = PaymentActionState.Idle
    }

    // --- Remote order sync -----------------------------------------------

    /** Fetch the account's remote orders for the Trips hub. */
    fun loadRemoteOrders() {
        _remoteOrders.value = RemoteOrdersState(loading = true)
        viewModelScope.launch {
            runCatching { TravelApi.listOrders() }
                .onSuccess { _remoteOrders.value = RemoteOrdersState(orders = it) }
                .onFailure { _remoteOrders.value = RemoteOrdersState(error = errorMessage(it)) }
        }
    }

    /** Fetch full detail for a single remote order. */
    fun loadOrderDetail(orderId: String) {
        _orderDetail.value = OrderDetailState(loading = true)
        viewModelScope.launch {
            runCatching { TravelApi.orderDetail(orderId) }
                .onSuccess { _orderDetail.value = OrderDetailState(order = it) }
                .onFailure { _orderDetail.value = OrderDetailState(error = errorMessage(it)) }
        }
    }

    // --- Cancellation -----------------------------------------------------

    fun resetCancellation() {
        _cancellation.value = CancellationState()
    }

    /** Fetch a refund quote for cancelling [orderId]. */
    fun quoteCancellation(orderId: String) {
        _cancellation.value = CancellationState(loading = true)
        viewModelScope.launch {
            runCatching { TravelApi.cancelQuote(orderId) }
                .onSuccess { _cancellation.value = CancellationState(quote = it) }
                .onFailure { _cancellation.value = CancellationState(error = errorMessage(it)) }
        }
    }

    /** Confirm the pending cancellation and mark the local trip cancelled. */
    fun confirmCancellation(orderId: String) {
        val quote = _cancellation.value.quote ?: return
        _cancellation.value = _cancellation.value.copy(confirming = true, error = null)
        viewModelScope.launch {
            runCatching { TravelApi.confirmCancellation(quote.id) }
                .onSuccess {
                    bookedTripDao.byId(orderId)?.let {
                        bookedTripDao.upsert(it.copy(status = "cancelled", awaitingPayment = false))
                    }
                    _cancellation.value = _cancellation.value.copy(confirming = false, done = true)
                }
                .onFailure {
                    _cancellation.value = _cancellation.value.copy(confirming = false, error = errorMessage(it))
                }
        }
    }

    // --- Change -----------------------------------------------------------

    fun resetChange() {
        _change.value = ChangeState()
    }

    /** Request a change: remove [removeSliceId] and add a slice on [newDate]. */
    fun requestChange(
        orderId: String,
        removeSliceId: String,
        origin: String,
        destination: String,
        newDate: String,
        cabin: String,
    ) {
        _change.value = ChangeState(loading = true)
        viewModelScope.launch {
            runCatching {
                TravelApi.changeRequest(
                    orderId,
                    ChangeRequestInputDto(
                        removeSliceIds = listOf(removeSliceId),
                        add = listOf(SearchSliceInputDto(origin = origin, destination = destination, date = newDate)),
                        cabin = cabin.ifBlank { null },
                    ),
                )
            }
                .onSuccess { _change.value = ChangeState(requested = true, offers = it.offers) }
                .onFailure { _change.value = ChangeState(requested = true, error = errorMessage(it)) }
        }
    }

    /** Accept a change offer, settling any difference via balance. */
    fun confirmChange(orderId: String, offerId: String) {
        _change.value = _change.value.copy(confirming = true, error = null)
        viewModelScope.launch {
            runCatching { TravelApi.confirmChange(offerId) }
                .onSuccess { result ->
                    bookedTripDao.byId(orderId)?.let {
                        bookedTripDao.upsert(it.copy(amount = result.totalAmount, currency = result.currency))
                    }
                    _change.value = _change.value.copy(confirming = false, done = true)
                }
                .onFailure {
                    _change.value = _change.value.copy(confirming = false, error = errorMessage(it))
                }
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
                status = if (result.awaitingPayment) "on hold" else "confirmed",
                type = "flight",
                awaitingPayment = result.awaitingPayment,
                paymentRequiredBy = result.paymentRequiredBy.orEmpty(),
            )
        )
    }

    // --- Stays (hotels) ---------------------------------------------------

    fun searchStays(place: String, checkIn: String, checkOut: String, rooms: Int, adults: Int) {
        selectedCheckIn = checkIn
        selectedCheckOut = checkOut
        recordRecent(
            RecentSearch(
                vertical = "STAYS",
                label = "$place · ${prettyDate(checkIn)}–${prettyDate(checkOut)}",
                origin = place,
                depart = checkIn,
                returnDate = checkOut,
                adults = adults,
            )
        )
        _stayResults.value = StaySearchState(loading = true, hasSearched = true)
        viewModelScope.launch {
            runCatching { StaysApi.search(place, checkIn, checkOut, rooms, adults) }
                .onSuccess { _stayResults.value = StaySearchState(results = it, hasSearched = true) }
                .onFailure { _stayResults.value = StaySearchState(error = errorMessage(it), hasSearched = true) }
        }
    }

    fun loadStayRates(searchResultId: String, accommodationName: String) {
        selectedStayName = accommodationName
        _stayRates.value = StayRatesState(loading = true)
        viewModelScope.launch {
            runCatching { StaysApi.rates(searchResultId) }
                .onSuccess {
                    if (it.name.isNotBlank()) selectedStayName = it.name
                    _stayRates.value = StayRatesState(rates = it)
                }
                .onFailure { _stayRates.value = StayRatesState(error = errorMessage(it)) }
        }
    }

    /** Choose a rate and quote it (confirming price) before collecting guests. */
    fun selectStayRate(rate: StayRateDto) {
        selectedRate = rate
        stayQuote = null
        _stayBooking.value = StayBookingState.Idle
        viewModelScope.launch {
            runCatching { StaysApi.quote(rate.id) }
                .onSuccess { stayQuote = it }
                .onFailure { /* fall back to the rate price at book time */ }
        }
    }

    fun selectedStayRate(): StayRateDto? = selectedRate

    /** The confirmed quote total, falling back to the selected rate's price. */
    fun stayTotal(): Pair<String, String> {
        val q = stayQuote
        val r = selectedRate
        return when {
            q != null -> q.totalAmount to q.totalCurrency
            r != null -> r.totalAmount to r.totalCurrency
            else -> "0" to "USD"
        }
    }

    fun bookStay(guest: StayGuestInputDto, email: String, phone: String) {
        val quoteId = stayQuote?.id
        if (quoteId == null) {
            _stayBooking.value = StayBookingState.Error("This rate is no longer available. Please pick another.")
            return
        }
        _stayBooking.value = StayBookingState.Loading
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
                    persistStay(result)
                    _stayBooking.value = StayBookingState.Success(result)
                }
                .onFailure { _stayBooking.value = StayBookingState.Error(errorMessage(it)) }
        }
    }

    fun resetStayBooking() {
        _stayBooking.value = StayBookingState.Idle
    }

    private suspend fun persistStay(result: StayBookingResultDto) {
        val name = result.accommodationName.ifBlank { selectedStayName }
        val checkIn = result.checkInDate.ifBlank { selectedCheckIn }
        // Stay bookings don't carry a total; fall back to the confirmed quote.
        val (quoteAmount, quoteCurrency) = stayTotal()
        val amount = result.totalAmount.takeIf { it.isNotBlank() && it != "0" } ?: quoteAmount
        val currency = result.totalCurrency.takeIf { it.isNotBlank() } ?: quoteCurrency
        bookedTripDao.upsert(
            BookedTrip(
                orderId = result.id,
                bookingReference = result.reference,
                route = name,
                departDate = checkIn.take(10),
                amount = amount,
                currency = currency,
                status = result.status.ifBlank { "confirmed" },
                type = "stay",
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
