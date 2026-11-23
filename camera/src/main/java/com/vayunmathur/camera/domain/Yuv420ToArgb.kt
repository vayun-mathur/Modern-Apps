package com.vayunmathur.camera.domain

import kotlin.math.roundToInt

/**
 * Pure YUV_420_888 -> ARGB conversion: the sensor-oriented pixel math a
 * Camera2 analysis frame needs to feed a `Bitmap` consumer
 * (`SelfieSegmenter.segment(Bitmap)`, `PanoramaEngine.offerFrame(Bitmap)`,
 * Motion-Photo frame copies), which CameraX frames get from
 * `ImageProxy.toBitmap()`.
 *
 * Semantics, deliberately identical to that path's contract:
 * - BT.601 limited range (Y 16-235, chroma centred on 128), the same
 *   colour science `YuvImage`/CameraX apply to these planes.
 * - Strides honoured exactly: every sample goes through
 *   `rowStride`/`pixelStride`, so HAL row padding and semi-planar
 *   (pixelStride 2) chroma are never mistaken for pixels.
 * - Sensor orientation preserved: the output is the unrotated sensor
 *   frame, exactly as `toBitmap()` hands it to the consumers above.
 *   Rotation is carried separately by the caller (as the CameraX paths
 *   carry `imageInfo.rotationDegrees`); this conversion neither knows
 *   nor invents it, and it applies no crop.
 *
 * Returns null when the planes cannot describe a full frame (fewer than
 * three planes, non-positive size, or a plane too short for its strides):
 * a partial bitmap would silently corrupt a mask/stitch, so the caller
 * drops the frame instead.
 */
object Yuv420ToArgb {

    /** Converts [planes] (Y, U, V in format order) to row-major ARGB ints. */
    fun convert(planes: List<PlaneView>, width: Int, height: Int): IntArray? {
        if (planes.size < 3 || width <= 0 || height <= 0) return null
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        if (!readable(yPlane, width, height)) return null
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        if (!readable(uPlane, chromaWidth, chromaHeight)) return null
        if (!readable(vPlane, chromaWidth, chromaHeight)) return null

        val out = IntArray(width * height)
        var offset = 0
        for (row in 0 until height) {
            val chromaRow = row / 2
            for (col in 0 until width) {
                val y = sample(yPlane, col, row)
                val u = sample(uPlane, col / 2, chromaRow)
                val v = sample(vPlane, col / 2, chromaRow)
                out[offset++] = argb(y, u, v)
            }
        }
        return out
    }

    private fun sample(plane: PlaneView, x: Int, y: Int): Int {
        val buffer = plane.buffer.duplicate()
        return buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
    }

    private fun readable(plane: PlaneView, width: Int, height: Int): Boolean {
        if (plane.rowStride <= 0 || plane.pixelStride <= 0) return false
        val last = (height - 1).toLong() * plane.rowStride +
            (width - 1).toLong() * plane.pixelStride
        return last < plane.buffer.limit()
    }

    private fun argb(y: Int, u: Int, v: Int): Int {
        val yScaled = 1.164f * (y - 16)
        val uCentred = u - 128
        val vCentred = v - 128
        val r = (yScaled + 1.596f * vCentred).roundToInt().coerceIn(0, 255)
        val g = (yScaled - 0.813f * vCentred - 0.391f * uCentred).roundToInt().coerceIn(0, 255)
        val b = (yScaled + 2.018f * uCentred).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
