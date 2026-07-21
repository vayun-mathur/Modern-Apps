package com.vayunmathur.travel.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.BookedTripDao
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.CustomerDao
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.data.FrequentFlyerDao
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.data.RecentSearchDao
import com.vayunmathur.travel.data.Vertical
import com.vayunmathur.travel.network.AirlineDto
import com.vayunmathur.travel.network.AircraftDto
import com.vayunmathur.travel.network.CancellationDto
import com.vayunmathur.travel.network.ChangeOfferDto
import com.vayunmathur.travel.network.ChangeRequestInputDto
import com.vayunmathur.travel.network.CityDto
import com.vayunmathur.travel.network.CustomerUserInputDto
import com.vayunmathur.travel.network.LoyaltyAccountDto
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.OrderEventDto
import com.vayunmathur.travel.network.OrderRequestDto
import com.vayunmathur.travel.network.OrderResultDto
import com.vayunmathur.travel.network.PassengerInputDto
import com.vayunmathur.travel.network.PaymentInputDto
import com.vayunmathur.travel.network.PlaceDto
import com.vayunmathur.travel.network.SearchSliceInputDto
import com.vayunmathur.travel.network.SeatCabinDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.ServiceSelectionDto
import com.vayunmathur.travel.network.StayBookingRequestDto
import com.vayunmathur.travel.network.StayBookingResultDto
import com.vayunmathur.travel.network.StayGuestInputDto
import com.vayunmathur.travel.network.StayQuoteDto
import com.vayunmathur.travel.network.StayRateDto
import com.vayunmathur.travel.network.StayRatesDto
import com.vayunmathur.travel.network.StaySearchResultDto
import com.vayunmathur.travel.network.StaysApi
import com.vayunmathur.travel.network.StaySuggestionDto
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
    val isRoundTrip: Boolean = false,
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
    val fareBrand: String? = null,
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
    /** True while still polling for more offers (incremental search). */
    val polling: Boolean = false,
) {
    /** Offers after applying the client-side filters. */
    val visibleOffers: List<OfferDto>
        get() = allOffers.filter { offer ->
            (filters.maxStops == null || offer.slices.all { it.stops <= filters.maxStops }) &&
                (filters.airlines.isEmpty() || offer.airlineIatas.any { it in filters.airlines }) &&
                (filters.fareBrand == null || offer.fareBrand == filters.fareBrand)
        }

    /** Distinct airline IATA codes present in the results, for the filter UI. */
    val availableAirlines: List<String>
        get() = allOffers.flatMap { it.airlineIatas }.distinct().sorted()

    /** Distinct fare brands present in the results, for the filter UI. */
    val availableFareBrands: List<String>
        get() = allOffers.map { it.fareBrand }.filter { it.isNotBlank() }.distinct().sorted()
}

/** State for the single-offer review (re-price) screen. */
data class OfferReviewState(
    val loading: Boolean = false,
    val error: String? = null,
    val offer: OfferDto? = null,
)

/**
 * State for the step-by-step (partial offer) round-trip flow. [offers] holds the
 * choices for the current leg (outbound, then return, then final fares);
 * [requestId] threads the partial offer request across steps.
 */
