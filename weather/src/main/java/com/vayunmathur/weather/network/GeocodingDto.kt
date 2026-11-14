package com.vayunmathur.weather.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Place-search response: on-device catalogue rows ([GeoDatabase.searchPlaces])
 * in the same shape city search has always shown, also reused by the
 * `get_weather_by_name` intent.
 */
@Serializable
data class GeocodingResponse(val results: List<GeocodingResult> = emptyList())

/** Immutable value describing geocoding result. */
@Serializable
data class GeocodingResult(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    @SerialName("admin1") val admin1: String? = null,
    val timezone: String? = null,
)
