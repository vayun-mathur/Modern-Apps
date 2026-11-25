package com.vayunmathur.weather.ui

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.map.GeoBounds
import com.vayunmathur.weather.domain.map.OmMapMetadata
import com.vayunmathur.weather.domain.map.colorizeToBitmap
import com.vayunmathur.weather.domain.map.omVariable
import com.vayunmathur.weather.network.WxStatic
import com.vayunmathur.weather.domain.WeatherMetric
import com.vayunmathur.weather.domain.colorRamp
import com.vayunmathur.weather.domain.mapMetrics
import com.vayunmathur.weather.network.RegionTimezone
import com.vayunmathur.weather.network.WeatherApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Longest side (px) of the decoded/colorized overlay raster. */
internal const val RASTER_MAX_DIM = 512

/**
 * Minimum camera zoom before the map resolves and shows the zoomed-in region's
 * local time. Below this the viewport spans multiple zones, so a single
 * region's time would be misleading. The initial camera zoom is 7 (≈ regional).
 */
internal const val MIN_ZOOM_FOR_REGION_TZ = 7.0

/** A decoded + geolocated weather field ready to colorize into a bitmap. */
internal data class DecodedRegion(
    val values: FloatArray,
    val width: Int,
    val height: Int,
    val bbox: GeoBounds,
)

/** Snapshot of the inputs that drive a decode; used to debounce scrubbing. */
internal data class DecodeRequest(
    val bbox: GeoBounds?,
    val metric: WeatherMetric,
    val timeIndex: Int,
    val meta: OmMapMetadata?,
)

/** Mutable map state owned by [WeatherMapPage]: metadata, decode cache, overlay. */
internal class WeatherMapState(
    metric: String,
) {
    var selectedMetric by mutableStateOf(
        runCatching { WeatherMetric.valueOf(metric) }
            .getOrDefault(WeatherMetric.Temperature)
            .takeIf { it in mapMetrics } ?: WeatherMetric.Temperature,
    )

    var metadata by mutableStateOf<OmMapMetadata?>(null)
    var timeIndex by mutableIntStateOf(0)
    var userScrubbed by mutableStateOf(false)
    var visibleBbox by mutableStateOf<GeoBounds?>(null)
    var overlay by mutableStateOf<ImageBitmap?>(null)
    var overlayBounds by mutableStateOf<GeoBounds?>(null)
    var loading by mutableStateOf(false)
    var regionTz by mutableStateOf<RegionTimezone?>(null)

    // Cache region lookups by ~0.1° center so panning doesn't re-hit the API.
    val tzCache = mutableMapOf<String, RegionTimezone>()

    // Cache decoded regions by (rounded bbox, variable, valid_time) so panning
    // back and forth (or re-selecting a measure/time) doesn't re-fetch.
    val cache = mutableMapOf<String, DecodedRegion>()

    val supportedMetrics: List<WeatherMetric>
        get() = metadata?.let { m -> mapMetrics.filter { m.supports(it) } } ?: mapMetrics
    val times: List<String> get() = metadata?.validTimes.orEmpty()
}

@Composable
internal fun rememberWeatherMapState(metric: String): WeatherMapState {
    return remember { WeatherMapState(metric) }
}

