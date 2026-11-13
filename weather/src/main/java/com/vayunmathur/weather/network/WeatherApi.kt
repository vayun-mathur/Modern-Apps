package com.vayunmathur.weather.network

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.weather.data.GeoDatabase

/**
 * Thin wrapper over the shared [NetworkClient] for the self-hosted `wx/v1`
 * bundle API on `api.maproj.org`. One `bundle()` call carries everything
 * the weather screen needs; the mapper normalizes it into the existing
 * DTOs so nothing downstream of `network/` changes. Place search is fully
 * on-device ([GeoDatabase]) — no geocoding host remains.
 *
 * Everything is requested in metric units; the UI converts at display time so
 * toggling °C/°F doesn't trigger another HTTP call.
 */
object WeatherApi {

    private const val BUNDLE_BASE = "https://api.maproj.org/wx/v1"
    private const val TAG = "WeatherApi"

    // Test seam: point the bundle API at an unroutable address so the
    // failure-path tests are hermetic on boxes that have internet.
    @androidx.annotation.VisibleForTesting
    internal var bundleBaseOverride: String? = null

    private val bundleBase: String get() = bundleBaseOverride ?: BUNDLE_BASE

    /**
     * Fetch the full bundle (current + hourly + daily + 15-min + AQ) for a
     * single coordinate. Single HTTP call. Throws on network / parse failure
     * — callers wrap in try/catch and fall back to cache.
     */
    suspend fun bundle(latitude: Double, longitude: Double, fields: String = "all"): WxBundle {
        // Full bundles come from the static R2 tiles first (computed on
        // device, identical to the server's); any failure there — ocean
        // point, no network to R2, a format change — falls back to the API.
        // Tests that point the API somewhere (bundleBaseOverride) skip it.
        if (fields == "all" && bundleBaseOverride == null && WxStatic.enabled) {
            try {
                return WxStatic.bundle(latitude, longitude)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                Log.status(TAG, "static weather unavailable (${e.javaClass.simpleName}: ${e.message}); using the API")
            }
        }
        val url = buildString {
            append(bundleBase).append("/bundle")
            append("?lat=").append(latitude)
            append("&lon=").append(longitude)
            append("&fields=").append(fields)
        }
        return NetworkClient.getJson(url)
    }

    /**
     * Fetch the current conditions + 24h hourly + 7-day daily forecast for a
     * single coordinate. Throws on network / parse failure — callers wrap in
     * try/catch and fall back to cache.
     */
    suspend fun forecast(latitude: Double, longitude: Double): ForecastResponse {
        return bundle(latitude, longitude).toForecastResponse()
    }

    /**
     * Look up up to [limit] places that match the user-typed [query]. Used by
     * the in-app city search and by the OpenAssistant
     * `get_weather_by_name` intent.
     *
     * Fully on-device: the places catalogue (`places.db.br`) is a committed
     * asset unpacked by [GeoDatabase], so search works on a clean checkout
     * with no network. Empty when the catalogue is absent or nothing matches.
     */
    suspend fun geocode(query: String, limit: Int = 5): GeocodingResponse {
        if (query.isBlank()) return GeocodingResponse(emptyList())
        val local = runCatching {
            GeoDatabase.searchPlaces(query, limit)
        }.getOrDefault(emptyList())
        return GeocodingResponse(local)
    }

    /**
     * Fetch just the current temperature (°C) for a coordinate. Used by the
     * city-search list to show the current temp next to each result.
     * Requests only the `current` bundle section (~200-byte body).
     */
    suspend fun currentTemperature(latitude: Double, longitude: Double): Double? {
        return bundle(latitude, longitude, "current").current?.temp
    }

    /**
     * Batch current temperatures for search results: one round trip for all
     * rows instead of N parallel [currentTemperature] calls. Returns
     * temp-per-coordinate in request order; a failed point is absent (callers
     * keep the per-row spinner → blank, same as today's `getOrNull`).
     */
    suspend fun currentTemperatures(points: List<Pair<Double, Double>>): Map<Pair<Double, Double>, Double> {
        if (points.isEmpty()) return emptyMap()
        val url = buildString {
            append(bundleBase).append("/current?points=")
            append(points.take(20).joinToString("|") { (lat, lon) -> "$lat,$lon" })
        }
        val batch: WxCurrentBatch = NetworkClient.getJson(url)
        return batch.points.associate { p -> (p.lat to p.lon) to p.temp }
    }

    /**
     * Resolve the IANA time zone for a coordinate. Requests only the envelope
     * (no weather data at all). Used by the map to label the zoomed-in
     * region's local time. Throws on failure.
     */
    suspend fun timezoneAt(latitude: Double, longitude: Double): RegionTimezone {
        return bundle(latitude, longitude, "tz").toRegionTimezone()
    }

    /**
     * Air quality + pollen for a coordinate. Best-effort: the service has gaps
     * outside Europe/North America, so callers should treat the whole response
     * as optional. Missing `aq` section maps to [AirQualityResponse](null),
     * exactly like today's nullable DTO.
     */
    suspend fun airQuality(latitude: Double, longitude: Double): AirQualityResponse {
        return bundle(latitude, longitude).toAirQuality() ?: AirQualityResponse(null)
    }
}
