package com.vayunmathur.pdf.platform

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.vayunmathur.pdf.data.Quadrilateral
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Singleton holding auto frame detector state and operations. */
object AutoFrameDetector {

    private const val MAX_DIM = 500
    internal const val MIN_AREA_FRACTION = 0.05
    private const val MIN_SCALED_DIM = 10
    private const val CANNY_HIGH_1 = 75
    private const val CANNY_LOW_1 = 200
    private const val CANNY_HIGH_2 = 50
    private const val CANNY_LOW_2 = 150
    private const val CANNY_HIGH_3 = 30
    private const val CANNY_LOW_3 = 100
    private const val SOBEL_WEIGHT_CENTER = 2
    private const val MAX_GRADIENT = 255
    private const val ANGLE_BINS = 4
    internal const val QUAD_CORNERS = 4
    internal const val APPROX_EPSILON_FRACTION = 0.02
    internal const val AREA_WEIGHT = 0.6
    internal const val ANGLE_WEIGHT = 0.4
    internal const val MIN_EDGE_LENGTH = 1e-6
    internal const val ADAPTIVE_RADIUS = 5
    internal const val ADAPTIVE_C = 2
    internal const val GAUSSIAN_SUM = 159
    internal const val GAUSSIAN_HALF = 2
    internal const val LUMA_RED = 77
    internal const val LUMA_GREEN = 150
    internal const val LUMA_BLUE = 29
    internal const val LUMA_SHIFT = 8
    internal const val CHANNEL_MASK = 0xFF
    internal const val MAX_CONTOUR_SAMPLES = 1000
    internal const val NEIGHBOR_COUNT = 8
    internal const val BACKTRACK_OFFSET = 6
    internal const val RED_SHIFT = 16
    internal const val GREEN_SHIFT = 8
    internal const val MIN_CONTOUR_POINTS = 3
    internal const val MIN_POLYGON_POINTS = 3

    fun detect(bitmap: Bitmap): Quadrilateral? {
        val maxDim = max(bitmap.width, bitmap.height)
        val scale = if (maxDim > MAX_DIM) MAX_DIM.toFloat() / maxDim else 1f
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(MIN_SCALED_DIM)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(MIN_SCALED_DIM)
        val scaled = if (scale < 1f) bitmap.scale(w, h) else bitmap

        return try {
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val gray = grayscale(pixels)
            val blurred = gaussianBlur(gray, w, h)

            detectFromCanny(blurred, w, h, CANNY_HIGH_1, CANNY_LOW_1)
                ?: detectFromCanny(blurred, w, h, CANNY_HIGH_2, CANNY_LOW_2)
                ?: detectFromCanny(blurred, w, h, CANNY_HIGH_3, CANNY_LOW_3)
                ?: detectFromAdaptiveThreshold(blurred, w, h)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun detectFromCanny(gray: IntArray, w: Int, h: Int, t1: Int, t2: Int): Quadrilateral? {
        val edges = canny(gray, w, h, t1, t2)
        dilate(edges, w, h)
        return findBestQuad(edges, w, h)
    }

    private fun detectFromAdaptiveThreshold(gray: IntArray, w: Int, h: Int): Quadrilateral? {
        val binary = adaptiveThreshold(gray, w, h)
        morphClose(binary, w, h)
        return findBestQuad(binary, w, h)
    }

    // --- Canny edge detection ---

    private class Gradients(val gx: IntArray, val gy: IntArray, val mag: IntArray)

    private fun canny(gray: IntArray, w: Int, h: Int, lowThresh: Int, highThresh: Int): BooleanArray {
        val g = sobelGradients(gray, w, h)
        val nms = nonMaxSuppression(g, w, h)
        return hysteresis(nms, w, h, lowThresh, highThresh)
    }

    private fun sobelGradients(gray: IntArray, w: Int, h: Int): Gradients {
        val gx = IntArray(w * h)
        val gy = IntArray(w * h)
        val mag = IntArray(w * h)
        // Sobel gradients
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val sx = -gray[i - w - 1] - SOBEL_WEIGHT_CENTER * gray[i - 1] - gray[i + w - 1] +
                    gray[i - w + 1] + SOBEL_WEIGHT_CENTER * gray[i + 1] + gray[i + w + 1]
                val sy = -gray[i - w - 1] - SOBEL_WEIGHT_CENTER * gray[i - w] - gray[i - w + 1] +
                    gray[i + w - 1] + SOBEL_WEIGHT_CENTER * gray[i + w] + gray[i + w + 1]
                gx[i] = sx
                gy[i] = sy
                mag[i] = min(MAX_GRADIENT, hypot(sx.toFloat(), sy.toFloat()).roundToInt())
            }
        }
        return Gradients(gx, gy, mag)
    }

