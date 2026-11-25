package com.vayunmathur.weather.network

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.weather.data.GeoDatabase

/**
 * The weather app's one network entry point. Every read is served from
 * the static packs on Cloudflare R2 (`static.maproj.org/wx/v1/`, written
 * by the server's `wx export` — see [WxStatic]) or, for place search,
 * from the on-device catalogue ([GeoDatabase]). The origin bundle API
 * (`api.maproj.org/wx/v1`) stays up for released/older clients, but this
 * app no longer reads it for any normal read.
 *
 * FLAGGED EXCEPTION (the one read that is not fully moved): a point with
 * no land tile — open ocean, or a tile whose history days are not yet in
 * the packs — throws [WxStatic.NotCovered] from the static path. The
 * generator emits land tiles only; emitting every ocean tile would
 * multiply the run pack several-fold for coordinates the place catalogue
 * never returns. For that case only, [bundle] falls back to the origin
 * bundle so a user standing on a ferry still gets weather instead of an
 * error. Every other read (current, batch temperatures, time zone, map)
 * is R2/on-device only and simply degrades (absent row / null) there.
 *
 * Everything is in metric units; the UI converts at display time so
 * toggling °C/°F doesn't trigger another fetch.
 */
object WeatherApi {

    private const val BUNDLE_BASE = "https://api.maproj.org/wx/v1"
    private const val TAG = "WeatherApi"

    // Test seam for the flagged NotCovered fallback only: point it at an
    // unroutable address so failure-path tests stay hermetic.
    @androidx.annotation.VisibleForTesting
    internal var bundleBaseOverride: String? = null

    private val bundleBase: String get() = bundleBaseOverride ?: BUNDLE_BASE

    /**
     * Fetch the full bundle (current + hourly + daily + 15-min + AQ) for a
     * single coordinate from R2. Throws on network / parse failure —
     * callers wrap in try/catch and fall back to cache. See the class doc
     * for the flagged [WxStatic.NotCovered] origin fallback.
     */
    suspend fun bundle(latitude: Double, longitude: Double): WxBundle {
        try {
            return WxStatic.bundle(latitude, longitude)
        } catch (e: WxStatic.NotCovered) {
            if (bundleBaseOverride != null) throw e
            Log.status(TAG, "static weather does not cover ($latitude,$longitude): ${e.message}; using the flagged origin fallback")
            val url = "$bundleBase/bundle?lat=$latitude&lon=$longitude&fields=all"
            return NetworkClient.getJson(url)
        }
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
     * asset unpacked by [GeoDatabase], so search works with no network and
     * no R2 download on first run. It is deliberately not an R2 gazetteer:
     * the catalogue is already static, ships with the APK, and works
     * offline from first launch; moving it to R2 would add a first-run
     * download and an offline gap for no origin-read benefit.
     */
    suspend fun geocode(query: String, limit: Int = 5): GeocodingResponse {
        if (query.isBlank()) return GeocodingResponse(emptyList())
        val local = runCatching {
            GeoDatabase.searchPlaces(query, limit)
        }.getOrDefault(emptyList())
        return GeocodingResponse(local)
    }

    /**
     * Fetch just the current temperature (°C) for a coordinate, sampled
     * from its R2 tile bundle. Used by the city-search list to show the
     * current temp next to each result. Null when the point cannot be
     * served statically.
     */
    suspend fun currentTemperature(latitude: Double, longitude: Double): Double? {
        return runCatching { WxStatic.currentTemperature(latitude, longitude) }.getOrNull()
    }

    /**
     * Batch current temperatures for search results, sampled per point
     * from the R2 tiles (cached, so rows sharing a tile cost one fetch).
     * Returns temp-per-coordinate in request order; a failed point is
     * absent (callers keep the per-row spinner → blank).
     */
    suspend fun currentTemperatures(points: List<Pair<Double, Double>>): Map<Pair<Double, Double>, Double> {
        return WxStatic.currentTemperatures(points)
    }

    /**
     * Resolve the IANA time zone for a coordinate from its R2 geo block
     * (offset/abbreviation resolved on device). Used by the map to label
     * the zoomed-in region's local time. Throws on failure.
     */
    suspend fun timezoneAt(latitude: Double, longitude: Double): RegionTimezone {
        return WxStatic.timezoneAt(latitude, longitude)
    }

    /**
     * Air quality + pollen for a coordinate, from the same static bundle.
     * Best-effort: the service has gaps outside Europe/North America, so
     * callers should treat the whole response as optional. Missing `aq`
     * section maps to [AirQualityResponse](null).
     */
    suspend fun airQuality(latitude: Double, longitude: Double): AirQualityResponse {
        return bundle(latitude, longitude).toAirQuality() ?: AirQualityResponse(null)
    }
}
