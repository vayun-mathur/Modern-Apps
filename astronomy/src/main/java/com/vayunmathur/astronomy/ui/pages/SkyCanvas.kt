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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.vayunmathur.astronomy.domain.engine.AltAz
import com.vayunmathur.astronomy.domain.projection.StereographicProjection
import com.vayunmathur.astronomy.domain.projection.ViewState
import com.vayunmathur.astronomy.ui.TrajectoryPoint
import com.vayunmathur.astronomy.ui.VisibleArt
import com.vayunmathur.astronomy.ui.VisibleSky
import kotlin.math.*

@Composable
fun SkyCanvas(
    visibleSky: VisibleSky,
    viewState: ViewState,
    showConstellationLines: Boolean,
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
    modifier: Modifier = Modifier
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
    val currentOnObjectTap by rememberUpdatedState(onObjectTap)
    val currentOnObjectOpen by rememberUpdatedState(onObjectOpen)
    val currentOnTap by rememberUpdatedState(onTap)

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
                            // First tap selects/highlights an object; a second tap on the
                            // already-selected object opens its detail page.
                            fun handleHit(id: String) {
                                if (id == currentSelectedId) currentOnObjectOpen(id) else currentOnObjectTap(id)
                            }
                            val hitPlanet = projectedPlanets.firstOrNull { (_, off) -> dist(off, pos) < 48f }
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
                                        val sp = sunProj?.second
                                        val mp = moonProj?.second
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

        if (showGrid) drawGrid(projection, viewState)
        drawHorizon(projection, viewState)

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

private const val ART_MESH = 12

/**
 * Draw a constellation figure so it lies on the celestial sphere. The 3 anchors
 * define an affine map from image pixels to 3D sky direction; a subdivided mesh
 * of that map is then run through the real sphere projection, so the artwork
 * curves and tracks with the stars instead of being a flat plane.
 */
private fun DrawScope.drawConstellationArt(
    projection: StereographicProjection,
    bmp: android.graphics.Bitmap,
    art: VisibleArt,
    paint: android.graphics.Paint
) {
    val a0 = art.anchors[0]; val a1 = art.anchors[1]; val a2 = art.anchors[2]
    val v0 = altAzToVec(a0.altAz); val v1 = altAzToVec(a1.altAz); val v2 = altAzToVec(a2.altAz)
    val inv = invert3x3(
        a0.srcX.toDouble(), a0.srcY.toDouble(), 1.0,
        a1.srcX.toDouble(), a1.srcY.toDouble(), 1.0,
        a2.srcX.toDouble(), a2.srcY.toDouble(), 1.0
    ) ?: return
    // Per-component coefficients (a,b,c) with comp(px,py) = a*px + b*py + c.
    fun coef(c0: Double, c1: Double, c2: Double) = doubleArrayOf(
        inv[0]*c0 + inv[1]*c1 + inv[2]*c2,
        inv[3]*c0 + inv[4]*c1 + inv[5]*c2,
        inv[6]*c0 + inv[7]*c1 + inv[8]*c2
    )
    val cx = coef(v0[0], v1[0], v2[0])
    val cy = coef(v0[1], v1[1], v2[1])
    val cz = coef(v0[2], v1[2], v2[2])

    val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
    val n = ART_MESH
    val verts = FloatArray((n + 1) * (n + 1) * 2)
    var k = 0
    for (j in 0..n) {
        val py = bh * j / n
        for (i in 0..n) {
            val px = bw * i / n
            var vx = cx[0]*px + cx[1]*py + cx[2]
            var vy = cy[0]*px + cy[1]*py + cy[2]
            var vz = cz[0]*px + cz[1]*py + cz[2]
            val len = sqrt(vx*vx + vy*vy + vz*vz)
            if (len < 1e-9) return
            vx /= len; vy /= len; vz /= len
            val alt = asin(vz.coerceIn(-1.0, 1.0))
            val az = atan2(vx, vy).let { if (it < 0) it + 2*PI else it }
            val off = projection.projectMesh(AltAz(az, alt)) ?: return
            verts[k++] = off.x; verts[k++] = off.y
        }
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawBitmapMesh(bmp, n, n, verts, 0, null, 0, paint)
    }
}

private fun altAzToVec(a: AltAz): DoubleArray {
    val ca = cos(a.altRad)
    return doubleArrayOf(ca * sin(a.azRad), ca * cos(a.azRad), sin(a.altRad))
}

private fun invert3x3(
    m0: Double, m1: Double, m2: Double,
    m3: Double, m4: Double, m5: Double,
    m6: Double, m7: Double, m8: Double
): DoubleArray? {
    val det = m0*(m4*m8 - m5*m7) - m1*(m3*m8 - m5*m6) + m2*(m3*m7 - m4*m6)
    if (abs(det) < 1e-12) return null
    val id = 1.0 / det
    return doubleArrayOf(
        (m4*m8 - m5*m7)*id, (m2*m7 - m1*m8)*id, (m1*m5 - m2*m4)*id,
        (m5*m6 - m3*m8)*id, (m0*m8 - m2*m6)*id, (m2*m3 - m0*m5)*id,
        (m3*m7 - m4*m6)*id, (m1*m6 - m0*m7)*id, (m0*m4 - m1*m3)*id
    )
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
