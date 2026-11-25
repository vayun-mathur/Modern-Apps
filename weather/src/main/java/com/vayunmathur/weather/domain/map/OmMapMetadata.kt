package com.vayunmathur.weather.domain.map

import com.vayunmathur.weather.domain.WeatherMetric
import com.vayunmathur.weather.domain.bundleHourlyVar
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Metadata for the shaded map, derived on device from the static R2 run
 * tiles ([com.vayunmathur.weather.network.WxStatic.mapMetadata]): the
 * model run, the forecast hours its tiles hold, and the variables it can
 * shade. No origin `data_spatial` read remains.
 *
 * - [referenceTime] is the model run.
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

/** The grid variable name backing [WeatherMetric]; identical to the bundle hourly name. */
val WeatherMetric.omVariable: String
    get() = bundleHourlyVar

private const val WIND_U = "wind_u_component_10m"
private const val WIND_V = "wind_v_component_10m"
