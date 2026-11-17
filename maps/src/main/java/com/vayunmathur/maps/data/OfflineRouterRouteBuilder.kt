package com.vayunmathur.maps.data
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.domain.WALK_LEG_PLACEHOLDER
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.domain.arriveAt
import com.vayunmathur.maps.domain.formatServiceTime
import com.vayunmathur.maps.domain.leaveAt
import com.vayunmathur.maps.domain.phraseWalkLegs

import android.content.Context
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.R
import com.vayunmathur.library.ui.R as UiR
import kotlin.time.Duration.Companion.seconds

/**
 * Converts native [OfflineRouter.RawStep]s into a [RouteService.Route], extracted from
 * [OfflineRouter] to keep that file under the length limit.
 *
 * Shared by the driving/walking `findRouteNative` path and the offline transit
 * `findTransitRouteNative` path (via [OfflineRouterTransit]).
 */
internal object OfflineRouterRouteBuilder {
    /** Millimetres per metre (native distance unit). */
    private const val MM_PER_METER = 1000.0
    /** Native durations are centiseconds (10 ms units). */
    private const val CENTISEC_PER_SECOND = 100.0
    /** Stride of one [lon, lat] pair in a native geometry array. */
    private const val GEOMETRY_PAIR_STRIDE = 2
    /** Bit 0 of a packed lane int: the lane leads onto the taken route. */
    private const val LANE_ACTIVE_BIT = 1
    /** Packed lane int holds `dirMask * 2 + valid`: shift to the mask. */
    private const val LANE_MASK_SHIFT = 1
    /** Mask for one unsigned RGB byte of a GTFS `route_color`. */
    private const val RGB_MASK = 0xFFFFFF
    /** Fallback line colour when neither the pack nor GTFS names one. */
    private const val FALLBACK_ROUTE_COLOR = "#FF0000"
    /** Native wait durations are centiseconds (10 ms units). */
    private const val WAIT_CENTISEC_PER_SECOND = 100
    /** Seconds per minute, for the wait-text split. */
    private const val SECONDS_PER_MINUTE = 60
    /** Metres per degree of latitude, for the elevation-profile distance. */
    private const val METERS_PER_DEGREE = 111_320.0
    /** Midpoint factor when averaging two latitudes. */
    private const val LAT_MIDPOINT = 0.5
    /**
     * Convert native [OfflineRouter.RawStep]s into a [RouteService.Route]: decode geometry,
     * localize maneuver text, attach transit details, and coalesce consecutive
     * same-road maneuvers. Shared by the driving/walking [findRouteNative] path
     * and the offline transit [findTransitRouteNative] path.
     */
    fun buildRoute(
            context: Context,
            rawSteps: Array<OfflineRouter.RawStep>,
            mode: RouteService.TravelMode
    ): RouteService.Route {
                val fullPolyline = mutableListOf<GeoPoint>()
                val processedSteps = rawSteps.map { raw -> buildStep(context, raw, mode, fullPolyline) }
                return assembleRoute(context, rawSteps, mode, fullPolyline, processedSteps)
    }

    private fun buildStep(
            context: Context,
            raw: OfflineRouter.RawStep,
            mode: RouteService.TravelMode,
            fullPolyline: MutableList<GeoPoint>,
    ): RouteService.Step {
        val positions = mutableListOf<GeoPoint>()
        for (i in raw.geometry.indices step GEOMETRY_PAIR_STRIDE) {
            val pos = GeoPoint(raw.geometry[i], raw.geometry[i + 1])
            positions.add(pos)
            if (fullPolyline.isEmpty() || fullPolyline.last() != pos) {
                fullPolyline.add(pos)
            }
        }

        val maneuver = maneuverOf(raw.maneuverId)
        val lanes = raw.lanePacked.map { code -> decodeLane(code) }
        val hasName = raw.roadName.isNotBlank()
        val instructionText = instructionText(context, raw, mode, maneuver, hasName)
        return RouteService.Step(
            distanceMeters = raw.distanceMm / MM_PER_METER,
            staticDuration = (raw.duration10ms / CENTISEC_PER_SECOND).seconds,
            polyline = positions,
            navInstruction = RouteService.API.NavInstruction(maneuver, instructionText),
            travelMode = stepTravelMode(raw, mode),
            speedRatio = raw.speedRatio,
            lanes = lanes,
            transitDetails = transitDetails(context, raw),
        )
    }

    private fun instructionText(
        context: Context,
        raw: OfflineRouter.RawStep,
        mode: RouteService.TravelMode,
        maneuver: RouteService.API.Maneuver,
        hasName: Boolean,
    ): String = namedInstruction(context, raw, maneuver, hasName)
        ?: waitInstruction(context, raw)
        ?: fallbackInstruction(context, raw, mode, hasName)

