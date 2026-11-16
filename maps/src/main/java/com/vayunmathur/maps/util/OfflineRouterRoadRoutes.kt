package com.vayunmathur.maps.util

import android.content.Context
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.SpecificFeature
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline-only multi-waypoint road routing for [OfflineRouter].
 *
 * Split out so `OfflineRouter` stays under the function cap. Replaces the old
 * server-side routing that handled intermediates remotely.
 * Positions = route.waypoints.map { it?.position ?: userPosition }.
 * Chains A->B, B->C ... using [OfflineRouter.getRoute] and concatenates
 * polylines (dedup join), steps, and sums distance/duration. Returns null if
 * any leg fails or if <2 positions. Same package, so no API changes for any
 * other caller.
 */
internal object OfflineRouterRoadRoutes {
    /** At least an origin and a destination for a multi-waypoint route. */
    private const val MIN_ROUTE_POSITIONS = 2

    /**
     * Plan a route for any [mode]. TRANSIT goes to the on-device RAPTOR planner
     * and nowhere else — there is no online routing fallback, so a journey the
     * pack cannot plan yields no transit route. Every other mode goes to the
     * road graph via [getRouteMulti].
     *
     * This is the **only** correct entry point for a caller whose mode is not a
     * literal: the road graph carries no timetable, so TRANSIT must never reach
     * [getRouteMulti]. Routing every mode-agnostic caller through here is what
     * guarantees that.
     */
    suspend fun getRouteForMode(
        context: Context,
        route: SpecificFeature.Route,
        userPosition: GeoPoint,
        mode: RouteService.TravelMode,
    ): RouteService.Route? = withContext(Dispatchers.Default) {
        if (mode != RouteService.TravelMode.TRANSIT) {
            return@withContext getRouteMulti(context, route, userPosition, mode)
        }
        val positions = route.waypoints.map { it?.position ?: userPosition }
        if (positions.size < MIN_ROUTE_POSITIONS) return@withContext null
        val start = positions.first()
        val end = positions.last()
        OfflineRouterTransit.getTransitRouteOffline(context, start, end)
    }

    suspend fun getRouteMulti(
        context: Context,
        route: SpecificFeature.Route,
        userPosition: GeoPoint,
        type: RouteService.TravelMode,
    ): RouteService.Route? = withContext(Dispatchers.Default) {
        val positions = route.waypoints.map { it?.position ?: userPosition }
        if (positions.size < MIN_ROUTE_POSITIONS) return@withContext null
        val legs = planLegs(context, positions, type) ?: return@withContext null
        if (legs.size == 1) return@withContext legs.first()
        combineLegs(legs)
    }

    private suspend fun planLegs(
        context: Context,
        positions: List<GeoPoint>,
        type: RouteService.TravelMode,
    ): List<RouteService.Route>? {
        val legs = mutableListOf<RouteService.Route>()
        for (i in 0 until positions.size - 1) {
            val leg = try {
                OfflineRouter.getRoute(context, positions[i], positions[i + 1], type)
            } catch (_: Exception) {
                return null
            }
            legs.add(leg)
        }
        if (legs.isEmpty()) return null
        return legs
    }

    private fun combineLegs(legs: List<RouteService.Route>): RouteService.Route {
        val acc = CombinedAccumulator()
        for (leg in legs) {
            acc.append(leg)
        }
        return acc.build()
    }

    /** Running totals while concatenating legs. */
    private class CombinedAccumulator {
        val polyline = mutableListOf<GeoPoint>()
        val steps = mutableListOf<RouteService.Step>()
        val elevation = mutableListOf<RouteService.ElevationPoint>()
        var totalDist = 0.0
        var totalSec = 0L
        var totalAscent = 0.0
        var totalDescent = 0.0

