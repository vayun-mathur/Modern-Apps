package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/**
 * A car-rental offer from `/api/travel/cars`. Coverage is limited upstream, so
 * this is deep-link-first: [price] is often 0 (unknown) and the UI shows a
 * "See prices" affordance that opens [bookingUrl].
 */
@Serializable
data class CarDto(
    val name: String = "",
    val price: Double = 0.0,
    val currency: String = "usd",
    val provider: String = "",
    val bookingUrl: String = "",
)
