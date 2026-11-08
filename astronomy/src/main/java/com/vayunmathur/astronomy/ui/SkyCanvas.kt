package com.vayunmathur.astronomy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.vayunmathur.astronomy.domain.projection.StereographicProjection
import com.vayunmathur.astronomy.domain.projection.ViewState
import com.vayunmathur.astronomy.domain.projection.projectAll
import com.vayunmathur.astronomy.platform.TrajectoryPoint
import com.vayunmathur.astronomy.platform.VisibleSky
import kotlin.math.sqrt

@Composable
fun SkyCanvas(
    visibleSky: VisibleSky,
    viewState: ViewState,
    showConstellationLines: Boolean,
    modifier: Modifier = Modifier,
    showConstellationArt: Boolean = false,
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
    onObjectOpen: (String) -> Unit = onObjectTap,
) {
    val textMeasurer = rememberTextMeasurer()
    val projection = remember(viewState) { StereographicProjection(viewState) }

    // Constellation-art bitmaps, decoded from assets on demand and cached.
    val context = LocalContext.current
    val artCache = remember { mutableMapOf<String, android.graphics.Bitmap?>() }
    val artPaint = remember {
        // Stellarium figure art is on a black background; additive blend makes the
        // black vanish and only the artwork glows over the sky.
        android.graphics.Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            alpha = 130
            blendMode = android.graphics.BlendMode.PLUS
        }
    }
    fun artBitmap(name: String): android.graphics.Bitmap? = artCache.getOrPut(name) {
        runCatching {
            context.assets.open("constellation_art/$name").use { android.graphics.BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    // Kept fresh so the pointer handler (keyed on projection/geometry, not these)
    // always sees the latest selection + callbacks.
    val currentSelectedId by rememberUpdatedState(selectedId)
    val currentShowPlanets by rememberUpdatedState(showPlanets)
    val currentOnObjectTap by rememberUpdatedState(onObjectTap)
    val currentOnObjectOpen by rememberUpdatedState(onObjectOpen)
    val currentOnTap by rememberUpdatedState(onTap)

    val projectedStars = remember(visibleSky.stars, projection) {
        // Stars are the largest list; batch-project them (native fast path when
        // available) and zip results back positionally, dropping culled points.
        val projected = projectAll(viewState, visibleSky.stars.map { it.altAz })
        visibleSky.stars.mapIndexedNotNull { i, vs ->
            projected[i]?.let { Triple(vs, it, vs.star.mag) }
        }
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
                            // First tap selects/highlights an object; a second tap on the
                            // already-selected object opens its detail page.
                            fun handleHit(id: String) {
                                if (id == currentSelectedId) currentOnObjectOpen(id) else currentOnObjectTap(id)
                            }
                            val hitPlanet = if (currentShowPlanets) {
                                projectedPlanets.firstOrNull { (_, off) -> dist(off, pos) < 48f }
                            } else {
                                null
                            }
                            if (hitPlanet != null) {
                                handleHit("PLANET_${hitPlanet.first.id}")
                            } else {
                                val closest = projectedStars.minByOrNull { (_, off, _) -> dist(off, pos) }
                                val hitStar = if (closest != null && dist(closest.second, pos) < 36f) closest else null
                                if (hitStar != null) handleHit("STAR_${hitStar.first.star.id}")
                                else {
                                    val hitDeep = projectedDeep.firstOrNull { (_, off) -> dist(off, pos) < 40f }
                                    if (hitDeep != null) handleHit(hitDeep.first.obj.id)
                                    else {
                                        val sp = if (currentShowPlanets) sunProj?.second else null
                                        val mp = if (currentShowPlanets) moonProj?.second else null
                                        if (sp != null && dist(sp, pos) < 40f) handleHit("SUN")
                                        else if (mp != null && dist(mp, pos) < 40f) handleHit("MOON")
                                        else currentOnTap(pos)
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        if (!transparentBackground) drawRect(Color(0xFF020617))

        if (showGrid) drawGrid(projection)
        drawHorizon(projection)

        // Constellation figure art, draped over the sphere: each image is warped
        // through a subdivided mesh so its interior follows the same projection as
        // the stars (not a flat triangle), keeping it glued to the stars on pan.
        if (showConstellationArt) {
            visibleSky.art.forEach { art ->
                if (art.anchors.size < 3) return@forEach
                if (art.anchors.none { projection.project(it.altAz) != null }) return@forEach
                val bmp = artBitmap(art.image) ?: return@forEach
                drawConstellationArt(projection, bmp, art, artPaint)
            }
        }

        if (showConstellationLines) {
            val starMap = projectedStars.associate { (vs, off, _) -> vs.star.id to off }
            visibleSky.constellations.forEach { const ->
                var sx = 0f; var sy = 0f; var n = 0
                const.segments.forEach { seg ->
                    if (seg.size < 2) return@forEach
                    val a = seg[0]; val b = seg[1]
                    val oa = starMap[a] ?: return@forEach
                    val ob = starMap[b] ?: return@forEach
                    drawLine(Color(0x66FFFFFF), oa, ob, 1f)
                    sx += oa.x + ob.x; sy += oa.y + ob.y; n += 2
                }
                // Label the constellation near the centroid of its drawn stars.
                if (n > 0) {
                    drawText(
                        textMeasurer, const.name,
                        topLeft = Offset(sx / n, sy / n),
                        style = TextStyle(Color(0x99B0C4FF), fontSize = 11.sp)
                    )
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

        // The Settings toggle is labelled "Planets / Sun / Moon", so the
        // Sun and Moon hide with the planets; they used to be drawn (and
        // tap-targetable) even with the toggle off.
        if (showPlanets) {
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
                selectedId == "SUN" && showPlanets -> sunProj?.second
                selectedId == "MOON" && showPlanets -> moonProj?.second
                selectedId.startsWith("PLANET_") && showPlanets -> projectedPlanets.firstOrNull { it.first.id == selectedId.removePrefix("PLANET_") }?.second
                selectedId.startsWith("STAR_") -> projectedStars.firstOrNull { "STAR_${it.first.star.id}" == selectedId }?.second
                else -> projectedDeep.firstOrNull { it.first.obj.id == selectedId }?.second
            }
            selOff?.let { drawCircle(Color.Yellow, 16f, it, style = Stroke(1.8f)) }
        }

        if (viewState.centerAltRad < Math.toRadians(60.0)) drawCardinalLabels(projection, textMeasurer)
    }
}
