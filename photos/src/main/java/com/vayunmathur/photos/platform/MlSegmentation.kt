package com.vayunmathur.photos.platform

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ml.SegmentationMask
import com.vayunmathur.library.ml.SubjectSegmenter
import com.vayunmathur.photos.data.Selection

/**
 * Auto-select the foreground subject with an on-device neural model —
 * **U²-Net portable** salient-object detection on **`:library:ml`**, our own Vulkan
 * compute runtime (Apache-2.0 weights, no ONNX Runtime, no MediaPipe, no Google Play
 * Services, F-Droid clean).
 *
 * U²-Net predicts a general per-pixel saliency map, so it selects arbitrary
 * subjects. Pixels above [FG_THRESHOLD] form the selection; the hard edge is
 * feathered slightly so cutouts aren't jagged. Runs off the main thread;
 * [onResult] is posted to the main thread (null on failure).
 *
 * Input 320×320 RGB, ImageNet-normalised; output a 320×320 saliency map through a
 * sigmoid. Both the resize and the normalisation now happen in
 * `library/ml/src/main/rust/src/preprocess.rs` rather than inside an out-of-repo AAR.
 *
 * This is the same network as the ncnn `u2netp` it replaces, re-sourced from a licensed
 * ONNX export, so the selections should look as they did. It is **GPU-only**: on a device
 * without Vulkan fp16 compute this returns null, which the caller already treats as "no
 * subject found".
 */
fun segmentSubject(context: Context, bitmap: Bitmap, onResult: (Selection?) -> Unit) {
    Thread {
        val sel = runCatching { runSegmenter(context, bitmap) }
            .getOrElse { Log.error("MlSegmentation", "segmentation failed", it); null }
        Handler(Looper.getMainLooper()).post { onResult(sel) }
    }.start()
}

private const val FG_THRESHOLD = 0.5f
private const val FEATHER_RADIUS = 1.5f

/**
 * Serialises the segmenter, which is not thread-safe.
 *
 * Nothing is cached between calls: the segmenter is built, used and closed inside one
 * [runSegmenter]. Its activation arena is **76 MiB of device memory**, and auto-select-
 * subject is a deliberate tap rather than something that happens continuously, so holding
 * that across the whole process to save the setup would be the wrong trade. Closing it also
 * releases the shared `VkDevice`, since `:photos` has no other user of `:library:ml`.
 */
private val segLock = Any()

/** Longest side handed to the segmenter, and of the returned mask. */
internal const val SEGMENTATION_MAX_DIM = 512

/** Size of the capped segmentation input / selection grid for a [width] x [height] source. */
internal fun segmentationInputSize(width: Int, height: Int, maxDim: Int = SEGMENTATION_MAX_DIM): Pair<Int, Int> {
    val scale = minOf(1f, maxDim.toFloat() / maxOf(width, height))
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

/**
 * [bitmap] downscaled to the [SEGMENTATION_MAX_DIM] cap, or [bitmap] itself when
 * it already fits. The caller owns a returned copy and must recycle it.
 */
internal fun downscaleForSegmentation(bitmap: Bitmap, maxDim: Int = SEGMENTATION_MAX_DIM): Bitmap {
    val (w, h) = segmentationInputSize(bitmap.width, bitmap.height, maxDim)
    return if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
}

/**
 * Run [segment] over the capped input and upsample its mask to the capped grid.
 * Split from [runSegmenter] so tests can capture the bitmap handed to the segmenter.
 */
internal fun runSegmentation(bitmap: Bitmap, segment: (Bitmap) -> SegmentationMask?): Selection? {
    // Cap the input and the returned mask resolution for speed/memory; the model
    // runs at its fixed (<=320) input size and downscales whatever it is given,
    // so handing it the full-size bitmap only pays for pixel copies the model
    // then throws away (its native path materialises the whole bitmap first).
    val (w, h) = segmentationInputSize(bitmap.width, bitmap.height)
    val input = downscaleForSegmentation(bitmap)
    val result = try {
        segment(input)
    } finally {
        if (input !== bitmap) input.recycle()
    } ?: return null

    val sw = result.width
    val sh = result.height
    val saliency = result.mask

    // Normalise to 0..1 (U²-Net output isn't guaranteed to span the full range).
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    for (v in saliency) {
        lo = minOf(lo, v)
        hi = maxOf(hi, v)
    }
    val range = (hi - lo).takeIf { it > 1e-6f } ?: 1f

    // Upsample the model mask to the (w,h) selection grid and threshold.
    val mask = FloatArray(w * h)
    for (y in 0 until h) {
        val sy = (y * sh / h).coerceIn(0, sh - 1)
        for (x in 0 until w) {
            val sx = (x * sw / w).coerceIn(0, sw - 1)
            val norm = (saliency[sy * sw + sx] - lo) / range
            mask[y * w + x] = if (norm >= FG_THRESHOLD) 1f else 0f
        }
    }

    return Selection(mask, w, h).applyFeather(FEATHER_RADIUS)
}

private fun runSegmenter(context: Context, bitmap: Bitmap): Selection? {
    synchronized(segLock) {
        return SubjectSegmenter(context.applicationContext).use { seg ->
            if (!seg.isAvailable) {
                Log.error("MlSegmentation", "U²-Net unavailable; see the ModelRunner log tag")
                null
            } else {
                // HARDWARE and unknown bitmap configs are handled inside SubjectSegmenter,
                // which has to deal with them anyway to call getPixels.
                runSegmentation(bitmap) { seg.segment(it) }
            }
        }
    }
}
