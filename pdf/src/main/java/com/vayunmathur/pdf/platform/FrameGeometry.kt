package com.vayunmathur.pdf.platform

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.pdf.data.Quadrilateral
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Contour tracing, polygon approximation and quad geometry for
 * [AutoFrameDetector].
 *
 * Extracted so the detector object stays under detekt's TooManyFunctions cap.
 * All functions are internal top-level: same module, no API change.
 */

// --- Contour tracing (Suzuki-Abe / Moore boundary tracing) ---

internal fun findBestQuad(binary: BooleanArray, w: Int, h: Int): Quadrilateral? {
    val contours = traceContours(binary, w, h)
    val minArea = w * h * AutoFrameDetector.MIN_AREA_FRACTION

    var best: Quadrilateral? = null
    var bestScore = 0.0

    for (contour in contours) {
        val candidate = quadCandidate(contour, minArea, w, h)
        if (candidate != null && candidate.score > bestScore) {
            bestScore = candidate.score
            best = candidate.quad
        }
    }
    return best
}

private class QuadCandidate(val quad: Quadrilateral, val score: Double)

private fun quadCandidate(
    contour: List<Pair<Int, Int>>,
    minArea: Double,
    w: Int,
    h: Int,
): QuadCandidate? {
    val area = contourArea(contour)
    if (area < minArea) return null

    val peri = contourPerimeter(contour)
    val approx = approxPolyDP(contour, AutoFrameDetector.APPROX_EPSILON_FRACTION * peri)
    if (approx.size != AutoFrameDetector.QUAD_CORNERS) return null
    if (!isConvex(approx)) return null

    val score = scoreCandidate(approx, area, w, h)
    val corners = sortCorners(approx)
    return QuadCandidate(normalizedQuad(corners, w, h), score)
}

private fun normalizedQuad(
    corners: List<Pair<Int, Int>>,
    w: Int,
    h: Int,
): Quadrilateral {
    return Quadrilateral(
        topLeft = normalizedCorner(corners[TOP_LEFT], w, h),
        topRight = normalizedCorner(corners[TOP_RIGHT], w, h),
        bottomRight = normalizedCorner(corners[BOTTOM_RIGHT], w, h),
        bottomLeft = normalizedCorner(corners[BOTTOM_LEFT], w, h)
    )
}

private const val TOP_LEFT = 0
private const val TOP_RIGHT = 1
private const val BOTTOM_RIGHT = 2
private const val BOTTOM_LEFT = 3

private fun normalizedCorner(corner: Pair<Int, Int>, w: Int, h: Int): Offset =
    Offset(corner.first.toFloat() / w, corner.second.toFloat() / h)

internal fun traceContours(binary: BooleanArray, w: Int, h: Int): List<List<Pair<Int, Int>>> {
    val visited = BooleanArray(w * h)
    val contours = mutableListOf<List<Pair<Int, Int>>>()
    // Moore neighbor offsets (clockwise from right)
    val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    val tracer = ContourTracer(binary, w, h, visited, dx, dy)

    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            tracer.traceFrom(x, y)?.let { contours.add(it) }
        }
    }

    return contours.sortedByDescending { contourArea(it) }
}

internal class ContourTracer(
    private val binary: BooleanArray,
    private val w: Int,
    private val h: Int,
    private val visited: BooleanArray,
    private val dx: IntArray,
    private val dy: IntArray,
) {
    fun traceFrom(x: Int, y: Int): List<Pair<Int, Int>>? {
        val i = y * w + x
        if (!binary[i] || visited[i]) return null
        // Only start from outer boundary pixels (pixel to the left is background)
        if (x > 0 && binary[i - 1]) return null

        val contour = walkBoundary(x, y)
        // Subsample long contours to keep things fast
        val step = max(1, contour.size / AutoFrameDetector.MAX_CONTOUR_SAMPLES)
        val sampled = if (step > 1) {
            contour.filterIndexed { idx, _ -> idx % step == 0 }
        } else {
            contour
        }
        if (sampled.size < AutoFrameDetector.QUAD_CORNERS) return null
        return sampled
    }

    private fun walkBoundary(x: Int, y: Int): List<Pair<Int, Int>> {
        val contour = mutableListOf<Pair<Int, Int>>()
        var cx = x
        var cy = y
        var dir = 0 // start looking right

        do {
            contour.add(cx to cy)
            visited[cy * w + cx] = true
            val next = stepToNext(cx, cy, dir) ?: break
            cx = next.first
            cy = next.second
            dir = next.third
        } while (cx != x || cy != y)
        return contour
    }

    private fun stepToNext(cx: Int, cy: Int, dir: Int): Triple<Int, Int, Int>? {
        // Search neighbors starting from (dir + 6) % 8 (backtrack direction + 1)
        val startDir = (dir + AutoFrameDetector.BACKTRACK_OFFSET) % AutoFrameDetector.NEIGHBOR_COUNT
        for (k in 0 until AutoFrameDetector.NEIGHBOR_COUNT) {
            val d = (startDir + k) % AutoFrameDetector.NEIGHBOR_COUNT
            val nx = cx + dx[d]
            val ny = cy + dy[d]
            if (isForeground(nx, ny)) return Triple(nx, ny, d)
        }
        return null
    }

    private fun isForeground(nx: Int, ny: Int): Boolean {
        if (!inXRange(nx)) return false
        if (!inYRange(ny)) return false
        return binary[ny * w + nx]
    }

    private fun inXRange(nx: Int): Boolean = nx in 0 until w

    private fun inYRange(ny: Int): Boolean = ny in 0 until h
}

