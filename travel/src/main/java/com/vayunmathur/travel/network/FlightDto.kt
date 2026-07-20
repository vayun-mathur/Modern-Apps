package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/**
 * A single cheapest-fare flight offer from `/api/travel/flights`.
 *
 * Note: Aviasales data is *cached aggregated prices* (cheapest fare seen per
 * route/date), not live availability, so [price] is indicative. [bookingUrl] is
 * the affiliate deep-link that opens the provider to complete the booking.
 */
@Serializable
data class FlightDto(
    val price: Double = 0.0,
    val currency: String = "usd",
    val origin: String = "",
    val destination: String = "",
    val departureAt: String = "",
    val returnAt: String? = null,
    val airline: String = "",
    val flightNumber: String = "",
    val transfers: Int = 0,
    val bookingUrl: String = "",
)
