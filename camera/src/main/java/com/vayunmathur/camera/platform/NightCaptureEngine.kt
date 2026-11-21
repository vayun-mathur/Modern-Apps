package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Multi-frame computational night capture. Takes a burst of upright/consistent
 * frames, aligns + merges them via the native [StitchNative] Rust library to cut
 * noise (~√N read/shot-noise reduction), then brightens the result with a
 * highlight-preserving curve so it reads as a bright night shot without clipping.
 *
 * Routing (see `captureNightPhoto` in CameraPhotoCapture.kt) prefers the vendor
 * camera-extensions / ExtensionMode.NIGHT path when `nightExtensionUsable` is true;
 * this custom engine is the fallback used on extension-less devices (most) and when
 * the extension is unusable, when auto low-light detection fires. The extension
 * path does its own vendor merge and does not go through this engine.
 *
 * Improvements vs old path:
 * - Burst is still collected from ImageAnalysis but at HIGHEST_AVAILABLE resolution,
 *   and fed to Rust as lossless RGBA via newNightSession/addNightRgbaFrame/mergeNight,
 *   avoiding the old Bitmap -> JPEG 95 -> decode double loss.
 * - Rust runs ORB registration at ~0.8 MP, rescales homography to full res, + deghosting
 *   against reference to avoid moving-object ghosts.
 * - Tone mapping preserves highlights with a soft knee instead of linear *1.6+18 clip.
 * - Full-res ImageCapture burst variant captures via ImageCapture repeated
 *   onImageCaptured (higher IQ than analysis stream).
 */
object NightCaptureEngine {
    /** How many frames to stack. Higher = less noise but longer capture + more work. */
    const val NIGHT_BURST_COUNT = 6

    // Brightening baked into the merged result (shadow lift + gain, highlight compressed).
    private const val NIGHT_GAIN = 1.6f
    private const val NIGHT_SHADOW_LIFT = 18f
    private const val HIGHLIGHT_COMPRESS_START = 220f
    private const val HIGHLIGHT_COMPRESS_FACTOR = 0.38f

    /** ARGB packing shifts, channel mask, JPEG quality, RGBA stride, dimension cap. */
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL_MASK = 0xFF
    private const val CHANNEL_MAX_FLOAT = 255f
    private const val CHANNEL_MIN = 0
    private const val CHANNEL_MAX_INT = 255
    private const val NIGHT_JPEG_QUALITY = 95
    private const val RGBA_BYTES_PER_PIXEL = 4
    private const val MAX_FRAME_SIDE_PX = 20000

    /** Merge input cap: frames are downscaled so merge/brighten runs at ≤8 MP. */
    internal const val MAX_MERGE_PIXELS = 8_000_000

