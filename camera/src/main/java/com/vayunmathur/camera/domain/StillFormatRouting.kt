package com.vayunmathur.camera.domain

/** Still container the user picked in camera settings. */
enum class StillFormat {
    JPEG,
    AVIF,
}

/**
 * Pure routing decisions for still saving: which container a capture
 * actually lands in. Kept Android-free so the fallback rules are unit
 * tested on the host.
 */
object StillFormatRouting {

    /** JPEG quality used for the platform/Bitmap JPEG fallback path. */
    const val JPEG_QUALITY = 95

    /** AVIF quality (ravif scale 1..100) for the native encoder. */
    const val AVIF_QUALITY = 80f

    /** AVIF speed (rav1e scale 1..10, 10 = fastest) for stills. */
    const val AVIF_SPEED = 8

    /**
     * The format a still is really saved as. AVIF is only honoured when
     * the native encoder is present; Motion Photos always stay JPEG
     * because the format is a JPEG with an appended MP4 trailer.
     */
    fun effectiveFormat(requested: StillFormat, nativeAvailable: Boolean, motionPhoto: Boolean): StillFormat =
        when {
            motionPhoto -> StillFormat.JPEG
            requested == StillFormat.AVIF && !nativeAvailable -> StillFormat.JPEG
            else -> requested
        }

    /** File extension (no dot) matching [format]. */
    fun extension(format: StillFormat): String = when (format) {
        StillFormat.JPEG -> "jpg"
        StillFormat.AVIF -> "avif"
    }

    /** MediaStore MIME type matching [format]. */
    fun mimeType(format: StillFormat): String = when (format) {
        StillFormat.JPEG -> "image/jpeg"
        StillFormat.AVIF -> "image/avif"
    }

    /** `IMG_<timestamp>` display name in the container's extension. */
    fun displayName(timestamp: String, format: StillFormat): String =
        "IMG_$timestamp.${extension(format)}"
}
