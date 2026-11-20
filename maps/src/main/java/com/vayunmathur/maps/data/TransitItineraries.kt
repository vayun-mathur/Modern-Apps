package com.vayunmathur.maps.data
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.domain.TransitClock

import com.vayunmathur.maps.data.transit.TripStop

/**
 * Trip-itinerary stop decoding for [OfflineRouterTransit].
 *
 * Split out so that object stays under the function cap. Same package, so no
 * API changes for any other caller.
 */
internal object TransitItineraries {
    /** Millis per second, for schedule/overlay time conversions. */
    private const val MILLIS_PER_SECOND = 1000L

    fun fetchItinerary(
        base: String,
        feed: String,
        vehicleId: Long,
        clock: TransitClock,
        overlay: OfflineRouterTransit.Overlay,
    ): OfflineRouter.RawTripItinerary? {
        return try {
            OfflineRouter.getTripItineraryNative(
                base, feed, vehicleId,
                clock.weekday, clock.date,
                clock.prevWeekday, clock.prevDate,
                overlay.coords, overlay.routes, overlay.times
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun tripStop(
        s: OfflineRouter.RawTripStop,
        clock: TransitClock,
    ): TripStop {
        val arr = clock.midnightMillis + s.arrSecs.toLong() * MILLIS_PER_SECOND
        val dep = clock.midnightMillis + s.depSecs.toLong() * MILLIS_PER_SECOND
        return TripStop(
            name = s.name,
            lat = s.lat,
            lon = s.lon,
            arrivesMillis = arr,
            departsMillis = dep,
            motisId = s.motisId,
        )
    }
}
