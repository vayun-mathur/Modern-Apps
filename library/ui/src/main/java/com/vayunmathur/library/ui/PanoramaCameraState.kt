package com.vayunmathur.library.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Where [PanoramaSphere]'s camera is currently pointing, so a caller can project
 * its own overlay into the same scene.
 *
 * Strictly one-way: the renderer publishes here and nothing here feeds back. That
 * is what lets a caller draw things "in" the sphere — Street View's navigation
 * arrows do — without the shared renderer knowing anything about them.
 *
 * The values are written from the touch handler, which runs on the main thread,
 * so reads recompose normally.
 */
@Stable
class PanoramaCameraState {

    private var yawState by mutableFloatStateOf(0f)
    private var pitchState by mutableFloatStateOf(0f)
    private var fovState by mutableFloatStateOf(DEFAULT_FOV_DEG)

    /**
     * World azimuth the camera faces, in radians.
     *
     * The mesh puts source-image column `u` at azimuth `-u * 2pi` (see
     * [PanoramaSphere]), so `u == -yaw / 2pi`.
     */
    val yaw: Float get() = yawState

    /** Camera elevation in radians; positive is up, zero is the horizon. */
    val pitch: Float get() = pitchState

    /** Vertical field of view in degrees, as handed to the projection matrix. */
    val fovDeg: Float get() = fovState

    internal fun publish(yaw: Float, pitch: Float, fovDeg: Float) {
        yawState = yaw
        pitchState = pitch
        fovState = fovDeg
    }

    /** Default panorama field of view, in degrees. */
    companion object {
        const val DEFAULT_FOV_DEG = 75f
    }
}

@Composable
fun rememberPanoramaCameraState(): PanoramaCameraState = remember { PanoramaCameraState() }
