package com.vayunmathur.travel.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.data.AirportCatalog
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.TRIP_STATUS_PENDING_PAYMENT
import com.vayunmathur.travel.data.TRIP_STATUS_PROCESSING
import com.vayunmathur.travel.data.TRIP_TYPE_FLIGHT
import com.vayunmathur.travel.domain.ancillariesNeedSaving
import com.vayunmathur.travel.domain.assignSeat
import com.vayunmathur.travel.domain.isTripPollingStatus
import com.vayunmathur.travel.domain.seatSelections
import com.vayunmathur.travel.network.AncillariesSelectionResultDto
import com.vayunmathur.travel.network.CheckoutDto
import com.vayunmathur.travel.network.LoyaltyAccountDto
import com.vayunmathur.travel.network.MissingDetailsException
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.PassengerInputDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.ServiceSelectionDto
import com.vayunmathur.travel.network.StillPricingException
import com.vayunmathur.travel.network.TravelApi
import com.vayunmathur.travel.network.TripContactDto
import com.vayunmathur.travel.network.TripDto
import com.vayunmathur.travel.network.TripRequestDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Initialize one blank passenger per booking passenger id on the offer,
 * unless a matching set is already present (so returning to the form keeps
 * entered values).
 */
fun TravelViewModel.initPassengers(offer: OfferDto) {
    val ids = offer.passengerIds.ifEmpty { listOf("") }
    if (passengersMutable.value.map { it.id } == ids) return
    passengersMutable.value = ids.map { PassengerInputDto(id = it) }
    // Pre-fill the lead passenger with any saved frequent-flyer accounts.
    viewModelScope.launch {
        val saved = repository.getAllFrequentFlyers()
        if (saved.isEmpty()) return@launch
        val loyalty = saved.map {
            LoyaltyAccountDto(airlineIataCode = it.airlineIata, accountNumber = it.accountNumber)
        }
        passengersMutable.value = passengersMutable.value.toMutableList().also { list ->
            list.firstOrNull()?.let { lead ->
                if (lead.loyaltyProgrammeAccounts.isEmpty()) {
                    list[0] = lead.copy(loyaltyProgrammeAccounts = loyalty)
                }
            }
        }
    }
}

/**
 * Prefill the lead passenger's contact fields from a saved booking profile
 * (local-only): email/phone/name fill blanks only, never overwrite typing.
 */
fun TravelViewModel.prefillFromProfile(email: String, givenName: String, familyName: String, phone: String) {
    if (passengersMutable.value.isEmpty()) return
    passengersMutable.value = passengersMutable.value.toMutableList().also { list ->
        val lead = list.firstOrNull() ?: return@also
        list[0] = lead.copy(
            email = lead.email.ifBlank { email },
            phoneNumber = lead.phoneNumber.ifBlank { phone },
            givenName = lead.givenName.ifBlank { givenName },
            familyName = lead.familyName.ifBlank { familyName },
        )
    }
}

fun TravelViewModel.updatePassenger(index: Int, passenger: PassengerInputDto) {
    passengersMutable.value = passengersMutable.value.toMutableList().also {
        if (index in it.indices) it[index] = passenger
    }
}

/** Set the selected quantity for an extra-baggage service (0 removes it). */
fun TravelViewModel.setBaggageQuantity(serviceId: String, quantity: Long) {
    selectedBaggageMutable.value = selectedBaggageMutable.value.toMutableMap().also {
        if (quantity <= 0) it.remove(serviceId) else it[serviceId] = quantity
    }
}

/** Set the selected quantity for a non-baggage extra service (0 removes it). */
fun TravelViewModel.setExtraQuantity(serviceId: String, quantity: Long) {
    selectedExtrasMutable.value = selectedExtrasMutable.value.toMutableMap().also {
        if (quantity <= 0) it.remove(serviceId) else it[serviceId] = quantity
    }
}

// --- Trip creation ----------------------------------------------------------

/**
 * Create the trip (`POST /trips` with the chosen offer ids + passengers +
 * contact). On success the flow moves to ancillaries; on 422 the UI routes
 * back to passengers with the missing fields.
 */
fun TravelViewModel.createTrip() {
    val offerIds = selectedOfferIdsMutable.value
    val pax = passengersMutable.value
    if (offerIds.isEmpty() || pax.isEmpty()) return
    val lead = pax.firstOrNull()
    bookingMutable.value = BookingState.Loading
    viewModelScope.launch {
        runCatching {
            TravelApi.createTrip(
                TripRequestDto(
                    offerIds = offerIds,
                    passengers = pax,
                    contact = lead?.let {
                        TripContactDto(email = it.email, phoneNumber = it.phoneNumber)
                    },
                )
            )
        }
            .onSuccess { trip ->
                currentTrip = trip
                savedServices = emptyList()
                bookingMutable.value = BookingState.TripReady(trip)
                loadAncillaries(trip.tripId)
            }
            .onFailure { t ->
                bookingMutable.value = if (t is MissingDetailsException) {
                    BookingState.MissingDetails(t.missingFields, errorMessage(t))
                } else {
                    BookingState.Error(errorMessage(t))
                }
            }
    }
}

