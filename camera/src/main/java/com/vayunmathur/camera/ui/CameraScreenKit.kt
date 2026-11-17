package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.vayunmathur.camera.platform.CameraMode

/** Which settings pane is open below the preview. */
internal enum class CameraSetting {
    BRIGHTNESS, SHADOWS, WARMTH, EXPOSURE_TIME, PORTRAIT_BLUR, ISO
}

/** Which camera pipeline drives the preview for the current mode. */
internal enum class SessionKind { PHOTO, PORTRAIT, PANORAMA, HIGH_SPEED, VIDEO }

internal fun Modifier.selectedPill(
    selected: Boolean,
    shape: Shape,
    color: Color = Color(0xFF3C3C3C)
): Modifier = if (selected) background(color, shape) else this
