package com.vayunmathur.weather.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the air-quality response we use, mapped from the self-hosted
 * `wx/v1/bundle` `aq` section.
 *
 * Provides US AQI (0..500 with 0..50 = good, 51..100 = moderate, …) plus
 * tree/grass/weed/ragweed/birch/olive/mugwort/alder pollen concentrations
 * (grains/m³). We collapse pollens into the standard three "categories"
 * for display: grass, tree (= max of tree species), weed (= max of weed
 * species). The section is absent outside coverage areas.
 */
@Serializable
data class AirQualityResponse(val current: AirQualityCurrent? = null)

/** Immutable value describing air quality current. */
@Serializable
data class AirQualityCurrent(
    val time: String,
    @SerialName("us_aqi") val usAqi: Int? = null,
    @SerialName("alder_pollen") val alderPollen: Double? = null,
    @SerialName("birch_pollen") val birchPollen: Double? = null,
    @SerialName("grass_pollen") val grassPollen: Double? = null,
    @SerialName("mugwort_pollen") val mugwortPollen: Double? = null,
    @SerialName("olive_pollen") val olivePollen: Double? = null,
    @SerialName("ragweed_pollen") val ragweedPollen: Double? = null,
)
