package com.vayunmathur.photos.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.vayunmathur.library.log.Log
import androidx.core.graphics.createBitmap
import com.vayunmathur.library.ml.DetectedFaceBox
import com.vayunmathur.library.ml.FaceDetector
import com.vayunmathur.library.ml.FaceEmbedder
import com.vayunmathur.photos.domain.ALIGNED_FACE_SIZE
import com.vayunmathur.photos.domain.ARCFACE_TEMPLATE
import com.vayunmathur.photos.domain.FaceQuality
import com.vayunmathur.photos.domain.clusterFaces
import com.vayunmathur.photos.domain.similarityTransform
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * On-device face detection + recognition. Everything runs locally on `:library:ml`'s
 * Vulkan compute runtime — no ONNX Runtime, no Google Play Services, F-Droid clean.
 *
 * Pipeline:
 *  1. Detection — **SCRFD 500M** ([FaceDetector]) finds faces and returns a bounding box
 *     + 5 landmarks (eyes, nose, mouth corners). Letterboxing, anchor decode and NMS all
 *     run natively.
 *  2. Alignment — a least-squares similarity transform maps all five landmarks onto
 *     ArcFace's template ([similarityTransform]) in an [INPUT_SIZE]² crop. Faces whose
 *     landmarks give no transform are skipped rather than embedded unaligned.
 *  3. Embedding — **MobileFaceNet** ([FaceEmbedder], insightface `w600k_mbf`,
 *     ArcFace-trained) produces a 512-d embedding from the aligned crop. Pixels
 *     are normalised (px - 127.5)/127.5 natively. The result is L2-normalised here.
 *  4. Quality — [FaceQuality] decides whether the face may form person clusters.
 *     Grouping itself happens later, over all stored faces, in [clusterFaces].
 *
 * The models ship as `.maml` assets in this app. They are **GPU-only**: on a device
 * without Vulkan fp16 compute [modelsAvailable] is false and people clustering turns off,
 * rather than falling back to a second implementation.
 */
object FaceRecognizer {

    /** Square input side (px) the embedder expects. ArcFace/MobileFaceNet is 112. */
    const val INPUT_SIZE = ALIGNED_FACE_SIZE

    /**
     * Bump this whenever the embedder model (or preprocessing) changes. The face
     * worker compares it against a stored value and, on mismatch, clears existing
     * clusters and re-scans so photos are re-grouped with the new embeddings.
     * (v5 = BlazeFace + EdgeFace on ONNX Runtime; v6 = SCRFD detector +
     * MobileFaceNet embedder on ncnn; v7 = the same two models on `:library:ml`'s
     * Vulkan runtime — fp16 weights, fp32 accumulation and our own letterbox, so the
     * embeddings are close to v6's but not identical, and mixing the two spaces in one
     * index would cluster badly; v8 = five-point alignment instead of two eyes.)
     */
    const val EMBEDDER_VERSION = 8

    private const val TAG = "FaceRecognizer"

    /** Below this a face is not even embedded; [FaceQuality] applies the real size floor. */
    private const val MIN_FACE_SIZE_PX = 8

    /**
     * One detected face: its L2-normalised embedding, the detector's bounding box
     * normalised to 0..1 of the source image so it survives resizing, and whether it
     * passed [FaceQuality].
     */
    data class DetectedFace(
        val embedding: FloatArray,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val clusterable: Boolean,
    )

    @Volatile private var detector: FaceDetector? = null
    @Volatile private var embedder: FaceEmbedder? = null
    @Volatile private var initFailed = false

    /** True if the native models could be loaded and the device has fp16 compute. */
    fun modelsAvailable(context: Context): Boolean = ensureInit(context)

    private fun closeQuietly() {
        try { detector?.close() } catch (_: IllegalStateException) { /* already closed */ }
        try { embedder?.close() } catch (_: IllegalStateException) { /* already closed */ }
    }

    @Synchronized
    private fun ensureInit(context: Context): Boolean {
        if (detector != null && embedder != null) return true
        if (initFailed) return false
        return try {
            val app = context.applicationContext
            val newDetector = FaceDetector(app)
            val newEmbedder = FaceEmbedder(app)
            // GPU-only: a device without Vulkan fp16 compute reports unavailable rather
            // than throwing, so the constructors succeeding is not enough.
            if (!newDetector.isAvailable || !newEmbedder.isAvailable) {
                Log.status(TAG, "no Vulkan fp16 compute; face clustering is off")
                newDetector.close()
                newEmbedder.close()
                initFailed = true
                return false
            }
            detector = newDetector
            embedder = newEmbedder
            true
        } catch (e: IllegalStateException) {
            Log.error(TAG, "Face models unavailable", e)
            closeQuietly()
            detector = null
            embedder = null
            initFailed = true
            false
        } catch (e: IOException) {
            Log.error(TAG, "Face models unavailable", e)
            closeQuietly()
            detector = null
            embedder = null
            initFailed = true
            false
        }
    }

