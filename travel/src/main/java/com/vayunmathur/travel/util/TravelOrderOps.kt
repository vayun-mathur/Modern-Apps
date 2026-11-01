package com.vayunmathur.travel.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.data.TRIP_STATUS_CANCELLED
import com.vayunmathur.travel.domain.isCheckoutUnexpired
import com.vayunmathur.travel.network.ChangeRequestInputDto
import com.vayunmathur.travel.network.SearchSliceInputDto
import com.vayunmathur.travel.network.TravelOrderApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STATUS_POLL_ROUNDS = 20
private const val STATUS_POLL_INTERVAL_MS = 3_000L

/**
 * Load booking detail for a stored trip: booked trips use the booking
 * reference + last name (`GET /bookings/:ref`), while still-pending trips
 * poll the trip endpoint.
 */
fun TravelViewModel.loadBookingDetail(orderId: String) {
    orderDetailMutable.value = OrderDetailState(loading = true)
    viewModelScope.launch {
        val stored = repository.getBookedTrip(orderId)
        if (stored == null) {
            orderDetailMutable.value = OrderDetailState(error = "Trip not found.")
            return@launch
        }
        if (stored.bookingReference.isBlank()) {
            // Not yet booked: refresh the trip and report its status.
            runCatching { com.vayunmathur.travel.network.TravelApi.trip(stored.tripId.ifBlank { stored.orderId }) }
                .onSuccess { trip ->
                    updateStoredTrip(trip)
                    orderDetailMutable.value = OrderDetailState(
                        error = "Payment is still pending for this trip."
                    )
                }
                .onFailure { orderDetailMutable.value = OrderDetailState(error = errorMessage(it)) }
            return@launch
        }
        runCatching { TravelOrderApi.bookingDetail(stored.bookingReference, stored.lastName) }
            .onSuccess { detail ->
                // Keep the local amount/status in sync with servicing reads.
                repository.upsertBookedTrip(
                    stored.copy(
                        amount = detail.totalAmount,
                        currency = detail.currency,
                        status = detail.status,
                    )
                )
                orderDetailMutable.value = OrderDetailState(order = detail)
            }
            .onFailure { orderDetailMutable.value = OrderDetailState(error = errorMessage(it)) }
    }
}

/** Refresh the currently shown booking detail. */
fun TravelViewModel.refreshBookingDetail(orderId: String) {
    val current = orderDetailMutable.value
    orderDetailMutable.value = current.copy(loading = true, error = null)
    loadBookingDetail(orderId)
}

/**
 * Re-run checkout for a stored pending trip to get a fresh payment intent
 * (used by "Complete payment" while the checkout has not expired).
 */
fun TravelViewModel.recheckoutForStoredTrip(orderId: String) {
    val stored = bookedTrips.value.find { it.orderId == orderId } ?: return
    if (!isCheckoutUnexpired(stored.checkoutExpiresAt)) {
        checkoutMutable.value = CheckoutState.Error("This booking session has expired. Please start a new booking.")
        return
    }
    val tripId = stored.tripId.ifBlank { stored.orderId }
    currentTrip = currentTrip?.takeIf { it.tripId == tripId }
    checkoutMutable.value = CheckoutState.Loading
    viewModelScope.launch {
        runCatching { com.vayunmathur.travel.network.TravelApi.checkout(tripId) }
            .onSuccess { checkout ->
                checkoutMutable.value = CheckoutState.Ready(checkout)
                repository.upsertBookedTrip(
                    stored.copy(
                        amount = checkout.totalAmount,
                        currency = checkout.currency,
                        checkoutExpiresAt = checkout.expiresAt,
                    )
                )
            }
            .onFailure { checkoutMutable.value = CheckoutState.Error(errorMessage(it)) }
    }
}

fun TravelViewModel.resetCancellation() {
    cancellationMutable.value = CancellationState()
    cancelPollJob?.cancel()
    cancelPollJob = null
}

