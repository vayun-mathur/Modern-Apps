package com.vayunmathur.camera.ui

/**
 * Pure rotation-in-place helpers for the portrait-locked camera.
 *
 * The activity never rotates (see AndroidManifest `screenOrientation="portrait"`),
 * so the window, and therefore every control's placement, is identical in
 * portrait and landscape. What changes with the physical device is only the
 * glyph orientation: [deviceRotationForOrientation] maps the sensor angle from
 * `OrientationEventListener` onto the quadrant the on-screen icons must rotate
 * to (via `Modifier.rotate`), while capture output rotation is handled by
 * CameraX (`SessionConfig.setAutoRotationEnabled(true)` in the session binder).
 */

/**
 * Maps an `OrientationEventListener` angle (0..359, or
 * `ORIENTATION_UNKNOWN` = -1) to the icon rotation in degrees clockwise.
 * Returns null for an unknown angle so the caller keeps the last rotation
 * instead of snapping back to upright.
 */
internal fun deviceRotationForOrientation(orientation: Int): Int? = when {
    orientation < 0 -> null
    orientation in 45..134 -> 270
    orientation in 135..224 -> 180
    orientation in 225..314 -> 90
    else -> 0
}

/**
 * Whether the capture controls (shutter row + mode/bottom rows) rearrange
 * side-by-side for [deviceRotation]. Always false: rotating the phone must
 * never move a control — the shutter stays centred at the bottom, the mode
 * row stays under it, and the bottom bar keeps its slots; only the icons
 * inside those slots rotate. A layout switch keyed on rotation (or on the
 * window widening that a rotating activity would cause) is the regression
 * this guards.
 */
internal fun captureControlsSideBySide(deviceRotation: Int): Boolean {
    require(deviceRotation == 0 || deviceRotation == 90 || deviceRotation == 180 || deviceRotation == 270) {
        "deviceRotation must be a quadrant, was $deviceRotation"
    }
    return false
}
