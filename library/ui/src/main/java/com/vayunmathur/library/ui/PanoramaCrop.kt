package com.vayunmathur.library.ui

/**
 * Which part of a full equirectangular sphere a stored image actually covers.
 *
 * A full 360×180 capture is [full]. A partial pano — a phone sweep, say — stores
 * only the band it photographed, and the XMP `GPano` rectangle says where that
 * band sits inside the sphere it would have been part of. The uncovered rest of
 * the sphere renders black.
 *
 * The field order deliberately matches photos' `PanoData`, the XMP struct these
 * values are read out of. All six are `Int`, so an order of our own would make a
 * positional copy from that struct compile clean and silently transpose
 * left/top with width/height — a panorama that renders subtly wrong and traces
 * back to nothing. Keep them aligned.
 */
data class PanoramaCrop(
    val fullWidth: Int,
    val fullHeight: Int,
    val croppedWidth: Int,
    val croppedHeight: Int,
    val croppedLeft: Int,
    val croppedTop: Int,
) {
    /** Factory for a [PanoramaCrop] covering the whole image. */
    companion object {
        /** An image that covers the whole sphere, so the crop is the image itself. */
        fun full(width: Int, height: Int) = PanoramaCrop(
            fullWidth = width,
            fullHeight = height,
            croppedWidth = width,
            croppedHeight = height,
            croppedLeft = 0,
            croppedTop = 0,
        )
    }
}