    /** Named/unnamed template for the plain road maneuvers, or null when [maneuver] is special. */
    private fun namedInstruction(
        context: Context,
        raw: OfflineRouter.RawStep,
        maneuver: RouteService.API.Maneuver,
        hasName: Boolean,
    ): String? {
        val templates = MANEUVER_TEMPLATES[maneuver] ?: return null
        val res = if (hasName) templates.first else templates.second
        return context.getString(res, raw.roadName)
    }

    /** Road maneuver → (named template, unnamed template). */
    private val MANEUVER_TEMPLATES: Map<RouteService.API.Maneuver, Pair<Int, Int>> = mapOf(
        RouteService.API.Maneuver.DEPART to
            (R.string.maneuver_depart to R.string.maneuver_depart_unnamed),
        RouteService.API.Maneuver.STRAIGHT to
            (R.string.maneuver_straight to R.string.maneuver_straight_unnamed),
        RouteService.API.Maneuver.TURN_LEFT to
            (R.string.maneuver_turn_left to R.string.maneuver_turn_left_unnamed),
        RouteService.API.Maneuver.TURN_RIGHT to
            (R.string.maneuver_turn_right to R.string.maneuver_turn_right_unnamed),
        RouteService.API.Maneuver.TURN_SLIGHT_LEFT to
            (R.string.maneuver_turn_slight_left to
                R.string.maneuver_turn_slight_left_unnamed),
        RouteService.API.Maneuver.TURN_SLIGHT_RIGHT to
            (R.string.maneuver_turn_slight_right to
                R.string.maneuver_turn_slight_right_unnamed),
        RouteService.API.Maneuver.TURN_SHARP_LEFT to
            (R.string.maneuver_turn_sharp_left to
                R.string.maneuver_turn_sharp_left_unnamed),
        RouteService.API.Maneuver.TURN_SHARP_RIGHT to
            (R.string.maneuver_turn_sharp_right to
                R.string.maneuver_turn_sharp_right_unnamed),
        RouteService.API.Maneuver.UTURN_LEFT to
            (R.string.maneuver_uturn to R.string.maneuver_uturn_unnamed),
        RouteService.API.Maneuver.UTURN_RIGHT to
            (R.string.maneuver_uturn to R.string.maneuver_uturn_unnamed),
        RouteService.API.Maneuver.MERGE to
            (R.string.maneuver_merge to R.string.maneuver_merge_unnamed),
        RouteService.API.Maneuver.RAMP_LEFT to
            (R.string.maneuver_ramp_left to R.string.maneuver_ramp_left_unnamed),
        RouteService.API.Maneuver.RAMP_RIGHT to
            (R.string.maneuver_ramp_right to R.string.maneuver_ramp_right_unnamed),
        RouteService.API.Maneuver.FORK_LEFT to
            (R.string.maneuver_fork_left to R.string.maneuver_fork_left_unnamed),
        RouteService.API.Maneuver.FORK_RIGHT to
            (R.string.maneuver_fork_right to R.string.maneuver_fork_right_unnamed),
        RouteService.API.Maneuver.ROUNDABOUT_LEFT to
            (R.string.maneuver_roundabout to R.string.maneuver_roundabout_unnamed),
        RouteService.API.Maneuver.ROUNDABOUT_RIGHT to
            (R.string.maneuver_roundabout to R.string.maneuver_roundabout_unnamed),
    )

    private fun waitInstruction(
        context: Context,
        raw: OfflineRouter.RawStep,
    ): String? {
        if (maneuverOf(raw.maneuverId) != RouteService.API.Maneuver.WAIT) return null
        val waitSeconds = raw.duration10ms / WAIT_CENTISEC_PER_SECOND
        val waitText = if (waitSeconds >= SECONDS_PER_MINUTE) {
            "${waitSeconds / SECONDS_PER_MINUTE} min"
        } else {
            "$waitSeconds sec"
        }
        return if (raw.stopCode != null && raw.stopCode.isNotBlank()) {
            context.getString(R.string.maneuver_wait_at, waitText, raw.roadName, raw.stopCode)
        } else {
            context.getString(R.string.maneuver_wait, waitText, raw.roadName)
        }
    }