data class PartialFlowState(
    val loading: Boolean = false,
    val error: String? = null,
    val requestId: String = "",
    val offers: List<OfferDto> = emptyList(),
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
    private val frequentFlyerDao: FrequentFlyerDao,
    private val customerDao: CustomerDao,
) : AndroidViewModel(application) {

    private val _flights = MutableStateFlow(FlightResultsState())
    val flights: StateFlow<FlightResultsState> = _flights.asStateFlow()

    /** The query backing the current results, kept so re-sort can re-fetch. */
    private var currentQuery: FlightQuery? = null

    private val _review = MutableStateFlow(OfferReviewState())
    val review: StateFlow<OfferReviewState> = _review.asStateFlow()

    private val _partialFlow = MutableStateFlow(PartialFlowState())
    val partialFlow: StateFlow<PartialFlowState> = _partialFlow.asStateFlow()

    private val _passengers = MutableStateFlow<List<PassengerInputDto>>(emptyList())
    val passengers: StateFlow<List<PassengerInputDto>> = _passengers.asStateFlow()

    private val _seatMap = MutableStateFlow(SeatMapState())
    val seatMap: StateFlow<SeatMapState> = _seatMap.asStateFlow()

    /** Selected extra-baggage services: service id → quantity. */
    private val _selectedBaggage = MutableStateFlow<Map<String, Long>>(emptyMap())
    val selectedBaggage: StateFlow<Map<String, Long>> = _selectedBaggage.asStateFlow()

    /** Selected non-baggage extra services (CFAR, priority boarding, …): id → quantity. */
    private val _selectedExtras = MutableStateFlow<Map<String, Long>>(emptyMap())
    val selectedExtras: StateFlow<Map<String, Long>> = _selectedExtras.asStateFlow()

    /** Selected seats keyed by `"segmentId|designator"`. */
    private val _selectedSeats = MutableStateFlow<Map<String, SeatElementDto>>(emptyMap())
    val selectedSeats: StateFlow<Map<String, SeatElementDto>> = _selectedSeats.asStateFlow()

    private val _airlines = MutableStateFlow<List<AirlineDto>>(emptyList())
    val airlines: StateFlow<List<AirlineDto>> = _airlines.asStateFlow()

    private val _aircraft = MutableStateFlow<List<AircraftDto>>(emptyList())
    val aircraft: StateFlow<List<AircraftDto>> = _aircraft.asStateFlow()

    private val _cities = MutableStateFlow<List<CityDto>>(emptyList())
    val cities: StateFlow<List<CityDto>> = _cities.asStateFlow()

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

    /** Saved frequent-flyer accounts, applied as loyalty pricing at search. */
    val frequentFlyers: StateFlow<List<FrequentFlyer>> = frequentFlyerDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveFrequentFlyer(airlineIata: String, accountNumber: String, airlineName: String) {
        val iata = airlineIata.trim().uppercase()
        val account = accountNumber.trim()
        if (iata.isBlank() || account.isBlank()) return
        viewModelScope.launch {
            frequentFlyerDao.upsert(
                FrequentFlyer(airlineIata = iata, accountNumber = account, airlineName = airlineName),
            )
        }
    }

    fun removeFrequentFlyer(airlineIata: String) {
        viewModelScope.launch { frequentFlyerDao.deleteById(airlineIata) }
    }

    // --- Customers (Duffel customer users) --------------------------------

    private val dataStore = DataStoreUtils.getInstance(application)
    private val activeCustomerKey = "travel_active_customer_id"

    /** Saved customer users; orders are associated with the active one. */
    val customers: StateFlow<List<Customer>> = customerDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The id of the currently selected customer (persisted), or blank. */
    val activeCustomerId: StateFlow<String> = dataStore.stringFlow(activeCustomerKey)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Create a Duffel customer user, store it locally, and make it active. */
    fun createCustomer(email: String, givenName: String, familyName: String, phone: String) {
        viewModelScope.launch {
            runCatching {
                TravelApi.createCustomer(
                    CustomerUserInputDto(
                        email = email.trim(),
                        givenName = givenName.trim(),
                        familyName = familyName.trim(),
                        phoneNumber = phone.trim().ifBlank { null },
                    )
                )
            }.onSuccess { dto ->
                if (dto.id.isNotBlank()) {
                    customerDao.upsert(
                        Customer(
                            id = dto.id,
                            email = dto.email,
                            givenName = dto.givenName,
                            familyName = dto.familyName,
                            phoneNumber = dto.phoneNumber.orEmpty(),
                        )
                    )
                    dataStore.setString(activeCustomerKey, dto.id)
                    _customerError.value = null
                }
            }.onFailure { _customerError.value = errorMessage(it) }
        }
    }

    private val _customerError = MutableStateFlow<String?>(null)
    val customerError: StateFlow<String?> = _customerError.asStateFlow()

    /** Select (or clear, with blank) the active customer. */
    fun selectCustomer(id: String) {
        viewModelScope.launch { dataStore.setString(activeCustomerKey, id) }
    }

    fun removeCustomer(id: String) {
        viewModelScope.launch {
            customerDao.deleteById(id)
            if (activeCustomerId.value == id) dataStore.setString(activeCustomerKey, "")
        }
    }

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
            val loyalty = frequentFlyerDao.getAll()
                .joinToString(",") { "${it.airlineIata}:${it.accountNumber}" }
            // Incremental search: create the request async, then poll /offers so
            // results appear progressively instead of blocking on the full set.
            runCatching {
                TravelApi.flightsAsync(
                    slices = query.slices,
                    adults = query.adults,
                    children = query.children,
                    infants = query.infants,
                    cabin = query.cabin,
                    maxConnections = query.maxConnections,
                    loyalty = loyalty,
                )
            }
                .onSuccess { started ->
                    val requestId = started.offerRequestId
                    if (requestId.isBlank()) {
                        _flights.value = FlightResultsState(
                            error = "Search could not be started. Please try again.",
                            hasSearched = true,
                        )
                        return@onSuccess
                    }
                    _flights.value = FlightResultsState(
                        hasSearched = true,
                        offerRequestId = requestId,
                        loading = true,
                        polling = true,
                    )
                    pollOffers(requestId, query.maxConnections)
                }
                .onFailure {
                    _flights.value = FlightResultsState(error = errorMessage(it), hasSearched = true)
                }
        }
    }

    /** Poll `/offers` for [requestId] a few rounds, updating results as they arrive. */
    private suspend fun pollOffers(requestId: String, maxConnections: Int) {
        var lastCount = -1
        var stableRounds = 0
        repeat(6) { round ->
            // Skip the initial wait on the first round so results show ASAP.
            if (round > 0) kotlinx.coroutines.delay(1_200)
            // Stop if the user has navigated to a different search.
            if (_flights.value.offerRequestId != requestId) return
            val offers = runCatching { TravelApi.offers(requestId, null, maxConnections) }.getOrNull()
            if (offers != null) {
                _flights.value = _flights.value.copy(
                    allOffers = offers,
                    loading = offers.isEmpty(),
                )
                if (offers.size == lastCount) stableRounds++ else stableRounds = 0
                lastCount = offers.size
                // Stop early once the count stabilizes with some results in hand.
                if (stableRounds >= 2 && offers.isNotEmpty()) {
                    _flights.value = _flights.value.copy(polling = false, loading = false)
                    return
                }
            }
        }
        _flights.value = _flights.value.copy(polling = false, loading = false)
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

    fun setFareBrandFilter(fareBrand: String?) {
        _flights.value = _flights.value.copy(
            filters = _flights.value.filters.copy(fareBrand = fareBrand),
        )
    }

    fun clearFilters() {
        _flights.value = _flights.value.copy(filters = FlightFilters())
    }

    // --- Offer review / re-price -----------------------------------------

    /** Seed the review screen with the tapped offer; clear any prior ancillaries. */
    fun selectOffer(offer: OfferDto) {
        _review.value = OfferReviewState(offer = offer)
        _selectedBaggage.value = emptyMap()
        _selectedExtras.value = emptyMap()
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

    // --- Partial offers (round-trip step-by-step flow) -------------------

    /** Start the round-trip partial flow: fetch the outbound leg's offers. */
    fun startPartialSearch(query: FlightQuery) {
        _partialFlow.value = PartialFlowState(loading = true)
        viewModelScope.launch {
            val loyalty = frequentFlyerDao.getAll()
                .joinToString(",") { "${it.airlineIata}:${it.accountNumber}" }
            runCatching {
                TravelApi.createPartialOffers(
                    slices = query.slices,
                    adults = query.adults,
                    children = query.children,
                    infants = query.infants,
                    cabin = query.cabin,
                    maxConnections = query.maxConnections,
                    loyalty = loyalty,
                )
            }
                .onSuccess { _partialFlow.value = PartialFlowState(requestId = it.id, offers = it.offers) }
                .onFailure { _partialFlow.value = PartialFlowState(error = errorMessage(it)) }
        }
    }

    /** Load the return leg's offers after an outbound partial offer is chosen. */
    fun loadPartialReturn(outboundId: String) {
        val requestId = _partialFlow.value.requestId
        if (requestId.isBlank()) {
            _partialFlow.value = _partialFlow.value.copy(error = "Please restart your search.")
            return
        }
        _partialFlow.value = _partialFlow.value.copy(loading = true, error = null, offers = emptyList())
        viewModelScope.launch {
            runCatching { TravelApi.selectPartialOffer(requestId, listOf(outboundId)) }
                .onSuccess { _partialFlow.value = _partialFlow.value.copy(loading = false, requestId = it.id.ifBlank { requestId }, offers = it.offers) }
                .onFailure { _partialFlow.value = _partialFlow.value.copy(loading = false, error = errorMessage(it)) }
        }
    }

    /** Load the final orderable fares after both legs are chosen. */
    fun loadPartialFares(outboundId: String, returnId: String) {
        val requestId = _partialFlow.value.requestId
        if (requestId.isBlank()) {
            _partialFlow.value = _partialFlow.value.copy(error = "Please restart your search.")
            return
        }
        _partialFlow.value = _partialFlow.value.copy(loading = true, error = null, offers = emptyList())
        viewModelScope.launch {
            runCatching { TravelApi.partialOfferFares(requestId, listOf(outboundId, returnId)) }
                .onSuccess { _partialFlow.value = _partialFlow.value.copy(loading = false, offers = it.offers) }
                .onFailure { _partialFlow.value = _partialFlow.value.copy(loading = false, error = errorMessage(it)) }
        }
    }

    /** Look up a partial-flow offer by id (for seeding the review screen). */
    fun partialOfferById(offerId: String): OfferDto? =
        _partialFlow.value.offers.find { it.offerId == offerId }

    /**
     * Initialize one blank passenger per Duffel passenger id on the offer,
     * unless a matching set is already present (so returning to the form keeps
     * entered values).
     */
    fun initPassengers(offer: OfferDto) {
        val ids = offer.passengerIds.ifEmpty { listOf("") }
        if (_passengers.value.map { it.id } == ids) return
        _passengers.value = ids.map { PassengerInputDto(id = it) }
        // Pre-fill the lead passenger with any saved frequent-flyer accounts.
        viewModelScope.launch {
            val saved = frequentFlyerDao.getAll()
            if (saved.isEmpty()) return@launch
            val loyalty = saved.map {
                LoyaltyAccountDto(airlineIataCode = it.airlineIata, accountNumber = it.accountNumber)
            }
            _passengers.value = _passengers.value.toMutableList().also { list ->
                list.firstOrNull()?.let { lead ->
                    if (lead.loyaltyProgrammeAccounts.isEmpty()) {
                        list[0] = lead.copy(loyaltyProgrammeAccounts = loyalty)
                    }
                }
            }
        }
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

    /** Set the selected quantity for a non-baggage extra service (0 removes it). */
    fun setExtraQuantity(serviceId: String, quantity: Long) {
        _selectedExtras.value = _selectedExtras.value.toMutableMap().also {
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

    /** Load the airline reference list once (for the frequent-flyer picker). */
    fun loadAirlines() {
        if (_airlines.value.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { TravelApi.airlines() }
                .onSuccess { list -> _airlines.value = list.filter { it.iataCode.isNotBlank() }.sortedBy { it.name } }
        }
    }

    /** Load the aircraft reference list once, to label segments by aircraft name. */
    fun loadAircraft() {
        if (_aircraft.value.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { TravelApi.aircraft() }.onSuccess { _aircraft.value = it }
        }
    }

    /** Load the cities reference list once, to improve place labels. */
    fun loadCities() {
        if (_cities.value.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { TravelApi.cities() }.onSuccess { _cities.value = it }
        }
    }

    /** Human aircraft name for an IATA aircraft code, falling back to the code. */
    fun aircraftName(iataCode: String): String =
        _aircraft.value.firstOrNull { it.iataCode == iataCode }?.name?.ifBlank { iataCode } ?: iataCode

    /** City name for an IATA city code, falling back to the code. */
    fun cityName(iataCode: String): String =
        _cities.value.firstOrNull { it.iataCode == iataCode }?.name?.ifBlank { iataCode } ?: iataCode

    /** Toggle selection of a (selectable) seat on a segment. */
    fun toggleSeat(segmentId: String, seat: SeatElementDto) {
        if (!seat.available || seat.serviceId == null) return
        val key = "$segmentId|${seat.designator}"
        _selectedSeats.value = _selectedSeats.value.toMutableMap().also {
            if (it.containsKey(key)) it.remove(key) else it[key] = seat
        }
    }

    /** Combined selected services (baggage + extras + seats) for the order body,
     *  tagged with the passenger each service belongs to when known. */
    private fun selectedServices(): List<ServiceSelectionDto> {
        val available = _review.value.offer?.availableServices.orEmpty()
        fun passengerFor(id: String): String? =
            available.firstOrNull { it.id == id }?.passengerIds?.firstOrNull()
        val bags = _selectedBaggage.value.map { (id, qty) ->
            ServiceSelectionDto(id = id, quantity = qty, passengerId = passengerFor(id))
        }
        val extras = _selectedExtras.value.map { (id, qty) ->
            ServiceSelectionDto(id = id, quantity = qty, passengerId = passengerFor(id))
        }
        val seats = _selectedSeats.value.values.mapNotNull { seat ->
            seat.serviceId?.let { ServiceSelectionDto(id = it, quantity = 1) }
        }
        return bags + extras + seats
    }

    // --- Booking ----------------------------------------------------------

    /** Book the reviewed offer with the collected passengers via test balance. */
    fun createOrder(hold: Boolean = false) {
        val offer = _review.value.offer ?: return
        val pax = _passengers.value
        val customerId = activeCustomerId.value.ifBlank { null }
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
                        customerUserId = customerId,
                    )
                )
            }
                .onSuccess { result ->
                    persistTrip(offer, pax, result, customerId.orEmpty())
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

    /** Recorded webhook events per order id (schedule changes / cancellations). */
    private val _orderEvents = MutableStateFlow<Map<String, List<OrderEventDto>>>(emptyMap())
    val orderEvents: StateFlow<Map<String, List<OrderEventDto>>> = _orderEvents.asStateFlow()

    /** Poll the server for order events; updates [orderEvents] for [orderId]. */
    fun loadOrderEvents(orderId: String) {
        if (orderId.isBlank()) return
        viewModelScope.launch {
            runCatching { TravelApi.orderEvents(orderId) }
                .onSuccess { events ->
                    _orderEvents.value = _orderEvents.value.toMutableMap().also { it[orderId] = events }
                }
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
        customerId: String = "",
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
                customerId = customerId,
            )
        )
    }

    // --- Stays (hotels) ---------------------------------------------------

    fun searchStays(
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
                label = "$place · ${prettyDate(checkIn)}–${prettyDate(checkOut)}",
                origin = place,
                depart = checkIn,
                returnDate = checkOut,
                adults = adults,
            )
        )
        _stayResults.value = StaySearchState(loading = true, hasSearched = true)
        viewModelScope.launch {
            runCatching { StaysApi.search(place, checkIn, checkOut, rooms, adults, latitude = latitude, longitude = longitude) }
                .onSuccess { _stayResults.value = StaySearchState(results = it, hasSearched = true) }
                .onFailure { _stayResults.value = StaySearchState(error = errorMessage(it), hasSearched = true) }
        }
    }

    /** Accommodation/location suggestions for the stays search box. */
    suspend fun staySuggestions(query: String): List<StaySuggestionDto> =
        runCatching { StaysApi.suggestions(query) }.getOrDefault(emptyList())

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
                customerId = activeCustomerId.value,
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
    private val frequentFlyerDao: FrequentFlyerDao,
    private val customerDao: CustomerDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TravelViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return TravelViewModel(application, recentSearchDao, bookedTripDao, frequentFlyerDao, customerDao) as T
    }
}
