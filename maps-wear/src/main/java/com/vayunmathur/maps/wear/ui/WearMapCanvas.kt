package com.vayunmathur.maps.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vayunmathur.maps.wear.domain.WearLineKind
import com.vayunmathur.maps.wear.domain.WearMapProjection
import com.vayunmathur.maps.wear.domain.WearMapSnapshot
import com.vayunmathur.maps.wear.domain.WearPosition
import com.vayunmathur.maps.wear.domain.WearRouteState
import com.vayunmathur.maps.wear.domain.wearDequantize
import com.vayunmathur.maps.wear.domain.wearOffsetInSnapshot
import com.vayunmathur.maps.wear.domain.wearRoutePointInSnapshot
import kotlin.math.cos
import kotlin.math.sin

private val LandColor = Color(0xFF101613)
private val WaterColor = Color(0xFF1D4E6B)
private val BuildingColor = Color(0xFF394149)
private val RoadColor = Color(0xFF9AA3AD)
private val PathColor = Color(0xFF6E7B6E)
private val RouteColor = Color(0xFF4DA3FF)
private val PoiColor = Color(0xFFFFC46B)
private val SavedColor = Color(0xFF7EE081)
private val UserColor = Color(0xFF4DA3FF)

/**
 * Lightweight Canvas renderer for one phone-supplied vector snapshot:
 * no tile stack, no map library — just projected polylines/polygons,
 * POI dots, the route overlay and the user puck. North-up in v1.
 * Draw order: water/buildings (fills), roads/paths, route, points, user.
 * Follows the user when a position is known, else centres the snapshot.
 */
@Composable
fun WearMapCanvas(
    snapshot: WearMapSnapshot,
    route: WearRouteState?,
    position: WearPosition?,
) {
    Canvas(Modifier.fillMaxSize()) {
        val userOffset = position?.let { wearOffsetInSnapshot(snapshot, it.latE7, it.lonE7) }
        val projection = WearMapProjection(
            viewportWidthPx = size.width,
            viewportHeightPx = size.height,
            radiusM = snapshot.radiusM,
            centerEastM = userOffset?.first ?: 0.0,
            centerNorthM = userOffset?.second ?: 0.0,
        )
        drawRect(LandColor)
        fun pathOf(flat: List<Int>, toMetres: (Int, Int) -> Pair<Double, Double>): Path {
            val path = Path()
            for (i in flat.indices step 2) {
                if (i + 1 >= flat.size) break
                val (east, north) = toMetres(flat[i], flat[i + 1])
                val (x, y) = projection.project(east, north)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }
        val snapshotPts = { dx: Int, dy: Int -> wearDequantize(dx) to wearDequantize(dy) }
        // Fills first, then strokes in kind-priority order.
        for (line in snapshot.lines.filter { it.closed }) {
            val color = if (line.kind == WearLineKind.WATER) WaterColor else BuildingColor
            drawPath(pathOf(line.pts, snapshotPts), color)
        }
        for (kind in listOf(WearLineKind.WATER, WearLineKind.ROAD, WearLineKind.PATH)) {
            for (line in snapshot.lines.filter { !it.closed && it.kind == kind }) {
                drawPath(
                    pathOf(line.pts, snapshotPts),
                    color = when (kind) {
                        WearLineKind.WATER -> WaterColor
                        WearLineKind.ROAD -> RoadColor
                        else -> PathColor
                    },
                    style = Stroke(
                        width = if (kind == WearLineKind.ROAD) 3f else 2f,
                        pathEffect = if (kind == WearLineKind.PATH) {
                            PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                        } else {
                            null
                        },
                    ),
                )
            }
        }
        // Snapshot-embedded route lines, then the live route overlay (fresher).
        for (line in snapshot.lines.filter { !it.closed && it.kind == WearLineKind.ROUTE }) {
            drawPath(pathOf(line.pts, snapshotPts), RouteColor, style = Stroke(width = 5f))
        }
        if (route?.active == true && route.routePts.size >= 4) {
            val routePath = pathOf(route.routePts) { dx, dy ->
                wearRoutePointInSnapshot(snapshot, route, dx, dy)
            }
            drawPath(routePath, Color.White, style = Stroke(width = 7f))
            drawPath(routePath, RouteColor, style = Stroke(width = 5f))
        }
        for (point in snapshot.points) {
            val (x, y) = projection.project(wearDequantize(point.dx), wearDequantize(point.dy))
            drawCircle(PoiColor, radius = 3.5f, center = Offset(x, y))
        }
        for (place in snapshot.saved) {
            val (east, north) = wearOffsetInSnapshot(snapshot, place.latE7, place.lonE7)
            val (x, y) = projection.project(east, north)
            drawCircle(SavedColor, radius = 4.5f, center = Offset(x, y))
        }
        // User puck last, with a bearing cone when the phone reported one.
        val (ux, uy) = if (userOffset != null) {
            projection.project(userOffset.first, userOffset.second)
        } else {
            projection.project(0.0, 0.0)
        }
        val bearing = position?.bearingDeg ?: snapshot.userBearingDeg
        if (bearing >= 0) {
            val rad = Math.toRadians(bearing.toDouble())
            val cone = Path().apply {
                moveTo(ux, uy)
                lineTo(
                    ux + 26f * sin(rad - 0.35).toFloat(),
                    uy - 26f * cos(rad - 0.35).toFloat(),
                )
                lineTo(
                    ux + 26f * sin(rad + 0.35).toFloat(),
                    uy - 26f * cos(rad + 0.35).toFloat(),
                )
                close()
            }
            drawPath(cone, UserColor.copy(alpha = 0.35f))
        }
        drawCircle(Color.White, radius = 8f, center = Offset(ux, uy))
        drawCircle(UserColor, radius = 6f, center = Offset(ux, uy))
        // Scale bar: 100 m in the bottom-left, north marker top-right.
        val scalePx = (100.0 * projection.pixelsPerMeter).toFloat()
        drawLine(Color.White, Offset(12f, size.height - 12f), Offset(12f + scalePx, size.height - 12f), 3f)
        drawCircle(Color.DarkGray, radius = 13f, center = Offset(size.width - 18f, 18f))
    }
}
