package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/**
 * A single operated leg of a [SliceDto], from `/api/travel/flights`.
 */
@Serializable
data class SegmentDto(
    val carrier: String = "",
    val flightNumber: String = "",
    val origin: String = "",
    val destination: String = "",
    val departureAt: String = "",
    val arrivalAt: String = "",
)

/**
 * One slice of an itinerary (outbound or return). [stops] is the number of
 * connections (`segments.size - 1`); [durationMinutes] is the whole-slice
 * travel time.
 */
@Serializable
data class SliceDto(
    val origin: String = "",
    val destination: String = "",
    val departureAt: String = "",
    val arrivalAt: String = "",
    val durationMinutes: Long = 0,
    val stops: Long = 0,
    val segments: List<SegmentDto> = emptyList(),
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
    val expiresAt: String = "",
    val passengerIds: List<String> = emptyList(),
    val slices: List<SliceDto> = emptyList(),
)

/** A flight search response: the offer-request id plus the mapped offers. */
@Serializable
data class OfferSearchDto(
    val offerRequestId: String = "",
    val offers: List<OfferDto> = emptyList(),
)
