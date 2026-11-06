package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap
import com.vayunmathur.library.log.Log

/**
 * Face detection and face embedding, for `:photos`'s people clustering.
 *
 * **SCRFD 500M** finds faces and their five keypoints; **MobileFaceNet** (`w600k_mbf`,
 * ArcFace-trained) turns an aligned crop into a 512-d embedding. Both run on Vulkan
 * compute with fp16 weights — see `photos/src/main/assets/README.md` for provenance and
 * the InsightFace licence, and `library/ml/src/main/rust/src/nets/{scrfd,mobilefacenet}.rs`
 * for the forward passes.
 *
 * These replace `com.vayunmathur.ncnn.FaceDetector` and `FaceEmbedder`. Same two models,
 * re-sourced from a licensed ONNX export and run on the GPU instead of ncnn's CPU
 * kernels. The embeddings are **not** bit-identical to ncnn's, so
 * `FaceRecognizer.EMBEDDER_VERSION` has to be bumped alongside this — the stored index
 * would otherwise mix two incompatible embedding spaces and cluster nonsense.
 *
 * GPU-only, like the segmenters: if [FaceDetector.isAvailable] is false the device has no
 * Vulkan fp16 compute and people clustering turns off, rather than falling back.
 */

/** One detected face. Every coordinate is a fraction of the source bitmap, `0..1`. */
data class DetectedFaceBox(
    /** Left edge. */
    val left: Float,
    /** Top edge. */
    val top: Float,
    /** Right edge. */
    val right: Float,
    /** Bottom edge. */
    val bottom: Float,
    /** Left eye, in the detector's keypoint order rather than the viewer's left. */
    val leftEyeX: Float,
    /** Left eye. */
    val leftEyeY: Float,
    /** Right eye. */
    val rightEyeX: Float,
    /** Right eye. */
    val rightEyeY: Float,
    /** Detector confidence, post-sigmoid. */
    val score: Float,
    /** Nose tip. */
    val noseX: Float,
    /** Nose tip. */
    val noseY: Float,
    /** Left mouth corner, in the detector's keypoint order. */
    val mouthLeftX: Float,
    /** Left mouth corner. */
    val mouthLeftY: Float,
    /** Right mouth corner. */
    val mouthRightX: Float,
    /** Right mouth corner. */
    val mouthRightY: Float,
)

/**
 * SCRFD 500M face detection.
 *
 * Not thread-safe: [detect] and [close] must not overlap. See the note in
 * [NativeSegmenter] about why the lock lives with the caller's threading rather than here.
 *
 * @param context used only to read the asset; not retained.
 * @param assetName the `.maml` in the app's assets.
 */
class FaceDetector(context: Context, assetName: String = DEFAULT_ASSET) : AutoCloseable {

    @Volatile private var handle: Long = if (!MlNative.isAvailable) {
        0L
    } else {
        try {
            MlNative.createScrfd(context.assets.open(assetName).use { it.readBytes() })
        } catch (expected: Exception) {
            Log.error(TAG, "cannot load $assetName", expected)
            0L
        }
    }

    /** Whether the detector came up. False means people clustering is off. */
    val isAvailable: Boolean get() = handle != 0L

    /** Reused across photos, so an indexing pass does not allocate one array per image. */
    private var pixels: IntArray? = null

    /**
     * Every face in [bitmap], or an empty list if there are none or the detector is
     * unavailable.
     *
     * [bitmap] may be any size: it is letterboxed and normalised natively.
     */
    fun detect(bitmap: Bitmap): List<DetectedFaceBox> {
        val live = handle
        if (live == 0L) return emptyList()
        // `getPixels` cannot read a HARDWARE bitmap, and a null config means an unknown
        // one; copying is the only option.
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE || bitmap.config == null) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        } ?: return emptyList()

        return try {
            val count = readable.width * readable.height
            var buffer = pixels
            if (buffer == null || buffer.size != count) {
                buffer = IntArray(count)
                pixels = buffer
            }
            readable.getPixels(buffer, 0, readable.width, 0, 0, readable.width, readable.height)
            val flat = MlNative.detectFaces(live, buffer, readable.width, readable.height)
                ?: return emptyList()
            unpack(flat)
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    /** Fifteen floats per face; see `MlNative.detectFaces` for the order. */
    private fun unpack(flat: FloatArray): List<DetectedFaceBox> {
        if (flat.size % FLOATS_PER_FACE != 0) {
            Log.error(TAG, "${flat.size} floats is not a whole number of faces")
            return emptyList()
        }
        val out = ArrayList<DetectedFaceBox>(flat.size / FLOATS_PER_FACE)
        var at = 0
        while (at + FLOATS_PER_FACE <= flat.size) {
            out += DetectedFaceBox(
                left = flat[at],
                top = flat[at + 1],
                right = flat[at + 2],
                bottom = flat[at + 3],
                leftEyeX = flat[at + 4],
                leftEyeY = flat[at + 5],
                rightEyeX = flat[at + 6],
                rightEyeY = flat[at + 7],
                score = flat[at + 8],
                noseX = flat[at + 9],
                noseY = flat[at + 10],
                mouthLeftX = flat[at + 11],
                mouthLeftY = flat[at + 12],
                mouthRightX = flat[at + 13],
                mouthRightY = flat[at + 14],
            )
            at += FLOATS_PER_FACE
        }
        return out
    }

    override fun close() {
        val toDestroy = handle
        handle = 0L
        pixels = null
        if (toDestroy != 0L) MlNative.destroy(toDestroy)
    }

    companion object {
        /** What `:photos` ships. */
        const val DEFAULT_ASSET: String = "scrfd_500m.maml"
        private const val TAG = "FaceDetector"
        private const val FLOATS_PER_FACE = 15
    }
}

/**
 * MobileFaceNet face embedding.
 *
 * Takes an aligned 112×112 crop and returns 512 floats. The result is **not**
 * L2-normalised — `FaceRecognizer` does that, as it did with ncnn.
 *
 * Not thread-safe: [embed] and [close] must not overlap.
 *
 * @param context used only to read the asset; not retained.
 * @param assetName the `.maml` in the app's assets.
 */
class FaceEmbedder(context: Context, assetName: String = DEFAULT_ASSET) : AutoCloseable {

    // The embedder is one input and one output, so it is exactly the segmenter's shape:
    // a 112x112 bitmap in, a 512x1x1 "mask" out. Reusing `NativeSegmenter` rather than
    // adding a second JNI path for the same three calls.
    private val native =
        NativeSegmenter(context, assetName, TAG, MlNative::createMobilefacenet)

    /** Whether the embedder came up. */
    val isAvailable: Boolean get() = native.isAvailable

    /**
     * The 512-d embedding of an aligned [bitmap], or null on failure.
     *
     * [bitmap] should already be the canonical 112×112 crop; anything else is resized
     * natively, which for a face crop is not what the caller wanted.
     */
    fun embed(bitmap: Bitmap): FloatArray? {
        val result = native.segment(bitmap) ?: return null
        if (result.mask.size != EMBEDDING_LENGTH) {
            Log.error(TAG, "a ${result.mask.size}-value embedding, expected $EMBEDDING_LENGTH")
            return null
        }
        return result.mask
    }

    override fun close() = native.close()

    companion object {
        /** What `:photos` ships. */
        const val DEFAULT_ASSET: String = "w600k_mbf.maml"
        /** Length of the embedding MobileFaceNet produces. */
        const val EMBEDDING_LENGTH = 512
        private const val TAG = "FaceEmbedder"
    }
}
