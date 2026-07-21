package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/** Reference data for an airline (name + logo URLs), from `/api/travel/airlines`. */
@Serializable
data class AirlineDto(
    val name: String = "",
    val iataCode: String = "",
    val logoSymbolUrl: String = "",
    val logoLockupUrl: String = "",
)

/** Reference data for an airport, from `/api/travel/airport`. */
@Serializable
data class AirportDto(
    val iataCode: String = "",
    val name: String = "",
    val cityName: String = "",
    val iataCountryCode: String = "",
    val timeZone: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
)
