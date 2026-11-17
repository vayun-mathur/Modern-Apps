package com.vayunmathur.library.image.decoders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import com.vayunmathur.library.image.ImageRequest
import java.nio.ByteBuffer

/** Decodes encoded image bytes into sampled bitmaps. */
object BitmapDecoder {

    /**
     * Longest edge an unconstrained ("original size") decode may produce.
     *
     * Requests without `.size(...)` used to decode at full resolution, so one
     * panorama or high-MP photo became a ~500 MB bitmap that crashed in
     * `RecordingCanvas.throwIfCannotDraw` as soon as it was drawn (files #768).
     * 4096 keeps full-screen viewers sharp while a square 4096x4096 ARGB_8888
     * bitmap is 64 MB, comfortably under Canvas's draw limit.
     */
    const val MAX_ORIGINAL_DIMENSION = 4096

    private const val HALF_DIVISOR = 2
    private const val SAMPLE_STEP = 2
    private const val MIN_SAMPLE_SIZE = 1
    private const val NO_SCALE_RATIO = 1f
    private const val MIN_DECODE_DIMENSION = 1

    private const val SVG_MIN_SNIFF_BYTES = 5
    private const val SVG_SNIFF_BYTES = 1024
    private const val SVG_TAG = "<svg"
    private const val XML_DECLARATION = "<?xml"

    fun isSvg(bytes: ByteArray): Boolean {
        if (bytes.size < SVG_MIN_SNIFF_BYTES) return false
        val head = String(bytes.take(SVG_SNIFF_BYTES).toByteArray()).trimStart()
        if (head.startsWith(SVG_TAG, ignoreCase = true)) return true
        return head.startsWith(XML_DECLARATION, ignoreCase = true) &&
            head.contains(SVG_TAG, ignoreCase = true)
    }

    suspend fun decode(
        bytes: ByteArray,
        request: ImageRequest,
        allowHardware: Boolean,
    ): Bitmap? {
        val reqSize = request.size
        val targetW = reqSize?.width ?: -1
        val targetH = reqSize?.height ?: -1

        val viaImageDecoder = try {
            decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), request, allowHardware)
        } catch (_: Exception) {
            null
        }
        if (viaImageDecoder != null) return viaImageDecoder
        return try { decodeWithBitmapFactory(bytes, targetW, targetH, allowHardware) } catch (_: Exception) { null }
    }

    /**
     * Decode straight from an [ImageDecoder.Source], so local media never has to be
     * read into a `ByteArray` first. Downsampling happens inside the decoder, which
     * is why the target size matters more than where the bytes came from.
     */
    fun decode(
        source: ImageDecoder.Source,
        request: ImageRequest,
        allowHardware: Boolean,
    ): Bitmap? {
        val reqSize = request.size
        val targetW = reqSize?.width ?: -1
        val targetH = reqSize?.height ?: -1

        return try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                if (isSizedDownsample(w, h, targetW, targetH)) {
                    val ratio = maxOf(w.toFloat() / targetW, h.toFloat() / targetH)
                    if (ratio > NO_SCALE_RATIO) {
                        decoder.setTargetSize(
                            (w / ratio).toInt().coerceAtLeast(MIN_DECODE_DIMENSION),
                            (h / ratio).toInt().coerceAtLeast(MIN_DECODE_DIMENSION),
                        )
                    }
                } else if (isUnconstrained(targetW, targetH)) {
                    // Unconstrained request: never hand back a bitmap larger than
                    // MAX_ORIGINAL_DIMENSION on its longest edge (see files #768).
                    val longest = maxOf(w, h)
                    if (longest > MAX_ORIGINAL_DIMENSION) {
                        val ratio = longest.toFloat() / MAX_ORIGINAL_DIMENSION
                        decoder.setTargetSize(
                            (w / ratio).toInt().coerceAtLeast(MIN_DECODE_DIMENSION),
                            (h / ratio).toInt().coerceAtLeast(MIN_DECODE_DIMENSION),
                        )
                    }
                }
                decoder.isUnpremultipliedRequired = false
                decoder.allocator = if (allowHardware) {
                    ImageDecoder.ALLOCATOR_DEFAULT
                } else {
                    ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isSizedDownsample(w: Int, h: Int, targetW: Int, targetH: Int): Boolean {
        if (targetW <= 0 || targetH <= 0) return false
        return w > targetW || h > targetH
    }

    private fun isUnconstrained(targetW: Int, targetH: Int): Boolean =
        targetW <= 0 || targetH <= 0

    /**
     * Power-of-two downsampling for the `BitmapFactory` fallback, pure arithmetic
     * so it is unit-testable without Android bitmaps.
     *
     * A sized request samples to roughly the target; an unconstrained request
     * ("original size") still caps the longest edge at [MAX_ORIGINAL_DIMENSION]
     * so no caller can produce a Canvas-crashing bitmap (files #768).
     */
    internal fun sampleSizeFor(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (isSizedDownsampleRequest(srcW, srcH, reqW, reqH)) {
            val halfW = srcW / HALF_DIVISOR
            val halfH = srcH / HALF_DIVISOR
            while (halfW / sample >= reqW && halfH / sample >= reqH) {
                sample *= SAMPLE_STEP
            }
        } else if (isOversizedOriginal(srcW, srcH)) {
            while (srcW / sample > MAX_ORIGINAL_DIMENSION || srcH / sample > MAX_ORIGINAL_DIMENSION) {
                sample *= SAMPLE_STEP
            }
        }
        return sample.coerceAtLeast(MIN_SAMPLE_SIZE)
    }

    private fun isSizedDownsampleRequest(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Boolean =
        isPositiveSize(srcW, srcH) && isPositiveSize(reqW, reqH)

    private fun isPositiveSize(width: Int, height: Int): Boolean = width > 0 && height > 0

    private fun isOversizedOriginal(srcW: Int, srcH: Int): Boolean =
        isPositiveSize(srcW, srcH) &&
            (srcW > MAX_ORIGINAL_DIMENSION || srcH > MAX_ORIGINAL_DIMENSION)

    private fun decodeWithBitmapFactory(
        bytes: ByteArray,
        reqW: Int,
        reqH: Int,
        allowHardware: Boolean,
    ): Bitmap {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val (w, h) = boundsOpts.outWidth to boundsOpts.outHeight

        val sample = sampleSizeFor(w, h, reqW, reqH)

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = if (allowHardware) Bitmap.Config.ARGB_8888 else Bitmap.Config.ARGB_8888
            // HARDWARE config cannot be used with inSampleSize on older APIs safely – keep ARGB
            inMutable = false
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("BitmapFactory failed")
        // If hardware requested, convert roughly by copying with HARDWARE.
        if (allowHardware) {
            return try {
                bmp.copy(Bitmap.Config.HARDWARE, false) ?: bmp
            } catch (_: Exception) { bmp }
        }
        return bmp
    }
}
