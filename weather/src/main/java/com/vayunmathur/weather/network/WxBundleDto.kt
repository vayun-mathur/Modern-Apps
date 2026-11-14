package com.vayunmathur.weather.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the self-hosted `GET /wx/v1/bundle` (custom versioned spec).
 *
 * Design rules (server guarantees): no time arrays — every regular series is
 * `{start, step, count, values}` with Unix-epoch `start`; epochs, never
 * local-ISO strings; absent sections are missing (never explicit null);
 * floats rounded to 2 decimals server-side; all 6 pollen species kept.
 *
 * The mapper ([WxBundleMapper]) expands these into the existing
 * [ForecastResponse]/[AirQualityResponse] types, so `domain/`, `platform/`,
 * `ui/`, `data/`, `intents/`, widgets, and the Room cache work byte-identical.
 */
@Serializable
data class WxBundle(
    val v: Int = 1,
    val lat: Double,
    val lon: Double,
    val tz: String = "UTC",
    @SerialName("tz_abbr") val tzAbbr: String = "UTC",
    @SerialName("utc_offset") val utcOffset: Int = 0,
    val model: String = "dwd_icon",
    val run: String = "",
    val current: WxCurrent? = null,
    val hourly: WxHourly? = null,
    val daily: WxDaily? = null,
    val min15: WxMin15? = null,
    val aq: WxAq? = null,
)

/** Immutable value describing wx current. */
@Serializable
data class WxCurrent(
    val time: Long,
    val temp: Double,
    val feels: Double,
    val rh: Int,
    val dew: Double,
    val code: Int,
    @SerialName("wind_spd") val windSpd: Double,
    @SerialName("wind_dir") val windDir: Int,
    val pres: Double,
    val vis: Double,
    val cloud: Int,
    val gust: Double,
    val day: Int,
)

/** Immutable value describing wx hourly. */
@Serializable
data class WxHourly(
    val start: Long,
    val step: Long = 3600,
    val count: Int = 0,
    val temp: List<Double> = emptyList(),
    val feels: List<Double> = emptyList(),
    val rh: List<Int> = emptyList(),
    val dew: List<Double> = emptyList(),
    val code: List<Int> = emptyList(),
    val pprob: List<Int> = emptyList(),
    val precip: List<Double> = emptyList(),
    @SerialName("wind_spd") val windSpd: List<Double> = emptyList(),
    @SerialName("wind_dir") val windDir: List<Int> = emptyList(),
    val pres: List<Double> = emptyList(),
    val vis: List<Double> = emptyList(),
    /** Nullable per element: CAMS UV coverage (~4d) is shorter than the 14-day window. */
    val uv: List<Double?> = emptyList(),
    val cloud: List<Int> = emptyList(),
    val gust: List<Double> = emptyList(),
    val day: List<Int> = emptyList(),
)

/** Immutable value describing wx daily. */
@Serializable
data class WxDaily(
    val start: String = "",
    val count: Int = 0,
    val code: List<Int> = emptyList(),
    val tmax: List<Double> = emptyList(),
    val tmin: List<Double> = emptyList(),
    @SerialName("feels_max") val feelsMax: List<Double> = emptyList(),
    @SerialName("feels_min") val feelsMin: List<Double> = emptyList(),
    val sunrise: List<Long> = emptyList(),
    val sunset: List<Long> = emptyList(),
    val daylight: List<Long> = emptyList(),
    val sunshine: List<Long> = emptyList(),
    @SerialName("uv_max") val uvMax: List<Double?> = emptyList(),
    @SerialName("pprob_max") val pprobMax: List<Int> = emptyList(),
    @SerialName("precip_sum") val precipSum: List<Double> = emptyList(),
    @SerialName("moon_phase") val moonPhase: List<Double> = emptyList(),
    val moonrise: List<Long?> = emptyList(),
    val moonset: List<Long?> = emptyList(),
)

/** Immutable value describing wx min15. */
@Serializable
data class WxMin15(
    val start: Long,
    val step: Long = 900,
    val count: Int = 0,
    val precip: List<Double> = emptyList(),
)

/** Immutable value describing wx aq. */
@Serializable
data class WxAq(
    val time: Long,
    @SerialName("us_aqi") val usAqi: Int? = null,
    val grass: Double? = null,
    val birch: Double? = null,
    val olive: Double? = null,
    val alder: Double? = null,
    val ragweed: Double? = null,
    val mugwort: Double? = null,
)

/** P4 batch response: current-hour temp/code/is_day per requested point. */
@Serializable
data class WxCurrentBatch(
    val v: Int = 1,
    val points: List<WxCurrentPoint> = emptyList(),
)

/** Immutable value describing wx current point. */
@Serializable
data class WxCurrentPoint(
    val lat: Double,
    val lon: Double,
    val temp: Double,
    val code: Int,
    val day: Int,
)
