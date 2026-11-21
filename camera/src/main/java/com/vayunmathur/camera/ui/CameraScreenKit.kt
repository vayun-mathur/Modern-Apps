package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.vayunmathur.camera.platform.CameraMode

/** Which settings pane is open below the preview. */
internal enum class CameraSetting {
    BRIGHTNESS, SHADOWS, WARMTH, EXPOSURE_TIME, PORTRAIT_BLUR, ISO, ZOOM
}

/**
 * The settings offered in [mode], in button-row order.
 *
 * Applicability is judged by whether the capture path actually honours the
 * setting in that mode:
 * - [CameraSetting.ZOOM]: `setZoomRatio` goes through the bound camera's
 *   `CameraControl`, which drives every bound use case — photo stills and
 *   the video Preview+VideoCapture alike (preview and recording), and is
 *   re-applied on every bind by `refreshCapabilities`/`restoreZoom`. So
 *   zoom is offered everywhere the zoom bar already worked.
 * - [CameraSetting.BRIGHTNESS] (exposure compensation): likewise a
 *   `CameraControl` call (`setExposureCompensationIndex`), re-applied on
 *   video binds too, so it is live for stills and video. Offered for
 *   PHOTO/PORTRAIT and the video family (VIDEO/TIMELAPSE/CINEMATIC).
 * - [CameraSetting.SHADOWS]/[CameraSetting.WARMTH]: baked into saved
 *   stills and previewed via a still-only RenderEffect; a video recording
 *   would silently drop them, so they stay PHOTO/PORTRAIT-only.
 * - [CameraSetting.EXPOSURE_TIME]/[CameraSetting.ISO]: manual shutter/ISO
 *   locks AE off; a fixed shutter fights the video frame rate, so they
 *   stay PHOTO-only (the existing manual-capture design).
 * - [CameraSetting.PORTRAIT_BLUR]: only the portrait session renders or
 *   bakes bokeh, so PORTRAIT-only.
 *
 * Flash/torch, grid, timer and mic are top-bar controls, not panes here;
 * video resolution/fps have no picker to offer (the video session always
 * records at max quality/fps, codec lives on the Settings page).
 */
internal fun settingsForMode(mode: CameraMode): List<CameraSetting> {
    val bakesStills = mode == CameraMode.PHOTO || mode == CameraMode.PORTRAIT
    val videoFamily = mode == CameraMode.VIDEO || mode == CameraMode.TIMELAPSE ||
        mode == CameraMode.CINEMATIC
    return buildList {
        if (bakesStills || videoFamily) add(CameraSetting.BRIGHTNESS)
        if (bakesStills) {
            add(CameraSetting.SHADOWS)
            add(CameraSetting.WARMTH)
        }
        if (mode == CameraMode.PHOTO) {
            add(CameraSetting.EXPOSURE_TIME)
            add(CameraSetting.ISO)
        }
        if (mode == CameraMode.PORTRAIT) add(CameraSetting.PORTRAIT_BLUR)
        add(CameraSetting.ZOOM)
    }
}

/**
 * The pane to actually show for [setting] in [mode]: a pane the new mode
 * does not offer resolves to null (the lens/zoom bar), so switching modes
 * with a photo-only pane open re-renders the mode-appropriate set instead
 * of leaving the stale slider on screen.
 */
internal fun effectiveSettingForMode(
    setting: CameraSetting?,
    mode: CameraMode,
): CameraSetting? = setting?.takeIf { it in settingsForMode(mode) }

/** Which camera pipeline drives the preview for the current mode. */
internal enum class SessionKind { PHOTO, PORTRAIT, PANORAMA, HIGH_SPEED, VIDEO }

internal fun Modifier.selectedPill(
    selected: Boolean,
    shape: Shape,
    color: Color = Color(0xFF3C3C3C)
): Modifier = if (selected) background(color, shape) else this
