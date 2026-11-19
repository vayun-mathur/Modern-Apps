package com.vayunmathur.weather.widget.glance

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.weather.data.weatherJson
import com.vayunmathur.weather.domain.roundCoord
import com.vayunmathur.weather.network.ForecastResponse

/**
 * Snapshot of cached weather used by the widgets. Populated from the
 * most-recent [com.vayunmathur.weather.data.WeatherCache] row for the
 * user's primary saved location (preferring the device-location row).
 * `null` while no forecast has been cached yet — the widget renders a
 * dash in that case rather than failing to load.
 */
data class WidgetWeather(
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val isDay: Boolean,
)

// Broad catch is deliberate: a snapshot miss renders as a dash, and Room plus
// kotlinx.serialization throw undocumented RuntimeExceptions (not just IOException).
@Suppress("TooGenericExceptionCaught")
internal suspend fun loadWeatherSnapshot(context: Context): WidgetWeather? {
    return try {
        val repo = WeatherRepository.get(context)
        // Prefer the device-current row, fall back to the first pinned location.
        val location = repo.getCurrentDeviceLocation()
            ?: repo.getLocations().firstOrNull()
            ?: return null
        val cache = repo.getCache(
            roundCoord(location.latitude),
            roundCoord(location.longitude),
        ) ?: return null
        val forecast = runCatching {
            weatherJson.decodeFromString<ForecastResponse>(cache.forecastJson)
        }.getOrNull() ?: return null
        val current = forecast.current ?: return null
        WidgetWeather(
            temperatureCelsius = current.temperature,
            weatherCode = current.weatherCode,
            isDay = current.isDay != 0,
        )
    } catch (e: Exception) {
        Log.dev("WeatherWidget", "no cached snapshot yet", e)
        null
    }
}
