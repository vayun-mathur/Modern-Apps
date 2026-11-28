package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap
import com.vayunmathur.library.log.Log

/**
 * One recognised line of text, in source-bitmap pixel coordinates.
 *
 * [corners] are the four corners of the region's oriented quad, ordered so that corner 0 to
 * 1 runs along the text and 0 to 3 spans its height. A detected region is a *rotated*
 * rectangle, not an upright one, so the quad carries information a rect would lose - and
 * [left]/[top]/[right]/[bottom] are its axis-aligned bounds for callers that only need one.
 */
data class RecognizedLine(
    val text: String,
    val confidence: Float,
    val corners: List<Pair<Float, Float>>,
    val vertical: Boolean,
) {
    val left: Float get() = corners.minOf { it.first }
    val top: Float get() = corners.minOf { it.second }
    val right: Float get() = corners.maxOf { it.first }
    val bottom: Float get() = corners.maxOf { it.second }
}

/**
 * On-device text recognition: PP-OCRv5 mobile detection and recognition on Vulkan compute.
 *
 * Replaces the ncnn PP-OCR path. The detector is DBNet over a PP-HGNetV2 backbone and the
 * recogniser is a PP-LCNetV3 backbone into a two-block transformer with a CTC head, both
 * converted to `.maml` by `scripts/ml/ppocr_fold.py`. The recogniser is the **latin** model
 * - 836 characters covering Latin-script languages, no CJK - which is what keeps it under
 * 4 MB.
 *
 * Everything between the two networks happens natively: the rotated crop out of each
 * detected quad, the CTC collapse, and reading order. So [recognize] crosses the JNI
 * boundary once per bitmap rather than once per line.
 *
 * # Availability
 *
 * The constructor never throws. [isAvailable] is false when `libmodelrunner.so` is missing
 * for this ABI, when an asset is absent, or when the device cannot give us a Vulkan device
 * with fp16 compute - and then [recognize] returns an empty list. Vulkan 1.1 with
 * `shaderFloat16` is available across the minSdk 31 fleet, so this is a guard rather than
 * an expected path.
 *
 * # Threading
 *
 * Thread-safe: recognise passes are serialised internally and [close] defers
 * the native destruction until any in-flight pass has returned, so no caller
 * has to hold its own lock across [recognize] and [close] to avoid freeing
 * the native handle under a live call.
 *
 * Not cheap to construct either - two nets, two arenas, two command buffers - so build one
 * per batch of images and [close] it, rather than one per image.
 */
