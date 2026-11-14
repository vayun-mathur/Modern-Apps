package com.vayunmathur.weather.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the forecast response we actually use, mapped from the
 * self-hosted `wx/v1/bundle`.
 *
 * We always request: current weather + hourly arrays + daily arrays in metric
 * units. Unit conversion for the UI happens at display time so swapping
 * Settings doesn't require a re-fetch.
 */
@Serializable
data class ForecastResponse(
    val latitude: Double,
    val longitude: Double,
    val timezone: String? = null,
    @SerialName("timezone_abbreviation") val timezoneAbbreviation: String? = null,
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val current: Current? = null,
    val hourly: Hourly? = null,
    val daily: Daily? = null,
    @SerialName("minutely_15") val minutely15: Minutely15? = null,
)

/**
 * Minimal bundle envelope used only to resolve the IANA
 * time zone (and its current abbreviation) for a coordinate.
 * Requested with `fields=tz`, which returns the envelope with no weather
 * data at all.
 */
@Serializable
data class RegionTimezone(
    val timezone: String? = null,
    @SerialName("timezone_abbreviation") val abbreviation: String? = null,
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
)

/**
 * Minimal bundle response used to show the current
 * temperature next to a city-search result. Requests only the `current`
 * section so the per-result lookups stay cheap.
 */
@Serializable
data class CurrentTemperatureResponse(
    val current: CurrentTemperature? = null,
)

/** Immutable value describing current temperature. */
@Serializable
data class CurrentTemperature(
    @SerialName("temperature_2m") val temperature: Double,
)

/** Immutable value describing minutely15. */
@Serializable
data class Minutely15(
    val time: List<String> = emptyList(),
    @SerialName("precipitation") val precipitation: List<Double> = emptyList(),
)

/** Immutable value describing current. */
@Serializable
data class Current(
    val time: String,
    @SerialName("temperature_2m") val temperature: Double,
    @SerialName("apparent_temperature") val apparentTemperature: Double,
    @SerialName("relative_humidity_2m") val relativeHumidity: Int,
    @SerialName("dew_point_2m") val dewPoint: Double = 0.0,
    @SerialName("weather_code") val weatherCode: Int,
    @SerialName("wind_speed_10m") val windSpeed: Double,
    @SerialName("wind_direction_10m") val windDirection: Int,
    @SerialName("pressure_msl") val pressureMsl: Double = 0.0,
    @SerialName("visibility") val visibility: Double = 0.0,
    @SerialName("cloud_cover") val cloudCover: Int = 0,
    @SerialName("wind_gusts_10m") val windGusts: Double = 0.0,
    @SerialName("is_day") val isDay: Int = 1,
)

/** Immutable value describing hourly. */
@Serializable
data class Hourly(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m") val temperature: List<Double> = emptyList(),
    @SerialName("apparent_temperature") val apparentTemperature: List<Double> = emptyList(),
    @SerialName("relative_humidity_2m") val relativeHumidity: List<Int> = emptyList(),
    @SerialName("dew_point_2m") val dewPoint: List<Double> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int> = emptyList(),
    @SerialName("precipitation_probability") val precipitationProbability: List<Int> = emptyList(),
    @SerialName("precipitation") val precipitation: List<Double> = emptyList(),
    @SerialName("wind_speed_10m") val windSpeed: List<Double> = emptyList(),
    @SerialName("wind_direction_10m") val windDirection: List<Int> = emptyList(),
    @SerialName("pressure_msl") val pressureMsl: List<Double> = emptyList(),
    @SerialName("visibility") val visibility: List<Double> = emptyList(),
    @SerialName("cloud_cover") val cloudCover: List<Int> = emptyList(),
    @SerialName("wind_gusts_10m") val windGusts: List<Double> = emptyList(),
    @SerialName("uv_index") val uvIndex: List<Double?> = emptyList(),
    @SerialName("is_day") val isDay: List<Int> = emptyList(),
)

/** Immutable value describing daily. */
@Serializable
data class Daily(
    val time: List<String> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int> = emptyList(),
    @SerialName("temperature_2m_max") val temperatureMax: List<Double> = emptyList(),
    @SerialName("temperature_2m_min") val temperatureMin: List<Double> = emptyList(),
    @SerialName("apparent_temperature_max") val apparentTemperatureMax: List<Double> = emptyList(),
    @SerialName("apparent_temperature_min") val apparentTemperatureMin: List<Double> = emptyList(),
    @SerialName("sunrise") val sunrise: List<String> = emptyList(),
    @SerialName("sunset") val sunset: List<String> = emptyList(),
    @SerialName("daylight_duration") val daylightDuration: List<Double> = emptyList(),
    @SerialName("sunshine_duration") val sunshineDuration: List<Double> = emptyList(),
    @SerialName("uv_index_max") val uvIndexMax: List<Double?> = emptyList(),
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int> = emptyList(),
    @SerialName("precipitation_sum") val precipitationSum: List<Double> = emptyList(),
    // 0/1 = new moon, 0.25 = first quarter, 0.5 = full, 0.75 = last quarter.
    @SerialName("moon_phase") val moonPhase: List<Double> = emptyList(),
    // Nullable elements: the moon skips a rise or a set roughly once a month,
    // since it comes up about 50 minutes later each day.
    @SerialName("moonrise") val moonrise: List<String?> = emptyList(),
    @SerialName("moonset") val moonset: List<String?> = emptyList(),
)
