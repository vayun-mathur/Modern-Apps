package com.vayunmathur.camera.platform.camera2

import kotlin.math.roundToInt

/**
 * Camera2 migration Phase 2: the viewfinder → sensor coordinate transform for
 * tap metering (`CONTROL_AF_REGIONS` / `CONTROL_AE_REGIONS`). Pure integer /
 * float math — no `Rect`, no `CameraCharacteristics` — so the geometry is
 * unit-tested directly; the driver converts [SensorRect] to
 * `android.graphics.Rect` / `MeteringRectangle` at the HAL boundary.
 *
 * Coordinate spaces:
 * - Viewfinder: normalised 0..1 over the *displayed* (centre-cropped) preview
 *   content. The caller divides the tap by the view size; letterboxing outside
 *   the content never reaches here.
 * - Sensor: pixel coordinates in `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, the space
 *   metering rectangles and `SCALER_CROP_REGION` live in.
 *
 * The transform accounts for: the relative rotation between sensor and
 * display, front-camera mirroring, and the current crop region (digital
 * zoom): a point in the cropped preview maps into the crop window, not the
 * full active array.
 */

/** Axis-aligned rectangle in sensor pixel coordinates (right/bottom exclusive). */
data class SensorRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun clampWithin(bounds: SensorRect): SensorRect {
        val w = minOf(width, bounds.width).coerceAtLeast(1)
        val h = minOf(height, bounds.height).coerceAtLeast(1)
        val l = left.coerceIn(bounds.left, bounds.right - w)
        val t = top.coerceIn(bounds.top, bounds.bottom - h)
        return SensorRect(l, t, l + w, t + h)
    }

    companion object {
        fun ofSize(width: Int, height: Int): SensorRect = SensorRect(0, 0, width, height)
    }
}

/** One metering region: a sensor-space rectangle plus its HAL weight. */
data class MeteringRegion(val rect: SensorRect, val weight: Int = DEFAULT_WEIGHT) {
    companion object {
        /** `MeteringRectangle.METERING_WEIGHT_MAX`-style centre weight. */
        const val DEFAULT_WEIGHT = 1000
    }
}

object MeteringTransform {

    /** Default metering window edge, as a fraction of the crop-region edge. */
    const val DEFAULT_REGION_FRACTION = 0.12f

    /**
     * Centred crop region for [zoomRatio] over the active array — the
     * `SCALER_CROP_REGION` fallback when `CONTROL_ZOOM_RATIO` is unavailable.
     * A ratio ≤ 1 (or a degenerate array) yields the full array.
     */
    fun cropRegionForZoom(activeArray: SensorRect, zoomRatio: Float): SensorRect {
        if (activeArray.width <= 0 || activeArray.height <= 0) return activeArray
        val ratio = zoomRatio.coerceAtLeast(1f)
        if (ratio <= 1f) return activeArray
        val cropW = (activeArray.width / ratio).roundToInt().coerceIn(1, activeArray.width)
        val cropH = (activeArray.height / ratio).roundToInt().coerceIn(1, activeArray.height)
        val left = activeArray.left + (activeArray.width - cropW) / 2
        val top = activeArray.top + (activeArray.height - cropH) / 2
        return SensorRect(left, top, left + cropW, top + cropH)
    }

    /**
     * Rotates a normalised viewfinder point into sensor orientation.
     * [relativeRotationDegrees] is `(sensorOrientation - displayRotation)`
     * normalised to 0/90/180/270: how far sensor space is rotated clockwise
     * from what the user sees. Front lenses mirror the preview, so the
     * x-axis flips before rotation when [mirrored].
     */
    fun rotateViewfinderPoint(
        x: Float,
        y: Float,
        relativeRotationDegrees: Int,
        mirrored: Boolean,
    ): Pair<Float, Float> {
        val mx = if (mirrored) 1f - x else x
        return when (((relativeRotationDegrees % 360) + 360) % 360) {
            90 -> (1f - y) to mx
            180 -> (1f - mx) to (1f - y)
            270 -> y to (1f - mx)
            else -> mx to y
        }
    }

    /**
     * Maps a normalised viewfinder tap to a sensor-space point inside
     * [cropRegion]. Out-of-range taps are clamped to the crop edges; a
     * degenerate crop yields the active-array centre.
     */
    fun viewfinderToSensorPoint(
        tapX: Float,
        tapY: Float,
        cropRegion: SensorRect,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        mirrored: Boolean,
    ): Pair<Float, Float> {
        if (cropRegion.width <= 0 || cropRegion.height <= 0) return 0f to 0f
        val relative = sensorOrientationDegrees - displayRotationDegrees
        val (rx, ry) = rotateViewfinderPoint(
            tapX.coerceIn(0f, 1f), tapY.coerceIn(0f, 1f), relative, mirrored,
        )
        val px = cropRegion.left + rx * cropRegion.width
        val py = cropRegion.top + ry * cropRegion.height
        return px.coerceIn(
            cropRegion.left.toFloat(), (cropRegion.right - 1).toFloat(),
        ) to py.coerceIn(cropRegion.top.toFloat(), (cropRegion.bottom - 1).toFloat())
    }

    /**
     * Full tap → metering region: centres a window of [regionFraction] of the
     * crop edge on the transformed tap point, clamped inside the active array.
     */
    fun meteringRegionForTap(
        tapX: Float,
        tapY: Float,
        activeArray: SensorRect,
        cropRegion: SensorRect,
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        mirrored: Boolean,
        regionFraction: Float = DEFAULT_REGION_FRACTION,
        weight: Int = MeteringRegion.DEFAULT_WEIGHT,
    ): MeteringRegion {
        val (px, py) = viewfinderToSensorPoint(
            tapX, tapY, cropRegion, sensorOrientationDegrees, displayRotationDegrees, mirrored,
        )
        val fraction = regionFraction.coerceIn(0.01f, 1f)
        val regionW = (cropRegion.width * fraction).roundToInt().coerceAtLeast(1)
        val regionH = (cropRegion.height * fraction).roundToInt().coerceAtLeast(1)
        val rect = SensorRect(
            (px - regionW / 2f).roundToInt(),
            (py - regionH / 2f).roundToInt(),
            (px + regionW / 2f).roundToInt(),
            (py + regionH / 2f).roundToInt(),
        ).clampWithin(activeArray)
        return MeteringRegion(rect, weight)
    }
}
