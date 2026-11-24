package com.vayunmathur.weather.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phone <-> watch sync protocol for the Wear OS Weather companion.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: Gradle cannot let one
 * application module depend on another, so the wear module
 * (`:weather:wear`, applicationId `com.vayunmathur.weather`) carries a copy
 * of this file under `com.vayunmathur.weather.wear.domain`. Change both
 * copies together; each side's unit tests pin the same golden JSON.
 *
 * Transport (WearLink, :library:wearlink — RFCOMM, no Play services):
 * - Presence is a completed handshake for [CAPABILITY] (the appId).
 * - Data items: [SNAPSHOT_PATH] phone -> watch, [WATCH_STATE_PATH] watch -> phone.
 * - Messages: [REQUEST_SYNC_PATH] watch -> phone (only watch-originated write).
 * Bounded by design: primary location first, then favorites, caps below.
 */
object WearWeatherProtocol {
    const val CAPABILITY = "weather_wear_companion"
    const val SNAPSHOT_PATH = "/weather/snapshot"
    const val WATCH_STATE_PATH = "/weather/watch-state"
    const val REQUEST_SYNC_PATH = "/weather/request-sync"
    const val MAX_LOCATIONS = 4
    const val MAX_HOURLY = 24
    const val MAX_DAILY = 7
    const val MAX_JSON_BYTES = 90_000
    const val MAX_NAME_CHARS = 80
    val json: Json = Json { ignoreUnknownKeys = true }
}

@Serializable
data class WearWeatherCurrent(
    val timeIso: String = "",
    val temperatureC: Double = 0.0,
    val apparentTemperatureC: Double = 0.0,
    val humidityPercent: Int = 0,
    val weatherCode: Int = -1,
    val isDay: Boolean = true,
    val windSpeedKmh: Double = 0.0,
    val windDirectionDegrees: Int = 0,
    val pressureHpa: Double = 0.0,
)

@Serializable
data class WearWeatherHour(
    val timeIso: String = "",
    val temperatureC: Double = 0.0,
    val weatherCode: Int = -1,
    val precipitationProbabilityPercent: Int = 0,
    val isDay: Boolean = true,
)

@Serializable
data class WearWeatherDay(
    val dateIso: String = "",
    val weatherCode: Int = -1,
    val temperatureMaxC: Double = 0.0,
    val temperatureMinC: Double = 0.0,
    val precipitationProbabilityMaxPercent: Int = 0,
)

@Serializable
data class WearWeatherLocation(
    val locationId: Long = 0L,
    val name: String = "",
    val country: String = "",
    val isPrimary: Boolean = false,
    val timezone: String = "UTC",
    val current: WearWeatherCurrent? = null,
    val hourly: List<WearWeatherHour> = emptyList(),
    val daily: List<WearWeatherDay> = emptyList(),
)

@Serializable
data class WearWeatherSnapshot(
    val generatedAtMillis: Long = 0L,
    val locations: List<WearWeatherLocation> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class WearWatchState(
    val appliedGeneratedAtMillis: Long = 0L,
    val locationCount: Int = 0,
)

/** Primary first (stable for equal rank), then field/list caps, then JSON-size cap (drop last locations, then hourly). */
fun buildWearWeatherSnapshot(
    candidates: List<WearWeatherLocation>,
    generatedAtMillis: Long,
): WearWeatherSnapshot {
    val ordered = candidates.sortedByDescending { it.isPrimary }.map { loc ->
        loc.copy(
            name = loc.name.take(WearWeatherProtocol.MAX_NAME_CHARS),
            country = loc.country.take(WearWeatherProtocol.MAX_NAME_CHARS),
            hourly = loc.hourly.take(WearWeatherProtocol.MAX_HOURLY),
            daily = loc.daily.take(WearWeatherProtocol.MAX_DAILY),
        )
    }
    var kept = ordered.take(WearWeatherProtocol.MAX_LOCATIONS)
    var truncated = kept.size < ordered.size
    var snapshot = WearWeatherSnapshot(generatedAtMillis, kept, truncated)
    fun over() = WearWeatherProtocol.json.encodeToString(snapshot).encodeToByteArray().size > WearWeatherProtocol.MAX_JSON_BYTES
    while (over() && kept.size > 1) {
        kept = kept.dropLast(1); truncated = true
        snapshot = WearWeatherSnapshot(generatedAtMillis, kept, true)
    }
    while (over() && kept.firstOrNull()?.hourly?.isNotEmpty() == true) {
        kept = kept.mapIndexed { i, loc -> if (i == 0) loc.copy(hourly = loc.hourly.dropLast(1)) else loc }
        snapshot = WearWeatherSnapshot(generatedAtMillis, kept, true)
    }
    return snapshot
}

/** True when a companion node is present (non-empty handshake set). */
fun wearCompanionPresent(nodeIdsWithCapability: Collection<String>): Boolean = nodeIdsWithCapability.isNotEmpty()