/**
 * Fetch a cancellation preview for a stored trip (refund, penalty,
 * commitable/message).
 */
fun TravelViewModel.quoteCancellation(orderId: String) {
    cancellationMutable.value = CancellationState(loading = true)
    viewModelScope.launch {
        val stored = repository.getBookedTrip(orderId)
        if (stored == null || stored.bookingReference.isBlank()) {
            cancellationMutable.value = CancellationState(error = "This trip can't be cancelled.")
            return@launch
        }
        runCatching { TravelOrderApi.cancelPreview(stored.bookingReference, stored.lastName) }
            .onSuccess { cancellationMutable.value = CancellationState(quote = it) }
            .onFailure { cancellationMutable.value = CancellationState(error = errorMessage(it)) }
    }
}

/**
 * Confirm the pending cancellation, then poll until succeeded/failed and
 * update the local trip.
 */
fun TravelViewModel.confirmCancellation(orderId: String) {
    val quote = cancellationMutable.value.quote ?: return
    cancellationMutable.value = cancellationMutable.value.copy(confirming = true, error = null)
    viewModelScope.launch {
        val stored = repository.getBookedTrip(orderId)
        val confirmed = runCatching { TravelOrderApi.confirmCancellation(quote.id) }.getOrNull()
        if (confirmed == null) {
            cancellationMutable.value = cancellationMutable.value.copy(
                confirming = false,
                error = errorMessage(IllegalStateException("Cancellation failed. Please try again."))
            )
            return@launch
        }
        if (confirmed.status == "succeeded") {
            stored?.let { repository.upsertBookedTrip(it.copy(status = TRIP_STATUS_CANCELLED, awaitingPayment = false)) }
            cancellationMutable.value = cancellationMutable.value.copy(confirming = false, done = true)
            return@launch
        }
        if (confirmed.status == "failed") {
            cancellationMutable.value = cancellationMutable.value.copy(
                confirming = false,
                error = confirmed.message ?: "Cancellation failed. Please try again."
            )
            return@launch
        }
        // In progress: poll.
        cancellationMutable.value = cancellationMutable.value.copy(confirming = false, polling = true)
        cancelPollJob?.cancel()
        cancelPollJob = viewModelScope.launch {
            repeat(STATUS_POLL_ROUNDS) { round ->
                if (round > 0) delay(STATUS_POLL_INTERVAL_MS)
                val ref = stored?.bookingReference.orEmpty()
                val status = runCatching {
                    TravelOrderApi.cancellationStatus(confirmed.id, ref, stored?.lastName.orEmpty())
                }.getOrNull() ?: return@launch
                if (status.status == "succeeded") {
                    stored?.let {
                        repository.upsertBookedTrip(it.copy(status = TRIP_STATUS_CANCELLED, awaitingPayment = false))
                    }
                    cancellationMutable.value = cancellationMutable.value.copy(polling = false, done = true)
                    return@launch
                }
                if (status.status == "failed") {
                    cancellationMutable.value = cancellationMutable.value.copy(
                        polling = false,
                        error = status.message ?: "Cancellation failed. Please try again."
                    )
                    return@launch
                }
            }
            cancellationMutable.value = cancellationMutable.value.copy(
                polling = false,
                error = "Still processing. Check back shortly."
            )
        }
    }
}

fun TravelViewModel.resetChange() {
    changeMutable.value = ChangeState()
    changePollJob?.cancel()
    changePollJob = null
}

