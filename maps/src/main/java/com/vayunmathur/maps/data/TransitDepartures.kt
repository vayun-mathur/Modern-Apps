package com.vayunmathur.maps.data
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.domain.GtfsRouteTypes
import com.vayunmathur.maps.domain.TransitClock

import com.vayunmathur.maps.data.transit.Departure

/**
 * Departure-board row decoding for [OfflineRouterTransit].
 *
 * Split out so that object stays under the function cap. Same package, so no
 * API changes for any other caller.
 */
internal object TransitDepartures {
    /** Millis per second, for schedule/overlay time conversions. */
    private const val MILLIS_PER_SECOND = 1000L
    /** Seconds per minute, for delay display. */
    private const val SECONDS_PER_MINUTE = 60
    /** Mask for one unsigned RGB byte of a GTFS `route_color`. */
    private const val RGB_MASK = 0xFFFFFF

    fun departureOf(
        d: OfflineRouter.RawDeparture,
        clock: TransitClock,
    ): Departure {
        val scheduled = clock.midnightMillis + d.depSecs.toLong() * MILLIS_PER_SECOND
        return Departure(
            line = d.routeName,
            headsign = d.headsign,
            scheduledMillis = scheduled,
            realtimeMillis = scheduled + d.delaySecs * MILLIS_PER_SECOND,
            delayMinutes = d.delaySecs / SECONDS_PER_MINUTE,
            realTime = d.realTime,
            platform = null,
            mode = GtfsRouteTypes.mode(d.routeType),
            routeColor = if (d.routeColor == 0) null
                else String.format("%06X", d.routeColor and RGB_MASK),
            cancelled = d.cancelled,
            tripVehicleId = d.tripId,
        )
    }
}
