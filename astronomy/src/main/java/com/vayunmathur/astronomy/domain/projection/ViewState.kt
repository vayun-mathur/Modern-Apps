package com.vayunmathur.astronomy.domain.projection

/** The sky view's center direction, field of view, screen size, and rotation. */
data class ViewState(
    val centerAzRad: Double,
    val centerAltRad: Double,
    val fovDeg: Float, // Float to align with slider/gesture Float domain
    val screenW: Float,
    val screenH: Float,
    val rotationRad: Double = 0.0
) {
    val fovRad get() = Math.toRadians(fovDeg.toDouble())
    val fovDegDouble get() = fovDeg.toDouble()
}
