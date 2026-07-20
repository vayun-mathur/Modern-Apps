package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Multi-frame computational night capture. Takes a burst of upright/consistent
 * (uncompressed) frames, aligns + merges them via the native [StitchNative] Rust
 * library to cut noise (~√N read/shot-noise reduction), then brightens the
 * result so it reads as a bright night shot.
 *
 * Source-agnostic: it only sees a [List]<[Bitmap]>. Alignment (feature-based
 * homography + temporal average) now lives in Rust, replacing the old OpenCV
 * SIFT path.
 */
object NightCaptureEngine {
    /** How many frames to stack. Higher = less noise but longer capture + more work. */
    const val NIGHT_BURST_COUNT = 6

    // Brightening baked into the merged result.
    private const val NIGHT_GAIN = 1.6f
    private const val NIGHT_SHADOW_LIFT = 18f

    /**
     * Aligns and merges [burst] into a single brightened bitmap. Returns null only when [burst] is
     * empty. Falls back to the middle frame if native align/merge is unavailable. Runs off the main
     * thread; the caller owns recycling of the input bitmaps.
     */
    suspend fun merge(burst: List<Bitmap>): Bitmap? = withContext(Dispatchers.Default) {
        if (burst.isEmpty()) return@withContext null

        val merged = alignAndMergeNative(burst)
        val source = merged ?: burst[burst.size / 2]

        val w = source.width
        val h = source.height
        val n = w * h
        val px = IntArray(n)
        source.getPixels(px, 0, w, 0, 0, w, h)
        for (i in 0 until n) {
            val p = px[i]
            val r = brighten(((p shr 16) and 0xFF).toFloat())
            val g = brighten(((p shr 8) and 0xFF).toFloat())
            val b = brighten((p and 0xFF).toFloat())
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        merged?.recycle()
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(px, 0, w, 0, 0, w, h)
        }
    }

    /** Runs the native feature-based align + temporal-average merge; null on failure. */
    private fun alignAndMergeNative(burst: List<Bitmap>): Bitmap? {
        if (!StitchNative.isAvailable) return null
        val handle = StitchNative.newSession(false)
        try {
            for (f in burst) {
                val baos = java.io.ByteArrayOutputStream()
                f.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                StitchNative.addFrame(handle, baos.toByteArray(), 0f, 0f, 0f)
            }
            val jpeg = StitchNative.merge(handle) ?: return null
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } finally {
            StitchNative.free(handle)
        }
    }

    /** Applies the night gain + shadow lift to a single 0..255 channel value. */
    private fun brighten(value: Float): Int =
        (value * NIGHT_GAIN + NIGHT_SHADOW_LIFT).roundToInt().coerceIn(0, 255)
}
