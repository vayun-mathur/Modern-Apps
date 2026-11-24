package com.vayunmathur.camera.platform

/** The zoom ratio panorama-style capture is locked to: no digital zoom, ever. */
internal const val ZOOM_LOCK_RATIO = 1f

/**
 * True for the modes whose capture is stitched from analysis frames
 * (PANORAMA / PHOTOSPHERE — the "Sphere" mode of the UI). Stitch
 * registration assumes every input frame shares the 1x field of view, so
 * these modes ignore the stored zoom ratio entirely: entering one applies
 * [ZOOM_LOCK_RATIO] to the live session, and leaving one resumes the
 * stored ratio (it is never overwritten while locked — see
 * `CameraControls.restoreZoom`).
 */
internal fun CameraMode.isZoomLockedTo1x(): Boolean =
    this == CameraMode.PANORAMA || this == CameraMode.PHOTOSPHERE

/** The zoom ratio to apply/show for [mode]: locked modes always get 1x. */
internal fun effectiveZoomRatioForMode(mode: CameraMode, storedZoomRatio: Float): Float =
    if (mode.isZoomLockedTo1x()) ZOOM_LOCK_RATIO else storedZoomRatio
