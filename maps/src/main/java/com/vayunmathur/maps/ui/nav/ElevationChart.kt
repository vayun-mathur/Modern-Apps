package com.vayunmathur.maps.ui.nav
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R
import com.vayunmathur.maps.domain.RouteService
import kotlin.math.roundToInt

/**
 * Route elevation profile chart (WS-G): plots ground elevation against cumulative distance and
 * summarises total ascent/descent. Fed by [RouteService.Route.elevationProfile], which the offline
 * router bakes from the graph's per-node DEM elevation. Renders nothing when there is no usable
 * profile (fewer than two samples, or a dead-flat route), so callers can invoke it unconditionally.
 */
@Composable
fun ElevationChart(
    profile: List<RouteService.ElevationPoint>,
    ascentMeters: Double,
    descentMeters: Double,
    modifier: Modifier = Modifier,
) {
    if (profile.size < 2) return
    val minElev = profile.minOf { it.elevationMeters }
    val maxElev = profile.maxOf { it.elevationMeters }
    val totalDist = profile.last().distanceMeters
    // A flat or zero-length route has no shape worth drawing.
    if (totalDist <= 0.0 || maxElev - minElev < 1.0) return

    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.nav_elevation_title),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            stringResource(
                R.string.nav_elevation_gain_loss,
                ascentMeters.roundToInt(),
                descentMeters.roundToInt(),
            ),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val w = size.width
            val h = size.height
            val elevRange = (maxElev - minElev).coerceAtLeast(1.0)
            // Map a profile sample to a canvas point, y inverted so higher ground is higher up.
            fun px(p: RouteService.ElevationPoint): Offset {
                val x = (p.distanceMeters / totalDist * w).toFloat()
                val y = (h - (p.elevationMeters - minElev) / elevRange * h).toFloat()
                return Offset(x, y)
            }

            val first = px(profile.first())
            val line = Path().apply { moveTo(first.x, first.y) }
            val area = Path().apply { moveTo(first.x, h); lineTo(first.x, first.y) }
            for (i in 1 until profile.size) {
                val o = px(profile[i])
                line.lineTo(o.x, o.y)
                area.lineTo(o.x, o.y)
            }
            area.lineTo(w, h)
            area.close()

            drawLine(axisColor, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
            drawPath(area, fillColor)
            drawPath(line, lineColor, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
