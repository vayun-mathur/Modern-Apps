package com.vayunmathur.maps.intents
import com.vayunmathur.maps.data.string

import com.vayunmathur.library.intents.maps.DirectionsLatLng
import com.vayunmathur.library.intents.maps.DirectionsRequest
import com.vayunmathur.library.intents.maps.DirectionsResult
import com.vayunmathur.library.intents.maps.DirectionsSegment
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.OfflineRouterRoadRoutes
import com.vayunmathur.maps.domain.RouteService
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

/**
 * Answers a co-signed caller's request to plan a route, exported as a signature-guarded
 * (`com.vayunmathur.maps.permissions.ACCESS_MAPS`) NoDisplay
 * [AssistantIntent][com.vayunmathur.library.util.AssistantIntent].
 *
 * The route is planned by the same on-device [OfflineRouterRoadRoutes.getRouteForMode] the app itself
 * uses, and mapped to a [DirectionsResult] carrying the polyline plus the per-step traffic
 * [speedRatio][RouteService.Step.speedRatio] — the colour input the caller needs to draw the
 * route with maps' own red/amber/green congestion ramp (see maps' `RouteOverlayBuilder`).
 */
@OptIn(InternalSerializationApi::class)
class DirectionsIntent : AssistantIntent<DirectionsRequest, DirectionsResult>(
    serializer<DirectionsRequest>(),
    serializer<DirectionsResult>(),
) {
    override suspend fun performCalculation(input: DirectionsRequest): DirectionsResult {
        val start = GeoPoint(input.fromLng, input.fromLat)
        val end = GeoPoint(input.toLng, input.toLat)
        val request = SpecificFeature.Route(
            listOf(
                SpecificFeature.GenericPlace(
                    name = "From",
                    phone = null,
                    website = null,
                    openingHours = null,
                    position = start,
                ),
                SpecificFeature.GenericPlace(
                    name = "To",
                    phone = null,
                    website = null,
                    openingHours = null,
                    position = end,
                ),
            ),
        )
        val route = OfflineRouterRoadRoutes.getRouteForMode(this, request, start, parseMode(input.mode))
            ?: return EMPTY
        return route.toDirectionsResult()
    }

    /**
     * Match the wire mode string onto [RouteService.TravelMode] case-insensitively. Anything
     * unrecognised routes as driving — the taxi caller only ever asks for a driving route, and
     * the road graph is the correct planner for it.
     */
    private fun parseMode(mode: String): RouteService.TravelMode =
        when (mode.trim().uppercase()) {
            "TRANSIT" -> RouteService.TravelMode.TRANSIT
            "WALK", "WALKING" -> RouteService.TravelMode.WALK
            "BICYCLE", "BIKE", "BICYCLING" -> RouteService.TravelMode.BICYCLE
            else -> RouteService.TravelMode.DRIVE
        }

    private fun RouteService.Route.toDirectionsResult(): DirectionsResult =
        DirectionsResult(
            polyline = polyline.map { DirectionsLatLng(it.latitude, it.longitude) },
            // One segment per step, mirroring RouteOverlayBuilder's non-navigating case, so the
            // caller can colour each run by its own traffic band. Degenerate steps are dropped.
            segments = step.filter { it.polyline.size >= 2 }.map { step ->
                DirectionsSegment(
                    points = step.polyline.map { DirectionsLatLng(it.latitude, it.longitude) },
                    speedRatio = step.speedRatio,
                )
            },
            distanceMeters = distanceMeters,
            durationSeconds = duration.inWholeSeconds.toDouble(),
        )

    private companion object {
        val EMPTY = DirectionsResult(emptyList(), emptyList(), 0.0, 0.0)
    }
}
