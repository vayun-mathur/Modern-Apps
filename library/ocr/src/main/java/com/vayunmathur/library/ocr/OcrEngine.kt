package com.vayunmathur.library.ocr
import android.content.Context
import android.graphics.Bitmap
import com.vayunmathur.library.ml.RecognizedLine
import com.vayunmathur.library.ml.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
/**
 * Shared on-device OCR engine using Baidu **PP-OCRv5 mobile**, running on the Vulkan
 * compute runtime in `:library:ml`.
 *
 * Detection is DBNet over a PP-HGNetV2 backbone; recognition is a PP-LCNetV3 backbone into a
 * two-block transformer with a CTC head. Both are converted ahead of time to `.maml` by
 * `scripts/ml/ppocr_fold.py` and shipped in this module's assets. The recogniser is the
 * **latin** model (836-char dict covering 47 Latin-script languages; no CJK) for a much
 * smaller footprint.
 *
 * # What replaced what
 *
 * This used to call into the `com.github.vayun-mathur:ncnn-android` AAR, where the whole
 * detect-crop-recognise pipeline lived inside `ppocrv5.cpp` and could not be read or tested.
 * All of it is now Rust in `:library:ml`: the DBNet post-processing in `post::dbnet`, the
 * rotated crop in `post::crop`, the CTC collapse in `post::ctc`, and the sequencing in
 * `post::ocr` - each host-tested against values computed by hand, and both models checked
 * numerically against onnxruntime by `scripts/ml/onnx_parity.py`.
 *
 * The engine still adapts the result to the [OcrResult]/[TextBox] shape consumers expect.
 * Reading order is decided natively now, so this no longer sorts.
 *
 * If Vulkan fp16 compute or an asset is unavailable the engine is inert (returns empty text,
 * never crashes) - call [isAvailable] to check up front.
 *
 * All heavy work runs off the main thread (Dispatchers.Default). Instances are safe to reuse
 * sequentially; concurrent calls are serialised internally.
 *
 * Usage:
 * ```
 * val ocr = OcrEngine(context)
 * val text = ocr.recognize(bitmap)   // suspend
 * ocr.close()                        // when done with a batch
 * ```
 */
class OcrEngine(private val context: Context) {
    /** A corner of a [TextBox] quad, in source-bitmap pixel coordinates. */
    data class Corner(val x: Float, val y: Float)
    /**
     * One recognised text region in source-bitmap pixel coordinates.
     *
     * [corners] are the four corners of the region's oriented quad in reading
     * order: corner 0 -> 1 runs along the text and corner 0 -> 3 spans its
     * height. [left]/[top]/[right]/[bottom] are the axis-aligned bounds of that
     * quad, so they stay meaningful for consumers that only need a rect.
     * [vertical] marks a region read as vertical script (stacked glyphs).
     */
    data class TextBox(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val corners: List<Corner> = listOf(
            Corner(left.toFloat(), top.toFloat()),
            Corner(right.toFloat(), top.toFloat()),
            Corner(right.toFloat(), bottom.toFloat()),
            Corner(left.toFloat(), bottom.toFloat()),
        ),
        val vertical: Boolean = false,
    ) {
        /** Centre of the quad. Bands reading order without skewing on tilt. */
        val centerY: Float get() = corners.sumOf { it.y.toDouble() }.toFloat() / corners.size
        val centerX: Float get() = corners.sumOf { it.x.toDouble() }.toFloat() / corners.size
    }
    /** Full result: the joined [text] plus the individual [boxes] it came from. */
    data class OcrResult(val text: String, val boxes: List<TextBox>)
    private val lock = Mutex()
    private var recognizer: TextRecognizer? = null
    private var initTried = false
    /** True if the runtime and the models are present and could be loaded. */
    suspend fun isAvailable(): Boolean = lock.withLock { ensureInit() }
    /**
     * Recognise all text in [bitmap] and return it as a single string (lines
     * joined by newlines), or an empty string if nothing is found or the engine
     * is unavailable. The caller's [bitmap] is not recycled.
     */
    suspend fun recognize(bitmap: Bitmap): String = recognizeDetailed(bitmap).text
    /** Like [recognize] but also returns the per-region [TextBox]es. */
    suspend fun recognizeDetailed(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        // The lock is held across the whole native call, not just the handle read: `close`
        // frees the Vulkan handle and reading it afterwards is a use-after-free.
        lock.withLock {
            if (!ensureInit()) return@withContext OcrResult("", emptyList())
            val engine = recognizer ?: return@withContext OcrResult("", emptyList())
            linesToResult(engine.recognize(bitmap))
        }
    }
    /** Release the models. */
    fun close() {
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        initTried = false
    }
    /** Create the engine once. */
    private fun ensureInit(): Boolean {
        recognizer?.let { return it.isAvailable }
        if (initTried) return false
        initTried = true
        // TextRecognizer documents "never throws", but a JNI linkage failure is an
        // Error, not an Exception, and escapes its catch - keep this engine's own
        // "inert, never crashes" guarantee here rather than depending on that.
        val created = try {
            TextRecognizer(context.applicationContext)
        } catch (_: Throwable) {
            return false
        }
        if (!created.isAvailable) {
            // Already logged natively under the `ModelRunner` tag, with the reason.
            created.close()
            return false
        }
        recognizer = created
        return true
    }
    internal companion object {
        /**
         * Native lines to the public result: blank lines dropped, each line
         * trimmed and mapped, degenerate (zero/negative-area) boxes dropped,
         * surviving text joined by newlines. Internal so the mapping is
         * host-testable without a Vulkan device.
         */
        fun linesToResult(lines: List<RecognizedLine>): OcrResult {
            val boxes = lines.asSequence()
                .filter { it.text.isNotBlank() }
                .map { toTextBox(it) }
                .filter { it.right > it.left && it.bottom > it.top }
                .toList()
            return OcrResult(boxes.joinToString("\n") { it.text }.trim(), boxes)
        }


        /**
         * A [RecognizedLine]'s quad and its axis-aligned bounds.
         *
         * The bounds are rounded outwards rather than truncated: a caller that draws the
         * rect should cover the glyphs rather than clip them, and `toInt()` on a float
         * pixel edge loses up to a pixel on each side.
         */
        fun toTextBox(line: RecognizedLine): OcrEngine.TextBox = OcrEngine.TextBox(
            text = line.text.trim(),
            left = kotlin.math.floor(line.left).toInt(),
            top = kotlin.math.floor(line.top).toInt(),
            right = kotlin.math.ceil(line.right).toInt(),
            bottom = kotlin.math.ceil(line.bottom).toInt(),
            corners = line.corners.map { OcrEngine.Corner(it.first, it.second) },
            vertical = line.vertical,
        )
    }
}
