package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap
import com.vayunmathur.library.log.Log

/** A segmentation mask: [width] × [height] probabilities in `0..1`, row-major. */
class SegmentationMask(
    /** Columns. Fixed per network, and independent of the input bitmap's size. */
    val width: Int,
    /** Rows. */
    val height: Int,
    /**
     * Foreground probability per pixel.
     *
     * A fresh array each call — the caller owns it and both call sites blend or threshold
     * it in place.
     */
    val mask: FloatArray,
)

/**
 * The safe half of the JNI surface: a network handle with a lifetime.
 *
 * `internal`, and held by composition rather than inherited from, so the public API of
 * this module is exactly [SelfieSegmenter] and [SubjectSegmenter] with three members each
 * — Kotlin would otherwise force this type public to let a public class extend it.
 *
 * # Failure is a state, not an exception
 *
 * The constructor never throws. If `libmodelrunner.so` is missing, or the device has no
 * Vulkan fp16 compute, or the asset will not parse, [isAvailable] is false and [segment]
 * returns null — the graceful-degradation shape `GpuStitcher` uses in `:camera`. That is
 * the GPU-only decision made visible: there is no CPU fallback, so the feature turns off
 * and the caller keeps whatever it had.
 *
 * # Not thread-safe, on purpose
 *
 * [segment] and [close] must not overlap. `BokehAnalyzer` holds `synchronized(lock)`
 * across the whole native call for exactly this reason — its comments record a real
 * `SEGV_MTESERR` use-after-free where `close` destroyed the net while worker threads were
 * still reading it. A `VkDevice` handle has the identical hazard, so putting a lock in
 * here as well would be a second, redundant one; the discipline stays where the threading
 * actually is.
 */
internal class NativeSegmenter(
    context: Context,
    assetName: String,
    tag: String,
    create: (ByteArray) -> Long,
) : AutoCloseable {

    /**
     * `@Volatile` because it is the one field whose staleness would be a use-after-free.
     *
     * Callers do establish happens-before via their own locks, so this is belt and braces
     * — but the cost is nothing and the failure mode is a native crash.
     */
    @Volatile private var handle: Long = if (!MlNative.isAvailable) {
        0L
    } else {
        try {
            // `.maml` is `noCompress` in both apps, so this is a straight copy out of the
            // mapped APK rather than an inflate into a second buffer.
            create(context.assets.open(assetName).use { it.readBytes() })
        } catch (expected: Exception) {
            Log.error(tag, "cannot load $assetName", expected)
            0L
        }
    }

    /** Whether the network came up. False means the feature is off on this device. */
    val isAvailable: Boolean get() = handle != 0L

    /**
     * Run the network over [bitmap] and return the mask, or null if it is unavailable or
     * the inference failed.
     *
     * [bitmap] may be any size: it is resized and normalised natively. The returned mask
     * is the network's own resolution, which the caller upsamples.
     */
    fun segment(bitmap: Bitmap): SegmentationMask? {
        if (handle == 0L) return null
        // `getPixels` cannot read a HARDWARE bitmap, and a null config means an unknown
        // one. Copying is the only option, and doing it here keeps both call sites from
        // each having to remember.
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE || bitmap.config == null) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        } ?: return null

        return try {
            val count = readable.width * readable.height
            var buffer = pixels
            if (buffer == null || buffer.size != count) {
                buffer = IntArray(count)
                pixels = buffer
            }
            readable.getPixels(buffer, 0, readable.width, 0, 0, readable.width, readable.height)
            val values = MlNative.segment(handle, buffer, readable.width, readable.height)
                ?: return null
            SegmentationMask(MlNative.maskWidth(handle), MlNative.maskHeight(handle), values)
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    /**
     * Reused across calls so the ~15 fps preview path does not allocate a megabyte of
     * `int[]` per second.
     */
    private var pixels: IntArray? = null

    /** Free the network. Idempotent, and safe to call from a `DisposableEffect`. */
    override fun close() {
        val toDestroy = handle
        handle = 0L
        pixels = null
        if (toDestroy != 0L) MlNative.destroy(toDestroy)
    }
}
