package com.vayunmathur.maps.ui.map
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.domain.PolylineIndex

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.RouteOverlay
import com.vayunmathur.library.map.RouteSegment
import com.vayunmathur.library.map.RouteStyle
import com.vayunmathur.maps.ui.theme.MapTokens
import com.vayunmathur.maps.domain.NavigationProgress

/**
 * Turns a [RouteService.Route] into the coloured-segment [RouteOverlay] the Vulkan renderer
 * draws, replacing the Compose `RouteLayer` that used to stroke the same segments on a Canvas
 * over `VectorMap`. Drawing in the renderer is what stops the route lagging the basemap by a
 * frame on a pan — the puck, region mask and traffic already moved in for the same reason.
 *
 * The colouring is unchanged from `RouteLayer`: driving uses traffic-aware red/amber/green,
 * transit uses the line's own brand colour, walk/bike a single inert blue, and during
 * navigation the route is split at the snapped point so the travelled part greys out. Only
 * the output type changed — coloured [RouteSegment]s instead of GeoJSON `route-color`
 * features.
 *
 * No casing, matching `RouteLayer`'s plain 8 dp stroke: the phone route sat directly on the
 * basemap with no outline. (The car uses [RouteStyle]'s default casing; this deliberately
 * turns it off.)
 */
fun buildRouteOverlay(
    route: RouteService.Route,
    navProgress: NavigationProgress?,
    tokens: MapTokens,
): RouteOverlay? {
    val segments = buildRouteSegments(route, navProgress, tokens)
    if (segments.isEmpty()) return null
    return RouteOverlay(segments, RouteStyle(width = 8.dp, casingWidth = 0.dp))
}

/**
 * The per-step colour for the static (non-navigating) case. Driving is traffic-aware,
 * transit uses the line's own colour (falling back when the feed gives none or an
 * unparseable one), walk/bike fall through to a single inert colour.
 */
private fun staticColorFor(step: RouteService.Step, tokens: MapTokens): Color =
    when (step.travelMode) {
        RouteService.TravelMode.DRIVE -> when {
            step.speedRatio < 0.5 -> tokens.traffic.jam
            step.speedRatio < 0.9 -> tokens.traffic.slow
            else -> tokens.traffic.free
        }
        // The colour the pack (or MOTIS) reported for this route, which is what the
        // step-list badge already shows. A blank or malformed feed colour falls back
        // rather than drawing a garbage colour.
        RouteService.TravelMode.TRANSIT ->
            step.transitDetails?.transitLine?.color?.ifBlank { null }?.let(::parseHexColor)
                ?: tokens.routeTransitFallback
        else -> tokens.routeInert
    }

/** Parse a `#rrggbb`/`#aarrggbb` feed colour, or `null` when it is not valid. */
private fun parseHexColor(hex: String): Color? =
    runCatching { Color(hex.toColorInt()) }.getOrNull()

/**
 * Build the coloured segment list for the route.
 *
 * When [navProgress] is null this returns one segment per [RouteService.Step] with the
 * mode-aware static colour.
 *
 * When [navProgress] is non-null the polyline is split at the snapped point so that:
 *  - steps strictly before the current step get the traveled-gray colour,
 *  - the current step is split: portion behind the snap → gray; portion ahead → mode colour,
 *  - steps strictly after keep their mode colour.
 *
 * Splitting at the segment level requires matching the snapped segment index (which is into
 * the FULL `route.polyline`) to the corresponding vertex inside the current step's local
 * polyline. The math here mirrors [com.vayunmathur.maps.domain.PolylineIndex]'s `stepRanges`
 * construction (cursor walk; steps share endpoints).
 */
private fun buildRouteSegments(
    route: RouteService.Route,
    navProgress: NavigationProgress?,
    tokens: MapTokens,
): List<RouteSegment> {
    val traveledGray = tokens.traffic.traveled
    if (navProgress == null) {
        return route.step.filter { it.polyline.size >= 2 }.map { step ->
            RouteSegment(step.polyline, staticColorFor(step, tokens))
        }
    }

    val currentStepIdx = navProgress.currentStepIndex
    val snappedSegIdx = navProgress.segmentIndex // index into route.polyline
    val snappedPos = navProgress.snappedPosition

    val out = mutableListOf<RouteSegment>()
    // Walk the full polyline alongside the steps the same way PolylineIndex builds
    // stepRanges, so we know the vertex range for each step.
    var cursor = 0
    for ((stepIdx, step) in route.step.withIndex()) {
        val stepLen = step.polyline.size
        if (stepLen < 2) {
            // Nothing to render for a degenerate step. Cursor stays where it was (mirrors
            // PolylineIndex skipping the cursor advance).
            continue
        }
        val first = cursor
        val last = (first + stepLen - 1).coerceAtMost(route.polyline.size - 1)
        val color = staticColorFor(step, tokens)

        when {
            stepIdx < currentStepIdx -> {
                // Entirely behind: gray.
                out += RouteSegment(step.polyline, traveledGray)
            }
            stepIdx > currentStepIdx -> {
                // Entirely ahead: mode colour.
                out += RouteSegment(step.polyline, color)
            }
            snappedSegIdx < first -> {
                // Snap fell on an earlier step than our step-index math attributed to this
                // step (brief off-by-one near a boundary, or a glitchy GPS fix). Treat the
                // whole step as ahead rather than fabricating a gray spur.
                out += RouteSegment(step.polyline, color)
            }
            snappedSegIdx > last -> {
                // Snap fell on a later step. Treat the whole step as behind.
                out += RouteSegment(step.polyline, traveledGray)
            }
            else -> {
                // The active step: split at the snap point. snappedSegIdx is guaranteed in
                // [first, last] by the two guards above.
                val localSnapVertex = snappedSegIdx - first
                // Behind portion: vertices 0..localSnapVertex, with the snapped position
                // appended so the gray ends exactly under the user.
                val behindVertices = step.polyline.subList(0, localSnapVertex + 1).toMutableList()
                behindVertices.add(snappedPos)
                if (behindVertices.size >= 2) {
                    out += RouteSegment(behindVertices, traveledGray)
                }
                // Ahead portion: snapped position, then remaining vertices.
                val aheadVertices = mutableListOf(snappedPos)
                aheadVertices.addAll(step.polyline.subList(localSnapVertex + 1, stepLen))
                if (aheadVertices.size >= 2) {
                    out += RouteSegment(aheadVertices, color)
                }
            }
        }
        cursor = last
    }
    return out
}
