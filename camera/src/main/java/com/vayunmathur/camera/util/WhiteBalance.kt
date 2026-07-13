package com.vayunmathur.camera.util

import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow

/**
 * Approximate color-temperature → white-balance gains for the manual Kelvin slider.
 *
 * Estimates the RGB color of a black-body emitter at the given temperature (Tanner Helland's
 * widely-used approximation), then derives per-channel gains that neutralize that cast
 * (gain = 1/channel), normalized so the smallest gain is 1.0. The result is packed into an
 * [RggbChannelVector] for `CaptureRequest.COLOR_CORRECTION_GAINS`.
 */
object WhiteBalance {

    const val MIN_KELVIN = 2000
    const val MAX_KELVIN = 8000

    fun kelvinToRggbGains(kelvin: Int): RggbChannelVector {
        val temp = kelvin.coerceIn(1000, 40000) / 100.0

        val red = if (temp <= 66.0) {
            255.0
        } else {
            329.698727446 * (temp - 60.0).pow(-0.1332047592)
        }.coerceIn(1.0, 255.0)

        val green = if (temp <= 66.0) {
            99.4708025861 * ln(temp) - 161.1195681661
        } else {
            288.1221695283 * (temp - 60.0).pow(-0.0755148492)
        }.coerceIn(1.0, 255.0)

        val blue = when {
            temp >= 66.0 -> 255.0
            temp <= 19.0 -> 1.0
            else -> 138.5177312231 * ln(temp - 10.0) - 305.0447927307
        }.coerceIn(1.0, 255.0)

        // Neutralizing gains are the inverse of the light color, normalized so the smallest is 1.0
        // (Camera2 requires gains >= 1.0).
        var gainR = 255.0 / red
        var gainG = 255.0 / green
        var gainB = 255.0 / blue
        val minGain = minOf(gainR, gainG, gainB)
        gainR /= minGain
        gainG /= minGain
        gainB /= minGain

        return RggbChannelVector(gainR.toFloat(), gainG.toFloat(), gainG.toFloat(), gainB.toFloat())
    }
}