fun TravelViewModel.resetBooking() {
    bookingMutable.value = BookingState.Idle
}

// --- Ancillaries ------------------------------------------------------------

private const val ANCILLARY_POLL_ROUNDS = 10

/**
 * Load trip ancillaries; retries while the server prices (202 `pricing` with
 * `retryAfterSeconds`, or 503). Bag services render immediately; the seat
 * section shows "still loading" with a retry while pending.
 */
fun TravelViewModel.loadAncillaries(tripId: String) {
    ancillariesMutable.value = AncillariesState(loading = true)
    viewModelScope.launch {
        var attempt = 0
        while (attempt < ANCILLARY_POLL_ROUNDS) {
            val result = runCatching { TravelApi.ancillaries(tripId) }
            val data = result.getOrNull()
            if (data != null && data.status != "pricing") {
                ancillariesMutable.value = AncillariesState(
                    ancillaries = data,
                    seatMapsLoading = data.seatMapsPending,
                )
                if (data.seatMaps.isNotEmpty()) seatMapMutable.value = SeatMapState(cabins = data.seatMaps)
                return@launch
            }
            val failure = result.exceptionOrNull()
            if (failure != null && failure !is StillPricingException) {
                ancillariesMutable.value = AncillariesState(error = errorMessage(failure))
                return@launch
            }
            // Still pricing: honor the server's hint, then retry.
            val waitSecs = (failure as? StillPricingException)?.retryAfterSeconds ?: 5
            delay(waitSecs.coerceIn(1, 30) * 1_000L)
            attempt++
        }
        ancillariesMutable.value = AncillariesState(
            error = "Extras are taking too long to load. You can continue without them."
        )
    }
}

/** Retry loading the seat maps only (keeps already-loaded bag services). */
fun TravelViewModel.retrySeatMaps() {
    val tripId = currentTrip?.tripId ?: return
    ancillariesMutable.value = ancillariesMutable.value.copy(seatMapsLoading = true, error = null)
    viewModelScope.launch {
        runCatching { TravelApi.ancillaries(tripId) }
            .onSuccess { data ->
                ancillariesMutable.value = ancillariesMutable.value.copy(
                    ancillaries = data,
                    seatMapsLoading = data.seatMapsPending,
                )
                if (data.seatMaps.isNotEmpty()) seatMapMutable.value = SeatMapState(cabins = data.seatMaps)
            }
            .onFailure { t ->
                if (t is StillPricingException) {
                    // Still pricing: stay in the loading state; the user can retry again.
                    ancillariesMutable.value = ancillariesMutable.value.copy(seatMapsLoading = false)
                    loadAncillaries(tripId)
                } else {
                    ancillariesMutable.value = ancillariesMutable.value.copy(
                        seatMapsLoading = false,
                        error = errorMessage(t)
                    )
                }
            }
    }
}

/** Use the trip's seat maps directly for the picker (no separate fetch). */
fun TravelViewModel.loadSeatMaps() {
    val cabins = ancillariesMutable.value.ancillaries?.seatMaps.orEmpty()
    seatMapMutable.value = SeatMapState(cabins = cabins)
}

/** Load the airline reference list once (for the frequent-flyer picker). */
fun TravelViewModel.loadAirlines() {
    if (airlinesMutable.value.isNotEmpty()) return
    viewModelScope.launch {
        AirportCatalog.ensureLoaded(getApplication())
        airlinesMutable.value = AirportCatalog.airlines()
    }
}

/** Load the aircraft reference list once, to label segments by aircraft name. */
fun TravelViewModel.loadAircraft() {
    if (aircraftMutable.value.isNotEmpty()) return
    viewModelScope.launch {
        AirportCatalog.ensureLoaded(getApplication())
        aircraftMutable.value = AirportCatalog.aircraft()
    }
}

/** Load the cities reference list once, to improve place labels. */
fun TravelViewModel.loadCities() {
    if (citiesMutable.value.isNotEmpty()) return
    viewModelScope.launch {
        AirportCatalog.ensureLoaded(getApplication())
        citiesMutable.value = AirportCatalog.cities()
    }
}

/** Human aircraft name for an IATA aircraft code, falling back to the code. */
fun TravelViewModel.aircraftName(iataCode: String): String = AirportCatalog.aircraftName(iataCode)

