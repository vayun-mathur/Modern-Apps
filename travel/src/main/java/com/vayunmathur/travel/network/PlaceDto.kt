package com.vayunmathur.travel.network

import kotlinx.serialization.Serializable

/**
 * An airport or city returned by `/api/travel/places`. [code] is the IATA
 * airport code (or a city code) the flight/hotel searches take as input;
 * [kind] is `"airport"` or `"city"`.
 */
@Serializable
data class PlaceDto(
    val code: String = "",
    val name: String = "",
    val city: String = "",
    val country: String = "",
    val type: String = "",
) {
    /** A one-line label for autocomplete rows, e.g. "JFK · New York, United States". */
    val label: String
        get() = buildString {
            append(code)
            if (name.isNotBlank() && name != code) append(" · ").append(name)
            val place = listOf(city, country).filter { it.isNotBlank() && it != name }
            if (place.isNotEmpty()) append(", ").append(place.joinToString(", "))
        }
}
