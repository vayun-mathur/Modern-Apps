package com.vayunmathur.camera.domain

/**
 * Geometry of a stitched panorama, written out as GPano XMP so compliant viewers
 * render the image as panoramic/360. [projectionType] is "cylindrical" for a flat
 * panorama or "equirectangular" for a full sphere.
 */
data class PanoInfo(
    val projectionType: String,
    val fullWidth: Int,
    val fullHeight: Int,
    val croppedWidth: Int,
    val croppedHeight: Int,
    val croppedLeft: Int,
    val croppedTop: Int,
)