class TextRecognizer(
    context: Context,
    detectionAsset: String = DETECTION_ASSET,
    recognitionAsset: String = RECOGNITION_ASSET,
    dictionaryAsset: String = DICTIONARY_ASSET,
) : AutoCloseable {
    private var handle: Long = 0L
    private var pixels: IntArray? = null

    // Lifetime guard for the native handle (issue #944). This class used to rely
    // on every caller holding an external lock across recognise + close; the pdf
    // viewer's OcrEngine holds its Mutex across recognise but its close() is a
    // plain non-suspend call that cannot, so closing a document while an OCR
    // pass was still running on Dispatchers.Default freed the OcrHandle under
    // the native call — a use-after-free that surfaces as an MTE tag mismatch
    // inside recognizeText. The guard is therefore internal now:
    //  - [callLock] serialises the native calls themselves (the handle's pixel
    //    Vec and Vulkan nets are single-user, and [pixels] is a reused buffer);
    //  - [stateLock] + [inFlight] make [close] non-destroying while a call is
    //    in flight: destruction is deferred to the last call's exit.
    private val callLock = Any()
    private val stateLock = Any()
    private var inFlight = 0
    private var closeRequested = false

    /** Mark a native call as started, or refuse it once closing. */
    private fun beginUse(): Boolean = synchronized(stateLock) {
        if (closeRequested || handle == 0L) {
            false
        } else {
            inFlight++
            true
        }
    }

    /** Mark a native call as finished; destroys the handle if [close] deferred it. */
    private fun endUse() {
        val destroy = synchronized(stateLock) {
            inFlight--
            if (closeRequested && inFlight == 0 && handle != 0L) {
                val live = handle
                handle = 0L
                live
            } else {
                0L
            }
        }
        if (destroy != 0L) MlNative.destroyOcr(destroy)
    }

    init {
        handle = if (!MlNative.isAvailable) {
            0L
        } else {
            try {
                val assets = context.assets
                val detection = assets.open(detectionAsset).use { it.readBytes() }
                val recognition = assets.open(recognitionAsset).use { it.readBytes() }
                val keys = assets.open(dictionaryAsset).use { it.readBytes() }.decodeToString()
                MlNative.createPpocr(detection, recognition, keys)
            } catch (expected: Exception) {
                Log.error(TAG, "cannot open the PP-OCRv5 assets", expected)
                0L
            }
        }
    }

    /** True if both networks came up and the dictionary parsed. */
    val isAvailable: Boolean get() = synchronized(stateLock) { handle != 0L && !closeRequested }

    /**
     * Recognise every line in [bitmap], in reading order.
     *
     * Returns an empty list when there is no text, when the engine is unavailable, or when
     * the pass failed - all three are "no text here" to a caller, and the reason is in
     * logcat under `ModelRunner`. The caller's [bitmap] is not recycled.
     */
    fun recognize(bitmap: Bitmap): List<RecognizedLine> {
        if (!beginUse()) return emptyList()
        try {
            // Serialised: the reused pixel buffer and the native handle's own
            // scratch Vec are single-user, so two concurrent passes would
            // interleave pixels even without a close in between.
            synchronized(callLock) {
                // `HARDWARE` bitmaps cannot be read with getPixels, and a null config means the
                // bitmap was already recycled.
                val readable = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                    bitmap
                } else {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return emptyList()
                }
                try {
                    if (readable.isRecycled) return emptyList()
                    // Long arithmetic: width*height as Int wraps negative on a
                    // bitmap past 2^31 pixels and would size the array from the wrap.
                    val countLong = readable.width.toLong() * readable.height.toLong()
                    if (countLong > Int.MAX_VALUE) return emptyList()
                    val count = countLong.toInt()
                    val buffer = pixels?.takeIf { it.size == count } ?: IntArray(count).also { pixels = it }
                    try {
                        readable.getPixels(buffer, 0, readable.width, 0, 0, readable.width, readable.height)
                    } catch (expected: IllegalStateException) {
                        // Recycled between the check above and here by whoever
                        // owns the bitmap: that is "no text", not a crash.
                        return emptyList()
                    }
                    val packed = MlNative.recognizeText(handle, buffer, readable.width, readable.height)
                        ?: return emptyList()
                    return parse(packed)
                } finally {
                    if (readable !== bitmap) readable.recycle()
                }
            }
        } finally {
            endUse()
        }
    }

    /**
     * Recognise every line in an `RGBA_8888` camera plane, reading
     * [buffer] in place natively — no Bitmap, no `getPixels`, no pixel
     * array crossing JNI. The crop (right/bottom exclusive) and quarter-
     * turn [rotationDegrees] are applied natively; returned lines are in
     * upright cropped-frame coordinates, identical to recognising a
     * cropped-and-rotated Bitmap of the same plane.
     *
     * Returns null when this path cannot run — engine unavailable,
     * [buffer] not direct, a plane shape or rotation native refuses, or
     * the native pass failed — so the caller can fall back to [recognize]
     * with a Bitmap. An empty list (not null) means the pass ran and
     * found no text. [buffer] must stay valid for the whole call: keep
     * its `ImageProxy` open until this returns.
     */
    @Suppress("LongParameterList") // mirrors the JNI signature one-to-one
    fun recognizeBuffer(
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        rotationDegrees: Int,
    ): List<RecognizedLine>? {
        if (!buffer.isDirect || !beginUse()) return null
        try {
            synchronized(callLock) {
                val packed = MlNative.recognizeTextBuffer(
                    handle, buffer, width, height, rowStride, pixelStride,
                    cropLeft, cropTop, cropRight, cropBottom, rotationDegrees,
                ) ?: return null
                return parse(packed)
            }
        } finally {
            endUse()
        }
    }

    /**
     * Free both networks. Idempotent, and safe to call while a recognise pass
     * is running on another thread: the native destruction is deferred until
     * the last in-flight pass returns (see the lifetime guard above), so this
     * never frees the handle under a live native call.
     */
    override fun close() {
        val destroy = synchronized(stateLock) {
            closeRequested = true
            pixels = null
            if (inFlight == 0 && handle != 0L) {
                val live = handle
                handle = 0L
                live
            } else {
                0L
            }
        }
        if (destroy != 0L) MlNative.destroyOcr(destroy)
    }

    private companion object {
        private const val TAG = "TextRecognizer"
        const val DETECTION_ASSET = "ppocr_det.maml"
        const val RECOGNITION_ASSET = "ppocr_rec.maml"
        const val DICTIONARY_ASSET = "ppocr_keys.txt"

        /** Text, then eight quad coordinates, then confidence, then the vertical flag. */
        private const val FIELDS = 11

        /**
         * Unpack what `MlNative.recognizeText` returns.
         *
         * A malformed region is dropped rather than throwing: the native side and this
         * function have to agree about eleven fields, and losing one line is a better
         * failure than losing the image.
         */
        fun parse(packed: String): List<RecognizedLine> {
            if (packed.isEmpty()) return emptyList()
            return packed.split('\n').mapNotNull { row ->
                val fields = row.split('\t')
                if (fields.size != FIELDS) return@mapNotNull null
                val numbers = fields.drop(1).map { it.toFloatOrNull() ?: return@mapNotNull null }
                RecognizedLine(
                    text = fields[TEXT_INDEX],
                    confidence = numbers[CONFIDENCE_INDEX],
                    corners = List(CORNER_COUNT) {
                        Pair(numbers[it * COORDS_PER_CORNER], numbers[it * COORDS_PER_CORNER + 1])
                    },
                    vertical = numbers[VERTICAL_INDEX] != 0f,
                )
            }
        }

        private const val TEXT_INDEX = 0
        private const val CONFIDENCE_INDEX = 8
        private const val VERTICAL_INDEX = 9
        private const val CORNER_COUNT = 4
        private const val COORDS_PER_CORNER = 2
    }
}