// --- Ramer-Douglas-Peucker polygon approximation ---

private fun approxPolyDP(points: List<Pair<Int, Int>>, epsilon: Double): List<Pair<Int, Int>> {
    if (points.size < AutoFrameDetector.MIN_POLYGON_POINTS) return points
    // For closed contours, find the two farthest points to split
    var maxDist = 0.0
    var splitA = 0
    var splitB = 0
    for (i in points.indices) {
        for (j in i + 1..min(i + points.size / 2, points.lastIndex)) {
            val d = dist(points[i], points[j])
            if (d > maxDist) {
                maxDist = d
                splitA = i
                splitB = j
            }
        }
    }
    val part1 = points.subList(splitA, splitB + 1)
    val part2 = points.subList(splitB, points.size) + points.subList(0, splitA + 1)
    val simplified1 = rdp(part1, epsilon)
    val simplified2 = rdp(part2, epsilon)
    // Merge, removing duplicate junction points
    val result = mutableListOf<Pair<Int, Int>>()
    result.addAll(simplified1)
    if (simplified2.size > 2) result.addAll(simplified2.subList(1, simplified2.size - 1))
    return result
}

private fun rdp(points: List<Pair<Int, Int>>, epsilon: Double): List<Pair<Int, Int>> {
    if (points.size <= 2) return points
    var maxDist = 0.0
    var maxIdx = 0
    val first = points.first()
    val last = points.last()
    for (i in 1 until points.size - 1) {
        val d = pointToLineDist(points[i], first, last)
        if (d > maxDist) {
            maxDist = d
            maxIdx = i
        }
    }
    if (maxDist > epsilon) {
        val left = rdp(points.subList(0, maxIdx + 1), epsilon)
        val right = rdp(points.subList(maxIdx, points.size), epsilon)
        return left + right.subList(1, right.size)
    }
    return listOf(first, last)
}

private fun pointToLineDist(p: Pair<Int, Int>, a: Pair<Int, Int>, b: Pair<Int, Int>): Double {
    val dx = b.first - a.first
    val dy = b.second - a.second
    val lenSq = dx.toLong() * dx + dy.toLong() * dy
    if (lenSq == 0L) return dist(p, a)
    val num = abs(
        dy.toLong() * p.first - dx.toLong() * p.second +
            b.first.toLong() * a.second - b.second.toLong() * a.first
    )
    return num.toDouble() / sqrt(lenSq.toDouble())
}

private fun dist(a: Pair<Int, Int>, b: Pair<Int, Int>): Double =
    hypot((a.first - b.first).toDouble(), (a.second - b.second).toDouble())

// --- Geometry utilities ---

private fun contourArea(points: List<Pair<Int, Int>>): Double {
    var area = 0L
    val n = points.size
    for (i in 0 until n) {
        val j = (i + 1) % n
        area += points[i].first.toLong() * points[j].second - points[j].first.toLong() * points[i].second
    }
    return abs(area) / 2.0
}

private fun contourPerimeter(points: List<Pair<Int, Int>>): Double {
    var peri = 0.0
    for (i in points.indices) peri += dist(points[i], points[(i + 1) % points.size])
    return peri
}

private fun isConvex(points: List<Pair<Int, Int>>): Boolean {
    val n = points.size
    if (n < AutoFrameDetector.MIN_CONTOUR_POINTS) return false
    var sign = 0
    for (i in 0 until n) {
        val o = points[i]
        val a = points[(i + 1) % n]
        val b = points[(i + 2) % n]
        val cross = (a.first - o.first).toLong() * (b.second - a.second) -
            (a.second - o.second).toLong() * (b.first - a.first)
        if (cross != 0L) {
            val s = if (cross > 0) 1 else -1
            if (sign == 0) {
                sign = s
            } else if (s != sign) {
                return false
            }
        }
    }
    return true
}

private fun scoreCandidate(points: List<Pair<Int, Int>>, area: Double, w: Int, h: Int): Double {
    val corners = AutoFrameDetector.QUAD_CORNERS
    val imageArea = w.toDouble() * h
    val areaScore = area / imageArea
    var angleScore = 1.0
    for (i in points.indices) {
        val prev = points[(i + corners - 1) % corners]
        val curr = points[i]
        val next = points[(i + 1) % corners]
        val v1x = (prev.first - curr.first).toDouble()
        val v1y = (prev.second - curr.second).toDouble()
        val v2x = (next.first - curr.first).toDouble()
        val v2y = (next.second - curr.second).toDouble()
        val len1 = hypot(v1x, v1y)
        val len2 = hypot(v2x, v2y)
        if (len1 < AutoFrameDetector.MIN_EDGE_LENGTH || len2 < AutoFrameDetector.MIN_EDGE_LENGTH) continue
        val cosAngle = (v1x * v2x + v1y * v2y) / (len1 * len2)
        angleScore *= (1.0 - abs(cosAngle))
    }
    return AutoFrameDetector.AREA_WEIGHT * areaScore + AutoFrameDetector.ANGLE_WEIGHT * angleScore
}

// --- Corner sorting ---

private fun sortCorners(corners: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    val bySum = corners.sortedBy { it.first + it.second }
    val tl = bySum.first()
    val br = bySum.last()
    val remaining = corners.toMutableList().apply {
        remove(tl)
        remove(br)
    }
    val byDiff = remaining.sortedBy { it.first - it.second }
    return listOf(tl, byDiff.last(), br, byDiff.first())
}
