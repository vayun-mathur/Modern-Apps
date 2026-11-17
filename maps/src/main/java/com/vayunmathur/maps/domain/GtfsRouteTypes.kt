package com.vayunmathur.maps.domain
import com.vayunmathur.maps.data.OfflineRouterTransit

/**
 * GTFS `route_type` (base + extended ranges) to a coarse mode label.
 *
 * Split out of [OfflineRouterTransit] so that object stays under the function
 * cap. Same package, so no API changes for any other caller.
 */
internal object GtfsRouteTypes {
    /** Base GTFS route_type codes (single digits). */
    private const val TRAM_BASE_0 = 0
    private const val SUBWAY_BASE = 1
    private const val RAIL_BASE = 2
    private const val BUS_BASE = 3
    private const val FERRY_BASE = 4
    private const val TRAM_BASE_5 = 5
    private const val AERIAL_BASE = 6
    private const val FUNICULAR_BASE = 7
    private const val TROLLEYBUS_BASE = 11
    private const val MONORAIL_BASE = 12

    /** Extended GTFS route_type codes (base codes are the single digits). */
    private const val EXTENDED_TRAM = 900
    private const val EXTENDED_BUS_800 = 800
    private const val EXTENDED_FERRY_1000 = 1000
    private const val EXTENDED_FERRY_1200 = 1200
    private const val EXTENDED_AERIAL = 1300
    private const val EXTENDED_FUNICULAR = 1400
    private val EXTENDED_SUBWAY = 400..499
    private val EXTENDED_RAIL = 100..199
    private val EXTENDED_BUS_RANGES = listOf(200..299, 700..799)

    /** Map a GTFS `route_type` (base + extended ranges) to a coarse mode label. */
    fun mode(t: Int): String =
        tramType(t) ?: subwayType(t) ?: railType(t) ?: busType(t)
            ?: ferryType(t) ?: aerialType(t) ?: fixedGuidewayType(t) ?: "TRANSIT"

    private fun tramType(t: Int): String? =
        if (t == TRAM_BASE_0 || t == TRAM_BASE_5 || t == EXTENDED_TRAM) "TRAM" else null

    private fun subwayType(t: Int): String? =
        if (t == SUBWAY_BASE || t in EXTENDED_SUBWAY) "SUBWAY" else null

    private fun railType(t: Int): String? =
        if (t == RAIL_BASE || t in EXTENDED_RAIL) "RAIL" else null

    private fun busType(t: Int): String? {
        if (t == BUS_BASE) return "BUS"
        if (EXTENDED_BUS_RANGES.any { t in it }) return "BUS"
        if (t == EXTENDED_BUS_800) return "BUS"
        return null
    }

    private fun ferryType(t: Int): String? =
        if (t == FERRY_BASE || t == EXTENDED_FERRY_1000 || t == EXTENDED_FERRY_1200) {
            "FERRY"
        } else {
            null
        }

    private fun aerialType(t: Int): String? =
        if (t == AERIAL_BASE || t == EXTENDED_AERIAL) "AERIAL" else null

    private fun fixedGuidewayType(t: Int): String? = when (t) {
        FUNICULAR_BASE, EXTENDED_FUNICULAR -> "FUNICULAR"
        TROLLEYBUS_BASE -> "TROLLEYBUS"
        MONORAIL_BASE -> "MONORAIL"
        else -> null
    }
}
