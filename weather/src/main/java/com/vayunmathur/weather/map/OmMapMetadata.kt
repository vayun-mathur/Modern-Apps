package com.vayunmathur.weather.map

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.weather.util.WeatherMetric
import com.vayunmathur.weather.util.openMeteoHourlyVar
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Metadata for an Open-Meteo spatial model, from
 * `https://map-tiles.open-meteo.com/data_spatial/<model>/latest.json`.
 *
 * - [referenceTime] is the model run (drives the `.om` folder path).
 * - [validTimes] are the hourly forecast steps (drive the time slider).
 * - [variables] is the availability list for this run (drives which measures
 *   we can shade).
 */
@Serializable
data class OmMapMetadata(
    @SerialName("reference_time") val referenceTime: String,
    @SerialName("valid_times") val validTimes: List<String> = emptyList(),
    val variables: List<String> = emptyList(),
) {
    /** Whether [metric] can be shaded for this run (handles derived wind speed). */
    fun supports(metric: WeatherMetric): Boolean = when (metric) {
        WeatherMetric.WindSpeed ->
            WIND_U in variables && WIND_V in variables
        else -> metric.omVariable in variables
    }
}

/** The `.om` variable name backing [WeatherMetric]; identical to the JSON API name. */
val WeatherMetric.omVariable: String
    get() = openMeteoHourlyVar

private const val WIND_U = "wind_u_component_10m"
private const val WIND_V = "wind_v_component_10m"

private const val SPATIAL_BASE = "https://map-tiles.open-meteo.com/data_spatial"

private val metadataJson = Json { ignoreUnknownKeys = true }

/**
 * Fetch the latest-run metadata for [domain]. The `variable` query param is
 * only a hint — the response lists every variable in the run — so we pass a
 * stable one. Returns `null` on network/parse failure.
 *
 * The bucket serves `latest.json` as `application/octet-stream`, so we can't use
 * Ktor content negotiation (it only deserializes matching content types); we
 * fetch the raw body and parse it ourselves.
 */
suspend fun fetchOmMapMetadata(domain: OmDomain): OmMapMetadata? {
    val url = "$SPATIAL_BASE/${domain.model}/latest.json?variable=temperature_2m"
    return runCatching {
        val response = NetworkClient.performRequest(url)
        if (!response.isSuccess) return null
        metadataJson.decodeFromString<OmMapMetadata>(response.body)
    }.getOrNull()
}

/**
 * Build the `.om` file URL for [domain] at model run [referenceTime] and step
 * [validTime].
 *
 * `reference_time` `2026-07-01T18:00:00Z` → folder `2026/07/01/1800Z`;
 * `valid_time` `2026-07-01T18:00Z` → filename `2026-07-01T1800.om`.
 */
fun omFileUrl(domain: OmDomain, referenceTime: String, validTime: String): String {
    val year = referenceTime.substring(0, 4)
    val month = referenceTime.substring(5, 7)
    val day = referenceTime.substring(8, 10)
    val hour = referenceTime.substring(11, 13)
    val minute = referenceTime.substring(14, 16)
    val folder = "$year/$month/$day/$hour${minute}Z"
    val file = validTime.replace(":", "").removeSuffix("Z")
    return "$SPATIAL_BASE/${domain.model}/$folder/$file.om"
}