/** City name for an IATA city code, falling back to the code. */
fun TravelViewModel.cityName(iataCode: String): String = AirportCatalog.cityName(iataCode)

/** Airline name for an IATA code (operating-carrier disclosure), falling back to the code. */
fun TravelViewModel.airlineName(iataCode: String): String =
    AirportCatalog.airlineName(iataCode).ifBlank { iataCode }

/** Tap a seat on a segment for one passenger (see [assignSeat]). */
fun TravelViewModel.toggleSeat(segmentId: String, passengerId: String, seat: SeatElementDto) {
    selectedSeatsMutable.value = assignSeat(selectedSeatsMutable.value, segmentId, passengerId, seat)
}

/** Combined selected services (baggage + extras + seats) for the trip body. */
internal fun TravelViewModel.selectedServices(): List<ServiceSelectionDto> {
    val available = ancillariesMutable.value.ancillaries?.services.orEmpty()
    val bags = selectedBaggageMutable.value.map { (id, qty) ->
        ServiceSelectionDto(id = id, quantity = qty)
    }
    val extras = selectedExtrasMutable.value.map { (id, qty) ->
        ServiceSelectionDto(id = id, quantity = qty)
    }
    // Include the selected passenger when the service is per-passenger.
    fun withPassenger(sel: ServiceSelectionDto): ServiceSelectionDto {
        val passengerId = available.firstOrNull { it.id == sel.id }?.passengerIds?.firstOrNull()
        return if (passengerId != null) sel.copy(passengerId = passengerId) else sel
    }
    return (bags + extras).map(::withPassenger) + seatSelections(selectedSeatsMutable.value)
}

/**
 * Save ancillary selections (`POST /trips/:id/ancillaries`, replacing all)
 * when they differ from what the trip already has — including clearing them
 * back to none. Then moves to checkout.
 */
fun TravelViewModel.saveAncillariesAndContinue() {
    val tripId = currentTrip?.tripId ?: return
    val selections = selectedServices()
    if (!ancillariesNeedSaving(selections, savedServices)) {
        startCheckout()
        return
    }
    ancillariesMutable.value = ancillariesMutable.value.copy(loading = true, error = null)
    viewModelScope.launch {
        runCatching { TravelApi.selectAncillaries(tripId, selections) }
            .onSuccess { _: AncillariesSelectionResultDto ->
                savedServices = selections
                ancillariesMutable.value = ancillariesMutable.value.copy(loading = false)
                startCheckout()
            }
            .onFailure {
                ancillariesMutable.value = ancillariesMutable.value.copy(loading = false, error = errorMessage(it))
            }
    }
}

// --- Checkout ---------------------------------------------------------------

/**
 * Get the trip's payment intent (`POST /trips/:id/checkout`). On 422 routes
 * back to passengers with the missing fields; otherwise surface the message.
 */
fun TravelViewModel.startCheckout() {
    val tripId = currentTrip?.tripId ?: return
    checkoutMutable.value = CheckoutState.Loading
    viewModelScope.launch {
        runCatching { TravelApi.checkout(tripId) }
            .onSuccess { checkout ->
                checkoutMutable.value = CheckoutState.Ready(checkout)
                persistPendingTrip(checkout)
            }
            .onFailure { t ->
                if (t is MissingDetailsException) {
                    bookingMutable.value = BookingState.MissingDetails(t.missingFields, errorMessage(t))
                } else {
                    checkoutMutable.value = CheckoutState.Error(errorMessage(t))
                }
            }
    }
}

/** The user accepted a changed price: allow "Pay securely". */
fun TravelViewModel.acceptNewPrice() {
    val ready = checkoutMutable.value as? CheckoutState.Ready ?: return
    checkoutMutable.value = ready.copy(acceptedNewPrice = true)
}

fun TravelViewModel.resetCheckout() {
    checkoutMutable.value = CheckoutState.Idle
}

/**
 * Persist the trip immediately (status pending_payment) so the booking
 * survives process death during payment; the confirmation flow polls from here.
 */
internal suspend fun TravelViewModel.persistPendingTrip(checkout: CheckoutDto) {
    val trip = currentTrip ?: return
    val offer = reviewMutable.value.offer
    val first = offer?.slices?.firstOrNull()
    val lastName = passengersMutable.value.firstOrNull()?.familyName.orEmpty()
    val route = if (first == null) {
        trip.bookingReference.ifBlank { "Flight booking" }
    } else {
        val base = "${first.origin} → ${first.destination}"
        if ((offer?.slices?.size ?: 0) > 1) "$base (round trip)" else base
    }
    repository.upsertBookedTrip(
        BookedTrip(
            orderId = trip.tripId,
            bookingReference = trip.bookingReference,
            route = route,
            departDate = first?.departureAt?.take(ISO_DATE_LENGTH).orEmpty(),
            amount = checkout.totalAmount,
            currency = checkout.currency,
            passengersJson = runCatching { bookingJson.encodeToString(passengersMutable.value) }.getOrDefault(""),
            status = TRIP_STATUS_PENDING_PAYMENT,
            type = TRIP_TYPE_FLIGHT,
            awaitingPayment = true,
            paymentRequiredBy = "",
            tripId = trip.tripId,
            checkoutExpiresAt = checkout.expiresAt,
            lastName = lastName,
        )
    )
}

