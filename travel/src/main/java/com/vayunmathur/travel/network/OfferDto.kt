package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/**
 * A baggage allowance on a [SegmentDto] (per passenger). [type] is `carry_on`
 * or `checked`; [quantity] is how many are included.
 */
@Serializable
data class BaggageDto(
    val type: String = "",
    val quantity: Long = 0,
) {
    /** e.g. "1 carry-on", "2 checked". */
    val label: String
        get() {
            val name = when (type) {
                "carry_on" -> "carry-on"
                "checked" -> "checked"
                else -> type.replace('_', ' ')
            }
            return "$quantity $name"
        }
}

/**
 * A single operated leg of a [SliceDto], from `/api/travel/flights`.
 */
@Serializable
data class SegmentDto(
    val id: String = "",
    val carrier: String = "",
    val carrierIata: String = "",
    val flightNumber: String = "",
    val origin: String = "",
    val destination: String = "",
    val departureAt: String = "",
    val arrivalAt: String = "",
    val aircraft: String = "",
    val cabinClass: String = "",
    val baggages: List<BaggageDto> = emptyList(),
)

/**
 * A fare rule: whether an action (refund/change) is allowed before departure,
 * and if so the penalty.
 */
@Serializable
data class PenaltyRuleDto(
    val allowed: Boolean = false,
    val penaltyAmount: String? = null,
    val penaltyCurrency: String? = null,
)

/** Refund/change conditions carried on an [OfferDto] (or a [SliceDto]). */
@Serializable
data class ConditionsDto(
    val refundBeforeDeparture: PenaltyRuleDto? = null,
    val changeBeforeDeparture: PenaltyRuleDto? = null,
)

/**
 * One slice of an itinerary (outbound or return). [stops] is the number of
 * connections (`segments.size - 1`); [durationMinutes] is the whole-slice
 * travel time.
 */
@Serializable
data class SliceDto(
    val id: String = "",
    val origin: String = "",
    val destination: String = "",
    val departureAt: String = "",
    val arrivalAt: String = "",
    val durationMinutes: Long = 0,
    val stops: Long = 0,
    val segments: List<SegmentDto> = emptyList(),
    val conditions: ConditionsDto = ConditionsDto(),
    val fareBrandName: String = "",
)

/** A passenger carried on an offer (id + Duffel type + optional age). */
@Serializable
data class OfferPassengerDto(
    val id: String = "",
    val type: String = "",
    val age: Long? = null,
)

/**
 * A bookable flight offer from `/api/travel/flights` (or refreshed via
 * `/api/travel/offer`). [totalAmount] is a decimal string (Duffel returns money
 * as strings). [offerId] is the id passed back when booking, and [expiresAt] is
 * when the offer can no longer be booked at this price.
 */
@Serializable
data class OfferDto(
    val offerId: String = "",
    val totalAmount: String = "0",
    val currency: String = "USD",
    val owner: String = "",
    val ownerIata: String = "",
    val ownerLogoUrl: String = "",
    val expiresAt: String = "",
    val passengerIdentityDocumentsRequired: Boolean = false,
    val requiresInstantPayment: Boolean = true,
    val totalDurationMinutes: Long = 0,
    val passengerIds: List<String> = emptyList(),
    val passengers: List<OfferPassengerDto> = emptyList(),
    val conditions: ConditionsDto = ConditionsDto(),
    val availableServices: List<ServiceDto> = emptyList(),
    val fareBrand: String = "",
    val slices: List<SliceDto> = emptyList(),
) {
    /** Distinct marketing airline IATA codes across all segments. */
    val airlineIatas: List<String>
        get() = slices.flatMap { it.segments }.map { it.carrierIata }.filter { it.isNotBlank() }.distinct()

    /** Included baggage summary from the first segment, e.g. "1 carry-on · 2 checked". */
    val baggageSummary: String
        get() = slices.firstOrNull()?.segments?.firstOrNull()?.baggages
            ?.filter { it.quantity > 0 }
            ?.joinToString(" · ") { it.label }
            .orEmpty()
}

/** A flight search response: the offer-request id plus the mapped offers. */
@Serializable
data class OfferSearchDto(
    val offerRequestId: String = "",
    val offers: List<OfferDto> = emptyList(),
)

/**
 * A step in a partial (step-by-step) offer request: the request [id] plus the
 * offers for the current leg (or the final orderable fares). Each partial offer
 * carries a single slice.
 */
@Serializable
data class PartialOfferDto(
    val id: String = "",
    val offers: List<OfferDto> = emptyList(),
)