    /**
     * Dimensions for a [width]×[height] frame capped at [maxPixels], preserving aspect
     * ratio. Unchanged when already within the cap (or degenerate); floored to ≥1px/side.
     */
    internal fun mergeScaledDimensions(width: Int, height: Int, maxPixels: Int = MAX_MERGE_PIXELS): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxPixels <= 0) return width to height
        val pixels = width.toLong() * height.toLong()
        if (pixels <= maxPixels) return width to height
        val scale = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble())
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    /** Downscaled copy of [frame] for merging, or [frame] itself when within the cap. */
    private fun downscaleForMerge(frame: Bitmap): Bitmap {
        val (w, h) = mergeScaledDimensions(frame.width, frame.height)
        if (w == frame.width && h == frame.height) return frame
        return Bitmap.createScaledBitmap(frame, w, h, true)
    }

    /**
     * Aligns and merges [burst] into a single brightened bitmap. Falls back to the
     * middle frame if native align/merge is unavailable. Runs off the main thread.
     */
    suspend fun merge(burst: List<Bitmap>): Bitmap? = withContext(Dispatchers.Default) {
        if (burst.isEmpty()) return@withContext null
        // Downscaled copies owned by this call (identity-distinct from caller frames).
        val owned = mutableListOf<Bitmap>()
        try {
            val sized = burst.filter { it.width > 0 && it.height > 0 }.let { list ->
                if (list.isEmpty()) return@withContext null
                val w = list[0].width
                val h = list[0].height
                val same = list.filter { it.width == w && it.height == h }
                if (same.size >= 2) same else list.take(1)
            }.map { frame ->
                downscaleForMerge(frame).also { if (it !== frame) owned.add(it) }
            }

            val merged = alignAndMergeNative(sized)
            val source = merged ?: sized[sized.size / 2]

            val w = source.width
            val h = source.height
            val n = w * h
            val px = IntArray(n)
            source.getPixels(px, 0, w, 0, 0, w, h)
            for (i in 0 until n) {
                val p = px[i]
                val a = (p ushr ALPHA_SHIFT) and CHANNEL_MASK
                val r = (p shr RED_SHIFT) and CHANNEL_MASK
                val g = (p shr GREEN_SHIFT) and CHANNEL_MASK
                val b = p and CHANNEL_MASK
                // Alpha preserved from source, RGB via highlight-preserving curve
                val rr = brightenChannel(r.toFloat())
                val gg = brightenChannel(g.toFloat())
                val bb = brightenChannel(b.toFloat())
                px[i] = (a shl ALPHA_SHIFT) or (rr shl RED_SHIFT) or (gg shl GREEN_SHIFT) or bb
            }
            if (merged != null && merged !== source) {
                merged.recycle()
            }
            createBitmap(w, h).apply {
                setPixels(px, 0, w, 0, 0, w, h)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            // Caller (mergeNightFrames) falls back to the middle original frame.
            null
        } catch (e: Throwable) {
            null
        } finally {
            owned.forEach { it.recycle() }
        }
    }

    /**
     * Applies the night brightening curve to an already-merged bitmap
     * (the RAW linear merge's tone-mapped output). Returns a new bitmap;
     * the caller owns both. This is the existing tone pipeline the RAW
     * path hands off to before `StillEncoder`.
     */
    fun applyNightTone(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr ALPHA_SHIFT) and CHANNEL_MASK
            val r = (p shr RED_SHIFT) and CHANNEL_MASK
            val g = (p shr GREEN_SHIFT) and CHANNEL_MASK
            val b = p and CHANNEL_MASK
            px[i] = (a shl ALPHA_SHIFT) or
                (brightenChannel(r.toFloat()) shl RED_SHIFT) or
                (brightenChannel(g.toFloat()) shl GREEN_SHIFT) or
                brightenChannel(b.toFloat())
        }
        return createBitmap(w, h).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    /** Lossless RGBA path -> Rust (no double JPEG), fallback to old JPEG path. */
    private fun alignAndMergeNative(burst: List<Bitmap>): Bitmap? {
        if (!StitchNative.isAvailable) return null
        // First try lossless RGBA session (no double JPEG quality loss)
        tryLosslessRgba(burst)?.let { return it }
        // Fallback: old JPEG-compressed path (backwards compat)
        return try {
            val handle = StitchNative.newSession(false)
            try {
                for (f in burst) {
                    val baos = java.io.ByteArrayOutputStream()
                    f.compress(Bitmap.CompressFormat.JPEG, NIGHT_JPEG_QUALITY, baos)
                    StitchNative.addFrame(handle, baos.toByteArray(), 0f, 0f, 0f)
                }
                val jpeg = StitchNative.merge(handle) ?: return null
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            } finally {
                StitchNative.free(handle)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryLosslessRgba(burst: List<Bitmap>): Bitmap? {
        return try {
            val handle = StitchNative.newNightSession()
            if (handle == 0L) return null
            try {
                for (f in burst) {
                    addRgbaFrame(handle, f)
                }
                val jpeg = StitchNative.mergeNight(handle) ?: return null
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            } finally {
                try {
                    StitchNative.freeNight(handle)
                } catch (_: Throwable) {
                    try { StitchNative.free(handle) } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Frame-size guard: non-empty and within the native dimension cap. */
    private fun isStitchableSize(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (w > MAX_FRAME_SIDE_PX || h > MAX_FRAME_SIDE_PX) return false
        return true
    }

    /** Converts one frame to RGBA bytes and adds it to the night session. */
    private fun addRgbaFrame(handle: Long, f: Bitmap) {
        val w = f.width
        val h = f.height
        if (!isStitchableSize(w, h)) return
        val ints = IntArray(w * h)
        f.getPixels(ints, 0, w, 0, 0, w, h)
        val rgba = ByteArray(w * h * RGBA_BYTES_PER_PIXEL)
        var j = 0
        for (p in ints) {
            rgba[j + RED_OFFSET] = ((p shr RED_SHIFT) and CHANNEL_MASK).toByte() // R
            rgba[j + GREEN_OFFSET] = ((p shr GREEN_SHIFT) and CHANNEL_MASK).toByte() // G
            rgba[j + BLUE_OFFSET] = (p and CHANNEL_MASK).toByte() // B
            rgba[j + ALPHA_OFFSET] = ((p ushr ALPHA_SHIFT) and CHANNEL_MASK).toByte() // A
            j += RGBA_BYTES_PER_PIXEL
        }
        StitchNative.addNightRgbaFrame(handle, rgba, w, h)
    }

    /** Highlight taper: gain at full white and shadow falloff factor. */
    private const val WHITE_GAIN = 1.15f
    private const val GAIN_TAPER = 0.65f
    private const val SHADOW_FALLOFF = 0.3f
    /** RGBA channel offsets within one pixel. */
    private const val RED_OFFSET = 0
    private const val GREEN_OFFSET = 1
    private const val BLUE_OFFSET = 2
    private const val ALPHA_OFFSET = 3
    /** Unit-luminance reference for the normalized brightness ratio. */
    private const val UNIT_LUMINANCE = 1f

    /**
     * Highlight-preserving brightening:
     * - Shadow lift weighted more in dark regions
     * - Gain tapers toward highlights so brights don't clip
     * - Soft knee compression above [HIGHLIGHT_COMPRESS_START]
     */
    private fun brightenChannel(value: Float): Int {
        val y = value / CHANNEL_MAX_FLOAT
        val taperedGain = taperedGain(y)
        val lifted = value * taperedGain + shadowLift(y)
        val compressed = compressHighlight(lifted)
        return compressed.roundToInt().coerceIn(CHANNEL_MIN, CHANNEL_MAX_INT)
    }

    /** Gain tapering from NIGHT_GAIN in shadows toward WHITE_GAIN at full white. */
    private fun taperedGain(normalizedY: Float): Float {
        return NIGHT_GAIN - normalizedY * (NIGHT_GAIN - WHITE_GAIN) * GAIN_TAPER
    }

    /** Shadow lift weighted toward dark regions with a soft falloff. */
    private fun shadowLift(normalizedY: Float): Float {
        return NIGHT_SHADOW_LIFT * (UNIT_LUMINANCE - normalizedY) *
            (UNIT_LUMINANCE - normalizedY * SHADOW_FALLOFF)
    }

    /** Soft-knee compression above [HIGHLIGHT_COMPRESS_START]. */
    private fun compressHighlight(lifted: Float): Float {
        if (lifted <= HIGHLIGHT_COMPRESS_START) return lifted
        return HIGHLIGHT_COMPRESS_START + (lifted - HIGHLIGHT_COMPRESS_START) * HIGHLIGHT_COMPRESS_FACTOR
    }
}
