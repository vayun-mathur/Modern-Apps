package com.vayunmathur.camera.domain

/**
 * Which side of the device a lens sits on.
 *
 * Pure JVM (no CameraX dependency); the platform layer maps these to
 * `CameraSelector.LENS_FACING_*` when building selectors.
 */
enum class LensFacing {
    BACK,
    FRONT,
}

/**
 * Physical role of a lens within its facing family, derived from the HAL focal
 * lengths at enumeration time (smallest = ultra-wide … largest = telephoto).
 */
enum class LensType {
    ULTRA_WIDE,
    WIDE,
    TELEPHOTO,
    MONOCHROME,
    FRONT,
}

/**
 * One physical lens enumerated from the device.
 *
 * @param logicalCameraId the Camera2 logical camera ID backing this lens; used
 *   by the platform selector filter to pin a bind to this module.
 * @param focalLengthMm35Eq 35mm-equivalent focal length when the HAL reports
 *   it; null when unavailable (the label math falls back to the intrinsic /
 *   nominal zoom ratios, see `domain/LensLabels.kt`).
 * @param nominalZoomRatio zoom ratio of this lens relative to the wide (1x)
 *   lens of the same facing, e.g. ~0.5 for UW, 1 for wide, 3+ for tele.
 * @param intrinsicZoomRatio `CameraInfo.getIntrinsicZoomRatio()` when the
 *   CameraX implementation knows it (logical multi-camera); null /
 *   `INTRINSIC_ZOOM_RATIO_UNKNOWN` otherwise. Refines the label math.
 * @param fallbackPriority lower wins when falling back within the same
 *   facing (wide/front default = 0).
 * @param labelKey stable key for the display label ("ultrawide", "wide",
 *   "tele", "mono", "front"); the UI maps it to `strings.xml`.
 */
data class PhysicalLens(
    val logicalCameraId: String,
    val lensType: LensType,
    val facing: LensFacing,
    val focalLengthMm35Eq: Float?,
    val nominalZoomRatio: Float,
    val intrinsicZoomRatio: Float? = null,
    val fallbackPriority: Int,
    val labelKey: String,
)
