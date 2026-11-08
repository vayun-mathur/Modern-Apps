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
 * Not thread-safe, and holding a lock across the whole of [recognize] and [close] is
 * required rather than advisable: the native handle is freed by [close] and reading it
 * afterwards is a use-after-free. See `BokehAnalyzer`, whose comments record what that
 * looked like when it happened.
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
    val isAvailable: Boolean get() = handle != 0L

    /**
     * Recognise every line in [bitmap], in reading order.
     *
     * Returns an empty list when there is no text, when the engine is unavailable, or when
     * the pass failed - all three are "no text here" to a caller, and the reason is in
     * logcat under `ModelRunner`. The caller's [bitmap] is not recycled.
     */
    fun recognize(bitmap: Bitmap): List<RecognizedLine> {
        if (handle == 0L) return emptyList()
        // `HARDWARE` bitmaps cannot be read with getPixels, and a null config means the
        // bitmap was already recycled.
        val readable = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return emptyList()
        }
        try {
            val count = readable.width * readable.height
            val buffer = pixels?.takeIf { it.size == count } ?: IntArray(count).also { pixels = it }
            readable.getPixels(buffer, 0, readable.width, 0, 0, readable.width, readable.height)
            val packed = MlNative.recognizeText(handle, buffer, readable.width, readable.height)
                ?: return emptyList()
            return parse(packed)
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    /** Free both networks. Idempotent. */
    override fun close() {
        val live = handle
        handle = 0L
        pixels = null
        if (live != 0L) MlNative.destroyOcr(live)
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
