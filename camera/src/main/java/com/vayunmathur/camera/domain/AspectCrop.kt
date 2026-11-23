package com.vayunmathur.camera.domain

/**
 * Pure centre-crop geometry for the still aspect-ratio setting.
 *
 * The user-facing ratio ([com.vayunmathur.camera.platform.AspectRatioOption])
 * is expressed in display orientation (portrait on this portrait-locked
 * app: 3:4, 9:16, ...), while captured still pixels arrive in sensor
 * orientation with a quarter-turn relative rotation on phone sensors.
 * A centre crop is symmetric, so the sensor-space crop rectangle does
 * not depend on the rotation *direction*: its width follows the display
 * height axis when the axes are swapped (90/270) and the display width
 * axis otherwise. Cropping anywhere else (or not at all, as the first
 * Camera2 save path did — `currentCropAspectRatio` had no caller) makes
 * the saved still wider than the viewfinder box the user framed.
 */
object AspectCrop {

    /** A pixel rectangle inside a frame. */
    data class Rect(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height

        fun isFullFrame(frameWidth: Int, frameHeight: Int): Boolean =
            left == 0 && top == 0 && width == frameWidth && height == frameHeight
    }

    /**
     * Largest centred rectangle inside a [frameWidth]x[frameHeight]
     * sensor frame whose *displayed* aspect is [displayRatioWidth] /
     * [displayRatioHeight] after a [rotationDegrees] quarter turn, or
     * null for degenerate input. When the frame already displays at the
     * target ratio the rectangle is the full frame.
     */
    fun centerCropRect(
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        displayRatioWidth: Int,
        displayRatioHeight: Int,
    ): Rect? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        if (displayRatioWidth <= 0 || displayRatioHeight <= 0) return null
        val swapped = ((rotationDegrees % 360) + 360) % 360 == 90 ||
            ((rotationDegrees % 360) + 360) % 360 == 270
        val displayWidth = if (swapped) frameHeight else frameWidth
        val displayHeight = if (swapped) frameWidth else frameHeight
        // Largest display-space crop at the target ratio.
        val target = displayRatioWidth.toFloat() / displayRatioHeight.toFloat()
        val displayAspect = displayWidth.toFloat() / displayHeight.toFloat()
        val cropDisplayWidth: Int
        val cropDisplayHeight: Int
        if (displayAspect > target) {
            cropDisplayHeight = displayHeight
            cropDisplayWidth = (displayHeight * target).toInt().coerceAtLeast(1)
        } else {
            cropDisplayWidth = displayWidth
            cropDisplayHeight = (displayWidth / target).toInt().coerceAtLeast(1)
        }
        // Map back to sensor axes (centre crop: direction-independent).
        val cropWidth = if (swapped) cropDisplayHeight else cropDisplayWidth
        val cropHeight = if (swapped) cropDisplayWidth else cropDisplayHeight
        return Rect(
            left = (frameWidth - cropWidth) / 2,
            top = (frameHeight - cropHeight) / 2,
            width = cropWidth.coerceAtMost(frameWidth),
            height = cropHeight.coerceAtMost(frameHeight),
        )
    }
}
