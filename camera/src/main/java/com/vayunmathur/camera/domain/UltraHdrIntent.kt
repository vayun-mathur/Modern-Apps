package com.vayunmathur.camera.domain

/** Caller's still-output intent, resolved before any platform capture runs. */
enum class UltraHdrIntent {
    PLAIN_JPEG,
    ULTRA_HDR,
}

/**
 * Mirrors the decision CameraX still capture makes today, so Camera2 does
 * not fork it: there is no user-facing Ultra HDR toggle. `CameraPhotoSessionShared.probeUltraHdr`
 * asks CameraX for `OUTPUT_FORMAT_JPEG_ULTRA_HDR` in the lens's supported
 * output formats and `CameraPhotoSessionSetup.buildPhotoCapture` selects it
 * whenever that probe passes (the bind ladder, not a setting, falls back to
 * plain JPEG when the combo cannot bind). CameraX's Ultra HDR format is in
 * turn backed by the HAL advertising `ImageFormat.JPEG_R` (API 34+), which
 * is exactly the boolean the Camera2 facade reports, so the same decision
 * is: supported -> Ultra HDR intent, unsupported -> plain JPEG.
 */
object UltraHdrIntentPolicy {

    /** The intent for a lens whose JPEG_R (Ultra HDR) support is [jpegRSupported]. */
    fun resolve(jpegRSupported: Boolean): UltraHdrIntent =
        if (jpegRSupported) UltraHdrIntent.ULTRA_HDR else UltraHdrIntent.PLAIN_JPEG

    /** True when the caller should request an Ultra HDR (JPEG_R) still. */
    fun wantsUltraHdr(jpegRSupported: Boolean): Boolean =
        resolve(jpegRSupported) == UltraHdrIntent.ULTRA_HDR
}
