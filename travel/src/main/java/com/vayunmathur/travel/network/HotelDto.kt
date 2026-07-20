package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/** A single hotel offer from `/api/travel/hotels`. */
@Serializable
data class HotelDto(
    val name: String = "",
    val stars: Int = 0,
    val price: Double = 0.0,
    val currency: String = "usd",
    val location: String = "",
    val bookingUrl: String = "",
)