    private fun nonMaxSuppression(g: Gradients, w: Int, h: Int): IntArray {
        // Non-maximum suppression
        val nms = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val m = g.mag[i]
                if (m == 0) continue
                val angle = quantizeAngle(g.gx[i], g.gy[i])
                val (n1, n2) = suppressionNeighbors(g.mag, i, w, angle)
                nms[i] = if (m >= n1 && m >= n2) m else 0
            }
        }
        return nms
    }

    private fun quantizeAngle(gx: Int, gy: Int): Int {
        return ((atan2(gy.toFloat(), gx.toFloat()) * ANGLE_BINS / Math.PI).roundToInt() + ANGLE_BINS) % ANGLE_BINS
    }

    private fun suppressionNeighbors(mag: IntArray, i: Int, w: Int, angle: Int): Pair<Int, Int> {
        return when (angle) {
            0 -> mag[i - 1] to mag[i + 1] // horizontal edge → compare left/right
            1 -> mag[i - w + 1] to mag[i + w - 1] // 45°
            2 -> mag[i - w] to mag[i + w] // vertical edge → compare above/below
            else -> mag[i - w - 1] to mag[i + w + 1] // 135°
        }
    }

    private fun hysteresis(nms: IntArray, w: Int, h: Int, lowThresh: Int, highThresh: Int): BooleanArray {
        // Hysteresis thresholding
        val result = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        for (i in nms.indices) {
            if (nms[i] < highThresh) continue
            result[i] = true
            queue.add(i)
        }
        while (queue.isNotEmpty()) {
            spreadWeakEdge(queue.removeFirst(), result, nms, w, h, lowThresh, queue)
        }
        return result
    }

    private fun spreadWeakEdge(
        i: Int,
        result: BooleanArray,
        nms: IntArray,
        w: Int,
        h: Int,
        lowThresh: Int,
        queue: ArrayDeque<Int>,
    ) {
        val x = i % w
        val y = i / w
        visitNeighbors(x, y, result, nms, w, h, lowThresh, queue)
    }

    private fun visitNeighbors(
        x: Int,
        y: Int,
        result: BooleanArray,
        nms: IntArray,
        w: Int,
        h: Int,
        lowThresh: Int,
        queue: ArrayDeque<Int>,
    ) {
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                trySpread(x + dx, y + dy, result, nms, w, h, lowThresh, queue)
            }
        }
    }

    private fun trySpread(
        nx: Int,
        ny: Int,
        result: BooleanArray,
        nms: IntArray,
        w: Int,
        h: Int,
        lowThresh: Int,
        queue: ArrayDeque<Int>,
    ) {
        if (!inBounds(nx, ny, w, h)) return
        val ni = ny * w + nx
        if (!result[ni] && nms[ni] >= lowThresh) {
            result[ni] = true
            queue.add(ni)
        }
    }

    private fun inBounds(nx: Int, ny: Int, w: Int, h: Int): Boolean {
        if (nx < 0 || nx >= w) return false
        return ny in 0 until h
    }
}