    private fun fallbackInstruction(
        context: Context,
        raw: OfflineRouter.RawStep,
        mode: RouteService.TravelMode,
        hasName: Boolean,
    ): String {
        if (raw.isTransit && raw.stopCode != null && raw.endStopCode != null) {
            return context.resources.getQuantityString(
                R.plurals.maneuver_ride_transit, raw.stopCount,
                raw.roadName, raw.stopCode, raw.endStopCode, raw.stopCount,
            )
        }
        if (mode == RouteService.TravelMode.TRANSIT) {
            // A walk leg of an itinerary. The planner names
            // every one of them "Walk", which the road-name
            // templates below turn into "Continue onto Walk".
            // Phrased for real after coalescing, from the
            // merged duration; this placeholder only has to
            // compare equal between adjacent walk legs so
            // they still merge.
            return WALK_LEG_PLACEHOLDER
        }
        return namedOrUnnamed(
            context, hasName, raw.roadName,
            R.string.maneuver_unspecified, UiR.string.continue_label,
        )
    }

    private fun namedOrUnnamed(
        context: Context,
        hasName: Boolean,
        roadName: String,
        named: Int,
        unnamed: Int,
    ): String = if (hasName) {
        context.getString(named, roadName)
    } else {
        context.getString(unnamed)
    }

    private fun maneuverOf(maneuverId: Int): RouteService.API.Maneuver =
        RouteService.API.Maneuver.entries.getOrElse(maneuverId) {
            RouteService.API.Maneuver.MANEUVER_UNSPECIFIED
        }

    /**
     * Decode packed turn lanes into ordered left→right lane guidance. Each int
     * is `dirMask * 2 + valid`, where dirMask is a bitmask of Maneuver ordinals
     * the lane offers (real OSM turn:lanes can allow several turns, e.g.
     * through+right) and bit0 is the active flag (lane leads onto the taken
     * route).
     */
    private fun decodeLane(code: Int): RouteService.API.Lane {
        val active = (code and LANE_ACTIVE_BIT) == 1
        val mask = code ushr LANE_MASK_SHIFT
        val directions = RouteService.API.Maneuver.entries.filter { m ->
            (mask and (1 shl m.ordinal)) != 0
        }
        return RouteService.API.Lane(
            directions = directions.ifEmpty {
                listOf(RouteService.API.Maneuver.STRAIGHT)
            },
            active = active,
        )
    }

    private fun stepTravelMode(
        raw: OfflineRouter.RawStep,
        mode: RouteService.TravelMode,
    ): RouteService.TravelMode = when {
        raw.isTransit -> RouteService.TravelMode.TRANSIT
        mode == RouteService.TravelMode.TRANSIT -> RouteService.TravelMode.WALK
        else -> mode
    }

    private fun transitDetails(
        context: Context,
        raw: OfflineRouter.RawStep,
    ): RouteService.API.TransitDetails? {
        if (!raw.isTransit || raw.gtfsFeed == null || raw.stopCode == null) return null
        return RouteService.API.TransitDetails(
            headsign = raw.headsign ?: "",
            stopCount = raw.stopCount,
            transitLine = RouteService.API.TransitLine(
                name = raw.roadName,
                // The index carries route_color for
                // every feed; GTFSProvider only sees
                // the bundled APK asset feed, so it is
                // just a fallback now.
                color = raw.routeColor
                    .takeIf { it != 0 }
                    ?.let { "#%06X".format(it and RGB_MASK) }
                    ?: GTFSProvider.getRouteColor(context, raw.gtfsFeed, raw.roadName)
                    ?: FALLBACK_ROUTE_COLOR
            ),
            stopDetails = RouteService.API.StopDetails(
                arrivalTime = formatServiceTime(raw.arrSecs),
                departureTime = formatServiceTime(raw.depSecs),
                arrivalStop = RouteService.API.Stop(raw.endStopCode ?: ""),
                departureStop = RouteService.API.Stop(raw.stopCode)
            ),
            feedName = raw.gtfsFeed
        )
    }

    private fun assembleRoute(
        context: Context,
        rawSteps: Array<OfflineRouter.RawStep>,
        mode: RouteService.TravelMode,
        fullPolyline: List<GeoPoint>,
        processedSteps: List<RouteService.Step>,
    ): RouteService.Route {
        val coalescedSteps = coalesceSteps(processedSteps)
        return RouteService.Route(
            duration = coalescedSteps.sumOf { it.staticDuration.inWholeSeconds }.seconds,
            distanceMeters = coalescedSteps.sumOf { it.distanceMeters },
            polyline = fullPolyline,
            step = phraseWalkLegs(coalescedSteps, mode) { minutes ->
                context.resources.getQuantityString(
                    R.plurals.maneuver_walk_minutes,
                    minutes,
                    minutes,
                )
            },
            departureTime = leaveAt(rawSteps),
            arrivalTime = arriveAt(rawSteps),
            elevationProfile = elevationProfile(rawSteps),
            // The whole-route total is carried on every RawStep (0 on transit legs),
            // so the first one is enough.
            ascentMeters = rawSteps.firstOrNull()?.ascentM ?: 0.0,
            descentMeters = rawSteps.firstOrNull()?.descentM ?: 0.0,
        )
    }

