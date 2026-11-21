package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import com.vayunmathur.camera.domain.StillFormat
import com.vayunmathur.camera.domain.StillFormatRouting
import com.vayunmathur.library.log.Log
import java.io.ByteArrayOutputStream

/** One encoded still plus the container it actually landed in. */
internal data class EncodedStill(val bytes: ByteArray, val format: StillFormat) {
    override fun equals(other: Any?): Boolean =
        other is EncodedStill && format == other.format && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + format.hashCode()
}

/**
 * Encodes a processed bitmap to the user's still format.
 *
 * AVIF goes through the Rust `ravif` encoder behind [StitchNative]; when
 * the native library is missing or encoding fails, this falls back to
 * JPEG bytes so a shot is never lost. Orientation must already be baked
 * into the pixels by the caller: ravif exposes no EXIF API, so AVIF
 * stills carry no orientation/GPS tags (the JPEG path stamps them via
 * `writeCaptureExif` instead).
 */
internal object StillEncoder {

    /**
     * Encoded container for [bitmap] under the current settings.
     * [rotationDegrees] is baked into the pixels on the AVIF path (no
     * EXIF orientation tag there); the JPEG path leaves pixels untouched
     * and the caller stamps orientation via EXIF as before.
     */
    fun CameraViewModel.encode(bitmap: Bitmap, rotationDegrees: Int = 0): EncodedStill {
        val requested = if (photoFormatMutable.value == PhotoFormat.AVIF) {
            StillFormat.AVIF
        } else {
            StillFormat.JPEG
        }
        val effective = StillFormatRouting.effectiveFormat(
            requested = requested,
            nativeAvailable = StitchNative.isAvailable,
            motionPhoto = false,
        )
        if (effective == StillFormat.AVIF) {
            val upright = uprightCopy(bitmap, rotationDegrees, 0)
            try {
                encodeAvif(upright)?.let { return EncodedStill(it, StillFormat.AVIF) }
            } finally {
                if (upright !== bitmap) upright.recycle()
            }
            Log.status("StillEncoder", "AVIF encode failed; falling back to JPEG")
        }
        return EncodedStill(encodeJpeg(bitmap), StillFormat.JPEG)
    }

    private fun encodeAvif(bitmap: Bitmap): ByteArray? {
        if (!StitchNative.isAvailable) return null
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val rgba = ByteArray(pixels.size * 4)
        for (i in pixels.indices) {
            val px = pixels[i]
            rgba[i * 4] = (px shr 16 and 0xFF).toByte()
            rgba[i * 4 + 1] = (px shr 8 and 0xFF).toByte()
            rgba[i * 4 + 2] = (px and 0xFF).toByte()
            rgba[i * 4 + 3] = (px ushr 24 and 0xFF).toByte()
        }
        return try {
            StitchNative.encodeAvif(
                rgba, width, height,
                StillFormatRouting.AVIF_QUALITY, StillFormatRouting.AVIF_SPEED,
            )
        } catch (e: UnsatisfiedLinkError) {
            Log.status("StillEncoder", "AVIF native call failed; JPEG fallback", e)
            null
        }
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, StillFormatRouting.JPEG_QUALITY, out)
            out.toByteArray()
        }
}
