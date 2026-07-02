package com.vayunmathur.weather.util

import androidx.compose.ui.graphics.Color
import com.vayunmathur.weather.network.ForecastResponse
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

/** A metric that has an hourly series we can plot. */
enum class WeatherMetric(val title: String) {
    Temperature("Temperature"),
    FeelsLike("Feels like"),
    Humidity("Humidity"),
    DewPoint("Dew point"),
    Precipitation("Precipitation"),
    WindSpeed("Wind speed"),
    WindGusts("Wind gusts"),
    Pressure("Pressure"),
    Visibility("Visibility"),
    CloudCover("Cloud cover"),
    UvIndex("UV index"),
}

/**
 * The Open-Meteo `hourly=` variable name that backs each metric. Used by the
 * map's grid-sampling query so the same measure shown in the graph can be
 * shaded across the area.
 */
val WeatherMetric.openMeteoHourlyVar: String
    get() = when (this) {
        WeatherMetric.Temperature -> "temperature_2m"
        WeatherMetric.FeelsLike -> "apparent_temperature"
        WeatherMetric.Humidity -> "relative_humidity_2m"
        WeatherMetric.DewPoint -> "dew_point_2m"
        WeatherMetric.Precipitation -> "precipitation"
        WeatherMetric.WindSpeed -> "wind_speed_10m"
        WeatherMetric.WindGusts -> "wind_gusts_10m"
        WeatherMetric.Pressure -> "pressure_msl"
        WeatherMetric.Visibility -> "visibility"
        WeatherMetric.CloudCover -> "cloud_cover"
        WeatherMetric.UvIndex -> "uv_index"
    }

/** One stop of a color ramp: a raw-unit [value] mapped to a display [color]. */
data class ColorStop(val value: Double, val color: Color)

/**
 * Per-metric color ramp in **raw API units** (°C, km/h, hPa, m, %, mm), sized
 * to each metric's natural range. Feeds the MapLibre `interpolate` fill color
 * on the shaded map; stops are strictly ascending. Display labels still go
 * through the existing unit formatters — the ramp itself is unit-agnostic.
 */
val WeatherMetric.colorRamp: List<ColorStop>
    get() = when (this) {
        WeatherMetric.Temperature, WeatherMetric.FeelsLike, WeatherMetric.DewPoint -> listOf(
            ColorStop(-20.0, Color(0xFF3B4CC0)),
            ColorStop(0.0, Color(0xFF5AC8FA)),
            ColorStop(10.0, Color(0xFF34C759)),
            ColorStop(20.0, Color(0xFFFFD60A)),
            ColorStop(30.0, Color(0xFFFF9500)),
            ColorStop(40.0, Color(0xFFFF3B30)),
        )
        WeatherMetric.Humidity, WeatherMetric.CloudCover -> listOf(
            ColorStop(0.0, Color(0xFFF4E7C3)),
            ColorStop(50.0, Color(0xFF5AC8FA)),
            ColorStop(100.0, Color(0xFF1F4EA8)),
        )
        WeatherMetric.Precipitation -> listOf(
            ColorStop(0.1, Color(0xFFCDE7F0)),
            ColorStop(2.0, Color(0xFF5AC8FA)),
            ColorStop(6.0, Color(0xFF1F76D2)),
            ColorStop(12.0, Color(0xFF7B2FBE)),
        )
        WeatherMetric.WindSpeed -> listOf(
            ColorStop(0.0, Color(0xFFE8F5E9)),
            ColorStop(20.0, Color(0xFF66BB6A)),
            ColorStop(40.0, Color(0xFFFFD60A)),
            ColorStop(70.0, Color(0xFFFF9500)),
            ColorStop(100.0, Color(0xFFFF3B30)),
        )
        WeatherMetric.WindGusts -> listOf(
            ColorStop(0.0, Color(0xFFE8F5E9)),
            ColorStop(30.0, Color(0xFF66BB6A)),
            ColorStop(60.0, Color(0xFFFFD60A)),
            ColorStop(90.0, Color(0xFFFF9500)),
            ColorStop(120.0, Color(0xFFFF3B30)),
        )
        WeatherMetric.Pressure -> listOf(
            ColorStop(980.0, Color(0xFF7B2FBE)),
            ColorStop(1000.0, Color(0xFF5AC8FA)),
            ColorStop(1013.0, Color(0xFF34C759)),
            ColorStop(1030.0, Color(0xFFFF9500)),
            ColorStop(1050.0, Color(0xFFFF3B30)),
        )
        WeatherMetric.Visibility -> listOf(
            ColorStop(0.0, Color(0xFF5D4037)),
            ColorStop(2000.0, Color(0xFFFF9500)),
            ColorStop(8000.0, Color(0xFFFFD60A)),
            ColorStop(20000.0, Color(0xFF5AC8FA)),
        )
        WeatherMetric.UvIndex -> listOf(
            ColorStop(0.0, Color(0xFF34C759)),
            ColorStop(3.0, Color(0xFFFFD60A)),
            ColorStop(6.0, Color(0xFFFF9500)),
            ColorStop(8.0, Color(0xFFFF3B30)),
            ColorStop(11.0, Color(0xFF7B2FBE)),
        )
    }

