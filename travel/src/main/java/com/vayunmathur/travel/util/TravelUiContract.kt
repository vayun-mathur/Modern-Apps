package com.vayunmathur.travel.util

import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.SeatElementDto
import com.vayunmathur.travel.network.StaySearchResultDto

/**
 * The UI contract for the screens the store listing images are rendered from.
 *
 * Those screens take state values plus an actions interface rather than [TravelViewModel]
 * and the nav back stack, so they can be rendered by a `@Preview` — see
 * `src/screenshotTest`. The `*Page` composables stay as thin binders over the ViewModel;
 * everything below lives in `util` alongside the existing `*State` classes, so `ui` depends
 * on `util` and never the reverse.
 *
 * Navigation is part of the actions rather than a separate set of lambdas: every one of
 * these screens has a back arrow plus at least one forward destination, and the binder is
 * the single place that knows about both the ViewModel and the back stack.
 */

/**
 * Everything the order-detail screen draws: the booked trip, plus refresh and
 * complete-payment actions while the payment is still pending.
 */
data class OrderDetailUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val order: OrderDetailDto? = null,
    val refreshing: Boolean = false,
    val checkout: CheckoutState = CheckoutState.Idle,
)

/**
 * Flight-results callbacks. Every method has a no-op default so a preview can render the
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface FlightResultsActions {
    fun setSort(sort: OfferSort) {}
    fun setMaxStopsFilter(maxStops: Int?) {}
    fun toggleAirlineFilter(iata: String) {}
    fun setFareBrandFilter(fareBrand: String?) {}

    /** Re-run the current search, which is how lapsed offers are refreshed. */
    fun refreshOffers() {}

    fun openOffer(offer: OfferDto) {}
    fun back() {}

    companion object {
        val Noop: FlightResultsActions = object : FlightResultsActions {}
    }
}

/** Seat-map callbacks. Same no-op-default arrangement as [FlightResultsActions]. */
interface SeatMapActions {
    /** Tap [seat] on [segmentId] for the passenger currently being seated. */
    fun toggleSeat(segmentId: String, passengerId: String, seat: SeatElementDto) {}

    /** Finish picking seats and return to the ancillaries step. */
    fun done() {}

    fun back() {}

    companion object {
        val Noop: SeatMapActions = object : SeatMapActions {}
    }
}

/** Stay-results callbacks. Same no-op-default arrangement as [FlightResultsActions]. */
interface StayResultsActions {
    fun openStay(result: StaySearchResultDto) {}
    fun back() {}

    companion object {
        val Noop: StayResultsActions = object : StayResultsActions {}
    }
}

/** Order-detail callbacks. Same no-op-default arrangement as [FlightResultsActions]. */
interface OrderDetailActions {
    fun refresh() {}
    fun completePayment() {}
    fun acceptNewPrice() {}
    fun openChange(orderId: String) {}
    fun openCancel(orderId: String) {}
    fun back() {}

    companion object {
        val Noop: OrderDetailActions = object : OrderDetailActions {}
    }
}