    /** Detect every face in [bitmap] and return one [DetectedFace] per face. */
    fun detectAndEmbed(context: Context, bitmap: Bitmap): List<DetectedFace> {
        if (!ensureInit(context)) return emptyList()
        val det = detector ?: return emptyList()
        val emb = embedder ?: return emptyList()

        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)

        val faces = try {
            det.detect(argb)
        } catch (e: IllegalStateException) {
            Log.error(TAG, "Face detection failed", e)
            if (argb != bitmap) argb.recycle()
            return emptyList()
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "Face detection failed", e)
            if (argb != bitmap) argb.recycle()
            return emptyList()
        }

        val out = ArrayList<DetectedFace>(faces.size)
        for (f in faces) {
            embedOneFace(emb, argb, f)?.let { out += it }
        }
        if (argb != bitmap) argb.recycle()
        return out
    }

    /** Embed a single detected face, or null when it should be skipped. */
    private fun embedOneFace(emb: FaceEmbedder, argb: Bitmap, f: DetectedFaceBox): DetectedFace? {
        val boxWidth = (f.right - f.left) * argb.width
        val boxHeight = (f.bottom - f.top) * argb.height
        if (boxWidth < MIN_FACE_SIZE_PX || boxHeight < MIN_FACE_SIZE_PX) return null
        val landmarks = landmarksPx(f, argb.width, argb.height)
        val aligned = alignFace(argb, landmarks) ?: return null
        try {
            // Null means the inference failed on this crop; the others may still be
            // fine, so skip this face rather than abandoning the photo.
            val raw = emb.embed(aligned) ?: run {
                Log.error(TAG, "Face embedding failed")
                return null
            }
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            aligned.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            val sharpness = FaceQuality.laplacianVariance(pixels, INPUT_SIZE, INPUT_SIZE)
            return DetectedFace(
                embedding = l2Normalize(raw),
                left = f.left,
                top = f.top,
                right = f.right,
                bottom = f.bottom,
                clusterable = FaceQuality.isClusterable(boxWidth, boxHeight, f.score, landmarks, sharpness),
            )
        } finally {
            aligned.recycle()
        }
    }

    /** The five landmarks in pixels, interleaved, in [ARCFACE_TEMPLATE] order. */
    private fun landmarksPx(f: DetectedFaceBox, w: Int, h: Int) = floatArrayOf(
        f.leftEyeX * w, f.leftEyeY * h,
        f.rightEyeX * w, f.rightEyeY * h,
        f.noseX * w, f.noseY * h,
        f.mouthLeftX * w, f.mouthLeftY * h,
        f.mouthRightX * w, f.mouthRightY * h,
    )

    /** Warp [src] so [landmarks] land on [ARCFACE_TEMPLATE]; null if they give no transform. */
    private fun alignFace(src: Bitmap, landmarks: FloatArray): Bitmap? {
        val t = similarityTransform(landmarks, ARCFACE_TEMPLATE) ?: return null
        val matrix = Matrix().apply { setValues(floatArrayOf(t[0], t[1], t[2], t[3], t[4], t[5], 0f, 0f, 1f)) }
        return try {
            val out = createBitmap(INPUT_SIZE, INPUT_SIZE)
            Canvas(out).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            out
        } catch (_: Exception) { null }
    }

    /** Cosine similarity of two embeddings (range roughly -1..1). */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0f || nb == 0f) return -1f
        return dot / (sqrt(na) * sqrt(nb))
    }

    /** Return an L2-normalised copy (unit length) so cosine == dot product. */
    fun l2Normalize(values: FloatArray): FloatArray {
        var norm = 0f
        for (v in values) norm += v * v
        norm = sqrt(norm)
        if (norm == 0f) return values
        return FloatArray(values.size) { values[it] / norm }
    }

    fun floatsToBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buffer.putFloat(v)
        return buffer.array()
    }

    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = buffer.float
        return out
    }
}
