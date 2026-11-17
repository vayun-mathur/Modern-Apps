package com.vayunmathur.pdf.platform

import kotlin.math.max
import kotlin.math.min

/**
 * Grayscale conversion, blur, adaptive threshold and morphology for
 * [AutoFrameDetector].
 *
 * Extracted so the detector object stays under detekt's TooManyFunctions cap.
 * All functions are internal top-level: same module, no API change.
 */

internal fun grayscale(pixels: IntArray): IntArray =
    IntArray(pixels.size) { i ->
        val p = pixels[i]
        val red = p shr AutoFrameDetector.RED_SHIFT and AutoFrameDetector.CHANNEL_MASK
        val green = p shr AutoFrameDetector.GREEN_SHIFT and AutoFrameDetector.CHANNEL_MASK
        val blue = p and AutoFrameDetector.CHANNEL_MASK
        (
            red * AutoFrameDetector.LUMA_RED +
                green * AutoFrameDetector.LUMA_GREEN +
                blue * AutoFrameDetector.LUMA_BLUE
            ) shr AutoFrameDetector.LUMA_SHIFT
    }

internal fun gaussianBlur(src: IntArray, w: Int, h: Int): IntArray {
    val kernel = intArrayOf(
        2, 4, 5, 4, 2,
        4, 9, 12, 9, 4,
        5, 12, 15, 12, 5,
        4, 9, 12, 9, 4,
        2, 4, 5, 4, 2
    )
    val dst = IntArray(w * h)
    val half = AutoFrameDetector.GAUSSIAN_HALF
    for (y in half until h - half) {
        for (x in half until w - half) {
            dst[y * w + x] = convolve(src, w, x, y, kernel)
        }
    }
    return dst
}

private fun convolve(src: IntArray, w: Int, x: Int, y: Int, kernel: IntArray): Int {
    var sum = 0
    var ki = 0
    val half = AutoFrameDetector.GAUSSIAN_HALF
    for (ky in -half..half) {
        for (kx in -half..half) {
            sum += src[(y + ky) * w + (x + kx)] * kernel[ki++]
        }
    }
    return sum / AutoFrameDetector.GAUSSIAN_SUM
}

internal fun adaptiveThreshold(gray: IntArray, w: Int, h: Int): BooleanArray {
    // Integral image for fast box mean
    val integral = LongArray((w + 1) * (h + 1))
    for (y in 0 until h) {
        for (x in 0 until w) {
            val iw = w + 1
            integral[(y + 1) * iw + (x + 1)] = gray[y * w + x].toLong() +
                integral[y * iw + (x + 1)] + integral[(y + 1) * iw + x] - integral[y * iw + x]
        }
    }
    return thresholdFromIntegral(integral, gray, w, h)
}

private fun thresholdFromIntegral(
    integral: LongArray,
    gray: IntArray,
    w: Int,
    h: Int,
): BooleanArray {
    val result = BooleanArray(w * h)
    val iw = w + 1
    for (y in 0 until h) {
        for (x in 0 until w) {
            val window = clampedWindow(x, y, w, h)
            val count = (window.x2 - window.x1 + 1) * (window.y2 - window.y1 + 1)
            val sum = windowSum(integral, iw, window)
            val mean = sum / count
            result[y * w + x] = gray[y * w + x] < mean - AutoFrameDetector.ADAPTIVE_C
        }
    }
    return result
}

private data class ThresholdWindow(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

private fun clampedWindow(x: Int, y: Int, w: Int, h: Int): ThresholdWindow {
    val radius = AutoFrameDetector.ADAPTIVE_RADIUS
    return ThresholdWindow(
        x1 = max(0, x - radius),
        y1 = max(0, y - radius),
        x2 = min(w - 1, x + radius),
        y2 = min(h - 1, y + radius),
    )
}

private fun windowSum(integral: LongArray, iw: Int, window: ThresholdWindow): Long {
    return integral[(window.y2 + 1) * iw + (window.x2 + 1)] -
        integral[window.y1 * iw + (window.x2 + 1)] -
        integral[(window.y2 + 1) * iw + window.x1] +
        integral[window.y1 * iw + window.x1]
}

internal fun dilate(edges: BooleanArray, w: Int, h: Int) {
    val copy = edges.copyOf()
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            if (hasForegroundNeighbor(copy, x, y, w)) edges[y * w + x] = true
        }
    }
}

private fun hasForegroundNeighbor(copy: BooleanArray, x: Int, y: Int, w: Int): Boolean {
    if (copy[y * w + x]) return true
    if (copy[(y - 1) * w + x]) return true
    if (copy[(y + 1) * w + x]) return true
    if (copy[y * w + x - 1]) return true
    return copy[y * w + x + 1]
}

internal fun morphClose(binary: BooleanArray, w: Int, h: Int) {
    dilate(binary, w, h)
    dilate(binary, w, h)
    erode(binary, w, h)
    erode(binary, w, h)
}

private fun erode(edges: BooleanArray, w: Int, h: Int) {
    val copy = edges.copyOf()
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            if (!hasFullNeighborhood(copy, x, y, w)) edges[y * w + x] = false
        }
    }
}

private fun hasFullNeighborhood(copy: BooleanArray, x: Int, y: Int, w: Int): Boolean {
    if (!copy[y * w + x]) return false
    if (!copy[(y - 1) * w + x]) return false
    if (!copy[(y + 1) * w + x]) return false
    if (!copy[y * w + x - 1]) return false
    return copy[y * w + x + 1]
}
