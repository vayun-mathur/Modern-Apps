package com.vayunmathur.findfamily.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.vayunmathur.findfamily.wear.domain.WearPerson
import com.vayunmathur.findfamily.wear.domain.WearRegion
import com.vayunmathur.findfamily.wear.domain.WearRelativeProjection
import com.vayunmathur.findfamily.wear.domain.wearRelativeOffsetMeters

private val CentreColor = Color(0xFF4DA3FF)
private val PersonColor = Color(0xFF7EE081)
private val StaleColor = Color(0xFF9AA3AD)
private val RingColor = Color(0xFF394149)

/**
 * Relative-position renderer: no basemap is faked — people are plotted
 * as labelled markers (first initial) at their true metres east/north
 * of the region centre, with the centre (you, when located) in the
 * middle. [highlightId] draws one person larger (detail view). Markers
 * outside the region radius are clamped to the rim, never hidden.
 */
@Composable
fun WearRelativeCanvas(
    region: WearRegion,
    people: List<WearPerson>,
    nowMillis: Long,
    highlightId: Long? = null,
) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0xFF101613))
        if (!region.hasCenter) return@Canvas
        val projection = WearRelativeProjection(size.width, size.height, region.radiusM)
        // Range rings at 1/3 and 2/3 radius for scale honesty.
        for (fraction in listOf(1f / 3f, 2f / 3f, 1f)) {
            drawCircle(
                RingColor,
                radius = (size.minDimension / 2f) * fraction,
                center = Offset(size.width / 2f, size.height / 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
            )
        }
        // North tick.
        drawLine(
            Color.White,
            Offset(size.width / 2f, 6f),
            Offset(size.width / 2f, 18f),
            strokeWidth = 3f,
        )
        for (person in people) {
            val lat = person.latE7 ?: continue
            val lon = person.lonE7 ?: continue
            var (east, north) = wearRelativeOffsetMeters(region.centerLatE7, region.centerLonE7, lat, lon)
            if (!projection.inside(east, north)) {
                val scale = region.radiusM / kotlin.math.sqrt(east * east + north * north)
                east *= scale
                north *= scale
            }
            val (x, y) = projection.project(east, north)
            val stale = com.vayunmathur.findfamily.wear.domain.wearIsStale(person, nowMillis)
            val isCentre = person.isSelf
            val highlighted = person.id == highlightId
            val radius = when {
                isCentre -> 9f
                highlighted -> 11f
                else -> 8f
            }
            drawCircle(Color.White, radius = radius + 2f, center = Offset(x, y))
            drawCircle(
                when {
                    isCentre -> CentreColor
                    stale -> StaleColor
                    else -> PersonColor
                },
                radius = radius,
                center = Offset(x, y),
            )
        }
        // Centre dot when self has no separate marker at the exact centre.
        drawCircle(Color.White, radius = 5f, center = Offset(size.width / 2f, size.height / 2f))
        drawCircle(CentreColor, radius = 3.5f, center = Offset(size.width / 2f, size.height / 2f))
    }
}

/** One-letter marker label for a person (initial), "?" when blank. */
fun wearMarkerInitial(name: String): String =
    name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