// --- Post-checkout polling --------------------------------------------------

/**
 * The payment sheet finished: book the trip (`POST /trips/:id/confirm`), then
 * poll to a final status. A failed confirm leaves the trip payable, so the
 * confirmation screen can offer "Complete payment" again.
 */
fun TravelViewModel.confirmPaidTrip(tripId: String) {
    pollJob?.cancel()
    tripPollMutable.value = TripPollState.Polling
    viewModelScope.launch {
        runCatching { TravelApi.confirmPayment(tripId) }
            .onSuccess { trip ->
                currentTrip = trip
                updateStoredTrip(trip)
                pollTrip(tripId)
            }
            .onFailure { tripPollMutable.value = TripPollState.Error(errorMessage(it)) }
    }
}

private const val TRIP_POLL_ROUNDS = 40
private const val TRIP_POLL_INTERVAL_MS = 3_000L

/**
 * Poll `GET /trips/:id` every ~3s up to ~2 minutes while the status is
 * pending_payment/processing (started on resume and on confirmation entry).
 * Updates the local trip and surfaces the final state.
 */
fun TravelViewModel.pollTrip(tripId: String) {
    pollJob?.cancel()
    tripPollMutable.value = TripPollState.Polling
    pollJob = viewModelScope.launch {
        repeat(TRIP_POLL_ROUNDS) { round ->
            if (round > 0) delay(TRIP_POLL_INTERVAL_MS)
            val trip = runCatching { TravelApi.trip(tripId) }.getOrNull()
            if (trip != null) {
                currentTrip = trip
                updateStoredTrip(trip)
                if (!isTripPollingStatus(trip.status)) {
                    tripPollMutable.value = TripPollState.Done(trip)
                    return@launch
                }
                tripPollMutable.value = TripPollState.Polling
            }
        }
        // Timed out still pending: surface the last known state.
        val last = currentTrip
        tripPollMutable.value = if (last != null) TripPollState.Done(last)
        else TripPollState.Error("Still processing. Pull to refresh for the latest status.")
    }
}

fun TravelViewModel.stopTripPoll() {
    pollJob?.cancel()
    pollJob = null
    if (tripPollMutable.value is TripPollState.Polling) {
        tripPollMutable.value = TripPollState.Idle
    }
}

/** Refresh one stored trip from the server (Trips pull-to-refresh / per-trip). */
fun TravelViewModel.refreshStoredTrip(orderId: String) {
    viewModelScope.launch {
        val stored = repository.getBookedTrip(orderId) ?: return@launch
        val tripId = stored.tripId.ifBlank { stored.orderId }
        runCatching { TravelApi.trip(tripId) }
            .onSuccess { trip ->
                currentTrip = trip
                updateStoredTrip(trip)
            }
    }
}

internal suspend fun TravelViewModel.updateStoredTrip(trip: TripDto) {
    val stored = repository.getBookedTrip(trip.tripId) ?: return
    val ref = trip.bookingReference.ifBlank { stored.bookingReference }
    repository.upsertBookedTrip(
        stored.copy(
            bookingReference = ref,
            amount = trip.totalAmount,
            currency = trip.currency,
            status = trip.status,
            awaitingPayment = trip.status == TRIP_STATUS_PENDING_PAYMENT,
            failureMessage = trip.failureMessage.orEmpty(),
        )
    )
}

fun TravelViewModel.tripById(orderId: String): BookedTrip? = bookedTrips.value.find { it.orderId == orderId }

/**
 * Trip ids still awaiting payment/processing (for resume polling in
 * `MainActivity.onResume`). Reads Room directly so it works even before the
 * observed list has its first emission.
 */
suspend fun TravelViewModel.pendingTripIds(): List<String> =
    runCatching { repository.getAllBookedTrips() }.getOrDefault(emptyList())
        .filter {
            it.status == TRIP_STATUS_PENDING_PAYMENT || it.status == TRIP_STATUS_PROCESSING
        }
        .map { it.tripId.ifBlank { it.orderId } }
        .filter { it.isNotBlank() }

internal val bookingJson = Json { ignoreUnknownKeys = true }

/** Length of an ISO `YYYY-MM-DD` date prefix. */
private const val ISO_DATE_LENGTH = 10
