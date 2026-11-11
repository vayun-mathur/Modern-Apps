package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.maps.data.PoiPin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Drives the ambient POI overlay: turns viewport boxes into a debounced list
 * of [PoiPin]s to render (Decisions D3/D7, snappier P23), sourced from
 * the offline OSM POI index (P29). No network.
 *
 * The driver keeps the previous pins on screen until a *new, non-empty*
 * lookup returns (an empty index never blinks the overlay to nothing),
 * debounces fast pans, and still skips wide views (> ~16 km, [MAX_LAT_SPAN])
 * where POI pins are meaningless. All index reads are on [Dispatchers.IO].
 */
class PoiMapViewModel(application: Application) : AndroidViewModel(application) {

    private val _pins = MutableStateFlow<List<PoiPin>>(emptyList())
    val pins: StateFlow<List<PoiPin>> = _pins.asStateFlow()

    // Ambient-pin refresh job. Cancelled on each new viewport so a fast pan debounces.
    private var offlineJob: Job? = null

    /**
     * Publish ambient pins from the OFFLINE POI index (P29) for the
     * [north]..[south] × [west]..[east] box. Debounced; views wider than [MAX_LAT_SPAN] and empty
     * results KEEP the previous pins so a fast pan / wide zoom never blinks the
     * overlay to nothing (P23 no-clear). The index query + name decode run on
     * [Dispatchers.IO]; [PoiIndex.initialize] is idempotent so calling it here is
     * cheap after the first map.
     */
    fun onViewportOffline(north: Double, east: Double, south: Double, west: Double) {
        if (abs(north - south) > MAX_LAT_SPAN) return
        val app = getApplication<Application>()
        offlineJob?.cancel()
        offlineJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            val pins = withContext(Dispatchers.IO) {
                runCatching {
                    PoiIndex.initialize(app)
                    PoiIndex.inViewport(west, south, east, north, cap = OFFLINE_CAP)
                        .map { it.toPin() }
                }.getOrDefault(emptyList())
            }
            if (pins.isNotEmpty()) _pins.value = pins
        }
    }

    /**
     * Feed a viewport (from `queryVisibleBoundingBox()`). Views wider than
     * [MAX_LAT_SPAN] are ignored, keeping the last pins (no clear). Same as
     * [onViewportOffline]: pins only ever come from the offline index.
     */
    fun onViewport(north: Double, east: Double, south: Double, west: Double) {
        onViewportOffline(north, east, south, west)
    }

    /** Map an offline index record to the pin model [pins] publishes; the
     *  category label drives its data-driven pin colour (P29). */
    private fun PoiIndex.PoiRecord.toPin(): PoiPin = PoiPin(
        id = "poi:$latE7:$lonE7",
        name = name,
        lat = lat,
        lng = lon,
        category = PoiCategories.label(type),
    )

    private companion object {
        // Idle-ish refresh for small nudges.
        const val DEBOUNCE_MS = 350L

        // ~16 km of latitude — roughly city-zoom; wider views skip the lookup.
        const val MAX_LAT_SPAN = 0.15

        // Cap on ambient offline pins per viewport (P29) so a dense city box
        // stays bounded; matches the ambient overlay's close-zoom feel.
        const val OFFLINE_CAP = 300
    }
}