    /**
     * Build the route-wide elevation profile from the native [OfflineRouter.RawStep]s: walk every step's
     * per-coordinate [OfflineRouter.RawStep.elevations] (parallel to its geometry), drop the join point each step
     * shares with the previous one, and pair each with its cumulative ground distance from the
     * start. Empty when no step carries elevation (transit, or a graph with no DEM).
     */
    private fun elevationProfile(rawSteps: Array<OfflineRouter.RawStep>): List<RouteService.ElevationPoint> {
        val out = mutableListOf<RouteService.ElevationPoint>()
        var cumDist = 0.0
        var prevLon = Double.NaN
        var prevLat = Double.NaN
        for (raw in rawSteps) {
            if (raw.elevations.isEmpty()) continue
            val g = raw.geometry
            var k = 0
            while (k + 1 < g.size) {
                val lon = g[k]
                val lat = g[k + 1]
                val elev = raw.elevations.getOrNull(k / 2) ?: break
                val first = prevLon.isNaN()
                val dup = !first && lon == prevLon && lat == prevLat
                if (!dup) {
                    if (!first) cumDist += crowMeters(prevLat, prevLon, lat, lon)
                    out.add(RouteService.ElevationPoint(cumDist, elev))
                    prevLon = lon
                    prevLat = lat
                }
                k += 2
            }
        }
        return out
    }

    /** Approximate ground distance in metres between two lat/lon points (equirectangular). */
    private fun crowMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dlat = (lat2 - lat1) * METERS_PER_DEGREE
        val dlon = (lon2 - lon1) * METERS_PER_DEGREE *
            Math.cos(Math.toRadians((lat1 + lat2) * LAT_MIDPOINT))
        return Math.hypot(dlat, dlon)
    }

    // Coalesce consecutive maneuvers that stay on the same road.
    // The native router can emit a chain of small "slight left /
    // slight right" entries along a curving stretch of road
    // (e.g. El Camino Real bending through Palo Alto) where
    // the road name never changes — visually that's "stay on
    // the same road", not a series of turns. Merge those into
    // a single step whose polyline + distance + duration is the
    // sum of the merged steps.
    private fun coalesceSteps(processedSteps: List<RouteService.Step>): List<RouteService.Step> {
        val coalescedSteps = mutableListOf<RouteService.Step>()
        for (step in processedSteps) {
            val prev = coalescedSteps.lastOrNull()
            // Smart-cast prev to non-null in the merge branch by
            // gating on prev != null first.
            if (prev != null && mergesWith(prev, step)) {
                coalescedSteps[coalescedSteps.lastIndex] = prev.copy(
                    distanceMeters = prev.distanceMeters + step.distanceMeters,
                    staticDuration = prev.staticDuration + step.staticDuration,
                    polyline = mergePolylines(prev.polyline, step.polyline),
                )
            } else {
                coalescedSteps.add(step)
            }
        }
        return coalescedSteps
    }

    private fun mergesWith(prev: RouteService.Step, step: RouteService.Step): Boolean =
        prev.travelMode == step.travelMode &&
            step.travelMode != RouteService.TravelMode.TRANSIT &&
            step.navInstruction.maneuver in NON_TURNING_MANEUVERS &&
            // Road name unchanged (instruction text is
            // road-name-templated, so equal strings ⇒ same road).
            sameRoadName(prev.navInstruction.instructions, step.navInstruction.instructions)

    /**
     * Maneuvers that we treat as "still on the same road" when their
     * instruction text doesn't change between steps. A SHARP turn or a
     * RAMP / FORK / MERGE / ROUNDABOUT is always a real maneuver even if
     * the road name happens to match.
     */
    private val NON_TURNING_MANEUVERS = setOf(
        RouteService.API.Maneuver.STRAIGHT,
        RouteService.API.Maneuver.TURN_SLIGHT_LEFT,
        RouteService.API.Maneuver.TURN_SLIGHT_RIGHT,
        RouteService.API.Maneuver.NAME_CHANGE,
        RouteService.API.Maneuver.MANEUVER_UNSPECIFIED,
    )

    /**
     * Two adjacent maneuvers are considered "on the same road" when the
     * instruction strings match. Instruction text is templated from the
     * road name (see the maneuver_* string templates), so identical
     * instruction strings ⇒ same road.
     */
    private fun sameRoadName(prev: String, curr: String): Boolean =
        prev.isNotBlank() && prev == curr

    /** Concatenate two step polylines, skipping the duplicate join point. */
    private fun mergePolylines(
        a: List<GeoPoint>,
        b: List<GeoPoint>,
    ): List<GeoPoint> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        return if (a.last() == b.first()) a + b.drop(1) else a + b
    }
}
