package com.vayunmathur.maps.data
import com.vayunmathur.maps.domain.TransitClock

import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.transit.TransitousDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * MOTIS realtime boards flattened for the JNI overlay arguments.
 *
 * Split out of [OfflineRouterTransit] so that object stays under the function
 * cap. Same package, so no API changes for any other caller.
 */
internal object TransitBoards {
    /** Millis per second, for schedule/overlay time conversions. */
    private const val MILLIS_PER_SECOND = 1000L

    suspend fun fetchBoards(
        stops: List<Pair<GeoPoint, String>>,
    ): List<Pair<GeoPoint, List<com.vayunmathur.maps.data.transit.Departure>>> = coroutineScope {
        stops.map { (p, motisId) ->
            async(Dispatchers.IO) {
                p to runCatching {
                    TransitousDataSource.departures(motisId)
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
    }

    fun buildOverlay(
        boards: List<Pair<GeoPoint, List<com.vayunmathur.maps.data.transit.Departure>>>,
        clock: TransitClock,
    ): OfflineRouterTransit.Overlay {
        val coords = mutableListOf<Double>()
        val routes = mutableListOf<String>()
        val times = mutableListOf<Int>()
        for ((pos, deps) in boards) {
            addDepartures(coords, routes, times, pos, deps, clock)
        }
        if (routes.isEmpty()) return OfflineRouterTransit.Overlay.EMPTY
        return OfflineRouterTransit.Overlay(coords.toDoubleArray(), routes.toTypedArray(), times.toIntArray())
    }

    private fun addDepartures(
        coords: MutableList<Double>,
        routes: MutableList<String>,
        times: MutableList<Int>,
        pos: GeoPoint,
        deps: List<com.vayunmathur.maps.data.transit.Departure>,
        clock: TransitClock,
    ) {
        for (d in deps) {
            addDeparture(coords, routes, times, pos, d, clock)
        }
    }

    private fun addDeparture(
        coords: MutableList<Double>,
        routes: MutableList<String>,
        times: MutableList<Int>,
        pos: GeoPoint,
        d: com.vayunmathur.maps.data.transit.Departure,
        clock: TransitClock,
    ) {
        if (d.line.isBlank()) return
        val delaySecs = ((d.realtimeMillis - d.scheduledMillis) / MILLIS_PER_SECOND).toInt()
        if (delaySecs == 0 && !d.cancelled) return
        coords.add(pos.latitude)
        coords.add(pos.longitude)
        routes.add(d.line)
        times.add(((d.scheduledMillis - clock.midnightMillis) / MILLIS_PER_SECOND).toInt())
        times.add(delaySecs)
        times.add(if (d.cancelled) 1 else 0)
    }
}
