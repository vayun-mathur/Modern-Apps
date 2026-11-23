package com.vayunmathur.camera.domain

/**
 * Pure sensor-to-display rotation for analysis frames: the Camera2
 * equivalent of CameraX's `ImageProxy.imageInfo.rotationDegrees`
 * (clockwise degrees the sensor-oriented frame must be rotated to sit
 * upright on the display).
 *
 * Formula (the documented `JPEG_ORIENTATION` relative rotation):
 * - BACK lens: `(sensorOrientation - displayRotation + 360) % 360`
 * - FRONT lens: `(sensorOrientation + displayRotation) % 360`
 *   (the front lens sees the world mirrored, so the display rotation
 *   adds instead of subtracting; mirroring itself is a separate flag
 *   consumers already handle, exactly as on the CameraX path).
 *
 * Known-device table (asserted in `AnalysisRotationTest`):
 * - back sensor 90, portrait display 0 -> 90 (the usual phone back lens)
 * - back sensor 90, display 90 -> 0; display 270 -> 180
 * - front sensor 270, portrait display 0 -> 270 (the usual front lens)
 * - front sensor 270, display 90 -> 0; display 270 -> 180
 */
object AnalysisRotation {

    fun rotationDegrees(
        sensorOrientationDegrees: Int,
        displayRotationDegrees: Int,
        facing: LensFacing?,
    ): Int {
        val sensor = normalize(sensorOrientationDegrees)
        val display = normalize(displayRotationDegrees)
        return when (facing) {
            LensFacing.FRONT -> (sensor + display) % 360
            LensFacing.BACK, null -> (sensor - display + 360) % 360
        }
    }

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
}

/**
 * A frame crop rectangle in sensor-frame pixel coordinates (the pure
 * equivalent of `ImageProxy.cropRect`). The Camera2 analysis stream
 * applies no scaler crop of its own, so frames carry the full-frame
 * crop; consumers that centre-crop to it are no-ops, exactly as with a
 * full-frame CameraX cropRect.
 */
data class FrameCrop(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun isFullFrame(frameWidth: Int, frameHeight: Int): Boolean =
        left == 0 && top == 0 && right == frameWidth && bottom == frameHeight

    companion object {
        fun fullFrame(frameWidth: Int, frameHeight: Int): FrameCrop =
            FrameCrop(0, 0, frameWidth, frameHeight)
    }
}
