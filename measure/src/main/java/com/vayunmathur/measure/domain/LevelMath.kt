package com.vayunmathur.measure.domain

import com.vayunmathur.measure.domain.HeldOrientation
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure gravity-to-tilt maths, split out from the sensor plumbing so every device
 * orientation can be unit tested without physically turning a phone.
 */
object LevelMath {

    /** |z| this close to 1 g means the screen faces up or down. */
    const val FLAT_GRAVITY_THRESHOLD = 8.0

    /** Level reading derived from gravity: pitch, roll, edge angle, quadrant, flatness, and held orientation. */
    data class Reading(
        val pitchDeg: Double,
        val rollDeg: Double,
        val edgeAngleDeg: Double,
        val quadrant: Int,
        val isFlat: Boolean,
        val orientation: HeldOrientation,
    )

    /**
     * @param x gravity along the device X axis (positive toward the right edge)
     * @param y gravity along the device Y axis (positive toward the top edge)
     * @param z gravity along the device Z axis (positive out of the screen)
     */
    fun fromGravity(x: Double, y: Double, z: Double): Reading {
        val pitch = Math.toDegrees(atan2(y, sqrt(x * x + z * z)))
        val roll = Math.toDegrees(atan2(-x, sqrt(y * y + z * z)))

        // In-plane gravity angle: 0 upright, 90 on the left side, 180 upside down.
        // Uses the gravity vector alone and never the display rotation, which is what
        // makes the reading independent of whether auto-rotate is enabled.
        val inPlane = Math.toDegrees(atan2(x, -y))
        val quadrant = (inPlane / 90.0).roundToInt()
        val edge = inPlane - quadrant * 90.0

        val flat = abs(z) > FLAT_GRAVITY_THRESHOLD
        val orientation = if (flat) {
            HeldOrientation.Flat
        } else {
            when (((quadrant % 4) + 4) % 4) {
                1 -> HeldOrientation.LandscapeLeft
                2 -> HeldOrientation.PortraitUpsideDown
                3 -> HeldOrientation.LandscapeRight
                else -> HeldOrientation.Portrait
            }
        }

        return Reading(pitch, roll, edge, quadrant, flat, orientation)
    }

    /**
     * How far to rotate the edge bubble so it reads horizontally in the real world.
     *
     * With auto-rotate on, the activity has already turned with the device and no
     * correction is needed. With it off, the activity stays put while the device turns,
     * and the difference is exactly what has to be undone.
     */
    fun uiRotation(quadrant: Int, displayRotationDeg: Int): Float {
        val deviceDeg = ((quadrant % 4) + 4) % 4 * 90
        return (((deviceDeg - displayRotationDeg) % 360 + 360) % 360).toFloat()
    }
}