/** All metrics that can be shaded on the map (every graphable metric). */
val graphableMetrics: List<WeatherMetric> = WeatherMetric.entries

/**
 * Per-metric display formatter for a raw metric value, honoring the user's
 * unit preferences. Shared by the graph value labels and the map legend so the
 * two never drift.
 */
fun metricValueFormatter(
    metric: WeatherMetric,
    tempUnit: TemperatureUnit,
    windUnit: WindUnit,
    pressureUnit: PressureUnit,
): (Double) -> String = when (metric) {
    WeatherMetric.Temperature, WeatherMetric.FeelsLike, WeatherMetric.DewPoint ->
        { v -> formatTemperatureCompact(v, tempUnit) }
    WeatherMetric.Humidity, WeatherMetric.CloudCover ->
        { v -> "${v.roundToInt()}%" }
    WeatherMetric.Precipitation ->
        { v -> if (windUnit == WindUnit.Mph) String.format(java.util.Locale.US, "%.2f in", mmToInches(v)) else String.format(java.util.Locale.US, "%.1f mm", v) }
    WeatherMetric.WindSpeed, WeatherMetric.WindGusts ->
        { v -> formatWind(v, windUnit) }
    WeatherMetric.Pressure ->
        { v -> formatPressure(v, pressureUnit) }
    WeatherMetric.Visibility ->
        { v -> if (windUnit == WindUnit.Mph) "${metersToMiles(v).roundToInt()} mi" else "${(v / 1000).roundToInt()} km" }
    WeatherMetric.UvIndex ->
        { v -> v.roundToInt().toString() }
}

/** A single (time, raw value) sample. Values are in API units (°C, km/h, hPa, m, %, mm). */
data class MetricPoint(val epochSec: Long, val value: Double)

/**
 * Extract the hourly series for [metric] over exactly one local calendar day
 * (midnight to midnight): the selected day/hour's date, or today when nothing
 * is selected. Values are raw API units; callers format them for display.
 */
fun metricSeries(
    forecast: ForecastResponse,
    metric: WeatherMetric,
    selected: SelectedDateOrTime?,
): List<MetricPoint> {
    val hourly = forecast.hourly ?: return emptyList()

    val raw: List<Double> = when (metric) {
        WeatherMetric.Temperature -> hourly.temperature
        WeatherMetric.FeelsLike -> hourly.apparentTemperature
        WeatherMetric.Humidity -> hourly.relativeHumidity.map { it.toDouble() }
        WeatherMetric.DewPoint -> hourly.dewPoint
        WeatherMetric.Precipitation -> hourly.precipitation
        WeatherMetric.WindSpeed -> hourly.windSpeed
        WeatherMetric.WindGusts -> hourly.windGusts
        WeatherMetric.Pressure -> hourly.pressureMsl
        WeatherMetric.Visibility -> hourly.visibility
        WeatherMetric.CloudCover -> hourly.cloudCover.map { it.toDouble() }
        WeatherMetric.UvIndex -> hourly.uvIndex
    }

    val targetDate = when (selected) {
        is SelectedDateOrTime.Day -> selected.isoDate
        is SelectedDateOrTime.Time -> selected.isoTime.substringBefore('T')
        // No selection: plot today (the location's local calendar day) so the
        // graph always runs midnight-to-midnight, never a rolling 24h window.
        null -> forecast.daily?.time?.firstOrNull() ?: localDate(forecast.utcOffsetSeconds)
    }

    val out = ArrayList<MetricPoint>()
    for (i in hourly.time.indices) {
        val value = raw.getOrNull(i) ?: continue
        val iso = hourly.time[i]
        if (iso.substringBefore('T') != targetDate) continue
        val epoch = parseLocalIsoToEpochSec(iso, forecast.utcOffsetSeconds) ?: continue
        out.add(MetricPoint(epoch, value))
    }
    return out
}

/** Today's date in the location's local time, as an ISO `yyyy-MM-dd` string. */
private fun localDate(utcOffsetSeconds: Int): String {
    val now = System.currentTimeMillis() / 1000
    return kotlin.time.Instant.fromEpochSeconds(now + utcOffsetSeconds)
        .toLocalDateTime(kotlinx.datetime.TimeZone.UTC).date.toString()
}
