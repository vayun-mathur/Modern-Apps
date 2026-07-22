package com.vayunmathur.astronomy.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.vayunmathur.astronomy.domain.engine.AltAz
import com.vayunmathur.astronomy.domain.projection.StereographicProjection
import com.vayunmathur.astronomy.domain.projection.ViewState
import com.vayunmathur.astronomy.ui.TrajectoryPoint
import com.vayunmathur.astronomy.ui.VisibleSky
import kotlin.math.*

@Composable
fun SkyCanvas(
    visibleSky: VisibleSky,
    viewState: ViewState,
    showConstellationLines: Boolean,
    showGrid: Boolean,
    showDeepSky: Boolean,
    showPlanets: Boolean,
    transparentBackground: Boolean = false,
    trajectory: List<TrajectoryPoint> = emptyList(),
    selectedId: String? = null,
    onPan: (azDeg: Double, altDeg: Double) -> Unit,
    onZoom: (Float) -> Unit,
    onTap: (Offset) -> Unit,
    onObjectTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val projection = remember(viewState) { StereographicProjection(viewState) }

    val projectedStars = remember(visibleSky.stars, projection) {
        visibleSky.stars.mapNotNull { vs -> projection.project(vs.altAz)?.let { Triple(vs, it, vs.star.mag) } }
    }
    val projectedPlanets = remember(visibleSky.planets, projection) {
        visibleSky.planets.mapNotNull { vp -> projection.project(vp.altAz)?.let { vp to it } }
    }
    val projectedDeep = remember(visibleSky.deepSky, projection) {
        visibleSky.deepSky.mapNotNull { vd -> projection.project(vd.altAz)?.let { vd to it } }
    }
    val sunProj = remember(visibleSky.sun, projection) { visibleSky.sun?.let { it to projection.project(it.altAz) } }
    val moonProj = remember(visibleSky.moon, projection) { visibleSky.moon?.let { it to projection.project(it.altAz) } }
    val trajectoryProjected = remember(trajectory, projection) {
        trajectory.mapNotNull { tp -> projection.project(tp.altAz)?.let { off -> off to tp } }
    }


    Canvas(
        modifier = modifier.fillMaxSize()
            .pointerInput(viewState) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    // viewState.fovDeg is Float
                    val azDelta = (-dragAmount.x / w * viewState.fovDeg).toDouble()
                    val altDelta = (dragAmount.y / h * viewState.fovDeg).toDouble() // revert: natural
                    onPan(azDelta, altDelta)
                }
            }
            .pointerInput(viewState) {
                detectTransformGestures { _, _, zoom, _ ->
                    onZoom((viewState.fovDeg / zoom).coerceIn(10f, 120f))
                }
            }
            .pointerInput(visibleSky, projection, projectedStars, projectedPlanets, projectedDeep, sunProj, moonProj) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        if (!change.pressed && change.previousPressed) {
                            val pos = change.position
                            fun dist(a: Offset, b: Offset): Float { val dx = a.x - b.x; val dy = a.y - b.y; return sqrt(dx*dx+dy*dy) }
                            val hitPlanet = projectedPlanets.firstOrNull { (_, off) -> dist(off, pos) < 48f }
                            if (hitPlanet != null) {
                                onObjectTap("PLANET_${hitPlanet.first.id}")
                            } else {
                                val closest = projectedStars.minByOrNull { (_, off, _) -> dist(off, pos) }
                                val hitStar = if (closest != null && dist(closest.second, pos) < 36f) closest else null
                                if (hitStar != null) onObjectTap("STAR_${hitStar.first.star.id}")
                                else {
                                    val hitDeep = projectedDeep.firstOrNull { (_, off) -> dist(off, pos) < 40f }
                                    if (hitDeep != null) onObjectTap(hitDeep.first.obj.id)
                                    else {
                                        val sp = sunProj?.second
                                        val mp = moonProj?.second
                                        if (sp != null && dist(sp, pos) < 40f) onObjectTap("SUN")
                                        else if (mp != null && dist(mp, pos) < 40f) onObjectTap("MOON")
                                        else onTap(pos)
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        if (!transparentBackground) drawRect(Color(0xFF020617))

        if (showGrid) drawGrid(projection, viewState)
        drawHorizon(projection, viewState)

        if (showConstellationLines) {
            val starMap = projectedStars.associate { (vs, off, _) -> vs.star.id to off }
            visibleSky.constellations.forEach { const ->
                const.segments.forEach { seg ->
                    if (seg.size < 2) return@forEach
                    val a = seg[0]; val b = seg[1]
                    val oa = starMap[a] ?: return@forEach
                    val ob = starMap[b] ?: return@forEach
                    drawLine(Color(0x66FFFFFF), oa, ob, 1f)
                }
            }
        }

        if (trajectoryProjected.size > 1) {
            val path = Path()
            var first = true
            trajectoryProjected.forEach { (off, _) ->
                if (first) { path.moveTo(off.x, off.y); first = false } else path.lineTo(off.x, off.y)
            }
            drawPath(path, Color(0x88FFEB3B), style = Stroke(2f))
        }

        if (showDeepSky) {
            projectedDeep.forEach { (vd, off) ->
                val color = when (vd.obj.type) { "galaxy" -> Color(0xFF90CAF9); "nebula" -> Color(0xFFCE93D8); "cluster" -> Color(0xFFFFCC80); else -> Color.White }
                drawCircle(color.copy(alpha = 0.8f), 6f, off)
                drawCircle(color, 6f, off, style = Stroke(1.5f))
                if (vd.obj.mag < 6) drawText(textMeasurer, vd.obj.id, topLeft = off + Offset(8f, -8f), style = TextStyle(color, fontSize = 10.sp))
            }
        }

        projectedStars.forEach { (vs, off, mag) ->
            val alpha = ((6.5 - mag) / 6.5).coerceIn(0.15, 1.0).toFloat()
            val col = bvToColor(vs.star.bv).copy(alpha = alpha)
            val r = starRadius(mag)
            drawCircle(col, r, off)
            if (mag < 1.0) drawCircle(col.copy(alpha = 0.2f), r * 2.2f, off)
        }

        sunProj?.let { (_, off) ->
            if (off != null) {
                drawCircle(Color(0x99FFEB3B), 18f, off)
                drawCircle(Color(0xFFFFEB3B), 10f, off)
                drawText(textMeasurer, "Sun", topLeft = off + Offset(14f, -10f), style = TextStyle(Color(0xFFFFEB3B), fontSize = 12.sp))
            }
        }
        moonProj?.let { (_, off) ->
            if (off != null) {
                drawCircle(Color.LightGray, 9f, off)
                drawCircle(Color.DarkGray, 9f, off, style = Stroke(1f))
                drawText(textMeasurer, "Moon", topLeft = off + Offset(12f, -8f), style = TextStyle(Color.LightGray, fontSize = 11.sp))
            }
        }

        if (showPlanets) {
            projectedPlanets.forEach { (vp, off) ->
                val planetColor = planetColor(vp.id)
                drawCircle(planetColor, 7f, off)
                drawCircle(planetColor.copy(alpha = 0.4f), 11f, off)
                drawText(textMeasurer, vp.name, topLeft = off + Offset(10f, -10f), style = TextStyle(planetColor, fontSize = 11.sp))
            }
        }

        if (selectedId != null) {
            val selOff = when {
                selectedId == "SUN" -> sunProj?.second
                selectedId == "MOON" -> moonProj?.second
                selectedId.startsWith("PLANET_") -> projectedPlanets.firstOrNull { it.first.id == selectedId.removePrefix("PLANET_") }?.second
                selectedId.startsWith("STAR_") -> projectedStars.firstOrNull { "STAR_${it.first.star.id}" == selectedId }?.second
                else -> projectedDeep.firstOrNull { it.first.obj.id == selectedId }?.second
            }
            selOff?.let { drawCircle(Color.Yellow, 16f, it, style = Stroke(1.8f)) }
        }

        if (viewState.centerAltRad < Math.toRadians(60.0)) drawCardinalLabels(projection, viewState, textMeasurer)
    }
}

/**
 * Whole-sphere azimuth + altitude graticule:
 * - parallels: alt = -80..+80 step 15 deg (visible + invisible altitude circles)
 * - meridians: az = 0..360 step 30 deg, alt -90..+90 step 3 deg
 * Handles below-horizon lines and dashed wrap-around by segmenting invisible gaps.
 */
private fun DrawScope.drawGrid(projection: com.vayunmathur.astronomy.domain.projection.SkyProjection, viewState: ViewState) {
    val gridMajor = Color(0x33FFFFFF)
    val gridMinor = Color(0x18FFFFFF)
    // Parallels: altitude circles (constant alt, sweep az 0..360)
    // Whole sphere — includes negative altitudes for below horizon when viewer tilts down / device pointing
    for (altDeg in -75..75 step 15) {
        if (altDeg == 0) continue // horizon drawn separately with distinct color
        val color = if (altDeg % 30 == 0) gridMajor else gridMinor
        var currentPath: Path? = null
        var lastVisible = false
        for (azDeg in 0..360 step 2) {
            val aa = AltAz(Math.toRadians(azDeg.toDouble()), Math.toRadians(altDeg.toDouble()))
            val off = projection.project(aa)
            if (off != null) {
                if (currentPath == null || !lastVisible) {
                    currentPath = Path().apply { moveTo(off.x, off.y) }
                } else {
                    currentPath.lineTo(off.x, off.y)
                }
                lastVisible = true
            } else {
                if (currentPath != null && lastVisible) {
                    drawPath(currentPath, color, style = Stroke(0.8f))
                    currentPath = null
                }
                lastVisible = false
            }
        }
        if (currentPath != null) drawPath(currentPath, color, style = Stroke(0.8f))
    }
    // Meridians: azimuth lines (constant az, sweep alt -90..+90)
    for (azDeg in 0..330 step 30) {
        val color = if (azDeg % 90 == 0) gridMajor else gridMinor
        var currentPath: Path? = null
        var lastVisible = false
        for (altDeg in -90..90 step 2) {
            // avoid poles singularity where az undefined at alt=90
            val aa = AltAz(Math.toRadians(azDeg.toDouble()), Math.toRadians(altDeg.toDouble()))
            val off = projection.project(aa)
            if (off != null) {
                if (currentPath == null || !lastVisible) {
                    currentPath = Path().apply { moveTo(off.x, off.y) }
                } else {
                    currentPath.lineTo(off.x, off.y)
                }
                lastVisible = true
            } else {
                if (currentPath != null && lastVisible) {
                    drawPath(currentPath, color, style = Stroke(0.8f))
                    currentPath = null
                }
                lastVisible = false
            }
        }
        if (currentPath != null) drawPath(currentPath, color, style = Stroke(0.8f))
    }
}

private fun DrawScope.drawHorizon(projection: com.vayunmathur.astronomy.domain.projection.SkyProjection, viewState: ViewState) {
    val path = Path(); var first = true; val col = Color(0x553CB371)
    for (azDeg in 0..360 step 2) {
        val aa = AltAz(Math.toRadians(azDeg.toDouble()), 0.0)
        val off = projection.project(aa) ?: continue
        if (first) { path.moveTo(off.x, off.y); first = false } else path.lineTo(off.x, off.y)
    }
    drawPath(path, col, style = Stroke(1.4f))
}

private fun DrawScope.drawCardinalLabels(projection: com.vayunmathur.astronomy.domain.projection.SkyProjection, viewState: ViewState, measurer: androidx.compose.ui.text.TextMeasurer) {
    listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, azDeg) ->
        val aa = AltAz(Math.toRadians(azDeg), 0.0)
        val off = projection.project(aa) ?: return@forEach
        drawText(measurer, label, topLeft = off + Offset(-6f, 6f), style = TextStyle(Color(0xFF9E9E9E), fontSize = 12.sp))
    }
}

private fun starRadius(mag: Double): Float = (4.2 - mag * 0.55).toFloat().coerceIn(0.9f, 5.5f)

fun bvToColor(bv: Double): Color = when {
    bv < -0.2 -> Color(0xFF9BB0FF)
    bv < 0.0 -> Color(0xFFC6D2FF)
    bv < 0.3 -> Color.White
    bv < 0.6 -> Color(0xFFFFFFE0)
    bv < 1.0 -> Color(0xFFFFE4B5)
    bv < 1.4 -> Color(0xFFFFA500)
    else -> Color(0xFFFF6347)
}

fun planetColor(id: String): Color = when (id) {
    "MERCURY" -> Color(0xFFA9A9A9)
    "VENUS" -> Color(0xFFFFE0B0)
    "MARS" -> Color(0xFFFF4500)
    "JUPITER" -> Color(0xFFDEB887)
    "SATURN" -> Color(0xFFF0E68C)
    "URANUS" -> Color(0xFFADD8E6)
    "NEPTUNE" -> Color(0xFF4169E1)
    else -> Color.White
}
