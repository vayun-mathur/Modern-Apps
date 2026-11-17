package com.vayunmathur.measure.domain

/** Device tilt for the level UI: bubble pitch/roll, edge deviation, flatness, orientation, and bubble rotation. */
data class Tilt(
    /** Front-to-back tilt for the flat 2D bubble, in degrees. */
    val pitchDeg: Double,
    /** Side-to-side tilt for the flat 2D bubble, in degrees. */
    val rollDeg: Double,
    /**
     * Deviation of the down-facing edge from level, in degrees.
     *
     * Measured against the nearest quarter turn, so it reads correctly whether the
     * phone is held upright, upside down, or on either side.
     */
    val edgeAngleDeg: Double,
    /** True when the device is lying close to flat, where the 2D bubble is the useful view. */
    val isFlat: Boolean,
    val orientation: HeldOrientation,
    /**
     * Rotation to apply to the edge bubble so it stays horizontal in the real world.
     *
     * Compensates for the gap between how the device is physically held and how the
     * activity happens to be rotated, which is why the level does not depend on
     * auto-rotate being enabled.
     */
    val uiRotationDeg: Float,
)
