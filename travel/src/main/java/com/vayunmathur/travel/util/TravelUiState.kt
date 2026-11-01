package com.vayunmathur.travel.util

import com.vayunmathur.travel.network.CancellationDto
import com.vayunmathur.travel.network.ChangeOfferDto
import com.vayunmathur.travel.network.ChangePriceDto
import com.vayunmathur.travel.network.CheckoutDto
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.SeatCabinDto
import com.vayunmathur.travel.network.StayBookingResultDto
import com.vayunmathur.travel.network.StayRatesDto
import com.vayunmathur.travel.network.StaySearchResultDto
import com.vayunmathur.travel.network.TripAncillariesDto
import com.vayunmathur.travel.network.TripDto

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
) {
    /** Single-slice legs (multi-city searches run one per leg). */
    val legs: List<String>
        get() = slices.split(',').map { it.trim() }.filter { it.isNotBlank() }
}

/** On-device sort options for the offer list (no server round-trip). */
enum class OfferSort(val key: String?, @androidx.annotation.StringRes val label: Int) {
    BEST(null, com.vayunmathur.travel.R.string.sort_best),
    CHEAPEST("total_amount", com.vayunmathur.travel.R.string.sort_cheapest),
    FASTEST("total_duration", com.vayunmathur.travel.R.string.sort_fastest),
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
) {
    /** Offers after applying the client-side filters. */
    val visibleOffers: List<OfferDto>
        get() = com.vayunmathur.travel.domain.sortOffers(
            com.vayunmathur.travel.domain.filterOffers(allOffers, filters),
            sort
        )

    /** Distinct airline IATA codes present in the results, for the filter UI. */
    val availableAirlines: List<String>
        get() = allOffers.flatMap { it.airlineIatas }.distinct().sorted()

    /** Distinct fare brands present in the results, for the filter UI. */
    val availableFareBrands: List<String>
        get() = allOffers.map { it.fareBrand }.filter { it.isNotBlank() }.distinct().sorted()
}

/** State for the single-offer review screen. */
data class OfferReviewState(
    val loading: Boolean = false,
    val error: String? = null,
    val offer: OfferDto? = null,
)

/**
 * State for the round-trip partial flow, grouped on-device from the
 * round-trip offers: step 1 distinct outbound slices ([step] 0), step 2
 * inbound slices for the chosen outbound ([step] 1), step 3 fares for the
 * pair ([step] 2). [outboundSignature]/[inboundSignature] thread the
 * selection between steps.
 */
data class PartialFlowState(
    val loading: Boolean = false,
    val error: String? = null,
    val allOffers: List<OfferDto> = emptyList(),
    val step: Int = 0,
    val outboundSignature: String = "",
    val inboundSignature: String = "",
    val offers: List<OfferDto> = emptyList(),
)

/** State for the trip ancillaries step (bags + seat maps off the trip). */
data class AncillariesState(
    val loading: Boolean = false,
    val error: String? = null,
    val ancillaries: TripAncillariesDto? = null,
    /** True while the seat maps are still pricing (retry available). */
    val seatMapsLoading: Boolean = false,
)

/** State for the seat-map screen. */
data class SeatMapState(
    val loading: Boolean = false,
    val error: String? = null,
    val cabins: List<SeatCabinDto> = emptyList(),
)

/** Booking lifecycle for the trip → checkout flow. */
sealed interface BookingState {
    data object Idle : BookingState
    data object Loading : BookingState
    /** Trip created; ancillaries step (or checkout when none) comes next. */
    data class TripReady(val trip: TripDto) : BookingState
    data class Error(val message: String) : BookingState
    /** Checkout demands missing traveler details: back to passengers with these fields. */
    data class MissingDetails(val missingFields: List<String>, val message: String) : BookingState
}

/** Lifecycle for the checkout step (payment intent → card sheet → confirm). */
sealed interface CheckoutState {
    data object Idle : CheckoutState
    data object Loading : CheckoutState
    /** Checkout link ready; [acceptedNewPrice] gates "Pay securely" when the price changed. */
    data class Ready(val checkout: CheckoutDto, val acceptedNewPrice: Boolean = false) : CheckoutState
    data class Error(val message: String) : CheckoutState
}

/** Lifecycle for polling a trip after checkout (resumed / refresh). */
sealed interface TripPollState {
    data object Idle : TripPollState
    data object Polling : TripPollState
    data class Done(val trip: TripDto) : TripPollState
    data class Error(val message: String) : TripPollState
}

/** Lifecycle for the pay-later action on a hold order. */
sealed interface PaymentActionState {
    data object Idle : PaymentActionState
    data object Loading : PaymentActionState
    data object Success : PaymentActionState
    data class Error(val message: String) : PaymentActionState
}

/** State for a single booked-trip detail screen (local + remote). */
data class OrderDetailState(
    val loading: Boolean = false,
    val error: String? = null,
    val order: OrderDetailDto? = null,
)

/** State for the cancellation flow (preview → confirm → poll). */
data class CancellationState(
    val loading: Boolean = false,
    val error: String? = null,
    val quote: CancellationDto? = null,
    val confirming: Boolean = false,
    val polling: Boolean = false,
    val done: Boolean = false,
)

/** State for the change flow (request → offers → price → confirm → poll). */
data class ChangeState(
    val loading: Boolean = false,
    val error: String? = null,
    val requested: Boolean = false,
    val offers: List<ChangeOfferDto> = emptyList(),
    val price: ChangePriceDto? = null,
    val pricingOfferId: String = "",
    val confirming: Boolean = false,
    val checkoutUrl: String? = null,
    val polling: Boolean = false,
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