/** Loads run metadata (from the R2 tile serving the page's location) once, then seeds the time index near the requested time. */
@Composable
internal fun WeatherMapMetadataEffect(state: WeatherMapState, isoTime: String?, latitude: Double, longitude: Double) {
    LaunchedEffect(latitude, longitude) {
        val meta = WxStatic.mapMetadata(latitude, longitude)
        state.metadata = meta
        Log.status(
            "OmMap",
            if (meta == null) "metadata NULL (no static tile for the page location)"
            else "metadata ok ref=${meta.referenceTime} times=${meta.validTimes.size} vars=${meta.variables.size}",
        )
        if (meta != null) {
            if (meta.validTimes.isNotEmpty() && !state.userScrubbed) {
                state.timeIndex = indexForIso(meta.validTimes, isoTime).coerceIn(0, meta.validTimes.size - 1)
            }
            // The incoming measure may not exist in this model run (e.g. UV,
            // dew point). Fall back to a supported one so the map isn't blank.
            if (!meta.supports(state.selectedMetric)) {
                val fallback = mapMetrics.firstOrNull { meta.supports(it) }
                if (fallback != null) state.selectedMetric = fallback
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
internal fun WeatherMapCameraEffects(
    state: WeatherMapState,
    camera: com.vayunmathur.library.map.CameraState,
) {
    // Track the visible bounding box, debounced so we only decode once the
    // camera settles.
    LaunchedEffect(camera) {
        snapshotFlow { camera.position to camera.projection }
            .debounce(400)
            .collectLatest { (_, projection) ->
                state.visibleBbox = projection?.queryVisibleBoundingBox()
            }
    }

    // Resolve the map center's time zone when zoomed in enough, so the panel
    // can show the region's local time alongside the viewer's. Cleared (falls
    // back to viewer-only) when zoomed out past the threshold.
    LaunchedEffect(camera) {
        snapshotFlow { camera.position.target to camera.position.zoom }
            .debounce(500)
            .collectLatest { (target, zoom) ->
                if (zoom < MIN_ZOOM_FOR_REGION_TZ) {
                    state.regionTz = null
                    return@collectLatest
                }
                val key = "${(target.latitude * 10).roundToInt()},${(target.longitude * 10).roundToInt()}"
                state.tzCache[key]?.let {
                    state.regionTz = it
                    return@collectLatest
                }
                val tz = withContext(Dispatchers.IO) {
                    runCatching { WeatherApi.timezoneAt(target.latitude, target.longitude) }.getOrNull()
                }
                if (tz?.timezone != null) {
                    state.tzCache[key] = tz
                    state.regionTz = tz
                }
            }
    }
}

@OptIn(FlowPreview::class)
@Composable
internal fun WeatherMapDecodeEffect(state: WeatherMapState) {
    // Decode + colorize when the region, measure, time step or run changes.
    // Debounced + collectLatest so dragging the time slider coalesces into a
    // single decode of the settled step instead of firing one per value.
    LaunchedEffect(Unit) {
        snapshotFlow { DecodeRequest(state.visibleBbox, state.selectedMetric, state.timeIndex, state.metadata) }
            .debounce(300)
            .collectLatest { req ->
                val bbox = req.bbox ?: return@collectLatest
                val meta = req.meta ?: return@collectLatest
                val validTime = meta.validTimes.getOrNull(req.timeIndex) ?: return@collectLatest
                if (!meta.supports(req.metric)) {
                    Log.status("OmMap", "skip decode: metric ${req.metric} not in the static run")
                    state.overlay = null
                    return@collectLatest
                }
                val variable = req.metric.omVariable
                val key = cacheKey(bbox, variable, validTime)

                val region = state.cache[key] ?: run {
                    state.loading = true
                    val decoded = withContext(Dispatchers.IO) {
                        val (w, h) = rasterSize(bbox)
                        val t0 = System.currentTimeMillis()
                        // Sampled from the R2 run tiles (nearest ICON cell;
                        // see WxStatic.regionValues for the precision note).
                        val values = WxStatic.regionValues(
                            variable, validTime,
                            bbox.west, bbox.south, bbox.east, bbox.north,
                            w, h,
                        )
                        if (values == null) {
                            Log.status("OmMap", "regionValues null for $variable $validTime")
                            return@withContext null
                        }
                        Log.status(
                            "OmMap",
                            "sampled $variable $validTime ${w}x$h in ${System.currentTimeMillis() - t0}ms",
                        )
                        DecodedRegion(values, w, h, bbox)
                    }
                    state.loading = false
                    if (decoded != null) state.cache[key] = decoded
                    decoded
                }

                if (region != null) {
                    state.overlay = colorizeToBitmap(region.values, region.width, region.height, req.metric.colorRamp)
                    state.overlayBounds = region.bbox
                }
            }
    }
}

/** Overlay raster size for [bbox], longest side [RASTER_MAX_DIM], aspect-matched. */
internal fun rasterSize(bbox: GeoBounds): Pair<Int, Int> {
    val lonSpan = abs(bbox.east - bbox.west).coerceAtLeast(1e-6)
    val latSpan = abs(bbox.north - bbox.south).coerceAtLeast(1e-6)
    return if (lonSpan >= latSpan) {
        RASTER_MAX_DIM to (RASTER_MAX_DIM * latSpan / lonSpan).roundToInt().coerceIn(16, RASTER_MAX_DIM)
    } else {
        (RASTER_MAX_DIM * lonSpan / latSpan).roundToInt().coerceIn(16, RASTER_MAX_DIM) to RASTER_MAX_DIM
    }
}

/** Cache key: metric variable + valid time + bounds rounded to ~0.1°. */
internal fun cacheKey(bbox: GeoBounds, variable: String, validTime: String): String {
    fun r(v: Double) = (v * 10).roundToInt()
    return "$variable@$validTime:${r(bbox.north)},${r(bbox.south)},${r(bbox.east)},${r(bbox.west)}"
}

/**
 * Index of the timestamp matching [isoTime] (by exact string, then by date+hour
 * prefix, then by date), or 0. Handles the trailing `Z` on `valid_times`.
 */
internal fun indexForIso(times: List<String>, isoTime: String?): Int {
    if (times.isEmpty()) return 0
    if (isoTime != null) {
        val exact = times.indexOf(isoTime)
        if (exact >= 0) return exact
        val hourPrefix = isoTime.take(13) // yyyy-MM-ddTHH
        val byHour = times.indexOfFirst { it.take(13) == hourPrefix }
        if (byHour >= 0) return byHour
        val dayPrefix = isoTime.take(10)
        val byDay = times.indexOfFirst { it.take(10) == dayPrefix }
        if (byDay >= 0) return byDay
    }
    return 0
}