/** Request a change: remove [removeSliceId] and add a slice on [newDate]. */
fun TravelViewModel.requestChange(
    orderId: String,
    removeSliceId: String,
    origin: String,
    destination: String,
    newDate: String,
    cabin: String,
) {
    changeMutable.value = ChangeState(loading = true)
    viewModelScope.launch {
        val stored = repository.getBookedTrip(orderId)
        if (stored == null || stored.bookingReference.isBlank()) {
            changeMutable.value = ChangeState(requested = true, error = "This trip can't be changed.")
            return@launch
        }
        runCatching {
            TravelOrderApi.changeRequest(
                stored.bookingReference,
                stored.lastName,
                ChangeRequestInputDto(
                    removeSliceIds = listOf(removeSliceId),
                    add = listOf(SearchSliceInputDto(origin = origin, destination = destination, date = newDate)),
                    cabin = cabin.ifBlank { null },
                ),
            )
        }
            .onSuccess { changeMutable.value = ChangeState(requested = true, offers = it.offers) }
            .onFailure { changeMutable.value = ChangeState(requested = true, error = errorMessage(it)) }
    }
}

/** Price a change offer (pay / refund / even) before confirming. */
fun TravelViewModel.priceChange(orderId: String, offerId: String) {
    changeMutable.value = changeMutable.value.copy(loading = true, error = null, pricingOfferId = offerId)
    viewModelScope.launch {
        val stored = repository.getBookedTrip(orderId)
        if (stored == null || stored.bookingReference.isBlank()) {
            changeMutable.value = changeMutable.value.copy(loading = false, error = "This trip can't be changed.")
            return@launch
        }
        runCatching { TravelOrderApi.changePrice(offerId, stored.bookingReference, stored.lastName) }
            .onSuccess { changeMutable.value = changeMutable.value.copy(loading = false, price = it) }
            .onFailure { changeMutable.value = changeMutable.value.copy(loading = false, error = errorMessage(it)) }
    }
}

/**
 * Confirm a change offer: when an extra payment is due the server returns a
 * checkoutUrl (open it in a Custom Tab, then poll); otherwise the change is
 * done and the local trip amount updates.
 */
fun TravelViewModel.confirmChange(orderId: String, offerId: String) {
    changeMutable.value = changeMutable.value.copy(confirming = true, error = null)
    viewModelScope.launch {
        val stored = repository.getBookedTrip(orderId)
        if (stored == null || stored.bookingReference.isBlank()) {
            changeMutable.value = changeMutable.value.copy(confirming = false, error = "This trip can't be changed.")
            return@launch
        }
        runCatching { TravelOrderApi.confirmChange(offerId, stored.bookingReference, stored.lastName) }
            .onSuccess { result ->
                if (!result.checkoutUrl.isNullOrBlank()) {
                    changeMutable.value = changeMutable.value.copy(
                        confirming = false,
                        checkoutUrl = result.checkoutUrl,
                        polling = true,
                    )
                    pollChangeStatus(stored.bookingReference, stored.lastName)
                } else {
                    val price = changeMutable.value.price
                    if (price != null && price.newFareAmount.isNotBlank()) {
                        repository.upsertBookedTrip(
                            stored.copy(amount = price.newFareAmount, currency = price.currency)
                        )
                    }
                    changeMutable.value = changeMutable.value.copy(confirming = false, done = true)
                }
            }
            .onFailure {
                changeMutable.value = changeMutable.value.copy(confirming = false, error = errorMessage(it))
            }
    }
}

private fun TravelViewModel.pollChangeStatus(ref: String, lastName: String) {
    changePollJob?.cancel()
    changePollJob = viewModelScope.launch {
        repeat(STATUS_POLL_ROUNDS) { round ->
            if (round > 0) delay(STATUS_POLL_INTERVAL_MS)
            val status = runCatching { TravelOrderApi.changeStatus(ref, lastName) }.getOrNull()
                ?: return@launch
            if (status.status == "confirmed") {
                changeMutable.value = changeMutable.value.copy(polling = false, done = true)
                return@launch
            }
            if (status.status == "failed") {
                changeMutable.value = changeMutable.value.copy(
                    polling = false,
                    error = "Change failed. Please try again."
                )
                return@launch
            }
        }
        changeMutable.value = changeMutable.value.copy(
            polling = false,
            error = "Still processing. Check back shortly."
        )
    }
}