        fun append(leg: RouteService.Route) {
            appendPolyline(leg.polyline)
            steps.addAll(leg.step)
            // Offset each leg's profile by the route distance before it so the chart is continuous.
            val distOffset = totalDist
            for (p in leg.elevationProfile) {
                elevation.add(p.copy(distanceMeters = p.distanceMeters + distOffset))
            }
            totalDist += leg.distanceMeters
            totalSec += leg.duration.inWholeSeconds
            totalAscent += leg.ascentMeters
            totalDescent += leg.descentMeters
        }

        private fun appendPolyline(next: List<GeoPoint>) {
            if (polyline.isEmpty()) {
                polyline.addAll(next)
                return
            }
            val first = next.firstOrNull()
            if (first != null && polyline.lastOrNull() == first) polyline.addAll(next.drop(1))
            else polyline.addAll(next)
        }

        fun build(): RouteService.Route = RouteService.Route(
            duration = totalSec.seconds,
            distanceMeters = totalDist,
            polyline = polyline,
            step = steps,
            elevationProfile = elevation,
            ascentMeters = totalAscent,
            descentMeters = totalDescent,
        )
    }
}

/**
 * Primary plus genuinely different alternatives for a two-point route.
 *
 * Candidate generation is deliberately bounded — one extra plan per
 * mode, on top of the single-route native planners: a via-detour plan
 * (midpoint pushed perpendicular to the direct corridor) for road
 * modes, and a later-departure RAPTOR plan for transit. Every candidate
 * then has to pass [AlternativeRoutes.diverseAlternatives]; when none
 * does, the result is exactly the primary. Multi-waypoint routes get
 * no generated candidates (a via point would rewrite the user's legs).
 */
/** See [AlternativeRoutes] for the gating this feeds. */
internal suspend fun planWithAlternatives(
    context: android.content.Context,
    route: SpecificFeature.Route,
    userPosition: GeoPoint,
    mode: RouteService.TravelMode,
): List<RouteService.Route> {
    val primary = OfflineRouterRoadRoutes.getRouteForMode(context, route, userPosition, mode)
        ?: return emptyList()
    val positions = route.waypoints.map { it?.position ?: userPosition }
    if (positions.size != 2) return listOf(primary)
    val start = positions.first()
    val end = positions.last()
    val candidate = try {
        if (mode == RouteService.TravelMode.TRANSIT) {
            OfflineRouterTransit.getTransitRouteOffline(context, start, end, departureOffsetSecs = 900)
        } else {
            val via = viaPoint(start, end)
            val viaFeature = SpecificFeature.GenericPlace("via", null, null, null, via)
            OfflineRouterRoadRoutes.getRouteMulti(
                context,
                SpecificFeature.Route(listOf(route.waypoints.first(), viaFeature, route.waypoints.last())),
                userPosition,
                mode,
            )
        }
    } catch (_: Exception) {
        null
    }
    val candidates = listOfNotNull(candidate)
    return listOf(primary) + AlternativeRoutes.diverseAlternatives(
        primary, candidates, transit = mode == RouteService.TravelMode.TRANSIT,
    )
}

/**
 * The midpoint of start→end pushed perpendicular by the larger of
 * 400 m or 20% of the direct distance: far enough to force a different
 * corridor where one exists, near enough that the detour is a route a
 * person might actually take.
 */
private fun viaPoint(start: GeoPoint, end: GeoPoint): GeoPoint {
    val midLat = (start.latitude + end.latitude) / 2
    val midLon = (start.longitude + end.longitude) / 2
    val dLat = end.latitude - start.latitude
    val dLon = (end.longitude - start.longitude) * kotlin.math.cos(Math.toRadians(midLat))
    val len = kotlin.math.sqrt(dLat * dLat + dLon * dLon).coerceAtLeast(1e-9)
    val offsetDeg = maxOf(400.0, 0.2 * len * 111_320.0) / 111_320.0
    val perpLat = -dLon / len * offsetDeg
    val perpLon = dLat / len * offsetDeg / kotlin.math.cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
    return GeoPoint(midLon + perpLon, midLat + perpLat)
}
